#include "profile.h"
#include "busy.h"

#include <QPainter>
#include <QPainterPath>
#include "chat.h"
#include "chatwire.h"
#include "groupwire.h"
#include "photos.h"
#include "richwire.h"
#include "vault.h"

#include <QBuffer>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QImageReader>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMetaObject>
#include <QMutexLocker>
#include <QThread>
#include <QUrl>
#include <QUuid>
#include <climits>
#include <cstdio>
#include <utility>

const char Profile::kCap[] = "p1";

namespace
{
// JPEG qualities tried in turn until the picture fits kSendMaxBytes.
const int kQualities[] = {80, 70, 60, 50, 40, 30, 20};
// A person's colour when none came from them: one of these, picked by the first byte of SHA-256 of their key.
const char *const kPalette[] = {"#d9853b", "#3fb27f", "#4f8fde", "#b86ad9", "#e0b44c", "#d9534f", "#2fa9a0", "#c96b8e",
                                "#7a9b3c", "#e07b39", "#5c7cfa", "#8d6e63"};
const QStringList kMyStatuses{QStringLiteral("online"), QStringLiteral("away"), QStringLiteral("dnd"), QStringLiteral("invisible")};
const QString kMe = QStringLiteral("me");

QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
QString hashOf(const QString &key) { return QString::fromLatin1(QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256).toHex().left(40)); }
}

// ------------------------------------------------------------------ the wire parts

bool Profile::colourOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString c = v.toString();
    if (c.size() != 7 || c.at(0) != QLatin1Char('#'))
        return false;
    for (qsizetype i = 1; i < 7; ++i) {
        const ushort u = c.at(i).unicode();
        if (!((u >= '0' && u <= '9') || (u >= 'a' && u <= 'f') || (u >= 'A' && u <= 'F')))
            return false;
    }
    return true;
}

// Only the markers before the scan are read, so a picture is never decoded before its size is known.
QSize Profile::jpegSize(const QByteArray &b)
{
    const auto at = [&b](qsizetype i) { return int(uchar(b.at(i))); };
    if (b.size() < 4 || at(0) != 0xFF || at(1) != 0xD8)
        return {};
    qsizetype i = 2;
    while (i + 4 <= b.size()) {
        if (at(i) != 0xFF)
            return {};
        const int m = at(i + 1);
        if (m == 0xFF) {
            ++i;
            continue;
        }
        if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
            i += 2;
            continue;
        }
        if (m == 0xD9 || m == 0xDA)
            return {};
        const int len = (at(i + 2) << 8) | at(i + 3);
        if (len < 2 || i + 2 + len > b.size())
            return {};
        const bool sof = (m >= 0xC0 && m <= 0xC3) || (m >= 0xC5 && m <= 0xC7) || (m >= 0xC9 && m <= 0xCB) || (m >= 0xCD && m <= 0xCF);
        if (sof) {
            if (len < 7)
                return {};
            return QSize((at(i + 7) << 8) | at(i + 8), (at(i + 5) << 8) | at(i + 6));
        }
        i += 2 + len;
    }
    return {};
}

bool Profile::pictureOk(const QByteArray &b) { return b.size() <= kMaxBytes && jpegSize(b) == QSize(kSide, kSide); }

bool Profile::ok(const QJsonObject &p)
{
    if (p.value(QStringLiteral("t")).toString() != QLatin1String("profile"))
        return false;
    const QJsonValue v = p.value(QStringLiteral("v"));
    if (!RichWire::isTs(v) || v.toDouble() < 1)
        return false;
    if (!colourOk(p.value(QStringLiteral("col"))))
        return false;
    const QJsonValue pic = p.value(QStringLiteral("pic"));
    if (!pic.isString())
        return false;
    const QString s = pic.toString();
    if (s.isEmpty())
        return true;
    // Over the cap is refused before anything is decoded.
    if (s.size() > (kMaxBytes + 2) / 3 * 4)
        return false;
    const QByteArray bytes = QByteArray::fromBase64(s.toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    return !bytes.isEmpty() && pictureOk(bytes);
}

QByteArray Profile::msg(qint64 v, const QByteArray &pic, const QString &col)
{
    return compact({{QStringLiteral("t"), QStringLiteral("profile")}, {QStringLiteral("v"), QJsonValue(v)},
                    {QStringLiteral("pic"), QString::fromLatin1(pic.toBase64())}, {QStringLiteral("col"), col}});
}

QString Profile::defaultColour(const QString &key)
{
    const QByteArray h = QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256);
    return QLatin1String(kPalette[uchar(h.at(0)) % (sizeof(kPalette) / sizeof(kPalette[0]))]);
}

