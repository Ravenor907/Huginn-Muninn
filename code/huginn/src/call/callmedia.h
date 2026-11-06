#pragma once

#include "audiodsp.h"
#include "callcrypto.h"
#include "jitterbuffer.h"
#include "opuscodec.h"
#include "stun.h"
#include "videowire.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <deque>
#include <functional>
#include <map>
#include <memory>

class AudioIO;
class QUdpSocket;
class TurnClient;

// The sound (and, between two c2 apps, the video) of one call, on every path at once
// (shared/docs/protocol.md 5.4 to 5.8):
//  - Relay: through our TURN allocation to the peer's relayed address.
//  - Wi-Fi: straight to the peer's own address on the network Chat already reaches them on.
//  - Bluetooth: the MEDIA characteristic, when the BLE link can carry it.
//  - Direct: only between two people who trust each other (5.8.2): straight to the peer's internet
//    address from the call socket, after both pinged each other's candidates (hole punching).
// A path is live while authentic packets arrive on it (4 s). Sound goes out on every live path with
// the same seq and the receiver's replay window keeps the first copy, except that the relay is
// left out while Wi-Fi or Direct carried something within the last 750 ms (5.8.3): free paths first,
// the relay as a standby that is pinged every second and takes over at once. Video never goes over
// Bluetooth. Microphone and speaker come from AudioIO; the self-test feeds and takes PCM instead.
class CallMedia : public QObject
{
    Q_OBJECT
public:
    enum Path { Relay = 0, Wifi = 1, Ble = 2, Direct = 3 };
    static const int kPaths = 4;

    // root and tag from the call's keys; offerer is true on the side that sent the offer.
    CallMedia(const QByteArray &root, const QByteArray &tag, bool offerer, QObject *parent = nullptr);
    ~CallMedia() override;

    // The UDP socket for the Wi-Fi and Direct paths (bound by the session before the keys exist, so
    // its port can go in the offer or answer). Takes it over; anything that waited on it is read now.
    void adoptLan(QUdpSocket *socket);
    QUdpSocket *lanSocket() const { return m_lan; }
    // A random port on all interfaces, for the Wi-Fi path; nullptr when none can be had.
    static QUdpSocket *bindLan(QObject *parent);
    void setLanPeer(const QHostAddress &host, quint16 port);   // port 0: learned from their first packet
    // peerRelayed: the relayed address the peer advertised. Some relays translate addresses between
    // their sites (Cloudflare anchors each allocation at the nearest data center), so its packets may
    // come from another port or address: the first authentic one from there makes that address a
    // second target (permitted and sent to as well). Media is end-to-end authenticated, so a source
    // can't be faked into this.
    void setRelay(TurnClient *turn, const Stun::Address &peerRelayed);
    void setBle(std::function<bool(const QByteArray &)> send) { m_bleSend = std::move(send); }
    // The Direct path: both sides trust each other. cands are the peer's srflx and host candidates
    // (more may follow); an authentic packet from any other internet address on the call socket is
    // the peer too (its NAT picked another port).
    void enableDirect(const QList<Stun::Address> &cands);
    void disableDirect();
    Stun::Address directAddr() const { return m_directAddr; }
    bool directEnabled() const { return m_direct; }
    // Datagrams on the call socket that aren't media (the STUN server's answer). True: used up.
    std::function<bool(const QByteArray &, const QHostAddress &, quint16)> onOther;
    // A datagram that reached the call socket before this media had it (the session read it while
    // it waited for a STUN answer).
    void lanDatagram(const QByteArray &bytes, const QHostAddress &from, quint16 port);
    // A media packet that came in over the relay or Bluetooth (Wi-Fi packets arrive by themselves).
    // relayFrom: the peer address the relay says it came from.
    void receive(const QByteArray &pkt, Path from, const Stun::Address &relayFrom = Stun::Address());
    // The relay addresses media goes to: the advertised one, then the one its packets come from.
    QList<Stun::Address> relayTargets() const;

    // Both apps speak c2 (5.8): receiver reports, Opus at 24 or 32 kbps (16 in a room, where every
    // peer gets its own copy), video. room: this pair's call is part of a voice room.
    void setC2(bool on, bool room);
    bool c2() const { return m_c2; }

