#pragma once

#include "call/stun.h"
#include "chatcrypto.h"

#include <QBitArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>
#include <functional>

class Chat;
class QFile;
class QTimer;
class QUdpSocket;
class StunProbe;
class TurnClient;
struct TurnLogin;

// Encrypted file sharing (caps f1, f2, f3 and f4; shared/docs/protocol.md sections 14, 27 and 30), the same as
// Muninn's FileLink.kt. Never through the relays (1.42). In this order: Nearby (the same Wi-Fi), Trusted direct (below),
// then Tor (cap f4, torfiles.h: a one-time onion service for one file); a transfer switches to Tor part way and goes on
// from the last piece. Every file asks Accept or Decline first (the receiving card), unless the person is set to Always
// (only someone trusted or Family). Files straight over the internet (cap f1, section 14): Never through Cloudflare's call relay: both sides
// learn their own internet address from a STUN server (DirectPath::stunServers(), the address lookup only),
// swap it in a sealed `file` message, and punch through their routers by sending to each other at
// once, as the Direct path of calls does (5.8.2). Only between two people who trust each other with
// their addresses ("Direct calls" on, as for Family). The file then goes in numbered 1100-byte pieces,
// each sealed with AES-256-GCM under a key only the two ends know; the receiver says which pieces it
// has and lost ones go again.
class FileLink : public QObject
{
    Q_OBJECT
    // Every transfer this run: [{id, peer, where, chat, name, size, mine, state, done, note, path, route, pace, paused,
    // held, kind, folder, group, relay}]. state: "connecting", "offered", "asking" (waiting for Accept or Decline),
    // "sending", "done", "failed". where: the chat it belongs to (their key, or "g:<group>"); chat: its name. path:
    // mine, the file sent; theirs, where it was saved once done. route: "Same Wi-Fi", "Straight to them" or "Over the internet"
    // (empty until known). pace: "2.1 MB/s · 12 s left". folder: where theirs is saved, as words. relay: a file of mine
    // going as a message over the internet route (its message shows it in the chat). A file of theirs coming over the
    // internet route shows too (id: its message id), until its message is in.
    Q_PROPERTY(QVariantList transfers READ transfers NOTIFY changed)
    // Running or waiting for an answer here: the Transfers button's count.
    Q_PROPERTY(int activeCount READ activeCount NOTIFY changed)
    // Moving or about to, not paused here or there (what Wait for it waits for); paused here (Resume and wait); and what
    // closing now would stop or cancel, in words ("" when nothing): moving, paused or waiting for them to accept.
    Q_PROPERTY(int runningCount READ runningCount NOTIFY changed)
    Q_PROPERTY(int pausedCount READ pausedCount NOTIFY changed)
    Q_PROPERTY(QString closeWarning READ closeWarning NOTIFY changed)
    // Kept parts of their files whose cards were closed (cap f3): { count, bytes }, for Transfers' "Discard all".
    Q_PROPERTY(QVariantMap keptAway READ keptAway NOTIFY changed)

public:
    static const char kCap[];                   // "f1"
    static constexpr int kChunk = 1100;
    static constexpr int kWindow = 96;
    static constexpr int kRtoMs = 500;
    static constexpr int kPunchMs = 20000;
    static constexpr int kIdleMs = 30000;
    // Cap f3 (27.7): resume and long waits. Silence ends a transfer only after kLongIdleMs; after kQuietMs of it each
    // side sends one packet a second (a probe or an ack) until the other is heard again.
    static const char kResumeCap[];             // "f3"
    static constexpr int kLongIdleMs = 120000;
    static constexpr int kQuietMs = 2000;
    static constexpr qint64 kMaxBytes = 4LL * 1024 * 1024 * 1024;
    // 1.42: no file arrives by itself any more (every one asks first, unless Always). Kept for the group type rule's
    // callers (chatfiles.cpp) only.
    static constexpr qint64 kAutoBytes = 50LL * 1024 * 1024;
    // Cap f4: quiet this long on Nearby or Trusted direct, it goes on over Tor; a queued offer goes again this often.
    static constexpr int kSwitchMs = 10000;
    // Cap f5 (MediaRoute.kt has the same): how long the direct way gets on Fastest, how long it may go quiet before the
    // relay, how long a side waits for the other's relay address, an offline person's wait, and the month's limit.
    static constexpr int kFastestPunchMs = 3000;
    static constexpr int kRelaySwitchMs = 6000;
    static constexpr int kRelayWaitMs = 45000;
    static constexpr int kFamilyWaitMs = 7 * 24 * 3600000;
    static constexpr qint64 kRelayLimitBytes = 800 * 1000000000LL;
    static bool relayOk(const QJsonObject &p);
    static bool relayMay(const QString &choice, bool ultra, bool family, bool peerCan, bool over);
    static bool torMay(const QString &choice, bool ultra, bool family, bool peerCan);
    bool relayPlan(const QString &peer, const QString &group) const;
    bool relayOver() const;
    QString relayNote() const;
    static constexpr int kReofferMs = 2 * 3600000;
    // The receiving card's words (the same as Muninn's FileLink): "a ZIP file", the risky types and their note, the trust
    // badges (only Trusted and Family may be set to Always), the queued sender's note, and each path's label.
    static QString typeWord(const QString &name);
    static bool riskyName(const QString &name);
    static QString riskyNote() { return QStringLiteral("Only open this if you were expecting it."); }
    static bool mayAlways(const QString &badge) { return badge == QLatin1String("Trusted") || badge == QLatin1String("Family"); }
    static QString queuedNote(const QString &who, const QString &device);
    static QString methodLabel(const QString &path, qint64 size = 0);
    static QString notSentText() { return QStringLiteral("Didn't send. Try again."); }
    static QString updateText() { return QStringLiteral("Ask them to update"); }
    static QString noPathText() { return QStringLiteral("Can't reach them right now"); }
    // How long an offer waits for an answer: longer for a file the other side asks about first.
    static constexpr int kAnswerMs = 120000;
    static constexpr int kAskAnswerMs = 10 * 60000;
    // Cap f3: a big file waits a day for Accept or Decline (both apps say f3).
    static constexpr int kLongAnswerMs = 24 * 3600000;
    static const qint64 kRelayMax;              // the largest file the relays take (Photos' 130 pieces)
    // A file small enough for the relays goes that way when no answer comes this soon (their device may be off).
    static constexpr int kRelayAfterMs = 30000;
    // How long an offer of a file of this size waits: a bigger one as long as the other side may ask first.
    static int answerMs(qint64 size, bool long_ = false) { return size <= kRelayMax ? kRelayAfterMs : long_ ? kLongAnswerMs : kAskAnswerMs; }

