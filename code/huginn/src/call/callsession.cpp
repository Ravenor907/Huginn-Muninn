#include "callsession.h"
#include "../ultraprivacy.h"
#include "../busy.h"

#include "callmedia.h"
#include "callsignal.h"
#include "directpath.h"
#include "turnclient.h"

#include <QDateTime>
#include <QUdpSocket>

std::function<int(const QString &key, bool room)> CallSession::gainFor;

namespace
{
const char kCodec[] = "opus16";
const int kOfferWaitMs = 400;      // the offer waits this long at most for the relay and the STUN answer
const int kCandsGapMs = 1000;      // "cands" at most this often (5.2, 5.8.8)
// Round 18 (relay.md "On demand"): a login is asked for only when the call needs the relay, and renewed only while the
// relay carries the call, so the Worker's budgets count time spent relaying. As in Muninn's CallLogic.
const qint64 kRelayNeedMs = 2500;     // no free path fresh this long since the media began (or since one last was): ask
const qint64 kRelayInUseMs = 60000;   // at renewal time, renewed only when the relay carried media within this
const qint64 kRelayLapseMs = 30000;   // a login not renewed is let go this long before it ends
const qint64 kRelayAskGapMs = 10000;  // on-demand asks at most this often
qint64 wallMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

CallSession::CallSession(CallSignal *signal, const QString &peerKey, LoginFetcher getLogin, QObject *parent)
    : QObject(parent), m_signal(signal), m_peer(peerKey), m_getLogin(std::move(getLogin))
{
    m_give.setSingleShot(true);
    m_noPath.setSingleShot(true);
    connect(&m_noPath, &QTimer::timeout, this, [this] {
        if (m_media && m_media->anyLive())
            return;
        hangup(QStringLiteral("nopath"));
    });
    // Paths while the call runs: "Reconnecting" when every one is dead, a fresh relay allocation (and
    // STUN answer) after 4 s of that, "Call dropped" after 20 s.
    m_watch.setInterval(500);
    connect(&m_watch, &QTimer::timeout, this, [this] {
        if (!m_media)
            return;
        relayOnDemand();
        if (!m_media)
            return;
        if (m_media->anyLive()) {
            m_downSince.invalidate();
            if (m_state == Connecting || m_state == Reconnecting) {
                if (!m_activeClock.isValid())
                    m_activeClock.start();
                setState(Active);
            }
            return;
        }
        if (m_state == Active) {
            if (!m_downSince.isValid())
                m_downSince.start();
            else if (m_downSince.elapsed() > 1500)
                setState(Reconnecting);
        } else if (m_state == Reconnecting && m_downSince.isValid()) {
            if (m_downSince.elapsed() > 20000)
                hangup(QStringLiteral("dropped"));
            else if (m_downSince.elapsed() > 4000 && m_restarts < 3 && m_relayDone && !m_loginAsked && (!m_restarted.isValid() || m_restarted.elapsed() > 6000))
                restartRelay();   // the network changed under us (Wi-Fi to mobile data, say)
        }
    });
}

CallSession::~CallSession()
{
    if (m_media)
        m_media->stop();
    if (m_turn)
        m_turn->stop();
}

bool CallSession::relayUp() const { return m_turn && m_turn->ready(); }
bool CallSession::viaTls() const { return m_turn && m_turn->overTls(); }

void CallSession::setState(State s)
{
    if (s == m_state)
        return;
    m_state = s;
    Q_EMIT stateChanged();
}

QString CallSession::lanHost() const
{
    if (lanHostOverride)
        return lanHostOverride(m_peer);
    return m_signal ? m_signal->lanHost(m_peer) : QString();
}

void CallSession::send(const QJsonObject &o)
{
    if (m_signal)
        m_signal->send(m_peer, o);
}

void CallSession::sendThrice(const QJsonObject &o)
{
    // 3 times over 2 s, then never again. Bound to the signal, so it goes on after this call is gone.
    send(o);
    QPointer<CallSignal> sig = m_signal;
    const QString peer = m_peer;
    for (int ms : {1000, 2000})
        QTimer::singleShot(ms, m_signal, [sig, peer, o] {
            if (sig)
                sig->send(peer, o);
        });
}

bool CallSession::shareDirect() const
{
    // Only to someone this side trusts, and as the callee only when the caller asked for it (5.8.1).
    // Ultra privacy: never the direct path (no STUN, no address swapped); the call goes through the call relay, or the
    // same Wi-Fi.
    return c2Local && trusted && internet && (m_offerer || m_peerP2p) && UltraPrivacy::directAllowed();
}

QJsonArray CallSession::cands() const
{
    QJsonArray out;
    if (m_turn && m_turn->ready()) {
        const Stun::Address r = m_turn->relayed();
        out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("relay")}, {QStringLiteral("a"), CallNet::toHost(r).toString()},
                               {QStringLiteral("p"), int(r.port)}});
    }
    QUdpSocket *sock = m_lan ? m_lan : m_media ? m_media->lanSocket() : nullptr;
    const quint16 lan = sock ? sock->localPort() : 0;
    if (lan && !lanHost().isEmpty())
        out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("lan")}, {QStringLiteral("p"), int(lan)}});
    if (lan && shareDirect()) {
        // 5.8.2: what the STUN server saw, then this computer's global IPv6 addresses.
        if (m_probe && m_probe->mapped().valid()) {
            const Stun::Address m = m_probe->mapped();
            out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("srflx")}, {QStringLiteral("a"), CallNet::toHost(m).toString()},
                                   {QStringLiteral("p"), int(m.port)}});
        }
        for (const QHostAddress &a : DirectPath::globalIpv6())
            out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("host")}, {QStringLiteral("a"), a.toString()}, {QStringLiteral("p"), int(lan)}});
    }
    return out;
}

