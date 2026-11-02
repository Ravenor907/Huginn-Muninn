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

// Everything Huginn ever keeps at the top of its data folder. Erase everything removes only these, never anything
// else someone keeps there.
bool huginnsOwn(const QString &name)
{
    static const QStringList kNames = {
        QStringLiteral("Huginn"), QStringLiteral("chat"), QStringLiteral("chat.restore"), QStringLiteral("chat.old"), QStringLiteral("cache"),
        QStringLiteral("scratch"), QStringLiteral("tls"), QStringLiteral("crash.log"), QStringLiteral("running.mark"),
        QStringLiteral("Huginn-start.log"), QStringLiteral("ring-key.json"), QStringLiteral("nudge-key.json"), QStringLiteral("QtProject.ini"),
        QStringLiteral(".huginn-write-test"), kMark, kMoving, QStringLiteral("instance.lock"), QStringLiteral("instance.closing"),
        QStringLiteral("instance.end")};
    return kNames.contains(name, Qt::CaseInsensitive) || name.startsWith(QLatin1String("chat-kept-"), Qt::CaseInsensitive);
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
        if (top && !huginnsOwn(fi.fileName()))
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

QString folderProblem()
{
    if (!s_problem.isEmpty())
        return s_problem;
    // The Windows portable build's folder beside Huginn.exe, or Qt's usual data folder elsewhere (the Flatpak's).
    const QString root = !s_root.isEmpty() ? s_root : dataDir();
    if (root.isEmpty())
        return {};
    const QString cantSave = !s_root.isEmpty()
        ? QStringLiteral("Huginn can't save anything in its folder (%1). Free up space on the disk, or move the Huginn folder to a folder "
                         "of your own that isn't synced to the cloud, like C:\\Users\\<you>\\Huginn, and open it again.")
              .arg(QDir::toNativeSeparators(QFileInfo(s_root).absolutePath()))
        : QStringLiteral("Huginn can't save in its data folder (%1). Free up space on the disk or check the folder's permissions, then "
                         "open Huginn again.")
              .arg(QDir::toNativeSeparators(root));
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
    // a full disk shows here too.
    QFile probe(root + QStringLiteral("/.huginn-write-test"));
    const QByteArray block(4096, 'x');
    const bool wrote = QDir().mkpath(root) && probe.open(QIODevice::WriteOnly) && probe.write(block) == block.size() && probe.flush();
    probe.close();
    probe.remove();
    if (!wrote)
        return cantSave;
    const QStorageInfo disk(root);
    if (disk.isValid() && disk.bytesAvailable() >= 0 && disk.bytesAvailable() < 1024 * 1024)
        return cantSave;
    // Taken as Huginn's own from now on.
    if (!s_root.isEmpty() && !QFileInfo::exists(s_root + QLatin1Char('/') + kMark)) {
        QFile mark(s_root + QLatin1Char('/') + kMark);
        if (mark.open(QIODevice::WriteOnly))
            mark.write("Huginn's data folder. Everything Huginn keeps is in here.\n");
    }
    return {};
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

// Only this Windows account (and the system itself) may open the data folder and everything in it: a protected
// list, nothing taken from the folder above (which may let every account in, like C:\ or a USB stick).
bool onlyThisAccount(const QString &dir)
{
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
        return false;
    DWORD n = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &n);
    std::vector<BYTE> buf(n ? n : 1);
    const bool got = n > 0 && GetTokenInformation(token, TokenUser, buf.data(), n, &n);
    CloseHandle(token);
    if (!got)
        return false;
    LPWSTR sid = nullptr;
    if (!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER *>(buf.data())->User.Sid, &sid))
        return false;
    const std::wstring sddl = L"D:P(A;OICI;FA;;;" + std::wstring(sid) + L")(A;OICI;FA;;;SY)";
    LocalFree(sid);
    PSECURITY_DESCRIPTOR sd = nullptr;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr))
        return false;
    BOOL present = FALSE, defaulted = FALSE;
    PACL dacl = nullptr;
    bool ok = GetSecurityDescriptorDacl(sd, &present, &dacl, &defaulted) && present && dacl;
    std::wstring path = QDir::toNativeSeparators(dir).toStdWString();
    if (ok)
        ok = SetNamedSecurityInfoW(path.data(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, nullptr, nullptr, dacl,
                                   nullptr)
            == ERROR_SUCCESS;
    LocalFree(sd);
    return ok;
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

void makePrivate()
{
#if defined(Q_OS_WIN) && defined(HUGINN_WINDOWS_PORTABLE)
    if (s_root.isEmpty() || !QFileInfo(s_root).isDir())
        return;
    QSettings s = Settings::store();
    if (s.value(QStringLiteral("portable/private")).toString() == s_root)
        return;   // done for this folder already
    if (onlyThisAccount(s_root)) {
        s.setValue(QStringLiteral("portable/private"), s_root);
        s.sync();
        CrashLog::note(QStringLiteral("data: the data folder is readable by this account only"));
    } else {
        // A drive without permissions (a USB stick formatted FAT32 or exFAT): the lock is what keeps the chats sealed.
        CrashLog::note(QStringLiteral("data: couldn't make the data folder private to this account (error %1)").arg(GetLastError()));
    }
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
    return all;
}
}
