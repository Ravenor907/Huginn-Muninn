#include "callcrypto.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <cstdio>
#include <cstring>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/kdf.h>
#include <openssl/rand.h>
#include <openssl/x509.h>

const char CallCrypto::kDomain[] = "huginn-muninn call v1";

namespace
{
const unsigned char *u(const QByteArray &b) { return reinterpret_cast<const unsigned char *>(b.constData()); }
unsigned char *u(QByteArray &b) { return reinterpret_cast<unsigned char *>(b.data()); }

EVP_PKEY *readPublic(const QByteArray &der)
{
    const unsigned char *p = u(der);
    return der.isEmpty() ? nullptr : d2i_PUBKEY(nullptr, &p, long(der.size()));
}

EVP_PKEY *readPrivate(const QByteArray &der)
{
    const unsigned char *p = u(der);
    return der.isEmpty() ? nullptr : d2i_AutoPrivateKey(nullptr, &p, long(der.size()));
}

CallCrypto::Ephemeral fromKey(EVP_PKEY *key)
{
    CallCrypto::Ephemeral e;
    if (!key)
        return e;
    unsigned char *buf = nullptr;
    int n = i2d_PUBKEY(key, &buf);
    if (n > 0)
        e.publicDer = QByteArray(reinterpret_cast<char *>(buf), n);
    OPENSSL_free(buf);
    buf = nullptr;
    PKCS8_PRIV_KEY_INFO *p8 = EVP_PKEY2PKCS8(key);
    n = p8 ? i2d_PKCS8_PRIV_KEY_INFO(p8, &buf) : 0;
    if (n > 0)
        e.privateDer = QByteArray(reinterpret_cast<char *>(buf), n);
    if (buf)
        OPENSSL_clear_free(buf, size_t(n > 0 ? n : 0));
    PKCS8_PRIV_KEY_INFO_free(p8);
    return e;
}

void put32(QByteArray &b, int at, quint32 v)
{
    b[at] = char(v >> 24);
    b[at + 1] = char(v >> 16);
    b[at + 2] = char(v >> 8);
    b[at + 3] = char(v);
}

quint32 get32(const QByteArray &b, int at)
{
    return (quint32(quint8(b[at])) << 24) | (quint32(quint8(b[at + 1])) << 16) | (quint32(quint8(b[at + 2])) << 8) | quint32(quint8(b[at + 3]));
}

// Canonical JSON string (2.1): raw UTF-8, only \ and " escaped.
QByteArray canonString(const QString &s)
{
    QByteArray out("\"");
    const QByteArray b = s.toUtf8();
    for (int i = 0; i < b.size(); ++i) {
        const char c = b[i];
        if (c == '"' || c == '\\')
            out += '\\';
        out += c;
    }
    out += '"';
    return out;
}

bool gcm(bool seal, const QByteArray &key, const QByteArray &iv, const QByteArray &in, const QByteArray &aad, QByteArray *out)
{
    if (key.size() != 32 || iv.size() != 12)
        return false;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx)
        return false;
    int len = 0;
    bool ok = false;
    if (seal) {
        QByteArray o(in.size() + CallCrypto::kGcmTag, 0);
        ok = EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, u(key), u(iv)) > 0
            && EVP_EncryptUpdate(ctx, nullptr, &len, u(aad), int(aad.size())) > 0
            && EVP_EncryptUpdate(ctx, u(o), &len, u(in), int(in.size())) > 0
            && EVP_EncryptFinal_ex(ctx, u(o) + len, &len) > 0
            && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, CallCrypto::kGcmTag, u(o) + in.size()) > 0;
        if (ok)
            *out = o;
    } else if (in.size() >= CallCrypto::kGcmTag) {
        const int n = int(in.size()) - CallCrypto::kGcmTag;
        QByteArray o(n, 0);
        QByteArray t = in.mid(n);
        int fin = 0;
        ok = EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, u(key), u(iv)) > 0
            && EVP_DecryptUpdate(ctx, nullptr, &len, u(aad), int(aad.size())) > 0
            && EVP_DecryptUpdate(ctx, u(o), &len, u(in), n) > 0
            && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, CallCrypto::kGcmTag, u(t)) > 0
            && EVP_DecryptFinal_ex(ctx, u(o) + len, &fin) > 0;
        if (ok)
            *out = o;
    }
    EVP_CIPHER_CTX_free(ctx);
    return ok;
}
}