// active (a fresh "on" in the last 5 minutes) shows their st (online when their app has no p1); otherwise offline
// for someone ever heard from, and nothing for anyone else.
QString Profile::shownStatus(bool active, const QString &st, bool heard)
{
    if (active)
        return RichWire::kStatuses.contains(st) ? st : QStringLiteral("online");
    return heard ? QStringLiteral("offline") : QString();
}

QString Profile::cleanCustom(const QString &s)
{
    QString out;
    for (const QChar c : s)
        if (c.unicode() >= 0x20 && c.unicode() != 0x7F)
            out += c;
    return RichWire::cut(out.trimmed(), RichWire::kMaxCustom);
}

QByteArray Profile::encode(const QImage &image)
{
    if (image.isNull())
        return {};
    const int s = qMin(image.width(), image.height());
    if (s < 1)
        return {};
    const QImage square = image.copy((image.width() - s) / 2, (image.height() - s) / 2, s, s)
                              .scaled(kSide, kSide, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                              .convertToFormat(QImage::Format_RGB888);
    for (const int q : kQualities) {
        QByteArray out;
        QBuffer buf(&out);
        buf.open(QIODevice::WriteOnly);
        if (!square.save(&buf, "JPEG", q))
            return {};
        buf.close();
        // Only pixels survive: no JFIF extras, nothing about this computer.
        const QByteArray clean = Photos::scrub(out);
        if (!clean.isEmpty() && clean.size() <= kSendMaxBytes && pictureOk(clean))
            return clean;
    }
    return {};
}

// ------------------------------------------------------------------ mine

Profile::Profile(Chat *chat, Vault *vault, QObject *parent)
    : QObject(parent), m_chat(chat), m_vault(vault)
{
    // Read once the vault is open; someone new (a contact, a group member, an app that now says p1) gets my picture.
    connect(m_chat, &Chat::changed, this, [this] {
        if (!m_chat->ready()) {
            m_loaded = false;
            return;
        }
        if (!m_loaded)
            load();
        reconcileSoon();
    });
    connect(m_chat, &Chat::liveChanged, this, &Profile::liveChanged);
    // Connected first, so the copy the image provider reads is current before anything shows a new picture URL.
    connect(this, &Profile::pictureChanged, this, &Profile::share);
    connect(this, &Profile::changed, this, &Profile::share);
    m_soon.setSingleShot(true);
    m_soon.setInterval(1500);
    connect(&m_soon, &QTimer::timeout, this, &Profile::reconcile);
    m_every.setInterval(60000);
    connect(&m_every, &QTimer::timeout, this, &Profile::reconcile);
    m_every.start();
}

QString Profile::myColour() const { return m_me.col.isEmpty() ? defaultColour(m_chat->chatKey()) : m_me.col; }

void Profile::setStatus(const QString &s)
{
    if (!kMyStatuses.contains(s) || s == m_status)
        return;
    m_status = s;
    saveMe();
    m_chat->announceStatus();
    Q_EMIT statusChanged(kMe);
    Q_EMIT changed();
}

void Profile::setCustomStatus(const QString &s)
{
    const QString c = cleanCustom(s);
    if (c == m_custom)
        return;
    m_custom = c;
    saveMe();
    m_chat->announceStatus();
    Q_EMIT statusChanged(kMe);
    Q_EMIT changed();
}

void Profile::setMyColour(const QString &hex)
{
    const QString c = hex.trimmed().toLower();
    if (!colourOk(c) || c == m_me.col)
        return;
    change(Entry{m_me.v + 1, c, m_me.pic});
}

bool Profile::setMyPicture(const QImage &image)
{
    const QByteArray bytes = encode(image);
    if (bytes.isEmpty())
        return false;
    change(Entry{m_me.v + 1, m_me.col.isEmpty() ? defaultColour(m_chat->chatKey()) : m_me.col, bytes});
    return true;
}

void Profile::clearMyPicture()
{
    if (m_me.pic.isEmpty())
        return;
    change(Entry{m_me.v + 1, m_me.col, QByteArray()});
}

void Profile::change(const Entry &e)
{
    if (!m_chat->ready())
        return;
    if (!m_loaded)
        load();
    m_me = e;
    m_cache.remove(kMe);
    saveMe();
    Q_EMIT pictureChanged(kMe);
    Q_EMIT changed();
    reconcileSoon();
}

QByteArray Profile::activeMsg(bool on, qint64 ts) const { return RichWire::activeMsg(on, ts, m_status, m_custom); }

// ------------------------------------------------------------------ everyone

QString Profile::statusOf(const QString &key) const
{
    if (key == kMe)
        return m_phoneOn ? m_phoneStatus : m_status;
    if (!m_chat->activeStatus())
        return QString();
    return shownStatus(m_chat->m_active.contains(key), m_statuses.value(key).first, m_heard.contains(key) || m_chat->hasM1(key));
}

QString Profile::colourOf(const QString &key) const
{
    if (key == kMe)
        return myColour();
    const QString c = m_peers.value(key).col;
    return c.isEmpty() ? defaultColour(key) : c;
}

int Profile::pictureVersion(const QString &key) const
{
    if (key == kMe && m_phoneOn)
        return m_phonePic.isEmpty() ? 0 : int(qMin<qint64>(m_phoneV, INT_MAX));
    const Entry e = key == kMe ? m_me : m_peers.value(key);
    return e.pic.isEmpty() ? 0 : int(qMin<qint64>(e.v, INT_MAX));
}

QUrl Profile::pictureUrl(const QString &key) const
{
    const int v = pictureVersion(key);
    if (v == 0)
        return {};
    if (key == kMe && m_phoneOn)
        return QUrl(QStringLiteral("image://profile/phone?v=") + QString::number(v));
    return QUrl(QStringLiteral("image://profile/") + (key == kMe ? kMe : hashOf(key)) + QStringLiteral("?v=") + QString::number(v));
}

QImage Profile::picture(const QString &key)
{
    const auto hit = m_cache.constFind(key);
    if (hit != m_cache.cend())
        return *hit;
    const QByteArray bytes = key == kMe ? (m_phoneOn ? m_phonePic : m_me.pic) : m_peers.value(key).pic;
    if (bytes.isEmpty())
        return {};
    const QImage img = QImage::fromData(bytes, "JPEG");
    if (img.isNull())
        return {};
    if (m_cache.size() > 64)
        m_cache.clear();
    m_cache.insert(key, img);
    return img;
}

void Profile::setPhoneMe(bool on, const QByteArray &pic, const QString &status)
{
    const QByteArray p = on && pictureOk(pic) ? pic : QByteArray();
    const QString st = on ? (kMyStatuses.contains(status) ? status : QStringLiteral("online")) : QString();
    if (on == m_phoneOn && p == m_phonePic && st == m_phoneStatus)
        return;
    const bool picChanged = on != m_phoneOn || p != m_phonePic;
    m_phoneOn = on;
    m_phonePic = p;
    m_phoneStatus = st;
    if (picChanged) {
        // A new version each time, so a picture URL shown before never stands for this one.
        ++m_phoneV;
        m_cache.remove(kMe);
        Q_EMIT pictureChanged(kMe);
    }
    Q_EMIT statusChanged(kMe);
    Q_EMIT changed();
}

QImage Profile::pictureById(const QString &id)
{
    if (id == kMe)
        return m_phoneOn ? QImage() : picture(kMe);
    if (id == QLatin1String("phone"))
        return m_phoneOn ? picture(kMe) : QImage();
    for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it)
        if (hashOf(it.key()) == id)
            return picture(it.key());
    return {};
}

