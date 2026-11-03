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
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QImageReader>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMetaObject>
#include <QMutexLocker>
#include <QThread>
#include <QTimer>
#include <QUrl>
#include <QUuid>
#include <climits>
#include <cstdio>
#include <utility>

const char Profile::kCap[] = "p1";
const char Profile::kCap2[] = "p2";

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

// ------------------------------------------------------------------ cap p2 (22.4): the wire parts and rules

qint64 Profile::versionOf(const QJsonValue &v)
{
    // A whole number, 1 <= v < 2^53 (a JSON number with a fraction, or anything else, is none).
    if (!v.isDouble())
        return 0;
    const double d = v.toDouble();
    if (!(d >= 1) || d >= 9007199254740992.0 || d != double(qint64(d)))
        return 0;
    return qint64(d);
}

QByteArray Profile::askMsg(qint64 have)
{
    const qint64 pv = have < 0 ? 0 : have > 9007199254740991LL ? 9007199254740991LL : have;
    return compact({{QStringLiteral("t"), QStringLiteral("profile?")}, {QStringLiteral("pv"), QJsonValue(pv)}});
}

// An app from before round 16 doesn't say "pv" in its ask; that, and anything unusable, counts as 0 (none), so it is
// still answered within the once-a-day limit.
qint64 Profile::askedPv(const QJsonObject &p)
{
    const QJsonValue v = p.value(QStringLiteral("pv"));
    if (!v.isDouble())
        return 0;
    const double d = v.toDouble();
    if (!(d >= 0) || d >= 9007199254740992.0 || d != double(qint64(d)))
        return 0;
    return qint64(d);
}

bool Profile::mayAnswer(qint64 mine, qint64 theirs, qint64 lastV, qint64 lastAt, qint64 now)
{
    return mine >= 1 && theirs < mine && (lastV != mine || lastAt == 0 || now - lastAt >= kDayMs || now < lastAt);
}

QByteArray Profile::ackMsg(qint64 v) { return compact({{QStringLiteral("t"), QStringLiteral("profile-ok")}, {QStringLiteral("v"), QJsonValue(v)}}); }

bool Profile::isCtl(const QJsonObject &p)
{
    const QString t = p.value(QStringLiteral("t")).toString();
    return t == QLatin1String("profile?") || t == QLatin1String("profile-ok");
}

bool Profile::ctlOk(const QJsonObject &p)
{
    const QString t = p.value(QStringLiteral("t")).toString();
    if (t == QLatin1String("profile?"))
        return true;
    return t == QLatin1String("profile-ok") && versionOf(p.value(QStringLiteral("v"))) > 0;
}

bool Profile::mayAsk(qint64 last, qint64 now, qint64 gap) { return last == 0 || now - last >= gap; }

qint64 Profile::retryMs(int tries)
{
    if (tries <= 1)
        return kFirstRetryMs;
    const int shift = tries - 1 < 20 ? tries - 1 : 20;
    const qint64 wait = qint64(kFirstRetryMs) << shift;
    return wait < kMaxRetryMs ? wait : qint64(kMaxRetryMs);
}

bool Profile::due(int tries, qint64 at, qint64 now, bool old)
{
    if (tries == 0)
        return true;
    if (tries >= kMaxTries)
        return false;
    if (old)
        return now - at >= kDayMs || now < at;
    return now - at >= retryMs(tries);
}

void Profile::DayBudget::roll(qint64 now)
{
    const qint64 d = now >= 0 ? now / kDayMs : -((-now + kDayMs - 1) / kDayMs);
    if (d != day) {
        day = d;
        n = 0;
    }
}

bool Profile::DayBudget::has(qint64 now)
{
    roll(now);
    return n < kDayMax;
}

void Profile::DayBudget::spend(qint64 now)
{
    roll(now);
    ++n;
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
    if (e.pic.isEmpty() && key != kMe && m_linked.contains(key))
        return int(qMin<qint64>(m_linkedV, INT_MAX));   // a linked phone's picture of them (cap l1)
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
    QByteArray bytes = key == kMe ? (m_phoneOn ? m_phonePic : m_me.pic) : m_peers.value(key).pic;
    if (bytes.isEmpty() && key != kMe)
        bytes = m_linked.value(key);
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
    for (auto it = m_linked.cbegin(); it != m_linked.cend(); ++it)
        if (hashOf(it.key()) == id)
            return picture(it.key());
    return {};
}

