#pragma once

#include <QImage>
#include <QQuickItem>
#include <QRectF>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

#include <vector>

class QPainter;
class QSGGeometry;

// The Seasonal look's falling leaves and snow (Leaves.qml), moved and drawn here in one piece: every shape is drawn
// once (per shape and opacity, in the theme's and Color vision's colors) into one picture, and each frame all of them
// go to the screen together as one batch of textured squares (one scene-graph node; with Software drawing, one
// painted node). No item, canvas or script per leaf. At most 30 frames a second. Same motion as before: a glide
// sideways as it falls, rock and tumble, flip edge-on and back, a lean into the gust, seeds spinning and orbiting.
class Fall : public QQuickItem
{
    Q_OBJECT
    // Moving: false holds everything where it is.
    Q_PROPERTY(bool running READ running WRITE setRunning NOTIFY changed)
    // Frames a second (at most 30).
    Q_PROPERTY(int fps READ fps WRITE setFps NOTIFY changed)
    // Settings › Look › Size times the screen's fit.
    Q_PROPERTY(double sizeScale READ sizeScale WRITE setSizeScale NOTIFY changed)
    // Snow: six-armed crystals in every place, and the seed slots rock and flip like the rest.
    Q_PROPERTY(bool snow READ snow WRITE setSnow NOTIFY changed)
    // Where each one starts and how it moves (Leaves.qml make()), in drawing order.
    Q_PROPERTY(QVariantList plan READ plan WRITE setPlan NOTIFY planChanged)
    // The colors (Leaves.qml inks): leaves [[light, mid, dark] x7], seeds [[dark, mid, light] x6], glint, flake.
    Q_PROPERTY(QVariantMap inks READ inks WRITE setInks NOTIFY changed)
    // How many are drawn, and in how many batches (one; --perf-selftest and Labs).
    Q_PROPERTY(int drawn READ drawn NOTIFY planChanged)
    Q_PROPERTY(int batches READ batches NOTIFY planChanged)

public:
    explicit Fall(QQuickItem *parent = nullptr);

    bool running() const { return m_timer.isActive(); }
    void setRunning(bool on);
    int fps() const { return m_fps; }
    void setFps(int fps);
    double sizeScale() const { return m_scale; }
    void setSizeScale(double s);
    bool snow() const { return m_snow; }
    void setSnow(bool on);
    QVariantList plan() const { return m_plan; }
    void setPlan(const QVariantList &plan);
    QVariantMap inks() const { return m_inks; }
    void setInks(const QVariantMap &inks);
    int drawn() const { return int(m_leaves.size()); }
    int batches() const { return m_leaves.empty() ? 0 : 1; }

    // Not moving: everything in its place for one still moment, no wind.
    Q_INVOKABLE void settle();

    // The picture every leaf is cut from: one cell per (shape, opacity), each cellPx square with a gap around it.
    struct Atlas {
        QImage image;
        int cellPx = 0, stride = 0, cols = 0;
        QRectF cellRect(int cell) const;
    };
    // Drawn for the screen's scale (on the scene graph's thread while the window's thread waits).
    Atlas makeAtlas(double dpr) const;
    quint64 atlasGen() const { return m_atlasGen; }
    // The squares of every leaf as triangles (six corners each) into g, in drawing order.
    void fillGeometry(QSGGeometry *g, const Atlas &atlas, QSize atlasSize) const;
    // The same, as painter transforms (Software drawing): for each leaf, its transform and where it goes in it.
    struct Quad {
        double x, y, rot, flip;
        QRectF target;
        int cell;
    };
    std::vector<Quad> quads() const;
    // How far past the edges a leaf can be drawn.
    double maxSide() const;

    // --perf-selftest: hundreds of leaves in one batch of squares, from one picture with a cell per shape, and held
    // still while not running.
    static bool selfTest();

Q_SIGNALS:
    void changed();
    void planChanged();

protected:
    QSGNode *updatePaintNode(QSGNode *old, UpdatePaintNodeData *) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    struct Leaf {
        bool seed = false;
        int sym = 0;
        double op = 1;
        int cell = 0;
        double x0 = 0, speed = 0.05, sway = 0, swayT = 3, w = 24, rps = 0, phase = 0, dir = 1, orbit = 0, orbitT = 3.5;
        double spin = 0, tumbleT = 0, flipT = 0, glide = 0.6, zig = 0.8;
        bool hasSpin = false, hasTumble = false, hasFlipT = false;
        double wx = 0, lastFall = -1;
        // Where it is now: its turning point, its turn in degrees and its flip (1 flat on, near 0 edge-on).
        double x = 0, y = 0, rot = 0, flip = 1;
    };
    void step();
    void place(Leaf &q, double t, double dt, double gust);
    void placeAll(double t, double dt);
    double now() const;
    void sortCells();
    double sideOf(const Leaf &q) const;
    void drawShape(QPainter &p, int shapeKey) const;

    QTimer m_timer;
    int m_fps = 30;
    double m_scale = 1;
    bool m_snow = false;
    qint64 m_t0 = 0;
    QVariantList m_plan;
    QVariantMap m_inks;
    std::vector<Leaf> m_leaves;
    // What each cell holds: (shape key, opacity in percent), in cell order.
    std::vector<std::pair<int, int>> m_cells;
    // Bumped when the picture has to be drawn again (shapes, colors, size).
    quint64 m_atlasGen = 1;
};
