#include "nostr.h"
#include "schnorr.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QRandomGenerator>
#include <QSettings>
#include <QTimer>
#include <QUrl>
#include <QWebSocket>
#include <cstdio>

namespace
{
constexpr int kMaxSeen = 2000;
constexpr int kMaxOutbox = 200;
constexpr int kAuthKind = 22242;
// A sending socket closes this long after the last thing given to it.
constexpr int kSendMs = 20000;

QString compact(const QJsonArray &a)
{
    return QString::fromUtf8(QJsonDocument(a).toJson(QJsonDocument::Compact));
}

qint64 now()
{
    return QDateTime::currentSecsSinceEpoch();
}

// NIP-01 string escaping: only these seven characters are escaped, everything else goes in as
// plain UTF-8. A generic JSON writer (Qt's, or org.json with its "\/") would give a different id.
void appendString(QByteArray &out, const QString &s)
{
    out += '"';
    const QByteArray utf8 = s.toUtf8();
    for (const char ch : utf8) {
        switch (ch) {
        case '\n': out += "\\n"; break;
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        default: out += ch;
        }
    }
    out += '"';
}

void appendValue(QByteArray &out, const QJsonValue &v)
{
    switch (v.type()) {
    case QJsonValue::Bool:
        out += v.toBool() ? "true" : "false";
        break;
    case QJsonValue::Double: {
        // Whole numbers as plain integers (created_at, kind); anything else is never in a valid tag.
        const qint64 i = v.toInteger();
        if (double(i) == v.toDouble())
            out += QByteArray::number(i);
        else
            out += QByteArray::number(v.toDouble(), 'g', 17);
        break;
    }
    case QJsonValue::String:
        appendString(out, v.toString());
        break;
    case QJsonValue::Array: {
        out += '[';
        const QJsonArray a = v.toArray();
        for (qsizetype i = 0; i < a.size(); ++i) {
            if (i)
                out += ',';
            appendValue(out, a.at(i));
        }
        out += ']';
        break;
    }
    case QJsonValue::Object: {
        out += '{';
        const QJsonObject o = v.toObject();
        bool first = true;
        for (auto it = o.begin(); it != o.end(); ++it) {
            if (!first)
                out += ',';
            first = false;
            appendString(out, it.key());
            out += ':';
            appendValue(out, it.value());
        }
        out += '}';
        break;
    }
    default:
        out += "null";
    }
}
}

QByteArray Nostr::serialize(const QString &pubkey, qint64 createdAt, int kind, const QJsonArray &tags, const QString &content)
{
    QByteArray out = "[0,";
    appendString(out, pubkey);
    out += ',' + QByteArray::number(createdAt) + ',' + QByteArray::number(kind) + ',';
    appendValue(out, tags);
    out += ',';
    appendString(out, content);
    out += ']';
    return out;
}

QString Nostr::idOf(const QJsonObject &ev)
{
    const QByteArray s = serialize(ev.value(QStringLiteral("pubkey")).toString(), ev.value(QStringLiteral("created_at")).toInteger(),
                                   ev.value(QStringLiteral("kind")).toInt(), ev.value(QStringLiteral("tags")).toArray(),
                                   ev.value(QStringLiteral("content")).toString());
    return Schnorr::hex(QCryptographicHash::hash(s, QCryptographicHash::Sha256));
}

QJsonObject Nostr::event(int kind, const QJsonArray &tags, const QString &content, const QByteArray &secret, qint64 createdAt, const QByteArray &aux)
{
    if (createdAt < 0)
        createdAt = now();
    const QString pub = Schnorr::hex(Schnorr::publicKey(secret));
    const QByteArray id = QCryptographicHash::hash(serialize(pub, createdAt, kind, tags, content), QCryptographicHash::Sha256);
    const QByteArray sig = Schnorr::sign(id, secret, aux);
    return QJsonObject{{QStringLiteral("id"), Schnorr::hex(id)},
                       {QStringLiteral("pubkey"), pub},
                       {QStringLiteral("created_at"), createdAt},
                       {QStringLiteral("kind"), kind},
                       {QStringLiteral("tags"), tags},
                       {QStringLiteral("content"), content},
                       {QStringLiteral("sig"), Schnorr::hex(sig)}};
}

