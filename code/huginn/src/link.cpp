#include "link.h"
#include "busy.h"
#include "chat.h"
#include "chatcrypto.h"
#include "msglimits.h"
#include "profile.h"
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
#include <QPointer>
#include <QThread>
#include <QUuid>
#include <algorithm>
#include <memory>
#include <utility>
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
            m_sums.clear();
            m_noSum.clear();
            m_sumsLoaded = false;
            m_afterSums.clear();
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
    // The phone's side: my profile goes to the companions a moment after it changes.
    m_meSoon.setSingleShot(true);
    m_meSoon.setInterval(600);
    connect(&m_meSoon, &QTimer::timeout, this, [this] { sendMe(false); });
    connect(m_chat, &Chat::changed, &m_meSoon, [this] {
        if (m_loaded && !m_companions.isEmpty())
            m_meSoon.start();
    });
    // A companion asks for what's missing every half minute while it's on the home Wi-Fi (one file at a time); the
    // phone sends history and pictures that waited for the home Wi-Fi.
    m_fetchTimer.setInterval(30000);
    connect(&m_fetchTimer, &QTimer::timeout, this, [this] {
        if (!m_chat->ready())
            return;
        Busy::Scope busy("linked phone");
        ensureLoaded();
        lanTick();
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
    m_meName.clear();
    m_meStatus.clear();
    m_meCustom.clear();
    m_mePic.clear();
    if (!m_phone.key.isEmpty()) {
        const QVariantMap me = readMe(o.value(QStringLiteral("me")).toObject());
        m_meName = vstr(me, "name");
        m_meStatus = vstr(me, "status");
        m_meCustom = vstr(me, "custom");
        m_mePic = me.value(QStringLiteral("pic")).toByteArray();
    }
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
    if (!m_phone.key.isEmpty()) {
        o.insert(QStringLiteral("phone"), devJson(m_phone));
        if (!m_meName.isEmpty()) {
            QJsonObject me{{QStringLiteral("t"), QStringLiteral("me")}, {QStringLiteral("n"), m_meName}, {QStringLiteral("st"), m_meStatus}};
            if (!m_meCustom.isEmpty())
                me.insert(QStringLiteral("cs"), m_meCustom);
            if (!m_mePic.isEmpty())
                me.insert(QStringLiteral("pic"), QString::fromLatin1(m_mePic.toBase64()));
            o.insert(QStringLiteral("me"), me);
        }
    }
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
    // Never history or a picture over the internet: those only on the home Wi-Fi (dropped when it isn't there now;
    // the callers keep what they owe).
    if (!internetOk(msg)) {
        sendLan(d.key, msg);
        return;
    }
    // Sealed like a private message: Bluetooth, the Wi-Fi and at most 2 relays.
    m_chat->sendLink(d.key, msg, d.mailbox.size() == 64 ? d.mailbox : QString());
}

bool Link::internetOk(const QJsonObject &msg)
{
    const QString t = jstr(msg, "t");
    if (t == QLatin1String("me"))
        return !msg.contains(QStringLiteral("pic"));
    return t == QLatin1String("msg") || t == QLatin1String("send") || t == QLatin1String("hello") || t == QLatin1String("unlink")
        || t == QLatin1String("result") || t == QLatin1String("sync") || t == QLatin1String("setme");
}

QString Link::errText(const QString &err)
{
    if (err == QLatin1String("confirm"))
        return QStringLiteral("Confirm the emoji on your phone");
    if (err == QLatin1String("not a contact"))
        return QStringLiteral("Not a contact on your phone");
    return QStringLiteral("Didn't send");
}

bool Link::lanHere(const QString &key) const
{
    return m_chat->ready() && home() && !key.isEmpty() && !m_chat->baseTo(key).isEmpty();
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
    // This device's own chats, groups, folders and contacts stay on show beside a linked phone's chats.
    return false;
}

QVariantMap Link::me() const
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_meName.isEmpty())
        return {};
    return {{QStringLiteral("name"), m_meName}, {QStringLiteral("status"), m_meStatus}, {QStringLiteral("custom"), m_meCustom},
            {QStringLiteral("picture"), !m_mePic.isEmpty()}};
}

QByteArray Link::mePicture() const
{
    ensureLoaded();
    return m_phone.key.isEmpty() || !m_phone.ok ? QByteArray() : m_mePic;
}

QString Link::meStatus() const
{
    ensureLoaded();
    return m_phone.key.isEmpty() || !m_phone.ok || m_meName.isEmpty() ? QString() : m_meStatus;
}

