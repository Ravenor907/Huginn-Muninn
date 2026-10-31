#include "ring.h"
#include "nostr.h"
#include "chatwire.h"
#include "schnorr.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QTemporaryDir>
#include <cstdio>

#include <openssl/evp.h>
#include <openssl/x509.h>

const char RingWire::kRingCap[] = "b1";
const char RingWire::kWrapCap[] = "e1";

namespace
{
const QByteArray kWrapDomain = "huginn-muninn wrap v2";
const QByteArray kRingDomain = "huginn-muninn ring v1";
const QByteArray kP256Prefix = QByteArray::fromHex("3059301306072a8648ce3d020106082a8648ce3d030107034200");

QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QByteArray unb64(const QJsonValue &v) { return QByteArray::fromBase64(v.toString().toLatin1()); }
QByteArray sha256(const QByteArray &b) { return QCryptographicHash::hash(b, QCryptographicHash::Sha256); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

// An integer 0 <= n < 2^53 in a JSON value (Qt keeps every number as a double).
bool wholeNumber(const QJsonValue &v)
{
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return d >= 0 && d < 9007199254740992.0 && double(qint64(d)) == d;
}
}

bool RingWire::keyOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QByteArray::FromBase64Result r = QByteArray::fromBase64Encoding(v.toString().toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    if (!r)
        return false;
    const QByteArray der = *r;
    if (der.size() != 91 || !der.startsWith(kP256Prefix) || der.at(26) != 4)
        return false;
    const unsigned char *p = reinterpret_cast<const unsigned char *>(der.constData());
    EVP_PKEY *k = d2i_PUBKEY(nullptr, &p, der.size());
    const bool ok = k && EVP_PKEY_get_base_id(k) == EVP_PKEY_EC && EVP_PKEY_get_bits(k) == 256;
    EVP_PKEY_free(k);
    return ok;
}

QString RingWire::wrapId(const QString &pubB64)
{
    return QString::fromLatin1(sha256(QByteArray::fromBase64(pubB64.toLatin1())).toHex().left(16));
}

QByteArray RingWire::wrapKey(const QByteArray &k1, const QByteArray &k2) { return sha256(kWrapDomain + k1 + k2); }

QJsonObject RingWire::sealWrap(const QString &staticPub, const QString &dayPub, const QByteArray &body)
{
    const ChatCrypto::KeyPair eph = ChatCrypto::newKeyPair();
    const QByteArray k1 = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, QByteArray::fromBase64(staticPub.toLatin1()));
    const QByteArray k2 = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, QByteArray::fromBase64(dayPub.toLatin1()));
    if (k1.isEmpty() || k2.isEmpty())
        return {};
    QByteArray nonce, data;
    ChatCrypto::seal(wrapKey(k1, k2), body, &nonce, &data);
    return QJsonObject{{QStringLiteral("e"), b64(eph.publicDer)}, {QStringLiteral("w"), wrapId(dayPub)},
                       {QStringLiteral("n"), b64(nonce)}, {QStringLiteral("d"), b64(data)}};
}

QByteArray RingWire::openWrap(const QJsonObject &o, const ChatCrypto::KeyPair &me, const std::function<ChatCrypto::KeyPair(const QString &)> &day, bool *ok)
{
    *ok = false;
    const ChatCrypto::KeyPair d = day ? day(o.value(QStringLiteral("w")).toString()) : ChatCrypto::KeyPair();
    if (d.privateDer.isEmpty())
        return {};
    const QByteArray e = unb64(o.value(QStringLiteral("e")));
    const QByteArray k1 = ChatCrypto::sharedKey(me.privateDer, me.publicDer, e);
    const QByteArray k2 = ChatCrypto::sharedKey(d.privateDer, d.publicDer, e);
    if (k1.isEmpty() || k2.isEmpty())
        return {};
    return ChatCrypto::open(wrapKey(k1, k2), unb64(o.value(QStringLiteral("n"))), unb64(o.value(QStringLiteral("d"))), ok);
}

