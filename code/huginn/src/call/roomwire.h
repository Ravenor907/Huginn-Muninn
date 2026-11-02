#pragma once

#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QString>
#include <QStringList>
#include <functional>

// The deterministic parts of voice rooms and group calls (shared/docs/protocol.md section 6), free of
// sockets and timers so --call-selftest can check them against shared/vectors/proto06-vectors.json:
//  - room messages {"t","call":"-","g","ch","ts","sig"}, signed with the chat key
//    (domain "huginn-muninn vroom v1"), and the receiver's checks in the spec's order (6.2)
//  - presence: who is in which room, newest ts per sender wins, 25 minutes without news is gone (6.4)
//  - the glare rule (lower chat key text offers) and the room size (8 people) (6.3)
// shared/tools/proto06_ref.py is the reference; when this and it disagree, it wins.
namespace RoomWire
{
extern const char kDomain[];              // "huginn-muninn vroom v1"
const int kMaxPeople = 8;                 // the one who started it included
const qint64 kFreshPastMs = 60000;
const qint64 kFreshAheadMs = 120000;
const qint64 kPresenceTtlMs = 25 * 60000;
const qint64 kAnnounceMs = 10 * 60000;    // those in a room re-send vjoin this often
const qint64 kRingMs = 45000;
// Cap v2 (6.8): an app with it re-sends vjoin every 10 s while in a room, to members whose caps include v2. Its
// presence is "Reconnecting" after 20 s without news and gone after 60 s (a phone that died never sends vleave).
extern const char kHeartbeatCap[];        // "v2"
const qint64 kHeartbeatMs = 10000;
const qint64 kQuietMs = 20000;
const qint64 kGoneMs = 60000;
// How someone counts who was last heard from quietMs ago: 0 gone, 1 in, 2 reconnecting. heartbeats: their app has v2.
int heard(qint64 quietMs, bool heartbeats);

// Exactly n raw bytes as canonical base64url without padding.
bool isId(const QString &s, int bytes);
bool isRoomId(const QString &ch);         // a voice channel id (8 bytes) or a group call id (16)
bool isGroupCall(const QString &ch);      // 16 bytes: a temporary room
QString newGroupCallId();

// A signed room message. sign: DER ECDSA of the bytes with this device's chat key.
QJsonObject make(const QString &t, const QString &g, const QString &ch, qint64 ts, const std::function<QByteArray(const QByteArray &)> &sign);
// "" when well-formed, otherwise the reason (as the reference words it).
QString check(const QJsonObject &m);
// 6.2: "ok", "stale" or why it was refused. state: the receiver's current state of m's group (nullptr: none).
QString accept(const QJsonObject *state, const QString &me, const QString &from, const QJsonObject &m, qint64 nowMs);
// 6.3: whose offer wins when two cross.
inline QString offerWins(const QString &a, const QString &b) { return a < b ? a : b; }
inline bool full(int peers) { return peers >= kMaxPeople - 1; }

// 6.4, for one device.
class Presence
{
public:
    // An accepted ("ok" or "stale") vjoin or vleave from `from`. True if it changed anything. heartbeats: the sender's
    // app has v2 (its presence goes after 60 s without news); fresh: the message was "ok" (a stale one from such an
    // app shows nothing new, so a late copy never brings someone back).
    bool apply(const QString &from, const QJsonObject &m, qint64 nowMs, bool heartbeats = false, bool fresh = true);
    // 0 not in (g, ch), 1 in, 2 reconnecting (heard() of their last news).
    int state(const QString &g, const QString &ch, const QString &key, qint64 nowMs) const;
    // Who is in (g, ch), sorted.
    QStringList in(const QString &g, const QString &ch, qint64 nowMs) const;
    // Rooms of g with anyone in them: ch -> sorted keys.
    QHash<QString, QStringList> rooms(const QString &g, qint64 nowMs) const;
    void forgetGroup(const QString &g);
    void clear() { m_entries.clear(); }

private:
    struct Entry {
        QString g, ch, key;
        bool in = false;
        bool beats = false;
        qint64 ts = 0, seen = 0;
    };
    QHash<QString, Entry> m_entries;   // g + " " + ch + " " + key
};

// Checks the room section of shared/vectors/proto06-vectors.json. Prints what it found; true when all match.
bool selfTest(const QString &vectorsPath);
}
