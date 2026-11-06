#include "videowire.h"

#include <algorithm>
#include <cstdio>

namespace
{
const size_t kMaxPending = 120;         // frames held at most (a few seconds)
int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
void put16(QByteArray &b, int v)
{
    b.append(char((v >> 8) & 0xFF));
    b.append(char(v & 0xFF));
}
int get16(const QByteArray &b, int at) { return (int(quint8(b[at])) << 8) | int(quint8(b[at + 1])); }
}

// ------------------------------------------------------------------ fragments

QByteArray VideoWire::encode(const Fragment &f)
{
    QByteArray b;
    b.append(char(f.stream & 0xFF));
    b.append(char((f.key ? 1 : 0) | (((f.rotation / 90) & 3) << 1)));
    put16(b, f.frame);
    b.append(char(f.index & 0xFF));
    b.append(char(f.count & 0xFF));
    b.append(f.data);
    return b;
}

bool VideoWire::decode(const QByteArray &p, Fragment *out)
{
    if (p.size() < kHeader + 1 || p.size() > kHeader + kMaxFragment)
        return false;
    Fragment f;
    f.stream = quint8(p[0]);
    const int flags = quint8(p[1]);
    f.key = flags & 1;
    f.rotation = ((flags >> 1) & 3) * 90;
    f.frame = quint16(get16(p, 2));
    f.index = quint8(p[4]);
    f.count = quint8(p[5]);
    if ((f.stream != Camera && f.stream != Screen) || (flags & 0xF8) || f.count < 1 || f.index >= f.count)
        return false;
    f.data = p.mid(kHeader);
    *out = f;
    return true;
}

QList<QByteArray> VideoWire::split(int stream, quint16 frame, bool key, int rotation, const QByteArray &vp8)
{
    QList<QByteArray> out;
    const int n = int((vp8.size() + kMaxFragment - 1) / kMaxFragment);
    if (vp8.isEmpty() || n > kMaxFragments)
        return out;
    // Even pieces, so the last packet isn't a sliver.
    const int each = int((vp8.size() + n - 1) / n);
    for (int i = 0; i < n; ++i) {
        Fragment f;
        f.stream = stream;
        f.key = key;
        f.rotation = rotation;
        f.frame = frame;
        f.index = i;
        f.count = n;
        f.data = vp8.mid(i * each, each);
        out << encode(f);
    }
    return out;
}

// ------------------------------------------------------------------ report and nack

QByteArray VideoWire::encodeReport(const Report &r)
{
    QByteArray b;
    b.append(char(clampi(r.lossPct, 0, 100)));
    b.append(char((r.camera ? 1 : 0) | (r.screen ? 2 : 0) | (r.notWatching ? 4 : 0)));
    put16(b, clampi(r.jitterMs, 0, 65535));
    put16(b, clampi(r.kbps, 0, 65535));
    put16(b, clampi(r.rttMs, 0, 65535));
    return b;
}

bool VideoWire::decodeReport(const QByteArray &p, Report *out)
{
    if (p.size() < 8 || quint8(p[0]) > 100)
        return false;
    Report r;
    r.lossPct = quint8(p[0]);
    r.camera = quint8(p[1]) & 1;
    r.screen = quint8(p[1]) & 2;
    r.notWatching = quint8(p[1]) & 4;
    r.jitterMs = get16(p, 2);
    r.kbps = get16(p, 4);
    r.rttMs = get16(p, 6);
    *out = r;
    return true;
}

QByteArray VideoWire::encodeNack(const Nack &n)
{
    QByteArray b;
    b.append(char(n.stream & 0xFF));
    put16(b, n.frame);
    const int k = std::min<int>(int(n.indexes.size()), kMaxFragments);
    b.append(char(k));
    for (int i = 0; i < k; ++i)
        b.append(char(n.indexes.at(i) & 0xFF));
    return b;
}

bool VideoWire::decodeNack(const QByteArray &p, Nack *out)
{
    if (p.size() < 4)
        return false;
    const int k = quint8(p[3]);
    if (p.size() != 4 + k || (quint8(p[0]) != Camera && quint8(p[0]) != Screen))
        return false;
    Nack n;
    n.stream = quint8(p[0]);
    n.frame = quint16(get16(p, 1));
    for (int i = 0; i < k; ++i)
        n.indexes << int(quint8(p[4 + i]));
    *out = n;
    return true;
}

