#include "portable.h"
#include "crashlog.h"
#include "scratch.h"
#include "../settings.h"

#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QLockFile>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QTemporaryDir>
#include <cstdio>
#include <optional>

#ifdef Q_OS_WIN
#include <windows.h>
#include <aclapi.h>
#include <winioctl.h>
#include <sddl.h>
#include <cstdlib>
#include <string>
#include <vector>
#endif

namespace
{
// The data folder (the Windows portable build only; empty elsewhere).
QString s_root;
// Set when Huginn.exe's own folder couldn't be found.
QString s_problem;

// What a running copy holds beside its data: never copied, never erased while it runs.
const QStringList kInUse = {QStringLiteral("instance.lock"), QStringLiteral("instance.closing"), QStringLiteral("instance.end")};

// In the data folder once Huginn has made it or taken it as its own. A "data" folder beside Huginn.exe that holds
// something else and no mark is never used (or erased): it belongs to another program.
const QString kMark = QStringLiteral(".huginn");
// Where a move from AppData is put together before it goes into place (portable/moveStarted until it has).
const QString kMoving = QStringLiteral(".moving");

// Round 19 (FIX 16, m8 Windows): also Huginn's, erased with the rest, but too plain a name to tell a folder is Huginn's by
// it: Tor's folder, the voices, the wrong-try count, an erase's note of what's left, and the temporary copies a save cut
// off by a crash leaves beside a file ("ring-key.json.XXXXXX").
bool erasedToo(const QString &name)
{
    static const QStringList kNames = {QStringLiteral("tor"), QStringLiteral("voices"), QStringLiteral("lock-state.json"), QStringLiteral("erase-pending")};
    static const QStringList kSaving = {QStringLiteral("lock-state.json."), QStringLiteral("ring-key.json."), QStringLiteral("nudge-key.json."),
                                        QStringLiteral("ring-allow.json."), QStringLiteral("erase-pending.")};
    if (kNames.contains(name, Qt::CaseInsensitive))
        return true;
    for (const QString &p : kSaving)
        if (name.startsWith(p, Qt::CaseInsensitive))
            return true;
    return false;
}

// Everything Huginn ever keeps at the top of its data folder. Erase everything removes only these, never anything
// else someone keeps there (and never the program: only names inside the data folder).
bool huginnsOwn(const QString &name)
{
    static const QStringList kNames = {
        QStringLiteral("Huginn"), QStringLiteral("chat"), QStringLiteral("chat.restore"), QStringLiteral("chat.old"), QStringLiteral("chat.old.replaced"), QStringLiteral("cache"),
        QStringLiteral("scratch"), QStringLiteral("tls"), QStringLiteral("crash.log"), QStringLiteral("running.mark"),
        QStringLiteral("Huginn-start.log"), QStringLiteral("ring-key.json"), QStringLiteral("nudge-key.json"), QStringLiteral("ring-allow.json"), QStringLiteral("QtProject.ini"),
        QStringLiteral(".huginn-write-test"), kMark, kMoving, QStringLiteral("instance.lock"), QStringLiteral("instance.closing"),
        QStringLiteral("instance.end")};
    return kNames.contains(name, Qt::CaseInsensitive) || name.startsWith(QLatin1String("chat-kept-"), Qt::CaseInsensitive)
           || name.startsWith(QLatin1String(".huginn-write-test-"), Qt::CaseInsensitive)
           // Round 17: a sync app's conflict copies of the single-instance lock ("instance-PC2.lock").
           || (name.startsWith(QLatin1String("instance"), Qt::CaseInsensitive) && name.contains(QLatin1String(".lock"), Qt::CaseInsensitive));
}

// A file anywhere under dir.
[[maybe_unused]] bool holdsFiles(const QString &dir)
{
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
    return it.hasNext();
}

#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
// An older version's settings are in the registry (HKCU\Software\<org>\Huginn). Asked of the keys above it, which
// are there already, so nothing is made by asking.
bool oldSettingsThere()
{
    if (!QSettings(QStringLiteral("HKEY_CURRENT_USER\\Software"), QSettings::NativeFormat).childGroups().contains(Settings::org(), Qt::CaseInsensitive))
        return false;
    return QSettings(QStringLiteral("HKEY_CURRENT_USER\\Software\\") + Settings::org(), QSettings::NativeFormat)
        .childGroups()
        .contains(QStringLiteral("Huginn"), Qt::CaseInsensitive);
}
#endif

#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
QString exeDir()
{
    std::wstring self(32768, L'\0');
    const DWORD n = GetModuleFileNameW(nullptr, self.data(), DWORD(self.size()));
    if (n == 0 || n >= self.size())
        return {};
    self.resize(n);
    return QFileInfo(QDir::fromNativeSeparators(QString::fromStdWString(self))).absolutePath();
}
#endif

// Qt's own settings and caches into root: the default format is an INI file there (Settings::store() and everything
// else open it with the default format), and the QML cache goes under its cache folder.
void pointQtAt(const QString &root)
{
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, root);
#ifdef Q_OS_WIN
    // Windows' own file pickers keep recent folders in the registry, so Huginn uses Qt's picker instead.
    QCoreApplication::setAttribute(Qt::AA_DontUseNativeDialogs);
    const std::wstring cache = QDir::toNativeSeparators(root + QStringLiteral("/cache/qmlcache")).toStdWString();
    _wputenv_s(L"QML_DISK_CACHE_PATH", cache.c_str());
#endif
}

bool writeAll(const QString &path, const QByteArray &bytes)
{
    QSaveFile f(path);
    return f.open(QIODevice::WriteOnly) && f.write(bytes) == bytes.size() && f.commit();
}

QByteArray readAll(const QString &path)
{
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray();
}

// Empties root of Huginn's own things: every file and folder of Huginn's goes (anything else someone keeps there
// stays), but the lock files of a running copy and settingsFile, which is kept (it was emptied through QSettings,
// which other open QSettings share). Below the top, the settings file's own folder is Huginn's, so all of it goes.
void eraseIn(const QString &root, const QString &settingsFile, bool top = true)
{
    if (root.isEmpty())
        return;
    const QString keep = QDir::cleanPath(settingsFile);
    const QFileInfoList all = QDir(root).entryInfoList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
    for (const QFileInfo &fi : all) {
        const QString path = QDir::cleanPath(fi.absoluteFilePath());
        if (kInUse.contains(fi.fileName(), Qt::CaseInsensitive) || path == keep || fi.fileName() == kMark)
            continue;
        if (top && !huginnsOwn(fi.fileName()) && !erasedToo(fi.fileName()))
            continue;   // not Huginn's: left as it is
        if (fi.isDir() && !fi.isSymLink()) {
            if (!keep.isEmpty() && keep.startsWith(path + QLatin1Char('/'))) {
                // The settings file's own folder: everything else in it goes.
                eraseIn(path, keep, false);
                continue;
            }
            QDir(path).removeRecursively();
        } else {
            QFile::remove(path);
        }
    }
}
}

