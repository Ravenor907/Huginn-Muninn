#include "icons.h"
#include "busy.h"

#include <QHash>
#include <QMutexLocker>
#include <QPainter>
#include <QPen>
#include <QStringList>

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace
{
constexpr double kPi = 3.14159265358979323846;

// The shapes, on a 24-unit grid (the same as Icon.qml had them).
const QHash<QString, QString> &paths()
{
    static const QHash<QString, QString> p{
        {QStringLiteral("folder"), QStringLiteral("M3 6h7l2 2h9v11H3z")},
        {QStringLiteral("file"), QStringLiteral("M6 3h8l4 4v14H6z M14 3v4h4")},
        {QStringLiteral("image"), QStringLiteral("M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6")},
        {QStringLiteral("network"), QStringLiteral("M3 5h18v10H3z M8 19h8 M12 15v4")},
        {QStringLiteral("phone"), QStringLiteral("M8 3h8v18H8z M11 18h2")},
        // An upright handset (earpiece top left, mouthpiece bottom right), not the hang-up shape.
        {QStringLiteral("call"), QStringLiteral("M6.5 3h3l1.5 4.5-2.2 1.6a11 11 0 0 0 6.1 6.1l1.6-2.2 4.5 1.5v3a2 2 0 0 1-2.2 2C11.6 18.9 5.1 12.4 4.5 5.2A2 2 0 0 1 6.5 3z")},
        {QStringLiteral("hangup"), QStringLiteral("M3 15c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-2.5a10 10 0 0 0-6 0V16l-3.5 1.5z")},
        {QStringLiteral("hash"), QStringLiteral("M10 4l-2 16 M16 4l-2 16 M4.5 9h15 M3.5 15h15")},
        {QStringLiteral("speaker"), QStringLiteral("M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6")},
        // Notifications: a bell, and struck through for a muted chat.
        {QStringLiteral("bell"), QStringLiteral("M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 20a2 2 0 0 0 4 0")},
        {QStringLiteral("belloff"), QStringLiteral("M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 20a2 2 0 0 0 4 0 M3 3l18 18")},
        {QStringLiteral("crown"), QStringLiteral("M4 17l-1-9 5 4 4-7 4 7 5-4-1 9z M4 20h16")},
        {QStringLiteral("tag"), QStringLiteral("M3 12V4h8l10 10-8 8z M7.5 8a0.5 0.5 0 1 0 0.01 0")},
        {QStringLiteral("gear"), QStringLiteral("M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1")},
        {QStringLiteral("power"), QStringLiteral("M12 3v8 M7 6.5a7.5 7.5 0 1 0 10 0")},
        {QStringLiteral("send"), QStringLiteral("M4 12l16-8-6 16-2-6z M12 14l8-10")},
        {QStringLiteral("shield"), QStringLiteral("M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z")},
        {QStringLiteral("attach"), QStringLiteral("M20 12l-8 8a5 5 0 0 1-7-7l9-9a3.5 3.5 0 0 1 5 5l-9 9a2 2 0 0 1-3-3l8-8")},
        {QStringLiteral("people"), QStringLiteral("M9 11a3.5 3.5 0 1 0 0.01 0 M3 20a6 6 0 0 1 12 0 M16 11a3 3 0 1 0 0.01 0 M16 15a5 5 0 0 1 5 5")},
        // One person: Contacts on the rail.
        {QStringLiteral("person"), QStringLiteral("M12 4.5a4 4 0 1 0 0.01 0 M4.5 20.5a7.5 7.5 0 0 1 15 0z")},
        {QStringLiteral("check"), QStringLiteral("M5 12l5 5 9-10")},
        {QStringLiteral("back"), QStringLiteral("M15 5l-7 7 7 7")},
        {QStringLiteral("forward"), QStringLiteral("M9 5l7 7-7 7")},
        {QStringLiteral("lock"), QStringLiteral("M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3")},
        {QStringLiteral("info"), QStringLiteral("M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1")},
        {QStringLiteral("plus"), QStringLiteral("M12 5v14 M5 12h14")},
        {QStringLiteral("qr"), QStringLiteral("M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2")},
        {QStringLiteral("play"), QStringLiteral("M7 4l13 8-13 8z")},
        {QStringLiteral("stop"), QStringLiteral("M7 7h10v10H7z")},
        {QStringLiteral("mic"), QStringLiteral("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3")},
        {QStringLiteral("reply"), QStringLiteral("M10 6l-6 6 6 6 M4 12h10a6 6 0 0 1 6 6v1")},
        {QStringLiteral("smile"), QStringLiteral("M12 3a9 9 0 1 0 0.01 0 M8 14a5 5 0 0 0 8 0 M9 9.5h0.01 M15 9.5h0.01")},
        {QStringLiteral("edit"), QStringLiteral("M4 20h4L19 9l-4-4L4 16z M14 6l4 4")},
        {QStringLiteral("pin"), QStringLiteral("M9 3h6l-1 6 4 4H6l4-4z M12 13v8")},
        {QStringLiteral("thread"), QStringLiteral("M4 5h16v10H9l-5 4z M8 9h8 M8 12h5")},
        {QStringLiteral("search"), QStringLiteral("M11 4a7 7 0 1 0 0.01 0 M20 20l-3.5-3.5")},
        {QStringLiteral("close"), QStringLiteral("M5 5l14 14 M19 5L5 19")},
        {QStringLiteral("down"), QStringLiteral("M5 9l7 7 7-7")},
        {QStringLiteral("up"), QStringLiteral("M5 15l7-7 7 7")},
        // Save: an arrow into a tray.
        {QStringLiteral("save"), QStringLiteral("M12 4v11 M7 10l5 5 5-5 M4 17v3h16v-3")},
        // The desktop panels.
        {QStringLiteral("sidebar"), QStringLiteral("M3 5h18v14H3z M9 5v14")},
        {QStringLiteral("headphones"), QStringLiteral("M4 16v-4a8 8 0 0 1 16 0v4 M4 14h3v6H4z M17 14h3v6h-3z")},
        {QStringLiteral("deafened"), QStringLiteral("M4 16v-4a8 8 0 0 1 16 0v4 M4 14h3v6H4z M17 14h3v6h-3z M3 3l18 18")},
        {QStringLiteral("micoff"), QStringLiteral("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M3 3l18 18")},
        {QStringLiteral("video"), QStringLiteral("M3 7h12v10H3z M15 11l6-3v8l-6-3z")},
        {QStringLiteral("keyboard"), QStringLiteral("M3 7h18v10H3z M6.5 10.5h1 M10 10.5h1 M13.5 10.5h1 M17 10.5h1 M7 14h10")},
        {QStringLiteral("moon"), QStringLiteral("M19 14.5A8 8 0 1 1 9.5 5a6.5 6.5 0 0 0 9.5 9.5z")},
        {QStringLiteral("screen"), QStringLiteral("M3 5h18v11H3z M8 20h8 M12 16v4")},
        // Three dots in a column: an item's one menu.
        {QStringLiteral("more"), QStringLiteral("M12 4.5a1 1 0 1 0 0.01 0 M12 11a1 1 0 1 0 0.01 0 M12 17.5a1 1 0 1 0 0.01 0")},
    };
    return p;
}

// Reads SVG path data one piece at a time.
class Reader
{
public:
    explicit Reader(const QString &d) : m_d(d) {}
    void skip()
    {
        while (m_i < m_d.size() && (m_d.at(m_i).isSpace() || m_d.at(m_i) == QLatin1Char(',')))
            ++m_i;
    }
    bool atEnd() { skip(); return m_i >= m_d.size(); }
    // A command letter next (not a number).
    bool atCommand()
    {
        skip();
        return m_i < m_d.size() && m_d.at(m_i).isLetter() && m_d.at(m_i) != QLatin1Char('e') && m_d.at(m_i) != QLatin1Char('E');
    }
    QChar command() { skip(); return m_d.at(m_i++); }
    bool number(double *out)
    {
        skip();
        const int start = m_i;
        auto digits = [this] { int n = 0; while (m_i < m_d.size() && m_d.at(m_i).isDigit()) { ++m_i; ++n; } return n; };
        if (m_i < m_d.size() && (m_d.at(m_i) == QLatin1Char('-') || m_d.at(m_i) == QLatin1Char('+')))
            ++m_i;
        int n = digits();
        if (m_i < m_d.size() && m_d.at(m_i) == QLatin1Char('.')) {
            ++m_i;
            n += digits();
        }
        if (n == 0) {
            m_i = start;
            return false;
        }
        if (m_i < m_d.size() && (m_d.at(m_i) == QLatin1Char('e') || m_d.at(m_i) == QLatin1Char('E'))) {
            const int e = m_i;
            ++m_i;
            if (m_i < m_d.size() && (m_d.at(m_i) == QLatin1Char('-') || m_d.at(m_i) == QLatin1Char('+')))
                ++m_i;
            if (digits() == 0)
                m_i = e;
        }
        bool ok = false;
        *out = m_d.mid(start, m_i - start).toDouble(&ok);
        return ok;
    }
    // An arc's flag: one 0 or 1, which may be written right against the next number.
    bool flag(bool *out)
    {
        skip();
        if (m_i < m_d.size() && (m_d.at(m_i) == QLatin1Char('0') || m_d.at(m_i) == QLatin1Char('1'))) {
            *out = m_d.at(m_i++) == QLatin1Char('1');
            return true;
        }
        return false;
    }

private:
    const QString &m_d;
    int m_i = 0;
};

// An SVG elliptical arc (endpoint form, SVG 1.1 F.6.5) as cubic curves of at most a quarter turn each.
void arcTo(QPainterPath &p, QPointF from, double rx, double ry, double phiDeg, bool large, bool sweep, QPointF to)
{
    if (from == to)
        return;
    rx = std::abs(rx);
    ry = std::abs(ry);
    if (rx < 1e-9 || ry < 1e-9) {
        p.lineTo(to);
        return;
    }
    const double phi = phiDeg * kPi / 180.0, c = std::cos(phi), s = std::sin(phi);
    const double dx2 = (from.x() - to.x()) / 2, dy2 = (from.y() - to.y()) / 2;
    const double x1 = c * dx2 + s * dy2, y1 = -s * dx2 + c * dy2;
    const double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
    if (lambda > 1) {
        rx *= std::sqrt(lambda);
        ry *= std::sqrt(lambda);
    }
    const double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
    const double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
    double coef = den > 0 ? std::sqrt(std::max(0.0, num / den)) : 0;
    if (large == sweep)
        coef = -coef;
    const double cxp = coef * rx * y1 / ry, cyp = -coef * ry * x1 / rx;
    const double cx = c * cxp - s * cyp + (from.x() + to.x()) / 2, cy = s * cxp + c * cyp + (from.y() + to.y()) / 2;
    const double t1 = std::atan2((y1 - cyp) / ry, (x1 - cxp) / rx);
    double dt = std::atan2((-y1 - cyp) / ry, (-x1 - cxp) / rx) - t1;
    if (sweep && dt < 0)
        dt += 2 * kPi;
    else if (!sweep && dt > 0)
        dt -= 2 * kPi;
    const int n = std::max(1, int(std::ceil(std::abs(dt) / (kPi / 2) - 1e-9)));
    const double step = dt / n, k = 4.0 / 3.0 * std::tan(step / 4);
    // A point on the unit circle at angle a, and its tangent, onto the ellipse.
    auto at = [&](double ux, double uy) { return QPointF(cx + c * rx * ux - s * ry * uy, cy + s * rx * ux + c * ry * uy); };
    double a = t1;
    for (int i = 0; i < n; ++i) {
        const double b = a + step;
        const double ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b);
        const QPointF c1 = at(ca - k * sa, sa + k * ca);
        const QPointF c2 = at(cb + k * sb, sb - k * cb);
        const QPointF end = i == n - 1 ? to : at(cb, sb);
        p.cubicTo(c1, c2, end);
        a = b;
    }
}
}