// ------------------------------------------------------------------ keys and ids

CallCrypto::Ephemeral CallCrypto::newEphemeral()
{
    EVP_PKEY *key = EVP_PKEY_Q_keygen(nullptr, nullptr, "EC", "P-256");
    Ephemeral e = fromKey(key);
    EVP_PKEY_free(key);
    return e;
}

CallCrypto::Ephemeral CallCrypto::ephemeralFromScalar(const QByteArray &scalar32)
{
    if (scalar32.size() != 32)
        return {};
    // SEC1 ECPrivateKey with only the private value and the curve; OpenSSL works out the public half.
    static const unsigned char head[] = {0x30, 0x31, 0x02, 0x01, 0x01, 0x04, 0x20};
    static const unsigned char curve[] = {0xa0, 0x0a, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07};
    QByteArray der(reinterpret_cast<const char *>(head), sizeof head);
    der += scalar32;
    der += QByteArray(reinterpret_cast<const char *>(curve), sizeof curve);
    EVP_PKEY *key = readPrivate(der);
    Ephemeral e = fromKey(key);
    EVP_PKEY_free(key);
    return e;
}

QString CallCrypto::base64Url(const QByteArray &raw)
{
    QByteArray b = raw.toBase64();
    QByteArray out;
    for (int i = 0; i < b.size(); ++i) {
        const char c = b[i];
        if (c == '=')
            break;
        out += c == '+' ? '-' : c == '/' ? '_' : c;
    }
    return QString::fromLatin1(out);
}

QByteArray CallCrypto::fromBase64Url(const QString &text)
{
    const QByteArray b = text.toLatin1();
    QByteArray std;
    for (int i = 0; i < b.size(); ++i) {
        const char c = b[i];
        const bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
        if (!ok)
            return QByteArray();
        std += c == '-' ? '+' : c == '_' ? '/' : c;
    }
    while (std.size() % 4)
        std += '=';
    return QByteArray::fromBase64(std);
}

QString CallCrypto::newCallId()
{
    QByteArray raw(16, 0);
    RAND_bytes(u(raw), 16);
    return base64Url(raw);
}

QByteArray CallCrypto::callIdBytes(const QString &callId)
{
    const QByteArray raw = fromBase64Url(callId);
    return raw.size() == 16 && base64Url(raw) == callId ? raw : QByteArray();
}

// ------------------------------------------------------------------ signatures

QByteArray CallCrypto::signedBytes(const QString &t, const QString &callId, const QString &ephB64)
{
    // Members in byte order: call, eph, t.
    QByteArray out(kDomain);
    out += '\n';
    out += "{\"call\":" + canonString(callId) + ",\"eph\":" + canonString(ephB64) + ",\"t\":" + canonString(t) + "}";
    return out;
}

QByteArray CallCrypto::signDer(const QByteArray &privateDer, const QByteArray &bytes)
{
    EVP_PKEY *key = readPrivate(privateDer);
    QByteArray sig;
    EVP_MD_CTX *ctx = key ? EVP_MD_CTX_new() : nullptr;
    size_t len = 0;
    if (ctx && EVP_DigestSignInit(ctx, nullptr, EVP_sha256(), nullptr, key) > 0
        && EVP_DigestSign(ctx, nullptr, &len, u(bytes), size_t(bytes.size())) > 0) {
        sig.resize(qsizetype(len));
        if (EVP_DigestSign(ctx, u(sig), &len, u(bytes), size_t(bytes.size())) > 0)
            sig.resize(qsizetype(len));
        else
            sig.clear();
    }
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return sig;
}

bool CallCrypto::verifyDer(const QByteArray &publicDer, const QByteArray &bytes, const QByteArray &sigDer)
{
    EVP_PKEY *key = readPublic(publicDer);
    EVP_MD_CTX *ctx = key ? EVP_MD_CTX_new() : nullptr;
    const bool ok = ctx && !sigDer.isEmpty() && EVP_DigestVerifyInit(ctx, nullptr, EVP_sha256(), nullptr, key) > 0
        && EVP_DigestVerify(ctx, u(sigDer), size_t(sigDer.size()), u(bytes), size_t(bytes.size())) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok;
}