QVariantMap Link::readMe(const QJsonObject &p)
{
    static const QStringList statuses{QStringLiteral("online"), QStringLiteral("away"), QStringLiteral("dnd"), QStringLiteral("invisible")};
    const QString n = jstr(p, "n").simplified().left(40);
    if (n.isEmpty())
        return {};
    const QString st = statuses.contains(jstr(p, "st")) ? jstr(p, "st") : QStringLiteral("online");
    QByteArray pic;
    const QString b = jstr(p, "pic");
    if (!b.isEmpty()) {
        const auto d = QByteArray::fromBase64Encoding(b.toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
        if (d.decodingStatus == QByteArray::Base64DecodingStatus::Ok && Profile::pictureOk(d.decoded))
            pic = d.decoded;   // anything else: no picture
    }
    return {{QStringLiteral("name"), n}, {QStringLiteral("status"), st}, {QStringLiteral("custom"), Profile::cleanCustom(jstr(p, "cs"))},
            {QStringLiteral("pic"), pic}};
}

QString Link::setMe(const QString &name, const QString &status, const QString &custom)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok)
        return QStringLiteral("No phone is linked.");
    const QVariantMap me = readMe(QJsonObject{{QStringLiteral("n"), name.isEmpty() ? m_meName : name},
                                              {QStringLiteral("st"), status.isEmpty() ? m_meStatus : status}, {QStringLiteral("cs"), custom}});
    if (me.isEmpty())
        return QStringLiteral("Give it a name.");
    m_meName = vstr(me, "name");
    m_meStatus = vstr(me, "status");
    m_meCustom = vstr(me, "custom");
    save();
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("setme")}, {QStringLiteral("n"), m_meName}, {QStringLiteral("st"), m_meStatus},
                                {QStringLiteral("cs"), m_meCustom}});
    return QString();
}

void Link::sendMe(bool force)
{
    ensureLoaded();
    if (!myProfile || !m_chat->ready())
        return;
    QList<Device> to;
    for (const Device &d : std::as_const(m_companions))
        if (d.ok)
            to << d;
    if (to.isEmpty())
        return;
    QJsonObject me = myProfile();
    me.insert(QStringLiteral("t"), QStringLiteral("me"));
    const QByteArray bytes = compact(me);
    if (!force && bytes == m_meSent)
        return;
    m_meSent = bytes;
    for (const Device &d : std::as_const(to))
        sendMeTo(d, me);
}

// "me" with its picture only on the home Wi-Fi; away from it the rest goes now ("pk": keep the picture you have) and the
// picture follows once both are on it again (lanTick).
void Link::sendMeTo(const Device &d, const QJsonObject &me)
{
    if (!me.contains(QStringLiteral("pic"))) {
        m_meOwed.remove(d.key);
        sendTo(d, me);
        return;
    }
    if (lanHere(d.key) && sendLan(d.key, me)) {
        m_meOwed.remove(d.key);
        return;
    }
    QJsonObject small = me;
    small.remove(QStringLiteral("pic"));
    small.insert(QStringLiteral("pk"), true);
    sendTo(d, small);
    m_meOwed.insert(d.key);
}

void Link::lanTick()
{
    // Not while file sums are still being worked out: the history waiting on them goes by itself once they are.
    if (m_companions.isEmpty() || !m_hashing.isEmpty())
        return;
    const QList<Device> all = m_companions;
    for (const Device &d : all) {
        if (!d.ok || !lanHere(d.key))
            continue;
        if (m_owed.contains(d.key))
            syncAll(&d);
        if (m_meOwed.contains(d.key) && myProfile) {
            QJsonObject me = myProfile();
            me.insert(QStringLiteral("t"), QStringLiteral("me"));
            sendMeTo(d, me);
        }
    }
}

