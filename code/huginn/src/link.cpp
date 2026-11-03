#include "link.h"
#include "busy.h"
#include "chat.h"
#include "sharing.h"
#include "groupwire.h"
#include "chatcrypto.h"
#include "msglimits.h"
#include "profile.h"
#include "richwire.h"
#include "settings.h"
#include "trust.h"
#include "vault.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QStandardPaths>
#include <QWindow>
#include <QJsonArray>
#include <QJsonDocument>
#include <QPointer>
#include <QRandomGenerator>
#include <QThread>
#include <QUuid>
#include <algorithm>
#include <cmath>
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
// The plain "linked to a phone" note beside the vault (Link::linkedWhileLocked). Only there while linked: Unlink
// removes it, so it never says a PC was linked once.
void notePhone(bool linked)
{
    QSettings s = Settings::store();
    if (linked) {
        if (!s.value(QStringLiteral("link/phone"), false).toBool())
            s.setValue(QStringLiteral("link/phone"), true);
    } else if (s.contains(QStringLiteral("link/phone"))) {
        s.remove(QStringLiteral("link/phone"));
    }
}
}

bool Link::linkedWhileLocked() const
{
    if (m_chat->ready()) {
        ensureLoaded();
        return !m_phone.key.isEmpty() && !m_phone.gone;
    }
    return Settings::store().value(QStringLiteral("link/phone"), false).toBool();
}

const char Link::kPrefix[] = "MUNINNL1:";
const char Link::kAcctCap[] = "l1";

Link::Link(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    // Round 15 (N2): a save the saving thread gave up on is written again from what's here.
    m_chat->onSaveLost(QStringLiteral("link-replay.json"), [this] {
        if (m_replayLoaded && m_chat->ready())
            m_chat->m_vault->write(QStringLiteral("link-replay.json"), m_replay.save());
    });
    m_chat->onSaveLost(QStringLiteral("mirror.json"), [this] {
        if (m_loaded && m_chat->ready())
            saveMirror();
    });
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
        if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
            return;
        // Cap l1: a phone linked before this version is asked once a run whether it keeps the whole account in step.
        if (!m_phone.l1 && !m_capsAsked && inFront()) {
            m_capsAsked = true;
            sendTo(m_phone, syncFrame(false));
        }
        if (m_want.isEmpty()) {
            for (const Mirror &c : std::as_const(m_mirror))
                for (const M &x : c.msgs)
                    want(x);
        }
        fetchNext();
    });
    m_fetchTimer.start();
    // Cap l2: chats read here go to the phone a moment later, together ("read", the newest time each).
    m_readSoon.setSingleShot(true);
    m_readSoon.setInterval(1500);
    connect(&m_readSoon, &QTimer::timeout, this, [this] {
        const QSet<QString> owed = std::exchange(m_readOwed, {});
        if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
            return;
        for (const QString &peer : owed) {
            const Mirror c = m_mirror.value(peer);
            if (c.readAt > 0)
                sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("read")}, {QStringLiteral("peer"), peer}, {QStringLiteral("upTo"), double(c.readAt)}});
        }
    });
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
    auto dev = [](const QJsonObject &d) {
        return Device{jstr(d, "k"), jstr(d, "p"), jstr(d, "n"), d.value(QStringLiteral("ok")).toBool(true), d.value(QStringLiteral("l")).toBool(),
                      d.value(QStringLiteral("gone")).toBool(), d.value(QStringLiteral("l2")).toBool()};
    };
    for (const QJsonValue &v : o.value(QStringLiteral("companions")).toArray())
        m_companions << dev(v.toObject());
    if (o.contains(QStringLiteral("phone")))
        m_phone = dev(o.value(QStringLiteral("phone")).toObject());
    notePhone(!m_phone.key.isEmpty() && !m_phone.gone);
    m_meName.clear();
    m_meStatus.clear();
    m_meCustom.clear();
    m_mePic.clear();
    m_own = m_phone.key.isEmpty() ? QJsonObject() : o.value(QStringLiteral("own")).toObject();
    m_nameWanted = o.value(QStringLiteral("askName")).toBool();
    m_drafts = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link-drafts.json"))).object();
    if (!m_phone.key.isEmpty()) {
        const QVariantMap me = readMe(o.value(QStringLiteral("me")).toObject());
        m_meName = vstr(me, "name");
        m_meStatus = vstr(me, "status");
        m_meCustom = vstr(me, "custom");
        m_mePic = me.value(QStringLiteral("pic")).toByteArray();
    }
    // Cap l1: the phone's account, as last merged here.
    m_acct.clear();
    m_acctFresh = false;
    if (!m_phone.key.isEmpty()) {
        const QJsonObject ao = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link-account.json"))).object();
        m_acctFresh = ao.value(QStringLiteral("fresh")).toBool();
        for (const QJsonValue &v : ao.value(QStringLiteral("items")).toArray()) {
            bool ok = false;
            const Item i = readItem(v.toObject(), &ok);
            if (ok)
                m_acct.insert(i.key(), i);
        }
    }
    rebuildPics();
    QMetaObject::invokeMethod(const_cast<Link *>(this), [self = const_cast<Link *>(this)] { Q_EMIT self->picturesChanged(); }, Qt::QueuedConnection);
    const QJsonObject mo = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("mirror.json"))).object();
    for (auto it = mo.begin(); it != mo.end(); ++it) {
        const QJsonObject c = it.value().toObject();
        Mirror m{jstr(c, "peer"), jstr(c, "n"), {}, daysOf(c, Limits::kDefault)};
        for (const QJsonValue &v : c.value(QStringLiteral("msgs")).toArray())
            m.msgs << mOf(v.toObject());
        // Read up to: kept from before this was saved, everything counts as read.
        m.readAt = c.contains(QStringLiteral("r")) ? qint64(c.value(QStringLiteral("r")).toDouble()) : m.msgs.isEmpty() ? 0 : m.msgs.last().time;
        const QJsonObject seen = c.value(QStringLiteral("s")).toObject();
        for (auto s = seen.begin(); s != seen.end(); ++s)
            if (qint64(s.value().toDouble()) > m.readAt)
                m.seen.insert(s.key(), qint64(s.value().toDouble()));
        if (!m.peer.isEmpty())
            m_mirror.insert(m.peer, m);
    }
}

void Link::save()
{
    auto devJson = [](const Device &d) {
        QJsonObject j{{QStringLiteral("k"), d.key}, {QStringLiteral("p"), d.mailbox}, {QStringLiteral("n"), d.name}, {QStringLiteral("ok"), d.ok}};
        if (d.l1)
            j.insert(QStringLiteral("l"), true);
        if (d.gone)
            j.insert(QStringLiteral("gone"), true);
        if (d.l2)
            j.insert(QStringLiteral("l2"), true);
        return j;
    };
    QJsonArray c;
    for (const Device &d : std::as_const(m_companions))
        c.append(devJson(d));
    QJsonObject o{{QStringLiteral("companions"), c}};
    if (m_nameWanted)
        o.insert(QStringLiteral("askName"), true);
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
        if (!m_own.isEmpty())
            o.insert(QStringLiteral("own"), m_own);
    }
    m_chat->m_vault->write(QStringLiteral("link.json"), compact(o));
    notePhone(!m_phone.key.isEmpty() && !m_phone.gone);
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
            if (!x.from.isEmpty())
                j.insert(QStringLiteral("fn"), x.from);
            if (!x.ch.isEmpty())
                j.insert(QStringLiteral("ch"), x.ch);
            if (!x.re.isEmpty())
                j.insert(QStringLiteral("re"), x.re);
            if (x.edited > 0)
                j.insert(QStringLiteral("ed"), double(x.edited));
            a.append(j);
        }
        QJsonObject c{{QStringLiteral("peer"), m.peer}, {QStringLiteral("n"), m.name}, {QStringLiteral("d"), m.days}, {QStringLiteral("msgs"), a},
                      {QStringLiteral("r"), double(m.readAt)}};
        if (!m.seen.isEmpty()) {
            QJsonObject seen;
            for (auto it = m.seen.cbegin(); it != m.seen.cend(); ++it)
                seen.insert(it.key(), double(it.value()));
            c.insert(QStringLiteral("s"), seen);
        }
        o.insert(m.peer, c);
    }
    m_chat->m_vault->write(QStringLiteral("mirror.json"), compact(o));
    Q_EMIT changed();
}

// ------------------------------------------------------------------ round 14: replay

QJsonObject LinkReplay::stamp(QJsonObject msg, qint64 nowMs)
{
    quint32 r[4];
    QRandomGenerator::system()->fillRange(r);
    QByteArray b;
    for (quint32 x : r)
        for (int i = 3; i >= 0; --i)
            b.append(char((x >> (8 * i)) & 0xff));
    msg.insert(QStringLiteral("lts"), double(nowMs));
    msg.insert(QStringLiteral("ln"), QString::fromLatin1(b.toHex()));
    return msg;
}

const char LinkReplay::kStampCap[] = "l4";

qint64 LinkReplay::skewOf(const QJsonObject &p, qint64 nowMs)
{
    const QJsonValue ltv = p.value(QStringLiteral("lts"));
    return ltv.isDouble() ? qint64(ltv.toDouble()) - nowMs : 0;
}

void LinkReplay::pinged(const QString &to, const QString &pn, qint64 nowMs)
{
    m_pings.insert(to, Ping{pn, nowMs});
}

void LinkReplay::forget(const QString &from)
{
    m_pings.remove(from);
    m_lastStamped.remove(from);
    const bool stamped = m_stamps.remove(from);
    const bool offset = m_offset.remove(from);
    const bool older = m_older.remove(from);
    m_quiet.remove(from);
    m_news.remove(from);
    if (stamped || offset || older)
        m_dirty = true;
}

// A stamped frame from it was taken: it stamps (again, after an update back to the beta), and isn't on an older version.
void LinkReplay::stamped(const QString &from)
{
    if (!m_stamps.contains(from)) {
        m_stamps.insert(from);
        m_dirty = true;
    }
    m_quiet.remove(from);
    if (m_older.remove(from))
        m_dirty = true;
    m_news.remove(from);
}

