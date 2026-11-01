#include "link.h"
#include "chat.h"
#include "chatcrypto.h"
#include "msglimits.h"
#include "trust.h"
#include "vault.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUuid>
#include <algorithm>
#include <cstdio>

namespace
{
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
QString vstr(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
QByteArray compact(const QJsonValue &v)
{
    return v.isArray() ? QJsonDocument(v.toArray()).toJson(QJsonDocument::Compact) : QJsonDocument(v.toObject()).toJson(QJsonDocument::Compact);
}
// A SHA-256 in lowercase hex.
bool hexOk(const QString &h)
{
    if (h.size() != 64)
        return false;
    for (const QChar c : h)
        if (!((c >= QLatin1Char('0') && c <= QLatin1Char('9')) || (c >= QLatin1Char('a') && c <= QLatin1Char('f'))))
            return false;
    return true;
}
QString sha256(const QByteArray &b) { return QString::fromLatin1(QCryptographicHash::hash(b, QCryptographicHash::Sha256).toHex()); }
bool kindOk(const QString &k)
{
    return k == QLatin1String("photo") || k == QLatin1String("voice") || k == QLatin1String("video") || k == QLatin1String("file");
}
}

const char Link::kPrefix[] = "MUNINNL1:";

Link::Link(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    connect(m_chat, &Chat::changed, this, [this] {
        if (!m_chat->ready() && m_loaded) {
            m_loaded = false;
            m_ownOpen = false;
            Q_EMIT changed();
        } else if (m_chat->ready() && !m_loaded) {
            Q_EMIT changed();
        }
    });
    // This device's own chats close again whenever the lock goes up.
    connect(m_chat->m_vault, &Vault::changed, this, [this] {
        if (m_ownOpen && m_chat->m_vault->screenLocked()) {
            m_ownOpen = false;
            Q_EMIT changed();
        }
    });
    // A companion asks for what's missing every half minute while it's on the home Wi-Fi (one file at a time).
    m_fetchTimer.setInterval(30000);
    connect(&m_fetchTimer, &QTimer::timeout, this, [this] {
        if (!m_chat->ready())
            return;
        ensureLoaded();
        if (m_phone.key.isEmpty() || !m_phone.ok)
            return;
        if (m_want.isEmpty()) {
            for (const Mirror &c : std::as_const(m_mirror))
                for (const M &x : c.msgs)
                    want(x);
        }
        fetchNext();
    });
    m_fetchTimer.start();
}

void Link::ensureLoaded() const
{
    if (m_loaded || !m_chat->ready())
        return;
    m_loaded = true;
    m_companions.clear();
    m_phone = Device();
    m_mirror.clear();
    const QJsonObject o = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link.json"))).object();
    auto dev = [](const QJsonObject &d) { return Device{jstr(d, "k"), jstr(d, "p"), jstr(d, "n"), d.value(QStringLiteral("ok")).toBool(true)}; };
    for (const QJsonValue &v : o.value(QStringLiteral("companions")).toArray())
        m_companions << dev(v.toObject());
    if (o.contains(QStringLiteral("phone")))
        m_phone = dev(o.value(QStringLiteral("phone")).toObject());
    const QJsonObject mo = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("mirror.json"))).object();
    for (auto it = mo.begin(); it != mo.end(); ++it) {
        const QJsonObject c = it.value().toObject();
        Mirror m{jstr(c, "peer"), jstr(c, "n"), {}, daysOf(c, Limits::kDefault)};
        for (const QJsonValue &v : c.value(QStringLiteral("msgs")).toArray())
            m.msgs << mOf(v.toObject());
        if (!m.peer.isEmpty())
            m_mirror.insert(m.peer, m);
    }
}

void Link::save()
{
    auto devJson = [](const Device &d) { return QJsonObject{{QStringLiteral("k"), d.key}, {QStringLiteral("p"), d.mailbox}, {QStringLiteral("n"), d.name}, {QStringLiteral("ok"), d.ok}}; };
    QJsonArray c;
    for (const Device &d : std::as_const(m_companions))
        c.append(devJson(d));
    QJsonObject o{{QStringLiteral("companions"), c}};
    if (!m_phone.key.isEmpty())
        o.insert(QStringLiteral("phone"), devJson(m_phone));
    m_chat->m_vault->write(QStringLiteral("link.json"), compact(o));
    Q_EMIT changed();
}

bool Link::firstSend(const QString &id)
{
    if (id.isEmpty())
        return true;   // a send with no id: nothing to check against
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    if (!m_sendsLoaded) {
        m_sendsLoaded = true;
        const QJsonObject o = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link-sends.json"))).object();
        for (auto it = o.begin(); it != o.end(); ++it)
            m_sends.insert(it.key(), qint64(it.value().toDouble()));
    }
    m_sends.removeIf([t](const QHash<QString, qint64>::iterator it) { return t - it.value() > 2 * 86400000LL; });
    if (m_sends.contains(id))
        return false;
    m_sends.insert(id, t);
    while (m_sends.size() > 1000) {
        auto oldest = m_sends.begin();
        for (auto it = m_sends.begin(); it != m_sends.end(); ++it)
            if (it.value() < oldest.value())
                oldest = it;
        m_sends.erase(oldest);
    }
    QJsonObject o;
    for (auto it = m_sends.cbegin(); it != m_sends.cend(); ++it)
        o.insert(it.key(), double(it.value()));
    m_chat->m_vault->write(QStringLiteral("link-sends.json"), compact(o));
    return true;
}

