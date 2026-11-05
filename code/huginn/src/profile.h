#pragma once

#include <QByteArray>
#include <QHash>
#include <QImage>
#include <QJsonObject>
#include <QMutex>
#include <QObject>
#include <QQuickImageProvider>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <QUrl>
#include <functional>

class Chat;
class Vault;

// Cap p1 (shared/docs/protocol.md, section 22), the same as Muninn's net/Profile.kt: profile pictures and status.
//  - Room "profile" {t:"profile", v, pic, col}: my picture (a 256x256 JPEG, base64) and colour, sealed through the
//    ratchet, to every contact and everyone in a group with me whose app said p1, once per person per version.
//    A receiver takes at most 24 KB; a sender keeps it at 12 KB or less so one relay event carries it.
//  - Room "live" "active" carries "st" (online, away or dnd) and "cs" (a custom status, at most 80 characters);
//    invisible is sent as not active with no "st". RichWire checks the fields.
//  - Cap p2 (22.4): "pv" (my version) on every envelope to an app that says p2, so one that missed my picture asks
//    ({"t":"profile?"}); a picture is answered {"t":"profile-ok","v"}, and to a p2 app it counts as sent only then
//    (until then it goes again after 2, 4, 8 ... minutes). Refresh on a person's page asks them (askPicture).
//    Round 16: "profile?" says the version the asker keeps ("pv"); it is answered once per version a day, only when
//    older; a restart sends again only a day after the last send; at most kDayMax pictures a day in all.
// Everything kept is in the vault (profile/me.json, profile/<hash>.json, profile/sent.json), so a backup and a move
// carry it and Erase everything wipes it. shared/vectors/p1-vectors.json checks the wire parts (selfTest).
class Profile : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString myStatus READ myStatus WRITE setStatus NOTIFY changed)
    Q_PROPERTY(QString myCustomStatus READ myCustomStatus WRITE setCustomStatus NOTIFY changed)
    Q_PROPERTY(bool hasMyPicture READ hasMyPicture NOTIFY changed)
    Q_PROPERTY(QString myColour READ myColour WRITE setMyColour NOTIFY changed)

