#include "chatcrypto.h"

#include <QCryptographicHash>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRandomGenerator>
#include <cstdio>

#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/x509.h>

namespace
{
const QByteArray kInfo = "huginn-muninn chat v1";
const QByteArray kAad = "huginn-muninn";

EVP_PKEY *readPublic(const QByteArray &der)
{
    const unsigned char *p = reinterpret_cast<const unsigned char *>(der.constData());
    return d2i_PUBKEY(nullptr, &p, der.size());
}

EVP_PKEY *readPrivate(const QByteArray &der)
{
    const unsigned char *p = reinterpret_cast<const unsigned char *>(der.constData());
    return d2i_AutoPrivateKey(nullptr, &p, der.size());
}

QByteArray hkdf(const QByteArray &ikm, const QByteArray &salt, const QByteArray &info, int length)
{
    QByteArray out(length, 0);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, nullptr);
    size_t len = size_t(length);
    const bool ok = ctx && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha256()) > 0
        && EVP_PKEY_CTX_set1_hkdf_salt(ctx, reinterpret_cast<const unsigned char *>(salt.constData()), salt.size()) > 0
        && EVP_PKEY_CTX_set1_hkdf_key(ctx, reinterpret_cast<const unsigned char *>(ikm.constData()), ikm.size()) > 0
        && EVP_PKEY_CTX_add1_hkdf_info(ctx, reinterpret_cast<const unsigned char *>(info.constData()), info.size()) > 0
        && EVP_PKEY_derive(ctx, reinterpret_cast<unsigned char *>(out.data()), &len) > 0;
    EVP_PKEY_CTX_free(ctx);
    return ok ? out : QByteArray();
}
}

ChatCrypto::KeyPair ChatCrypto::newKeyPair()
{
    KeyPair kp;
    EVP_PKEY *key = EVP_PKEY_Q_keygen(nullptr, nullptr, "EC", "P-256");
    if (!key)
        return kp;
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
    EVP_PKEY_free(key);
    return kp;
}

QByteArray ChatCrypto::publicFromPrivate(const QByteArray &privateDer)
{
    if (privateDer.isEmpty())
        return {};
    EVP_PKEY *key = readPrivate(privateDer);
    if (!key)
        return {};
    QByteArray out;
    unsigned char *buf = nullptr;
    const int n = i2d_PUBKEY(key, &buf);
    if (n > 0)
        out = QByteArray(reinterpret_cast<char *>(buf), n);
    OPENSSL_free(buf);
    EVP_PKEY_free(key);
    return out;
}

bool ChatCrypto::samePublic(const QByteArray &a, const QByteArray &b)
{
    if (a.isEmpty() || b.isEmpty())
        return false;
    if (a == b)
        return true;
    EVP_PKEY *x = readPublic(a);
    EVP_PKEY *y = readPublic(b);
    const bool same = x && y && EVP_PKEY_eq(x, y) == 1;
    EVP_PKEY_free(x);
    EVP_PKEY_free(y);
    return same;
}

QByteArray ChatCrypto::sharedKey(const QByteArray &myPrivateDer, const QByteArray &myPublicDer, const QByteArray &theirPublicDer)
{
    EVP_PKEY *mine = readPrivate(myPrivateDer);
    EVP_PKEY *theirs = readPublic(theirPublicDer);
    QByteArray secret;
    if (mine && theirs) {
        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(mine, nullptr);
        size_t len = 0;
        if (ctx && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_derive_set_peer(ctx, theirs) > 0 && EVP_PKEY_derive(ctx, nullptr, &len) > 0) {
            secret.resize(int(len));
            if (EVP_PKEY_derive(ctx, reinterpret_cast<unsigned char *>(secret.data()), &len) <= 0)
                secret.clear();
        }
        EVP_PKEY_CTX_free(ctx);
    }
    EVP_PKEY_free(mine);
    EVP_PKEY_free(theirs);
    if (secret.isEmpty())
        return {};
    // Order the two public keys (unsigned bytes) so both sides feed HKDF the same salt.
    const bool mineFirst = std::lexicographical_compare(
        reinterpret_cast<const unsigned char *>(myPublicDer.constBegin()), reinterpret_cast<const unsigned char *>(myPublicDer.constEnd()),
        reinterpret_cast<const unsigned char *>(theirPublicDer.constBegin()), reinterpret_cast<const unsigned char *>(theirPublicDer.constEnd()));
    const QByteArray salt = QCryptographicHash::hash(mineFirst ? myPublicDer + theirPublicDer : theirPublicDer + myPublicDer, QCryptographicHash::Sha256);
    return hkdf(secret, salt, kInfo, 32);
}