QStringList Nostr::defaultRelays()
{
    return {QStringLiteral("wss://relay.damus.io"), QStringLiteral("wss://nos.lol"), QStringLiteral("wss://relay.primal.net"),
            QStringLiteral("wss://nostr.mom"), QStringLiteral("wss://relay.nostr.band")};
}

Nostr::Nostr(std::function<QByteArray()> secret, QObject *parent)
    : QObject(parent)
    , m_secret(std::move(secret))
{
    m_subId = QStringLiteral("huginn-") + QString::number(QRandomGenerator::global()->generate(), 16).rightJustified(8, QLatin1Char('0'));
    m_ringSubId = QStringLiteral("huginn-r") + QString::number(QRandomGenerator::global()->generate(), 16).rightJustified(8, QLatin1Char('0'));
}

void Nostr::restoreSeen(const QByteArray &stored)
{
    QStringList all = QJsonDocument::fromJson(stored).object().value(QStringLiteral("seen")).toVariant().toStringList();
    QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    const QStringList plain = s.value(QStringLiteral("nostr/seen")).toString().split(QLatin1Char(','), Qt::SkipEmptyParts);
    QSet<QString> have(all.begin(), all.end());
    // Then what plain settings had, then what came while the vault was locked.
    const QStringList mem = m_seen;
    for (const QString &id : plain + mem)
        if (!have.contains(id)) {
            have.insert(id);
            all << id;
        }
    while (all.size() > kMaxSeen)
        have.remove(all.takeFirst());
    m_seen = all;
    m_seenSet = have;
    // The plain list goes only once the vault really has it.
    if (!plain.isEmpty() || s.contains(QStringLiteral("nostr/seen"))) {
        if (keepSeen && keepSeen(QJsonDocument(QJsonObject{{QStringLiteral("seen"), QJsonArray::fromStringList(m_seen)}}).toJson(QJsonDocument::Compact)))
            s.remove(QStringLiteral("nostr/seen"));
    }
}

Nostr::~Nostr()
{
    // Children are deleted after this; make sure none of them calls back into a half-gone object.
    for (const Link &l : std::as_const(m_links)) {
        if (QWebSocket *ws = l.ws.data()) {
            QObject::disconnect(ws, nullptr, this, nullptr);
            ws->abort();
        }
    }
    for (const Sender &l : std::as_const(m_senders)) {
        if (QWebSocket *ws = l.ws.data()) {
            QObject::disconnect(ws, nullptr, this, nullptr);
            ws->abort();
        }
    }
}

QByteArray Nostr::secret() const
{
    QByteArray b = m_secret ? m_secret() : QByteArray();
    if (b.size() == 64)
        b = QByteArray::fromHex(b);
    return b.size() == 32 ? b : QByteArray();
}

QString Nostr::publicKey() const
{
    if (!m_pub.isEmpty())
        return m_pub;
    QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    m_pub = s.value(QStringLiteral("nostr/pub")).toString();
    if (m_pub.isEmpty()) {
        const QByteArray sk = secret();
        if (sk.isEmpty())
            return QString();
        m_pub = Schnorr::hex(Schnorr::publicKey(sk));
        if (m_pub.isEmpty())
            return QString();
        s.setValue(QStringLiteral("nostr/pub"), m_pub);
        // A getter can't emit directly; tell bindings once the call has returned.
        QMetaObject::invokeMethod(const_cast<Nostr *>(this), &Nostr::publicKeyChanged, Qt::QueuedConnection);
    }
    return m_pub;
}

QStringList Nostr::relays() const
{
    const QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    QStringList list;
    const QStringList lines = s.value(QStringLiteral("nostr/relays")).toString().split(QLatin1Char('\n'));
    for (const QString &l : lines) {
        if (l.trimmed().startsWith(QLatin1String("wss://")))
            list << l.trimmed();
    }
    return list.isEmpty() ? defaultRelays() : list;
}

void Nostr::setKeepAlive(bool on)
{
    if (m_keepAlive == on)
        return;
    m_keepAlive = on;
    Q_EMIT keepAliveChanged();
}

// ------------------------------------------------------------------ relays

