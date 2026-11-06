#include "bigvideo.h"

#include <QBuffer>
#include <QColor>
#include <QImage>
#include <QMediaPlayer>
#include <QMutexLocker>
#include <QPointer>
#include <QTimer>
#include <QUrl>
#include <QVideoFrame>
#include <QVideoSink>
#include <cstring>
#include <memory>

namespace BigVideo
{
QString dir(const QString &id) { return QStringLiteral("photos/") + id + QStringLiteral(".v"); }
QString piece(const QString &id, int i) { return dir(id) + QLatin1Char('/') + QStringLiteral("%1").arg(i, 5, 10, QLatin1Char('0')); }
QString sizeName(const QString &id) { return dir(id) + QStringLiteral("/size"); }

namespace
{
QByteArray jpeg(const QImage &img, int quality)
{
    QByteArray out;
    QBuffer buf(&out);
    if (!buf.open(QIODevice::WriteOnly) || !img.save(&buf, "JPEG", quality))
        return {};
    return out;
}
}

QByteArray blank()
{
    QImage img(16, 16, QImage::Format_RGB32);
    img.fill(QColor(20, 20, 24));
    return jpeg(img, 60);
}

Device::Device(qint64 total, std::function<QByteArray(int)> read, QObject *parent)
    : QIODevice(parent)
    , m_total(total)
    , m_read(std::move(read))
{
}

Device::~Device()
{
    QMutexLocker l(&m_lock);
    for (QByteArray &b : m_kept)
        b.fill(0);
    m_kept.clear();
}

bool Device::open(OpenMode mode)
{
    // Read only, and unbuffered: every read comes straight from the pieces at pos().
    if ((mode & QIODevice::WriteOnly) || m_total <= 0)
        return false;
    return QIODevice::open(QIODevice::ReadOnly | QIODevice::Unbuffered);
}

void Device::close()
{
    {
        QMutexLocker l(&m_lock);
        for (QByteArray &b : m_kept)
            b.fill(0);
        m_kept.clear();
        m_order.clear();
    }
    QIODevice::close();
}

bool Device::seek(qint64 pos)
{
    if (pos < 0 || pos > m_total)
        return false;
    return QIODevice::seek(pos);
}

QByteArray Device::get(int i)
{
    QMutexLocker l(&m_lock);
    const auto it = m_kept.constFind(i);
    if (it != m_kept.cend())
        return it.value();
    QByteArray b = m_read ? m_read(i) : QByteArray();
    if (b.isEmpty())
        return {};
    m_kept.insert(i, b);
    m_order << i;
    while (m_order.size() > 2) {
        QByteArray old = m_kept.take(m_order.takeFirst());
        old.fill(0);
    }
    return b;
}

qint64 Device::readData(char *data, qint64 maxlen)
{
    qint64 at = pos();
    if (at >= m_total)
        return 0;   // the end
    qint64 done = 0;
    while (done < maxlen && at < m_total) {
        const int i = int(at / kPiece);
        const QByteArray b = get(i);
        const qint64 inPiece = at - qint64(i) * kPiece;
        if (b.isEmpty() || inPiece >= b.size())
            break;
        const qint64 n = qMin(maxlen - done, qint64(b.size()) - inPiece);
        std::memcpy(data + done, b.constData() + inPiece, size_t(n));
        done += n;
        at += n;
    }
    return done == 0 ? -1 : done;   // a piece that isn't there: an error, never made-up bytes
}

void poster(QIODevice *dev, QObject *context, std::function<void(const QByteArray &)> done)
{
    auto *player = new QMediaPlayer(context);
    auto *sink = new QVideoSink(player);
    dev->setParent(player);
    auto ended = std::make_shared<bool>(false);
    QPointer<QMediaPlayer> p(player);
    // Once: the picture (or blank()), then the player and its device go.
    auto end = [p, ended, done](const QByteArray &jpg) {
        if (*ended)
            return;
        *ended = true;
        if (p) {
            p->stop();
            p->deleteLater();
        }
        done(jpg.isEmpty() ? blank() : jpg);
    };
    QObject::connect(sink, &QVideoSink::videoFrameChanged, player, [end](const QVideoFrame &frame) {
        if (!frame.isValid())
            return;
        QImage img = frame.toImage();
        if (img.isNull())
            return;
        if (qMax(img.width(), img.height()) > kPosterSide)
            img = img.scaled(kPosterSide, kPosterSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
        end(jpeg(img.convertToFormat(QImage::Format_RGB32), 82));
    });
    QObject::connect(player, &QMediaPlayer::errorOccurred, player, [end](QMediaPlayer::Error, const QString &) { end(QByteArray()); });
    QTimer::singleShot(8000, player, [end] { end(QByteArray()); });
    if (!dev->isOpen() && !dev->open(QIODevice::ReadOnly)) {
        QTimer::singleShot(0, player, [end] { end(QByteArray()); });
        return;
    }
    // No sound output: nothing is heard while the first frame is taken.
    player->setVideoSink(sink);
    player->setSourceDevice(dev, QUrl(QStringLiteral("video.mp4")));
    player->play();
}
}
