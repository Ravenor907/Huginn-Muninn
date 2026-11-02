#include "winupdate.h"

#include <QObject>
#include <QRegularExpression>

#ifdef Q_OS_WIN
#include "singleinstance.h"

#include "crashlog.h"
#include "../settings.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
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
// In update-staging once the update is unpacked and checked: the SHA-256 of the staged Huginn.exe.
const QString kReady = QStringLiteral("update.ready");
const QString kStaging = QStringLiteral("update-staging");
// In lib\: every file the last update put there (one relative path a line). Only those are ever deleted by the next one.
const QString kInstalled = QStringLiteral("lib/huginn-files.txt");

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

struct Moved {
    QString target, aside;   // aside empty: there was no old file
};

// Puts back what was moved, after a failed swap.
void rollBack(const QList<Moved> &moved)
{
    for (auto it = moved.crbegin(); it != moved.crend(); ++it) {
        QFile::remove(it->target);
        if (!it->aside.isEmpty())
            QFile::rename(it->aside, it->target);
    }
}

// Moves a file aside so a new one can take its name; true if the name is free afterwards. Only for files no running
// program uses (the swap runs from the staged copy, after the old one closed).
bool moveAside(const QString &target, QString *aside)
{
    aside->clear();
    if (!QFileInfo::exists(target))
        return true;
    const QString to = target + kAside;
    QFile::remove(to);
    if (QFile::rename(target, to)) {
        *aside = to;
        return true;
    }
    return QFile::remove(target);
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
    // A staged update that is ready is finished by finishAtStart(), not removed.
    if (!QFileInfo::exists(stagingDir() + QLatin1Char('/') + kReady))
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
        QFile::remove(ready);   // once only, whatever happens below
        const QStringList files = filesUnder(staging);
        QList<Moved> moved;
        QSet<QString> fresh;
        QString failed;
        for (const QString &rel : files) {
            const QString target = root + QLatin1Char('/') + rel;
            fresh.insert(QDir::cleanPath(target).toLower());
            QDir().mkpath(QFileInfo(target).absolutePath());
            Moved m{target, {}};
            if (!retry([&] { return moveAside(target, &m.aside); }) || !QFile::copy(staging + QLatin1Char('/') + rel, target)) {
                if (!m.aside.isEmpty())
                    QFile::rename(m.aside, target);
                failed = rel;
                break;
            }
            moved << m;
        }
        if (!failed.isEmpty()) {
            rollBack(moved);
            noteFailed(QCoreApplication::applicationVersion(),
                       QStringLiteral("The update couldn't replace %1 (another program, often antivirus, was using it), so Huginn kept the "
                                      "version it had. Try again later with Check now in Settings › Updates and about.")
                           .arg(QDir::toNativeSeparators(failed)));
        } else {
            // What lib\ held that the new version doesn't (old plugins Qt would still find) goes, but only files an
            // update of Huginn put there (the list from last time). Nothing else in the folder is ever touched.
            const QString listPath = root + QLatin1Char('/') + kInstalled;
            QStringList before;
            {
                QFile list(listPath);
                if (list.open(QIODevice::ReadOnly | QIODevice::Text))
                    before = QString::fromUtf8(list.readAll()).split(QLatin1Char('\n'), Qt::SkipEmptyParts);
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
                const bool listed = before.contains(rel, Qt::CaseInsensitive);
                if (listed || (before.isEmpty() && kKinds.contains(QFileInfo(f).suffix().toLower())))
                    QFile::remove(f);
            }
            QStringList now;
            for (const QString &rel : files)
                if (rel.startsWith(QLatin1String("lib/"), Qt::CaseInsensitive))
                    now << rel;
            QSaveFile list(listPath);
            if (!list.open(QIODevice::WriteOnly | QIODevice::Text) || list.write(now.join(QLatin1Char('\n')).toUtf8() + '\n') < 0 || !list.commit())
                CrashLog::note(QStringLiteral("update: couldn't keep the list of lib files"));
            deleteAsides(root);
            QSettings st = Settings::store();
            st.remove(QStringLiteral("update/failed"));
            st.sync();
        }
        // Huginn from its own folder (updated, or as it was): it waits until this copy has ended, then removes
        // update-staging.
        if (!SingleInstance::startAgain(root + QStringLiteral("/Huginn.exe"), root))
            CrashLog::note(QStringLiteral("update: couldn't start Huginn after the update"));
        return true;
    }
    // Huginn's own folder with an update staged and ready (the restart after it didn't happen): the staged copy is
    // started to finish it, if it is exactly the file that was checked.
    QFile mark(stagingDir() + QLatin1Char('/') + kReady);
    if (!mark.exists())
        return false;
    const QString staged = stagingDir() + QStringLiteral("/Huginn.exe");
    QByteArray want;
    if (mark.open(QIODevice::ReadOnly)) {
        want = mark.read(256).trimmed();
        mark.close();
    }
    // Tried from here once already (the staged copy didn't get as far as finishing): given up, Huginn starts as it is.
    const bool tried = want.endsWith(" tried");
    want = want.left(64);
    if (tried || want.size() != 64 || fileSha256(staged) != want) {
        // Said on screen at the next start (Updater), and that version isn't downloaded again by itself.
        const QString version = Settings::store().value(QStringLiteral("update/trying")).toString();
        noteFailed(version, tried ? QStringLiteral("The update didn't finish: the new Huginn.exe didn't start (antivirus may have blocked it). "
                                                   "Huginn kept the version it had. Try again later with Check now in Settings › Updates and about.")
                                  : QStringLiteral("The update was changed or removed before it went in (antivirus may have quarantined it). "
                                                   "Huginn kept the version it had. Try again later with Check now in Settings › Updates and about."));
        QDir(stagingDir()).removeRecursively();
        return false;
    }
    if (mark.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        mark.write(want + " tried");
        mark.close();
    }
    if (SingleInstance::startAgain(staged, stagingDir()))
        return true;
    noteFailed(Settings::store().value(QStringLiteral("update/trying")).toString(),
               QStringLiteral("The update couldn't start the new Huginn.exe (antivirus may have blocked it). Huginn kept the version it had. "
                              "Try again later with Check now in Settings › Updates and about."));
    QDir(stagingDir()).removeRecursively();
    return false;
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
        // Ready: the staged Huginn.exe's SHA-256, so only that exact file finishes the update.
        const QString staged = staging + QStringLiteral("/Huginn.exe");
        const QByteArray sum = fileSha256(staged);
        QFile mark(staging + QLatin1Char('/') + kReady);
        if (sum.isEmpty() || !mark.open(QIODevice::WriteOnly | QIODevice::Truncate) || mark.write(sum) != sum.size() || !mark.flush()) {
            QDir(staging).removeRecursively();
            done(false, false, QStringLiteral("Couldn't write the update. Is the disk full?"));
            return;
        }
        mark.close();
        QFile::remove(zipPath);

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
