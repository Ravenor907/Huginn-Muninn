#include "winupdate.h"

#include <QObject>
#include <QRegularExpression>

#ifdef Q_OS_WIN
#include "singleinstance.h"
#include "portable.h"

#include "crashlog.h"
#include "../settings.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QProcess>
#include <QSaveFile>
#include <QSet>
#include <QStringList>
#include <QThread>
#include <QTimer>
// Qt's zip reader: in QtCore from Qt 6.8 on, in QtGui before.
#if __has_include(<QtCore/private/qzipreader_p.h>)
#include <QtCore/private/qzipreader_p.h>
#else
#include <QtGui/private/qzipreader_p.h>
#endif
#include <windows.h>
#endif

namespace WinUpdate
{
QString assetName()
{
    return QStringLiteral("Huginn-Windows.zip");
}

QString digestName()
{
    return assetName() + QStringLiteral(".sha256");
}

QByteArray parseDigest(const QByteArray &text)
{
    // "sha256:<hex>" (GitHub's asset digest) or "<hex>  Huginn-Windows.zip" (sha256sum).
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("(?:^|[^0-9a-fA-F])([0-9a-fA-F]{64})(?:$|[^0-9a-fA-F])"))
                                          .match(QString::fromLatin1(text.trimmed()));
    return m.hasMatch() ? m.captured(1).toLower().toLatin1() : QByteArray();
}

#ifndef Q_OS_WIN

bool supported() { return false; }
bool finishAtStart() { return false; }
bool giveUpStaged(const QString &) { return false; }
void cleanUp() {}
void tidyOldLayout() {}
QString folderProblem() { return {}; }
void install(const QString &, QObject *, std::function<void(bool, bool, const QString &)> done)
{
    if (done)
        done(false, false, QStringLiteral("Updates from a zip are only for the Windows build."));
}

#else

