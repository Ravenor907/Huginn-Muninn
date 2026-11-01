#pragma once

#include <QJsonObject>
#include <QString>

// Cap d1 (shared/docs/protocol.md, section 16): message limits, the parts Huginn and Muninn must agree on.
// shared/tools/d1_ref.py is the reference; shared/vectors/d1-vectors.json the vectors (Muninn: net/Limits.kt).
//  - every chat deletes messages older than its limit: 7 days, at most 30
//  - a Family or Work group carries "kind" and "days" in its signed state (GroupWire::checkState)
//  - each side of a 1:1 chat says its limit in "days" in its envelopes; the shorter one counts
namespace Limits
{
extern const char kCap[];              // "d1"
const int kDefault = 7;
const int kLongest = 30;
const qint64 kDay = 86400000;

// A group's limit: its "days", 7 for a Standard group.
int groupDays(const QJsonObject &state);
// The limit an envelope says its sender keeps, 0 when it says nothing usable (the old value stays).
int envelopeDays(const QJsonObject &o);
// What I keep a chat for: my choice (0: none) only for someone in my Family or Work folder, else 7.
int myDays(int chosen, bool close);
// A 1:1 chat's limit: the shorter of the two. theirs 0: not said yet (7). An app without d1 doesn't count.
int pairDays(int mine, bool hasD1, int theirs);
// The oldest time that stays.
qint64 cutoff(qint64 now, int days);
// The same state as a Family, Work ("family", "work") or Standard ("") group keeping messages [days].
QJsonObject withLimit(QJsonObject state, const QString &kind, int days);
QString kindName(const QString &kind);   // "Standard", "Family", "Work"

// Checks shared/vectors/d1-vectors.json (chat-vectors.json next to it). Prints what it found.
bool selfTest(const QString &vectorsPath);
}
