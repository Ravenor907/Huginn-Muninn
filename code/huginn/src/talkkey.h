#pragma once

#include <QObject>
#include <QString>

class Settings;
class RoomManager;

// The push to talk key (Settings › Calls). Watches the keyboard while Huginn is in front: holding the
// key in a voice room lets the microphone send (RoomManager::setTalking). While a text box has the focus a key that
// types (` or Space) types there instead, so a message can still have one in it; a key that types nothing (Caps Lock,
// F13) always talks. Not a system-wide hook: other apps' keys are
// never seen, so it only works while Huginn has the focus. GlobalKeys (globalkeys.h) covers the rest of the time where
// the system allows it.
class TalkKey : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int key READ key NOTIFY changed)
    Q_PROPERTY(QString keyName READ keyName NOTIFY changed)
    Q_PROPERTY(bool capturing READ capturing NOTIFY changed)
    // The key puts a character in a text box, so it types there instead of talking while one has the focus.
    Q_PROPERTY(bool keyTypes READ keyTypes NOTIFY changed)
    // No key picked yet (a new install): push to talk waits for one (Settings › Calls).
    Q_PROPERTY(bool hasKey READ hasKey NOTIFY changed)
    // A text box has the focus right now, so a key that types goes there instead of talking.
    Q_PROPERTY(bool inBox READ inBox NOTIFY changed)

public:
    TalkKey(Settings *settings, RoomManager *rooms, QObject *parent = nullptr);

    int key() const { return m_key; }
    QString keyName() const;
    bool capturing() const { return m_capturing; }
    bool keyTypes() const;
    bool hasKey() const { return m_key != 0; }
    bool inBox() const { return m_inBox; }

    // The next key pressed becomes the push to talk key (Escape keeps the old one).
    Q_INVOKABLE void capture();
    Q_INVOKABLE void stopCapture();

Q_SIGNALS:
    void changed();
    // The talk key went into a text box during a voice room instead of talking (once a run).
    void typedInstead();

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
    bool m_toldTyped = false;
    bool m_inBox = false;
};
