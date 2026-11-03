#include "turnclient.h"
#include "../busy.h"

#include "callcrypto.h"
#include "relayconfig.h"
#include "../settings.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QHostInfo>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QSslSocket>
#include <QTcpSocket>
#include <QTimeZone>
#include <QUdpSocket>
#include <QUrl>
#include <QSettings>
#include <thread>

namespace
{
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
const int kUdpGiveUpMs = 3500;     // no Allocate answer over UDP by then: try TLS
const int kTlsGiveUpMs = 9000;
const int kRebindMs = 4 * 60 * 1000;   // permissions last 5 minutes, channels 10
}

void CallNet::resolve(const QString &host, QObject *ctx, std::function<void(const QHostAddress &)> done, int tries)
{
    // IPv4 first (what the relay hands out for calls), else IPv6.
    resolveBoth(host, ctx, [done](const QHostAddress &v4, const QHostAddress &v6) { done(v4.isNull() ? v6 : v4); }, tries);
}

void CallNet::resolveBoth(const QString &host, QObject *ctx, std::function<void(const QHostAddress &, const QHostAddress &)> done, int tries)
{
    // An IP literal needs no lookup; a name goes to the system resolver.
    const QHostAddress literal(host);
    if (!literal.isNull()) {
        const bool v4 = literal.protocol() == QAbstractSocket::IPv4Protocol;
        QTimer::singleShot(0, ctx, [done, literal, v4] { done(v4 ? literal : QHostAddress(), v4 ? QHostAddress() : literal); });
        return;
    }
    QPointer<QObject> guard(ctx);
    QHostInfo::lookupHost(host, ctx, [host, ctx, guard, done, tries](const QHostInfo &info) {
        if (!guard)
            return;
        QHostAddress v4, v6;
        for (const QHostAddress &x : info.addresses()) {
            if (v4.isNull() && x.protocol() == QAbstractSocket::IPv4Protocol)
                v4 = x;
            else if (v6.isNull() && x.protocol() == QAbstractSocket::IPv6Protocol)
                v6 = x;
        }
        if (!v4.isNull() || !v6.isNull() || tries <= 1) {
            done(v4, v6);
            return;
        }
        QTimer::singleShot(1000, ctx, [host, ctx, done, tries] { resolveBoth(host, ctx, done, tries - 1); });
    });
}

QHostAddress CallNet::toHost(const Stun::Address &a)
{
    if (a.ip.size() == 4)
        return QHostAddress((quint32(quint8(a.ip[0])) << 24) | (quint32(quint8(a.ip[1])) << 16) | (quint32(quint8(a.ip[2])) << 8) | quint32(quint8(a.ip[3])));
    if (a.ip.size() == 16)
        return QHostAddress(reinterpret_cast<const quint8 *>(a.ip.constData()));
    return QHostAddress();
}

Stun::Address CallNet::fromHost(const QHostAddress &h, quint16 port)
{
    Stun::Address a;
    a.port = port;
    bool v4 = false;
    const quint32 ip4 = h.toIPv4Address(&v4);
    if (v4) {
        a.ip = QByteArray(4, 0);
        a.ip[0] = char(ip4 >> 24);
        a.ip[1] = char(ip4 >> 16);
        a.ip[2] = char(ip4 >> 8);
        a.ip[3] = char(ip4);
    } else if (h.protocol() == QAbstractSocket::IPv6Protocol) {
        const Q_IPV6ADDR v6 = h.toIPv6Address();
        a.ip = QByteArray(reinterpret_cast<const char *>(v6.c), 16);
    }
    return a;
}

// ------------------------------------------------------------------ the Worker

RelayCredentials::RelayCredentials(QObject *parent) : QObject(parent) {}

