#include "fall.h"
#include "busy.h"

#include <QColor>
#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QQuickWindow>
#include <QRadialGradient>
#include <QSGGeometry>
#include <QSGGeometryNode>
#include <QSGRenderNode>
#include <QSGRendererInterface>
#include <QSGTexture>
#include <QSGTextureMaterial>
#include <QSet>
#include <QThread>
#include <QTransform>

#include <algorithm>
#include <iterator>
#include <cmath>
#include <cstdio>
#include <map>
#include <utility>

namespace
{
constexpr double kTau = 6.283185307179586;
constexpr double kStill = 37.3;   // the moment everything holds at when not moving
constexpr int kGap = 2;           // transparent pixels around each cell of the picture
constexpr int kMaxCell = 192;     // a cell's largest side in pixels (bigger leaves are stretched from it)

// A gust everyone feels: 0 most of the time, rising to 1 for a few seconds now and then.
double gustAt(double t)
{
    const double g = std::sin(t * 0.21) * std::sin(t * 0.07 + 1.3);
    return g > 0 ? g * g : 0;
}

// ------------------------------------------------------------- the shapes (as Leaves.qml's Canvas drew them)
// Fifteen leaves: shape (maple, oak, elm) and color (light to dark of the gradient, which is also the vein color).
const int kLeafShape[15] = {0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2};
const int kLeafTone[15] = {0, 0, 1, 2, 3, 1, 0, 1, 0, 4, 5, 2, 6, 2, 6};
// The colors before the theme and Color vision have their say (Leaves.qml passes its own; these are for the self-test).
const char *const kTones[7][3] = {{"#f7d994", "#e8b45a", "#9a6a22"}, {"#f6c384", "#e59a4c", "#8d4f1c"}, {"#e88a5e", "#c45a2e", "#6e2c12"},
                                  {"#f0a26c", "#d6763a", "#7f3a16"}, {"#dd7a56", "#b9452a", "#5e2010"}, {"#cf9a55", "#a86a2a", "#5a3512"},
                                  {"#f2d57a", "#d9a93f", "#8c6a1d"}};
const char *const kSeedTones[6][3] = {{"#6a4a22", "#b89a5c", "#e9d9a8"}, {"#6a4a22", "#b89a5c", "#e9d9a8"}, {"#7a5a2a", "#c9a86a", "#e9d9a8"},
                                      {"#7a5a2a", "#c9a86a", "#e9d9a8"}, {"#8a6a33", "#d4b67a", "#e9d9a8"}, {"#7a5a2a", "#c9a86a", "#e9d9a8"}};
// The oak and elm outlines as points around the edge.
const double kOak[] = {29.0, 0.0, 28.0, 1.8, 24.0, 4.3, 23.1, 5.4, 23.1, 6.7, 24.5, 10.6, 24.2, 12.4, 22.0, 13.1, 15.8, 12.5, 15.1, 13.3, 14.1, 17.0, 12.9, 17.9, 10.5, 16.6, 6.0, 11.2, 2.2, 14.7, 1.3, 15.0, 0.6, 14.6, 0.2, 12.0, 0.9, 5.7, 0.0, 0.0, 0.9, -5.7, 0.2, -12.0, 0.6, -14.6, 1.3, -15.0, 2.2, -14.7, 6.0, -11.2, 10.5, -16.6, 12.9, -17.9, 14.1, -17.0, 15.1, -13.3, 15.8, -12.5, 22.0, -13.1, 24.2, -12.4, 24.5, -10.6, 23.1, -6.7, 23.1, -5.4, 24.0, -4.3, 28.0, -1.8};
const double kElm[] = {36.0, 0.0, 36.2, 1.5, 33.6, 3.1, 32.3, 4.9, 28.4, 5.6, 27.2, 6.8, 24.3, 6.7, 23.7, 7.9, 21.4, 7.5, 21.0, 8.7, 19.2, 8.0, 18.7, 9.2, 17.2, 8.2, 16.5, 9.5, 15.3, 8.4, 14.5, 9.6, 13.6, 8.4, 12.5, 9.6, 11.8, 8.3, 10.4, 9.4, 9.9, 8.1, 8.2, 9.0, 7.8, 7.7, 5.8, 8.3, 5.3, 7.0, 3.0, 7.1, 2.7, 5.8, 0.3, 5.3, 0.2, 3.8, -2.0, 2.6, -1.3, 0.9, -2.0, 0.0, -2.5, -0.9, -0.8, -2.4, -1.1, -4.0, 1.4, -4.9, 1.6, -6.3, 4.0, -6.5, 4.5, -7.8, 6.6, -7.4, 7.0, -8.7, 8.9, -8.0, 9.3, -9.2, 10.8, -8.2, 11.5, -9.5, 12.7, -8.4, 13.5, -9.6, 14.4, -8.4, 15.5, -9.6, 16.2, -8.3, 17.6, -9.4, 18.1, -8.1, 19.8, -9.0, 20.3, -7.7, 22.3, -8.3, 22.8, -7.1, 25.2, -7.4, 26.2, -6.2, 29.8, -6.0, 31.1, -4.7, 34.8, -3.3, 35.2, -1.1};
// Veins from the stem's base, as quadratic curves (control point, then end), and the spots (x, y, radius).
const double kVeins[3][5][4] = {
    {{19.5, 0, 35, 0}, {13.5, -10.5, 23, -21}, {13.5, 10.5, 23, 21}, {5.5, -11.5, 7, -23}, {5.5, 11.5, 7, 23}},
    {{15, 0, 26, 0}, {10, -5.5, 16, -11}, {10, 5.5, 16, 11}, {6, -6, 8, -12}, {6, 6, 8, 12}},
    {{18, 0, 32, 0}, {9, -4, 14, -8}, {9, 4, 14, 8}, {13, -3, 22, -6}, {13, 3, 22, 6}}};
struct Spot {
    double x, y, r;
};
const Spot kSpots0[] = {{7.8, -1.9, 1.1}, {8.2, -7, 0.8}, {18.1, -6.3, 1.2}, {16.6, -6.5, 1}, {13.2, -1.6, 1.4}};
const Spot kSpots1[] = {{6.3, 7.9, 0.9}, {9.2, -3.1, 0.9}};
const Spot kSpots2[] = {{17.6, -1.5, 1.1}, {7, -8.1, 0.7}, {8.2, -6.3, 1.2}};
std::pair<const Spot *, int> spotsOf(int shape)
{
    if (shape == 1)
        return {kSpots1, int(std::size(kSpots1))};
    if (shape == 2)
        return {kSpots2, int(std::size(kSpots2))};
    return {kSpots0, int(std::size(kSpots0))};
}

QPainterPath outline(int shape)
{
    QPainterPath p;
    p.setFillRule(Qt::WindingFill);
    if (shape == 0) {
        // The maple: five pointed lobes.
        p.moveTo(1, -2); p.quadTo(3.1, -5.5, 4, -9); p.quadTo(2.4, -12, 2, -15);
        p.lineTo(7, -23); p.lineTo(10, -18); p.quadTo(12.6, -19, 14, -20); p.quadTo(12.9, -16.5, 13, -13);
        p.quadTo(15.6, -14.5, 17, -16); p.lineTo(23, -21); p.lineTo(26, -15); p.quadTo(24.4, -12.5, 24, -10);
        p.quadTo(21.9, -8.5, 21, -7); p.quadTo(23.6, -7, 25, -7); p.quadTo(27.6, -8, 29, -9);
        p.quadTo(32.1, -6, 34, -3); p.lineTo(35, 0); p.lineTo(34, 3); p.quadTo(30.9, 6, 29, 9);
        p.quadTo(26.4, 8, 25, 7); p.quadTo(22.4, 7, 21, 7); p.quadTo(23.1, 8.5, 24, 10);
        p.quadTo(25.6, 12.5, 26, 15); p.lineTo(23, 21); p.lineTo(17, 16); p.quadTo(14.4, 14.5, 13, 13);
        p.quadTo(14.1, 16.5, 14, 20); p.quadTo(11.4, 19, 10, 18); p.lineTo(7, 23); p.lineTo(2, 15);
        p.quadTo(3.6, 12, 4, 9); p.quadTo(1.9, 5.5, 1, 2); p.closeSubpath();
        return p;
    }
    const double *pts = shape == 1 ? kOak : kElm;
    const int n = int(shape == 1 ? std::size(kOak) : std::size(kElm));
    p.moveTo(pts[0], pts[1]);
    for (int i = 2; i + 1 < n; i += 2)
        p.lineTo(pts[i], pts[i + 1]);
    p.closeSubpath();
    return p;
}

// Each crystal's strokes: (x1, y1, x2, y2) for each arm and each side branch.
std::vector<double> crystal(double r, std::initializer_list<double> twigs)
{
    std::vector<double> out;
    const double third = kTau / 6;
    for (int i = 0; i < 6; ++i) {
        const double a = i * third, c = std::cos(a), n = std::sin(a);
        out.insert(out.end(), {0, 0, c * r, n * r});
        for (double at : twigs) {
            const double len = r * (at > 0.6 ? 0.2 : 0.3);
            for (int side = -1; side <= 1; side += 2) {
                const double b = a + side * third;
                out.insert(out.end(), {c * r * at, n * r * at, c * r * at + std::cos(b) * len, n * r * at + std::sin(b) * len});
            }
        }
    }
    return out;
}
const std::vector<double> &flakeLines(int type)
{
    static const std::vector<double> lines[3] = {crystal(14, {0.5, 0.75}), crystal(11, {0.6}), crystal(8, {0.55})};
    return lines[std::clamp(type, 0, 2)];
}

QColor colorOf(const QVariant &v, const char *fallback)
{
    const QColor c = QColor::fromString(v.toString());
    return c.isValid() ? c : QColor::fromString(QLatin1String(fallback));
}
QPen pen(const QColor &c, double w)
{
    QPen p(c);
    p.setWidthF(w);
    p.setCapStyle(Qt::RoundCap);
    p.setJoinStyle(Qt::RoundJoin);
    return p;
}

// The shape key: a leaf's sym (0 to 14), a seed's (15 to 20), or in Snow 100 plus the crystal (0 big to 2 small).
int shapeKeyOf(bool snow, int sym)
{
    sym = std::clamp(sym, 0, 20);
    if (!snow)
        return sym;
    return 100 + (sym >= 15 ? 2 : kLeafShape[sym]);
}

// ------------------------------------------------------------- the scene-graph nodes
// Hardware drawing: every leaf as two triangles in one geometry, cut from one texture.
class LeafNode : public QSGGeometryNode
{
public:
    LeafNode()
    {
        auto *g = new QSGGeometry(QSGGeometry::defaultAttributes_TexturedPoint2D(), 0);
        g->setDrawingMode(QSGGeometry::DrawTriangles);
        g->setVertexDataPattern(QSGGeometry::StreamPattern);
        setGeometry(g);
        setFlag(QSGNode::OwnsGeometry);
        auto *m = new QSGTextureMaterial;
        m->setFiltering(QSGTexture::Linear);
        m->setMipmapFiltering(QSGTexture::Linear);
        setMaterial(m);
        setFlag(QSGNode::OwnsMaterial);
    }
    ~LeafNode() override { delete texture; }
    QSGTexture *texture = nullptr;
    quint64 gen = 0;
    double dpr = 0;
    Fall::Atlas atlas;   // its layout (the picture itself is in the texture)
    QSize atlasSize;
};

// Software drawing: one node that paints every leaf from the same picture.
class PaintedLeaves : public QSGRenderNode
{
public:
    explicit PaintedLeaves(QQuickWindow *w) : window(w) {}
    void render(const RenderState *state) override
    {
        QSGRendererInterface *rif = window ? window->rendererInterface() : nullptr;
        auto *p = rif ? static_cast<QPainter *>(rif->getResource(window, QSGRendererInterface::PainterResource)) : nullptr;
        if (!p || atlas.image.isNull())
            return;
        p->save();
        const QRegion *clip = state ? state->clipRegion() : nullptr;
        if (clip && !clip->isEmpty())
            p->setClipRegion(*clip, Qt::ReplaceClip);
        const QTransform base = matrix() ? matrix()->toTransform() : QTransform();
        p->setOpacity(inheritedOpacity());
        p->setRenderHint(QPainter::SmoothPixmapTransform);
        for (const Fall::Quad &q : quads) {
            QTransform t;
            t.translate(q.x, q.y);
            t.rotate(q.rot);
            t.scale(q.flip, 1);
            p->setTransform(t * base);
            p->drawImage(q.target, atlas.image, atlas.cellRect(q.cell));
        }
        p->restore();
    }
    RenderingFlags flags() const override { return BoundedRectRendering; }
    QRectF rect() const override { return bounds; }