void Link::forgetPhone()
{
    m_phone = Device();
    m_ownOpen = false;
    m_mirror.clear();
    m_meName.clear();
    m_meStatus.clear();
    m_meCustom.clear();
    m_mePic.clear();
    m_pending.clear();
    m_askedAt = 0;
    saveMirror();
    dropMedia();
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
            sendMe(true);
            return;
        }
    if (m_phone.key == key && !m_phone.ok) {
        m_phone.ok = true;
        save();
        // "f": the first one after the emoji, which the phone takes even inside the 10 minutes.
        sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("sync")}, {QStringLiteral("f"), true}});
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
    for (const Pending &p : m_pending)
        if (p.peer == peer)
            out << QVariantMap{{QStringLiteral("id"), p.id}, {QStringLiteral("text"), p.text}, {QStringLiteral("mine"), true}, {QStringLiteral("time"), double(p.time)},
                               {QStringLiteral("kind"), QString()}, {QStringLiteral("media"), QString()}, {QStringLiteral("name"), QString()},
                               {QStringLiteral("size"), 0.0}, {QStringLiteral("pending"), true}, {QStringLiteral("failed"), !p.err.isEmpty()},
                               {QStringLiteral("err"), p.err}};
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
        // Only a plain file name: never a folder, a hidden file or a name Windows keeps for itself.
        const QString name = safeName(x.name, x.kind == QLatin1String("photo") ? QStringLiteral("photo.jpg") : QStringLiteral("file"));
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

QString Link::safeName(const QString &raw, const QString &fallback)
{
    QString name;
    for (const QChar c : raw)
        name += (c.unicode() < 0x20 || c.unicode() == 0x7f || QStringLiteral("<>:\"/\\|?*").contains(c)) ? QChar(QLatin1Char('_')) : c;
    // At most kNameMax characters, keeping a short extension.
    constexpr int kNameMax = 120;
    if (name.size() > kNameMax) {
        const qsizetype dot = name.lastIndexOf(QLatin1Char('.'));
        const QString ext = dot > 0 && name.size() - dot <= 16 ? name.mid(dot) : QString();
        QString stem = name.left(kNameMax - ext.size());
        if (!stem.isEmpty() && stem.back().isHighSurrogate())
            stem.chop(1);
        name = stem + ext;
    }
    name = name.trimmed();
    while (name.startsWith(QLatin1Char('.')))
        name.remove(0, 1);
    // Windows refuses names ending in a dot or a space.
    while (name.endsWith(QLatin1Char('.')) || name.endsWith(QLatin1Char(' ')))
        name.chop(1);
    if (name.isEmpty())
        name = fallback;
    // CON, PRN, AUX, NUL, COM1 to COM9 and LPT1 to LPT9, with or without an extension, in any case.
    const QString stem = name.section(QLatin1Char('.'), 0, 0).trimmed().toUpper();
    const bool numbered = stem.size() == 4 && (stem.startsWith(QLatin1String("COM")) || stem.startsWith(QLatin1String("LPT")))
                          && stem.at(3) >= QLatin1Char('1') && stem.at(3) <= QLatin1Char('9');
    if (stem == QLatin1String("CON") || stem == QLatin1String("PRN") || stem == QLatin1String("AUX") || stem == QLatin1String("NUL") || numbered)
        name.prepend(QLatin1Char('_'));
    return name;
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
    if (dec.decodingStatus != QByteArray::Base64DecodingStatus::Ok)
        return {};
    const QJsonObject o = QJsonDocument::fromJson(dec.decoded).object();
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
    if (confirmed) {
        syncAll(&d);
        sendMe(true);
    }
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
    m_owed.remove(key);
    m_meOwed.remove(key);
    // Files waiting for it are dropped (one already going stops at its next part, which isn't taken any more).
    for (qsizetype i = m_serve.size() - 1; i >= (m_serveAt >= 0 ? 1 : 0); --i)
        if (m_serve.at(i).to == key)
            m_serve.removeAt(i);
    if (!m_phone.key.isEmpty() && m_phone.key == key) {
        sendTo(m_phone, bye);
        forgetPhone();
    }
    save();
}

// ------------------------------------------------------------------ the phone's side

void Link::loadSums()
{
    if (m_sumsLoaded || !m_chat->ready())
        return;
    m_sumsLoaded = true;
    const QJsonObject o = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link-sums.json"))).object();
    for (auto it = o.begin(); it != o.end(); ++it) {
        const QJsonObject v = it.value().toObject();
        const QString h = jstr(v, "h");
        const double size = v.value(QStringLiteral("s")).toDouble();
        if (hexOk(h) && size >= 1 && size <= double(kMediaMax))
            m_sums.insert(it.key(), Sum{h, qint64(size)});
    }
}

void Link::saveSums(QSet<QString> live)
{
    if (!m_chat->ready())
        return;
    // Only photos the chats still have (and the ones just worked out).
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it)
        for (const QVariant &v : it.value()) {
            const QString p = vstr(v.toMap(), "photo");
            if (!p.isEmpty())
                live.insert(p);
        }
    m_sums.removeIf([&live](const QHash<QString, Sum>::iterator it) { return !live.contains(it.key()); });
    QJsonObject o;
    for (auto it = m_sums.cbegin(); it != m_sums.cend(); ++it)
        o.insert(it.key(), QJsonObject{{QStringLiteral("h"), it.value().hash}, {QStringLiteral("s"), double(it.value().size)}});
    m_chat->m_vault->write(QStringLiteral("link-sums.json"), compact(o));
}