namespace Portable
{
QString rootFor(const QString &exeDir)
{
    QString dir = exeDir;
    dir.replace(QLatin1Char('\\'), QLatin1Char('/'));   // Windows paths, also when tested elsewhere
    dir = QDir::cleanPath(dir);
    // The staged update runs from update-staging (platform/winupdate.cpp): its data is the main copy's.
    const qsizetype slash = dir.lastIndexOf(QLatin1Char('/'));
    if (slash > 0 && dir.mid(slash + 1).compare(QLatin1String("update-staging"), Qt::CaseInsensitive) == 0)
        dir = dir.left(slash);
    return dir + QStringLiteral("/data");
}

void setUp()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    const QString dir = exeDir();
    if (dir.isEmpty()) {
        // Nowhere of its own: said on screen at start (main.cpp), and nothing goes to AppData meanwhile.
        s_problem = QStringLiteral("Huginn couldn't find its own folder. Unzip Huginn into a folder of your own that isn't synced to the cloud, "
                                   "like C:\\Users\\<you>\\Huginn, and open it again.");
        s_root = QDir::tempPath() + QStringLiteral("/Huginn-data");
    } else {
        s_root = rootFor(dir);
    }
    pointQtAt(s_root);
    // Qt's compiled shaders and pipelines would otherwise be kept in AppData.
    QCoreApplication::setAttribute(Qt::AA_DisableShaderDiskCache);
#endif
}

bool on()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    return true;
#else
    return false;
#endif
}

QString dataDir()
{
    return !s_root.isEmpty() ? s_root : QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
}

QString cacheDir()
{
    return !s_root.isEmpty() ? s_root + QStringLiteral("/cache") : QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
}

QString localDir()
{
    return !s_root.isEmpty() ? s_root : QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
}

#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
namespace
{
// This process's Windows account, as a SID string ("" when it can't be read).
std::wstring mySid()
{
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
        return {};
    DWORD n = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &n);
    std::vector<BYTE> buf(n ? n : 1);
    const bool got = n > 0 && GetTokenInformation(token, TokenUser, buf.data(), n, &n);
    CloseHandle(token);
    if (!got)
        return {};
    LPWSTR sid = nullptr;
    if (!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER *>(buf.data())->User.Sid, &sid))
        return {};
    std::wstring out(sid);
    LocalFree(sid);
    return out;
}

// Who owns a folder: the SID string, and the account's name when this computer knows it ("" when it doesn't, as for an
// account of another computer or of an earlier Windows). False when the owner can't be read.
bool ownerOf(const QString &dir, std::wstring *sidText, QString *name)
{
    PSID owner = nullptr;
    PSECURITY_DESCRIPTOR sd = nullptr;
    std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    if (GetNamedSecurityInfoW(path.data(), SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION, &owner, nullptr, nullptr, nullptr, &sd) != ERROR_SUCCESS
        || !owner) {
        if (sd)
            LocalFree(sd);
        return false;
    }
    LPWSTR text = nullptr;
    if (ConvertSidToStringSidW(owner, &text)) {
        *sidText = text;
        LocalFree(text);
    }
    wchar_t user[256], domain[256];
    DWORD un = 256, dn = 256;
    SID_NAME_USE use;
    if (LookupAccountSidW(nullptr, owner, user, &un, domain, &dn, &use))
        *name = QString::fromWCharArray(user, int(un));
    LocalFree(sd);
    return !sidText->empty();
}

// An account's own SID (a local or domain account, S-1-5-21-..., or a Microsoft Entra one, S-1-12-1-...), not
// Administrators (S-1-5-32-544), the system (S-1-5-18) or TrustedInstaller (S-1-5-80-...).
bool personSid(const std::wstring &sid)
{
    return sid.rfind(L"S-1-5-21-", 0) == 0 || sid.rfind(L"S-1-12-1-", 0) == 0;
}

// Inside Program Files (either one), where Windows lets only administrators save.
bool inProgramFiles(const QString &dir)
{
    const QString d = QDir::cleanPath(QDir::fromNativeSeparators(dir)) + QLatin1Char('/');
    for (const char *var : {"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
        const QString pf = QDir::cleanPath(QDir::fromNativeSeparators(qEnvironmentVariable(var)));
        if (!pf.isEmpty() && d.startsWith(pf + QLatin1Char('/'), Qt::CaseInsensitive))
            return true;
    }
    return false;
}

// Writes a little more than one byte into dir and flushes it (a full disk shows here too). On failure *err is
// Windows' own error, which tells a full disk from a folder this account may not open.
bool probeWrite(const QString &dir, DWORD *err)
{
    *err = 0;
    const std::wstring folder = QDir::toNativeSeparators(dir).toStdWString();
    if (!QDir().mkpath(dir)) {
        // Asked again directly, for the reason.
        if (!CreateDirectoryW(folder.c_str(), nullptr)) {
            *err = GetLastError();
            if (*err != ERROR_ALREADY_EXISTS)
                return false;
        }
        *err = 0;
    }
    // Round 17 (m1): a name of this process's own, so two starts at once (a double click on a slow drive) never collide.
    const std::wstring file = folder + L"\\.huginn-write-test-" + std::to_wstring(GetCurrentProcessId());
    HANDLE h = INVALID_HANDLE_VALUE;
    for (int attempt = 0; attempt < 4; ++attempt) {
        h = CreateFileW(file.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr);
        if (h != INVALID_HANDLE_VALUE)
            break;
        *err = GetLastError();
        // Held a moment by another program (antivirus scanning the file): tried again shortly.
        if (*err != ERROR_SHARING_VIOLATION)
            return false;
        Sleep(200);
    }
    if (h == INVALID_HANDLE_VALUE)
        return false;
    *err = 0;
    std::vector<char> block(4096, 'x');
    DWORD wrote = 0;
    bool ok = WriteFile(h, block.data(), DWORD(block.size()), &wrote, nullptr) && wrote == block.size();
    if (ok)
        ok = FlushFileBuffers(h) != 0;
    if (!ok)
        *err = GetLastError();
    CloseHandle(h);
    return ok;
}
}
#endif

