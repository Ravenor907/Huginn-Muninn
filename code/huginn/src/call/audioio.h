#pragma once

#include "audiodsp.h"

#include <QAudioDevice>
#include <QAudioFormat>
#include <QByteArray>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <functional>
#include <vector>

struct TvVoice;

class QAudioSink;
class QAudioSource;
class QIODevice;
class QMediaDevices;

// The microphone and the speaker for a call: 16 kHz mono 16-bit, 20 ms frames. When a device won't
// take that format, its own preferred format is used and the sound is converted and resampled here.
// With voice processing in the build (HUGINN_HAVE_VOICE), the microphone goes through WebRTC's echo
// canceller, noise suppressor and level control, which hears everything the speaker plays: the people on
// the call don't hear themselves back from laptop speakers, as on Muninn.
//  - captured(pcm): one 20 ms frame (320 samples, 640 bytes) from the microphone.
//  - pull: asked for the next 20 ms to play whenever the speaker has room (the speaker's clock sets
//    the pace, which is what the jitter buffer wants).
class AudioIO : public QObject
{
    Q_OBJECT
public:
    explicit AudioIO(QObject *parent = nullptr);
    ~AudioIO() override;

    std::function<void(qint16 *pcm320)> pull;
    bool start();
    void stop();
    bool running() const { return m_running; }

    // Outputs to pick from: [{id, name}]; the current one's id ("" = the system default).
    QVariantList outputs() const;
    QString output() const { return m_outputId; }
    QString outputName() const;
    void setOutput(const QString &id);
    // Steps to the next output in the list (the "Speaker" button), back to the default after the last.
    void nextOutput();

Q_SIGNALS:
    void captured(const QByteArray &pcm);
    void error(const QString &text);
    void outputsChanged();
    // A device change the call followed by itself ("Microphone: USB headset"), for a toast.
    void deviceNotice(const QString &text);

private:
    bool startInput();
    void stopInput();
    bool startOutput();
    void stopOutput();
    // The microphone and speaker it would open now (Settings' pick while it's plugged in, else the default).
    static QAudioDevice inputWanted();
    QAudioDevice outputWanted() const;
    // Microphones or speakers came or went, or the open microphone stopped by itself: follow them while running.
    void followInputs();
    void followOutputs();
    void onInput();
    void pump();
    static QAudioFormat wanted();
    static QAudioFormat pick(const QAudioDevice &dev);
    static void toMono(const QAudioFormat &f, const char *data, int frames, std::vector<float> *out);
    static QByteArray fromMono(const QAudioFormat &f, const float *data, int n);

    QMediaDevices *m_devices = nullptr;
    QAudioSource *m_source = nullptr;
    QAudioSink *m_sink = nullptr;
    QPointer<QIODevice> m_in, m_out;
    QAudioFormat m_inFormat, m_outFormat;
    AudioDsp::Resampler m_down, m_up;
    std::vector<float> m_mono, m_resampled;
    std::vector<qint16> m_frame;
    QByteArray m_pending;          // converted output bytes the sink had no room for yet
    QTimer m_pump;
    QString m_outputId;
    QAudioDevice m_inDev, m_outDev;   // what's open now
    QByteArray m_lastMic;             // the microphone opened last (a toast only when it changes)
    qint64 m_inRestartAt = 0;         // the last time a stopped microphone was opened again (at most once a second)
    bool m_running = false;
    TvVoice *m_voice = nullptr;    // the echo canceller, while running (null without it in the build)
    std::vector<qint16> m_clean;   // a processed microphone frame
};
