#pragma once

#include <QJsonObject>
#include <QElapsedTimer>
#include <QObject>
#include <QSet>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <functional>

#include "callsession.h"   // CallSkewGate

class CallSession;
class CallSignal;
#include <QVideoSink>
class VideoReceiver;
class VideoSender;
class Chime;
class RelayCredentials;
class Vault;
struct TurnLogin;

// Calls for QML (registered as the "Calls" singleton): one 1:1 call at a time, the ringtone and the
// desktop notification for an incoming call, and the call history (kept encrypted in the Vault as
// calls.json, like everything else in Chat). No IP address ever reaches anything shown here.
class CallManager : public QObject
{
    Q_OBJECT
    // "idle", "calling", "ringing" (they're being rung), "incoming", "connecting", "active",
    // "reconnecting", "ended" (shown for a moment, then idle again).
    Q_PROPERTY(QString state READ state NOTIFY changed)
    Q_PROPERTY(bool inCall READ inCall NOTIFY changed)
    Q_PROPERTY(QString peerKey READ peerKey NOTIFY changed)
    Q_PROPERTY(QString peerName READ peerName NOTIFY changed)
    Q_PROPERTY(QString statusText READ statusText NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(bool muted READ muted NOTIFY changed)
    Q_PROPERTY(bool peerMuted READ peerMuted NOTIFY changed)
    Q_PROPERTY(bool peerSpeaking READ peerSpeaking NOTIFY changed)
    Q_PROPERTY(bool relayLive READ relayLive NOTIFY changed)
    Q_PROPERTY(bool wifiLive READ wifiLive NOTIFY changed)
    Q_PROPERTY(bool bleLive READ bleLive NOTIFY changed)
    // The direct internet path between two people who trust each other (shared/docs/protocol.md 5.8.2).
    Q_PROPERTY(bool directLive READ directLive NOTIFY changed)
    Q_PROPERTY(bool directTried READ directTried NOTIFY changed)
    Q_PROPERTY(QString outputName READ outputName NOTIFY changed)
    // Video (shared/docs/protocol.md 5.8.5): this build can do it; this call can (both c2 with VP8, answered);
    // the camera is on; the other person's camera is on; the turn that shows their picture upright;
    // an incoming call that started with video.
    Q_PROPERTY(bool videoAvailable READ videoAvailable CONSTANT)
    Q_PROPERTY(bool videoPossible READ videoPossible NOTIFY changed)
    Q_PROPERTY(bool cameraOn READ cameraOn NOTIFY changed)
    Q_PROPERTY(bool peerCamera READ peerCamera NOTIFY changed)
    Q_PROPERTY(int peerRotation READ peerRotation NOTIFY changed)
    Q_PROPERTY(bool incomingVideo READ incomingVideo NOTIFY changed)
    Q_PROPERTY(QString cameraName READ cameraName NOTIFY changed)
    Q_PROPERTY(QVariantList history READ history NOTIFY historyChanged)
    Q_PROPERTY(bool available READ available CONSTANT)

public:
    CallManager(CallSignal *signal, Vault *vault, QObject *parent = nullptr);
    ~CallManager() override;

    QString state() const;
    bool inCall() const;
    QString peerKey() const;
    QString peerName() const;
    QString statusText() const;
    int elapsed() const;
    bool muted() const;
    bool peerMuted() const;
    bool peerSpeaking() const;
    bool relayLive() const;
    bool wifiLive() const;
    bool bleLive() const;
    bool directLive() const;
    bool directTried() const;
    QString outputName() const;
    bool videoAvailable() const;
    bool videoPossible() const;
    bool cameraOn() const { return m_cameraOn; }
    bool peerCamera() const;
    int peerRotation() const;
    bool incomingVideo() const;
    QString cameraName() const;
    QVariantList history() const { return m_history; }
    bool available() const;

    // "" when the call is starting, otherwise what to tell the person.
    Q_INVOKABLE QString call(const QString &key);
    // A video call: the camera is on from the start (asks nothing first: Windows has no camera prompt).
    Q_INVOKABLE QString callVideo(const QString &key);
    Q_INVOKABLE bool canVideo(const QString &key) const;
    Q_INVOKABLE void answerVideo();
    Q_INVOKABLE void toggleCamera();
    Q_INVOKABLE void nextCamera();
    // The VideoOutputs of the call page: their picture, and the small self view.
    Q_INVOKABLE void setRemoteSink(QObject *videoSink);
    Q_INVOKABLE void setLocalSink(QObject *videoSink);
    Q_INVOKABLE bool canCall(const QString &key) const;
    // A conversation with key is open: fetch the relay login now (cached for two hours), so a call
    // doesn't wait for the relay Worker. At most one try a minute.
    Q_INVOKABLE void prepare(const QString &key);
    Q_INVOKABLE void answer();
    Q_INVOKABLE void decline();
    Q_INVOKABLE void hangup();
    Q_INVOKABLE void toggleMute();
    Q_INVOKABLE void nextOutput();
    Q_INVOKABLE QString duration(int seconds) const;
    // Calls older than their chat's message limit go (cutoff(key): the oldest time kept, in ms).
    // main.cpp runs it whenever Chat prunes messages.
    void pruneHistory(const std::function<qint64(const QString &)> &cutoff);
    // Trusted contacts are called directly over the internet when both trust each other (saves the
    // relay's data); everyone else only through the relay, so they never learn your IP address.
    // Default: everyone in the Family folder. The switch overrides it, on or off.
    Q_INVOKABLE bool trusted(const QString &key) const;
    Q_INVOKABLE void setTrusted(const QString &key, bool on);

    // Set by main.cpp: true while this device is in a voice room (1:1 offers then get busy, 6.6).
    std::function<bool()> busyElsewhere;
    // Set by main.cpp: their chat is muted (and not "Always right away"). A call from them rings silently: it shows here
    // and can be answered, without a sound or a notification (unanswered, it is a missed call in their chat).
    std::function<bool(const QString &key)> hushedFor;
    // Cap b1 (shared/docs/protocol.md section 23): a ring envelope opened while Huginn is locked. It rings with
    // "Incoming call" and no name (nothing else is known). Answer or Decline on the notification is kept, and the
    // offer itself, read once Huginn is unlocked, follows it.
    void ringLocked(const QString &callId, qint64 ts);
    // A TURN login from the relay Worker (also used by voice rooms, so one cache serves both).
    void getLogin(std::function<void(const TurnLogin &, const QString &)> done);
    QByteArray relayId();

Q_SIGNALS:
    void changed();
    void tick();
    void historyChanged();
    // A 1:1 call ended and went into the history (result: "answered", "missed" or how it ended): main.cpp puts it in
    // that chat's timeline, and a missed one raises a notification that opens the chat.
    void logged(const QString &key, bool out, const QString &result, int seconds);
    void incoming(const QString &name);
    void finished(const QString &text);   // for a toast
    void trustChanged(const QString &key, bool on);   // the rooms' sessions follow it too

private:
    void onSignal(const QString &from, const QJsonObject &msg);
    void adopt(CallSession *s);
    void sessionEnded();
    void addHistory(const QString &key, bool out, const QString &result, qint64 started, int seconds);
    void load();
    void save();
    void ring(bool on);
    void notifyIncoming(bool on);
    void notifyLocked(bool on);
    void endLockedRing();
    QString m_lockedCall;          // cap b1: the call ringing (or rung) while locked
    int m_lockedChoice = 0;        // 0 nothing yet, 1 answer, 2 decline
    bool m_lockedRinging = false;
    QTimer m_lockedTimer;
    void onNotificationAction(const QString &action);   // a button on the ringing notification

    CallSignal *m_signal;
    QPointer<Vault> m_vault;
    RelayCredentials *m_relay;
    QPointer<CallSession> m_session;
    QString m_lastPeer, m_endText;
    QTimer m_ticker, m_clear, m_ringer;
    Chime *m_ringtone = nullptr;   // never QSoundEffect: it can crash on Windows (see Chime)
    QVariantList m_history;      // newest first: {id, key, name, out, result, started, seconds, missed}
    qint64 m_started = 0;
    qint64 m_preparedAt = 0;
    uint m_notification = 0;
    QSet<QString> m_handled;     // call ids already answered with busy or logged as missed
    CallSkewGate m_skew;         // round 14: offers from a clock more than a minute off (5.2)
    QElapsedTimer m_mono;        // its monotonic clock
    bool m_loaded = false;
    QSet<QString> m_trustOn, m_trustOff;   // the switch, where it was set (calltrust.json in the Vault)
    void loadTrust();
    void saveTrust();
    void prime(CallSession *s);  // trust and codecs for a new session
    QString startCall(const QString &key, bool video);
    void startVideo();           // the session's media is up: show their picture, send ours if the camera is on
    void setCamera(bool on);
    void stopVideo();
    bool m_cameraOn = false;
    VideoSender *m_camera = nullptr;
    VideoReceiver *m_remote = nullptr;
    QPointer<QVideoSink> m_remoteSink, m_localSink;
    QTimer m_videoRate;
};
