#include "audiodsp.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace
{
const double kPi = 3.14159265358979323846;   // M_PI is missing on MSVC
}

void AudioDsp::Resampler::setRates(int inRate, int outRate)
{
    m_in = inRate > 0 ? inRate : kRate;
    m_out = outRate > 0 ? outRate : kRate;
    m_step = double(m_in) / double(m_out);
    reset();
}

void AudioDsp::Resampler::process(const float *in, int n, std::vector<float> *out)
{
    if (n <= 0)
        return;
    if (passthrough()) {
        out->insert(out->end(), in, in + n);
        return;
    }
    int i = 0;
    if (!m_primed) {
        m_last = in[0];
        m_primed = true;
        i = 1;
        m_pos = 0;
    }
    // Output samples fall between m_last (position 0) and in[i] (position 1).
    for (; i < n; ++i) {
        const float next = in[i];
        while (m_pos < 1.0) {
            out->push_back(float(m_last + (next - m_last) * m_pos));
            m_pos += m_step;
        }
        m_pos -= 1.0;
        m_last = next;
    }
}

double AudioDsp::levelDb(const qint16 *pcm, int n)
{
    if (n <= 0)
        return -100;
    double sum = 0;
    for (int i = 0; i < n; ++i)
        sum += double(pcm[i]) * double(pcm[i]);
    const double rms = std::sqrt(sum / n) / 32768.0;
    return rms > 1e-5 ? 20.0 * std::log10(rms) : -100;
}

void AudioDsp::mixUnder(qint16 *into, const qint16 *video, int n, bool talk)
{
    const int div = talk ? 4 : 2;
    for (int i = 0; i < n; ++i) {
        const int v = int(into[i]) + int(video[i]) / div;
        into[i] = qint16(v < -32768 ? -32768 : v > 32767 ? 32767 : v);
    }
}

bool AudioDsp::talking(const qint16 *pcm, int n)
{
    if (n <= 0)
        return false;
    qint64 sum = 0;
    for (int i = 0; i < n; ++i)
        sum += std::abs(int(pcm[i]));
    return sum / n > 900;
}

bool AudioDsp::Speaking::frame(double levelDb)
{
    if (levelDb > -45) {
        if (++m_loud >= 2) {
            m_on = true;
            m_hold = 15;   // 300 ms of frames
        }
    } else {
        m_loud = 0;
        if (m_hold > 0)
            --m_hold;
        if (m_hold == 0)
            m_on = false;
    }
    return m_on;
}

double AudioDsp::Gate::threshold() const
{
    if (!m_auto)
        return m_manual;
    // Nothing measured yet (the first 2 s after joining): the manual default, so game sound doesn't open it at once.
    if (!m_measured)
        return kDefaultDb;
    const double t = floorDb() + 15;
    return t < -45 ? -45 : t > kMaxDb ? kMaxDb : t;
}

bool AudioDsp::Gate::frame(double levelDb)
{
    // The steady sound: the quietest frame in 2 s blocks (100 frames), this block's and the last one's.
    if (levelDb < m_blockMin)
        m_blockMin = levelDb;
    if (++m_blockFrames >= 100) {
        m_lastMin = m_blockMin;
        m_blockMin = 0;
        m_blockFrames = 0;
        m_measured = true;
    }
    const double t = threshold();
    if (levelDb > t || (m_open && levelDb > t - 6)) {
        m_open = true;
        m_hold = 20;   // 400 ms of frames
    } else if (m_hold > 0) {
        --m_hold;
    } else {
        m_open = false;
    }
    return m_open;
}

void AudioDsp::Gate::reset()
{
    m_open = false;
    m_hold = 0;
    m_lastMin = -70;
    m_blockMin = 0;
    m_blockFrames = 0;
    m_measured = false;
}

void AudioDsp::tone(qint16 *pcm, int n, double hz, double amplitude, qint64 *phase)
{
    for (int i = 0; i < n; ++i) {
        const double t = double((*phase)++) / kRate;
        pcm[i] = qint16(std::lround(std::sin(2 * kPi * hz * t) * amplitude * 32767.0));
    }
}

double AudioDsp::zeroCrossingHz(const qint16 *pcm, int n, int rate)
{
    int crossings = 0;
    for (int i = 1; i < n; ++i)
        if ((pcm[i - 1] < 0) != (pcm[i] < 0))
            ++crossings;
    return n > 1 ? crossings * double(rate) / (2.0 * (n - 1)) : 0;
}

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}
}

