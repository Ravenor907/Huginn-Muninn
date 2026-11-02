#pragma once

#include <QObject>
#include <QString>
#include <functional>
#include <memory>

class Settings;
class RoomManager;
class TalkKey;

// Push to talk, Mute and Deafen while another app (a game) is in front, without watching the keyboard:
//  - Windows (platform/globalkeys_windows.cpp): Mute (Ctrl+Shift+M) and Deafen (Ctrl+Shift+D) are registered with
//    RegisterHotKey while in a voice room or a call, so Windows tells Huginn about those two combinations only. Push to
//    talk works only while Huginn is in front (TalkKey). No key is ever polled and nothing is hooked.
//  - Linux (platform/globalkeys_linux.cpp): the desktop's GlobalShortcuts portal, where there is one. The desktop
//    owns the keys (it may ask once to allow them, and they can be changed in its settings) and says when one goes
//    down and up.
//  - No portal: only TalkKey and Main.qml's shortcuts, while Huginn is in front; Settings says so.
// While Huginn is in front TalkKey and Main.qml do the same jobs; a key the system took never reaches them twice.
class GlobalKeys : public QObject
{
    Q_OBJECT
    // The keys work while another app is in front.
    Q_PROPERTY(bool everywhere READ everywhere NOTIFY changed)
    // Settings › Calls' notes for Push to talk and for the Mute and Deafen keys.
    Q_PROPERTY(QString talkNote READ talkNote NOTIFY changed)
    Q_PROPERTY(QString keysNote READ keysNote NOTIFY changed)

public:
    enum Key { Talk, Mute, Deafen };

    // What one platform does. want(): in a voice room or a call (voice); push to talk on in a room (talk), with
    // TalkKey's Qt key.
    struct Backend {
        virtual ~Backend() = default;
        virtual void want(bool voice, bool talk, int qtKey) = 0;
        virtual bool everywhere() const = 0;
        // Keys another app holds, like "Ctrl+Shift+M" ("" when none).
        virtual QString taken() const { return QString(); }
    };

    GlobalKeys(Settings *settings, RoomManager *rooms, TalkKey *talkKey, QObject *parent = nullptr);
    ~GlobalKeys() override;

    // Set by main.cpp: a 1:1 call is on (Mute and Deafen work in calls too).
    std::function<bool()> inCall;

    bool everywhere() const;
    QString talkNote() const;
    QString keysNote() const;
    // In a room or a call or not, push to talk on or off, another talk key: the backend follows.
    void update();

    // From the backend: a key went down, or up (Talk only).
    void key(Key k, bool down);
    void backendChanged() { Q_EMIT changed(); }

Q_SIGNALS:
    void changed();
    // Main.qml calls Ui.toggleMic() and Ui.toggleDeaf(), the same as the voice card and Ctrl+Shift+M and D.
    void mutePressed();
    void deafenPressed();

private:
    bool voice() const;
    Settings *m_settings;
    RoomManager *m_rooms;
    TalkKey *m_talkKey;
    std::unique_ptr<Backend> m_backend;
    bool m_talking = false;   // the talk key is down, as a backend said
};

// Made by the platform's file.
GlobalKeys::Backend *makeGlobalKeysBackend(GlobalKeys *owner);