QByteArray RingWire::ringKey(const QByteArray &shared) { return sha256(kRingDomain + shared); }

QJsonObject RingWire::sealRing(const QString &ringPub, const QByteArray &plain)
{
    const ChatCrypto::KeyPair eph = ChatCrypto::newKeyPair();
    const QByteArray k = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, QByteArray::fromBase64(ringPub.toLatin1()));
    if (k.isEmpty())
        return {};
    QByteArray nonce, data;
    ChatCrypto::seal(ringKey(k), plain, &nonce, &data);
    return QJsonObject{{QStringLiteral("e"), b64(eph.publicDer)}, {QStringLiteral("n"), b64(nonce)}, {QStringLiteral("d"), b64(data)}};
}

QByteArray RingWire::openRing(const QJsonObject &o, const ChatCrypto::KeyPair &ring, bool *ok)
{
    *ok = false;
    const QByteArray k = ChatCrypto::sharedKey(ring.privateDer, ring.publicDer, unb64(o.value(QStringLiteral("e"))));
    if (k.isEmpty())
        return {};
    return ChatCrypto::open(ringKey(k), unb64(o.value(QStringLiteral("n"))), unb64(o.value(QStringLiteral("d"))), ok);
}

QByteArray RingWire::ringPlain(const QString &callId, qint64 ts)
{
    return compact(QJsonObject{{QStringLiteral("t"), QStringLiteral("ring")}, {QStringLiteral("call"), callId}, {QStringLiteral("ts"), ts}});
}

bool RingWire::ringOk(const QJsonObject &p)
{
    static const QRegularExpression callId(QStringLiteral("^[A-Za-z0-9_-]{22}$"));
    const QJsonValue c = p.value(QStringLiteral("call"));
    return p.value(QStringLiteral("t")).toString() == QLatin1String("ring") && c.isString() && callId.match(c.toString()).hasMatch()
        && wholeNumber(p.value(QStringLiteral("ts")));
}

bool RingWire::ringFresh(qint64 ts, qint64 now) { return ts >= now - kRingOldMs && ts <= now + kRingAheadMs; }

QString RingWire::ringContent(const QString &ringPub, const QString &callId, qint64 ts)
{
    const QJsonObject o = sealRing(ringPub, ringPlain(callId, ts));
    if (o.isEmpty())
        return QString();
    return b64(Nostr::pad(compact(o)));
}

bool RingWire::openRingContent(const QString &content, const ChatCrypto::KeyPair &ring, QString *callId, qint64 *ts)
{
    const QJsonObject o = QJsonDocument::fromJson(QByteArray::fromBase64(content.toLatin1())).object();
    if (o.isEmpty())
        return false;
    bool ok = false;
    const QJsonObject p = QJsonDocument::fromJson(openRing(o, ring, &ok)).object();
    if (!ok || !ringOk(p))
        return false;
    *callId = p.value(QStringLiteral("call")).toString();
    *ts = p.value(QStringLiteral("ts")).toInteger();
    return true;
}

RingWire::Announce RingWire::announceOf(const QJsonObject &r, qint64 nowS)
{
    static const QRegularExpression mailbox(QStringLiteral("^[0-9a-f]{64}$"));
    Announce a;
    const QJsonValue rp = r.value(QStringLiteral("rp"));
    if (keyOk(r.value(QStringLiteral("rk"))) && rp.isString() && mailbox.match(rp.toString()).hasMatch()) {
        a.rk = r.value(QStringLiteral("rk")).toString();
        a.rp = rp.toString();
    }
    const QJsonValue wt = r.value(QStringLiteral("wt"));
    if (keyOk(r.value(QStringLiteral("wk"))) && wholeNumber(wt)) {
        const qint64 t = wt.toInteger();
        if (nowS - t < kWrapKeepS && t - nowS <= kWrapAheadS) {
            a.wk = r.value(QStringLiteral("wk")).toString();
            a.wt = t;
        }
    }
    return a;
}