public:
    Profile(Chat *chat, Vault *vault, QObject *parent = nullptr);

    static const char kCap[];              // "p1"
    static const int kMaxBytes = 24 * 1024;   // a received picture, at most
    static const int kSendMaxBytes = 12 * 1024;   // what a sender keeps under, so one relay event carries it
    static const int kSide = 256;
    static const qint64 kRateMs = 10000;   // one profile message per sender in this long
    // Cap p2 (22.4).
    static const char kCap2[];             // "p2"
    static const qint64 kAskMs = 5 * 60000;   // at most one "profile?" to a person in this long (from a "pv")
    static const qint64 kRefreshMs = 10000;   // Refresh by hand: no sooner than this after the last ask
    // Round 16: a "profile?" is answered at most once per version of mine a day, and only when the asker says it keeps
    // an older one; pictures after a restart wait a day after the last one to that person; at most kDayMax a day in all.
    static const qint64 kDayMs = 24LL * 3600 * 1000;
    static const int kDayMax = 200;
    static const int kCtlPer10s = 5;          // "profile?" and "profile-ok" per sender in 10 s (apart from pictures)
    static const qint64 kFirstRetryMs = 2 * 60000;          // no "profile-ok": again after 2, 4, 8 ... minutes,
    static const qint64 kMaxRetryMs = 6LL * 3600 * 1000;    // at most 6 hours apart,
    static const int kMaxTries = 10;                        // at most this many times

    // Mine: online, away, dnd or invisible; the custom text (at most 80 characters); the colour of my initial disc.
    QString myStatus() const { return m_status; }
    QString myCustomStatus() const { return m_custom; }
    Q_INVOKABLE void setStatus(const QString &s);
    Q_INVOKABLE void setCustomStatus(const QString &s);
    bool hasMyPicture() const { return !m_me.pic.isEmpty(); }
    QString myColour() const;
    Q_INVOKABLE void setMyColour(const QString &hex);
    // Crops to the centre square, scales to 256x256, encodes small enough to send, stores and sends it.
    // False when the picture can't be made small enough (or is empty).
    Q_INVOKABLE bool setMyPicture(const QImage &image);
    Q_INVOKABLE void clearMyPicture();

    // Everyone: the status to show ("online", "away", "dnd", "offline", or "" when nothing is known; "me" is mine),
    // and the colour for their initial disc (the one they sent, else one from their key).
    Q_INVOKABLE QString statusOf(const QString &key) const;
    Q_INVOKABLE QString colourOf(const QString &key) const;
    // image://profile/<key>?v=<n> when they have a picture here, else an empty URL. "me" for mine.
    Q_INVOKABLE QUrl pictureUrl(const QString &key) const;
    Q_INVOKABLE int pictureVersion(const QString &key) const;
    // The picture itself (decoded once and kept), null when there is none.
    QImage picture(const QString &key);
    // The same by the id in a picture URL ("me", "phone", or the hash of a key).
    QImage pictureById(const QString &id);
    // For the image provider, from any thread: the JPEG behind an id in a picture URL, from a copy made on the main
    // thread whenever a picture changes. Never waits on the main thread.
    QByteArray sharedPicture(const QString &id) const;

    // Chat's side: a fresh "active" from key (room "live"), a room "profile" message from a contact or group peer
    // (false when malformed; an older version is taken and changes nothing), someone removed for good, and the
    // message that says my status (sendActive).
    void tookLive(const QString &key, bool on, const QJsonObject &p);
    bool receive(const QString &fromKey, const QJsonObject &p);
    void forget(const QString &key);
    QByteArray activeMsg(bool on, qint64 ts) const;
    bool invisible() const { return m_status == QLatin1String("invisible"); }
    // While a linked phone is the account here (Link::sealed): its picture (a JPEG as pictureOk, or empty) and status show
    // as mine on this screen ("me" in pictureUrl, picture and statusOf). Nothing of it is ever sent from here. on false:
    // my own again.
    void setPhoneMe(bool on, const QByteArray &pic, const QString &status);
    QByteArray myPictureJpeg() const { return m_me.pic; }   // my own picture as sent (empty when none)
    // Cap l1 (protocol.md 15.1): a linked phone's contacts' pictures (key to JPEG), shown for anyone with none of their own
    // here. Nothing of them is ever sent from here.
    void setLinkedPictures(const QHash<QString, QByteArray> &pics);

    // Cap p2 (22.4). My version for "pv" (0: none, and no "pv" goes); an envelope from key that opened said "pv"; key
    // asked for mine ("profile?"); a picture from key was taken (it hears "profile-ok"); key says mine came.
    qint64 myVersion() const { return m_me.v; }
    bool saysP2(const QString &key) const;
    void sawVersion(const QString &key, qint64 pv);
    void askedBy(const QString &key, qint64 theirs);
    void ack(const QString &key, qint64 v);
    void acked(const QString &key, qint64 v);
    // Refresh on a person's page: asks them for their current picture (through a linked phone while it is the account
    // here). Says what happened, for a toast.
    Q_INVOKABLE QString askPicture(const QString &key);
    // Set by main: a linked phone asks for us ("pask", cap l1); false when no phone takes it.
    std::function<bool(const QString &)> askPhone;

    // The wire parts, shared with the self-test.
    static bool colourOk(const QJsonValue &v);
    static QSize jpegSize(const QByteArray &b);        // from the frame header; invalid when it isn't a JPEG
    static bool pictureOk(const QByteArray &b);        // at most kMaxBytes, a JPEG, exactly kSide x kSide
    static bool ok(const QJsonObject &p);              // a room "profile" payload
    static QByteArray msg(qint64 v, const QByteArray &pic, const QString &col);
    static QString defaultColour(const QString &key);
    static QString shownStatus(bool active, const QString &st, bool heard);
    static QString cleanCustom(const QString &s);
    // Cap p2: a usable version ("v" or "pv": a whole number, 1 <= v < 2^53), else 0; the small messages; the rules.
    static qint64 versionOf(const QJsonValue &v);
    static QByteArray askMsg(qint64 have);            // "profile?" saying the version of theirs kept here (0: none)
    static qint64 askedPv(const QJsonObject &p);       // the version an asker says it keeps; 0 when none or unsaid
    static bool mayAnswer(qint64 mine, qint64 theirs, qint64 lastV, qint64 lastAt, qint64 now);
    static QByteArray ackMsg(qint64 v);
    static bool isCtl(const QJsonObject &p);           // "profile?" or "profile-ok"
    static bool ctlOk(const QJsonObject &p);           // well-formed "profile?" or "profile-ok"
    static bool mayAsk(qint64 last, qint64 now, qint64 gap);
    static qint64 retryMs(int tries);
    static bool due(int tries, qint64 at, qint64 now, bool old = false);   // old: the last send was before this start
    // Pictures sent on one day (days since 1970, UTC), at most kDayMax.
    struct DayBudget {
        qint64 day = 0;
        int n = 0;
        void roll(qint64 now);
        bool has(qint64 now);
        void spend(qint64 now);
    };
    static bool p2SelfTest();
    // The JPEG to send: the centre square at 256x256, the first quality of the ladder that fits, or empty.
    static QByteArray encode(const QImage &image);
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();
    void pictureChanged(const QString &key);
    void statusChanged(const QString &key);