void CallSession::addCands(QJsonObject &o) const
{
    const QJsonArray c = cands();
    o.insert(QStringLiteral("cands"), c);
    o.remove(QStringLiteral("lp"));
    for (const QJsonValue &v : c)
        if (str(v.toObject(), "t") == QLatin1String("lan"))
            o.insert(QStringLiteral("lp"), v.toObject().value(QStringLiteral("p")).toInt());
}

void CallSession::addC2(QJsonObject &o) const
{
    if (!c2Local)
        return;
    o.insert(QStringLiteral("c2"), true);
    if (videoCodec)
        o.insert(QStringLiteral("vc"), QStringLiteral("vp8"));
    o.insert(QStringLiteral("vid"), wantVideo);
    if (shareDirect())
        o.insert(QStringLiteral("p2p"), true);
}

void CallSession::readC2(const QJsonObject &msg)
{
    m_peerC2 = msg.value(QStringLiteral("c2")).toBool();
    m_peerVp8 = m_peerC2 && str(msg, "vc") == QLatin1String("vp8");
    m_peerVid = m_peerC2 && msg.value(QStringLiteral("vid")).toBool();
    m_peerP2p = m_peerC2 && msg.value(QStringLiteral("p2p")).toBool();
}

CallSession::Cands CallSession::parseCands(const QJsonObject &msg)
{
    Cands out;
    const QJsonArray c = msg.value(QStringLiteral("cands")).toArray();
    if (c.size() > 8) {
        out.ok = false;
        return out;
    }
    auto portOf = [](const QJsonValue &v) {
        const double d = v.toDouble(-1);
        return d >= 1 && d <= 65535 && d == double(int(d)) ? quint16(d) : quint16(0);
    };
    for (const QJsonValue &v : c) {
        const QJsonObject o = v.toObject();
        const QString t = str(o, "t");
        const quint16 port = portOf(o.value(QStringLiteral("p")));
        if (!port)
            continue;
        if (t == QLatin1String("relay") && !out.relay.valid()) {
            // A relay address is always a literal IP (the TURN server hands it out).
            QHostAddress a;
            if (a.setAddress(str(o, "a")))
                out.relay = CallNet::fromHost(a, port);
        } else if (t == QLatin1String("lan") && !out.lanPort) {
            out.lanPort = port;
        } else if ((t == QLatin1String("srflx") || t == QLatin1String("host")) && out.direct.size() < 3) {
            // 5.8.2: an internet address of a trusted peer. Literal IPs only, never a local one.
            QHostAddress a;
            if (a.setAddress(str(o, "a")) && DirectPath::usable(a)) {
                const Stun::Address d = CallNet::fromHost(a, port);
                if (d.valid() && !out.direct.contains(d))
                    out.direct << d;
            }
        }
    }
    if (const quint16 lp = portOf(msg.value(QStringLiteral("lp"))))
        out.lanPort = lp;
    return out;
}

void CallSession::applyCands(const QJsonObject &msg)
{
    const Cands c = parseCands(msg);
    if (!c.ok)
        return;
    // Round 18: a new relay address from the peer once the media runs says it needs the relay; with no free path fresh
    // here either, this side asks for its own (the relay path needs an allocation on both sides).
    if (c.relay.valid() && !(c.relay == m_peerRelayed) && m_media && m_mediaAt > 0 && !m_media->freeFresh())
        m_relayWanted = true;
    if (c.relay.valid())
        m_peerRelayed = c.relay;
    if (c.lanPort)
        m_peerLanPort = c.lanPort;
    for (const Stun::Address &d : c.direct)
        if (!m_peerDirect.contains(d) && m_peerDirect.size() < 4)
            m_peerDirect << d;
    if (m_turn && m_peerRelayed.valid())
        m_turn->addPeer(m_peerRelayed);   // the permission is ready before the first packet needs it
    if (m_media) {
        if (m_peerRelayed.valid() && m_turn)
            m_media->setRelay(m_turn, m_peerRelayed);
        const QString host = lanHost();
        if (!host.isEmpty())
            m_media->setLanPeer(QHostAddress(host), m_peerLanPort);
        if (m_directOn)
            m_media->enableDirect(m_peerDirect);
    }
}