Link::Sum Link::sumOf(const QString &photoId)
{
    loadSums();
    return photoId.isEmpty() ? Sum() : m_sums.value(photoId);
}

bool Link::sumsReady(const QStringList &photos, std::function<void()> then)
{
    loadSums();
    QList<QPair<QString, std::function<QByteArray()>>> todo;
    bool ready = true;
    for (const QString &p : photos) {
        if (p.isEmpty() || m_sums.contains(p) || (m_noSum.contains(p) && now() - m_noSum.value(p) < 60000))
            continue;
        ready = false;
        if (!m_hashing.contains(p)) {
            m_hashing.insert(p);
            todo << qMakePair(p, m_chat->m_vault->reader(QStringLiteral("photos/") + p));
        }
    }
    if (ready)
        return true;
    if (then)
        m_afterSums << std::move(then);
    if (todo.isEmpty())
        return false;
    // Reading and hashing a big file takes a while: never on the GUI thread.
    auto out = std::make_shared<QList<QPair<QString, Sum>>>();
    QThread *t = QThread::create([todo, out] {
        for (const auto &job : todo) {
            QByteArray bytes = job.second();
            Sum s;
            if (!bytes.isEmpty() && bytes.size() <= kMediaMax)
                s = Sum{sha256(bytes), bytes.size()};
            bytes.fill(0);
            out->append(qMakePair(job.first, s));
        }
    });
    QPointer<Link> self(this);
    connect(t, &QThread::finished, this, [self, t, out] {
        t->deleteLater();
        if (!self)
            return;
        for (const auto &r : std::as_const(*out))
            self->m_hashing.remove(r.first);
        if (!self->m_chat->ready() || !self->m_sumsLoaded)
            return;   // locked meanwhile: worked out again next time
        QSet<QString> fresh;
        for (const auto &r : std::as_const(*out)) {
            if (r.second.hash.isEmpty()) {
                self->m_noSum.insert(r.first, now());   // not there (yet), or too big to mirror: shows as words
            } else {
                self->m_sums.insert(r.first, r.second);
                self->m_noSum.remove(r.first);
                fresh.insert(r.first);
            }
        }
        self->saveSums(fresh);
        if (!self->m_hashing.isEmpty())
            return;
        // Whatever waited for these goes now (each checks again, so one still missing just waits more).
        const QList<std::function<void()>> after = std::exchange(self->m_afterSums, {});
        for (const auto &f : after)
            f();
    });
    t->start();
    return false;
}

// The photo id of a file the companions were told about (only in a contact's 1:1 chat), or "".
QString Link::photoWith(const QString &hash)
{
    loadSums();
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it) {
        if (!m_chat->isContact(it.key()) || m_chat->isTrashed(it.key()))
            continue;
        for (const QVariant &v : it.value()) {
            const QString p = vstr(v.toMap(), "photo");
            if (!p.isEmpty() && vstr(v.toMap(), "unsent").isEmpty() && m_sums.value(p).hash == hash)
                return p;
        }
    }
    return QString();
}

