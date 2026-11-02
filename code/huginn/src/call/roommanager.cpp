#include "roommanager.h"

#include "audioio.h"
#include "../settings.h"
#include "callmanager.h"
#include "callmedia.h"
#include "callsession.h"
#include "callsignal.h"
#include "opuscodec.h"
#include "videoengine.h"
#include "vpxcodec.h"
#include "../groupwire.h"

#include <QVideoSink>
#include "../platform/chime.h"
#include "../platform/notify.h"

#include <QDateTime>
#include <QGuiApplication>
#include <QScreen>
#include <QWindowCapture>
#include <QJsonArray>
#include <QUrl>
#include <algorithm>

namespace
{
QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

qint64 RoomManager::now() { return QDateTime::currentMSecsSinceEpoch(); }

RoomManager::RoomManager(CallSignal *signal, CallManager *calls, LoginFetcher login, QObject *parent)
    : QObject(parent), m_signal(signal), m_calls(calls), m_login(std::move(login))
{
    connect(m_signal, &CallSignal::received, this, &RoomManager::onSignal);
    if (calls)
        connect(calls, &CallManager::trustChanged, this, [this](const QString &key, bool on) {
            const Peer p = m_peers.value(key);
            if (!on && p.s)
                p.s->setTrusted(false);
        });
    connect(m_signal, &CallSignal::mediaReceived, this, [this](const QString &from, const QByteArray &pkt) {
        const Peer p = m_peers.value(from);
        if (p.s && p.s->media())
            p.s->media()->receive(pkt, CallMedia::Ble);
    });
    // Everyone in a room says so again every 10 minutes (6.3 step 5), which also heals lost offers.
    m_announce.setInterval(int(RoomWire::kAnnounceMs));
    connect(&m_announce, &QTimer::timeout, this, [this] {
        if (inRoom() && (!groupState || groupState(m_g).isEmpty())) {
            m_endText = QStringLiteral("You're not in this group anymore");
            leave();
            return;
        }
        announce(false);
    });
    // Cap v2 (6.8): "still here" every 10 s while in a room, so someone whose app died leaves within a minute.
    m_beat.setInterval(int(RoomWire::kHeartbeatMs));
    connect(&m_beat, &QTimer::timeout, this, &RoomManager::heartbeat);
    m_ticker.setInterval(1000);
    connect(&m_ticker, &QTimer::timeout, this, &RoomManager::tick);
    // Presence ages out (25 minutes without news, or 20 s and 60 s for an app that sends heartbeats): look again often.
    m_presenceTick.setInterval(5000);
    connect(&m_presenceTick, &QTimer::timeout, this, [this] {
        ++m_serial;
        Q_EMIT presenceChanged();
    });
    m_presenceTick.start();
    // Voice activity (Settings › Calls): automatic unless changed.
    const QString gate = Settings::saved(QStringLiteral("voiceActivity"), QStringLiteral("auto")).toString();
    m_gateOff = gate == QLatin1String("off");
    m_gate.setAutomatic(gate != QLatin1String("manual") && !m_gateOff);
    m_gate.setThreshold(Settings::saved(QStringLiteral("voiceThreshold"), AudioDsp::Gate::kDefaultDb).toDouble());
    m_testEnd.setSingleShot(true);
    m_testEnd.setInterval(60000);
    connect(&m_testEnd, &QTimer::timeout, this, [this] { setMicTest(false); });
    m_clear.setSingleShot(true);
    m_clear.setInterval(3000);
    connect(&m_clear, &QTimer::timeout, this, [this] {
        m_endText.clear();
        Q_EMIT changed();
    });
    m_ringtone = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/raven_message.wav"), this);
    m_ringer.setInterval(3000);
    connect(&m_ringer, &QTimer::timeout, this, [this] {
        if (ringing() && now() - m_ringSince > RoomWire::kRingMs) {
            const QString text = QStringLiteral("Missed group call from %1").arg(ringName());
            const QString rg = m_ringG;
            stopRing();
            Q_EMIT finished(text);
            Q_EMIT logged(rg, text, true);
            return;
        }
        if (!Settings::hushed())
            m_ringtone->play();
    });
}

RoomManager::~RoomManager()
{
    setMicTest(false);
    leave();
}

// ------------------------------------------------------------------ voice activity and the mic test

QString RoomManager::gateMode() const
{
    return m_gateOff ? QStringLiteral("off") : m_gate.automatic() ? QStringLiteral("auto") : QStringLiteral("manual");
}

void RoomManager::setGateMode(const QString &mode)
{
    const QString m = mode == QLatin1String("manual") || mode == QLatin1String("off") ? mode : QStringLiteral("auto");
    if (m == gateMode())
        return;
    // Going to manual from automatic starts from where automatic was.
    if (m == QLatin1String("manual") && m_gate.automatic() && !m_gateOff)
        setGateThreshold(gateThreshold());
    m_gateOff = m == QLatin1String("off");
    m_gate.setAutomatic(m == QLatin1String("auto"));
    Settings::store().setValue(QStringLiteral("voiceActivity"), m);
    Q_EMIT gateChanged();
    Q_EMIT levelChanged();
}

void RoomManager::setGateThreshold(int db)
{
    m_gate.setThreshold(db);
    Settings::store().setValue(QStringLiteral("voiceThreshold"), qRound(m_gate.threshold()));
    if (m_gate.automatic()) {
        m_gate.setAutomatic(false);
        m_gateOff = false;
        Settings::store().setValue(QStringLiteral("voiceActivity"), QStringLiteral("manual"));
        Q_EMIT gateChanged();
    }
    Q_EMIT levelChanged();
}

// One microphone frame: its level for the meter, and whether it goes out (push to talk, or voice activity).
bool RoomManager::gateFrame(const qint16 *frame)
{
    const double level = AudioDsp::levelDb(frame, AudioDsp::kFrame);
    const bool gateOpen = m_gate.frame(level);
    const bool send = m_ptt ? m_talking : (m_gateOff || gateOpen);
    m_level = level;
    if (send != m_sendingNow || ++m_levelFrames >= 4) {
        m_levelFrames = 0;
        m_sendingNow = send;
        Q_EMIT levelChanged();
    }
    return send;
}

void RoomManager::setMicTest(bool on)
{
    on = on && !inRoom();
    if (on == (m_test != nullptr))
        return;
    if (on) {
        m_gate.reset();
        m_test = new AudioIO(this);
        m_test->pull = [](qint16 *pcm) { std::fill(pcm, pcm + AudioDsp::kFrame, qint16(0)); };
        connect(m_test, &AudioIO::captured, this, [this](const QByteArray &pcm) {
            if (pcm.size() == AudioDsp::kFrame * 2)
                gateFrame(reinterpret_cast<const qint16 *>(pcm.constData()));
        });
        if (!m_test->start()) {
            m_test->deleteLater();
            m_test = nullptr;
            Q_EMIT finished(QStringLiteral("The microphone isn't available"));
        } else {
            m_testEnd.start();
        }
    } else {
        m_testEnd.stop();
        m_test->stop();
        m_test->deleteLater();
        m_test = nullptr;
        m_level = -100;
        m_sendingNow = false;
    }
    Q_EMIT gateChanged();
    Q_EMIT levelChanged();
}

QString RoomManager::me() const { return m_signal->myKey(); }

bool RoomManager::beats(const QString &key) const { return m_signal->hasCap(key, QString::fromLatin1(RoomWire::kHeartbeatCap)); }

void RoomManager::heartbeat()
{
    if (!inRoom())
        return;
    // Cap g2: timed out or banned here while in the room: out of it.
    if (barred && barred(m_g, me())) {
        m_endText = joinBlock ? joinBlock(m_g) : QString();
        const QString text = m_endText;
        leave();
        if (!text.isEmpty())
            Q_EMIT finished(text);
        return;
    }
    for (const QString &k : members(m_g))
        if (beats(k))
            sendRoom(QStringLiteral("vjoin"), k, m_g, m_ch);
    // Someone with v2 who hasn't been heard from for 60 s and whose call isn't up is out of the room.
    const qint64 t = now();
    bool gone = false;
    for (const QString &k : m_peers.keys()) {
        const Peer p = m_peers.value(k);
        if (!beats(k) || (p.s && p.s->state() == CallSession::Active) || now() - m_joinedAt < RoomWire::kGoneMs)
            continue;
        if (m_presence.state(m_g, m_ch, k, t) == 0) {
            dropSession(k);
            gone = true;
        }
    }
    // Cap g2: someone timed out or banned while in the room leaves it here.
    for (const QString &k : m_peers.keys())
        if (barred && barred(m_g, k)) {
            dropSession(k);
            gone = true;
        }
    if (gone) {
        ++m_serial;
        Q_EMIT presenceChanged();
        Q_EMIT changed();
    }
}

QString RoomManager::nameOf(const QString &g, const QString &key) const
{
    QString n = memberName ? memberName(g, key) : QString();
    if (n.isEmpty())
        n = m_signal->nameOf(key);
    return n.isEmpty() ? QStringLiteral("Someone") : n;
}

QString RoomManager::title() const
{
    if (!inRoom())
        return QString();
    if (groupCall())
        return QStringLiteral("Group call");
    const QJsonObject st = groupState ? groupState(m_g) : QJsonObject();
    const QString n = str(GroupWire::channel(st, m_ch), "n");
    return n.isEmpty() ? QStringLiteral("Voice room") : n;
}

QString RoomManager::groupName() const
{
    const QJsonObject st = groupState && inRoom() ? groupState(m_g) : QJsonObject();
    return str(st, "name");
}

QString RoomManager::ringName() const { return ringing() ? nameOf(m_ringG, m_ringFrom) : QString(); }

QString RoomManager::ringGroup() const
{
    const QJsonObject st = groupState && ringing() ? groupState(m_ringG) : QJsonObject();
    return str(st, "name");
}

int RoomManager::peerCount() const
{
    int n = 0;
    for (const Peer &p : m_peers)
        if (p.s && p.s->state() != CallSession::Ended)
            ++n;
    return n;
}

QVariantList RoomManager::people() const
{
    QVariantList out;
    if (!inRoom())
        return out;
    out << QVariantMap{{QStringLiteral("key"), me()}, {QStringLiteral("name"), QStringLiteral("You")}, {QStringLiteral("me"), true},
                       {QStringLiteral("speaking"), !m_muted && m_mySpeaking.on()}, {QStringLiteral("muted"), m_muted},
                       {QStringLiteral("state"), QStringLiteral("live")}};
    QVariantList others;
    for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it) {
        const CallSession *s = it->s;
        if (!s || s->state() == CallSession::Ended)
            continue;
        const QString state = s->state() == CallSession::Active ? QStringLiteral("live")
            : s->state() == CallSession::Reconnecting ? QStringLiteral("reconnecting") : QStringLiteral("connecting");
        others << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), nameOf(m_g, it.key())}, {QStringLiteral("me"), false},
                              {QStringLiteral("speaking"), s->media() && s->media()->speaking()}, {QStringLiteral("muted"), s->peerMuted()},
                              {QStringLiteral("state"), state}};
    }
    // Cap v2 (6.8): still saying they're here, but their call dropped: Reconnecting until it's back or they're gone.
    const qint64 t = now();
    for (const QString &k : m_presence.in(m_g, m_ch, t)) {
        if (k == me() || (m_peers.value(k).s && m_peers.value(k).s->state() != CallSession::Ended) || !beats(k))
            continue;
        others << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(m_g, k)}, {QStringLiteral("me"), false},
                              {QStringLiteral("speaking"), false}, {QStringLiteral("muted"), false}, {QStringLiteral("state"), QStringLiteral("reconnecting")}};
    }
    std::sort(others.begin(), others.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("name")).toString().toLower() < b.toMap().value(QStringLiteral("name")).toString().toLower();
    });
    out << others;
    return out;
}

