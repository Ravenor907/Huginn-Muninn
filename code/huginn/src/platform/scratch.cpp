#include "scratch.h"
#include "portable.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>

namespace Scratch
{
QString dir()
{
#ifdef Q_OS_WIN
    QString d = Portable::localDir();
    if (d.isEmpty())
        d = QDir::tempPath();
    d += QStringLiteral("/scratch");
#else
    QString d = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
    if (d.isEmpty())
        d = QDir::tempPath();
#endif
    QDir().mkpath(d);
    return d;
}

QString path(const QString &name)
{
    return dir() + QLatin1Char('/') + name;
}

void shred(const QString &path)
{
    QFile f(path);
    if (!f.exists())
        return;
    if (f.open(QIODevice::ReadWrite)) {
        const QByteArray zeros(64 * 1024, '\0');
        qint64 left = f.size();
        while (left > 0) {
            const qint64 n = f.write(zeros.constData(), qMin<qint64>(left, zeros.size()));
            if (n <= 0)
                break;
            left -= n;
        }
        f.flush();
        f.close();
    }
    QFile::remove(path);
}

void clearLeftovers()
{
#ifdef Q_OS_WIN
    // Only Huginn writes in this folder: everything in it is a leftover.
    QDir d(dir());
    const QFileInfoList files = d.entryInfoList(QDir::Files | QDir::Hidden | QDir::System);
    for (const QFileInfo &fi : files)
        shred(fi.absoluteFilePath());
#else
    // The runtime folder is shared with other files of Huginn's: only the ones written here.
    QDir d(dir());
    const QFileInfoList files = d.entryInfoList({QStringLiteral("huginn-voice.*"), QStringLiteral("huginn-video.*"), QStringLiteral("huginn-paste-*")}, QDir::Files);
    for (const QFileInfo &fi : files)
        shred(fi.absoluteFilePath());
#endif
}
}