IconProvider::IconProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_cache(4 * 1024 * 1024)   // in bytes (cost below): a few hundred icons
{
}

QPainterPath IconProvider::parseSvg(const QString &d)
{
    QPainterPath p;
    Reader r(d);
    QPointF cur, start, lastCtrl;
    QChar cmd, prev;
    while (!r.atEnd()) {
        if (r.atCommand())
            cmd = r.command();
        else if (cmd.isNull())
            break;   // numbers before any command: not a path
        const bool rel = cmd.isLower();
        const QChar up = cmd.toUpper();
        const QPointF base = rel ? cur : QPointF();
        double v[7];
        auto read = [&r, &v](int n) {
            for (int i = 0; i < n; ++i)
                if (!r.number(&v[i]))
                    return false;
            return true;
        };
        bool ok = true;
        if (up == QLatin1Char('Z')) {
            p.closeSubpath();
            cur = start;
            prev = up;
            cmd = QChar();   // a number right after Z starts nothing
            continue;
        } else if (up == QLatin1Char('M')) {
            if ((ok = read(2))) {
                cur = base + QPointF(v[0], v[1]);
                start = cur;
                p.moveTo(cur);
                // More pairs after a move are lines.
                cmd = rel ? QLatin1Char('l') : QLatin1Char('L');
            }
        } else if (up == QLatin1Char('L')) {
            if ((ok = read(2))) {
                cur = base + QPointF(v[0], v[1]);
                p.lineTo(cur);
            }
        } else if (up == QLatin1Char('H')) {
            if ((ok = read(1))) {
                cur.setX(rel ? cur.x() + v[0] : v[0]);
                p.lineTo(cur);
            }
        } else if (up == QLatin1Char('V')) {
            if ((ok = read(1))) {
                cur.setY(rel ? cur.y() + v[0] : v[0]);
                p.lineTo(cur);
            }
        } else if (up == QLatin1Char('C')) {
            if ((ok = read(6))) {
                const QPointF c1 = base + QPointF(v[0], v[1]), c2 = base + QPointF(v[2], v[3]);
                cur = base + QPointF(v[4], v[5]);
                p.cubicTo(c1, c2, cur);
                lastCtrl = c2;
            }
        } else if (up == QLatin1Char('S')) {
            if ((ok = read(4))) {
                const QPointF c1 = (prev == QLatin1Char('C') || prev == QLatin1Char('S')) ? cur * 2 - lastCtrl : cur;
                const QPointF c2 = base + QPointF(v[0], v[1]);
                cur = base + QPointF(v[2], v[3]);
                p.cubicTo(c1, c2, cur);
                lastCtrl = c2;
            }
        } else if (up == QLatin1Char('Q')) {
            if ((ok = read(4))) {
                const QPointF c = base + QPointF(v[0], v[1]);
                cur = base + QPointF(v[2], v[3]);
                p.quadTo(c, cur);
                lastCtrl = c;
            }
        } else if (up == QLatin1Char('T')) {
            if ((ok = read(2))) {
                const QPointF c = (prev == QLatin1Char('Q') || prev == QLatin1Char('T')) ? cur * 2 - lastCtrl : cur;
                cur = base + QPointF(v[0], v[1]);
                p.quadTo(c, cur);
                lastCtrl = c;
            }
        } else if (up == QLatin1Char('A')) {
            bool large = false, sweep = false;
            ok = r.number(&v[0]) && r.number(&v[1]) && r.number(&v[2]) && r.flag(&large) && r.flag(&sweep) && r.number(&v[3]) && r.number(&v[4]);
            if (ok) {
                const QPointF to = base + QPointF(v[3], v[4]);
                arcTo(p, cur, v[0], v[1], v[2], large, sweep, to);
                cur = to;
            }
        } else {
            break;   // a command this doesn't know
        }
        if (!ok)
            break;
        prev = up;
    }
    return p;
}