void Profile::share()
{
    QHash<QString, QByteArray> all;
    if (!m_me.pic.isEmpty())
        all.insert(kMe, m_me.pic);
    if (m_phoneOn)
        all.insert(QStringLiteral("phone"), m_phonePic);   // may be empty: then no picture stands for mine
    for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it)
        if (!it->pic.isEmpty())
            all.insert(hashOf(it.key()), it->pic);
    QMutexLocker l(&m_sharedLock);
    m_shared.swap(all);
}

QByteArray Profile::sharedPicture(const QString &id) const
{
    QMutexLocker l(&m_sharedLock);
    // Mine shows only while no linked phone stands in for it (pictureById does the same).
    if (id == kMe && m_shared.contains(QStringLiteral("phone")))
        return {};
    return m_shared.value(id);
}

void Profile::tookLive(const QString &key, bool on, const QJsonObject &p)
{
    m_heard.insert(key);
    if (!on) {
        m_statuses.remove(key);
    } else {
        const QString st = p.value(QStringLiteral("st")).toString();
        const QJsonValue cs = p.value(QStringLiteral("cs"));
        m_statuses.insert(key, qMakePair(RichWire::kStatuses.contains(st) ? st : QStringLiteral("online"),
                                         RichWire::customOk(cs) ? cs.toString() : QString()));
    }
    Q_EMIT statusChanged(key);
}

