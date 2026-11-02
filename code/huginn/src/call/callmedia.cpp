#include "callmedia.h"
#include "../modwire.h"
#include "../busy.h"
#include "../settings.h"

#include "audioio.h"
#include "turnclient.h"

#include <QRandomGenerator>
#include <algorithm>
#include <QUdpSocket>

namespace
{
const qint64 kLiveMs = 4000;          // a path with nothing authentic for this long is dead
const qint64 kSilenceEveryMs = 400;   // DTX: one silent packet this often
const qint64 kEagerMs = 15000;        // pings to paths not live yet: fast at first
const qint64 kFreshMs = 750;          // a free path that answered a ping this recently rests the relay
const qint64 kReportEveryMs = 1000;   // receiver reports (c2)
const int kKeepFrames = 64;           // sent video frames kept for resends
const int kMaxResendPerTick = 120;
const qint64 kKeyGapMs = 300;         // keyframe requests at most this often; a keyframe this fresh answers one
}

CallMedia::CallMedia(const QByteArray &root, const QByteArray &tag, bool offerer, QObject *parent)
    : QObject(parent), m_keys(root), m_tx(&m_keys, offerer ? CallCrypto::O2A : CallCrypto::A2O, tag),
      m_rx(&m_keys, offerer ? CallCrypto::A2O : CallCrypto::O2A, tag)
{
    m_clock.start();
    m_tick.setInterval(25);
    m_tick.setTimerType(Qt::PreciseTimer);
    connect(&m_tick, &QTimer::timeout, this, &CallMedia::tick);
    m_asm[0].stream = VideoWire::Camera;
    m_asm[1].stream = VideoWire::Screen;
    m_rate[0].setCap(VideoWire::cameraProfile(false).capKbps);
    m_rate[1].setCap(VideoWire::screenProfile(false).capKbps);
}

CallMedia::~CallMedia()
{
    stop();
}

QUdpSocket *CallMedia::bindLan(QObject *parent)
{
    auto *s = new QUdpSocket(parent);
    Busy::nameSocket(s, "call media");
    if (!s->bind(QHostAddress::Any, 0)) {
        delete s;
        return nullptr;
    }
    return s;
}

void CallMedia::adoptLan(QUdpSocket *socket)
{
    if (!socket || m_lan)
        return;
    m_lan = socket;
    m_lan->setParent(this);
    connect(m_lan, &QUdpSocket::readyRead, this, &CallMedia::onLan);
    QTimer::singleShot(0, this, &CallMedia::onLan);
}

void CallMedia::setLanPeer(const QHostAddress &host, quint16 port)
{
    m_lanHost = host;
    if (port)
        m_lanPeerPort = port;
}

void CallMedia::setRelay(TurnClient *turn, const Stun::Address &peerRelayed)
{
    if (turn != m_turn)
        m_seenRelayed = Stun::Address();   // a new allocation: what the old one heard is gone
    m_turn = turn;
    if (peerRelayed == m_seenRelayed)
        m_seenRelayed = Stun::Address();
    m_peerRelayed = peerRelayed;
    if (turn && peerRelayed.valid())
        turn->addPeer(peerRelayed);
}

void CallMedia::enableDirect(const QList<Stun::Address> &cands)
{
    if (!m_direct) {
        m_paths[Direct].lastPing = -1000000;
        m_directSince = now();
    }
    m_direct = true;
    for (const Stun::Address &a : cands)
        if (a.valid() && !m_directCands.contains(a) && m_directCands.size() < 4)
            m_directCands << a;
}

void CallMedia::disableDirect()
{
    // "Direct calls" turned off mid-call: nothing more goes to their address, nothing from it counts.
    m_direct = false;
    m_directCands.clear();
    m_directAddr = Stun::Address();
    m_paths[Direct] = PathState();
    tick();
}

void CallMedia::setC2(bool on, bool room)
{
    m_c2 = on;
    m_room = room;
    pickBitrate();
}

