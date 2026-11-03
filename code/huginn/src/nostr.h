#pragma once

#include <functional>

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QStringList>
#include <QTimer>

class QThread;
class NostrWorker;

// Messages for people who aren't nearby, through public Nostr relays (no accounts, no server to
// run). Each device has a Nostr key; its public half is its mailbox. A message is an event of
// kind 1059 addressed to the mailbox, signed by a throwaway key, so the event itself doesn't name its sender.
// The content is already sealed with the chat keys (ChatCrypto), so relays only see scrambled text, padded
// to a few fixed sizes (Chat::wrap). Sending uses sockets of its own that never ask for the inbox or sign in as this
// computer's mailbox (they close after a few quiet minutes); the internet address can still link the two unless a VPN is used.
// The inbox is asked for from 20 minutes before each relay last finished sending what it held (never past the longest
// message limit);
// a relay that sends a full page is asked again for what came before it, until a page comes back short. Events
// already handled are skipped before they are read in full.
// Same behaviour and wire format as Muninn's Nostr.kt.
// The relay sockets, reading what they send, the signature checks and opening the wrapping all run on a thread of
// their own (NostrWorker, "huginn-relays"). This object stays on the window's thread: it hands that thread copies of
// the keys and takes back only new, checked, opened events, in batches, at most about 30 times a second. Whether an
// event was taken goes back the same way; a relay's cursor only moves on once every event before it has been answered.
class Nostr : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString publicKey READ publicKey NOTIFY publicKeyChanged)
    Q_PROPERTY(bool keepAlive READ keepAlive WRITE setKeepAlive NOTIFY keepAliveChanged)

public:
    static constexpr int Kind = 1059;
    static QStringList defaultRelays();

    // secret gives this device's 32-byte Nostr secret (64 hex characters also work); empty while locked.
    explicit Nostr(std::function<QByteArray()> secret, QObject *parent = nullptr);
    ~Nostr() override;

    // Every envelope that arrives for this device (content of the event). Return false to see it again later.
    std::function<bool(const QString &content)> onEnvelope;
    // Opens an envelope's wrapping on the relay thread: it may only use what it captured (copies of the keys), never
    // anything that lives on the window's thread. ok false: it couldn't (onEnvelope gets the content instead).
    using Opener = std::function<QByteArray(const QString &content, bool *ok)>;
    // Makes an Opener from the keys as they are now (on this thread). Asked again whenever the keys may have changed.
    std::function<Opener()> opener;
    // What the relay thread opened. Return false to see it again later. Unset: onEnvelope gets the content.
    std::function<bool(const QByteArray &body)> onOpened;
    // Cap b1: the ring mailboxes and their Nostr secrets, from this device's ring key outside the vault (empty: none;
    // two for a while after a block renewed it). They are asked for on an inbox request of their own, locked or not,
    // and relays that ask get a sign-in as each.
    std::function<QStringList()> ringMailboxes;
    std::function<QList<QByteArray>()> ringSecrets;
    // Every event for the ring mailbox (its content): never kept, never handed to onEnvelope. live: it came after the
    // relay's end of stored events on that request, so it was sent just now (not kept on the relay).
    std::function<void(const QString &content, bool live)> onRing;
    // The events already taken are kept in the vault (main.cpp): keepSeen saves the list, restoreSeen
    // reads it back when the vault opens (with what came while it was locked). keepSeen returns whether the list was written.
    std::function<bool(const QByteArray &stored)> keepSeen;
    // Set: the list is made and written on the saving thread instead (keepSeen is not used).
    std::function<void(std::function<QByteArray()> make)> keepSeenLater;
    void restoreSeen(const QByteArray &stored);
    // Stops the relay thread: its sockets close and it is waited for (a few seconds at most, never forever). Also
    // when Huginn closes and when this goes. Nothing is sent or received after it.
    void shutdown();
    // --perf-selftest: events through the relay thread and back, dropped copies, a cursor that waits for answers,
    // and a shutdown that never hangs.
    static bool threadSelfTest();

    int connected() const { return m_live; }
    // The mailbox (x-only public key in hex). Cached, so it still works while locked; empty if never unlocked.
    QString publicKey() const;
    // The relays Huginn uses: defaultRelays().
    QStringList relays() const;
    // Reconnect by itself when a relay drops (set while Huginn is listening for messages).
    bool keepAlive() const { return m_keepAlive; }
    void setKeepAlive(bool on);

    Q_INVOKABLE void connectRelays();
    // The network came back: drop dead connections and connect again now.
    Q_INVOKABLE void reconnectNow();
    // Asks every open relay for the inbox again: after unlocking (the key and the handler change),
    // or once the mailbox is first known on a fresh install.
    Q_INVOKABLE void resubscribe();
    // Closes every relay and turns keepAlive off. Hides QObject::disconnect; call that one as QObject::disconnect.
    Q_INVOKABLE void disconnect();
    // Sends sealed content to someone's mailbox through every relay (open ones first), on sending sockets of their own.
    // days > 0: the event carries a NIP-40 expiration tag (expiration(), the same for every chat). Returns how many relays are connected now.
    Q_INVOKABLE int publish(const QString &mailboxHex, const QString &content, int days = 0);
    // The same, with more say: onStored runs once the first relay answers OK to it (NIP-20 ["OK", id, true, ...]), never
    // before; maxRelays > 0 sends it to at most that many relays (open ones first; kSpread otherwise); low marks a linked device's frame
    // (section 15), which waits in an outbox of its own (kMaxLowOutbox) and never pushes a real message out of the main one.
    int publishTo(const QString &mailboxHex, const QString &content, int days, std::function<void()> onStored, int maxRelays = 0, bool low = false);
    static constexpr int kMaxOutbox = 200;
    // How many relays an event goes to unless asked otherwise.
    // Every relay: a phone saving battery listens on only one of them, so a message must be on all.
    static constexpr int kSpread = 99;
    // A relay that sends this many stored events before EOSE may have held more: it is asked for the ones before them.
    static constexpr int kPageFull = 100;
    // The vault was locked or erased: the cached inbox key goes.
    void forgetSecret();
    static constexpr int kMaxLowOutbox = 40;
    // A linked PC's frames to its phone go to every relay too (0: no limit): the phone may listen on only some of them.
    static constexpr int kLinkRelays = 0;
    // The relays an event limited to n goes to: the open ones first (in list order), then the rest.
    static QStringList pickRelays(const QStringList &all, const QSet<QString> &live, int n);
    // An event's created_at is up to this many seconds before the real time (section 20.3).
    static constexpr qint64 kBlurS = 600;
    // Round 14: a relay is asked from this long before it last finished sending: the blur plus 30 minutes for a
    // sender's clock that is off. What comes again is dropped as seen.
    static constexpr qint64 kSinceSlackS = kBlurS + 30 * 60;
    // The longest message limit in days (section 16).
    static constexpr int kLongestDays = 30;
    // NIP-40 expiration for an event made at createdAt (already blurred): that time plus kBlurS plus the longest message
    // limit, rounded up to the next whole day (UTC). Never before the real time plus any chat's limit, and the same for
    // every chat, so a relay learns neither the sending time nor the chat's limit from it (section 20.3).
    static qint64 expiration(qint64 createdAt);
    // Opens the sending sockets now (a call is starting), so the next publish goes out at once.
    // They close after the usual few minutes of quiet: nothing is sent to keep them open.
    void warmSenders();

    // NIP-01: the event id is the SHA-256 of [0,pubkey,created_at,kind,tags,content] as compact JSON.
    static QByteArray serialize(const QString &pubkey, qint64 createdAt, int kind, const QJsonArray &tags, const QString &content);
    static QString idOf(const QJsonObject &ev);
    // A signed event. createdAt < 0 means now; empty aux means random.
    static QJsonObject event(int kind, const QJsonArray &tags, const QString &content, const QByteArray &secret,
                             qint64 createdAt = -1, const QByteArray &aux = QByteArray());
    // Envelope padding (shared/docs/protocol.md section 20): the size a relay sees is one of 1 KB, 4 KB, 16 KB, 32 KB,
    // then 16 KB steps. pad() adds a "z" field of zeros to the wrapping JSON so its base64 is exactly that size.
    static int padBucket(int n);
    static QByteArray pad(const QByteArray &json);
    // Checks shared/vectors/nostr-vectors.json (and pad-vectors.json next to it); prints what it found. Returns true when everything matches.
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void connectedChanged();
    void publicKeyChanged();
    void keepAliveChanged();