void Nostr::connectRelays()
{
    for (const QString &url : relays())
        open(url);
}

void Nostr::open(const QString &url)
{
    // One socket per relay, never two: only opens if there isn't one already.
    if (m_links.contains(url))
        return;
    auto *ws = new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this);
    m_links.insert(url, Link{ws, true});
    connect(ws, &QWebSocket::connected, this, [this, url, ws] { opened(url, ws); });
    connect(ws, &QWebSocket::disconnected, this, [this, url, ws] { gone(url, ws); });
    connect(ws, &QWebSocket::errorOccurred, this, [this, url, ws](QAbstractSocket::SocketError) { gone(url, ws); });
    connect(ws, &QWebSocket::textMessageReceived, this, [this, url, ws](const QString &text) { onMessage(url, ws, text); });
    connect(ws, &QWebSocket::pong, this, [this, url, ws] {
        auto it = m_links.find(url);
        if (it != m_links.end() && it->ws == ws)
            it->ponged = true;
    });
    // A relay that never answers counts as down after 10 s, so the retry can start.
    QTimer::singleShot(10000, ws, [this, url, ws] {
        if (ws->state() != QAbstractSocket::ConnectedState)
            gone(url, ws);
    });
    ws->open(QUrl(url));
}

void Nostr::opened(const QString &url, QWebSocket *ws)
{
    if (m_links.value(url).ws != ws) {   // replaced meanwhile
        drop(ws);
        return;
    }
    // Only reset the back-off once it has stayed up a minute (a relay that accepts and drops at once).
    QTimer::singleShot(60000, ws, [this, url, ws] {
        if (m_links.value(url).ws == ws && m_live.contains(url))
            m_retries.remove(url);
    });
    // Ping every 30 s; no pong by the next ping means the connection died quietly.
    auto *ping = new QTimer(ws);
    ping->setInterval(30000);
    connect(ping, &QTimer::timeout, this, [this, url, ws] {
        auto it = m_links.find(url);
        if (it == m_links.end() || it->ws != ws)
            return;
        if (!it->ponged) {
            gone(url, ws);
            return;
        }
        it->ponged = false;
        ws->ping();
    });
    ping->start();
    setLive(url, true);
    subscribe(ws);
    // This relay is reachable again: what still waits goes to it on a sending socket, never on this one.
    if (!m_outbox.isEmpty())
        send(url, m_outbox);
}

// Only the current socket for this relay counts; an old one closing changes nothing.
void Nostr::gone(const QString &url, QWebSocket *ws)
{
    auto it = m_links.find(url);
    if (it == m_links.end() || it->ws != ws)
        return;
    m_links.erase(it);
    drop(ws);
    setLive(url, false);
    if (!m_keepAlive)
        return;
    // Try again: 2 s, 4 s, 8 s ... up to a minute between tries.
    const int n = m_retries.value(url) + 1;
    m_retries.insert(url, n);
    QTimer::singleShot(qMin(60, 1 << qMin(n, 6)) * 1000, this, [this, url] {
        if (m_keepAlive)
            open(url);
    });
}

void Nostr::drop(QWebSocket *ws)
{
    if (!ws)
        return;
    QObject::disconnect(ws, nullptr, this, nullptr);
    ws->abort();
    ws->deleteLater();
}

void Nostr::setLive(const QString &url, bool live)
{
    const bool changed = live ? !m_live.contains(url) : m_live.contains(url);
    if (live)
        m_live.insert(url);
    else
        m_live.remove(url);
    if (changed)
        Q_EMIT connectedChanged();
}

void Nostr::reconnectNow()
{
    if (!m_keepAlive && m_links.isEmpty())
        return;
    for (const QString &url : relays()) {
        if (m_live.contains(url))
            continue;
        drop(m_links.take(url).ws.data());
        m_retries.remove(url);
        open(url);
    }
}

void Nostr::disconnect()
{
    setKeepAlive(false);
    const QHash<QString, Link> links = m_links;
    m_links.clear();
    for (const Link &l : links) {
        QWebSocket *ws = l.ws.data();
        if (!ws)
            continue;
        // Close politely, but don't wait on a relay that never answers.
        QObject::disconnect(ws, nullptr, this, nullptr);
        connect(ws, &QWebSocket::disconnected, ws, &QObject::deleteLater);
        QTimer::singleShot(5000, ws, &QObject::deleteLater);
        ws->close();
    }
    if (!m_live.isEmpty()) {
        m_live.clear();
        Q_EMIT connectedChanged();
    }
}

