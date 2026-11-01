#pragma once

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <functional>

class Chat;

// Linked devices, companion style (shared/docs/protocol.md section 15), the same as Muninn's Link.kt.
// A companion shows a phone's 1:1 chats and sends through it while the phone is online; contacts see
// nothing new. Huginn can be either end:
//  - a companion: it shows a link code (MUNINNL1:, its chat key, mailbox and a token good for 10
//    minutes and once) as a QR code; the phone takes it and answers t "hello". After the 4 emoji are
//    checked, "chat" pages and "msg" mirror every message the phone keeps, and "send" asks the phone to
//    send as itself. Photos, voice, video and files come later, only over the home Wi-Fi ("get", "part").
//  - the phone: it takes a companion's code (Link a device) and mirrors its own 1:1 chats to it.
// Room "link" is sealed like a private message; Chat hands it here (Chat::receiveExtra), and anything
// from a device that isn't linked is answered 403.
// While a phone is linked, its chats show in their own section beside this device's own chats, groups, folders and
// contacts, which all stay on show; the phone's name, picture and status are "you" on this screen (me).
class Link : public QObject
{
    Q_OBJECT
    // On a companion: the phone it mirrors, {key, name, ok}, or empty.
    Q_PROPERTY(QVariantMap phone READ phone NOTIFY changed)
    // On the phone: the devices that mirror it, [{key, name, ok}] (ok: the 4 emoji were checked).
    Q_PROPERTY(QVariantList companions READ companions NOTIFY changed)
    // On a companion: the phone's chats, newest first: [{peer, name, last, time}].
    Q_PROPERTY(QVariantList mirror READ mirror NOTIFY changed)
    // The link code being shown here (empty until newCode), and when it stops working (ms).
    Q_PROPERTY(QString code READ code NOTIFY changed)
    Q_PROPERTY(qint64 codeUntil READ codeUntil NOTIFY changed)
    // Always false: this device's own chats are never put away while a phone is linked (kept for the pages that ask).
    Q_PROPERTY(bool sealed READ sealed NOTIFY changed)
    // On a companion: the phone's own profile, shown here as "you" while it's linked and checked:
    // {name, status, custom, picture (bool)}; empty when no checked phone is linked or it hasn't said yet.
    Q_PROPERTY(QVariantMap me READ me NOTIFY changed)

public:
    static const char kPrefix[];                       // "MUNINNL1:"
    static constexpr qint64 kTokenMs = 10 * 60000;
    static constexpr qint64 kRefreshMs = 60000;        // a new code this long before the old one runs out
    static constexpr int kPageMsgs = 100;              // messages in one "chat" page
    static constexpr int kPageBytes = 20 * 1024;       // and its JSON ("msgs", UTF-8) at most this many bytes
    static constexpr int kText = 2000;                 // characters of "x"
    static constexpr int kPart = 192 * 1024;           // bytes of a file in one "part"
    static constexpr qint64 kMediaMax = 100LL * 1024 * 1024;

    explicit Link(Chat *chat, QObject *parent = nullptr);

    QVariantMap phone() const;
    QVariantList companions() const;
    QVariantList mirror() const;
    QString code() const { return m_code; }
    qint64 codeUntil() const { return m_tokenUntil; }
    bool sealed() const;
    QVariantMap me() const;
    QByteArray mePicture() const;           // the phone's picture (JPEG), empty when none
    QString meStatus() const;               // the phone's status ("online", "away", "dnd", "invisible"), "" when none