    QQuickWindow *window = nullptr;
    quint64 gen = 0;
    double dpr = 0;
    Fall::Atlas atlas;
    std::vector<Fall::Quad> quads;
    QRectF bounds;
};
}

QRectF Fall::Atlas::cellRect(int cell) const
{
    if (cols <= 0)
        return QRectF();
    return QRectF(kGap + (cell % cols) * stride, kGap + (cell / cols) * stride, cellPx, cellPx);
}

Fall::Fall(QQuickItem *parent)
    : QQuickItem(parent)
    , m_t0(QDateTime::currentMSecsSinceEpoch())
{
    setFlag(ItemHasContents, true);
    m_timer.setTimerType(Qt::CoarseTimer);
    m_timer.setInterval(1000 / m_fps);
    connect(&m_timer, &QTimer::timeout, this, &Fall::step);
}

double Fall::now() const { return (QDateTime::currentMSecsSinceEpoch() - m_t0) / 1000.0; }

void Fall::setRunning(bool on)
{
    if (on == m_timer.isActive())
        return;
    if (on)
        m_timer.start();
    else
        m_timer.stop();
    Q_EMIT changed();
}

void Fall::setFps(int fps)
{
    fps = qBound(1, fps, 30);
    if (fps == m_fps)
        return;
    m_fps = fps;
    m_timer.setInterval(1000 / m_fps);
    Q_EMIT changed();
}

