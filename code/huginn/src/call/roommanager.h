#pragma once

#include "audiodsp.h"
#include "roomwire.h"

#include <QElapsedTimer>
#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QTimer>
#include <QVariantList>
#include <functional>

class AudioIO;
class CallManager;
class CallSession;
class CallSignal;
class Chime;
#include <QVideoSink>
class VideoReceiver;
class VideoSender;
struct TurnLogin;

// Voice rooms and group calls for QML (the "Rooms" singleton), shared/docs/protocol.md section 6.
// A room is a full mesh of 1:1 calls: one CallSession per other person (each with its own keys,
// TURN allocation and paths, so media is encrypted per recipient and nobody learns an IP address).
// This class owns the microphone and the speaker for all of them: each 20 ms frame from the
// microphone goes to every peer's media, and each peer's jitter buffer and decoder give a frame
// that is mixed into the speaker. Signaling is room "call" through Chat, like 1:1 calls.
class RoomManager : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool inRoom READ inRoom NOTIFY changed)
    Q_PROPERTY(QString group READ group NOTIFY changed)
    Q_PROPERTY(QString room READ room NOTIFY changed)
    Q_PROPERTY(bool groupCall READ groupCall NOTIFY changed)
    Q_PROPERTY(QString title READ title NOTIFY changed)          // "Hangout", or "Group call"
    Q_PROPERTY(QString groupName READ groupName NOTIFY changed)
    // [{key, name, me, speaking, muted, state}] with me first; state "connecting", "live", "reconnecting".
    Q_PROPERTY(QVariantList people READ people NOTIFY changed)
    Q_PROPERTY(bool muted READ muted NOTIFY changed)
    // Deafened: nobody is heard here (and the microphone is muted while it lasts).
    Q_PROPERTY(bool deafened READ deafened NOTIFY changed)
    // Push to talk (Settings): the microphone only sends while the key is held. talking: held now.
    Q_PROPERTY(bool pushToTalk READ pushToTalk WRITE setPushToTalk NOTIFY changed)
    Q_PROPERTY(bool talking READ talking NOTIFY changed)
    Q_PROPERTY(QString statusText READ statusText NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(QString outputName READ outputName NOTIFY changed)
    Q_PROPERTY(QString note READ note NOTIFY changed)
    Q_PROPERTY(bool ringing READ ringing NOTIFY changed)
    Q_PROPERTY(QString ringName READ ringName NOTIFY changed)     // who is calling
    Q_PROPERTY(QString ringGroup READ ringGroup NOTIFY changed)   // the group's name
    Q_PROPERTY(int presence READ presenceSerial NOTIFY presenceChanged)   // bumps when whoIn() may differ
    Q_PROPERTY(int maxPeople READ maxPeople CONSTANT)
    // Screen share (shared/docs/protocol.md 5.8.7): this build can share; this computer shares now; the
    // name of someone else who shares ("" when nobody does).
    Q_PROPERTY(bool canShare READ canShare CONSTANT)
    Q_PROPERTY(bool sharing READ sharing NOTIFY changed)
    Q_PROPERTY(QString sharerName READ sharerName NOTIFY changed)

public:
    using LoginFetcher = std::function<void(std::function<void(const TurnLogin &, const QString &)>)>;

    // Set by main.cpp.
    std::function<QJsonObject(const QString &g)> groupState;      // current state; empty when none or read-only
    std::function<bool(const QString &key)> canRoom;              // caps v1, or not known yet; not blocked
    std::function<bool(const QString &key)> lacksRooms;           // caps known, without v1
    std::function<QString(const QString &g, const QString &key)> memberName;

    RoomManager(CallSignal *signal, CallManager *calls, LoginFetcher login, QObject *parent = nullptr);
    ~RoomManager() override;

    bool inRoom() const { return !m_g.isEmpty(); }
    QString group() const { return m_g; }
    QString room() const { return m_ch; }
    bool groupCall() const { return RoomWire::isGroupCall(m_ch); }
    QString title() const;
    QString groupName() const;
    QVariantList people() const;
    bool muted() const { return m_muted; }
    bool deafened() const { return m_deaf; }
    bool pushToTalk() const { return m_ptt; }
    void setPushToTalk(bool on);
    bool talking() const { return m_talking; }
    QString statusText() const;
    int elapsed() const { return m_clock.isValid() ? int(m_clock.elapsed() / 1000) : 0; }
    QString outputName() const;
    QString note() const { return m_note; }
    bool ringing() const { return !m_ringG.isEmpty(); }
    QString ringName() const;
    QString ringGroup() const;
    int presenceSerial() const { return m_serial; }
    int maxPeople() const { return RoomWire::kMaxPeople; }
    bool canShare() const;
    bool sharing() const { return m_share != nullptr; }
    QString sharerName() const;

    // "" when joined, otherwise what to tell the person.
    Q_INVOKABLE QString join(const QString &g, const QString &ch);
    Q_INVOKABLE void leave();
    Q_INVOKABLE void toggleMute();
    Q_INVOKABLE void toggleDeafen();
    // The push to talk key went down or up (only while Huginn is in front).
    Q_INVOKABLE void setTalking(bool on);
    Q_INVOKABLE void nextOutput();
    // Rings keys (all members who can join when empty) into a new group call of g, and joins it.
    Q_INVOKABLE QString callGroup(const QString &g, const QStringList &keys = QStringList());
    Q_INVOKABLE QString answerRing();
    Q_INVOKABLE void declineRing();
    // Who is in a room right now: [{key, name, me, speaking}], for the voice room rows.
    Q_INVOKABLE QVariantList whoIn(const QString &g, const QString &ch) const;
    // Group calls going on in g: [{ch, count, names}].
    Q_INVOKABLE QVariantList groupCalls(const QString &g) const;
    // Members that can't take part yet (their app has no voice rooms).
    Q_INVOKABLE bool needsUpdate(const QString &key) const { return lacksRooms && lacksRooms(key); }
    Q_INVOKABLE bool isIn(const QString &g, const QString &ch) const { return !m_g.isEmpty() && m_g == g && m_ch == ch; }
    // Starts or stops sharing this computer's screen with everyone in the room.
    Q_INVOKABLE void toggleShare();
    // Where someone else's shared screen is shown (a QML VideoOutput's videoSink).
    Q_INVOKABLE void setScreenSink(QObject *videoSink);

Q_SIGNALS:
    void changed();
    void tick();
    void presenceChanged();
    void incoming(const QString &name, const QString &group);
    void finished(const QString &text);   // for a toast
    // A group call ended here, or one rang and was missed: main.cpp puts it in the group's timeline (a missed one
    // also raises a notification that opens the group).
    void logged(const QString &g, const QString &text, bool missed);

private:
    struct Peer {
        QPointer<CallSession> s;
        int retries = 0;
    };
    void onSignal(const QString &from, const QJsonObject &msg);
    void onRoomMessage(const QString &from, const QJsonObject &msg);
    void onRoomOffer(const QString &from, const QJsonObject &msg);
    CallSession *newSession(const QString &key);
    void offerTo(const QString &key, int retries = 0);
    void dropSession(const QString &key);
    void peerEnded(const QString &key, CallSession *s);
    QStringList members(const QString &g) const;   // others who can join rooms
    void sendRoom(const QString &t, const QString &key, const QString &g, const QString &ch);
    void announce(bool onlyUnconnected);
    void startRing(const QString &from, const QString &g, const QString &ch);
    void stopRing();
    void mix(qint16 *pcm);
    void onMic(const QByteArray &pcm);
    QString nameOf(const QString &g, const QString &key) const;
    QString me() const;
    int peerCount() const;
    static qint64 now();

    CallSignal *m_signal;
    QPointer<CallManager> m_calls;
    LoginFetcher m_login;
    QString m_g, m_ch, m_note, m_endText;
    qint64 m_joinedAt = 0;
    bool m_muted = false;
    bool m_deaf = false, m_mutedByDeaf = false;
    bool m_ptt = false, m_talking = false;
    QHash<QString, Peer> m_peers;
    QSet<QString> m_rung, m_declined;
    RoomWire::Presence m_presence;
    int m_serial = 0;
    AudioIO *m_audio = nullptr;
    AudioDsp::Speaking m_mySpeaking;
    QElapsedTimer m_clock;
    QTimer m_announce, m_ticker, m_presenceTick, m_clear, m_ringer;
    QString m_ringG, m_ringCh, m_ringFrom;
    qint64 m_ringSince = 0;
    Chime *m_ringtone = nullptr;   // never QSoundEffect: it can crash on Windows (see Chime)
    int m_gen = 0;   // bumps on every join, so timers of an earlier room do nothing
    // Screen share (5.8.7).
    void watchMedia(const QString &key, CallSession *s);
    void shareTo(CallSession *s, bool on);
    void pickSharer();
    void stopShare();
    VideoSender *m_share = nullptr;
    VideoReceiver *m_view = nullptr;
    QString m_sharer;
    QPointer<QVideoSink> m_screenSink;
    QTimer m_shareRate;
};
