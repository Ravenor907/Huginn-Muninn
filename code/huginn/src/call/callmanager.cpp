#include "callmanager.h"

#include "audioio.h"
#include "../settings.h"
#include "callcrypto.h"
#include "callmedia.h"
#include "callsession.h"
#include "callsignal.h"
#include "opuscodec.h"
#include "relayconfig.h"
#include "turnclient.h"
#include "videoengine.h"
#include "vpxcodec.h"
#include "../vault.h"
#include "../ring.h"

#include <QVideoSink>

#include <QDateTime>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRandomGenerator>
#include <QUrl>

#ifdef Q_OS_LINUX
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusReply>
#include <QVariantMap>
#else
#include "../platform/notify.h"
#endif
#include "../platform/chime.h"

namespace
{
const char kFile[] = "calls.json";
const char kTrustFile[] = "calltrust.json";
const int kMaxHistory = 200;
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

CallManager::CallManager(CallSignal *signal, Vault *vault, QObject *parent)
    : QObject(parent), m_signal(signal), m_vault(vault), m_relay(new RelayCredentials(this))
{
    connect(m_signal, &CallSignal::received, this, &CallManager::onSignal);
    connect(m_signal, &CallSignal::mediaReceived, this, [this](const QString &from, const QByteArray &pkt) {
        if (m_session && m_session->peerKey() == from && m_session->media())
            m_session->media()->receive(pkt, CallMedia::Ble);
    });
    m_ticker.setInterval(1000);
    connect(&m_ticker, &QTimer::timeout, this, &CallManager::tick);
    // The video bitrate follows the other side's reports (5.8.6).
    m_videoRate.setInterval(1000);
    connect(&m_videoRate, &QTimer::timeout, this, [this] {
        if (m_camera && m_session && m_session->media())
            m_camera->setKbps(m_session->media()->videoKbps(VideoWire::Camera));
    });
    m_clear.setSingleShot(true);
    m_clear.setInterval(2500);
    connect(&m_clear, &QTimer::timeout, this, [this] {
        m_endText.clear();
        Q_EMIT changed();
    });
    // The raven call, every 3 s while someone is calling.
    m_ringtone = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/raven_message.wav"), this);
    m_ringer.setInterval(3000);
    connect(&m_ringer, &QTimer::timeout, m_ringtone, &Chime::play);
    // Cap b1: a call that rang while locked stops ringing after as long as any call rings.
    m_lockedTimer.setSingleShot(true);
    m_lockedTimer.setInterval(45000);
    connect(&m_lockedTimer, &QTimer::timeout, this, &CallManager::endLockedRing);
    if (m_vault) {
        // Unlocked: the offer itself comes in now (with the name), so the nameless ring stops.
        connect(m_vault, &Vault::opened, this, &CallManager::endLockedRing);
        connect(m_vault, &Vault::opened, this, &CallManager::load);
        connect(m_vault, &Vault::opened, this, &CallManager::loadTrust);
        // Panic lock: the call ends at once and nothing about it stays on screen.
        connect(m_vault, &Vault::panicked, this, [this] {
            if (m_session)
                m_session->hangup();
            m_endText.clear();
            Q_EMIT changed();
        });
        if (!m_vault->locked()) {
            load();
            loadTrust();
        }
    }
#ifdef Q_OS_LINUX
    QDBusConnection::sessionBus().connect(QString(), QStringLiteral("/org/freedesktop/Notifications"), QStringLiteral("org.freedesktop.Notifications"),
                                          QStringLiteral("ActionInvoked"), this, SLOT(onNotificationAction(uint, QString)));
#endif
}

CallManager::~CallManager()
{
    if (m_session)
        m_session->hangup();
}

bool CallManager::available() const { return opusAvailable(); }

// ------------------------------------------------------------------ state for QML

QString CallManager::state() const
{
    if (!m_session || m_session->state() == CallSession::Ended)
        return m_endText.isEmpty() ? QStringLiteral("idle") : QStringLiteral("ended");
    switch (m_session->state()) {
    case CallSession::Calling: return QStringLiteral("calling");
    case CallSession::Ringing: return QStringLiteral("ringing");
    case CallSession::Incoming: return QStringLiteral("incoming");
    case CallSession::Connecting: return QStringLiteral("connecting");
    case CallSession::Active: return QStringLiteral("active");
    case CallSession::Reconnecting: return QStringLiteral("reconnecting");
    case CallSession::Ended: break;
    }
    return QStringLiteral("idle");
}

bool CallManager::inCall() const
{
    const QString s = state();
    return s != QLatin1String("idle") && s != QLatin1String("incoming");
}

QString CallManager::peerKey() const { return m_session ? m_session->peerKey() : m_lastPeer; }

QString CallManager::peerName() const
{
    const QString k = peerKey();
    if (k.isEmpty())
        return QString();
    const QString n = m_signal->nameOf(k);
    return n.isEmpty() ? QStringLiteral("Someone") : n;
}

QString CallManager::statusText() const
{
    const QString s = state();
    if (s == QLatin1String("calling")) return QStringLiteral("Calling…");
    if (s == QLatin1String("ringing")) return QStringLiteral("Ringing…");
    if (s == QLatin1String("incoming")) return QStringLiteral("Incoming call");
    if (s == QLatin1String("connecting")) return QStringLiteral("Connecting…");
    if (s == QLatin1String("reconnecting")) return QStringLiteral("Reconnecting…");
    if (s == QLatin1String("ended")) return m_endText;
    if (s == QLatin1String("active")) return QStringLiteral("Connected · ") + duration(elapsed());
    return QString();
}

int CallManager::elapsed() const { return m_session ? int(m_session->activeMs() / 1000) : 0; }
bool CallManager::muted() const { return m_session && m_session->muted(); }
bool CallManager::peerMuted() const { return m_session && m_session->peerMuted(); }
bool CallManager::peerSpeaking() const { return m_session && m_session->media() && m_session->media()->speaking(); }
bool CallManager::relayLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Relay); }
bool CallManager::wifiLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Wifi); }
bool CallManager::bleLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Ble); }
bool CallManager::directLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Direct); }
bool CallManager::directTried() const { return m_session && m_session->directTried(); }
bool CallManager::videoAvailable() const { return vpxAvailable(); }
bool CallManager::videoPossible() const { return m_session && m_session->media() && m_session->videoBoth() && m_session->state() != CallSession::Ended; }
bool CallManager::peerCamera() const { return videoPossible() && m_session->media()->peerSending(VideoWire::Camera); }
int CallManager::peerRotation() const { return m_remote ? m_remote->rotation() : 0; }
bool CallManager::incomingVideo() const { return m_session && m_session->state() == CallSession::Incoming && m_session->peerWantsVideo() && vpxAvailable(); }
QString CallManager::cameraName() const { return m_camera ? m_camera->cameraName() : QString(); }

