#include "nostr.h"
#include "busy.h"
#include "settings.h"
#include "schnorr.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QHostAddress>
#include <QTcpServer>
#include <QThread>
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
#include <atomic>
#include <cstdio>

namespace
{
constexpr int kMaxSeen = 20000;
constexpr int kMaxRejected = 5000;
constexpr int kAuthKind = 22242;
// A sending socket closes this long after the last thing given to it.
constexpr int kSendMs = 180000;
// The handled events' list is written at most this often.
constexpr int kSeenSaveMs = 30000;
// The relay thread hands its events over at most this often (about 30 times a second).
constexpr int kBatchMs = 33;

// The event id in a relay's ["EVENT", sub, {...}] without reading the whole message: 32 bytes, or empty when it isn't
// written the usual way (then the message is read in full). A quote can't stand unescaped inside a JSON string, so
// "id":" only ever starts the event's own id.
QByteArray quickId(const QString &text)
{
    if (!text.startsWith(QLatin1String("[\"EVENT\"")))
        return {};
    const qsizetype at = text.indexOf(QLatin1String("\"id\":\""));
    if (at < 0 || at + 6 + 64 > text.size())
        return {};
    const QString hex = text.mid(at + 6, 64);
    for (const QChar c : hex)
        if (!((c >= QLatin1Char('0') && c <= QLatin1Char('9')) || (c >= QLatin1Char('a') && c <= QLatin1Char('f'))))
            return {};
    if (at + 6 + 64 >= text.size() || text.at(at + 6 + 64) != QLatin1Char('"'))
        return {};
    return QByteArray::fromHex(hex.toLatin1());
}

// The subscription an ["EVENT", sub, ...] came on, and the event's created_at, read the same quick way.
QString quickSub(const QString &text)
{
    const qsizetype start = text.indexOf(QLatin1Char('"'), 8);
    const qsizetype end = start < 0 ? -1 : text.indexOf(QLatin1Char('"'), start + 1);
    return end > start + 1 && end - start < 80 ? text.mid(start + 1, end - start - 1) : QString();
}

qint64 quickCreatedAt(const QString &text)
{
    const qsizetype at = text.indexOf(QLatin1String("\"created_at\":"));
    if (at < 0)
        return 0;
    qint64 v = 0;
    int digits = 0;
    for (qsizetype i = at + 13; i < text.size() && digits < 12; ++i) {
        const QChar c = text.at(i);
        if (c == QLatin1Char(' ') && digits == 0)
            continue;
        if (c < QLatin1Char('0') || c > QLatin1Char('9'))
            break;
        v = v * 10 + (c.unicode() - '0');
        ++digits;
    }
    return digits > 0 ? v : 0;
}

QString hostOf(const QString &url) { return QUrl(url).host(); }

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

// ------------------------------------------------------------------ the relay thread

// Owns every relay socket and all the reading, checking and opening; lives on Nostr's thread ("huginn-relays").
// Reached only through Nostr::post() (queued calls) and answers only through queued calls to Nostr: no QObject of
// either side is ever touched from the other thread.
class NostrWorker : public QObject
{
public:
    NostrWorker(Nostr *owner, const QHash<QString, qint64> &eose, qint64 since)
        : m_owner(owner), m_eoseAt(eose), m_since(since)
    {
        m_subId = QStringLiteral("huginn-") + QString::number(QRandomGenerator::global()->generate(), 16).rightJustified(8, QLatin1Char('0'));
        m_ringSubId = QStringLiteral("huginn-r") + QString::number(QRandomGenerator::global()->generate(), 16).rightJustified(8, QLatin1Char('0'));
        m_pageSubId = QStringLiteral("huginn-p") + QString::number(QRandomGenerator::global()->generate(), 16).rightJustified(8, QLatin1Char('0'));
    }
    ~NostrWorker() override { abortAll(); }

    void setKeys(const Nostr::Keys &k) { m_keys = k; }
    void setKeepAlive(bool on) { m_keepAlive = on; }
    void connectRelays()
    {
        if (m_down)
            return;
        for (const QString &url : Nostr::defaultRelays())
            open(url);
    }
    void reconnectNow();
    void resubscribe();
    void disconnectAll();
    void shutdown();
    void publish(quint64 token, const QString &mailboxHex, const QString &content, int days, int maxRelays, bool low);
    void warmSenders()
    {
        if (m_down)
            return;
        for (const QString &url : Nostr::pickRelays(Nostr::defaultRelays(), m_live, Nostr::kSpread))
            send(url, {});
    }
    // What Nostr did with the events it was handed: taken ones are never handed over again; ones left for later
    // (locked, a key not here yet) are skipped until the inbox is asked for again.
    void verdicts(const QList<QByteArray> &taken, const QList<QPair<QByteArray, int>> &later);
    void restoreSeen(const QList<QByteArray> &ids)
    {
        for (const QByteArray &id : ids)
            noteSeen(id);
    }
    // --perf-selftest: a relay's message as if it came on a socket ("$SUB" stands for the inbox request), and a
    // socket to any address.
    void feed(const QString &url, QString text)
    {
        text.replace(QLatin1String("$SUB"), m_subId);
        onMessage(url, nullptr, text);
    }
    void openUrl(const QString &url) { open(url); }

private:
    struct Link {
        QPointer<QWebSocket> ws;
        bool ponged = true;
    };
    struct Sender {
        QPointer<QWebSocket> ws;
        QStringList queue;
        bool open = false;
        QPointer<QTimer> closer;
    };
    struct Out {
        QString msg, id;
        QStringList relays;           // empty: every relay
        bool low = false;
    };