QJsonObject Link::fJson(const M &m)
{
    return {{QStringLiteral("h"), m.hash}, {QStringLiteral("s"), double(m.size)}, {QStringLiteral("k"), m.kind}, {QStringLiteral("nm"), m.name}};
}

void Link::saveMirror()
{
    QJsonObject o;
    for (const Mirror &m : std::as_const(m_mirror)) {
        QJsonArray a;
        for (const M &x : m.msgs) {
            QJsonObject j{{QStringLiteral("id"), x.id}, {QStringLiteral("x"), x.text}, {QStringLiteral("me"), x.mine}, {QStringLiteral("t"), double(x.time)}};
            if (!x.hash.isEmpty())
                j.insert(QStringLiteral("f"), fJson(x));
            a.append(j);
        }
        o.insert(m.peer, QJsonObject{{QStringLiteral("peer"), m.peer}, {QStringLiteral("n"), m.name}, {QStringLiteral("d"), m.days}, {QStringLiteral("msgs"), a}});
    }
    m_chat->m_vault->write(QStringLiteral("mirror.json"), compact(o));
    Q_EMIT changed();
}

void Link::sendTo(const Device &d, const QJsonObject &msg)
{
    // Sealed like a private message, on every route at once.
    m_chat->sendEverywhere(d.key, QStringLiteral("link"), msg, d.mailbox.size() == 64 ? d.mailbox : QString());
}

// Only to the device's address on the home Wi-Fi (the LAN route), never anywhere else. False when there is none.
bool Link::sendLan(const QString &key, const QJsonObject &msg, std::function<void(int)> done)
{
    if (!m_chat->ready() || !home())
        return false;
    const QString base = m_chat->baseTo(key);
    if (base.isEmpty())
        return false;
    const QByteArray json = compact(m_chat->envelope(QStringLiteral("link"), key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    m_chat->post(base, json, std::move(done));
    return true;
}

QVariantMap Link::phone() const
{
    ensureLoaded();
    if (m_phone.key.isEmpty())
        return {};
    return {{QStringLiteral("key"), m_phone.key}, {QStringLiteral("name"), m_phone.name}, {QStringLiteral("ok"), m_phone.ok}};
}

QVariantList Link::companions() const
{
    ensureLoaded();
    QVariantList out;
    for (const Device &d : std::as_const(m_companions))
        out << QVariantMap{{QStringLiteral("key"), d.key}, {QStringLiteral("name"), d.name}, {QStringLiteral("ok"), d.ok}};
    return out;
}

bool Link::sealed() const
{
    ensureLoaded();
    return !m_phone.key.isEmpty() && m_phone.ok && !m_ownOpen;
}

bool Link::ownChats() const
{
    return m_chat->ready() && (!m_chat->m_contacts.isEmpty() || !m_chat->m_groups.isEmpty());
}

bool Link::openOwn(const QString &secret)
{
    ensureLoaded();
    if (!m_chat->ready())
        return false;
    // The app lock's own check (a wrong one counts as a wrong try, like on the lock screen).
    if (m_chat->m_vault->hasLock() && !m_chat->m_vault->unlock(secret))
        return false;
    m_ownOpen = true;
    Q_EMIT changed();
    return true;
}

void Link::closeOwn()
{
    if (!m_ownOpen)
        return;
    m_ownOpen = false;
    Q_EMIT changed();
}

QVariantList Link::unchecked() const
{
    ensureLoaded();
    QVariantList out;
    auto add = [&](const Device &d) {
        out << QVariantMap{{QStringLiteral("key"), d.key}, {QStringLiteral("name"), d.name},
                           {QStringLiteral("emoji"), Trust::emojiCode(ChatCrypto::sharedKey(m_chat->m_priv, m_chat->m_pub, QByteArray::fromBase64(d.key.toLatin1())))}};
    };
    for (const Device &d : std::as_const(m_companions))
        if (!d.ok)
            add(d);
    if (!m_phone.key.isEmpty() && !m_phone.ok)
        add(m_phone);
    return out;
}

void Link::confirm(const QString &key)
{
    ensureLoaded();
    for (Device &d : m_companions)
        if (d.key == key && !d.ok) {
            d.ok = true;
            save();
            const Device copy = d;
            syncAll(&copy);
            return;
        }
    if (m_phone.key == key && !m_phone.ok) {
        m_phone.ok = true;
        save();
        sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("sync")}});
    }
}

QVariantList Link::mirror() const
{
    ensureLoaded();
    QList<const Mirror *> all;
    for (const Mirror &m : std::as_const(m_mirror))
        all << &m;
    auto last = [](const Mirror *m) { return m->msgs.isEmpty() ? qint64(0) : m->msgs.last().time; };
    std::sort(all.begin(), all.end(), [&](const Mirror *a, const Mirror *b) { return last(a) > last(b); });
    QVariantList out;
    for (const Mirror *m : all)
        out << QVariantMap{{QStringLiteral("peer"), m->peer}, {QStringLiteral("name"), m->name},
                           {QStringLiteral("last"), m->msgs.isEmpty() ? QString() : (m->msgs.last().mine ? QStringLiteral("You: ") : QString()) + m->msgs.last().text.left(80)},
                           {QStringLiteral("time"), double(last(m))}};
    return out;
}