    // devices false: no microphone or speaker (self-test); use feed() and played() instead.
    void start(bool devices = true);
    void stop();
    void setMuted(bool on) { m_muted = on; }
    // Voice activity can silence the microphone: only ever in a voice room, never in a 1:1 call.
    bool micGated() const { return !m_gateOff; }
    // Their volume here in percent (0 to 200, cap g2's menus; 0 while muted for me), asked for every 20 ms frame of
    // their decoded sound before it's played or mixed. Unset: 100.
    std::function<int()> gain;
    // A video shared into the call (CallShare): 20 ms of its sound at 16 kHz to send under the voice (shareOut), and to
    // play here through the call's own output so the echo canceller knows it (shareHere). False: none right now.
    std::function<bool(qint16 *pcm320)> shareOut, shareHere;
    bool muted() const { return m_muted; }
    AudioIO *audio() const { return m_audio; }

    bool live(Path p) const;
    bool anyLive() const { return live(Relay) || live(Wifi) || live(Ble) || live(Direct); }
    // A free path (Wi-Fi or Direct) answered a ping within the last 750 ms (so it works both ways,
    // not only towards us past a one-way firewall): the relay rests.
    bool freeFresh() const;
    // 1.45: a voice packet also goes on the relay next to Wi-Fi or Direct: only voice, only with a relay allocation
    // already there, never in Ultra privacy.
    static bool dualVoice(bool audio, bool relayReady, bool ultra) { return audio && relayReady && !ultra; }
    // Round 18 (relay.md "On demand"): the relay carried media (not pings or pongs) within the last ms milliseconds.
    bool relayUsedWithin(qint64 ms) const { return m_relayUsedAt >= 0 && now() - m_relayUsedAt < ms; }
    bool gotMedia() const { return m_gotMedia; }
    bool speaking() const { return m_speaking.on(); }
    int rttMs() const { return m_rtt; }
    int peerLossPct() const { return m_peerReport.lossPct; }

    // ---- video (5.8.5 to 5.8.7)
    // What this side says it sends (in every report, so the other side shows or hides the picture).
    void setVideoSending(int stream, bool on);
    bool videoSending(int stream) const { return m_sending[stream & 1]; }
    // One encoded VP8 frame: cut into packets and sent on the paths video may use. False when
    // video can't go now (no c2, no path, or too big).
    bool sendVideo(int stream, bool key, int rotation, const QByteArray &vp8);
    bool peerSending(int stream) const { return m_peerSending[stream & 1]; }
    // Cap c3 (5.8.7): the peer watches our screen share. While it doesn't, sendVideo() skips the screen
    // stream; when it does again, the frames go on from the next keyframe.
    bool peerWatching() const { return !m_peerReport.notWatching; }
    // Asks the peer for a keyframe now (the decoder lost its place, or the picture came back on screen).
    void requestKeyframe(int stream);
    // The size, frame rate and bitrate cap for a stream right now (480p over the relay), and the
    // bitrate the reports allow (kbps).
    VideoWire::Profile profile(int stream) const;
    int videoKbps(int stream) const { return m_rate[stream & 1].kbps(); }