// The photo id of a message's file for the companions ("" for none).
static QString mediaPhoto(const QVariantMap &m)
{
    const QString photo = m.value(QStringLiteral("photo")).toString();
    if (photo.isEmpty() || !m.value(QStringLiteral("unsent")).toString().isEmpty())
        return QString();
    return photo;
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
    // Bytes of UTF-8 JSON, as the page goes out: "[", the items with a comma between, "]".
    QList<QJsonArray> out;
    QJsonArray page;
    qsizetype bytes = 2;
    for (const QJsonValue &v : msgs) {
        const qsizetype size = compact(v.toObject()).size();
        if (!page.isEmpty() && (page.size() >= kPageMsgs || bytes + 1 + size > kPageBytes)) {
            out << page;
            page = QJsonArray();
            bytes = 2;
        }
        bytes += (page.isEmpty() ? 0 : 1) + size;
        page.append(v);
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
    // Every file's SHA-256 first (worked out off the GUI thread), so each page names its files.
    QStringList photos;
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it) {
        if (it.value().isEmpty() || !m_chat->isContact(it.key()) || m_chat->isTrashed(it.key()))
            continue;
        for (const QVariant &v : it.value()) {
            const QString p = mediaPhoto(v.toMap());
            if (!p.isEmpty())
                photos << p;
        }
    }
    const QString onlyKey = only ? only->key : QString();
    const bool everyone = !only;
    if (!sumsReady(photos, [this, onlyKey, everyone] {
            // Again once the sums are known, to the same devices if they're still linked and checked.
            if (everyone) {
                syncAll(nullptr);
                return;
            }
            for (const Device &d : std::as_const(m_companions))
                if (d.key == onlyKey) {
                    const Device copy = d;
                    syncAll(&copy);
                    return;
                }
        }))
        return;
    // History only over the home Wi-Fi: a companion that isn't on it now gets it once it is (lanTick).
    QList<Device> lan;
    for (const Device &d : std::as_const(to)) {
        if (lanHere(d.key)) {
            lan << d;
            m_owed.remove(d.key);
        } else {
            m_owed.insert(d.key);
        }
    }
    if (lan.isEmpty())
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
            for (const Device &d : std::as_const(lan))
                if (!sendLan(d.key, json))
                    m_owed.insert(d.key);   // off the home Wi-Fi meanwhile: all of it again later
        }
    }
}