namespace
{
// What the swap moves aside (never a running program's files: the copy doing it runs from update-staging), deleted
// right after or at the next start.
const QString kAside = QStringLiteral(".huginn-old");
// In update-staging once the update is unpacked and checked: every staged file's SHA-256 and name, one a line
// ("<sha256> <relative path>"). The SHA-256 of this list is kept in Huginn's own settings ("update/staged"), never only
// here: update-staging is a plain folder that anything able to write beside Huginn.exe could fill, so a list in it
// proves nothing by itself. "update/stagedTries" counts how often a start handed the update to the staged copy.
const QString kReady = QStringLiteral("update.ready");
const QString kStagedKey = QStringLiteral("update/staged");
const QString kTriesKey = QStringLiteral("update/stagedTries");
const QString kStaging = QStringLiteral("update-staging");
// In lib\: every file the last update put there (one relative path a line). Only those are ever deleted by the next one.
const QString kInstalled = QStringLiteral("lib/huginn-files.txt");
// Round 17: in update-staging when Controlled folder access blocked the staged Huginn.exe. It stays a week, so the person
// can pick it in Windows Security's Allow an app (which needs the file there).
const QString kBlocked = QStringLiteral("update.blocked");

// A swap that didn't go in: why, kept in settings ("update/failed" = "<version>|<sentence>"). The next start says so,
// and timed checks don't download that version again until Check now (updater.cpp).
void noteFailed(const QString &version, const QString &why)
{
    CrashLog::note(QStringLiteral("update: ") + why);
    QSettings s = Settings::store();
    s.setValue(QStringLiteral("update/failed"), version + QLatin1Char('|') + why);
    s.sync();
}

// The portable folder people unzipped: the one with Huginn.exe. Works before Qt has started.
QString appDir()
{
    std::wstring self(32768, L'\0');
    const DWORD n = GetModuleFileNameW(nullptr, self.data(), DWORD(self.size()));
    if (n == 0 || n >= self.size())
        return QCoreApplication::instance() ? QCoreApplication::applicationDirPath() : QString();
    self.resize(n);
    return QFileInfo(QDir::fromNativeSeparators(QString::fromStdWString(self))).absolutePath();
}
QString stagingDir() { return appDir() + QLatin1Char('/') + kStaging; }

// Round 17: what to say when Controlled folder access (or another security program) keeps the staged copy from saving.
// Moving out of the protected folders comes first; allowing the staged copy too is the second way.
QString blockedText(const QString &root)
{
    return QStringLiteral("The update didn't go in: Windows' Controlled folder access (or another security program) doesn't let the new "
                          "Huginn.exe save in Huginn's folder, so Huginn kept the version it had.\n\n"
                          "Best: close Huginn and move its whole folder out of Documents, Desktop and Pictures, to a folder like "
                          "C:\\Users\\<you>\\Huginn. Updates then just work.\n\n"
                          "Or allow the new copy too: Windows Security › Virus & threat protection › Manage ransomware protection › Allow "
                          "an app through Controlled folder access › Add an allowed app › Browse all apps, and pick %1. Then choose "
                          "Check now in Settings › Updates and about.")
        .arg(QDir::toNativeSeparators(root + QLatin1Char('/') + kStaging + QStringLiteral("/Huginn.exe")));
}

QByteArray fileSha256(const QString &path)
{
    QFile f(path);
    QCryptographicHash h(QCryptographicHash::Sha256);
    if (!f.open(QIODevice::ReadOnly) || !h.addData(&f))
        return {};
    return h.result().toHex();
}

// The files under dir (relative to it), the ready mark and the start log left out.
QStringList filesUnder(const QString &dir)
{
    QStringList out;
    QDirIterator it(dir, QDir::Files | QDir::Hidden, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString rel = QDir(dir).relativeFilePath(it.next());
        if (rel == kReady || rel.compare(QLatin1String("Huginn-start.log"), Qt::CaseInsensitive) == 0)
            continue;
        out << rel;
    }
    return out;
}

// Round 19 (FIX 8): Tor's own files (lib/tor/...). Antivirus sometimes quarantines tor.exe; one that can't be written or
// replaced is left out, so it never blocks an update (Ultra privacy then says Tor is missing).
bool isTor(const QString &rel)
{
    return rel.startsWith(QLatin1String("lib/tor/"), Qt::CaseInsensitive);
}

// A file that was in use a moment ago (the copy that just closed) can take a moment to let go.
bool retry(const std::function<bool()> &step)
{
    for (int i = 0; i < 20; ++i) {
        if (step())
            return true;
        QThread::msleep(250);
    }
    return false;
}

// A file name from the zip, made safe: relative, no "..", no drive letters or streams.
QString safeRelative(QString p)
{
    p.replace(QLatin1Char('\\'), QLatin1Char('/'));
    while (p.startsWith(QLatin1Char('/')))
        p.remove(0, 1);
    p = QDir::cleanPath(p);
    if (p.isEmpty() || p == QLatin1String(".") || p.startsWith(QLatin1String("../")) || p == QLatin1String("..") || p.contains(QLatin1Char(':')))
        return {};
    return p;
}

// Round 17 (m4): the swap's journal, in Huginn's folder while new files go in (one relative path a line, "+" first for a
// file the version before didn't have). Every new file is copied in first as "<name>.huginn-new" (the slow part,
// nothing replaced yet); only then is this written and each one renamed into place, the old one moved aside as
// "<name>.huginn-old". It stays until the swap is fully done or fully undone. A start that finds it (the power went,
// or antivirus ended the staged copy) never touches the files itself (they are the running copy's): the staged copy
// is started again to finish it, and the moved-aside files are kept meanwhile.
const QString kJournal = QStringLiteral("update.swap");
const QString kNew = QStringLiteral(".huginn-new");

QStringList readJournal(const QString &root)
{
    QFile j(root + QLatin1Char('/') + kJournal);
    if (!j.open(QIODevice::ReadOnly | QIODevice::Text))
        return {};
    return QString::fromUtf8(j.readAll()).split(QLatin1Char('\n'), Qt::SkipEmptyParts);
}

// The staged update's list, only when it is exactly the one this Huginn wrote (its SHA-256 is in the settings).
struct Manifest {
    bool ok = false;
    QStringList files;              // relative paths, Huginn.exe among them
    QHash<QString, QByteArray> sums; // relative path -> SHA-256 (hex)
};
Manifest readManifest(const QString &staging)
{
    Manifest m;
    QFile f(staging + QLatin1Char('/') + kReady);
    if (!f.open(QIODevice::ReadOnly))
        return m;
    const QByteArray text = f.read(1024 * 1024 + 1);
    f.close();
    const QByteArray want = Settings::store().value(kStagedKey).toString().toLatin1();
    if (text.size() > 1024 * 1024 || want.size() != 64 || QCryptographicHash::hash(text, QCryptographicHash::Sha256).toHex() != want)
        return m;
    for (const QByteArray &line : text.split('\n')) {
        if (line.isEmpty())
            continue;
        const QString rel = safeRelative(QString::fromUtf8(line.mid(65)));
        const QByteArray sum = line.left(64);
        if (line.size() < 66 || line.at(64) != ' ' || rel.isEmpty() || parseDigest(sum) != sum || m.sums.contains(rel))
            return {};
        m.files << rel;
        m.sums.insert(rel, sum);
    }
    m.ok = m.sums.contains(QStringLiteral("Huginn.exe"));
    return m;
}

void forgetStaged()
{
    QSettings st = Settings::store();
    st.remove(kStagedKey);
    st.remove(kTriesKey);
    st.sync();
}

// Puts the version from before back, from the journal: every file moved aside goes back in place, and a file the old
// version didn't have goes. Each step is tried for a while (antivirus may hold a file a moment); nothing is ever deleted
// that isn't also somewhere else (a new file is in update-staging). True when everything is back.
bool rollBack(const QString &root, const QStringList &journal)
{
    bool all = true;
    for (auto it = journal.crbegin(); it != journal.crend(); ++it) {
        const bool added = it->startsWith(QLatin1Char('+'));
        const QString target = root + QLatin1Char('/') + (added ? it->mid(1) : *it);
        QFile::remove(target + kNew);
        if (QFileInfo::exists(target + kAside)) {
            const bool back = retry([&] {
                return (!QFileInfo::exists(target) || QFile::remove(target)) && QFile::rename(target + kAside, target);
            });
            if (!back)
                CrashLog::note(QStringLiteral("update: couldn't put %1 back yet").arg(added ? it->mid(1) : *it));
            all = all && back;
        } else if (added) {
            all = retry([&] { return !QFileInfo::exists(target) || QFile::remove(target); }) && all;
        }
        // Otherwise it was never moved: the old file is still in place.
    }
    return all;
}

// Makes the name free for the new file; true when it is. The old file is moved aside, never deleted. resuming: a swap
// that was cut off is being finished, and a "<name>.huginn-old" already there is that swap's (the version from
// before), kept as it is; the file in place is then that swap's new one (also in update-staging), so it may go.
// Only for files no running program uses (the swap runs from the staged copy, after the old one closed).
bool moveAside(const QString &target, bool resuming)
{
    const QString to = target + kAside;
    if (QFileInfo::exists(to))
        return resuming && (!QFileInfo::exists(target) || QFile::remove(target));
    return !QFileInfo::exists(target) || QFile::rename(target, to);
}

// Unpacks the zip into the staging folder. Returns the files it holds (relative to the folder with
// Huginn.exe, e.g. "Huginn.exe", "lib/Qt6Core.dll") or an empty list with *why set.
QStringList unpack(const QString &zipPath, const QString &staging, QString *why)
{
    QZipReader zip(zipPath);
    if (!zip.isReadable() || zip.status() != QZipReader::NoError) {
        *why = QStringLiteral("The update couldn't be opened. Try again.");
        return {};
    }
    const QList<QZipReader::FileInfo> entries = zip.fileInfoList();
    // Huginn.exe sits at the top of the zip or one folder down (Huginn/Huginn.exe).
    QString prefix;
    bool found = false;
    for (const QZipReader::FileInfo &e : entries) {
        const QString p = safeRelative(e.filePath);
        if (!e.isFile || p.isEmpty())
            continue;
        if (p.compare(QLatin1String("Huginn.exe"), Qt::CaseInsensitive) == 0 || (p.count(QLatin1Char('/')) == 1 && p.endsWith(QLatin1String("/Huginn.exe"), Qt::CaseInsensitive))) {
            prefix = p.left(p.size() - int(qstrlen("Huginn.exe")));
            found = true;
            break;
        }
    }
    if (!found) {
        *why = QStringLiteral("The update doesn't hold Huginn.exe.");
        return {};
    }
    QStringList files;
    for (const QZipReader::FileInfo &e : entries) {
        if (e.isSymLink) {
            *why = QStringLiteral("The update holds a link, which Huginn doesn't accept.");
            return {};
        }
        if (!e.isFile)
            continue;
        const QString p = safeRelative(e.filePath);
        if (p.isEmpty() || !p.startsWith(prefix, Qt::CaseInsensitive))
            continue;
        const QString rel = p.mid(prefix.size());
        if (rel.isEmpty() || rel.endsWith(kAside, Qt::CaseInsensitive))
            continue;
        // The data folder beside Huginn.exe is never replaced: an update keeps the identity, chats and settings.
        if (rel.compare(QLatin1String("data"), Qt::CaseInsensitive) == 0 || rel.startsWith(QLatin1String("data/"), Qt::CaseInsensitive))
            continue;
        const QByteArray data = zip.fileData(e.filePath);
        if (data.size() != e.size) {
            *why = QStringLiteral("The update is damaged (%1). Try again.").arg(rel);
            return {};
        }
        const QString out = staging + QLatin1Char('/') + rel;
        QDir().mkpath(QFileInfo(out).absolutePath());
        QFile f(out);
        if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate) || f.write(data) != data.size() || !f.flush()) {
            if (isTor(rel)) {
                f.close();
                f.remove();
                CrashLog::note(QStringLiteral("update: couldn't write %1, left out").arg(rel));
                continue;
            }
            *why = QStringLiteral("Couldn't write the update (%1). Is the disk full?").arg(rel);
            return {};
        }
        files << rel;
    }
    if (!files.contains(QStringLiteral("lib/lib.manifest"), Qt::CaseInsensitive)) {
        *why = QStringLiteral("The update is missing lib\\lib.manifest.");
        return {};
    }
    return files;
}