// ------------------------------------------------------------------ assembler

void VideoWire::Assembler::reset()
{
    m_frames.clear();
    m_next = m_high = -1;
    m_needKey = true;
    m_keyAsked = -1000000;
}

qint64 VideoWire::Assembler::extend(quint16 f) const
{
    if (m_high < 0)
        return f;
    const qint16 delta = qint16(quint16(f - quint16(m_high & 0xFFFF)));
    return m_high + delta;
}

void VideoWire::Assembler::dropBefore(qint64 ext)
{
    while (!m_frames.empty() && m_frames.begin()->first < ext)
        m_frames.erase(m_frames.begin());
}

void VideoWire::Assembler::add(const Fragment &f, qint64 now)
{
    const qint64 ext = extend(f.frame);
    if (m_next >= 0 && ext < m_next)
        return;   // already handed out or given up on
    if (m_high >= 0 && ext > m_high + 1000) {
        reset();   // a jump: start over at the next keyframe
        return add(f, now);
    }
    Pending &p = m_frames[ext];
    if (p.count == 0) {
        p.count = f.count;
        p.key = f.key;
        p.rotation = f.rotation;
        if (p.seen == 0)
            p.seen = now;
    }
    if (f.count != p.count || p.parts.count(f.index))
        return;   // a copy, or doesn't fit what came before
    p.parts[f.index] = f.data;
    p.last = now;
    if (ext > m_high)
        m_high = ext;
    while (m_frames.size() > kMaxPending) {
        m_frames.erase(m_frames.begin());
        m_needKey = true;
    }
}

bool VideoWire::Assembler::next(Frame *out)
{
    // A complete keyframe ahead of a hole: jump to it.
    auto pick = m_frames.end();
    if (m_needKey || m_next < 0 || !(m_frames.count(m_next) && m_frames[m_next].complete())) {
        for (auto it = m_frames.begin(); it != m_frames.end(); ++it)
            if (it->second.key && it->second.complete()) {
                pick = it;
                break;
            }
        if (pick == m_frames.end() && (m_needKey || m_next < 0))
            return false;
        if (pick == m_frames.end())
            return false;   // waiting for frame m_next
    } else {
        pick = m_frames.find(m_next);
    }
    const qint64 ext = pick->first;
    Pending p = pick->second;
    dropBefore(ext + 1);
    m_next = ext + 1;
    m_needKey = false;
    Frame f;
    f.key = p.key;
    f.rotation = p.rotation;
    f.frame = quint16(ext & 0xFFFF);
    for (const auto &kv : p.parts)
        f.data.append(kv.second);
    ++m_stats.frames;
    if (p.key)
        ++m_stats.keyframes;
    *out = f;
    return true;
}

VideoWire::Assembler::Ask VideoWire::Assembler::poll(qint64 now, int rtt)
{
    Ask ask;
    rtt = clampi(rtt, 20, 1000);
    const qint64 nackGap = std::max(40, rtt * 6 / 5);
    const qint64 giveUp = std::max(250, 2 * rtt + 120);
    if (!m_needKey && m_next >= 0 && m_high - m_next >= qint64(kMaxPending)) {
        // A gap too wide to fill (or to hold placeholders for): wait for a keyframe instead.
        ++m_stats.abandoned;
        m_needKey = true;
        dropBefore(m_high);
    }
    if (!m_needKey && m_next >= 0) {
        for (qint64 ext = m_next; ext <= m_high; ++ext) {
            auto it = m_frames.find(ext);
            if (it == m_frames.end()) {
                // Nothing at all came of this frame, but later ones did: ask for all of it.
                it = m_frames.emplace(ext, Pending()).first;
                it->second.seen = now;
            }
            Pending &p = it->second;
            if (p.complete())
                continue;
            const bool stalled = ext < m_high || now - p.last > 30;
            if (!stalled)
                continue;
            if (now - p.seen > giveUp) {
                // Can't be had in time: everything after it needs a keyframe.
                ++m_stats.abandoned;
                m_needKey = true;
                break;
            }
            if (p.nacks < 3 && now - p.nackAt >= nackGap) {
                Nack n;
                n.stream = stream;
                n.frame = quint16(ext & 0xFFFF);
                for (int i = 0; i < p.count; ++i)
                    if (!p.parts.count(i))
                        n.indexes << i;
                n.first = p.nacks == 0;
                p.nackAt = now;
                ++p.nacks;
                ++m_stats.nacked;
                ask.nacks.push_back(n);
                if (ask.nacks.size() >= 8)
                    break;
            }
        }
    }
    bool keyWaiting = false;
    for (const auto &kv : m_frames)
        keyWaiting = keyWaiting || kv.second.key;
    if (m_needKey && !keyWaiting && now - m_keyAsked >= std::max(300, rtt * 2)) {
        m_keyAsked = now;
        ++m_stats.keyRequests;
        ask.keyframe = true;
    }
    return ask;
}

