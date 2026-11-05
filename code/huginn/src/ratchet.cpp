#include "ratchet.h"
#include "proof.h"

#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonValue>
#include <algorithm>
#include <cstddef>
#include <cstdio>

#include <openssl/bn.h>
#include <openssl/core_names.h>
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/obj_mac.h>
#include <openssl/param_build.h>
#include <openssl/x509.h>

namespace
{
const int kMaxSkip = 1000;
const int kMaxSessions = 3;
const QByteArray kRootInfo = "huginn-muninn ratchet";
const QByteArray kMessageInfo = "huginn-muninn message";

const unsigned char *u(const QByteArray &b) { return reinterpret_cast<const unsigned char *>(b.constData()); }
unsigned char *u(QByteArray &b) { return reinterpret_cast<unsigned char *>(b.data()); }

QString toB64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QByteArray fromB64(const QString &s) { return QByteArray::fromBase64(s.toLatin1()); }

EVP_PKEY *readPublic(const QByteArray &der)
{
    const unsigned char *p = u(der);
    return d2i_PUBKEY(nullptr, &p, der.size());
}

EVP_PKEY *readPrivate(const QByteArray &der)
{
    const unsigned char *p = u(der);
    return d2i_AutoPrivateKey(nullptr, &p, der.size());
}

// Plain ECDH between my private key and their public key (base64 SPKI). Empty on bad input.
QByteArray dh(const QByteArray &myPrivateDer, const QString &theirPublicB64)
{
    EVP_PKEY *mine = readPrivate(myPrivateDer);
    EVP_PKEY *theirs = readPublic(fromB64(theirPublicB64));
    QByteArray secret;
    if (mine && theirs) {
        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(mine, nullptr);
        size_t len = 0;
        if (ctx && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_derive_set_peer(ctx, theirs) > 0 && EVP_PKEY_derive(ctx, nullptr, &len) > 0) {
            secret.resize(int(len));
            if (EVP_PKEY_derive(ctx, u(secret), &len) <= 0)
                secret.clear();
        }
        EVP_PKEY_CTX_free(ctx);
    }
    EVP_PKEY_free(mine);
    EVP_PKEY_free(theirs);
    return secret;
}

QByteArray hkdf(const QByteArray &ikm, const QByteArray &salt, const QByteArray &info, int length)
{
    QByteArray out(length, 0);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, nullptr);
    size_t len = size_t(length);
    const bool ok = ctx && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha256()) > 0
        && EVP_PKEY_CTX_set1_hkdf_salt(ctx, u(salt), int(salt.size())) > 0
        && EVP_PKEY_CTX_set1_hkdf_key(ctx, u(ikm), int(ikm.size())) > 0
        && EVP_PKEY_CTX_add1_hkdf_info(ctx, u(info), int(info.size())) > 0
        && EVP_PKEY_derive(ctx, u(out), &len) > 0;
    EVP_PKEY_CTX_free(ctx);
    return ok ? out : QByteArray();
}

QByteArray hmac(const QByteArray &key, char byte)
{
    QByteArray out(32, 0);
    const unsigned char data = static_cast<unsigned char>(byte);
    size_t len = 0;
    if (!EVP_Q_mac(nullptr, "HMAC", nullptr, "SHA256", nullptr, u(key), size_t(key.size()), &data, 1, u(out), 32, &len) || len != 32)
        return {};
    return out;
}

// KDF_RK: new root key and a chain key. False on bad input.
bool kdfRk(const QByteArray &rk, const QByteArray &dhOut, QByteArray *newRk, QByteArray *ck)
{
    if (dhOut.isEmpty())
        return false;
    const QByteArray out = hkdf(dhOut, rk, kRootInfo, 64);
    if (out.size() != 64)
        return false;
    *newRk = out.left(32);
    *ck = out.mid(32);
    return true;
}

// KDF_CK: the message key, and the chain key moves on.
QByteArray kdfCk(QByteArray *ck)
{
    const QByteArray mk = hmac(*ck, 1);
    *ck = hmac(*ck, 2);
    return mk;
}

