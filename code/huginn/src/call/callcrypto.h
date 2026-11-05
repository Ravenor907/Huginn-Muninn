#pragma once

#include <QByteArray>
#include <QString>
#include <algorithm>
#include <map>

// The deterministic parts of 1:1 calls (shared/docs/protocol.md 5.2 to 5.4), kept free of sockets and
// timers so --call-selftest can check them byte for byte against shared/vectors/proto06-vectors.json:
//  - the ephemeral P-256 key of each call, signed with the chat key (domain "huginn-muninn call v1")
//  - ECDH, the call root, the chain of epochs and each epoch's per-direction AES-256-GCM keys
//  - media packets: 12-byte header (0x81), sealed with the epoch key of their seq
//  - the receiver's checks in the spec's order, with the 1024-packet replay window
// shared/tools/proto06_ref.py is the reference; when this and it disagree, it wins.
namespace CallCrypto
{
extern const char kDomain[];           // "huginn-muninn call v1"
const int kHeader = 12;
const int kGcmTag = 16;
const int kMinPacket = kHeader + kGcmTag;   // 28
const int kMaxPacket = 1200;
const int kWindow = 1024;
const int kEpochBits = 14;              // a new epoch every 16384 packets
const int kMaxAhead = 2;                // epochs a packet may be ahead of the receiver
const unsigned char kVersion = 0x81;

// 3 to 6 only between two c2 apps (shared/docs/protocol.md 5.8): video fragments, receiver reports,
// keyframe requests and resend requests (NACK). An app without c2 refuses them as "type"; nobody sends them to one.
enum Type { Audio = 0, Ping = 1, Pong = 2, Video = 3, Report = 4, KeyRequest = 5, Nack = 6 };
enum Direction { O2A = 0, A2O = 1 };    // offerer to answerer, and back

struct Ephemeral {
    QByteArray privateDer;   // PKCS#8, never stored
    QByteArray publicDer;    // SubjectPublicKeyInfo
};
Ephemeral newEphemeral();
// The key with this 32-byte private value (the self-test's 0x3001 and 0x4001).
Ephemeral ephemeralFromScalar(const QByteArray &scalar32);

// Call ids: 16 random bytes, base64url without padding.
QString newCallId();
QByteArray callIdBytes(const QString &callId);   // empty unless it is 16 bytes
QString base64Url(const QByteArray &raw);
QByteArray fromBase64Url(const QString &text);

// UTF-8(domain + "\n" + canon({"call","eph","t"})), what offer and answer signatures cover.
QByteArray signedBytes(const QString &t, const QString &callId, const QString &ephB64);
// ECDSA P-256 / SHA-256, DER. Random k. privateDer is PKCS#8 (or SEC1).
QByteArray signDer(const QByteArray &privateDer, const QByteArray &bytes);
bool verifyDer(const QByteArray &publicDer, const QByteArray &bytes, const QByteArray &sigDer);

// The 32-byte x coordinate of ECDH(mine, theirs); empty on bad keys.
QByteArray ecdh(const QByteArray &myPrivateDer, const QByteArray &theirPublicDer);
QByteArray root(const QByteArray &ikm, const QByteArray &callId, const QByteArray &offerEphDer, const QByteArray &answerEphDer);
QByteArray tag(const QByteArray &callId);              // 2 bytes
QByteArray nextChain(const QByteArray &chain);         // HMAC-SHA256(chain, 0x02)

struct EpochKeys {
    QByteArray key[2];       // indexed by Direction
    QByteArray salt[2];
};
EpochKeys epochKeys(const QByteArray &chain);
QByteArray header(int type, const QByteArray &tag, quint32 seq, quint32 ts);
QByteArray nonce(const QByteArray &salt, quint32 seq);

QByteArray hmacSha256(const QByteArray &key, const QByteArray &data);
QByteArray hkdf(const QByteArray &salt, const QByteArray &ikm, const QByteArray &info, int length);
QByteArray sha256(const QByteArray &data);
void wipe(QByteArray &b);

// The epochs of one call. Keys are derived when first needed; forgetBefore() wipes older epochs
// and every chain value before them, so a device captured mid-call can't open earlier minutes.
class Keyring
{
public:
    explicit Keyring(const QByteArray &root = QByteArray());
    ~Keyring();
    Keyring(const Keyring &) = delete;
    Keyring &operator=(const Keyring &) = delete;
    bool valid() const { return !m_chain.isEmpty(); }
    // nullptr for an epoch that was already forgotten.
    const EpochKeys *get(quint32 epoch);
    void forgetBefore(quint32 epoch);
    quint32 oldest() const { return m_base; }
    int cached() const { return int(m_keys.size()); }

private:
    quint32 m_base = 0;            // the epoch m_chain belongs to
    QByteArray m_chain;
    std::map<quint32, EpochKeys> m_keys;
};

// One direction out. seq counts every packet of any type from 0.
class Sender
{
public:
    Sender(Keyring *keys, Direction dir, const QByteArray &tag) : m_keys(keys), m_dir(dir), m_tag(tag) {}
    QByteArray seal(int type, quint32 ts, const QByteArray &payload) { return sealAt(type, m_next++, ts, payload); }
    QByteArray sealAt(int type, quint32 seq, quint32 ts, const QByteArray &payload);
    quint32 next() const { return m_next; }
    quint32 epoch() const { return m_next >> kEpochBits; }

private:
    Keyring *m_keys;
    Direction m_dir;
    QByteArray m_tag;
    quint32 m_next = 0;
};

// One direction in (5.4). open() returns "" and fills out, or the reason it was dropped:
// "size", "version", "type", "other call", "too old", "duplicate", "too far ahead", "not authentic".
class Receiver
{
public:
    struct Packet {
        int type = 0;
        quint32 seq = 0, ts = 0;
        QByteArray payload;
    };
    Receiver(Keyring *keys, Direction dir, const QByteArray &tag) : m_keys(keys), m_dir(dir), m_tag(tag) {}
    QString open(const QByteArray &pkt, Packet *out);
    qint64 high() const { return m_high; }
    // The oldest epoch the window can still accept.
    quint32 lowEpoch() const { return m_high < 0 ? 0 : quint32(std::max<qint64>(0, m_high - kWindow + 1) >> kEpochBits); }
    // Peeks at a packet's tag without opening it (which call it is for).
    static QByteArray tagOf(const QByteArray &pkt) { return pkt.size() >= 4 ? pkt.mid(2, 2) : QByteArray(); }

private:
    bool seen(quint32 seq) const;
    void mark(quint32 seq);
    Keyring *m_keys;
    Direction m_dir;
    QByteArray m_tag;
    qint64 m_high = -1;
    quint64 m_bits[kWindow / 64] = {};   // bit (seq % kWindow): seq was accepted, for seqs inside the window
};

// Checks the call section of shared/vectors/proto06-vectors.json (with shared/vectors/chat-vectors.json keys inside
// it under "keys"). Prints what it found; true when everything matches.
bool selfTest(const QString &vectorsPath);
}
