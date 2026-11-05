#pragma once

#include <QImage>
#include <QString>

// A QR code for text, drawn dark on light at about side x side pixels (null image if it can't be made).
// Linux: KDE's Prison (already in the KDE runtime). Windows: libqrencode from vcpkg.
namespace Platform
{
QImage qrCode(const QString &text, int side);
}