void Nostr::resubscribe()
{
    for (auto it = m_links.begin(); it != m_links.end(); ++it)
        if (m_live.contains(it.key()) && it->ws) {
            // A relay that asked for a sign-in while locked gets one now, as the mailbox the vault holds the key of.
            const QString challenge = m_challenges.value(it.key());
            if (!challenge.isEmpty())
                signIn(it.key(), it->ws.data(), challenge);
            subscribe(it->ws.data());
        }
}

void Nostr::signIn(const QString &url, QWebSocket *ws, const QString &challenge)
{
    const QJsonArray tags{QJsonValue(QJsonArray{QStringLiteral("relay"), url}), QJsonValue(QJsonArray{QStringLiteral("challenge"), challenge})};
    const QByteArray sk = secret();
    if (!sk.isEmpty())
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("AUTH"), event(kAuthKind, tags, QString(), sk)}));
    for (const QByteArray &rs : ringSecrets ? ringSecrets() : QList<QByteArray>())
        if (rs.size() == 32)
            ws->sendTextMessage(compact(QJsonArray{QStringLiteral("AUTH"), event(kAuthKind, tags, QString(), rs)}));
}

void Nostr::subscribe(QWebSocket *ws)
{
    // Events can be backdated a little (see publish), so look back two days and skip ones already seen.
    const QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    const qint64 since = qMax<qint64>(0, s.value(QStringLiteral("nostr/since"), 0).toLongLong() - 2 * 86400);
    const QString pub = publicKey();
    if (!pub.isEmpty()) {
        const QJsonObject filter{{QStringLiteral("kinds"), QJsonArray{Kind}},
                                 {QStringLiteral("#p"), QJsonArray{pub}},
                                 {QStringLiteral("since"), since}};
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("REQ"), m_subId, filter}));
    }
    // Cap b1: the ring mailbox, on a request of its own (a relay may answer one and not the other).
    const QStringList rings = ringMailboxes ? ringMailboxes() : QStringList();
    if (!rings.isEmpty()) {
        const QJsonObject filter{{QStringLiteral("kinds"), QJsonArray{Kind}},
                                 {QStringLiteral("#p"), QJsonArray::fromStringList(rings)},
                                 {QStringLiteral("since"), qMax<qint64>(0, now() - 2 * 86400)}};
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("REQ"), m_ringSubId, filter}));
    }
}

void Nostr::onMessage(const QString &url, QWebSocket *ws, const QString &text)
{
    const QJsonDocument doc = QJsonDocument::fromJson(text.toUtf8());
    if (!doc.isArray())
        return;
    const QJsonArray a = doc.array();
    const QString type = a.at(0).toString();
    if (type == QLatin1String("EVENT")) {
        if (a.at(2).isObject())
            handle(a.at(2).toObject());
    } else if (type == QLatin1String("AUTH")) {
        // Some relays only hand out messages to the mailbox's owner: prove it (NIP-42).
        m_challenges.insert(url, a.at(1).toString());
        if (secret().isEmpty() && !(ringSecrets && !ringSecrets().isEmpty()))
            return;
        signIn(url, ws, a.at(1).toString());
        subscribe(ws);
    } else if (type == QLatin1String("OK")) {
        const QString id = a.at(1).toString();
        if (a.at(2).toBool() && !id.isEmpty())
            m_outbox.removeIf([&id](const QString &msg) { return msg.contains(id); });
    }
}