bool RingWire::mayUse(qint64 wt, qint64 nowS) { return nowS - wt <= kWrapUseS && wt - nowS <= kWrapAheadS; }
// A day key stamped more than a day ahead (made while the clock ran fast) is not kept: it would stay the newest and
// make a new one on every call once the clock is put back.
bool RingWire::keep(qint64 t, qint64 nowS) { return nowS - t < kWrapKeepS && t - nowS <= kWrapAheadS; }
bool RingWire::rotateDue(qint64 newest, qint64 nowS) { return newest < 0 || nowS - newest >= kWrapNewS || newest - nowS > kWrapAheadS; }

// ------------------------------------------------------------------ this device's day keys

WrapKeys::WrapKeys(std::function<QByteArray()> read, std::function<void(const QByteArray &)> write)
    : m_read(std::move(read)), m_write(std::move(write))
{
}

void WrapKeys::load()
{
    if (m_loaded)
        return;
    m_loaded = true;
    for (const QJsonValue &v : QJsonDocument::fromJson(m_read ? m_read() : QByteArray()).array()) {
        const QJsonObject o = v.toObject();
        Day d{o.value(QStringLiteral("i")).toString(), o.value(QStringLiteral("k")).toString(), unb64(o.value(QStringLiteral("p"))),
              o.value(QStringLiteral("t")).toInteger()};
        if (!d.id.isEmpty() && !d.priv.isEmpty())
            m_days << d;
    }
}

void WrapKeys::save() const
{
    QJsonArray a;
    for (const Day &d : m_days)
        a << QJsonObject{{QStringLiteral("i"), d.id}, {QStringLiteral("k"), d.pub}, {QStringLiteral("p"), b64(d.priv)}, {QStringLiteral("t"), d.t}};
    if (m_write)
        m_write(QJsonDocument(a).toJson(QJsonDocument::Compact));
}

WrapKeys::Day WrapKeys::current(qint64 nowS)
{
    load();
    const qsizetype before = m_days.size();
    m_days.removeIf([nowS](const Day &d) { return !RingWire::keep(d.t, nowS); });
    const Day *newest = nullptr;
    for (const Day &d : std::as_const(m_days))
        if (!newest || d.t >= newest->t)
            newest = &d;
    if (newest && !RingWire::rotateDue(newest->t, nowS)) {
        const Day keepIt = *newest;
        if (m_days.size() != before)
            save();
        return keepIt;
    }
    const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
    const QString pub = b64(kp.publicDer);
    const Day d{RingWire::wrapId(pub), pub, kp.privateDer, nowS};
    m_days << d;
    save();
    return d;
}

WrapKeys::Day WrapKeys::renew(qint64 nowS)
{
    load();
    m_days.removeIf([nowS](const Day &d) { return !RingWire::keep(d.t, nowS); });
    const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
    const QString pub = b64(kp.publicDer);
    const Day d{RingWire::wrapId(pub), pub, kp.privateDer, nowS};
    m_days << d;
    save();
    return d;
}

ChatCrypto::KeyPair WrapKeys::find(const QString &id, qint64 nowS)
{
    load();
    for (const Day &d : std::as_const(m_days))
        if (d.id == id && RingWire::keep(d.t, nowS))
            return ChatCrypto::KeyPair{d.priv, QByteArray::fromBase64(d.pub.toLatin1())};
    return {};
}

void WrapKeys::prune(qint64 nowS)
{
    load();
    if (m_days.removeIf([nowS](const Day &d) { return !RingWire::keep(d.t, nowS); }) > 0)
        save();
}

// ------------------------------------------------------------------ what contacts announced

PeerKeys::PeerKeys(std::function<QByteArray()> read, std::function<void(const QByteArray &)> write)
    : m_read(std::move(read)), m_write(std::move(write))
{
}