LinkReplay::Verdict LinkReplay::judge(const QString &from, const QJsonObject &p, qint64 nowMs, bool direct)
{
    const QString type = p.value(QStringLiteral("t")).toString();
    if (!p.contains(QStringLiteral("ln"))) {
        // Round 16, cap l4 (protocol.md 15, "Going back to an older version"): once a device has stamped, an unstamped
        // frame from it is never taken, whatever its caps say (a relay could replay one from before 1.36). A "hello"
        // needs its one-time token, so it's taken (that is linking again). One that keeps sending only unstamped
        // frames is found on an older version, and the person is asked to link it again.
        if (!m_stamps.contains(from) || type == QLatin1String("hello"))
            return Verdict::Taken;
        // Round 17 (m11): only an unstamped frame straight over Wi-Fi or Bluetooth, or one within 2 minutes after a ping
        // to it that no stamped frame answered, counts toward "on an older version": a relay replaying old frames can't
        // raise the question.
        const auto pinged = m_pings.constFind(from);
        const bool unanswered = pinged != m_pings.constEnd() && nowMs >= pinged->at && nowMs - pinged->at <= 120000
                                && m_lastStamped.value(from, 0) < pinged->at;
        if (!direct && !unanswered)
            return Verdict::Unstamped;
        Quiet &q = m_quiet[from];
        if (q.count == 0)
            q.first = nowMs;
        ++q.count;
        if (!m_older.contains(from) && olderBy(q.first, q.count, nowMs)) {
            m_older.insert(from);
            m_news.insert(from);
            m_dirty = true;
        }
        return Verdict::Unstamped;
    }
    const QJsonValue lnv = p.value(QStringLiteral("ln")), ltv = p.value(QStringLiteral("lts"));
    // (round 17: the time check below applies whatever route the frame came on)
    const QString ln = lnv.toString();
    if (!lnv.isString() || ln.size() != 32 || !std::all_of(ln.cbegin(), ln.cend(), [](QChar c) { const char16_t u = c.unicode(); return (u >= u'0' && u <= u'9') || (u >= u'a' && u <= u'f'); }))
        return Verdict::Dropped;
    const double d = ltv.toDouble();
    if (!ltv.isDouble() || d != std::floor(d))
        return Verdict::Dropped;
    const qint64 t = qint64(d);
    // Round 17 (security M2): every frame, whatever route it came on, is held to the 36 hours from either this clock or
    // this clock moved by how far that device's clock is off. The offset is learned only from a pong straight over
    // Wi-Fi or Bluetooth answering this app's own ping within 30 seconds (and echoing its "pn" when it says one), so a
    // device with a wrong date keeps working once the two have met, and an old frame handed over on the LAN is still
    // more than 36 hours behind both.
    const auto within = [&](qint64 at) { return t >= at - kMaxAgeMs && t <= at + kMaxAgeMs; };
    const auto asked = m_pings.constFind(from);
    const bool challenged = direct && type == QLatin1String("pong") && asked != m_pings.constEnd() && nowMs >= asked->at
                            && nowMs - asked->at <= kChallengeMs
                            && (!p.contains(QStringLiteral("pn")) || p.value(QStringLiteral("pn")).toString() == asked->pn);
    if (challenged) {
        m_pings.remove(from);
        const qint64 off = t - nowMs;
        if (qAbs(off) > kOffsetMinMs) {
            // Kept again only when it moved by more than 10 minutes, so frames don't each write the file.
            if (!m_offset.contains(from) || qAbs(m_offset.value(from) - off) > 600000) {
                m_offset.insert(from, off);
                m_dirty = true;
            }
        } else if (m_offset.remove(from)) {
            m_dirty = true;
        }
    }
    if (!within(nowMs) && !(m_offset.contains(from) && within(nowMs + m_offset.value(from))))
        return Verdict::Skewed;
    m_lastStamped.insert(from, nowMs);
    for (auto it = m_seen.begin(); it != m_seen.end();) {
        if (it.value() < nowMs)
            it = m_seen.erase(it);
        else
            ++it;
    }
    // A stamped frame makes it a device that stamps (again, after an update back to the beta).
    if (type == QLatin1String("chat") || type == QLatin1String("part") || type == QLatin1String("ping") || type == QLatin1String("pong")) {
        stamped(from);
        return Verdict::Taken;
    }
    const QString key = from + QLatin1Char('\n') + ln;
    if (m_seen.contains(key))
        return Verdict::Dropped;
    stamped(from);
    m_seen.insert(key, qMax(nowMs, t) + kMaxAgeMs);
    while (m_seen.size() > kMaxSeen) {
        auto oldest = m_seen.begin();
        for (auto it = m_seen.begin(); it != m_seen.end(); ++it)
            if (it.value() < oldest.value())
                oldest = it;
        m_seen.erase(oldest);
    }
    m_dirty = true;
    return Verdict::Taken;
}

QByteArray LinkReplay::save()
{
    m_dirty = false;
    QJsonObject n;
    for (auto it = m_seen.cbegin(); it != m_seen.cend(); ++it)
        n.insert(it.key(), double(it.value()));
    QJsonArray st;
    for (const QString &k : std::as_const(m_stamps))
        st.append(k);
    QJsonObject off;
    for (auto it = m_offset.cbegin(); it != m_offset.cend(); ++it)
        off.insert(it.key(), double(it.value()));
    QJsonArray old;
    for (const QString &k : std::as_const(m_older))
        old.append(k);
    return QJsonDocument(QJsonObject{{QStringLiteral("s"), st}, {QStringLiteral("n"), n}, {QStringLiteral("o2"), off}, {QStringLiteral("old"), old}})
        .toJson(QJsonDocument::Compact);
}

void LinkReplay::load(const QByteArray &json)
{
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    for (const QJsonValue &v : o.value(QStringLiteral("s")).toArray())
        if (!v.toString().isEmpty())
            m_stamps.insert(v.toString());
    const QJsonObject n = o.value(QStringLiteral("n")).toObject();
    for (auto it = n.constBegin(); it != n.constEnd(); ++it)
        m_seen.insert(it.key(), qint64(it.value().toDouble()));
    // Round 17: "o2", offsets learned only from answered pings; the older "o" (learned from any Wi-Fi frame) is dropped.
    const QJsonObject off = o.value(QStringLiteral("o2")).toObject();
    for (auto it = off.constBegin(); it != off.constEnd(); ++it)
        if (it.value().isDouble())
            m_offset.insert(it.key(), qint64(it.value().toDouble()));
    for (const QJsonValue &v : o.value(QStringLiteral("old")).toArray())
        if (!v.toString().isEmpty())
            m_older.insert(v.toString());
}

void Link::loadReplay() const
{
    if (m_replayLoaded)
        return;
    m_replayLoaded = true;
    m_replay.load(m_chat->m_vault->read(QStringLiteral("link-replay.json")));
}

void Link::forgetStamps(const QString &key)
{
    loadReplay();
    const bool wasOlder = m_replay.olderOnes().contains(key);
    m_replay.forget(key);
    if (m_replay.dirty())
        m_chat->m_vault->write(QStringLiteral("link-replay.json"), m_replay.save());
    if (wasOlder)
        Q_EMIT changed();
}

LinkReplay::Verdict Link::replayVerdict(const QString &from, const QJsonObject &p, const QString &via)
{
    loadReplay();
    const LinkReplay::Verdict v = m_replay.judge(from, p, now(), via == QLatin1String("wifi") || via == QLatin1String("bluetooth"));
    if (m_replay.dirty())
        m_chat->m_vault->write(QStringLiteral("link-replay.json"), m_replay.save());
    return v;
}

void Link::reprobe()
{
    // Round 15 (cap l4): the phone sent a frame without a stamp after it had stamped (it went back to an older app, or
    // the frame is a relay's old copy). A ping asks it what it is now. Round 16: an older app's pong (unstamped) is
    // never taken either; it only counts toward finding the phone on an older version (LinkReplay::found).
    // Sent whether or not this window is in front, at most every 2 minutes.
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
        return;
    const qint64 t = now();
    if (m_reprobeAt > 0 && t - m_reprobeAt < 120000)
        return;
    m_reprobeAt = t;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("ping")}, {QStringLiteral("caps"), linkCaps()}, {QStringLiteral("dn"), myDeviceName()},
                                {QStringLiteral("pn"), pingNonce()}});
}

QString Link::pingNonce()
{
    // Round 17 (security M2): only the pong that answers this ping within 30 seconds teaches the phone's clock offset.
    const QString pn = QString::number(QRandomGenerator::system()->generate64(), 16).rightJustified(16, QLatin1Char('0'));
    m_replay.pinged(m_phone.key, pn, now());
    return pn;
}

void Link::sayClockOff(const QString &from, qint64 skewMs)
{
    // Round 15: a device whose clock is more than 36 hours from this one is said once a run. Its frames over Wi-Fi and
    // Bluetooth are taken, and then its relay frames too (LinkReplay keeps the difference); a relay frame before that is
    // dropped (it could be an old copy).
    if (m_skewSaid.contains(from))
        return;
    m_skewSaid.insert(from);
    QString name;
    if (!m_phone.key.isEmpty() && m_phone.key == from)
        name = m_phone.name;
    for (const Device &d : std::as_const(m_companions))
        if (d.key == from)
            name = d.name;
    if (name.isEmpty())
        name = QStringLiteral("Your linked device");
    const qint64 days = qMax<qint64>(2, (qAbs(skewMs) + 43200000) / 86400000);
    Q_EMIT m_chat->notice(QStringLiteral("%1's clock and this computer's are about %2 days apart. Set the right date and time on whichever is "
                                         "wrong. Until then, what comes over the internet may wait until both are on the home Wi-Fi.")
                              .arg(name)
                              .arg(days));
}

void Link::sendTo(const Device &d, const QJsonObject &msg)
{
    // Never history or a picture over the internet: those only on the home Wi-Fi (dropped when it isn't there now;
    // the callers keep what they owe).
    if (!internetOk(msg)) {
        sendLan(d.key, msg);
        return;
    }
    // Sealed like a private message: Bluetooth, the Wi-Fi and at most 2 relays. Round 14: stamped once ("lts", "ln").
    m_chat->sendLink(d.key, LinkReplay::stamp(msg, now()), d.mailbox.size() == 64 ? d.mailbox : QString());
}

bool Link::internetOk(const QJsonObject &msg)
{
    const QString t = jstr(msg, "t");
    if (t == QLatin1String("me"))
        return !msg.contains(QStringLiteral("pic"));
    if (t == QLatin1String("acct"))
        return acctInternetOk(msg);
    return t == QLatin1String("msg") || t == QLatin1String("send") || t == QLatin1String("hello") || t == QLatin1String("unlink")
        || t == QLatin1String("result") || t == QLatin1String("sync") || t == QLatin1String("setme")
        // Cap l2 (15.2).
        || t == QLatin1String("ping") || t == QLatin1String("pong") || t == QLatin1String("read")
        // Cap p2 (22.4): Refresh, asked of the phone.
        || t == QLatin1String("pask");
}

QString Link::errText(const QString &err)
{
    if (err == QLatin1String("confirm"))
        return QStringLiteral("Confirm the emoji on your phone");
    if (err == QLatin1String("not a contact"))
        return QStringLiteral("Not a contact on your phone");
    if (err == QLatin1String("not in group"))
        return QStringLiteral("You're not in this group any more");
    if (err == QLatin1String("admins only"))
        return QStringLiteral("Only admins post in that channel. Pick another channel above, or post from your phone.");
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
    const QByteArray json = compact(m_chat->envelope(QStringLiteral("link"), key, compact(LinkReplay::stamp(msg, now())), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    m_chat->post(base, json, std::move(done));
    return true;
}

QVariantMap Link::phone() const
{
    ensureLoaded();
    if (m_phone.key.isEmpty())
        return {};
    return {{QStringLiteral("key"), m_phone.key}, {QStringLiteral("name"), m_phone.name}, {QStringLiteral("ok"), m_phone.ok},
            {QStringLiteral("gone"), m_phone.gone}, {QStringLiteral("l1"), m_phone.l1}, {QStringLiteral("l2"), m_phone.l2},
            {QStringLiteral("heard"), double(m_heardAt)}, {QStringLiteral("asked"), double(m_pingAt)},
            {QStringLiteral("answered"), m_pingAt > 0 && m_pongAt >= m_pingAt}, {QStringLiteral("lan"), lanHere(m_phone.key)},
            {QStringLiteral("home"), home()}};
}

namespace
{
// A message's channel ("main" when the phone gave none).
QString chOf(const Link::M &x)
{
    return x.ch.isEmpty() ? QStringLiteral("main") : x.ch;
}
// Unread: after readAt, and in a group after its channel's own seen time (its tab was on screen here).
bool unreadMsg(const Link::M &x, qint64 readAt, const QHash<QString, qint64> &seen)
{
    return !x.mine && x.time > qMax(readAt, seen.value(chOf(x)));
}
int unreadIn(const QList<Link::M> &msgs, qint64 readAt, const QHash<QString, qint64> &seen)
{
    int n = 0;
    for (const Link::M &x : msgs)
        if (unreadMsg(x, readAt, seen))
            n++;
    return n;
}
}

int Link::unread() const
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
        return 0;
    int n = 0;
    for (const Mirror &m : std::as_const(m_mirror))
        if (!m_chat->isMuted(QStringLiteral("mirror:") + m.peer) && unreadIn(m.msgs, m.readAt, m.seen) > 0)
            ++n;   // chats, like the badge's own chats (not messages)
    return n;
}

QString Link::olderText(bool phone)
{
    return QStringLiteral("Your linked %1 is on an older version. Link again to keep using it.").arg(phone ? QStringLiteral("phone") : QStringLiteral("computer"));
}

QVariantList Link::older() const
{
    ensureLoaded();
    if (!m_chat->ready())
        return {};
    loadReplay();
    const QSet<QString> old = m_replay.olderOnes();
    QVariantList out;
    if (!m_phone.key.isEmpty() && m_phone.ok && !m_phone.gone && old.contains(m_phone.key))
        out << QVariantMap{{QStringLiteral("key"), m_phone.key}, {QStringLiteral("name"), m_phone.name}, {QStringLiteral("phone"), true},
                           {QStringLiteral("text"), olderText(true)}};
    for (const Device &d : std::as_const(m_companions))
        if (d.ok && old.contains(d.key))
            out << QVariantMap{{QStringLiteral("key"), d.key}, {QStringLiteral("name"), d.name}, {QStringLiteral("phone"), false},
                               {QStringLiteral("text"), olderText(false)}};
    return out;
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
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone || m_meName.isEmpty())
        return {};
    return {{QStringLiteral("name"), m_meName}, {QStringLiteral("status"), m_meStatus}, {QStringLiteral("custom"), m_meCustom},
            {QStringLiteral("picture"), !m_mePic.isEmpty()}};
}