QJsonObject CallSession::signedPart(const QString &t)
{
    const QString eph = QString::fromLatin1(m_eph.publicDer.toBase64());
    const QByteArray sig = m_signal ? m_signal->sign(CallCrypto::signedBytes(t, m_callId, eph)) : QByteArray();
    return QJsonObject{{QStringLiteral("t"), t}, {QStringLiteral("call"), m_callId}, {QStringLiteral("eph"), eph},
                       {QStringLiteral("sig"), QString::fromLatin1(sig.toBase64())}};
}

// ------------------------------------------------------------------ relay and direct candidates

void CallSession::startRelay()
{
    ++m_relayTries;
    if (!m_getLogin || !internet) {
        m_relayDone = true;
        relayReady();
        return;
    }
    QPointer<CallSession> self(this);
    m_loginAsked = true;
    m_getLogin([self](const TurnLogin &login, const QString &why) {
        if (!self)
            return;
        self->m_loginAsked = false;
        if (self->m_state == Ended)
            return;
        if (!login.valid()) {
            self->m_relayWhy = why.isEmpty() ? QStringLiteral("The internet relay isn't available") : why;
            self->m_relayDone = true;
            self->relayReady();
            Q_EMIT self->pathsChanged();
            // One more try a little later (a captive network, a server that didn't answer in time).
            if (self->m_relayTries < 2)
                QTimer::singleShot(2000, self, [self] {
                    if (self && self->m_state != Ended && !(self->m_turn && self->m_turn->ready()))
                        self->startRelay();
                });
            return;
        }
        TurnClient *old = self->m_turn;
        auto *turn = new TurnClient(login, self);
        self->m_turn = turn;
        if (old) {
            old->stop();
            old->deleteLater();
        }
        if (self->m_leaving) {   // a renewal's handover underway: this allocation replaces both
            self->m_leaving->stop();
            self->m_leaving->deleteLater();
            self->m_leaving = nullptr;
        }
        const qint64 exp = login.expiresMs;
        connect(turn, &TurnClient::allocated, self, [self, turn, exp] {
            if (turn != self->m_turn)
                return;
            self->scheduleRenew(exp);
            self->m_relayDone = true;
            self->m_relayWhy.clear();
            if (self->m_peerRelayed.valid())
                turn->addPeer(self->m_peerRelayed);
            if (self->m_media && self->m_peerRelayed.valid())
                self->m_media->setRelay(turn, self->m_peerRelayed);
            self->relayReady();
            Q_EMIT self->pathsChanged();
        });
        connect(turn, &TurnClient::failed, self, [self, turn](const QString &why) {
            if (turn != self->m_turn)
                return;
            self->m_relayWhy = why == QLatin1String(TurnClient::kCantReach) ? why : QStringLiteral("Couldn't reach the internet relay");
            self->m_relayDone = true;
            self->relayReady();
            Q_EMIT self->pathsChanged();
            // One more try a little later (a busy network, a server that didn't answer in time).
            if (self->m_relayTries < 2)
                QTimer::singleShot(2000, self, [self] {
                    if (self && self->m_state != Ended && !(self->m_turn && self->m_turn->ready()))
                        self->startRelay();
                });
        });
        // Every packet the relay delivers goes to media, whatever its source: only peers we gave a
        // permission can reach our allocation, and media opens nothing that isn't authentic. A relay
        // between sites may translate the peer's address (CallMedia::setRelay).
        connect(turn, &TurnClient::data, self, [self](const Stun::Address &from, const QByteArray &bytes) {
            if (self->m_media)
                self->m_media->receive(bytes, CallMedia::Relay, from);
        });
        turn->start();
    });
}

// Renewal (relay.md "Renewal"): a login lasts 10 minutes, so a longer call gets a fresh one and a new allocation 2 minutes
// before it ends. Media keeps going out through the old allocation for kHandoverMs while the peer learns the new address
// in "cands" (a peer of any version follows a changed relay address); what arrives on either counts.
void CallSession::scheduleRenew(qint64 expiresMs)
{
    m_renewFor = expiresMs;
    m_renewTries = 0;
    const qint64 wait = expiresMs - RelayCredentials::kRenewBeforeMs - wallMs();
    if (wait <= 0)
        return;
    QPointer<CallSession> self(this);
    QTimer::singleShot(int(qMin<qint64>(wait, 24 * 3600000)), this, [self, expiresMs] {
        if (self && self->m_state != Ended && self->m_renewFor == expiresMs)
            self->renewIfUsed();
    });
}

