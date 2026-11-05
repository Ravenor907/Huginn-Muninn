#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>

class QSoundEffect;

namespace Platform
{
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

private:
    QByteArray m_wav;                 // Windows: the file itself, kept while it may be playing; Linux: its header
    QSoundEffect *m_effect = nullptr; // Linux
};
