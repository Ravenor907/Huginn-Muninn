#pragma once

#include "chatcrypto.h"
#include "ratchet.h"

#include <QByteArray>
#include <QJsonObject>
#include <QString>
#include <QStringList>
#include <map>
#include <utility>
#include <vector>

// How a chat message goes on the wire, the same as Muninn's net/Chat.kt:
//  - Every envelope says "cap":"r1": this app can read ratchet messages. Apps without "caps" test
//    exactly that, so it never changes; "caps" lists everything this app can do ("r1 g1 c1 c2 t1 v1 m1 m2"; m1: replies, reactions, edits, typing, see RichWire; m2: albums, video messages, polls, link previews, see MoreWire).
//  - v1: "nonce" + "data", sealed with the pair key (ChatCrypto::seal).
//  - r1: "rh" {b, d, pn, n} (the ratchet header) + "data" (standard base64 of the ratchet
//    ciphertext), for private-room messages to a contact whose app said "cap":"r1".
// Kept apart from Chat so the self-test can drive it without a network or a vault.
namespace ChatWire
{
extern const char kCap[];   // "r1"
// What this app can do, sent as "caps" (shared/docs/protocol.md 1.1). Only what Huginn really does:
// no k1 or k2 (managing a kids' TV is Muninn only), or peers would try it and fail. t1: takes a
// kids' TV card (7.6), so a parent can invite someone on Huginn to talk with the kids. v1: voice
// rooms and group calls (section 6).
extern const char kCaps[];  // "r1 g1 c1 c2 t1 v1 m1 m2 d1 i1 f1 j1 x1 s1 b1 e1 a1 p1 v2 g2 f2 g3 f3 c3 g4 n1 n2" (n1, n2: nudges to a locked phone, see RingWire) (g4: join refusals, see GroupWire) (v2: room heartbeats, see RoomWire) (a1: animated GIFs, see Photos) (g2: group timeouts and bans, see ModWire) (f2: file sharing, see Sharing) (f3: resume and long waits, see FileLink) (c3: no screen frames to a peer not watching, see CallMedia)

// The ratchet is for private-room and group payloads to a contact (or group peer) that said it
// can read it.
bool useRatchet(const QString &room, bool contact, bool capable);
bool isRatchet(const QJsonObject &o);
bool saysCapable(const QJsonObject &o);
// The "caps" tokens of an envelope; *present is false when it has none (an app without caps).
QStringList capsOf(const QJsonObject &o, bool *present = nullptr);
// Round 14 (1.1): tokens the caps this app said last time (before) had that it doesn't say now: it went back to an older
// release (Labs "Go back to stable"), so every contact is probed again and stops sending what it dropped.
bool capsDropped(const QString &before, const QString &now);

// Adds "cap" and "caps" and seals plain into the envelope fields (room, fromKey, fromName, mailbox, id, ...).
// With a peer the ratchet is used and the peer moves on (save it before sending); when the
// ratchet can't make a message, v1 is used instead. bind: cap s1, the clear fields are sealed in too
// ("aad":"s1", see Proof), only for someone whose app said s1.
QJsonObject seal(QJsonObject fields, const QByteArray &sk, const QString &theirKey, const QByteArray &plain, Ratchet::Peer *peer,
                 const Ratchet::NewKey &newKey, bool bind = false);

// Opens either kind. An "rh" message needs the sender's peer, which moves on only when the
// message opens: pass a copy and keep it once the message is accepted. ok false on any failure.
// A bound envelope (cap s1) also needs myKey: it was sealed to that key and no other.
QByteArray open(const QJsonObject &o, const QByteArray &sk, const ChatCrypto::KeyPair &me, Ratchet::Peer *peer,
                const Ratchet::NewKey &newKey, bool *ok, const QString &myKey = QString());

// Sealed envelopes kept for resending (a day at most), so every retry and every route sends
// the very same bytes and the ratchet never seals one message twice.
class Outbox
{
public:
    struct Entry {
        qint64 time = 0;
        QString key;                     // who it's for
        std::vector<QByteArray> bodies;  // compact JSON, one per piece
    };
    const Entry *find(const QString &id) const;
    void put(const QString &id, const Entry &e) { m_entries[id] = e; }
    bool drop(const QString &id) { return m_entries.erase(id) > 0; }
    std::vector<QString> ids() const;
    std::vector<QString> expired(qint64 now, qint64 maxAge) const;
    void clear() { m_entries.clear(); }

    static QByteArray save(const Entry &e);
    static bool load(const QByteArray &json, Entry *e);

private:
    std::map<QString, Entry> m_entries;
};

// Message ids seen lately from each sender (receipts, unsends, keep in touch), so a second copy
// is dropped before it reaches the ratchet. note remembers what it was about (an unsend's target).
class Seen
{
public:
    bool has(const QString &from, const QString &id) const;
    void add(const QString &from, const QString &id, qint64 now, const QString &note = QString());
    QString note(const QString &from, const QString &id) const;
    void prune(qint64 now, qint64 maxAge, size_t max);
    // Kept on disk too (only a hash of who and which), so a restart doesn't let an old copy in again.
    bool dirty() const { return m_dirty; }
    QByteArray save();
    void load(const QByteArray &json, qint64 now, qint64 maxAge);

private:
    std::map<QString, std::pair<qint64, QString>> m_ids;
    bool m_dirty = false;
};

// Envelopes, v1 and r1 both ways, the capability upgrade, resends, duplicates and nicknames.
// Prints what it found; true when everything passed.
bool selfTest();
}