// Round 18 (relay.md "On demand"): renewed only while the relay carries the call. On standby (Wi-Fi or Direct carry it)
// the login isn't renewed: renewed later if the relay takes over before it ends (relayOnDemand), else let go
// kRelayLapseMs before it ends; the call asks again if it needs the relay after that.
void CallSession::renewIfUsed()
{
    if (!m_media || m_media->relayUsedWithin(kRelayInUseMs)) {
        renewRelay();
        return;
    }
    m_renewDue = true;
    const qint64 forExp = m_renewFor;
    QPointer<CallSession> self(this);
    QTimer::singleShot(int(qBound(qint64(0), qint64(forExp - kRelayLapseMs - wallMs()), qint64(24) * 3600000)), this, [self, forExp] {
        if (self && self->m_state != Ended && self->m_renewFor == forExp && self->m_renewDue)
            self->lapseRelay();
    });
}

void CallSession::lapseRelay()
{
    m_renewDue = false;
    m_renewFor = 0;
    if (m_media)
        m_media->setRelay(nullptr, m_peerRelayed);
    for (TurnClient *t : {m_turn.data(), m_leaving.data()})
        if (t) {
            t->stop();
            t->deleteLater();
        }
    m_turn = nullptr;
    m_leaving = nullptr;
    Q_EMIT pathsChanged();
}

bool CallSession::relayAtStart() const
{
    // Round 18: at the start, the relay only when no free path could carry the call: not on the same Wi-Fi, no
    // Bluetooth, and no direct path this side would try. Otherwise it is asked for once the call needs it.
    return lanHost().isEmpty() && !(m_signal && m_signal->bleReady(m_peer)) && !shareDirect();
}

void CallSession::relayOnDemand()
{
    if (!m_media || !m_getLogin || !internet || m_state == Ended || m_mediaAt == 0)
        return;
    const qint64 t = wallMs();
    // A free path that works both ways, or Bluetooth carrying the call, needs no relay.
    if (m_media->freeFresh() || m_media->live(CallMedia::Ble)) {
        m_freeAt = t;
        m_relayWanted = false;
        return;
    }
    if (m_turn && m_turn->ready()) {
        // A renewal put off on standby: the relay carries the call now.
        if (m_renewDue && m_media->relayUsedWithin(5000)) {
            m_renewDue = false;
            renewRelay();
        }
        m_relayWanted = false;
        return;
    }
    if ((m_turn && !m_relayDone) ||   // being made (a failed one is asked for again below, kRelayAskGapMs apart)
        m_loginAsked || (!m_relayWanted && t - qMax(m_mediaAt, m_freeAt) < kRelayNeedMs) || t - m_relayAskedAt < kRelayAskGapMs)
        return;
    m_relayWanted = false;
    m_relayAskedAt = t;
    m_relayDone = false;
    m_relayTries = 0;   // startRelay counts this one, so a failure of it gets its own second try
    startRelay();
}

void CallSession::renewRelay()
{
    // No relay up (a restart is underway): that restart's allocation sets its own renewal.
    if (!m_getLogin || !internet || m_state == Ended || !m_turn || !m_turn->ready())
        return;
    const qint64 forExp = m_renewFor;
    QPointer<CallSession> self(this);
    m_getLogin([self, forExp](const TurnLogin &login, const QString &) {
        if (!self || self->m_state == Ended || self->m_renewFor != forExp)
            return;
        if (!login.valid() || login.expiresMs <= forExp) {
            // Refused or not reached: once more a little later. The allocation in use lasts until its login ends,
            // and after that the free paths, or a restart, carry the call.
            if (self->m_renewTries++ < 1)
                QTimer::singleShot(20000, self, [self, forExp] {
                    if (self && self->m_state != Ended && self->m_renewFor == forExp)
                        self->renewRelay();
                });
            return;
        }
        auto *next = new TurnClient(login, self);
        const qint64 exp = login.expiresMs;
        connect(next, &TurnClient::data, self, [self](const Stun::Address &from, const QByteArray &bytes) {
            if (self->m_media)
                self->m_media->receive(bytes, CallMedia::Relay, from);
        });
        connect(next, &TurnClient::failed, self, [self, next, forExp](const QString &why) {
            if (next == self->m_turn) {   // it had taken over: as for any relay that fails
                self->m_relayWhy = why == QLatin1String(TurnClient::kCantReach) ? why : QStringLiteral("Couldn't reach the internet relay");
                self->relayReady();
                Q_EMIT self->pathsChanged();
                return;
            }
            next->stop();
            next->deleteLater();
            if (self->m_renewFor == forExp && self->m_renewTries++ < 1)
                QTimer::singleShot(20000, self, [self, forExp] {
                    if (self && self->m_state != Ended && self->m_renewFor == forExp)
                        self->renewRelay();
                });
        });
        connect(next, &TurnClient::allocated, self, [self, next, exp, forExp] {
            if (self->m_state == Ended || self->m_renewFor != forExp || !self->m_turn) {
                next->stop();
                next->deleteLater();
                return;
            }
            QPointer<TurnClient> old = self->m_turn;
            self->m_leaving = old;
            self->m_turn = next;   // "cands" now carry the new address; media stays on the old one for now
            if (self->m_peerRelayed.valid())
                next->addPeer(self->m_peerRelayed);
            self->relayReady();
            Q_EMIT self->pathsChanged();
            self->scheduleRenew(exp);
            // Round 18: a renewal put off on standby may come late; the old allocation hands over before its login ends.
            const qint64 handover = qBound(qint64(5000), qint64(forExp - wallMs() - 15000), qint64(RelayCredentials::kHandoverMs));
            QTimer::singleShot(int(handover), self, [self, old, next] {
                if (!self)
                    return;
                if (self->m_turn == next && self->m_media && self->m_peerRelayed.valid())
                    self->m_media->setRelay(next, self->m_peerRelayed);
                if (old) {
                    old->stop();
                    old->deleteLater();
                }
                if (self->m_leaving == old)
                    self->m_leaving = nullptr;
            });
        });
        next->start();
    });
}

