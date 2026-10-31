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

class QWebSocket;

// Messages for people who aren't nearby, through public Nostr relays (no accounts, no server to
// run). Each device has a Nostr key; its public half is its mailbox. A message is an event of
// kind 1059 addressed to the mailbox, signed by a throwaway key, so the event itself doesn't name its sender.
// The content is already sealed with the chat keys (ChatCrypto), so relays only see scrambled text, padded
// to a few fixed sizes (Chat::wrap). Sending uses its own short-lived sockets that never ask for the inbox
// or sign in as this computer's mailbox; the internet address can still link the two unless a VPN is used.
// Same behaviour and wire format as Muninn's Nostr.kt.
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
    // Cap b1: the ring mailboxes and their Nostr secrets, from this device's ring key outside the vault (empty: none;
    // two for a while after a block renewed it). They are asked for on an inbox request of their own, locked or not,
    // and relays that ask get a sign-in as each.
    std::function<QStringList()> ringMailboxes;
    std::function<QList<QByteArray>()> ringSecrets;
    // Every event for the ring mailbox (its content): never kept, never handed to onEnvelope.
    std::function<void(const QString &content)> onRing;
    // The events already taken are kept in the vault (main.cpp): keepSeen saves the list, restoreSeen
    // reads it back when the vault opens (and moves in a list found in plain settings, removing it there only once the
    // vault has it). keepSeen returns whether the list was written.
    std::function<bool(const QByteArray &stored)> keepSeen;
    void restoreSeen(const QByteArray &stored);

    int connected() const { return int(m_live.size()); }
    // The mailbox (x-only public key in hex). Cached, so it still works while locked; empty if never unlocked.
    QString publicKey() const;
    // QSettings nostr/relays (one wss:// address per line) when set, otherwise defaultRelays().
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
    // Sends sealed content to someone's mailbox through every relay, on sending sockets of their own.
    // days > 0: the event carries a NIP-40 expiration tag (expiration(), the same for every chat). Returns how many relays are connected now.
    Q_INVOKABLE int publish(const QString &mailboxHex, const QString &content, int days = 0);
    // An event's created_at is up to this many seconds before the real time (section 20.3).
    static constexpr qint64 kBlurS = 600;
    // The longest message limit in days (section 16).
    static constexpr int kLongestDays = 30;
    // NIP-40 expiration for an event made at createdAt (already blurred): that time plus kBlurS plus the longest message
    // limit, rounded up to the next whole day (UTC). Never before the real time plus any chat's limit, and the same for
    // every chat, so a relay learns neither the sending time nor the chat's limit from it (section 20.3).
    static qint64 expiration(qint64 createdAt);
    // Opens the sending sockets now (a call is starting), so the next publish goes out at once.
    // They close after the usual 20 s of quiet: nothing is sent to keep them open.
    void warmSenders();

    // NIP-01: the event id is the SHA-256 of [0,pubkey,created_at,kind,tags,content] as compact JSON.
    static QByteArray serialize(const QString &pubkey, qint64 createdAt, int kind, const QJsonArray &tags, const QString &content);
    static QString idOf(const QJsonObject &ev);
    // A signed event. createdAt < 0 means now; empty aux means random.
    static QJsonObject event(int kind, const QJsonArray &tags, const QString &content, const QByteArray &secret,
                             qint64 createdAt = -1, const QByteArray &aux = QByteArray());
    // Envelope padding (shared/docs/protocol-0.6.md section 20): the size a relay sees is one of 1 KB, 4 KB, 16 KB, 32 KB,
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
    struct Link {
        QPointer<QWebSocket> ws;
        bool ponged = true;
    };

    // A socket that only sends: no REQ, no AUTH. Closes 20 s after the last thing given to it.
    struct Sender {
        QPointer<QWebSocket> ws;
        QStringList queue;
        bool open = false;
        QPointer<QTimer> closer;
    };
    void send(const QString &url, const QStringList &msgs);
    void dropSender(const QString &url, QWebSocket *ws);
    QHash<QString, Sender> m_senders;

    QByteArray secret() const;
    void open(const QString &url);
    void opened(const QString &url, QWebSocket *ws);
    void gone(const QString &url, QWebSocket *ws);
    void drop(QWebSocket *ws);
    void subscribe(QWebSocket *ws);
    // NIP-42: signs in as my mailbox (while unlocked) and as the ring mailbox (always, cap b1).
    void signIn(const QString &url, QWebSocket *ws, const QString &challenge);
    QString m_ringSubId;
    QHash<QString, QString> m_challenges;   // per relay: its last sign-in challenge, to sign in again once unlocked
    void onMessage(const QString &url, QWebSocket *ws, const QString &text);
    void handle(const QJsonObject &ev);
    void setLive(const QString &url, bool live);

    std::function<QByteArray()> m_secret;
    mutable QString m_pub;
    QString m_subId;
    bool m_keepAlive = false;
    QHash<QString, Link> m_links;     // exactly one socket per relay
    QSet<QString> m_live;             // relays whose socket is open
    QHash<QString, int> m_retries;
    QStringList m_outbox;             // events waiting for a relay
    QStringList m_seen;               // oldest first, at most 2000
    QSet<QString> m_seenSet;
    QSet<QString> m_working;
    QSet<QString> m_announced;        // announced while locked, not opened yet
};