QString RoomManager::statusText() const
{
    if (!inRoom())
        return m_endText;
    int live = 0;
    for (const Peer &p : m_peers)
        if (p.s && (p.s->state() == CallSession::Active || p.s->state() == CallSession::Reconnecting))
            ++live;
    if (live == 0)
        return peerCount() > 0 ? QStringLiteral("Connecting…") : groupCall() ? QStringLiteral("Ringing…") : QStringLiteral("Waiting for others");
    return m_calls ? m_calls->duration(elapsed()) : QString();
}

QString RoomManager::outputName() const { return m_audio ? m_audio->outputName() : QStringLiteral("Default"); }

QStringList RoomManager::members(const QString &g) const
{
    QStringList out;
    const QJsonObject st = groupState ? groupState(g) : QJsonObject();
    const QString mine = me();
    for (const QString &k : GroupWire::keysOf(st))
        if (k != mine && (!canRoom || canRoom(k)))
            out << k;
    return out;
}

void RoomManager::sendRoom(const QString &t, const QString &key, const QString &g, const QString &ch)
{
    CallSignal *sig = m_signal;
    m_signal->send(key, RoomWire::make(t, g, ch, now(), [sig](const QByteArray &b) { return sig->sign(b); }));
}

void RoomManager::announce(bool onlyUnconnected)
{
    if (!inRoom())
        return;
    for (const QString &k : members(m_g)) {
        if (onlyUnconnected && m_peers.value(k).s)
            continue;
        sendRoom(QStringLiteral("vjoin"), k, m_g, m_ch);
    }
}