// What to say when the data folder can't be written. err: Windows' error (0 elsewhere or when not known); full:
// the disk has less than 1 MB free.
QString cantWriteText(const QString &root, unsigned long err, bool full)
{
    const QString where = QDir::toNativeSeparators(root);
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    const QString folder = QDir::toNativeSeparators(QFileInfo(root).absolutePath());
    const bool diskFull = full || err == ERROR_DISK_FULL || err == ERROR_HANDLE_DISK_FULL;
    if (diskFull)
        return QStringLiteral("The disk Huginn's folder is on (%1) is full, so Huginn can't save anything. Free up some space on it, then open "
                              "Huginn again.")
            .arg(folder);
    if (err == ERROR_WRITE_PROTECT)
        return QStringLiteral("Huginn's folder (%1) is on a drive that is write-protected, so Huginn can't save anything. Turn off the "
                              "drive's write protection, or move the Huginn folder to a folder of your own, like C:\\Users\\<you>\\Huginn, and "
                              "open it again.")
            .arg(folder);
    if (err == ERROR_ACCESS_DENIED) {
        const QString ownFolder = QStringLiteral("Move the whole Huginn folder to a folder of your own, like C:\\Users\\<you>\\Huginn, and "
                                                 "open Huginn.exe from there (not with Run as administrator).");
        // Round 16: Program Files, where only administrators may save (whoever owns a "data" made there by an
        // administrator's run).
        if (inProgramFiles(root))
            return QStringLiteral("Huginn is in Program Files (%1), where Windows lets only administrators save, so Huginn can't keep "
                                  "your chats there. %2")
                .arg(folder, ownFolder);
        // No data folder, and none can be made: the folder Huginn is in doesn't let this account save.
        if (!QFileInfo(root).isDir())
            return QStringLiteral("Windows won't let Huginn make its data folder in %1: this account can't save in that folder, or a "
                                  "security program (like Controlled folder access in Windows Security) is blocking it. %2")
                .arg(folder, ownFolder);
        // The data folder is private to the account that set it up (makePrivate): whose is it?
        std::wstring sid;
        QString name;
        const bool known = ownerOf(root, &sid, &name);
        const std::wstring me = mySid();
        // Made by an administrator's run (Run as administrator) or by Windows itself, not by an account.
        if (known && !personSid(sid))
            return QStringLiteral("Huginn's data folder (%1) was made when Huginn ran as an administrator, so this account can't save in "
                                  "it. %2 Or open the \"data\" folder in File Explorer, choose Continue when it asks for permission, then "
                                  "open Huginn again.")
                .arg(where, ownFolder);
        // This account's own folder (Huginn already put its permissions right, folderProblem): something else blocks it.
        if (known && !me.empty() && sid == me)
            return QStringLiteral("Windows won't let this account save in Huginn's data folder (%1), though the folder is this "
                                  "account's own. Controlled folder access in Windows Security, or another security program, is "
                                  "probably blocking Huginn.\n\nBest: move the whole Huginn folder out of Documents, Pictures and Desktop "
                                  "to a folder like C:\\Users\\<you>\\Huginn, and open it from there. Updates then just work too.\n\n"
                                  "Or allow Huginn.exe there (Windows Security › Virus & threat protection › Manage ransomware protection › "
                                  "Allow an app through Controlled folder access). Each update then needs its new copy allowed as well "
                                  "(update-staging\\Huginn.exe in Huginn's folder); Huginn says so when one comes.")
                .arg(where);
        // Round 16: a folder made private by 1.36's first beta has no administrators in its list, so File Explorer's
        // Continue can't open it. Taking ownership always works; then Huginn puts its permissions right by itself.
        const QString recover = QStringLiteral("If these chats are yours from another computer or from before Windows was installed again: "
                                               "right-click the \"data\" folder › Properties › Security › Advanced. Next to Owner choose "
                                               "Change, type your Windows account name, choose Check Names, then OK. Tick \"Replace owner on "
                                               "subcontainers and objects\", choose OK (it needs an administrator's OK), then OK again, and "
                                               "open Huginn: it gives the folder the right permissions itself.");
        if (known && !me.empty() && sid != me && !name.isEmpty())
            return QStringLiteral("Huginn's data folder (%1) belongs to the Windows account \"%2\" on this computer, and only that account "
                                  "can open it. Sign in as %2 to use these chats, or give this account its own copy of Huginn: unzip "
                                  "it into a folder of your own, like C:\\Users\\<you>\\Huginn.")
                .arg(where, name);
        if (known && !me.empty() && sid != me)
            return QStringLiteral("Huginn's data folder (%1) was set up by a Windows account this computer doesn't know: on another "
                                  "computer, or before Windows was installed again. Only that account can open it.\n\n%2")
                .arg(where, recover);
        return QStringLiteral("Windows won't let this account into Huginn's data folder (%1). It's private to the Windows account that "
                              "set it up. If that's another account on this computer, sign in to it, or give this account its own "
                              "copy of Huginn in a folder of its own.\n\n%2")
            .arg(where, recover);
    }
    if (!s_root.isEmpty())
        return QStringLiteral("Huginn can't save anything in its folder (%1)%2. Check that the drive is still connected and isn't "
                              "read-only, or move the Huginn folder to a folder of your own that isn't synced to the cloud, like "
                              "C:\\Users\\<you>\\Huginn, and open it again.")
            .arg(folder, err ? QStringLiteral(" (Windows error %1)").arg(err) : QString());
#else
    Q_UNUSED(err);
#endif
    if (full)
        return QStringLiteral("The disk Huginn's data folder is on (%1) is full, so Huginn can't save anything. Free up some space, then "
                              "open Huginn again.")
            .arg(where);
    return QStringLiteral("Huginn can't save in its data folder (%1). Check the folder's permissions and that the disk isn't read-only or "
                          "full, then open Huginn again.")
        .arg(where);
}

#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
namespace
{
bool onlyThisAccount(const QString &dir, DWORD *err);
bool inheritAgain(const QString &dir, DWORD *err);
bool movableDrive(const QString &dir);

// Round 16: a data folder this account owns but can't save in (made private by 1.36's first beta under an account
// that is gone, then taken over with Change owner, or a list that lost this account): an owner may always set the
// folder's permissions, so Huginn puts them right (as makePrivate would, or the drive's own on a removable drive) and
// tries again. False, with Windows' error, when it still can't save (Controlled folder access, say).
bool repairOwn(const QString &root, DWORD *err)
{
    std::wstring sid;
    QString name;
    const std::wstring me = mySid();
    if (!QFileInfo(root).isDir() || !ownerOf(root, &sid, &name) || me.empty() || sid != me)
        return false;
    DWORD fix = 0;
    const bool movable = movableDrive(root);
    const bool fixed = movable ? inheritAgain(root, &fix) : onlyThisAccount(root, &fix);
    CrashLog::note(fixed ? QStringLiteral("data: this account owns the data folder but couldn't save in it: its permissions were put right")
                         : QStringLiteral("data: this account owns the data folder but couldn't save in it, and its permissions couldn't be "
                                          "put right (error %1)").arg(fix));
    return fixed && probeWrite(root, err);
}
}
#endif

