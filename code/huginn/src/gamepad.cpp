#include "gamepad.h"
#include "busy.h"

#include <SDL.h>

#include <QCoreApplication>
#include <QGuiApplication>
#include <QDebug>
#include <QMouseEvent>
#include <QQuickWindow>

#include <cmath>
#include <cstdio>
#include <utility>

namespace
{
constexpr int kTrigger = 12000;

QString buttonName(Uint8 b)
{
    switch (b) {
    case SDL_CONTROLLER_BUTTON_A: return QStringLiteral("a");
    case SDL_CONTROLLER_BUTTON_B: return QStringLiteral("b");
    case SDL_CONTROLLER_BUTTON_X: return QStringLiteral("x");
    case SDL_CONTROLLER_BUTTON_Y: return QStringLiteral("y");
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER: return QStringLiteral("lb");
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER: return QStringLiteral("rb");
    case SDL_CONTROLLER_BUTTON_START: return QStringLiteral("start");
    case SDL_CONTROLLER_BUTTON_BACK: return QStringLiteral("back");
    case SDL_CONTROLLER_BUTTON_DPAD_UP: return QStringLiteral("up");
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN: return QStringLiteral("down");
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT: return QStringLiteral("left");
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT: return QStringLiteral("right");
    case SDL_CONTROLLER_BUTTON_LEFTSTICK: return QStringLiteral("ls");
    case SDL_CONTROLLER_BUTTON_RIGHTSTICK: return QStringLiteral("rs");
#if SDL_VERSION_ATLEAST(2, 0, 14)
    // The back paddles (Xbox Elite, some handhelds): no job in Huginn, so one can be the talk button (TalkKey).
    case SDL_CONTROLLER_BUTTON_PADDLE1: return QStringLiteral("p1");
    case SDL_CONTROLLER_BUTTON_PADDLE2: return QStringLiteral("p2");
    case SDL_CONTROLLER_BUTTON_PADDLE3: return QStringLiteral("p3");
    case SDL_CONTROLLER_BUTTON_PADDLE4: return QStringLiteral("p4");
#endif
    }
    return {};
}

// Only the D-pad repeats. The sticks aren't buttons: the left one scrolls and the
// right one moves the pointer, so they never share the D-pad's names.
bool isDirection(const QString &b)
{
    return b == QLatin1String("up") || b == QLatin1String("down") || b == QLatin1String("left") || b == QLatin1String("right");
}
}

Gamepad::Gamepad(QObject *parent)
    : QObject(parent)
{
    m_clock.start();
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (SDL_Init(SDL_INIT_GAMECONTROLLER) != 0) {
        qWarning() << "Controller support is off:" << SDL_GetError();
        return;
    }
    m_sdlReady = true;
    openAll();
    connect(&m_poll, &QTimer::timeout, this, &Gamepad::poll);
    m_poll.start(pollInterval());
    // Read as often as a frame while Huginn is in front, much less behind other windows.
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, [this] { retime(); });

    m_repeat.setSingleShot(false);
    connect(&m_repeat, &QTimer::timeout, this, [this] {
        if (m_repeatButton.isEmpty())
            return;
        m_repeat.setInterval(70);
        m_repeating = true;
        Q_EMIT pressed(m_repeatButton);
        m_repeating = false;
    });
}

Gamepad::~Gamepad()
{
    for (const Pad &p : std::as_const(m_pads))
        SDL_GameControllerClose(p.ctl);
    m_pads.clear();
    if (m_sdlReady)
        SDL_Quit();
}

// Read the controller often only while it is in use and Huginn is in front (60 times a second, 30 with Battery saver);
// at rest in front, and behind other windows, 10 times a second (4 behind them with Battery saver); with none, look
// for one every 2 seconds. Nothing is lost at rest: SDL keeps the presses until they are read.
int Gamepad::pollInterval() const
{
    if (m_pads.isEmpty())
        return 2000;
    const bool front = QGuiApplication::applicationState() == Qt::ApplicationActive;
    if (front && inUse())
        return m_relaxed ? 33 : 16;
    if (front)
        return 100;
    return m_relaxed ? 250 : 100;
}

