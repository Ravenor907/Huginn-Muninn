#include "jitterbuffer.h"

#include <algorithm>
#include <cstdio>
#include <vector>

namespace
{
const qint64 kWindowMs = 3000;      // arrival statistics window
const size_t kMaxSamples = 200;
const size_t kMaxPackets = 100;     // 2 s of audio at most
const int kResyncSlots = 50;        // a second off: jump instead of creeping
const int kQuietAfter = 5;          // concealed frames in a row before going silent
const qint64 kShrinkEveryMs = 2000; // the delay goes down by at most one frame this often

qint64 floorDiv(qint64 a, qint64 b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
}

JitterBuffer::JitterBuffer(int minMs, int maxMs, int startMs) : m_min(minMs), m_max(maxMs), m_start(startMs), m_target(startMs) {}

void JitterBuffer::reset()
{
    m_packets.clear();
    m_transit.clear();
    m_started = m_playing = false;
    m_play = m_offset = 0;
    m_cooldown = m_quiet = m_late = 0;
    m_shrunk = 0;
    m_target = m_start;
}

void JitterBuffer::push(quint32 ts, const QByteArray &opus, qint64 nowMs)
{
    const qint64 slot = (qint64(ts) + kFrameMs / 2) / kFrameMs;
    const qint64 transit = nowMs - qint64(ts);
    m_transit.emplace_back(nowMs, transit);
    while (m_transit.size() > kMaxSamples || (!m_transit.empty() && nowMs - m_transit.front().first > kWindowMs))
        m_transit.pop_front();
    if (!m_started) {
        m_started = true;
        m_play = slot;
        m_offset = transit + m_start;
    }
    if (m_playing && slot < m_play) {
        // Came after its turn: hold one more frame from now on (until calmer times shrink it).
        ++m_stats.late;
        m_late = std::min(m_late + 1, 4);
        m_shrunk = nowMs;
        return;
    }
    if (m_packets.count(slot)) {
        ++m_stats.duplicate;
        return;
    }
    m_packets[slot] = opus;
    while (m_packets.size() > kMaxPackets)
        m_packets.erase(m_packets.begin());
}

void JitterBuffer::adapt(qint64 nowMs)
{
    while (!m_transit.empty() && nowMs - m_transit.front().first > kWindowMs)
        m_transit.pop_front();
    if (m_transit.size() < 10)
        return;
    std::vector<qint64> t;
    t.reserve(m_transit.size());
    for (const auto &s : m_transit)
        t.push_back(s.second);
    std::sort(t.begin(), t.end());
    const qint64 lo = t.front();
    const qint64 p95 = t[std::min(t.size() - 1, size_t(double(t.size() - 1) * 0.95 + 0.5))];
    // Room for the wander plus one frame, in whole frames.
    const qint64 spread = p95 - lo;
    const int want = std::clamp(int(((spread + kFrameMs - 1) / kFrameMs) * kFrameMs + kFrameMs) + m_late * kFrameMs, m_min, m_max);
    if (want > m_target) {
        m_target = want;          // rougher: at once
        m_shrunk = nowMs;
    } else if (want < m_target && nowMs - m_shrunk >= kShrinkEveryMs) {
        m_target -= kFrameMs;     // calmer: a frame at a time
        m_shrunk = nowMs;
        if (m_late > 0)
            --m_late;
    }
    m_offset = lo + m_target;
}

JitterBuffer::Frame JitterBuffer::pop(qint64 nowMs)
{
    Frame f;
    if (!m_started)
        return f;
    adapt(nowMs);
    if (m_cooldown > 0)
        --m_cooldown;
    const qint64 desired = floorDiv(nowMs - m_offset, kFrameMs);
    if (!m_playing) {
        if (desired < m_play)
            return f;
        m_playing = true;
    }
    qint64 diff = desired - m_play;
    if (diff > kResyncSlots || diff < -kResyncSlots) {
        // After a long pause or a jump in either clock: start over at the right place.
        m_play = desired;
        while (!m_packets.empty() && m_packets.begin()->first < m_play)
            m_packets.erase(m_packets.begin());
        diff = 0;
    }
    if (diff >= 2 && m_cooldown == 0) {
        // Behind: leave one frame out.
        m_packets.erase(m_play);
        ++m_play;
        ++m_stats.skipped;
        m_cooldown = 5;
    } else if (diff <= -2 && m_cooldown == 0) {
        // Ahead: play one made-up frame and stay on this slot.
        m_cooldown = 5;
        ++m_stats.stretched;
        f.kind = m_quiet >= kQuietAfter ? Nothing : Conceal;
        return f;
    }
    // Packets older than the play position can't be used.
    while (!m_packets.empty() && m_packets.begin()->first < m_play)
        m_packets.erase(m_packets.begin());

    auto it = m_packets.find(m_play);
    if (it != m_packets.end()) {
        f.kind = Packet;
        f.data = it->second;
        m_packets.erase(it);
        ++m_stats.played;
        m_quiet = 0;
    } else {
        auto next = m_packets.find(m_play + 1);
        if (next != m_packets.end()) {
            f.kind = Fec;
            f.data = next->second;
            ++m_stats.fec;
            m_quiet = 0;
        } else if (m_quiet < kQuietAfter) {
            f.kind = Conceal;
            ++m_stats.concealed;
            ++m_quiet;
        }
    }
    ++m_play;
    return f;
}

// ------------------------------------------------------------------ self-test

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}
quint32 g_rand = 12345;
int rnd(int n)
{
    g_rand = g_rand * 1103515245u + 12345u;
    return int((g_rand >> 16) % quint32(n));
}

