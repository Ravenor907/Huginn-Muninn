#pragma once

#include <QtGlobal>
#include <vector>

// Small sound helpers for calls, free of Qt Multimedia so the self-test can run them anywhere.
namespace AudioDsp
{
const int kRate = 16000;            // what goes into Opus and comes out of it
const int kFrame = kRate / 50;      // 320 samples: 20 ms

// Linear-interpolation resampler for a mono float stream. Keeps its place between calls, so a
// stream fed in pieces of any size comes out the same as fed in one go.
class Resampler
{
public:
    Resampler(int inRate = kRate, int outRate = kRate) { setRates(inRate, outRate); }
    void setRates(int inRate, int outRate);
    bool passthrough() const { return m_in == m_out; }
    void process(const float *in, int n, std::vector<float> *out);
    void reset() { m_pos = 0; m_last = 0; m_primed = false; }

private:
    int m_in = kRate, m_out = kRate;
    double m_step = 1;     // input samples per output sample
    double m_pos = 0;      // where the next output sample falls, from m_last (0) to the first new sample (1)
    float m_last = 0;
    bool m_primed = false;
};

// The level of a frame in dBFS (full scale 0, silence -100).
double levelDb(const qint16 *pcm, int n);

// 5.5: speaking while the 20 ms level is above -45 dBFS for 2 frames in a row, held for 300 ms after.
class Speaking
{
public:
    // One 20 ms frame; true while speaking.
    bool frame(double levelDb);
    bool on() const { return m_on; }
    void reset() { m_loud = 0; m_hold = 0; m_on = false; }

private:
    int m_loud = 0;
    int m_hold = 0;     // frames left to hold
    bool m_on = false;
};

// Voice activity (Settings › Calls): a noise gate. The microphone sends while the 20 ms level is over the
// threshold, and silence goes out otherwise, so a game's sound from the speakers doesn't leak between words.
//  - Opens on the first frame over the threshold; stays open while the level is within 6 dB under it, and for
//    400 ms after it drops below that, so the ends of words aren't cut.
//  - Manual: the threshold picked (-60 to -20 dBFS, -40 unless changed).
//  - Automatic (the default): 15 dB over the steady sound around you (the quietest 20 ms in the last 2 to 4 s),
//    never under -45 or over -20 dBFS. A game's music or a fan raises it within 2 to 4 s.
class Gate
{
public:
    static constexpr double kMinDb = -60;
    static constexpr double kMaxDb = -20;
    static constexpr double kDefaultDb = -40;
    void setThreshold(double db) { m_manual = db < kMinDb ? kMinDb : db > kMaxDb ? kMaxDb : db; }
    void setAutomatic(bool on) { m_auto = on; }
    bool automatic() const { return m_auto; }
    // The threshold in use now (dBFS): the manual one, or the automatic one.
    double threshold() const;
    double floorDb() const { return m_blockMin < m_lastMin ? m_blockMin : m_lastMin; }
    // One 20 ms frame's level; true while the frame should be sent.
    bool frame(double levelDb);
    bool open() const { return m_open; }
    void reset();

private:
    double m_manual = kDefaultDb;
    bool m_auto = true;
    bool m_open = false;
    int m_hold = 0;            // frames left to hold open
    double m_lastMin = -70;    // the quietest frame of the last 2 s block (quiet, until one is heard)
    double m_blockMin = 0;     // and of the block so far
    int m_blockFrames = 0;
};

// A test tone, for the loopback self-test: amplitude 0..1 of full scale.
void tone(qint16 *pcm, int n, double hz, double amplitude, qint64 *phaseSamples);
// Rough frequency of a frame from its zero crossings.
double zeroCrossingHz(const qint16 *pcm, int n, int rate = kRate);

bool selfTest();
}
