#include "thumbs.h"
#include "photos.h"

#include <QMutexLocker>

namespace
{
const int kSide = 320;
}

ThumbProvider::ThumbProvider(std::function<QByteArray(const QString &)> read)
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_read(std::move(read))
{
    m_cache.setMaxCost(48 * 1024 * 1024);   // bytes of pictures kept, about 120 thumbnails
}

QImage ThumbProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    Q_UNUSED(requestedSize)
    {
        QMutexLocker l(&m_lock);
        if (const QImage *hit = m_cache.object(id)) {
            if (size)
                *size = hit->size();
            return *hit;
        }
    }
    // Chat::blob: "photos/<id>" (an id never has a slash, so it can't reach outside the photos folder).
    if (id.isEmpty() || id.contains(QLatin1Char('/')) || id.contains(QLatin1Char('\\')))
        return {};
    QByteArray bytes = m_read(QStringLiteral("photos/") + id);
    // A GIF (cap a1) shows its first frame, and only once it passes the GIF limits again.
    const bool gif = Photos::isGif(bytes);
    QImage img = gif && Photos::cleanGif(bytes).isEmpty() ? QImage() : QImage::fromData(bytes, gif ? "GIF" : "JPEG");
    bytes.fill(0);
    if (img.isNull())
        return {};
    if (qMax(img.width(), img.height()) > kSide)
        img = img.scaled(kSide, kSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    if (size)
        *size = img.size();
    QMutexLocker l(&m_lock);
    m_cache.insert(id, new QImage(img), int(img.sizeInBytes()));
    return img;
}