    // What a file on its way says, the same words as Muninn's FileText (checked in selfTest).
    static int percent(qint64 done, qint64 size);
    static QString statusText(const QVariantMap &t, const QString &who);
    // A file of mine sent as a message over the internet route: state "sending", "sent", "delivered", "read";
    // fraction: how much a route took so far, below 0 before anything did.
    static QString relayText(const QString &state, bool failed, double fraction);
    // A file over kRelayMax that can't go direct (Retry stays on its card); bigWhy: what a big file of mine whose straight
    // path failed says instead of why (the same as Muninn's FileText.bigWhy).
    static QString tooBig();
    static QString bigWhy(const QString &why, qint64 size);
    // "2.1 MB/s · 12 s left"; empty until a second has passed and something moved.
    static QString paceText(qint64 done, qint64 total, qint64 ms);
    static QString leftText(qint64 seconds);
    static QString sizeText(qint64 bytes);
    static QString iconFor(const QString &name);
    // Hold (cap f2, 27.5): 1 byte, 1 paused or 0 going again. Any side sends it every second while paused.
    enum Type : quint8 { Probe = 1, Data = 2, Ack = 3, Done = 4, Hold = 5 };

    // What the sender keeps: which pieces went when, which are confirmed.
    class Sender
    {
    public:
        explicit Sender(int pieces);
        // The pieces to send now: lost ones again first (older than rto), then new ones while the
        // window has room.
        QList<int> due(qint64 now, qint64 rto = kRtoMs);
        // The receiver has everything below `below`, and of the 64 after it the ones set in bits.
        void ack(int below, quint64 bits);
        bool done() const { return m_acked == m_n; }
        int confirmedBelow() const { return m_below; }
        bool has(int i) const { return i >= 0 && i < m_n && m_got.testBit(i); }   // cap f4: a Tor stream skips it
    private:
        void mark(int i);
        int m_n, m_next = 0, m_acked = 0, m_below = 0;
        QBitArray m_got;
        QHash<int, qint64> m_sentAt;
    };
    // What the receiver keeps: which pieces are in.
    class Receiver
    {
    public:
        explicit Receiver(int pieces);
        bool take(int i);                       // true when it's new
        void ack(int *below, quint64 *bits) const;
        bool done() const { return m_have == m_n; }
        // Cap f3: the first `below` pieces are already on disk from a try that stopped.
        void preload(int below);
        int below() const { return m_below; }
    private:
        int m_n, m_have = 0, m_below = 0;
        QBitArray m_got;
    };