struct Run {
    JitterBuffer::Stats stats;
    int maxTarget = 0, minTarget = 1000, maxBuffered = 0, gapsAfterPause = 0;
};

// frames: how many 20 ms frames the sender makes (after the last one, up to 5 concealed frames fade
// it out); jitter: extra random delay up to this many ms; lossPct: packets lost; drift: receiver
// clock speed (1.001 = 0.1 % fast); pause: frames [a, b) are DTX silence (not sent).
Run simulate(int frames, int jitter, int lossPct, double drift, int pauseFrom, int pauseTo)
{
    JitterBuffer jb;
    std::multimap<qint64, std::pair<quint32, QByteArray>> inflight;   // arrival -> (ts, data)
    Run r;
    for (int i = 0; i < frames; ++i) {
        if (i >= pauseFrom && i < pauseTo)
            continue;
        if (rnd(100) < lossPct)
            continue;
        const qint64 sentAt = qint64(i) * 20;
        inflight.emplace(sentAt + 30 + (jitter ? rnd(jitter + 1) : 0), std::make_pair(quint32(i * 20), QByteArray(1, char(i & 0x7F))));
    }
    const qint64 end = qint64(frames) * 20 + 500;
    // The receiver's sound card asks for a frame every 20 ms of its own clock.
    double clock = 0;
    qint64 nextPop = 0;
    for (qint64 t = 0; t < end; t += 1) {
        while (!inflight.empty() && inflight.begin()->first <= t) {
            jb.push(inflight.begin()->second.first, inflight.begin()->second.second, qint64(clock));
            inflight.erase(inflight.begin());
        }
        if (qint64(clock) >= nextPop) {
            nextPop += 20;
            jb.pop(qint64(clock));
            r.maxTarget = std::max(r.maxTarget, jb.targetMs());
            r.minTarget = std::min(r.minTarget, jb.targetMs());
            r.maxBuffered = std::max(r.maxBuffered, jb.bufferedMs());
        }
        clock += drift;
    }
    r.stats = jb.stats();
    return r;
}

// A phone on a busy radio: calm, then every 2 s a burst where packets bunch up for up to 120 ms.
Run simulateBursts(int frames)
{
    JitterBuffer jb;
    std::multimap<qint64, std::pair<quint32, QByteArray>> inflight;
    Run r;
    for (int i = 0; i < frames; ++i) {
        const qint64 sentAt = qint64(i) * 20;
        const int inBurst = int(sentAt % 2000);
        const qint64 extra = inBurst < 120 ? 120 - inBurst : rnd(8);
        inflight.emplace(sentAt + 30 + extra, std::make_pair(quint32(i * 20), QByteArray(1, char(i & 0x7F))));
    }
    const qint64 end = qint64(frames) * 20 + 500;
    for (qint64 t = 0; t < end; ++t) {
        while (!inflight.empty() && inflight.begin()->first <= t) {
            jb.push(inflight.begin()->second.first, inflight.begin()->second.second, t);
            inflight.erase(inflight.begin());
        }
        if (t % 20 == 0) {
            jb.pop(t);
            r.maxTarget = std::max(r.maxTarget, jb.targetMs());
            r.minTarget = std::min(r.minTarget, jb.targetMs());
        }
    }
    r.stats = jb.stats();
    return r;
}
}

bool JitterBuffer::selfTest()
{
    g_fail = 0;
    std::printf("jitter buffer\n");
    Run calm = simulate(500, 0, 0, 1.0, -1, -1);
    check(calm.stats.played == 500 && calm.stats.concealed <= 5 && calm.stats.late == 0 && calm.stats.skipped == 0 && calm.minTarget == 40,
          "steady network: every frame played, delay at the 40 ms floor");
    Run wobbly = simulate(1500, 60, 0, 1.0, -1, -1);
    check(wobbly.maxTarget >= 60 && wobbly.maxTarget <= 200 && wobbly.stats.late < 1500 / 40, "60 ms of jitter: the delay grows, few frames late");
    Run wild = simulate(1500, 400, 0, 1.0, -1, -1);
    check(wild.maxTarget == 200, "400 ms of jitter: the delay stops at 200 ms");
    Run burst = simulateBursts(1500);
    check(burst.stats.late < 1500 / 50 && burst.maxTarget <= 200, "bursts every 2 s (phone radios): few frames late, the delay holds between them");
    Run lossy = simulate(1500, 10, 10, 1.0, -1, -1);
    check(lossy.stats.fec > 50 && lossy.stats.concealed > 0 && lossy.stats.played > 1200, "10 % loss: rebuilt from FEC or concealed");
    Run fast = simulate(3000, 10, 0, 1.002, -1, -1);
    Run slow = simulate(3000, 10, 0, 0.998, -1, -1);
    check(fast.stats.stretched > 0 && fast.maxBuffered <= 200, "receiver clock 0.2 % fast: stretches, buffer stays small");
    check(slow.stats.skipped > 0 && slow.maxBuffered <= 200, "receiver clock 0.2 % slow: drops frames, buffer stays small");
    Run pause = simulate(1000, 10, 0, 1.0, 300, 600);
    check(pause.stats.played >= 680 && pause.stats.concealed < 20, "a 6 second DTX pause: silence, then back in step");
    std::printf("jitter buffer: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