// ------------------------------------------------------------------ actions

QString RoomManager::join(const QString &g, const QString &ch)
{
    if (!opusAvailable())
        return QStringLiteral("Calls aren't available in this build.");
    if (m_calls && m_calls->inCall())
        return QStringLiteral("Hang up the call first.");
    if (!RoomWire::isRoomId(ch))
        return QStringLiteral("That isn't a voice room.");
    const QJsonObject st = groupState ? groupState(g) : QJsonObject();
    if (st.isEmpty() || !GroupWire::isMember(st, me()))
        return QStringLiteral("You're not in this group anymore.");
    if (joinBlock) {
        const QString why = joinBlock(g);
        if (!why.isEmpty())
            return why;   // timed out or banned (cap g2)
    }
    if (!RoomWire::isGroupCall(ch) && str(GroupWire::channel(st, ch), "t") != QLatin1String("voice"))
        return QStringLiteral("That isn't a voice room.");
    if (inRoom()) {
        if (m_g == g && m_ch == ch)
            return QString();
        leave();
    }
    if (ringing() && m_ringG == g && m_ringCh == ch)
        stopRing();
    ++m_gen;
    m_g = g;
    m_ch = ch;
    m_joinedAt = now();
    m_endText.clear();
    m_note.clear();
    m_clear.stop();
    m_rung.clear();
    m_declined.clear();
    m_mySpeaking.reset();
    setMicTest(false);
    m_gate.reset();
    // Muted or deafened in your card before joining: the room starts that way.
    m_deaf = Settings::savedDeafened();
    m_muted = Settings::savedMicMuted() || m_deaf;
    m_mutedByDeaf = m_deaf && Settings::savedMutedByDeaf();
    m_clock.start();
    m_audio = new AudioIO(this);
    m_audio->pull = [this](qint16 *pcm) { mix(pcm); };
    connect(m_audio, &AudioIO::captured, this, &RoomManager::onMic);
    connect(m_audio, &AudioIO::outputsChanged, this, &RoomManager::changed);
    // A microphone plugged in or pulled out while in the room: followed, and a toast says which. Joined with none,
    // the note about it goes once one is open.
    connect(m_audio, &AudioIO::deviceNotice, this, [this](const QString &text) {
        if (text.startsWith(QStringLiteral("Microphone: ")))
            m_note.clear();
        Q_EMIT finished(text);
        Q_EMIT changed();
    });
    if (!m_audio->start())
        m_note = QStringLiteral("The microphone isn't available");
    announce(false);
    // Lost on the way? Again to those who haven't called yet, at 4 s and 12 s (6.3 step 5).
    const int gen = m_gen;
    for (int ms : {4000, 12000})
        QTimer::singleShot(ms, this, [this, gen] {
            if (gen == m_gen)
                announce(true);
        });
    m_announce.start();
    m_beat.start();
    m_ticker.start();
    ++m_serial;
    Q_EMIT presenceChanged();
    Q_EMIT changed();
    Q_EMIT gateChanged();
    return QString();
}