// AES-256-GCM with the key and nonce derived from the message key, and the header as AAD.
QByteArray seal(const QByteArray &mk, const Ratchet::Header &h, const QByteArray &plain)
{
    const QByteArray k = hkdf(mk, QByteArray(32, 0), kMessageInfo, 44);
    if (k.size() != 44)
        return {};
    const QByteArray key = k.left(32), nonce = k.mid(32), aad = h.aad();
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    const bool ok = c && EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) > 0
        && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) > 0
        && EVP_EncryptInit_ex(c, nullptr, nullptr, u(key), u(nonce)) > 0
        && EVP_EncryptUpdate(c, nullptr, &len, u(aad), int(aad.size())) > 0
        && EVP_EncryptUpdate(c, u(out), &len, u(plain), int(plain.size())) > 0
        && (total = len, EVP_EncryptFinal_ex(c, u(out) + total, &len) > 0)
        && (total += len, EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total) > 0);
    EVP_CIPHER_CTX_free(c);
    return ok ? out.left(total + 16) : QByteArray();
}

bool unseal(const QByteArray &mk, const Ratchet::Header &h, const QByteArray &data, QByteArray *plain)
{
    const QByteArray k = hkdf(mk, QByteArray(32, 0), kMessageInfo, 44);
    if (k.size() != 44 || data.size() < 16)
        return false;
    const QByteArray key = k.left(32), nonce = k.mid(32), aad = h.aad();
    const QByteArray body = data.left(data.size() - 16);
    QByteArray tag = data.mid(data.size() - 16);
    QByteArray out(body.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    const bool good = c && EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) > 0
        && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) > 0
        && EVP_DecryptInit_ex(c, nullptr, nullptr, u(key), u(nonce)) > 0
        && EVP_DecryptUpdate(c, nullptr, &len, u(aad), int(aad.size())) > 0
        && EVP_DecryptUpdate(c, u(out), &len, u(body), int(body.size())) > 0
        && (total = len, EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, tag.data()) > 0)
        && EVP_DecryptFinal_ex(c, u(out) + total, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    if (!good)
        return false;
    *plain = out.left(total + len);
    return true;
}

// Keeps the receiving keys for messages up to (not including) "until". False when too many.
bool skipTo(Ratchet::Session &s, int until)
{
    if (s.ckr.isEmpty())
        return true;
    if (qint64(until) - qint64(s.nr) > kMaxSkip)
        return false;
    while (s.nr < until) {
        const QByteArray mk = kdfCk(&s.ckr);
        if (mk.isEmpty())
            return false;
        s.skipped.push_back({s.dhr, s.nr, mk});
        ++s.nr;
    }
    if (s.skipped.size() > size_t(kMaxSkip))
        s.skipped.erase(s.skipped.begin(), s.skipped.begin() + std::ptrdiff_t(s.skipped.size() - size_t(kMaxSkip)));
    return true;
}

// The decrypt steps from the spec, on a session the caller throws away if this fails.
bool step(Ratchet::Session &s, const Ratchet::Header &h, const QByteArray &cipher, const Ratchet::NewKey &newKey, QByteArray *plain)
{
    for (auto it = s.skipped.begin(); it != s.skipped.end(); ++it) {
        if (it->d == h.d && it->n == h.n) {
            const QByteArray mk = it->mk;
            s.skipped.erase(it);
            return unseal(mk, h, cipher, plain);
        }
    }
    if (h.d != s.dhr) {
        if (!skipTo(s, h.pn))
            return false;
        s.pn = s.ns;
        s.ns = 0;
        s.nr = 0;
        s.dhr = h.d;
        if (!kdfRk(s.rk, dh(s.dhs.privateDer, s.dhr), &s.rk, &s.ckr))
            return false;
        s.dhs = newKey ? newKey() : ChatCrypto::KeyPair();
        if (s.dhs.privateDer.isEmpty() || s.dhs.publicDer.isEmpty())
            return false;
        if (!kdfRk(s.rk, dh(s.dhs.privateDer, s.dhr), &s.rk, &s.cks))
            return false;
    }
    if (!skipTo(s, h.n) || s.ckr.isEmpty())
        return false;
    const QByteArray mk = kdfCk(&s.ckr);
    ++s.nr;
    return !mk.isEmpty() && unseal(mk, h, cipher, plain);
}