bool Link::haveMedia(const QString &hash) const
{
    return !hash.isEmpty() && QFile::exists(m_chat->m_vault->path(QStringLiteral("photos/") + mediaId(hash)));
}

QVariantList Link::messages(const QString &peer) const
{
    ensureLoaded();
    QVariantList out;
    for (const M &x : m_mirror.value(peer).msgs)
        out << QVariantMap{{QStringLiteral("id"), x.id}, {QStringLiteral("text"), x.text}, {QStringLiteral("mine"), x.mine}, {QStringLiteral("time"), double(x.time)},
                           {QStringLiteral("kind"), x.kind}, {QStringLiteral("media"), haveMedia(x.hash) ? mediaId(x.hash) : QString()},
                           {QStringLiteral("name"), x.name}, {QStringLiteral("size"), double(x.size)}};
    return out;
}

QString Link::saveMedia(const QString &peer, const QString &id) const
{
    ensureLoaded();
    for (const M &x : m_mirror.value(peer).msgs) {
        if (x.id != id || x.hash.isEmpty())
            continue;
        QByteArray bytes = m_chat->blob(mediaId(x.hash));
        if (bytes.isEmpty())
            return QStringLiteral("It isn't here yet. It comes over on your home Wi-Fi.");
        // Only a plain file name: never a folder, never a hidden file.
        QString name = QFileInfo(x.name).fileName().remove(QLatin1Char('\\')).trimmed();
        while (name.startsWith(QLatin1Char('.')))
            name.remove(0, 1);
        if (name.isEmpty())
            name = x.kind == QLatin1String("photo") ? QStringLiteral("photo.jpg") : QStringLiteral("file");
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
        QDir().mkpath(dir);
        const QFileInfo fi(name);
        QString path = QDir(dir).filePath(name);
        for (int n = 1; QFile::exists(path) && n < 1000; ++n)
            path = QDir(dir).filePath(fi.completeBaseName() + QStringLiteral(" (%1)").arg(n) + (fi.suffix().isEmpty() ? QString() : QStringLiteral(".") + fi.suffix()));
        QFile f(path);
        const bool ok = f.open(QIODevice::WriteOnly) && f.write(bytes) == bytes.size();
        bytes.fill(0);
        return ok ? QString() : QStringLiteral("Couldn't save it.");
    }
    return QStringLiteral("That message isn't here any more.");
}

// ------------------------------------------------------------------ pairing

QString Link::newCode()
{
    ensureLoaded();
    if (!m_chat->ready())
        return QString();
    m_token = QUuid::createUuid().toString(QUuid::Id128);
    m_tokenUntil = now() + kTokenMs;
    // The code shown before keeps working until its own 10 minutes are up (a phone may have scanned it just now).
    const qint64 t = now();
    m_tokens.removeIf([t](const QHash<QString, qint64>::iterator it) { return it.value() < t; });
    m_tokens.insert(m_token, m_tokenUntil);
    QString name = m_chat->username();
    const QJsonObject o{{QStringLiteral("k"), m_chat->myKey()}, {QStringLiteral("p"), m_chat->mailbox()}, {QStringLiteral("n"), name.left(40)},
                        {QStringLiteral("i"), m_token}};
    m_code = QString::fromLatin1(kPrefix) + QString::fromLatin1(compact(o).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
    Q_EMIT changed();
    return m_code;
}

bool Link::codeDue(qint64 t, qint64 until) { return t > until - kRefreshMs; }

QString Link::freshCode()
{
    if (m_code.isEmpty() || codeDue(now(), m_tokenUntil))
        return newCode();
    return m_code;
}

QJsonObject Link::readCode(const QString &text)
{
    const QString prefix = QString::fromLatin1(kPrefix);
    const int at = text.indexOf(prefix);
    if (at < 0)
        return {};
    QString body;
    for (const QChar c : text.mid(at + prefix.size())) {
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    const auto dec = QByteArray::fromBase64Encoding(body.toLatin1(), QByteArray::Base64UrlEncoding | QByteArray::AbortOnBase64DecodingErrors);
    if (!dec)
        return {};
    const QJsonObject o = QJsonDocument::fromJson(*dec).object();
    const QString k = jstr(o, "k");
    if (k.size() < 40 || k.size() > 200)
        return {};
    const ChatCrypto::KeyPair t = ChatCrypto::newKeyPair();
    if (ChatCrypto::sharedKey(t.privateDer, t.publicDer, QByteArray::fromBase64(k.toLatin1())).isEmpty())
        return {};
    return {{QStringLiteral("k"), k}, {QStringLiteral("p"), jstr(o, "p")}, {QStringLiteral("n"), jstr(o, "n").left(40)}, {QStringLiteral("i"), jstr(o, "i")}};
}

QString Link::link(const QString &text, bool confirmed)
{
    ensureLoaded();
    if (!m_chat->ready())
        return QStringLiteral("Unlock Huginn first.");
    if (text.indexOf(QString::fromLatin1(kPrefix)) < 0)
        return QStringLiteral("That isn't a link code. On the other device: Settings › Linked devices › Link my phone.");
    const QJsonObject o = readCode(text);
    if (o.isEmpty())
        return QStringLiteral("That link code is damaged.");
    const QString k = jstr(o, "k");
    if (k == m_chat->myKey())
        return QStringLiteral("That's this device's own code.");
    if (k == m_phone.key)
        return QStringLiteral("That device is the phone this one mirrors.");
    QString name = jstr(o, "n").trimmed();
    const Device d{k, jstr(o, "p"), name.isEmpty() ? QStringLiteral("Linked device") : name, confirmed};
    m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == k; }), m_companions.end());
    m_companions << d;
    save();
    sendTo(d, QJsonObject{{QStringLiteral("t"), QStringLiteral("hello")}, {QStringLiteral("i"), jstr(o, "i")}, {QStringLiteral("n"), m_chat->username()},
                          {QStringLiteral("p"), m_chat->mailbox()}});
    if (confirmed)
        syncAll(&d);
    return QString();
}