private:
    friend class NostrWorker;
    // What the relay thread hands over, at most about 30 times a second (one queued call for a burst).
    struct Incoming {
        QByteArray id;         // 32 bytes
        QString content;       // the event's content, as it came
        QByteArray body;       // opened on the relay thread (opened): what onOpened gets
        bool opened = false;
        bool tried = false;    // the relay thread tried to open it (and couldn't, when !opened)
        bool ring = false;     // for the ring mailbox (cap b1): onRing, never onEnvelope
        bool live = false;     // a ring that came after its request's EOSE (sent now, not kept on the relay)
        int gen = 0;           // which inbox request it came on
    };
    struct Batch {
        QList<Incoming> events;
        QList<quint64> oks;    // publishes a relay said OK to
        int live = -1;         // relays open now (-1: not changed)
        bool eoseChanged = false;
        QHash<QString, qint64> eose;
        QStringList status;    // for Labs › Performance check (only while one runs)
    };
    // The keys the relay thread works with: copies taken on this thread (pushKeys), never the vault itself.
    struct Keys {
        QByteArray secret;
        QString pub;
        QStringList rings;
        QList<QByteArray> ringSecrets;
        Opener open;
    };
    void take(const Batch &b);
    void pushKeys();
    void post(std::function<void(NostrWorker *)> f);
    QByteArray secret() const;
    mutable QByteArray m_secretCache;   // read from the vault once, not for every event
    // The taken events' list and the time of the last one are written at most every 30 seconds, not once per event
    // (and at once when Huginn closes).
    void noteSeenSoon();
    void flushSeen();
    void noteSeen(const QByteArray &id);
    bool m_seenDirty = false;
    qint64 m_takenAt = 0;
    QList<QByteArray> m_seen;           // what is kept in the vault (the relay thread has its own copy to skip by)
    QSet<QByteArray> m_seenSet;
    QHash<QString, qint64> m_eoseAt;    // the relay thread's cursors, as it last said
    QStringList m_status;
    std::function<QByteArray()> m_secret;
    mutable QString m_pub;
    bool m_keepAlive = false;
    bool m_keysPushed = false;
    int m_live = 0;
    quint64 m_nextToken = 0;
    int m_batches = 0;                  // batches taken (--perf-selftest)
    QHash<quint64, std::function<void()>> m_okWait;   // publish: what runs on its first OK
    QThread *m_thread = nullptr;
    NostrWorker *m_worker = nullptr;    // lives on m_thread; only ever reached through post()
};
