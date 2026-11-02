#pragma once

#include "stun.h"

#include <QHash>
#include <QHostAddress>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QStringList>
#include <QTimer>
#include <functional>
#include <map>

class QSslSocket;
class QUdpSocket;

namespace CallNet
{
QHostAddress toHost(const Stun::Address &a);
Stun::Address fromHost(const QHostAddress &h, quint16 port);
// Looks host up (DNS), up to tries times a second apart; an IP literal is used as it is.
// done gets a null address when the name couldn't be looked up.
void resolve(const QString &host, QObject *ctx, std::function<void(const QHostAddress &)> done, int tries = 3);
// The same, giving the first IPv4 and the first IPv6 address (either may be null).
void resolveBoth(const QString &host, QObject *ctx, std::function<void(const QHostAddress &v4, const QHostAddress &v6)> done, int tries = 3);
}

// A TURN login from the relay Worker (shared/docs/relay.md).
struct TurnLogin {
    QStringList urls;
    QString username, credential;
    qint64 expiresMs = 0;
    bool valid() const { return !username.isEmpty() && !credential.isEmpty() && !urls.isEmpty(); }
};

// Asks the relay Worker for a TURN login (HTTPS) when a call needs one; keeps it in
// memory until 5 minutes before it expires. Follows the Worker's rules: after 429 nothing until the
// next UTC day, after a 5xx at least 60 s. The credential is never logged.
class RelayCredentials : public QObject
{
    Q_OBJECT
public:
    explicit RelayCredentials(QObject *parent = nullptr);
    // identityKey: this device's chat key (SPKI DER); its SHA-256 is the Worker's "id".
    // done is called exactly once, never from inside get().
    void get(const QByteArray &identityKey, std::function<void(const TurnLogin &login, const QString &why)> done);
    // Builds the request body (also used by the self-test); tok is the install token, left out when empty.
    static QByteArray body(const QByteArray &identityKey, qint64 unixSeconds, const QByteArray &secret, const QString &tok = QString());

    // Install tokens (relay.md "Install tokens"): an install registers once with a proof of work and gets a token the
    // Worker signed, renewed by every login; logins with it never share a pool with requests that only know APP_SECRET.
    static constexpr int kPowBits = 18;
    static constexpr qint64 kTokenSpareS = 86400;   // a token with less left than this is registered again first
    // SHA-256 of "huginn-relay pow v1:" + id + ":" + t + ":" + pow starts with bits zero bits.
    static bool powOk(const QByteArray &idHex, qint64 t, qint64 pow, int bits = kPowBits);
    // The smallest such pow (about 260,000 tries on average), or -1 after maxTries.
    static qint64 solvePow(const QByteArray &idHex, qint64 t, int bits = kPowBits, qint64 maxTries = qint64(1) << 26);
    // {"id", "t", "sig", "pow"}.
    static QByteArray regBody(const QByteArray &identityKey, qint64 unixSeconds, const QByteArray &secret, qint64 pow);
    // A kept token "<id>|<tok>|<exp>": the token when it is this id's and more than kTokenSpareS is left, else empty.
    static QString tokenFor(const QString &kept, const QString &idHex, qint64 nowS);
    // {"tok", "exp"} in an answer, as kept; empty when there's none.
    static QString tokenOf(const QJsonObject &o, const QString &idHex);
    // The Worker's time in a 401 {"error":"stale","now":...}, or -1.
    static qint64 staleNow(const QJsonObject &o);

private:
    void fetch();
    void postTurn(const QString &tok);
    void registerThen();
    void finish(const TurnLogin &login, const QString &why);
    QString idHex() const;
    qint64 workerNow() const;

    QNetworkAccessManager m_net;
    TurnLogin m_cache;
    QByteArray m_identity;
    qint64 m_blockedUntil = 0;
    qint64 m_regNotBefore = 0;   // ms: no registration before (the next UTC day after a 429, else an hour)
    qint64 m_offsetS = 0;        // the Worker's clock less this device's, learned from a "stale" answer
    int m_retries = 0;
    bool m_busy = false;
    QList<std::function<void(const TurnLogin &, const QString &)>> m_waiting;
};

// One TURN allocation (RFC 8656) on Cloudflare: Allocate with the long-term credential and
// MESSAGE-INTEGRITY, Refresh before it lapses, ChannelBind per peer (which also makes the permission,
// refreshed every 4 minutes), ChannelData for media. UDP first; if that gets no answer, TURN over TLS
// on port 443, then any other turns: URL. Audio goes only through here over the internet: no STUN,
// no direct internet path.
class TurnClient : public QObject
{
    Q_OBJECT
public:
    explicit TurnClient(const TurnLogin &login, QObject *parent = nullptr);
    ~TurnClient() override;

    void start();
    void stop();   // deallocates (Refresh with lifetime 0)
    bool ready() const { return m_relayed.valid(); }
    // The reason failed() gives when not one server name could be looked up.
    static constexpr const char kCantReach[] = "Can't reach the call relay right now";
    // The relayed transport address: the only address offers and answers carry.
    Stun::Address relayed() const { return m_relayed; }
    bool overTls() const { return m_tls != nullptr; }
    void addPeer(const Stun::Address &peer);
    bool send(const Stun::Address &peer, const QByteArray &data);

    struct Server {
        QString host;
        quint16 port = 0;
        bool tls = false;
    };
    // The servers to try, in order, from the Worker's URLs (public for the self-test).
    static QList<Server> servers(const QStringList &urls);

Q_SIGNALS:
    void allocated();
    void failed(const QString &why);
    void data(const Stun::Address &peer, const QByteArray &bytes);

private:
    struct Pending {
        Stun::Message msg;
        bool auth = false;
        int tries = 0;
        int rto = 250;
        QTimer *timer = nullptr;
    };
    struct Peer {
        Stun::Address addr;
        quint16 channel = 0;
        bool bound = false;
    };

    void tryNext();
    void connectUdp(const QHostAddress &host, quint16 port);
    void connectTls(const Server &s, const QHostAddress &ip);
    void request(quint16 method, const std::vector<std::pair<quint16, QByteArray>> &attrs, bool auth = true,
                 const Stun::Address &peer = Stun::Address());
    void transmit(const QByteArray &tid);
    void writeRaw(const QByteArray &bytes);
    void onDatagrams();
    void onTlsData();
    void onPacket(const QByteArray &bytes);
    void onResponse(const Stun::Message &m, const QByteArray &bytes);
    void allocate();
    void refresh(quint32 lifetime);
    void bind(Peer &p);
    void dropPending();
    void fail(const QString &why);

    TurnLogin m_login;
    QList<Server> m_servers;
    int m_next = 0;
    QUdpSocket *m_udp = nullptr;
    QSslSocket *m_tls = nullptr;
    QByteArray m_tlsBuf;
    QHostAddress m_serverAddr;
    quint16 m_serverPort = 0;
    QString m_realm, m_nonce;
    QByteArray m_key;
    Stun::Address m_relayed;
    std::map<QByteArray, Pending> m_pending;
    QList<Peer> m_peers;
    quint16 m_nextChannel = 0x4000;
    QTimer m_refresh, m_rebind, m_giveUp;
    bool m_stopped = false;
    QHash<QString, QHostAddress> m_resolved;   // per allocation attempt: names looked up
    QSet<QString> m_unresolved;                // and names it couldn't (not asked again)
    QHash<QString, QHostAddress> m_resolved6;  // a name's IPv6 address, tried when its IPv4 one fails
    Server m_altServer;                        // the server to try again over IPv6 before the next one
    QHostAddress m_altIp;
};