void PeerKeys::load()
{
    if (m_loaded)
        return;
    m_loaded = true;
    const QJsonObject all = QJsonDocument::fromJson(m_read ? m_read() : QByteArray()).object();
    for (auto it = all.begin(); it != all.end(); ++it) {
        const QJsonObject o = it.value().toObject();
        RingWire::Announce a;
        a.rk = o.value(QStringLiteral("rk")).toString();
        a.rp = o.value(QStringLiteral("rp")).toString();
        a.wk = o.value(QStringLiteral("wk")).toString();
        a.wt = o.value(QStringLiteral("wt")).toInteger();
        m_keys.insert(it.key(), a);
    }
}

void PeerKeys::save() const
{
    QJsonObject all;
    for (auto it = m_keys.cbegin(); it != m_keys.cend(); ++it)
        all.insert(it.key(), QJsonObject{{QStringLiteral("rk"), it->rk}, {QStringLiteral("rp"), it->rp}, {QStringLiteral("wk"), it->wk},
                                         {QStringLiteral("wt"), it->wt}});
    if (m_write)
        m_write(QJsonDocument(all).toJson(QJsonDocument::Compact));
}

void PeerKeys::take(const QString &key, const RingWire::Announce &a)
{
    if (a.rk.isEmpty() && a.wk.isEmpty())
        return;
    load();
    const RingWire::Announce old = m_keys.value(key);
    RingWire::Announce now = old;
    if (!a.rk.isEmpty()) {
        now.rk = a.rk;
        now.rp = a.rp;
    }
    if (!a.wk.isEmpty() && a.wt > old.wt) {
        now.wk = a.wk;
        now.wt = a.wt;
    }
    if (now.rk == old.rk && now.rp == old.rp && now.wk == old.wk && now.wt == old.wt)
        return;
    m_keys.insert(key, now);
    save();
}

RingWire::Announce PeerKeys::get(const QString &key)
{
    load();
    return m_keys.value(key);
}

QString PeerKeys::wrapFor(const QString &key, qint64 nowS)
{
    const RingWire::Announce a = get(key);
    return !a.wk.isEmpty() && RingWire::mayUse(a.wt, nowS) ? a.wk : QString();
}

void PeerKeys::forget(const QString &key)
{
    load();
    if (m_keys.remove(key))
        save();
}

// ------------------------------------------------------------------ this device's ring key

RingKey::RingKey(const QString &path) : m_path(path) {}

namespace
{
QJsonObject pairJson(const ChatCrypto::KeyPair &k, const QByteArray &secret)
{
    return QJsonObject{{QStringLiteral("k"), b64(k.publicDer)}, {QStringLiteral("s"), b64(k.privateDer)}, {QStringLiteral("n"), QString::fromLatin1(secret.toHex())}};
}

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
}