void CallMedia::start(bool devices)
{
    if (m_running)
        return;
    m_running = true;
    m_started = now();
    m_lastReport = now();
    if (devices) {
        // Voice activity (Settings › Calls) for a call too: silence goes out between words, so game sound stays out.
        const QString gate = Settings::saved(QStringLiteral("voiceActivity"), QStringLiteral("auto")).toString();
        m_gateOff = gate == QLatin1String("off");
        m_gate.reset();
        m_gate.setAutomatic(gate != QLatin1String("manual") && !m_gateOff);
        m_gate.setThreshold(Settings::saved(QStringLiteral("voiceThreshold"), AudioDsp::Gate::kDefaultDb).toDouble());
        m_audio = new AudioIO(this);
        m_audio->pull = [this](qint16 *pcm) { onPull(pcm); };
        connect(m_audio, &AudioIO::captured, this, [this](const QByteArray &pcm) {
            if (pcm.size() != AudioDsp::kFrame * 2)
                return;
            static const qint16 silence[AudioDsp::kFrame] = {};
            const qint16 *frame = reinterpret_cast<const qint16 *>(pcm.constData());
            if (!m_gateOff && !m_gate.frame(AudioDsp::levelDb(frame, AudioDsp::kFrame)))
                frame = silence;
            onFrame(frame);
        });
        m_audio->start();
    }
    m_tick.start();
    tick();
}

void CallMedia::stop()
{
    m_running = false;
    m_tick.stop();
    if (m_audio) {
        m_audio->stop();
        m_audio->deleteLater();
        m_audio = nullptr;
    }
    if (m_lan) {
        m_lan->close();
        m_lan->deleteLater();
        m_lan = nullptr;
    }
    for (auto &m : m_sentFrames)
        m.clear();
}

bool CallMedia::live(Path p) const { return now() - m_paths[p].lastRx < kLiveMs; }

bool CallMedia::freeFresh() const
{
    const qint64 t = now();
    return t - m_paths[Wifi].lastPong < kFreshMs || t - m_paths[Direct].lastPong < kFreshMs;
}

bool CallMedia::candidate(Path p) const
{
    switch (p) {
    case Relay: return m_turn && m_turn->ready() && m_peerRelayed.valid();
    case Wifi: return m_lan && !m_lanHost.isNull() && m_lanPeerPort != 0;
    case Ble: return bool(m_bleSend);
    case Direct: return m_lan && m_direct && (!m_directCands.isEmpty() || m_directAddr.valid());
    }
    return false;
}

void CallMedia::sendOn(Path p, const QByteArray &pkt)
{
    if (pkt.isEmpty())
        return;
    switch (p) {
    case Relay:
        if (m_turn)
            for (const Stun::Address &a : relayTargets())
                m_turn->send(a, pkt);
        break;
    case Wifi:
        if (m_lan && m_lanPeerPort)
            m_lan->writeDatagram(pkt, m_lanHost, m_lanPeerPort);
        break;
    case Ble:
        if (m_bleSend)
            m_bleSend(pkt);
        break;
    case Direct:
        if (!m_lan)
            break;
        if (m_directAddr.valid() && live(Direct)) {
            m_lan->writeDatagram(pkt, CallNet::toHost(m_directAddr), m_directAddr.port);
        } else {
            // Still punching: every candidate gets it (the replay window drops the copies).
            for (const Stun::Address &a : std::as_const(m_directCands))
                m_lan->writeDatagram(pkt, CallNet::toHost(a), a.port);
            if (m_directAddr.valid() && !m_directCands.contains(m_directAddr))
                m_lan->writeDatagram(pkt, CallNet::toHost(m_directAddr), m_directAddr.port);
        }
        break;
    }
}

void CallMedia::sendMedia(const QByteArray &pkt, bool video)
{
    // Free paths first: while Wi-Fi or Direct carries the call, the relay only gets pings (5.8.3).
    const bool rest = freeFresh();
    for (int i = 0; i < kPaths; ++i) {
        const Path p = Path(i);
        if (!live(p) || (p == Relay && rest) || (p == Ble && video))
            continue;
        sendOn(p, pkt);
        if (p == Relay && !video)
            ++m_stats.relayAudio;
    }
}

void CallMedia::sendPing(Path p)
{
    // 8 random bytes; the pong carries them back. The last one sent times the round trip.
    QByteArray payload(8, 0);
    const quint64 r = QRandomGenerator::system()->generate64();
    for (int i = 0; i < 8; ++i)
        payload[i] = char(r >> (8 * i));
    m_pingPayload = payload;
    m_pingSentAt = now();
    sendOn(p, m_tx.seal(CallCrypto::Ping, quint32(now() - m_started), payload));
    m_paths[p].lastPing = now();
    ++m_stats.pings;
    forgetOld();
}

