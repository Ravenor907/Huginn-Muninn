#pragma once

#include <QByteArray>
#include <QString>
#include <functional>

class QObject;

// Updates for the Windows portable build (Huginn-Windows.zip from the GitHub release).
// Updater downloads the zip, checks its SHA-256 against the one published with the release, then
// calls install(), which does everything inside Huginn itself: it unpacks the zip into a staging
// folder beside Huginn.exe, moves each file in use aside (Windows lets a running program's files be
// renamed), puts the new files in their place and starts the new Huginn.exe as a normal window (it waits for this
// one to close).
// The moved-aside files are deleted the next time Huginn starts (cleanUp()).
// On other platforms (and in a Windows development build) supported() is false.
namespace WinUpdate
{
bool supported();
QString assetName();      // "Huginn-Windows.zip", the release asset to look for
QString digestName();     // "Huginn-Windows.zip.sha256", published beside it by the release job
// The SHA-256 (64 hex digits, lower case) in a .sha256 file or a GitHub asset digest ("sha256:..."); empty if none.
QByteArray parseDigest(const QByteArray &text);
// Empty when Huginn's folder can be written to, else a sentence for the person.
QString folderProblem();
// At every start, before Qt loads anything: deletes what an update moved aside, and files from other
// folder layouts (everything at the top, or a launcher at the top with the app in lib\).
void cleanUp();
void tidyOldLayout();   // the folder-layout part of cleanUp(), kept for callers that run it later
// done: ok (the files were replaced), restarting (the new copy was started; this one must close now) and what to say.
void install(const QString &zipPath, QObject *context, std::function<void(bool ok, bool restarting, const QString &message)> done);
}
