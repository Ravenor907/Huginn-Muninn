#pragma once

#include <functional>

#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QJsonValue>
#include <QList>
#include <QString>
#include <QStringList>

#include "chatcrypto.h"

// Caps b1 and e1 (shared/docs/protocol-0.6.md section 23), the same as Muninn's net/Ring.kt;
// shared/tools/e1b1_ref.py is the reference and writes shared/vectors/e1b1-vectors.json.
//  - e1: over the relays, an envelope is wrapped for the recipient's chat key and one of its day keys ("wrap keys",
//    a new one every day, each kept 40 days). Once a day key is gone, a stolen chat key and saved relay traffic no
//    longer show who sent what. Room "file" goes through the ratchet.
//  - b1: each device has a ring key outside the vault. A caller also sends a tiny envelope sealed to it (a call id and
//    a time, nothing more) to the ring mailbox, so a locked device can ring "Incoming call" without opening anything else.
//  - Both public halves travel in receipts ("rk", "rp", "wk", "wt"), only to apps whose caps say b1 or e1.
namespace RingWire
{
extern const char kRingCap[];   // "b1"
extern const char kWrapCap[];   // "e1"
constexpr qint64 kDayS = 86400;
constexpr qint64 kWrapUseS = 7 * kDayS;     // a sender uses a day key at most this long after it was made
constexpr qint64 kWrapKeepS = 40 * kDayS;   // the recipient keeps its private half this long
constexpr qint64 kWrapNewS = kDayS;         // a new day key once the newest is this old
constexpr qint64 kWrapAheadS = kDayS;       // a day key's time may be at most this far ahead
constexpr qint64 kRingOldMs = 60000;        // a ring, like an offer (5.2): no older than this
constexpr qint64 kRingAheadMs = 120000;     // and no further ahead than this

// A P-256 public key: standard base64 of its 91-byte SubjectPublicKeyInfo with an uncompressed point.
bool keyOk(const QJsonValue &v);
// A day key's id on the wire: the first 16 hex characters of SHA-256 of its SubjectPublicKeyInfo.
QString wrapId(const QString &pubB64);
QByteArray wrapKey(const QByteArray &k1, const QByteArray &k2);
// The e1 wrapping {"e","w","n","d"} of body for their chat key and one of their day keys (empty on bad input).
QJsonObject sealWrap(const QString &staticPub, const QString &dayPub, const QByteArray &body);
// Opens an e1 wrapping with my chat key and the day key its "w" names (day() gives an empty pair when that one is gone).
QByteArray openWrap(const QJsonObject &o, const ChatCrypto::KeyPair &me, const std::function<ChatCrypto::KeyPair(const QString &)> &day, bool *ok);
QByteArray ringKey(const QByteArray &shared);
// The ring envelope {"e","n","d"}: plain sealed to a ring key.
QJsonObject sealRing(const QString &ringPub, const QByteArray &plain);
QByteArray openRing(const QJsonObject &o, const ChatCrypto::KeyPair &ring, bool *ok);
// {"t":"ring","call":<call id>,"ts":<ms>}: nothing else, no name, no key.
QByteArray ringPlain(const QString &callId, qint64 ts);
// Well-formed: t "ring", call a call id (22 base64url characters), ts an integer 0 <= ts < 2^53. Other fields are ignored.
bool ringOk(const QJsonObject &p);
bool ringFresh(qint64 ts, qint64 now);
// The content of the relay event for a ring envelope, padded like every relay envelope (section 20).
QString ringContent(const QString &ringPub, const QString &callId, qint64 ts);
bool openRingContent(const QString &content, const ChatCrypto::KeyPair &ring, QString *callId, qint64 *ts);

// What a receipt's "rk", "rp", "wk", "wt" say; each half empty when absent or malformed.
struct Announce {
    QString rk, rp, wk;
    qint64 wt = 0;
};
Announce announceOf(const QJsonObject &r, qint64 nowS);
bool mayUse(qint64 wt, qint64 nowS);
// Kept: under 40 days old and no more than a day ahead (a key stamped further ahead is deleted).
bool keep(qint64 t, qint64 nowS);
bool rotateDue(qint64 newest, qint64 nowS);   // newest < 0: none yet

// Checks shared/vectors/e1b1-vectors.json; prints what it found. Returns true when everything matches.
bool selfTest(const QString &vectorsPath);
}

