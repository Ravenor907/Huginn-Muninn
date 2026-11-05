#include "vpxcodec.h"

#include <cstring>

bool vp8KeyframeSize(const QByteArray &f, int *width, int *height)
{
    if (f.size() < 10 || (quint8(f[0]) & 1) != 0 || quint8(f[3]) != 0x9D || quint8(f[4]) != 0x01 || quint8(f[5]) != 0x2A)
        return false;
    *width = (quint8(f[6]) | (quint8(f[7]) << 8)) & 0x3FFF;
    *height = (quint8(f[8]) | (quint8(f[9]) << 8)) & 0x3FFF;
    return *width > 0 && *height > 0;
}

#ifdef HUGINN_HAVE_VPX
#include <vpx/vp8cx.h>
#include <vpx/vp8dx.h>
#include <vpx/vpx_decoder.h>
#include <vpx/vpx_encoder.h>

Vp8Encoder::Vp8Encoder(int width, int height, int fps, int kbps, bool screen) : m_w(width & ~1), m_h(height & ~1), m_kbps(kbps), m_fps(fps)
{
    auto *cfg = new vpx_codec_enc_cfg_t;
    if (vpx_codec_enc_config_default(vpx_codec_vp8_cx(), cfg, 0) != VPX_CODEC_OK) {
        delete cfg;
        return;
    }
    cfg->g_w = unsigned(m_w);
    cfg->g_h = unsigned(m_h);
    cfg->g_timebase.num = 1;
    cfg->g_timebase.den = 1000;
    cfg->rc_target_bitrate = unsigned(kbps);
    cfg->g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;
    cfg->g_lag_in_frames = 0;
    cfg->g_threads = 2;
    cfg->g_pass = VPX_RC_ONE_PASS;
    cfg->rc_end_usage = VPX_CBR;
    cfg->rc_min_quantizer = 4;
    cfg->rc_max_quantizer = 56;
    cfg->rc_undershoot_pct = 100;
    cfg->rc_overshoot_pct = 15;
    cfg->rc_buf_initial_sz = 500;
    cfg->rc_buf_optimal_sz = 600;
    cfg->rc_buf_sz = 1000;
    cfg->rc_dropframe_thresh = screen ? 0 : 30;
    cfg->kf_mode = VPX_KF_AUTO;
    cfg->kf_max_dist = unsigned(fps * 10);   // a keyframe at least every 10 s (5.8.5)
    auto *ctx = new vpx_codec_ctx_t;
    if (vpx_codec_enc_init(ctx, vpx_codec_vp8_cx(), cfg, 0) != VPX_CODEC_OK) {
        delete ctx;
        delete cfg;
        return;
    }
    vpx_codec_control(ctx, VP8E_SET_CPUUSED, screen ? -4 : -6);   // realtime speed
    vpx_codec_control(ctx, VP8E_SET_NOISE_SENSITIVITY, 0);
    vpx_codec_control(ctx, VP8E_SET_TOKEN_PARTITIONS, VP8_ONE_TOKENPARTITION);
    vpx_codec_control(ctx, VP8E_SET_STATIC_THRESHOLD, screen ? 100 : 1);
    vpx_codec_control(ctx, VP8E_SET_SCREEN_CONTENT_MODE, screen ? 1 : 0);
    m_ctx = ctx;
    m_cfg = cfg;
}

Vp8Encoder::~Vp8Encoder()
{
    if (m_ctx) {
        vpx_codec_destroy(static_cast<vpx_codec_ctx_t *>(m_ctx));
        delete static_cast<vpx_codec_ctx_t *>(m_ctx);
    }
    delete static_cast<vpx_codec_enc_cfg_t *>(m_cfg);
}

void Vp8Encoder::setKbps(int kbps)
{
    if (!m_ctx || kbps == m_kbps || kbps <= 0)
        return;
    m_kbps = kbps;
    auto *cfg = static_cast<vpx_codec_enc_cfg_t *>(m_cfg);
    cfg->rc_target_bitrate = unsigned(kbps);
    vpx_codec_enc_config_set(static_cast<vpx_codec_ctx_t *>(m_ctx), cfg);
}

