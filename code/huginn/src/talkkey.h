#pragma once

#include <QObject>
#include <QString>

class Settings;
class RoomManager;

// The push to talk key (Settings › Calls). Watches the keyboard while Huginn is in front: holding the
// key in a voice room lets the microphone send (RoomManager::setTalking), and the key is kept from
// typing into a message box while push to talk is on. Not a system-wide hook: other apps' keys are
// never seen, so it only works while Huginn has the focus. GlobalKeys (globalkeys.h) covers the rest of the time where
// the system allows it.
class TalkKey : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int key READ key NOTIFY changed)
    Q_PROPERTY(QString keyName READ keyName NOTIFY changed)
    Q_PROPERTY(bool capturing READ capturing NOTIFY changed)

public:
    TalkKey(Settings *settings, RoomManager *rooms, QObject *parent = nullptr);

    int key() const { return m_key; }
    QString keyName() const;
    bool capturing() const { return m_capturing; }

    // The next key pressed becomes the push to talk key (Escape keeps the old one).
    Q_INVOKABLE void capture();
    Q_INVOKABLE void stopCapture();

Q_SIGNALS:
    void changed();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void release();
    Settings *m_settings;
    RoomManager *m_rooms;
    int m_key;
    bool m_capturing = false;
    bool m_down = false;
};