void Profile::setLinkedPictures(const QHash<QString, QByteArray> &pics)
{
    QHash<QString, QByteArray> ok;
    for (auto it = pics.cbegin(); it != pics.cend(); ++it)
        if (pictureOk(it.value()))
            ok.insert(it.key(), it.value());
    if (ok == m_linked)
        return;
    QSet<QString> touched;
    for (auto it = m_linked.cbegin(); it != m_linked.cend(); ++it)
        touched.insert(it.key());
    for (auto it = ok.cbegin(); it != ok.cend(); ++it)
        touched.insert(it.key());
    m_linked = ok;
    // A new version each time, so a picture URL shown before never stands for these.
    ++m_linkedV;
    for (const QString &k : std::as_const(touched)) {
        m_cache.remove(k);
        Q_EMIT pictureChanged(k);
    }
    share();
    Q_EMIT changed();
}

void Profile::share()
{
    QHash<QString, QByteArray> all;
    if (!m_me.pic.isEmpty())
        all.insert(kMe, m_me.pic);
    if (m_phoneOn)
        all.insert(QStringLiteral("phone"), m_phonePic);   // may be empty: then no picture stands for mine
    for (auto it = m_linked.cbegin(); it != m_linked.cend(); ++it)
        all.insert(hashOf(it.key()), it.value());   // a linked phone's, unless their own is here (below)
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
    m_tries.remove(key);
    m_asked.remove(key);
    m_answered.remove(key);
    m_answerNow.remove(key);
    saveP2();
    m_cache.remove(key);
    m_heard.remove(key);
    m_statuses.remove(key);
    Q_EMIT pictureChanged(key);
    Q_EMIT changed();
}

// ------------------------------------------------------------------ cap p2 (22.4)

bool Profile::saysP2(const QString &key) const { return m_chat->hasCap(key, QLatin1String(kCap2)); }

bool Profile::inTouch(const QString &key) const
{
    const Chat *c = m_chat;
    return (c->isContact(key) || c->isGroupPeer(key)) && !c->isBlocked(key) && !c->isTrashed(key);
}

bool Profile::claim(QHash<QString, qint64> &map, const QString &key, qint64 gap)
{
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    if (!mayAsk(map.value(key, 0), t, gap))
        return false;
    if (map.size() > 2000)
        map.clear();
    map.insert(key, t);
    return true;
}

// Sent once the message being handled is done (its caps noted, its ratchet step kept), never from inside it.
void Profile::sendSmall(const QString &key, const QByteArray &plain)
{
    QTimer::singleShot(0, this, [this, key, plain] {
        Chat *c = m_chat;
        if (!c->ready() || c->moving() || !inTouch(key) || (c->route(key).isEmpty() && c->mailboxOf(key).isEmpty()))
            return;
        c->deliver(key, c->envelope(QStringLiteral("profile"), key, plain, QUuid::createUuid().toString(QUuid::WithoutBraces)), c->mailboxOf(key));
    });
}

void Profile::sawVersion(const QString &key, qint64 pv)
{
    if (!m_loaded || pv <= m_peers.value(key).v || !saysP2(key) || !inTouch(key))
        return;
    if (claim(m_asked, key, kAskMs))
        sendSmall(key, askMsg(m_peers.value(key).v));
}

QString Profile::askPicture(const QString &key)
{
    const QString asked = QStringLiteral("Asked for their picture.");
    // Someone in touch with this computer: asked from here.
    if (m_chat->ready() && inTouch(key) && saysP2(key)) {
        if (claim(m_asked, key, kRefreshMs))
            sendSmall(key, askMsg(m_peers.value(key).v));
        return asked;
    }
    // Otherwise a linked phone that is the account here asks its contact (cap l1).
    if (m_phoneOn && askPhone && askPhone(key))
        return asked;
    return QStringLiteral("Their app can't be asked for it yet.");
}