QString folderProblem()
{
    if (!s_problem.isEmpty())
        return s_problem;
    // The Windows portable build's folder beside Huginn.exe, or Qt's usual data folder elsewhere (the Flatpak's).
    const QString root = !s_root.isEmpty() ? s_root : dataDir();
    if (root.isEmpty())
        return {};
    // A "data" folder beside Huginn.exe that another program keeps (it holds things and none of them are Huginn's):
    // never used, and so never erased.
    if (!s_root.isEmpty() && QFileInfo(s_root).isDir() && !QFileInfo::exists(s_root + QLatin1Char('/') + kMark)) {
        const QStringList there = QDir(s_root).entryList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
        bool ours = false;
        for (const QString &n : there)
            ours = ours || huginnsOwn(n);
        if (!there.isEmpty() && !ours)
            return QStringLiteral("The folder beside Huginn.exe already has a \"data\" folder that belongs to another program (%1). "
                                  "Put Huginn in a folder of its own, like C:\\Users\\<you>\\Huginn, and open it again.")
                .arg(QDir::toNativeSeparators(s_root));
    }
    // Qt doesn't read Windows permissions by default, so just try writing: a little more than one byte, flushed, so
    // a full disk shows here too. Windows says why it failed: a full disk, a write-protected drive, or a folder that is
    // private to another account each get their own words.
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    DWORD err = 0;
    bool wrote = probeWrite(root, &err);
    if (!wrote && err == ERROR_ACCESS_DENIED)
        wrote = repairOwn(root, &err);
#else
    const unsigned long err = 0;
    QFile probe(root + QStringLiteral("/.huginn-write-test-") + QString::number(QCoreApplication::applicationPid()));
    const QByteArray block(4096, 'x');
    const bool wrote = QDir().mkpath(root) && probe.open(QIODevice::WriteOnly) && probe.write(block) == block.size() && probe.flush();
    probe.close();
    probe.remove();
#endif
    const QStorageInfo disk(root);
    const bool full = disk.isValid() && disk.bytesAvailable() >= 0 && disk.bytesAvailable() < 1024 * 1024;
    if (!wrote || full)
        return cantWriteText(root, err, full);
    // Taken as Huginn's own from now on.
    if (!s_root.isEmpty() && !QFileInfo::exists(s_root + QLatin1Char('/') + kMark)) {
        QFile mark(s_root + QLatin1Char('/') + kMark);
        if (mark.open(QIODevice::WriteOnly))
            mark.write("Huginn's data folder. Everything Huginn keeps is in here.\n");
    }
    return {};
}

bool canWriteForUpdate()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    if (s_root.isEmpty() || !s_problem.isEmpty())
        return false;
    // The data folder (beside the main copy) and the main copy's own folder, where the update's files go.
    DWORD err = 0;
    return probeWrite(s_root, &err) && probeWrite(QFileInfo(s_root).absolutePath(), &err);
#else
    return true;
#endif
}

bool controlledFolder(const QString &path)
{
#ifdef Q_OS_WIN
    // Qt reads Windows' own known folders, so ones moved elsewhere (into OneDrive, say) are found too.
    const QString p = QDir::cleanPath(QDir::fromNativeSeparators(path)) + QLatin1Char('/');
    for (const QStandardPaths::StandardLocation where : {QStandardPaths::DocumentsLocation, QStandardPaths::DesktopLocation, QStandardPaths::PicturesLocation,
                                                         QStandardPaths::MusicLocation, QStandardPaths::MoviesLocation}) {
        for (const QString &dir : QStandardPaths::standardLocations(where)) {
            const QString d = QDir::cleanPath(QDir::fromNativeSeparators(dir));
            if (!d.isEmpty() && p.startsWith(d + QLatin1Char('/'), Qt::CaseInsensitive))
                return true;
        }
    }
    return false;
#else
    Q_UNUSED(path);
    return false;
#endif
}

QString nestedCopy()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    // Only a data folder that is new (nothing of Huginn's in it yet).
    if (s_root.isEmpty() || QFileInfo::exists(s_root + QStringLiteral("/chat")) || QFileInfo::exists(s_root + QStringLiteral("/Huginn")))
        return {};
    // Huginn\Huginn\Huginn.exe (unzipped into the old Huginn folder): the old data is one or two folders up.
    QDir up(QFileInfo(s_root).absolutePath());
    for (int i = 0; i < 2 && up.cdUp(); ++i) {
        const QString other = up.absolutePath() + QStringLiteral("/data");
        if (QFileInfo(other + QStringLiteral("/chat")).isDir() && holdsFiles(other + QStringLiteral("/chat")))
            return QDir::toNativeSeparators(up.absolutePath());
    }
#endif
    return {};
}

#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
namespace
{
#ifndef FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS
#define FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS 0x00400000
#endif
#ifndef FILE_ATTRIBUTE_RECALL_ON_OPEN
#define FILE_ATTRIBUTE_RECALL_ON_OPEN 0x00040000
#endif
#ifndef FILE_ATTRIBUTE_PINNED
#define FILE_ATTRIBUTE_PINNED 0x00080000
#endif
#ifndef FILE_ATTRIBUTE_UNPINNED
#define FILE_ATTRIBUTE_UNPINNED 0x00100000
#endif

// The OneDrive folder the data folder is in ("" when none): by OneDrive's own variables, or by a file that is only
// in the cloud until it's opened.
QString syncedBy(const QString &root)
{
    const QString here = QDir::cleanPath(root);
    for (const wchar_t *name : {L"OneDrive", L"OneDriveConsumer", L"OneDriveCommercial"}) {
        const wchar_t *v = _wgetenv(name);
        if (!v || !*v)
            continue;
        const QString base = QDir::cleanPath(QDir::fromNativeSeparators(QString::fromWCharArray(v)));
        if (!base.isEmpty() && (here.compare(base, Qt::CaseInsensitive) == 0 || here.startsWith(base + QLatin1Char('/'), Qt::CaseInsensitive)))
            return base;
    }
    const DWORD cloudOnly = FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS | FILE_ATTRIBUTE_RECALL_ON_OPEN | FILE_ATTRIBUTE_OFFLINE;
    for (const QString &p : {here, QFileInfo(here).absolutePath(), here + QStringLiteral("/chat")}) {
        const DWORD a = GetFileAttributesW(reinterpret_cast<const wchar_t *>(QDir::toNativeSeparators(p).utf16()));
        if (a != INVALID_FILE_ATTRIBUTES && (a & (cloudOnly | FILE_ATTRIBUTE_PINNED | FILE_ATTRIBUTE_UNPINNED)))
            return QFileInfo(here).absolutePath();
    }
    return {};
}

// Only this Windows account, the system itself and administrators may open the data folder and everything in it: a
// protected list, nothing taken from the folder above (which may let every account in, like C:\ or a USB stick).
// Administrators are in so the folder can always be got back (File Explorer's Continue, after a Windows reinstall);
// other standard accounts are kept out. *err: Windows' error when it fails.
bool onlyThisAccount(const QString &dir, DWORD *err)
{
    *err = 0;
    const std::wstring sid = mySid();
    if (sid.empty()) {
        *err = GetLastError();
        return false;
    }
    const std::wstring sddl = L"D:P(A;OICI;FA;;;" + sid + L")(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)";
    PSECURITY_DESCRIPTOR sd = nullptr;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr)) {
        *err = GetLastError();
        return false;
    }
    BOOL present = FALSE, defaulted = FALSE;
    PACL dacl = nullptr;
    bool ok = GetSecurityDescriptorDacl(sd, &present, &dacl, &defaulted) && present && dacl;
    std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    if (ok) {
        const DWORD r = SetNamedSecurityInfoW(path.data(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, nullptr,
                                              nullptr, dacl, nullptr);
        ok = r == ERROR_SUCCESS;
        *err = r;
    }
    LocalFree(sd);
    return ok;
}