// Something held, a stick pushed past its rest zone, or a press or move in the last second and a half.
bool Gamepad::inUse() const
{
    bool held = !m_repeatButton.isEmpty() || m_held != Qt::NoButton || stickInUse();
    for (auto it = m_pads.cbegin(); it != m_pads.cend() && !held; ++it)
        held = !it.value().held.isEmpty();
    return held || m_clock.elapsed() - m_lastActive < 1500;
}

void Gamepad::retime()
{
    const int ms = pollInterval();
    if (m_poll.interval() != ms)
        m_poll.setInterval(ms);
}

void Gamepad::setRelaxed(bool relaxed)
{
    if (relaxed == m_relaxed)
        return;
    m_relaxed = relaxed;
    retime();
}

bool Gamepad::setAxis(double &axis, int raw)
{
    double v = qBound(-1.0, raw / 32767.0, 1.0);
    // Rest jitter is 0; the rest in steps of 1/128, far finer than anything the scrolling or pointer can show.
    v = std::abs(v) < 0.06 ? 0.0 : std::round(v * 128.0) / 128.0;
    if (v == axis)
        return false;
    axis = v;
    return true;
}

void Gamepad::open(int index)
{
    if (!SDL_IsGameController(index))
        return;
    const qint32 id = SDL_JoystickGetDeviceInstanceID(index);
    if (id < 0 || m_pads.contains(id))
        return;
    _SDL_GameController *ctl = SDL_GameControllerOpen(index);
    if (!ctl)
        return;
    Pad p;
    p.ctl = ctl;
    switch (SDL_GameControllerGetType(ctl)) {
    case SDL_CONTROLLER_TYPE_PS3:
    case SDL_CONTROLLER_TYPE_PS4:
    case SDL_CONTROLLER_TYPE_PS5:
        p.style = QStringLiteral("playstation");
        break;
    default:
        p.style = QStringLiteral("xbox");
    }
    const bool first = m_pads.isEmpty();
    m_pads.insert(id, p);
    // The newest pad sets the prompts until another one is used (docking brings the new pad in hand).
    useStyleOf(id);
    m_poll.setInterval(pollInterval());
    if (first)
        Q_EMIT connectedChanged();
}

void Gamepad::openAll()
{
    for (int i = 0; i < SDL_NumJoysticks(); ++i)
        open(i);
}

void Gamepad::close(qint32 id)
{
    auto it = m_pads.find(id);
    if (it == m_pads.end())
        return;
    // Whatever this pad held is let go, unless another pad holds the same button.
    const QSet<QString> held = it.value().held;
    for (const QString &b : held)
        up(id, b);
    SDL_GameControllerClose(it.value().ctl);
    m_pads.erase(it);
    if (mergeSticks())
        Q_EMIT stickChanged();
    if (!m_pads.isEmpty())
        useStyleOf(m_pads.constBegin().key());
    m_poll.setInterval(pollInterval());
    if (m_pads.isEmpty())
        Q_EMIT connectedChanged();
}

void Gamepad::useStyleOf(qint32 pad)
{
    const auto it = m_pads.constFind(pad);
    if (it == m_pads.cend() || it.value().style == m_style)
        return;
    m_style = it.value().style;
    Q_EMIT styleChanged();
}

bool Gamepad::heldElsewhere(qint32 pad, const QString &button) const
{
    for (auto it = m_pads.cbegin(); it != m_pads.cend(); ++it)
        if (it.key() != pad && it.value().held.contains(button))
            return true;
    return false;
}

void Gamepad::down(qint32 pad, const QString &button, bool repeats)
{
    auto it = m_pads.find(pad);
    if (it == m_pads.end() || it.value().held.contains(button))
        return;
    it.value().held.insert(button);
    useStyleOf(pad);
    // Already down on another pad: one press, not two. Just let go on another pad: its other copy, not a new tap.
    if (heldElsewhere(pad, button) || !m_twins.press(pad, button, m_clock.elapsed()))
        return;
    Q_EMIT pressed(button);
    if (repeats) {
        m_repeatButton = button;
        m_repeat.start(380);
    }
}

