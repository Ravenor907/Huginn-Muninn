#pragma once

#include <QHash>
#include <QMetaProperty>
#include <QObject>
#include <QPointer>
#include <QQuickItem>
#include <QTimer>
#include <QVariantMap>

// Moves the Seasonal look's falling leaves and snow (Leaves.qml) from C++, at most 30 times a second: each leaf is a
// small Canvas drawn once, inside a zero-size item at its turning point, with a Scale (its flip) and a Rotation (its
// turn). Only their x, y, angle and xScale change; no script runs per frame. Same motion as before: a glide sideways
// as it falls, rock and tumble, flip edge-on and back, a lean into the gust, seeds spinning and orbiting.
class Fall : public QObject
{
    Q_OBJECT
    // Moving: false holds everything where it is.
    Q_PROPERTY(bool running READ running WRITE setRunning NOTIFY changed)
    // Frames a second (at most 30).
    Q_PROPERTY(int fps READ fps WRITE setFps NOTIFY changed)
    Q_PROPERTY(double areaWidth MEMBER m_w NOTIFY changed)
    Q_PROPERTY(double areaHeight MEMBER m_h NOTIFY changed)
    // Settings › Look › Size times the screen's fit.
    Q_PROPERTY(double sizeScale MEMBER m_scale NOTIFY changed)
    // Snow: the seed slots rock and flip like the rest.
    Q_PROPERTY(bool snow MEMBER m_snow NOTIFY changed)

public:
    explicit Fall(QObject *parent = nullptr);

    bool running() const { return m_timer.isActive(); }
    void setRunning(bool on);
    int fps() const { return m_fps; }
    void setFps(int fps);

    // A leaf's item, its Scale and Rotation and its plan (Leaves.qml make()); again with a new plan replaces it.
    Q_INVOKABLE void add(QQuickItem *item, QObject *flip, QObject *turn, const QVariantMap &plan);
    Q_INVOKABLE void remove(QQuickItem *item);
    // Not moving: everything in its place for one still moment, no wind.
    Q_INVOKABLE void settle();

Q_SIGNALS:
    void changed();

private:
    struct Leaf {
        QPointer<QQuickItem> item;
        QPointer<QObject> flip, turn;
        bool seed = false;
        double x0 = 0, speed = 0.05, sway = 0, swayT = 3, w = 24, rps = 0, phase = 0, dir = 1, orbit = 0, orbitT = 3.5;
        double spin = 0, tumbleT = 0, flipT = 0, glide = 0.6, zig = 0.8;
        bool hasSpin = false, hasTumble = false, hasFlipT = false;
        double wx = 0, lastFall = -1;
    };
    void step();
    void place(Leaf &q, double t, double dt, double gust);
    double now() const;

    QTimer m_timer;
    int m_fps = 30;
    double m_w = 0, m_h = 0, m_scale = 1;
    bool m_snow = false;
    qint64 m_t0 = 0;
    QHash<QQuickItem *, Leaf> m_leaves;
    QMetaProperty m_xScale, m_angle;
};
