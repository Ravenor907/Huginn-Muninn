#pragma once

#include "videowire.h"
#include "vpxcodec.h"

#include <QAtomicInt>
#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QSize>
#include <QThread>
#include <QVideoFrame>
#include <QVideoSink>
#define HUGINN_MAP_READ QVideoFrame::ReadOnly
#define HUGINN_MAP_WRITE QVideoFrame::WriteOnly
#include <memory>

class QCamera;
class QMediaCaptureSession;
class QScreenCapture;

// Cameras, the screen and the pictures of video calls and screen share (shared/docs/protocol.md 5.8.5
// to 5.8.7). Frames come from Qt Multimedia (QCamera, QScreenCapture), are scaled to the call's
// profile and encoded with VP8 on a worker thread; what comes from the other side is decoded there too
// and shown through a QVideoSink (a QML VideoOutput's). Sealing and sending is CallMedia's.
namespace VideoFrames
{
// Scaled down (never up) to fit inside target, even width and height, BT.601 limited range.
I420 toI420(const QVideoFrame &frame, const QSize &target);
I420 toI420(const QImage &image, const QSize &target);
QVideoFrame toFrame(const I420 &pic);
// A picture for the self-test: a moving gradient with a box.
I420 testPattern(int width, int height, int t);
}

class VideoEncodeWorker : public QObject
{
    Q_OBJECT
public:
    explicit VideoEncodeWorker(bool screen) : m_screen(screen) {}
    QAtomicInt busy;
    void frame(const QVideoFrame &f);
    void picture(const I420 &pic);
public Q_SLOTS:
    void setProfile(int width, int height, int fps, int kbps);
    void setKbps(int kbps);
    void key();
Q_SIGNALS:
    void encoded(const QByteArray &vp8, bool key);
private:
    void encode(const I420 &pic);
    bool m_screen;
    std::unique_ptr<Vp8Encoder> m_enc;
    QSize m_target{640, 480};
    int m_fps = 24, m_kbps = 420;
    bool m_key = true;
    QElapsedTimer m_clock;
    qint64 m_last = -100000;
};

// Camera or screen in, VP8 frames out (encoded()).
class VideoSender : public QObject
{
    Q_OBJECT
public:
    enum Source { Camera, Screen };
    explicit VideoSender(Source source, QObject *parent = nullptr);
    ~VideoSender() override;
    // False when there is no camera (or screen) to use.
    bool start();
    void stop();
    bool running() const { return m_running; }
    void setProfile(const VideoWire::Profile &p);
    void setKbps(int kbps);
    void requestKey();
    // The camera's own picture, for the small self view (picture in picture).
    void setPreview(QVideoSink *sink) { m_preview = sink; }
    // The next camera (front, back, an external one), when there is more than one.
    bool nextCamera();
    QString cameraName() const;
    // Self-test: encode this picture as if it came from the camera.
    void feed(const I420 &pic);

Q_SIGNALS:
    void encoded(const QByteArray &vp8, bool key);
    void failed(const QString &why);

private:
    void onFrame(const QVideoFrame &f);
    Source m_source;
    QMediaCaptureSession *m_capture = nullptr;
    QCamera *m_camera = nullptr;
    QScreenCapture *m_screen = nullptr;
    QVideoSink *m_sink = nullptr;
    QPointer<QVideoSink> m_preview;
    QThread m_thread;
    VideoEncodeWorker *m_worker = nullptr;
    int m_cameraIndex = 0;
    bool m_running = false;
};

class VideoDecodeWorker : public QObject
{
    Q_OBJECT
public:
    QAtomicInt queued;
    void decode(const QByteArray &vp8, bool key, int rotation);
Q_SIGNALS:
    void picture(const QVideoFrame &frame, int rotation, const QSize &size);
    void needKey();
private:
    Vp8Decoder m_dec;
    bool m_haveKey = false;
};

// VP8 frames in (from CallMedia::videoFrame), pictures out to a QVideoSink.
class VideoReceiver : public QObject
{
    Q_OBJECT
public:
    explicit VideoReceiver(QObject *parent = nullptr);
    ~VideoReceiver() override;
    void setSink(QVideoSink *sink) { m_sink = sink; }
    void frame(const QByteArray &vp8, bool key, int rotation);
    int rotation() const { return m_rotation; }
    QSize size() const { return m_size; }
    int frames() const { return m_frames; }
    QVideoFrame lastFrame() const { return m_last; }

Q_SIGNALS:
    void changed();                 // size or rotation
    void needKey();                 // the decoder lost its place: ask the sender for a keyframe

private:
    QThread m_thread;
    VideoDecodeWorker *m_worker = nullptr;
    QPointer<QVideoSink> m_sink;
    int m_rotation = 0, m_frames = 0;
    QSize m_size;
    QVideoFrame m_last;
};
