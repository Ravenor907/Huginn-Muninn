#include "proof.h"

#include "chatcrypto.h"
#include "chatwire.h"
#include "groupwire.h"
#include "ratchet.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMessageAuthenticationCode>
#include <QRandomGenerator>
#include <cmath>
#include <cstdio>

const char Proof::kCap[] = "s1";
const char Proof::kMark[] = "aad";

namespace
{
const char kRoute[] = "huginn-muninn route v1";
const char kClose[] = "huginn-muninn close v1";
const QByteArray kHint = "huginn-muninn hint v1|";
const QByteArray kBound = "huginn-muninn s1\n";
const qint64 kDay = 86400000;

bool hexOf(const QString &s, int n, bool upper)
{
    if (s.size() != n)
        return false;
    for (const QChar c : s) {
        const ushort u = c.unicode();
        if (!((u >= '0' && u <= '9') || (upper ? (u >= 'A' && u <= 'F') : (u >= 'a' && u <= 'f'))))
            return false;
    }
    return true;
}

QJsonObject routeObj(const QString &key, const QString &fp) { return {{QStringLiteral("fp"), fp}, {QStringLiteral("k"), key}}; }
QJsonObject closeObj(const QString &key, const QString &n) { return {{QStringLiteral("k"), key}, {QStringLiteral("n"), n}}; }
}

QString Proof::route(const QByteArray &privateDer, const QString &key, const QString &fp)
{
    const QString f = fp.toUpper();
    if (!hexOf(f, 64, true))
        return {};
    bool ok = false;
    const QJsonObject s = GroupWire::sign(privateDer, QLatin1String(kRoute), routeObj(key, f), &ok);
    return ok ? s.value(QStringLiteral("sig")).toString() : QString();
}

bool Proof::routeOk(const QString &key, const QString &fp, const QString &sig)
{
    const QString f = fp.toUpper();
    if (sig.isEmpty() || !hexOf(f, 64, true))
        return false;
    QJsonObject o = routeObj(key, f);
    o.insert(QStringLiteral("sig"), sig);
    return GroupWire::verify(key, QLatin1String(kRoute), o);
}

bool Proof::isNonce(const QString &n) { return hexOf(n, 32, false); }

QString Proof::newNonce()
{
    QByteArray b(16, 0);
    for (int i = 0; i < 16; ++i)
        b[i] = char(QRandomGenerator::system()->bounded(256));
    return QString::fromLatin1(b.toHex());
}

QString Proof::close(const QByteArray &privateDer, const QString &key, const QString &nonce)
{
    if (!isNonce(nonce))
        return {};
    bool ok = false;
    const QJsonObject s = GroupWire::sign(privateDer, QLatin1String(kClose), closeObj(key, nonce), &ok);
    return ok ? s.value(QStringLiteral("sig")).toString() : QString();
}

bool Proof::closeOk(const QString &key, const QString &nonce, const QString &sig)
{
    if (sig.isEmpty() || !isNonce(nonce))
        return false;
    QJsonObject o = closeObj(key, nonce);
    o.insert(QStringLiteral("sig"), sig);
    return GroupWire::verify(key, QLatin1String(kClose), o);
}

QString Proof::hint(const QString &key, qint64 day)
{
    const QByteArray mac = QMessageAuthenticationCode::hash(kHint + QByteArray::number(day), key.toUtf8(), QCryptographicHash::Sha256);
    return QString::fromLatin1(mac.toHex().left(32));
}

QString Proof::hintNow(const QString &key) { return hint(key, QDateTime::currentMSecsSinceEpoch() / kDay); }

bool Proof::hintMatches(const QString &key, const QString &h, qint64 nowMs)
{
    if (h.size() != 32)
        return false;
    const qint64 d = nowMs / kDay;
    bool any = false;
    for (qint64 x = d - 1; x <= d + 1; ++x)
        any |= hint(key, x) == h;
    return any;
}

QString Proof::keyForHint(const QString &h, const QStringList &keys, qint64 nowMs)
{
    if (h.size() != 32)
        return {};
    for (const QString &k : keys)
        if (hintMatches(k, h, nowMs))
            return k;
    return {};
}