QByteArray Link::mePicture() const
{
    ensureLoaded();
    return m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone ? QByteArray() : m_mePic;
}

QString Link::meStatus() const
{
    ensureLoaded();
    return m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone || m_meName.isEmpty() ? QString() : m_meStatus;
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

bool Link::askPicture(const QString &peer)
{
    ensureLoaded();
    // Cap p2 (protocol.md 22.4): the phone asks its contact for their current picture; it comes here as l1 pictures do.
    if (peer.isEmpty() || m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone || !m_phone.l1)
        return false;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("pask")}, {QStringLiteral("peer"), peer}});
    return true;
}

QString Link::setMe(const QString &name, const QString &status, const QString &custom)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
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

bool Link::restoreOwn()
{
    if (m_own.isEmpty())
        return false;
    const QJsonObject own = std::exchange(m_own, {});
    if (setMyProfile)
        setMyProfile(jstr(own, "n"), jstr(own, "st"), jstr(own, "cs"));
    if (setMyPicture)
        setMyPicture(QByteArray::fromBase64(jstr(own, "pic").toLatin1()));
    return !jstr(own, "n").isEmpty();   // it had no name of its own (linked first): ask for one
}

void Link::forgetPhone()
{
    // Cap l1 made the phone's name and picture this device's own: its own from before come back. Not known (linked
    // before they were kept): the phone's picture goes, and the screens ask for a name.
    const bool took = m_phone.l1;
    m_ownBack = restoreOwn() || !took;
    if (!m_ownBack && setMyPicture)
        setMyPicture(QByteArray());
    m_phone = Device();
    m_ownOpen = false;
    m_mirror.clear();
    m_meName.clear();
    m_meStatus.clear();
    m_meCustom.clear();
    m_mePic.clear();
    m_pending.clear();
    m_askedAt = 0;
    m_heardAt = m_pingAt = m_pongAt = 0;
    m_readOwed.clear();
    m_freshPages.clear();
    m_acct.clear();
    m_acctFresh = false;
    saveAcct();
    saveMirror();
    dropMedia();
    if (!m_drafts.isEmpty()) {
        m_drafts = QJsonObject();
        m_chat->m_vault->remove(QStringLiteral("link-drafts.json"));
        Q_EMIT draftsChanged();
    }
}

QVariantMap Link::drafts() const
{
    ensureLoaded();
    return m_drafts.toVariantMap();
}

void Link::setDraft(const QString &peer, const QString &text, const QString &re)
{
    ensureLoaded();
    if (peer.isEmpty() || !m_chat->ready())
        return;
    const QJsonObject before = m_drafts.value(peer).toObject();
    if (text.isEmpty() && re.isEmpty()) {
        if (!m_drafts.contains(peer))
            return;
        m_drafts.remove(peer);
    } else {
        const QJsonObject d{{QStringLiteral("text"), GroupWire::clip(text, Chat::kMax)}, {QStringLiteral("re"), re.left(200)}};
        if (d == before)
            return;
        m_drafts.insert(peer, d);
    }
    m_chat->m_vault->write(QStringLiteral("link-drafts.json"), compact(m_drafts));
    Q_EMIT draftsChanged();
}

bool Link::nameWanted() const
{
    ensureLoaded();
    return m_nameWanted;
}

void Link::nameGiven()
{
    ensureLoaded();
    if (!m_nameWanted)
        return;
    m_nameWanted = false;
    save();
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
        // "f": the first one after the emoji, which the phone takes even inside the 10 minutes. After "Erase and link my
        // phone again" it also says "relink", once, so the phone asks whether to replace this PC's old entry.
        QJsonObject first = syncFrame(true);
        QSettings s = Settings::store();
        if (s.value(QStringLiteral("link/relink"), false).toBool()) {
            first.insert(QStringLiteral("relink"), true);
            s.remove(QStringLiteral("link/relink"));
        }
        sendTo(m_phone, first);
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
    // A group's text channels from its signed state, in its order: [{id, name, ao}] (ao: only admins post, 28.2).
    auto chansOf = [](const Item &i) {
        QVariantList chans;
        for (const QJsonValue &v : i.v.value(QStringLiteral("st")).toObject().value(QStringLiteral("channels")).toArray()) {
            const QJsonObject c = v.toObject();
            if (jstr(c, "t") != QLatin1String("text") || jstr(c, "id").isEmpty())
                continue;
            const QString n = jstr(c, "n").simplified().left(32);
            chans << QVariantMap{{QStringLiteral("id"), jstr(c, "id")}, {QStringLiteral("name"), n.isEmpty() ? QStringLiteral("general") : n},
                                 {QStringLiteral("ao"), c.value(QStringLiteral("ao")).toBool()}};
        }
        return chans;
    };
    // The group's about text and rules (cap g3), from its signed state; paragraphs as lines.
    auto aboutOf = [](const Item &i) {
        return i.v.value(QStringLiteral("st")).toObject().value(QStringLiteral("about")).toString().replace(QChar(0x2029), QLatin1Char('\n')).left(600);
    };
    // Tabs only once the phone has sent an item with its channel (J4-2): an older Muninn sends none, so everything would
    // land in the first tab and the others would stay empty.
    const bool tabbed = std::any_of(all.cbegin(), all.cend(), [](const Mirror *m) {
        return std::any_of(m->msgs.cbegin(), m->msgs.cend(), [](const M &x) { return !x.ch.isEmpty(); });
    });
    QVariantList out;
    for (const Mirror *m : all) {
        // Cap l1: the name the account gives (a nickname first), the folders it's in, and my role in a group.
        QString name = m->name, role;
        QStringList folders;
        QVariantList chans;
        QString about;
        if (m_phone.l1) {
            const bool group = m->peer.startsWith(QLatin1String("g:"));
            const Item i = m_acct.value(group ? QStringLiteral("group|") + m->peer.mid(2) : QStringLiteral("contact|") + m->peer);
            if (!i.del && !i.v.isEmpty()) {
                const QString n = (group ? QString() : jstr(i.v, "nn")).trimmed();
                name = n.isEmpty() ? (jstr(i.v, "n").trimmed().isEmpty() ? name : jstr(i.v, "n").left(40)) : n.left(40);
                if (group) {
                    const QString r = jstr(i.v, "role");
                    role = r == QLatin1String("owner") ? QStringLiteral("Owner") : r == QLatin1String("admin") ? QStringLiteral("Admin") : QStringLiteral("Member");
                    if (tabbed)
                        chans = chansOf(i);
                    about = aboutOf(i);
                }
            }
            for (const Item &f : std::as_const(m_acct))
                if (f.k == QLatin1String("folder") && !f.del && f.v.value(QStringLiteral("k")).toArray().contains(QJsonValue(m->peer)))
                    folders << jstr(f.v, "n");
            folders.sort(Qt::CaseInsensitive);
        }
        // Per channel: how many are unread, and how many of those are for me (an @mention, or a reply to one of mine).
        QVariantMap tabs;
        if (chans.size() > 1) {
            QHash<QString, int> n, me;
            for (const M &x : m->msgs) {
                if (!unreadMsg(x, m->readAt, m->seen))
                    continue;
                n[chOf(x)]++;
                if ((!m_meName.isEmpty() && RichWire::mentions(x.text, m_meName))
                    || (!x.re.isEmpty() && std::any_of(m->msgs.cbegin(), m->msgs.cend(), [&](const M &y) { return y.id == x.re && y.mine; })))
                    me[chOf(x)]++;
            }
            for (auto it = n.cbegin(); it != n.cend(); ++it)
                tabs.insert(it.key(), QVariantMap{{QStringLiteral("n"), it.value()}, {QStringLiteral("me"), me.value(it.key())}});
        }
        const M *l = m->msgs.isEmpty() ? nullptr : &m->msgs.last();
        out << QVariantMap{{QStringLiteral("peer"), m->peer}, {QStringLiteral("name"), name},
                           {QStringLiteral("last"), !l ? QString() : (l->mine ? QStringLiteral("You: ") : l->from.isEmpty() ? QString() : l->from + QStringLiteral(": ")) + l->text.left(80)},
                           {QStringLiteral("time"), double(last(m))}, {QStringLiteral("role"), role}, {QStringLiteral("folders"), folders.join(QStringLiteral(", "))},
                           {QStringLiteral("unread"), unreadIn(m->msgs, m->readAt, m->seen)}, {QStringLiteral("muted"), m_chat->isMuted(QStringLiteral("mirror:") + m->peer)},
                           {QStringLiteral("group"), m->peer.startsWith(QLatin1String("g:"))}, {QStringLiteral("chans"), chans},
                           {QStringLiteral("tabs"), tabs}, {QStringLiteral("about"), about}};
    }
    // Cap l1: a group on the phone with nothing here yet (just joined, or no messages) still shows, at the end.
    if (m_phone.l1) {
        for (const Item &i : std::as_const(m_acct)) {
            const QString peer = QStringLiteral("g:") + i.id;
            if (i.k != QLatin1String("group") || i.del || !jstr(i.v, "gone").isEmpty() || m_mirror.contains(peer))
                continue;
            const QString r = jstr(i.v, "role");
            out << QVariantMap{{QStringLiteral("peer"), peer}, {QStringLiteral("name"), jstr(i.v, "n").trimmed().isEmpty() ? QStringLiteral("Group") : jstr(i.v, "n").left(40)},
                                 {QStringLiteral("last"), QString()}, {QStringLiteral("time"), 0.0},
                                 {QStringLiteral("role"), r == QLatin1String("owner") ? QStringLiteral("Owner") : r == QLatin1String("admin") ? QStringLiteral("Admin") : QStringLiteral("Member")},
                                 {QStringLiteral("folders"), QString()}, {QStringLiteral("unread"), 0},
                                 {QStringLiteral("muted"), m_chat->isMuted(QStringLiteral("mirror:") + peer)}, {QStringLiteral("group"), true},
                                 {QStringLiteral("chans"), tabbed ? chansOf(i) : QVariantList()},
                                 {QStringLiteral("tabs"), QVariantMap()}, {QStringLiteral("about"), aboutOf(i)}};
        }
    }
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
                           {QStringLiteral("name"), x.name}, {QStringLiteral("size"), double(x.size)}, {QStringLiteral("from"), x.from},
                           {QStringLiteral("ch"), x.ch.isEmpty() ? QStringLiteral("main") : x.ch}, {QStringLiteral("re"), x.re},
                           {QStringLiteral("edited"), double(x.edited)}};
    for (const Pending &p : m_pending)
        if (p.peer == peer)
            out << QVariantMap{{QStringLiteral("id"), p.id}, {QStringLiteral("text"), p.text}, {QStringLiteral("mine"), true}, {QStringLiteral("time"), double(p.time)},
                               {QStringLiteral("kind"), QString()}, {QStringLiteral("media"), QString()}, {QStringLiteral("name"), QString()},
                               {QStringLiteral("size"), 0.0}, {QStringLiteral("pending"), true}, {QStringLiteral("failed"), !p.err.isEmpty()},
                               {QStringLiteral("err"), p.err}, {QStringLiteral("ch"), p.ch.isEmpty() ? QStringLiteral("main") : p.ch},
                               {QStringLiteral("re"), p.re}, {QStringLiteral("edited"), 0.0}};
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
        if (ok)
            m_lastSaved = path;
        return ok ? QString() : QStringLiteral("Couldn't save it.");
    }
    return QStringLiteral("That message isn't here any more.");
}