    void open(const QString &url);
    void opened(const QString &url, QWebSocket *ws);
    void gone(const QString &url, QWebSocket *ws);
    void drop(QWebSocket *ws);
    void subscribe(QWebSocket *ws);
    void signIn(const QString &url, QWebSocket *ws, const QString &challenge);
    void onMessage(const QString &url, QWebSocket *ws, const QString &text);
    void handle(const QString &url, const QJsonObject &ev);
    void setLive(const QString &url, bool live);
    void send(const QString &url, const QStringList &msgs);
    void dropSender(const QString &url, QWebSocket *ws);
    void takeOk(const QString &id);
    void noteSeen(const QByteArray &id);
    qint64 sinceFor(const QString &url) const;
    void storedEvent(const QString &url, const QString &sub, qint64 createdAt);
    void endOfStored(const QString &url, QWebSocket *ws, const QString &sub);
    void commitCursors();
    void abortAll();
    // The batch for Nostr: sent at most every kBatchMs.
    void deliverSoon();
    void deliver();

    Nostr *m_owner;                    // on the window's thread: only ever the target of a queued call
    Nostr::Keys m_keys;
    Nostr::Batch m_batch;
    bool m_batchSoon = false;
    bool m_down = false;
    bool m_keepAlive = false;
    QString m_subId, m_ringSubId, m_pageSubId;
    QHash<QString, Link> m_links;      // exactly one socket per relay
    QSet<QString> m_live;              // relays whose socket is open
    QHash<QString, int> m_retries;
    QHash<QString, Sender> m_senders;
    QList<Out> m_outbox;
    QHash<QString, quint64> m_okWait;  // event id: Nostr's token for it
    QList<QByteArray> m_seen;
    QSet<QByteArray> m_seenSet;
    QList<QByteArray> m_rejected;
    QSet<QByteArray> m_rejectedSet;
    QSet<QByteArray> m_later;
    QSet<QByteArray> m_working;        // handed to Nostr, not answered yet
    QSet<QString> m_announced;         // announced while locked, not opened yet
    int m_gen = 0;                     // inbox requests made: an answer "later" from before the last one doesn't count
    QHash<QString, qint64> m_eoseAt;
    QHash<QString, qint64> m_cursorWait;   // EOSE came; the cursor moves once every event before it is answered
    QSet<QString> m_eosed;
    bool m_refused = false;
    qint64 m_since = 0;                // "nostr/since" as it was at start, then the last event taken
    struct Page {
        int count = 0;
        qint64 oldest = 0;
        qint64 until = 0;
    };
    QHash<QString, Page> m_pages;
    QSet<QString> m_signedIn, m_authClosed, m_askedAgain;
    QHash<QString, QString> m_challenges;
};

void NostrWorker::deliverSoon()
{
    if (m_batchSoon)
        return;
    m_batchSoon = true;
    QTimer::singleShot(kBatchMs, this, [this] {
        m_batchSoon = false;
        deliver();
    });
}

void NostrWorker::deliver()
{
    if (Busy::on) {
        int low = 0, open = 0;
        for (const Out &o : std::as_const(m_outbox))
            low += o.low ? 1 : 0;
        for (const Sender &sn : std::as_const(m_senders))
            open += sn.open ? 1 : 0;
        m_batch.status = QStringList{
            QStringLiteral("relays open: %1 of %2, inbox live on %3").arg(m_live.size()).arg(Nostr::defaultRelays().size()).arg(m_eosed.size()),
            QStringLiteral("relay sending sockets: %1 (%2 open)").arg(m_senders.size()).arg(open),
            QStringLiteral("events waiting for a relay: %1 (%2 a linked device's), waiting for OK: %3").arg(m_outbox.size() - low).arg(low).arg(m_okWait.size()),
            QStringLiteral("handled events kept: %1, refused: %2, left for later: %3, waiting for an answer: %4")
                .arg(m_seen.size()).arg(m_rejected.size()).arg(m_later.size()).arg(m_working.size())};
    }
    if (m_batch.events.isEmpty() && m_batch.oks.isEmpty() && m_batch.live < 0 && !m_batch.eoseChanged && m_batch.status.isEmpty())
        return;
    Nostr::Batch b = std::move(m_batch);
    m_batch = Nostr::Batch();
    Busy::count("relay batches handed to the window's thread");
    Nostr *owner = m_owner;
    QMetaObject::invokeMethod(owner, [owner, b = std::move(b)] { owner->take(b); }, Qt::QueuedConnection);
}

void NostrWorker::abortAll()
{
    for (const Link &l : std::as_const(m_links))
        if (QWebSocket *ws = l.ws.data()) {
            QObject::disconnect(ws, nullptr, this, nullptr);
            ws->abort();
            delete ws;
        }
    for (const Sender &l : std::as_const(m_senders))
        if (QWebSocket *ws = l.ws.data()) {
            QObject::disconnect(ws, nullptr, this, nullptr);
            ws->abort();
            delete ws;
        }
    m_links.clear();
    m_senders.clear();
    m_live.clear();
}

void NostrWorker::shutdown()
{
    // Never waits on a relay: every socket is cut at once, then the thread's loop ends (Nostr::shutdown joins it).
    m_down = true;
    m_keepAlive = false;
    abortAll();
    // Sockets closing politely from disconnect() are children of this object: they go with it.
    const QObjectList kids = children();
    for (QObject *c : kids)
        if (auto *ws = qobject_cast<QWebSocket *>(c)) {
            QObject::disconnect(ws, nullptr, this, nullptr);
            ws->abort();
        }
    QThread::currentThread()->quit();
}

void NostrWorker::noteSeen(const QByteArray &id)
{
    if (id.isEmpty() || m_seenSet.contains(id))
        return;
    m_seen << id;
    m_seenSet.insert(id);
    while (m_seen.size() > kMaxSeen)
        m_seenSet.remove(m_seen.takeFirst());
}

void NostrWorker::open(const QString &url)
{
    // One socket per relay, never two: only opens if there isn't one already.
    if (m_down || m_links.contains(url))
        return;
    auto *ws = new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this);
    Busy::nameSocket(ws, "nostr relay");
    Busy::count(QStringLiteral("relay connects: ") + hostOf(url));
    m_links.insert(url, Link{ws, true});
    m_signedIn.remove(url);
    m_authClosed.remove(url);
    m_askedAgain.remove(url);
    m_eosed.remove(url);
    m_pages.remove(url);
    connect(ws, &QWebSocket::connected, this, [this, url, ws] {
        Busy::ThreadScope busy("relay thread: connections");
        opened(url, ws);
    });
    connect(ws, &QWebSocket::disconnected, this, [this, url, ws] {
        Busy::ThreadScope busy("relay thread: connections");
        gone(url, ws);
    });
    connect(ws, &QWebSocket::errorOccurred, this, [this, url, ws](QAbstractSocket::SocketError) {
        Busy::ThreadScope busy("relay thread: connections");
        Busy::count(QStringLiteral("relay errors: ") + hostOf(url));
        gone(url, ws);
    });
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

void NostrWorker::opened(const QString &url, QWebSocket *ws)
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
    Busy::count(QStringLiteral("relay opened: ") + hostOf(url));
    setLive(url, true);
    subscribe(ws);
    // This relay is reachable again: what still waits for it goes on a sending socket, never on this one. Real messages
    // first, a linked device's frames after them (those meant for this relay, and ones whose relays are all down).
    QStringList again;
    for (int pass = 0; pass < 2; ++pass)
        for (const Out &o : std::as_const(m_outbox)) {
            if (o.low != (pass == 1))
                continue;
            bool anyLive = false;
            for (const QString &r : o.relays)
                anyLive = anyLive || (r != url && m_live.contains(r));
            if (o.relays.isEmpty() || o.relays.contains(url) || !anyLive)
                again << o.msg;
        }
    if (!again.isEmpty())
        send(url, again);
}

