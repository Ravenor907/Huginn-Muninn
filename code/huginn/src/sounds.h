#pragma once

#include <QList>
#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

#include <functional>

class Chime;

// Huginn's own sounds in one place (Settings › Notifications › Sounds): the raven call for a message, a nudge while
// locked or a file waiting, and what the call ringtones and the mute cues report. Every message-kind sound goes
// through SoundGate (at most one every few seconds, and one for a catch-up burst after starting, unlocking or the
// relays reconnecting), and each sound played is kept in Recent sounds (in memory only, never what a message says,
// a name only as the notification setting allows) so it can be seen why one played.
class Sounds : public QObject
{
    Q_OBJECT
    // Settings › Notifications › Sounds.
    Q_PROPERTY(bool messageSound READ messageSound WRITE setMessageSound NOTIFY changed)
    Q_PROPERTY(bool ringSound READ ringSound WRITE setRingSound NOTIFY changed)
    Q_PROPERTY(bool fileSound READ fileSound WRITE setFileSound NOTIFY changed)
    Q_PROPERTY(int volume READ volume WRITE setVolume NOTIFY changed)
    // [{ time ("14:05"), kind ("Message"), chat (a name or ""), why }], newest first, at most 20.
    Q_PROPERTY(QVariantList recent READ recent NOTIFY recentChanged)

public:
    // The message-kind sounds' limits (pure, for the self-test).
    struct Gate {
        static constexpr qint64 kGapMs = 5000;       // at most one message sound in this long
        static constexpr qint64 kCatchUpMs = 30000;  // after a start, an unlock or a reconnect: one sound for all of it
        qint64 last = -1;                            // when one last played (-1: never)
        qint64 catchUpUntil = 0;
        bool catchUpSounded = false;
        // May one play now? Counts it if so.
        bool take(qint64 now);
        // What comes in the next kCatchUpMs is what waited (a backlog): one sound for all of it.
        void catchUp(qint64 now);
    };

    explicit Sounds(QObject *parent = nullptr);
    static Sounds *instance();

    bool messageSound() const;
    bool ringSound() const;
    bool fileSound() const;
    int volume() const;
    void setMessageSound(bool v);
    void setRingSound(bool v);
    void setFileSound(bool v);
    void setVolume(int v);
    QVariantList recent() const;

    // Read where no Sounds is at hand (the ring in src/call): Settings › Notifications › Call ringtone.
    static bool ringOn();
    // The raven call for something new: kind "Message", "File offer", "Locked" or "New activity"; chat is who or
    // where it's from (kept only as the notification setting allows); why says what was on screen. Do not disturb,
    // quiet hours, the kind's own switch and the Gate decide. True when it played.
    bool raven(const QString &kind, const QString &chat, const QString &why);
    // Something that waited is coming in now (Huginn started, unlocked, or the relays came back).
    void catchUp();
    // A sound played somewhere else (a ringtone, a mute cue): into Recent sounds.
    static void note(const QString &kind, const QString &chat, const QString &why);
    // Settings › Notifications › Sounds › Play: the message sound once, at the volume set, whatever is switched off.
    Q_INVOKABLE void test();
    // A lock takes the list away (it may name people).
    Q_INVOKABLE void clearRecent();
    // What notifications may show now: 0 nothing, 1 the name, 2 name and message (locked: 0). Set by main.cpp.
    std::function<int()> previewMode;

    static bool selfTest();
    Chime *chime() const { return m_raven; }   // the raven call itself (the Wi-Fi self-test plays it)

Q_SIGNALS:
    void changed();
    void recentChanged();

private:
    void add(const QString &kind, const QString &chat, const QString &why);
    Chime *m_raven = nullptr;
    Gate m_gate;
    QList<QVariantMap> m_recent;
};
