#include "voice.h"
#include "chat.h"
#include "platform/scratch.h"
#include "settings.h"

#include <QAudioDevice>
#include <QAudioInput>
#include <QAudioOutput>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QMediaCaptureSession>
#include <QMediaDevices>
#include <QMediaFormat>
#include <QMediaPlayer>
#include <QMediaRecorder>
#include <QStandardPaths>
#include <QUrl>
#include <cstdio>
#include <memory>

Voice::Voice(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    m_ticker.setInterval(150);
    connect(&m_ticker, &QTimer::timeout, this, [this] {
        const qint64 wall = QDateTime::currentMSecsSinceEpoch();
        const qint64 gap = wall - m_lastTick;
        m_lastTick = wall;
        // Asleep while Y (or the microphone) was held, or the clock stepped: the hold was never let go on purpose.
        // Thrown away, never sent.
        if (m_recording && slept(gap)) {
            cancel();
            Q_EMIT dropped();
            return;
        }
        Q_EMIT tick();
        if (m_recording && elapsed() >= 5 * 60 * 1000 && !m_target.isEmpty())
            finish(m_target);   // the 5-minute limit: send what there is
    });
}

Voice::~Voice()
{
    cancel();
    stop();
}

QString Voice::tempFile() const
{
    // Linux: the memory-backed runtime folder. Windows: Huginn's private scratch folder (see Scratch).
    return Scratch::path(QStringLiteral("huginn-voice.aac"));
}

int Voice::elapsed() const { return m_recording && m_since.isValid() ? int(qMin<qint64>(m_since.elapsed(), 24 * 3600 * 1000)) : 0; }

qreal Voice::progress() const
{
    if (!m_player || m_player->duration() <= 0)
        return 0;
    return qreal(m_player->position()) / m_player->duration();
}

bool Voice::selfTest()
{
    const QString mom = QStringLiteral("k1"), main = QStringLiteral("g:family:main");
    // Let go in the chat it started in: sent. In another chat or channel, or nowhere: thrown away.
    const bool same = sendable(mom, mom) && sendable(main, main);
    const bool other = !sendable(mom, QStringLiteral("k2")) && !sendable(main, QStringLiteral("g:family:games"))
        && !sendable(main, QStringLiteral("g:crew:main")) && !sendable(mom, QString()) && !sendable(QString(), QString());
    // Ticks 150 ms apart (or a slow second) carry on; a sleep, or the clock stepped either way, ends it.
    const bool clock = !slept(150) && !slept(3000) && slept(10001) && slept(600000) && slept(-60000) && !slept(-100);
    const bool ok = same && other && clock;
    std::printf("voice: a held message goes only to its own chat, never across a sleep: %s\n", ok ? "ok" : "FAILED");
    return ok;
}

QString Voice::length(int ms)
{
    const int s = (ms + 500) / 1000;
    return QStringLiteral("%1:%2").arg(s / 60).arg(s % 60, 2, 10, QLatin1Char('0'));
}

bool Voice::start(const QString &key)
{
    stop();
    cancel();
    m_target = key;
    m_error.clear();
    if (!m_session) {
        m_session = new QMediaCaptureSession(this);
        m_input = new QAudioInput(this);
        m_recorder = new QMediaRecorder(this);
        m_session->setAudioInput(m_input);
        m_session->setRecorder(m_recorder);
        QMediaFormat f;
        f.setFileFormat(QMediaFormat::AAC);   // ADTS: bare AAC frames, no container
        f.setAudioCodec(QMediaFormat::AudioCodec::AAC);
        m_recorder->setMediaFormat(f);
        m_recorder->setAudioChannelCount(1);
        m_recorder->setAudioSampleRate(44100);
        m_recorder->setAudioBitRate(32000);
        m_recorder->setQuality(QMediaRecorder::NormalQuality);
        connect(m_recorder, &QMediaRecorder::errorOccurred, this, [this](QMediaRecorder::Error, const QString &e) {
            m_error = QStringLiteral("The microphone isn't available: ") + e;
            cancel();
        });
    }
    // Settings › Calls › Microphone, while it's plugged in.
    QAudioDevice mic = QMediaDevices::defaultAudioInput();
    const QString micId = Settings::savedMic();
    for (const QAudioDevice &d : QMediaDevices::audioInputs())
        if (!micId.isEmpty() && QString::fromLatin1(d.id()) == micId)
            mic = d;
    m_input->setDevice(mic);
    Scratch::shred(tempFile());
    m_recorder->setOutputLocation(QUrl::fromLocalFile(tempFile()));
    m_recorder->record();
    m_recording = true;
    m_since.start();
    m_lastTick = QDateTime::currentMSecsSinceEpoch();
    m_ticker.start();
    Q_EMIT changed();
    return true;
}