void CallMedia::tick()
{
    const qint64 t = now();
    m_resentThisTick = 0;
    for (int i = 0; i < kPaths; ++i) {
        const Path p = Path(i);
        if (!candidate(p))
            continue;
        // Live: Wi-Fi and Direct every 250 ms (so the relay takes over within a second if they
        // stop), the others every second. Not live yet: 50 ms for 2 s, then 250 ms to 15 s, then 2 s.
        // Direct candidates are paced from when the direct path was turned on, not from the start of the call.
        const qint64 age = t - (p == Direct ? m_directSince : m_started);
        const qint64 every = live(p) ? ((p == Wifi || p == Direct) ? 250 : 1000) : (age < 2000 ? 50 : age < kEagerMs ? 250 : 2000);
        if (t - m_paths[i].lastPing >= every)
            sendPing(p);
    }
    bool changed = false;
    for (int i = 0; i < kPaths; ++i) {
        const bool l = live(Path(i));
        if (l != m_wasLive[i]) {
            m_wasLive[i] = l;
            changed = true;
        }
    }
    // The video size follows a free path that answered pings within 3 s (steadier than the 750 ms rule).
    const bool free = t - m_paths[Wifi].lastPong < 3000 || t - m_paths[Direct].lastPong < 3000;
    if (free != m_wasFree) {
        m_wasFree = free;
        m_rate[0].setCap(profile(VideoWire::Camera).capKbps);
        m_rate[1].setCap(profile(VideoWire::Screen).capKbps);
        Q_EMIT profileChanged();
    }
    if (changed)
        Q_EMIT pathsChanged();
    pickBitrate();
    if (m_c2 && m_gotMedia && t - m_lastReport >= kReportEveryMs)
        sendReport();
    if (m_c2)
        pollVideo();
}

void CallMedia::pickBitrate()
{
    if (!m_c2 || m_room) {
        m_enc.setBitrate(16000);   // 5.5, and every peer of a room gets its own copy
        m_enc.setLossPercent(10);
        return;
    }
    // 5.8.4: 32 kbps on a free path, 24 through the relay; FEC sized to the loss they see.
    m_enc.setBitrate(freeFresh() ? 32000 : 24000);
    const int loss = m_peerReport.lossPct;
    m_enc.setLossPercent(std::clamp(loss + loss / 2 + 3, 5, 30));
}

void CallMedia::onFrame(const qint16 *pcm)
{
    if (!m_running)
        return;
    const quint32 ts = m_frames++ * quint32(JitterBuffer::kFrameMs);
    if (m_muted || !m_enc.ok())
        return;   // muted: no audio packets at all (pings go on)
    const QByteArray opus = m_enc.encode(pcm);
    if (opus.isEmpty())
        return;
    if (OpusEncoder16::isSilence(opus) && now() - m_lastAudioSent < kSilenceEveryMs)
        return;
    const QByteArray pkt = m_tx.seal(CallCrypto::Audio, ts, opus);
    m_lastAudioSent = now();
    ++m_stats.audioSent;
    if (dropEvery > 0 && m_stats.audioSent % dropEvery == 0)
        return;
    sendMedia(pkt, false);
    forgetOld();
}

void CallMedia::onPull(qint16 *pcm)
{
    const JitterBuffer::Frame f = m_jitter.pop(now());
    bool ok = false;
    switch (f.kind) {
    case JitterBuffer::Packet: ok = m_dec.decode(f.data, pcm); break;
    case JitterBuffer::Fec: ok = m_dec.decodeFec(f.data, pcm); break;
    case JitterBuffer::Conceal: ok = m_dec.conceal(pcm); break;
    case JitterBuffer::Nothing: std::fill(pcm, pcm + AudioDsp::kFrame, qint16(0)); break;
    }
    if (ok)
        ++m_stats.decoded;
    // The speaking ring follows what they said, whatever their volume here.
    const bool was = m_speaking.on();
    if (m_speaking.frame(AudioDsp::levelDb(pcm, AudioDsp::kFrame)) != was)
        Q_EMIT speakingChanged();
    if (gain)
        ModWire::applyGain(pcm, AudioDsp::kFrame, gain());
}