// Only the current socket for this relay counts; an old one closing changes nothing.
void NostrWorker::gone(const QString &url, QWebSocket *ws)
{
    auto it = m_links.find(url);
    if (it == m_links.end() || it->ws != ws)
        return;
    m_links.erase(it);
    drop(ws);
    m_eosed.remove(url);
    m_pages.remove(url);
    m_cursorWait.remove(url);
    Busy::count(QStringLiteral("relay dropped: ") + hostOf(url));
    setLive(url, false);
    if (!m_keepAlive || m_down)
        return;
    Busy::count(QStringLiteral("relay reconnects: ") + hostOf(url));
    // Try again: 2 s, 4 s, 8 s ... up to a minute between tries.
    const int n = m_retries.value(url) + 1;
    m_retries.insert(url, n);
    QTimer::singleShot(qMin(60, 1 << qMin(n, 6)) * 1000, this, [this, url] {
        if (m_keepAlive && !m_down)
            open(url);
    });
}

void NostrWorker::drop(QWebSocket *ws)
{
    if (!ws)
        return;
    QObject::disconnect(ws, nullptr, this, nullptr);
    ws->abort();
    ws->deleteLater();
}

void NostrWorker::setLive(const QString &url, bool live)
{
    const bool changed = live ? !m_live.contains(url) : m_live.contains(url);
    if (live)
        m_live.insert(url);
    else
        m_live.remove(url);
    if (changed) {
        m_batch.live = int(m_live.size());
        deliverSoon();
    }
}

void NostrWorker::reconnectNow()
{
    if (m_down || (!m_keepAlive && m_links.isEmpty()))
        return;
    for (const QString &url : Nostr::defaultRelays()) {
        if (m_live.contains(url))
            continue;
        drop(m_links.take(url).ws.data());
        m_retries.remove(url);
        open(url);
    }
}

void NostrWorker::disconnectAll()
{
    m_keepAlive = false;
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
    m_cursorWait.clear();
    if (!m_live.isEmpty()) {
        m_live.clear();
        m_batch.live = 0;
        deliverSoon();
    }
}

void NostrWorker::resubscribe()
{
    if (m_down)
        return;
    // Unlocked (or the keys changed): what was left for later comes again (the cursors stayed where they were), and
    // the cursors may move on once it is taken.
    if (!m_keys.secret.isEmpty())
        m_refused = false;
    for (auto it = m_links.begin(); it != m_links.end(); ++it)
        if (m_live.contains(it.key()) && it->ws) {
            // A relay that asked for a sign-in while locked gets one now, as the mailbox the vault holds the key of.
            const QString challenge = m_challenges.value(it.key());
            if (!challenge.isEmpty())
                signIn(it.key(), it->ws.data(), challenge);
            subscribe(it->ws.data());
        }
}

void NostrWorker::signIn(const QString &url, QWebSocket *ws, const QString &challenge)
{
    if (!ws)
        return;
    const QJsonArray tags{QJsonValue(QJsonArray{QStringLiteral("relay"), url}), QJsonValue(QJsonArray{QStringLiteral("challenge"), challenge})};
    if (!m_keys.secret.isEmpty())
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("AUTH"), Nostr::event(kAuthKind, tags, QString(), m_keys.secret)}));
    for (const QByteArray &rs : std::as_const(m_keys.ringSecrets))
        if (rs.size() == 32)
            ws->sendTextMessage(compact(QJsonArray{QStringLiteral("AUTH"), Nostr::event(kAuthKind, tags, QString(), rs)}));
}

qint64 NostrWorker::sinceFor(const QString &url) const
{
    // From 20 minutes before this relay last finished sending what it held (created_at is blurred up to kBlurS, and
    // clocks differ). A relay with no cursor yet: from two days before the last event taken, as before. Never further
    // back than the longest message limit (nothing older is kept anywhere).
    const qint64 floor = qMax<qint64>(0, now() - (qint64(Nostr::kLongestDays) + 1) * 86400);
    const qint64 cursor = m_eoseAt.value(url, 0);
    if (cursor > 0)
        return qMax(floor, cursor - 20 * 60);
    return qMax(floor, m_since - 2 * 86400);
}

void NostrWorker::storedEvent(const QString &url, const QString &sub, qint64 createdAt)
{
    // Only stored events count (before EOSE), on the inbox request or a page request still going.
    if (sub == m_subId ? m_eosed.contains(url) : (sub != m_pageSubId || m_pages.value(url).until == 0))
        return;
    Page &pg = m_pages[url];
    ++pg.count;
    if (createdAt > 0 && (pg.oldest <= 0 || createdAt < pg.oldest))
        pg.oldest = createdAt;
}

