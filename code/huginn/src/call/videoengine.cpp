#include "videoengine.h"

#include <QCamera>
#include <QCameraDevice>
#include <QCameraFormat>
#include <QGuiApplication>
#include <QImage>
#include <QMediaCaptureSession>
#include <QMediaDevices>
#include <QScreen>
#include <QScreenCapture>
#include <QWindowCapture>
#include <QVideoFrameFormat>
#include <QVideoSink>
#include <algorithm>
#include <cstdlib>
#include <cstring>

// ------------------------------------------------------------------ pictures

I420 VideoFrames::toI420(const QImage &image, const QSize &target)
{
    I420 out;
    if (image.isNull() || target.isEmpty())
        return out;
    QSize s = image.size();
    if (s.width() > target.width() || s.height() > target.height())
        s = s.scaled(target, Qt::KeepAspectRatio);
    const int w = s.width() & ~1, h = s.height() & ~1;
    if (w < 16 || h < 16)
        return out;
    QImage img = image.size() == QSize(w, h) ? image : image.scaled(w, h, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);
    out.width = w;
    out.height = h;
    out.data.resize(w * h * 3 / 2);
    uchar *Y = reinterpret_cast<uchar *>(out.data.data());
    uchar *U = Y + w * h;
    uchar *V = U + (w / 2) * (h / 2);
    for (int y = 0; y < h; y += 2) {
        const QRgb *r0 = reinterpret_cast<const QRgb *>(img.constScanLine(y));
        const QRgb *r1 = reinterpret_cast<const QRgb *>(img.constScanLine(y + 1));
        for (int x = 0; x < w; x += 2) {
            int sr = 0, sg = 0, sb = 0;
            const QRgb px[4] = {r0[x], r0[x + 1], r1[x], r1[x + 1]};
            for (int k = 0; k < 4; ++k) {
                const int r = qRed(px[k]), g = qGreen(px[k]), b = qBlue(px[k]);
                Y[(y + (k >> 1)) * w + x + (k & 1)] = uchar(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                sr += r;
                sg += g;
                sb += b;
            }
            sr >>= 2;
            sg >>= 2;
            sb >>= 2;
            U[(y / 2) * (w / 2) + x / 2] = uchar(std::clamp(((-38 * sr - 74 * sg + 112 * sb + 128) >> 8) + 128, 0, 255));
            V[(y / 2) * (w / 2) + x / 2] = uchar(std::clamp(((112 * sr - 94 * sg - 18 * sb + 128) >> 8) + 128, 0, 255));
        }
    }
    return out;
}

I420 VideoFrames::toI420(const QVideoFrame &frame, const QSize &target)
{
    if (!frame.isValid())
        return I420();
    return toI420(frame.toImage(), target);
}

QVideoFrame VideoFrames::toFrame(const I420 &pic)
{
    if (!pic.valid())
        return QVideoFrame();
    QVideoFrame f(QVideoFrameFormat(QSize(pic.width, pic.height), QVideoFrameFormat::Format_YUV420P));
    if (!f.map(HUGINN_MAP_WRITE))
        return QVideoFrame();
    const uchar *src[3] = {pic.y(), pic.u(), pic.v()};
    for (int p = 0; p < 3; ++p) {
        const int pw = p ? pic.width / 2 : pic.width, ph = p ? pic.height / 2 : pic.height;
        uchar *dst = f.bits(p);
        const int stride = f.bytesPerLine(p);
        if (!dst)
            continue;
        for (int y = 0; y < ph; ++y)
            std::memcpy(dst + qsizetype(y) * stride, src[p] + qsizetype(y) * pw, size_t(pw));
    }
    f.unmap();
    return f;
}

I420 VideoFrames::testPattern(int w, int h, int t)
{
    I420 p;
    p.width = w;
    p.height = h;
    p.data.resize(w * h * 3 / 2);
    uchar *Y = reinterpret_cast<uchar *>(p.data.data());
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const bool box = x >= (t * 4) % (w - 64) && x < (t * 4) % (w - 64) + 64 && y >= h / 3 && y < h / 3 + 64;
            Y[y * w + x] = box ? 235 : uchar(16 + ((x + y + t) % 200));
        }
    std::memset(Y + w * h, 128, size_t(w * h / 2));
    return p;
}

// ------------------------------------------------------------------ sending