// ------------------------------------------------------------------ rate

void VideoWire::RateControl::setCap(int kbps)
{
    const bool first = m_raised == 0 && m_lowered == 0 && m_kbps == 420;
    const int was = m_cap;
    m_cap = std::max(kMin, kbps);
    // A higher cap (the call moved from the relay to a free path): at least 60 % of it at once, as a new call starts.
    if (first || m_cap > was)
        m_kbps = std::max({kMin, m_kbps, m_cap * 6 / 10});
    m_kbps = std::min(m_kbps, m_cap);
}

void VideoWire::RateControl::onReport(int loss, qint64 now)
{
    if (loss >= 10) {
        m_kbps = std::max(kMin, m_kbps * 3 / 4);
        m_lowered = now;
    } else if (loss >= 4) {
        m_kbps = std::max(kMin, m_kbps * 92 / 100);
        m_lowered = now;
    } else if (loss < 2 && now - m_lowered >= 3000 && now - m_raised >= 1000) {
        m_kbps = std::min(m_cap, m_kbps * 108 / 100 + 10);
        m_raised = now;
    }
}

VideoWire::Profile VideoWire::cameraProfile(bool freePath)
{
    // 700 kbps of video plus about 40 of sound and packet overhead: about 0.35 to 0.4 GB an hour.
    return freePath ? Profile{1280, 720, 30, 2000} : Profile{640, 480, 24, 700};
}

VideoWire::Profile VideoWire::screenProfile(bool freePath, bool smooth)
{
    // Smooth: the camera's sizes and frame rates, which the encoder already keeps up with.
    if (smooth)
        return freePath ? Profile{1280, 720, 30, 2500} : Profile{854, 480, 24, 800};
    return freePath ? Profile{1920, 1080, 10, 2500} : Profile{1280, 720, 8, 800};
}

int VideoWire::rung(int kbps, int cap, int was)
{
    const int lines[3] = {cap * 45 / 100, cap * 25 / 100, cap * 12 / 100};   // under lines[i]: at least step i + 1
    int r = 0;
    for (int i = 0; i < 3; ++i)
        if (kbps < lines[i])
            r = i + 1;
    if (r < was) {   // up only with room to spare
        int up = was;
        while (up > r && kbps >= lines[up - 1] * 5 / 4)
            --up;
        r = up;
    }
    return r;
}

VideoWire::Profile VideoWire::onRung(const Profile &p, int r)
{
    switch (r) {
    case 0: return p;
    case 1: return Profile{std::min(p.width, 854), std::min(p.height, 480), std::min(p.fps, 24), p.capKbps};
    case 2: return Profile{std::min(p.width, 640), std::min(p.height, 360), std::min(p.fps, 20), p.capKbps};
    default: return Profile{std::min(p.width, 320), std::min(p.height, 240), std::min(p.fps, 15), p.capKbps};
    }
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
QByteArray frameBytes(int n, int seed)
{
    QByteArray b(n, 0);
    for (int i = 0; i < n; ++i)
        b[i] = char((i * 31 + seed * 7) & 0xFF);
    return b;
}
}

