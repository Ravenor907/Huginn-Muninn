#pragma once

#include <QString>
#include <QStringList>

// Where Huginn keeps everything. The Windows portable build (Huginn-Windows.zip) keeps it all in a "data" folder
// beside Huginn.exe: the settings (an INI file, never the registry), the vault and chats, keys, tls, crash.log, the
// start log, the cache (update downloads, Qt's QML cache, a move's parts) and scratch files. Linux (the Flatpak) and
// other builds keep using Qt's usual folders. System::dataDir() and System::cacheDir() go through here.
namespace Portable
{
// First thing in main(), before QApplication: on the Windows portable build, Qt's settings (QSettings, as an INI file)
// and caches point at the data folder, and Qt keeps no shader cache of its own elsewhere.
void setUp();
// The Windows portable build.
bool on();
// Huginn's own data (the vault and chats, keys, tls, crash.log, the single-instance lock). AppDataLocation elsewhere.
QString dataDir();
// What can be made again (update downloads, Qt's QML cache, a move's parts). CacheLocation elsewhere.
QString cacheDir();
// Scratch files of this computer (a voice note being recorded). AppLocalDataLocation elsewhere.
QString localDir();
// Empty when the data folder can be written (it is made if missing); otherwise what to say on screen.
QString folderProblem();
// The self-tests and the screenshot tour: a throwaway data folder in the temp folder, emptied first.
void isolate();
// A Huginn from before the data folder (it locks its AppData folder) is still open after waiting up to ms: false.
bool oldCopyClosed(int ms);
// First start of this version: Huginn's AppData folders and registry settings are copied into the data folder, then
// the originals are deleted (noted in crash.log). False when the copy failed: *why says so, and nothing was deleted.
bool moveOldData(QString *why);
// Erase everything: every file in the data folder goes; the settings file is emptied in place, and the lock this
// copy holds stays until it closes.
void eraseAll();

// The data folder for a Huginn.exe in exeDir: "data" beside it (beside the main copy for the staged update, which
// runs from update-staging).
QString rootFor(const QString &exeDir);
// Copies the files under from into to (a file to already has stays; crash.log is added to). skip: top-level names
// left out. False on the first file that can't be copied: what was copied is taken back and *why names it.
bool copyTree(const QString &from, const QString &to, const QStringList &skip, QString *why);
// --stability-selftest: the data folder's place, the copy and the erase.
bool selfTest();
}
