#pragma once

#include "callcrypto.h"
#include "stun.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <functional>

class CallMedia;
class CallSignal;
class QUdpSocket;
class StunProbe;
class TurnClient;
struct TurnLogin;

// One 1:1 call, from the first offer to the last hangup (shared/docs/protocol.md 5.2, 5.6 and 5.8).
// The offer, answer and cands carry:
//   cands: [{"t":"relay","a":"<TURN relayed IP>","p":<port>}]   when a relay allocation is up
//          [{"t":"lan","p":<UDP port>}]                          when Chat reaches them on Wi-Fi
//          [{"t":"srflx"|"host","a":"<IP>","p":<port>}]          only to someone this side trusts (5.8.2)
//   lp:    <the same UDP port>                                    likewise
// Both apps send the lan port both ways and accept either. "lan" has no address: each side already
// knows the other's Wi-Fi address from Chat, so no IP of the person is ever put in a message for
// anyone who isn't trusted. Between two c2 apps the offer and answer also carry "c2":true, "vc":"vp8"
// when video works, "vid" (camera on) and "p2p" (direct path wanted, 5.8.1).
// Timings: offer every 3 s for up to 45 s, answer every 2 s until the first media (15 s at most),
// hangup, decline and busy 3 times over 2 s.
class CallSession : public QObject
{
    Q_OBJECT
public:
    enum State { Calling, Ringing, Incoming, Connecting, Active, Reconnecting, Ended };
    Q_ENUM(State)

    // getLogin fetches a TURN login (nullptr: no relay, as in the self-test's loopback).
    using LoginFetcher = std::function<void(std::function<void(const TurnLogin &, const QString &)>)>;

    CallSession(CallSignal *signal, const QString &peerKey, LoginFetcher getLogin, QObject *parent = nullptr);
    ~CallSession() override;

    // Outgoing.
    void start();
    // Incoming: the offer (already checked by takeOffer).
    static QString checkOffer(const QJsonObject &offer, const QString &fromKey, qint64 nowMs);
    // The candidates of an offer, answer or cands message: the first "relay" with a literal IP, the
    // Wi-Fi port from "lp" or else the first "lan" candidate, and (5.8.2) up to 3 "srflx"/"host"
    // internet addresses. ok is false with more than 8.
    struct Cands {
        Stun::Address relay;
        quint16 lanPort = 0;
        QList<Stun::Address> direct;
        bool ok = true;
    };
    static Cands parseCands(const QJsonObject &msg);
    void takeOffer(const QJsonObject &offer);
    void answer();
    void decline();
    void hangup(const QString &why = QStringLiteral("hangup"));
    void setMuted(bool on);
    // The "Direct calls" switch changed during the call (5.8.1): off stops the direct path at once.
    void setTrusted(bool on);
    // Every room "call" message for this call id from the peer.
    void handle(const QJsonObject &msg);

    State state() const { return m_state; }
    QString callId() const { return m_callId; }
    QString peerKey() const { return m_peer; }
    bool outgoing() const { return m_offerer; }
    bool muted() const { return m_muted; }
    bool peerMuted() const { return m_peerMuted; }
    bool wasAnswered() const { return m_answered; }
    qint64 activeMs() const { return m_activeClock.isValid() ? m_activeClock.elapsed() : 0; }
    // What to tell the person when it ended: "Call ended", "No answer", "Declined", "Busy",
    // "Can't connect", "Call dropped", "Their app can't take this call".
    QString endText() const { return m_endText; }
    CallMedia *media() const { return m_media; }
    bool relayUp() const;
    bool viaTls() const;
    // When we are the offerer: the offer's sealed body is resent as built (same JSON every time).
    QJsonObject offerMessage() const { return m_offer; }
    // Both sides speak c2 (known once the offer, or the answer, has come).
    bool bothC2() const { return m_c2 && m_peerC2; }
    // Video can go both ways (both c2, both have VP8).
    bool videoBoth() const { return bothC2() && videoCodec && m_peerVp8; }
    // The caller turned its camera on for this call (an incoming video call).
    bool peerWantsVideo() const { return m_peerVid; }
    // Both trust each other: the Direct path is tried (5.8.2).
    bool directTried() const { return m_directOn; }

    bool useDevices = true;   // false: no microphone or speaker (self-test, voice rooms)
    QJsonObject offerExtra;   // added to the offer: a voice room's "g" and "ch" (section 6)
    std::function<QString(const QString &)> lanHostOverride;   // self-test: where the peer's "Wi-Fi" is
    // Set before start() or takeOffer():
    bool c2Local = true;      // this side speaks c2 (the self-test turns it off to act without c2)
    bool trusted = false;     // this side trusts the peer: direct internet path allowed (5.8.1)
    bool internet = true;     // false (Settings › Internet calls off): no relay, no STUN, no direct path
    bool videoCodec = false;  // this build has VP8 (Huginn on Windows; Muninn always)
    bool wantVideo = false;   // start with the camera on (a video call)
    QString stunHost;         // self-test: another STUN server ("host:port")

Q_SIGNALS:
    void stateChanged();
    void pathsChanged();
    void mutedChanged();
    void speakingChanged();
    void mediaStarted();      // media() exists now (video can be wired to it)
    void ended();

private:
    void setState(State s);
    void send(const QJsonObject &o);
    void sendThrice(const QJsonObject &o);
    QJsonArray cands() const;
    void addCands(QJsonObject &o) const;   // "cands" and "lp"
    void addC2(QJsonObject &o) const;      // "c2", "vc", "vid", "p2p"
    void readC2(const QJsonObject &msg);
    void applyCands(const QJsonObject &msg);
    void startRelay();
    void restartRelay();
    void relayReady();
    void startProbe();
    void onLanEarly();
    void sendOffer();
    void sendAnswer();
    bool startMedia();
    void finish(const QString &text);
    QString lanHost() const;
    QJsonObject signedPart(const QString &t);
    bool shareDirect() const;              // put this side's internet addresses in what it sends

    CallSignal *m_signal;
    QString m_peer, m_callId;
    LoginFetcher m_getLogin;
    bool m_offerer = false;
    State m_state = Calling;
    CallCrypto::Ephemeral m_eph;
    QByteArray m_theirEph;        // DER
    QJsonObject m_offer, m_answer;
    QPointer<TurnClient> m_turn;
    bool m_relayDone = false;     // allocated or given up
    int m_relayTries = 0, m_restarts = 0;
    QElapsedTimer m_restarted;
    QUdpSocket *m_lan = nullptr;  // until the media takes it
    QList<QByteArray> m_early;    // media datagrams that came before the media did
    QList<QPair<QHostAddress, quint16>> m_earlyFrom;
    StunProbe *m_probe = nullptr;
    CallMedia *m_media = nullptr;
    Stun::Address m_peerRelayed;
    quint16 m_peerLanPort = 0;
    QList<Stun::Address> m_peerDirect;
    QTimer m_resend, m_give, m_noPath, m_watch;
    qint64 m_lastCands = 0;
    bool m_candsQueued = false;
    int m_answerSends = 0;
    bool m_muted = false, m_peerMuted = false, m_answered = false, m_offerSent = false, m_answerSent = false;
    bool m_c2 = false, m_peerC2 = false, m_peerVp8 = false, m_peerVid = false, m_peerP2p = false, m_directOn = false;
    QElapsedTimer m_activeClock, m_downSince;
    QString m_endText;
};
