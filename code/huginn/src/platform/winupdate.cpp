#include "winupdate.h"

#include <QObject>
#include <QRegularExpression>

#ifdef Q_OS_WIN
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QProcess>
#include <QSet>
#include <QStringList>
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
// What an update moves aside; deleted at the next start.
const QString kAside = QStringLiteral(".huginn-old");

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
QString stagingDir() { return appDir() + QStringLiteral("/update-staging"); }

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

// Moves a file aside so a new one can take its name; true if the name is free afterwards.
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
        return QStringLiteral("Huginn can't update itself where it is (%1). Move the Huginn folder somewhere of your own, "
                              "like Documents or the Desktop, and try again.")
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
    if (QFileInfo::exists(lib + QStringLiteral("/Huginn.exe"))) {
        QString aside;
        moveAside(lib + QStringLiteral("/Huginn.exe"), &aside);
        QFile::remove(aside);
    }
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
        const QString root = appDir(), staging = stagingDir();
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

        // Swap: each old file is moved aside (fine even while it runs) and the new one moved in.
        QList<Moved> moved;
        QSet<QString> fresh;
        for (const QString &rel : files) {
            const QString target = root + QLatin1Char('/') + rel;
            fresh.insert(QDir::cleanPath(target).toLower());
            QDir().mkpath(QFileInfo(target).absolutePath());
            Moved m{target, {}};
            if (!moveAside(target, &m.aside) || !QFile::rename(staging + QLatin1Char('/') + rel, target)) {
                if (!m.aside.isEmpty())
                    QFile::rename(m.aside, target);
                rollBack(moved);
                QDir(staging).removeRecursively();
                qWarning("update: couldn't replace %s", qPrintable(rel));
                done(false, false, QStringLiteral("Couldn't replace %1. Close anything using the Huginn folder and try again.").arg(QDir::toNativeSeparators(rel)));
                return;
            }
            moved << m;
        }
        // Whatever lib\ held that the new version doesn't (old plugins Qt would still find) goes aside too.
        QDirIterator old(root + QStringLiteral("/lib"), QDir::Files | QDir::Hidden, QDirIterator::Subdirectories);
        while (old.hasNext()) {
            const QString f = old.next();
            if (f.endsWith(kAside, Qt::CaseInsensitive) || fresh.contains(QDir::cleanPath(f).toLower()))
                continue;
            QString aside;
            moveAside(f, &aside);
        }
        QDir(staging).removeRecursively();
        QFile::remove(zipPath);

        // The new Huginn.exe in its own folder. It waits until this one has saved and closed.
        if (!SingleInstance::startAgain(root + QStringLiteral("/Huginn.exe"), root)) {
            done(true, false, QStringLiteral("Updated. Open Huginn again."));
            return;
        }
        done(true, true, QStringLiteral("Updated. Restarting…"));
    });
}

#endif
}