void Link::onMessage(const QString &peer, const QVariantMap &m)
{
    ensureLoaded();
    if (m_companions.isEmpty() || !m_chat->isContact(peer) || m.value(QStringLiteral("system")).toBool())
        return;
    // A photo or file goes once its SHA-256 is known (worked out off the GUI thread).
    const QString id = vstr(m, "id");
    if (!sumsReady({mediaPhoto(m)}, [this, peer, id] {
            // The message as it is now (it may have been unsent meanwhile); gone: nothing to send.
            for (const QVariant &v : m_chat->m_private.value(peer))
                if (vstr(v.toMap(), "id") == id) {
                    onMessage(peer, v.toMap());
                    return;
                }
        }))
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
    Busy::Scope busy("linked phone");
    ensureLoaded();
    if (!m_chat->ready())
        return false;
    const QString t = jstr(p, "t");
    const bool comp = std::any_of(m_companions.cbegin(), m_companions.cend(), [&](const Device &d) { return d.key == from; });
    const bool ph = !m_phone.key.isEmpty() && m_phone.key == from;
    if (t == QLatin1String("hello") && !comp) {
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
        if (ph) {
            // The same phone linked again: its chats stay, and the 4 emoji are checked again before anything shows.
            m_phone.ok = false;
            if (!n.isEmpty())
                m_phone.name = n;
            if (mb.size() == 64)
                m_phone.mailbox = mb;
            m_ownOpen = false;
            save();
            return true;
        }
        forgetPhone();   // another phone: nothing of the last one stays
        m_phone = Device{from, mb.size() == 64 ? mb : QString(), n.isEmpty() ? QStringLiteral("Your phone") : n, false};
        save();
        return true;
    }
    if (t == QLatin1String("unlink") && (comp || ph)) {
        if (comp)
            m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == from; }), m_companions.end());
        if (ph) {
            Q_EMIT m_chat->notice(QStringLiteral("%1 unlinked this device.").arg(m_phone.name));
            forgetPhone();
        }
        save();
        return true;
    }
    // Nothing goes either way until this screen's emoji were checked.
    const bool okHere = ph ? m_phone.ok : std::any_of(m_companions.cbegin(), m_companions.cend(), [&](const Device &d) { return d.key == from && d.ok; });
    if (!okHere && comp && t == QLatin1String("send")) {
        // Not checked here yet: say so, so the companion doesn't wait for nothing.
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from)
                answerSend(d, jstr(p, "id"), false, QStringLiteral("confirm"));
        return true;
    }
    if (!okHere && (comp || ph))
        return t == QLatin1String("chat") || t == QLatin1String("msg") || t == QLatin1String("send") || t == QLatin1String("sync")
            || t == QLatin1String("me") || t == QLatin1String("setme");
    if (ph && t == QLatin1String("me")) {
        // The phone's own profile: shown here as "you" while it's linked.
        const QVariantMap me = readMe(p);
        if (me.isEmpty())
            return true;
        m_meName = vstr(me, "name");
        m_meStatus = vstr(me, "status");
        m_meCustom = vstr(me, "custom");
        // "pk": sent away from the home Wi-Fi without the picture: the one here stays until it comes.
        if (!p.value(QStringLiteral("pk")).toBool())
            m_mePic = me.value(QStringLiteral("pic")).toByteArray();
        save();
        return true;
    }
    if (ph && t == QLatin1String("result")) {
        // The phone's answer to a "send" from here: gone (it shows as the phone's own message), or why it didn't send.
        const QString id = jstr(p, "id");
        for (qsizetype i = 0; i < m_pending.size(); ++i) {
            if (m_pending.at(i).id != id)
                continue;
            const Pending done = m_pending.at(i);
            if (p.value(QStringLiteral("ok")).toBool()) {
                m_pending.removeAt(i);
                // Its copy may not be here yet: shown under the phone's own id, which the copy then merges into.
                const QString mid = jstr(p, "mid");
                if (!mid.isEmpty()) {
                    Mirror &c = m_mirror[done.peer];
                    if (c.peer.isEmpty()) {
                        c.peer = done.peer;
                        c.name = QStringLiteral("Someone");
                    }
                    if (std::none_of(c.msgs.cbegin(), c.msgs.cend(), [&](const M &x) { return x.id == mid; })) {
                        merge(c.msgs, {M{mid, done.text, true, now(), {}, {}, {}, 0}});
                        saveMirror();
                    }
                }
            } else {
                m_pending[i].err = errText(jstr(p, "err"));
            }
            Q_EMIT changed();
            break;
        }
        return true;
    }
    if (comp && t == QLatin1String("setme")) {
        // A companion changed my name or status: the phone is the account, so it changes here and goes to every companion.
        if (setMyProfile) {
            QJsonObject q = p;
            if (jstr(p, "n").trimmed().isEmpty())
                q.insert(QStringLiteral("n"), m_chat->username());   // a blank name leaves mine as it is
            const QVariantMap me = readMe(q);
            if (!me.isEmpty())
                setMyProfile(vstr(me, "name"), jstr(p, "st").isEmpty() ? QString() : vstr(me, "status"), vstr(me, "custom"));
        }
        sendMe(true);
        return true;
    }
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
        // My own message from here came back: it isn't pending any more.
        if (x.mine)
            for (qsizetype i = 0; i < m_pending.size(); ++i)
                if (m_pending.at(i).peer == peer && m_pending.at(i).text.left(kText) == x.text) {
                    m_pending.removeAt(i);
                    break;
                }
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
        if (b.decodingStatus != QByteArray::Base64DecodingStatus::Ok || b.decoded.size() != expect) {
            m_failed.insert(h);
            m_want.remove(h);
            m_fetching.clear();
            m_parts.clear();
            return false;
        }
        m_fetchAt = now();
        if (m_parts.at(i).isEmpty()) {
            m_parts[i] = b.decoded;
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
        // Answered either way ("result"), so the companion never waits for nothing.
        const QString peer = jstr(p, "peer"), text = jstr(p, "text").trimmed(), id = jstr(p, "id");
        Device dev;
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from)
                dev = d;
        if (text.isEmpty()) {
            answerSend(dev, id, false, QStringLiteral("empty"));
        } else if (!m_chat->isContact(peer)) {
            answerSend(dev, id, false, QStringLiteral("not a contact"));
        } else if (!firstSend(id)) {
            answerSend(dev, id, true, QString());   // sent already: its copy went then
        } else {
            const QString mid = m_chat->sendPrivateWith(peer, text.left(Chat::kMax), QString(), QJsonObject());
            answerSend(dev, id, !mid.isEmpty(), mid.isEmpty() ? QStringLiteral("not sent") : QString(), mid);
        }
        return true;
    }
    if (comp && t == QLatin1String("sync")) {
        // Refresh at most once in 10 minutes per companion; the first one after the emoji ("f") always.
        if (!p.value(QStringLiteral("f")).toBool() && !refreshDue(m_synced.value(from, 0), now()))
            return true;
        m_synced.insert(from, now());
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from) {
                const Device copy = d;
                syncAll(&copy);
            }
        sendMe(true);
        return true;
    }
    return comp || ph;
}

// ------------------------------------------------------------------ the companion's side

QString Link::send(const QString &peer, const QString &text)
{
    ensureLoaded();
    const QString t = text.trimmed();
    if (m_phone.key.isEmpty())
        return QStringLiteral("No phone is linked any more.");
    if (!m_phone.ok)
        return QStringLiteral("Check the emoji on both screens first.");
    if (t.isEmpty())
        return QStringLiteral("Write something first.");
    // Shown at once as pending, until the phone answers or its copy comes back.
    const Pending p{QUuid::createUuid().toString(QUuid::WithoutBraces), peer, t.left(Chat::kMax), QString(), now()};
    m_pending << p;
    while (m_pending.size() > 50)
        m_pending.removeFirst();
    sendPending(p);
    Q_EMIT changed();
    return QString();
}

