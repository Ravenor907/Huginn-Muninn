#pragma once

#include <functional>

#include <QByteArray>
#include <QHash>
#include <QSet>
#include <QJsonObject>
#include <QJsonValue>
#include <QList>
#include <QString>
#include <QStringList>

#include "chatcrypto.h"

// Caps b1 and e1 (shared/docs/protocol.md section 23), the same as Muninn's net/Ring.kt;
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

// What a receipt's "rk", "rp", "wk", "wt" (and "nk", "np", cap n2) say; each half empty when absent or malformed.
struct Announce {
    QString rk, rp, wk;
    qint64 wt = 0;
    QString nk, np;
};
Announce announceOf(const QJsonObject &r, qint64 nowS);
bool mayUse(qint64 wt, qint64 nowS);
// Kept: under 40 days old and no more than a day ahead (a key stamped further ahead is deleted).
bool keep(qint64 t, qint64 nowS);
bool rotateDue(qint64 newest, qint64 nowS);   // newest < 0: none yet

// Caps n1 and n2 (protocol.md 23.3): nudges. A real message (text, photo, voice or video message, file offer, group
// invite) also sends one tiny sealed envelope a locked device can open without its vault, at most one a minute per
// person (per person and chat with n2). A locked device notifies only for these.
//  - n1: {"t":"nudge","ts":...} sealed to a contact's ring key, to their ring mailbox.
//  - n2: the same with "from" (the sender's name), "chat" ("" for 1:1, else the group's name) and "kind", sealed to
//    their nudge key (NudgeKey: contacts and group peers get it) and sent to their nudge mailbox; to a contact whose
//    nudge key hasn't come yet, sealed to their ring key instead.
extern const char kNudgeCap[];       // "n1"
extern const char kRichNudgeCap[];   // "n2"
constexpr qint64 kNudgeEveryMs = 60000;
constexpr int kNudgeNameMax = 40;    // "from" and "chat" are at most this many characters
// Whether something sent in room with plaintext plain is a real message (and so worth a nudge).
bool nudgeCounts(const QString &room, const QByteArray &plain);
// The kind of a real message in room: "photo", "file", "invite" or "msg" (a photo room's own kind comes from blobKind).
QString nudgeKind(const QString &room);
// A photo room message: "voice", "video", "file" (a small file) or "photo" (a GIF too).
QString blobKind(int voiceMs, int videoMs, const QString &file);
// A name in a nudge: control and format characters taken out, trimmed, at most kNudgeNameMax characters. Not a string: "".
QString nudgeClean(const QJsonValue &v);
// What a nudge says. An n1 nudge names nobody and is a "msg". known: it opened with the ring key, which only contacts
// get, so its "from" is a contact's own word; one that opened with the nudge key may be any group peer's (anonymous).
struct Nudge {
    qint64 ts = 0;
    QString from, chat;
    QString kind = QStringLiteral("msg");
    bool known = true;
};
// A nudge from the nudge key names nobody: "from", "chat" and "kind" go (round 15, protocol.md 23.3).
Nudge anonymous(const Nudge &n);
// A nudge older than this by its own ts is a replay or long gone: dropped.
constexpr qint64 kNudgeMaxAgeMs = 2LL * 86400000;
inline bool nudgeFresh(const Nudge &n, qint64 nowMs) { return n.ts >= nowMs - kNudgeMaxAgeMs; }
// Round 14: someone whose app can't nudge this device while locked: a contact whose known caps have neither n1 nor
// n2, or a group-only peer without n2. Unknown caps (empty) count as able.
bool cantNudge(const QStringList &caps, bool groupOnly);
// {"t":"nudge","ts":<ms>}: the n1 form, nothing else.
QByteArray nudgePlain(qint64 ts);
// {"t":"nudge","ts","from","chat","kind"}: the n2 form, names cleaned as a receiver cleans them.
QByteArray richNudgePlain(qint64 ts, const QString &from, const QString &chat, const QString &kind);
// Well-formed: t "nudge" and ts an integer 0 <= ts < 2^53. Other fields are ignored here.
bool nudgeOk(const QJsonObject &p);
// A well-formed nudge, n1 or n2, read into n: names cleaned, an unknown or missing kind is "msg".
bool nudgeRead(const QJsonObject &p, Nudge *n);
// The relay event's content: n1 sealed to a ring key, padded like a ring.
QString nudgeContent(const QString &ringPub, qint64 ts);
// n2, sealed to a nudge key (or a ring key) the same way.
QString richNudgeContent(const QString &pub, qint64 ts, const QString &from, const QString &chat, const QString &kind);
// A nudge's content opened with keys (a ring key or a nudge key): true with what it says.
bool openNudgeContent(const QString &content, const ChatCrypto::KeyPair &keys, Nudge *n);
// A locked nudge's notification, for Settings > Notifications > Show (mode 0 nothing, 1 the name, 2 name and message):
// one per sender and chat (with nothing, one for all); the title says who and the group ("from · chat"), the text what
// kind came (the message itself can't be opened while locked) or how many once more came.
QString nudgeGroupKey(int mode, const Nudge &n);
QString nudgeTitle(int mode, const Nudge &n);
QString nudgeText(int mode, const QString &kind, int count);