void RingKey::load()
{
    if (m_loaded)
        return;
    const auto pairOf = [](const QJsonObject &o) {
        Pair p;
        p.keys = ChatCrypto::KeyPair{unb64(o.value(QStringLiteral("s"))), unb64(o.value(QStringLiteral("k")))};
        p.secret = QByteArray::fromHex(o.value(QStringLiteral("n")).toString().toLatin1());
        if (p.keys.privateDer.isEmpty() || p.keys.publicDer.isEmpty() || p.secret.size() != 32)
            return Pair();
        p.mailbox = Schnorr::hex(Schnorr::publicKey(p.secret));
        return p;
    };
    QFile f(m_path);
    if (f.open(QIODevice::ReadOnly)) {
        const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
        f.close();
        m_now = pairOf(o);
        const QJsonObject old = o.value(QStringLiteral("old")).toObject();
        m_old = pairOf(old);
        m_oldUntil = m_old.mailbox.isEmpty() ? 0 : old.value(QStringLiteral("until")).toInteger();
        // Written by an older Huginn with the default permissions: this user only from now on.
        QFile::setPermissions(m_path, QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    }
    if (m_now.mailbox.isEmpty()) {
        m_now.keys = ChatCrypto::newKeyPair();
        m_now.secret = Schnorr::newSecret();
        m_now.mailbox = Schnorr::hex(Schnorr::publicKey(m_now.secret));
        save();
    }
    m_loaded = !m_now.keys.privateDer.isEmpty() && !m_now.mailbox.isEmpty();
}

bool RingKey::save() const
{
    QJsonObject o = pairJson(m_now.keys, m_now.secret);
    if (m_oldUntil > 0 && !m_old.mailbox.isEmpty()) {
        QJsonObject old = pairJson(m_old.keys, m_old.secret);
        old.insert(QStringLiteral("until"), m_oldUntil);
        o.insert(QStringLiteral("old"), old);
    }
    const QByteArray data = compact(o);
    // Readable by this user only (like the certificate's key, tls.cpp), written beside the file and then swapped in.
    QDir().mkpath(QFileInfo(m_path).absolutePath());
    const QString tmp = m_path + QStringLiteral(".new");
    QFile::remove(tmp);
    QFile f(tmp);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate, QFileDevice::ReadOwner | QFileDevice::WriteOwner))
        return false;
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const bool ok = f.write(data) == data.size();
    f.close();
    if (!ok || f.error() != QFileDevice::NoError) {
        QFile::remove(tmp);
        return false;
    }
    QFile::remove(m_path);
    return QFile::rename(tmp, m_path);
}

QString RingKey::publicKey()
{
    m_forgotten = false;
    load();
    return b64(m_now.keys.publicDer);
}

QString RingKey::mailbox()
{
    m_forgotten = false;
    load();
    return m_now.mailbox;
}

QStringList RingKey::mailboxes()
{
    if (m_forgotten)
        return {};
    load();
    QStringList out;
    if (!m_now.mailbox.isEmpty())
        out << m_now.mailbox;
    if (m_oldUntil > nowMs() && !m_old.mailbox.isEmpty())
        out << m_old.mailbox;
    return out;
}

QList<QByteArray> RingKey::nostrSecrets()
{
    if (m_forgotten)
        return {};
    load();
    QList<QByteArray> out;
    if (m_now.secret.size() == 32)
        out << m_now.secret;
    if (m_oldUntil > nowMs() && m_old.secret.size() == 32)
        out << m_old.secret;
    return out;
}

bool RingKey::open(const QString &content, QString *callId, qint64 *ts)
{
    if (m_forgotten)
        return false;
    load();
    if (RingWire::openRingContent(content, m_now.keys, callId, ts))
        return true;
    return m_oldUntil > nowMs() && !m_old.keys.privateDer.isEmpty() && RingWire::openRingContent(content, m_old.keys, callId, ts);
}

void RingKey::renew()
{
    m_forgotten = false;
    load();
    m_old = m_now;
    m_oldUntil = nowMs() + kOldKeepMs;
    m_now.keys = ChatCrypto::newKeyPair();
    m_now.secret = Schnorr::newSecret();
    m_now.mailbox = Schnorr::hex(Schnorr::publicKey(m_now.secret));
    save();
}

bool RingKey::dropOld()
{
    if (m_forgotten)
        return false;
    load();
    if (m_oldUntil == 0 || m_oldUntil > nowMs())
        return false;
    m_old = Pair();
    m_oldUntil = 0;
    save();
    return true;
}

void RingKey::forget()
{
    QFile::remove(m_path);
    QFile::remove(m_path + QStringLiteral(".new"));
    m_now = Pair();
    m_old = Pair();
    m_oldUntil = 0;
    m_loaded = false;
    m_forgotten = true;
}

// ------------------------------------------------------------------ self-test

