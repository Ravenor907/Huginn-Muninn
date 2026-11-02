#pragma once

#include <QJsonObject>
#include <QJsonValue>
#include <QString>
#include <QtGlobal>

// Cap g2 (shared/docs/protocol.md section 26), the deterministic parts, kept apart from Chat so the self-tests can
// check them against shared/vectors/g2-vectors.json (shared/tools/g2_ref.py is the reference):
//  - group timeouts, bans and removed messages: records an admin signs, like a group state, with their own domain
//    {"a": "ban" | "unban" | "timeout" | "untimeout" | "remove", "g", "k" (who), "by" (the admin), "ts", ["until"],
//     ["m" (the message, only for "remove")], "sig"}
//  - the per-person volume (0 to 200 %) both apps apply to someone's decoded sound before mixing.
// Muninn's twin is net/Mod.kt.
namespace ModWire
{
extern const char kCap[];       // "g2"
extern const char kDomain[];    // "huginn-muninn gmod v1"
const qint64 kMinute = 60000;
const qint64 kDay = 86400000;
const qint64 kLongest = 7 * kDay + kMinute;   // the longest timeout taken: a week, and a minute of slack
const int kMaxList = 50;                      // records in one "mod" control message
const int kVolumeMin = 0, kVolumeMax = 200, kVolumeDefault = 100;

// The timeouts the menus offer: 10 minutes, 1 hour, 1 day, 1 week.
const qint64 kTimeouts[] = {10 * kMinute, 60 * kMinute, kDay, 7 * kDay};

// Empty when a record is well-formed, otherwise why not. Does not check the signature.
QString check(const QJsonObject &r);
// "ok" when the record is taken against the receiver's current state of its group (null: none), otherwise why not.
QString accept(const QJsonObject *state, const QJsonObject &r);
// "ban" for ban and unban, "timeout" for timeout and untimeout: each slot keeps its newest record per person.
QString slot(const QJsonObject &r);
// True when next replaces cur in its slot: a higher ts (the same ts keeps cur). cur empty: nothing yet.
bool newer(const QJsonObject &cur, const QJsonObject &next);
bool banned(const QJsonObject &rec);
bool timedOut(const QJsonObject &rec, qint64 nowMs);
// An unsigned record (sign it with GroupWire::sign and kDomain). until only for "timeout".
QJsonObject make(const QString &action, const QString &g, const QString &who, const QString &by, qint64 ts, qint64 until = 0);
// An unsigned "remove": an admin's "Delete for everyone" of msg, a message author wrote. Not kept in a slot.
QJsonObject makeRemove(const QString &g, const QString &author, const QString &by, qint64 ts, const QString &msg);
bool isRemove(const QJsonObject &r);

// Whole numbers 0 to 200; anything else is 100.
int clampVolume(const QJsonValue &v);
int clampVolume(int pct);
// One sample at pct percent (clamped), truncated toward zero and clipped to 16 bits.
qint16 gain(qint16 sample, int pct);
// A whole frame in place; 100 % leaves it as it is.
void applyGain(qint16 *pcm, int n, int pct);

// Checks shared/vectors/g2-vectors.json (with chat-vectors.json next to it for nothing more than the path): records,
// forged and tampered ones refused, order, timeout expiry, volume and gain, and cap g3's about text. Prints what it found.
bool selfTest(const QString &vectorsPath);
// The volume part on its own (for --call-selftest).
bool volumeSelfTest(const QString &vectorsPath);
}
