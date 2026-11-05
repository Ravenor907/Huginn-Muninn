#pragma once

#include <QJsonObject>
#include <QString>
#include <QStringList>

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

// A group's limit: its "days", 7 for a Folk group (an old "work" group keeps its days).
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
QString kindName(const QString &kind);   // "Family" for "family", else "Folk" (an old "work" group is Folk)

// Group types (protocol.md 16.2): Folk and Family. On the wire a Family group has "kind":"family"; a Folk group
// has no "kind" (an old "work" group is shown and treated as Folk and keeps its days).
extern const char kFolk[];             // "folk"
extern const char kFamily[];           // "family"
QString groupType(const QJsonObject &state);    // "folk" or "family"
QString typeName(const QString &type);          // "Folk" or "Family"
QString typeLine(const QString &type);          // the one line under its name
QStringList typeRules(const QString &type);     // "What's the difference?"
// Family groups have no invite links: only admins add people, by a code or face to face.
bool linksAllowed(const QJsonObject &state);
// Members (not only admins) may make invite links: a Folk group with "mi".
bool membersMayInvite(const QJsonObject &state);
// A file of this size from a member arrives by itself (Folk: small ones; Family: all of them).
bool filesArrive(const QString &type, qint64 size, qint64 autoBytes);
// The same state as a group of this type. Family: "kind":"family", days 7 to 30 (7 left out), no "mi". Folk: no
// "kind" or "days", and "mi" on when miOk (everyone's app takes it); an old "work" group already is Folk and stays.
QJsonObject withType(QJsonObject state, const QString &type, int days, bool miOk = false);

// Checks shared/vectors/d1-vectors.json (chat-vectors.json next to it). Prints what it found.
bool selfTest(const QString &vectorsPath);
}
