#include "thumbs.h"
#include "photos.h"

#include <QMutexLocker>

namespace
{
const int kSide = 320;
const int kMaxSide = 1280;
}

ThumbProvider::ThumbProvider(std::function<QByteArray(const QString &)> read)
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_read(std::move(read))
{
    m_cache.setMaxCost(48 * 1024 * 1024);   // bytes of pictures kept, about 120 thumbnails
}

QImage ThumbProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    // Asked for a size (a photo in a chat): big enough to fill that box, never bigger than kMaxSide; otherwise kSide.
    int side = kSide;
    if (requestedSize.isValid() && requestedSize.width() > 0 && requestedSize.height() > 0)
        side = qBound(64, qMax(requestedSize.width(), requestedSize.height()), kMaxSide);
    const QString key = side == kSide ? id : id + QLatin1Char('@') + QString::number(side);
    {
        QMutexLocker l(&m_lock);
        if (const QImage *hit = m_cache.object(key)) {
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
    // Its shorter side fills the box (a chat crops it); a grid's square still gets the whole picture within kSide.
    if (side == kSide) {
        if (qMax(img.width(), img.height()) > kSide)
            img = img.scaled(kSide, kSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    } else if (qMin(img.width(), img.height()) > side) {
        img = img.scaled(side, side, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
    }
    if (size)
        *size = img.size();
    QMutexLocker l(&m_lock);
    m_cache.insert(key, new QImage(img), int(img.sizeInBytes()));
    return img;
}