void CallSession::restartRelay()
{
    // A new allocation (and STUN answer) on whatever network there is now; the peer hears of it in
    // "cands" and sends there. The old one is let go once the new one is asked for.
    m_relayDone = false;
    ++m_restarts;
    m_restarted.start();
    m_relayTries = 0;   // a failure of the new one gets its own second try (startRelay counts this one)
    startRelay();
    if (shareDirect() && m_media && m_media->lanSocket()) {
        delete m_probe;
        m_probe = nullptr;
        startProbe();
    }
}

void CallSession::setTrusted(bool on)
{
    if (on == trusted)
        return;
    trusted = on;
    if (on)
        return;   // turning it on takes effect from the next call
    // Off mid-call: no more of this side's addresses go out, and the direct path stops at once.
    m_directOn = false;
    if (m_media)
        m_media->disableDirect();
    m_peerDirect.clear();
    if (m_offerSent || m_answerSent)
        relayReady();   // the next "cands" (and resends) carry no srflx or host
    Q_EMIT pathsChanged();
}

void CallSession::startProbe()
{
    if (m_probe || !shareDirect())
        return;
    QUdpSocket *sock = m_lan ? m_lan : m_media ? m_media->lanSocket() : nullptr;
    if (!sock)
        return;
    m_probe = new StunProbe(sock, this);
    connect(m_probe, &StunProbe::finished, this, [this] {
        if (m_probe && (m_probe->mapped().valid() || (m_offerer && !m_offerSent)))
            relayReady();
    });
    if (m_lan && !m_media)
        connect(m_lan, &QUdpSocket::readyRead, this, &CallSession::onLanEarly);
    if (m_media) {
        // The media hands the STUN server's answer to this probe (a new one after a restart).
        QPointer<StunProbe> probe = m_probe;
        m_media->onOther = [probe](const QByteArray &b, const QHostAddress &from, quint16 port) { return probe && probe->take(b, from, port); };
    }
    if (stunHost.isEmpty()) {
        m_probe->start();
        return;
    }
    const int colon = stunHost.lastIndexOf(QLatin1Char(':'));
    m_probe->start(stunHost.left(colon), quint16(stunHost.mid(colon + 1).toUInt()));
}

void CallSession::onLanEarly()
{
    Busy::Scope busy("call media");
    // The socket is ours until the media takes it: the STUN answer is used, media kept for later.
    while (m_lan && m_lan->hasPendingDatagrams()) {
        QByteArray b(int(qMax<qint64>(0, m_lan->pendingDatagramSize())), 0);
        QHostAddress from;
        quint16 port = 0;
        const qint64 n = m_lan->readDatagram(b.data(), b.size(), &from, &port);
        if (n <= 0)
            continue;
        b.resize(int(n));
        if (m_probe && m_probe->take(b, from, port))
            continue;
        if (m_early.size() < 64 && quint8(b[0]) == CallCrypto::kVersion) {
            m_early << b;
            m_earlyFrom << qMakePair(from, port);
        }
    }
}

void CallSession::relayReady()
{
    // The offer waits for the relay and the STUN answer (at most kOfferWaitMs); anything later
    // goes out as "cands".
    if (m_offerer && !m_offerSent && m_state == Calling) {
        const bool probing = shareDirect() && m_probe && !m_probe->done();
        if (m_relayDone && !probing)
            sendOffer();
        return;
    }
    if ((m_offerSent || m_answerSent) && m_state != Ended) {
        // At most one "cands" a second; a later one waits instead of being lost.
        const qint64 wait = kCandsGapMs - (wallMs() - m_lastCands);
        if (wait > 0) {
            if (!m_candsQueued) {
                m_candsQueued = true;
                QTimer::singleShot(int(wait), this, [this] { m_candsQueued = false; relayReady(); });
            }
            return;
        }
        m_lastCands = wallMs();
        QJsonObject o{{QStringLiteral("t"), QStringLiteral("cands")}, {QStringLiteral("call"), m_callId}};
        addCands(o);
        send(o);
        // The resent offer or answer says it too.
        if (m_offerer && !m_answered)
            addCands(m_offer);
        else if (!m_offerer && !m_answer.isEmpty())
            addCands(m_answer);
    }
}