    static int pieces(qint64 size);
    static QByteArray key(const QByteArray &shared, const QString &id);
    // type || seq (8 bytes, big endian) || AES-256-GCM(payload), nonce dir (4 bytes) || seq, AAD type || id.
    static QByteArray seal(const QByteArray &key, const QString &id, quint8 type, quint32 dir, quint64 seq, const QByteArray &payload);
    // False when it isn't ours or was changed on the way.
    static bool open(const QByteArray &key, const QString &id, quint32 dir, const QByteArray &packet, quint8 *type, QByteArray *payload);
    // A file's name: 1 to 200 characters, no "/", "\\" or control characters.
    static bool nameOk(const QJsonValue &name);
    // An offer, accept or decline is well-formed (section 14).
    static bool ok(const QJsonObject &p);
    // shared/vectors/i1f1-vectors.json's "file" part, then a whole transfer between two of these on this computer.
    static bool selfTest(const QJsonObject &vectors, const QByteArray &shared);

    explicit FileLink(Chat *chat, QObject *parent = nullptr);
    ~FileLink() override;

    // Sends a file to someone, by the best route (Sharing::route): the same Wi-Fi, straight over the internet when both
    // trust each other, through the relays when it's 2 MB or less. group: it's that group's file (27.2). "" when it's on
    // its way (its card says how it goes), else what to tell the person.
    Q_INVOKABLE QString send(const QString &peer, const QString &path, const QString &group = QString());
    // Cap v3 (29.3): a full-size video ([vidMs] long) straight to [peer] (in [group], or ""), as an offer with "vid". If it
    // can't go straight (no answer within kRelayAfterMs, no way through, refused by rules or space, broken off), its
    // card goes and [back] sends the relay copy. With no [back] (a long video in full), it waits as any file does.
    // [path]: the video itself (never changed or deleted here).
    QString sendVideo(const QString &peer, const QString &path, const QString &group, int vidMs, std::function<void()> back);
    // The one way to send (Send file, drag and drop): files to a chat, their key or "g:<group>[:<channel>]". In a group,
    // once to each member, after the group's own file rules said yes. "" when on their way, else why not.
    Q_INVOKABLE QString sendTo(const QString &where, const QVariantList &urls);
    // Cap f4: whether a file can reach them now (the Send menu's "Encrypted file sharing" row): false only when no path
    // can (no internet and not on the same Wi-Fi, or their app is too old). reachNote: "" or what the greyed row says
    // ("Ask them to update", "Can't reach them right now"). torWarning: the question before a file over 1 GB that will
    // most likely go over Tor ("" when it needn't ask).
    Q_INVOKABLE bool canReach(const QString &peer) const { return reachNote(peer).isEmpty(); }
    Q_INVOKABLE QString reachNote(const QString &peer) const;
    Q_INVOKABLE QString torWarning(const QString &peer, double size) const;
    // Whether "Always accept from" may be set for them: their badge is Trusted or Family (Details › Send me files).
    Q_INVOKABLE bool mayAlwaysFrom(const QString &peer) const { return mayAlways(badge(peer)); }
    // Pause and go on (cap f2): either side, while it moves.
    Q_INVOKABLE void pause(const QString &id);
    Q_INVOKABLE void resume(const QString &id);
    Q_INVOKABLE bool canPause(const QString &id) const;
    Q_INVOKABLE bool canResume(const QString &id) const;
    // Every transfer paused here goes on (Resume and wait, when closing).
    Q_INVOKABLE void resumeAll();
    // "Always accept from <name>": accept, and from now on their files arrive without asking (only someone trusted or
    // Family; for anyone else it just accepts).
    Q_INVOKABLE void acceptAlways(const QString &id);
    // A chat's Files: everything sent and received there, newest first: [{name, size, path, mine, time, there, folder}].
    Q_INVOKABLE QVariantList filesIn(const QString &where);
    // Delete: a received file goes from the disk; a sent one only from the list (the original stays where it was).
    Q_INVOKABLE bool deleteFile(const QString &where, const QString &path);
    // Where a chat's files are saved, as words ("Downloads/Huginn/Ana").
    Q_INVOKABLE QString folderText(const QString &where) const;
    Q_INVOKABLE QString kind(const QString &name) const;
    // A transfer of mine or theirs is for this chat (their key, or "g:<group>").
    bool expects(const QString &peer, const QString &id) const;
    int activeCount() const;
    int runningCount() const;
    int pausedCount() const;
    QString closeWarning() const;
    // The same with another word for what ends it ("Restarting"): whether it stops, or pauses and goes on later.
    Q_INVOKABLE QString closeText(const QString &verb) const;
    // Cap f3: their app keeps a stopped file and goes on from there, and waits a day for a big file's answer.
    bool hasF3(const QString &peer) const { return resumes && resumes(peer); }
    // A failed file of mine that needs Direct calls with them (no shared Wi-Fi, too big for the relays).
    Q_INVOKABLE bool needsDirect(const QString &id) const;
    Q_INVOKABLE void cancel(const QString &id);
    // A big file waiting ("asking"): take it now, or say no (the sender hears so and it's gone here).
    Q_INVOKABLE void accept(const QString &id);
    Q_INVOKABLE void decline(const QString &id);
    // Close on a card: it goes from the list. A stopped file of theirs keeps what came (for kKeepDays), so it still
    // goes on from there if they send it again; discard also deletes that part. keptSize: bytes kept for a card (0: none).
    Q_INVOKABLE void dismiss(const QString &id);
    Q_INVOKABLE void discard(const QString &id);
    Q_INVOKABLE double keptSize(const QString &id) const;
    QVariantMap keptAway() const;
    // Deletes every kept part whose card was closed (they'd go by themselves after kKeepDays).
    Q_INVOKABLE void discardKept();
    Q_INVOKABLE bool hasF1(const QString &peer) const { return peerHasF1 && peerHasF1(peer); }
    // A file that came through the relays (a message with "file"): saved in Downloads. "" when saved.
    Q_INVOKABLE QString saveReceived(const QString &peer, const QString &msgId);
    QVariantList transfers() const;