void Fall::setSizeScale(double s)
{
    if (qFuzzyCompare(s, m_scale))
        return;
    m_scale = s;
    ++m_atlasGen;   // the cells are drawn at the new size
    if (!running())
        placeAll(kStill, 0);
    update();
    Q_EMIT changed();
}

void Fall::setSnow(bool on)
{
    if (on == m_snow)
        return;
    m_snow = on;
    sortCells();
    if (!running())
        placeAll(kStill, 0);
    update();
    Q_EMIT changed();
}

void Fall::setInks(const QVariantMap &inks)
{
    if (inks == m_inks)
        return;
    m_inks = inks;
    ++m_atlasGen;   // a new theme or Color vision: the shapes in the new colors
    update();
    Q_EMIT changed();
}

void Fall::setPlan(const QVariantList &plan)
{
    if (plan == m_plan)
        return;
    Busy::Scope busy("Seasonal animation");
    m_plan = plan;
    std::vector<Leaf> next;
    next.reserve(plan.size());
    for (int i = 0; i < plan.size(); ++i) {
        const QVariantMap p = plan.at(i).toMap();
        auto num = [&p](const char *k, double fallback) { return p.value(QLatin1String(k), fallback).toDouble(); };
        Leaf q;
        q.seed = p.value(QStringLiteral("kind")).toString() == QLatin1String("seed");
        q.sym = int(num("sym", 0));
        q.op = std::clamp(num("op", 1), 0.0, 1.0);
        q.x0 = num("x0", 0);
        q.speed = num("speed", 0.05);
        q.sway = num("sway", 0);
        q.swayT = std::max(0.1, num("swayT", 3));
        q.w = num("w", 24);
        q.rps = num("rps", 0);
        q.phase = num("phase", 0);
        q.dir = num("dir", 1);
        q.orbit = num("orbit", 0);
        q.orbitT = std::max(0.1, num("orbitT", 3.5));
        q.hasSpin = p.contains(QStringLiteral("spin"));
        q.spin = num("spin", 0);
        q.hasTumble = p.contains(QStringLiteral("tumbleT"));
        q.tumbleT = std::max(0.1, num("tumbleT", 3));
        q.hasFlipT = p.contains(QStringLiteral("flipT"));
        q.flipT = std::max(0.1, num("flipT", 2.2));
        q.glide = num("glide", 0.6);
        q.zig = num("zig", 0.8);
        // The same place in the plan keeps its wind.
        if (size_t(i) < m_leaves.size()) {
            q.wx = m_leaves[i].wx;
            q.lastFall = m_leaves[i].lastFall;
        }
        next.push_back(q);
    }
    m_leaves = std::move(next);
    sortCells();
    const double t = running() ? now() : kStill;
    placeAll(t, 0);
    update();
    Q_EMIT planChanged();
}