// Checks shared/vectors/e1b1-vectors.json; prints what it found. Returns true when everything matches.
bool selfTest(const QString &vectorsPath);
}

// What a locked device does with nudges from the nudge key (maybe not a contact). Round 15 (protocol.md 23.3): nothing
// the sender chose is shown; they all share one "Group message" notification that counts up, and sound at most once
// per kAlertEveryMs in all. A contact's nudge (known) keeps its names and sounds as before.
class LockedNudgeLimit
{
public:
    static constexpr qint64 kAlertEveryMs = 120000;
    struct Take {
        RingWire::Nudge n;   // as it will read
        QString key;         // its notification
        bool alert = true;   // may sound
    };
    Take take(int mode, const RingWire::Nudge &n, qint64 nowMs);
    void clear();

private:
    qint64 m_lastAlert = -kAlertEveryMs;
};

// Cap n1: at most one nudge per recipient per RingWire::kNudgeEveryMs.
class NudgeGate
{
public:
    explicit NudgeGate(qint64 everyMs = RingWire::kNudgeEveryMs) : m_every(everyMs) {}
    // True when a nudge to key may go now (and counts it).
    bool take(const QString &key, qint64 nowMs);

private:
    qint64 m_every;
    QHash<QString, qint64> m_last;
};

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
    // Copies of the day keys kept now, for the relay thread (Nostr::opener).
    QList<Day> kept(qint64 nowS);
    // Chat::unwrap with copies of the keys: my chat key and the day keys (kept()). Safe on any thread: it uses only what
    // it is given. ok only when it opened (or the content wasn't sealed) and there is something in it.
    static QByteArray openEnvelope(const QString &content, const ChatCrypto::KeyPair &me, const QList<Day> &days, qint64 nowS, bool *ok);
    // A save the saving thread gave up on: written again from memory (nothing when never read).
    void saveAgain() const
    {
        if (m_loaded)
            save();
    }

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
    // A save the saving thread gave up on: written again from memory (nothing when never read).
    void saveAgain() const
    {
        if (m_loaded)
            save();
    }

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
    // A nudge's content opened the same way (caps n1 and n2): true with what it says.
    bool nudge(const QString &content, RingWire::Nudge *n);
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

// Cap n2: this device's nudge key, a P-256 key pair and a Nostr secret for its nudge mailbox, kept like the ring key in
// a file outside the vault (nudge-key.json) and erased with it. Its public half goes to contacts and group peers whose
// app says n2 ("nk", "np"), so group members can nudge too. It only ever opens nudges: a ring sealed to it is never a
// call, so someone who got it through a group can't ring this device. Made new with the ring key (a block).
class NudgeKey
{
public:
    explicit NudgeKey(const QString &path) : m_keys(path) {}
    QString publicKey() { return m_keys.publicKey(); }   // "nk"
    QString mailbox() { return m_keys.mailbox(); }       // "np"
    QStringList mailboxes() { return m_keys.mailboxes(); }
    QList<QByteArray> nostrSecrets() { return m_keys.nostrSecrets(); }
    // A nudge's content opened: true with what it says. Never a ring.
    bool nudge(const QString &content, RingWire::Nudge *n) { return m_keys.nudge(content, n); }
    void renew() { m_keys.renew(); }
    bool dropOld() { return m_keys.dropOld(); }
    void forget() { m_keys.forget(); }

private:
    RingKey m_keys;
};