void CallMedia::onLan()
{
    Busy::Scope busy("call media");
    while (m_lan && m_lan->hasPendingDatagrams()) {
        QByteArray b(int(qMax<qint64>(0, m_lan->pendingDatagramSize())), 0);
        QHostAddress from;
        quint16 port = 0;
        const qint64 n = m_lan->readDatagram(b.data(), b.size(), &from, &port);
        if (n <= 0)
            continue;
        b.resize(int(n));
        lanDatagram(b, from, port);
    }
}

void CallMedia::lanDatagram(const QByteArray &b, const QHostAddress &from, quint16 port)
{
    if (b.isEmpty())
        return;
    if (quint8(b[0]) != CallCrypto::kVersion) {
        if (onOther)
            onOther(b, from, port);
        return;
    }
    // Wi-Fi: only from the address Chat knows them at (5.6: the source has to fit).
    if (!m_lanHost.isNull() && from.isEqual(m_lanHost, QHostAddress::TolerantConversion)) {
        handle(b, Wifi, from, port);
        return;
    }
    // Direct: only when both trust each other; any internet source, as authentic packets prove who it is.
    if (m_direct)
        handle(b, Direct, from, port);
}

void CallMedia::receive(const QByteArray &pkt, Path from, const Stun::Address &relayFrom) { handle(pkt, from, QHostAddress(), 0, relayFrom); }

QList<Stun::Address> CallMedia::relayTargets() const
{
    QList<Stun::Address> out;
    if (m_peerRelayed.valid())
        out << m_peerRelayed;
    if (m_seenRelayed.valid() && m_seenRelayed != m_peerRelayed)
        out << m_seenRelayed;
    return out;
}

void CallMedia::handle(const QByteArray &pkt, Path from, const QHostAddress &host, quint16 port, const Stun::Address &relayFrom)
{
    if (pkt.isEmpty() || quint8(pkt[0]) != CallCrypto::kVersion)
        return;
    CallCrypto::Receiver::Packet p;
    const QString why = m_rx.open(pkt, &p);
    if (!why.isEmpty()) {
        if (why == QLatin1String("duplicate"))
            ++m_stats.duplicates;
        else
            ++m_stats.refused;
        // A duplicate is still proof this path works, but only from where that path already is: a
        // copy replayed from anywhere else proves nothing and never moves the Direct path (5.8.2).
        if (why == QLatin1String("duplicate")) {
            if (from != Direct || (port && CallNet::fromHost(host, port) == m_directAddr))
                m_paths[from].lastRx = now();
        }
        return;
    }
    if (from == Wifi && port)
        m_lanPeerPort = port;   // a NAT or a firewall may have changed it: answer where it came from
    if (from == Direct && port)
        m_directAddr = CallNet::fromHost(host, port);   // where their NAT sends from: answer there
    if (from == Relay && relayFrom.valid() && relayFrom != m_peerRelayed && relayFrom != m_seenRelayed) {
        // Authentic, but relayed from an address they didn't advertise: send there too.
        m_seenRelayed = relayFrom;
        if (m_turn)
            m_turn->addPeer(relayFrom);
    }
    const bool wasLive = live(from);
    m_paths[from].lastRx = now();
    if (!wasLive)
        tick();
    if (!m_gotMedia) {
        m_gotMedia = true;
        Q_EMIT firstMedia();
    }
    m_bytesIn += pkt.size();
    switch (p.type) {
    case CallCrypto::Ping:
        sendOn(from, m_tx.seal(CallCrypto::Pong, quint32(now() - m_started), p.payload));
        break;
    case CallCrypto::Pong:
        ++m_stats.pongs;
        m_paths[from].lastPong = now();
        if (p.payload == m_pingPayload)
            m_rtt = int(now() - m_pingSentAt);
        break;
    case CallCrypto::Audio: {
        ++m_stats.audioIn;
        // Loss for the report: gaps of up to 10 frames are lost packets, longer ones a DTX pause.
        const qint64 slot = (qint64(p.ts) + 10) / 20;
        if (m_lastSlot >= 0) {
            const qint64 d = slot - m_lastSlot;
            if (d >= 1 && d <= 10) {
                m_audioDue += int(d);
                m_audioLost += int(d - 1);
            } else if (d > 10) {
                m_audioDue += 1;
            } else if (m_audioLost > 0) {
                --m_audioLost;   // one counted lost came late after all
            }
        }
        if (slot > m_lastSlot)
            m_lastSlot = slot;
        m_jitter.push(p.ts, p.payload, now());
        break;
    }
    case CallCrypto::Video:
        if (m_c2)
            onVideo(p.payload);
        break;
    case CallCrypto::Report: {
        VideoWire::Report r;
        if (m_c2 && VideoWire::decodeReport(p.payload, &r))
            onReport(r);
        break;
    }
    case CallCrypto::KeyRequest:
        if (m_c2 && p.payload.size() >= 1 && quint8(p.payload[0]) <= 1) {
            // A keyframe that just went out answers it already (requests cross on the way).
            const int st = quint8(p.payload[0]);
            ++m_stats.keyRequests;
            if (now() - m_keySentAt[st] >= kKeyGapMs)
                Q_EMIT keyframeWanted(st);
            else
                ++m_stats.keyIgnored;
        }
        break;
    case CallCrypto::Nack:
        if (m_c2)
            onNack(p.payload);
        break;
    }
    forgetOld();
}