// Round 17 (m2): a folder made by "Run as administrator" (or by an administrator with UAC off) is owned by the
// Administrators group, not by an account. When this account may change the owner (full control through the folder
// above, as in its own user folder), it becomes the owner, so the private list below can be set. False when it can't.
bool takeOwnership(const QString &dir)
{
    const std::wstring me = mySid();
    PSID sid = nullptr;
    if (me.empty() || !ConvertStringSidToSidW(me.c_str(), &sid))
        return false;
    std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    const DWORD r = SetNamedSecurityInfoW(path.data(), SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION, sid, nullptr, nullptr, nullptr);
    LocalFree(sid);
    CrashLog::note(r == ERROR_SUCCESS ? QStringLiteral("data: the data folder an administrator's run made is this account's now")
                                      : QStringLiteral("data: couldn't take the data folder an administrator's run made (error %1)").arg(r));
    return r == ERROR_SUCCESS;
}

// The folder's own list is emptied and it takes its permissions from the folder above again (undoes onlyThisAccount).
bool inheritAgain(const QString &dir, DWORD *err)
{
    ACL empty;
    if (!InitializeAcl(&empty, sizeof(empty), ACL_REVISION)) {
        *err = GetLastError();
        return false;
    }
    std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    *err = SetNamedSecurityInfoW(path.data(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | UNPROTECTED_DACL_SECURITY_INFORMATION, nullptr, nullptr,
                                 &empty, nullptr);
    return *err == ERROR_SUCCESS;
}

// A drive that moves between computers (a USB stick or USB disk, a memory card): its folder is never made private to
// this computer's account, because on the next computer (or after a Windows reinstall) nobody could open it.
bool movableDrive(const QString &dir)
{
    wchar_t vol[MAX_PATH + 1] = {0};
    const std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    if (!GetVolumePathNameW(path.c_str(), vol, MAX_PATH))
        return false;
    const UINT type = GetDriveTypeW(vol);
    if (type == DRIVE_REMOVABLE || type == DRIVE_REMOTE || type == DRIVE_CDROM)
        return true;
    // A USB disk says it is fixed: its bus tells. "C:\" becomes "\\.\C:".
    std::wstring dev(vol);
    if (dev.size() < 2 || dev[1] != L':')
        return false;
    dev = L"\\\\.\\" + dev.substr(0, 2);
    HANDLE h = CreateFileW(dev.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE)
        return false;
    STORAGE_PROPERTY_QUERY q = {};
    q.PropertyId = StorageDeviceProperty;
    q.QueryType = PropertyStandardQuery;
    STORAGE_DEVICE_DESCRIPTOR desc = {};
    DWORD got = 0;
    const bool asked = DeviceIoControl(h, IOCTL_STORAGE_QUERY_PROPERTY, &q, sizeof(q), &desc, sizeof(desc), &got, nullptr) != 0;
    CloseHandle(h);
    return asked && (desc.BusType == BusTypeUsb || desc.BusType == BusTypeSd || desc.BusType == BusTypeMmc || desc.RemovableMedia);
}
}
#endif

QString syncedWarning()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    if (s_root.isEmpty())
        return {};
    const QString by = syncedBy(s_root);
    if (by.isEmpty())
        return {};
    // Kept on this computer at all times (OneDrive's "Always keep on this device"), so no file is ever only in the
    // cloud when Huginn needs it.
    const std::wstring path = QDir::toNativeSeparators(s_root).toStdWString();
    const DWORD a = GetFileAttributesW(path.c_str());
    if (a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_PINNED))
        SetFileAttributesW(path.c_str(), (a | FILE_ATTRIBUTE_PINNED) & ~DWORD(FILE_ATTRIBUTE_UNPINNED));
    // Said once for each folder.
    QSettings s = Settings::store();
    if (s.value(QStringLiteral("portable/syncWarned")).toString() == s_root)
        return {};
    s.setValue(QStringLiteral("portable/syncWarned"), s_root);
    s.sync();
    CrashLog::note(QStringLiteral("data: the data folder is in a synced folder"));
    return QStringLiteral("Huginn's folder is in a folder synced to the cloud, like OneDrive (%1), so your chats and keys are copied to the cloud, and opening the same "
                          "folder on two computers would break your chats.\n\nClose Huginn and move its whole folder somewhere that isn't "
                          "synced, like C:\\Users\\<you>\\Huginn, then open it from there. Until then Huginn keeps its files on this "
                          "computer, and a lock (Settings › Privacy and lock) keeps them sealed.")
        .arg(QDir::toNativeSeparators(by));
#else
    return {};
#endif
}

QString makePrivate()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    if (s_root.isEmpty() || !QFileInfo(s_root).isDir())
        return {};
    QSettings s = Settings::store();
    // "private2": done for this folder already (with administrators in). A folder made private by 1.36's first beta
    // is done again, so an administrator can always get it back.
    if (s.value(QStringLiteral("portable/private2")).toString() == s_root)
        return {};
    // Never anything that could keep someone out of their own chats: a drive that goes from computer to computer,
    // or a folder another Windows account owns (it may be using it too). Huginn keeps working; a lock seals the files.
    QString why;
    std::wstring owner;
    QString ownerName;
    const std::wstring me = mySid();
    if (movableDrive(s_root)) {
        why = QStringLiteral("a removable or network drive");
        // 1.36's first beta made it private anyway: it takes its permissions from the drive again, so the next
        // computer can open it.
        if (s.value(QStringLiteral("portable/private")).toString() == s_root) {
            DWORD err = 0;
            if (inheritAgain(s_root, &err)) {
                s.remove(QStringLiteral("portable/private"));
                s.sync();
                CrashLog::note(QStringLiteral("data: the data folder on a removable drive is open to its drive's permissions again"));
            } else {
                CrashLog::note(QStringLiteral("data: couldn't give the data folder its drive's permissions back (error %1)").arg(err));
            }
        }
    } else if (ownerOf(s_root, &owner, &ownerName) && !me.empty() && owner != me && !(owner == L"S-1-5-32-544" && takeOwnership(s_root))) {
        why = QStringLiteral("owned by another account");
    } else {
        DWORD err = 0;
        if (onlyThisAccount(s_root, &err)) {
            s.setValue(QStringLiteral("portable/private2"), s_root);
            s.sync();
            CrashLog::note(QStringLiteral("data: the data folder is readable by this account (and administrators) only"));
            return {};
        }
        // A drive without permissions (a USB stick formatted FAT32 or exFAT).
        why = QStringLiteral("error %1").arg(err);
    }
    CrashLog::note(QStringLiteral("data: the data folder stays open to other accounts (%1)").arg(why));
    // Said once for each folder (and only when no lock is set: main.cpp asks).
    if (s.value(QStringLiteral("portable/openWarned")).toString() == s_root)
        return {};
    return QStringLiteral("Other people who use this computer, or anyone with this drive, can copy Huginn's folder (%1), "
                          "because Windows can't keep it private to your account here (a USB or network drive, a drive formatted "
                          "FAT32 or exFAT, a folder another account set up, or one made while Huginn ran as an administrator)."
                          "\n\nSet a lock in Settings › Privacy and lock: your chats and keys are then sealed with it, wherever the "
                          "folder goes.")
        .arg(QDir::toNativeSeparators(QFileInfo(s_root).absolutePath()));