    // A new link code for a phone to take (a new token each time).
    Q_INVOKABLE QString newCode();
    // The code on show, or a new one when there is none or it has less than a minute left.
    Q_INVOKABLE QString freshCode();
    // On the phone: link the device whose code this is. "" when done, else what to tell the person.
    // Nothing is mirrored until both screens showed the same 4 emoji and the person said so (confirm); confirmed only
    // for a device already proven (moving, section 18).
    Q_INVOKABLE QString link(const QString &code, bool confirmed = false);
    // The linked devices whose emoji haven't been checked yet: [{key, name, emoji}] (a companion, or the phone).
    Q_INVOKABLE QVariantList unchecked() const;
    Q_INVOKABLE void confirm(const QString &key);
    Q_INVOKABLE void unlink(const QString &key);
    // On a companion: one mirrored chat's messages, oldest first:
    // [{id, text, mine, time, kind ("" or photo, voice, video, file), media (an id for Chat.blob, "" until it's here), name, size}].
    Q_INVOKABLE QVariantList messages(const QString &peer) const;
    // On a companion: a photo, voice, video or file that came from the phone, saved in Downloads. "" when done,
    // else what to tell the person.
    Q_INVOKABLE QString saveMedia(const QString &peer, const QString &id) const;
    // On a companion: send through the phone. False when no phone is linked.
    Q_INVOKABLE bool send(const QString &peer, const QString &text);
    Q_INVOKABLE void sync();
    // On a companion: this device's own chats while a phone is linked. openOwn checks the app lock (pattern or
    // password; anything when there's no lock) and shows them until the screen locks or closeOwn().
    Q_INVOKABLE bool ownChats() const;
    // On a companion: a new name, status or custom status for the phone (the account), sent to it as "setme" and
    // shown here at once. "" when sent, else what to tell the person.
    Q_INVOKABLE QString setMe(const QString &name, const QString &status, const QString &custom);
    Q_INVOKABLE bool openOwn(const QString &secret);
    Q_INVOKABLE void closeOwn();

    // Room "link" from fromKey, opened; via is the route it came on ("wifi", "bluetooth" or "internet").
    // False: not from a device linked with this one.
    bool receive(const QString &fromKey, const QJsonObject &p, const QString &via);
    // On the phone: a message in or out of a 1:1 chat (Chat::addPrivate), for the companions.
    void onMessage(const QString &peer, const QVariantMap &m);
    // On a companion: messages past each chat's limit (cap d1, the phone's "d") go, as on the phone.
    void prune(qint64 now);
    // Set by main.cpp: true while this device is on its usual Wi-Fi (not its own Direct connect hotspot).
    std::function<bool()> homeWifi;
    // Set by main.cpp, on the phone's side: my profile for "me" ({n, st, cs, pic (base64 JPEG, omitted when none)}),
    // and what a companion's "setme" changes here (name, status, custom status; an empty one stays as it is).
    std::function<QJsonObject()> myProfile;
    std::function<void(const QString &, const QString &, const QString &)> setMyProfile;
    // On the phone: my profile to every checked companion, when it changed since it last went (force: anyway).
    void sendMe(bool force = false);
    // A "me" frame as the companion takes it: {name, status, custom, pic (JPEG bytes)}, or empty when it isn't one.
    static QVariantMap readMe(const QJsonObject &p);

    // A link code's {k, p, n, i}; empty when it isn't one.
    static QJsonObject readCode(const QString &text);
    static bool selfTest(const QJsonObject &vectors);