void Link::sendPending(const Pending &p)
{
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}, {QStringLiteral("peer"), p.peer}, {QStringLiteral("text"), p.text},
                                {QStringLiteral("id"), p.id}});
    // No answer in a minute: "Didn't send", with Retry.
    const QString id = p.id;
    QTimer::singleShot(int(kEchoMs + 500), this, [this, id] {
        for (Pending &x : m_pending)
            if (x.id == id && x.err.isEmpty() && now() - x.time >= kEchoMs) {
                x.err = QStringLiteral("Didn't send");
                Q_EMIT changed();
            }
    });
}

void Link::retrySend(const QString &id)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok)
        return;
    for (Pending &x : m_pending)
        if (x.id == id) {
            x.err.clear();
            x.time = now();
            sendPending(x);   // the same id: the phone sends it at most once
            Q_EMIT changed();
            return;
        }
}

void Link::answerSend(const Device &d, const QString &id, bool ok, const QString &err, const QString &mid)
{
    if (d.key.isEmpty() || id.isEmpty())
        return;
    QJsonObject r{{QStringLiteral("t"), QStringLiteral("result")}, {QStringLiteral("id"), id}, {QStringLiteral("ok"), ok}};
    if (!err.isEmpty())
        r.insert(QStringLiteral("err"), err);
    if (!mid.isEmpty())
        r.insert(QStringLiteral("mid"), mid);
    sendTo(d, r);
}