void Link::unlink(const QString &key)
{
    ensureLoaded();
    const QJsonObject bye{{QStringLiteral("t"), QStringLiteral("unlink")}};
    for (const Device &d : std::as_const(m_companions))
        if (d.key == key)
            sendTo(d, bye);
    m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == key; }), m_companions.end());
    // Files waiting for it are dropped (one already going stops at its next part, which isn't taken any more).
    for (qsizetype i = m_serve.size() - 1; i >= (m_serveAt >= 0 ? 1 : 0); --i)
        if (m_serve.at(i).to == key)
            m_serve.removeAt(i);
    if (!m_phone.key.isEmpty() && m_phone.key == key) {
        sendTo(m_phone, bye);
        m_phone = Device();
        m_ownOpen = false;
        m_mirror.clear();
        saveMirror();
        dropMedia();
    }
    save();
}

// ------------------------------------------------------------------ the phone's side

Link::Sum Link::sumOf(const QString &photoId)
{
    if (photoId.isEmpty())
        return {};
    const auto hit = m_sums.constFind(photoId);
    if (hit != m_sums.cend())
        return *hit;
    QByteArray bytes = m_chat->blob(photoId);
    if (bytes.isEmpty() || bytes.size() > kMediaMax)
        return {};
    const Sum s{sha256(bytes), bytes.size()};
    bytes.fill(0);
    m_sums.insert(photoId, s);
    return s;
}

// The photo id of a file the companions were told about (only in a contact's 1:1 chat), or "".
QString Link::photoWith(const QString &hash)
{
    for (auto it = m_sums.cbegin(); it != m_sums.cend(); ++it)
        if (it.value().hash == hash)
            return it.key();
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it) {
        if (!m_chat->isContact(it.key()) || m_chat->isTrashed(it.key()))
            continue;
        for (const QVariant &v : it.value()) {
            const QString p = vstr(v.toMap(), "photo");
            if (!p.isEmpty() && vstr(v.toMap(), "unsent").isEmpty() && !m_sums.contains(p) && sumOf(p).hash == hash)
                return p;
        }
    }
    return QString();
}

QJsonObject Link::mJson(const QVariantMap &m, int max)
{
    QString x, kind, name;
    const QString photo = vstr(m, "photo");
    if (!vstr(m, "unsent").isEmpty()) {
        x = QStringLiteral("Message deleted");
    } else if (m.value(QStringLiteral("voice")).toInt() > 0) {
        x = QStringLiteral("Voice message");
        kind = QStringLiteral("voice");
        name = QStringLiteral("voice.aac");
    } else if (m.value(QStringLiteral("video")).toInt() > 0) {
        x = QStringLiteral("Video message");
        kind = QStringLiteral("video");
        name = QStringLiteral("video.mp4");
    } else if (!vstr(m, "file").isEmpty()) {
        x = QStringLiteral("File: ") + vstr(m, "file");
        kind = QStringLiteral("file");
        name = vstr(m, "file");
    } else if (!photo.isEmpty()) {
        x = QStringLiteral("Photo");
        kind = QStringLiteral("photo");
        name = QStringLiteral("photo.jpg");
    } else {
        x = vstr(m, "text");
    }
    QJsonObject o{{QStringLiteral("id"), vstr(m, "id")}, {QStringLiteral("x"), x.left(max)}, {QStringLiteral("me"), m.value(QStringLiteral("mine")).toBool()},
                  {QStringLiteral("t"), double(m.value(QStringLiteral("time")).toLongLong())}};
    // The file itself stays here until a companion asks for it on the home Wi-Fi.
    if (!kind.isEmpty() && !photo.isEmpty()) {
        const Sum s = sumOf(photo);
        if (!s.hash.isEmpty())
            o.insert(QStringLiteral("f"), QJsonObject{{QStringLiteral("h"), s.hash}, {QStringLiteral("s"), double(s.size)}, {QStringLiteral("k"), kind},
                                                      {QStringLiteral("nm"), name.left(255)}});
    }
    return o;
}

QList<QJsonArray> Link::pages(const QJsonArray &msgs)
{
    QList<QJsonArray> out;
    QJsonArray page;
    qsizetype bytes = 0;
    for (const QJsonValue &v : msgs) {
        const qsizetype size = compact(v.toObject()).size() + 1;
        if (!page.isEmpty() && (page.size() >= kPageMsgs || bytes + size > kPageBytes)) {
            out << page;
            page = QJsonArray();
            bytes = 0;
        }
        page.append(v);
        bytes += size;
    }
    if (!page.isEmpty())
        out << page;
    return out;
}

