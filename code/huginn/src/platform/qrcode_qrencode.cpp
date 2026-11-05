#include "qrcode.h"

#include <QColor>
#include <QPainter>
#include <qrencode.h>

namespace Platform
{
QImage qrCode(const QString &text, int side)
{
    const QByteArray utf8 = text.toUtf8();
    QRcode *code = QRcode_encodeString(utf8.constData(), 0, QR_ECLEVEL_M, QR_MODE_8, 1);
    if (!code)
        return {};
    // A quiet zone of four modules (what scanners expect), then whole pixels per module, centred.
    constexpr int quiet = 4;
    const int modules = code->width + 2 * quiet;
    const int scale = qMax(1, side / modules);
    const int drawn = modules * scale;
    const int offset = (qMax(side, drawn) - drawn) / 2;
    QImage img(qMax(side, drawn), qMax(side, drawn), QImage::Format_RGB32);
    img.fill(Qt::white);
    QPainter p(&img);
    p.setPen(Qt::NoPen);
    p.setBrush(Qt::black);
    for (int y = 0; y < code->width; ++y)
        for (int x = 0; x < code->width; ++x)
            if (code->data[y * code->width + x] & 1)
                p.drawRect(offset + (x + quiet) * scale, offset + (y + quiet) * scale, scale, scale);
    p.end();
    QRcode_free(code);
    return img;
}
}