// ------------------------------------------------------------------ key schedule

QByteArray CallCrypto::sha256(const QByteArray &data)
{
    QByteArray out(32, 0);
    unsigned int len = 0;
    EVP_Digest(data.constData(), size_t(data.size()), u(out), &len, EVP_sha256(), nullptr);
    return out;
}

QByteArray CallCrypto::hmacSha256(const QByteArray &key, const QByteArray &data)
{
    QByteArray out(32, 0);
    unsigned int len = 32;
    if (!HMAC(EVP_sha256(), key.constData(), int(key.size()), u(data), size_t(data.size()), u(out), &len))
        return QByteArray();
    return out;
}

QByteArray CallCrypto::hkdf(const QByteArray &salt, const QByteArray &ikm, const QByteArray &info, int length)
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

void CallCrypto::wipe(QByteArray &b)
{
    if (!b.isEmpty())
        OPENSSL_cleanse(b.data(), size_t(b.size()));
    b.clear();
}

QByteArray CallCrypto::ecdh(const QByteArray &myPrivateDer, const QByteArray &theirPublicDer)
{
    EVP_PKEY *mine = readPrivate(myPrivateDer);
    EVP_PKEY *theirs = readPublic(theirPublicDer);
    QByteArray secret;
    EVP_PKEY_CTX *ctx = mine && theirs ? EVP_PKEY_CTX_new(mine, nullptr) : nullptr;
    size_t len = 0;
    if (ctx && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_derive_set_peer(ctx, theirs) > 0 && EVP_PKEY_derive(ctx, nullptr, &len) > 0) {
        secret.resize(qsizetype(len));
        if (EVP_PKEY_derive(ctx, u(secret), &len) <= 0 || len != 32)
            secret.clear();
    }
    EVP_PKEY_CTX_free(ctx);
    EVP_PKEY_free(mine);
    EVP_PKEY_free(theirs);
    return secret;
}

QByteArray CallCrypto::root(const QByteArray &ikm, const QByteArray &callId, const QByteArray &offerEphDer, const QByteArray &answerEphDer)
{
    if (ikm.size() != 32 || callId.size() != 16)
        return QByteArray();
    const QByteArray salt = sha256(callId + offerEphDer + answerEphDer);
    return hkdf(salt, ikm, QByteArray(kDomain), 32);
}

QByteArray CallCrypto::tag(const QByteArray &callId)
{
    return sha256(QByteArray("huginn-muninn call tag") + callId).left(2);
}

QByteArray CallCrypto::nextChain(const QByteArray &chain)
{
    return hmacSha256(chain, QByteArray(1, char(0x02)));
}

CallCrypto::EpochKeys CallCrypto::epochKeys(const QByteArray &chain)
{
    QByteArray km = hkdf(QByteArray(32, 0), chain, QByteArray("huginn-muninn call keys"), 72);
    EpochKeys k;
    if (km.size() != 72)
        return k;
    k.key[O2A] = km.mid(0, 32);
    k.salt[O2A] = km.mid(32, 4);
    k.key[A2O] = km.mid(36, 32);
    k.salt[A2O] = km.mid(68, 4);
    wipe(km);
    return k;
}

QByteArray CallCrypto::header(int type, const QByteArray &tag, quint32 seq, quint32 ts)
{
    QByteArray h(kHeader, 0);
    h[0] = char(kVersion);
    h[1] = char(type);
    h[2] = tag.size() == 2 ? tag[0] : char(0);
    h[3] = tag.size() == 2 ? tag[1] : char(0);
    put32(h, 4, seq);
    put32(h, 8, ts);
    return h;
}

QByteArray CallCrypto::nonce(const QByteArray &salt, quint32 seq)
{
    QByteArray n = salt + QByteArray(4, 0) + QByteArray(4, 0);
    put32(n, 8, seq);
    return n;
}

// ------------------------------------------------------------------ keyring

CallCrypto::Keyring::Keyring(const QByteArray &root) : m_chain(root) {}

CallCrypto::Keyring::~Keyring()
{
    forgetBefore(0xFFFFFFFFu);
    wipe(m_chain);
}