void Nostr::handle(const QJsonObject &ev)
{
    const QString id = ev.value(QStringLiteral("id")).toString();
    // The same event comes from every relay at once: only one copy is handled at a time.
    if (id.isEmpty() || m_seenSet.contains(id) || m_working.contains(id))
        return;
    // Cap b1: an event for the ring mailbox is only ever a ring envelope. Tried with the ring key, then dropped.
    const QStringList rings = ringMailboxes ? ringMailboxes() : QStringList();
    if (!rings.isEmpty()) {
        QString p;
        for (const QJsonValue &t : ev.value(QStringLiteral("tags")).toArray())
            if (t.toArray().at(0).toString() == QLatin1String("p")) {
                p = t.toArray().at(1).toString();
                break;
            }
        if (!p.isEmpty() && rings.contains(p)) {
            const bool good = idOf(ev) == id
                && Schnorr::verify(Schnorr::unhex(id), Schnorr::unhex(ev.value(QStringLiteral("pubkey")).toString()),
                                   Schnorr::unhex(ev.value(QStringLiteral("sig")).toString()));
            m_seen << id;
            m_seenSet.insert(id);
            while (m_seen.size() > kMaxSeen)
                m_seenSet.remove(m_seen.takeFirst());
            if (good && onRing)
                onRing(ev.value(QStringLiteral("content")).toString());
            return;
        }
    }
    // While locked, say "New message" once per event, not once per relay.
    if (secret().isEmpty()) {
        if (m_announced.contains(id))
            return;
        m_announced.insert(id);
    }
    m_working.insert(id);
    // Only trust what the event's own signature covers.
    const bool valid = idOf(ev) == id
        && Schnorr::verify(Schnorr::unhex(id), Schnorr::unhex(ev.value(QStringLiteral("pubkey")).toString()),
                           Schnorr::unhex(ev.value(QStringLiteral("sig")).toString()));
    const bool taken = valid && onEnvelope && onEnvelope(ev.value(QStringLiteral("content")).toString());
    m_working.remove(id);
    if (!taken)
        return;
    m_seen << id;
    m_seenSet.insert(id);
    while (m_seen.size() > kMaxSeen)
        m_seenSet.remove(m_seen.takeFirst());
    if (keepSeen)
        keepSeen(QJsonDocument(QJsonObject{{QStringLiteral("seen"), QJsonArray::fromStringList(m_seen)}}).toJson(QJsonDocument::Compact));
    QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    s.setValue(QStringLiteral("nostr/since"), now());
}

// ------------------------------------------------------------------ sending

void Nostr::send(const QString &url, const QStringList &msgs)
{
    auto it = m_senders.find(url);
    if (it == m_senders.end() || !it->ws) {
        auto *ws = new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this);
        auto *closer = new QTimer(ws);
        closer->setSingleShot(true);
        closer->setInterval(kSendMs);
        connect(closer, &QTimer::timeout, this, [this, url, ws] { dropSender(url, ws); });
        connect(ws, &QWebSocket::connected, this, [this, url, ws] {
            auto s = m_senders.find(url);
            if (s == m_senders.end() || s->ws != ws) {
                ws->close();
                return;
            }
            s->open = true;
            for (const QString &m : std::as_const(s->queue))
                ws->sendTextMessage(m);
            s->queue.clear();
        });
        connect(ws, &QWebSocket::disconnected, this, [this, url, ws] { dropSender(url, ws); });
        connect(ws, &QWebSocket::errorOccurred, this, [this, url, ws](QAbstractSocket::SocketError) { dropSender(url, ws); });
        // Only "OK" matters here. "AUTH" is never answered: signing in would name this computer's mailbox.
        connect(ws, &QWebSocket::textMessageReceived, this, [this](const QString &text) {
            const QJsonArray a = QJsonDocument::fromJson(text.toUtf8()).array();
            const QString id = a.at(1).toString();
            if (a.at(0).toString() == QLatin1String("OK") && a.at(2).toBool() && !id.isEmpty())
                m_outbox.removeIf([&id](const QString &msg) { return msg.contains(id); });
        });
        it = m_senders.insert(url, Sender{ws, {}, false, closer});
        ws->open(QUrl(url));
    }
    if (it->closer)
        it->closer->start();   // starts the 20 s again
    if (it->open && it->ws) {
        for (const QString &m : msgs)
            it->ws->sendTextMessage(m);
    } else {
        it->queue << msgs;
    }
}

void Nostr::warmSenders()
{
    for (const QString &url : relays())
        send(url, {});
}