void VideoEncodeWorker::frame(const QVideoFrame &f)
{
    if (!m_clock.isValid())
        m_clock.start();
    const qint64 now = m_clock.elapsed();
    if (now - m_last >= 1000 / qMax(1, m_fps) - 5) {
        m_last = now;
        encode(VideoFrames::toI420(f, m_target));
    }
    busy.storeRelease(0);
}

void VideoEncodeWorker::picture(const I420 &pic)
{
    encode(pic);
    busy.storeRelease(0);
}

void VideoEncodeWorker::encode(const I420 &pic)
{
    if (!pic.valid())
        return;
    if (!m_enc || m_enc->width() != pic.width || m_enc->height() != pic.height) {
        m_enc = std::make_unique<Vp8Encoder>(pic.width, pic.height, m_fps, m_kbps, m_screen);
        m_key = true;
        if (!m_enc->ok())
            return;
    }
    bool isKey = false;
    const QByteArray out = m_enc->encode(pic, m_key, &isKey);
    if (out.isEmpty())
        return;
    if (isKey)
        m_key = false;
    Q_EMIT encoded(out, isKey);
}

void VideoEncodeWorker::setProfile(int width, int height, int fps, int kbps)
{
    const QSize t(width, height);
    if (t == m_target && fps == m_fps)
        return;   // the same profile: the bitrate the reports gave stays
    m_enc.reset();   // the next frame starts a new encoder (with a keyframe)
    m_target = t;
    m_fps = fps;
    setKbps(kbps);
}

void VideoEncodeWorker::setKbps(int kbps)
{
    m_kbps = kbps;
    if (m_enc)
        m_enc->setKbps(kbps);
}

void VideoEncodeWorker::key() { m_key = true; }

VideoSender::VideoSender(Source source, QObject *parent) : QObject(parent), m_source(source)
{
    m_worker = new VideoEncodeWorker(source == Screen);
    m_worker->moveToThread(&m_thread);
    connect(&m_thread, &QThread::finished, m_worker, &QObject::deleteLater);
    connect(m_worker, &VideoEncodeWorker::encoded, this, &VideoSender::encoded);
    m_thread.setObjectName(source == Screen ? QStringLiteral("huginn-screen-enc") : QStringLiteral("huginn-video-enc"));
    m_thread.start();
}

VideoSender::~VideoSender()
{
    stop();
    m_thread.quit();
    m_thread.wait(2000);
}

bool VideoSender::start()
{
    if (m_running)
        return true;
    if (!m_capture)
        m_capture = new QMediaCaptureSession(this);
    if (!m_sink) {
        m_sink = new QVideoSink(this);
        connect(m_sink, &QVideoSink::videoFrameChanged, this, &VideoSender::onFrame);
    }
    m_capture->setVideoSink(m_sink);
    if (m_source == Camera) {
        const QList<QCameraDevice> inputs = QMediaDevices::videoInputs();
        if (inputs.isEmpty())
            return false;
        const QCameraDevice dev = inputs.at(m_cameraIndex % inputs.size());
        delete m_camera;
        m_camera = new QCamera(dev, this);
        // The camera format nearest the profile's size, so little is thrown away.
        QCameraFormat best;
        qint64 bestScore = -1;
        for (const QCameraFormat &f : dev.videoFormats()) {
            const qint64 area = qint64(f.resolution().width()) * f.resolution().height();
            const qint64 score = std::abs(area - 640LL * 480) + (f.maxFrameRate() < 20 ? 10000000 : 0);
            if (bestScore < 0 || score < bestScore) {
                best = f;
                bestScore = score;
            }
        }
        if (!best.isNull())
            m_camera->setCameraFormat(best);
        connect(m_camera, &QCamera::errorOccurred, this, [this](QCamera::Error, const QString &text) { Q_EMIT failed(text); });
        m_capture->setCamera(m_camera);
        m_camera->start();
    } else if (m_useWindow) {
        // One app window: only it is captured, wherever it is and whatever is in front of it.
        if (!m_window.isValid())
            return false;
        if (!m_windowCap) {
            m_windowCap = new QWindowCapture(this);
            connect(m_windowCap, &QWindowCapture::errorOccurred, this, [this](QWindowCapture::Error, const QString &text) { Q_EMIT failed(text); });
        }
        m_windowCap->setWindow(m_window);
        m_capture->setWindowCapture(m_windowCap);
        m_windowCap->start();
    } else {
        if (!m_screen) {
            m_screen = new QScreenCapture(this);
            connect(m_screen, &QScreenCapture::errorOccurred, this, [this](QScreenCapture::Error, const QString &text) { Q_EMIT failed(text); });
        }
        QScreen *screen = m_target ? m_target.data() : QGuiApplication::primaryScreen();
        if (!screen)
            return false;
        m_screen->setScreen(screen);
        m_capture->setScreenCapture(m_screen);
        m_screen->start();
    }
    m_running = true;
    requestKey();
    return true;
}