    // For the chat's file cards.
    Q_INVOKABLE QString text(const QVariantMap &t) const { return statusText(t, who(t.value(QStringLiteral("peer")).toString())); }
    Q_INVOKABLE QString relayLine(const QString &state, bool failed, double fraction) const { return relayText(state, failed, fraction); }
    Q_INVOKABLE QString icon(const QString &name) const { return iconFor(name); }
    Q_INVOKABLE QString pace(double done, double total, double ms) const { return paceText(qint64(done), qint64(total), qint64(ms)); }
    Q_INVOKABLE QString size(double bytes) const { return sizeText(qint64(bytes)); }
    // Cancel shows: mine while it runs, theirs while it comes straight (one over the internet route just arrives).
    Q_INVOKABLE bool canCancel(const QString &id) const;
    Q_INVOKABLE bool canRetry(const QString &id) const;
    // Retry: the card goes and its file is sent again.
    Q_INVOKABLE void retry(const QString &id);
    // Open: the app this computer uses for that kind of file (only when the person asks). Show in folder: its folder.
    // A program or a file whose name hides what it is (Sharing::openWarning) isn't opened: askOpen says why, and
    // openAnyway opens it once the person agrees.
    Q_INVOKABLE bool openFile(const QString &path);
    Q_INVOKABLE bool openAnyway(const QString &path) const;
    // Linux: a drop's document-portal key (application/vnd.portal.filetransfer) as file URLs the sandbox can read; empty
    // when there is none (Ui.dropUrls then uses the drop's own URLs).
    Q_INVOKABLE QVariantList portalUrls(const QString &key) const;
    Q_INVOKABLE bool showInFolder(const QString &path) const;