QString Link::safeName(const QString &raw, const QString &fallback)
{
    QString name;
    for (const QChar c : raw) {
        if (Sharing::hiddenChar(c.unicode()))
            continue;   // round 17 (m8): hidden direction and zero-width marks only ever hide what a file is
        name += (c.unicode() < 0x20 || c.unicode() == 0x7f || QStringLiteral("<>:\"/\\|?*").contains(c)) ? QChar(QLatin1Char('_')) : c;
    }
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
    // Round 17 (m8): COM and LPT with a superscript 1, 2 or 3, and CONIN$ and CONOUT$, are Windows' too.
    const QChar n = stem.size() == 4 ? stem.at(3) : QChar();
    const bool numbered = stem.size() == 4 && (stem.startsWith(QLatin1String("COM")) || stem.startsWith(QLatin1String("LPT")))
                          && ((n >= QLatin1Char('1') && n <= QLatin1Char('9')) || n == QChar(0x00B9) || n == QChar(0x00B2) || n == QChar(0x00B3));
    if (stem == QLatin1String("CON") || stem == QLatin1String("PRN") || stem == QLatin1String("AUX") || stem == QLatin1String("NUL")
        || stem == QLatin1String("CONIN$") || stem == QLatin1String("CONOUT$") || numbered)
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
    // The phone lists this computer by its device name (Settings), not the chat name.
    const QString name = myDeviceName();
    const QJsonObject o{{QStringLiteral("k"), m_chat->myKey()}, {QStringLiteral("p"), m_chat->mailbox()}, {QStringLiteral("n"), name.left(40)},
                        {QStringLiteral("i"), m_token}};
    m_code = QString::fromLatin1(kPrefix) + QString::fromLatin1(compact(o).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
    Q_EMIT changed();
    return m_code;
}

bool Link::codeDue(qint64 t, qint64 until) { return t > until - kRefreshMs; }

QString Link::myDeviceName() const
{
    const QString n = (deviceName ? deviceName() : QString()).simplified().left(40);
    return n.isEmpty() ? m_chat->username().left(40) : n;
}

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

QString Link::link(const QString &text)
{
    return linkWith(text, false, true);
}

QString Link::linkWith(const QString &text, bool confirmed, bool ask)
{
    ensureLoaded();
    if (!m_chat->ready())
        return QStringLiteral("Unlock Huginn first.");
    if (text.indexOf(QString::fromLatin1(kPrefix)) < 0)
        return QStringLiteral("That isn't a link code. On the other device: Settings › Devices and backup › Linked devices › Link this PC to my phone.");
    const QJsonObject o = readCode(text);
    if (o.isEmpty())
        return QStringLiteral("That link code is damaged.");
    const QString k = jstr(o, "k");
    if (k == m_chat->myKey())
        return QStringLiteral("That's this device's own code.");
    if (k == m_phone.key)
        return QStringLiteral("That device is the phone this one mirrors.");
    // Round 17 (B1): showing this PC's chats on another device needs the current lock given again (used up here).
    if (ask && !m_chat->vault()->takeGrant(QStringLiteral("link")))
        return QStringLiteral("Confirm your lock first, then paste the code again.");
    QString name = jstr(o, "n").trimmed();
    const Device d{k, jstr(o, "p"), name.isEmpty() ? QStringLiteral("Linked device") : name, confirmed};
    m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == k; }), m_companions.end());
    m_companions << d;
    forgetStamps(k);   // round 15: linked anew, so whether it stamps is learned again
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
    forgetStamps(key);   // round 15: a link made again learns anew whether it stamps
    m_owed.remove(key);
    m_meOwed.remove(key);
    // Files waiting for it are dropped (one already going stops at its next part, which isn't taken any more).
    for (qsizetype i = m_serve.size() - 1; i >= (m_serveAt >= 0 ? 1 : 0); --i)
        if (m_serve.at(i).to == key)
            m_serve.removeAt(i);
    if (!m_phone.key.isEmpty() && m_phone.key == key) {
        if (!m_phone.gone)
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
    t->setObjectName(QStringLiteral("huginn-link-hash"));
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
    m.from = jstr(o, "fn").simplified().left(40);
    // Optional: a group message's channel, what it answers, and when it was edited.
    m.ch = jstr(o, "ch").left(40);
    m.re = jstr(o, "re").left(200);
    m.edited = qMax<qint64>(0, qint64(o.value(QStringLiteral("ed")).toDouble()));
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
    // A phone unlinked with its copy kept here is linked no more: only a hello (linking it again) is taken from it.
    const bool samePhone = !m_phone.key.isEmpty() && m_phone.key == from;
    const bool ph = samePhone && !m_phone.gone;
    // Round 14: a frame from a linked device that's too old, came before, or lacks the stamp it has: dropped (answered as
    // if taken). Round 15: a relay's frame off by more than 36 hours is said once; an unstamped one from a phone that
    // stamped asks it again what it is now (cap l4).
    if (comp || ph) {
        const LinkReplay::Verdict v = replayVerdict(from, p, via);
        const qint64 skew = LinkReplay::skewOf(p, now());
        if (v == LinkReplay::Verdict::Skewed || (v == LinkReplay::Verdict::Taken && qAbs(skew) > LinkReplay::kMaxAgeMs))
            sayClockOff(from, skew);
        else if (v == LinkReplay::Verdict::Unstamped && ph)
            reprobe();
        // Round 16: found on an older version: the person is asked to link it again (Main.qml, Linked.older).
        if (v == LinkReplay::Verdict::Unstamped && m_replay.found(from))
            Q_EMIT changed();
        if (v != LinkReplay::Verdict::Taken)
            return true;
    }
    if (ph)
        m_heardAt = now();   // cap l2: the phone is there (shown as its status)
    if (t == QLatin1String("hello") && !comp) {
        // Pairing: a phone answers my link code with its token, in time and only once.
        const QString token = jstr(p, "i");
        if (token.isEmpty() || !m_tokens.contains(token) || now() > m_tokens.value(token))
            return false;
        m_tokens.clear();   // once: every code shown so far stops working
        // Round 15: linked anew, so whether it stamps is learned again from what it sends now.
        forgetStamps(from);
        m_token.clear();
        m_code.clear();
        m_tokenUntil = 0;
        const QString n = jstr(p, "n").left(40).trimmed();
        const QString mb = jstr(p, "p");
        if (samePhone) {
            // The same phone linked again: its chats stay, and the 4 emoji are checked again before anything shows.
            // Cap l1: its next full account replaces the one here.
            m_phone.ok = false;
            m_phone.gone = false;
            m_acctFresh = true;
            saveAcct();
            if (!n.isEmpty())
                m_phone.name = n;
            if (mb.size() == 64)
                m_phone.mailbox = mb;
            m_ownOpen = false;
            save();
            return true;
        }
        forgetPhone();   // another phone: nothing of the last one stays
        m_nameWanted = false;
        m_phone = Device{from, mb.size() == 64 ? mb : QString(), n.isEmpty() ? QStringLiteral("Your phone") : n, false};
        m_acctFresh = true;   // cap l1: its first full account replaces whatever this device had
        save();
        saveAcct();
        return true;
    }
    if (t == QLatin1String("unlink") && (comp || ph)) {
        forgetStamps(from);
        if (comp)
            m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == from; }), m_companions.end());
        if (ph) {
            Q_EMIT m_chat->notice(QStringLiteral("%1 unlinked this device.").arg(m_phone.name));
            forgetPhone();
            // Its own name from before isn't known: the next screens ask for one (R7), so it doesn't keep the phone's.
            if (!m_ownBack)
                m_nameWanted = true;
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
            || t == QLatin1String("me") || t == QLatin1String("setme") || t == QLatin1String("acct") || t == QLatin1String("ping")
            || t == QLatin1String("pong") || t == QLatin1String("read");
    if (ph && t == QLatin1String("pong")) {
        // Cap l2: the phone answered a "ping": it's there, and it takes "read".
        m_pongAt = now();
        if (!m_phone.l2) {
            m_phone.l2 = true;
            save();
        } else {
            Q_EMIT changed();
        }
        return true;
    }
    if (ph && t == QLatin1String("read")) {
        // Cap l2: read on the phone up to then: read here too.
        const QString peer = jstr(p, "peer");
        const qint64 upTo = qint64(p.value(QStringLiteral("upTo")).toDouble());
        auto it = m_mirror.find(peer);
        if (it != m_mirror.end() && upTo > it->readAt) {
            it->readAt = upTo;
            saveMirror();
        }
        return true;
    }
    if (comp && t == QLatin1String("ping")) {
        // Huginn as the phone: answer, and take the companion's device name.
        const QString dn = jstr(p, "dn").simplified().left(40);
        for (Device &d : m_companions)
            if (d.key == from && !dn.isEmpty() && d.name != dn) {
                d.name = dn;
                save();
            }
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from) {
                QJsonObject pong{{QStringLiteral("t"), QStringLiteral("pong")}, {QStringLiteral("caps"), QString::fromLatin1(LinkReplay::kStampCap)}};
                // Round 17: the ping's own "pn" said back, so the other end learns this clock only from a real answer.
                const QString pn = jstr(p, "pn");
                if (!pn.isEmpty() && pn.size() <= 32)
                    pong.insert(QStringLiteral("pn"), pn);
                sendTo(d, pong);
            }
        return true;
    }
    if (ph && t == QLatin1String("acct")) {
        // Cap l1: the phone's account (all of it, or what changed).
        takeAcct(p);
        return true;
    }
    if (ph && t == QLatin1String("me")) {
        // The phone's own profile: shown here as "you" while it's linked.
        const QVariantMap me = readMe(p);
        if (me.isEmpty())
            return true;
        m_meName = vstr(me, "name");
        m_meStatus = vstr(me, "status");
        m_meCustom = vstr(me, "custom");
        // "pk": sent away from the home Wi-Fi without the picture: the one here stays until it comes.
        const QByteArray before = m_mePic;
        if (!p.value(QStringLiteral("pk")).toBool())
            m_mePic = me.value(QStringLiteral("pic")).toByteArray();
        // Cap l1: the phone's profile is this device's own too, with nothing of the old one left.
        if (m_phone.l1) {
            applyMe(m_meName, m_meStatus, m_meCustom);
            if (m_mePic != before && setMyPicture)
                setMyPicture(m_mePic);
        }
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
                        M x{mid, done.text, true, now(), {}, {}, {}, 0};
                        x.ch = done.ch;
                        x.re = done.re;
                        merge(c.msgs, {x});
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
        // A chat that's new here from this sync: its history counts as read (only what comes after is unread).
        if (!known || m_freshPages.contains(peer)) {
            if (!c.msgs.isEmpty())
                c.readAt = qMax(c.readAt, c.msgs.last().time);
            if (p.value(QStringLiteral("pg")).toInt() < p.value(QStringLiteral("pgs")).toInt(1) - 1)
                m_freshPages.insert(peer);
            else
                m_freshPages.remove(peer);
        }
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
        // Cap l3 (15.3): an id already here is that message edited or deleted: it replaces the old copy, quietly.
        const auto was = std::find_if(c.msgs.cbegin(), c.msgs.cend(), [&](const M &y) { return y.id == x.id; });
        const bool again = was != c.msgs.cend();
        // Its file went with it (deleted for everyone): the copy here goes too, not at some later prune.
        const bool fileGone = again && !was->hash.isEmpty() && was->hash != x.hash;
        merge(c.msgs, {x});
        if (fileGone)
            dropMedia();
        // My own message from here came back: it isn't pending any more. Only when one pending row has its text (two
        // the same wait for their "result", which ends each by its own id).
        if (x.mine && !again) {
            qsizetype at = -1, same = 0;
            for (qsizetype i = 0; i < m_pending.size(); ++i)
                if (m_pending.at(i).peer == peer && m_pending.at(i).text.left(kText) == x.text) {
                    at = i;
                    ++same;
                }
            if (same == 1)
                m_pending.removeAt(at);
        }
        saveMirror();
        want(x);
        fetchNext();
        // Who wrote it, in a group ("Sam in gaming server"). Muted here or on the phone: notifyFor stays quiet.
        // One deleted before it ever came here (sent on late) isn't news: no notification for it.
        // A group set to Mentions only here (R10) hears of @me and of replies to my messages: the title says so, as for
        // this computer's own groups (Chat::toMe).
        if (!x.mine && !again && x.text != QLatin1String("Message deleted")) {
            const bool toMe = !x.from.isEmpty()
                && ((!m_meName.isEmpty() && RichWire::mentions(x.text, m_meName))
                    || (!x.re.isEmpty() && std::any_of(c.msgs.cbegin(), c.msgs.cend(), [&](const M &y) { return y.id == x.re && y.mine; })));
            const QString who = x.from.isEmpty() ? c.name : x.from + (toMe ? QStringLiteral(" mentioned you in ") : QStringLiteral(" in ")) + c.name;
            // A group's message names its channel ("mirror:<peer>#<channel>"): only that tab on screen counts as looking
            // (main.cpp), and a click opens that tab. Mute and Reduce notifications go by the chat (Chat::notifyFor).
            const bool group = peer.startsWith(QLatin1String("g:"));
            Q_EMIT m_chat->arrived(who, x.text.left(60), QStringLiteral("mirror:") + peer + (group ? QStringLiteral("#") + chOf(x) : QString()));
        }
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
            const QString mid = m_chat->sendPrivateWith(peer, GroupWire::clip(text, Chat::kMax), QString(), QJsonObject());
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

QString Link::send(const QString &peer, const QString &text, const QString &re, const QString &ch)
{
    ensureLoaded();
    const QString t = text.trimmed();
    if (m_phone.key.isEmpty() || m_phone.gone)
        return QStringLiteral("No phone is linked any more.");
    if (!m_phone.ok)
        return QStringLiteral("Check the emoji on both screens first.");
    if (t.isEmpty())
        return QStringLiteral("Write something first.");
    // Shown at once as pending, until the phone answers or its copy comes back.
    const bool group = peer.startsWith(QLatin1String("g:"));
    const Pending p{QUuid::createUuid().toString(QUuid::WithoutBraces), peer, GroupWire::clip(t, Chat::kMax), QString(), re.left(200), now(),
                    group && ch.size() <= 40 ? ch : QString()};
    m_pending << p;
    while (m_pending.size() > 50)
        m_pending.removeFirst();
    sendPending(p);
    Q_EMIT changed();
    return QString();
}

void Link::sendPending(const Pending &p)
{
    QJsonObject frame{{QStringLiteral("t"), QStringLiteral("send")}, {QStringLiteral("peer"), p.peer}, {QStringLiteral("text"), p.text},
                      {QStringLiteral("id"), p.id}};
    if (!p.re.isEmpty())
        frame.insert(QStringLiteral("re"), p.re);
    if (!p.ch.isEmpty())
        frame.insert(QStringLiteral("ch"), p.ch);   // 15.1: the channel being viewed
    sendTo(m_phone, frame);
    // No answer in a minute: "Phone didn't answer", with Retry (it still goes if the phone takes it later; never twice).
    const QString id = p.id;
    QTimer::singleShot(int(kEchoMs + 500), this, [this, id] {
        for (Pending &x : m_pending)
            if (x.id == id && x.err.isEmpty() && now() - x.time >= kEchoMs) {
                x.err = QStringLiteral("Phone didn't answer");
                Q_EMIT changed();
            }
    });
}

void Link::retrySend(const QString &id)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone)
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
    if (m_phone.key.isEmpty() || m_phone.gone)
        return QStringLiteral("No phone is linked.");
    if (!m_phone.ok)
        return QStringLiteral("Check the emoji on both screens first.");
    const qint64 t = now();
    if (!refreshDue(m_askedAt, t)) {
        const qint64 mins = qMax<qint64>(1, (kRefreshEveryMs - (t - m_askedAt) + 59999) / 60000);
        return QStringLiteral("Asked already. You can ask again in %1 min.").arg(mins);
    }
    m_askedAt = t;
    sendTo(m_phone, syncFrame(false));
    fetchNext();
    const QString name = m_phone.name.isEmpty() ? QStringLiteral("your phone") : m_phone.name;
    return lanHere(m_phone.key) ? QStringLiteral("Asked %1 for its chats.").arg(name)
                                : QStringLiteral("Asked %1. History comes over on home Wi-Fi.").arg(name);
}