void VideoSender::stop()
{
    if (!m_running)
        return;
    m_running = false;
    if (m_camera)
        m_camera->stop();
    if (m_screen)
        m_screen->stop();
    if (m_windowCap)
        m_windowCap->stop();
    if (m_preview)
        m_preview->setVideoFrame(QVideoFrame());
}

bool VideoSender::nextCamera()
{
    if (m_source != Camera || QMediaDevices::videoInputs().size() < 2)
        return false;
    ++m_cameraIndex;
    if (m_running) {
        m_running = false;
        if (m_camera)
            m_camera->stop();
        start();
    }
    return true;
}

QString VideoSender::cameraName() const { return m_camera ? m_camera->cameraDevice().description() : QString(); }

void VideoSender::setProfile(const VideoWire::Profile &p)
{
    QMetaObject::invokeMethod(m_worker, [w = m_worker, p] { w->setProfile(p.width, p.height, p.fps, p.capKbps * 6 / 10); }, Qt::QueuedConnection);
}

void VideoSender::setKbps(int kbps)
{
    QMetaObject::invokeMethod(m_worker, [w = m_worker, kbps] { w->setKbps(kbps); }, Qt::QueuedConnection);
}

void VideoSender::requestKey()
{
    QMetaObject::invokeMethod(m_worker, [w = m_worker] { w->key(); }, Qt::QueuedConnection);
}

void VideoSender::onFrame(const QVideoFrame &f)
{
    if (!m_running || !f.isValid())
        return;
    if (m_preview)
        m_preview->setVideoFrame(f);
    // The encoder takes the newest frame when it is free; a busy one skips frames instead of falling behind.
    if (!m_worker->busy.testAndSetOrdered(0, 1))
        return;
    QMetaObject::invokeMethod(m_worker, [w = m_worker, f] { w->frame(f); }, Qt::QueuedConnection);
}

void VideoSender::feed(const I420 &pic)
{
    if (!m_worker->busy.testAndSetOrdered(0, 1))
        return;
    QMetaObject::invokeMethod(m_worker, [w = m_worker, pic] { w->picture(pic); }, Qt::QueuedConnection);
}

// ------------------------------------------------------------------ receiving

void VideoDecodeWorker::decode(const QByteArray &vp8, bool key, int rotation)
{
    queued.fetchAndSubOrdered(1);
    if (!m_haveKey && !key) {
        Q_EMIT needKey();
        return;
    }
    I420 pic;
    if (!m_dec.decode(vp8, &pic)) {
        m_haveKey = false;
        Q_EMIT needKey();
        return;
    }
    m_haveKey = true;
    Q_EMIT picture(VideoFrames::toFrame(pic), rotation, QSize(pic.width, pic.height));
}

VideoReceiver::VideoReceiver(QObject *parent) : QObject(parent)
{
    m_worker = new VideoDecodeWorker;
    m_worker->moveToThread(&m_thread);
    connect(&m_thread, &QThread::finished, m_worker, &QObject::deleteLater);
    connect(m_worker, &VideoDecodeWorker::needKey, this, &VideoReceiver::needKey);
    connect(m_worker, &VideoDecodeWorker::picture, this, [this](const QVideoFrame &f, int rotation, const QSize &size) {
        ++m_frames;
        m_last = f;
        if (rotation != m_rotation || size != m_size) {
            m_rotation = rotation;
            m_size = size;
            Q_EMIT changed();
        }
        if (m_sink)
            m_sink->setVideoFrame(f);
    });
    m_thread.setObjectName(QStringLiteral("huginn-video-dec"));
    m_thread.start();
}

VideoReceiver::~VideoReceiver()
{
    m_thread.quit();
    m_thread.wait(2000);
}

void VideoReceiver::frame(const QByteArray &vp8, bool key, int rotation)
{
    if (m_worker->queued.loadAcquire() > 30) {
        // Far behind (a slow computer): drop what waits and start again at a keyframe.
        Q_EMIT needKey();
        if (!key)
            return;
    }
    m_worker->queued.fetchAndAddOrdered(1);
    QMetaObject::invokeMethod(m_worker, [w = m_worker, vp8, key, rotation] { w->decode(vp8, key, rotation); }, Qt::QueuedConnection);
}