    // A file of mine that can't go at all: its card says why (wifi: only the same Wi-Fi can take it).
    // direct: it would go with Trusted direct on for each other (its card offers that).
    void addFailed(const QString &peer, const QString &name, qint64 size, const QString &why, const QString &path = {}, bool wifi = false,
                   const QString &id = {}, const QString &group = {}, bool direct = false);
    // A file of theirs coming over the internet route: have of n pieces. Done: its message is in (or half of it was
    // let go); a card for the same file that couldn't come straight goes too.
    void relayIn(const QString &msgId, const QString &peer, const QString &name, int have, int n);
    void relayInDone(const QString &msgId, const QString &peer = {}, const QString &name = {});
    // 1.42: an older app's file over the relays, turned down: a failed card says why (nothing is kept).
    void oldRelayIn(const QString &msgId, const QString &peer, const QString &name)
    {
        addRefused(peer, QString(), msgId, name, 0, QStringLiteral("Sent from an older app. Ask them to update."));
    }
    // A file that came as a message: saved in its chat's folder in Downloads at once. Its full path, or empty when it
    // couldn't be.
    QString saveBytes(const QByteArray &bytes, const QString &name, const QString &peer = QString());

    // An opened, checked (ok()) room "file" message from a contact.
    void onSignal(const QString &from, const QJsonObject &p);
    // Ultra privacy turned on (main.cpp, round 19): nothing goes straight over the internet any more. A transfer on the
    // same Wi-Fi goes on there; a waiting or running one that isn't goes by relay (a small file of mine) or stops and
    // says why, and the STUN keepalive of a big file's offer stops.
    void ultraOn();

