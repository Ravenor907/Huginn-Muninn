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
// nothing new. Routes (section 15): history ("chat" pages), media ("get", "part") and a "me" with its picture go only over
// the home Wi-Fi (sendLan) and wait until both are on it; only small live frames use the internet, to at most 2 relays
// (Chat::sendLink). History goes on "They match", on linking again, and on Refresh (at most once in 10 minutes), never
// when a chat is opened. Huginn can be either end:
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
    // On a companion: the phone it mirrors, {key, name, ok, gone, l1, l2}, or empty. Cap l2 (15.2) adds how it's doing:
    // heard (ms: the last frame from it this run, 0 none), asked (ms: the last "ping" sent), answered (a "pong" came after
    // it), lan (it's found on this Wi-Fi now), home (this device is on its usual Wi-Fi). Never an address.
    Q_PROPERTY(QVariantMap phone READ phone NOTIFY changed)
    // On a companion: how many of the phone's chats have unread messages (each chat's count is in mirror).
    Q_PROPERTY(int unread READ unread NOTIFY changed)
    // On the phone: the devices that mirror it, [{key, name, ok}] (ok: the 4 emoji were checked).
    Q_PROPERTY(QVariantList companions READ companions NOTIFY changed)
    // On a companion: the phone's chats, newest first: [{peer, name, last, time, role, folders, unread, muted, group, chans}]
    // (chans: a group's text channels, [{id, name, ao}], from its signed state; empty without cap l1).
    // Cap l1: groups the account has with no messages here yet (just joined) are listed too.
    Q_PROPERTY(QVariantList mirror READ mirror NOTIFY changed)
    // The link code being shown here (empty until newCode), and when it stops working (ms).
    Q_PROPERTY(QString code READ code NOTIFY changed)
    Q_PROPERTY(qint64 codeUntil READ codeUntil NOTIFY changed)
    // Always false: this device's own chats are never put away while a phone is linked (kept for the pages that ask).
    Q_PROPERTY(bool sealed READ sealed NOTIFY changed)
    // On a companion: the phone's own profile, shown here as "you" while it's linked and checked:
    // {name, status, custom, picture (bool)}; empty when no checked phone is linked or it hasn't said yet.
    Q_PROPERTY(QVariantMap me READ me NOTIFY changed)
    // Cap l1 (section 15.1), on a companion: the phone's whole account, kept in step both ways:
    // {on (the phone takes part), contacts: [{key, name, nick, shown, verified, blocked, picture}],
    //  groups: [{id, name, role, gone, members: [{key, name, role}]}], folders: [{id, name, keys}]}.
    Q_PROPERTY(QVariantMap account READ account NOTIFY changed)
    // On a companion: half-written messages in the phone's chats, kept (in the vault) until sent or cleared:
    // {peer: {text, re}}. Their own signal, so typing never redraws the chats.
    Q_PROPERTY(QVariantMap drafts READ drafts NOTIFY draftsChanged)
    // The phone unlinked this device and its own name from before wasn't known: ask for one (until nameGiven).
    Q_PROPERTY(bool nameWanted READ nameWanted NOTIFY changed)