QByteArray RelayCredentials::body(const QByteArray &identityKey, qint64 t, const QByteArray &secret, const QString &tok, bool renew)
{
    const QByteArray id = CallCrypto::sha256(identityKey).toHex();
    const QByteArray sig = CallCrypto::hmacSha256(secret, id + "." + QByteArray::number(t)).toHex();
    QJsonObject o{{QStringLiteral("id"), QString::fromLatin1(id)}, {QStringLiteral("t"), t}, {QStringLiteral("sig"), QString::fromLatin1(sig)}};
    if (!tok.isEmpty())
        o.insert(QStringLiteral("tok"), tok);
    if (renew)
        o.insert(QStringLiteral("rn"), 1);
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

qint64 RelayCredentials::retryMs(const QJsonObject &o, qint64 untilTomorrowMs)
{
    if (!o.value(QStringLiteral("retry")).isDouble())
        return -1;
    return qBound(qint64(60000), qint64(o.value(QStringLiteral("retry")).toDouble()) * 1000, qMax(qint64(60000), untilTomorrowMs));
}

QByteArray RelayCredentials::regBody(const QByteArray &identityKey, qint64 t, const QByteArray &secret, qint64 pow)
{
    QJsonObject o = QJsonDocument::fromJson(body(identityKey, t, secret)).object();
    o.insert(QStringLiteral("pow"), pow);
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

namespace
{
bool zeroBits(const QByteArray &d, int bits)
{
    int left = bits;
    for (int i = 0; i < d.size() && left > 0; ++i) {
        const int v = quint8(d[i]);
        if (left >= 8) {
            if (v != 0)
                return false;
            left -= 8;
        } else {
            return (v >> (8 - left)) == 0;
        }
    }
    return left <= 0;
}
}

bool RelayCredentials::powOk(const QByteArray &idHex, qint64 t, qint64 pow, int bits)
{
    if (pow < 0)
        return false;
    return zeroBits(CallCrypto::sha256("huginn-relay pow v1:" + idHex + ":" + QByteArray::number(t) + ":" + QByteArray::number(pow)), bits);
}

qint64 RelayCredentials::solvePow(const QByteArray &idHex, qint64 t, int bits, qint64 maxTries)
{
    const QByteArray head = "huginn-relay pow v1:" + idHex + ":" + QByteArray::number(t) + ":";
    QCryptographicHash h(QCryptographicHash::Sha256);
    for (qint64 n = 0; n < maxTries; ++n) {
        h.reset();
        h.addData(head);
        h.addData(QByteArray::number(n));
        if (zeroBits(h.result(), bits))
            return n;
    }
    return -1;
}

QString RelayCredentials::tokenFor(const QString &kept, const QString &idHex, qint64 nowS)
{
    const QStringList p = kept.split(QLatin1Char('|'));
    if (p.size() != 3 || p[0] != idHex || p[1].isEmpty())
        return QString();
    bool ok = false;
    const qint64 exp = p[2].toLongLong(&ok);
    return ok && exp - nowS > kTokenSpareS ? p[1] : QString();
}

QString RelayCredentials::tokenOf(const QJsonObject &o, const QString &idHex)
{
    const QString tok = o.value(QStringLiteral("tok")).toString();
    const qint64 exp = qint64(o.value(QStringLiteral("exp")).toDouble());
    if (tok.isEmpty() || tok.contains(QLatin1Char('|')) || exp <= 0)
        return QString();
    return idHex + QLatin1Char('|') + tok + QLatin1Char('|') + QString::number(exp);
}

qint64 RelayCredentials::staleNow(const QJsonObject &o)
{
    if (o.value(QStringLiteral("error")).toString() != QLatin1String("stale") || !o.value(QStringLiteral("now")).isDouble())
        return -1;
    return qint64(o.value(QStringLiteral("now")).toDouble());
}

QString RelayCredentials::idHex() const
{
    return QString::fromLatin1(CallCrypto::sha256(m_identity).toHex());
}

qint64 RelayCredentials::workerNow() const
{
    return nowMs() / 1000 + m_offsetS;
}

void RelayCredentials::get(const QByteArray &identityKey, std::function<void(const TurnLogin &, const QString &)> done)
{
    // Reuse a login until 5 minutes before it expires.
    if (m_cache.valid() && nowMs() < m_cache.expiresMs - 300000) {
        const TurnLogin l = m_cache;
        QTimer::singleShot(0, this, [done, l] { done(l, QString()); });
        return;
    }
    if (!RelayConfig::configured()) {
        QTimer::singleShot(0, this, [done] { done(TurnLogin(), QStringLiteral("The internet relay isn't set up yet")); });
        return;
    }
    if (nowMs() < m_blockedUntil) {
        QTimer::singleShot(0, this, [done] { done(TurnLogin(), QStringLiteral("The internet relay is resting")); });
        return;
    }
    m_identity = identityKey;
    m_waiting << done;
    if (!m_busy)
        fetch();
}

void RelayCredentials::fetch()
{
    m_busy = true;
    m_retries = 0;
    // The install token lives in Huginn's settings, outside the vault: it only spends this install's own logins.
    const QString tok = tokenFor(Settings::store().value(QStringLiteral("relay/token")).toString(), idHex(), workerNow());
    if (tok.isEmpty() && nowMs() >= m_regNotBefore)
        registerThen();
    else
        postTurn(tok);
}

void RelayCredentials::registerThen()
{
    // The proof of work takes a moment: on a thread of its own, then back here.
    const QByteArray id = idHex().toLatin1();
    const qint64 t = workerNow();
    QPointer<RelayCredentials> self(this);
    std::thread([self, id, t] {
        const qint64 pow = solvePow(id, t);
        QMetaObject::invokeMethod(QCoreApplication::instance(), [self, t, pow] {
            if (!self)
                return;
            if (pow < 0) {
                self->m_regNotBefore = nowMs() + 3600000;
                self->postTurn(QString());
                return;
            }
            QNetworkRequest req(QUrl(QString::fromLatin1(RelayConfig::kWorkerUrl) + QStringLiteral("/register")));
            req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
            req.setTransferTimeout(10000);
            QNetworkReply *r = self->m_net.post(req, regBody(self->m_identity, t, QByteArray(RelayConfig::kAppSecret), pow));
            connect(r, &QNetworkReply::finished, self, [self, r] {
                r->deleteLater();
                const int status = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
                const QString kept = status == 200 ? tokenOf(QJsonDocument::fromJson(r->readAll()).object(), self->idHex()) : QString();
                if (kept.isEmpty()) {
                    // An older Worker (404), a full day or no answer: this login goes without a token.
                    if (status == 429) {
                        const QDateTime tomorrow(QDateTime::currentDateTimeUtc().date().addDays(1), QTime(0, 0), QTimeZone::UTC);
                        self->m_regNotBefore = tomorrow.toMSecsSinceEpoch();
                    } else {
                        self->m_regNotBefore = nowMs() + 3600000;
                    }
                } else {
                    QSettings st = Settings::store();
                    st.setValue(QStringLiteral("relay/token"), kept);
                }
                self->postTurn(tokenFor(kept, self->idHex(), self->workerNow()));
            });
        }, Qt::QueuedConnection);
    }).detach();
}

void RelayCredentials::postTurn(const QString &tok)
{
    QNetworkRequest req(QUrl(QString::fromLatin1(RelayConfig::kWorkerUrl) + QStringLiteral("/turn")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(10000);   // a captive or hostile network: give up soon, the call retries once
    QNetworkReply *r = m_net.post(req, body(m_identity, workerNow(), QByteArray(RelayConfig::kAppSecret), tok, true));
    connect(r, &QNetworkReply::finished, this, [this, r, tok] {
        r->deleteLater();
        const int status = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QJsonObject o = QJsonDocument::fromJson(r->readAll()).object();
        if (status == 401 && m_retries < 2) {
            ++m_retries;
            // The clock is off: once more, corrected by the Worker's own time.
            const qint64 now = staleNow(o);
            if (now >= 0) {
                m_offsetS = now - nowMs() / 1000;
                postTurn(tok);
                return;
            }
            // The token was refused (run out, or the Worker's secret changed): dropped and registered again once.
            const QString err = o.value(QStringLiteral("error")).toString();
            if (!tok.isEmpty() && (err == QLatin1String("bad_token") || err == QLatin1String("token_expired"))) {
                QSettings st = Settings::store();
                st.remove(QStringLiteral("relay/token"));
                if (nowMs() >= m_regNotBefore)
                    registerThen();
                else
                    postTurn(QString());
                return;
            }
        }
        if (status == 200) {
            TurnLogin l;
            const QJsonValue urls = o.value(QStringLiteral("urls"));
            if (urls.isArray()) {
                for (const QJsonValue &u : urls.toArray())
                    l.urls << u.toString();
            } else if (urls.isString()) {
                l.urls << urls.toString();
            }
            l.username = o.value(QStringLiteral("username")).toString();
            l.credential = o.value(QStringLiteral("credential")).toString();
            l.expiresMs = nowMs() + qint64(o.value(QStringLiteral("ttl")).toDouble()) * 1000;
            if (l.valid()) {
                // A login renews the token.
                const QString kept = tokenOf(o, idHex());
                if (!kept.isEmpty()) {
                    QSettings st = Settings::store();
                    st.setValue(QStringLiteral("relay/token"), kept);
                }
                m_cache = l;
                finish(l, QString());
                return;
            }
            finish(TurnLogin(), QStringLiteral("The internet relay didn't answer properly"));
            return;
        }
        bool busy = false;
        if (status == 429) {
            // A cap: not again until the next UTC day, or for as long as the Worker says ("retry", relay.md "Limits").
            const QDateTime tomorrow(QDateTime::currentDateTimeUtc().date().addDays(1), QTime(0, 0), QTimeZone::UTC);
            const qint64 untilTomorrow = tomorrow.toMSecsSinceEpoch() - nowMs();
            const qint64 retry = retryMs(o, untilTomorrow);
            busy = retry >= 0 && retry < untilTomorrow;
            m_blockedUntil = nowMs() + (retry >= 0 ? retry : untilTomorrow);
        } else if (status >= 500) {
            // No answer at all (status 0, say before a hotel's login page) doesn't rest the relay.
            m_blockedUntil = nowMs() + 60000;
        }
        // Worded like Muninn's TurnCreds; never a credential or an address.
        finish(TurnLogin(), status == 0 ? QStringLiteral("Couldn't reach the internet relay")
                            : status == 401 ? QStringLiteral("This device's clock looks wrong, so the internet relay said no")
                            : status == 429 ? (busy ? QStringLiteral("The internet relay is busy right now") : QStringLiteral("The internet relay is used up for today"))
                                            : QStringLiteral("The internet relay didn't answer properly"));
    });
}

void RelayCredentials::finish(const TurnLogin &login, const QString &why)
{
    m_busy = false;
    const auto waiting = m_waiting;
    m_waiting.clear();
    for (const auto &w : waiting)
        w(login, why);
}

// ------------------------------------------------------------------ TURN

TurnClient::TurnClient(const TurnLogin &login, QObject *parent) : QObject(parent), m_login(login)
{
    m_servers = servers(login.urls);
    m_refresh.setSingleShot(true);
    connect(&m_refresh, &QTimer::timeout, this, [this] { refresh(600); });
    m_rebind.setInterval(kRebindMs);
    connect(&m_rebind, &QTimer::timeout, this, [this] {
        for (Peer &p : m_peers)
            bind(p);
    });
    m_giveUp.setSingleShot(true);
    connect(&m_giveUp, &QTimer::timeout, this, [this] {
        if (!ready())
            tryNext();
    });
}

TurnClient::~TurnClient()
{
    stop();
}

QList<TurnClient::Server> TurnClient::servers(const QStringList &urls)
{
    // UDP first (the first turn: ... transport=udp), then TLS on 443, then any other turns:.
    QList<Server> udp, tls443, tls;
    for (const QString &raw : urls) {
        const bool secure = raw.startsWith(QLatin1String("turns:"));
        if (!secure && !raw.startsWith(QLatin1String("turn:")))
            continue;   // never STUN
        QString rest = raw.mid(secure ? 6 : 5);
        QString transport = secure ? QStringLiteral("tcp") : QStringLiteral("udp");
        const int q = rest.indexOf(QLatin1Char('?'));
        if (q >= 0) {
            const QString query = rest.mid(q + 1);
            rest = rest.left(q);
            if (query.contains(QLatin1String("transport=tcp")))
                transport = QStringLiteral("tcp");
        }
        const int colon = rest.lastIndexOf(QLatin1Char(':'));
        Server s;
        s.host = colon > 0 ? rest.left(colon) : rest;
        s.port = quint16(colon > 0 ? rest.mid(colon + 1).toUInt() : (secure ? 5349 : 3478));
        s.tls = secure;
        if (s.host.isEmpty() || !s.port)
            continue;
        if (!secure && transport == QLatin1String("udp"))
            udp << s;
        else if (secure && s.port == 443)
            tls443 << s;
        else if (secure)
            tls << s;
        // Plain TURN over TCP is skipped: TLS on 443 gets through the same firewalls.
    }
    QList<Server> out;
    if (!udp.isEmpty())
        out << udp.first();
    out << tls443 << tls;
    if (out.isEmpty()) {
        out << Server{QStringLiteral("turn.cloudflare.com"), 3478, false};
        out << Server{QStringLiteral("turn.cloudflare.com"), 443, true};
    }
    return out;
}

void TurnClient::start()
{
    m_stopped = false;
    m_next = 0;
    m_resolved.clear();
    m_unresolved.clear();
    m_resolved6.clear();
    m_altIp = QHostAddress();
    tryNext();
}

void TurnClient::tryNext()
{
    dropPending();
    if (m_udp) {
        m_udp->deleteLater();
        m_udp = nullptr;
    }
    if (m_tls) {
        m_tls->disconnect(this);
        m_tls->abort();
        m_tls->deleteLater();
        m_tls = nullptr;
    }
    m_tlsBuf.clear();
    m_realm.clear();
    m_nonce.clear();
    m_key.clear();
    if (m_stopped)
        return;
    if (!m_altIp.isNull()) {
        // The IPv4 address of this server didn't work: the same server over IPv6 before the next one.
        const QHostAddress ip = m_altIp;
        m_altIp = QHostAddress();
        if (m_altServer.tls)
            connectTls(m_altServer, ip);
        else
            connectUdp(ip, m_altServer.port);
        return;
    }
    if (m_next >= m_servers.size()) {
        // Not one name could be looked up: say so.
        fail(m_resolved.isEmpty() ? QString::fromLatin1(kCantReach) : QStringLiteral("relay unreachable"));
        return;
    }
    const Server s = m_servers.at(m_next++);
    auto go = [this, s](const QHostAddress &ip) {
        // An IPv4 address that fails is followed by the same name's IPv6 address, if it has one.
        if (ip.protocol() == QAbstractSocket::IPv4Protocol && m_resolved6.contains(s.host)) {
            m_altServer = s;
            m_altIp = m_resolved6.value(s.host);
        }
        if (s.tls)
            connectTls(s, ip);
        else
            connectUdp(ip, s.port);
    };
    if (m_resolved.contains(s.host)) {
        go(m_resolved.value(s.host));
        return;
    }
    if (m_unresolved.contains(s.host)) {
        QTimer::singleShot(0, this, &TurnClient::tryNext);
        return;
    }
    CallNet::resolveBoth(s.host, this, [this, s, go](const QHostAddress &v4, const QHostAddress &v6) {
        if (m_stopped || m_tls || m_udp)
            return;
        const QHostAddress ip = v4.isNull() ? v6 : v4;
        if (ip.isNull()) {
            m_unresolved.insert(s.host);
            tryNext();
            return;
        }
        m_resolved.insert(s.host, ip);
        if (!v4.isNull() && !v6.isNull())
            m_resolved6.insert(s.host, v6);
        go(ip);
    });
}

void TurnClient::connectUdp(const QHostAddress &host, quint16 port)
{
    m_udp = new QUdpSocket(this);
    Busy::nameSocket(m_udp, "turn");
    const bool v4 = host.protocol() == QAbstractSocket::IPv4Protocol;
    if (!m_udp->bind(v4 ? QHostAddress(QHostAddress::AnyIPv4) : QHostAddress(QHostAddress::AnyIPv6), 0)) {
        tryNext();
        return;
    }
    m_serverAddr = host;
    m_serverPort = port;
    connect(m_udp, &QUdpSocket::readyRead, this, &TurnClient::onDatagrams);
    m_giveUp.start(kUdpGiveUpMs);
    allocate();
}

void TurnClient::connectTls(const Server &s, const QHostAddress &ip)
{
    m_tls = new QSslSocket(this);
    Busy::nameSocket(m_tls, "turn");
    m_serverPort = s.port;
    connect(m_tls, &QSslSocket::encrypted, this, [this] { allocate(); });
    connect(m_tls, &QSslSocket::readyRead, this, &TurnClient::onTlsData);
    connect(m_tls, &QSslSocket::disconnected, this, [this] {
        if (!m_stopped && ready())
            fail(QStringLiteral("relay closed"));
    });
    connect(m_tls, &QSslSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        if (!m_stopped && !ready())
            QTimer::singleShot(0, this, &TurnClient::tryNext);
    });
    m_giveUp.start(kTlsGiveUpMs);
    // To the address looked up; the certificate is still checked against the server's name.
    m_tls->connectToHostEncrypted(ip.toString(), s.port, s.host);
}

void TurnClient::stop()
{
    if (m_stopped)
        return;
    if (ready())
        refresh(0);   // deallocate; fire and forget
    m_stopped = true;
    m_refresh.stop();
    m_rebind.stop();
    m_giveUp.stop();
    dropPending();
    m_relayed = Stun::Address();
    if (m_tls) {
        m_tls->disconnect(this);
        m_tls->disconnectFromHost();
    }
}

void TurnClient::dropPending()
{
    for (auto &p : m_pending)
        delete p.second.timer;
    m_pending.clear();
}

void TurnClient::fail(const QString &why)
{
    m_relayed = Stun::Address();
    m_refresh.stop();
    m_rebind.stop();
    Q_EMIT failed(why);
}

void TurnClient::allocate()
{
    QByteArray transport(4, 0);
    transport[0] = 17;   // UDP
    request(Stun::Allocate, {{Stun::RequestedTransport, transport}}, !m_key.isEmpty());
}

void TurnClient::refresh(quint32 lifetime)
{
    QByteArray v(4, 0);
    v[0] = char(lifetime >> 24);
    v[1] = char(lifetime >> 16);
    v[2] = char(lifetime >> 8);
    v[3] = char(lifetime);
    request(Stun::Refresh, {{Stun::Lifetime, v}});
}

void TurnClient::bind(Peer &p)
{
    QByteArray ch(4, 0);
    ch[0] = char(p.channel >> 8);
    ch[1] = char(p.channel);
    request(Stun::ChannelBind, {{Stun::ChannelNumber, ch}}, true, p.addr);
}

void TurnClient::request(quint16 method, const std::vector<std::pair<quint16, QByteArray>> &attrs, bool auth, const Stun::Address &peer)
{
    Pending p;
    p.msg.method = method;
    p.msg.cls = Stun::Request;
    p.msg.tid = Stun::newTid();
    for (const auto &a : attrs)
        p.msg.add(a.first, a.second);
    if (peer.valid())
        p.msg.addAddress(Stun::XorPeerAddress, peer);   // XORed with this request's own tid
    p.auth = auth && !m_key.isEmpty();
    p.timer = new QTimer(this);
    p.timer->setSingleShot(true);
    const QByteArray tid = p.msg.tid;
    connect(p.timer, &QTimer::timeout, this, [this, tid] { transmit(tid); });
    m_pending[tid] = p;
    transmit(tid);
}

void TurnClient::transmit(const QByteArray &tid)
{
    auto it = m_pending.find(tid);
    if (it == m_pending.end())
        return;
    Pending &p = it->second;
    if (p.tries >= (m_tls ? 1 : 6)) {
        const quint16 method = p.msg.method;
        delete p.timer;
        m_pending.erase(it);
        if (method == Stun::Refresh && ready() && !m_stopped)
            fail(QStringLiteral("relay stopped answering"));
        return;
    }
    Stun::Message m = p.msg;
    if (p.auth) {
        m.addText(Stun::Username, m_login.username);
        m.addText(Stun::Realm, m_realm);
        m.addText(Stun::Nonce, m_nonce);
    }
    writeRaw(Stun::encode(m, p.auth ? m_key : QByteArray(), false));
    ++p.tries;
    // UDP: 250 ms, doubling, at most 1.6 s apart. TLS is reliable: one try, 8 s to answer.
    p.timer->start(m_tls ? 8000 : p.rto);
    p.rto = qMin(p.rto * 2, 1600);
}

void TurnClient::writeRaw(const QByteArray &bytes)
{
    if (m_udp)
        m_udp->writeDatagram(bytes, m_serverAddr, m_serverPort);
    else if (m_tls && m_tls->isEncrypted())
        m_tls->write(bytes);
}

void TurnClient::onDatagrams()
{
    Busy::Scope busy("call relay");
    while (m_udp && m_udp->hasPendingDatagrams()) {
        QByteArray b(int(qMax<qint64>(0, m_udp->pendingDatagramSize())), 0);
        QHostAddress from;
        quint16 port = 0;
        const qint64 n = m_udp->readDatagram(b.data(), b.size(), &from, &port);
        if (n <= 0)
            continue;
        b.resize(int(n));
        // Only the relay itself talks on this socket.
        if (port != m_serverPort || !(from.isEqual(m_serverAddr, QHostAddress::TolerantConversion)))
            continue;
        onPacket(b);
    }
}

void TurnClient::onTlsData()
{
    Busy::Scope busy("call relay");
    if (!m_tls)
        return;
    m_tlsBuf += m_tls->readAll();
    for (;;) {
        const int n = Stun::frameLength(m_tlsBuf);
        if (n < 0) {
            fail(QStringLiteral("relay stream out of step"));
            return;
        }
        if (n == 0 || m_tlsBuf.size() < n)
            return;
        QByteArray frame = m_tlsBuf.left(n);
        m_tlsBuf.remove(0, n);
        onPacket(frame);
    }
}

void TurnClient::onPacket(const QByteArray &b)
{
    if (Stun::isChannelData(b)) {
        quint16 ch = 0;
        QByteArray data;
        if (!Stun::parseChannelData(b, &ch, &data))
            return;
        for (const Peer &p : std::as_const(m_peers))
            if (p.channel == ch) {
                Q_EMIT this->data(p.addr, data);
                return;
            }
        return;
    }
    Stun::Message m;
    if (!Stun::parse(b, &m))
        return;
    if (m.method == Stun::Data && m.cls == Stun::Indication) {
        const Stun::Address peer = m.address(Stun::XorPeerAddress);
        if (peer.valid())
            Q_EMIT this->data(peer, m.get(Stun::DataAttr));
        return;
    }
    if (m.cls == Stun::Success || m.cls == Stun::Error)
        onResponse(m, b);
}

void TurnClient::onResponse(const Stun::Message &m, const QByteArray &bytes)
{
    auto it = m_pending.find(m.tid);
    if (it == m_pending.end())
        return;
    // Once we have a key, a success must carry a valid MESSAGE-INTEGRITY. Errors may not: a 438 (stale
    // nonce) usually comes without one, and dropping it would stall the request until it times out (a
    // ChannelBind never bound, a Refresh that ends the allocation). An error can't do harm here: the
    // request is only sent again with the nonce it brings, or given up.
    if (m.cls == Stun::Success && !m_key.isEmpty() && it->second.auth && !Stun::checkIntegrity(bytes, m_key))
        return;
    Pending p = it->second;
    delete p.timer;
    m_pending.erase(it);

    if (m.cls == Stun::Error) {
        const int code = m.errorCode();
        if ((code == 401 && !p.auth) || code == 438) {
            // Learn (or renew) realm and nonce, then ask again with the credential.
            m_realm = m.text(Stun::Realm).isEmpty() ? m_realm : m.text(Stun::Realm);
            m_nonce = m.text(Stun::Nonce);
            m_key = Stun::longTermKey(m_login.username, m_realm, m_login.credential);
            std::vector<std::pair<quint16, QByteArray>> attrs;
            for (const auto &a : p.msg.attrs)
                if (a.first != Stun::XorPeerAddress)
                    attrs.push_back(a);
            // A peer address is XORed with the tid, so it is decoded and encoded again for the new one.
            request(p.msg.method, attrs, true, p.msg.address(Stun::XorPeerAddress));
            return;
        }
        if (p.msg.method == Stun::Allocate) {
            if (code == 437 && ready())
                return;
            // 401 with the credential: the login is no good. Anything else: try the next server.
            if (code == 401) {
                fail(QStringLiteral("relay login refused"));
                return;
            }
            QTimer::singleShot(0, this, &TurnClient::tryNext);
        }
        return;
    }

    switch (p.msg.method) {
    case Stun::Allocate: {
        const Stun::Address relayed = m.address(Stun::XorRelayedAddress);
        if (!relayed.valid()) {
            QTimer::singleShot(0, this, &TurnClient::tryNext);
            return;
        }
        m_giveUp.stop();
        m_altIp = QHostAddress();
        m_relayed = relayed;
        const quint32 life = m.u32(Stun::Lifetime, 600);
        m_refresh.start(int(qMax<quint32>(30, life > 60 ? life - 60 : life / 2)) * 1000);
        m_rebind.start();
        for (Peer &peer : m_peers)
            bind(peer);
        Q_EMIT allocated();
        break;
    }
    case Stun::Refresh: {
        const quint32 life = m.u32(Stun::Lifetime, 600);
        if (life > 0)
            m_refresh.start(int(qMax<quint32>(30, life > 60 ? life - 60 : life / 2)) * 1000);
        break;
    }
    case Stun::ChannelBind: {
        const quint16 ch = quint16((quint8(p.msg.get(Stun::ChannelNumber).at(0)) << 8) | quint8(p.msg.get(Stun::ChannelNumber).at(1)));
        for (Peer &peer : m_peers)
            if (peer.channel == ch)
                peer.bound = true;
        break;
    }
    default:
        break;
    }
}

void TurnClient::addPeer(const Stun::Address &peer)
{
    if (!peer.valid())
        return;
    for (const Peer &p : std::as_const(m_peers))
        if (p.addr == peer)
            return;
    if (m_nextChannel > 0x4FFF)
        return;
    Peer p;
    p.addr = peer;
    p.channel = m_nextChannel++;
    m_peers << p;
    if (ready())
        bind(m_peers.last());
}

bool TurnClient::send(const Stun::Address &peer, const QByteArray &data)
{
    if (!ready())
        return false;
    for (const Peer &p : std::as_const(m_peers)) {
        if (p.addr != peer)
            continue;
        if (p.bound) {
            writeRaw(Stun::channelData(p.channel, data, m_tls != nullptr));
        } else {
            // Until the channel is bound: a Send indication (no authentication on indications).
            Stun::Message m;
            m.method = Stun::Send;
            m.cls = Stun::Indication;
            m.tid = Stun::newTid();
            m.addAddress(Stun::XorPeerAddress, peer);
            m.add(Stun::DataAttr, data);
            writeRaw(Stun::encode(m));
        }
        return true;
    }
    return false;
}