QByteArray Vp8Encoder::encode(const I420 &pic, bool forceKey, bool *isKey)
{
    *isKey = false;
    if (!m_ctx || !pic.valid() || pic.width != m_w || pic.height != m_h)
        return QByteArray();
    vpx_image_t img;
    if (!vpx_img_wrap(&img, VPX_IMG_FMT_I420, unsigned(m_w), unsigned(m_h), 1, const_cast<uchar *>(pic.y())))
        return QByteArray();
    auto *ctx = static_cast<vpx_codec_ctx_t *>(m_ctx);
    const qint64 step = 1000 / qMax(1, m_fps);
    if (vpx_codec_encode(ctx, &img, m_pts, static_cast<unsigned long>(step), forceKey ? VPX_EFLAG_FORCE_KF : 0, VPX_DL_REALTIME) != VPX_CODEC_OK)
        return QByteArray();
    m_pts += step;
    QByteArray out;
    vpx_codec_iter_t it = nullptr;
    while (const vpx_codec_cx_pkt_t *p = vpx_codec_get_cx_data(ctx, &it)) {
        if (p->kind != VPX_CODEC_CX_FRAME_PKT)
            continue;
        out.append(static_cast<const char *>(p->data.frame.buf), int(p->data.frame.sz));
        if (p->data.frame.flags & VPX_FRAME_IS_KEY)
            *isKey = true;
    }
    return out;
}

Vp8Decoder::Vp8Decoder()
{
    auto *ctx = new vpx_codec_ctx_t;
    vpx_codec_dec_cfg_t cfg;
    std::memset(&cfg, 0, sizeof cfg);
    cfg.threads = 2;
    if (vpx_codec_dec_init(ctx, vpx_codec_vp8_dx(), &cfg, 0) != VPX_CODEC_OK) {
        delete ctx;
        return;
    }
    m_ctx = ctx;
}

Vp8Decoder::~Vp8Decoder()
{
    if (m_ctx) {
        vpx_codec_destroy(static_cast<vpx_codec_ctx_t *>(m_ctx));
        delete static_cast<vpx_codec_ctx_t *>(m_ctx);
    }
}

bool Vp8Decoder::decode(const QByteArray &vp8, I420 *out)
{
    if (!m_ctx || vp8.isEmpty())
        return false;
    auto *ctx = static_cast<vpx_codec_ctx_t *>(m_ctx);
    if (vpx_codec_decode(ctx, reinterpret_cast<const uint8_t *>(vp8.constData()), unsigned(vp8.size()), nullptr, 0) != VPX_CODEC_OK)
        return false;
    vpx_codec_iter_t it = nullptr;
    vpx_image_t *img = nullptr, *last = nullptr;
    while ((img = vpx_codec_get_frame(ctx, &it)))
        last = img;
    if (!last || last->fmt != VPX_IMG_FMT_I420)
        return false;
    const int w = int(last->d_w) & ~1, h = int(last->d_h) & ~1;
    out->width = w;
    out->height = h;
    out->data.resize(w * h * 3 / 2);
    char *dst = out->data.data();
    for (int p = 0; p < 3; ++p) {
        const int pw = p ? w / 2 : w, ph = p ? h / 2 : h;
        const uchar *src = last->planes[p];
        for (int y = 0; y < ph; ++y) {
            std::memcpy(dst, src + qsizetype(y) * last->stride[p], size_t(pw));
            dst += pw;
        }
    }
    return true;
}

#else
// Built without libvpx: no video.
Vp8Encoder::Vp8Encoder(int width, int height, int fps, int kbps, bool) : m_w(width), m_h(height), m_kbps(kbps), m_fps(fps) {}
Vp8Encoder::~Vp8Encoder() {}
void Vp8Encoder::setKbps(int) {}
QByteArray Vp8Encoder::encode(const I420 &, bool, bool *isKey) { *isKey = false; return QByteArray(); }
Vp8Decoder::Vp8Decoder() {}
Vp8Decoder::~Vp8Decoder() {}
bool Vp8Decoder::decode(const QByteArray &, I420 *) { return false; }
#endif
