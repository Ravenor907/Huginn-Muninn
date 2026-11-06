#include "videomsg.h"
#include "chat.h"
#include "groupvideo.h"
#include "morewire.h"
#include "photos.h"
#include "platform/scratch.h"

#include <QAudioInput>
#include <QAudioOutput>
#include <QCamera>
#include <QCameraDevice>
#include <QCameraFormat>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QMediaCaptureSession>
#include <QMediaDevices>
#include <QMediaFormat>
#include <QMediaPlayer>
#include <QMediaRecorder>
#include <QStandardPaths>
#include <QUrl>
#include <cstdlib>
#include <memory>

VideoMsg::VideoMsg(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    m_ticker.setInterval(200);
    connect(&m_ticker, &QTimer::timeout, this, [this] {
        Q_EMIT tick();
        if (m_recording && elapsed() >= MoreWire::kVideoMaxMs - 300)
            stopRecording();   // a minute at most: what there is waits for Send or Discard
    });
}

VideoMsg::~VideoMsg()
{
    closeCamera();
    stop();
    m_clip.fill(0);
}

QString VideoMsg::tempFile() const
{
    // Linux: the memory-backed runtime folder. Windows: Huginn's private scratch folder (see Scratch).
    return Scratch::path(QStringLiteral("huginn-video.mp4"));
}

void VideoMsg::setError(const QString &e)
{
    m_error = e;
    Q_EMIT changed();
}

int VideoMsg::elapsed() const { return m_recording ? int(QDateTime::currentMSecsSinceEpoch() - m_started) : 0; }

qreal VideoMsg::progress() const
{
    if (!m_player || m_player->duration() <= 0)
        return 0;
    return qreal(m_player->position()) / m_player->duration();
}

bool VideoMsg::hasCamera() const { return !QMediaDevices::videoInputs().isEmpty(); }

bool VideoMsg::openCamera(QObject *videoOutput)
{
    stop();
    closeCamera();
    m_error.clear();
    const QCameraDevice dev = QMediaDevices::defaultVideoInput();
    if (dev.isNull()) {
        setError(QStringLiteral("No camera found on this device."));
        return false;
    }
    m_camera = new QCamera(dev, this);
    // The smallest picture at least 480 high (or the largest there is): enough for a video message.
    QCameraFormat pick;
    for (const QCameraFormat &f : dev.videoFormats()) {
        const int h = f.resolution().height();
        const int best = pick.isNull() ? 0 : pick.resolution().height();
        if (pick.isNull() || (best < 480 && h > best) || (h >= 480 && h < best))
            pick = f;
    }
    if (!pick.isNull())
        m_camera->setCameraFormat(pick);
    connect(m_camera, &QCamera::errorOccurred, this, [this](QCamera::Error, const QString &e) {
        setError(QStringLiteral("The camera isn't available: ") + e);
        closeCamera();
    });
    m_session = new QMediaCaptureSession(this);
    m_input = new QAudioInput(this);
    m_recorder = new QMediaRecorder(this);
    m_session->setCamera(m_camera);
    m_session->setAudioInput(m_input);
    m_session->setRecorder(m_recorder);
    m_session->setVideoOutput(videoOutput);
    m_view = videoOutput;
    QMediaFormat f(QMediaFormat::MPEG4);
    f.setVideoCodec(QMediaFormat::VideoCodec::H264);
    f.setAudioCodec(QMediaFormat::AudioCodec::AAC);
    m_recorder->setMediaFormat(f);
    if (!pick.isNull()) {
        // Shrunk to at most 640 on the long side, keeping its shape.
        QSize s = pick.resolution();
        if (qMax(s.width(), s.height()) > kSide)
            s = s.scaled(kSide, kSide, Qt::KeepAspectRatio);
        m_recorder->setVideoResolution(QSize(s.width() & ~1, s.height() & ~1));
    }
    m_recorder->setEncodingMode(QMediaRecorder::AverageBitRateEncoding);
    m_recorder->setVideoBitRate(kVideoBitrate);
    m_recorder->setAudioBitRate(kAudioBitrate);
    m_recorder->setAudioChannelCount(1);
    connect(m_recorder, &QMediaRecorder::errorOccurred, this, [this](QMediaRecorder::Error, const QString &e) {
        setError(QStringLiteral("The video couldn't be recorded: ") + e);
        m_recording = false;
        m_ticker.stop();
        Scratch::shred(tempFile());
        Q_EMIT changed();
    });
    m_camera->start();
    Q_EMIT changed();
    return true;
}