// One cell per shape and opacity in the plan, in the order first met.
void Fall::sortCells()
{
    std::vector<std::pair<int, int>> cells;
    std::map<std::pair<int, int>, int> index;
    for (Leaf &q : m_leaves) {
        const std::pair<int, int> key{shapeKeyOf(m_snow, q.sym), int(std::lround(q.op * 100))};
        auto it = index.find(key);
        if (it == index.end()) {
            it = index.emplace(key, int(cells.size())).first;
            cells.push_back(key);
        }
        q.cell = it->second;
    }
    if (cells != m_cells) {
        m_cells = std::move(cells);
        ++m_atlasGen;
    }
}

void Fall::settle()
{
    if (running())
        return;
    placeAll(kStill, 0);
    update();
}

void Fall::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickItem::geometryChange(newGeometry, oldGeometry);
    if (newGeometry.size() == oldGeometry.size())
        return;
    if (!running())
        placeAll(kStill, 0);
    update();
}

void Fall::step()
{
    Busy::Scope busy("Seasonal animation");
    placeAll(now(), m_timer.interval() / 1000.0);
    update();
}

void Fall::placeAll(double t, double dt)
{
    const double g = gustAt(t);
    for (Leaf &q : m_leaves)
        place(q, t, dt, g);
}

double Fall::sideOf(const Leaf &q) const { return std::max(3.0, std::ceil((q.w > 0 ? q.w : 24) * m_scale)); }

double Fall::maxSide() const
{
    double m = 0;
    for (const Leaf &q : m_leaves)
        m = std::max(m, sideOf(q));
    return m;
}

