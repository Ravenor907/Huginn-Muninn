#pragma once

#include <QObject>
#include <QString>

class Settings;
class RoomManager;
class QTimer;

// The push to talk key (Settings › Calls). Watches the keyboard while Huginn is in front: holding the
// key in a voice room lets the microphone send (RoomManager::setTalking). While a text box has the focus a key that
// types (` or Space) types there instead, so a message can still have one in it; a key that types nothing (Caps Lock,
// F13) always talks. Not a system-wide hook: other apps' keys are
// never seen, so it only works while Huginn has the focus. GlobalKeys (globalkeys.h) covers the rest of the time where
// the system allows it. A controller button (LT, a back paddle, Y or LB) can be the talk button too: Main.qml passes each
// press and release to pad(), which holds the mic open while it's down (only while Huginn is in front).
// With push to talk on and neither a key nor a button picked, the mic uses voice activity (main.cpp), so it's never dead.
class TalkKey : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int key READ key NOTIFY changed)
    Q_PROPERTY(QString keyName READ keyName NOTIFY changed)
    Q_PROPERTY(bool capturing READ capturing NOTIFY changed)
    // The key puts a character in a text box, so it types there instead of talking while one has the focus.
    Q_PROPERTY(bool keyTypes READ keyTypes NOTIFY changed)
    // A key or a controller button is picked. None yet (a new install): voice activity until one is.
    Q_PROPERTY(bool hasKey READ hasKey NOTIFY changed)
    // Only a controller button is picked and no controller is connected: voice activity until one is (main.cpp).
    Q_PROPERTY(bool padAway READ padAway NOTIFY changed)
    // Something can talk right now: a keyboard key, or the controller button with a controller connected.
    Q_PROPERTY(bool canTalk READ canTalk NOTIFY changed)
    // The controller with the only talk button went away (or slept) during a voice room: push to talk stays on and the
    // mic is muted (Main.qml), never an open mic mid-room. Voice activity only at join time. Ends with the room or the
    // controller coming back.
    Q_PROPERTY(bool padGone READ padGone NOTIFY changed)
    // The controller's talk button ("rb", "" for none), and its name to show ("RB").
    Q_PROPERTY(QString padButton READ padButton NOTIFY changed)
    Q_PROPERTY(QString padName READ padName NOTIFY changed)
    // A text box has the focus right now, so a key that types goes there instead of talking.
    Q_PROPERTY(bool inBox READ inBox NOTIFY changed)

public:
    TalkKey(Settings *settings, RoomManager *rooms, QObject *parent = nullptr);

    int key() const { return m_key; }
    QString keyName() const;
    bool capturing() const { return m_capturing; }
    bool keyTypes() const;
    bool hasKey() const { return m_key != 0 || !m_pad.isEmpty(); }
    bool padAway() const { return m_key == 0 && !m_pad.isEmpty() && !m_padConnected; }
    bool canTalk() const { return m_key != 0 || (!m_pad.isEmpty() && m_padConnected); }
    bool padGone() const { return m_padGone; }
    // A controller connected or not (main.cpp follows Gamepad).
    void setPadConnected(bool connected);
    QString padButton() const { return m_pad; }
    QString padName() const { return m_pad.toUpper(); }
    bool inBox() const { return m_inBox; }

    // The next key pressed becomes the push to talk key, or the next controller button the talk button (Escape or B
    // keeps the old one). Stops on its own when Huginn goes to the background; Settings stops it when it closes.
    Q_INVOKABLE void capture();
    Q_INVOKABLE void stopCapture();
    // Takes the controller's talk button away (the keyboard key stays).
    Q_INVOKABLE void clearPad();
    // Takes the keyboard talk key away (the controller's button stays). With neither, voice activity again.
    Q_INVOKABLE void clearKey();
    // Every controller press and release (Main.qml): true when it was taken here (picking the button, B to stop
    // picking, or the talk button held in a voice room), so Main.qml does nothing else with it.
    Q_INVOKABLE bool pad(const QString &button, bool down);

Q_SIGNALS:
    void changed();
    // The talk key went into a text box during a voice room instead of talking (once a run).
    void typedInstead();
    // A controller button was just picked as the talk button ("y", "lb"...): Settings warns when it has another job.
    void padPicked(const QString &button);
    // A controller button that can't talk was pressed while picking ("x", "a"...): Settings says why.
    void padRefused(const QString &button);
    // The talk button's controller went away mid-room (Main.qml mutes the mic and says so), or came back.
    void controllerGone();
    void controllerBack();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void release();
    static int firstKey();
    static bool boxFocused();
    Settings *m_settings;
    RoomManager *m_rooms;
    int m_key;
    bool m_capturing = false;
    bool m_down = false;
    QString m_pad;            // the controller's talk button, "" for none
    bool m_padDown = false;   // it's held, talking
    bool m_padTook = false;        // its press talked, so its release is taken here too (even after the room ended)
    bool m_padConnected = false;
    bool m_padGone = false;        // padGone()
    // LB as the talk button: the mic opens about 80 ms after the press, and not at all while that hold is LB with Up
    // or Down (mute and deafen), so muting never sends a blip of sound first.
    QTimer *m_padWait = nullptr;
    bool m_padHushed = false;
    bool m_toldTyped = false;
    bool m_inBox = false;
};
