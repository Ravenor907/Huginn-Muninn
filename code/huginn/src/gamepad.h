#pragma once

#include <QElapsedTimer>
#include <QHash>
#include <QObject>
#include <QPointF>
#include <QTimer>

struct _SDL_GameController;
class QQuickWindow;

// Reads controllers through SDL. In Game Mode Steam hands non-Steam apps a virtual Xbox pad,
// so this is the same path every Steam game uses. The D-pad holds to repeat. The sticks are
// read as they are: the left one scrolls, the right one moves the on-screen pointer (Main.qml).
class Gamepad : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString style READ style NOTIFY connectedChanged)
    // Left stick, -1 to 1 (down is positive).
    Q_PROPERTY(double stickX READ stickX NOTIFY stickChanged)
    Q_PROPERTY(double stickY READ stickY NOTIFY stickChanged)
    // Right stick, -1 to 1 (down is positive).
    Q_PROPERTY(double rightX READ rightX NOTIFY stickChanged)
    Q_PROPERTY(double rightY READ rightY NOTIFY stickChanged)

public:
    explicit Gamepad(QObject *parent = nullptr);
    ~Gamepad() override;

    bool connected() const { return m_pad != nullptr; }
    QString style() const { return m_style; }
    // True while a held d-pad direction is repeating (not a fresh press).
    Q_INVOKABLE bool repeating() const { return m_repeating; }
    double stickX() const { return m_stickX; }
    double stickY() const { return m_stickY; }
    double rightX() const { return m_rightX; }
    double rightY() const { return m_rightY; }

    // The stick pointer: real mouse events at a point in the window, so every MouseArea,
    // TapHandler and HoverHandler answers it as it would a mouse. button: 1 left, 2 right.
    Q_INVOKABLE void pointerMove(QQuickWindow *window, double x, double y);
    Q_INVOKABLE void pointerButton(QQuickWindow *window, double x, double y, int button, bool down);

    // The label for a button prompt in the current controller's style.
    Q_INVOKABLE QString glyph(const QString &button) const;
    // Battery saver: read less often (main.cpp follows System::batterySaving).
    void setRelaxed(bool relaxed);

Q_SIGNALS:
    void pressed(const QString &button);
    void released(const QString &button);
    void connectedChanged();
    void stickChanged();

private:
    void poll();
    int pollInterval() const;
    void open(int index);
    void down(const QString &button, bool repeats);
    void up(const QString &button);
    void axisAsButton(const QString &button, bool active, bool repeats);
    // A stick's new value, a little rounded so its jitter at rest says nothing; true when it changed.
    static bool setAxis(double &axis, int raw);
    void retime();

    QTimer m_poll;
    QTimer m_repeat;
    _SDL_GameController *m_pad = nullptr;
    bool m_sdlReady = false;
    QString m_style = QStringLiteral("xbox");
    QString m_repeatButton;
    bool m_repeating = false;
    QHash<QString, bool> m_axisHeld;
    double m_stickX = 0;
    double m_stickY = 0;
    double m_rightX = 0;
    double m_rightY = 0;
    bool m_relaxed = false;
    Qt::MouseButtons m_held = Qt::NoButton;   // stick-pointer buttons down right now
    QElapsedTimer m_clock;                    // timestamps for those events (double clicks)
};