    // The rules, separate so the self-test can check them.
    struct M {
        QString id, text;
        bool mine = false;
        qint64 time = 0;
        QString hash, kind, name;           // a photo, voice, video or file: its SHA-256 (hex), kind and name
        qint64 size = 0;
    };
    // Merges a page into a chat's messages by id (a later copy wins), oldest first; never drops one that's there.
    static void merge(QList<M> &into, const QList<M> &page);
    // Messages (as sent) into "chat" pages: at most kPageMsgs each and kPageBytes of UTF-8 JSON, oldest first.
    static QList<QJsonArray> pages(const QJsonArray &msgs);
    // A file that came in parts is the one named: its size and SHA-256 match.
    static bool mediaOk(const QByteArray &bytes, const QString &hash, qint64 size);
    // "get" and "part" only on the home Wi-Fi: never over the internet, Bluetooth or Direct connect.
    static bool lanOnly(const QString &via, bool homeNet);
    // How many parts a file of size bytes takes (0 when it's empty or too big).
    static int partsFor(qint64 size);
    // A file name from the phone made safe to save here: no folder, no character Windows or Linux refuses,
    // not hidden, not a name Windows keeps for itself, at most 120 characters. fallback when nothing is left.
    static QString safeName(const QString &name, const QString &fallback);
    // A code shown until it has less than kRefreshMs left, then a new one.
    static bool codeDue(qint64 at, qint64 until);

Q_SIGNALS:
    void changed();

private:
    struct Device {
        QString key, mailbox, name;
        bool ok = true;                     // the 4 emoji were checked (true when stored without "ok")
    };
    struct Mirror {
        QString peer, name;
        QList<M> msgs;
        int days = 7;                       // the phone's limit for this chat (Limits::kDefault)
    };
    void ensureLoaded() const;
    void save();
    void saveMirror();
    void sendTo(const Device &d, const QJsonObject &msg);
    bool sendLan(const QString &key, const QJsonObject &msg, std::function<void(int)> done = {});
    bool home() const { return !homeWifi || homeWifi(); }
    void syncAll(const Device *only);
    QJsonObject mJson(const QVariantMap &m, int max);
    static M mOf(const QJsonObject &o);
    static QJsonObject fJson(const M &m);
    static int daysOf(const QJsonObject &o, int fallback);

    // The phone's side of media: SHA-256 and size of each photo id it has sent (kept in the vault, link-sums.json),
    // worked out on a worker thread; and the files being sent now.
    struct Sum {
        QString hash;
        qint64 size = 0;
    };
    Sum sumOf(const QString &photoId);       // known sums only, never reads the file
    QString photoWith(const QString &hash);
    void loadSums();
    void saveSums(QSet<QString> live);
    // True when the sums of all these photo ids are known (or can't be had); else they're worked out off the
    // GUI thread and then() runs once none are left to do.
    bool sumsReady(const QStringList &photos, std::function<void()> then);
    bool m_sumsLoaded = false;
    QSet<QString> m_hashing;
    QHash<QString, qint64> m_noSum;     // photo ids with no sum to be had, and when that was found (tried again after a minute)
    QList<std::function<void()>> m_afterSums;
    struct Serve {
        QString to, hash, photo;
    };
    QHash<QString, Sum> m_sums;
    QList<Serve> m_serve;
    QByteArray m_serveBytes;
    int m_serveAt = -1;
    void serveNext();
    void servePart();

    // The companion's side: files to fetch from the phone and the one coming in.
    static QString mediaId(const QString &hash) { return QStringLiteral("lm-") + hash; }
    bool haveMedia(const QString &hash) const;
    void want(const M &m);
    void fetchNext();
    void dropMedia();
    QSet<QString> m_want, m_failed;
    QHash<QString, qint64> m_sizes;
    QString m_fetching;
    qint64 m_fetchAt = 0;
    QList<QByteArray> m_parts;
    int m_partsHave = 0;
    QTimer m_fetchTimer;

    Chat *m_chat;
    mutable bool m_loaded = false;
    mutable QList<Device> m_companions;
    mutable Device m_phone;                 // key empty: none
    mutable QHash<QString, Mirror> m_mirror;
    QString m_token, m_code;
    qint64 m_tokenUntil = 0;
    QHash<QString, qint64> m_tokens;        // every code shown that hasn't run out: a refreshed one leaves the last one working
    bool m_ownOpen = false;
    // The companion's side of "me" (in link.json).
    mutable QString m_meName, m_meStatus, m_meCustom;
    mutable QByteArray m_mePic;
    void forgetPhone();                     // the phone unlinked or was unlinked: what came with it goes
    // The phone's side: the last "me" sent (compact JSON), so a change goes once.
    QByteArray m_meSent;
    QTimer m_meSoon;
    // Ids of companions' "send" already sent (two days, on disk), so none goes out twice.
    bool firstSend(const QString &id);
    QHash<QString, qint64> m_sends;
    bool m_sendsLoaded = false;
};