void NostrWorker::endOfStored(const QString &url, QWebSocket *ws, const QString &sub)
{
    if (sub != m_subId && sub != m_pageSubId)
        return;
    if (m_links.value(url).ws != ws)
        return;
    if (sub == m_subId)
        m_eosed.insert(url);   // from here on the inbox request is live on this relay
    Page &pg = m_pages[url];
    const bool full = pg.count >= Nostr::kPageFull && pg.oldest > 0 && (pg.until == 0 || pg.oldest < pg.until);
    if (full && ws) {
        // A full page: there may be more before it. Asked for on a request of its own (the inbox request stays live).
        Busy::count("relay inbox pages asked for");
        pg.until = pg.oldest;
        pg.count = 0;
        const QString pub = m_keys.pub;
        if (!pub.isEmpty()) {
            const QJsonObject filter{{QStringLiteral("kinds"), QJsonArray{Nostr::Kind}},
                                     {QStringLiteral("#p"), QJsonArray{pub}},
                                     {QStringLiteral("since"), sinceFor(url)},
                                     {QStringLiteral("until"), pg.until}};
            ws->sendTextMessage(compact(QJsonArray{QStringLiteral("REQ"), m_pageSubId, filter}));
            return;
        }
    }
    if (sub == m_pageSubId && ws)
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("CLOSE"), m_pageSubId}));
    m_pages.remove(url);
    // Everything this relay held has come: next time only what is newer. Not while something was left for later, and
    // not before Nostr has answered for every event handed to it.
    m_cursorWait.insert(url, now());
    commitCursors();
}

void NostrWorker::commitCursors()
{
    if (m_cursorWait.isEmpty() || !m_working.isEmpty())
        return;
    if (!m_refused) {
        for (auto it = m_cursorWait.cbegin(); it != m_cursorWait.cend(); ++it)
            m_eoseAt.insert(it.key(), it.value());
        m_batch.eose = m_eoseAt;
        m_batch.eoseChanged = true;
        deliverSoon();
    }
    m_cursorWait.clear();
}

void NostrWorker::subscribe(QWebSocket *ws)
{
    if (!ws)
        return;
    // A new request: what was left for later is tried again when its copies come.
    m_later.clear();
    ++m_gen;
    QString url;
    for (auto it = m_links.cbegin(); it != m_links.cend(); ++it)
        if (it->ws == ws)
            url = it.key();
    // Stored events are counted again from here (a full page asks for the ones before it).
    m_eosed.remove(url);
    m_pages.remove(url);
    const QString pub = m_keys.pub;
    if (!pub.isEmpty()) {
        const QJsonObject filter{{QStringLiteral("kinds"), QJsonArray{Nostr::Kind}},
                                 {QStringLiteral("#p"), QJsonArray{pub}},
                                 {QStringLiteral("since"), sinceFor(url)}};
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("REQ"), m_subId, filter}));
        Busy::count("relay inbox requests");
    }
    // Cap b1: the ring mailbox, on a request of its own (a relay may answer one and not the other).
    const QStringList rings = m_keys.rings;
    if (!rings.isEmpty()) {
        const QJsonObject filter{{QStringLiteral("kinds"), QJsonArray{Nostr::Kind}},
                                 {QStringLiteral("#p"), QJsonArray::fromStringList(rings)},
                                 {QStringLiteral("since"), sinceFor(url)}};
        ws->sendTextMessage(compact(QJsonArray{QStringLiteral("REQ"), m_ringSubId, filter}));
    }
}

void NostrWorker::onMessage(const QString &url, QWebSocket *ws, const QString &text)
{
    if (m_down)
        return;
    Busy::ThreadScope busy("relay thread: messages");
    // The same event comes from every relay: a copy already handled (or refused, or left for later, or still with
    // Nostr) is skipped before the message is read in full.
    const QByteArray quick = quickId(text);
    if (!quick.isEmpty()) {
        Busy::count("relay EVENTs received");
        if (m_seenSet.contains(quick) || m_rejectedSet.contains(quick) || m_later.contains(quick) || m_working.contains(quick)) {
            Busy::count("relay EVENTs skipped as already handled");
            storedEvent(url, quickSub(text), quickCreatedAt(text));
            return;
        }
    }
    const QJsonDocument doc = QJsonDocument::fromJson(text.toUtf8());
    if (!doc.isArray())
        return;
    const QJsonArray a = doc.array();
    const QString type = a.at(0).toString();
    if (type == QLatin1String("EVENT")) {
        if (quick.isEmpty())
            Busy::count("relay EVENTs received");
        if (a.at(2).isObject()) {
            const QJsonObject ev = a.at(2).toObject();
            storedEvent(url, a.at(1).toString(), ev.value(QStringLiteral("created_at")).toInteger());
            handle(url, ev);
        }
    } else if (type == QLatin1String("EOSE")) {
        endOfStored(url, ws, a.at(1).toString());
    } else if (type == QLatin1String("AUTH")) {
        // Some relays only hand out messages to the mailbox's owner: prove it (NIP-42).
        Busy::count("relay sign-in requests");
        m_challenges.insert(url, a.at(1).toString());
        if (m_keys.secret.isEmpty() && m_keys.ringSecrets.isEmpty())
            return;
        signIn(url, ws, a.at(1).toString());
        m_signedIn.insert(url);
        // The inbox request went out when the relay opened. Asked for once more after the sign-in (a relay may hand
        // out the mailbox's events only now), unless it already sent everything it held on it.
        if ((m_authClosed.contains(url) || !m_eosed.contains(url)) && !m_askedAgain.contains(url)) {
            m_authClosed.remove(url);
            m_askedAgain.insert(url);
            subscribe(ws);
        }
    } else if (type == QLatin1String("CLOSED")) {
        const QString sub = a.at(1).toString();
        if ((sub != m_subId && sub != m_ringSubId) || !a.at(2).toString().startsWith(QLatin1String("auth-required")))
            return;
        Busy::count("relay inbox requests closed until signed in");
        if (!m_signedIn.contains(url)) {
            m_authClosed.insert(url);   // asked again right after the sign-in
        } else if (!m_askedAgain.contains(url)) {
            // Signed in already: once more, a moment later (the relay may not have taken the sign-in yet).
            m_askedAgain.insert(url);
            QPointer<QWebSocket> w(ws);
            QTimer::singleShot(1500, this, [this, url, w] {
                if (w && m_links.value(url).ws == w && m_live.contains(url))
                    subscribe(w.data());
            });
        }
    } else if (type == QLatin1String("OK")) {
        if (a.at(2).toBool())
            takeOk(a.at(1).toString());
    }
}

