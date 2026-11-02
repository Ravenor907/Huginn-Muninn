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
#include <QTemporaryDir>
#include <cstdio>
#include <optional>

#ifdef Q_OS_WIN
#include <windows.h>
#include <cstdlib>
#include <string>
#endif

namespace
{
// The data folder (the Windows portable build only; empty elsewhere).
QString s_root;
// Set when Huginn.exe's own folder couldn't be found.
QString s_problem;

// What a running copy holds beside its data: never copied, never erased while it runs.
const QStringList kInUse = {QStringLiteral("instance.lock"), QStringLiteral("instance.closing"), QStringLiteral("instance.end")};

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

// Empties root: every file and folder goes but the lock files of a running copy and settingsFile, which is kept (it
// was emptied through QSettings, which other open QSettings share).
void eraseIn(const QString &root, const QString &settingsFile)
{
    if (root.isEmpty())
        return;
    const QString keep = QDir::cleanPath(settingsFile);
    const QFileInfoList all = QDir(root).entryInfoList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
    for (const QFileInfo &fi : all) {
        const QString path = QDir::cleanPath(fi.absoluteFilePath());
        if (kInUse.contains(fi.fileName(), Qt::CaseInsensitive) || path == keep)
            continue;
        if (fi.isDir() && !fi.isSymLink()) {
            if (!keep.isEmpty() && keep.startsWith(path + QLatin1Char('/'))) {
                // The settings file's own folder: everything else in it goes.
                eraseIn(path, keep);
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
        s_problem = QStringLiteral("Huginn couldn't find its own folder. Unzip Huginn somewhere of your own, like Documents, and open it again.");
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
    if (s_root.isEmpty())
        return {};
    // Qt doesn't read Windows permissions by default, so just try writing.
    QFile probe(s_root + QStringLiteral("/.huginn-write-test"));
    if (!QDir().mkpath(s_root) || !probe.open(QIODevice::WriteOnly) || probe.write("x", 1) != 1) {
        return QStringLiteral("Huginn can't save anything in its folder (%1). Move the Huginn folder somewhere of your own, "
                              "like Documents or the Desktop, and open it again.")
            .arg(QDir::toNativeSeparators(QFileInfo(s_root).absolutePath()));
    }
    probe.close();
    probe.remove();
    return {};
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
    // This data folder has chats of its own already (another Huginn left data in AppData since): nothing is mixed in.
    if (QFileInfo::exists(root + QStringLiteral("/chat"))) {
        if (!ini.value(QStringLiteral("portable/oldLeft"), false).toBool()) {
            ini.setValue(QStringLiteral("portable/oldLeft"), true);
            ini.sync();
            CrashLog::note(QStringLiteral("data: Huginn data in AppData left as it is; this folder already has its own"));
        }
        return true;
    }
    const QString failed = QStringLiteral("Huginn couldn't move its saved chats and settings into the data folder beside Huginn.exe. "
                                          "Nothing was deleted. Check the disk has room, then open Huginn again.");
    QString file;
    // The roaming folder (vault, chats, keys, tls, crash.log); the local one holds the cache. Scratch files are leftovers.
    if (hasRoam && !copyTree(roam, root, {}, &file)) {
        CrashLog::note(QStringLiteral("data: couldn't copy ") + file + QStringLiteral(" from AppData; nothing deleted"));
        *why = failed;
        return false;
    }
    if (hasLocal && !copyTree(local, root, {QStringLiteral("scratch")}, &file)) {
        CrashLog::note(QStringLiteral("data: couldn't copy ") + file + QStringLiteral(" from the local AppData folder; nothing deleted"));
        *why = failed;
        return false;
    }
    int keys = 0;
    for (const QString &k : regKeys) {
        if (!ini.contains(k)) {
            ini.setValue(k, reg->value(k));
            ++keys;
        }
    }
    ini.setValue(QStringLiteral("portable/movedIn"), true);
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
    eraseIn(to, to + QStringLiteral("/Huginn/Huginn.ini"));
    const QStringList left = QDir(to).entryList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System);
    check("erase empties the data folder", left.size() == 2 && left.contains(QStringLiteral("instance.lock")) && left.contains(QStringLiteral("Huginn"))
                                               && QDir(to + QStringLiteral("/Huginn")).entryList(QDir::Files | QDir::Hidden) == QStringList{QStringLiteral("Huginn.ini")});
    return all;
}
}