// ------------------------------------------------------------------ reports (5.8.4)

void CallMedia::sendReport()
{
    const qint64 t = now();
    const qint64 span = std::max<qint64>(1, t - m_lastReport);
    m_lastReport = t;
    VideoWire::Report r;
    const int due = m_audioDue + m_videoDue, lost = m_audioLost + m_videoLost;
    r.lossPct = due > 0 ? std::clamp(lost * 100 / due, 0, 100) : 0;
    r.camera = m_sending[0];
    r.screen = m_sending[1];
    r.jitterMs = m_jitter.targetMs();
    r.kbps = int(m_bytesIn * 8 / span);
    r.rttMs = m_rtt;
    m_audioDue = m_audioLost = m_videoDue = m_videoLost = 0;
    m_bytesIn = 0;
    ++m_stats.reports;
    sendMedia(m_tx.seal(CallCrypto::Report, quint32(t - m_started), VideoWire::encodeReport(r)), false);
}

void CallMedia::onReport(const VideoWire::Report &r)
{
    const bool wasPaused = m_peerReport.notWatching;
    m_peerReport = r;
    if (wasPaused && !r.notWatching && m_sending[1])
        Q_EMIT keyframeWanted(VideoWire::Screen);   // c3: watching again, so a keyframe goes next
    if (r.camera != m_peerSending[0] || r.screen != m_peerSending[1]) {
        m_peerSending[0] = r.camera;
        m_peerSending[1] = r.screen;
        if (!r.camera)
            m_asm[0].reset();
        if (!r.screen)
            m_asm[1].reset();
        Q_EMIT peerVideoChanged();
    }
    for (int s = 0; s < 2; ++s)
        if (m_sending[s])
            m_rate[s].onReport(r.lossPct, now());
    pickBitrate();
}

// ------------------------------------------------------------------ video (5.8.5 to 5.8.7)

VideoWire::Profile CallMedia::profile(int stream) const
{
    const bool free = m_wasFree;
    return (stream & 1) == VideoWire::Screen ? VideoWire::screenProfile(free) : VideoWire::cameraProfile(free);
}

void CallMedia::setVideoSending(int stream, bool on)
{
    stream &= 1;
    if (m_sending[stream] == on)
        return;
    m_sending[stream] = on;
    if (!on) {
        m_sentFrames[stream].clear();
        m_sentOrder[stream].clear();
    }
    // Tell them now rather than at the next report.
    if (m_c2 && m_running && m_gotMedia)
        sendReport();
}

