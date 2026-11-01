#pragma once

#include <QByteArray>
#include <deque>
#include <map>

// Plays a call's audio in order at a steady pace (shared/docs/protocol.md 5.5). Packets come in with
// the sender's ts (ms since its first packet, 20 ms per frame); pop() is called once per 20 ms by
// the sound card and says what to play next:
//  - Packet: the frame that belongs here.
//  - Fec: it was lost, but the next one is here: rebuild it from that packet's in-band FEC.
//  - Conceal: lost with nothing to rebuild it from: Opus packet loss concealment.
//  - Nothing: silence (before the first packet, or a long DTX pause).
// The delay adapts between minMs and maxMs from how much the arrival times wander (the 95th
// percentile over the last few seconds), starting at startMs. It grows at once when the network gets
// rougher (and by a frame for every packet that came too late to play), but shrinks only one frame
// every 2 s, so a single calm moment doesn't pull it back under the next burst. Being too far behind
// drops a frame, too far ahead plays one concealed frame, at most once every 100 ms, so it follows a
// sender whose clock runs a little fast or slow.
class JitterBuffer
{
public:
    enum Kind { Nothing, Packet, Fec, Conceal };
    struct Frame {
        Kind kind = Nothing;
        QByteArray data;
    };
    static const int kFrameMs = 20;

    explicit JitterBuffer(int minMs = 40, int maxMs = 200, int startMs = 60);
    void push(quint32 ts, const QByteArray &opus, qint64 nowMs);
    Frame pop(qint64 nowMs);
    void reset();

    int targetMs() const { return m_target; }
    int bufferedMs() const { return int(m_packets.size()) * kFrameMs; }
    struct Stats {
        int played = 0, fec = 0, concealed = 0, late = 0, skipped = 0, stretched = 0, duplicate = 0;
    };
    const Stats &stats() const { return m_stats; }

    // Simulated network: jitter, loss, a DTX pause and clock drift. Prints what it found.
    static bool selfTest();

private:
    void adapt(qint64 nowMs);

    int m_min, m_max, m_start, m_target;
    std::map<qint64, QByteArray> m_packets;      // slot (ts / 20) -> Opus packet
    std::deque<std::pair<qint64, qint64>> m_transit;   // (arrival ms, arrival - ts)
    bool m_started = false, m_playing = false;
    qint64 m_play = 0;           // the next slot to play
    qint64 m_offset = 0;         // local ms minus sender ts at which a frame is due
    int m_cooldown = 0;          // pops until the next drop or stretch is allowed
    int m_quiet = 0;             // concealed frames in a row
    int m_late = 0;              // frames of extra delay owed to packets that came too late
    qint64 m_shrunk = 0;         // when the delay last went down
    Stats m_stats;
};
