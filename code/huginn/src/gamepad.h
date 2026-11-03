#pragma once

#include <QElapsedTimer>
#include <QHash>
#include <QObject>
#include <QPointF>
#include <QSet>
#include <QTimer>

#include <cmath>

struct _SDL_GameController;
class QQuickWindow;

// Round 15 (R9): one controller seen as two devices (Steam's virtual pad and the raw one) whose copies don't overlap in
// time (down and up on one, then down and up on the other, in one poll): the second copy's press within kTwinMs of the
// first's release, on another pad, is the same tap, never a second one. Its release goes with it. Round 16: when the
// copies of the last tap overlapped (another pad held the button at its press or release: overlapped()), the next
// press is a real tap whatever pad it comes on, so fast taps are never lost.
class TapTwins
{
public:
    static constexpr qint64 kTwinMs = 40;
    // A press that would count (no other pad holds the button): false when it's the other copy of one just let go.
    bool press(qint32 pad, const QString &button, qint64 nowMs);
    // A release that would count: false when its press didn't (and it's let go quietly).
    bool release(qint32 pad, const QString &button, qint64 nowMs);
    // A press or release of the button was not counted because another pad held it: this tap's copies overlap.
    void overlapped(const QString &button) { m_overlap.insert(button); }
    static bool selfTest();

private:
    struct Up {
        qint32 pad = -1;
        qint64 at = 0;
        bool overlapped = false;   // that tap's copies overlapped
    };
    QHash<QString, Up> m_lastUp;   // per button: the pad that last let it go, and when
    QSet<QString> m_twin;          // pad + "|" + button: a press taken as the other copy
    QSet<QString> m_overlap;       // buttons whose tap now going on has overlapping copies
};

// Reads controllers through SDL. In Game Mode Steam hands non-Steam apps a virtual Xbox pad,
// so this is the same path every Steam game uses. The D-pad holds to repeat. The sticks are
// read as they are: the left one scrolls, the right one moves the on-screen pointer (Main.qml).
class Gamepad : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    // The style of the controller that was used last (a docked pad and the built-in one can differ).
    Q_PROPERTY(QString style READ style NOTIFY styleChanged)
    // Left stick, -1 to 1 (down is positive).
    Q_PROPERTY(double stickX READ stickX NOTIFY stickChanged)
    Q_PROPERTY(double stickY READ stickY NOTIFY stickChanged)
    // Right stick, -1 to 1 (down is positive).
    Q_PROPERTY(double rightX READ rightX NOTIFY stickChanged)
    Q_PROPERTY(double rightY READ rightY NOTIFY stickChanged)

public:
    explicit Gamepad(QObject *parent = nullptr);
    ~Gamepad() override;

    // Any controller at all: every one plugged in is read, so a docked pad works beside the built-in one.
    bool connected() const { return !m_pads.isEmpty(); }
    QString style() const { return m_style; }
    // True while a held d-pad direction is repeating (not a fresh press).
    Q_INVOKABLE bool repeating() const { return m_repeating; }
    double stickX() const { return m_stickX; }
    // A stick pushed past a small rest zone (drift at rest says nothing about someone being there).
    bool stickInUse(double rest = 0.12) const
    {
        return std::abs(m_stickX) >= rest || std::abs(m_stickY) >= rest || std::abs(m_rightX) >= rest || std::abs(m_rightY) >= rest;
    }
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
    void styleChanged();
    void stickChanged();

private:
    void poll();
    int pollInterval() const;
    void open(int index);
    void openAll();
    void close(qint32 id);
    // A press or release from one pad. Merged across pads: a button counts as down while any pad
    // holds it, so one pad letting go can't end another's hold.
    void down(qint32 pad, const QString &button, bool repeats);
    void up(qint32 pad, const QString &button);
    void axisAsButton(qint32 pad, const QString &button, bool active, bool repeats);
    bool heldElsewhere(qint32 pad, const QString &button) const;
    void useStyleOf(qint32 pad);
    // The sticks as shown: per axis, the pad pushed furthest.
    bool mergeSticks();
    // A stick's new value, a little rounded so its jitter at rest says nothing; true when it changed.
    static bool setAxis(double &axis, int raw);
    void retime();

    struct Pad {
        _SDL_GameController *ctl = nullptr;
        QString style;
        QSet<QString> held;   // buttons (and triggers) this pad holds now
        double axes[4] = {0, 0, 0, 0};
    };

    QTimer m_poll;
    QTimer m_repeat;
    QHash<qint32, Pad> m_pads;
    bool m_sdlReady = false;
    QString m_style = QStringLiteral("xbox");
    QString m_repeatButton;
    bool m_repeating = false;
    double m_stickX = 0;
    double m_stickY = 0;
    double m_rightX = 0;
    double m_rightY = 0;
    bool m_relaxed = false;
    Qt::MouseButtons m_held = Qt::NoButton;   // stick-pointer buttons down right now
    QElapsedTimer m_clock;                    // timestamps for those events (double clicks)
    TapTwins m_twins;                         // one pad seen twice: one tap, not two (R9)
    qint64 m_lastActive = -100000;            // m_clock time of the last press, release or stick move
    bool inUse() const;
};