// ------------------------------------------------------------------ video (5.8.5)

bool CallManager::canVideo(const QString &key) const { return canCall(key) && vpxAvailable() && m_signal->mayHave(key, QStringLiteral("c2")); }

QString CallManager::callVideo(const QString &key) { return startCall(key, vpxAvailable()); }

void CallManager::answerVideo()
{
    if (!m_session || m_session->state() != CallSession::Incoming)
        return;
    m_cameraOn = vpxAvailable();
    m_session->wantVideo = m_cameraOn;
    answer();
}

void CallManager::toggleCamera() { setCamera(!m_cameraOn); }

void CallManager::nextCamera()
{
    if (m_camera && m_camera->nextCamera())
        Q_EMIT changed();
}

void CallManager::setRemoteSink(QObject *sink)
{
    m_remoteSink = qobject_cast<QVideoSink *>(sink);
    if (m_remote)
        m_remote->setSink(m_remoteSink);
    if (m_remoteSink && m_session && m_session->media())
        m_session->media()->requestKeyframe(VideoWire::Camera);   // a fresh picture for the new view
}

void CallManager::setLocalSink(QObject *sink)
{
    m_localSink = qobject_cast<QVideoSink *>(sink);
    if (m_camera)
        m_camera->setPreview(m_localSink);
}