// Chat's active map changed: someone's status ran out (or came), so the dots follow.
void Profile::liveChanged()
{
    QSet<QString> now;
    for (auto it = m_chat->m_active.cbegin(); it != m_chat->m_active.cend(); ++it)
        now.insert(it.key());
    for (const QString &k : std::as_const(m_wasActive))
        if (!now.contains(k)) {
            m_statuses.remove(k);
            Q_EMIT statusChanged(k);
        }
    for (const QString &k : std::as_const(now))
        if (!m_wasActive.contains(k))
            Q_EMIT statusChanged(k);
    m_wasActive = now;
}

bool Profile::receive(const QString &fromKey, const QJsonObject &p)
{
    if (!m_loaded && m_chat->ready())
        load();
    if (!m_loaded || !ok(p))
        return false;
    const qint64 v = qint64(p.value(QStringLiteral("v")).toDouble());
    const QString pic = p.value(QStringLiteral("pic")).toString();
    QByteArray bytes;
    if (!pic.isEmpty()) {
        bytes = Photos::scrub(QByteArray::fromBase64(pic.toLatin1(), QByteArray::AbortOnBase64DecodingErrors));
        if (bytes.isEmpty() || !pictureOk(bytes))
            return false;
    }
    const auto cur = m_peers.constFind(fromKey);
    if (cur != m_peers.cend() && v <= cur->v)
        return true;   // taken, but nothing newer in it
    const Entry e{v, p.value(QStringLiteral("col")).toString().toLower(), bytes};
    m_peers.insert(fromKey, e);
    m_cache.remove(fromKey);
    m_vault->write(file(fromKey), compact(toJson(fromKey, e)));
    Q_EMIT pictureChanged(fromKey);
    Q_EMIT changed();
    return true;
}

void Profile::forget(const QString &key)
{
    if (m_peers.remove(key))
        m_vault->remove(file(key));
    if (m_sent.remove(key))
        saveSent();
    m_cache.remove(key);
    m_heard.remove(key);
    m_statuses.remove(key);
    Q_EMIT pictureChanged(key);
    Q_EMIT changed();
}

// ------------------------------------------------------------------ sending

void Profile::reconcileSoon()
{
    if (!m_soon.isActive())
        m_soon.start();
}

// My picture and colour to everyone who should have this version and doesn't yet: contacts, and members of my groups,
// only those whose app said p1, never anyone blocked or in the trash. One is marked sent only once a route took it, so
// someone out of reach gets it on a later pass.
void Profile::reconcile()
{
    Busy::Scope busy("profile sharing");
    Chat *c = m_chat;
    if (!m_loaded || !c->ready() || c->moving() || m_me.v == 0)
        return;
    QSet<QString> keys;
    for (auto it = c->m_contacts.cbegin(); it != c->m_contacts.cend(); ++it)
        keys.insert(it.key());
    const QString me = c->chatKey();
    for (auto it = c->m_groups.cbegin(); it != c->m_groups.cend(); ++it) {
        if (it->state.isEmpty() || it->removed || it->left)
            continue;
        for (const QJsonValue &v : it->state.value(QStringLiteral("members")).toArray()) {
            const QString k = v.toObject().value(QStringLiteral("k")).toString();
            if (!k.isEmpty() && k != me)
                keys.insert(k);
        }
    }
    const QByteArray plain = msg(m_me.v, m_me.pic, m_me.col);
    bool changed = false;
    for (const QString &k : std::as_const(keys)) {
        if (c->isBlocked(k) || c->isTrashed(k) || (!c->isContact(k) && !c->isGroupPeer(k)) || !c->hasCap(k, QLatin1String(kCap)))
            continue;
        if (m_sent.value(k, 0) == m_me.v)
            continue;
        // No way to reach them right now: nothing is sealed (that would use up a ratchet step and a save each pass).
        if (c->route(k).isEmpty() && c->mailboxOf(k).isEmpty())
            continue;
        const QJsonObject env = c->envelope(QStringLiteral("profile"), k, plain, QUuid::createUuid().toString(QUuid::WithoutBraces));
        if (c->deliver(k, env, c->mailboxOf(k)).isEmpty())
            continue;
        m_sent.insert(k, m_me.v);
        changed = true;
    }
    if (changed)
        saveSent();
}