#else
    return {};
#endif
}

void openWarned()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    if (s_root.isEmpty())
        return;
    QSettings s = Settings::store();
    s.setValue(QStringLiteral("portable/openWarned"), s_root);
    s.sync();
#endif
}

void isolate()
{
    if (s_root.isEmpty())
        return;
    s_problem.clear();
    s_root = QDir::tempPath() + QStringLiteral("/HuginnSelfTest-data");
    QDir(s_root).removeRecursively();
    QDir().mkpath(s_root);
    pointQtAt(s_root);
}

bool oldCopyClosed(int ms)
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    const QString old = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    const QString path = old + QStringLiteral("/instance.lock");
    if (old.isEmpty() || !QFileInfo::exists(path))
        return true;
    // Held only while that copy runs (platform/singleinstance.cpp); a crashed copy's lock is taken over.
    QLockFile lock(path);
    lock.setStaleLockTime(0);
    if (!lock.tryLock(ms))
        return false;
    lock.unlock();
    return true;
#else
    Q_UNUSED(ms);
    return true;
#endif
}

bool copyTree(const QString &from, const QString &to, const QStringList &skip, QString *why)
{
    QStringList made;
    auto fail = [&](const QString &rel) {
        for (const QString &m : std::as_const(made))
            QFile::remove(m);
        if (why)
            *why = rel;
        return false;
    };
    const QDir base(from);
    QDirIterator it(from, QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString src = it.next();
        const QString rel = base.relativeFilePath(src);
        const QString top = rel.section(QLatin1Char('/'), 0, 0);
        if (skip.contains(top, Qt::CaseInsensitive) || kInUse.contains(rel, Qt::CaseInsensitive) || rel.startsWith(QLatin1String("..")))
            continue;
        const QString dst = to + QLatin1Char('/') + rel;
        if (!QDir().mkpath(QFileInfo(dst).absolutePath()))
            return fail(rel);
        if (QFileInfo::exists(dst)) {
            // The log keeps both, the older lines first; anything else already here stays as it is.
            if (rel.compare(QLatin1String("crash.log"), Qt::CaseInsensitive) == 0 && !writeAll(dst, readAll(src) + readAll(dst)))
                return fail(rel);
            continue;
        }
        if (!QFile::copy(src, dst) || QFileInfo(dst).size() != QFileInfo(src).size()) {
            QFile::remove(dst);
            return fail(rel);
        }
        made << dst;
    }
    return true;
}

bool mergeInto(const QString &from, const QString &to, QString *why)
{
    const QFileInfoList all = QDir(from).entryInfoList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
    for (const QFileInfo &fi : all) {
        const QString dst = to + QLatin1Char('/') + fi.fileName();
        const QFileInfo there(dst);
        if (fi.isDir() && !fi.isSymLink()) {
            if (!there.exists()) {
                if (!QDir().rename(fi.absoluteFilePath(), dst)) {
                    if (why)
                        *why = fi.fileName();
                    return false;
                }
            } else if (there.isDir()) {
                if (!mergeInto(fi.absoluteFilePath(), dst, why))
                    return false;
            }
            continue;
        }
        if (!there.exists()) {
            if (!QFile::rename(fi.absoluteFilePath(), dst)) {
                if (why)
                    *why = fi.fileName();
                return false;
            }
        } else if (fi.fileName().compare(QLatin1String("crash.log"), Qt::CaseInsensitive) == 0) {
            // The log keeps both, the older lines first.
            if (!writeAll(dst, readAll(fi.absoluteFilePath()) + readAll(dst))) {
                if (why)
                    *why = fi.fileName();
                return false;
            }
        }
        // Anything else already in place stays as it is (put there by a move that stopped partway, from the same files).
    }
    return true;
}