// Where leaf q is at time t (seconds): its turning point, its turn in degrees and how far it faces you (1 flat on,
// near 0 edge-on), the same as Leaves.qml's pose() was.
void Fall::place(Leaf &q, double t, double dt, double gust)
{
    const double W = width(), H = height(), ss = m_scale;
    const double side = sideOf(q);
    const double tt = t + q.phase;
    // Top to bottom and round again: from 12% above the area to 13% below it, or by its own size for a big one.
    const double top = std::min(-0.12 * H, -side);
    const double drop = std::max(1.13 * H, H + side) - top;
    const double fallen = drop > 0 ? std::fmod(tt * q.speed * H, drop) : 0;
    // Back at the top: the wind's push starts over.
    if (fallen < q.lastFall)
        q.wx = 0;
    q.lastFall = fallen;
    double x = q.x0 * W + std::sin(tt / q.swayT * kTau) * q.sway * ss + std::sin(tt / (q.swayT * 2.7) * kTau) * q.sway * 0.4 * ss;
    double y = top + fallen;
    double rot = 0, flip = 1;
    if (q.seed && !m_snow) {
        rot = std::fmod(tt * q.rps * 360, 360);
        x += std::cos(tt / q.orbitT * kTau) * q.orbit * ss;
        y += std::sin(tt / q.orbitT * kTau) * q.orbit * 0.5 * ss;
        q.wx += gust * 70 * ss * dt;
    } else {
        // Rock with the sway, a slow tumble each way, and a lean into the gust (in Snow the seed slots too).
        const double spin = q.hasSpin ? q.spin : 16 * q.dir;
        const double tumbleT = q.hasTumble ? q.tumbleT : q.orbitT + 0.6;
        const double flipT = q.hasFlipT ? q.flipT : 2.2 + std::fmod(q.phase, 1.0);
        rot = spin * std::sin(tt / q.swayT * kTau) + std::sin(tt * 0.6) * 10 + q.dir * 18 * std::sin(tt / tumbleT * kTau) + gust * 28 * q.dir;
        flip = 0.55 + 0.45 * std::cos(tt / flipT * kTau);
        // Gliding sideways as it falls, and pushed along by the gust.
        x += q.dir * q.glide * q.zig * fallen * 0.16;
        q.wx += gust * q.zig * 110 * ss * dt;
    }
    x += q.wx;
    // Off one side by its own size comes back in on the other.
    const double span = W + side * 2;
    if (span > 0)
        x = std::fmod(std::fmod(x + side, span) + span, span) - side;
    q.x = x;
    q.y = y;
    q.rot = rot;
    q.flip = flip;
}