void VideoMsg::closeCamera()
{
    if (m_recorder && m_recorder->recorderState() != QMediaRecorder::StoppedState) {
        m_recorder->disconnect(this);
        m_recorder->stop();
    }
    m_recording = false;
    if (m_camera)
        m_camera->stop();
    // Deleted later: a stopping recorder may still be finishing its file.
    for (QObject *o : std::initializer_list<QObject *>{m_session, m_recorder, m_input, m_camera})
        if (o)
            o->deleteLater();
    m_session = nullptr;
    m_recorder = nullptr;
    m_input = nullptr;
    m_camera = nullptr;
    Scratch::shred(tempFile());
    if (m_playing.isEmpty())
        m_ticker.stop();
    Q_EMIT changed();
}

void VideoMsg::record()
{
    if (!m_recorder || m_recording)
        return;
    discard();
    m_error.clear();
    Scratch::shred(tempFile());
    m_recorder->setOutputLocation(QUrl::fromLocalFile(tempFile()));
    m_recorder->record();
    m_recording = true;
    m_started = QDateTime::currentMSecsSinceEpoch();
    m_ticker.start();
    Q_EMIT changed();
}

void VideoMsg::stopRecording()
{
    if (!m_recording || !m_recorder)
        return;
    const int ms = qMin(elapsed(), MoreWire::kVideoMaxMs);
    m_recording = false;
    QMediaRecorder *rec = m_recorder;
    // The file is complete once the recorder says it has stopped.
    auto once = std::make_shared<QMetaObject::Connection>();
    *once = connect(rec, &QMediaRecorder::recorderStateChanged, this, [this, ms, once](QMediaRecorder::RecorderState s) {
        if (s != QMediaRecorder::StoppedState)
            return;
        disconnect(*once);
        QFile f(tempFile());
        QByteArray mp4;
        if (f.open(QIODevice::ReadOnly)) {
            mp4 = f.readAll();
            f.close();
        }
        Scratch::shred(tempFile());
        closeCamera();
        if (ms < 700 || mp4.isEmpty())
            return;
        if (!MoreWire::isVideo(mp4)) {
            setError(QStringLiteral("The video couldn't be recorded on this device."));
            return;
        }
        m_clip = mp4;
        const int real = MoreWire::mp4Ms(mp4);
        m_clipMs = qBound(1, real > 0 ? real : ms, MoreWire::kVideoMaxMs);
        Q_EMIT changed();
    });
    rec->stop();
    m_ticker.stop();
    Q_EMIT changed();
}

QString VideoMsg::send(const QString &where)
{
    if (m_clip.isEmpty())
        return QStringLiteral("There's no video to send.");
    if (m_playing == QLatin1String("review"))
        stop();
    const QString r = m_chat->sendVideo(where, m_clip, m_clipMs);
    if (r.isEmpty() || !r.contains(QLatin1String("needs a newer")))
        discard();
    return r;
}

void VideoMsg::discard()
{
    if (m_playing == QLatin1String("review"))
        stop();
    m_clip.fill(0);
    m_clip.clear();
    m_clipMs = 0;
    Q_EMIT changed();
}