void RoomManager::leave()
{
    if (!inRoom())
        return;
    const QString g = m_g, ch = m_ch;
    const bool wasCall = groupCall();
    const int seconds = elapsed();
    for (const QString &k : members(g))
        sendRoom(QStringLiteral("vleave"), k, g, ch);
    const QHash<QString, Peer> peers = m_peers;
    m_peers.clear();
    m_g.clear();
    m_ch.clear();
    ++m_gen;
    for (const Peer &p : peers)
        if (p.s) {
            p.s->disconnect(this);
            p.s->hangup();
            p.s->deleteLater();
        }
    m_announce.stop();
    m_beat.stop();
    m_offered.clear();
    m_ticker.stop();
    m_clock.invalidate();
    stopShare();
    if (m_view) {
        m_view->deleteLater();
        m_view = nullptr;
    }
    m_sharer.clear();
    if (m_screenSink)
        m_screenSink->setVideoFrame(QVideoFrame());
    if (m_audio) {
        m_audio->stop();
        m_audio->deleteLater();
        m_audio = nullptr;
    }
    if (m_endText.isEmpty())
        m_endText = QStringLiteral("You left");
    m_clear.start();
    m_level = -100;
    m_sendingNow = false;
    ++m_serial;
    Q_EMIT presenceChanged();
    Q_EMIT changed();
    Q_EMIT gateChanged();
    Q_EMIT levelChanged();
    // A group call (not a voice channel) goes into the group's timeline.
    if (wasCall)
        Q_EMIT logged(g, seconds > 0 ? QStringLiteral("Group call · %1:%2").arg(seconds / 60).arg(seconds % 60, 2, 10, QLatin1Char('0'))
                                     : QStringLiteral("Group call"), false);
}

void RoomManager::toggleMute()
{
    // Unmuting while deafened turns both off, like taking the headphones back off.
    if (m_muted && m_deaf) {
        m_deaf = false;
        m_mutedByDeaf = false;
    }
    m_muted = !m_muted;
    for (const Peer &p : std::as_const(m_peers))
        if (p.s)
            p.s->setMuted(m_muted);
    Q_EMIT changed();
}

void RoomManager::toggleDeafen()
{
    m_deaf = !m_deaf;
    if (m_deaf && !m_muted) {
        toggleMute();
        m_mutedByDeaf = true;
    } else if (!m_deaf && m_mutedByDeaf) {
        m_mutedByDeaf = false;
        if (m_muted)
            toggleMute();
    }
    Q_EMIT changed();
}

void RoomManager::setPushToTalk(bool on)
{
    if (on == m_ptt)
        return;
    m_ptt = on;
    m_talking = false;
    Q_EMIT changed();
}

void RoomManager::setTalking(bool on)
{
    if (on == m_talking)
        return;
    m_talking = on;
    Q_EMIT changed();
}

void RoomManager::nextOutput()
{
    if (!m_audio)
        return;
    m_audio->nextOutput();
    Q_EMIT changed();
}

QString RoomManager::callGroup(const QString &g, const QStringList &keys)
{
    const QString ch = RoomWire::newGroupCallId();
    const QString why = join(g, ch);
    if (!why.isEmpty())
        return why;
    const QStringList all = members(g);
    QStringList targets;
    for (const QString &k : keys.isEmpty() ? all : keys)
        if (all.contains(k) && !targets.contains(k))
            targets << k;
    if (targets.isEmpty()) {
        leave();
        m_endText.clear();
        return QStringLiteral("Nobody in this group can join calls yet.");
    }
    for (const QString &k : targets) {
        m_rung.insert(k);
        sendRoom(QStringLiteral("vring"), k, g, ch);
    }
    const int gen = m_gen;
    // Once more at 15 s to those who haven't joined or said no (6.5 step 1).
    QTimer::singleShot(15000, this, [this, gen, g, ch] {
        if (gen != m_gen)
            return;
        for (const QString &k : std::as_const(m_rung))
            if (!m_peers.value(k).s && !m_declined.contains(k))
                sendRoom(QStringLiteral("vring"), k, g, ch);
    });
    // Nobody came within 45 s: give up (6.5 step 4).
    QTimer::singleShot(int(RoomWire::kRingMs), this, [this, gen] {
        if (gen != m_gen || peerCount() > 0)
            return;
        m_endText = QStringLiteral("No one joined");
        leave();
        Q_EMIT finished(m_endText);
    });
    return QString();
}

QString RoomManager::answerRing()
{
    if (!ringing())
        return QString();
    const QString g = m_ringG, ch = m_ringCh;
    stopRing();
    return join(g, ch);
}

void RoomManager::declineRing()
{
    if (!ringing())
        return;
    sendRoom(QStringLiteral("vdecline"), m_ringFrom, m_ringG, m_ringCh);
    stopRing();
}

void RoomManager::startRing(const QString &from, const QString &g, const QString &ch)
{
    m_ringG = g;
    m_ringCh = ch;
    m_ringFrom = from;
    m_ringSince = now();
    if (!Settings::hushed())
        m_ringtone->play();
    m_ringer.start();
    const QString who = ringName(), grp = ringGroup();
    if (QGuiApplication::applicationState() != Qt::ApplicationActive)
        Platform::notify(QStringLiteral("Group call"), m_signal->showPreview() ? QStringLiteral("%1 is calling %2").arg(who, grp) : QStringLiteral("Someone is calling"),
                         int(RoomWire::kRingMs));
    Q_EMIT incoming(who, grp);
    Q_EMIT changed();
}