// ------------------------------------------------------------- drawing
// One shape at the origin of a 48 by 48 box whose stem's base sits at (12, 24) (the painter is already there).
void Fall::drawShape(QPainter &p, int key) const
{
    const QVariantList leafInks = m_inks.value(QStringLiteral("leaves")).toList();
    const QVariantList seedInks = m_inks.value(QStringLiteral("seeds")).toList();
    if (key >= 100) {
        // A snowflake in a leaf's or seed's place, its middle 12 right of the stem's base.
        const std::vector<double> &ln = flakeLines(key - 100);
        p.translate(12, 0);
        p.setOpacity(0.85);
        p.setPen(pen(colorOf(m_inks.value(QStringLiteral("flake")), "#eef4ff"), 1.8));
        p.setBrush(Qt::NoBrush);
        QPainterPath path;
        for (size_t i = 0; i + 3 < ln.size(); i += 4) {
            path.moveTo(ln[i], ln[i + 1]);
            path.lineTo(ln[i + 2], ln[i + 3]);
        }
        p.drawPath(path);
        return;
    }
    if (key >= 15) {
        // A maple seed, the seed itself at the origin.
        const int n = std::clamp(key - 15, 0, 5);
        const QVariantList tone = seedInks.value(n, seedInks.value(0)).toList();
        const QColor t0 = colorOf(tone.value(0), kSeedTones[n][0]), t1 = colorOf(tone.value(1), kSeedTones[n][1]),
                     t2 = colorOf(tone.value(2), kSeedTones[n][2]);
        QLinearGradient g(2, 0, 38, 0);
        g.setColorAt(0, t0);
        g.setColorAt(0.25, t1);
        g.setColorAt(1, t2);
        // The wing.
        p.setOpacity(0.95);
        p.setBrush(g);
        p.setPen(pen(t0, 0.5));
        QPainterPath wing;
        wing.moveTo(2, 0);
        wing.cubicTo(8, -9, 22, -11, 34, -7);
        wing.cubicTo(38, -5, 38, 1, 34, 3);
        wing.cubicTo(24, 7, 10, 7, 2, 2);
        wing.closeSubpath();
        p.drawPath(wing);
        // Its ribs.
        p.setOpacity(0.55);
        p.setBrush(Qt::NoBrush);
        p.setPen(pen(t0, 0.35));
        QPainterPath ribs;
        ribs.moveTo(4, -1); ribs.quadTo(18, -6, 33, -6);
        ribs.moveTo(4, 0); ribs.quadTo(18, -3, 33, -3);
        ribs.moveTo(4, 0.5); ribs.quadTo(18, 0, 33, -1);
        ribs.moveTo(4, 1); ribs.quadTo(18, 2, 33, 1);
        p.drawPath(ribs);
        // The seed itself, with a glint.
        p.setOpacity(1);
        p.setPen(Qt::NoPen);
        p.setBrush(t0);
        p.drawEllipse(QRectF(1 - 4.2, -3, 8.4, 6));
        p.setOpacity(0.7);
        p.setBrush(colorOf(m_inks.value(QStringLiteral("glint")), "#9a7a44"));
        p.drawEllipse(QRectF(-2.2, -2, 4.4, 2.4));
        return;
    }
    // A leaf, its stem's base at the origin.
    const int sym = std::clamp(key, 0, 14);
    const int shape = kLeafShape[sym], t = kLeafTone[sym];
    const QVariantList tone = leafInks.value(t).toList();
    const QColor light = colorOf(tone.value(0), kTones[t][0]), mid = colorOf(tone.value(1), kTones[t][1]), dark = colorOf(tone.value(2), kTones[t][2]);
    // The stem.
    p.setOpacity(1);
    p.setBrush(Qt::NoBrush);
    p.setPen(pen(dark, 1.2));
    QPainterPath stem;
    stem.moveTo(-7, 1.2);
    stem.quadTo(-3.5, 0.4, 0, 0);
    p.drawPath(stem);
    // The blade, lit from near the stem.
    QRadialGradient g(QPointF(7.2, -2.4), 31.2);
    g.setColorAt(0, light);
    g.setColorAt(0.55, mid);
    g.setColorAt(1, dark);
    p.setBrush(g);
    p.setPen(pen(dark, 0.35));
    p.drawPath(outline(shape));
    // Veins and a few spots.
    p.setOpacity(0.8);
    p.setBrush(Qt::NoBrush);
    p.setPen(pen(dark, 0.7));
    QPainterPath veins;
    for (const auto &v : kVeins[shape]) {
        veins.moveTo(0, 0);
        veins.quadTo(v[0], v[1], v[2], v[3]);
    }
    p.drawPath(veins);
    p.setOpacity(0.35);
    p.setPen(Qt::NoPen);
    p.setBrush(dark);
    const auto [sp, nSp] = spotsOf(shape);
    for (int i = 0; i < nSp; ++i)
        p.drawEllipse(QPointF(sp[i].x, sp[i].y), sp[i].r, sp[i].r);
}

Fall::Atlas Fall::makeAtlas(double dpr) const
{
    Busy::Scope busy("Seasonal animation");
    Atlas a;
    const int n = int(m_cells.size());
    if (n == 0)
        return a;
    a.cellPx = std::clamp(int(std::ceil(maxSide() * std::max(1.0, dpr))), 8, kMaxCell);
    a.stride = a.cellPx + 2 * kGap;
    a.cols = int(std::ceil(std::sqrt(double(n))));
    const int rows = (n + a.cols - 1) / a.cols;
    a.image = QImage(a.cols * a.stride, rows * a.stride, QImage::Format_ARGB32_Premultiplied);
    a.image.fill(Qt::transparent);
    QImage cell(a.cellPx, a.cellPx, QImage::Format_ARGB32_Premultiplied);
    QPainter out(&a.image);
    for (int i = 0; i < n; ++i) {
        // Drawn whole, then laid in at its opacity (as the leaf's item had it: overlaps inside a leaf don't add up).
        cell.fill(Qt::transparent);
        {
            QPainter p(&cell);
            p.setRenderHint(QPainter::Antialiasing);
            const double k = a.cellPx / 48.0;
            p.translate(12 * k, 24 * k);
            p.scale(k, k);
            drawShape(p, m_cells[i].first);
        }
        out.setOpacity(m_cells[i].second / 100.0);
        out.drawImage(a.cellRect(i).topLeft(), cell);
    }
    out.end();
    return a;
}

std::vector<Fall::Quad> Fall::quads() const
{
    std::vector<Quad> out;
    out.reserve(m_leaves.size());
    const double along = m_snow ? 0.5 : 0.25;
    for (const Leaf &q : m_leaves) {
        const double side = sideOf(q);
        out.push_back({q.x, q.y, q.rot, q.flip, QRectF(-side * along, -side * 0.5, side, side), q.cell});
    }
    return out;
}