// ------------------------------------------------------------------ cap l1: the whole account (section 15.1)

namespace
{
// A picture in an item: a 256x256 JPEG as section 22 takes it, else none.
QByteArray picOf(const QJsonObject &v)
{
    const auto d = QByteArray::fromBase64Encoding(jstr(v, "pic").toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    return d.decodingStatus == QByteArray::Base64DecodingStatus::Ok && Profile::pictureOk(d.decoded) ? d.decoded : QByteArray();
}
// Until turned back on, as Chat::muteChat takes it (the largest JavaScript date).
constexpr qint64 kMuteForever = 8640000000000000LL;
// A "mute" item: when it ends (ms), kMuteForever for "f", 0 when it's a tombstone or already over.
qint64 muteUntil(const Link::Item &i)
{
    if (i.del)
        return 0;
    if (i.v.value(QStringLiteral("f")).toBool())
        return kMuteForever;
    const double u = i.v.value(QStringLiteral("u")).toDouble();
    return u > double(now()) && u < double(kMuteForever) ? qint64(u) : 0;
}
}

QJsonObject Link::Item::json() const
{
    QJsonObject o{{QStringLiteral("k"), k}, {QStringLiteral("id"), id}, {QStringLiteral("ts"), double(ts)}};
    if (del)
        o.insert(QStringLiteral("del"), true);
    else
        o.insert(QStringLiteral("v"), v);
    return o;
}

Link::Item Link::readItem(const QJsonObject &o, bool *ok)
{
    static const QStringList kinds{QStringLiteral("me"), QStringLiteral("pic"), QStringLiteral("contact"), QStringLiteral("group"), QStringLiteral("folder"),
                                   QStringLiteral("mute")};
    Item i;
    i.k = jstr(o, "k");
    i.id = jstr(o, "id");
    i.ts = qint64(o.value(QStringLiteral("ts")).toDouble());
    i.del = o.value(QStringLiteral("del")).toBool();
    i.v = o.value(QStringLiteral("v")).toObject();
    *ok = kinds.contains(i.k) && !i.id.isEmpty() && i.id.size() <= 200 && i.ts > 0 && (i.del || o.value(QStringLiteral("v")).isObject());
    if (i.del)
        i.v = QJsonObject();
    return i;
}

bool Link::itemWins(const Item *local, const Item &in, bool winTies)
{
    if (!local || in.ts > local->ts)
        return true;
    if (in.ts < local->ts || !winTies)
        return false;
    // The same ts: the phone's copy, unless it's the very same.
    return in.del != local->del || (!in.del && in.v != local->v);
}

QList<Link::Item> Link::mergeItems(QHash<QString, Item> &set, const QList<Item> &incoming, bool winTies)
{
    QList<Item> won;
    for (const Item &i : incoming) {
        const auto it = set.constFind(i.key());
        if (!itemWins(it == set.cend() ? nullptr : &*it, i, winTies))
            continue;
        set.insert(i.key(), i);
        won.erase(std::remove_if(won.begin(), won.end(), [&](const Item &x) { return x.key() == i.key(); }), won.end());
        won << i;
    }
    return won;
}

void Link::adoptItems(QHash<QString, Item> &set, const QList<Item> &incoming)
{
    set.clear();
    for (const Item &i : incoming)
        set.insert(i.key(), i);
}

void Link::pruneItems(QHash<QString, Item> &set, qint64 t)
{
    QStringList old;
    for (auto it = set.cbegin(); it != set.cend(); ++it)
        if (it->del && t - it->ts > kTombMs)
            old << it.key();
    for (const QString &k : std::as_const(old))
        set.remove(k);
}

bool Link::acctInternetOk(const QJsonObject &msg)
{
    if (jstr(msg, "t") != QLatin1String("acct") || msg.value(QStringLiteral("full")).toBool())
        return false;
    for (const QJsonValue &v : msg.value(QStringLiteral("items")).toArray()) {
        const QJsonObject o = v.toObject();
        if (jstr(o, "k") == QLatin1String("pic") && !o.value(QStringLiteral("del")).toBool())
            return false;
    }
    return compact(msg).size() <= kAcctLiveBytes;
}

QString Link::linkCaps()
{
    // l1 to l3 (15.1 to 15.3), and l4: this app stamps its link frames (15, "Replay").
    return QString::fromLatin1(kAcctCap) + QStringLiteral(" l2 l3 ") + QString::fromLatin1(LinkReplay::kStampCap);
}

bool Link::saysL1(const QString &caps)
{
    return caps.split(QLatin1Char(' '), Qt::SkipEmptyParts).contains(QString::fromLatin1(kAcctCap));
}

QJsonObject Link::syncFrame(bool first) const
{
    // "caps": "l1" asks the phone for its whole account too; an old phone ignores it and links the old way.
    QJsonObject o{{QStringLiteral("t"), QStringLiteral("sync")}, {QStringLiteral("caps"), linkCaps()}};
    if (first)
        o.insert(QStringLiteral("f"), true);
    return o;
}

void Link::saveAcct()
{
    if (!m_chat->ready())
        return;
    if (m_acct.isEmpty() && !m_acctFresh) {
        m_chat->m_vault->write(QStringLiteral("link-account.json"), QByteArray("{}"));
        return;
    }
    QJsonArray a;
    for (const Item &i : std::as_const(m_acct))
        a.append(i.json());
    m_chat->m_vault->write(QStringLiteral("link-account.json"), compact(QJsonObject{{QStringLiteral("fresh"), m_acctFresh}, {QStringLiteral("items"), a}}));
}

void Link::rebuildPics() const
{
    m_pics.clear();
    for (const Item &i : std::as_const(m_acct))
        if (i.k == QLatin1String("pic") && !i.del && i.id != QLatin1String("me")) {
            const QByteArray b = picOf(i.v);
            if (!b.isEmpty())
                m_pics.insert(i.id, b);
        }
}

QHash<QString, QByteArray> Link::contactPictures() const
{
    ensureLoaded();
    return m_phone.l1 ? m_pics : QHash<QString, QByteArray>();
}

void Link::applyMe(const QString &name, const QString &status, const QString &custom)
{
    if (setMyProfile && !name.isEmpty())
        setMyProfile(name, status, custom);
}

void Link::takeAcct(const QJsonObject &p)
{
    QList<Item> in;
    for (const QJsonValue &v : p.value(QStringLiteral("items")).toArray()) {
        bool ok = false;
        const Item i = readItem(v.toObject(), &ok);
        if (ok)
            in << i;
    }
    m_phone.l1 = true;   // only a phone that keeps the account in step sends these
    QList<Item> won;
    if (p.value(QStringLiteral("full")).toBool() && m_acctFresh) {
        // The first full set after linking: the phone's account replaces this device's, its name and picture too.
        // This device's own are kept aside (once), to come back on unlinking.
        if (m_own.isEmpty() && myProfile)
            m_own = myProfile();
        adoptItems(m_acct, in);
        m_acctFresh = false;
        won = in;
    } else {
        won = mergeItems(m_acct, in, true);
    }
    pruneItems(m_acct, now());
    bool pics = false;
    for (const Item &i : std::as_const(won)) {
        if (i.k == QLatin1String("me") && !i.del) {
            const QVariantMap me = readMe(i.v);
            if (me.isEmpty())
                continue;
            m_meName = vstr(me, "name");
            m_meStatus = vstr(me, "status");
            m_meCustom = vstr(me, "custom");
            applyMe(m_meName, m_meStatus, m_meCustom);
        } else if (i.k == QLatin1String("pic") && i.id == QLatin1String("me")) {
            m_mePic = i.del ? QByteArray() : picOf(i.v);
            if (setMyPicture)
                setMyPicture(m_mePic);
        } else if (i.k == QLatin1String("pic")) {
            pics = true;
        } else if (i.k == QLatin1String("mute")) {
            m_chat->muteChat(QStringLiteral("mirror:") + i.id, muteUntil(i));
        }
    }
    // The last page of a full set and the phone has no picture: neither has this device (no leftover old one).
    const bool full = p.value(QStringLiteral("full")).toBool();
    const int pgs = p.value(QStringLiteral("pgs")).toInt(1);
    if (full && p.value(QStringLiteral("pg")).toInt() >= pgs - 1 && m_acct.value(QStringLiteral("pic|me")).v.isEmpty()) {
        m_mePic.clear();
        if (setMyPicture)
            setMyPicture(QByteArray());
    }
    if (pics) {
        rebuildPics();
        Q_EMIT picturesChanged();
    }
    saveAcct();
    save();
}

QVariantMap Link::account() const
{
    ensureLoaded();
    QVariantList contacts, groups, folders;
    const bool on = m_chat->ready() && !m_phone.key.isEmpty() && m_phone.ok && m_phone.l1;
    if (on) {
        for (const Item &i : std::as_const(m_acct)) {
            if (i.del)
                continue;
            if (i.k == QLatin1String("contact")) {
                const QString n = jstr(i.v, "n").simplified().left(40), nn = jstr(i.v, "nn").simplified().left(40);
                contacts << QVariantMap{{QStringLiteral("key"), i.id}, {QStringLiteral("name"), n.isEmpty() ? QStringLiteral("Someone") : n},
                                        {QStringLiteral("nick"), nn}, {QStringLiteral("shown"), nn.isEmpty() ? (n.isEmpty() ? QStringLiteral("Someone") : n) : nn},
                                        {QStringLiteral("verified"), i.v.value(QStringLiteral("vf")).toBool()},
                                        {QStringLiteral("blocked"), i.v.value(QStringLiteral("bl")).toBool()}, {QStringLiteral("picture"), m_pics.contains(i.id)}};
            } else if (i.k == QLatin1String("group")) {
                const QJsonObject st = i.v.value(QStringLiteral("st")).toObject();
                const QString owner = jstr(st, "owner");
                const QJsonArray admins = st.value(QStringLiteral("admins")).toArray();
                QVariantList members;
                for (const QJsonValue &m : st.value(QStringLiteral("members")).toArray()) {
                    const QString k = jstr(m.toObject(), "k");
                    members << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), jstr(m.toObject(), "n").left(40)},
                                           {QStringLiteral("role"), k == owner ? QStringLiteral("owner") : admins.contains(QJsonValue(k)) ? QStringLiteral("admin") : QStringLiteral("member")}};
                }
                groups << QVariantMap{{QStringLiteral("id"), i.id}, {QStringLiteral("name"), jstr(i.v, "n").left(40)}, {QStringLiteral("role"), jstr(i.v, "role")},
                                      {QStringLiteral("gone"), jstr(i.v, "gone")}, {QStringLiteral("members"), members}};
            } else if (i.k == QLatin1String("folder")) {
                QStringList keys;
                for (const QJsonValue &k : i.v.value(QStringLiteral("k")).toArray())
                    keys << k.toString();
                folders << QVariantMap{{QStringLiteral("id"), i.id}, {QStringLiteral("name"), jstr(i.v, "n").left(24)}, {QStringLiteral("keys"), keys}};
            }
        }
        auto byName = [](const QVariant &a, const QVariant &b) {
            const QVariantMap x = a.toMap(), y = b.toMap();
            const QString nx = x.value(QStringLiteral("shown"), x.value(QStringLiteral("name"))).toString();
            const QString ny = y.value(QStringLiteral("shown"), y.value(QStringLiteral("name"))).toString();
            return QString::compare(nx, ny, Qt::CaseInsensitive) < 0;
        };
        std::sort(contacts.begin(), contacts.end(), byName);
        std::sort(groups.begin(), groups.end(), byName);
        std::sort(folders.begin(), folders.end(), byName);
    }
    return {{QStringLiteral("on"), on}, {QStringLiteral("contacts"), contacts}, {QStringLiteral("groups"), groups}, {QStringLiteral("folders"), folders}};
}

