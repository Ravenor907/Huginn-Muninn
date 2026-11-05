#pragma once

#include <QByteArray>
#include <QString>
#include <functional>

class QObject;

// Updates for the Windows portable build (Huginn-Windows.zip from the GitHub release).
// Updater downloads the zip, checks its SHA-256 against the one published with the release, then
// calls install(), which does everything inside Huginn itself: it unpacks the zip into update-staging beside
// Huginn.exe, marks it ready (with the staged Huginn.exe's SHA-256) and starts the staged Huginn.exe as a normal window.
// Nothing the running copy uses is renamed or replaced.
// The staged Huginn.exe waits until the old copy has closed, copies its files over Huginn's folder (finishAtStart()),
// starts Huginn.exe from there and ends; that copy removes update-staging. When the restart doesn't happen, the next
// start of Huginn.exe finds the ready update and hands it to the staged copy the same way.
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
// At start, once this copy is the only one: true when this copy only finishes a staged update (it is the staged
// Huginn.exe, or Huginn's own one handing a ready update to it) and must end now; Huginn was started again.
bool finishAtStart();
// At every start, after finishAtStart(): deletes update-staging (unless an update there is ready), what an update
// moved aside, and files from other folder layouts (everything at the top, or a launcher at the top with the app in lib\).
void cleanUp();
void tidyOldLayout();   // the folder-layout part of cleanUp(), kept for callers that run it later
// Round 17: the staged Huginn.exe started with --update-probe answers (exit code 0) only when it may write where the update
// must go: Windows' Controlled folder access allows apps by path, so an allowed Huginn.exe doesn't make the staged one
// allowed. install() asks it first and never hands over to a copy that can't write.
constexpr int kProbeOk = 0, kProbeBlocked = 3;
// Round 17: this copy is the staged update and can't go on (it can't save where it must): the update is given up, the
// reason kept for the next start, and Huginn is started again from its own folder, so a failed update never leaves
// nothing running. False when this copy isn't a staged update.
bool giveUpStaged(const QString &why);
// done: ok (the update is staged and ready), restarting (the staged copy was started; this one must close now) and what to say.
void install(const QString &zipPath, QObject *context, std::function<void(bool ok, bool restarting, const QString &message)> done);
}