void Fall::fillGeometry(QSGGeometry *g, const Atlas &atlas, QSize atlasSize) const
{
    const int n = int(m_leaves.size());
    if (g->vertexCount() != n * 6)
        g->allocate(n * 6);
    QSGGeometry::TexturedPoint2D *v = g->vertexDataAsTexturedPoint2D();
    const double aw = std::max(1, atlasSize.width()), ah = std::max(1, atlasSize.height());
    const std::vector<Quad> qs = quads();
    for (const Quad &q : qs) {
        const double rad = q.rot * kTau / 360.0, c = std::cos(rad), s = std::sin(rad);
        const QRectF src = atlas.cellRect(q.cell);
        const double u0 = src.left() / aw, u1 = src.right() / aw, v0 = src.top() / ah, v1 = src.bottom() / ah;
        // Flipped (the Scale), turned (the Rotation), then moved to its turning point, as the leaf's item was.
        auto corner = [&](double lx, double ly, double u, double t, QSGGeometry::TexturedPoint2D *to) {
            const double fx = lx * q.flip;
            to->set(float(q.x + fx * c - ly * s), float(q.y + fx * s + ly * c), float(u), float(t));
        };
        const QRectF r = q.target;
        QSGGeometry::TexturedPoint2D tl, tr, bl, br;
        corner(r.left(), r.top(), u0, v0, &tl);
        corner(r.right(), r.top(), u1, v0, &tr);
        corner(r.left(), r.bottom(), u0, v1, &bl);
        corner(r.right(), r.bottom(), u1, v1, &br);
        *v++ = tl; *v++ = tr; *v++ = bl;
        *v++ = bl; *v++ = tr; *v++ = br;
    }
}

QSGNode *Fall::updatePaintNode(QSGNode *old, UpdatePaintNodeData *)
{
    QQuickWindow *win = window();
    if (m_leaves.empty() || width() <= 0 || height() <= 0 || !win) {
        delete old;
        return nullptr;
    }
    const double dpr = win->effectiveDevicePixelRatio();
    QSGRendererInterface *rif = win->rendererInterface();
    if (rif && rif->graphicsApi() == QSGRendererInterface::Software) {
        auto *n = dynamic_cast<PaintedLeaves *>(old);
        if (!n) {
            delete old;
            n = new PaintedLeaves(win);
        }
        if (n->gen != m_atlasGen || n->dpr != dpr) {
            n->atlas = makeAtlas(dpr);
            n->gen = m_atlasGen;
            n->dpr = dpr;
        }
        n->quads = quads();
        const double m = maxSide();
        n->bounds = QRectF(-m, -m, width() + 2 * m, height() + 2 * m);
        n->markDirty(QSGNode::DirtyMaterial);
        return n;
    }
    auto *n = dynamic_cast<LeafNode *>(old);
    if (!n) {
        delete old;
        n = new LeafNode;
    }
    if (!n->texture || n->gen != m_atlasGen || n->dpr != dpr) {
        Atlas a = makeAtlas(dpr);
        QSGTexture *t = a.image.isNull() ? nullptr
                                         : win->createTextureFromImage(a.image, QQuickWindow::CreateTextureOptions(QQuickWindow::TextureHasAlphaChannel) | QQuickWindow::TextureHasMipmaps);
        if (!t) {
            delete n;
            return nullptr;
        }
        t->setFiltering(QSGTexture::Linear);
        t->setMipmapFiltering(QSGTexture::Linear);
        static_cast<QSGTextureMaterial *>(n->material())->setTexture(t);
        delete n->texture;
        n->texture = t;
        // The picture's size, for the texture coordinates; the texture has its own copy of it.
        n->atlasSize = a.image.size();
        a.image = QImage();
        n->atlas = a;
        n->gen = m_atlasGen;
        n->dpr = dpr;
        n->markDirty(QSGNode::DirtyMaterial);
    }
    fillGeometry(n->geometry(), n->atlas, n->atlasSize);
    n->markDirty(QSGNode::DirtyGeometry);
    return n;
}

// ------------------------------------------------------------------ --perf-selftest