void Profile::askedBy(const QString &key, qint64 theirs)
{
    if (!m_loaded || m_me.v == 0 || !saysP2(key) || !inTouch(key))
        return;
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    const Answer last = m_answered.value(key);
    if (!mayAnswer(m_me.v, theirs, last.v, last.at, t))
        return;
    // They keep an older one: it goes once now. The retry count isn't reset, so asking never starts a new round.
    m_answered.insert(key, Answer{m_me.v, t});
    m_answerNow.insert(key);
    if (m_sent.remove(key))
        saveSent();
    saveP2();
    reconcileSoon();
}

void Profile::ack(const QString &key, qint64 v)
{
    if (v > 0 && saysP2(key))
        sendSmall(key, ackMsg(v));
}

void Profile::acked(const QString &key, qint64 v)
{
    if (m_me.v < 1 || v != m_me.v)
        return;
    const bool had = m_tries.remove(key);
    m_answerNow.remove(key);
    if (m_sent.value(key, 0) == v) {
        if (had)
            saveP2();
        return;
    }
    m_sent.insert(key, v);
    saveSent();
    saveP2();
}

// ------------------------------------------------------------------ sending

void Profile::reconcileSoon()
{
    if (!m_soon.isActive())
        m_soon.start();
}

// My picture and colour to everyone who should have this version and doesn't yet: contacts, and members of my groups,
// only those whose app said p1, never anyone blocked or in the trash. One is marked sent only once a route took it, so
// someone out of reach gets it on a later pass. Cap p2: an app that says p2 has it only once its "profile-ok" came;
// until then it goes again after 2, 4, 8 ... minutes (at most 6 hours apart, 10 times; after a restart only a day after
// the last send to them). Round 16: at most kDayMax pictures a day in all.
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
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    bool changed = false, counted = false;
    for (const QString &k : std::as_const(keys)) {
        if (c->isBlocked(k) || c->isTrashed(k) || (!c->isContact(k) && !c->isGroupPeer(k)) || !c->hasCap(k, QLatin1String(kCap)))
            continue;
        if (m_sent.value(k, 0) == m_me.v)
            continue;
        if (!m_budget.has(t))
            break;
        const bool p2 = c->hasCap(k, QLatin1String(kCap2));
        Try was = m_tries.value(k);
        if (was.v != m_me.v)
            was = Try{m_me.v, 0, 0, false};
        if (p2 && !m_answerNow.contains(k) && !due(was.n, was.at, t, was.old))
            continue;
        // No way to reach them right now: nothing is sealed (that would use up a ratchet step and a save each pass).
        if (c->route(k).isEmpty() && c->mailboxOf(k).isEmpty())
            continue;
        const QJsonObject env = c->envelope(QStringLiteral("profile"), k, plain, QUuid::createUuid().toString(QUuid::WithoutBraces));
        if (c->deliver(k, env, c->mailboxOf(k)).isEmpty())
            continue;
        m_budget.spend(t);
        counted = true;
        if (p2) {
            m_answerNow.remove(k);
            m_tries.insert(k, Try{m_me.v, was.n + 1, t, false});
            continue;
        }
        m_sent.insert(k, m_me.v);
        changed = true;
    }
    if (changed)
        saveSent();
    if (counted)
        saveP2();
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