void Nostr::dropSender(const QString &url, QWebSocket *ws)
{
    auto it = m_senders.find(url);
    if (it != m_senders.end() && it->ws == ws)
        m_senders.erase(it);
    if (!ws)
        return;
    QObject::disconnect(ws, nullptr, this, nullptr);
    ws->close();
    ws->deleteLater();
}

int Nostr::publish(const QString &mailboxHex, const QString &content, int days)
{
    // QJsonValue(...) keeps the inner array nested: MSVC treats QJsonArray{QJsonArray{...}} as a copy.
    QJsonArray tags{QJsonValue(QJsonArray{QStringLiteral("p"), mailboxHex})};
    // A new throwaway key for every message, and a slightly blurred time, so relays can't link messages to a sender.
    const qint64 createdAt = now() - QRandomGenerator::global()->bounded(int(kBlurS));
    // NIP-40: relays may drop it once the longest message limit has passed (it's gone from both sides by then). Taken from
    // the blurred time and rounded up to a whole day, so it shows neither the sending time nor the chat's limit.
    if (days > 0)
        tags.append(QJsonValue(QJsonArray{QStringLiteral("expiration"), QString::number(expiration(createdAt))}));
    const QJsonObject ev = event(Kind, tags, content, Schnorr::newSecret(), createdAt);
    const QString msg = compact(QJsonArray{QStringLiteral("EVENT"), ev});
    m_outbox << msg;
    while (m_outbox.size() > kMaxOutbox)
        m_outbox.removeFirst();
    // Every relay gets it, each on a sending socket of its own that never asks for the inbox or signs in.
    for (const QString &url : relays())
        send(url, {msg});
    if (m_links.isEmpty())
        connectRelays();
    return int(m_live.size());
}

qint64 Nostr::expiration(qint64 createdAt)
{
    const qint64 at = createdAt + kBlurS + qint64(kLongestDays) * 86400;
    return (at + 86399) / 86400 * 86400;
}

// ------------------------------------------------------------------ self-test

int Nostr::padBucket(int n)
{
    for (int b : {1024, 4096, 16384, 32768})
        if (n <= b)
            return b;
    return (n + 16383) / 16384 * 16384;
}

QByteArray Nostr::pad(const QByteArray &json)
{
    QByteArray body = json.trimmed();
    if (!body.endsWith('}'))
        return json;
    const int base = int(body.size()) + 7;   // ,"z":"" adds 7 characters
    const int target = padBucket((base + 2) / 3 * 4) / 4 * 3;
    body.chop(1);
    return body + ",\"z\":\"" + QByteArray(target - base, '0') + "\"}";
}