void RoomManager::stopRing()
{
    m_ringer.stop();
    m_ringtone->stop();
    m_ringG.clear();
    m_ringCh.clear();
    m_ringFrom.clear();
    Q_EMIT changed();
}

QVariantList RoomManager::whoIn(const QString &g, const QString &ch) const
{
    QStringList keys = m_presence.in(g, ch, now());
    const bool mine = isIn(g, ch);
    if (mine) {
        for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it)
            if (it->s && it->s->state() != CallSession::Ended && !keys.contains(it.key()))
                keys << it.key();
    }
    QVariantList out;
    if (mine)
        out << QVariantMap{{QStringLiteral("key"), me()}, {QStringLiteral("name"), QStringLiteral("You")}, {QStringLiteral("me"), true},
                           {QStringLiteral("speaking"), !m_muted && m_mySpeaking.on()}, {QStringLiteral("state"), QStringLiteral("live")}};
    const qint64 t = now();
    for (const QString &k : keys) {
        if (k == me())
            continue;
        const Peer p = m_peers.value(k);
        const bool live = mine && p.s && p.s->state() == CallSession::Active;
        const bool quiet = !live && ((mine && p.s && p.s->state() == CallSession::Reconnecting) || m_presence.state(g, ch, k, t) == 2);
        out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(g, k)}, {QStringLiteral("me"), false},
                           {QStringLiteral("speaking"), mine && p.s && p.s->media() && p.s->media()->speaking()},
                           {QStringLiteral("state"), quiet ? QStringLiteral("reconnecting") : QStringLiteral("live")}};
    }
    return out;
}

QVariantList RoomManager::groupCalls(const QString &g) const
{
    QHash<QString, QStringList> rooms = m_presence.rooms(g, now());
    if (inRoom() && m_g == g && groupCall() && !rooms.contains(m_ch))
        rooms.insert(m_ch, QStringList());
    QVariantList out;
    for (auto it = rooms.cbegin(); it != rooms.cend(); ++it) {
        if (!RoomWire::isGroupCall(it.key()))
            continue;
        const QVariantList who = whoIn(g, it.key());
        QStringList names;
        for (const QVariant &v : who)
            names << v.toMap().value(QStringLiteral("name")).toString();
        out << QVariantMap{{QStringLiteral("ch"), it.key()}, {QStringLiteral("count"), int(who.size())}, {QStringLiteral("names"), names.join(QStringLiteral(", "))},
                           {QStringLiteral("mine"), isIn(g, it.key())}};
    }
    return out;
}

// ------------------------------------------------------------------ audio

void RoomManager::mix(qint16 *pcm)
{
    int acc[AudioDsp::kFrame] = {};
    qint16 one[AudioDsp::kFrame];
    for (const Peer &p : std::as_const(m_peers)) {
        if (!p.s || !p.s->media() || p.s->state() == CallSession::Ended)
            continue;
        p.s->media()->playFrame(one);
        for (int i = 0; i < AudioDsp::kFrame; ++i)
            acc[i] += one[i];
    }
    // Deafened: every frame is still taken (so the buffers keep pace), and none is played.
    if (m_deaf)
        std::fill(acc, acc + AudioDsp::kFrame, 0);
    for (int i = 0; i < AudioDsp::kFrame; ++i)
        pcm[i] = qint16(std::clamp(acc[i], -32768, 32767));
}

void RoomManager::onMic(const QByteArray &pcm)
{
    if (pcm.size() != AudioDsp::kFrame * 2)
        return;
    const qint16 *frame = reinterpret_cast<const qint16 *>(pcm.constData());
    // Push to talk with the key up, or voice activity under the threshold: silence goes out instead (no mute message
    // on every press).
    static const qint16 silence[AudioDsp::kFrame] = {};
    const bool held = gateFrame(frame);
    if (!held)
        frame = silence;
    const bool was = m_mySpeaking.on();
    if (m_mySpeaking.frame(m_muted || !held ? -100.0 : AudioDsp::levelDb(frame, AudioDsp::kFrame)) != was)
        Q_EMIT changed();
    // Every peer's media gets every frame, muted or not, so their timestamps keep pace; muted
    // media sends no audio (5.5).
    for (const Peer &p : std::as_const(m_peers))
        if (p.s && p.s->media() && p.s->state() != CallSession::Ended)
            p.s->media()->feed(frame);
}

// ------------------------------------------------------------------ the mesh

CallSession *RoomManager::newSession(const QString &key)
{
    auto *s = new CallSession(m_signal, key, m_login, this);
    s->useDevices = false;   // this class has the microphone and the speaker
    // Two people in a room who trust each other may go direct too (5.8.2); everyone else: relay only.
    s->trusted = m_calls && m_calls->trusted(key);
    s->internet = Settings::internetCallsOn();
    s->videoCodec = vpxAvailable();   // screen share (5.8.7)
    watchMedia(key, s);
    connect(s, &CallSession::stateChanged, this, [this, s] {
        // Muted before the pair's call was up: tell them once it is.
        if (s->state() == CallSession::Active && m_muted)
            m_signal->send(s->peerKey(), QJsonObject{{QStringLiteral("t"), QStringLiteral("mute")}, {QStringLiteral("call"), s->callId()}, {QStringLiteral("on"), true}});
        Q_EMIT changed();
    });
    connect(s, &CallSession::pathsChanged, this, &RoomManager::changed);
    connect(s, &CallSession::mutedChanged, this, &RoomManager::changed);
    connect(s, &CallSession::speakingChanged, this, &RoomManager::changed);
    connect(s, &CallSession::ended, this, [this, key, s] { peerEnded(key, s); });
    return s;
}