private:
    struct Entry {
        qint64 v = 0;
        QString col;
        QByteArray pic;   // JPEG, empty when none
    };
    void load();
    void saveMe();
    void saveSent();
    void saveP2();
    void change(const Entry &e);
    void reconcileSoon();
    void reconcile();
    void liveChanged();
    void share();   // copies every picture into m_shared (main thread)
    static QString file(const QString &key);
    static QJsonObject toJson(const QString &key, const Entry &e);
    static Entry fromJson(const QJsonObject &o);

    Chat *m_chat;
    Vault *m_vault;
    bool m_loaded = false;
    Entry m_me;
    QString m_status = QStringLiteral("online"), m_custom;
    QHash<QString, Entry> m_peers;
    QHash<QString, qint64> m_sent;
    struct Try {
        qint64 v = 0;
        int n = 0;
        qint64 at = 0;
        bool old = false;   // counted before this start
    };
    // Cap p2: my version v sent n times with no "profile-ok", the last at at. m_tries, m_answered and m_budget are kept
    // in profile/p2.json, so a restart doesn't send everything again.
    QHash<QString, Try> m_tries;
    QHash<QString, qint64> m_asked;       // cap p2: when I last asked each person for theirs (memory only)
    struct Answer {
        qint64 v = 0;
        qint64 at = 0;
    };
    QHash<QString, Answer> m_answered;    // cap p2: the version of mine last sent because they asked, and when
    QSet<QString> m_answerNow;            // asked, and answered on the next pass whatever the retry wait
    DayBudget m_budget;                   // pictures sent today, everyone together
    bool inTouch(const QString &key) const;
    bool claim(QHash<QString, qint64> &map, const QString &key, qint64 gap);
    void sendSmall(const QString &key, const QByteArray &plain);
    QHash<QString, QPair<QString, QString>> m_statuses;   // key -> (st, cs) while their app is open
    QSet<QString> m_heard;                                 // keys a live active ever came from
    QSet<QString> m_wasActive;
    QHash<QString, QImage> m_cache;
    QTimer m_soon, m_every;
    bool m_phoneOn = false;
    QByteArray m_phonePic;
    QHash<QString, QByteArray> m_linked;   // setLinkedPictures
    qint64 m_linkedV = 0;
    QString m_phoneStatus;
    qint64 m_phoneV = 0;
    mutable QMutex m_sharedLock;
    QHash<QString, QByteArray> m_shared;   // id in a picture URL -> JPEG, read by the image provider's thread
};

// "image://profile/<key>?v=<n>": the picture of key ("me" for mine). Reads Profile::sharedPicture only, so a
// loading thread never waits on the main thread (which could hang Huginn while it closes).
class ProfileImageProvider : public QQuickImageProvider
{
public:
    explicit ProfileImageProvider(Profile *profile);
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

private:
    Profile *m_profile;
};