QPainterPath IconProvider::path(const QString &name)
{
    // Read once per name, then kept.
    static QMutex lock;
    static QHash<QString, QPainterPath> parsed;
    const QString known = paths().contains(name) ? name : QStringLiteral("file");
    QMutexLocker locked(&lock);
    auto it = parsed.constFind(known);
    if (it != parsed.cend())
        return it.value();
    const QPainterPath p = parseSvg(paths().value(known));
    parsed.insert(known, p);
    return p;
}

QImage IconProvider::draw(const QString &name, int size, const QColor &color, double lineWidth, QSize pixels)
{
    if (size <= 0)
        size = 24;
    if (!pixels.isValid() || pixels.isEmpty())
        pixels = QSize(size, size);
    QImage img(pixels, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter p(&img);
    p.setRenderHint(QPainter::Antialiasing);
    // As Icon.qml's Shape had it: the path scaled to the size, the line lineWidth wide (wider from 28 up), in the
    // window's units; the picture is that times the screen's scale.
    const double sx = double(pixels.width()) / 24.0, sy = double(pixels.height()) / 24.0;
    const double perUnit = double(pixels.width()) / size;   // pixels per window unit
    const double linePx = lineWidth * std::max(1.0, size / 28.0) * perUnit;
    p.scale(sx, sy);
    QPen pen(color);
    pen.setWidthF(linePx / sx);
    pen.setCapStyle(Qt::SquareCap);
    pen.setJoinStyle(Qt::MiterJoin);
    pen.setMiterLimit(2);
    p.setPen(pen);
    p.setBrush(Qt::NoBrush);
    p.drawPath(path(name));
    p.end();
    return img;
}

QImage IconProvider::requestImage(const QString &id, QSize *size, const QSize &requested)
{
    Busy::Scope busy("icons");
    // <name>/<size>/<rrggbb or aarrggbb>/<line width>
    const QStringList parts = id.split(QLatin1Char('/'));
    const QString name = parts.value(0);
    const int units = qBound(1, parts.value(1).toInt(), 2048);
    QColor color = QColor::fromString(QString(QLatin1Char('#') + parts.value(2)));
    if (!color.isValid())
        color = QColor(0x80, 0x80, 0x80);
    bool okWidth = false;
    double lineWidth = parts.value(3).toDouble(&okWidth);
    if (!okWidth || lineWidth <= 0)
        lineWidth = 1.6;
    QSize pixels = requested.isValid() && !requested.isEmpty() ? requested : QSize(units, units);
    pixels = pixels.boundedTo(QSize(1024, 1024)).expandedTo(QSize(1, 1));
    const QString key = id + QLatin1Char('@') + QString::number(pixels.width()) + QLatin1Char('x') + QString::number(pixels.height());
    {
        QMutexLocker locked(&m_lock);
        if (const QImage *had = m_cache.object(key)) {
            if (size)
                *size = had->size();
            return *had;
        }
    }
    const QImage img = draw(name, units, color, lineWidth, pixels);
    {
        QMutexLocker locked(&m_lock);
        m_cache.insert(key, new QImage(img), int(std::min<qsizetype>(img.sizeInBytes(), 4 * 1024 * 1024)));
        ++m_drawn;
    }
    if (size)
        *size = img.size();
    return img;
}

bool IconProvider::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("perf self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    // Every shape reads to something that stays on its 24-unit grid.
    bool shapes = true;
    for (auto it = paths().cbegin(); it != paths().cend(); ++it) {
        const QRectF b = parseSvg(it.value()).boundingRect();
        if (b.isEmpty() && b.width() <= 0 && b.height() <= 0)
            shapes = false;
        if (b.left() < -0.5 || b.top() < -0.5 || b.right() > 24.5 || b.bottom() > 24.5)
            shapes = false;
    }
    // A circle from two arcs comes out round: "M12 3a9 9 0 1 0 0.01 0" is nearly a whole circle of radius 9.
    const QRectF ring = parseSvg(QStringLiteral("M12 3a9 9 0 1 0 0.01 0")).boundingRect();
    const bool round = std::abs(ring.width() - 18) < 0.2 && std::abs(ring.height() - 18) < 0.2 && std::abs(ring.center().y() - 12) < 0.2;
    check("icon paths read (lines, curves and arcs) and stay on their grid", shapes && round);

    IconProvider p;
    QSize got;
    const QImage a = p.requestImage(QStringLiteral("check/24/7aa2f7/1.6"), &got, QSize(48, 48));
    const QImage b = p.requestImage(QStringLiteral("check/24/7aa2f7/1.6"), nullptr, QSize(48, 48));
    p.requestImage(QStringLiteral("check/24/ff0000/1.6"), nullptr, QSize(48, 48));
    int lit = 0;
    for (int y = 0; y < a.height(); ++y)
        for (int x = 0; x < a.width(); ++x)
            lit += qAlpha(a.pixel(x, y)) > 0;
    check("an icon is drawn once per look and reused (one picture shared, another color its own)",
          got == QSize(48, 48) && a.cacheKey() == b.cacheKey() && p.drawn() == 2 && lit > 40 && lit < 48 * 48 / 2);
    return all;
}
