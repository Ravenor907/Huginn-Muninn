#pragma once

#include <QCache>
#include <QColor>
#include <QImage>
#include <QMutex>
#include <QPainterPath>
#include <QQuickImageProvider>
#include <QString>

// Icon.qml's line icons, each drawn once per name, size, color and line width into a small picture
// ("image://icon/<name>/<size>/<rrggbb or aarrggbb>/<line width>") and reused: every Icon with the same look shares
// one texture (Qt Quick's atlas batches them), instead of each tracing its own path on every frame it changes.
// The shapes are SVG path data on a 24-unit grid, stroked with square caps and mitered corners.
class IconProvider : public QQuickImageProvider
{
public:
    IconProvider();
    QImage requestImage(const QString &id, QSize *size, const QSize &requested) override;

    // The path for a name on its 24-unit grid ("file" for one it doesn't know).
    static QPainterPath path(const QString &name);
    // SVG path data (M L H V C S Q T A Z, absolute and relative) as a QPainterPath.
    static QPainterPath parseSvg(const QString &d);
    // One icon at size (in the window's units) drawn into pixels (size times the screen's scale).
    static QImage draw(const QString &name, int size, const QColor &color, double lineWidth, QSize pixels);

    // --perf-selftest: icons are drawn once and handed out again, and the paths read right.
    static bool selfTest();
    int drawn() const { return m_drawn; }

private:
    QMutex m_lock;
    QCache<QString, QImage> m_cache;
    int m_drawn = 0;
};
