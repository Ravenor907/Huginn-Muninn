#include "schnorr.h"

#include <memory>

#include <openssl/bn.h>
#include <openssl/ec.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/obj_mac.h>
#include <openssl/rand.h>

namespace
{
struct BnFree { void operator()(BIGNUM *b) const { BN_clear_free(b); } };
struct PointFree { void operator()(EC_POINT *p) const { EC_POINT_clear_free(p); } };
using Bn = std::unique_ptr<BIGNUM, BnFree>;
using Point = std::unique_ptr<EC_POINT, PointFree>;

// The curve, its order n and field prime p, and a scratch context, for one call.
struct Curve {
    EC_GROUP *group = EC_GROUP_new_by_curve_name(NID_secp256k1);
    BN_CTX *ctx = BN_CTX_new();
    Bn n{BN_new()};
    Bn p{BN_new()};
    Curve()
    {
        if (group) {
            BN_copy(n.get(), EC_GROUP_get0_order(group));
            EC_GROUP_get_curve(group, p.get(), nullptr, nullptr, ctx);
        }
    }
    ~Curve()
    {
        BN_CTX_free(ctx);
        EC_GROUP_free(group);
        // Failed checks on bad input leave errors behind; don't let them pile up.
        ERR_clear_error();
    }
    bool ok() const { return group && ctx && n && p; }
    Point point() const { return Point(EC_POINT_new(group)); }
};

// One curve and scratch context per thread, made once (making the curve costs more than a signature check).
// Failed checks on bad input leave errors behind: each call clears them as it ends.
struct Use {
    Curve &c;
    Use() : c(curve()) {}
    ~Use() { ERR_clear_error(); }
    static Curve &curve()
    {
        thread_local Curve made;
        return made;
    }
};

const unsigned char *u(const QByteArray &b) { return reinterpret_cast<const unsigned char *>(b.constData()); }

Bn number(const QByteArray &b)
{
    return Bn(BN_bin2bn(u(b), int(b.size()), nullptr));
}

QByteArray bytes32(const BIGNUM *x)
{
    QByteArray out(32, 0);
    BN_bn2binpad(x, reinterpret_cast<unsigned char *>(out.data()), 32);
    return out;
}

QByteArray sha256(const QByteArray &data)
{
    QByteArray out(32, 0);
    unsigned int len = 32;
    EVP_Digest(data.constData(), size_t(data.size()), reinterpret_cast<unsigned char *>(out.data()), &len, EVP_sha256(), nullptr);
    return out;
}

QByteArray taggedHash(const char *tag, const QByteArray &msg)
{
    const QByteArray t = sha256(QByteArray(tag));
    QByteArray all = t;
    all.append(t);
    all.append(msg);
    return sha256(all);
}

// A hash taken as a number mod n.
Bn hashMod(const Curve &c, const char *tag, const QByteArray &msg)
{
    Bn h = number(taggedHash(tag, msg));
    BN_nnmod(h.get(), h.get(), c.n.get(), c.ctx);
    return h;
}

// k*G, and its affine x and y.
bool mulG(const Curve &c, const BIGNUM *k, BIGNUM *x, BIGNUM *y)
{
    Point pt = c.point();
    return pt && EC_POINT_mul(c.group, pt.get(), k, nullptr, nullptr, c.ctx) == 1 && !EC_POINT_is_at_infinity(c.group, pt.get())
        && EC_POINT_get_affine_coordinates(c.group, pt.get(), x, y, c.ctx) == 1;
}

// A secret is a number from 1 to n-1.
Bn secretNumber(const Curve &c, const QByteArray &secret)
{
    if (secret.size() != 32)
        return Bn();
    Bn d = number(secret);
    if (!d || BN_is_zero(d.get()) || BN_cmp(d.get(), c.n.get()) >= 0)
        return Bn();
    BN_set_flags(d.get(), BN_FLG_CONSTTIME);
    return d;
}
}

QByteArray Schnorr::newSecret()
{
    const Use use;
    Curve &c = use.c;
    QByteArray b(32, 0);
    while (c.ok()) {
        if (RAND_bytes(reinterpret_cast<unsigned char *>(b.data()), 32) == 1 && secretNumber(c, b))
            return b;
    }
    return QByteArray();
}

QByteArray Schnorr::publicKey(const QByteArray &secret)
{
    const Use use;
    Curve &c = use.c;
    if (!c.ok())
        return QByteArray();
    Bn d = secretNumber(c, secret);
    Bn x(BN_new()), y(BN_new());
    if (!d || !mulG(c, d.get(), x.get(), y.get()))
        return QByteArray();
    return bytes32(x.get());
}