bool CallMedia::sendVideo(int stream, bool key, int rotation, const QByteArray &vp8)
{
    stream &= 1;
    if (!m_c2 || !m_running || !m_sending[stream])
        return false;
    if (stream == VideoWire::Screen) {
        // Cap c3 (5.8.7): nothing to a peer that isn't watching, then from a keyframe on.
        if (m_peerReport.notWatching) {
            m_screenWaitKey = true;
            return false;
        }
        if (m_screenWaitKey && !key)
            return false;
        m_screenWaitKey = false;
    }
    const quint16 n = m_videoFrame[stream]++;
    if (key)
        m_keySentAt[stream] = now();
    const QList<QByteArray> parts = VideoWire::split(stream, n, key, rotation, vp8);
    if (parts.isEmpty())
        return false;
    m_sentFrames[stream][n] = parts;
    m_sentOrder[stream].push_back(n);
    while (int(m_sentOrder[stream].size()) > kKeepFrames) {
        m_sentFrames[stream].erase(m_sentOrder[stream].front());
        m_sentOrder[stream].pop_front();
    }
    const quint32 ts = quint32(now() - m_started);
    for (const QByteArray &part : parts) {
        ++m_stats.videoSent;
        if (dropVideoEvery > 0 && m_stats.videoSent % dropVideoEvery == 0)
            continue;
        sendMedia(m_tx.seal(CallCrypto::Video, ts, part), true);
    }
    forgetOld();
    return true;
}

void CallMedia::requestKeyframe(int stream)
{
    stream &= 1;
    // At most one request per max(300 ms, 2 round trips): a keyframe needs that long to come back.
    const qint64 t = now();
    if (!m_c2 || !m_running || t - m_keyAskedAt[stream] < std::max<qint64>(kKeyGapMs, 2 * m_rtt))
        return;
    m_keyAskedAt[stream] = t;
    ++m_stats.keyAsked;
    sendMedia(m_tx.seal(CallCrypto::KeyRequest, quint32(now() - m_started), QByteArray(1, char(stream & 1))), false);
}

void CallMedia::onVideo(const QByteArray &payload)
{
    VideoWire::Fragment f;
    if (!VideoWire::decode(payload, &f))
        return;
    ++m_stats.videoIn;
    ++m_videoDue;
    if (!m_peerSending[f.stream]) {
        // Video before the report that says so: it's on.
        m_peerSending[f.stream] = true;
        Q_EMIT peerVideoChanged();
    }
    VideoWire::Assembler &a = m_asm[f.stream];
    a.add(f, now());
    VideoWire::Assembler::Frame out;
    while (a.next(&out))
        Q_EMIT videoFrame(f.stream, out.data, out.key, out.rotation);
}

void CallMedia::pollVideo()
{
    const qint64 t = now();
    for (int s = 0; s < 2; ++s) {
        if (!m_peerSending[s])
            continue;
        const VideoWire::Assembler::Ask ask = m_asm[s].poll(t, m_rtt);
        for (const VideoWire::Nack &n : ask.nacks) {
            if (n.first)
                m_videoLost += std::max<int>(1, int(n.indexes.size()));
            sendMedia(m_tx.seal(CallCrypto::Nack, quint32(t - m_started), VideoWire::encodeNack(n)), false);
        }
        if (ask.keyframe)
            requestKeyframe(s);
        VideoWire::Assembler::Frame out;
        while (m_asm[s].next(&out))
            Q_EMIT videoFrame(s, out.data, out.key, out.rotation);
    }
}

void CallMedia::onNack(const QByteArray &payload)
{
    VideoWire::Nack n;
    if (!VideoWire::decodeNack(payload, &n))
        return;
    const auto it = m_sentFrames[n.stream].find(n.frame);
    if (it == m_sentFrames[n.stream].end())
        return;   // too old: their next keyframe request covers it
    const QList<QByteArray> &parts = it->second;
    QList<int> which = n.indexes;
    if (which.isEmpty())
        for (int i = 0; i < parts.size(); ++i)
            which << i;
    const quint32 ts = quint32(now() - m_started);
    for (int i : std::as_const(which)) {
        if (i < 0 || i >= parts.size() || m_resentThisTick >= kMaxResendPerTick)
            continue;
        ++m_resentThisTick;
        ++m_stats.resent;
        sendMedia(m_tx.seal(CallCrypto::Video, ts, parts.at(i)), true);
    }
}

void CallMedia::forgetOld()
{
    // Epochs both directions are past are wiped (5.3).
    const quint32 oldest = qMin(m_tx.epoch(), m_rx.lowEpoch());
    if (oldest > m_keys.oldest())
        m_keys.forgetBefore(oldest);
}