void CallManager::startVideo()
{
    CallSession *s = m_session;
    if (!s || !s->media() || !s->videoBoth())
        return;
    CallMedia *m = s->media();
    if (!m_remote) {
        m_remote = new VideoReceiver(this);
        m_remote->setSink(m_remoteSink);
        connect(m_remote, &VideoReceiver::changed, this, &CallManager::changed);
        QPointer<CallMedia> pm = m;
        connect(m_remote, &VideoReceiver::needKey, this, [pm] {
            if (pm)
                pm->requestKeyframe(VideoWire::Camera);
        });
        connect(m, &CallMedia::videoFrame, m_remote, [this](int stream, const QByteArray &vp8, bool key, int rotation) {
            if (stream == VideoWire::Camera && m_remote)
                m_remote->frame(vp8, key, rotation);
        });
        connect(m, &CallMedia::peerVideoChanged, this, &CallManager::changed);
        connect(m, &CallMedia::keyframeWanted, this, [this](int stream) {
            if (stream == VideoWire::Camera && m_camera)
                m_camera->requestKey();
        });
        connect(m, &CallMedia::profileChanged, this, [this] {
            if (m_camera && m_session && m_session->media())
                m_camera->setProfile(m_session->media()->profile(VideoWire::Camera));
        });
    }
    if (m_cameraOn)
        setCamera(true);
    Q_EMIT changed();
}

void CallManager::setCamera(bool on)
{
    m_cameraOn = on && vpxAvailable();
    CallMedia *m = m_session ? m_session->media() : nullptr;
    if (!m || !m_session->videoBoth()) {
        Q_EMIT changed();   // remembered for when the call connects
        return;
    }
    if (m_cameraOn) {
        if (!m_camera) {
            m_camera = new VideoSender(VideoSender::Camera, this);
            connect(m_camera, &VideoSender::encoded, this, [this](const QByteArray &vp8, bool key) {
                if (m_session && m_session->media())
                    m_session->media()->sendVideo(VideoWire::Camera, key, 0, vp8);
            });
            connect(m_camera, &VideoSender::failed, this, [this](const QString &) {
                Q_EMIT finished(QStringLiteral("The camera isn't available"));
                setCamera(false);
            });
        }
        m_camera->setPreview(m_localSink);
        m_camera->setProfile(m->profile(VideoWire::Camera));
        if (!m_camera->start()) {
            Q_EMIT finished(QStringLiteral("No camera found"));
            m_cameraOn = false;
            m->setVideoSending(VideoWire::Camera, false);
            Q_EMIT changed();
            return;
        }
        m->setVideoSending(VideoWire::Camera, true);
        m_videoRate.start();
    } else {
        m->setVideoSending(VideoWire::Camera, false);
        if (m_camera)
            m_camera->stop();
        m_videoRate.stop();
    }
    Q_EMIT changed();
}

void CallManager::stopVideo()
{
    m_videoRate.stop();
    m_cameraOn = false;
    if (m_camera) {
        m_camera->stop();
        m_camera->deleteLater();
        m_camera = nullptr;
    }
    if (m_remote) {
        m_remote->deleteLater();
        m_remote = nullptr;
    }
    if (m_remoteSink)
        m_remoteSink->setVideoFrame(QVideoFrame());
}

QString CallManager::outputName() const
{
    AudioIO *a = m_session && m_session->media() ? m_session->media()->audio() : nullptr;
    return a ? a->outputName() : QStringLiteral("Default");
}

QString CallManager::duration(int s) const
{
    if (s < 0)
        s = 0;
    const int h = s / 3600, m = (s / 60) % 60, sec = s % 60;
    const QString mmss = QStringLiteral("%1:%2").arg(m, h ? 2 : 1, 10, QLatin1Char('0')).arg(sec, 2, 10, QLatin1Char('0'));
    return h ? QStringLiteral("%1:%2").arg(h).arg(mmss) : mmss;
}

// ------------------------------------------------------------------ actions

bool CallManager::canCall(const QString &key) const { return available() && !key.isEmpty() && m_signal->canCall(key); }

void CallManager::prepare(const QString &key)
{
    const qint64 now = nowMs();
    if (now - m_preparedAt < 60000 || !Settings::internetCallsOn() || !canCall(key) || !RelayConfig::configured() || m_signal->myKey().isEmpty())
        return;
    m_preparedAt = now;
    getLogin([](const TurnLogin &, const QString &) {});
}

