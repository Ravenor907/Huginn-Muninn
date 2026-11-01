#pragma once

#include <QByteArray>
#include <QHash>
#include <QImage>
#include <QJsonObject>
#include <QObject>
#include <QQuickImageProvider>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <QUrl>

class Chat;
class Vault;

// Cap p1 (shared/docs/protocol.md, section 22), the same as Muninn's net/Profile.kt: profile pictures and status.
//  - Room "profile" {t:"profile", v, pic, col}: my picture (a 256x256 JPEG, base64) and colour, sealed through the
//    ratchet, to every contact and everyone in a group with me whose app said p1, once per person per version.
//    A receiver takes at most 24 KB; a sender keeps it at 12 KB or less so one relay event carries it.
//  - Room "live" "active" carries "st" (online, away or dnd) and "cs" (a custom status, at most 80 characters);
//    invisible is sent as not active with no "st". RichWire checks the fields.
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
    // The same by the id in a picture URL ("me", or the hash of a key), for the image provider.
    QImage pictureById(const QString &id);

    // Chat's side: a fresh "active" from key (room "live"), a room "profile" message from a contact or group peer
    // (false when malformed; an older version is taken and changes nothing), someone removed for good, and the
    // message that says my status (sendActive).
    void tookLive(const QString &key, bool on, const QJsonObject &p);
    bool receive(const QString &fromKey, const QJsonObject &p);
    void forget(const QString &key);
    QByteArray activeMsg(bool on, qint64 ts) const;
    bool invisible() const { return m_status == QLatin1String("invisible"); }

    // The wire parts, shared with the self-test.
    static bool colourOk(const QJsonValue &v);
    static QSize jpegSize(const QByteArray &b);        // from the frame header; invalid when it isn't a JPEG
    static bool pictureOk(const QByteArray &b);        // at most kMaxBytes, a JPEG, exactly kSide x kSide
    static bool ok(const QJsonObject &p);              // a room "profile" payload
    static QByteArray msg(qint64 v, const QByteArray &pic, const QString &col);
    static QString defaultColour(const QString &key);
    static QString shownStatus(bool active, const QString &st, bool heard);
    static QString cleanCustom(const QString &s);
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
    void change(const Entry &e);
    void reconcileSoon();
    void reconcile();
    void liveChanged();
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
    QHash<QString, QPair<QString, QString>> m_statuses;   // key -> (st, cs) while their app is open
    QSet<QString> m_heard;                                 // keys a live active ever came from
    QSet<QString> m_wasActive;
    QHash<QString, QImage> m_cache;
    QTimer m_soon, m_every;
};

// "image://profile/<key>?v=<n>": the picture of key ("me" for mine). The vault is only read on the main thread.
class ProfileImageProvider : public QQuickImageProvider
{
public:
    explicit ProfileImageProvider(Profile *profile);
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

private:
    Profile *m_profile;
};