ChatCrypto::KeyPair exportPair(EVP_PKEY *key)
{
    ChatCrypto::KeyPair kp;
    unsigned char *buf = nullptr;
    int n = i2d_PUBKEY(key, &buf);
    if (n > 0)
        kp.publicDer = QByteArray(reinterpret_cast<char *>(buf), n);
    OPENSSL_free(buf);
    buf = nullptr;
    PKCS8_PRIV_KEY_INFO *p8 = EVP_PKEY2PKCS8(key);
    n = p8 ? i2d_PKCS8_PRIV_KEY_INFO(p8, &buf) : 0;
    if (n > 0)
        kp.privateDer = QByteArray(reinterpret_cast<char *>(buf), n);
    OPENSSL_free(buf);
    PKCS8_PRIV_KEY_INFO_free(p8);
    return kp;
}

int intField(const QJsonObject &o, const QString &key)
{
    const QJsonValue v = o.value(key);
    return v.isDouble() ? v.toInt(-1) : -1;
}
}

// ---- Header ----

QByteArray Ratchet::Header::aad() const
{
    const QByteArray head = QByteArray("v1|") + b.toUtf8() + '|' + d.toUtf8() + '|' + QByteArray::number(pn) + '|' + QByteArray::number(n);
    // Cap s1: then the same bound fields as v1 (Proof::aad), after a line break.
    return bind.isEmpty() ? head : head + '\n' + Proof::aad(bind);
}

QJsonObject Ratchet::Header::toJson() const
{
    QJsonObject o;
    o.insert(QStringLiteral("b"), b);
    o.insert(QStringLiteral("d"), d);
    o.insert(QStringLiteral("pn"), pn);
    o.insert(QStringLiteral("n"), n);
    return o;
}

Ratchet::Header Ratchet::Header::fromJson(const QJsonObject &o, bool *ok)
{
    Header h;
    h.b = o.value(QStringLiteral("b")).toString();
    h.d = o.value(QStringLiteral("d")).toString();
    h.pn = intField(o, QStringLiteral("pn"));
    h.n = intField(o, QStringLiteral("n"));
    if (ok)
        *ok = !h.b.isEmpty() && !h.d.isEmpty() && h.pn >= 0 && h.n >= 0;
    return h;
}

// ---- Session ----

Ratchet::Session Ratchet::Session::alice(const QByteArray &sk, const QString &theirStaticB64, const ChatCrypto::KeyPair &eph)
{
    Session s;
    s.dhs = eph;
    s.dhr = theirStaticB64;
    s.base = toB64(eph.publicDer);
    if (!kdfRk(sk, dh(eph.privateDer, theirStaticB64), &s.rk, &s.cks)) {
        s.rk.clear();
        s.cks.clear();
    }
    return s;
}

Ratchet::Session Ratchet::Session::bob(const QByteArray &sk, const ChatCrypto::KeyPair &myStatic, const QString &base)
{
    Session s;
    s.dhs = myStatic;
    s.base = base;
    s.rk = sk;
    return s;
}

std::pair<Ratchet::Header, QByteArray> Ratchet::Session::encrypt(const QByteArray &plain, const QByteArray &bind)
{
    Header h;
    h.bind = bind;
    if (!canSend())
        return {h, QByteArray()};
    QByteArray ck = cks;
    const QByteArray mk = kdfCk(&ck);
    h.b = base;
    h.d = toB64(dhs.publicDer);
    h.pn = pn;
    h.n = ns;
    const QByteArray c = mk.isEmpty() ? QByteArray() : seal(mk, h, plain);
    if (c.isEmpty())
        return {h, QByteArray()};
    cks = ck;
    ++ns;
    return {h, c};
}

QByteArray Ratchet::Session::decrypt(const Header &h, const QByteArray &cipher, const NewKey &newKey, bool *ok)
{
    *ok = false;
    Session s = *this;
    QByteArray plain;
    if (!step(s, h, cipher, newKey, &plain))
        return {};
    *this = std::move(s);
    *ok = true;
    return plain;
}

QJsonObject Ratchet::Session::toJson() const
{
    QJsonArray skip;
    for (const Skipped &k : skipped) {
        QJsonObject e;
        e.insert(QStringLiteral("d"), k.d);
        e.insert(QStringLiteral("n"), k.n);
        e.insert(QStringLiteral("mk"), toB64(k.mk));
        skip.append(e);
    }
    QJsonObject o;
    o.insert(QStringLiteral("base"), base);
    o.insert(QStringLiteral("rk"), toB64(rk));
    o.insert(QStringLiteral("dhsPrivate"), toB64(dhs.privateDer));
    o.insert(QStringLiteral("dhsPublic"), toB64(dhs.publicDer));
    o.insert(QStringLiteral("dhr"), dhr);
    o.insert(QStringLiteral("cks"), toB64(cks));
    o.insert(QStringLiteral("ckr"), toB64(ckr));
    o.insert(QStringLiteral("ns"), ns);
    o.insert(QStringLiteral("nr"), nr);
    o.insert(QStringLiteral("pn"), pn);
    o.insert(QStringLiteral("skipped"), skip);
    return o;
}