    // Self-test: one microphone frame in, and pull one speaker frame out.
    void feed(const qint16 *pcm320) { onFrame(pcm320); }
    void playFrame(qint16 *pcm320) { onPull(pcm320); }
    int dropEvery = 0;           // self-test: leave out every Nth audio packet on purpose
    int dropVideoEvery = 0;      // self-test: leave out every Nth video packet (resends get through)
    struct Stats {
        int audioSent = 0, audioIn = 0, duplicates = 0, refused = 0, pings = 0, pongs = 0, decoded = 0;
        int relayAudio = 0;      // audio packets that went through the relay
        int reports = 0, videoSent = 0, videoIn = 0, resent = 0, keyRequests = 0, keyIgnored = 0, keyAsked = 0;
    };
    const Stats &stats() const { return m_stats; }
    const JitterBuffer &jitter() const { return m_jitter; }
    const OpusEncoder16 &encoder() const { return m_enc; }
    const VideoWire::Assembler &assembler(int stream) const { return m_asm[stream & 1]; }

Q_SIGNALS:
    void pathsChanged();
    void firstMedia();          // the first authentic packet from the other side
    void speakingChanged();
    // A whole VP8 frame from the peer, in order, ready for the decoder.
    void videoFrame(int stream, const QByteArray &vp8, bool key, int rotation);
    void keyframeWanted(int stream);     // the peer lost something: encode a keyframe next
    void peerVideoChanged();             // peerSending() changed
    void profileChanged();               // a free path came or went: another size is allowed

private:
    struct PathState {
        qint64 lastRx = -1000000;
        qint64 lastPing = -1000000;
        qint64 lastPong = -1000000;   // a pong proves the path works both ways
    };
    void onFrame(const qint16 *pcm);
    void onPull(qint16 *pcm);
    void onLan();
    void tick();
    void sendOn(Path p, const QByteArray &pkt);
    void sendMedia(const QByteArray &pkt, bool video);
    bool candidate(Path p) const;
    void handle(const QByteArray &pkt, Path from, const QHostAddress &host, quint16 port, const Stun::Address &relayFrom = Stun::Address());
    void sendPing(Path p);
    void sendReport();
    void onReport(const VideoWire::Report &r);
    void onVideo(const QByteArray &payload);
    void onNack(const QByteArray &payload);
    void pollVideo();
    void pickBitrate();
    void forgetOld();
    qint64 now() const { return m_clock.elapsed(); }

    CallCrypto::Keyring m_keys;
    CallCrypto::Sender m_tx;
    CallCrypto::Receiver m_rx;
    OpusEncoder16 m_enc;
    OpusDecoder16 m_dec;
    JitterBuffer m_jitter;
    AudioDsp::Speaking m_speaking;
    AudioIO *m_audio = nullptr;
    AudioDsp::Gate m_gate;     // voice activity for the microphone (voice rooms only, never a 1:1 call)
    bool m_gateOff = false;    // Voice activity: Always on
    QUdpSocket *m_lan = nullptr;
    QHostAddress m_lanHost;
    quint16 m_lanPeerPort = 0;
    QPointer<TurnClient> m_turn;
    Stun::Address m_peerRelayed, m_seenRelayed;
    std::function<bool(const QByteArray &)> m_bleSend;
    bool m_direct = false;
    QList<Stun::Address> m_directCands;
    Stun::Address m_directAddr;          // where the peer's direct packets come from
    PathState m_paths[kPaths];
    bool m_wasLive[kPaths] = {false, false, false, false};
    bool m_wasFree = false;
    QElapsedTimer m_clock;
    QTimer m_tick;
    qint64 m_started = 0, m_lastAudioSent = -100000;
    qint64 m_relayUsedAt = -1;   // media clock; -1: the relay never carried media
    quint32 m_frames = 0;
    bool m_muted = false, m_gotMedia = false, m_running = false;
    bool m_c2 = false, m_room = false;
    QByteArray m_pingPayload;
    qint64 m_pingSentAt = 0;
    int m_rtt = 0;
    Stats m_stats;
    bool m_twoPaths = false;   // Labs, Voice over two paths, read once per call
    // Receiver report: what came in since the last one.
    qint64 m_lastReport = 0;
    qint64 m_lastSlot = -1;
    int m_audioDue = 0, m_audioLost = 0, m_videoDue = 0, m_videoLost = 0;
    qint64 m_bytesIn = 0;
    VideoWire::Report m_peerReport;
    // Video.
    bool m_sending[2] = {false, false}, m_peerSending[2] = {false, false};
    quint16 m_videoFrame[2] = {0, 0};
    bool m_screenWaitKey = false;   // cap c3: screen frames resume at a keyframe
    std::map<quint16, QList<QByteArray>> m_sentFrames[2];   // the last frames' packets, for resends
    std::deque<quint16> m_sentOrder[2];
    VideoWire::Assembler m_asm[2];
    VideoWire::RateControl m_rate[2];
    int m_resentThisTick = 0;
    qint64 m_directSince = 0;
    qint64 m_keySentAt[2] = {-100000, -100000}, m_keyAskedAt[2] = {-100000, -100000};
    // The camera's step on the size ladder (VideoWire::rung), changed at most every 10 s.
    int m_rung = 0;
    qint64 m_rungAt = -100000;
};