void deleteAsides(const QString &root)
{
    // The top of the folder (not below it: Huginn.exe could have been put somewhere big) and all of lib\.
    for (const QFileInfo &fi : QDir(root).entryInfoList({QLatin1Char('*') + kAside}, QDir::Files | QDir::Hidden))
        QFile::remove(fi.absoluteFilePath());
    QDirIterator it(root + QStringLiteral("/lib"), {QLatin1Char('*') + kAside}, QDir::Files | QDir::Hidden, QDirIterator::Subdirectories);
    while (it.hasNext())
        QFile::remove(it.next());
}
}

bool supported()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    return true;   // only the CI-built zip; a development build updates by rebuilding
#else
    return false;
#endif
}

QString folderProblem()
{
    // Qt doesn't read Windows permissions by default, so just try writing.
    QFile probe(appDir() + QStringLiteral("/.huginn-write-test"));
    if (!probe.open(QIODevice::WriteOnly)) {
        return QStringLiteral("Huginn can't update itself where it is (%1). Move the Huginn folder to a folder of your own that "
                              "isn't synced to the cloud, like C:\\Users\\<you>\\Huginn, and try again.")
            .arg(QDir::toNativeSeparators(appDir()));
    }
    probe.close();
    probe.remove();
    return {};
}

void cleanUp()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    const QString root = appDir();
    if (root.isEmpty())
        return;
    // A swap that was cut off (its journal is still there) is never finished here: these are the running copy's own
    // files. finishAtStart() hands it to the staged copy; until then nothing it needs (new files, moved-aside old
    // ones, update-staging) is touched.
    if (QFileInfo::exists(root + QLatin1Char('/') + kJournal)) {
        const bool staged = QFileInfo::exists(stagingDir() + QLatin1Char('/') + kReady);
        CrashLog::note(staged ? QStringLiteral("update: a swap that was cut off waits for the staged copy")
                              : QStringLiteral("update: a swap was cut off and nothing is left to finish it"));
        if (!staged) {
            // Nothing can finish or undo it without renaming this copy's own files: said once, and the person is told
            // how to put Huginn right. The journal goes, so this isn't said at every start.
            noteFailed(Settings::store().value(QStringLiteral("update/trying")).toString(),
                       QStringLiteral("The last update was cut off before it finished, so some of Huginn's files may be new and some old. "
                                      "If Huginn misbehaves, download Huginn again and unzip it over this folder: the data folder "
                                      "with your chats stays as it is."));
            QFile::remove(root + QLatin1Char('/') + kJournal);
        }
        tidyOldLayout();
        return;
    }
    // New files copied in before a swap was cut off, with no journal, are taken away (the old version is whole).
    for (const QString &dir : {root, root + QStringLiteral("/lib")}) {
        QDirIterator it(dir, {QLatin1Char('*') + kNew}, QDir::Files | QDir::Hidden, dir == root ? QDirIterator::NoIteratorFlags : QDirIterator::Subdirectories);
        while (it.hasNext())
            QFile::remove(it.next());
    }
    // A staged update that is ready is finished by finishAtStart(), not removed. Round 17: one Controlled folder access
    // blocked stays a week, so its Huginn.exe can be picked in Allow an app.
    const QFileInfo blocked(stagingDir() + QLatin1Char('/') + kBlocked);
    const bool keepBlocked = blocked.exists() && blocked.lastModified().daysTo(QDateTime::currentDateTime()) < 7;
    if (!QFileInfo::exists(stagingDir() + QLatin1Char('/') + kReady) && !keepBlocked)
        QDir(stagingDir()).removeRecursively();
    deleteAsides(root);
    tidyOldLayout();