void CallManager::getLogin(std::function<void(const TurnLogin &, const QString &)> done)
{
    // Internet calls off: the relay Worker is never asked (and, like get(), never answers from inside).
    if (!Settings::internetCallsOn()) {
        QTimer::singleShot(0, this, [done = std::move(done)] { done(TurnLogin(), QStringLiteral("Internet calls are off")); });
        return;
    }
    m_relay->get(relayId(), std::move(done));
}

QByteArray CallManager::relayId()
{
    // The relay Worker counts logins per id. A random id made once and kept in the vault, not the chat key:
    // anyone who knows a chat key could use up that person's logins, and the Worker would see who calls.
    if (m_vault->locked())
        return QByteArray();
    QJsonObject s = QJsonDocument::fromJson(m_vault->read(QStringLiteral("secrets.json"))).object();
    QByteArray id = QByteArray::fromHex(s.value(QStringLiteral("relayId")).toString().toLatin1());
    if (id.size() != 32) {
        id.resize(32);
        QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(id.data()), 8);
        s.insert(QStringLiteral("relayId"), QString::fromLatin1(id.toHex()));
        m_vault->write(QStringLiteral("secrets.json"), QJsonDocument(s).toJson(QJsonDocument::Compact));
    }
    return id;
}

QString CallManager::call(const QString &key) { return startCall(key, false); }

QString CallManager::startCall(const QString &key, bool video)
{
    if (!available())
        return QStringLiteral("Calls aren't available in this build.");
    if (m_session && m_session->state() != CallSession::Ended)
        return QStringLiteral("You're already in a call.");
    if (busyElsewhere && busyElsewhere())
        return QStringLiteral("Leave the voice room first.");
    if (!m_signal->canCall(key))
        return QStringLiteral("Needs Muninn or Huginn 0.6");
    if (!Settings::internetCallsOn() && m_signal->lanHost(key).isEmpty() && !m_signal->bleReady(key))
        return QStringLiteral("Internet calls are off in Settings › Calls. Calls work on the same Wi-Fi.");
    // The internet route's sending sockets close after 20 s of quiet; reopening one (TLS and all)
    // starts now, while the offer is being put together, not after it's ready.
    m_signal->warmUp(key);
    auto *s = new CallSession(m_signal, key, [this](auto done) { getLogin(std::move(done)); }, this);
    prime(s);
    s->wantVideo = video;
    adopt(s);
    m_cameraOn = video;
    m_started = nowMs();
    s->start();
    return QString();
}

void CallManager::adopt(CallSession *s)
{
    stopVideo();
    if (m_session)
        m_session->deleteLater();
    m_session = s;
    m_endText.clear();
    m_clear.stop();
    m_lastPeer = s->peerKey();
    connect(s, &CallSession::stateChanged, this, [this, s] {
        if (s != m_session)
            return;
        const bool active = s->state() == CallSession::Active || s->state() == CallSession::Reconnecting;
        if (active && !m_ticker.isActive())
            m_ticker.start();
        if (s->state() != CallSession::Incoming) {
            ring(false);
            notifyIncoming(false);
        }
        Q_EMIT changed();
    });
    connect(s, &CallSession::pathsChanged, this, &CallManager::changed);
    connect(s, &CallSession::mutedChanged, this, &CallManager::changed);
    connect(s, &CallSession::speakingChanged, this, &CallManager::changed);
    connect(s, &CallSession::ended, this, &CallManager::sessionEnded);
    Q_EMIT changed();
}

void CallManager::answer()
{
    if (m_session && m_session->state() == CallSession::Incoming) {
        ring(false);
        notifyIncoming(false);
        m_started = nowMs();
        m_session->answer();
    }
}

void CallManager::decline()
{
    if (m_session && m_session->state() == CallSession::Incoming)
        m_session->decline();
}

void CallManager::hangup()
{
    if (!m_session)
        return;
    if (m_session->state() == CallSession::Incoming)
        m_session->decline();
    else
        m_session->hangup();
}

void CallManager::toggleMute()
{
    if (m_session)
        m_session->setMuted(!m_session->muted());
}

void CallManager::nextOutput()
{
    AudioIO *a = m_session && m_session->media() ? m_session->media()->audio() : nullptr;
    if (!a)
        return;
    a->nextOutput();
    Q_EMIT changed();
}

