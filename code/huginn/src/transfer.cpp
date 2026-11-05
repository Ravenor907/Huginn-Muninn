#include "transfer.h"
#include "settings.h"
#include "system.h"

#include "platform/qrcode.h"

#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QPainter>
#include <QStandardPaths>
#include <QUrl>

Transfer::Transfer(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
{
    connect(settings, &Settings::changed, this, &Transfer::stateChanged);
}

QString Transfer::saveName() const
{
    return System::displayPath(QUrl::fromLocalFile(m_settings->transferFolder()));
}

bool Transfer::customSave() const
{
    const QString downloads = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    return QDir(m_settings->transferFolder()) != QDir(downloads);
}

bool Transfer::pickSaveFolder()
{
    const QString dir = QFileDialog::getExistingDirectory(nullptr, QStringLiteral("Save files to"), m_settings->transferFolder());
    if (dir.isEmpty())
        return false;
    m_settings->setTransferFolder(dir);
    return true;
}

void Transfer::resetSaveFolder()
{
    m_settings->setTransferFolder(QStandardPaths::writableLocation(QStandardPaths::DownloadLocation));
}

void Transfer::share(const QVariantList &urls)
{
    for (const QVariant &v : urls) {
        const QUrl u = v.toUrl().isEmpty() ? QUrl(v.toString()) : v.toUrl();
        const QString p = u.isLocalFile() ? u.toLocalFile() : v.toString();
        if (QFileInfo(p).isFile() && !m_shared.contains(p))
            m_shared << p;
    }
    Q_EMIT itemsChanged();
}

QImage QrImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    const QString text = QUrl::fromPercentEncoding(id.toUtf8());
    const int side = requestedSize.width() > 0 ? requestedSize.width() : 512;
    QImage out(side, side, QImage::Format_RGB32);
    out.fill(QColor("#E4E1DA"));
    const QImage qr = Platform::qrCode(text, side);
    if (!qr.isNull()) {
        QPainter p(&out);
        p.setRenderHint(QPainter::SmoothPixmapTransform, false);
        p.drawImage(QRect(0, 0, side, side), qr);
    }
    if (size)
        *size = out.size();
    return out;
}
