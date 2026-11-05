#pragma once

#include <QBuffer>
#include <QElapsedTimer>
#include <QObject>
#include <QTimer>

class QAudioInput;
class QAudioOutput;
class QMediaCaptureSession;
class QMediaPlayer;
class QMediaRecorder;
class Chat;

// Voice messages, the same format as Muninn: bare AAC frames (ADTS), so there's no container and
// nothing in it but the sound. While recording, the sound is written to Huginn's runtime folder
// (in memory, never on the disk), read back and wiped at once; it's only ever saved encrypted.
// Played back from memory too. Up to 5 minutes.
class Voice : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool recording READ recording NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(QString playing READ playing NOTIFY changed)
    Q_PROPERTY(qreal progress READ progress NOTIFY tick)
    Q_PROPERTY(QString error READ error NOTIFY changed)

public:
    explicit Voice(Chat *chat, QObject *parent = nullptr);
    ~Voice() override;

    bool recording() const { return m_recording; }
    int elapsed() const;
    QString playing() const { return m_playing; }
    qreal progress() const;
    QString error() const { return m_error; }

    Q_INVOKABLE bool start(const QString &key);
    // Stops and sends to the chat it was started for (too short = nothing sent). Round 17: a recording only ever
    // goes to that chat. When key isn't it (the chat or channel changed under a held Y or microphone), it's thrown
    // away instead (dropped()). Returns whether it went on to be sent.
    Q_INVOKABLE bool finish(const QString &key);
    Q_INVOKABLE void cancel();
    Q_INVOKABLE void play(const QString &id);
    Q_INVOKABLE void stop();
    Q_INVOKABLE static QString length(int ms);
    // Round 17: the rules, on their own for the self-test. A recording started for target may be sent when let go
    // in key; a gap between ticks of gapMs (wall clock, either way) means the device slept or the clock jumped.
    static bool sendable(const QString &target, const QString &key) { return !target.isEmpty() && key == target; }
    static bool slept(qint64 gapMs) { return gapMs > 10000 || gapMs < -10000; }
    static bool selfTest();

Q_SIGNALS:
    void changed();
    void tick();
    // A recording thrown away by Voice itself, never sent: finish() for another chat, or the device slept (or the
    // clock jumped) while it was recording. Main says "Recording cancelled."
    void dropped();

private:
    QString tempFile() const;
    Chat *m_chat;
    QMediaCaptureSession *m_session = nullptr;
    QAudioInput *m_input = nullptr;
    QMediaRecorder *m_recorder = nullptr;
    QMediaPlayer *m_player = nullptr;
    QAudioOutput *m_output = nullptr;
    QBuffer m_buffer;
    QByteArray m_sound;
    QTimer m_ticker;
    bool m_recording = false;
    // How long it has been recording: a steady clock, so a clock change never makes a clip 5 minutes long.
    QElapsedTimer m_since;
    // The wall clock at the last tick: a gap of more than 10 s means the device slept (or the clock jumped).
    qint64 m_lastTick = 0;
    QString m_sendTo, m_target, m_playing, m_error;
};