bool Fall::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("perf self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    Fall f;
    f.setSize(QSizeF(1600, 900));
    // As Leaves.qml plans them: leaves at three depths (three opacities) in fifteen looks, and about 5 seeds in 23.
    QVariantList plan;
    const int nSeeds = 400 * 5 / 23, nLeaves = 400 - nSeeds;
    for (int i = 0; i < nLeaves; ++i) {
        const int depth = (i / 15) % 3;   // every look at every depth
        plan << QVariantMap{{QStringLiteral("kind"), QStringLiteral("leaf")}, {QStringLiteral("sym"), i % 15}, {QStringLiteral("x0"), (i % 97) / 97.0},
                            {QStringLiteral("depth"), depth}, {QStringLiteral("speed"), 0.03 + depth * 0.02}, {QStringLiteral("sway"), 20},
                            {QStringLiteral("swayT"), 3.0}, {QStringLiteral("w"), 17 + (i % 23)}, {QStringLiteral("spin"), 12},
                            {QStringLiteral("flipT"), 2.2}, {QStringLiteral("phase"), i * 0.37}, {QStringLiteral("dir"), i % 2 ? 1 : -1},
                            {QStringLiteral("glide"), 0.8}, {QStringLiteral("tumbleT"), 3.1}, {QStringLiteral("zig"), 1.0},
                            {QStringLiteral("op"), depth == 0 ? 0.6 : depth == 1 ? 0.85 : 1.0}};
    }
    for (int i = 0; i < nSeeds; ++i)
        plan << QVariantMap{{QStringLiteral("kind"), QStringLiteral("seed")}, {QStringLiteral("sym"), 15 + i % 6}, {QStringLiteral("x0"), (i % 41) / 41.0},
                            {QStringLiteral("speed"), 0.07}, {QStringLiteral("sway"), 12}, {QStringLiteral("swayT"), 4.5}, {QStringLiteral("w"), 22},
                            {QStringLiteral("rps"), 2.0}, {QStringLiteral("phase"), i * 0.53}, {QStringLiteral("orbit"), 12},
                            {QStringLiteral("orbitT"), 3.5}, {QStringLiteral("op"), 0.9}};
    f.setPlan(plan);
    f.settle();

    // One batch: a single geometry with every leaf's two triangles, cut from one picture with a cell per look.
    const Atlas a = f.makeAtlas(1.0);
    QSGGeometry g(QSGGeometry::defaultAttributes_TexturedPoint2D(), 0);
    f.fillGeometry(&g, a, a.image.size());
    bool inPicture = true;
    const QSGGeometry::TexturedPoint2D *v = g.vertexDataAsTexturedPoint2D();
    for (int i = 0; i < g.vertexCount(); ++i)
        inPicture = inPicture && v[i].tx >= 0 && v[i].tx <= 1 && v[i].ty >= 0 && v[i].ty <= 1 && std::isfinite(v[i].x) && std::isfinite(v[i].y);
    // Every cell has its shape in it.
    bool cellsDrawn = !a.image.isNull();
    for (int c = 0; cellsDrawn && c < int(f.m_cells.size()); ++c) {
        const QRect r = a.cellRect(c).toRect();
        int lit = 0;
        for (int y = r.top(); y <= r.bottom(); y += 2)
            for (int x = r.left(); x <= r.right(); x += 2)
                lit += qAlpha(a.image.pixel(x, y)) > 0;
        cellsDrawn = lit > 4;
    }
    const int cells = int(f.m_cells.size());   // 15 looks x 3 depths, and 6 seeds
    std::printf("perf self-test: Seasonal: %d leaves and seeds, %d batch, %d cells in one %dx%d picture\n", f.drawn(), f.batches(), cells,
                a.image.width(), a.image.height());
    check("Seasonal leaves draw as one batch (one geometry, one picture, a cell per look)",
          f.drawn() == 400 && f.batches() == 1 && g.vertexCount() == 400 * 6 && cells == 15 * 3 + 6 && inPicture && cellsDrawn);

    // Held still (Less motion, Battery saver, in the background): nothing moves between frames.
    const auto positions = [&f, &a] {
        QSGGeometry h(QSGGeometry::defaultAttributes_TexturedPoint2D(), 0);
        f.fillGeometry(&h, a, a.image.size());
        std::vector<float> out;
        const QSGGeometry::TexturedPoint2D *p = h.vertexDataAsTexturedPoint2D();
        for (int i = 0; i < h.vertexCount(); ++i)
            out.insert(out.end(), {p[i].x, p[i].y});
        return out;
    };
    const auto wait = [](int ms) {
        QElapsedTimer t;
        t.start();
        while (t.elapsed() < ms) {
            QCoreApplication::processEvents(QEventLoop::AllEvents, 10);
            QThread::msleep(5);
        }
    };
    const std::vector<float> still = positions();
    wait(150);
    const bool held = positions() == still;
    f.setRunning(true);
    wait(200);
    f.setRunning(false);
    const bool moved = positions() != still;
    // Snow: the same places, crystals in three sizes and three depths (and the seeds' at their own opacity).
    f.setSnow(true);
    const int snowCells = int(f.m_cells.size());
    check("Seasonal holds still while stopped, moves while running, and Snow needs only its crystals",
          held && moved && snowCells <= 3 * 4 && f.batches() == 1);
    return all;
}