void CallManager::sessionEnded()
{
    CallSession *s = m_session;
    if (!s)
        return;
    stopVideo();
    ring(false);
    notifyIncoming(false);
    m_ticker.stop();
    const QString text = s->endText();
    const bool missed = !s->outgoing() && !s->wasAnswered() && text != QLatin1String("Declined");
    const QString result = s->wasAnswered() ? QStringLiteral("answered") : missed ? QStringLiteral("missed") : text;
    addHistory(s->peerKey(), s->outgoing(), result, m_started ? m_started : nowMs(), int(s->activeMs() / 1000));
    m_handled.insert(s->callId());
    m_endText = text;
    m_clear.start();
    Q_EMIT finished(missed ? QStringLiteral("Missed call from %1").arg(peerName()) : text);
    Q_EMIT changed();
}

// ------------------------------------------------------------------ signaling in

void CallManager::onSignal(const QString &from, const QJsonObject &msg)
{
    const QString t = str(msg, "t"), id = str(msg, "call");
    if (id == QLatin1String("-") || msg.contains(QStringLiteral("g")))
        return;   // voice rooms and group calls: RoomManager
    if (m_session && m_session->callId() == id && m_session->peerKey() == from) {
        m_session->handle(msg);
        return;
    }
    if (t != QLatin1String("offer") || m_handled.contains(id))
        return;
    // Cap b1: this call already rang while Huginn was locked, and Answer or Decline was chosen then.
    const int choice = id == m_lockedCall ? m_lockedChoice : 0;
    if (id == m_lockedCall) {
        endLockedRing();
        m_lockedCall.clear();
        m_lockedChoice = 0;
    }
    // Answered while locked: unlocking took a moment, so the offer may be older than usual (never past the ring).
    const qint64 offerTs = qint64(msg.value(QStringLiteral("ts")).toDouble());
    const bool answeredLocked = choice == 1 && offerTs >= nowMs() - 45000 - 60000;
    const QString why = CallSession::checkOffer(msg, from, answeredLocked ? qMin(nowMs(), offerTs + 60000) : nowMs());
    if (choice == 2 && why.isEmpty()) {
        m_handled.insert(id);
        const QJsonObject no{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), id}};
        m_signal->send(from, no);
        addHistory(from, false, QStringLiteral("Declined"), nowMs(), 0);
        return;
    }
    if (why == QLatin1String("signature") || why == QLatin1String("malformed"))
        return;   // refused: not from who it says
    if (!available() || why == QLatin1String("codec")) {
        m_handled.insert(id);
        m_signal->send(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), id}});
        return;
    }
    if (why == QLatin1String("stale")) {
        // An old offer that waited on a relay: a missed call, never a ringing one.
        m_handled.insert(id);
        addHistory(from, false, QStringLiteral("missed"), qint64(msg.value(QStringLiteral("ts")).toDouble()), 0);
        return;
    }
    // Internet calls off and they aren't on this Wi-Fi or Bluetooth: declined at once, never rung, and a missed-call
    // line says why.
    if (!Settings::internetCallsOn() && m_signal->lanHost(from).isEmpty() && !m_signal->bleReady(from)) {
        m_handled.insert(id);
        m_signal->send(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), id}});
        addHistory(from, false, QStringLiteral("internetOff"), nowMs(), 0);
        return;
    }
    if ((m_session && m_session->state() != CallSession::Ended) || (busyElsewhere && busyElsewhere())) {
        m_handled.insert(id);
        const QJsonObject busy{{QStringLiteral("t"), QStringLiteral("busy")}, {QStringLiteral("call"), id}};
        m_signal->send(from, busy);
        QPointer<CallSignal> sig = m_signal;
        for (int ms : {1000, 2000})
            QTimer::singleShot(ms, this, [sig, from, busy] {
                if (sig)
                    sig->send(from, busy);
            });
        addHistory(from, false, QStringLiteral("missed"), nowMs(), 0);
        return;
    }
    auto *s = new CallSession(m_signal, from, [this](auto done) { getLogin(std::move(done)); }, this);
    prime(s);
    adopt(s);
    m_started = nowMs();
    s->takeOffer(msg);
    if (answeredLocked) {
        // Answer was already chosen on the ringing notification: answered now.
        Q_EMIT incoming(peerName());
        answer();
        return;
    }
    ring(true);
    notifyIncoming(true);
    Q_EMIT incoming(peerName());
}