// ------------------------------------------------------------------ outgoing

void CallSession::start()
{
    m_offerer = true;
    m_c2 = c2Local;
    m_callId = CallCrypto::newCallId();
    m_eph = CallCrypto::newEphemeral();
    m_lan = CallMedia::bindLan(this);
    setState(Calling);
    startProbe();
    // Round 18: the relay now only when nothing free could carry the call; otherwise on demand (relayOnDemand).
    const bool relayNow = relayAtStart();
    if (relayNow)
        startRelay();
    else
        m_relayDone = true;
    m_give.disconnect(this);
    connect(&m_give, &QTimer::timeout, this, [this] {
        if (!m_offerSent)
            sendOffer();
    });
    // Ring them at once when the relay is quick; otherwise the offer goes without it and the relay follows in "cands".
    m_give.start(kOfferWaitMs);
    if (!relayNow)
        relayReady();   // no relay to wait for: the offer goes as soon as the STUN answer (if any) is in
}

void CallSession::sendOffer()
{
    m_offerSent = true;
    m_give.stop();
    QJsonObject o = signedPart(QStringLiteral("offer"));
    o.insert(QStringLiteral("ts"), wallMs());   // an integer on the wire, like Muninn's
    addCands(o);
    o.insert(QStringLiteral("ble"), m_signal && m_signal->bleReady(m_peer));
    o.insert(QStringLiteral("codec"), QString::fromLatin1(kCodec));
    addC2(o);
    for (auto it = offerExtra.constBegin(); it != offerExtra.constEnd(); ++it)
        o.insert(it.key(), it.value());
    m_offer = o;
    send(o);
    // Every 3 s until they ring, answer, decline or are busy; no answer after 45 s.
    m_resend.disconnect(this);
    connect(&m_resend, &QTimer::timeout, this, [this] {
        if (m_state == Calling || m_state == Ringing)
            send(m_offer);
    });
    m_resend.start(3000);
    QTimer::singleShot(45000, this, [this] {
        if (m_state == Calling || m_state == Ringing)
            hangup(QStringLiteral("noanswer"));
    });
}

// ------------------------------------------------------------------ incoming

QString CallSkewGate::judge(const QString &call, qint64 ts, qint64 nowMs, qint64 elapsedMs)
{
    if (ts >= nowMs - 60000 && ts <= nowMs + 120000)
        return QStringLiteral("ring");
    if (ts < nowMs - kSkewMs || ts > nowMs + kSkewMs)
        return QStringLiteral("missed");
    for (auto it = m_first.begin(); it != m_first.end();) {
        if (elapsedMs - it.value() > 2 * kWaitMs)
            it = m_first.erase(it);
        else
            ++it;
    }
    const auto f = m_first.constFind(call);
    if (f == m_first.cend()) {
        m_first.insert(call, elapsedMs);
        return QStringLiteral("wait");
    }
    const qint64 gap = elapsedMs - f.value();
    if (gap > kWaitMs)
        return QStringLiteral("missed");
    return gap >= kLiveGapMs ? QStringLiteral("ring") : QStringLiteral("wait");
}

QString CallSession::checkOffer(const QJsonObject &o, const QString &fromKey, qint64 now)
{
    const QString callId = str(o, "call"), eph = str(o, "eph");
    if (CallCrypto::callIdBytes(callId).isEmpty() || eph.isEmpty())
        return QStringLiteral("malformed");
    const QByteArray sig = QByteArray::fromBase64(str(o, "sig").toLatin1());
    if (!CallCrypto::verifyDer(QByteArray::fromBase64(fromKey.toLatin1()), CallCrypto::signedBytes(QStringLiteral("offer"), callId, eph), sig))
        return QStringLiteral("signature");
    const qint64 ts = qint64(o.value(QStringLiteral("ts")).toDouble());
    if (ts < now - 60000 || ts > now + 120000)
        return QStringLiteral("stale");   // sat on a relay: a missed call, not a ringing one
    if (!parseCands(o).ok)
        return QStringLiteral("malformed");   // more than 8 candidates
    if (str(o, "codec") != QLatin1String(kCodec))
        return QStringLiteral("codec");
    return QString();
}

void CallSession::takeOffer(const QJsonObject &offer)
{
    m_offerer = false;
    m_c2 = c2Local;
    m_offer = offer;
    m_callId = str(offer, "call");
    m_theirEph = QByteArray::fromBase64(str(offer, "eph").toLatin1());
    readC2(offer);
    applyCands(offer);
    m_lan = CallMedia::bindLan(this);
    setState(Incoming);
    send(QJsonObject{{QStringLiteral("t"), QStringLiteral("ring")}, {QStringLiteral("call"), m_callId}});
    // Get the relay (and, for someone trusted, the STUN answer) ready while it rings, so answering
    // is quick. Nothing is sent to them until the person answers. Round 18: the relay only when nothing free
    // could carry the call; otherwise once the call needs it (relayOnDemand).
    startProbe();
    if (relayAtStart())
        startRelay();
    else
        m_relayDone = true;
    QTimer::singleShot(45000, this, [this] {
        if (m_state == Incoming)
            finish(QStringLiteral("Missed call"));
    });
}