// ------------------------------------------------------------------ files

QString Profile::file(const QString &key) { return QStringLiteral("profile/") + hashOf(key) + QStringLiteral(".json"); }

QJsonObject Profile::toJson(const QString &key, const Entry &e)
{
    return {{QStringLiteral("k"), key}, {QStringLiteral("v"), QJsonValue(e.v)}, {QStringLiteral("col"), e.col},
            {QStringLiteral("pic"), QString::fromLatin1(e.pic.toBase64())}};
}

Profile::Entry Profile::fromJson(const QJsonObject &o)
{
    Entry e;
    e.v = qint64(o.value(QStringLiteral("v")).toDouble());
    e.col = jstr(o, "col");
    e.pic = QByteArray::fromBase64(jstr(o, "pic").toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    if (!e.pic.isEmpty() && !pictureOk(e.pic))
        e.pic.clear();
    return e;
}

void Profile::saveMe()
{
    QJsonObject o = toJson(kMe, m_me);
    o.insert(QStringLiteral("st"), m_status);
    o.insert(QStringLiteral("cs"), m_custom);
    m_vault->write(QStringLiteral("profile/me.json"), compact(o));
}

void Profile::saveSent()
{
    QJsonObject o;
    for (auto it = m_sent.cbegin(); it != m_sent.cend(); ++it)
        o.insert(it.key(), QJsonValue(it.value()));
    m_vault->write(QStringLiteral("profile/sent.json"), compact(o));
}

void Profile::load()
{
    m_loaded = true;
    m_peers.clear();
    m_sent.clear();
    m_cache.clear();
    const QJsonObject me = QJsonDocument::fromJson(m_vault->read(QStringLiteral("profile/me.json"))).object();
    m_me = fromJson(me);
    m_status = kMyStatuses.contains(jstr(me, "st")) ? jstr(me, "st") : QStringLiteral("online");
    m_custom = cleanCustom(jstr(me, "cs"));
    const QJsonObject sent = QJsonDocument::fromJson(m_vault->read(QStringLiteral("profile/sent.json"))).object();
    for (auto it = sent.constBegin(); it != sent.constEnd(); ++it)
        m_sent.insert(it.key(), qint64(it.value().toDouble()));
    const QDir dir(m_vault->path(QStringLiteral("profile")));
    for (const QString &name : dir.entryList({QStringLiteral("*.json")}, QDir::Files)) {
        if (name == QLatin1String("me.json") || name == QLatin1String("sent.json"))
            continue;
        const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QStringLiteral("profile/") + name)).object();
        const QString k = jstr(o, "k");
        if (!k.isEmpty() && k != kMe)
            m_peers.insert(k, fromJson(o));
    }
    Q_EMIT changed();
}

// ------------------------------------------------------------------ the image provider

ProfileImageProvider::ProfileImageProvider(Profile *profile)
    : QQuickImageProvider(QQuickImageProvider::Image), m_profile(profile)
{
}

QImage ProfileImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    Q_UNUSED(requestedSize)
    // "<hash>?v=<n>" or "me?v=<n>": the version only makes QML ask again after a change.
    const QString who = id.section(QLatin1Char('?'), 0, 0);
    const QByteArray bytes = m_profile->sharedPicture(who);
    QImage img = bytes.isEmpty() ? QImage() : QImage::fromData(bytes, "JPEG");
    // Round here, so QML needs no shader effect: everything outside the circle is clear.
    if (!img.isNull()) {
        const int side = qMin(img.width(), img.height());
        QImage round(side, side, QImage::Format_ARGB32_Premultiplied);
        round.fill(Qt::transparent);
        QPainter p(&round);
        p.setRenderHint(QPainter::Antialiasing);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        QPainterPath circle;
        circle.addEllipse(QRectF(0, 0, side, side));
        p.setClipPath(circle);
        p.drawImage(QRect(0, 0, side, side), img, QRect((img.width() - side) / 2, (img.height() - side) / 2, side, side));
        p.end();
        img = round;
    }
    if (size)
        *size = img.size();
    return img;
}