QByteArray Schnorr::sign(const QByteArray &msg, const QByteArray &secret, const QByteArray &aux)
{
    const Use use;
    Curve &c = use.c;
    if (!c.ok())
        return QByteArray();
    QByteArray rand = aux;
    if (rand.isEmpty()) {
        rand = QByteArray(32, 0);
        if (RAND_bytes(reinterpret_cast<unsigned char *>(rand.data()), 32) != 1)
            return QByteArray();
    }
    Bn d0 = secretNumber(c, secret);
    Bn px(BN_new()), py(BN_new());
    if (!d0 || !mulG(c, d0.get(), px.get(), py.get()))
        return QByteArray();
    // Only keys whose point has an even y exist in BIP-340, so flip the secret when y is odd.
    Bn d(BN_new());
    if (BN_is_odd(py.get()))
        BN_sub(d.get(), c.n.get(), d0.get());
    else
        BN_copy(d.get(), d0.get());
    BN_set_flags(d.get(), BN_FLG_CONSTTIME);
    const QByteArray pub = bytes32(px.get());

    QByteArray t = bytes32(d.get());
    const QByteArray mask = taggedHash("BIP0340/aux", rand);
    for (int i = 0; i < 32; ++i)
        t[i] = char(t[i] ^ mask[i]);
    Bn k0 = hashMod(c, "BIP0340/nonce", t + pub + msg);
    Bn rx(BN_new()), ry(BN_new());
    if (!k0 || BN_is_zero(k0.get()) || !mulG(c, k0.get(), rx.get(), ry.get()))
        return QByteArray();
    // Same rule for the nonce point R.
    Bn k(BN_new());
    if (BN_is_odd(ry.get()))
        BN_sub(k.get(), c.n.get(), k0.get());
    else
        BN_copy(k.get(), k0.get());
    const QByteArray r = bytes32(rx.get());
    Bn e = hashMod(c, "BIP0340/challenge", r + pub + msg);

    // s = (k + e*d) mod n
    Bn s(BN_new());
    if (!e || BN_mod_mul(s.get(), e.get(), d.get(), c.n.get(), c.ctx) != 1 || BN_mod_add(s.get(), s.get(), k.get(), c.n.get(), c.ctx) != 1)
        return QByteArray();
    return r + bytes32(s.get());
}

bool Schnorr::verify(const QByteArray &msg, const QByteArray &pubkey, const QByteArray &sig)
{
    if (sig.size() != 64 || pubkey.size() != 32)
        return false;
    const Use use;
    Curve &c = use.c;
    if (!c.ok())
        return false;
    // lift_x: the point with this x and an even y; fails when x isn't on the curve.
    Bn x = number(pubkey);
    Point pt = c.point();
    if (!x || !pt || BN_cmp(x.get(), c.p.get()) >= 0 || EC_POINT_set_compressed_coordinates(c.group, pt.get(), x.get(), 0, c.ctx) != 1)
        return false;
    Bn r = number(sig.left(32));
    Bn s = number(sig.mid(32));
    if (!r || !s || BN_cmp(r.get(), c.p.get()) >= 0 || BN_cmp(s.get(), c.n.get()) >= 0)
        return false;
    Bn e = hashMod(c, "BIP0340/challenge", sig.left(32) + pubkey + msg);
    // R = s*G - e*P, written as s*G + (n - e)*P.
    Bn minusE(BN_new());
    Point rr = c.point();
    Bn rx(BN_new()), ry(BN_new());
    if (!e || BN_mod_sub(minusE.get(), c.n.get(), e.get(), c.n.get(), c.ctx) != 1 || !rr
        || EC_POINT_mul(c.group, rr.get(), s.get(), pt.get(), minusE.get(), c.ctx) != 1 || EC_POINT_is_at_infinity(c.group, rr.get())
        || EC_POINT_get_affine_coordinates(c.group, rr.get(), rx.get(), ry.get(), c.ctx) != 1)
        return false;
    return !BN_is_odd(ry.get()) && BN_cmp(rx.get(), r.get()) == 0;
}

QString Schnorr::hex(const QByteArray &bytes)
{
    return QString::fromLatin1(bytes.toHex());
}

QByteArray Schnorr::unhex(const QString &text)
{
    return QByteArray::fromHex(text.toLatin1());
}