const CallCrypto::EpochKeys *CallCrypto::Keyring::get(quint32 epoch)
{
    if (m_chain.isEmpty() || epoch < m_base)
        return nullptr;
    auto it = m_keys.find(epoch);
    if (it != m_keys.end())
        return &it->second;
    QByteArray c = m_chain;
    for (quint32 e = m_base; e < epoch; ++e) {
        const QByteArray n = nextChain(c);
        wipe(c);
        c = n;
    }
    EpochKeys k = epochKeys(c);
    wipe(c);
    if (k.key[0].isEmpty())
        return nullptr;
    return &(m_keys[epoch] = k);
}

void CallCrypto::Keyring::forgetBefore(quint32 epoch)
{
    for (auto it = m_keys.begin(); it != m_keys.end();) {
        if (it->first >= epoch) {
            ++it;
            continue;
        }
        for (int d = 0; d < 2; ++d) {
            wipe(it->second.key[d]);
            wipe(it->second.salt[d]);
        }
        it = m_keys.erase(it);
    }
    if (epoch == 0xFFFFFFFFu || m_chain.isEmpty())
        return;
    while (m_base < epoch) {
        const QByteArray n = nextChain(m_chain);
        wipe(m_chain);
        m_chain = n;
        ++m_base;
    }
}

// ------------------------------------------------------------------ packets

QByteArray CallCrypto::Sender::sealAt(int type, quint32 seq, quint32 ts, const QByteArray &payload)
{
    const EpochKeys *k = m_keys->get(seq >> kEpochBits);
    if (!k)
        return QByteArray();
    const QByteArray h = header(type, m_tag, seq, ts);
    QByteArray body;
    if (!gcm(true, k->key[m_dir], nonce(k->salt[m_dir], seq), payload, h, &body))
        return QByteArray();
    const QByteArray pkt = h + body;
    return pkt.size() > kMaxPacket ? QByteArray() : pkt;
}

bool CallCrypto::Receiver::seen(quint32 seq) const
{
    const quint32 i = seq % kWindow;
    return (m_bits[i / 64] >> (i % 64)) & 1u;
}

void CallCrypto::Receiver::mark(quint32 seq)
{
    if (qint64(seq) > m_high) {
        // Clear the slots the window moves over: they now stand for newer seqs.
        const qint64 from = m_high + 1;
        if (qint64(seq) - from >= kWindow) {
            std::memset(m_bits, 0, sizeof m_bits);
        } else {
            for (qint64 s = from; s < qint64(seq); ++s) {
                const quint32 i = quint32(s) % kWindow;
                m_bits[i / 64] &= ~(quint64(1) << (i % 64));
            }
        }
        m_high = seq;
    }
    const quint32 i = seq % kWindow;
    m_bits[i / 64] |= quint64(1) << (i % 64);
}

QString CallCrypto::Receiver::open(const QByteArray &pkt, Packet *out)
{
    if (pkt.size() < kMinPacket || pkt.size() > kMaxPacket)
        return QStringLiteral("size");
    if (quint8(pkt[0]) != kVersion)
        return QStringLiteral("version");
    const int type = quint8(pkt[1]);
    if (type < Audio || type > Nack)
        return QStringLiteral("type");
    if (pkt.mid(2, 2) != m_tag)
        return QStringLiteral("other call");
    const quint32 seq = get32(pkt, 4), ts = get32(pkt, 8);
    if (m_high >= 0 && qint64(seq) + kWindow <= m_high)
        return QStringLiteral("too old");
    if (qint64(seq) <= m_high && seen(seq))
        return QStringLiteral("duplicate");
    const quint32 cur = quint32(std::max<qint64>(m_high, 0) >> kEpochBits);
    if ((seq >> kEpochBits) > cur + kMaxAhead)
        return QStringLiteral("too far ahead");
    const EpochKeys *k = m_keys->get(seq >> kEpochBits);
    if (!k)
        return QStringLiteral("too old");
    QByteArray plain;
    if (!gcm(false, k->key[m_dir], nonce(k->salt[m_dir], seq), pkt.mid(kHeader), pkt.left(kHeader), &plain))
        return QStringLiteral("not authentic");
    // Only an authentic packet moves the window.
    mark(seq);
    if (out) {
        out->type = type;
        out->seq = seq;
        out->ts = ts;
        out->payload = plain;
    }
    return QString();
}