Ratchet::Session Ratchet::Session::fromJson(const QJsonObject &o, bool *ok)
{
    Session s;
    s.base = o.value(QStringLiteral("base")).toString();
    s.rk = fromB64(o.value(QStringLiteral("rk")).toString());
    s.dhs.privateDer = fromB64(o.value(QStringLiteral("dhsPrivate")).toString());
    s.dhs.publicDer = fromB64(o.value(QStringLiteral("dhsPublic")).toString());
    s.dhr = o.value(QStringLiteral("dhr")).toString();
    s.cks = fromB64(o.value(QStringLiteral("cks")).toString());
    s.ckr = fromB64(o.value(QStringLiteral("ckr")).toString());
    s.ns = intField(o, QStringLiteral("ns"));
    s.nr = intField(o, QStringLiteral("nr"));
    s.pn = intField(o, QStringLiteral("pn"));
    bool good = !s.base.isEmpty() && s.rk.size() == 32 && !s.dhs.privateDer.isEmpty() && !s.dhs.publicDer.isEmpty()
        && (s.cks.isEmpty() || s.cks.size() == 32) && (s.ckr.isEmpty() || s.ckr.size() == 32) && s.ns >= 0 && s.nr >= 0 && s.pn >= 0;
    const QJsonArray skip = o.value(QStringLiteral("skipped")).toArray();
    for (int i = 0; i < int(skip.size()) && i < kMaxSkip; ++i) {
        const QJsonObject e = skip.at(i).toObject();
        Skipped k{e.value(QStringLiteral("d")).toString(), intField(e, QStringLiteral("n")), fromB64(e.value(QStringLiteral("mk")).toString())};
        if (k.d.isEmpty() || k.n < 0 || k.mk.size() != 32)
            good = false;
        else
            s.skipped.push_back(k);
    }
    if (ok)
        *ok = good;
    return s;
}

// ---- Peer ----

Ratchet::Session *Ratchet::Peer::find(const QString &base)
{
    for (Session &s : sessions)
        if (s.base == base)
            return &s;
    return nullptr;
}

// Moves a session to the front and forgets the oldest past 3.
void Ratchet::Peer::touch(const QString &base)
{
    auto it = std::find_if(sessions.begin(), sessions.end(), [&](const Session &s) { return s.base == base; });
    if (it != sessions.end() && it != sessions.begin())
        std::rotate(sessions.begin(), it, it + 1);
    if (sessions.size() > size_t(kMaxSessions))
        sessions.resize(size_t(kMaxSessions));
}

std::pair<Ratchet::Header, QByteArray> Ratchet::Peer::encrypt(const QByteArray &sk, const QString &theirStaticB64, const QByteArray &plain,
                                                              const NewKey &newKey, const QByteArray &bind)
{
    Session *s = active.isEmpty() ? nullptr : find(active);
    if (s && s->canSend()) {
        auto out = s->encrypt(plain, bind);
        if (!out.second.isEmpty())
            touch(active);
        return out;
    }
    const ChatCrypto::KeyPair eph = newKey ? newKey() : ChatCrypto::KeyPair();
    if (eph.privateDer.isEmpty() || eph.publicDer.isEmpty())
        return {Header(), QByteArray()};
    Session fresh = Session::alice(sk, theirStaticB64, eph);
    auto out = fresh.encrypt(plain, bind);
    if (out.second.isEmpty())
        return out;
    sessions.insert(sessions.begin(), std::move(fresh));
    active = sessions.front().base;
    touch(active);
    return out;
}

