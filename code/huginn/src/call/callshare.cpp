#include "callshare.h"

#include <QAudioBuffer>
#include <QAudioFormat>
#include <QMediaPlayer>
#include <QUrl>
#include <QVideoSink>
#include <algorithm>
#if QT_VERSION >= QT_VERSION_CHECK(6, 8, 0)
#include <QAudioBufferOutput>
#define HUGINN_SHARE_SOUND 1
#endif

namespace
{
const size_t kMostQueued = size_t(AudioDsp::kRate) / 2;   // half a second of sound waits at most
const size_t kPrime = size_t(AudioDsp::kFrame) * 3;       // 60 ms gathered before it starts (and after a gap)
}

CallShare::CallShare(QObject *parent) : QObject(parent) {}

CallShare::~CallShare()
{
    if (m_player)
        m_player->stop();
}

qint64 CallShare::atMs() const { return m_player ? m_player->position() : 0; }
qint64 CallShare::lengthMs() const { return m_player ? m_player->duration() : 0; }

void CallShare::start(const QString &path)
{
    stop();
    m_on = true;
    m_paused = false;
    m_sawVideo = false;
    m_lastSecond = -1;
    m_rotation = 0;
    m_send.clear();
    m_here.clear();
    m_sendPrimed = m_herePrimed = false;
    m_rate = 0;
    auto *player = new QMediaPlayer(this);
    m_player = player;
    m_sink = new QVideoSink(player);
    player->setVideoSink(m_sink);
    connect(m_sink, &QVideoSink::videoFrameChanged, this, [this, player](const QVideoFrame &f) {
        if (player != m_player.data() || !m_on || m_paused || !f.isValid())
            return;
        m_sawVideo = true;
        // Its turn comes with each frame (the file's metadata can come after the first ones). The call's video carries
        // no mirror, so a mirrored file goes as it's stored.
        m_rotation = (int(f.rotation()) % 360 + 360) % 360;
        Q_EMIT frame(f);
    });
#ifdef HUGINN_SHARE_SOUND
    // The sound comes here as buffers (asked for at the call's rate, mono), never to a sound device of its own.
    QAudioFormat want;
    want.setSampleRate(AudioDsp::kRate);
    want.setChannelCount(1);
    want.setSampleFormat(QAudioFormat::Int16);
    m_audio = new QAudioBufferOutput(want, player);
    player->setAudioBufferOutput(m_audio);
    connect(m_audio, &QAudioBufferOutput::audioBufferReceived, this, [this, player](const QAudioBuffer &b) {
        if (player == m_player.data())
            onAudio(b);
    });
#endif
    connect(player, &QMediaPlayer::mediaStatusChanged, this, [this, player](QMediaPlayer::MediaStatus s) {
        if (player != m_player.data())
            return;
        if (s == QMediaPlayer::InvalidMedia) {
            Q_EMIT failed(QStringLiteral("That video can't be opened."));
            finish();
        } else if ((s == QMediaPlayer::LoadedMedia || s == QMediaPlayer::BufferedMedia) && !player->hasVideo() && !m_sawVideo) {
            Q_EMIT failed(QStringLiteral("That isn't a video Huginn can play."));
            finish();
        } else if (s == QMediaPlayer::EndOfMedia) {
            finish();   // played to the end: the camera's place goes back
        }
    });
    connect(player, &QMediaPlayer::errorOccurred, this, [this, player](QMediaPlayer::Error, const QString &) {
        if (player != m_player.data())
            return;
        Q_EMIT failed(QStringLiteral("That video can't be opened."));
        finish();
    });
    connect(player, &QMediaPlayer::positionChanged, this, [this, player](qint64 ms) {
        if (player != m_player.data() || ms / 1000 == m_lastSecond)
            return;
        m_lastSecond = ms / 1000;
        Q_EMIT changed();
    });
    connect(player, &QMediaPlayer::durationChanged, this, &CallShare::changed);
    player->setSource(path.startsWith(QLatin1String("file:")) ? QUrl(path) : QUrl::fromLocalFile(path));
    player->play();
    Q_EMIT changed();
}

void CallShare::pause(bool on)
{
    if (!m_on || !m_player || m_paused == on)
        return;
    m_paused = on;
    if (on)
        m_player->pause();
    else
        m_player->play();
    Q_EMIT changed();
}

void CallShare::stop()
{
    if (!m_on)
        return;
    finish();
}

void CallShare::finish()
{
    if (!m_on)
        return;
    m_on = false;
    m_paused = false;
    if (m_player) {
        QMediaPlayer *p = m_player;
        m_player = nullptr;
        p->stop();
        p->deleteLater();   // it may be inside one of its own signals now
    }
    m_sink = nullptr;
    m_audio = nullptr;
    m_rotation = 0;
    m_send.clear();
    m_here.clear();
    m_sendPrimed = m_herePrimed = false;
    Q_EMIT changed();
    Q_EMIT stopped();
}

void CallShare::onAudio(const QAudioBuffer &b)
{
    if (!m_on || m_paused || !b.isValid())
        return;
    const QAudioFormat f = b.format();
    const int ch = std::max(1, f.channelCount());
    const int bps = f.bytesPerSample();
    const int frames = int(b.frameCount());
    if (frames <= 0 || bps <= 0 || f.sampleRate() <= 0)
        return;
    // Mono, -1 to 1, whatever it came as.
    const char *d = b.constData<char>();
    m_mono.clear();
    m_mono.reserve(size_t(frames));
    for (int i = 0; i < frames; ++i) {
        float sum = 0;
        for (int c = 0; c < ch; ++c)
            sum += f.normalizedSampleValue(d + (qsizetype(i) * ch + c) * bps);
        m_mono.push_back(sum / float(ch));
    }
    if (f.sampleRate() != m_rate) {
        m_rate = f.sampleRate();
        m_resampler.setRates(m_rate, AudioDsp::kRate);
        m_resampler.reset();
    }
    m_res.clear();
    m_resampler.process(m_mono.data(), int(m_mono.size()), &m_res);
    push(m_res);
}

void CallShare::push(const std::vector<float> &mono16k)
{
    for (float s : mono16k) {
        const qint16 v = qint16(std::clamp(int(s * 32767.f), -32768, 32767));
        m_send.push_back(v);
        m_here.push_back(v);
    }
    // Nothing takes it (no microphone, or the speaker stalled): the oldest goes, so it never runs behind the picture.
    while (m_send.size() > kMostQueued)
        m_send.pop_front();
    while (m_here.size() > kMostQueued)
        m_here.pop_front();
}

bool CallShare::take(std::deque<qint16> &q, bool &primed, qint16 *out)
{
    if (!primed) {
        if (q.size() < kPrime)
            return false;
        primed = true;
    }
    if (q.size() < size_t(AudioDsp::kFrame)) {
        primed = false;   // ran dry: gather a little again before going on
        return false;
    }
    std::copy(q.begin(), q.begin() + AudioDsp::kFrame, out);
    q.erase(q.begin(), q.begin() + AudioDsp::kFrame);
    return true;
}