void CallSession::answer()
{
    if (m_state != Incoming)
        return;
    m_answered = true;
    m_eph = CallCrypto::newEphemeral();
    m_directOn = m_c2 && m_peerC2 && trusted && internet && m_peerP2p;
    if (!startMedia()) {
        hangup(QStringLiteral("error"));
        return;
    }
    sendAnswer();
    setState(Connecting);
    m_noPath.start(15000);
    m_watch.start();
}

void CallSession::sendAnswer()
{
    m_answerSent = true;
    QJsonObject o = signedPart(QStringLiteral("answer"));
    addCands(o);
    o.insert(QStringLiteral("ble"), m_signal && m_signal->bleReady(m_peer));
    addC2(o);
    m_answer = o;
    send(o);
    // Every 2 s until the first media packet, for 15 s at most.
    m_answerSends = 1;
    m_resend.disconnect(this);
    connect(&m_resend, &QTimer::timeout, this, [this] {
        if (m_state == Ended || (m_media && m_media->gotMedia()) || ++m_answerSends > 8) {
            m_resend.stop();
            return;
        }
        addCands(m_answer);   // the relay may be up by now
        send(m_answer);
    });
    m_resend.start(2000);
}

void CallSession::decline()
{
    if (m_state != Incoming)
        return;
    sendThrice(QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), m_callId}});
    finish(QStringLiteral("Declined"));
}

// ------------------------------------------------------------------ both

bool CallSession::startMedia()
{
    const QString offerEph = m_offerer ? QString::fromLatin1(m_eph.publicDer.toBase64()) : str(m_offer, "eph");
    const QByteArray offerDer = QByteArray::fromBase64(offerEph.toLatin1());
    const QByteArray answerDer = m_offerer ? m_theirEph : m_eph.publicDer;
    const QByteArray id = CallCrypto::callIdBytes(m_callId);
    QByteArray ikm = CallCrypto::ecdh(m_eph.privateDer, m_theirEph);
    QByteArray root = CallCrypto::root(ikm, id, offerDer, answerDer);
    CallCrypto::wipe(ikm);
    if (root.isEmpty())
        return false;
    m_media = new CallMedia(root, CallCrypto::tag(id), m_offerer, this);
    CallCrypto::wipe(root);   // the media's keyring has its own copy
    m_mediaAt = wallMs();
    const bool room = offerExtra.contains(QStringLiteral("g")) || m_offer.contains(QStringLiteral("g"));
    m_media->setC2(bothC2(), room);
    if (m_lan)
        disconnect(m_lan, &QUdpSocket::readyRead, this, &CallSession::onLanEarly);
    m_media->adoptLan(m_lan);
    m_lan = nullptr;
    if (m_probe) {
        m_probe->setSocket(m_media->lanSocket());
        QPointer<StunProbe> probe = m_probe;
        m_media->onOther = [probe](const QByteArray &b, const QHostAddress &from, quint16 port) { return probe && probe->take(b, from, port); };
    }
    // The ephemeral private key is never kept past this point.
    CallCrypto::wipe(m_eph.privateDer);
    const QString host = lanHost();
    if (!host.isEmpty())
        m_media->setLanPeer(QHostAddress(host), m_peerLanPort);
    if (m_turn && m_peerRelayed.valid())
        m_media->setRelay(m_turn, m_peerRelayed);
    if (m_directOn)
        m_media->enableDirect(m_peerDirect);
    if (m_signal && m_signal->bleReady(m_peer)) {
        QPointer<CallSignal> sig = m_signal;
        const QString peer = m_peer;
        m_media->setBle([sig, peer](const QByteArray &pkt) { return sig && sig->sendMedia(peer, pkt); });
    }
    connect(m_media, &CallMedia::pathsChanged, this, &CallSession::pathsChanged);
    connect(m_media, &CallMedia::speakingChanged, this, &CallSession::speakingChanged);
    connect(m_media, &CallMedia::firstMedia, this, [this] { m_watch.start(); });
    m_media->setMuted(m_muted);
    if (gainFor) {
        const QString peer = m_peer;
        // Mute for me counts in a voice room only; a 1:1 call uses just the volume.
        const bool inRoom = offerExtra.contains(QStringLiteral("g")) || m_offer.contains(QStringLiteral("g"));
        m_media->gain = [peer, inRoom] { return gainFor ? gainFor(peer, inRoom) : 100; };
    }
    m_media->start(useDevices);
    // Media that reached the socket while it rang (their first pings).
    for (int i = 0; i < m_early.size(); ++i)
        m_media->lanDatagram(m_early.at(i), m_earlyFrom.at(i).first, m_earlyFrom.at(i).second);
    m_early.clear();
    m_earlyFrom.clear();
    Q_EMIT mediaStarted();
    return true;
}