bool moveOldData(QString *why)
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    const QString root = s_root;
    const QString roam = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    const QString local = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    const bool hasRoam = !roam.isEmpty() && QFileInfo(roam).isDir();
    const bool hasLocal = !local.isEmpty() && QFileInfo(local).isDir() && QDir::cleanPath(local) != QDir::cleanPath(roam);
    // The older settings in the registry. Opening HKCU\Software\<org>\Huginn through Qt would make the key when it
    // isn't there, so it is only opened once HKCU\Software lists it; read here, written only to delete it.
    const bool regThere = oldSettingsThere();
    std::optional<QSettings> reg;
    QStringList regKeys;
    if (regThere) {
        reg.emplace(QSettings::NativeFormat, QSettings::UserScope, Settings::org(), QStringLiteral("Huginn"));
        regKeys = reg->allKeys();
    }
    if (root.isEmpty() || (!hasRoam && !hasLocal && !regThere))
        return true;
    QSettings ini = Settings::store();
    const bool movedIn = ini.value(QStringLiteral("portable/movedIn"), false).toBool();
    const bool started = ini.value(QStringLiteral("portable/moveStarted"), false).toBool();
    const QString moving = root + QLatin1Char('/') + kMoving;
    // A move that stopped partway last time (a full disk, a file held by antivirus): its parts go and it starts over,
    // from the originals, which are still where they were.
    if (started && !movedIn)
        QDir(moving).removeRecursively();
    // The older settings this folder is missing, imported whenever a move hasn't finished, even when chats are here
    // already (an older version's move that stopped partway left them without the settings). The public chat key is
    // left out when chats are here: Chat works it out from the private one, so it always matches.
    auto importSettings = [&](bool chatsHere) {
        int keys = 0;
        for (const QString &k : std::as_const(regKeys)) {
            if (ini.contains(k) || (chatsHere && k == QLatin1String("chat/pub")))
                continue;
            ini.setValue(k, reg->value(k));
            ++keys;
        }
        return keys;
    };
    // This data folder has chats of its own (another Huginn left data in AppData since): AppData's files aren't mixed in.
    if (!started && holdsFiles(root + QStringLiteral("/chat"))) {
        if (!movedIn && regThere) {
            const int keys = importSettings(true);
            if (keys > 0)
                CrashLog::note(QStringLiteral("data: imported %1 missing settings from the registry").arg(keys));
        }
        if (!ini.value(QStringLiteral("portable/oldLeft"), false).toBool()) {
            ini.setValue(QStringLiteral("portable/oldLeft"), true);
            CrashLog::note(QStringLiteral("data: Huginn data in AppData left as it is; this folder already has its own"));
        }
        ini.sync();
        return true;
    }
    const QString failed = QStringLiteral("Huginn couldn't move its saved chats and settings into the data folder beside Huginn.exe. "
                                          "Nothing was deleted. Check the disk has room, then open Huginn again.");
    // Marked first: a move that stops anywhere below is started over next time, never taken as finished.
    ini.setValue(QStringLiteral("portable/moveStarted"), true);
    ini.sync();
    if (ini.status() != QSettings::NoError) {
        CrashLog::note(QStringLiteral("data: couldn't write the settings file; nothing moved"));
        *why = failed;
        return false;
    }
    QString file;
    // Put together beside the data first: the roaming folder (vault, chats, keys, tls, crash.log), then the local one
    // (the cache). Scratch files are leftovers.
    if (!QDir().mkpath(moving) || (hasRoam && !copyTree(roam, moving, {}, &file))) {
        CrashLog::note(QStringLiteral("data: couldn't copy ") + file + QStringLiteral(" from AppData; nothing deleted"));
        QDir(moving).removeRecursively();
        *why = failed;
        return false;
    }
    if (hasLocal && !copyTree(local, moving, {QStringLiteral("scratch")}, &file)) {
        CrashLog::note(QStringLiteral("data: couldn't copy ") + file + QStringLiteral(" from the local AppData folder; nothing deleted"));
        QDir(moving).removeRecursively();
        *why = failed;
        return false;
    }
    const int keys = regThere ? importSettings(false) : 0;
    ini.sync();
    if (ini.status() != QSettings::NoError) {
        CrashLog::note(QStringLiteral("data: couldn't write the settings file; nothing deleted"));
        QDir(moving).removeRecursively();
        *why = failed;
        return false;
    }
    // Everything is together: into place (renames on the same disk), then marked as done.
    if (!mergeInto(moving, root, &file)) {
        CrashLog::note(QStringLiteral("data: couldn't put ") + file + QStringLiteral(" in place; nothing deleted, tried again next time"));
        *why = failed;
        return false;
    }
    QDir(moving).removeRecursively();
    ini.setValue(QStringLiteral("portable/movedIn"), true);
    ini.remove(QStringLiteral("portable/moveStarted"));
    ini.sync();
    if (ini.status() != QSettings::NoError) {
        CrashLog::note(QStringLiteral("data: couldn't write the settings file; nothing deleted"));
        *why = failed;
        return false;
    }
    // Everything is in the data folder: the originals go.
    bool gone = true;
    if (hasRoam) {
        gone = QDir(roam).removeRecursively() && gone;
        QDir().rmdir(QFileInfo(roam).absolutePath());   // the "Huginn" folder above, once empty
    }
    if (hasLocal) {
        const QString scratch = local + QStringLiteral("/scratch");
        for (const QFileInfo &fi : QDir(scratch).entryInfoList(QDir::Files | QDir::Hidden | QDir::System))
            Scratch::shred(fi.absoluteFilePath());
        gone = QDir(local).removeRecursively() && gone;
        QDir().rmdir(QFileInfo(local).absolutePath());
    }
    if (regThere) {
        reg.reset();
        // HKCU\Software\<org>\Huginn and everything in it, then HKCU\Software\<org> when nothing else is in it.
        bool orgEmpty = false;
        {
            QSettings org(QStringLiteral("HKEY_CURRENT_USER\\Software\\") + Settings::org(), QSettings::NativeFormat);
            org.remove(QStringLiteral("Huginn"));
            org.sync();
            orgEmpty = org.childGroups().isEmpty() && org.childKeys().isEmpty();
        }
        if (orgEmpty) {
            QSettings software(QStringLiteral("HKEY_CURRENT_USER\\Software"), QSettings::NativeFormat);
            software.remove(Settings::org());
            software.sync();
        }
        gone = gone && !oldSettingsThere();
    }
    CrashLog::note(QStringLiteral("data: moved Huginn's AppData folders and %1 registry settings into the data folder beside Huginn.exe%2")
                       .arg(keys)
                       .arg(gone ? QStringLiteral("; the originals are deleted") : QStringLiteral("; some originals couldn't be deleted")));
    return true;
#else
    Q_UNUSED(why);
    return true;
#endif
}

void eraseAll()
{
    if (s_root.isEmpty())
        return;
    QSettings s = Settings::store();
    s.clear();
    s.sync();
    eraseIn(s_root, s.fileName());
}