bool Proof::isBound(const QJsonObject &o) { return o.value(QLatin1String(kMark)).toString() == QLatin1String(kCap); }

QByteArray Proof::bound(const QJsonObject &o, const QString &toKey, bool *ok)
{
    *ok = false;
    QJsonObject b{{QStringLiteral("toKey"), toKey}};
    for (const char *k : {"room", "id", "fromKey", "mailbox", "caps", "invite"}) {
        const QJsonValue v = o.value(QLatin1String(k));
        if (v.isUndefined())
            continue;
        if (!v.isString())
            return {};
        b.insert(QLatin1String(k), v);
    }
    const QJsonValue d = o.value(QStringLiteral("days"));
    if (!d.isUndefined()) {
        // A whole number, 0 <= days < 2^53.
        const double x = d.toDouble(-1);
        if (!d.isDouble() || x < 0 || x >= 9007199254740992.0 || std::floor(x) != x)
            return {};
        b.insert(QStringLiteral("days"), qint64(x));
    }
    return GroupWire::canon(b, ok);
}

QByteArray Proof::aad(const QByteArray &boundText) { return kBound + boundText; }

// ------------------------------------------------------------------ self-test

namespace
{
QJsonObject readJson(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return {};
    return QJsonDocument::fromJson(f.readAll()).object();
}
}