bool Voice::finish(const QString &key)
{
    if (!m_recording)
        return false;
    // Only ever to the chat it was started for: let go in another chat, it's thrown away.
    if (!sendable(m_target, key)) {
        cancel();
        Q_EMIT dropped();
        return false;
    }
    m_sendTo = m_target;
    const int ms = qMin(elapsed(), 5 * 60 * 1000);
    m_recording = false;
    // The file is complete once the recorder says it has stopped.
    auto once = std::make_shared<QMetaObject::Connection>();
    const QString to = m_target;
    *once = connect(m_recorder, &QMediaRecorder::recorderStateChanged, this, [this, ms, to, once](QMediaRecorder::RecorderState s) {
        if (s != QMediaRecorder::StoppedState)
            return;
        disconnect(*once);
        QFile f(tempFile());
        QByteArray aac;
        if (f.open(QIODevice::ReadOnly)) {
            aac = f.readAll();
            f.close();
        }
        Scratch::shred(tempFile());
        if (ms >= 700 && !aac.isEmpty())
            m_chat->sendVoice(to, aac, ms);
        m_sendTo.clear();
    });
    m_recorder->stop();
    m_ticker.stop();
    Q_EMIT changed();
    return true;
}

void Voice::cancel()
{
    if (m_recorder && m_recorder->recorderState() != QMediaRecorder::StoppedState)
        m_recorder->stop();
    m_recording = false;
    m_sendTo.clear();
    m_target.clear();
    Scratch::shred(tempFile());
    m_ticker.stop();
    Q_EMIT changed();
}

void Voice::play(const QString &id)
{
    stop();
    m_sound = m_chat->blob(id);
    if (m_sound.isEmpty())
        return;
    if (!m_player) {
        m_player = new QMediaPlayer(this);
        m_output = new QAudioOutput(this);
        m_player->setAudioOutput(m_output);
        connect(m_player, &QMediaPlayer::mediaStatusChanged, this, [this](QMediaPlayer::MediaStatus s) {
            if (s == QMediaPlayer::EndOfMedia || s == QMediaPlayer::InvalidMedia)
                stop();
        });
    }
    // Settings › Calls › Speaker, while it's plugged in.
    QAudioDevice out = QMediaDevices::defaultAudioOutput();
    const QString outId = Settings::savedSpeaker();
    for (const QAudioDevice &d : QMediaDevices::audioOutputs())
        if (!outId.isEmpty() && QString::fromLatin1(d.id()) == outId)
            out = d;
    m_output->setDevice(out);
    m_buffer.close();
    m_buffer.setData(m_sound);
    m_buffer.open(QIODevice::ReadOnly);
    m_player->setSourceDevice(&m_buffer, QUrl(QStringLiteral("voice.aac")));
    m_player->play();
    m_playing = id;
    m_ticker.start();
    Q_EMIT changed();
}

void Voice::stop()
{
    if (m_player)
        m_player->stop();
    m_buffer.close();
    m_sound.fill(0);
    m_sound.clear();
    m_playing.clear();
    if (!m_recording)
        m_ticker.stop();
    Q_EMIT changed();
}