// Every message each chat still keeps (within its limit), oldest first, in pages, to one device (or to every companion).
void Link::syncAll(const Device *only)
{
    QList<Device> to;
    for (const Device &d : only ? QList<Device>{*only} : m_companions)
        if (d.ok)
            to << d;
    if (to.isEmpty())
        return;
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it) {
        const QString peer = it.key();
        if (it.value().isEmpty() || !m_chat->isContact(peer) || m_chat->isTrashed(peer))
            continue;
        QJsonArray msgs;
        for (const QVariant &v : it.value()) {
            const QVariantMap m = v.toMap();
            if (!m.value(QStringLiteral("system")).toBool())
                msgs.append(mJson(m, kText));
        }
        const QList<QJsonArray> all = pages(msgs);
        for (qsizetype i = 0; i < all.size(); ++i) {
            const QJsonObject json{{QStringLiteral("t"), QStringLiteral("chat")}, {QStringLiteral("peer"), peer}, {QStringLiteral("n"), m_chat->nameOf(peer)},
                                   {QStringLiteral("msgs"), all.at(i)}, {QStringLiteral("d"), m_chat->chatDays(peer)},
                                   {QStringLiteral("pg"), int(i)}, {QStringLiteral("pgs"), int(all.size())}};
            for (const Device &d : to)
                sendTo(d, json);
        }
    }
}

void Link::onMessage(const QString &peer, const QVariantMap &m)
{
    ensureLoaded();
    if (m_companions.isEmpty() || !m_chat->isContact(peer) || m.value(QStringLiteral("system")).toBool())
        return;
    const QJsonObject json{{QStringLiteral("t"), QStringLiteral("msg")}, {QStringLiteral("peer"), peer}, {QStringLiteral("n"), m_chat->nameOf(peer)},
                           {QStringLiteral("m"), mJson(m, kText)}, {QStringLiteral("d"), m_chat->chatDays(peer)}};
    for (const Device &d : std::as_const(m_companions))
        if (d.ok)
            sendTo(d, json);
}

int Link::partsFor(qint64 size)
{
    if (size <= 0 || size > kMediaMax)
        return 0;
    return int((size + kPart - 1) / kPart);
}

bool Link::lanOnly(const QString &via, bool homeNet) { return homeNet && via == QLatin1String("wifi"); }

bool Link::mediaOk(const QByteArray &bytes, const QString &hash, qint64 size)
{
    return size > 0 && bytes.size() == size && hexOk(hash) && sha256(bytes) == hash;
}

// One file at a time, part after part, each only once the one before was taken.
void Link::serveNext()
{
    if (m_serveAt >= 0)
        return;
    while (!m_serve.isEmpty()) {
        m_serveBytes = m_chat->blob(m_serve.first().photo);
        if (partsFor(m_serveBytes.size()) > 0) {
            m_serveAt = 0;
            servePart();
            return;
        }
        m_serveBytes.clear();
        m_serve.removeFirst();
    }
}

void Link::servePart()
{
    if (m_serve.isEmpty()) {
        m_serveAt = -1;
        return;
    }
    const Serve s = m_serve.first();
    const int n = partsFor(m_serveBytes.size());
    auto finish = [this] {
        m_serveBytes.fill(0);
        m_serveBytes.clear();
        if (!m_serve.isEmpty())
            m_serve.removeFirst();
        m_serveAt = -1;
        QTimer::singleShot(0, this, &Link::serveNext);
    };
    if (m_serveAt >= n) {
        finish();
        return;
    }
    const QJsonObject part{{QStringLiteral("t"), QStringLiteral("part")}, {QStringLiteral("h"), s.hash}, {QStringLiteral("i"), m_serveAt}, {QStringLiteral("n"), n},
                           {QStringLiteral("b"), QString::fromLatin1(m_serveBytes.mid(qsizetype(m_serveAt) * kPart, kPart).toBase64())}};
    const bool sent = sendLan(s.to, part, [this, finish](int code) {
        if (code != 200) {
            finish();   // off the home Wi-Fi, or the companion stopped asking: it asks again later
            return;
        }
        m_serveAt++;
        servePart();
    });
    if (!sent)
        finish();
}

// ------------------------------------------------------------------ the companion's side of media

void Link::want(const M &m)
{
    if (m.hash.isEmpty() || m_failed.contains(m.hash) || partsFor(m.size) == 0 || haveMedia(m.hash))
        return;
    m_want.insert(m.hash);
    m_sizes.insert(m.hash, m.size);
}

void Link::fetchNext()
{
    // One file at a time; one that stopped coming for a minute is asked for again later.
    if (!m_fetching.isEmpty()) {
        if (now() - m_fetchAt < 60000)
            return;
        m_fetching.clear();
        m_parts.clear();
    }
    if (m_phone.key.isEmpty() || !m_phone.ok)
        return;
    for (auto it = m_want.begin(); it != m_want.end();) {
        if (haveMedia(*it) || m_failed.contains(*it))
            it = m_want.erase(it);
        else
            ++it;
    }
    if (m_want.isEmpty())
        return;
    const QString h = *m_want.cbegin();
    if (!sendLan(m_phone.key, QJsonObject{{QStringLiteral("t"), QStringLiteral("get")}, {QStringLiteral("h"), h}}))
        return;   // not on the home Wi-Fi with the phone right now
    m_fetching = h;
    m_fetchAt = now();
    m_parts = QList<QByteArray>(partsFor(m_sizes.value(h)));
    m_partsHave = 0;
}