void RoomManager::offerTo(const QString &key, int retries)
{
    if (!inRoom() || m_peers.value(key).s)
        return;
    CallSession *s = newSession(key);
    s->offerExtra = QJsonObject{{QStringLiteral("g"), m_g}, {QStringLiteral("ch"), m_ch}};
    Peer p;
    p.s = s;
    p.retries = retries;
    m_peers.insert(key, p);
    s->start();
    if (m_muted)
        s->setMuted(true);
    Q_EMIT changed();
}

void RoomManager::dropSession(const QString &key)
{
    const Peer p = m_peers.take(key);
    if (p.s) {
        p.s->disconnect(this);
        p.s->deleteLater();   // without a word (6.3 step 4): its destructor stops media and the relay
    }
}

void RoomManager::peerEnded(const QString &key, CallSession *s)
{
    const auto it = m_peers.find(key);
    if (it == m_peers.end() || it->s != s) {
        s->deleteLater();
        return;
    }
    const int retries = it->retries;
    m_peers.erase(it);
    s->deleteLater();
    if (key == m_sharer)
        QTimer::singleShot(0, this, &RoomManager::pickSharer);
    const QString why = s->endText();
    // A pair that lost its connection while both stay: the lower key calls again (6.3 step 7).
    if (inRoom() && (why.startsWith(QLatin1String("Can't connect")) || why == QLatin1String("Call dropped")) && retries < 3
        && RoomWire::offerWins(me(), key) == me()) {
        const int gen = m_gen;
        QTimer::singleShot(5000, this, [this, gen, key, retries] {
            if (gen == m_gen)
                offerTo(key, retries + 1);
        });
    }
    ++m_serial;
    Q_EMIT presenceChanged();
    Q_EMIT changed();
}

// ------------------------------------------------------------------ signaling in

void RoomManager::onSignal(const QString &from, const QJsonObject &msg)
{
    if (str(msg, "call") == QLatin1String("-")) {
        onRoomMessage(from, msg);
        return;
    }
    if (msg.contains(QStringLiteral("g"))) {
        if (str(msg, "t") == QLatin1String("offer"))
            onRoomOffer(from, msg);
        return;
    }
    // Everything else for a call of this room (ring, answer, cands, mute, hangup).
    const Peer p = m_peers.value(from);
    if (p.s && p.s->callId() == str(msg, "call"))
        p.s->handle(msg);
}

void RoomManager::onRoomMessage(const QString &from, const QJsonObject &msg)
{
    const QString g = str(msg, "g"), ch = str(msg, "ch"), t = str(msg, "t");
    const QJsonObject st = groupState ? groupState(g) : QJsonObject();
    const QString verdict = RoomWire::accept(st.isEmpty() ? nullptr : &st, me(), from, msg, now());
    const bool fresh = verdict == QLatin1String("ok");
    if (!fresh && verdict != QLatin1String("stale"))
        return;   // refused (6.2)
    // Cap g2: someone timed out or banned doesn't join, ring or show up in this group's rooms (a leave still counts).
    if (barred && barred(g, from) && t != QLatin1String("vleave"))
        return;
    const bool here = isIn(g, ch);
    if (t == QLatin1String("vjoin") || t == QLatin1String("vleave")) {
        if (m_presence.apply(from, msg, now(), beats(from), fresh)) {
            ++m_serial;
            Q_EMIT presenceChanged();
        }
        if (t == QLatin1String("vjoin")) {
            // A heartbeat from someone with no call here: offered again at most every 30 s (a pair that can't connect
            // doesn't try every 10 s).
            if (fresh && here && !m_peers.value(from).s && now() - m_offered.value(from) >= 30000) {
                if (RoomWire::full(peerCount()))
                    sendRoom(QStringLiteral("full"), from, g, ch);
                else {
                    m_offered.insert(from, now());
                    offerTo(from);
                }
            }
        } else {
            m_offered.remove(from);
            if (here && m_peers.value(from).s) {
                const Peer p = m_peers.take(from);
                p.s->disconnect(this);
                p.s->hangup();
                p.s->deleteLater();
                Q_EMIT changed();
            }
            // The one who rang gave up and nobody is left: stop ringing.
            if (ringing() && m_ringG == g && m_ringCh == ch && m_presence.in(g, ch, now()).isEmpty()) {
                const QString text = QStringLiteral("Missed group call from %1").arg(ringName());
                stopRing();
                Q_EMIT finished(text);
                Q_EMIT logged(g, text, true);
            }
        }
        return;
    }
    if (t == QLatin1String("vring")) {
        if (here || (ringing() && m_ringG == g && m_ringCh == ch))
            return;   // in it already, or the same ring again (6.5 step 1)
        const bool busy = inRoom() || (m_calls && (m_calls->inCall() || m_calls->state() == QLatin1String("incoming")));
        if (!fresh || busy || ringing()) {
            const QString text = QStringLiteral("Missed group call from %1").arg(nameOf(g, from));
            Q_EMIT finished(text);
            Q_EMIT logged(g, text, true);
            return;
        }
        // A muted group: no ringing, just a line in the group (its voice room rows show who is in).
        if (hushedFor && hushedFor(g)) {
            Q_EMIT logged(g, QStringLiteral("Missed group call from %1").arg(nameOf(g, from)), true);
            return;
        }
        startRing(from, g, ch);
        return;
    }
    if (!fresh || !here)
        return;
    if (t == QLatin1String("vdecline")) {
        m_declined.insert(from);
        Q_EMIT finished(QStringLiteral("%1 can't join right now").arg(nameOf(g, from)));
    } else if (t == QLatin1String("full") && now() - m_joinedAt < 30000) {
        m_endText = QStringLiteral("This room is full (%1 people)").arg(RoomWire::kMaxPeople);
        leave();
        Q_EMIT finished(m_endText);
    }
}