QString Link::changeItem(const QString &k, const QString &id, bool del, const QJsonObject &v)
{
    ensureLoaded();
    if (!acctOn())
        return QStringLiteral("Link your phone first.");
    Item i{k, id, now(), del, del ? QJsonObject() : v};
    const auto old = m_acct.constFind(i.key());
    if (old != m_acct.cend()) {
        if (old->del == del && (del || old->v == i.v))
            return QString();   // nothing changed
        i.ts = qMax(i.ts, old->ts + 1);
    }
    m_acct.insert(i.key(), i);
    saveAcct();
    // A small change goes like any live frame; the phone takes it if it's later than what it has.
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("acct")}, {QStringLiteral("items"), QJsonArray{i.json()}}});
    Q_EMIT changed();
    return QString();
}

QString Link::renameContact(const QString &key, const QString &nickname)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("contact|") + key);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("Not a contact on your phone");
    QJsonObject v = i.v;
    const QString nn = nickname.simplified().left(40);
    if (nn.isEmpty())
        v.remove(QStringLiteral("nn"));
    else
        v.insert(QStringLiteral("nn"), nn);
    return changeItem(QStringLiteral("contact"), key, false, v);
}

QString Link::blockContact(const QString &key, bool on)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("contact|") + key);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("Not a contact on your phone");
    QJsonObject v = i.v;
    v.insert(QStringLiteral("bl"), on);
    return changeItem(QStringLiteral("contact"), key, false, v);
}

QString Link::removeContact(const QString &key)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("contact|") + key);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("Not a contact on your phone");
    const QString r = changeItem(QStringLiteral("contact"), key, true, {});
    if (r.isEmpty()) {
        // Out of every folder too, as on the phone.
        const QList<Item> all = m_acct.values();
        for (const Item &f : all)
            if (f.k == QLatin1String("folder") && !f.del && f.v.value(QStringLiteral("k")).toArray().contains(QJsonValue(key)))
                putInFolder(f.id, key, false);
    }
    return r;
}

QString Link::addFolder(const QString &name)
{
    const QString n = name.simplified().left(24);
    if (n.isEmpty())
        return QStringLiteral("Give it a name.");
    return changeItem(QStringLiteral("folder"), QUuid::createUuid().toString(QUuid::Id128).left(12), false,
                      QJsonObject{{QStringLiteral("n"), n}, {QStringLiteral("k"), QJsonArray()}});
}

QString Link::renameFolder(const QString &id, const QString &name)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("folder|") + id);
    const QString n = name.simplified().left(24);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("That folder is gone.");
    if (n.isEmpty())
        return QStringLiteral("Give it a name.");
    QJsonObject v = i.v;
    v.insert(QStringLiteral("n"), n);
    return changeItem(QStringLiteral("folder"), id, false, v);
}

QString Link::deleteFolder(const QString &id)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("folder|") + id);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("That folder is gone.");
    return changeItem(QStringLiteral("folder"), id, true, {});
}

QString Link::putInFolder(const QString &id, const QString &key, bool on)
{
    ensureLoaded();
    const Item i = m_acct.value(QStringLiteral("folder|") + id);
    if (i.del || i.v.isEmpty())
        return QStringLiteral("That folder is gone.");
    QJsonArray k = i.v.value(QStringLiteral("k")).toArray();
    qsizetype at = k.size();
    for (qsizetype j = 0; j < k.size(); ++j)
        if (k.at(j).toString() == key) {
            at = j;
            break;
        }
    if (on == (at < k.size()))
        return QString();
    if (on)
        k.append(key);
    else
        k.removeAt(at);
    QJsonObject v = i.v;
    v.insert(QStringLiteral("k"), k);
    return changeItem(QStringLiteral("folder"), id, false, v);
}

// ------------------------------------------------------------------ cap l2: read state, mute, the phone's status (section 15.2)

bool Link::startChat(const QString &peer)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone || peer.isEmpty())
        return false;
    if (m_mirror.contains(peer))
        return true;
    const Item i = m_acct.value(QStringLiteral("contact|") + peer);
    if (i.del || i.v.isEmpty())
        return false;
    const QString n = jstr(i.v, "n").simplified().left(40);
    Mirror m;
    m.peer = peer;
    m.name = n.isEmpty() ? QStringLiteral("Someone") : n;
    m_mirror.insert(peer, m);
    saveMirror();
    return true;
}

void Link::markRead(const QString &peer, const QString &ch)
{
    ensureLoaded();
    // Opened: with Reduce notifications, its next message notifies again (as this computer's own chats do).
    m_chat->m_notified.remove(QStringLiteral("mirror:") + peer);
    auto it = m_mirror.find(peer);
    if (it == m_mirror.end() || it->msgs.isEmpty())
        return;
    qint64 upTo = it->msgs.last().time;
    bool seenMoved = false;
    if (!ch.isEmpty()) {
        // One tab on screen: that channel is read. The chat (here and on the phone) only up to just before the first
        // message still unread in another channel, so that one stays unread until its own tab is viewed.
        qint64 inCh = 0;
        for (const M &x : std::as_const(it->msgs))
            if (chOf(x) == ch)
                inCh = qMax(inCh, x.time);
        if (inCh > qMax(it->readAt, it->seen.value(ch))) {
            it->seen.insert(ch, inCh);
            seenMoved = true;
        }
        for (const M &x : std::as_const(it->msgs))
            if (unreadMsg(x, it->readAt, it->seen)) {
                upTo = x.time - 1;
                break;
            }
    }
    if (upTo <= it->readAt) {
        if (seenMoved)
            saveMirror();
        return;
    }
    it->readAt = upTo;
    for (auto s = it->seen.begin(); s != it->seen.end();)
        s = s.value() <= upTo ? it->seen.erase(s) : std::next(s);
    saveMirror();
    // The phone marks it read too (an older phone ignores "read").
    if (!m_phone.key.isEmpty() && m_phone.ok && !m_phone.gone) {
        m_readOwed.insert(peer);
        m_readSoon.start();
    }
}