void CallSession::handle(const QJsonObject &msg)
{
    const QString t = str(msg, "t");
    if (m_state == Ended)
        return;
    if (t == QLatin1String("offer")) {
        // A resent offer: ring again, or the same answer again; it may carry newer candidates.
        if (!m_offerer) {
            applyCands(msg);
            if (m_state == Incoming)
                send(QJsonObject{{QStringLiteral("t"), QStringLiteral("ring")}, {QStringLiteral("call"), m_callId}});
            else if (!m_answer.isEmpty())
                send(m_answer);
        }
        return;
    }
    if (t == QLatin1String("ring")) {
        if (m_offerer && m_state == Calling)
            setState(Ringing);
        return;
    }
    if (t == QLatin1String("answer")) {
        if (m_offerer && m_answered && QByteArray::fromBase64(str(msg, "eph").toLatin1()) == m_theirEph) {
            // A resent answer: same keys, maybe with the relay that wasn't up the first time.
            applyCands(msg);
            return;
        }
        if (!m_offerer || (m_state != Calling && m_state != Ringing))
            return;
        const QString eph = str(msg, "eph");
        const QByteArray sig = QByteArray::fromBase64(str(msg, "sig").toLatin1());
        if (!CallCrypto::verifyDer(QByteArray::fromBase64(m_peer.toLatin1()), CallCrypto::signedBytes(QStringLiteral("answer"), m_callId, eph), sig))
            return;   // refused: not signed by the person we called
        if (!parseCands(msg).ok)
            return;   // malformed: more than 8 candidates
        m_resend.stop();
        m_answered = true;
        m_theirEph = QByteArray::fromBase64(eph.toLatin1());
        readC2(msg);
        // Direct only when both trust each other: we asked (p2p in the offer), they answered with theirs.
        m_directOn = m_c2 && m_peerC2 && trusted && internet && m_peerP2p;
        applyCands(msg);
        if (!startMedia()) {
            hangup(QStringLiteral("error"));
            return;
        }
        setState(Connecting);
        m_noPath.start(15000);
        m_watch.start();
        return;
    }
    if (t == QLatin1String("cands")) {
        applyCands(msg);
        return;
    }
    if (t == QLatin1String("mute")) {
        m_peerMuted = msg.value(QStringLiteral("on")).toBool();
        Q_EMIT mutedChanged();
        return;
    }
    if (t == QLatin1String("decline")) {
        finish(QStringLiteral("Declined"));
        return;
    }
    if (t == QLatin1String("busy")) {
        finish(QStringLiteral("Busy"));
        return;
    }
    if (t == QLatin1String("hangup")) {
        const QString why = str(msg, "why");
        finish(why == QLatin1String("nopath") ? cantConnect()
               : why == QLatin1String("dropped") ? QStringLiteral("Call dropped")
               : why == QLatin1String("noanswer") && m_state == Incoming ? QStringLiteral("Missed call")
               : QStringLiteral("Call ended"));
    }
}

void CallSession::setMuted(bool on)
{
    if (on == m_muted)
        return;
    m_muted = on;
    if (m_media)
        m_media->setMuted(on);
    if (m_state != Ended && (m_answered || m_offerSent))
        send(QJsonObject{{QStringLiteral("t"), QStringLiteral("mute")}, {QStringLiteral("call"), m_callId}, {QStringLiteral("on"), on}});
    Q_EMIT mutedChanged();
}

void CallSession::hangup(const QString &why)
{
    if (m_state == Ended)
        return;
    if (m_offerSent || m_answerSent || m_state == Incoming)
        sendThrice(QJsonObject{{QStringLiteral("t"), QStringLiteral("hangup")}, {QStringLiteral("call"), m_callId}, {QStringLiteral("why"), why}});
    finish(why == QLatin1String("nopath") ? cantConnect()
           : why == QLatin1String("dropped") ? QStringLiteral("Call dropped")
           : why == QLatin1String("noanswer") ? QStringLiteral("No answer")
           : why == QLatin1String("error") ? QStringLiteral("The call couldn't start")
           : QStringLiteral("Call ended"));
}

QString CallSession::cantConnect() const
{
    // Like Muninn: the relay's reason, worded for people, when the relay never came up.
    if (!m_relayWhy.isEmpty() && !relayUp())
        return QStringLiteral("Can't connect: %1").arg(m_relayWhy);
    return QStringLiteral("Can't connect");
}

void CallSession::finish(const QString &text)
{
    if (m_state == Ended)
        return;
    m_endText = text;
    m_resend.stop();
    m_give.stop();
    m_noPath.stop();
    m_watch.stop();
    if (m_media)
        m_media->stop();
    if (m_turn)
        m_turn->stop();
    CallCrypto::wipe(m_eph.privateDer);
    setState(Ended);
    Q_EMIT ended();
}
