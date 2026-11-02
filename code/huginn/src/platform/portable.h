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
// Empty when the data folder can be written (it is made if missing) and has room; otherwise what to say on screen.
// Every build checks its own data folder (the Flatpak's too). Windows: a "data" folder beside Huginn.exe that another
// program keeps is never used; one that is Huginn's gets a mark (.huginn) the first time.
QString folderProblem();
// Windows: this Huginn was unzipped inside another Huginn's folder (Huginn\Huginn\Huginn.exe), so its data folder is new
// while the old one, with chats, is one or two folders up: that folder, to say so. Empty otherwise.
QString nestedCopy();
// Windows: the data folder is inside OneDrive. Its files are kept on this computer from now on (pinned), and the
// first time for each folder this returns what to say on screen (empty otherwise).
QString syncedWarning();
// Windows: the data folder is made readable and writable by this Windows account (and the system) only, once per
// folder. A drive without permissions (FAT32, exFAT) can't: noted in crash.log.
void makePrivate();
// The self-tests and the screenshot tour: a throwaway data folder in the temp folder, emptied first.
void isolate();
// A Huginn from before the data folder (it locks its AppData folder) is still open after waiting up to ms: false.
bool oldCopyClosed(int ms);
// First start of this version: Huginn's AppData folders are copied into data\.moving, the registry settings into the
// settings file, then it all goes into place and only then are the originals deleted (noted in crash.log). Marked as
// started first, so one that stops partway is started over next time. A folder with chats already still gets the
// registry settings it is missing. False when it failed: *why says so, and nothing was deleted.
bool moveOldData(QString *why);
// Erase everything: everything of Huginn's in the data folder goes (anything else someone keeps there stays); the
// settings file is emptied in place, and the lock this copy holds stays until it closes.
void eraseAll();

// The data folder for a Huginn.exe in exeDir: "data" beside it (beside the main copy for the staged update, which
// runs from update-staging).
QString rootFor(const QString &exeDir);
// Copies the files under from into to (a file to already has stays; crash.log is added to). skip: top-level names
// left out. False on the first file that can't be copied: what was copied is taken back and *why names it.
bool copyTree(const QString &from, const QString &to, const QStringList &skip, QString *why);
// Moves everything under from into to by renaming (the same disk): what to already has stays (crash.log is added to),
// folders in both are merged. False on the first that can't move (*why names it); what moved stays moved.
bool mergeInto(const QString &from, const QString &to, QString *why);
// --stability-selftest: the data folder's place, the copy and the erase.
bool selfTest();
}
