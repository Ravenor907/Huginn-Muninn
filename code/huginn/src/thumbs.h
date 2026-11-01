#pragma once

#include <QCache>
#include <QImage>
#include <QMutex>
#include <QQuickImageProvider>
#include <functional>

// Small pictures for album grids and the Shared page: "image://thumb/<photo id>". Each photo is read from the
// vault once, shrunk and kept in memory (never on disk), so a long Shared page never decodes every photo at
// full size. Reading and shrinking happen on Qt's loading thread, through Vault::anyThreadReader: it never waits
// on the main thread (which could hang Huginn while it closes).
class ThumbProvider : public QQuickImageProvider
{
public:
    explicit ThumbProvider(std::function<QByteArray(const QString &)> read);
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

private:
    std::function<QByteArray(const QString &)> m_read;
    QMutex m_lock;
    QCache<QString, QImage> m_cache;
};
