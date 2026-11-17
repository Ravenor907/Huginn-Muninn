#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>

class QSoundEffect;
class QTimer;

namespace Platform
{
// A WAV file (PCM, 8, 16, 24 or 32 bits) with its samples scaled to volume (0 to 1); anything else comes back as it was.
// Windows' PlaySound has no volume of its own, so Huginn's sounds are scaled in memory there (and checked by the self-test).
QByteArray scaledWav(const QByteArray &wav, double volume);
// The path of a bundled file (raven_message.wav, raven-gold.png) in the app's resources: the usual
// :/qt/qml/Huginn/assets/<name>, or wherever the build put it. Empty if it isn't there.
QString resource(const QString &name);
}

// The raven call for a new message, and the ring for calls and voice rooms (played again by their
// timers). Linux plays it with QSoundEffect. Windows plays it with the system's own PlaySound from
// memory: Qt's QSoundEffect can crash there when it is played (QTBUG-142694, Qt 6.8.3 to 6.10.1),
// and a chime or a ring needs nothing more. Nothing on Windows may use QSoundEffect.
class Chime : public QObject
{
public:
    Chime(const QString &resource, QObject *parent = nullptr);
    ~Chime() override;
    void play();
    void stop();   // stops this sound if it is still playing (not another Chime's)
    bool ready() const;   // the sound loaded (for the self-test)
    // Settings › Notifications › Volume: every Huginn sound, 0 to 100 percent (100 until moved).
    static void setVolume(int percent);
    static int volume();

private:
    QSoundEffect *effect();           // Linux: made when wanted, let go of a little after it last played
    QByteArray m_wav;                 // Windows: the file itself, kept while it may be playing; Linux: its header
    QByteArray m_scaled;              // Windows: the copy at the volume in use (empty at 100%), kept while it may be playing
    int m_scaledAt = 100;
    QString m_source;                 // Linux: the file, as QSoundEffect wants it
    QSoundEffect *m_effect = nullptr; // Linux: only while it plays (and for 10 seconds after)
    QTimer *m_letGo = nullptr;        // Linux
};
