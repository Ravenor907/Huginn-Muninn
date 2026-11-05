#include "qrcode.h"

#include <Prison/Barcode>
#include <QSizeF>

namespace Platform
{
QImage qrCode(const QString &text, int side)
{
    auto code = Prison::Barcode::create(Prison::QRCode);
    if (!code)
        return {};
    code->setData(text);
    return code->toImage(QSizeF(side, side));
}
}
