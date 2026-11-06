#pragma once

#include <QByteArray>
#include <QList>
#include <map>
#include <vector>

// Video and screen share between two c2 apps (shared/docs/protocol.md 5.8.5 to 5.8.7), the parts without
// sockets, codecs or cameras, so --call-selftest checks them (and Muninn's VideoWire.kt does the same):
//  - a VP8 frame cut into fragments, each the plaintext of one media packet of type 3 (video),
//    sealed with the call's per-direction keys like audio, so video is end-to-end encrypted too
//  - the receiver putting fragments back together, asking again for lost ones (NACK, type 6) and for
//    a fresh keyframe (type 5) when a frame can't be had
//  - the receiver report (type 4) both sides send every second
//  - the sender's bitrate, which follows the reports
namespace VideoWire
{
const int kHeader = 6;
const int kMaxFragment = 1200 - 28 - kHeader;    // 1166 bytes of VP8 per packet
const int kMaxFragments = 255;
enum Stream { Camera = 0, Screen = 1 };

struct Fragment {
    int stream = Camera;
    bool key = false;       // the frame is a keyframe
    int rotation = 0;       // degrees clockwise to turn it upright: 0, 90, 180, 270
    quint16 frame = 0;      // per stream, +1 for every encoded frame
    int index = 0, count = 1;
    QByteArray data;
};
QByteArray encode(const Fragment &f);
bool decode(const QByteArray &payload, Fragment *out);   // false: malformed
// One frame as fragment payloads, in order. Empty when it would need more than 255.
QList<QByteArray> split(int stream, quint16 frame, bool key, int rotation, const QByteArray &vp8);

// Receiver report, 8 bytes (type 4).
struct Report {
    int lossPct = 0;        // of the packets due since the last report
    bool camera = false;    // the sender of the report is sending camera video
    bool screen = false;    // ... is sharing its screen
    bool notWatching = false;   // bit 2: ... isn't watching our screen share (cap c3, 5.8.7)
    int jitterMs = 0;       // its jitter buffer's delay
    int kbps = 0;           // media it received since the last report, all paths, copies dropped
    int rttMs = 0;          // its round trip, 0 when unknown
};
QByteArray encodeReport(const Report &r);
bool decodeReport(const QByteArray &p, Report *out);

// Resend request (type 6): these fragments of one frame; no indexes means the whole frame.
struct Nack {
    int stream = Camera;
    quint16 frame = 0;
    QList<int> indexes;
    bool first = false;     // not on the wire: the first time this frame is asked for (loss counting)
};
QByteArray encodeNack(const Nack &n);
bool decodeNack(const QByteArray &p, Nack *out);

// Puts one stream's frames back together and hands them out in order, ready for the decoder.
// After a loss it can't repair, it waits for the next keyframe (VP8 frames build on the ones before).
class Assembler
{
public:
    struct Frame {
        QByteArray data;
        bool key = false;
        int rotation = 0;
        quint16 frame = 0;
    };
    void add(const Fragment &f, qint64 nowMs);
    bool next(Frame *out);
    // What to ask the sender for now; call every 20 to 50 ms.
    struct Ask {
        std::vector<Nack> nacks;
        bool keyframe = false;
    };
    Ask poll(qint64 nowMs, int rttMs);
    void reset();
    struct Stats {
        int frames = 0, keyframes = 0, nacked = 0, abandoned = 0, keyRequests = 0;
    };
    const Stats &stats() const { return m_stats; }
    int stream = Camera;

private:
    struct Pending {
        int count = 0;
        std::map<int, QByteArray> parts;
        bool key = false;
        int rotation = 0;
        qint64 seen = 0;        // first fragment, or when it was found missing
        qint64 last = 0;        // newest fragment
        qint64 nackAt = 0;
        int nacks = 0;
        bool complete() const { return count > 0 && int(parts.size()) == count; }
    };
    qint64 extend(quint16 f) const;
    void dropBefore(qint64 ext);

    std::map<qint64, Pending> m_frames;   // extended frame number -> what has come of it
    qint64 m_next = -1;          // the next frame to hand out
    qint64 m_high = -1;          // newest frame number seen
    bool m_needKey = true;       // waiting for a keyframe
    qint64 m_keyAsked = -1000000;
    Stats m_stats;
};

// The sender's video bitrate (kbps): starts at 60 % of the cap, backs off on loss, climbs slowly
// while the network is clean.
class RateControl
{
public:
    void setCap(int kbps);
    void onReport(int lossPct, qint64 nowMs);
    int kbps() const { return m_kbps; }
    int cap() const { return m_cap; }
    static const int kMin = 120;

private:
    int m_cap = 700, m_kbps = 420;
    qint64 m_raised = 0, m_lowered = 0;
};

// The bitrate and size limits of 5.8.6: over the relay 480p (about 0.4 GB an hour with the sound),
// on a free path (Wi-Fi or direct) up to 720p.
struct Profile {
    int width, height, fps, capKbps;
};
Profile cameraProfile(bool freePath);
// Screen share: Sharp (the default here) is full size at up to 10 frames a second, for text; Smooth is 30 a second
// at 720p (24 at 480p over the relay), for games and video, at the same bitrate cap.
Profile screenProfile(bool freePath, bool smooth = false);
// The camera's step on the ladder for the bitrate the path carries now: 0 as the profile says, 1 at most 480p24,
// 2 at most 360p20, 3 at most 240p15. Going down is at once; going back up needs a quarter more than the line
// (was: the step now), so it doesn't flap. Sound is never part of this: it goes first, always. (Muninn's VideoWire.rung.)
int rung(int kbps, int was);
// p held to ladder step r.
Profile onRung(const Profile &p, int r);

bool selfTest();
}
