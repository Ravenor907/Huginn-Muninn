#include "savedmedia.h"

#include "groupvideo.h"
#include "platform/scratch.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFileInfo>
#include <QPointer>
#include <QQmlEngine>
#include <QStandardPaths>
#include <QThreadPool>
#include <QUrl>
#include <QUuid>
#include <QVideoFrame>
#include <QVideoSink>
#include <algorithm>
#include <cstdio>

namespace {
const char kShotPrefix[] = "huginn-camera-";

void registerType()
{
    qmlRegisterSingletonType<SavedMedia>("Huginn", 1, 0, "MediaFiles", [](QQmlEngine *, QJSEngine *) -> QObject * { return new SavedMedia; });
}
}
Q_COREAPP_STARTUP_FUNCTION(registerType)

SavedMedia::SavedMedia(QObject *parent)
    : QObject(parent)
{
}

QString SavedMedia::kind(const QString &name)
{
    const QString s = QFileInfo(name).suffix().toLower();
    if (s == QLatin1String("gif"))
        return QStringLiteral("gif");
    static const QStringList photos{QStringLiteral("jpg"), QStringLiteral("jpeg"), QStringLiteral("png"), QStringLiteral("webp"),
                                    QStringLiteral("bmp"), QStringLiteral("heic")};
    if (photos.contains(s))
        return QStringLiteral("photo");
    static const QStringList videos{QStringLiteral("mp4"), QStringLiteral("m4v"), QStringLiteral("mov")};
    if (videos.contains(s))
        return QStringLiteral("video");
    return QString();
}

QString SavedMedia::clock(int ms)
{
    if (ms <= 0)
        return QString();
    const int s = (ms + 500) / 1000, h = s / 3600, m = (s / 60) % 60, sec = s % 60;
    if (h > 0)
        return QStringLiteral("%1:%2:%3").arg(h).arg(m, 2, 10, QLatin1Char('0')).arg(sec, 2, 10, QLatin1Char('0'));
    return QStringLiteral("%1:%2").arg(m).arg(sec, 2, 10, QLatin1Char('0'));
}

QVariantList SavedMedia::find(const QStringList &roots)
{
    QList<QFileInfo> found;
    QStringList seen;
    int looked = 0;
    for (const QString &root : roots) {
        if (root.isEmpty() || seen.contains(root) || !QDir(root).exists())
            continue;
        seen << root;
        QDirIterator it(root, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext() && looked < kLook) {
            const QFileInfo f(it.next());
            ++looked;
            // Thumbnail caches and hidden folders aren't anyone's saved media.
            const QString inside = f.absoluteFilePath().mid(root.size());
            if (inside.contains(QLatin1String("/thumbnails")) || inside.contains(QLatin1String("/.")))
                continue;
            if (!kind(f.fileName()).isEmpty() && f.size() > 0)
                found << f;
        }
    }
    std::sort(found.begin(), found.end(), [](const QFileInfo &a, const QFileInfo &b) { return a.lastModified() > b.lastModified(); });
    QVariantList out;
    int probed = 0;
    for (const QFileInfo &f : found.mid(0, kMost)) {
        const QString k = kind(f.fileName());
        int ms = 0;
        if (k == QLatin1String("video") && probed < kProbe) {
            ++probed;
            QByteArray head;
            ms = GroupVideo::probeFile(f.absoluteFilePath(), &head).ms;
        }
        out << QVariantMap{{QStringLiteral("path"), f.absoluteFilePath()},
                           {QStringLiteral("url"), QUrl::fromLocalFile(f.absoluteFilePath()).toString()},
                           {QStringLiteral("name"), f.fileName()},
                           {QStringLiteral("kind"), k},
                           {QStringLiteral("time"), double(f.lastModified().toMSecsSinceEpoch())},
                           {QStringLiteral("size"), double(f.size())},
                           {QStringLiteral("ms"), ms}};
    }
    return out;
}

void SavedMedia::load()
{
    const QStringList roots{QStandardPaths::writableLocation(QStandardPaths::PicturesLocation),
                            QStandardPaths::writableLocation(QStandardPaths::MoviesLocation)};
    const int run = ++m_run;
    if (!m_loading) {
        m_loading = true;
        Q_EMIT loadingChanged();
    }
    QPointer<SavedMedia> self(this);
    QThreadPool::globalInstance()->start([self, roots, run] {
        const QVariantList list = find(roots);
        QMetaObject::invokeMethod(qApp, [self, list, run] {
            // Only the newest look answers (one opened again while the last was still looking).
            if (!self || run != self->m_run)
                return;
            self->m_loading = false;
            Q_EMIT self->loadingChanged();
            Q_EMIT self->ready(list);
        }, Qt::QueuedConnection);
    });
}

QString SavedMedia::keep(const QImage &image) const
{
    if (image.isNull())
        return QString();
    const QString path = Scratch::path(QString::fromLatin1(kShotPrefix) + QUuid::createUuid().toString(QUuid::WithoutBraces) + QStringLiteral(".jpg"));
    if (!image.save(path, "JPG", 92)) {
        Scratch::shred(path);
        return QString();
    }
    return path;
}

QString SavedMedia::keepFrame(QObject *videoOutput) const
{
    auto *sink = videoOutput ? qobject_cast<QVideoSink *>(videoOutput->property("videoSink").value<QObject *>()) : nullptr;
    if (!sink)
        return QString();
    QVideoFrame frame = sink->videoFrame();
    if (!frame.isValid())
        return QString();
    return keep(frame.toImage());
}

void SavedMedia::shred(const QString &path) const
{
    const QFileInfo f(path);
    if (path.isEmpty() || !f.fileName().startsWith(QLatin1String(kShotPrefix)) || QDir(f.absolutePath()) != QDir(Scratch::dir()))
        return;
    Scratch::shred(path);
}

bool SavedMedia::selfTest()
{
    bool ok = true;
    auto check = [&ok](bool c, const char *what) {
        if (!c) {
            std::fprintf(stderr, "SavedMedia self-test failed: %s\n", what);
            ok = false;
        }
    };
    check(kind(QStringLiteral("a.JPG")) == QLatin1String("photo"), "jpg is a photo");
    check(kind(QStringLiteral("/x/y.heic")) == QLatin1String("photo"), "heic is a photo");
    check(kind(QStringLiteral("b.gif")) == QLatin1String("gif"), "gif is a GIF");
    check(kind(QStringLiteral("c.MOV")) == QLatin1String("video"), "mov is a video");
    check(kind(QStringLiteral("d.m4v")) == QLatin1String("video"), "m4v is a video");
    check(kind(QStringLiteral("e.zip")).isEmpty(), "zip isn't media");
    check(kind(QStringLiteral("noext")).isEmpty(), "no suffix isn't media");
    check(clock(0).isEmpty(), "unknown length is empty");
    check(clock(18000) == QLatin1String("0:18"), "0:18");
    check(clock(134000) == QLatin1String("2:14"), "2:14");
    check(clock(299600) == QLatin1String("5:00"), "rounds to 5:00");
    check(clock(3725000) == QLatin1String("1:02:05"), "1:02:05");
    return ok;
}