// ------------------------------------------------------------------ ringing

void CallManager::ring(bool on)
{
    if (on) {
        // Do not disturb: the call still shows, without sound.
        if (Settings::silenced())
            return;
        m_ringtone->play();
        m_ringer.start();
    } else {
        m_ringer.stop();
        m_ringtone->stop();
    }
}

void CallManager::notifyIncoming(bool on)
{
    const bool wanted = on && QGuiApplication::applicationState() != Qt::ApplicationActive;
#ifdef Q_OS_LINUX
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    if (!wanted) {
        if (m_notification)
            n.call(QStringLiteral("CloseNotification"), m_notification);
        m_notification = 0;
        return;
    }
    const bool preview = !m_vault || !m_vault->screenLocked();
    QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                      {QStringLiteral("resident"), true}};
    QDBusReply<uint> r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), m_notification, QStringLiteral("io.github.ravenor907.Huginn"),
                                QStringLiteral("Incoming call"), preview ? peerName() + QStringLiteral(" is calling") : QStringLiteral("Someone is calling"),
                                QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                hints, 45000);
    if (r.isValid())
        m_notification = r.value();
#else
    // Windows: a plain toast (no buttons); answering happens in the app.
    if (wanted)
        Platform::notify(QStringLiteral("Incoming call"), (!m_vault || !m_vault->screenLocked()) ? peerName() + QStringLiteral(" is calling") : QStringLiteral("Someone is calling"), 45000);
#endif
}

void CallManager::ringLocked(const QString &callId, qint64 ts)
{
    // Unlocked: the offer itself rings, with the name.
    if (!m_vault || !m_vault->locked() || callId.isEmpty() || callId == m_lockedCall || m_handled.contains(callId))
        return;
    if ((m_session && m_session->state() != CallSession::Ended) || !RingWire::ringFresh(ts, nowMs()))
        return;
    m_lockedCall = callId;
    m_lockedChoice = 0;
    m_lockedRinging = true;
    ring(true);
    notifyLocked(true);
    m_lockedTimer.start();
}

void CallManager::endLockedRing()
{
    m_lockedTimer.stop();
    if (!m_lockedRinging)
        return;
    m_lockedRinging = false;
    ring(false);
    notifyLocked(false);
}

void CallManager::notifyLocked(bool on)
{
#ifdef Q_OS_LINUX
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    if (!on) {
        if (m_notification)
            n.call(QStringLiteral("CloseNotification"), m_notification);
        m_notification = 0;
        return;
    }
    QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                      {QStringLiteral("resident"), true}};
    QDBusReply<uint> r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), m_notification, QStringLiteral("io.github.ravenor907.Huginn"),
                                QStringLiteral("Incoming call"), QStringLiteral("Unlock Huginn to answer"),
                                QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                hints, 45000);
    if (r.isValid())
        m_notification = r.value();
#else
    // Windows: a plain toast (no buttons); unlocking Huginn shows the call.
    if (on)
        Platform::notify(QStringLiteral("Incoming call"), QStringLiteral("Unlock Huginn to answer"), 45000);
#endif
}

void CallManager::onNotificationAction(uint id, const QString &action)
{
    if (!m_notification || id != m_notification)
        return;
    m_notification = 0;
    // Cap b1: the nameless ring while locked. What was chosen waits for the offer, read once Huginn is unlocked.
    if (m_lockedRinging && !m_session) {
        m_lockedChoice = action == QLatin1String("answer") ? 1 : action == QLatin1String("decline") ? 2 : 0;
        endLockedRing();
        return;
    }
    if (action == QLatin1String("answer"))
        answer();
    else if (action == QLatin1String("decline"))
        decline();
}

// ------------------------------------------------------------------ trust (5.8.1)

bool CallManager::trusted(const QString &key) const
{
    if (m_trustOn.contains(key))
        return true;
    if (m_trustOff.contains(key))
        return false;
    return m_signal->inFamily(key);
}