    // Hooks: Chat's by default, the self-test's own in the test.
    std::function<void(const QString &peer, const QJsonObject &msg)> signal;
    std::function<bool(const QString &peer)> trusted;
    std::function<bool(const QString &peer)> peerHasF1;
    // Their app waits kAskAnswerMs for a big file (its caps say e1): only then is a big file asked about first.
    // An older sender gives up after kAnswerMs, so its big files are taken as before. Unset: it waits.
    std::function<bool(const QString &peer)> waitsForAsk;
    // In one of my Family groups (protocol.md 16.2): their files arrive by themselves, whatever the size.
    std::function<bool(const QString &peer)> allArrive;
    std::function<QString(const QString &peer, const QByteArray &bytes, const QString &name)> relay;
    std::function<QString(const QString &peer)> nameOf;
    // Cap f2 (section 27). lanHost: where they are on this Wi-Fi (a device whose chat key proved its certificate), never
    // shown or sent; empty when they aren't here. Their app understands the "lan" candidate and group files with f2.
    std::function<QString(const QString &peer)> lanHost;
    std::function<bool(const QString &peer)> hasF2;
    std::function<bool(const QString &peer)> resumes;
    std::function<QString(const QString &g, const QString &from, qint64 size)> groupRefusal;
    std::function<bool(const QString &g, qint64 size)> groupArrives;
    std::function<QString(const QString &g)> groupName;
    std::function<QStringList(const QString &g)> groupMembers;
    std::function<QString()> myKey;
    std::function<bool(const QString &peer)> alwaysFrom;
    std::function<void(const QString &peer)> setAlways;
    std::function<bool(const QString &peer)> isContact;
    // Cap f4 (section 30): their app says f4; Tor may start here for a file; their trust badge (Trusted, Family, Contact,
    // Not trusted); "phone" or "computer" when known; whether this computer is online.
    std::function<bool(const QString &peer)> hasF4;
    // Cap f5 (section 32): their app can carry a file over the call relay; "auto", "fastest" or "tor" for this chat; a
    // login, as a call gets one; the month's relayed media (mb added, 0 only asks; done gets the total in MB, or -1).
    std::function<bool(const QString &peer)> hasF5;
    std::function<bool(const QString &peer)> asksFirst;   // Family or trusted, but set to Ask in their details
    std::function<QString(const QString &peer, const QString &group)> routeFor;
    std::function<void(std::function<void(const TurnLogin &, const QString &)>)> relayLogin;
    std::function<void(qint64 mb, std::function<void(qint64)>)> relayMeter;
    std::function<bool()> torAllowed;
    std::function<QString(const QString &peer)> badgeOf;
    std::function<QString(const QString &peer)> deviceWord;
    std::function<bool()> online;
    // Set by Chat::sendFileBlob: the message a file of mine went as (its card follows the message).
    QString lastRelayId;
    QString saveDir;
    bool testLocal = false;                     // self-test: 127.0.0.1 only, no STUN
    // Round 19 (interop 6): when someone was last told a file from them couldn't come because of Ultra privacy.
    QHash<QString, qint64> m_ultraTold;
    int testDropEvery = 0;                      // self-test: lose every Nth data packet the first time

Q_SIGNALS:
    void changed();
    void notice(const QString &text);
    // openFile() of a file that may run as a program: what to ask before opening it.
    void askOpen(const QString &path, const QString &warning);
    // A file that asks Accept or Decline first: worth a system notification (id: its card; where: its chat).
    void offered(const QString &id, const QString &where, const QString &text);

private:
    struct Xfer;
    void startSocket(Xfer *x, std::function<void(QJsonArray)> ready);
    void sendOffer(Xfer *x, const QJsonArray &cands);
    void acceptOffer(Xfer *x);
    void startPunch(Xfer *x);
    void needTick(Xfer *x);
    // Cap f5: the file through a call relay allocation (protocol.md 32).
    void startRelay(Xfer *x);
    void openLeg(Xfer *x, const TurnLogin &login);
    void relayUp(Xfer *x);
    void relayGaveUp(Xfer *x);
    void onRelayData(Xfer *x, const QByteArray &bytes);
    bool takePacket(Xfer *x, quint8 type, const QByteArray &payload);
    bool autoRetry(const QString &id, const QString &path);
    void meterRoll() const;
    void relayUsed(qint64 bytes);
    void reportMeter(qint64 bytes) const;
    QHash<QString, int> m_autoTries;
    qint64 m_meterHold = 0;
    mutable qint64 m_meterAsked = 0;
    void punchTick(Xfer *x);
    bool startData(Xfer *x);
    void dataTick(Xfer *x);
    void onDatagram(Xfer *x);
    void sendAck(Xfer *x);
    void receivedAll(Xfer *x);
    void fail(Xfer *x, const QString &why, bool tell = true);
    // No straight path for a file of mine: a small one goes over the internet route instead, else it fails with why.
    void fallBack(Xfer *x, const QString &why);
    // Cap v3: a full-size video of mine that can't go straight: its card goes and its relay copy is sent instead. True
    // when it was one (x is gone then).
    bool videoFellBack(Xfer *x, const QString &why);
    // Cap f4: the rest of a file over Tor (mine: a one-time onion service; theirs: connect once its `tor` message came).
    void startTor(Xfer *x);
    void torConnect(Xfer *x);
    void torEnded(Xfer *x, int end);
    bool prepareData(Xfer *x);
    QString badge(const QString &peer) const;
    int m_nextVid = 0;
    std::function<void()> m_nextBack;
    void drop(const QString &id);
    void finish(Xfer *x);
    void end(Xfer *x);
    void put(Xfer *x, bool now = false);
    void sendPacket(Xfer *x, quint8 type, const QByteArray &payload, const Stun::Address &to);
    QString freeName(const QString &dir, const QString &name) const;
    QString who(const QString &peer) const { return nameOf ? nameOf(peer) : peer.left(8); }
    // A chat's folder: Downloads/Huginn/<its name> (the save folder itself in the self-test).
    QString folderOf(const QString &peer, const QString &group) const;
    QString shownFolder(const QString &dir) const;
    // Where saved files go: Settings' "Save files to" (Downloads unless changed); saveDir in the self-test.
    QString base() const;
    QString chatOf(const QString &peer, const QString &group) const;
    QString lanOf(const QString &peer) const { return lanHost ? lanHost(peer) : QString(); }
    // A card of theirs that was refused before it began (group rules, no space): it says why.
    void addRefused(const QString &peer, const QString &group, const QString &id, const QString &name, qint64 size, const QString &why);
    // A file of mine that went as a message: its card follows the message until it's sent.
    void addRelay(const QString &peer, const QString &group, const QString &name, qint64 size, const QString &path);
    void relayTick();
    // The Files list of each chat, kept in the vault.
    void logFile(const QString &where, const QString &name, qint64 size, const QString &path, bool mine);
    void loadLog();
    void saveLog();
    // Cap f3: a file of theirs that stopped part way, kept to go on from: by card id. Kept across a restart too
    // (keptparts.json in the vault, with its card), for kKeepDays.
    struct Partial { QString peer, sha, path, folder, name, group; qint64 size = 0; int below = 0; qint64 time = 0; };
    QHash<QString, Partial> m_partial;
    // Cards of mine whose Retry goes on from where it stopped (cap f3), kept across a restart with the parts.
    QSet<QString> m_resumable;
    // The ones already retried by themselves this run (once each).
    QSet<QString> m_autoTried;
    static constexpr int kKeepDays = 7;
    bool m_keptLoaded = false, m_keptSaved = false;
    // Read once the vault is open; written when a kept part or a resumable card comes or goes, and on quitting.
    void loadKept();
    void saveKept();
    // Quitting: a file of mine on its way becomes a card whose Retry goes on, and one of theirs is kept as a part.
    void keepForRestart();
    void dropPartial(const QString &id);
    // The kept part this file of theirs goes on from (its card id), or "".
    QString partialFor(const Xfer *x) const;
    // A file of mine already hashed (path: size, last change, sha): Retry doesn't read it all again.
    struct Hashed { qint64 size = 0, changed = 0; QString sha; };
    QHash<QString, Hashed> m_hashed;
    QList<QVariantMap> m_log;
    bool m_logLoaded = false;

    Chat *m_chat;
    QHash<QString, Xfer *> m_x;
    // Cap f4: offers of theirs already answered here (declined, taken, gone): a queued offer sent again doesn't ask twice.
    QSet<QString> m_answered;
    QList<QVariantMap> m_list;                  // what transfers() shows, newest last
    qint64 m_lastShown = 0;
};
