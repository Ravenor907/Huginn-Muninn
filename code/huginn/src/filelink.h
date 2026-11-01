#pragma once

#include "call/stun.h"
#include "chatcrypto.h"

#include <QBitArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>
#include <functional>

class Chat;
class QFile;
class QTimer;
class QUdpSocket;
class StunProbe;

// Files over the internet, straight from one device to the other (cap f1, shared/docs/protocol.md
// section 14), the same as Muninn's FileLink.kt. Never through Cloudflare's call relay: both sides
// learn their own internet address from a STUN server (DirectPath::stunServers(), the address lookup only),
// swap it in a sealed `file` message, and punch through their routers by sending to each other at
// once, as the Direct path of calls does (5.8.2). Only between two people who trust each other with
// their addresses ("Direct calls" on, as for Family). The file then goes in numbered 1100-byte pieces,
// each sealed with AES-256-GCM under a key only the two ends know; the receiver says which pieces it
// has and lost ones go again. Files of 2 MB or less that can't go direct use the relays as pieces.
class FileLink : public QObject
{
    Q_OBJECT
    // Every transfer this run: [{id, peer, name, size, mine, state, done, note, path, wifi}]. state: "connecting",
    // "offered", "asking" (a big file waiting for Accept or Decline), "sending", "done", "failed". path: mine, the
    // file sent; theirs, where it was saved once done. wifi: too big for the internet route (Send on Wi-Fi).
    // A file of theirs coming over the internet route shows too (id: its message id), until its message is in.
    Q_PROPERTY(QVariantList transfers READ transfers NOTIFY changed)

public:
    static const char kCap[];                   // "f1"
    static constexpr int kChunk = 1100;
    static constexpr int kWindow = 96;
    static constexpr int kRtoMs = 500;
    static constexpr int kPunchMs = 20000;
    static constexpr int kIdleMs = 30000;
    static constexpr qint64 kMaxBytes = 4LL * 1024 * 1024 * 1024;
    // From someone trusted, a file smaller than this arrives by itself; a bigger one asks Accept or Decline first.
    static constexpr qint64 kAutoBytes = 50LL * 1024 * 1024;
    // How long an offer waits for an answer: longer for a file the other side asks about first.
    static constexpr int kAnswerMs = 120000;
    static constexpr int kAskAnswerMs = 10 * 60000;
    static const qint64 kRelayMax;              // the largest file the relays take (Photos' 130 pieces)
    // A file small enough for the relays goes that way when no answer comes this soon (their device may be off).
    static constexpr int kRelayAfterMs = 30000;
    // How long an offer of a file of this size waits: a bigger one as long as the other side may ask first.
    static int answerMs(qint64 size) { return size <= kRelayMax ? kRelayAfterMs : kAskAnswerMs; }

    // What a file on its way says, the same words as Muninn's FileText (checked in selfTest).
    static int percent(qint64 done, qint64 size);
    static QString statusText(const QVariantMap &t, const QString &who);
    // A file of mine sent as a message over the internet route: state "sending", "sent", "delivered", "read";
    // fraction: how much a route took so far, below 0 before anything did.
    static QString relayText(const QString &state, bool failed, double fraction);
    static QString tooBig();
    // "2.1 MB/s · 12 s left"; empty until a second has passed and something moved.
    static QString paceText(qint64 done, qint64 total, qint64 ms);
    static QString leftText(qint64 seconds);
    static QString sizeText(qint64 bytes);
    static QString iconFor(const QString &name);
    enum Type : quint8 { Probe = 1, Data = 2, Ack = 3, Done = 4 };

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

    // Sends a file to a contact: straight when both trust each other, through the relays when it's
    // 2 MB or less and can't go straight. "" when it's on its way, else what to tell the person.
    Q_INVOKABLE QString send(const QString &peer, const QString &path);
    Q_INVOKABLE void cancel(const QString &id);
    // A big file waiting ("asking"): take it now, or say no (the sender hears so and it's gone here).
    Q_INVOKABLE void accept(const QString &id);
    Q_INVOKABLE void decline(const QString &id);
    Q_INVOKABLE void dismiss(const QString &id);
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
    Q_INVOKABLE bool openFile(const QString &path) const;
    Q_INVOKABLE bool showInFolder(const QString &path) const;

    // A file of mine that can't go at all: its card says why (wifi: Send on Wi-Fi helps).
    void addFailed(const QString &peer, const QString &name, qint64 size, const QString &why, const QString &path = {}, bool wifi = false,
                   const QString &id = {});
    // A file of theirs coming over the internet route: have of n pieces. Done: its message is in (or half of it was
    // let go); a card for the same file that couldn't come straight goes too.
    void relayIn(const QString &msgId, const QString &peer, const QString &name, int have, int n);
    void relayInDone(const QString &msgId, const QString &peer = {}, const QString &name = {});
    // A file that came as a message: saved in Downloads at once. Its full path, or empty when it couldn't be.
    QString saveBytes(const QByteArray &bytes, const QString &name);

    // An opened, checked (ok()) room "file" message from a contact.
    void onSignal(const QString &from, const QJsonObject &p);

    // Hooks: Chat's by default, the self-test's own in the test.
    std::function<void(const QString &peer, const QJsonObject &msg)> signal;
    std::function<bool(const QString &peer)> trusted;
    std::function<bool(const QString &peer)> peerHasF1;
    // Their app waits kAskAnswerMs for a big file (its caps say e1): only then is a big file asked about first.
    // An older sender gives up after kAnswerMs, so its big files are taken as before. Unset: it waits.
    std::function<bool(const QString &peer)> waitsForAsk;
    std::function<QString(const QString &peer, const QByteArray &bytes, const QString &name)> relay;
    std::function<QString(const QString &peer)> nameOf;
    QString saveDir;
    bool testLocal = false;                     // self-test: 127.0.0.1 only, no STUN
    int testDropEvery = 0;                      // self-test: lose every Nth data packet the first time

Q_SIGNALS:
    void changed();
    void notice(const QString &text);

private:
    struct Xfer;
    void startSocket(Xfer *x, std::function<void(QJsonArray)> ready);
    void sendOffer(Xfer *x, const QJsonArray &cands);
    void acceptOffer(Xfer *x);
    void startPunch(Xfer *x);
    void punchTick(Xfer *x);
    bool startData(Xfer *x);
    void dataTick(Xfer *x);
    void onDatagram(Xfer *x);
    void sendAck(Xfer *x);
    void receivedAll(Xfer *x);
    void fail(Xfer *x, const QString &why, bool tell = true);
    // No straight path for a file of mine: a small one goes over the internet route instead, else it fails with why.
    void fallBack(Xfer *x, const QString &why);
    void drop(const QString &id);
    void finish(Xfer *x);
    void end(Xfer *x);
    void put(Xfer *x, bool now = false);
    void sendPacket(Xfer *x, quint8 type, const QByteArray &payload, const Stun::Address &to);
    QString freeName(const QString &name) const;
    QString who(const QString &peer) const { return nameOf ? nameOf(peer) : peer.left(8); }

    Chat *m_chat;
    QHash<QString, Xfer *> m_x;
    QList<QVariantMap> m_list;                  // what transfers() shows, newest last
    qint64 m_lastShown = 0;
};