void VideoMsg::play(const QString &id, QObject *videoOutput)
{
    stop();
    // A longer video in the chat (bigvideo.h): read from its sealed pieces as it plays, never whole in memory.
    QIODevice *big = id == QLatin1String("review") ? nullptr : m_chat->bigVideoDevice(id, this);
    if (big && !big->open(QIODevice::ReadOnly)) {
        delete big;
        big = nullptr;
    }
    // A longer video whose pieces aren't here (backups and moves leave them out): only its poster is.
    if (!big && id != QLatin1String("review") && m_chat->bigVideoGone(id)) {
        setError(QStringLiteral("This video isn't on this computer. Longer videos don't come along in a backup or a move."));
        return;
    }
    if (!big)
        m_shown = id == QLatin1String("review") ? m_clip : m_chat->blob(id);
    if (!big && !MoreWire::isVideo(m_shown)) {
        m_shown.clear();
        setError(QStringLiteral("This video can't be played."));
        return;
    }
    if (!m_player) {
        m_player = new QMediaPlayer(this);
        m_output = new QAudioOutput(this);
        m_player->setAudioOutput(m_output);
        connect(m_player, &QMediaPlayer::mediaStatusChanged, this, [this](QMediaPlayer::MediaStatus s) {
            if (s == QMediaPlayer::EndOfMedia) {
                m_paused = true;   // stays on its last picture; Play starts it again
                Q_EMIT changed();
            } else if (s == QMediaPlayer::InvalidMedia) {
                setError(QStringLiteral("This video can't be played."));
            }
        });
    }
    m_player->setVideoOutput(videoOutput);
    m_view = videoOutput;
    m_buffer.close();
    if (big) {
        m_big = big;
        m_player->setSourceDevice(big, QUrl(QStringLiteral("video.mp4")));
    } else {
        m_buffer.setData(m_shown);
        m_buffer.open(QIODevice::ReadOnly);
        m_player->setSourceDevice(&m_buffer, QUrl(QStringLiteral("video.mp4")));
    }
    m_player->play();
    m_playing = id;
    m_paused = false;
    m_ticker.start();
    Q_EMIT changed();
}

void VideoMsg::togglePause()
{
    if (!m_player || m_playing.isEmpty())
        return;
    if (m_player->playbackState() == QMediaPlayer::PlayingState) {
        m_player->pause();
        m_paused = true;
    } else {
        if (m_player->mediaStatus() == QMediaPlayer::EndOfMedia)
            m_player->setPosition(0);
        m_player->play();
        m_paused = false;
    }
    Q_EMIT changed();
}

void VideoMsg::stop()
{
    if (m_player) {
        m_player->stop();
        m_player->setVideoOutput(nullptr);
        if (m_big)
            m_player->setSource(QUrl());   // lets go of the pieces' device before it goes
    }
    if (m_big) {
        m_big->close();
        m_big->deleteLater();
        m_big = nullptr;
    }
    m_buffer.close();
    m_shown.fill(0);
    m_shown.clear();
    m_playing.clear();
    m_paused = false;
    if (!m_recording)
        m_ticker.stop();
    Q_EMIT changed();
}

QString VideoMsg::pickFile() const
{
    return QFileDialog::getOpenFileName(nullptr, QStringLiteral("Send a video"), QStandardPaths::writableLocation(QStandardPaths::MoviesLocation),
                                        QStringLiteral("Videos (*.mp4 *.m4v *.mov)"));
}

bool VideoMsg::fitsMessage(const QString &path) const
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly) || f.size() > Photos::kVideoSendBytes)
        return false;
    const QByteArray mp4 = f.readAll();
    return MoreWire::isVideo(mp4) && MoreWire::mp4Ms(mp4) <= MoreWire::kVideoMaxMs + 500;
}

QString VideoMsg::sendFile(const QString &where, const QString &path)
{
    QFile f(path);
    const qint64 max = Photos::kVideoSendBytes;
    if (!f.open(QIODevice::ReadOnly))
        return QStringLiteral("That video couldn't be opened.");
    if (f.size() > max)
        return QStringLiteral("That video is too big: video messages are up to 2 MB.");
    const QByteArray mp4 = f.readAll();
    if (!MoreWire::isVideo(mp4))
        return QStringLiteral("Only MP4 videos can be sent as video messages.");
    const int ms = MoreWire::mp4Ms(mp4);
    if (ms > MoreWire::kVideoMaxMs + 500)
        return QStringLiteral("Video messages are up to a minute long.");
    // Round 19 (FIX-LIST 24): a picked video goes without its location and camera details, as a group's does
    // (GroupVideo::scrubbed), and only one every app plays as it is.
    if (!GroupVideo::probe(mp4).plain)
        return QStringLiteral("This video's format doesn't play everywhere. It can go as a file.");
    const QByteArray clean = GroupVideo::scrubbed(mp4);
    if (clean.isEmpty())
        return QStringLiteral("Huginn can't take this video's location and camera details out. It can go as a file.");
    return m_chat->sendVideo(where, clean, ms > 0 ? ms : 1000);
}