bool VideoWire::selfTest()
{
    g_fail = 0;
    std::printf("video wire\n");
    {
        Fragment f;
        f.stream = Screen;
        f.key = true;
        f.rotation = 270;
        f.frame = 0xBEEF;
        f.index = 2;
        f.count = 3;
        f.data = QByteArray("vp8", 3);
        const QByteArray e = encode(f);
        Fragment g;
        check(e.size() == kHeader + 3 && quint8(e[0]) == 1 && quint8(e[1]) == 7 && quint8(e[2]) == 0xBE && quint8(e[3]) == 0xEF && quint8(e[4]) == 2
                  && quint8(e[5]) == 3 && decode(e, &g) && g.stream == Screen && g.key && g.rotation == 270 && g.frame == 0xBEEF && g.index == 2 && g.count == 3
                  && g.data == f.data,
              "a fragment's 6-byte header round-trips");
        QByteArray bad = e;
        bad[4] = 3;
        check(!decode(bad, &g), "an index past the count is malformed");
        bad = e;
        bad[0] = 2;
        check(!decode(bad, &g), "an unknown stream is malformed");
        check(!decode(e.left(kHeader), &g), "a fragment with no data is malformed");
    }
    {
        const QByteArray big = frameBytes(5000, 1);
        const QList<QByteArray> parts = split(Camera, 7, true, 90, big);
        bool sizes = parts.size() == 5;
        for (const QByteArray &p : parts)
            sizes = sizes && p.size() <= kHeader + kMaxFragment && p.size() >= kHeader + 900;
        check(sizes, "5000 bytes go in 5 even packets, each under 1200 once sealed");
        check(split(Camera, 0, false, 0, frameBytes(kMaxFragment * 255 + 1, 0)).isEmpty(), "a frame over 255 packets is not sent");
        Assembler a;
        Assembler::Frame out;
        // Fragments in a shuffled order.
        for (int i : {3, 0, 4, 1, 2}) {
            Fragment f;
            decode(parts.at(i), &f);
            a.add(f, 0);
        }
        check(a.next(&out) && out.data == big && out.key && out.rotation == 90 && out.frame == 7, "fragments in any order make the frame again");
        check(!a.next(&out), "nothing more to hand out");
    }
    {
        // A stream of frames with one packet lost: a NACK, the resend, and the frame plays in order.
        Assembler a;
        Assembler::Frame out;
        std::vector<QList<QByteArray>> sent;
        for (int i = 0; i < 4; ++i)
            sent.push_back(split(Camera, quint16(65534 + i), i == 0, 0, frameBytes(2500, i)));   // frame numbers wrap
        qint64 t = 0;
        int got = 0;
        for (int i = 0; i < 4; ++i) {
            for (int k = 0; k < sent[i].size(); ++k) {
                if (i == 1 && k == 1)
                    continue;   // lost
                Fragment f;
                decode(sent[i].at(k), &f);
                a.add(f, t);
            }
            t += 40;
            while (a.next(&out))
                ++got;
        }
        check(got == 1, "frames after a hole wait for it");
        Assembler::Ask ask = a.poll(t, 50);
        check(ask.nacks.size() == 1 && ask.nacks[0].frame == 65535 && ask.nacks[0].indexes == QList<int>{1} && !ask.keyframe,
              "the hole is asked for again (NACK) with the frame number wrapped");
        Nack n;
        check(decodeNack(encodeNack(ask.nacks[0]), &n) && n.frame == 65535 && n.indexes == QList<int>{1}, "a NACK round-trips");
        check(a.poll(t + 10, 50).nacks.empty(), "not asked twice within a round trip");
        Fragment f;
        decode(sent[1].at(1), &f);
        a.add(f, t + 60);
        while (a.next(&out))
            ++got;
        check(got == 4 && out.frame == 1 && a.stats().abandoned == 0, "the resent packet fills it and every frame plays, in order");
    }
    {
        // A frame lost for good: give up on it, ask for a keyframe, and start again from it.
        Assembler a;
        Assembler::Frame out;
        qint64 t = 0;
        int got = 0;
        bool asked = false;
        for (int i = 0; i < 30; ++i) {
            const bool key = i == 0 || i == 20;
            const QList<QByteArray> parts = split(Camera, quint16(i), key, 0, frameBytes(1500, i));
            for (int k = 0; k < parts.size(); ++k) {
                if (i == 5)
                    continue;   // the whole frame is gone and never resent
                Fragment f;
                decode(parts.at(k), &f);
                a.add(f, t);
            }
            asked = asked || a.poll(t, 40).keyframe;
            while (a.next(&out))
                ++got;
            t += 40;
        }
        check(a.stats().abandoned == 1 && asked, "a frame that can't be had is given up and a keyframe asked for");
        check(got == 5 + 10 && out.frame == 29, "frames from the next keyframe on play again");
        Assembler fresh;
        check(fresh.poll(0, 40).keyframe, "a new receiver asks for a keyframe at once");
        // A jump of 500 frames: no placeholders for all of them, a keyframe asked for instead.
        Assembler gap;
        Assembler::Frame f0;
        for (int i : {0, 500}) {
            for (const QByteArray &pp : split(Camera, quint16(i), i == 0, 0, frameBytes(800, i))) {
                Fragment fr;
                decode(pp, &fr);
                gap.add(fr, 0);
            }
            gap.next(&f0);
        }
        Assembler::Ask ga = gap.poll(100, 40);
        check(ga.nacks.empty() && ga.keyframe && gap.stats().abandoned == 1, "a gap over 120 frames waits for a keyframe, no NACK storm");
    }
    {
        Report r;
        r.lossPct = 7;
        r.camera = true;
        r.jitterMs = 80;
        r.kbps = 812;
        r.rttMs = 45;
        Report g;
        const QByteArray e = encodeReport(r);
        check(e.size() == 8 && decodeReport(e, &g) && g.lossPct == 7 && g.camera && !g.screen && g.jitterMs == 80 && g.kbps == 812 && g.rttMs == 45,
              "a receiver report is 8 bytes and round-trips");
        Report w;
        w.screen = true;
        w.notWatching = true;
        Report gw;
        const QByteArray ew = encodeReport(w);
        check(quint8(ew[1]) == 6 && decodeReport(ew, &gw) && gw.screen && gw.notWatching && !gw.camera && !g.notWatching,
              "report flag bit 2 says not watching (cap c3)");
    }
    {
        RateControl rc;
        rc.setCap(700);
        check(rc.kbps() == 420, "video starts at 60 % of the cap");
        rc.onReport(12, 1000);
        check(rc.kbps() == 315, "12 % loss: a quarter less");
        qint64 t = 1000;
        for (int i = 0; i < 60; ++i)
            rc.onReport(0, t += 1000);
        check(rc.kbps() == 700, "a clean network climbs back to the cap and stops there");
        RateControl up;
        up.setCap(700);
        up.onReport(12, 1000);
        up.setCap(2000);
        check(up.kbps() == 1200 && rung(up.kbps(), up.cap(), 2) == 0, "a higher cap (a free path) starts at 60 % of it, on ladder step 0");
        up.setCap(700);
        check(up.kbps() == 700, "a lower cap holds it under");
        check(cameraProfile(false).height == 480 && cameraProfile(false).capKbps <= 700 && cameraProfile(true).height == 720,
              "480p over the relay, 720p on Wi-Fi or direct");
        check(screenProfile(true, true).fps == 30 && screenProfile(true, true).height == 720 && screenProfile(false, true).height == 480
                  && screenProfile(true, true).capKbps == screenProfile(true).capKbps && screenProfile(false, true).capKbps == screenProfile(false).capKbps,
              "screen share Smooth: 30 fps at 720p (480p over the relay), the same bitrate caps as Sharp");
    }
    {
        check(rung(1200, 2000, 0) == 0 && rung(800, 2000, 0) == 1 && rung(200, 2000, 0) == 3 && rung(420, 700, 0) == 0, "the camera's ladder steps down at once");
        // Back up only with a quarter more than the line.
        check(rung(260, 2000, 3) == 3 && rung(310, 2000, 3) == 2 && rung(1000, 2000, 1) == 1 && rung(1130, 2000, 1) == 0, "and back up only with room to spare");
        const Profile p = cameraProfile(true);
        const Profile p0 = onRung(p, 0), p1 = onRung(p, 1), p3 = onRung(p, 3);
        check(p0.width == p.width && p0.height == p.height && p0.fps == p.fps && p0.capKbps == p.capKbps && p1.height == 480 && p3.fps == 15,
              "ladder step 0 is the profile, 1 is 480p, 3 is 15 fps");
    }
    std::printf("video wire: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