void Gamepad::up(qint32 pad, const QString &button)
{
    auto it = m_pads.find(pad);
    if (it == m_pads.end() || !it.value().held.remove(button))
        return;
    if (heldElsewhere(pad, button) || !m_twins.release(pad, button, m_clock.elapsed()))
        return;
    if (m_repeatButton == button) {
        m_repeatButton.clear();
        m_repeat.stop();
    }
    Q_EMIT released(button);
}

bool TapTwins::press(qint32 pad, const QString &button, qint64 nowMs)
{
    const auto it = m_lastUp.constFind(button);
    if (it == m_lastUp.cend() || it->pad == pad || nowMs - it->at > kTwinMs)
        return true;
    m_twin.insert(QString::number(pad) + QLatin1Char('|') + button);
    return false;
}

bool TapTwins::release(qint32 pad, const QString &button, qint64 nowMs)
{
    if (m_twin.remove(QString::number(pad) + QLatin1Char('|') + button))
        return false;
    m_lastUp.insert(button, Up{pad, nowMs});
    return true;
}

bool TapTwins::selfTest()
{
    TapTwins t;
    // Pad 1 taps A, then its twin (pad 2) taps A in the same poll: one tap, its release quiet too.
    const bool first = t.press(1, QStringLiteral("a"), 1000) && t.release(1, QStringLiteral("a"), 1000);
    const bool twin = !t.press(2, QStringLiteral("a"), 1010) && !t.release(2, QStringLiteral("a"), 1012);
    // A real second tap a moment later, on either pad, counts; so does another button at once.
    const bool again = t.press(2, QStringLiteral("a"), 1200) && t.release(2, QStringLiteral("a"), 1250)
        && t.press(2, QStringLiteral("a"), 1260) && t.release(2, QStringLiteral("a"), 1300);
    const bool other = t.press(1, QStringLiteral("b"), 1301);
    const bool ok = first && twin && again && other;
    std::printf("gamepad: one pad seen twice taps once: %s\n", ok ? "ok" : "FAILED");
    return ok;
}

void Gamepad::axisAsButton(qint32 pad, const QString &button, bool active, bool repeats)
{
    const auto it = m_pads.constFind(pad);
    if (it == m_pads.cend() || it.value().held.contains(button) == active)
        return;
    if (active)
        down(pad, button, repeats);
    else
        up(pad, button);
}

bool Gamepad::mergeSticks()
{
    double merged[4] = {0, 0, 0, 0};
    for (const Pad &p : std::as_const(m_pads))
        for (int i = 0; i < 4; ++i)
            if (std::abs(p.axes[i]) > std::abs(merged[i]))
                merged[i] = p.axes[i];
    double *out[4] = {&m_stickX, &m_stickY, &m_rightX, &m_rightY};
    bool changed = false;
    for (int i = 0; i < 4; ++i) {
        if (*out[i] != merged[i]) {
            *out[i] = merged[i];
            changed = true;
        }
    }
    return changed;
}

void Gamepad::poll()
{
    Busy::Scope busy("controller");
    bool moved = false;
    bool button = false;
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
        if (e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERBUTTONUP)
            button = true;
        switch (e.type) {
        case SDL_CONTROLLERDEVICEADDED:
            open(e.cdevice.which);
            break;
        case SDL_CONTROLLERDEVICEREMOVED:
            close(e.cdevice.which);
            break;
        case SDL_CONTROLLERBUTTONDOWN: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                down(e.cbutton.which, b, isDirection(b));
            break;
        }
        case SDL_CONTROLLERBUTTONUP: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                up(e.cbutton.which, b);
            break;
        }
        case SDL_CONTROLLERAXISMOTION: {
            const int v = e.caxis.value;
            const qint32 pad = e.caxis.which;
            int axis = -1;
            switch (e.caxis.axis) {
            case SDL_CONTROLLER_AXIS_TRIGGERLEFT:
                axisAsButton(pad, QStringLiteral("lt"), v > kTrigger, false);
                break;
            case SDL_CONTROLLER_AXIS_TRIGGERRIGHT:
                axisAsButton(pad, QStringLiteral("rt"), v > kTrigger, false);
                break;
            case SDL_CONTROLLER_AXIS_LEFTX: axis = 0; break;
            case SDL_CONTROLLER_AXIS_LEFTY: axis = 1; break;
            case SDL_CONTROLLER_AXIS_RIGHTX: axis = 2; break;
            case SDL_CONTROLLER_AXIS_RIGHTY: axis = 3; break;
            }
            auto it = m_pads.find(pad);
            if (axis >= 0 && it != m_pads.end() && setAxis(it.value().axes[axis], v)) {
                if (std::abs(it.value().axes[axis]) > 0.5)   // pushed on purpose, not drift
                    useStyleOf(pad);
                moved = mergeSticks() || moved;
            }
            break;
        }
        }
    }
    // Once a read, however many moves came in: the bindings on the sticks run once, not once per event.
    if (moved)
        Q_EMIT stickChanged();
    if (button || moved)
        m_lastActive = m_clock.elapsed();
    retime();
}