void RoomManager::onRoomOffer(const QString &from, const QJsonObject &msg)
{
    const QString g = str(msg, "g"), ch = str(msg, "ch"), call = str(msg, "call");
    const QString why = CallSession::checkOffer(msg, from, now());
    if (why == QLatin1String("signature") || why == QLatin1String("malformed") || why == QLatin1String("stale"))
        return;
    const QJsonObject st = groupState ? groupState(g) : QJsonObject();
    if (!isIn(g, ch) || !GroupWire::isMember(st, from) || why == QLatin1String("codec") || (barred && barred(g, from))) {
        m_signal->send(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), call}});
        return;
    }
    const Peer cur = m_peers.value(from);
    if (cur.s) {
        if (cur.s->callId() == call) {
            cur.s->handle(msg);   // a resent offer
            return;
        }
        // Both offered (6.3 step 4): the lower chat key's offer wins.
        if (cur.s->outgoing() && RoomWire::offerWins(me(), from) == me())
            return;
        dropSession(from);
    }
    CallSession *s = newSession(from);
    Peer p;
    p.s = s;
    p.retries = cur.retries;
    m_peers.insert(from, p);
    if (m_muted)
        s->setMuted(true);
    s->takeOffer(msg);
    s->answer();   // no ringing inside a room (6.3 step 3)
    Q_EMIT changed();
}

// ------------------------------------------------------------------ screen share (5.8.7)

bool RoomManager::canShare() const { return vpxAvailable(); }

QString RoomManager::sharerName() const { return m_sharer.isEmpty() ? QString() : nameOf(m_g, m_sharer); }

void RoomManager::setScreenSink(QObject *sink)
{
    m_screenSink = qobject_cast<QVideoSink *>(sink);
    if (m_view)
        m_view->setSink(m_screenSink);
    const Peer p = m_peers.value(m_sharer);
    if (m_screenSink && p.s && p.s->media())
        p.s->media()->requestKeyframe(VideoWire::Screen);
}

void RoomManager::watchMedia(const QString &key, CallSession *s)
{
    connect(s, &CallSession::mediaStarted, this, [this, key, s] {
        CallMedia *m = s->media();
        if (!m)
            return;
        connect(m, &CallMedia::videoFrame, this, [this, key](int stream, const QByteArray &vp8, bool isKey, int rotation) {
            if (stream == VideoWire::Screen && key == m_sharer && m_view)
                m_view->frame(vp8, isKey, rotation);
        });
        connect(m, &CallMedia::peerVideoChanged, this, &RoomManager::pickSharer);
        connect(m, &CallMedia::keyframeWanted, this, [this](int stream) {
            if (stream == VideoWire::Screen && m_share)
                m_share->requestKey();
        });
        if (m_share)
            shareTo(s, true);   // joined while we share: they see it too
    });
}

void RoomManager::shareTo(CallSession *s, bool on)
{
    if (!s || !s->media() || !s->videoBoth())
        return;
    s->media()->setVideoSending(VideoWire::Screen, on);
    if (on && m_share)
        m_share->requestKey();
}

bool RoomManager::sharePicker() const
{
    // Wayland and the Flatpak go through the desktop portal, whose own dialog asks what to share.
    return !QGuiApplication::platformName().startsWith(QLatin1String("wayland")) && qEnvironmentVariableIsEmpty("FLATPAK_ID");
}

void RoomManager::toggleShare()
{
    if (m_share) {
        stopShare();
        Q_EMIT changed();
        return;
    }
    if (!inRoom() || !vpxAvailable())
        return;
    if (sharePicker()) {
        Q_EMIT pickShare();
        return;
    }
    const QString why = startShare([](VideoSender *) {}, QStringLiteral("Your screen"));
    if (!why.isEmpty())
        Q_EMIT finished(why);
}

QVariantList RoomManager::shareWindows()
{
    m_windows.clear();
    QVariantList out;
    for (const QCapturableWindow &w : QWindowCapture::capturableWindows()) {
        if (!w.isValid() || w.description().trimmed().isEmpty())
            continue;
        out << QVariantMap{{QStringLiteral("i"), int(m_windows.size())}, {QStringLiteral("title"), w.description().trimmed()},
                           {QStringLiteral("window"), QVariant::fromValue(w)}};
        m_windows << w;
        if (m_windows.size() >= 24)
            break;
    }
    return out;
}

