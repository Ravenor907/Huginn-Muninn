#include "opuscodec.h"

#include <cstring>

#ifdef HUGINN_HAVE_OPUS
#include <opus.h>

namespace
{
const int kRate = 16000;
const int kFrame = 320;
const int kMaxPacket = 1000;   // far above 16 kbps; media packets stay under 1200 bytes
}

OpusEncoder16::OpusEncoder16()
{
    int err = 0;
    OpusEncoder *e = opus_encoder_create(kRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (!e || err != OPUS_OK)
        return;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(16000));
    opus_encoder_ctl(e, OPUS_SET_VBR(1));
    opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(e, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(e, OPUS_SET_DTX(1));
    opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    m_enc = e;
}

void OpusEncoder16::setBitrate(int bps)
{
    bps = qBound(8000, bps, 64000);
    if (!m_enc || bps == m_bitrate)
        return;
    m_bitrate = bps;
    opus_encoder_ctl(static_cast<OpusEncoder *>(m_enc), OPUS_SET_BITRATE(bps));
}

void OpusEncoder16::setLossPercent(int pct)
{
    pct = qBound(0, pct, 40);
    if (!m_enc || pct == m_loss)
        return;
    m_loss = pct;
    opus_encoder_ctl(static_cast<OpusEncoder *>(m_enc), OPUS_SET_PACKET_LOSS_PERC(pct));
}

OpusEncoder16::~OpusEncoder16()
{
    if (m_enc)
        opus_encoder_destroy(static_cast<OpusEncoder *>(m_enc));
}

QByteArray OpusEncoder16::encode(const qint16 *pcm320)
{
    if (!m_enc)
        return QByteArray();
    QByteArray out(kMaxPacket, 0);
    const opus_int32 n = opus_encode(static_cast<OpusEncoder *>(m_enc), pcm320, kFrame, reinterpret_cast<unsigned char *>(out.data()), kMaxPacket);
    if (n <= 0)
        return QByteArray();
    out.resize(n);
    return out;
}

OpusDecoder16::OpusDecoder16()
{
    int err = 0;
    OpusDecoder *d = opus_decoder_create(kRate, 1, &err);
    if (d && err == OPUS_OK)
        m_dec = d;
}

OpusDecoder16::~OpusDecoder16()
{
    if (m_dec)
        opus_decoder_destroy(static_cast<OpusDecoder *>(m_dec));
}

namespace
{
bool run(void *dec, const QByteArray *packet, bool fec, qint16 *pcm)
{
    if (!dec) {
        std::memset(pcm, 0, kFrame * sizeof(qint16));
        return false;
    }
    const unsigned char *data = packet ? reinterpret_cast<const unsigned char *>(packet->constData()) : nullptr;
    const opus_int32 len = packet ? opus_int32(packet->size()) : 0;
    const int n = opus_decode(static_cast<OpusDecoder *>(dec), data, len, pcm, kFrame, fec ? 1 : 0);
    if (n == kFrame)
        return true;
    if (n > 0 && n < kFrame) {
        std::memset(pcm + n, 0, size_t(kFrame - n) * sizeof(qint16));
        return true;
    }
    std::memset(pcm, 0, kFrame * sizeof(qint16));
    return false;
}
}

bool OpusDecoder16::decode(const QByteArray &packet, qint16 *pcm320) { return run(m_dec, &packet, false, pcm320); }

bool OpusDecoder16::decodeFec(const QByteArray &next, qint16 *pcm320) { return run(m_dec, &next, true, pcm320); }

bool OpusDecoder16::conceal(qint16 *pcm320) { return run(m_dec, nullptr, false, pcm320); }

#else
// Built without libopus (CMake didn't find it): calls say they can't start; nothing else changes.
OpusEncoder16::OpusEncoder16() {}
OpusEncoder16::~OpusEncoder16() {}
void OpusEncoder16::setBitrate(int bps) { m_bitrate = bps; }
void OpusEncoder16::setLossPercent(int pct) { m_loss = pct; }
QByteArray OpusEncoder16::encode(const qint16 *) { return QByteArray(); }
OpusDecoder16::OpusDecoder16() {}
OpusDecoder16::~OpusDecoder16() {}
bool OpusDecoder16::decode(const QByteArray &, qint16 *pcm320) { std::memset(pcm320, 0, 320 * sizeof(qint16)); return false; }
bool OpusDecoder16::decodeFec(const QByteArray &, qint16 *pcm320) { std::memset(pcm320, 0, 320 * sizeof(qint16)); return false; }
bool OpusDecoder16::conceal(qint16 *pcm320) { std::memset(pcm320, 0, 320 * sizeof(qint16)); return false; }
#endif
