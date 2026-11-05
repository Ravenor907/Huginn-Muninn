#pragma once

#include <QJsonObject>
#include <QJsonValue>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

// Cap m1 (shared/docs/protocol.md, section 11): replies, reactions, edits, typing and active status, the
// parts Huginn and Muninn must agree on. shared/tools/m1_ref.py is the reference; shared/vectors/m1-vectors.json the vectors.
//  - room "rich": a 1:1 message with a reply, {text, re}. Handled like "private" in every other way.
//  - room "mx":   {t:"react", msg, e, ts} and {t:"edit", msg, text, ts} for a 1:1 message.
//  - room "live": {t:"typing"|"active", on, ts}, sealed with v1, never retried. Cap p1 (section 22) adds "st"
//                 (online, away or dnd) and "cs" (a custom status, at most 80 characters) to "active".
//  - gmsg "re", and room "group" t "react" and "edit" for group messages.
namespace RichWire
{
extern const char kCap[];              // "m1"
const int kMaxText = 2000;
const int kMaxEmoji = 16;
const int kMaxId = 64;
const qint64 kLiveWindow = 60000;      // typing and active: only within a minute of our clock
const qint64 kTypingMs = 6000;         // "typing" shows this long after the last "on"
const qint64 kTypingEvery = 3000;      // "on" at most this often while typing
const qint64 kActiveMs = 5 * 60000;    // "active" shows this long after the last "on"
const qint64 kActiveEvery = 2 * 60000; // and is said again this often while Huginn is in front
const int kMaxCustom = 80;             // cap p1: a custom status, in code points
// Cap p1: the statuses that go on the wire in "st" (invisible is sent as not active, with no "st").
inline const QStringList kStatuses{QStringLiteral("online"), QStringLiteral("away"), QStringLiteral("dnd")};

bool isMsgId(const QJsonValue &v);
bool isTs(const QJsonValue &v);
qint64 ts(const QJsonObject &p);
bool textOk(const QJsonValue &v);
bool emojiOk(const QJsonValue &v);
// Cap p1: a custom status is text of at most 80 code points with no control characters ("" is none).
bool customOk(const QJsonValue &v);
// The first n code points of s (never half a surrogate pair).
QString cut(const QString &s, int n);
// Well-formed for room "rich", "mx", "live", "gmsg" (its "re" only) or "group" (react and edit only).
bool ok(const QString &room, const QJsonObject &p);
bool fresh(qint64 ts, qint64 now);

// Reactions on one message: who -> {e, t}. One per person; a later t replaces it ("" takes it away,
// kept as a marker so an older copy can't bring it back). True when it changed.
bool react(QVariantMap &all, const QString &from, const QString &e, qint64 ts);
QVariantMap shown(const QVariantMap &all);                     // who -> emoji, without the taken-back ones
QVariantList counts(const QVariantMap &all, const QString &me);  // [{e, n, mine}], most first
bool editWins(const QString &author, const QString &from, qint64 ts, qint64 shownTs);
// "@name" for me (any case), not inside a longer word.
bool mentions(const QString &text, const QString &me);

QByteArray richText(const QString &text, const QString &re);
QByteArray reactMsg(const QString &msg, const QString &e, qint64 ts);
QByteArray editMsg(const QString &msg, const QString &text, qint64 ts);
QByteArray liveMsg(const QString &t, bool on, qint64 ts);
// Cap p1: "active" with my status. Invisible (or not active) goes as not active with no "st", so an app without p1
// sees exactly what it always did; custom is cut to 80 characters and left out when empty.
QByteArray activeMsg(bool on, qint64 ts, const QString &status, const QString &custom);

// Checks shared/vectors/m1-vectors.json. Prints what it found; true when everything passed.
bool selfTest(const QString &vectorsPath);
}
