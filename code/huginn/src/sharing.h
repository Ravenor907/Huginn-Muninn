#pragma once

#include <QJsonObject>
#include <QString>

// Cap f2 (shared/docs/protocol.md, section 27): file sharing, the parts Huginn and Muninn must agree on. Plain logic
// only, checked in --chat-selftest (Muninn: net/Sharing.kt, SharingTest).
//  - the route a file takes: the same Wi-Fi first ("Direct"), else the internet ("Over the internet")
//  - a group's file rules in its signed state: who may send ("fs") and the size limit ("fm", whole MB)
//  - where a received file goes: Downloads/Huginn/<chat name>, with every name made safe
//  - why a file was refused, in plain words
namespace Sharing
{
extern const char kCap[];          // "f2"
extern const char kEveryone[];     // "everyone": "fs" left out
extern const char kAdmins[];       // "admins"
extern const char kNobody[];       // "nobody"
const int kMaxLimitMb = 4096;      // "fm": 1 to 4096 (4 GB, the most a file can be)

// The route for a file: "direct" (the same Wi-Fi), "internet" (straight over the internet, only between people who
// trust each other with their addresses), "relay" (small files as sealed pieces through the relays), or "" when none
// can take it (too big for the relays, no trust, not on the same Wi-Fi).
QString route(bool sameWifi, bool trusted, qint64 size, qint64 relayMax);
// What a transfer row says about its route: "Direct" for "direct", else "Over the internet".
QString routeLabel(const QString &route);

// A group's file rules (27.3). Who may send: "everyone", "admins" or "nobody".
QString whoMaySend(const QJsonObject &state);
// The size limit in MB, 0 for none.
int limitMb(const QJsonObject &state);
bool hasRules(const QJsonObject &state);
// Empty when a file of this size from this member is welcome, else why not in plain words.
QString refusal(const QJsonObject &state, const QString &from, qint64 size);
// The same state with these rules (who: "everyone", "admins" or "nobody"; mb 0: no limit).
QJsonObject withRules(QJsonObject state, const QString &who, int mb);
// Empty when "fs" and "fm" are well-formed (or left out), else the reason (GroupWire::checkState).
QString checkRules(const QJsonObject &state);
// "Everyone can send files", "Only admins can send files · Up to 25 MB", "Nobody can send files".
QString rulesLine(const QJsonObject &state);

// One safe file or folder name: no folders, "..", control characters, hidden direction or zero-width marks,
// characters Windows refuses (":" streams too), trailing dots or spaces, or reserved names (CON, NUL, COM1...).
// fallback when nothing is left.
QString safeName(const QString &name, const QString &fallback);
// The folder a chat's files go in: base/<app>/<chat name, made safe>.
QString folderFor(const QString &base, const QString &app, const QString &chatName);
// A file's kind in a few words, from its name: "PDF document", "Picture", "App installer", "File".
QString kindOf(const QString &name);
// Before opening a received file: empty when it's safe to open with a click, else what to ask (a program or script,
// one whose real kind Windows hides behind another extension, or a name with hidden direction marks).
QString openWarning(const QString &name);
// Windows: marks a received file as from the internet (its Zone.Identifier stream), so Windows checks it before it
// runs. Does nothing elsewhere.
void markFromInternet(const QString &path);

// A decline's "why" (27.4) in plain words for the sender: "rules", "space", "no".
QString declineText(const QString &why);

bool selfTest();
}