QString Link::refresh()
{
    ensureLoaded();
    if (m_phone.key.isEmpty())
        return QStringLiteral("No phone is linked.");
    if (!m_phone.ok)
        return QStringLiteral("Check the emoji on both screens first.");
    const qint64 t = now();
    if (!refreshDue(m_askedAt, t)) {
        const qint64 mins = qMax<qint64>(1, (kRefreshEveryMs - (t - m_askedAt) + 59999) / 60000);
        return QStringLiteral("Asked already. You can ask again in %1 min.").arg(mins);
    }
    m_askedAt = t;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("sync")}});
    fetchNext();
    const QString name = m_phone.name.isEmpty() ? QStringLiteral("your phone") : m_phone.name;
    return lanHere(m_phone.key) ? QStringLiteral("Asked %1 for its chats.").arg(name)
                                : QStringLiteral("Asked %1. History comes over on home Wi-Fi.").arg(name);
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

    // Paging and merging: pages of at most 100 messages and 20 KB of UTF-8 JSON, merged by id without losing any.
    QJsonArray small, big, wide;
    for (int i = 0; i < 250; ++i)
        small.append(QJsonObject{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("x"), QStringLiteral("hi")}, {QStringLiteral("me"), false},
                                 {QStringLiteral("t"), double(1000 + i)}});
    for (int i = 0; i < 60; ++i)
        big.append(QJsonObject{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("x"), QString(kText, QLatin1Char('a'))}, {QStringLiteral("me"), true},
                               {QStringLiteral("t"), double(1000 + i)}});
    // Two bytes a character in UTF-8: counted in bytes, not characters.
    for (int i = 0; i < 60; ++i)
        wide.append(QJsonObject{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("x"), QString(kText, QChar(0x00E9))}, {QStringLiteral("me"), true},
                                {QStringLiteral("t"), double(1000 + i)}});
    const QList<QJsonArray> sp = pages(small), bp = pages(big), wp = pages(wide);
    check(sp.size() == 3 && sp.at(0).size() == 100 && sp.at(2).size() == 50, "250 messages make pages of 100, 100 and 50");
    bool under = bp.size() > 1;
    int count = 0;
    for (const QJsonArray &a : bp) {
        under = under && compact(a).size() <= kPageBytes;
        count += int(a.size());
    }
    check(under && count == 60, "long messages make pages of at most 20 KB, none lost");
    bool wideUnder = wp.size() > bp.size();
    int wideCount = 0;
    for (const QJsonArray &a : wp) {
        wideUnder = wideUnder && compact(a).size() <= kPageBytes && a.size() <= 5;
        wideCount += int(a.size());
    }
    check(wideUnder && wideCount == 60, "pages are measured in UTF-8 bytes");
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

    // A file name from the phone is made safe to save.
    const QString fb = QStringLiteral("file");
    check(safeName(QStringLiteral("a<b>c:d\"e/f\\g|h?i*j.txt"), fb) == QLatin1String("a_b_c_d_e_f_g_h_i_j.txt"), "characters Windows refuses become _");
    check(safeName(QStringLiteral("x\ty\nz.txt"), fb) == QLatin1String("x_y_z.txt"), "control characters become _");
    check(safeName(QStringLiteral("..hidden"), fb) == QLatin1String("hidden") && safeName(QStringLiteral("..."), fb) == fb, "never a hidden file");
    check(safeName(QStringLiteral("CON"), fb) == QLatin1String("_CON") && safeName(QStringLiteral("nul.txt"), fb) == QLatin1String("_nul.txt")
              && safeName(QStringLiteral("com1.jpg"), fb) == QLatin1String("_com1.jpg") && safeName(QStringLiteral("LPT9"), fb) == QLatin1String("_LPT9")
              && safeName(QStringLiteral("COM10.txt"), fb) == QLatin1String("COM10.txt") && safeName(QStringLiteral("console.txt"), fb) == QLatin1String("console.txt"),
          "names Windows keeps for itself get a _");
    const QString longName = safeName(QString(300, QLatin1Char('a')) + QStringLiteral(".pdf"), fb);
    check(longName.size() == 120 && longName.endsWith(QLatin1String(".pdf")), "long names are cut, keeping the extension");

    // The code on show is replaced a minute before it runs out.
    const qint64 until = 10000000;
    check(!codeDue(until - kRefreshMs - 1, until) && codeDue(until - kRefreshMs + 1, until) && codeDue(until + 1, until), "a new code a minute before the old one runs out");
    // "me": the phone's profile as the companion takes it.
    {
        const QVariantMap m = readMe(QJsonObject{{QStringLiteral("t"), QStringLiteral("me")}, {QStringLiteral("n"), QStringLiteral("  Ravenor  ")},
                                                 {QStringLiteral("st"), QStringLiteral("dnd")}, {QStringLiteral("cs"), QStringLiteral("At work")}});
        check(vstr(m, "name") == QLatin1String("Ravenor") && vstr(m, "status") == QLatin1String("dnd") && vstr(m, "custom") == QLatin1String("At work")
                  && m.value(QStringLiteral("pic")).toByteArray().isEmpty(), "me: name, status and custom status");
        check(readMe(QJsonObject{{QStringLiteral("n"), QStringLiteral("   ")}}).isEmpty(), "me: no name, not taken");
        check(vstr(readMe(QJsonObject{{QStringLiteral("n"), QStringLiteral("A")}, {QStringLiteral("st"), QStringLiteral("busy")}}), "status") == QLatin1String("online"),
              "me: an unknown status is online");
        check(readMe(QJsonObject{{QStringLiteral("n"), QString(60, QLatin1Char('x'))}}).value(QStringLiteral("name")).toString().size() == 40, "me: a name is cut at 40");
        check(readMe(QJsonObject{{QStringLiteral("n"), QStringLiteral("A")}, {QStringLiteral("pic"), QStringLiteral("bm90IGEganBlZw==")}})
                  .value(QStringLiteral("pic")).toByteArray().isEmpty(), "me: a picture that isn't a 256x256 JPEG is dropped");
    }
    // Routes: history, media and a picture only on the home Wi-Fi; small live frames may use the internet.
    {
        auto f = [](const char *t) { return QJsonObject{{QStringLiteral("t"), QString::fromLatin1(t)}}; };
        check(internetOk(f("msg")) && internetOk(f("send")) && internetOk(f("hello")) && internetOk(f("unlink")) && internetOk(f("result"))
                  && internetOk(f("me")), "small live frames may use the internet");
        check(!internetOk(f("chat")) && !internetOk(f("get")) && !internetOk(f("part")), "history and media never use the internet");
        QJsonObject me = f("me");
        me.insert(QStringLiteral("pic"), QStringLiteral("AAAA"));
        check(!internetOk(me), "a me with its picture never uses the internet");
        check(refreshDue(0, 1) && !refreshDue(1000, 1000 + kRefreshEveryMs - 1) && refreshDue(1000, 1000 + kRefreshEveryMs),
              "Refresh at most once in 10 minutes");
        check(errText(QStringLiteral("confirm")) == QLatin1String("Confirm the emoji on your phone")
                  && errText(QStringLiteral("not a contact")) == QLatin1String("Not a contact on your phone"), "a phone's result shows why");
    }
    return bad == 0;
}