void NostrWorker::handle(const QString &url, const QJsonObject &ev)
{
    Q_UNUSED(url);
    const QString id = ev.value(QStringLiteral("id")).toString();
    const QByteArray raw = QByteArray::fromHex(id.toLatin1());
    // The same event comes from every relay at once: only one copy is handled at a time.
    if (id.isEmpty() || raw.size() != 32 || m_seenSet.contains(raw) || m_rejectedSet.contains(raw) || m_later.contains(raw)
        || m_working.contains(raw))
        return;
    const auto reject = [this, &raw] {
        Busy::count("relay EVENTs refused (id or signature)");
        m_rejected << raw;
        m_rejectedSet.insert(raw);
        while (m_rejected.size() > kMaxRejected)
            m_rejectedSet.remove(m_rejected.takeFirst());
    };
    const auto valid = [&] {
        Busy::ThreadScope busy("relay thread: signature checks");
        return Nostr::idOf(ev) == id
            && Schnorr::verify(raw, Schnorr::unhex(ev.value(QStringLiteral("pubkey")).toString()),
                               Schnorr::unhex(ev.value(QStringLiteral("sig")).toString()));
    };
    // Cap b1: an event for the ring mailbox is only ever a ring envelope. Tried with the ring key, then dropped.
    const QStringList &rings = m_keys.rings;
    if (!rings.isEmpty()) {
        QString p;
        for (const QJsonValue &t : ev.value(QStringLiteral("tags")).toArray())
            if (t.toArray().at(0).toString() == QLatin1String("p")) {
                p = t.toArray().at(1).toString();
                break;
            }
        if (!p.isEmpty() && rings.contains(p)) {
            if (!valid()) {
                reject();
                return;
            }
            Busy::count("relay EVENTs for the ring mailbox");
            noteSeen(raw);
            Nostr::Incoming in;
            in.id = raw;
            in.content = ev.value(QStringLiteral("content")).toString();
            in.ring = true;
            m_batch.events << in;
            deliverSoon();
            return;
        }
    }
    // While locked, say "New message" once per event, not once per relay.
    if (m_keys.secret.isEmpty()) {
        if (m_announced.contains(id))
            return;
        if (m_announced.size() > 5000)
            m_announced.clear();
        m_announced.insert(id);
    }
    // Only trust what the event's own signature covers.
    if (!valid()) {
        reject();
        return;
    }
    Nostr::Incoming in;
    in.id = raw;
    in.content = ev.value(QStringLiteral("content")).toString();
    in.gen = m_gen;
    // Opened here, with copies of the keys, so the window's thread only gets what is ready to read.
    if (!m_keys.secret.isEmpty() && m_keys.open) {
        Busy::ThreadScope busy("relay thread: opening");
        bool ok = false;
        const QByteArray body = m_keys.open(in.content, &ok);
        in.tried = true;
        if (ok && !body.isEmpty()) {
            in.opened = true;
            in.body = body;
        }
    }
    m_working.insert(raw);
    m_batch.events << in;
    deliverSoon();
}

void NostrWorker::verdicts(const QList<QByteArray> &taken, const QList<QPair<QByteArray, int>> &later)
{
    for (const QByteArray &id : taken) {
        m_working.remove(id);
        noteSeen(id);
        m_since = now();
        Busy::count("relay EVENTs taken");
    }
    for (const auto &l : later) {
        m_working.remove(l.first);
        Busy::count("relay EVENTs left for later");
        // Left for later (locked, or a key not here yet): its copies from other relays are skipped until the inbox is
        // asked for again (not when it has been asked for again since: then they come again), and no relay's cursor
        // moves on past it.
        if (l.second == m_gen) {
            if (m_later.size() > 5000)
                m_later.clear();
            m_later.insert(l.first);
        }
        m_refused = true;
    }
    commitCursors();
}

// ------------------------------------------------------------------ sending