QString CallManager::trustText(const QString &key) const
{
    if (m_trustOn.contains(key))
        return QStringLiteral("On");
    if (m_trustOff.contains(key))
        return QStringLiteral("Off");
    return m_signal->inFamily(key) ? QStringLiteral("On (Family folder)") : QStringLiteral("Off");
}

void CallManager::setTrusted(const QString &key, bool on)
{
    if (key.isEmpty())
        return;
    m_trustOn.remove(key);
    m_trustOff.remove(key);
    (on ? m_trustOn : m_trustOff).insert(key);
    saveTrust();
    if (!on && m_session && m_session->peerKey() == key)
        m_session->setTrusted(false);
    Q_EMIT trustChanged(key, on);
    Q_EMIT changed();
}

void CallManager::prime(CallSession *s)
{
    s->trusted = trusted(s->peerKey());
    s->internet = Settings::internetCallsOn();
    s->c2Local = true;
    s->videoCodec = vpxAvailable();
    connect(s, &CallSession::mediaStarted, this, [this, s] {
        if (s == m_session)
            startVideo();
    });
}

void CallManager::loadTrust()
{
    if (!m_vault || m_vault->locked())
        return;
    const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QString::fromLatin1(kTrustFile))).object();
    for (const QJsonValue &v : o.value(QStringLiteral("on")).toArray())
        m_trustOn.insert(v.toString());
    for (const QJsonValue &v : o.value(QStringLiteral("off")).toArray())
        m_trustOff.insert(v.toString());
    m_trustOff.subtract(m_trustOn);
}

void CallManager::saveTrust()
{
    if (!m_vault || m_vault->locked())
        return;
    QJsonArray on, off;
    for (const QString &k : std::as_const(m_trustOn))
        on << k;
    for (const QString &k : std::as_const(m_trustOff))
        off << k;
    m_vault->write(QString::fromLatin1(kTrustFile), QJsonDocument(QJsonObject{{QStringLiteral("on"), on}, {QStringLiteral("off"), off}}).toJson(QJsonDocument::Compact));
}

// ------------------------------------------------------------------ history

void CallManager::addHistory(const QString &key, bool out, const QString &result, qint64 started, int seconds)
{
    QVariantMap e{{QStringLiteral("id"), CallCrypto::newCallId()},
                  {QStringLiteral("key"), key},
                  {QStringLiteral("name"), m_signal->nameOf(key)},
                  {QStringLiteral("out"), out},
                  {QStringLiteral("result"), result},
                  {QStringLiteral("started"), started},
                  {QStringLiteral("seconds"), seconds},
                  {QStringLiteral("missed"), result == QLatin1String("missed") || result == QLatin1String("internetOff")}};
    m_history.prepend(e);
    while (m_history.size() > kMaxHistory)
        m_history.removeLast();
    save();
    Q_EMIT historyChanged();
    Q_EMIT logged(key, out, result, seconds);
}

void CallManager::pruneHistory(const std::function<qint64(const QString &)> &cutoff)
{
    if (!cutoff)
        return;
    QVariantList kept;
    for (const QVariant &v : std::as_const(m_history)) {
        const QVariantMap e = v.toMap();
        if (e.value(QStringLiteral("started")).toLongLong() >= cutoff(e.value(QStringLiteral("key")).toString()))
            kept << v;
    }
    if (kept.size() == m_history.size())
        return;
    m_history = kept;
    save();
    Q_EMIT historyChanged();
}

void CallManager::load()
{
    if (!m_vault || m_vault->locked())
        return;
    const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QString::fromLatin1(kFile))).object();
    // Calls logged while the vault was locked come first, then what was saved.
    QVariantList saved = o.value(QStringLiteral("calls")).toArray().toVariantList();
    if (!m_loaded)
        m_history.append(saved);
    m_seen = qMax(m_seen, qint64(o.value(QStringLiteral("seen")).toDouble()));
    m_loaded = true;
    save();
    Q_EMIT historyChanged();
}

void CallManager::save()
{
    if (!m_vault || m_vault->locked() || !m_loaded)
        return;
    const QJsonObject o{{QStringLiteral("seen"), double(m_seen)}, {QStringLiteral("calls"), QJsonArray::fromVariantList(m_history)}};
    m_vault->write(QString::fromLatin1(kFile), QJsonDocument(o).toJson(QJsonDocument::Compact));
}