// Files no message here names any more (past the limit, or the phone was unlinked) are deleted.
void Link::dropMedia()
{
    QSet<QString> keep;
    for (const Mirror &c : std::as_const(m_mirror))
        for (const M &x : c.msgs)
            if (!x.hash.isEmpty())
                keep.insert(mediaId(x.hash));
    const QStringList files = QDir(m_chat->m_vault->path(QStringLiteral("photos"))).entryList({QStringLiteral("lm-*")}, QDir::Files);
    for (const QString &f : files)
        if (!keep.contains(f))
            m_chat->m_vault->remove(QStringLiteral("photos/") + f);
    if (m_phone.key.isEmpty()) {
        m_want.clear();
        m_fetching.clear();
        m_parts.clear();
    }
}

// ------------------------------------------------------------------ room "link", either side

// The phone's limit for a chat (cap d1), 7 to 30 days; fallback when it says nothing.
int Link::daysOf(const QJsonObject &o, int fallback)
{
    if (!o.contains(QStringLiteral("d")))
        return fallback;
    return qBound(Limits::kDefault, o.value(QStringLiteral("d")).toInt(Limits::kDefault), Limits::kLongest);
}

void Link::prune(qint64 t)
{
    ensureLoaded();
    bool changedAny = false;
    for (Mirror &m : m_mirror) {
        const qint64 cut = Limits::cutoff(t, m.days);
        const qsizetype before = m.msgs.size();
        m.msgs.erase(std::remove_if(m.msgs.begin(), m.msgs.end(), [cut](const M &x) { return x.time < cut; }), m.msgs.end());
        changedAny = changedAny || m.msgs.size() != before;
    }
    if (changedAny) {
        saveMirror();
        dropMedia();
    }
}

Link::M Link::mOf(const QJsonObject &o)
{
    M m{jstr(o, "id"), jstr(o, "x").left(kText), o.value(QStringLiteral("me")).toBool(), qint64(o.value(QStringLiteral("t")).toDouble()), {}, {}, {}, 0};
    const QJsonObject f = o.value(QStringLiteral("f")).toObject();
    const QString h = jstr(f, "h"), k = jstr(f, "k");
    const double s = f.value(QStringLiteral("s")).toDouble();
    if (hexOk(h) && kindOk(k) && s >= 1 && s <= double(kMediaMax)) {
        m.hash = h;
        m.kind = k;
        m.size = qint64(s);
        m.name = jstr(f, "nm").left(255);
    }
    return m;
}

void Link::merge(QList<M> &into, const QList<M> &page)
{
    for (const M &x : page) {
        if (x.id.isEmpty())
            continue;
        const auto at = std::find_if(into.begin(), into.end(), [&](const M &y) { return y.id == x.id; });
        if (at != into.end())
            *at = x;
        else
            into << x;
    }
    std::stable_sort(into.begin(), into.end(), [](const M &a, const M &b) { return a.time < b.time; });
}