bool Nostr::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        fprintf(stderr, "nostr self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    auto s = [](const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); };
    auto b = [&](const QJsonObject &o, const char *k) { return Schnorr::unhex(s(o, k)); };

    // BIP-340's official test vector 0.
    const QJsonObject o = v.value(QStringLiteral("official")).toObject();
    const bool officialKey = Schnorr::hex(Schnorr::publicKey(b(o, "seckey"))) == s(o, "pubkey");
    const QByteArray osig = Schnorr::sign(b(o, "msg"), b(o, "seckey"), b(o, "aux"));
    const bool officialSig = Schnorr::hex(osig) == s(o, "sig") && Schnorr::verify(b(o, "msg"), b(o, "pubkey"), osig);

    // An event made by the reference code, the same one Muninn's tests check.
    const QJsonArray tags{QJsonValue(QJsonArray{QStringLiteral("p"), s(v, "recipient")})};
    const qint64 createdAt = v.value(QStringLiteral("created_at")).toInteger();
    const int kind = v.value(QStringLiteral("kind")).toInt();
    const bool serialized = serialize(s(v, "pubkey"), createdAt, kind, tags, s(v, "content")) == s(v, "serialized").toUtf8();
    const QJsonObject ev = event(kind, tags, s(v, "content"), b(v, "seckey"), createdAt, b(v, "aux"));
    const bool eventOk = s(ev, "pubkey") == s(v, "pubkey") && s(ev, "id") == s(v, "id") && s(ev, "sig") == s(v, "sig") && idOf(ev) == s(v, "id");
    // The event still checks out after a trip through JSON text, as a relay hands it back.
    const QJsonObject back = QJsonDocument::fromJson(QJsonDocument(ev).toJson(QJsonDocument::Compact)).object();
    const bool trip = idOf(back) == s(v, "id")
        && Schnorr::verify(b(back, "id"), b(back, "pubkey"), b(back, "sig"));

    // NIP-01 escaping: seven escapes, "/" and non-ASCII left as they are.
    const QString tricky = QString::fromUtf8("q\"b\\n\nr\rt\tb\bf\f a/b caf\xc3\xa9");
    const QByteArray expected = "[0,\"ab\",1,1,[[\"t\",\"a/b\"]],\"q\\\"b\\\\n\\nr\\rt\\tb\\bf\\f a/b caf\xc3\xa9\"]";
    const bool escaping = serialize(QStringLiteral("ab"), 1, 1, QJsonArray{QJsonValue(QJsonArray{QStringLiteral("t"), QStringLiteral("a/b")})}, tricky) == expected;

    // A changed signature must fail.
    const QByteArray sk = Schnorr::newSecret();
    const QByteArray msg(32, 1);
    QByteArray sig = Schnorr::sign(msg, sk);
    const bool good = Schnorr::verify(msg, Schnorr::publicKey(sk), sig);
    sig[5] = char(sig[5] ^ 1);
    const bool forged = good && !Schnorr::verify(msg, Schnorr::publicKey(sk), sig);

    // Envelope padding (shared/vectors/pad-vectors.json, next to the Nostr vectors; section 20).
    bool padded = false;
    QFile pf(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("pad-vectors.json")));
    if (pf.open(QIODevice::ReadOnly)) {
        const QJsonObject pv = QJsonDocument::fromJson(pf.readAll()).object();
        padded = !pv.value(QStringLiteral("pad")).toArray().isEmpty();
        for (const QJsonValue &c : pv.value(QStringLiteral("bucket")).toArray())
            padded = padded && padBucket(c[QStringLiteral("n")].toInt()) == c[QStringLiteral("bucket")].toInt();
        for (const QJsonValue &c : pv.value(QStringLiteral("pad")).toArray()) {
            const int d = c[QStringLiteral("d")].toInt();
            const QByteArray js = "{\"e\":\"" + QByteArray(60, 'A') + "\",\"n\":\"" + QByteArray(16, 'B') + "\",\"d\":\"" + QByteArray(d, 'C') + "\"}";
            const QByteArray out = pad(js);
            const QJsonObject o = QJsonDocument::fromJson(out).object();
            padded = padded && out.toBase64().size() == c[QStringLiteral("content")].toInt()
                && o.value(QStringLiteral("z")).toString().size() == c[QStringLiteral("zeros")].toInt()
                && o.value(QStringLiteral("d")).toString().size() == d && o.size() == 4;
        }
    }
    // The NIP-40 expiration tag (section 20.3), in the same file.
    bool expiry = pf.isOpen();
    if (expiry) {
        pf.seek(0);
        const QJsonObject pv = QJsonDocument::fromJson(pf.readAll()).object();
        const QJsonArray ex = pv.value(QStringLiteral("expiry")).toArray();
        expiry = !ex.isEmpty() && pv.value(QStringLiteral("blur")).toInteger() == kBlurS
            && pv.value(QStringLiteral("longestDays")).toInt() == kLongestDays;
        for (const QJsonValue &c : ex) {
            const qint64 e = expiration(c[QStringLiteral("created")].toInteger());
            expiry = expiry && e == c[QStringLiteral("expiration")].toInteger() && e % 86400 == 0;
        }
    }

    printf("nostr self-test: BIP-340 key %s, BIP-340 signature %s, serialization %s, event id and signature %s, "
           "survives JSON round trip %s, NIP-01 escaping %s, rejects forged signature %s, envelope padding %s, expiration %s\n",
           officialKey ? "ok" : "WRONG", officialSig ? "ok" : "WRONG", serialized ? "ok" : "WRONG", eventOk ? "ok" : "WRONG",
           trip ? "ok" : "WRONG", escaping ? "ok" : "WRONG", forged ? "ok" : "WRONG", padded ? "ok" : "WRONG", expiry ? "ok" : "WRONG");
    return officialKey && officialSig && serialized && eventOk && trip && escaping && forged && padded && expiry;
}