bool selfTest()
{
    bool all = true;
    auto check = [&](const char *what, bool ok) {
        std::printf("data folder self-test: %s %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    check("beside Huginn.exe", rootFor(QStringLiteral("C:/Games/Huginn")) == QLatin1String("C:/Games/Huginn/data"));
    check("the staged update uses the same folder", rootFor(QStringLiteral("C:/Games/Huginn/update-staging")) == QLatin1String("C:/Games/Huginn/data")
                                                        && rootFor(QStringLiteral("C:\\Games\\Huginn\\Update-Staging")) == QLatin1String("C:/Games/Huginn/data"));
    if (on()) {
        const QSettings s = Settings::store();
        check("settings are an INI file in the data folder", s.format() == QSettings::IniFormat
                                                                  && QDir::cleanPath(s.fileName()).startsWith(QDir::cleanPath(dataDir()) + QLatin1Char('/')));
        check("the cache and scratch files are in the data folder", cacheDir().startsWith(dataDir() + QLatin1Char('/')) && localDir() == dataDir());
    }

    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        check("temp folder", false);
        return false;
    }
    const QString from = tmp.path() + QStringLiteral("/old"), to = tmp.path() + QStringLiteral("/data");
    auto put = [](const QString &path, const QByteArray &bytes) {
        QDir().mkpath(QFileInfo(path).absolutePath());
        return writeAll(path, bytes);
    };
    put(from + QStringLiteral("/chat/vault.key"), "old key");
    put(from + QStringLiteral("/chat/chats.json"), "old chats");
    put(from + QStringLiteral("/tls/key.pem"), "pem");
    put(from + QStringLiteral("/crash.log"), "old line\n");
    put(from + QStringLiteral("/instance.lock"), "1");
    put(from + QStringLiteral("/scratch/voice.tmp"), "v");
    put(to + QStringLiteral("/crash.log"), "new line\n");
    put(to + QStringLiteral("/chat/chats.json"), "kept");
    QString file;
    const bool copied = copyTree(from, to, {QStringLiteral("scratch")}, &file);
    check("copies every file", copied && readAll(to + QStringLiteral("/chat/vault.key")) == "old key" && readAll(to + QStringLiteral("/tls/key.pem")) == "pem");
    check("keeps what was there and adds to the log", readAll(to + QStringLiteral("/chat/chats.json")) == "kept"
                                                          && readAll(to + QStringLiteral("/crash.log")) == "old line\nnew line\n");
    check("leaves out the lock and scratch files", !QFileInfo::exists(to + QStringLiteral("/instance.lock")) && !QFileInfo::exists(to + QStringLiteral("/scratch")));
    check("the originals stay until it's done", QFileInfo::exists(from + QStringLiteral("/chat/vault.key")));

    put(to + QStringLiteral("/instance.lock"), "1");
    put(to + QStringLiteral("/Huginn/Huginn.ini"), "[General]\n");
    put(to + QStringLiteral("/Huginn/other.ini"), "x");
    put(to + QStringLiteral("/cache/Huginn-v2.zip"), "zip");
    put(to + QStringLiteral("/chat-kept-2026-01-01/chats.json"), "kept");
    // Round 19: Tor's folder, the voices, the count of tries and a save's leftover copy go too.
    put(to + QStringLiteral("/tor/data/state"), "tor");
    put(to + QStringLiteral("/voices/f/model.onnx"), "voice");
    put(to + QStringLiteral("/lock-state.json"), "{}");
    put(to + QStringLiteral("/ring-key.json.Ab12Cd"), "key");
    // Something another program keeps in a folder named data: never Huginn's to erase.
    put(to + QStringLiteral("/savegame.dat"), "not Huginn's");
    put(to + QStringLiteral("/lib/other.dll"), "not Huginn's");
    eraseIn(to, to + QStringLiteral("/Huginn/Huginn.ini"));
    const QStringList left = QDir(to).entryList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
    check("erase empties the data folder of Huginn's things", left.size() == 4 && left.contains(QStringLiteral("instance.lock")) && left.contains(QStringLiteral("Huginn"))
                                               && QDir(to + QStringLiteral("/Huginn")).entryList(QDir::Files | QDir::Hidden) == QStringList{QStringLiteral("Huginn.ini")});
    check("erase leaves what isn't Huginn's", readAll(to + QStringLiteral("/savegame.dat")) == "not Huginn's"
                                                  && readAll(to + QStringLiteral("/lib/other.dll")) == "not Huginn's");

    // A move put together beside the data goes into place; what a move that stopped partway already put there stays,
    // and doing it again changes nothing.
    const QString parts = tmp.path() + QStringLiteral("/parts"), into = tmp.path() + QStringLiteral("/into");
    put(parts + QStringLiteral("/chat/vault.key"), "key");
    put(parts + QStringLiteral("/chat/chats.json"), "chats");
    put(parts + QStringLiteral("/crash.log"), "old\n");
    put(into + QStringLiteral("/chat/chats.json"), "chats");
    put(into + QStringLiteral("/crash.log"), "new\n");
    QString why;
    const bool merged = mergeInto(parts, into, &why);
    check("a move goes into place", merged && readAll(into + QStringLiteral("/chat/vault.key")) == "key" && readAll(into + QStringLiteral("/chat/chats.json")) == "chats"
                                        && readAll(into + QStringLiteral("/crash.log")) == "old\nnew\n");
    check("an empty chat folder isn't taken as chats", [&] {
        QDir().mkpath(tmp.path() + QStringLiteral("/empty/chat/sub"));
        return !holdsFiles(tmp.path() + QStringLiteral("/empty/chat")) && holdsFiles(into + QStringLiteral("/chat"));
    }());
    // Only a full disk is told to free up space; a folder Windows keeps another account out of never is.
    check("a full disk says so", cantWriteText(tmp.path(), 0, true).contains(QStringLiteral("full")));
    check("a folder that can't be written isn't called full", !cantWriteText(tmp.path(), 0, false).contains(QStringLiteral("Free up")));
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    check("Windows: a disk-full error says full", cantWriteText(tmp.path(), ERROR_DISK_FULL, false).contains(QStringLiteral("full")));
    check("Windows: access denied speaks of the account, not of space",
          [&] {
              const QString t = cantWriteText(tmp.path(), ERROR_ACCESS_DENIED, false);
              return t.contains(QStringLiteral("account")) && !t.contains(QStringLiteral("Free up"));
          }());
    check("Windows: this account owns a folder it made", [&] {
        std::wstring sid;
        QString name;
        return ownerOf(tmp.path(), &sid, &name) && sid == mySid();
    }());
    // Round 16: Program Files, a folder that can't be made and this account's own blocked folder each get their own
    // words; none says the folder is private to another account.
    check("Windows: Program Files says Program Files", [&] {
        const QString pf = qEnvironmentVariable("ProgramFiles");
        if (pf.isEmpty())
            return true;
        const QString t = cantWriteText(QDir::fromNativeSeparators(pf) + QStringLiteral("/Huginn/data"), ERROR_ACCESS_DENIED, false);
        return t.contains(QStringLiteral("Program Files")) && t.contains(QStringLiteral("folder of your own")) && !t.contains(QStringLiteral("private"));
    }());
    check("Windows: a data folder that can't be made says where to move Huginn", [&] {
        const QString t = cantWriteText(tmp.path() + QStringLiteral("/none/data"), ERROR_ACCESS_DENIED, false);
        return t.contains(QStringLiteral("folder of your own")) && t.contains(QStringLiteral("Controlled folder access")) && !t.contains(QStringLiteral("private"));
    }());
    check("Windows: this account's own folder, blocked, speaks of Controlled folder access", [&] {
        const QString t = cantWriteText(tmp.path(), ERROR_ACCESS_DENIED, false);
        return t.contains(QStringLiteral("Controlled folder access")) && !t.contains(QStringLiteral("private"));
    }());
    check("Windows: account SIDs are told from Administrators and the system", personSid(L"S-1-5-21-1-2-3-1001") && personSid(L"S-1-12-1-5-6-7-8")
                                                                                    && !personSid(L"S-1-5-32-544") && !personSid(L"S-1-5-18")
                                                                                    && !personSid(L"S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"));
    check("Windows: a folder this account owns and can save in needs no repair", [&] {
        DWORD e = 0;
        return repairOwn(tmp.path(), &e) && e == 0;
    }());
#endif
    return all;
}
}