QByteArray Ratchet::Peer::decrypt(const QByteArray &sk, const ChatCrypto::KeyPair &myStatic, const Header &h, const QByteArray &cipher,
                                  const NewKey &newKey, bool *ok)
{
    *ok = false;
    if (Session *s = find(h.b)) {
        const QByteArray plain = s->decrypt(h, cipher, newKey, ok);
        if (*ok) {
            active = h.b;
            touch(h.b);
        }
        return plain;
    }
    // Only the first sender's own key can start a session.
    if (h.b.isEmpty() || h.d != h.b)
        return {};
    Session fresh = Session::bob(sk, myStatic, h.b);
    const QByteArray plain = fresh.decrypt(h, cipher, newKey, ok);
    if (!*ok)
        return {};
    sessions.insert(sessions.begin(), std::move(fresh));
    active = h.b;
    touch(h.b);
    return plain;
}

QJsonObject Ratchet::Peer::toJson() const
{
    QJsonArray list;
    for (const Session &s : sessions)
        list.append(s.toJson());
    QJsonObject o;
    o.insert(QStringLiteral("active"), active);
    o.insert(QStringLiteral("sessions"), list);
    return o;
}

Ratchet::Peer Ratchet::Peer::fromJson(const QJsonObject &o, bool *ok)
{
    Peer p;
    bool good = true;
    const QJsonArray list = o.value(QStringLiteral("sessions")).toArray();
    for (int i = 0; i < int(list.size()) && i < kMaxSessions; ++i) {
        bool one = false;
        Session s = Session::fromJson(list.at(i).toObject(), &one);
        if (one)
            p.sessions.push_back(std::move(s));
        else
            good = false;
    }
    p.active = o.value(QStringLiteral("active")).toString();
    if (!p.active.isEmpty() && !p.find(p.active))
        p.active.clear();
    if (ok)
        *ok = good;
    return p;
}

// ---- Test keys ----

ChatCrypto::KeyPair Ratchet::keyFromNumber(quint64 n)
{
    ChatCrypto::KeyPair kp;
    unsigned char be[8];
    for (int i = 0; i < 8; ++i)
        be[i] = static_cast<unsigned char>(n >> (56 - 8 * i));
    EC_GROUP *group = EC_GROUP_new_by_curve_name(NID_X9_62_prime256v1);
    BIGNUM *d = BN_bin2bn(be, 8, nullptr);
    EC_POINT *point = group ? EC_POINT_new(group) : nullptr;
    unsigned char pub[65];
    size_t pubLen = 0;
    if (point && d && !BN_is_zero(d) && EC_POINT_mul(group, point, d, nullptr, nullptr, nullptr) > 0)
        pubLen = EC_POINT_point2oct(group, point, POINT_CONVERSION_UNCOMPRESSED, pub, sizeof pub, nullptr);
    OSSL_PARAM_BLD *bld = pubLen ? OSSL_PARAM_BLD_new() : nullptr;
    OSSL_PARAM *params = nullptr;
    if (bld && OSSL_PARAM_BLD_push_utf8_string(bld, OSSL_PKEY_PARAM_GROUP_NAME, "prime256v1", 0) > 0
        && OSSL_PARAM_BLD_push_BN(bld, OSSL_PKEY_PARAM_PRIV_KEY, d) > 0
        && OSSL_PARAM_BLD_push_octet_string(bld, OSSL_PKEY_PARAM_PUB_KEY, pub, pubLen) > 0)
        params = OSSL_PARAM_BLD_to_param(bld);
    EVP_PKEY_CTX *ctx = params ? EVP_PKEY_CTX_new_from_name(nullptr, "EC", nullptr) : nullptr;
    EVP_PKEY *key = nullptr;
    if (ctx && EVP_PKEY_fromdata_init(ctx) > 0 && EVP_PKEY_fromdata(ctx, &key, EVP_PKEY_KEYPAIR, params) > 0 && key)
        kp = exportPair(key);
    EVP_PKEY_free(key);
    EVP_PKEY_CTX_free(ctx);
    OSSL_PARAM_free(params);
    OSSL_PARAM_BLD_free(bld);
    EC_POINT_free(point);
    BN_free(d);
    EC_GROUP_free(group);
    return kp;
}

// ---- Self-test ----

namespace
{
QJsonObject readJson(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return {};
    return QJsonDocument::fromJson(f.readAll()).object();
}

QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

// A fresh pair of static keys and their pair key, for the checks that don't use the vectors.
struct Pair {
    ChatCrypto::KeyPair a = ChatCrypto::newKeyPair(), b = ChatCrypto::newKeyPair();
    QByteArray skA = ChatCrypto::sharedKey(a.privateDer, a.publicDer, b.publicDer);
    QByteArray skB = ChatCrypto::sharedKey(b.privateDer, b.publicDer, a.publicDer);
};
}