bool Proof::selfTest(const QString &vectorsPath)
{
    const QJsonObject v = readJson(vectorsPath);
    const QJsonObject c = readJson(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("chat-vectors.json")));
    if (v.isEmpty() || c.isEmpty()) {
        std::printf("s1: can't read %s and chat-vectors.json next to it: FAILED\n", qPrintable(vectorsPath));
        return false;
    }
    bool all = true;
    const auto check = [&all](const QString &what, bool ok) {
        std::printf("s1 %s: %s\n", qPrintable(what), ok ? "ok" : "FAILED");
        all &= ok;
    };
    auto b = [&](const char *k) { return QByteArray::fromBase64(c.value(QLatin1String(k)).toString().toLatin1()); };
    const ChatCrypto::KeyPair a{b("privA"), b("pubA")}, bk{b("privB"), b("pubB")};
    const QString pubA = c.value(QStringLiteral("pubA")).toString(), pubB = c.value(QStringLiteral("pubB")).toString();

    // Route proofs from the vectors, then my own both ways.
    bool routes = true;
    for (const QJsonValue &x : v.value(QStringLiteral("route")).toObject().value(QStringLiteral("checks")).toArray()) {
        const QJsonObject r = x.toObject();
        const QString key = r.value(QStringLiteral("key")).toString(), n = r.value(QStringLiteral("n")).toString(), sig = r.value(QStringLiteral("sig")).toString();
        const bool got = r.value(QStringLiteral("kind")).toString() == QLatin1String("wifi") ? routeOk(key, n, sig) : closeOk(key, n, sig);
        if (got != r.value(QStringLiteral("ok")).toBool()) {
            std::printf("s1 route %s: wrong\n", qPrintable(r.value(QStringLiteral("what")).toString()));
            routes = false;
        }
    }
    check(QStringLiteral("route proofs from the vectors"), routes);
    const QString fp = v.value(QStringLiteral("route")).toObject().value(QStringLiteral("fingerprint")).toString();
    const QString n = newNonce();
    check(QStringLiteral("my own proofs check out, and never sign a fingerprint as a number"),
          isNonce(n) && routeOk(pubA, fp, route(a.privateDer, pubA, fp)) && closeOk(pubA, n, close(a.privateDer, pubA, n))
              && close(a.privateDer, pubA, fp).isEmpty() && !closeOk(pubB, n, close(a.privateDer, pubA, n)));

    // Hints.
    const QJsonObject h = v.value(QStringLiteral("hint")).toObject();
    bool hints = true;
    for (const QJsonValue &x : h.value(QStringLiteral("days")).toArray()) {
        const QJsonObject d = x.toObject();
        hints &= hint(d.value(QStringLiteral("key")).toString(), d.value(QStringLiteral("day")).toInteger()) == d.value(QStringLiteral("hint")).toString();
    }
    for (const QJsonValue &x : h.value(QStringLiteral("match")).toArray()) {
        const QJsonObject m = x.toObject();
        hints &= hintMatches(m.value(QStringLiteral("key")).toString(), m.value(QStringLiteral("hint")).toString(), m.value(QStringLiteral("now")).toInteger())
            == m.value(QStringLiteral("ok")).toBool();
    }
    const qint64 now = h.value(QStringLiteral("now")).toInteger();
    hints &= keyForHint(hint(pubA, now / kDay), {pubB, pubA}, now) == pubA && keyForHint(hint(pubA, now / kDay), {pubB}, now).isEmpty();
    check(QStringLiteral("hidden hints"), hints);

    // Bound fields.
    bool shapes = true;
    for (const QJsonValue &x : v.value(QStringLiteral("bound")).toArray()) {
        const QJsonObject bc = x.toObject();
        bool ok = false;
        const QByteArray got = bound(bc.value(QStringLiteral("envelope")).toObject(), bc.value(QStringLiteral("toKey")).toString(), &ok);
        const QJsonValue want = bc.value(QStringLiteral("bound"));
        shapes &= want.isNull() ? !ok : (ok && got == want.toString().toUtf8());
    }
    check(QStringLiteral("bound fields"), shapes);

    // v1, bound: opens only as sealed, for the one it was sealed to.
    const QJsonObject x1 = v.value(QStringLiteral("v1")).toObject();
    const QByteArray sk = QByteArray::fromHex(x1.value(QStringLiteral("pairKey")).toString().toLatin1());
    const QJsonObject env = x1.value(QStringLiteral("envelope")).toObject();
    const QString to = x1.value(QStringLiteral("toKey")).toString();
    bool ok = false;
    const QByteArray text = bound(env, to, &ok);
    const auto openV1 = [&](const QJsonObject &o, const QString &me) {
        bool good = false;
        const QByteArray p = ChatWire::open(o, sk, bk, nullptr, nullptr, &good, me);
        return good ? p : QByteArray("(refused)");
    };
    check(QStringLiteral("v1 bound: aad and open"), ok && text == x1.value(QStringLiteral("bound")).toString().toUtf8()
                                                      && aad(text) == x1.value(QStringLiteral("aad")).toString().toUtf8()
                                                      && openV1(env, to) == x1.value(QStringLiteral("plain")).toString().toUtf8()
                                                      && openV1(env, pubA) == "(refused)");
    bool refused = true;
    for (const QJsonValue &r : x1.value(QStringLiteral("refuse")).toArray())
        refused &= openV1(r.toObject().value(QStringLiteral("envelope")).toObject(), to) == "(refused)";
    check(QStringLiteral("v1 bound: changed fields are refused"), refused);
    QByteArray nonce = QByteArray::fromHex(x1.value(QStringLiteral("nonce")).toString().toLatin1()), data;
    ChatCrypto::seal(sk, x1.value(QStringLiteral("plain")).toString().toUtf8(), &nonce, &data, aad(text));
    check(QStringLiteral("v1 bound: the same bytes"), QString::fromLatin1(data.toBase64()) == env.value(QStringLiteral("data")).toString());

    // The ratchet, bound, from the vectors (Bob's first message on a new session).
    const QJsonObject rx = v.value(QStringLiteral("ratchet")).toObject();
    const QJsonObject renv = rx.value(QStringLiteral("envelope")).toObject();
    {
        bool rok = false;
        Ratchet::Header hd = Ratchet::Header::fromJson(renv.value(QStringLiteral("rh")).toObject(), &rok);
        hd.bind = bound(renv, to, &ok);
        Ratchet::Peer bob, bob2;
        bool opened = false, changed = true;
        const QByteArray plain = ChatWire::open(renv, sk, bk, &bob, [] { return ChatCrypto::newKeyPair(); }, &opened, to);
        QJsonObject tampered = renv;
        tampered.insert(QStringLiteral("days"), 7);
        ChatWire::open(tampered, sk, bk, &bob2, [] { return ChatCrypto::newKeyPair(); }, &changed, to);
        check(QStringLiteral("ratchet bound: aad and open"), rok && ok && hd.aad() == rx.value(QStringLiteral("aad")).toString().toUtf8() && opened
                                                                 && plain == rx.value(QStringLiteral("plain")).toString().toUtf8() && !changed);
    }

    // Sealing both ways through ChatWire: v1 and the ratchet, bound; an unbound one opens too.
    {
        const QByteArray skAB = ChatCrypto::sharedKey(a.privateDer, a.publicDer, bk.publicDer);
        QJsonObject f{{QStringLiteral("room"), QStringLiteral("private")}, {QStringLiteral("fromKey"), pubA}, {QStringLiteral("fromName"), QStringLiteral("Raven")},
                      {QStringLiteral("mailbox"), QString(64, QLatin1Char('a'))}, {QStringLiteral("id"), QStringLiteral("id-1")}, {QStringLiteral("days"), 30}};
        const Ratchet::NewKey nk = [] { return ChatCrypto::newKeyPair(); };
        bool o1 = false, o2 = false, o3 = true, o4 = false, o5 = true;
        const QJsonObject bv1 = ChatWire::seal(f, skAB, pubB, "hi", nullptr, nk, true);
        const QByteArray p1 = ChatWire::open(bv1, skAB, bk, nullptr, nk, &o1, pubB);
        const QJsonObject old = ChatWire::seal(f, skAB, pubB, "hi", nullptr, nk);
        const QByteArray p2 = ChatWire::open(old, skAB, bk, nullptr, nk, &o2, pubB);
        QJsonObject moved = bv1;
        moved.insert(QStringLiteral("days"), 7);
        ChatWire::open(moved, skAB, bk, nullptr, nk, &o3, pubB);
        Ratchet::Peer pa, pb;
        const QJsonObject r = ChatWire::seal(f, skAB, pubB, "ratchet", &pa, nk, true);
        QJsonObject r2 = r;
        r2.insert(QStringLiteral("room"), QStringLiteral("live"));
        Ratchet::Peer pb2 = pb;
        ChatWire::open(r2, skAB, bk, &pb2, nk, &o5, pubB);
        const QByteArray p4 = ChatWire::open(r, skAB, bk, &pb, nk, &o4, pubB);
        check(QStringLiteral("seal and open both ways"), isBound(bv1) && o1 && p1 == "hi" && !isBound(old) && o2 && p2 == "hi" && !o3
                                                             && isBound(r) && ChatWire::isRatchet(r) && !o5 && o4 && p4 == "ratchet");
    }

    // Group states: at most 1000 ahead, the owner wins a tie.
    const QJsonObject g = v.value(QStringLiteral("group")).toObject();
    const QJsonObject st = g.value(QStringLiteral("states")).toObject();
    bool groups = true;
    for (const QJsonValue &x : g.value(QStringLiteral("accept")).toArray()) {
        const QJsonObject t = x.toObject();
        const QJsonObject cur = st.value(t.value(QStringLiteral("current")).toString()).toObject();
        const GroupWire::Verdict r = GroupWire::accept(&cur, st.value(t.value(QStringLiteral("new")).toString()).toObject());
        if (r.ok != t.value(QStringLiteral("accept")).toBool() || r.why != t.value(QStringLiteral("why")).toString()) {
            std::printf("s1 group %s -> %s: got %s\n", qPrintable(t.value(QStringLiteral("current")).toString()),
                        qPrintable(t.value(QStringLiteral("new")).toString()), qPrintable(r.why));
            groups = false;
        }
    }
    for (const QJsonValue &x : g.value(QStringLiteral("winner")).toArray()) {
        const QJsonObject w = x.toObject();
        groups &= GroupWire::wins(st.value(w.value(QStringLiteral("a")).toString()).toObject(), st.value(w.value(QStringLiteral("b")).toString()).toObject())
            == (w.value(QStringLiteral("wins")).toString() == QLatin1String("a"));
    }
    check(QStringLiteral("group states: 1000 ahead at most, the owner wins a tie"), groups);
    return all;
}