// This device's day keys (e1): a new one every day, each kept RingWire::kWrapKeepS. Saved (encrypted) through write.
class WrapKeys
{
public:
    struct Day {
        QString id, pub;
        QByteArray priv;   // PKCS#8
        qint64 t = 0;
    };
    WrapKeys(std::function<QByteArray()> read, std::function<void(const QByteArray &)> write);
    // The day key to announce now: a new one when the newest is a day old. Old ones past keeping go, and so do ones
    // stamped more than a day ahead (a clock put back never makes a new key on every call).
    Day current(qint64 nowS);
    // A new day key now, whatever the newest's age (after a restore: the backup's keys may be older than what
    // contacts hold). It is the one current() gives from then on.
    Day renew(qint64 nowS);
    // The key pair a wrapping's "w" names, while it's kept (empty when gone).
    ChatCrypto::KeyPair find(const QString &id, qint64 nowS);
    void prune(qint64 nowS);

private:
    void load();
    void save() const;
    std::function<QByteArray()> m_read;
    std::function<void(const QByteArray &)> m_write;
    QList<Day> m_days;
    bool m_loaded = false;
};

// What each contact announced (b1, e1): their ring key and ring mailbox, their newest day key. Saved (encrypted) through write.
class PeerKeys
{
public:
    PeerKeys(std::function<QByteArray()> read, std::function<void(const QByteArray &)> write);
    // Takes what an authentic receipt from key announced: the ring key as it says, a day key only when newer.
    void take(const QString &key, const RingWire::Announce &a);
    RingWire::Announce get(const QString &key);
    // Their day key to wrap with now, or empty (none yet, or older than a week: then the wrapping is the plain one).
    QString wrapFor(const QString &key, qint64 nowS);
    void forget(const QString &key);

private:
    void load();
    void save() const;
    std::function<QByteArray()> m_read;
    std::function<void(const QByteArray &)> m_write;
    QHash<QString, RingWire::Announce> m_keys;
    bool m_loaded = false;
};

// This device's ring key (b1): a P-256 key pair and a Nostr secret for the ring mailbox, in a file of Huginn's own
// outside the vault (never an OS key store), readable by this user only. With it a locked Huginn can tell a call is
// coming, and nothing else: someone holding the computer learns only that calls came and when.
class RingKey
{
public:
    static constexpr qint64 kOldKeepMs = 2 * 86400000LL;   // after renew(), the old ring mailbox is still read this long
    explicit RingKey(const QString &path);
    QString publicKey();      // "rk"
    QString mailbox();        // "rp": where callers send ring envelopes
    // The ring mailboxes to read and their Nostr secrets (to sign in to relays that ask, NIP-42): the current one,
    // and the one before a renew() for kOldKeepMs.
    QStringList mailboxes();
    QList<QByteArray> nostrSecrets();
    // A ring envelope's content opened (with the current key, or the old one while it's read): true with the call id and ts.
    bool open(const QString &content, QString *callId, qint64 *ts);
    // Someone was blocked: a new key pair and ring mailbox, so they can't ring this device any more. The old one is read
    // for kOldKeepMs (contacts who haven't heard of the new one yet).
    void renew();
    // True (once) when the old ring mailbox just stopped being read.
    bool dropOld();
    // Erase everything and Move: the file goes, and no ring mailbox is read. A new key is made only when publicKey() or
    // mailbox() is asked for again (Chat, once there is a vault again).
    void forget();

private:
    struct Pair {
        ChatCrypto::KeyPair keys;
        QByteArray secret;
        QString mailbox;
    };
    void load();
    bool save() const;
    QString m_path;
    Pair m_now, m_old;
    qint64 m_oldUntil = 0;   // ms; 0: no old one
    bool m_loaded = false;
    bool m_forgotten = false;
};