#endif
}

void tidyOldLayout()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    const QString root = appDir(), lib = root + QStringLiteral("/lib");
    if (root.isEmpty() || !QFileInfo(lib).isDir())
        return;
    // Huginn.exe at the top is the app; a copy in lib\ (a launcher layout) goes.
    if (QFileInfo::exists(lib + QStringLiteral("/Huginn.exe")))
        QFile::remove(lib + QStringLiteral("/Huginn.exe"));
    // The start log lives in the data folder now; one left beside Huginn.exe by an older version goes.
    QFile::remove(root + QStringLiteral("/Huginn-start.log"));
#endif
}

bool finishAtStart()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    const QString here = appDir();
    if (here.isEmpty())
        return false;
    // This copy is the staged update (Huginn.exe in update-staging, started by the copy that unpacked it, or by the
    // next start): the old copy has closed, so its files are free. They are replaced here, Huginn is started from its
    // own folder and this copy ends. Nothing in use is renamed: this copy runs from update-staging.
    const QString ready = here + QLatin1Char('/') + kReady;
    if (QFileInfo(here).fileName().compare(kStaging, Qt::CaseInsensitive) == 0 && QFileInfo::exists(ready)) {
        const QString staging = here, root = QFileInfo(here).absolutePath();
        const QString version = Settings::store().value(QStringLiteral("update/trying")).toString();
        const Manifest manifest = readManifest(staging);
        QStringList files;
        if (manifest.ok) {
            files = manifest.files;
        } else {
            // An update staged by a version before lists were kept (its mark holds only Huginn.exe's SHA-256, and
            // nothing is in the settings): everything in update-staging, as that version did. Anything else is refused.
            QFile mark(ready);
            const QByteArray old = mark.open(QIODevice::ReadOnly) ? mark.read(256).trimmed() : QByteArray();
            if (Settings::store().contains(kStagedKey) || parseDigest(old.left(64)) != old.left(64) || (old.size() > 64 && old.mid(64) != " tried")) {
                QFile::remove(ready);
                forgetStaged();
                noteFailed(version, QStringLiteral("The update was changed before it went in (antivirus may have quarantined part of it), so Huginn "
                                                   "kept the version it had. Try again later with Check now in Settings › Updates and about."));
                if (!SingleInstance::startAgain(root + QStringLiteral("/Huginn.exe"), root))
                    CrashLog::note(QStringLiteral("update: couldn't start Huginn after refusing an update"));
                return true;
            }
            files = filesUnder(staging);
        }
        // Round 19 (FIX 8): "Update without Tor" (Updater::updateWithoutTor) leaves Tor's folder as it is, once.
        const bool skipTor = Settings::store().value(QStringLiteral("update/skipTor")).toBool();
        if (skipTor)
            files.removeIf([](const QString &rel) { return isTor(rel); });
        bool torLeft = skipTor;
        QSet<QString> fresh;
        QString failed;
        // A swap that was cut off before (its journal is still here): finished now, from the same staged files.
        const QStringList before = readJournal(root);
        const bool resuming = QFileInfo::exists(root + QLatin1Char('/') + kJournal);
        // A fresh swap first takes away what earlier updates moved aside, so every "<name>.huginn-old" from here on is
        // this swap's own (the version from before).
        if (!resuming)
            deleteAsides(root);
        // Round 17 (m4): first every new file is copied in beside the old one ("<name>.huginn-new"): cut off here,
        // nothing of the old version has changed (the next start's cleanUp takes them away).
        QStringList copied;
        for (const QString &rel : std::as_const(files)) {
            const QString from = staging + QLatin1Char('/') + rel, target = root + QLatin1Char('/') + rel;
            QDir().mkpath(QFileInfo(target).absolutePath());
            QFile::remove(target + kNew);
            // Each file exactly as it was checked and unpacked.
            const bool same = !manifest.ok || fileSha256(from) == manifest.sums.value(rel);
            if (!same || !QFile::copy(from, target + kNew)) {
                if (isTor(rel)) {
                    QFile::remove(target + kNew);
                    CrashLog::note(QStringLiteral("update: couldn't copy %1, left out").arg(rel));
                    torLeft = true;
                    continue;
                }
                failed = rel;
                break;
            }
            copied << rel;
        }
        // What's swapped in is what was copied (a Tor file left out above isn't).
        if (failed.isEmpty())
            files = copied;
        // Then the journal ("+" for a file the version before didn't have; a cut-off swap's marks are kept), and only
        // quick renames.
        QStringList journal;
        if (failed.isEmpty()) {
            QSet<QString> seen;
            for (const QString &line : before) {
                journal << line;
                seen.insert((line.startsWith(QLatin1Char('+')) ? line.mid(1) : line).toLower());
            }
            for (const QString &rel : std::as_const(files))
                if (!seen.contains(rel.toLower()))
                    journal << (QFileInfo::exists(root + QLatin1Char('/') + rel) ? rel : QString(QLatin1Char('+') + rel));
            QSaveFile j(root + QLatin1Char('/') + kJournal);
            if (!j.open(QIODevice::WriteOnly | QIODevice::Text) || j.write(journal.join(QLatin1Char('\n')).toUtf8() + '\n') < 0 || !j.commit())
                failed = kJournal;
        }
        for (const QString &rel : std::as_const(files)) {
            if (!failed.isEmpty())
                break;
            const QString target = root + QLatin1Char('/') + rel;
            fresh.insert(QDir::cleanPath(target).toLower());
            if (!retry([&] { return moveAside(target, resuming); }) || !retry([&] { return QFile::rename(target + kNew, target); })) {
                if (isTor(rel)) {
                    // Left as it was: the old file back in place.
                    if (!QFileInfo::exists(target) && QFileInfo::exists(target + kAside))
                        retry([&] { return QFile::rename(target + kAside, target); });
                    QFile::remove(target + kNew);
                    fresh.remove(QDir::cleanPath(target).toLower());
                    CrashLog::note(QStringLiteral("update: couldn't replace %1, left out").arg(rel));
                    torLeft = true;
                    continue;
                }
                failed = rel;
                break;
            }
        }
        bool done = true;   // false: the old version isn't whole yet, so everything stays for the next start
        if (!failed.isEmpty()) {
            for (const QString &rel : std::as_const(copied))
                QFile::remove(root + QLatin1Char('/') + rel + kNew);
            // Never anything deleted before the old file is back: the journal and the moved-aside files stay until it is.
            done = journal.isEmpty() || rollBack(root, journal);
            if (done)
                QFile::remove(root + QLatin1Char('/') + kJournal);
            noteFailed(version, done ? QStringLiteral("The update couldn't replace %1 (another program, often antivirus, was using it), so Huginn kept the "
                                                      "version it had. Try again later with Check now in Settings › Updates and about.")
                                           .arg(QDir::toNativeSeparators(failed))
                                     : QStringLiteral("The update couldn't replace %1 (another program, often antivirus, was using it), and some of "
                                                      "Huginn's files couldn't be put back yet. Huginn finishes the update the next time it opens.")
                                           .arg(QDir::toNativeSeparators(failed)));
        } else {
            // What lib\ held that the new version doesn't (old plugins Qt would still find) goes, but only files an
            // update of Huginn put there (the list from last time). Nothing else in the folder is ever touched.
            const QString listPath = root + QLatin1Char('/') + kInstalled;
            QStringList listed;
            {
                QFile list(listPath);
                if (list.open(QIODevice::ReadOnly | QIODevice::Text))
                    listed = QString::fromUtf8(list.readAll()).split(QLatin1Char('\n'), Qt::SkipEmptyParts);
            }
            const bool ours = QFileInfo::exists(root + QStringLiteral("/lib/lib.manifest"));
            // No list yet (the first update since lists were kept): only the kinds of file Huginn's lib\ is made of.
            static const QStringList kKinds = {QStringLiteral("dll"), QStringLiteral("qml"), QStringLiteral("qmldir"), QStringLiteral("qmltypes"),
                                               QStringLiteral("qm"), QStringLiteral("js"), QStringLiteral("mjs"), QStringLiteral("metainfo")};
            QDirIterator old(root + QStringLiteral("/lib"), QDir::Files | QDir::Hidden, QDirIterator::Subdirectories);
            while (ours && old.hasNext()) {
                const QString f = old.next();
                if (f.endsWith(kAside, Qt::CaseInsensitive) || fresh.contains(QDir::cleanPath(f).toLower()))
                    continue;
                const QString rel = QDir(root).relativeFilePath(f);
                // Tor's folder left as it was (Update without Tor, or a file antivirus held): kept.
                if (torLeft && isTor(rel))
                    continue;
                const bool wasListed = listed.contains(rel, Qt::CaseInsensitive);
                if (wasListed || (listed.isEmpty() && kKinds.contains(QFileInfo(f).suffix().toLower())))
                    QFile::remove(f);
            }
            QStringList now;
            for (const QString &rel : std::as_const(files))
                if (rel.startsWith(QLatin1String("lib/"), Qt::CaseInsensitive))
                    now << rel;
            // Tor files kept from before stay on the list, so a later update can still replace or tidy them.
            if (torLeft)
                for (const QString &rel : std::as_const(listed))
                    if (isTor(rel) && !now.contains(rel, Qt::CaseInsensitive) && QFileInfo::exists(root + QLatin1Char('/') + rel))
                        now << rel;
            QSaveFile list(listPath);
            if (!list.open(QIODevice::WriteOnly | QIODevice::Text) || list.write(now.join(QLatin1Char('\n')).toUtf8() + '\n') < 0 || !list.commit())
                CrashLog::note(QStringLiteral("update: couldn't keep the list of lib files"));
            // Everything new is in place: only now the journal goes, then what was moved aside.
            QFile::remove(root + QLatin1Char('/') + kJournal);
            deleteAsides(root);
            QSettings st = Settings::store();
            st.remove(QStringLiteral("update/failed"));
            st.sync();
        }
        if (done) {
            QFile::remove(ready);   // update-staging goes at the next start (cleanUp)
            forgetStaged();
        }
        // "Update without Tor" is for this one swap only, whatever happened.
        if (skipTor) {
            QSettings st = Settings::store();
            st.remove(QStringLiteral("update/skipTor"));
            st.sync();
        }
        // Huginn from its own folder (updated, or as it was): it waits until this copy has ended, then removes
        // update-staging.
        if (!SingleInstance::startAgain(root + QStringLiteral("/Huginn.exe"), root))
            CrashLog::note(QStringLiteral("update: couldn't start Huginn after the update"));
        return true;
    }
    // Huginn's own folder with an update staged and ready (the restart after it didn't happen, or a swap that was cut
    // off): the staged copy is started to finish it, only when its list is the one this Huginn wrote (its SHA-256 is in
    // the settings, not in update-staging) and the staged Huginn.exe is exactly the file that was checked.
    QFile mark(stagingDir() + QLatin1Char('/') + kReady);
    if (!mark.exists())
        return false;
    const QString staged = stagingDir() + QStringLiteral("/Huginn.exe");
    const bool cutOff = QFileInfo::exists(appDir() + QLatin1Char('/') + kJournal);
    const Manifest manifest = readManifest(stagingDir());
    const int tries = Settings::store().value(kTriesKey, 0).toInt();
    // Handed over once already (three times for a swap that was cut off) and still not finished: given up, Huginn
    // starts as it is.
    const bool tried = tries >= (cutOff ? 3 : 1);
    if (tried || !manifest.ok || fileSha256(staged) != manifest.sums.value(QStringLiteral("Huginn.exe"))) {
        // Said on screen at the next start (Updater), and that version isn't downloaded again by itself.
        const QString version = Settings::store().value(QStringLiteral("update/trying")).toString();
        noteFailed(version, tried ? QStringLiteral("The update didn't finish: the new Huginn.exe didn't start (antivirus may have blocked it). "
                                                   "Huginn kept the version it had. Try again later with Check now in Settings › Updates and about.")
                                  : QStringLiteral("The update was changed or removed before it went in (antivirus may have quarantined it). "
                                                   "Huginn kept the version it had. Try again later with Check now in Settings › Updates and about."));
        QDir(stagingDir()).removeRecursively();
        forgetStaged();
        return false;
    }
    {
        QSettings st = Settings::store();
        st.setValue(kTriesKey, tries + 1);
        st.sync();
    }
    if (SingleInstance::startAgain(staged, stagingDir()))
        return true;
    noteFailed(Settings::store().value(QStringLiteral("update/trying")).toString(),
               QStringLiteral("The update couldn't start the new Huginn.exe (antivirus may have blocked it). Huginn kept the version it had. "
                              "Try again later with Check now in Settings › Updates and about."));
    QDir(stagingDir()).removeRecursively();
    forgetStaged();
    return false;
