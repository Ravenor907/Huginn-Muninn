#pragma once

#include <QByteArray>
#include <QHostAddress>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <functional>

// Link previews (cap m2, shared/docs/protocol.md 12.4), the same as Muninn's LinkPreview.kt: made on the sending
// computer only, never on the receiving one, so no website learns who got a link or when it was read. The
// page's title, description and picture come from its Open Graph tags (or its <title>); the picture is shrunk
// to a small JPEG with nothing but pixels.
namespace LinkPreview
{
// Fetches [url] and calls [done] with its "lp" object ({u, t, d, i}), or an empty one when the page couldn't
// be read or has no title. [ctx] going away cancels the call.
void fetch(const QString &url, QObject *ctx, std::function<void(const QJsonObject &)> done);
// From a page's HTML: {u, t, d} (no picture yet), and the picture's address in *image.
QJsonObject fromPage(const QString &url, const QString &html, QString *image);
// A picture made small: at most 240 px and 16 KB, a JPEG with nothing but pixels; empty if it can't be.
QByteArray thumbnail(const QByteArray &picture);
// Previews only reach the internet: false for this computer, the LAN, link-local, carrier-grade NAT and other
// addresses that aren't on the internet.
bool publicAddress(const QHostAddress &a);
// https only, and not a private address written out.
bool allowedUrl(const QString &url);
bool selfTest();
}
