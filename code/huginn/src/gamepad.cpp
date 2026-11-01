#include "gamepad.h"
#include "busy.h"

#include <SDL.h>

#include <QCoreApplication>
#include <QGuiApplication>
#include <QDebug>
#include <QMouseEvent>
#include <QQuickWindow>

#include <cmath>

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
    for (int i = 0; i < SDL_NumJoysticks(); ++i) {
        if (SDL_IsGameController(i)) {
            open(i);
            break;
        }
    }
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
    if (m_pad)
        SDL_GameControllerClose(m_pad);
    if (m_sdlReady)
        SDL_Quit();
}

// Read the controller often only while it is in use and Huginn is in front (60 times a second, 30 with Battery saver);
// at rest in front, and behind other windows, 10 times a second (4 behind them with Battery saver); with none, look
// for one every 2 seconds. Nothing is lost at rest: SDL keeps the presses until they are read.
int Gamepad::pollInterval() const
{
    if (!m_pad)
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
    for (auto it = m_axisHeld.cbegin(); it != m_axisHeld.cend() && !held; ++it)
        held = it.value();
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
    if (m_pad)
        return;
    m_pad = SDL_GameControllerOpen(index);
    if (!m_pad)
        return;
    switch (SDL_GameControllerGetType(m_pad)) {
    case SDL_CONTROLLER_TYPE_PS3:
    case SDL_CONTROLLER_TYPE_PS4:
    case SDL_CONTROLLER_TYPE_PS5:
        m_style = QStringLiteral("playstation");
        break;
    default:
        m_style = QStringLiteral("xbox");
    }
    m_poll.setInterval(pollInterval());
    Q_EMIT connectedChanged();
}

void Gamepad::down(const QString &button, bool repeats)
{
    Q_EMIT pressed(button);
    if (repeats) {
        m_repeatButton = button;
        m_repeat.start(380);
    }
}

void Gamepad::up(const QString &button)
{
    if (m_repeatButton == button) {
        m_repeatButton.clear();
        m_repeat.stop();
    }
    Q_EMIT released(button);
}

void Gamepad::axisAsButton(const QString &button, bool active, bool repeats)
{
    const bool was = m_axisHeld.value(button);
    if (active == was)
        return;
    m_axisHeld[button] = active;
    if (active)
        down(button, repeats);
    else
        up(button);
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
            if (m_pad && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(m_pad)) == e.cdevice.which) {
                SDL_GameControllerClose(m_pad);
                m_pad = nullptr;
                m_stickX = m_stickY = m_rightX = m_rightY = 0;
                Q_EMIT stickChanged();
                Q_EMIT connectedChanged();
                for (int i = 0; i < SDL_NumJoysticks(); ++i)
                    if (SDL_IsGameController(i)) { open(i); break; }
                m_poll.setInterval(pollInterval());
            }
            break;
        case SDL_CONTROLLERBUTTONDOWN: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                down(b, isDirection(b));
            break;
        }
        case SDL_CONTROLLERBUTTONUP: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                up(b);
            break;
        }
        case SDL_CONTROLLERAXISMOTION: {
            const int v = e.caxis.value;
            switch (e.caxis.axis) {
            case SDL_CONTROLLER_AXIS_TRIGGERLEFT:
                axisAsButton(QStringLiteral("lt"), v > kTrigger, false);
                break;
            case SDL_CONTROLLER_AXIS_TRIGGERRIGHT:
                axisAsButton(QStringLiteral("rt"), v > kTrigger, false);
                break;
            case SDL_CONTROLLER_AXIS_LEFTX:
                moved = setAxis(m_stickX, v) || moved;
                break;
            case SDL_CONTROLLER_AXIS_LEFTY:
                moved = setAxis(m_stickY, v) || moved;
                break;
            case SDL_CONTROLLER_AXIS_RIGHTX:
                moved = setAxis(m_rightX, v) || moved;
                break;
            case SDL_CONTROLLER_AXIS_RIGHTY:
                moved = setAxis(m_rightY, v) || moved;
                break;
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