bool AudioDsp::selfTest()
{
    g_fail = 0;
    std::printf("audio helpers\n");
    // 48 kHz to 16 kHz and back: the length is a third, and a 440 Hz tone stays 440 Hz.
    std::vector<float> in(48000);
    for (int i = 0; i < 48000; ++i)
        in[size_t(i)] = float(std::sin(2 * kPi * 440.0 * i / 48000.0) * 0.5);
    Resampler down(48000, 16000);
    std::vector<float> whole;
    down.process(in.data(), int(in.size()), &whole);
    Resampler pieces(48000, 16000);
    std::vector<float> parts;
    for (size_t at = 0; at < in.size(); at += 441)
        pieces.process(in.data() + at, int(std::min<size_t>(441, in.size() - at)), &parts);
    check(std::abs(int(whole.size()) - 16000) <= 2, "48 kHz to 16 kHz: a third as many samples");
    bool same = whole.size() == parts.size();
    for (size_t i = 0; same && i < whole.size(); ++i)
        same = std::abs(whole[i] - parts[i]) < 1e-6f;
    check(same, "fed in pieces, the same output");
    std::vector<qint16> pcm(whole.size());
    for (size_t i = 0; i < whole.size(); ++i)
        pcm[i] = qint16(whole[i] * 32767);
    const double hz = zeroCrossingHz(pcm.data(), int(pcm.size()));
    check(hz > 430 && hz < 450, "the tone keeps its pitch");
    Resampler up(16000, 44100);
    std::vector<float> back;
    up.process(whole.data(), int(whole.size()), &back);
    check(std::abs(int(back.size()) - 44100) <= 3, "16 kHz to 44.1 kHz");

    qint16 quiet[kFrame] = {}, loud[kFrame];
    qint64 phase = 0;
    tone(loud, kFrame, 300, 0.2, &phase);
    check(levelDb(quiet, kFrame) <= -99 && levelDb(loud, kFrame) > -20 && levelDb(loud, kFrame) < -15, "frame levels");
    Speaking s;
    const bool one = s.frame(-20), two = s.frame(-20);
    int held = 0;
    while (s.frame(-80) && held < 100)
        ++held;
    check(!one && two && held == 14, "speaking after 2 loud frames, held 300 ms");

    // Voice activity, set by hand.
    Gate g;
    g.setAutomatic(false);
    check(g.threshold() == Gate::kDefaultDb, "voice activity: -40 dBFS unless changed");
    g.setThreshold(-90);
    const bool lowest = g.threshold() == Gate::kMinDb;
    g.setThreshold(0);
    check(lowest && g.threshold() == Gate::kMaxDb, "the threshold stays within -60 to -20 dBFS");
    g.setThreshold(Gate::kDefaultDb);
    bool leaked = false;
    for (int i = 0; i < 250; ++i)
        leaked = g.frame(-50) || leaked;
    check(!leaked, "game sound under the threshold never opens it");
    check(g.frame(-25), "a word opens it on its first frame");
    bool stays = true;
    for (int i = 0; i < 10; ++i)
        stays = g.frame(-44) && stays;
    check(stays, "a softer sound just under the threshold keeps it open");
    int hold = 0;
    while (g.frame(-80) && hold < 100)
        ++hold;
    check(hold == 20 && !g.open(), "held 400 ms after the voice stops, then closed");

    // Automatic (the default).
    Gate a;
    check(a.automatic() && a.threshold() == Gate::kDefaultDb && !a.frame(-45), "automatic: -40 dBFS until the first 2 s are heard");
    for (int i = 0; i < 100; ++i)
        a.frame(-75);
    check(a.threshold() == -45 && a.frame(-35), "automatic: a normal voice in a quiet room opens it");
    for (int i = 0; i < 30; ++i)
        a.frame(-75);
    int leakedLate = 0;
    for (int i = 0; i < 300; ++i)
        if (a.frame(-35) && i >= 200)
            ++leakedLate;
    check(leakedLate == 0 && a.threshold() >= -21, "automatic: steady game sound raises it and stops going out within 4 s");
    check(a.frame(-12), "automatic: talking over the game still opens it");
    a.reset();
    check(!a.open() && a.threshold() == Gate::kDefaultDb, "reset: -40 dBFS until it hears the room again");
    // A shared video's sound under the voice.
    qint16 voice[kFrame], video[kFrame], none[kFrame] = {};
    for (int i = 0; i < kFrame; ++i) {
        voice[i] = 4000;
        video[i] = 32000;
    }
    const bool talk = talking(voice, kFrame), silent = !talking(none, kFrame);
    mixUnder(voice, video, kFrame, true);
    mixUnder(none, video, kFrame, false);
    check(talk && silent && voice[0] == 4000 + 8000 && none[0] == 16000, "a shared video's sound: a quarter under a voice, half alone");
    qint16 top[1] = {30000}, big[1] = {32000};
    mixUnder(top, big, 1, false);
    check(top[0] == 32767, "a shared video's sound is clipped, never wrapped");
    std::printf("audio helpers: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
