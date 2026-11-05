#pragma once

#include <QByteArray>
#include <QtGlobal>

// libopus for calls, the settings both apps use (shared/docs/protocol.md 5.5): VOIP, mono, 16 kHz,
// 16 kbps VBR, 20 ms frames, in-band FEC (10 % expected loss), DTX, complexity 5.
// Only opuscodec.cpp includes <opus.h>, so nothing else needs the library's headers. Without
// HUGINN_HAVE_OPUS (CMake found no libopus) both classes exist but ok() is false.
// opusAvailable(): this build can make calls at all.
inline bool opusAvailable()
{
#ifdef HUGINN_HAVE_OPUS
    return true;
#else
    return false;
#endif
}

class OpusEncoder16
{
public:
    OpusEncoder16();
    ~OpusEncoder16();
    OpusEncoder16(const OpusEncoder16 &) = delete;
    OpusEncoder16 &operator=(const OpusEncoder16 &) = delete;
    bool ok() const { return m_enc != nullptr; }
    // One 20 ms frame (320 samples). Empty on error. With DTX a silent frame is 1 or 2 bytes:
    // isSilence() says so and the caller sends only some of those.
    QByteArray encode(const qint16 *pcm320);
    static bool isSilence(const QByteArray &packet) { return packet.size() <= 2; }
    // Between two c2 apps (shared/docs/protocol.md 5.8.4): 24 or 32 kbps, and the loss the other side
    // reports, so in-band FEC carries as much as the network needs. Unchanged when the same.
    void setBitrate(int bps);
    void setLossPercent(int pct);
    int bitrate() const { return m_bitrate; }
    int lossPercent() const { return m_loss; }

private:
    void *m_enc = nullptr;
    int m_bitrate = 16000, m_loss = 10;
};

class OpusDecoder16
{
public:
    OpusDecoder16();
    ~OpusDecoder16();
    OpusDecoder16(const OpusDecoder16 &) = delete;
    OpusDecoder16 &operator=(const OpusDecoder16 &) = delete;
    bool ok() const { return m_dec != nullptr; }
    // Each fills 320 samples and returns false on error (pcm is then silence).
    bool decode(const QByteArray &packet, qint16 *pcm320);
    // The frame before `next`, rebuilt from next's in-band FEC.
    bool decodeFec(const QByteArray &next, qint16 *pcm320);
    // Packet loss concealment (also the comfort noise in a DTX pause).
    bool conceal(qint16 *pcm320);

private:
    void *m_dec = nullptr;
};