public:
    static const char kPrefix[];                       // "MUNINNL1:"
    static constexpr qint64 kTokenMs = 10 * 60000;
    static constexpr qint64 kRefreshMs = 60000;        // a new code this long before the old one runs out
    static constexpr int kPageMsgs = 100;              // messages in one "chat" page
    static constexpr int kPageBytes = 20 * 1024;       // and its JSON ("msgs", UTF-8) at most this many bytes
    static constexpr int kText = 2000;                 // characters of "x"
    static constexpr int kPart = 192 * 1024;           // bytes of a file in one "part"
    static constexpr qint64 kMediaMax = 100LL * 1024 * 1024;
    static constexpr qint64 kRefreshEveryMs = 10 * 60000;   // Refresh (a "sync" without "f") at most this often per companion
    static constexpr qint64 kEchoMs = 60000;                // a "send" with no answer from the phone this long: "Didn't send"

    explicit Link(Chat *chat, QObject *parent = nullptr);

    QVariantMap phone() const;
    int unread() const;
    QVariantList companions() const;
    QVariantList mirror() const;
    QString code() const { return m_code; }
    qint64 codeUntil() const { return m_tokenUntil; }
    bool sealed() const;
    QVariantMap me() const;
    QByteArray mePicture() const;           // the phone's picture (JPEG), empty when none
    QString meStatus() const;               // the phone's status ("online", "away", "dnd", "invisible"), "" when none
    QVariantMap account() const;
    // Cap l1: the phone's contacts' pictures (key to JPEG), for Profile to show (empty when none or no l1 phone).
    QHash<QString, QByteArray> contactPictures() const;

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
    // On a companion: unlink the phone. keep: its chats, contacts, groups and folders stay here, read only (Unlink
    // again, or linking another phone, takes them away); else everything that came from it goes now. False when this
    // device's own name from before linking isn't known (linked before it was kept): ask for one.
    Q_INVOKABLE bool unlinkPhone(bool keep);
    // Cap l1, on a companion: changes to the phone's account, made here and sent to it (the later change wins on both).
    // "" when done, else what to tell the person.
    Q_INVOKABLE QString renameContact(const QString &key, const QString &nickname);
    Q_INVOKABLE QString blockContact(const QString &key, bool on);
    Q_INVOKABLE QString removeContact(const QString &key);
    Q_INVOKABLE QString addFolder(const QString &name);
    Q_INVOKABLE QString renameFolder(const QString &id, const QString &name);
    Q_INVOKABLE QString deleteFolder(const QString &id);
    Q_INVOKABLE QString putInFolder(const QString &id, const QString &key, bool on);
    // On a companion: one mirrored chat's messages, oldest first:
    // [{id, text, mine, time, kind ("" or photo, voice, video, file), media (an id for Chat.blob, "" until it's here), name, size}],
    // then what was sent from here and not answered yet: {id, text, mine, time, pending: true, failed, err}.
    // Every message also has ch (a group's channel id, "main" when none was given), re (what it answers) and edited (ms, 0).
    Q_INVOKABLE QVariantList messages(const QString &peer) const;
    // On a companion: a photo, voice, video or file that came from the phone, saved in Downloads. "" when done,
    // else what to tell the person.
    Q_INVOKABLE QString saveMedia(const QString &peer, const QString &id) const;
    // Where the last saveMedia put its file (to open it or show it in its folder).
    Q_INVOKABLE QString lastSaved() const { return m_lastSaved; }
    // On a companion: send through the phone. It shows at once as pending until the phone answers ("result") or its copy
    // comes back; no answer in a minute: "Didn't send". "" when it went, else what to tell the person.
    // re: the message this one answers (optional; a phone without it sends it as a plain message).
    // ch: for a group ("g:<id>"), the channel being viewed; the phone posts there (an older phone, or a channel that
    // isn't there: the group's first channel, as before; an "Only admins post" one, for a member: not sent).
    Q_INVOKABLE QString send(const QString &peer, const QString &text, const QString &re = QString(), const QString &ch = QString());
    // On a companion: a phone contact's chat with no messages here yet, so it can be written to (Contacts › Message).
    // False when it isn't a contact on the phone.
    Q_INVOKABLE bool startChat(const QString &peer);
    // On a companion: a mirrored chat was read here (on screen). Its unread count goes, and the phone hears of it ("read").
    Q_INVOKABLE void markRead(const QString &peer);
    // On a companion: mute a mirrored chat until (ms; 0 unmutes, a huge value until turned back on). Here at once, and with
    // cap l1 on the phone too (a "mute" item). "" when done, else what to tell the person.
    Q_INVOKABLE QString setMute(const QString &peer, qint64 until);
    // On a companion: ask whether the phone is there ("ping", at most once a minute). The answer shows in phone.
    Q_INVOKABLE void ping();
    // On a companion: one that didn't send, again (the same id, so the phone never sends it twice).
    Q_INVOKABLE void retrySend(const QString &id);
    // A chat's draft (text "" and re "" clear it).
    Q_INVOKABLE void setDraft(const QString &peer, const QString &text, const QString &re);
    QVariantMap drafts() const;
    bool nameWanted() const;
    // This device's own account has its name again (or the person chose not to give one now).
    Q_INVOKABLE void nameGiven();
    // On a companion: ask the phone for every chat again (Refresh), at most once in 10 minutes. What to tell the person.
    Q_INVOKABLE QString refresh();
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
    // Set by main.cpp: this computer's device name (Settings), for the phone's list of linked devices.
    std::function<QString()> deviceName;
    // Set by main.cpp, on the phone's side: my profile for "me" ({n, st, cs, pic (base64 JPEG, omitted when none)}),
    // and what a companion's "setme" changes here (name, status, custom status; an empty one stays as it is).
    std::function<QJsonObject()> myProfile;
    std::function<void(const QString &, const QString &, const QString &)> setMyProfile;
    // Set by main.cpp, on a companion with an l1 phone: the phone's picture becomes this device's own (empty: none).
    std::function<void(const QByteArray &)> setMyPicture;
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
        QString from;                       // cap l1, a group chat: who wrote it
        QString ch;                         // a group chat: its channel's id ("" from an older phone: the main one)
        QString re;                         // the id of the message it answers ("" for none)
        qint64 edited = 0;                  // when it was last edited (0: never)
    };
    // Merges a page into a chat's messages by id (a later copy wins), oldest first; never drops one that's there.
    static void merge(QList<M> &into, const QList<M> &page);
    // Messages (as sent) into "chat" pages: at most kPageMsgs each and kPageBytes of UTF-8 JSON, oldest first.
    static QList<QJsonArray> pages(const QJsonArray &msgs);
    // A file that came in parts is the one named: its size and SHA-256 match.
    static bool mediaOk(const QByteArray &bytes, const QString &hash, qint64 size);
    // "get" and "part" only on the home Wi-Fi: never over the internet, Bluetooth or Direct connect.
    static bool lanOnly(const QString &via, bool homeNet);
    // A frame that may go over the internet (a small live one): msg, send, hello, unlink, result, sync, setme, and me
    // without its picture. Everything else ("chat", "get", "part", a "me" with "pic") only on the home Wi-Fi.
    static bool internetOk(const QJsonObject &msg);
    // A Refresh from a companion last taken at last (ms, 0: never) may be taken at t.
    static bool refreshDue(qint64 last, qint64 t) { return last <= 0 || t - last >= kRefreshEveryMs; }
    // What a companion shows for a phone's {"t": "result", "ok": false, "err"}.
    static QString errText(const QString &err);
    // How many parts a file of size bytes takes (0 when it's empty or too big).
    static int partsFor(qint64 size);
    // A file name from the phone made safe to save here: no folder, no character Windows or Linux refuses,
    // not hidden, not a name Windows keeps for itself, at most 120 characters. fallback when nothing is left.
    static QString safeName(const QString &name, const QString &fallback);
    // A code shown until it has less than kRefreshMs left, then a new one.
    static bool codeDue(qint64 at, qint64 until);

    // Cap l1 (section 15.1), the same as Muninn's LinkAccount: the account is items {k, id, ts, v} or tombstones
    // {k, id, ts, del: true}; per (k, id) the later ts wins, a tie goes to the phone's copy, tombstones stay 30 days.
    static const char kAcctCap[];                      // "l1"
    static constexpr qint64 kTombMs = 30LL * 86400000;
    static constexpr int kAcctLiveBytes = 16 * 1024;   // a change frame that may use the internet, at most
    struct Item {
        QString k, id;
        qint64 ts = 0;
        bool del = false;
        QJsonObject v;
        QString key() const { return k + QLatin1Char('|') + id; }
        QJsonObject json() const;
    };
    // One item as it came; ok false when it isn't one.
    static Item readItem(const QJsonObject &o, bool *ok);
    static bool itemWins(const Item *local, const Item &in, bool winTies);
    // Merges incoming into set; returns the items that won (to apply).
    static QList<Item> mergeItems(QHash<QString, Item> &set, const QList<Item> &incoming, bool winTies);
    // The first full set from a phone just linked: it replaces everything here (its name, never a leftover one).
    static void adoptItems(QHash<QString, Item> &set, const QList<Item> &incoming);
    static void pruneItems(QHash<QString, Item> &set, qint64 now);
    static bool acctInternetOk(const QJsonObject &msg);
    static bool saysL1(const QString &caps);

