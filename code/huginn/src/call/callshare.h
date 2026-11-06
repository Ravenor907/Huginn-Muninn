#pragma once

#include "audiodsp.h"

#include <QObject>
#include <QPointer>
#include <QString>
#include <QVideoFrame>
#include <deque>
#include <vector>

class QAudioBuffer;
class QAudioBufferOutput;
class QMediaPlayer;
class QVideoSink;

// Share a video inside a 1:1 video call (as Muninn's CallShare.kt). The video plays in the camera's place: its frames
// go out on the camera's stream (CallManager hands them to the camera's VideoSender, so the camera's profile, bitrate
// and keyframe requests carry on), and the other side sees it large on its call screen. Its sound goes into the call
// under the voices (CallMedia::shareOut), and plays here through the call's own output (CallMedia::shareHere), so the
// echo canceller knows it and it doesn't come back through the microphone. Nothing new goes on the wire. The person
// sharing can pause, go on and stop. QMediaPlayer decodes it; no sound device of its own is opened.
class CallShare : public QObject
{
    Q_OBJECT
public:
    explicit CallShare(QObject *parent = nullptr);
    ~CallShare() override;

    // Starts playing the video at path (a local file). Whether it opens is known a moment later: failed() if it can't.
    void start(const QString &path);
    void pause(bool on);
    // Stops it (stopped() follows when it was on).
    void stop();
    bool active() const { return m_on; }
    bool paused() const { return m_paused; }
    qint64 atMs() const;
    qint64 lengthMs() const;
    // Degrees clockwise to turn the video upright (0, 90, 180, 270), from its latest frame: a phone video filmed
    // upright is stored sideways with this set. It goes with each frame, as the camera's rotation does.
    int rotation() const { return m_rotation; }

    // 20 ms of the video's sound at 16 kHz mono: what goes out with the microphone, and what plays here. False: none now.
    bool takeSend(qint16 *pcm320) { return take(m_send, m_sendPrimed, pcm320); }
    bool takeHere(qint16 *pcm320) { return take(m_here, m_herePrimed, pcm320); }

Q_SIGNALS:
    void changed();                          // on, paused, where it is
    void frame(const QVideoFrame &frame);    // a picture of it, to send in the camera's place
    void failed(const QString &why);         // it couldn't be played (stopped() follows)
    void stopped();                          // sharing is over: stopped, played to the end, or failed

private:
    void onAudio(const QAudioBuffer &b);
    void push(const std::vector<float> &mono16k);
    static bool take(std::deque<qint16> &q, bool &primed, qint16 *out);
    void finish();

    QPointer<QMediaPlayer> m_player;
    QVideoSink *m_sink = nullptr;
    QAudioBufferOutput *m_audio = nullptr;
    bool m_on = false, m_paused = false, m_sawVideo = false;
    qint64 m_lastSecond = -1;
    int m_rotation = 0;
    std::deque<qint16> m_send, m_here;
    bool m_sendPrimed = false, m_herePrimed = false;
    AudioDsp::Resampler m_resampler;
    int m_rate = 0;
    std::vector<float> m_mono, m_res;
};