#else
    return false;
#endif
}

bool giveUpStaged(const QString &why)
{
#ifdef HUGINN_WINDOWS_PORTABLE
    const QString here = appDir();
    if (here.isEmpty() || QFileInfo(here).fileName().compare(kStaging, Qt::CaseInsensitive) != 0)
        return false;
    const QString root = QFileInfo(here).absolutePath();
    QFile::remove(here + QLatin1Char('/') + kReady);   // never tried again by itself
    forgetStaged();
    const bool blocked = !Portable::canWriteForUpdate();
    if (blocked) {
        QFile mark(here + QLatin1Char('/') + kBlocked);
        if (mark.open(QIODevice::WriteOnly))
            mark.write("1");
    }
    noteFailed(Settings::store().value(QStringLiteral("update/trying")).toString(),
               blocked ? blockedText(root)
                       : QStringLiteral("The update didn't finish (%1), so Huginn kept the version it had. Try again later with Check now in "
                                        "Settings › Updates and about.").arg(why.left(300)));
    if (!SingleInstance::startAgain(root + QStringLiteral("/Huginn.exe"), root))
        CrashLog::note(QStringLiteral("update: couldn't start Huginn again after a blocked update"));
    return true;
#else
    return false;
#endif
}

void install(const QString &zipPath, QObject *context, std::function<void(bool, bool, const QString &)> done)
{
    // On the next turn of the event loop, so "Installing…" is on screen while the zip unpacks.
    QTimer::singleShot(0, context, [zipPath, done] {
        const QString problem = folderProblem();
        if (!problem.isEmpty()) {
            done(false, false, problem);
            return;
        }
        const QString staging = stagingDir();
        QDir(staging).removeRecursively();
        if (!QDir().mkpath(staging)) {
            done(false, false, QStringLiteral("Couldn't make a folder for the update."));
            return;
        }
        QString why;
        const QStringList files = unpack(zipPath, staging, &why);
        if (files.isEmpty()) {
            qWarning("update: %s", qPrintable(why));
            QDir(staging).removeRecursively();
            done(false, false, why);
            return;
        }
        // Ready: every staged file's SHA-256, so only exactly these files finish the update. The list's own SHA-256
        // goes into Huginn's settings (in the data folder), never only beside the files.
        const QString staged = staging + QStringLiteral("/Huginn.exe");
        QByteArray list;
        bool summed = true;
        for (const QString &rel : files) {
            const QByteArray sum = fileSha256(staging + QLatin1Char('/') + rel);
            summed = summed && sum.size() == 64;
            list += sum + ' ' + rel.toUtf8() + '\n';
        }
        QFile mark(staging + QLatin1Char('/') + kReady);
        if (!summed || !mark.open(QIODevice::WriteOnly | QIODevice::Truncate) || mark.write(list) != list.size() || !mark.flush()) {
            QDir(staging).removeRecursively();
            done(false, false, QStringLiteral("Couldn't write the update. Is the disk full?"));
            return;
        }
        mark.close();
        {
            QSettings st = Settings::store();
            st.setValue(kStagedKey, QString::fromLatin1(QCryptographicHash::hash(list, QCryptographicHash::Sha256).toHex()));
            st.setValue(kTriesKey, 0);
            st.sync();
            if (st.status() != QSettings::NoError) {
                QDir(staging).removeRecursively();
                forgetStaged();
                done(false, false, QStringLiteral("Couldn't write the update. Is the disk full?"));
                return;
            }
        }
        QFile::remove(zipPath);

        // Round 17: Controlled folder access allows programs by path, so the staged Huginn.exe may not save where this one
        // can. It is asked first (--update-probe tries a write in Huginn's folder and its data folder, then ends); only one
        // that can is handed the update, so the restart never leaves nothing running.
        {
            QProcess probe;
            probe.setProgram(staged);
            probe.setArguments({QStringLiteral("--update-probe")});
            probe.setWorkingDirectory(staging);
            probe.start();
            const bool ran = probe.waitForStarted(10000) && probe.waitForFinished(20000);
            if (!ran)
                probe.kill();
            const bool canWrite = ran && probe.exitStatus() == QProcess::NormalExit && probe.exitCode() == kProbeOk;
            if (!canWrite) {
                const bool blocked = ran && probe.exitStatus() == QProcess::NormalExit && probe.exitCode() == kProbeBlocked;
                CrashLog::note(QStringLiteral("update: the staged Huginn.exe %1").arg(blocked ? QStringLiteral("can't save in Huginn's folder")
                                                                                         : QStringLiteral("didn't run")));
                QFile::remove(staging + QLatin1Char('/') + kReady);
                forgetStaged();
                if (blocked) {
                    QFile keep(staging + QLatin1Char('/') + kBlocked);
                    if (keep.open(QIODevice::WriteOnly))
                        keep.write("1");
                    done(false, false, blockedText(appDir()));
                } else {
                    QDir(staging).removeRecursively();
                    done(false, false, QStringLiteral("The new Huginn.exe didn't start (antivirus may have blocked or quarantined it), so Huginn "
                                                      "kept the version it had. Try again later with Check now in Settings › Updates and about."));
                }
                return;
            }
        }

        // Nothing of the running copy is touched. The staged Huginn.exe waits until this one has saved and closed,
        // puts its files in place and starts Huginn from its own folder (finishAtStart()).
        if (!SingleInstance::startAgain(staged, staging)) {
            done(true, false, QStringLiteral("The update is ready. Open Huginn again to finish it."));
            return;
        }
        done(true, true, QStringLiteral("Updated. Restarting…"));
    });
}

#endif
}
