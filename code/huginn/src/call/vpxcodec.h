#pragma once

#include <QByteArray>
#include <QtGlobal>

// VP8 for video calls and screen share (shared/docs/protocol.md 5.8.5), with libvpx: realtime, error
// resilient, one token partition, a keyframe on request. Muninn uses Android's MediaCodec VP8, the same
// bitstream. Only vpxcodec.cpp includes <vpx/...>; without HUGINN_HAVE_VPX (CMake found no libvpx)
// both classes exist but ok() is false and this build offers no video (vpxAvailable()).
inline bool vpxAvailable()
{
#ifdef HUGINN_HAVE_VPX
    return true;
#else
    return false;
#endif
}

// One picture, I420: Y plane width x height, U and V planes (width/2) x (height/2), tightly packed.
struct I420 {
    int width = 0, height = 0;
    QByteArray data;
    bool valid() const { return width > 0 && height > 0 && data.size() == width * height * 3 / 2; }
    const uchar *y() const { return reinterpret_cast<const uchar *>(data.constData()); }
    const uchar *u() const { return y() + width * height; }
    const uchar *v() const { return u() + (width / 2) * (height / 2); }
};

class Vp8Encoder
{
public:
    // screen: tuned for text and still pictures (screen share).
    Vp8Encoder(int width, int height, int fps, int kbps, bool screen = false);
    ~Vp8Encoder();
    Vp8Encoder(const Vp8Encoder &) = delete;
    Vp8Encoder &operator=(const Vp8Encoder &) = delete;
    bool ok() const { return m_ctx != nullptr; }
    int width() const { return m_w; }
    int height() const { return m_h; }
    void setKbps(int kbps);
    // One picture of exactly width() x height(). Empty when the encoder dropped it (rate control).
    QByteArray encode(const I420 &pic, bool forceKey, bool *isKey);

private:
    void *m_ctx = nullptr;
    void *m_cfg = nullptr;
    int m_w = 0, m_h = 0, m_kbps = 0;
    qint64 m_pts = 0;
    int m_fps = 24;
};

class Vp8Decoder
{
public:
    Vp8Decoder();
    ~Vp8Decoder();
    Vp8Decoder(const Vp8Decoder &) = delete;
    Vp8Decoder &operator=(const Vp8Decoder &) = delete;
    bool ok() const { return m_ctx != nullptr; }
    // False when the frame couldn't be decoded (the caller asks for a keyframe).
    bool decode(const QByteArray &vp8, I420 *out);

private:
    void *m_ctx = nullptr;
};

// The size a VP8 keyframe says (RFC 6386 9.1); false when it isn't a keyframe.
bool vp8KeyframeSize(const QByteArray &frame, int *width, int *height);