Q_SIGNALS:
    void changed();
    void draftsChanged();
    // Cap l1: the phone's contacts' pictures changed (contactPictures).
    void picturesChanged();

private:
    struct Device {
        QString key, mailbox, name;
        bool ok = true;                     // the 4 emoji were checked (true when stored without "ok")
        bool l1 = false;                    // the phone sends its whole account (cap l1, 15.1)
        bool gone = false;                  // unlinked, its copy kept here read only
        bool l2 = false;                    // cap l2 (15.2): on the phone, a companion that takes "read"; on a companion, a phone that answered "ping"
    };
    struct Mirror {
        QString peer, name;
        QList<M> msgs;
        int days = 7;                       // the phone's limit for this chat (Limits::kDefault)
        qint64 readAt = 0;                  // read here (or on the phone, cap l2) up to this message time
    };
    void ensureLoaded() const;
    void save();
    void saveMirror();
    // A small live frame (internetOk): every route, at most 2 relays. Anything else only over sendLan.
    void sendTo(const Device &d, const QJsonObject &msg);
    bool sendLan(const QString &key, const QJsonObject &msg, std::function<void(int)> done = {});
    bool home() const { return !homeWifi || homeWifi(); }
    bool lanHere(const QString &key) const;     // the device is on the home Wi-Fi with this one now
    void sendMeTo(const Device &d, const QJsonObject &me);
    void lanTick();                             // the phone's side: history and pictures that waited for the home Wi-Fi
    QSet<QString> m_owed, m_meOwed;             // companions whose history, or "me" picture, waits for the home Wi-Fi
    QHash<QString, qint64> m_synced;            // per companion: when its history was last asked for or sent
    qint64 m_askedAt = 0;                       // the companion's side: when Refresh last asked
    qint64 m_heardAt = 0, m_pingAt = 0, m_pongAt = 0;   // the companion's side, cap l2: the phone last heard, pinged, answered
    QSet<QString> m_readOwed;                   // the companion's side: chats whose "read" goes to the phone soon
    QSet<QString> m_freshPages;                 // the companion's side: chats new from this sync's pages (their history counts as read)
    QTimer m_readSoon;
    QString myDeviceName() const;
    // The companion's side: sent through the phone, not answered yet (err: why it didn't send; "" while waiting).
    struct Pending {
        QString id, peer, text, err, re;
        qint64 time = 0;
        QString ch;   // a group's channel (15.1), "" for the first one
    };
    QList<Pending> m_pending;
    void sendPending(const Pending &p);
    void answerSend(const Device &d, const QString &id, bool ok, const QString &err, const QString &mid = QString());
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
    // This device's own profile from before the phone's replaced it (myProfile's shape, in link.json), put back on
    // unlinking; m_ownBack: whether the last forgetPhone put a name back (or never needed to).
    mutable QJsonObject m_own;
    mutable QString m_lastSaved;
    bool m_ownBack = true;
    mutable bool m_nameWanted = false;      // in link.json ("askName")
    mutable QJsonObject m_drafts;           // link-drafts.json
    bool restoreOwn();
    // Cap l1, the companion's side: the phone's account (link-account.json), and whether the next full set replaces it.
    mutable QHash<QString, Item> m_acct;
    mutable bool m_acctFresh = false;
    bool m_capsAsked = false;                // this run asked an older link's phone about l1 once
    void saveAcct();
    mutable QHash<QString, QByteArray> m_pics;   // contactPictures, decoded once
    void rebuildPics() const;
    void takeAcct(const QJsonObject &p);
    void applyMe(const QString &name, const QString &status, const QString &custom);
    QString changeItem(const QString &k, const QString &id, bool del, const QJsonObject &v);
    QJsonObject syncFrame(bool first) const;
    bool acctOn() const { return !m_phone.key.isEmpty() && m_phone.ok && !m_phone.gone && m_phone.l1; }
    // The phone's side: the last "me" sent (compact JSON), so a change goes once.
    QByteArray m_meSent;
    QTimer m_meSoon;
    // Ids of companions' "send" already sent (two days, on disk), so none goes out twice.
    bool firstSend(const QString &id);
    QHash<QString, qint64> m_sends;
    bool m_sendsLoaded = false;
};