bool Ratchet::selfTest(const QString &vectorsPath)
{
    const QJsonObject v = readJson(vectorsPath);
    const QJsonObject c = readJson(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("chat-vectors.json")));
    if (v.isEmpty() || c.isEmpty()) {
        fprintf(stderr, "ratchet self-test: can't read %s and chat-vectors.json next to it\n", qPrintable(vectorsPath));
        return false;
    }
    auto b = [&](const char *k) { return QByteArray::fromBase64(c.value(QLatin1String(k)).toString().toLatin1()); };
    const ChatCrypto::KeyPair staticA{b("privA"), b("pubA")}, staticB{b("privB"), b("pubB")};
    const QString pubA = toB64(staticA.publicDer), pubB = toB64(staticB.publicDer);
    const QByteArray skA = ChatCrypto::sharedKey(staticA.privateDer, staticA.publicDer, staticB.publicDer);
    const QByteArray skB = ChatCrypto::sharedKey(staticB.privateDer, staticB.publicDer, staticA.publicDer);
    const bool keys = !skA.isEmpty() && skA == skB && skA.toHex() == v.value(QStringLiteral("sk")).toString().toLatin1()
        && pubA == v.value(QStringLiteral("pubA")).toString() && pubB == v.value(QStringLiteral("pubB")).toString();

    // The conversation from main() in shared/tools/ratchet_ref.py, through Peer, saving and loading
    // both sides before every step like Chat does.
    quint64 nextA = 0x1001, nextB = 0x2001;
    const NewKey keyA = [&] { return keyFromNumber(nextA++); };
    const NewKey keyB = [&] { return keyFromNumber(nextB++); };
    Peer alice, bob;
    const QJsonArray messages = v.value(QStringLiteral("messages")).toArray();
    int sent = 0, sentOk = 0, delivered = 0, deliveredOk = 0;
    bool reload = true;
    QJsonArray order;
    auto reloadBoth = [&] {
        bool x = false, y = false;
        alice = Peer::fromJson(alice.toJson(), &x);
        bob = Peer::fromJson(bob.toJson(), &y);
        reload = reload && x && y;
    };
    auto send = [&](int i) {
        reloadBoth();
        ++sent;
        const QJsonObject m = messages.at(i).toObject();
        const bool fromA = m.value(QStringLiteral("from")).toString() == QStringLiteral("A");
        const QByteArray text = m.value(QStringLiteral("text")).toString().toUtf8();
        auto out = fromA ? alice.encrypt(skA, pubB, text, keyA) : bob.encrypt(skB, pubA, text, keyB);
        const QJsonObject want = m.value(QStringLiteral("header")).toObject();
        bool hok = false;
        const Header wh = Header::fromJson(want, &hok);
        if (hok && out.first.b == wh.b && out.first.d == wh.d && out.first.pn == wh.pn && out.first.n == wh.n
            && toB64(out.second) == m.value(QStringLiteral("c")).toString())
            ++sentOk;
    };
    auto deliver = [&](const char *to, int i) {
        reloadBoth();
        ++delivered;
        order.append(QJsonArray{QString::fromLatin1(to), i});
        const QJsonObject m = messages.at(i).toObject();
        bool hok = false, ok = false;
        const Header h = Header::fromJson(m.value(QStringLiteral("header")).toObject(), &hok);
        const QByteArray cipher = fromB64(m.value(QStringLiteral("c")).toString());
        const QByteArray plain = to[0] == 'A' ? alice.decrypt(skA, staticA, h, cipher, keyA, &ok) : bob.decrypt(skB, staticB, h, cipher, keyB, &ok);
        if (hok && ok && QString::fromUtf8(plain) == m.value(QStringLiteral("text")).toString())
            ++deliveredOk;
    };
    send(0); send(1);
    deliver("B", 0); deliver("B", 1);
    send(2); deliver("A", 2);
    send(3); send(4); send(5);
    deliver("B", 3); deliver("B", 5); deliver("B", 4);
    send(6); send(7);
    deliver("A", 7); deliver("A", 6);
    send(8); deliver("B", 8);
    const bool msgs = sent == int(messages.size()) && sentOk == sent;
    const QJsonArray wantOrder = v.value(QStringLiteral("deliver")).toArray();
    const bool dels = deliveredOk == delivered && compact(QJsonObject{{QStringLiteral("o"), order}}) == compact(QJsonObject{{QStringLiteral("o"), wantOrder}});

    // Tampering: a changed byte, a changed header, a new session's first message, and a jump
    // past the skip limit all fail and leave the state exactly as it was.
    bool tamper = true;
    {
        Pair p;
        const QString pb = toB64(p.b.publicDer);
        Peer a, bb;
        bool ok = false;
        auto first = a.encrypt(p.skA, pb, "first", ChatCrypto::newKeyPair);
        QByteArray bad = first.second;
        bad[0] = char(bad[0] ^ 1);
        bb.decrypt(p.skB, p.b, first.first, bad, ChatCrypto::newKeyPair, &ok);
        tamper = tamper && !ok && bb.sessions.empty() && bb.active.isEmpty();
        tamper = tamper && bb.decrypt(p.skB, p.b, first.first, first.second, ChatCrypto::newKeyPair, &ok) == "first" && ok;
        auto second = a.encrypt(p.skA, pb, "second", ChatCrypto::newKeyPair);
        const QByteArray before = compact(bb.toJson());
        bad = second.second;
        bad[bad.size() - 1] = char(bad[bad.size() - 1] ^ 0x80);
        bb.decrypt(p.skB, p.b, second.first, bad, ChatCrypto::newKeyPair, &ok);
        tamper = tamper && !ok && compact(bb.toJson()) == before;
        Header moved = second.first;
        moved.n = 7;
        bb.decrypt(p.skB, p.b, moved, second.second, ChatCrypto::newKeyPair, &ok);
        tamper = tamper && !ok && compact(bb.toJson()) == before;
        Header far = second.first;
        far.n = kMaxSkip + 5;
        bb.decrypt(p.skB, p.b, far, second.second, ChatCrypto::newKeyPair, &ok);
        tamper = tamper && !ok && compact(bb.toJson()) == before;
        tamper = tamper && bb.decrypt(p.skB, p.b, second.first, second.second, ChatCrypto::newKeyPair, &ok) == "second" && ok;
        // The same message again is refused (its key is gone).
        bb.decrypt(p.skB, p.b, second.first, second.second, ChatCrypto::newKeyPair, &ok);
        tamper = tamper && !ok;
    }

    // Both sides start at once: each sends before hearing from the other, then they take turns.
    bool both = true;
    {
        Pair p;
        const QString pa = toB64(p.a.publicDer), pb = toB64(p.b.publicDer);
        Peer a, bb;
        bool ok1 = false, ok2 = false;
        auto ma = a.encrypt(p.skA, pb, "a0", ChatCrypto::newKeyPair);
        auto mb = bb.encrypt(p.skB, pa, "b0", ChatCrypto::newKeyPair);
        both = a.decrypt(p.skA, p.a, mb.first, mb.second, ChatCrypto::newKeyPair, &ok1) == "b0"
            && bb.decrypt(p.skB, p.b, ma.first, ma.second, ChatCrypto::newKeyPair, &ok2) == "a0" && ok1 && ok2;
        for (int round = 1; round <= 3 && both; ++round) {
            const QByteArray ta = "a" + QByteArray::number(round), tb = "b" + QByteArray::number(round);
            ma = a.encrypt(p.skA, pb, ta, ChatCrypto::newKeyPair);
            both = both && bb.decrypt(p.skB, p.b, ma.first, ma.second, ChatCrypto::newKeyPair, &ok1) == ta && ok1;
            mb = bb.encrypt(p.skB, pa, tb, ChatCrypto::newKeyPair);
            both = both && a.decrypt(p.skA, p.a, mb.first, mb.second, ChatCrypto::newKeyPair, &ok2) == tb && ok2;
        }
        both = both && !a.active.isEmpty() && a.active == bb.active && ma.first.b == mb.first.b && a.sessions.size() == 2
            && bb.sessions.size() == 2;
    }

    printf("ratchet self-test: keys %s, %d messages match %s, %d deliveries open %s, save and load %s, rejects tampering %s, "
           "both start at once %s\n",
           keys ? "ok" : "WRONG", sent, msgs ? "ok" : "WRONG", delivered, dels ? "ok" : "WRONG", reload ? "ok" : "WRONG",
           tamper ? "ok" : "WRONG", both ? "ok" : "WRONG");
    return keys && msgs && dels && reload && tamper && both;
}