QVariantList RoomManager::shareScreens() const
{
    QVariantList out;
    const QList<QScreen *> screens = QGuiApplication::screens();
    for (int i = 0; i < int(screens.size()); ++i) {
        const QSize sz = screens.at(i)->size() * screens.at(i)->devicePixelRatio();
        out << QVariantMap{{QStringLiteral("i"), i}, {QStringLiteral("name"), QStringLiteral("Screen %1").arg(i + 1)},
                           {QStringLiteral("size"), QStringLiteral("%1 %2 %3").arg(sz.width()).arg(QChar(0x00D7)).arg(sz.height())},
                           {QStringLiteral("primary"), screens.at(i) == QGuiApplication::primaryScreen()}};
    }
    return out;
}

QString RoomManager::shareWindow(int i)
{
    if (i < 0 || i >= int(m_windows.size()) || !m_windows.at(i).isValid())
        return QStringLiteral("That window is closed. Pick another.");
    const QCapturableWindow w = m_windows.at(i);
    return startShare([w](VideoSender *v) { v->setWindow(w); }, w.description().trimmed());
}

QString RoomManager::shareScreen(int i)
{
    const QList<QScreen *> screens = QGuiApplication::screens();
    if (i < 0 || i >= int(screens.size()))
        return QStringLiteral("That screen isn't there anymore.");
    QScreen *screen = screens.at(i);
    return startShare([screen](VideoSender *v) { v->setScreen(screen); }, QStringLiteral("Screen %1").arg(i + 1));
}

QString RoomManager::startShare(const std::function<void(VideoSender *)> &aim, const QString &title)
{
    if (m_share)
        stopShare();
    if (!inRoom() || !vpxAvailable())
        return QStringLiteral("Join a voice room first.");
    m_share = new VideoSender(VideoSender::Screen, this);
    aim(m_share);
    m_shareTitle = title;
    connect(m_share, &VideoSender::encoded, this, [this](const QByteArray &vp8, bool key) {
        // Encoded once, sealed for each person with that pair's keys.
        for (const Peer &p : std::as_const(m_peers))
            if (p.s && p.s->media() && p.s->videoBoth())
                p.s->media()->sendVideo(VideoWire::Screen, key, 0, vp8);
    });
    connect(m_share, &VideoSender::failed, this, [this](const QString &) {
        stopShare();
        Q_EMIT finished(QStringLiteral("Sharing stopped: it can't be captured anymore"));
        Q_EMIT changed();
    });
    // The size and bitrate everyone can take: the relay profile as soon as one of them is on the relay.
    auto profile = [this] {
        bool allFree = !m_peers.isEmpty();
        for (const Peer &p : std::as_const(m_peers))
            if (p.s && p.s->media() && p.s->media()->peerWatching()
                && p.s->media()->profile(VideoWire::Screen).capKbps == VideoWire::screenProfile(false).capKbps)
                allFree = false;
        return VideoWire::screenProfile(allFree, m_shareSmooth);
    };
    m_share->setProfile(profile());
    if (!m_share->start()) {
        m_share->deleteLater();
        m_share = nullptr;
        Q_EMIT changed();
        return QStringLiteral("That can't be shared. Pick another.");
    }
    for (const Peer &p : std::as_const(m_peers))
        shareTo(p.s, true);
    m_shareRate.disconnect(this);
    m_shareRate.setInterval(1000);
    connect(&m_shareRate, &QTimer::timeout, this, [this, profile] {
        if (!m_share)
            return;
        int kbps = 0;
        for (const Peer &p : std::as_const(m_peers))
            if (p.s && p.s->media() && p.s->videoBoth() && p.s->media()->peerWatching()) {   // c3: only those watching
                const int k = p.s->media()->videoKbps(VideoWire::Screen);
                kbps = kbps ? std::min(kbps, k) : k;
            }
        if (kbps)
            m_share->setKbps(kbps);
        m_share->setProfile(profile());
    });
    m_shareRate.start();
    Q_EMIT changed();
    return QString();
}

void RoomManager::setShareSmooth(bool on)
{
    if (on == m_shareSmooth)
        return;
    m_shareSmooth = on;
    Q_EMIT changed();   // a share in progress takes it at its next second (m_shareRate)
}

void RoomManager::stopShare()
{
    m_shareRate.stop();
    if (!m_share)
        return;
    for (const Peer &p : std::as_const(m_peers))
        shareTo(p.s, false);
    m_share->stop();
    m_share->deleteLater();
    m_share = nullptr;
}

void RoomManager::pickSharer()
{
    // The one shown stays while they share; otherwise the first who does.
    auto sharesNow = [this](const QString &k) {
        const Peer p = m_peers.value(k);
        return p.s && p.s->media() && p.s->media()->peerSending(VideoWire::Screen);
    };
    if (!m_sharer.isEmpty() && sharesNow(m_sharer))
        return;
    QString next;
    for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it)
        if (sharesNow(it.key())) {
            next = it.key();
            break;
        }
    if (next == m_sharer)
        return;
    m_sharer = next;
    if (m_view) {
        m_view->deleteLater();
        m_view = nullptr;
    }
    if (m_screenSink)
        m_screenSink->setVideoFrame(QVideoFrame());
    if (!next.isEmpty()) {
        m_view = new VideoReceiver(this);
        m_view->setSink(m_screenSink);
        const Peer p = m_peers.value(next);
        QPointer<CallMedia> pm = p.s ? p.s->media() : nullptr;
        connect(m_view, &VideoReceiver::needKey, this, [pm] {
            if (pm)
                pm->requestKeyframe(VideoWire::Screen);
        });
        if (pm)
            pm->requestKeyframe(VideoWire::Screen);
    }
    Q_EMIT changed();
}