QString Gamepad::glyph(const QString &button) const
{
    const bool ps = m_style == QLatin1String("playstation");
    if (button == QLatin1String("a")) return ps ? QStringLiteral("✕") : QStringLiteral("A");
    if (button == QLatin1String("b")) return ps ? QStringLiteral("○") : QStringLiteral("B");
    if (button == QLatin1String("x")) return ps ? QStringLiteral("□") : QStringLiteral("X");
    if (button == QLatin1String("y")) return ps ? QStringLiteral("△") : QStringLiteral("Y");
    if (button == QLatin1String("lb")) return ps ? QStringLiteral("L1") : QStringLiteral("LB");
    if (button == QLatin1String("rb")) return ps ? QStringLiteral("R1") : QStringLiteral("RB");
    if (button == QLatin1String("lt")) return ps ? QStringLiteral("L2") : QStringLiteral("LT");
    if (button == QLatin1String("rt")) return ps ? QStringLiteral("R2") : QStringLiteral("RT");
    if (button == QLatin1String("start")) return ps ? QStringLiteral("Options") : QStringLiteral("Menu");
    if (button == QLatin1String("back")) return ps ? QStringLiteral("Share") : QStringLiteral("View");
    if (button == QLatin1String("right")) return QStringLiteral("▶");
    if (button == QLatin1String("left")) return QStringLiteral("◀");
    if (button == QLatin1String("updown")) return QStringLiteral("▲▼");
    if (button == QLatin1String("dpad")) return QStringLiteral("✚");
    if (button == QLatin1String("ls")) return ps ? QStringLiteral("L3") : QStringLiteral("LS");
    if (button == QLatin1String("rs")) return ps ? QStringLiteral("R3") : QStringLiteral("RS");
    // The back paddles: each controller names them its own way (M1, L4...), so plain words.
    if (button == QLatin1String("p1")) return QStringLiteral("Back paddle 1");
    if (button == QLatin1String("p2")) return QStringLiteral("Back paddle 2");
    if (button == QLatin1String("p3")) return QStringLiteral("Back paddle 3");
    if (button == QLatin1String("p4")) return QStringLiteral("Back paddle 4");
    return button.toUpper();
}

void Gamepad::pointerMove(QQuickWindow *window, double x, double y)
{
    if (!window)
        return;
    const QPointF at(x, y);
    // A move with nothing held is a hover, which is how hover tools and highlights appear.
    QMouseEvent ev(QEvent::MouseMove, at, at, window->mapToGlobal(at), Qt::NoButton, m_held, Qt::NoModifier);
    ev.setTimestamp(quint64(m_clock.elapsed()));
    QCoreApplication::sendEvent(window, &ev);
}

void Gamepad::pointerButton(QQuickWindow *window, double x, double y, int button, bool down)
{
    if (!window)
        return;
    const Qt::MouseButton b = button == 2 ? Qt::RightButton : Qt::LeftButton;
    // A release only for a press we sent, a press only once.
    if (down == m_held.testFlag(b))
        return;
    m_held.setFlag(b, down);
    const QPointF at(x, y);
    QMouseEvent ev(down ? QEvent::MouseButtonPress : QEvent::MouseButtonRelease, at, at, window->mapToGlobal(at),
                   b, m_held, Qt::NoModifier);
    ev.setTimestamp(quint64(m_clock.elapsed()));
    QCoreApplication::sendEvent(window, &ev);
}