bool Link::receive(const QString &from, const QJsonObject &p, const QString &via)
{
    ensureLoaded();
    if (!m_chat->ready())
        return false;
    const QString t = jstr(p, "t");
    const bool comp = std::any_of(m_companions.cbegin(), m_companions.cend(), [&](const Device &d) { return d.key == from; });
    const bool ph = !m_phone.key.isEmpty() && m_phone.key == from;
    if (t == QLatin1String("hello") && !comp && !ph) {
        // Pairing: a phone answers my link code with its token, in time and only once.
        const QString token = jstr(p, "i");
        if (token.isEmpty() || !m_tokens.contains(token) || now() > m_tokens.value(token))
            return false;
        m_tokens.clear();   // once: every code shown so far stops working
        m_token.clear();
        m_code.clear();
        m_tokenUntil = 0;
        const QString n = jstr(p, "n").left(40).trimmed();
        const QString mb = jstr(p, "p");
        m_phone = Device{from, mb.size() == 64 ? mb : QString(), n.isEmpty() ? QStringLiteral("Your phone") : n, false};
        m_ownOpen = false;
        m_mirror.clear();
        save();
        saveMirror();
        dropMedia();
        return true;
    }
    if (t == QLatin1String("unlink") && (comp || ph)) {
        if (comp)
            m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == from; }), m_companions.end());
        if (ph) {
            Q_EMIT m_chat->notice(QStringLiteral("%1 unlinked this device.").arg(m_phone.name));
            m_phone = Device();
            m_ownOpen = false;
            m_mirror.clear();
            saveMirror();
            dropMedia();
        }
        save();
        return true;
    }
    // Nothing goes either way until this screen's emoji were checked.
    const bool okHere = ph ? m_phone.ok : std::any_of(m_companions.cbegin(), m_companions.cend(), [&](const Device &d) { return d.key == from && d.ok; });
    if (!okHere && (comp || ph))
        return t == QLatin1String("chat") || t == QLatin1String("msg") || t == QLatin1String("send") || t == QLatin1String("sync");
    if (ph && t == QLatin1String("chat")) {
        // A page of a chat's history: merged by message id, so a later page never wipes an earlier one.
        const QString peer = jstr(p, "peer");
        if (peer.isEmpty())
            return true;
        QList<M> page;
        for (const QJsonValue &v : p.value(QStringLiteral("msgs")).toArray())
            page << mOf(v.toObject());
        const bool known = m_mirror.contains(peer);
        Mirror &c = m_mirror[peer];
        c.peer = peer;
        c.days = daysOf(p, known ? c.days : Limits::kDefault);
        const QString n = jstr(p, "n").left(40);
        if (!n.isEmpty())
            c.name = n;
        if (c.name.isEmpty())
            c.name = QStringLiteral("Someone");
        merge(c.msgs, page);
        for (const M &x : std::as_const(page))
            want(x);
        saveMirror();
        prune(now());
        fetchNext();
        return true;
    }
    if (ph && t == QLatin1String("msg")) {
        const QString peer = jstr(p, "peer");
        const QJsonObject mo = p.value(QStringLiteral("m")).toObject();
        if (peer.isEmpty() || mo.isEmpty())
            return true;
        const M x = mOf(mo);
        const bool known = m_mirror.contains(peer);
        Mirror &c = m_mirror[peer];
        c.peer = peer;
        c.days = daysOf(p, known ? c.days : Limits::kDefault);   // no "d": the last one stays
        const QString n = jstr(p, "n").left(40);
        if (!n.isEmpty())
            c.name = n;
        if (c.name.isEmpty())
            c.name = QStringLiteral("Someone");
        merge(c.msgs, {x});
        saveMirror();
        want(x);
        fetchNext();
        if (!x.mine)
            Q_EMIT m_chat->arrived(c.name, x.text.left(60), QStringLiteral("mirror:") + peer);
        return true;
    }
    if (ph && t == QLatin1String("part")) {
        // Only on the home Wi-Fi, only the file asked for, in its own size of parts.
        if (!lanOnly(via, home()))
            return true;   // dropped quietly
        const QString h = jstr(p, "h");
        const int n = partsFor(m_sizes.value(h));
        const int i = p.value(QStringLiteral("i")).toInt(-1);
        if (h.isEmpty() || h != m_fetching || n == 0 || p.value(QStringLiteral("n")).toInt() != n || i < 0 || i >= n || m_parts.size() != n)
            return false;
        const auto b = QByteArray::fromBase64Encoding(jstr(p, "b").toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
        const qint64 size = m_sizes.value(h);
        const qint64 expect = i < n - 1 ? kPart : size - qint64(kPart) * (n - 1);
        if (!b || b->size() != expect) {
            m_failed.insert(h);
            m_want.remove(h);
            m_fetching.clear();
            m_parts.clear();
            return false;
        }
        m_fetchAt = now();
        if (m_parts.at(i).isEmpty()) {
            m_parts[i] = *b;
            m_partsHave++;
        }
        if (m_partsHave < n)
            return true;
        QByteArray all = m_parts.join();
        m_parts.clear();
        m_fetching.clear();
        m_want.remove(h);
        if (mediaOk(all, h, size))
            m_chat->m_vault->write(QStringLiteral("photos/") + mediaId(h), all);
        else
            m_failed.insert(h);   // not the file named: never kept
        all.fill(0);
        Q_EMIT changed();
        QTimer::singleShot(0, this, &Link::fetchNext);
        return true;
    }
    if (comp && t == QLatin1String("get")) {
        // A file for a companion: only asked and sent on the home Wi-Fi, never over the internet.
        const QString h = jstr(p, "h");
        if (!lanOnly(via, home()) || !hexOk(h))
            return true;
        const QString photo = photoWith(h);
        if (photo.isEmpty())
            return true;
        const bool queued = std::any_of(m_serve.cbegin(), m_serve.cend(), [&](const Serve &s) { return s.to == from && s.hash == h; });
        if (!queued && m_serve.size() < 50)
            m_serve << Serve{from, h, photo};
        serveNext();
        return true;
    }
    if (comp && t == QLatin1String("send")) {
        // The phone sends it as its own message, only to one of its contacts.
        // Each "send" once, by its own id: the same one again (a copy, or one sent on) never goes out twice.
        const QString peer = jstr(p, "peer"), text = jstr(p, "text").trimmed();
        if (!text.isEmpty() && m_chat->isContact(peer) && firstSend(jstr(p, "id")))
            m_chat->sendPrivate(peer, text.left(Chat::kMax));
        return true;
    }
    if (comp && t == QLatin1String("sync")) {
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from) {
                const Device copy = d;
                syncAll(&copy);
            }
        return true;
    }
    return comp || ph;
}

// ------------------------------------------------------------------ the companion's side

bool Link::send(const QString &peer, const QString &text)
{
    ensureLoaded();
    const QString t = text.trimmed();
    if (m_phone.key.isEmpty() || t.isEmpty())
        return false;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}, {QStringLiteral("peer"), peer}, {QStringLiteral("text"), t.left(Chat::kMax)},
                                {QStringLiteral("id"), QUuid::createUuid().toString(QUuid::WithoutBraces)}});
    return true;
}

void Link::sync()
{
    ensureLoaded();
    if (!m_phone.key.isEmpty())
        sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("sync")}});
}