void NostrWorker::send(const QString &url, const QStringList &msgs)
{
    if (m_down)
        return;
    auto it = m_senders.find(url);
    if (it == m_senders.end() || !it->ws) {
        auto *ws = new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this);
        Busy::nameSocket(ws, "nostr send");
        Busy::count(QStringLiteral("relay sending sockets opened: ") + hostOf(url));
        auto *closer = new QTimer(ws);
        closer->setObjectName(QStringLiteral("nostr send closer"));
        closer->setSingleShot(true);
        closer->setInterval(kSendMs);
        connect(closer, &QTimer::timeout, this, [this, url, ws] { dropSender(url, ws); });
        connect(ws, &QWebSocket::connected, this, [this, url, ws] {
            Busy::ThreadScope busy("relay thread: sending");
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
        connect(ws, &QWebSocket::disconnected, this, [this, url, ws] {
            Busy::ThreadScope busy("relay thread: sending");
            dropSender(url, ws);
        });
        connect(ws, &QWebSocket::errorOccurred, this, [this, url, ws](QAbstractSocket::SocketError) {
            Busy::ThreadScope busy("relay thread: sending");
            Busy::count(QStringLiteral("relay sending errors: ") + hostOf(url));
            dropSender(url, ws);
        });
        // Only "OK" matters here. "AUTH" is never answered: signing in would name this computer's mailbox.
        connect(ws, &QWebSocket::textMessageReceived, this, [this](const QString &text) {
            Busy::ThreadScope busy("relay thread: sending");
            const QJsonArray a = QJsonDocument::fromJson(text.toUtf8()).array();
            if (a.at(0).toString() == QLatin1String("OK") && a.at(2).toBool())
                takeOk(a.at(1).toString());
        });
        it = m_senders.insert(url, Sender{ws, {}, false, closer});
        ws->open(QUrl(url));
    }
    if (it->closer)
        it->closer->start();   // starts the few minutes again
    if (it->open && it->ws) {
        for (const QString &m : msgs)
            it->ws->sendTextMessage(m);
    } else {
        it->queue << msgs;
    }
}

void NostrWorker::dropSender(const QString &url, QWebSocket *ws)
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

void NostrWorker::takeOk(const QString &id)
{
    if (id.isEmpty())
        return;
    m_outbox.removeIf([&id](const Out &o) { return o.id == id; });
    // NIP-20: the first relay that stored it. Only now is it "sent" (Nostr runs what waited for it).
    const auto it = m_okWait.find(id);
    if (it == m_okWait.end())
        return;
    m_batch.oks << it.value();
    m_okWait.erase(it);
    deliverSoon();
}

void NostrWorker::publish(quint64 token, const QString &mailboxHex, const QString &content, int days, int maxRelays, bool low)
{
    if (m_down)
        return;
    Busy::ThreadScope busy("relay thread: signing and sending");
    // QJsonValue(...) keeps the inner array nested: MSVC treats QJsonArray{QJsonArray{...}} as a copy.
    QJsonArray tags{QJsonValue(QJsonArray{QStringLiteral("p"), mailboxHex})};
    // A new throwaway key for every message, and a slightly blurred time, so relays can't link messages to a sender.
    const qint64 createdAt = now() - QRandomGenerator::global()->bounded(int(Nostr::kBlurS));
    // NIP-40: relays may drop it once the longest message limit has passed (it's gone from both sides by then). Taken from
    // the blurred time and rounded up to a whole day, so it shows neither the sending time nor the chat's limit.
    if (days > 0)
        tags.append(QJsonValue(QJsonArray{QStringLiteral("expiration"), QString::number(Nostr::expiration(createdAt))}));
    const QJsonObject ev = Nostr::event(Nostr::Kind, tags, content, Schnorr::newSecret(), createdAt);
    const QString msg = compact(QJsonArray{QStringLiteral("EVENT"), ev});
    const QString id = ev.value(QStringLiteral("id")).toString();
    const QStringList to = Nostr::pickRelays(Nostr::defaultRelays(), m_live, maxRelays > 0 ? maxRelays : Nostr::kSpread);
    m_outbox << Out{msg, id, to, low};
    // Each kind has its own cap: a linked device's frames only ever push out older ones of their own.
    int count = 0;
    for (const Out &o : std::as_const(m_outbox))
        count += o.low == low ? 1 : 0;
    for (qsizetype i = 0; i < m_outbox.size() && count > (low ? Nostr::kMaxLowOutbox : Nostr::kMaxOutbox);) {
        if (m_outbox.at(i).low == low) {
            m_outbox.removeAt(i);   // its OK, if one still comes, still counts
            count--;
        } else {
            ++i;
        }
    }
    if (token) {
        if (m_okWait.size() > 500)
            m_okWait.clear();
        m_okWait.insert(id, token);
    }
    // The relays chosen get it, each on a sending socket of its own that never asks for the inbox or signs in.
    for (const QString &url : to)
        send(url, {msg});
    if (m_links.isEmpty())
        connectRelays();
}

// ------------------------------------------------------------------ the window's thread

Nostr::Nostr(std::function<QByteArray()> secret, QObject *parent)
    : QObject(parent)
    , m_secret(std::move(secret))
{
    const QSettings s = Settings::store();
    const QVariantMap eose = s.value(QStringLiteral("nostr/eose")).toMap();
    for (auto it = eose.cbegin(); it != eose.cend(); ++it)
        m_eoseAt.insert(it.key(), it.value().toLongLong());
    const qint64 since = s.value(QStringLiteral("nostr/since"), 0).toLongLong();
    // The relay thread: made here, then moved to its thread before it starts, so everything it makes is made there.
    m_thread = new QThread(this);
    m_thread->setObjectName(QStringLiteral("huginn-relays"));
    m_worker = new NostrWorker(this, m_eoseAt, since);
    m_worker->moveToThread(m_thread);
    QObject::connect(m_thread, &QThread::finished, m_worker, &QObject::deleteLater);
    m_thread->start();
    if (QCoreApplication *app = QCoreApplication::instance())
        connect(app, &QCoreApplication::aboutToQuit, this, [this] {
            shutdown();
            flushSeen();
        });
    // Labs › Performance check: how the relays stand at the end of a check (as the relay thread last said).
    Busy::addStatus(this, [this] {
        QStringList out = m_status;
        out << QStringLiteral("relay replies waiting for OK here: %1, relays open as last told: %2").arg(m_okWait.size()).arg(m_live);
        return out;
    });
}

Nostr::~Nostr()
{
    shutdown();
}

void Nostr::post(std::function<void(NostrWorker *)> f)
{
    NostrWorker *w = m_worker;
    if (!w)
        return;
    QMetaObject::invokeMethod(w, [w, f = std::move(f)] { f(w); }, Qt::QueuedConnection);
}

void Nostr::shutdown()
{
    if (!m_thread)
        return;
    QThread *t = m_thread;
    m_thread = nullptr;
    // Nothing more goes to it: the shutdown is the last thing it is asked.
    post([](NostrWorker *w) { w->shutdown(); });
    m_worker = nullptr;
    // If its loop never ran the shutdown (it can't block on this thread, but a stuck system call could), it is asked
    // to stop again, then left behind rather than waited on forever: Huginn is closing anyway.
    if (!t->wait(3000)) {
        t->quit();
        if (!t->wait(1000)) {
            qWarning("nostr: the relay thread didn't stop in time; leaving it");
            t->setParent(nullptr);   // never destroyed while running (that would abort)
            return;
        }
    }
    delete t;
}

void Nostr::pushKeys()
{
    if (!m_worker)
        return;
    Keys k;
    k.secret = secret();
    k.secret.detach();   // its own copy: forgetSecret() wiping this one never touches the other thread's
    k.pub = publicKey();
    k.rings = ringMailboxes ? ringMailboxes() : QStringList();
    for (const QByteArray &rs : ringSecrets ? ringSecrets() : QList<QByteArray>()) {
        QByteArray c = rs;
        c.detach();
        k.ringSecrets << c;
    }
    if (!k.secret.isEmpty() && opener)
        k.open = opener();
    m_keysPushed = true;
    post([k](NostrWorker *w) { w->setKeys(k); });
}

void Nostr::take(const Batch &b)
{
    Busy::Scope busy("relay batches");
    ++m_batches;
    if (b.live >= 0 && b.live != m_live) {
        m_live = b.live;
        Q_EMIT connectedChanged();
    }
    if (!b.status.isEmpty())
        m_status = b.status;
    if (b.eoseChanged) {
        m_eoseAt = b.eose;
        noteSeenSoon();
    }
    for (const quint64 token : b.oks) {
        const auto it = m_okWait.find(token);
        if (it == m_okWait.end())
            continue;
        const std::function<void()> f = it.value();
        m_okWait.erase(it);
        if (f)
            f();
    }
    QList<QByteArray> taken;
    QList<QPair<QByteArray, int>> later;
    bool stale = false;
    for (const Incoming &in : b.events) {
        if (in.ring) {
            noteSeen(in.id);
            noteSeenSoon();
            if (onRing)
                onRing(in.content);
            continue;
        }
        const bool ok = in.opened && onOpened ? onOpened(in.body) : onEnvelope && onEnvelope(in.content);
        if (ok) {
            noteSeen(in.id);
            m_takenAt = now();
            noteSeenSoon();
            taken << in.id;
        } else {
            later << qMakePair(in.id, in.gen);
        }
        // Tried there and not opened: the keys it has may be old (a new day key). It gets fresh copies.
        stale = stale || (in.tried && !in.opened);
    }
    if (!taken.isEmpty() || !later.isEmpty())
        post([taken, later](NostrWorker *w) { w->verdicts(taken, later); });
    if (stale)
        pushKeys();
}

void Nostr::noteSeenSoon()
{
    if (m_seenDirty)
        return;
    m_seenDirty = true;
    QTimer::singleShot(kSeenSaveMs, this, &Nostr::flushSeen);
}

void Nostr::flushSeen()
{
    if (!m_seenDirty)
        return;
    m_seenDirty = false;
    if (keepSeenLater) {
        // Made into JSON and written on the saving thread, from a copy of the list taken now.
        const QList<QByteArray> seen = m_seen;
        keepSeenLater([seen] {
            QJsonArray ids;
            for (const QByteArray &id : seen)
                ids.append(QString::fromLatin1(id.toHex()));
            return QJsonDocument(QJsonObject{{QStringLiteral("seen"), ids}}).toJson(QJsonDocument::Compact);
        });
    } else if (keepSeen) {
        QJsonArray ids;
        for (const QByteArray &id : std::as_const(m_seen))
            ids.append(QString::fromLatin1(id.toHex()));
        keepSeen(QJsonDocument(QJsonObject{{QStringLiteral("seen"), ids}}).toJson(QJsonDocument::Compact));
    }
    QSettings s = Settings::store();
    if (m_takenAt > 0)
        s.setValue(QStringLiteral("nostr/since"), m_takenAt);
    QVariantMap eose;
    for (auto it = m_eoseAt.cbegin(); it != m_eoseAt.cend(); ++it)
        eose.insert(it.key(), it.value());
    s.setValue(QStringLiteral("nostr/eose"), eose);
}

void Nostr::restoreSeen(const QByteArray &stored)
{
    QList<QByteArray> all;
    QSet<QByteArray> have;
    for (const QJsonValue &v : QJsonDocument::fromJson(stored).object().value(QStringLiteral("seen")).toArray()) {
        const QByteArray id = QByteArray::fromHex(v.toString().toLatin1());
        if (id.size() == 32 && !have.contains(id)) {
            have.insert(id);
            all << id;
        }
    }
    // Then what came while the vault was locked.
    for (const QByteArray &id : std::as_const(m_seen))
        if (!have.contains(id)) {
            have.insert(id);
            all << id;
        }
    while (all.size() > kMaxSeen)
        have.remove(all.takeFirst());
    m_seen = all;
    m_seenSet = have;
    post([all](NostrWorker *w) { w->restoreSeen(all); });
}

void Nostr::noteSeen(const QByteArray &id)
{
    if (id.isEmpty() || m_seenSet.contains(id))
        return;
    m_seen << id;
    m_seenSet.insert(id);
    while (m_seen.size() > kMaxSeen)
        m_seenSet.remove(m_seen.takeFirst());
}

QByteArray Nostr::secret() const
{
    if (!m_secretCache.isEmpty())
        return m_secretCache;
    QByteArray b = m_secret ? m_secret() : QByteArray();
    if (b.size() == 64)
        b = QByteArray::fromHex(b);
    if (b.size() != 32)
        return QByteArray();
    m_secretCache = b;   // kept until the vault locks or is erased (forgetSecret)
    return b;
}

void Nostr::forgetSecret()
{
    m_secretCache.fill(0);
    m_secretCache.clear();
    // The relay thread's copy goes too (and so does its way of opening envelopes).
    if (m_keysPushed)
        pushKeys();
}

QString Nostr::publicKey() const
{
    if (!m_pub.isEmpty())
        return m_pub;
    QSettings s = Settings::store();
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
    return defaultRelays();
}

void Nostr::setKeepAlive(bool on)
{
    if (m_keepAlive == on)
        return;
    m_keepAlive = on;
    post([on](NostrWorker *w) { w->setKeepAlive(on); });
    Q_EMIT keepAliveChanged();
}

void Nostr::connectRelays()
{
    pushKeys();
    post([](NostrWorker *w) { w->connectRelays(); });
}

void Nostr::reconnectNow()
{
    pushKeys();
    post([](NostrWorker *w) { w->reconnectNow(); });
}

void Nostr::disconnect()
{
    setKeepAlive(false);
    post([](NostrWorker *w) { w->disconnectAll(); });
}

void Nostr::resubscribe()
{
    pushKeys();
    post([](NostrWorker *w) { w->resubscribe(); });
}

void Nostr::warmSenders()
{
    post([](NostrWorker *w) { w->warmSenders(); });
}

QStringList Nostr::pickRelays(const QStringList &all, const QSet<QString> &live, int n)
{
    if (n <= 0 || n >= all.size())
        return all;
    QStringList out;
    for (const QString &u : all)
        if (live.contains(u) && out.size() < n)
            out << u;
    for (const QString &u : all)
        if (!out.contains(u) && out.size() < n)
            out << u;
    return out;
}

int Nostr::publish(const QString &mailboxHex, const QString &content, int days)
{
    return publishTo(mailboxHex, content, days, {});
}

int Nostr::publishTo(const QString &mailboxHex, const QString &content, int days, std::function<void()> onStored, int maxRelays, bool low)
{
    // Signed and sent on the relay thread; onStored runs here once a relay has said OK to it.
    quint64 token = 0;
    if (onStored) {
        if (m_okWait.size() > 500)
            m_okWait.clear();
        token = ++m_nextToken;
        m_okWait.insert(token, std::move(onStored));
    }
    if (!m_keysPushed)
        pushKeys();   // a relay opened for it asks for the inbox too, which needs the mailbox
    post([token, mailboxHex, content, days, maxRelays, low](NostrWorker *w) { w->publish(token, mailboxHex, content, days, maxRelays, low); });
    return m_live;
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

// ------------------------------------------------------------------ --perf-selftest

bool Nostr::threadSelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        printf("perf self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    const auto waitFor = [](const std::function<bool()> &done, int ms) {
        QElapsedTimer t;
        t.start();
        while (!done() && t.elapsed() < ms) {
            QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
            QThread::msleep(2);
        }
        return done();
    };
    const QByteArray sk = Schnorr::newSecret();
    const QString pub = Schnorr::hex(Schnorr::publicKey(sk));
    const QString url = QStringLiteral("wss://selftest.invalid");
    const auto eventText = [&pub](const QByteArray &body, bool forged) {
        QJsonObject e = event(Kind, QJsonArray{QJsonValue(QJsonArray{QStringLiteral("p"), pub})}, QString::fromLatin1(body.toBase64()), Schnorr::newSecret());
        if (forged)
            e.insert(QStringLiteral("content"), QStringLiteral("changed"));
        return compact(QJsonArray{QStringLiteral("EVENT"), QStringLiteral("$SUB"), e});
    };
    const QString eose = compact(QJsonArray{QStringLiteral("EOSE"), QStringLiteral("$SUB")});

    // Declared before the Nostr: the relay thread may write it until the Nostr has joined that thread.
    std::atomic<QThread *> openedOn{nullptr};
    QThread *const gui = QThread::currentThread();
    QList<QByteArray> bodies;
    bool onGui = true;
    int laterOnce = 1;
    {
        Nostr n([sk] { return sk; });
        n.opener = [&openedOn]() -> Opener {
            return [&openedOn](const QString &content, bool *ok) {
                openedOn = QThread::currentThread();
                *ok = true;
                return QByteArray::fromBase64(content.toLatin1());
            };
        };
        n.onOpened = [&](const QByteArray &body) {
            onGui = onGui && QThread::currentThread() == gui;
            if (body == "later" && laterOnce-- > 0)
                return false;
            bodies << body;
            return true;
        };
        n.onEnvelope = [](const QString &) { return true; };
        n.pushKeys();

        // Round trip: checked, opened and deduped there; taken here, in order; a forged one never comes.
        const QString one = eventText("one", false), later = eventText("later", false);
        const QStringList first{one, one, eventText("forged", true), later, eventText("two", false), eose};
        n.post([url, first](NostrWorker *w) {
            for (const QString &t : first)
                w->feed(url, t);
        });
        waitFor([&] { return bodies.size() >= 2; }, 3000);
        waitFor([] { return false; }, 300);   // anything more that would come
        check("relay thread round trip (opened there, in order, copies and forgeries dropped)",
              bodies == QList<QByteArray>{"one", "two"} && onGui && openedOn.load() && openedOn.load() != gui);
        check("a relay's cursor waits while an event is left for later", !n.m_eoseAt.contains(url));

        // Asked again once unlocked: the cursor moves once everything before the EOSE is answered.
        n.resubscribe();
        const QString three = eventText("three", false);
        n.post([url, three, eose](NostrWorker *w) {
            w->feed(url, three);
            w->feed(url, eose);
        });
        waitFor([&] { return n.m_eoseAt.contains(url); }, 3000);
        check("the cursor moves once every event is answered", n.m_eoseAt.contains(url) && bodies.size() == 3 && bodies.last() == "three");

        // A burst comes in a few batches, not one call per event.
        QStringList burst;
        for (int i = 0; i < 200; ++i)
            burst << eventText("b" + QByteArray::number(i), false);
        const int before = n.m_batches;
        n.post([url, burst](NostrWorker *w) {
            for (const QString &t : burst)
                w->feed(url, t);
        });
        waitFor([&] { return bodies.size() >= 203; }, 10000);
        const int batches = n.m_batches - before;
        check("a burst of 200 events is handed over in batches", bodies.size() == 203 && batches >= 1 && batches <= 20);

        // Shutdown: a socket still connecting to a server that never answers, and a burst still queued, never hold it up.
        QTcpServer silent;
        silent.listen(QHostAddress::LocalHost);
        const QString stuck = QStringLiteral("ws://127.0.0.1:%1").arg(silent.serverPort());
        n.post([stuck](NostrWorker *w) { w->openUrl(stuck); });
        waitFor([&] { return silent.hasPendingConnections(); }, 2000);
        n.post([url, burst](NostrWorker *w) {
            for (const QString &t : burst)
                w->feed(url, t);
        });
        QElapsedTimer t;
        t.start();
        n.shutdown();
        const qint64 took = t.elapsed();
        check("shutdown joins the relay thread without hanging", n.m_thread == nullptr && n.m_worker == nullptr && took < 3500);
        n.publish(pub, QStringLiteral("after"));   // nothing to send it to: dropped, never a crash
        n.resubscribe();
    }
    check("the relay thread is gone with its Nostr", true);
    return all;
}