// ------------------------------------------------------------------ self-test

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}
QByteArray hex(const QJsonValue &v) { return QByteArray::fromHex(v.toString().toLatin1()); }
QByteArray scalar(const QString &s)
{
    // "0x3001": a big-endian number, left-padded to 32 bytes.
    QByteArray h = s.toLatin1().mid(2);
    while (h.size() < 64)
        h = "0" + h;
    return QByteArray::fromHex(h);
}
}

bool CallCrypto::selfTest(const QString &vectorsPath)
{
    g_fail = 0;
    std::printf("call: %s\n", qPrintable(vectorsPath));
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::printf("  FAIL can't read the vectors\n");
        return false;
    }
    const QJsonObject all = QJsonDocument::fromJson(f.readAll()).object();
    const QJsonObject c = all.value(QStringLiteral("call")).toObject();
    const QJsonObject keys = all.value(QStringLiteral("keys")).toObject();
    const QString callId = c.value(QStringLiteral("callId")).toString();
    const QByteArray id = callIdBytes(callId);
    check(id.size() == 16, "call id is 16 bytes of base64url");
    check(tag(id).toHex() == c.value(QStringLiteral("tag")).toString().toLatin1(), "tag");

    const QJsonObject offer = c.value(QStringLiteral("offer")).toObject();
    const QJsonObject answer = c.value(QStringLiteral("answer")).toObject();
    const QString eA = offer.value(QStringLiteral("eph")).toString(), eB = answer.value(QStringLiteral("eph")).toString();
    const QByteArray sOffer = signedBytes(QStringLiteral("offer"), callId, eA), sAnswer = signedBytes(QStringLiteral("answer"), callId, eB);
    check(sOffer == c.value(QStringLiteral("offerSigned")).toString().toUtf8(), "offer signed bytes");
    check(sAnswer == c.value(QStringLiteral("answerSigned")).toString().toUtf8(), "answer signed bytes");
    const QByteArray pubA = QByteArray::fromBase64(keys.value(QStringLiteral("A")).toString().toLatin1());
    const QByteArray pubB = QByteArray::fromBase64(keys.value(QStringLiteral("B")).toString().toLatin1());
    const QByteArray sigO = QByteArray::fromBase64(offer.value(QStringLiteral("sig")).toString().toLatin1());
    const QByteArray sigA = QByteArray::fromBase64(answer.value(QStringLiteral("sig")).toString().toLatin1());
    check(verifyDer(pubA, sOffer, sigO), "offer signature verifies with A");
    check(verifyDer(pubB, sAnswer, sigA), "answer signature verifies with B");
    check(!verifyDer(pubB, sOffer, sigO), "offer signature refused with B");
    check(!verifyDer(pubA, signedBytes(QStringLiteral("offer"), callId, eB), sigO), "offer signature refused over another key");

    const Ephemeral ephA = ephemeralFromScalar(scalar(c.value(QStringLiteral("offerEphPriv")).toString()));
    const Ephemeral ephB = ephemeralFromScalar(scalar(c.value(QStringLiteral("answerEphPriv")).toString()));
    const QByteArray derA = QByteArray::fromBase64(eA.toLatin1()), derB = QByteArray::fromBase64(eB.toLatin1());
    check(ephA.publicDer == derA, "offer ephemeral key from 0x3001");
    check(ephB.publicDer == derB, "answer ephemeral key from 0x4001");
    const QByteArray ikmA = ecdh(ephA.privateDer, derB), ikmB = ecdh(ephB.privateDer, derA);
    check(ikmA == ikmB && ikmA.toHex() == c.value(QStringLiteral("ecdh")).toString().toLatin1(), "ECDH, both ends");
    const QByteArray rootA = root(ikmA, id, derA, derB), rootB = root(ikmB, id, derA, derB);
    check(rootA == rootB && rootA.toHex() == c.value(QStringLiteral("root")).toString().toLatin1(), "call root, both ends");

    const QJsonArray epochs = c.value(QStringLiteral("epochs")).toArray();
    QByteArray ch = rootA;
    bool epochsOk = epochs.size() > 0;
    for (int i = 0; i < epochs.size(); ++i) {
        const QJsonObject e = epochs.at(i).toObject();
        const EpochKeys k = epochKeys(ch);
        epochsOk = epochsOk && ch == hex(e.value(QStringLiteral("chain"))) && k.key[O2A] == hex(e.value(QStringLiteral("o2aKey")))
            && k.salt[O2A] == hex(e.value(QStringLiteral("o2aSalt"))) && k.key[A2O] == hex(e.value(QStringLiteral("a2oKey")))
            && k.salt[A2O] == hex(e.value(QStringLiteral("a2oSalt")));
        ch = nextChain(ch);
    }
    check(epochsOk, "every epoch's chain, keys and salts");

    Keyring ringA(rootA), ringB(rootB);
    const QByteArray t = tag(id);
    Sender o2a(&ringA, O2A, t), a2o(&ringB, A2O, t);
    const QJsonArray frames = c.value(QStringLiteral("frames")).toArray();
    bool framesOk = frames.size() > 0;
    for (int i = 0; i < frames.size(); ++i) {
        const QJsonObject fr = frames.at(i).toObject();
        Sender &s = fr.value(QStringLiteral("dir")).toString() == QLatin1String("o2a") ? o2a : a2o;
        const QByteArray pkt = s.sealAt(fr.value(QStringLiteral("type")).toInt(), quint32(fr.value(QStringLiteral("seq")).toDouble()),
                                        quint32(fr.value(QStringLiteral("ts")).toDouble()), hex(fr.value(QStringLiteral("payload"))));
        if (pkt != hex(fr.value(QStringLiteral("packet")))) {
            std::printf("       frame %d differs\n", i);
            framesOk = false;
        }
    }
    check(framesOk, "sealing every frame gives its packet");

    // Each side's receiver sees the other direction; one receiver per side for the whole list.
    Keyring rxRingA(rootA), rxRingB(rootB);
    Receiver rxB(&rxRingB, O2A, t), rxA(&rxRingA, A2O, t);
    const QJsonArray steps = c.value(QStringLiteral("receive")).toArray();
    bool rxOk = steps.size() > 0;
    for (int i = 0; i < steps.size(); ++i) {
        const QJsonObject st = steps.at(i).toObject();
        const QJsonValue fi = st.value(QStringLiteral("frame"));
        const QByteArray pkt = fi.isDouble() ? hex(frames.at(fi.toInt()).toObject().value(QStringLiteral("packet"))) : hex(st.value(QStringLiteral("packet")));
        Receiver &r = st.value(QStringLiteral("receiver")).toString() == QLatin1String("B") ? rxB : rxA;
        Receiver::Packet p;
        QString why = r.open(pkt, &p);
        if (why.isEmpty())
            why = QStringLiteral("ok");
        const QString want = st.value(QStringLiteral("result")).toString();
        if (why != want) {
            std::printf("       receive step %d: got \"%s\", want \"%s\"\n", i, qPrintable(why), qPrintable(want));
            rxOk = false;
        }
        if (why == QLatin1String("ok") && fi.isDouble() && p.payload != hex(frames.at(fi.toInt()).toObject().value(QStringLiteral("payload"))))
            rxOk = false;
    }
    check(rxOk, "every receive step gives its result, in order");

    // Forward secrecy: once forgotten, an epoch can't be opened again, newer ones still can.
    rxRingB.forgetBefore(2);
    Receiver late(&rxRingB, O2A, t);
    check(late.open(hex(frames.at(0).toObject().value(QStringLiteral("packet"))), nullptr) == QLatin1String("too old")
              && rxRingB.get(1) == nullptr && rxRingB.get(2) != nullptr,
          "forgotten epochs stay forgotten");

    // Fresh keys sign and verify, and a fresh call id is well-formed.
    const Ephemeral fresh = newEphemeral();
    const QByteArray sb = signedBytes(QStringLiteral("offer"), newCallId(), QString::fromLatin1(fresh.publicDer.toBase64()));
    check(verifyDer(fresh.publicDer, sb, signDer(fresh.privateDer, sb)), "a new key signs and verifies (random k)");
    check(callIdBytes(newCallId()).size() == 16 && callIdBytes(QStringLiteral("not-an-id")).isEmpty(), "call ids");
    std::printf("call: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