// {"day","n","try":{key:[v,n,at]},"ans":{key:[v,at]}}: cap p2's retry state, answers and today's count.
void Profile::saveP2()
{
    if (!m_loaded)
        return;
    QJsonObject tries, ans;
    for (auto it = m_tries.cbegin(); it != m_tries.cend(); ++it)
        tries.insert(it.key(), QJsonArray{QJsonValue(it->v), it->n, QJsonValue(it->at)});
    for (auto it = m_answered.cbegin(); it != m_answered.cend(); ++it)
        ans.insert(it.key(), QJsonArray{QJsonValue(it->v), QJsonValue(it->at)});
    m_vault->write(QStringLiteral("profile/p2.json"),
                   compact({{QStringLiteral("day"), QJsonValue(m_budget.day)}, {QStringLiteral("n"), m_budget.n},
                            {QStringLiteral("try"), tries}, {QStringLiteral("ans"), ans}}));
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
    // Cap p2's limits; one that can't be read starts them anew. Sends counted before this start wait a day (Try::old).
    m_tries.clear();
    m_answered.clear();
    m_answerNow.clear();
    const QJsonObject p2 = QJsonDocument::fromJson(m_vault->read(QStringLiteral("profile/p2.json"))).object();
    m_budget.day = qint64(p2.value(QStringLiteral("day")).toDouble());
    m_budget.n = p2.value(QStringLiteral("n")).toInt();
    const QJsonObject tries = p2.value(QStringLiteral("try")).toObject();
    for (auto it = tries.constBegin(); it != tries.constEnd(); ++it) {
        const QJsonArray a = it.value().toArray();
        m_tries.insert(it.key(), Try{qint64(a.at(0).toDouble()), a.at(1).toInt(), qint64(a.at(2).toDouble()), true});
    }
    const QJsonObject ans = p2.value(QStringLiteral("ans")).toObject();
    for (auto it = ans.constBegin(); it != ans.constEnd(); ++it) {
        const QJsonArray a = it.value().toArray();
        m_answered.insert(it.key(), Answer{qint64(a.at(0).toDouble()), qint64(a.at(1).toDouble())});
    }
    const QDir dir(m_vault->path(QStringLiteral("profile")));
    for (const QString &name : dir.entryList({QStringLiteral("*.json")}, QDir::Files)) {
        if (name == QLatin1String("me.json") || name == QLatin1String("sent.json") || name == QLatin1String("p2.json"))
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
    // "<hash>?v=<n>" or "me?v=<n>": the version only makes QML ask again after a change.
    const QString who = id.section(QLatin1Char('?'), 0, 0);
    const QByteArray bytes = m_profile->sharedPicture(who);
    QImage img = bytes.isEmpty() ? QImage() : QImage::fromData(bytes, "JPEG");
    // No bigger than asked for: the rounding below then works on a small picture.
    const int want = requestedSize.isValid() ? qMax(requestedSize.width(), requestedSize.height()) : 0;
    if (!img.isNull() && want > 0 && qMin(img.width(), img.height()) > want)
        img = img.scaled(want, want, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
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
    check(p2SelfTest(), "cap p2");
    check(pass > 80, "the vectors have cases");
    std::printf("p1 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}

// Cap p2 (protocol.md 22.4): the wire shapes and rules, the same as Muninn's P2Test.
bool Profile::p2SelfTest()
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const char *what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("p2 self-test: FAILED %s\n", what);
    };
    const QStringList caps = QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' '));
    check(caps.last() == QLatin1String(kCap2) && caps.contains(QLatin1String(kCap)), "caps say p2, last, and still p1");
    const auto obj = [](const QByteArray &b) { return QJsonDocument::fromJson(b).object(); };
    check(askMsg(0) == QByteArrayLiteral("{\"pv\":0,\"t\":\"profile?\"}") && obj(askMsg(7)).value(QStringLiteral("pv")).toInteger() == 7
              && obj(askMsg(7)).size() == 2,
          "ask shape (round 16: says the version kept)");
    check(obj(ackMsg(5)).value(QStringLiteral("t")).toString() == QLatin1String("profile-ok") && obj(ackMsg(5)).value(QStringLiteral("v")).toInteger() == 5
              && obj(ackMsg(5)).size() == 2,
          "profile-ok shape");
    check(ctlOk(obj(askMsg(3))) && ctlOk(obj(ackMsg(5))) && isCtl(obj(askMsg(3))) && isCtl(obj(ackMsg(5))), "small ones are well-formed");
    check(ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"x\":1}"))), "ask: other fields ignored");
    check(!ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile-ok\"}"))) && !ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile-ok\",\"v\":0}")))
              && !ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile-ok\",\"v\":\"5\"}"))) && !ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile\",\"v\":5}"))),
          "profile-ok needs a usable v");
    check(!isCtl(obj(msg(2, QByteArray(), QStringLiteral("#3a7bd5")))) && !ok(obj(askMsg(3))) && !ok(obj(ackMsg(5))), "never taken for a picture");
    check(versionOf(QJsonValue(1)) == 1 && versionOf(QJsonValue(qint64(9007199254740991LL))) == 9007199254740991LL && versionOf(QJsonValue(0)) == 0
              && versionOf(QJsonValue(-3)) == 0 && versionOf(QJsonValue(2.5)) == 0 && versionOf(QJsonValue(QStringLiteral("4"))) == 0
              && versionOf(QJsonValue()) == 0 && versionOf(QJsonValue(9007199254740992.0)) == 0,
          "versions");
    check(versionOf(obj(QByteArrayLiteral("{\"room\":\"private\",\"pv\":4}")).value(QStringLiteral("pv"))) == 4, "pv on an envelope");
    const qint64 t = 1000000000;
    check(mayAsk(0, t, kAskMs) && !mayAsk(t, t + kAskMs - 1, kAskMs) && mayAsk(t, t + kAskMs, kAskMs) && kAskMs == 300000
              && kRefreshMs == 10000 && kCtlPer10s == 5,
          "asking is rate limited");
    check(retryMs(1) == 120000 && retryMs(2) == 240000 && retryMs(3) == 480000 && retryMs(8) == 256LL * 60000 && retryMs(9) == 6LL * 3600000
              && retryMs(1000) == 6LL * 3600000,
          "retries back off");
    const qint64 a = 5000000;
    check(due(0, 0, a) && !due(1, a, a + 120000 - 1) && due(1, a, a + 120000) && !due(3, a, a + 7 * 60000) && due(3, a, a + 8 * 60000)
              && due(kMaxTries - 1, a, a + 6LL * 3600000) && !due(kMaxTries, a, a + 100LL * 6 * 3600000) && kMaxTries == 10,
          "retries stop after 10");
    // Round 16 (N2): the same cases as Muninn's P2Test.
    check(askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"pv\":4}"))) == 4 && askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"pv\":0}"))) == 0
              && askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\"}"))) == 0 && ctlOk(obj(QByteArrayLiteral("{\"t\":\"profile?\"}")))
              && askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"pv\":-1}"))) == 0
              && askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"pv\":\"4\"}"))) == 0
              && askedPv(obj(QByteArrayLiteral("{\"t\":\"profile?\",\"pv\":2.5}"))) == 0,
          "an ask says the version kept; an older app's counts as none");
    const qint64 d = 10 * kDayMs;
    check(mayAnswer(5, 4, 0, 0, d) && mayAnswer(5, 0, 0, 0, d) && !mayAnswer(5, 5, 0, 0, d) && !mayAnswer(5, 6, 0, 0, d) && !mayAnswer(0, 0, 0, 0, d),
          "answered only when the asker keeps an older version");
    check(!mayAnswer(5, 4, 5, d, d + 60000) && !mayAnswer(5, 4, 5, d, d + kDayMs - 1) && mayAnswer(5, 4, 5, d, d + kDayMs) && mayAnswer(6, 4, 5, d, d + 1),
          "answered once per version a day");
    {
        qint64 last = 0, lastV = 0;
        int answers = 0;
        for (qint64 now = d; now < d + kDayMs; now += 60000)
            if (mayAnswer(5, 0, lastV, last, now)) {
                ++answers;
                last = now;
                lastV = 5;
            }
        check(answers == 1, "a peer asking every minute for a day gets one answer");
    }
    check(!due(1, a, a + 2 * 60000, true) && !due(1, a, a + kDayMs - 1, true) && due(1, a, a + kDayMs, true) && !due(kMaxTries, a, a + 10 * kDayMs, true)
              && due(0, 0, a, true),
          "a restart waits a day per person");
    {
        DayBudget b;
        const qint64 day = 20000LL * kDayMs;
        int sent = 0;
        for (int i = 0; i < kDayMax * 3; ++i)
            if (b.has(day + i)) {
                b.spend(day + i);
                ++sent;
            }
        const bool full = !b.has(day + kDayMs - 1);
        check(sent == kDayMax && full && b.has(day + kDayMs) && b.n == 0 && kDayMax == 200 && kDayMs == 86400000LL, "at most kDayMax pictures a day");
    }
    std::printf("p2 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
