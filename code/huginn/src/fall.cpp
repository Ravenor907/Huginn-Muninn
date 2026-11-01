#include "fall.h"
#include "busy.h"

#include <QDateTime>
#include <QQuickItem>

#include <algorithm>
#include <cmath>

namespace
{
constexpr double kTau = 6.283185307179586;
constexpr double kStill = 37.3;   // the moment everything holds at when not moving

// A gust everyone feels: 0 most of the time, rising to 1 for a few seconds now and then.
double gustAt(double t)
{
    const double g = std::sin(t * 0.21) * std::sin(t * 0.07 + 1.3);
    return g > 0 ? g * g : 0;
}
}

Fall::Fall(QObject *parent)
    : QObject(parent)
    , m_t0(QDateTime::currentMSecsSinceEpoch())
{
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

void Fall::add(QQuickItem *item, QObject *flip, QObject *turn, const QVariantMap &p)
{
    if (!item)
        return;
    if (flip && !m_xScale.isValid())
        m_xScale = flip->metaObject()->property(flip->metaObject()->indexOfProperty("xScale"));
    if (turn && !m_angle.isValid())
        m_angle = turn->metaObject()->property(turn->metaObject()->indexOfProperty("angle"));
    Leaf q;
    q.item = item;
    q.flip = flip;
    q.turn = turn;
    auto num = [&p](const char *k, double fallback) { return p.value(QLatin1String(k), fallback).toDouble(); };
    q.seed = p.value(QStringLiteral("kind")).toString() == QLatin1String("seed");
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
    // Already there with this plan: it keeps its wind.
    const auto old = m_leaves.constFind(item);
    if (old != m_leaves.cend()) {
        q.wx = old->wx;
        q.lastFall = old->lastFall;
    }
    Leaf &in = m_leaves[item];
    in = q;
    const double t = running() ? now() : kStill;
    place(in, t, 0, gustAt(t));
}

void Fall::remove(QQuickItem *item) { m_leaves.remove(item); }

void Fall::settle()
{
    if (running())
        return;
    const double g = gustAt(kStill);
    for (auto it = m_leaves.begin(); it != m_leaves.end(); ++it)
        place(it.value(), kStill, 0, g);
}

void Fall::step()
{
    Busy::Scope busy("Seasonal animation");
    const double t = now();
    const double dt = m_timer.interval() / 1000.0;
    const double g = gustAt(t);
    for (auto it = m_leaves.begin(); it != m_leaves.end();) {
        if (!it->item) {
            it = m_leaves.erase(it);
            continue;
        }
        place(it.value(), t, dt, g);
        ++it;
    }
}

// Where leaf q is at time t (seconds): its turning point, its turn in degrees and how far it faces you (1 flat on,
// near 0 edge-on), the same as Leaves.qml's pose() was.
void Fall::place(Leaf &q, double t, double dt, double gust)
{
    if (!q.item)
        return;
    const double W = m_w, H = m_h, ss = m_scale;
    const double side = std::max(3.0, std::ceil(q.w * ss));
    const double tt = t + q.phase;
    // Top to bottom and round again: from 12% above the area to 13% below it, or by its own size for a big one.
    const double top = std::min(-0.12 * H, -side);
    const double drop = std::max(1.13 * H, H + side) - top;
    const double fallen = std::fmod(tt * q.speed * H, drop);
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
    q.item->setX(x);
    q.item->setY(y);
    if (q.turn && m_angle.isValid())
        m_angle.write(q.turn.data(), rot);
    if (q.flip && m_xScale.isValid())
        m_xScale.write(q.flip.data(), flip);
}