// ------------------------------------------------------------------ self-test (shared/vectors/p1-vectors.json)

bool Profile::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("p1 self-test: FAILED %s\n", qPrintable(what));
    };
    QFile f(vectorsPath);
    const QJsonObject v = f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
    if (v.isEmpty()) {
        std::printf("p1 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject lim = v.value(QStringLiteral("limits")).toObject();
    check(lim.value(QStringLiteral("maxBytes")).toInt() == kMaxBytes && lim.value(QStringLiteral("sendMaxBytes")).toInt() == kSendMaxBytes
              && lim.value(QStringLiteral("side")).toInt() == kSide && lim.value(QStringLiteral("maxCustom")).toInt() == RichWire::kMaxCustom
              && lim.value(QStringLiteral("ratePer10s")).toInt() == 1,
          "limits");
    const QJsonArray pal = lim.value(QStringLiteral("palette")).toArray();
    check(pal.size() == int(sizeof(kPalette) / sizeof(kPalette[0])), "palette size");
    for (int i = 0; i < pal.size() && i < int(sizeof(kPalette) / sizeof(kPalette[0])); ++i)
        check(pal.at(i).toString() == QLatin1String(kPalette[i]), QStringLiteral("palette %1").arg(i));
    const QJsonArray ql = lim.value(QStringLiteral("qualities")).toArray();
    check(ql.size() == int(sizeof(kQualities) / sizeof(kQualities[0])), "qualities");
    for (int i = 0; i < ql.size() && i < int(sizeof(kQualities) / sizeof(kQualities[0])); ++i)
        check(ql.at(i).toInt() == kQualities[i], QStringLiteral("quality %1").arg(i));
    check(QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' ')).contains(QLatin1String(kCap)), "caps say p1");
    check(ChatWire::useRatchet(QStringLiteral("profile"), true, true), "profile goes through the ratchet");

    const QJsonObject pics = v.value(QStringLiteral("pictures")).toObject();
    for (const QString &name : pics.keys()) {
        const QJsonArray c = pics.value(name).toArray();
        const QByteArray bytes = QByteArray::fromBase64(c.at(0).toString().toLatin1());
        check(bytes.size() == c.at(3).toInt(), name + QStringLiteral(" bytes"));
        const QSize s = jpegSize(bytes);
        check(c.at(1).toInt() == 0 ? !s.isValid() : s == QSize(c.at(1).toInt(), c.at(2).toInt()), name + QStringLiteral(" size"));
        check(pictureOk(bytes) == c.at(4).toBool(), name + QStringLiteral(" accepted"));
        // What Qt decodes agrees with the header, for the real pictures.
        if (c.at(4).toBool())
            check(QImage::fromData(bytes, "JPEG").size() == QSize(kSide, kSide), name + QStringLiteral(" decodes"));
    }
    const QJsonObject prof = v.value(QStringLiteral("profile")).toObject();
    const QJsonArray good = prof.value(QStringLiteral("good")).toArray();
    for (int i = 0; i < good.size(); ++i)
        check(ok(good.at(i).toObject()), QStringLiteral("profile good %1").arg(i));
    const QJsonArray refuse = prof.value(QStringLiteral("refuse")).toArray();
    for (int i = 0; i < refuse.size(); ++i)
        check(!ok(refuse.at(i).toObject()), QStringLiteral("profile refuses %1").arg(i));
    for (const char *name : {"tooBig", "atCapNotJpeg", "small"}) {
        const QByteArray bytes = QByteArray::fromBase64(pics.value(QLatin1String(name)).toArray().at(0).toString().toLatin1());
        check(!ok(QJsonDocument::fromJson(msg(1, bytes, QStringLiteral("#3a7bd5"))).object()), QStringLiteral("refuses %1 as a message").arg(QLatin1String(name)));
    }
    const QByteArray okPic = QByteArray::fromBase64(pics.value(QStringLiteral("ok")).toArray().at(0).toString().toLatin1());
    const QJsonObject mine = QJsonDocument::fromJson(msg(4, okPic, QStringLiteral("#3a7bd5"))).object();
    check(ok(mine) && mine.value(QStringLiteral("v")).toInt() == 4 && mine.value(QStringLiteral("t")).toString() == QLatin1String("profile"), "my message");
    check(ok(QJsonDocument::fromJson(msg(5, QByteArray(), QStringLiteral("#3a7bd5"))).object()), "a clearing message");
    for (const QJsonValue &x : prof.value(QStringLiteral("versions")).toArray()) {
        const QJsonArray c = x.toArray();
        check((qint64(c.at(1).toDouble()) > qint64(c.at(0).toDouble())) == c.at(2).toBool(), "versions");
    }

    const QJsonObject live = v.value(QStringLiteral("live")).toObject();
    const QJsonArray lgood = live.value(QStringLiteral("good")).toArray();
    for (int i = 0; i < lgood.size(); ++i)
        check(RichWire::ok(QStringLiteral("live"), lgood.at(i).toObject()), QStringLiteral("live good %1").arg(i));
    const QJsonArray lbad = live.value(QStringLiteral("refuse")).toArray();
    for (int i = 0; i < lbad.size(); ++i)
        check(!RichWire::ok(QStringLiteral("live"), lbad.at(i).toObject()), QStringLiteral("live refuses %1").arg(i));
    for (const QJsonValue &x : live.value(QStringLiteral("sent")).toArray()) {
        const QJsonArray c = x.toArray();
        const QJsonObject want = c.at(3).toObject();
        const QJsonObject got = QJsonDocument::fromJson(RichWire::activeMsg(c.at(0).toBool(), 1, c.at(1).toString(), c.at(2).toString())).object();
        check(got == want && RichWire::ok(QStringLiteral("live"), got), QStringLiteral("sent %1 %2").arg(c.at(1).toString(), c.at(2).toString().left(8)));
    }
    for (const QJsonValue &x : live.value(QStringLiteral("shown")).toArray()) {
        const QJsonArray c = x.toArray();
        check(shownStatus(c.at(0).toBool(), c.at(1).toString(), c.at(2).toBool()) == c.at(3).toString(), QStringLiteral("shown %1").arg(c.at(3).toString()));
    }
    check(cleanCustom(QStringLiteral("  Lunch\u0007 ")) == QLatin1String("Lunch"), "custom cleaned");
    check(GroupWire::length(cleanCustom(QString(100, QChar(0xE9)))) == RichWire::kMaxCustom, "custom cut");

    const QJsonObject col = v.value(QStringLiteral("colours")).toObject();
    for (const QJsonValue &c : col.value(QStringLiteral("good")).toArray())
        check(colourOk(c), QStringLiteral("colour %1").arg(c.toString()));
    for (const QJsonValue &c : col.value(QStringLiteral("refuse")).toArray())
        check(!colourOk(c), QStringLiteral("refuses colour %1").arg(c.toString()));
    check(!colourOk(QJsonValue()) && !colourOk(QJsonValue(7)), "colour types");
    for (const QJsonValue &x : col.value(QStringLiteral("default")).toArray()) {
        const QJsonArray c = x.toArray();
        check(defaultColour(c.at(0).toString()) == c.at(1).toString(), QStringLiteral("default colour %1").arg(c.at(1).toString()));
    }

    // The encoder: a big picture ends up 256x256 and within what one relay event carries.
    QImage big(1024, 768, QImage::Format_RGB32);
    for (int y = 0; y < big.height(); ++y)
        for (int x = 0; x < big.width(); ++x)
            big.setPixel(x, y, qRgb(x & 0xFF, y & 0xFF, (x * y) & 0xFF));
    const QByteArray enc = encode(big);
    check(!enc.isEmpty() && enc.size() <= kSendMaxBytes && pictureOk(enc) && ok(QJsonDocument::fromJson(msg(1, enc, QStringLiteral("#3a7bd5"))).object()), "encode");
    check(encode(QImage()).isEmpty(), "encode nothing");
    check(pass > 80, "the vectors have cases");
    std::printf("p1 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