QString Link::setMute(const QString &peer, qint64 until)
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || peer.isEmpty())
        return QStringLiteral("No phone is linked.");
    m_chat->muteChat(QStringLiteral("mirror:") + peer, until);
    Q_EMIT changed();
    // Cap l1: on the phone too. Without it, only here.
    if (!acctOn())
        return QString();
    const bool off = until <= now();
    return changeItem(QStringLiteral("mute"), peer, off,
                      until >= kMuteForever ? QJsonObject{{QStringLiteral("f"), true}} : QJsonObject{{QStringLiteral("u"), double(until)}});
}

bool Link::inFront()
{
    const QWindow *w = QGuiApplication::focusWindow();
    return mayCheckIn(QGuiApplication::applicationState(), w && w->isVisible(), w && w->windowStates().testFlag(Qt::WindowMinimized));
}

void Link::ping()
{
    ensureLoaded();
    if (m_phone.key.isEmpty() || !m_phone.ok || m_phone.gone || !inFront())
        return;
    const qint64 t = now();
    if (m_pingAt > 0 && t - m_pingAt < 60000)
        return;
    m_pingAt = t;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("ping")}, {QStringLiteral("caps"), linkCaps()},
                                {QStringLiteral("dn"), myDeviceName()}, {QStringLiteral("pn"), pingNonce()}});
    Q_EMIT changed();
    // No answer in 20 seconds: the status says so.
    QTimer::singleShot(20500, this, [this] { Q_EMIT changed(); });
}