// ------------------------------------------------------------------ self-test (shared/vectors/i1f1-vectors.json "link")

bool Link::selfTest(const QJsonObject &v)
{
    int bad = 0;
    auto check = [&bad](bool ok, const char *what) {
        if (!ok) {
            bad++;
            std::fprintf(stderr, "link FAIL: %s\n", what);
        }
    };
    const QJsonObject l = v.value(QStringLiteral("link")).toObject();
    for (const QJsonValue &x : l.value(QStringLiteral("good")).toArray()) {
        const QJsonObject c = x.toObject();
        if (readCode(jstr(c, "code")) != c.value(QStringLiteral("read")).toObject()) {
            bad++;
            std::fprintf(stderr, "link FAIL: %s\n", qPrintable(jstr(c, "code")));
        }
    }
    for (const QJsonValue &x : l.value(QStringLiteral("bad")).toArray())
        if (!readCode(x.toString()).isEmpty()) {
            bad++;
            std::fprintf(stderr, "link FAIL: should refuse %s\n", qPrintable(x.toString()));
        }

    // Paging and merging: pages of at most 100 messages and 48 KB, merged by id without losing any.
    QJsonArray small, big;
    for (int i = 0; i < 250; ++i)
        small.append(QJsonObject{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("x"), QStringLiteral("hi")}, {QStringLiteral("me"), false},
                                 {QStringLiteral("t"), double(1000 + i)}});
    for (int i = 0; i < 60; ++i)
        big.append(QJsonObject{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("x"), QString(kText, QLatin1Char('a'))}, {QStringLiteral("me"), true},
                               {QStringLiteral("t"), double(1000 + i)}});
    const QList<QJsonArray> sp = pages(small), bp = pages(big);
    check(sp.size() == 3 && sp.at(0).size() == 100 && sp.at(2).size() == 50, "250 messages make pages of 100, 100 and 50");
    bool under = bp.size() > 1;
    int count = 0;
    for (const QJsonArray &a : bp) {
        under = under && compact(a).size() <= kPageBytes + a.size();
        count += int(a.size());
    }
    check(under && count == 60, "long messages make pages under 48 KB, none lost");
    QList<M> chat;
    for (int k = int(sp.size()) - 1; k >= 0; --k) {   // pages in any order
        QList<M> page;
        for (const QJsonValue &x : sp.at(k))
            page << mOf(x.toObject());
        merge(chat, page);
    }
    check(chat.size() == 250 && chat.first().id == QLatin1String("0") && chat.last().id == QLatin1String("249"), "pages merge by id, oldest first");
    QList<M> again{mOf(QJsonObject{{QStringLiteral("id"), QStringLiteral("5")}, {QStringLiteral("x"), QStringLiteral("edited")}, {QStringLiteral("t"), 1005.0}})};
    merge(chat, again);
    merge(chat, {});
    check(chat.size() == 250 && chat.at(5).text == QLatin1String("edited"), "a later copy replaces, an empty page wipes nothing");

    // A file's parts: its size and SHA-256 must match what the message named.
    const QByteArray file("raven", 5);
    const QString h = sha256(file);
    check(mediaOk(file, h, 5), "the right file is kept");
    check(!mediaOk(QByteArray("ravem", 5), h, 5), "a changed file is refused");
    check(!mediaOk(file, h, 6), "a file of another size is refused");
    check(!mediaOk(file, h.toUpper(), 5), "the SHA-256 is lowercase hex");
    const M f = mOf(QJsonObject{{QStringLiteral("id"), QStringLiteral("p")}, {QStringLiteral("x"), QStringLiteral("Photo")},
                                {QStringLiteral("f"), QJsonObject{{QStringLiteral("h"), h}, {QStringLiteral("s"), 5}, {QStringLiteral("k"), QStringLiteral("photo")}, {QStringLiteral("nm"), QStringLiteral("a.jpg")}}}});
    check(f.hash == h && f.kind == QLatin1String("photo") && f.size == 5, "a message names its file");
    const M g = mOf(QJsonObject{{QStringLiteral("id"), QStringLiteral("q")},
                                {QStringLiteral("f"), QJsonObject{{QStringLiteral("h"), h}, {QStringLiteral("s"), double(kMediaMax + 1)}, {QStringLiteral("k"), QStringLiteral("photo")}}}});
    check(g.hash.isEmpty(), "a file over 100 MB is never asked for");
    check(partsFor(1) == 1 && partsFor(kPart) == 1 && partsFor(kPart + 1) == 2 && partsFor(0) == 0 && partsFor(kMediaMax + 1) == 0, "parts of 192 KB");

    // Media only on the home Wi-Fi.
    check(lanOnly(QStringLiteral("wifi"), true), "the home Wi-Fi carries media");
    check(!lanOnly(QStringLiteral("internet"), true) && !lanOnly(QStringLiteral("bluetooth"), true), "the internet and Bluetooth never carry media");
    check(!lanOnly(QStringLiteral("wifi"), false), "Direct connect never carries media");

    // The code on show is replaced a minute before it runs out.
    const qint64 until = 10000000;
    check(!codeDue(until - kRefreshMs - 1, until) && codeDue(until - kRefreshMs + 1, until) && codeDue(until + 1, until), "a new code a minute before the old one runs out");
    return bad == 0;
}