QString ChatCrypto::pairingCode(const QByteArray &key)
{
    const QByteArray d = QCryptographicHash::hash(QByteArray("pairing code") + key, QCryptographicHash::Sha256);
    const quint32 n = (quint32(quint8(d[0])) << 24) | (quint32(quint8(d[1])) << 16) | (quint32(quint8(d[2])) << 8) | quint32(quint8(d[3]));
    return QStringLiteral("%1").arg(n % 1000000, 6, 10, QLatin1Char('0'));
}

void ChatCrypto::seal(const QByteArray &key, const QByteArray &plain, QByteArray *nonce, QByteArray *data, const QByteArray &aadIn)
{
    const QByteArray &ad = aadIn.isEmpty() ? kAad : aadIn;
    if (nonce->size() != 12) {
        nonce->resize(12);
        for (int i = 0; i < 12; ++i)
            (*nonce)[i] = char(QRandomGenerator::system()->bounded(256));
    }
    data->clear();
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    const bool ok = c && EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) > 0
        && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) > 0
        && EVP_EncryptInit_ex(c, nullptr, nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(nonce->constData())) > 0
        && EVP_EncryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(ad.constData()), int(ad.size())) > 0
        && EVP_EncryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(plain.constData()), plain.size()) > 0
        && (total = len, EVP_EncryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len) > 0)
        && (total += len, EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total) > 0);
    EVP_CIPHER_CTX_free(c);
    if (ok)
        *data = out.left(total + 16);
}

QByteArray ChatCrypto::open(const QByteArray &key, const QByteArray &nonce, const QByteArray &data, bool *ok, const QByteArray &aadIn)
{
    const QByteArray &ad = aadIn.isEmpty() ? kAad : aadIn;
    *ok = false;
    if (key.size() != 32 || nonce.size() != 12 || data.size() < 16)
        return {};
    const QByteArray body = data.left(data.size() - 16);
    QByteArray tag = data.right(16);
    QByteArray out(body.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    const bool good = c && EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) > 0
        && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) > 0
        && EVP_DecryptInit_ex(c, nullptr, nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(nonce.constData())) > 0
        && EVP_DecryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(ad.constData()), int(ad.size())) > 0
        && EVP_DecryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(body.constData()), body.size()) > 0
        && (total = len, EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, tag.data()) > 0)
        && EVP_DecryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    if (!good)
        return {};
    *ok = true;
    return out.left(total + len);
}

bool ChatCrypto::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        fprintf(stderr, "chat self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    auto b = [&](const char *k) { return QByteArray::fromBase64(v.value(QLatin1String(k)).toString().toLatin1()); };
    const QByteArray fromA = sharedKey(b("privA"), b("pubA"), b("pubB"));
    const QByteArray fromB = sharedKey(b("privB"), b("pubB"), b("pubA"));
    bool ok = false;
    const QByteArray text = open(fromB, b("nonce"), b("data"), &ok);
    QByteArray n, d;
    seal(fromA, "round trip", &n, &d);
    bool ok2 = false;
    const QByteArray back = open(fromB, n, d, &ok2);
    const bool keys = fromA.toHex() == v.value(QStringLiteral("key")).toString().toLatin1() && fromB == fromA;
    const bool code = pairingCode(fromA) == v.value(QStringLiteral("code")).toString();
    const bool msg = ok && QString::fromUtf8(text) == v.value(QStringLiteral("text")).toString();
    const bool trip = ok2 && back == "round trip";
    QByteArray bad = d;
    bad[0] = char(bad[0] ^ 1);
    bool ok3 = true;
    open(fromB, n, bad, &ok3);
    printf("chat self-test: key %s, code %s, opens Muninn's message %s, round trip %s, rejects tampering %s\n", keys ? "ok" : "WRONG",
           code ? "ok" : "WRONG", msg ? "ok" : "WRONG", trip ? "ok" : "WRONG", !ok3 ? "ok" : "WRONG");
    return keys && code && msg && trip && !ok3;
}