bool Link::unlinkPhone(bool keep)
{
    ensureLoaded();
    if (m_phone.key.isEmpty())
        return true;
    if (!keep || m_phone.gone) {
        unlink(m_phone.key);
        return m_ownBack;
    }
    // Keep a copy: the phone forgets this device, and what came from it stays here, read only.
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("unlink")}});
    m_phone.gone = true;
    m_pending.clear();
    m_ownOpen = false;
    m_acctFresh = false;
    // Nothing more is fetched; what's here stays.
    m_want.clear();
    m_fetching.clear();
    m_parts.clear();
    m_partsHave = 0;
    saveAcct();
    save();
    return true;
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

    // Round 14: link frames are stamped; a replay, an old frame, or an unstamped one from a device that stamps is dropped.
    {
        const qint64 t0 = 1800000000000;
        const QJsonObject send = LinkReplay::stamp(QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}, {QStringLiteral("text"), QStringLiteral("hi")}}, t0);
        LinkReplay r;
        const QString pc = QStringLiteral("PC");
        const bool oldApp = r.take(pc, QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}}, t0);
        const bool first = r.take(pc, send, t0 + 1000);
        const bool again = r.take(pc, send, t0 + 2000);
        const bool unstamped = r.take(pc, QJsonObject{{QStringLiteral("t"), QStringLiteral("unlink")}}, t0 + 3000);
        const QJsonObject later = LinkReplay::stamp(QJsonObject{{QStringLiteral("t"), QStringLiteral("unlink")}}, t0);
        const bool tooOld = r.take(pc, later, t0 + LinkReplay::kMaxAgeMs + 1);
        const QJsonObject page = LinkReplay::stamp(QJsonObject{{QStringLiteral("t"), QStringLiteral("chat")}}, t0);
        const bool pages = r.take(pc, page, t0) && r.take(pc, page, t0);
        LinkReplay kept;
        kept.load(r.save());
        const bool remembered = !kept.take(pc, send, t0 + 5000) && !kept.take(pc, QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}}, t0);
        check(oldApp && first && !again && !unstamped && !tooOld && pages && remembered && send.value(QStringLiteral("ln")).toString().size() == 32
                  && send.value(QStringLiteral("lts")).toDouble() == double(t0) && send.value(QStringLiteral("ln")) != later.value(QStringLiteral("ln")),
              "link frames: stamped, and a replay, an old one or an unstamped one after stamps is dropped");
    }
    // Round 16 (cap l4): an unstamped frame never switches the guard off; a device that keeps sending only unstamped
    // frames is found on an older version, to be linked again. The same cases as Muninn's LinkTest.
    {
        const qint64 t0 = 1800000000000;
        const auto st = [](const char *type, qint64 at) { return LinkReplay::stamp(QJsonObject{{QStringLiteral("t"), QLatin1String(type)}}, at); };
        const auto plain = [](const char *type) { return QJsonObject{{QStringLiteral("t"), QLatin1String(type)}}; };
        // Round 17 (m11): the old phone's unstamped frames come straight over Wi-Fi here (only those, or ones after an
        // unanswered ping, count toward finding it on an older version).
        const auto un = [](LinkReplay &r, const QString &k, const QJsonObject &o, qint64 at) { return r.judge(k, o, at, true) == LinkReplay::Verdict::Unstamped; };
        LinkReplay r16;
        const QString pc16 = QStringLiteral("PC");
        const QJsonObject send = st("send", t0);
        const bool stamped = r16.take(pc16, send, t0);
        // A relay replays a 1.35-era ping, sync and pong (no l4), then a send and an unlink: all dropped.
        QJsonObject sync = plain("sync");
        sync.insert(QStringLiteral("caps"), QStringLiteral("l1 l2 l3"));
        QJsonObject claims = plain("ping");
        claims.insert(QStringLiteral("caps"), QString::fromLatin1(LinkReplay::kStampCap));
        const bool dropped = un(r16, pc16, plain("ping"), t0 + 1) && un(r16, pc16, sync, t0 + 2) && un(r16, pc16, plain("pong"), t0 + 3) && un(r16, pc16, plain("send"), t0 + 4)
                             && un(r16, pc16, plain("unlink"), t0 + 5) && un(r16, pc16, claims, t0 + 6);
        const bool notYet = !r16.found(pc16) && r16.olderOnes().isEmpty();
        // A stamped frame starts the count again; a replayed stamped one (seen before) doesn't.
        const bool fresh = r16.take(pc16, st("ping", t0 + 1000), t0 + 1000);
        const bool still = un(r16, pc16, plain("ping"), t0 + LinkReplay::kOlderMs) && un(r16, pc16, plain("ping"), t0 + LinkReplay::kOlderMs + 1)
                           && r16.judge(pc16, send, t0 + LinkReplay::kOlderMs + 2) == LinkReplay::Verdict::Dropped && !r16.found(pc16);
        check(stamped && dropped && notYet && fresh && still, "link frames: an old unstamped frame never turns the guard off (l4, round 16)");

        LinkReplay pr16;
        const QString ph16 = QStringLiteral("PHONE");
        QJsonObject pong = st("pong", t0);
        pong.insert(QStringLiteral("caps"), QString::fromLatin1(LinkReplay::kStampCap));
        const bool first = pr16.take(ph16, pong, t0);
        const bool keeps = un(pr16, ph16, plain("msg"), t0 + 1000) && un(pr16, ph16, plain("pong"), t0 + 120000) && !pr16.found(ph16)
                           && un(pr16, ph16, plain("pong"), t0 + 1000 + LinkReplay::kOlderMs);
        const bool foundOnce = pr16.found(ph16) && !pr16.found(ph16) && pr16.olderOnes() == QSet<QString>{ph16} && un(pr16, ph16, plain("msg"), t0 + 2 * LinkReplay::kOlderMs);
        LinkReplay kept16;
        kept16.load(pr16.save());
        const bool remembered16 = kept16.olderOnes() == QSet<QString>{ph16} && un(kept16, ph16, plain("msg"), t0);
        // Linking again: its hello (one-time token) is taken, and forget starts over.
        const bool hello = kept16.take(ph16, plain("hello"), t0);
        kept16.forget(ph16);
        const bool relinked = kept16.olderOnes().isEmpty() && kept16.take(ph16, plain("msg"), t0);
        // Updated again instead: its first stamped frame clears it and guards it once more.
        const QJsonObject again16 = st("send", t0);
        const bool back = pr16.take(ph16, again16, t0) && pr16.olderOnes().isEmpty() && !pr16.take(ph16, again16, t0 + 1) && un(pr16, ph16, plain("send"), t0);
        check(first && keeps && foundOnce && remembered16 && hello && relinked && back && LinkReplay::kOlderFrames == 3 && LinkReplay::kOlderMs == 600000
                  && olderText(true) == QLatin1String("Your linked phone is on an older version. Link again to keep using it.")
                  && olderText(false) == QLatin1String("Your linked computer is on an older version. Link again to keep using it."),
              "link frames: a device back on an older version is found and linked again (l4, round 16)");
        // This app says l4 on sync and ping (and its pong), beside l1 to l3.
        const QStringList caps = linkCaps().split(QLatin1Char(' '));
        check(caps.contains(QStringLiteral("l4")) && caps.contains(QStringLiteral("l1")) && caps.contains(QStringLiteral("l3"))
                  && QString::fromLatin1(LinkReplay::kStampCap) == QLatin1String("l4"),
              "link caps: l4 is said");
        // A clock 5 days off. Round 17 (security M2): a frame is dropped as off on every route, Wi-Fi and Bluetooth
        // too, until a pong over Wi-Fi or Bluetooth answers this app's own ping within 30 seconds; after that its frames
        // are taken on any route; a replay of a frame is still refused, and an old copy from before still is.
        const qint64 off = 5LL * 86400000;
        LinkReplay c;
        const QString pc = QStringLiteral("PC");
        const QJsonObject a = st("send", t0 - off), b = st("send", t0 - off + 60000), old = st("send", t0 - off - 3 * LinkReplay::kMaxAgeMs);
        const bool relayFirst = c.judge(pc, a, t0) == LinkReplay::Verdict::Skewed;
        const bool directFirst = c.judge(pc, a, t0, true) == LinkReplay::Verdict::Skewed;
        // An unsolicited pong straight over Wi-Fi teaches nothing; one that answers a ping (saying its pn back) does.
        const bool unasked = c.judge(pc, st("pong", t0 - off + 1000), t0 + 1000, true) == LinkReplay::Verdict::Skewed;
        c.pinged(pc, QStringLiteral("00aa11bb22cc33dd"), t0 + 2000);
        QJsonObject wrongPn = st("pong", t0 - off + 3000);
        wrongPn.insert(QStringLiteral("pn"), QStringLiteral("ffffffffffffffff"));
        const bool wrongAnswer = c.judge(pc, wrongPn, t0 + 3000, true) == LinkReplay::Verdict::Skewed;
        QJsonObject answer = st("pong", t0 - off + 4000);
        answer.insert(QStringLiteral("pn"), QStringLiteral("00aa11bb22cc33dd"));
        const bool answered = c.take(pc, answer, t0 + 4000, true);
        const bool direct = c.take(pc, a, t0 + 5000, true);
        const bool relayAfter = c.take(pc, b, t0 + 60000);
        const bool replay = !c.take(pc, a, t0 + 120000) && !c.take(pc, a, t0 + 120000, true);
        const bool oldCopy = c.judge(pc, old, t0 + 180000) == LinkReplay::Verdict::Skewed && c.judge(pc, old, t0 + 180000, true) == LinkReplay::Verdict::Skewed;
        LinkReplay kept;
        kept.load(c.save());
        const bool remembered = kept.take(pc, st("send", t0 - off + 240000), t0 + 240000);
        // A ping answered too late (over 30 s) teaches nothing either.
        LinkReplay late;
        late.pinged(pc, QStringLiteral("0123456789abcdef"), t0);
        QJsonObject slow = st("pong", t0 - off);
        slow.insert(QStringLiteral("pn"), QStringLiteral("0123456789abcdef"));
        const bool tooLate = late.judge(pc, slow, t0 + LinkReplay::kChallengeMs + 1, true) == LinkReplay::Verdict::Skewed;
        check(relayFirst && directFirst && unasked && wrongAnswer && answered && direct && relayAfter && replay && oldCopy && remembered && tooLate
                  && LinkReplay::skewOf(a, t0) == -off,
              "link frames: a clock days off keeps working once a ping is answered on Wi-Fi or Bluetooth");
        // Round 17 (security M2): a relay's frame copied and handed over the LAN 3 days later is refused like any other.
        LinkReplay lan;
        const QJsonObject captured = st("unlink", t0);
        const bool lanReplay = lan.judge(pc, captured, t0 + 3 * 86400000LL, true) == LinkReplay::Verdict::Skewed
                               && lan.judge(pc, captured, t0 + 3 * 86400000LL) == LinkReplay::Verdict::Skewed;
        check(lanReplay, "link frames: an old frame over Wi-Fi or Bluetooth is held to the 36 hours too");
        // Round 17 (m11): a relay replaying unstamped frames never raises "on an older version"; after a ping nobody
        // answered with a stamped frame, they count.
        LinkReplay relayOnly;
        const QString ph = QStringLiteral("PHONE");
        const bool stampsFirst = relayOnly.take(ph, st("send", t0), t0);
        const QJsonObject bare{{QStringLiteral("t"), QStringLiteral("msg")}};
        for (int i = 0; i < 6; ++i)
            relayOnly.judge(ph, bare, t0 + 1000 + i * LinkReplay::kOlderMs);
        const bool notRaised = !relayOnly.found(ph) && relayOnly.olderOnes().isEmpty();
        const qint64 tp = t0 + 10 * LinkReplay::kOlderMs;
        relayOnly.pinged(ph, QStringLiteral("1111222233334444"), tp);
        relayOnly.judge(ph, bare, tp + 1000);
        relayOnly.judge(ph, bare, tp + 2000);
        relayOnly.pinged(ph, QStringLiteral("5555666677778888"), tp + LinkReplay::kOlderMs);
        relayOnly.judge(ph, bare, tp + LinkReplay::kOlderMs + 1000);
        check(stampsFirst && notRaised && relayOnly.found(ph), "link frames: only unstamped frames on Wi-Fi or after an unanswered ping count as an older version");
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
    // Round 17 (m8): the superscript COM and LPT names, CONIN$ and CONOUT$, and hidden direction marks.
    check(safeName(QStringLiteral("COM") + QChar(0x00B9) + QStringLiteral(".txt"), fb).startsWith(QLatin1Char('_'))
              && safeName(QStringLiteral("lpt") + QChar(0x00B3), fb).startsWith(QLatin1Char('_')) && safeName(QStringLiteral("CONIN$"), fb) == QLatin1String("_CONIN$")
              && safeName(QStringLiteral("conout$.log"), fb) == QLatin1String("_conout$.log")
              && safeName(QStringLiteral("Photos") + QChar(0x202e) + QStringLiteral("gpj.exe"), fb) == QLatin1String("Photosgpj.exe"),
          "more names Windows keeps, and hidden marks, are made safe");
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
        check(internetOk(f("ping")) && internetOk(f("pong")) && internetOk(f("read")), "l2: ping, pong and read are small live frames");
        QJsonObject me = f("me");
        me.insert(QStringLiteral("pic"), QStringLiteral("AAAA"));
        check(!internetOk(me), "a me with its picture never uses the internet");
        check(mayCheckIn(Qt::ApplicationActive, true, false) && !mayCheckIn(Qt::ApplicationInactive, true, false)
                  && !mayCheckIn(Qt::ApplicationHidden, true, false) && !mayCheckIn(Qt::ApplicationSuspended, true, false)
                  && !mayCheckIn(Qt::ApplicationActive, false, false) && !mayCheckIn(Qt::ApplicationActive, true, true),
              "a companion checks in with its phone only while in front and in use");
        check(refreshDue(0, 1) && !refreshDue(1000, 1000 + kRefreshEveryMs - 1) && refreshDue(1000, 1000 + kRefreshEveryMs),
              "Refresh at most once in 10 minutes");
        check(errText(QStringLiteral("confirm")) == QLatin1String("Confirm the emoji on your phone")
                  && errText(QStringLiteral("not a contact")) == QLatin1String("Not a contact on your phone"), "a phone's result shows why");
    }
    // Cap l1: the whole account, merged item by item; the later ts wins, a tie goes to the phone, deletes are tombstones.
    {
        auto contact = [](const char *key, qint64 ts, const char *nick) {
            QJsonObject v{{QStringLiteral("n"), QStringLiteral("Name")}, {QStringLiteral("vf"), true}, {QStringLiteral("bl"), false}};
            if (*nick)
                v.insert(QStringLiteral("nn"), QString::fromLatin1(nick));
            return Item{QStringLiteral("contact"), QString::fromLatin1(key), ts, false, v};
        };
        auto me = [](const char *name, qint64 ts) {
            return Item{QStringLiteral("me"), QStringLiteral("me"), ts, false,
                        QJsonObject{{QStringLiteral("n"), QString::fromLatin1(name)}, {QStringLiteral("st"), QStringLiteral("online")}}};
        };
        auto nick = [](const QHash<QString, Item> &set, const char *key) { return jstr(set.value(QStringLiteral("contact|") + QString::fromLatin1(key)).v, "nn"); };
        QHash<QString, Item> set;
        mergeItems(set, {contact("a", 100, "Ma"), contact("b", 100, "Bee")}, true);
        const QList<Item> won = mergeItems(set, {contact("a", 200, "Mom"), contact("b", 50, "Old"), contact("c", 5, "")}, false);
        check(set.size() == 3 && won.size() == 2 && nick(set, "a") == QLatin1String("Mom") && nick(set, "b") == QLatin1String("Bee"),
              "l1: new items merge in, the later one wins per item");
        QHash<QString, Item> tie = set;
        mergeItems(tie, {contact("b", 100, "Phone's")}, true);
        check(nick(tie, "b") == QLatin1String("Phone's"), "l1: a tie goes to the phone's copy on the companion");
        tie = set;
        mergeItems(tie, {contact("b", 100, "Phone's")}, false);
        check(nick(tie, "b") == QLatin1String("Bee"), "l1: a tie stays with the phone on the phone");
        check(mergeItems(set, {contact("a", 200, "Mom")}, true).isEmpty(), "l1: the same copy again changes nothing");
        mergeItems(set, {Item{QStringLiteral("contact"), QStringLiteral("a"), 300, true, {}}}, false);
        check(set.value(QStringLiteral("contact|a")).del, "l1: a delete is a tombstone");
        mergeItems(set, {contact("a", 250, "Back")}, true);
        check(set.value(QStringLiteral("contact|a")).del, "l1: an older copy never brings a deleted one back");
        mergeItems(set, {contact("a", 400, "Again")}, false);
        check(!set.value(QStringLiteral("contact|a")).del && nick(set, "a") == QLatin1String("Again"), "l1: a newer one does");
        mergeItems(set, {Item{QStringLiteral("folder"), QStringLiteral("f"), 10, true, {}}}, true);
        pruneItems(set, 10 + kTombMs);
        check(set.contains(QStringLiteral("folder|f")), "l1: a tombstone stays 30 days");
        pruneItems(set, 11 + kTombMs);
        check(!set.contains(QStringLiteral("folder|f")) && set.size() == 3, "l1: then it goes, and nothing else does");
        // Linking: the phone's account replaces this device's, its name with nothing of the old one left.
        QHash<QString, Item> pc;
        mergeItems(pc, {me("Old computer name", 9000), contact("x", 9000, "")}, true);
        QHash<QString, Item> merged = pc;
        mergeItems(merged, {me("Raven", 10), contact("y", 10, "")}, true);
        check(jstr(merged.value(QStringLiteral("me|me")).v, "n") == QLatin1String("Old computer name"), "l1: plain merging would keep a newer old name");
        adoptItems(pc, {me("Raven", 10), contact("y", 10, "")});
        bool leftover = false;
        for (const Item &i : std::as_const(pc))
            leftover = leftover || compact(i.v).contains("Old computer name");
        check(jstr(pc.value(QStringLiteral("me|me")).v, "n") == QLatin1String("Raven") && !pc.contains(QStringLiteral("contact|x")) && !leftover,
              "l1: linking replaces the name, with no leftover");
        mergeItems(pc, {me("Raven K", 20)}, false);
        check(jstr(pc.value(QStringLiteral("me|me")).v, "n") == QLatin1String("Raven K"), "l1: after linking, the later change wins");
        // Items as they come, the caps token, and which frames may use the internet.
        bool ok = true;
        readItem(QJsonObject{{QStringLiteral("k"), QStringLiteral("car")}, {QStringLiteral("id"), QStringLiteral("a")}, {QStringLiteral("ts"), 1.0},
                             {QStringLiteral("v"), QJsonObject()}}, &ok);
        bool ok2 = true;
        readItem(QJsonObject{{QStringLiteral("k"), QStringLiteral("me")}, {QStringLiteral("id"), QStringLiteral("me")}, {QStringLiteral("v"), QJsonObject()}}, &ok2);
        bool ok3 = false;
        const Item t = readItem(Item{QStringLiteral("contact"), QStringLiteral("z"), 7, true, {}}.json(), &ok3);
        check(!ok && !ok2 && ok3 && t.del && t.ts == 7, "l1: bad items are dropped, a tombstone reads back");
        // Cap l2: a mute item, until turned back on, until a time, or over.
        bool ok4 = false;
        const Item always = readItem(QJsonObject{{QStringLiteral("k"), QStringLiteral("mute")}, {QStringLiteral("id"), QStringLiteral("g:x")}, {QStringLiteral("ts"), 1.0},
                                                  {QStringLiteral("v"), QJsonObject{{QStringLiteral("f"), true}}}}, &ok4);
        const Item soon{QStringLiteral("mute"), QStringLiteral("a"), 1, false, QJsonObject{{QStringLiteral("u"), double(now() + 60000)}}};
        const Item over{QStringLiteral("mute"), QStringLiteral("a"), 1, false, QJsonObject{{QStringLiteral("u"), 5.0}}};
        check(ok4 && muteUntil(always) == kMuteForever && muteUntil(soon) > now() && muteUntil(over) == 0
                  && muteUntil(Item{QStringLiteral("mute"), QStringLiteral("a"), 2, true, {}}) == 0, "l2: mute items");
        check(saysL1(QStringLiteral("l1")) && saysL1(QStringLiteral("r1 l1 m1")) && !saysL1(QStringLiteral("l10")) && !saysL1(QString()), "l1: the caps token");
        const QJsonObject small{{QStringLiteral("t"), QStringLiteral("acct")}, {QStringLiteral("items"), QJsonArray{contact("a", 1, "").json()}}};
        QJsonObject full = small;
        full.insert(QStringLiteral("full"), true);
        const QJsonObject pic{{QStringLiteral("t"), QStringLiteral("acct")},
                              {QStringLiteral("items"), QJsonArray{Item{QStringLiteral("pic"), QStringLiteral("a"), 1, false, QJsonObject{{QStringLiteral("pic"), QStringLiteral("AAAA")}}}.json()}}};
        check(internetOk(small) && !internetOk(full) && !internetOk(pic), "l1: a small change may use the internet, the full set and pictures never");
    }
    return bad == 0;
}