bool RingWire::selfTest(const QString &vectorsPath)
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("e1/b1 %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::fprintf(stderr, "e1/b1 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    const auto s = [](const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); };
    const auto pair = [&s](const QJsonObject &o, const char *pub, const char *priv) {
        return ChatCrypto::KeyPair{QByteArray::fromBase64(s(o, priv).toLatin1()), QByteArray::fromBase64(s(o, pub).toLatin1())};
    };

    check("caps say b1 and e1", QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' ')).contains(QLatin1String(kRingCap))
                                    && QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' ')).contains(QLatin1String(kWrapCap)));
    const QJsonObject c = v.value(QStringLiteral("constants")).toObject();
    check("constants", c.value(QStringLiteral("wrapUseS")).toInteger() == kWrapUseS && c.value(QStringLiteral("wrapKeepS")).toInteger() == kWrapKeepS
                           && c.value(QStringLiteral("wrapNewS")).toInteger() == kWrapNewS && c.value(QStringLiteral("wrapAheadS")).toInteger() == kWrapAheadS
                           && c.value(QStringLiteral("ringOldMs")).toInteger() == kRingOldMs && c.value(QStringLiteral("ringAheadMs")).toInteger() == kRingAheadMs);

    // e1: the keys, the id, and the reference's wrapping opens (and the changed ones don't).
    const QJsonObject w = v.value(QStringLiteral("wrap")).toObject();
    const ChatCrypto::KeyPair eph = pair(w, "ephPub", "ephPriv"), me = pair(w, "recipientPub", "recipientPriv"),
                              day = pair(w, "dayPub", "dayPriv"), other = pair(w, "otherDayPub", "otherDayPriv");
    const QByteArray k1 = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, me.publicDer);
    const QByteArray k2 = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, day.publicDer);
    check("wrap keys", k1.toHex() == s(w, "k1").toLatin1() && k2.toHex() == s(w, "k2").toLatin1() && wrapKey(k1, k2).toHex() == s(w, "key").toLatin1());
    check("day key ids", wrapId(s(w, "dayPub")) == s(w, "dayId") && wrapId(s(w, "otherDayPub")) == s(w, "otherDayId"));
    const auto days = [&](const QString &id) {
        return id == s(w, "dayId") ? day : id == s(w, "otherDayId") ? other : ChatCrypto::KeyPair();
    };
    const auto onlyOther = [&](const QString &id) { return id == s(w, "otherDayId") ? other : ChatCrypto::KeyPair(); };
    bool ok = false;
    const QByteArray body = openWrap(w.value(QStringLiteral("outer")).toObject(), me, days, &ok);
    check("wrapping opens", ok && body == s(w, "body").toUtf8());
    bool gone = true, changed = true, otherId = true;
    openWrap(w.value(QStringLiteral("outer")).toObject(), me, onlyOther, &gone);
    openWrap(w.value(QStringLiteral("changed")).toObject(), me, days, &changed);
    openWrap(w.value(QStringLiteral("otherId")).toObject(), me, days, &otherId);
    check("a gone day key, a changed wrapping or another day key's id don't open", !gone && !changed && !otherId);
    const QJsonObject mine = sealWrap(b64(me.publicDer), b64(day.publicDer), "hello");
    bool round = false;
    check("own wrapping round trip", openWrap(mine, me, days, &round) == "hello" && round && mine.value(QStringLiteral("w")).toString() == s(w, "dayId"));

    // b1: the ring envelope.
    const QJsonObject r = v.value(QStringLiteral("ring")).toObject();
    const ChatCrypto::KeyPair ring = pair(r, "ringPub", "ringPriv"), reph = pair(r, "ephPub", "ephPriv"), wrong = pair(r, "wrongKeyPub", "wrongKeyPriv");
    const QByteArray rk = ChatCrypto::sharedKey(reph.privateDer, reph.publicDer, ring.publicDer);
    check("ring key", rk.toHex() == s(r, "shared").toLatin1() && ringKey(rk).toHex() == s(r, "key").toLatin1());
    bool rok = false, wok = true;
    check("ring envelope opens", openRing(r.value(QStringLiteral("outer")).toObject(), ring, &rok) == s(r, "plain").toUtf8() && rok);
    openRing(r.value(QStringLiteral("outer")).toObject(), wrong, &wok);
    check("ring envelope refuses another key", !wok);
    QString call;
    qint64 ts = 0;
    const QString content = ringContent(b64(ring.publicDer), QStringLiteral("AAECAwQFBgcICQoLDA0ODw"), 1761500130000);
    check("ring content round trip", content.size() % 1024 == 0 && openRingContent(content, ring, &call, &ts)
                                         && call == QLatin1String("AAECAwQFBgcICQoLDA0ODw") && ts == 1761500130000
                                         && !openRingContent(content, wrong, &call, &ts));
    bool plains = true;
    for (const QJsonValue &p : v.value(QStringLiteral("ringPlain")).toObject().value(QStringLiteral("good")).toArray())
        plains = plains && ringOk(p.toObject());
    for (const QJsonValue &p : v.value(QStringLiteral("ringPlain")).toObject().value(QStringLiteral("refuse")).toArray())
        plains = plains && !ringOk(p.toObject());
    check("ring plaintexts", plains);
    bool fresh = true;
    for (const QJsonValue &x : v.value(QStringLiteral("ringFresh")).toArray())
        fresh = fresh && ringFresh(x[QStringLiteral("ts")].toInteger(), x[QStringLiteral("now")].toInteger()) == x[QStringLiteral("ring")].toBool();
    check("ring freshness", fresh);

    // Both: the fields receipts carry, and the day key rules.
    bool ann = true;
    for (const QJsonValue &x : v.value(QStringLiteral("announce")).toArray()) {
        const Announce a = announceOf(x[QStringLiteral("fields")].toObject(), x[QStringLiteral("now")].toInteger());
        if ((!a.rk.isEmpty() && !a.rp.isEmpty()) != x[QStringLiteral("ring")].toBool() || !a.wk.isEmpty() != x[QStringLiteral("wrap")].toBool()) {
            std::fprintf(stderr, "e1/b1 announce case \"%s\" differs\n", qPrintable(x[QStringLiteral("name")].toString()));
            ann = false;
        }
    }
    check("announced keys", ann);
    bool rules = true;
    for (const QJsonValue &x : v.value(QStringLiteral("useWrap")).toArray())
        rules = rules && mayUse(x[QStringLiteral("wt")].toInteger(), x[QStringLiteral("now")].toInteger()) == x[QStringLiteral("use")].toBool();
    for (const QJsonValue &x : v.value(QStringLiteral("keepWrap")).toArray())
        rules = rules && keep(x[QStringLiteral("t")].toInteger(), x[QStringLiteral("now")].toInteger()) == x[QStringLiteral("keep")].toBool();
    for (const QJsonValue &x : v.value(QStringLiteral("rotate")).toArray()) {
        const qint64 newest = x[QStringLiteral("newest")].isNull() ? -1 : x[QStringLiteral("newest")].toInteger();
        rules = rules && rotateDue(newest, x[QStringLiteral("now")].toInteger()) == x[QStringLiteral("new")].toBool();
    }
    check("day key rules", rules);

    // This device's day keys: one a day, kept 40 days, then gone.
    {
        QByteArray stored;
        WrapKeys keys([&stored] { return stored; }, [&stored](const QByteArray &b) { stored = b; });
        const qint64 t0 = 1761500000;
        const WrapKeys::Day d0 = keys.current(t0);
        const bool same = keys.current(t0 + 3600).id == d0.id;
        const WrapKeys::Day d1 = keys.current(t0 + kDayS);
        WrapKeys again([&stored] { return stored; }, [&stored](const QByteArray &b) { stored = b; });
        const QJsonObject sealed = sealWrap(b64(me.publicDer), d0.pub, "hi");
        bool o1 = false, o2 = true;
        const QByteArray got = openWrap(sealed, me, [&](const QString &id) { return again.find(id, t0 + 39 * kDayS); }, &o1);
        openWrap(sealed, me, [&](const QString &id) { return again.find(id, t0 + 40 * kDayS); }, &o2);
        check("own day keys", same && d1.id != d0.id && got == "hi" && o1 && !o2);
        // A clock put back: the key stamped days ahead goes, and the one made then stays (no new key on every call).
        QByteArray fastStored;
        WrapKeys fast([&fastStored] { return fastStored; }, [&fastStored](const QByteArray &b) { fastStored = b; });
        const WrapKeys::Day ahead = fast.current(t0 + 5 * kDayS);
        const WrapKeys::Day back1 = fast.current(t0);
        const WrapKeys::Day back2 = fast.current(t0 + 60);
        check("clock put back", back1.id != ahead.id && back2.id == back1.id && fast.find(ahead.id, t0).privateDer.isEmpty()
                                    && !keep(t0 + kDayS + 1, t0) && keep(t0 + kDayS, t0));
    }
    // What contacts announced: the ring key as said, a day key only when newer, used for a week.
    {
        QByteArray stored;
        PeerKeys keys([&stored] { return stored; }, [&stored](const QByteArray &b) { stored = b; });
        const QString a = b64(ChatCrypto::newKeyPair().publicDer), b = b64(ChatCrypto::newKeyPair().publicDer);
        keys.take(QStringLiteral("A"), Announce{a, QString(64, QLatin1Char('a')), a, 1000});
        keys.take(QStringLiteral("A"), Announce{QString(), QString(), b, 900});
        const bool older = keys.get(QStringLiteral("A")).wk == a;
        keys.take(QStringLiteral("A"), Announce{QString(), QString(), b, 2000});
        PeerKeys back([&stored] { return stored; }, [&stored](const QByteArray &x) { stored = x; });
        check("contacts' keys", older && back.get(QStringLiteral("A")).wk == b && back.get(QStringLiteral("A")).rk == a
                                    && back.wrapFor(QStringLiteral("A"), 2000 + kWrapUseS) == b && back.wrapFor(QStringLiteral("A"), 2001 + kWrapUseS).isEmpty());
    }
    // The ring key file outside the vault: made once, read back the same, and it opens what was sealed to it.
    {
        QTemporaryDir tmp;
        const QString path = tmp.path() + QStringLiteral("/ring-key.json");
        RingKey a(path);
        const QString pub = a.publicKey();
        RingKey b(path);
        QString cid;
        qint64 t = 0;
        check("ring key file", tmp.isValid() && keyOk(pub) && b.publicKey() == pub && b.mailbox() == a.mailbox() && a.mailbox().size() == 64
                                   && b.open(ringContent(pub, QStringLiteral("AAECAwQFBgcICQoLDA0ODw"), 42), &cid, &t) && t == 42);
#ifdef Q_OS_UNIX
        check("ring key file is this user's only",
              (QFileInfo(path).permissions() & (QFileDevice::ReadGroup | QFileDevice::ReadOther | QFileDevice::WriteGroup | QFileDevice::WriteOther)) == 0);
#endif
        // Renewed (someone blocked): a new key and mailbox; the old one is still read for two days, then dropped.
        const QString oldBox = b.mailbox();
        b.renew();
        RingKey c(path);
        QString cid2;
        qint64 t2 = 0;
        check("ring key renewed", c.publicKey() != pub && c.mailbox() != oldBox && c.mailboxes() == QStringList({c.mailbox(), oldBox})
                                      && c.nostrSecrets().size() == 2 && c.open(ringContent(pub, QStringLiteral("AAECAwQFBgcICQoLDA0ODw"), 7), &cid2, &t2)
                                      && t2 == 7 && !c.dropOld());
        // Erase everything: the file goes, and the next key is a new one.
        const QString cpub = c.publicKey();
        c.forget();
        const bool gone = !QFile::exists(path) && c.mailboxes().isEmpty() && !QFile::exists(path);
        RingKey d(path);
        check("ring key forgotten", gone && d.publicKey() != cpub && d.mailboxes().size() == 1);
    }
    return all;
}
