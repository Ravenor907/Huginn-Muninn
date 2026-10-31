#include "localsend.h"
#include "proof.h"
#include "lan.h"
#include "settings.h"
#include "system.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMimeDatabase>
#include <QNetworkInterface>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QRandomGenerator>
#include <QSslConfiguration>
#include <QSslSocket>
#include <QStorageInfo>
#include <QSysInfo>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QUuid>
#include <QStringList>
#include <QVariantMap>

namespace
{
constexpr quint16 kPort = 53317;
const QHostAddress kGroup(QStringLiteral("224.0.0.167"));
const QString kApi = QStringLiteral("/api/localsend/v2");

QString randomToken()
{
    return QUuid::createUuid().toString(QUuid::WithoutBraces).remove(QLatin1Char('-'));
}
}

LocalSend::LocalSend(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
{
    // Our certificate's SHA-256 is our fingerprint, as LocalSend's HTTPS mode defines it. Without a
    // certificate (it couldn't be made or saved) fall back to plain HTTP and a random fingerprint.
    m_tls = Tls::load();
    m_secure = m_tls.isValid() && QSslSocket::supportsSsl();
    m_fingerprint = m_secure ? m_tls.fingerprint : randomToken();
    QString host = QSysInfo::machineHostName();
    if (host.isEmpty() || host == QLatin1String("localhost"))
        host = QStringLiteral("handheld");
    m_alias = QStringLiteral("Huginn on %1").arg(host);

    if (m_secure) {
        QSslConfiguration cfg = QSslConfiguration::defaultConfiguration();
        cfg.setLocalCertificate(m_tls.cert);
        cfg.setPrivateKey(m_tls.key);
        // Ask for the sender's certificate but don't require it (not every Huginn or Muninn shows one,
        // and it's self-signed anyway): when one is shown, the fingerprint it claims must match it.
        cfg.setPeerVerifyMode(QSslSocket::QueryPeer);
        cfg.setCaCertificates({});
        m_serverTls = cfg;
    }
    // Wi-Fi comes and goes (and a VPN may come up): follow the home network's addresses.
    m_lanTimer.setInterval(5000);
    connect(&m_lanTimer, &QTimer::timeout, this, &LocalSend::bindLan);
    connect(&m_udp, &QUdpSocket::readyRead, this, &LocalSend::onDatagrams);
    // Peers' certificates are self-signed, so the usual checks can't apply; makeRequest() turns them
    // off and checkPeer() checks the fingerprint instead. This stays for any error that still comes up.
    connect(&m_net, &QNetworkAccessManager::sslErrors, this, [](QNetworkReply *r, const QList<QSslError> &) { r->ignoreSslErrors(); });

    m_announceTimer.setInterval(60000);
    connect(&m_announceTimer, &QTimer::timeout, this, [this] {
        announce();
        // Forget devices not heard from in 10 minutes.
        const QDateTime cutoff = QDateTime::currentDateTime().addSecs(-600);
        bool changed = false;
        for (auto it = m_devices.begin(); it != m_devices.end();) {
            if (it->seen < cutoff) { it = m_devices.erase(it); changed = true; }
            else ++it;
        }
        if (changed)
            Q_EMIT devicesChanged();
    });

    m_requestTimer.setSingleShot(true);
    m_requestTimer.setInterval(60000);
    connect(&m_requestTimer, &QTimer::timeout, this, [this] {
        if (m_pending.socket) {
            turnedDown(m_pending.ip);
            reply(m_pending.socket, 403);
            Q_EMIT finished(false, QStringLiteral("No answer in a minute, so the files from %1 were turned down.").arg(m_pending.from));
        }
        m_pending = {};
        Q_EMIT requestChanged();
    });

    m_sessionTimer.setInterval(15000);
    connect(&m_sessionTimer, &QTimer::timeout, this, [this] {
        if (m_session.active && m_session.touched.secsTo(QDateTime::currentDateTime()) > 120)
            endSession(false, QStringLiteral("%1 stopped sending.").arg(m_session.from));
    });
}

QJsonObject LocalSend::info(bool withAnnounce, bool announceFlag) const
{
    QJsonObject o{{QStringLiteral("alias"), m_alias},
                  {QStringLiteral("version"), QStringLiteral("2.1")},
                  {QStringLiteral("deviceModel"), QStringLiteral("Huginn")},
                  {QStringLiteral("deviceType"), QStringLiteral("desktop")},
                  {QStringLiteral("fingerprint"), m_fingerprint},
                  {QStringLiteral("port"), int(kPort)},
                  {QStringLiteral("protocol"), m_secure ? QStringLiteral("https") : QStringLiteral("http")},
                  {QStringLiteral("download"), false}};
    const bool hidden = silent && silent();
    if (hidden)
        o.insert(QStringLiteral("alias"), QStringLiteral("Huginn"));   // not even the computer's name
    if (chatInfo) {
        const QJsonObject extra = chatInfo();
        for (auto it = extra.begin(); it != extra.end(); ++it)
            o.insert(it.key(), it.value());
        // Cap s1: the chat key vouches for this certificate (never while hidden).
        const QString proof = m_secure && chatProve && !hidden ? chatProve(m_fingerprint) : QString();
        if (!proof.isEmpty())
            o.insert(QStringLiteral("chatProof"), proof);
    }
    if (withAnnounce)
        o.insert(QStringLiteral("announce"), announceFlag);
    return o;
}

QList<LocalSend::Peer> LocalSend::chatPeers() const
{
    QList<Peer> out;
    for (const Device &d : m_devices)
        if (!d.chatKey.isEmpty())
            out << Peer{QStringLiteral("%1://%2:%3").arg(d.protocol, Lan::urlHost(d.ip)).arg(d.port), d.chatKey, d.chatName, d.alias, d.type, d.proven};
    return out;
}

QString LocalSend::baseFor(const QString &chatKey, bool strict) const
{
    const Device *best = nullptr;
    for (const Device &d : m_devices) {
        if (d.chatKey != chatKey || (strict && !d.proven))
            continue;
        if (!best || (d.proven && !best->proven) || (d.proven == best->proven && d.seen > best->seen))
            best = &d;
    }
    return best ? QStringLiteral("%1://%2:%3").arg(best->protocol, Lan::urlHost(best->ip)).arg(best->port) : QString();
}

bool LocalSend::provenFor(const QString &chatKey) const
{
    for (const Device &d : m_devices)
        if (d.proven && d.chatKey == chatKey)
            return true;
    return false;
}

void LocalSend::turnedDown(const QString &ip)
{
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    if (m_prompts.size() > 256)
        m_prompts.removeIf([t](const QHash<QString, QList<qint64>>::iterator it) { return it.value().isEmpty() || t - it.value().last() > 600000; });
    QList<qint64> &q = m_prompts[ip];
    q << t;
    while (q.size() > 3)
        q.removeFirst();
}

bool LocalSend::asking(const QString &ip)
{
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    if (m_asked.size() > 512)
        m_asked.removeIf([t](const QHash<QString, qint64>::iterator it) { return t - it.value() > 10000; });
    if (t - m_asked.value(ip, 0) < 10000)
        return false;
    m_asked.insert(ip, t);
    return true;
}

// ------------------------------------------------------------------ certificates

QString LocalSend::expectedFor(const QUrl &url) const
{
    // The fingerprint a device at this address announced, newest first. Only for HTTPS: a device
    // with encryption off has a random fingerprint and no certificate to check.
    if (url.scheme() != QLatin1String("https"))
        return {};
    const Device *best = nullptr;
    for (const Device &d : m_devices)
        if (d.protocol == QLatin1String("https") && d.ip == url.host() && d.port == url.port(kPort) && (!best || d.seen > best->seen))
            best = &d;
    return best ? best->id : QString();
}

QNetworkRequest LocalSend::makeRequest(const QUrl &url, const QString &expected, bool anonymous) const
{
    QNetworkRequest req(url);
    if (url.scheme() != QLatin1String("https"))
        return req;
    QSslConfiguration c = QSslConfiguration::defaultConfiguration();
    // Peers' certificates are self-signed, so there's no authority to check them against;
    // checkPeer() compares the fingerprint instead.
    c.setPeerVerifyMode(QSslSocket::VerifyNone);
    // Peers are addressed by IP, and the name below is not a real host name: send none.
    c.setSslOption(QSsl::SslOptionDisableServerNameIndication, true);
    // Show our certificate: some LocalSend apps require one, and use it to prove our fingerprint. Not on a register
    // sent while hidden (anonymous): nothing that names this computer.
    if (m_secure && !anonymous) {
        c.setLocalCertificate(m_tls.cert);
        c.setPrivateKey(m_tls.key);
    }
    req.setSslConfiguration(c);
    // Qt reuses open connections by host, port and this name. A name per expected fingerprint
    // means a connection checked for one device is never reused for a request meant for another,
    // or one opened unchecked during a scan.
    // An anonymous one gets a name of its own, so a later request that shows the certificate never reuses it.
    if (!expected.isEmpty())
        req.setPeerVerifyName((anonymous ? QStringLiteral("a") : QStringLiteral("p")) + expected.left(40).toLower());
    return req;
}

void LocalSend::checkPeer(QNetworkReply *r, const QString &expected)
{
    // Runs right after the handshake, before anything is sent: Qt documents aborting here as the
    // way to refuse a peer. The fingerprint seen is kept for scans, which have nothing to expect yet.
    connect(r, &QNetworkReply::encrypted, this, [r, expected] {
        const QString got = Tls::fingerprintOf(r->sslConfiguration().peerCertificate());
        r->setProperty("peerFingerprint", got);
        if (!expected.isEmpty() && !Tls::sameFingerprint(got, expected)) {
            qWarning("Nearby sharing: refused %s, its certificate doesn't match the fingerprint it announced",
                     qPrintable(r->url().host()));
            r->setProperty("pinFailed", true);
            r->abort();
        }
    });
}

QNetworkRequest LocalSend::peerRequest(const QUrl &url) const
{
    return makeRequest(url, expectedFor(url));
}

void LocalSend::pinReply(QNetworkReply *r)
{
    checkPeer(r, expectedFor(r->request().url()));
}

// ------------------------------------------------------------------ start and stop

void LocalSend::start()
{
    if (running())
        return;
    m_error.clear();
    m_on = true;
    // UDP has to take the whole port to hear multicast (Linux delivers it only to a wildcard
    // socket); what it hears is checked in onDatagrams(), and it joins the group only on the home network.
    m_udp.bind(QHostAddress::AnyIPv4, kPort, QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint);
    bindLan();
    m_lanTimer.start();
    m_announceTimer.start();
    m_sessionTimer.start();
    Q_EMIT stateChanged();
}

QSslConfiguration LocalSend::serverTls()
{
    if (!(silent && silent())) {
        m_hiddenTls = QSslConfiguration();   // visible again: the next time hidden gets a new one
        return m_serverTls;
    }
    if (m_hiddenTls.localCertificate().isNull()) {
        const Tls::Identity id = Tls::throwaway();
        if (!id.isValid())
            return m_serverTls;   // couldn't make one: /register and /info still answer nothing
        m_hiddenTls = m_serverTls;
        m_hiddenTls.setLocalCertificate(id.cert);
        m_hiddenTls.setPrivateKey(id.key);
    }
    return m_hiddenTls;
}

void LocalSend::hiddenChanged()
{
    if (!m_secure)
        return;
    const QSslConfiguration cfg = serverTls();
    for (const auto &s : m_servers)
        if (auto *tls = qobject_cast<QSslServer *>(s.second))
            tls->setSslConfiguration(cfg);
}

void LocalSend::bindLan()
{
    if (!m_on)
        return;
    hiddenChanged();
    const QList<QHostAddress> want = Lan::addresses();
    bool changed = false;
    // Addresses that went away (Wi-Fi off, another network): stop listening there.
    for (auto it = m_servers.begin(); it != m_servers.end();) {
        if (!want.contains(it->first)) {
            it->second->close();
            it->second->deleteLater();
            it = m_servers.erase(it);
            changed = true;
        } else {
            ++it;
        }
    }
    QString failed;
    for (const QHostAddress &a : want) {
        bool have = false;
        for (const auto &s : m_servers)
            have = have || s.first == a;
        if (have)
            continue;
        QTcpServer *server = nullptr;
        if (m_secure) {
            auto *tls = new QSslServer(this);
            tls->setSslConfiguration(serverTls());
            // QSslServer hands over a socket only once its handshake is done.
            connect(tls, &QTcpServer::pendingConnectionAvailable, this, [this, tls] { onConnection(tls); });
            server = tls;
        } else {
            server = new QTcpServer(this);
            connect(server, &QTcpServer::newConnection, this, [this, server] { onConnection(server); });
        }
        if (!server->listen(a, kPort)) {
            if (a.protocol() == QAbstractSocket::IPv4Protocol)
                failed = a.toString();
            delete server;
            continue;
        }
        m_servers.emplace_back(a, server);
        changed = true;
    }
    // Multicast: join on the home network's interfaces only, and again whenever they change.
    QStringList joined;
    for (const QNetworkInterface &iface : Lan::interfaces()) {
        if (!(iface.flags() & QNetworkInterface::CanMulticast))
            continue;
        joined << iface.name();
        if (!m_joined.contains(iface.name()))
            m_udp.joinMulticastGroup(kGroup, iface);
    }
    m_joined = joined;

    const QString error = !failed.isEmpty() && m_servers.empty()
                              ? QStringLiteral("Nearby sharing couldn't start. Is the LocalSend app already running on this handheld?")
                              : want.isEmpty() ? QStringLiteral("Waiting for Wi-Fi: nearby sharing works on a home network only.") : QString();
    if (error != m_error) {
        m_error = error;
        changed = true;
    }
    if (changed) {
        announce();
        Q_EMIT stateChanged();
    }
}

void LocalSend::stop()
{
    m_on = false;
    m_lanTimer.stop();
    m_announceTimer.stop();
    m_sessionTimer.stop();
    m_udp.close();
    m_joined.clear();
    for (auto &s : m_servers) {
        s.second->close();
        s.second->deleteLater();
    }
    m_servers.clear();
    m_error.clear();
    Q_EMIT stateChanged();
}

void LocalSend::announce()
{
    // Hidden from people nearby: nothing goes out on its own.
    if (!running() || !listening() || (silent && silent()))
        return;
    const QByteArray msg = QJsonDocument(info(true, true)).toJson(QJsonDocument::Compact);
    for (const QNetworkInterface &iface : Lan::interfaces()) {
        if (!(iface.flags() & QNetworkInterface::CanMulticast))
            continue;
        m_udp.setMulticastInterface(iface);
        m_udp.writeDatagram(msg, kGroup, kPort);
    }
}

void LocalSend::refresh()
{
    if (!running())
        return;
    announce();
    // Some routers drop multicast, so also ask every address on the home network directly.
    const QString me = Lan::firstIPv4();
    if (me.isEmpty() || m_scanLeft > 0)
        return;
    const QString prefix = me.section(QLatin1Char('.'), 0, 2) + QLatin1Char('.');
    for (int i = 1; i < 255; ++i) {
        const QString ip = prefix + QString::number(i);
        if (ip == me)
            continue;
        ++m_scanLeft;
        registerWith(ip, kPort, QStringLiteral("https"), true);
    }
    Q_EMIT devicesChanged();
}

void LocalSend::registerWith(const QString &ip, int port, const QString &protocol, bool scan, const QString &expected)
{
    // expected: the fingerprint the device announced (empty for a scan, which can't know it yet).
    const QString pinTo = protocol == QLatin1String("https") ? expected : QString();
    QNetworkRequest req = makeRequest(QUrl(QStringLiteral("%1://%2:%3%4/register").arg(protocol, Lan::urlHost(ip)).arg(port).arg(kApi)), pinTo,
                                      silent && silent());
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(scan ? 2500 : 5000);
    QNetworkReply *r = m_net.post(req, QJsonDocument(info(false, false)).toJson(QJsonDocument::Compact));
    checkPeer(r, pinTo);
    connect(r, &QNetworkReply::finished, this, [this, r, ip, port, protocol, scan] {
        r->deleteLater();
        const QJsonObject o = QJsonDocument::fromJson(r->readAll()).object();
        const bool ok = r->error() == QNetworkReply::NoError && !o.isEmpty();
        if (r->property("pinFailed").toBool()) {
            // Someone answered with a different certificate than the device announced: ignore it.
        } else if (ok) {
            QJsonObject withPort = o;
            if (!withPort.contains(QStringLiteral("port")))
                withPort.insert(QStringLiteral("port"), port);
            // Over HTTPS the certificate is the device's identity, whatever the answer claims.
            QString proven;
            if (protocol == QLatin1String("https")) {
                proven = r->property("peerFingerprint").toString();
                if (proven.isEmpty())
                    proven = Tls::fingerprintOf(r->sslConfiguration().peerCertificate());
            }
            remember(withPort, ip, protocol, proven);
        } else if (protocol == QLatin1String("https") && (r->error() == QNetworkReply::SslHandshakeFailedError
                                                          || r->error() == QNetworkReply::UnknownNetworkError
                                                          || r->error() == QNetworkReply::ProtocolFailure
                                                          || r->error() == QNetworkReply::RemoteHostClosedError)) {
            // The device answers, just not over HTTPS: its encryption is off.
            registerWith(ip, port, QStringLiteral("http"), scan);
            return;
        } else if (!scan) {
            // Couldn't reach it over HTTP: answer on multicast instead, as the protocol allows.
            const QByteArray msg = QJsonDocument(info(true, false)).toJson(QJsonDocument::Compact);
            m_udp.writeDatagram(msg, kGroup, kPort);
        }
        if (scan && --m_scanLeft == 0)
            Q_EMIT devicesChanged();
    });
}

void LocalSend::onDatagrams()
{
    while (m_udp.hasPendingDatagrams()) {
        QHostAddress from;
        QByteArray data(int(m_udp.pendingDatagramSize()), Qt::Uninitialized);
        m_udp.readDatagram(data.data(), data.size(), &from);
        if (!Lan::isNeighbour(from))
            continue;   // only devices on the same home network (not a VPN or anything farther away)
        const QJsonObject o = QJsonDocument::fromJson(data).object();
        const QString fp = o.value(QStringLiteral("fingerprint")).toString();
        if (o.isEmpty() || Tls::sameFingerprint(fp, m_fingerprint))   // our own announcement
            continue;
        const QString ip = Lan::text(from);
        // A multicast message proves nothing: a device is only listed once its certificate answered (register).
        const bool announce = o.value(QStringLiteral("announce")).toBool(o.value(QStringLiteral("announcement")).toBool());
        const auto known = m_devices.constFind(fp.toUpper());
        if (!announce && known != m_devices.constEnd() && known->ip == ip)
            continue;
        // Hidden: no answer to anyone's announcement (Look again still asks devices directly).
        if (silent && silent())
            continue;
        if (asking(ip))
            registerWith(ip, o.value(QStringLiteral("port")).toInt(kPort), o.value(QStringLiteral("protocol")).toString(QStringLiteral("https")), !announce, fp);
    }
}

void LocalSend::remember(const QJsonObject &o, const QString &ip, const QString &protocolFallback, const QString &provenFp)
{
    // provenFp: the fingerprint of a certificate the device actually showed us. It wins over
    // what the message claims.
    const QString protocol = o.value(QStringLiteral("protocol")).toString(protocolFallback);
    // Over HTTPS nothing is learned without a certificate it really showed: a spoofed announce can't take over another
    // device's entry.
    if (protocol == QLatin1String("https") && provenFp.isEmpty())
        return;
    QString id = provenFp.isEmpty() ? o.value(QStringLiteral("fingerprint")).toString() : provenFp;
    if (id.isEmpty() || Tls::sameFingerprint(id, m_fingerprint))
        return;
    if (protocol == QLatin1String("https"))
        id = id.toUpper();   // certificate hashes: one spelling, so a device is listed once
    // A fingerprint seen over HTTPS is a certificate hash. Nobody else may take it over by
    // claiming it with encryption off, which would skip the certificate check when sending.
    const auto old = m_devices.constFind(id);
    if (old != m_devices.constEnd() && old->protocol == QLatin1String("https") && protocol != QLatin1String("https"))
        return;
    Device d;
    d.id = id;
    // Never an address on screen: a device without a name is just "A nearby device".
    d.alias = o.value(QStringLiteral("alias")).toString().trimmed();
    if (d.alias.isEmpty())
        d.alias = QStringLiteral("A nearby device");
    d.model = o.value(QStringLiteral("deviceModel")).toString();
    d.type = o.value(QStringLiteral("deviceType")).toString(QStringLiteral("mobile"));
    d.ip = ip;
    d.port = o.value(QStringLiteral("port")).toInt(kPort);
    d.protocol = protocol;
    // Cap s1: whose chat it is. A hidden device sends a hint only a contact can match; its proof must check out against
    // this certificate. A wrong proof means it isn't theirs; no proof at all is an app without s1 (not proven).
    QString claimed = o.value(QStringLiteral("chatKey")).toString();
    if (claimed.isEmpty() && hintKey)
        claimed = hintKey(o.value(QStringLiteral("hint")).toString());
    const QString sig = o.value(QStringLiteral("chatProof")).toString();
    d.proven = !claimed.isEmpty() && !provenFp.isEmpty() && !sig.isEmpty() && Proof::routeOk(claimed, provenFp, sig);
    d.chatKey = d.proven || sig.isEmpty() ? claimed : QString();
    d.chatName = o.value(QStringLiteral("chatName")).toString();
    d.app = o.value(QStringLiteral("chatApp")).toString();
    d.seen = QDateTime::currentDateTime();
    m_devices.insert(id, d);
    Q_EMIT devicesChanged();
}

QVariantList LocalSend::devices() const
{
    QList<Device> list = m_devices.values();
    std::sort(list.begin(), list.end(), [](const Device &a, const Device &b) { return a.alias.compare(b.alias, Qt::CaseInsensitive) < 0; });
    QVariantList out;
    for (const Device &d : std::as_const(list))
        out << QVariantMap{{QStringLiteral("id"), d.id}, {QStringLiteral("alias"), d.alias}, {QStringLiteral("model"), d.model},
                           {QStringLiteral("type"), d.type}, {QStringLiteral("app"), d.app},
                           {QStringLiteral("chatKey"), d.chatKey}, {QStringLiteral("chatName"), d.chatName}};
    return out;
}

// ------------------------------------------------------------------ receiving

void LocalSend::onConnection(QTcpServer *server)
{
    while (QTcpSocket *s = server->nextPendingConnection()) {
        // At most 32 at once (nobody can pile them up), and only from this very network.
        if (!Lan::isNeighbour(s->peerAddress()) || m_conns.size() >= 32) {
            // Reached our home-network address from somewhere else (routed in over a VPN, say): no.
            s->abort();
            s->deleteLater();
            continue;
        }
        Conn conn;
        if (auto *ssl = qobject_cast<QSslSocket *>(s))
            conn.peerFp = Tls::fingerprintOf(ssl->peerCertificate());
        m_conns.insert(s, conn);
        connect(s, &QTcpSocket::readyRead, this, [this, s] { onData(s); });
        connect(s, &QTcpSocket::disconnected, this, [this, s] {
            auto it = m_conns.find(s);
            if (it != m_conns.end()) {
                if (it->file) {
                    // The sender went away mid-file: don't leave half a file behind.
                    it->file->close();
                    it->file->remove();
                    delete it->file;
                    if (m_session.files.contains(it->fileId)) {
                        m_session.files[it->fileId].failed = true;
                        m_session.files[it->fileId].received = 0;
                    }
                    Q_EMIT progressChanged();
                }
                delete it->hash;
                m_conns.erase(it);
            }
            if (m_pending.socket == s) {
                m_requestTimer.stop();
                m_pending = {};
                Q_EMIT requestChanged();
            }
            s->deleteLater();
        });
        // The request may have arrived together with the end of the handshake, before we were
        // listening for it. Read it from the event loop rather than inside the server's signal.
        if (s->bytesAvailable() > 0)
            QTimer::singleShot(0, s, [this, s] { onData(s); });
    }
}

void LocalSend::onData(QTcpSocket *s)
{
    auto it = m_conns.find(s);
    if (it == m_conns.end())
        return;
    Conn &c = *it;
    if (c.replied) {
        s->readAll();
        return;
    }
    QByteArray chunk;
    if (!c.headDone) {
        c.head += s->readAll();
        const int end = c.head.indexOf("\r\n\r\n");
        if (end < 0) {
            if (c.head.size() > 64 * 1024)
                s->abort();
            return;
        }
        chunk = c.head.mid(end + 4);
        const QList<QByteArray> lines = c.head.left(end).split('\n');
        c.headDone = true;
        const QList<QByteArray> first = lines.value(0).trimmed().split(' ');
        c.method = QString::fromLatin1(first.value(0));
        const QUrl target(QString::fromLatin1(first.value(1)));
        c.path = target.path();
        for (const auto &kv : QUrlQuery(target).queryItems(QUrl::FullyDecoded))
            c.query.insert(kv.first, kv.second);
        for (int i = 1; i < lines.size(); ++i) {
            const int colon = lines.at(i).indexOf(':');
            if (colon > 0)
                c.headers.insert(QString::fromLatin1(lines.at(i).left(colon)).trimmed().toLower(),
                                 QString::fromUtf8(lines.at(i).mid(colon + 1)).trimmed());
        }
        c.bodyLeft = c.headers.value(QStringLiteral("content-length")).toLongLong();
        // Chat envelopes are at most 64 KB (protocol 9), other requests a little JSON: anything bigger is refused up front.
        const bool upload = c.path == kApi + QStringLiteral("/upload");
        const qint64 most = c.path == QLatin1String("/api/huginn/v1/message") ? 96 * 1024 : 2 * 1024 * 1024;
        if (c.bodyLeft < 0 || (!upload && c.bodyLeft > most)) {
            reply(s, 413);
            return;
        }
        route(s, c);
        if (!m_conns.contains(s))
            return;
    } else {
        chunk = s->readAll();
    }
    if (chunk.isEmpty() && c.bodyLeft > 0)
        return;
    if (c.file) {
        // Never past the length the upload was accepted with: anything after it is not part of the file.
        if (chunk.size() > c.bodyLeft)
            chunk.truncate(qsizetype(qMax<qint64>(0, c.bodyLeft)));
        c.file->write(chunk);
        if (c.hash)
            c.hash->addData(chunk);
        c.bodyLeft -= chunk.size();
        m_session.touched = QDateTime::currentDateTime();
        if (m_session.files.contains(c.fileId))
            m_session.files[c.fileId].received += chunk.size();
        Q_EMIT progressChanged();
        if (c.bodyLeft <= 0)
            onBody(s, c);
    } else if (!c.method.isEmpty() && c.bodyLeft >= 0) {
        if (chunk.size() > c.bodyLeft)
            chunk.truncate(qsizetype(qMax<qint64>(0, c.bodyLeft)));
        c.body += chunk;
        c.bodyLeft -= chunk.size();
        if (c.body.size() > 2 * 1024 * 1024 + 16 * 1024) {
            s->abort();
            return;
        }
        if (c.bodyLeft <= 0) {
            c.bodyLeft = -1;   // handled
            onBody(s, c);
        }
    }
}

void LocalSend::route(QTcpSocket *s, Conn &c)
{
    // Uploads stream straight to disk; everything else is a small JSON body handled in onBody.
    if (c.method == QLatin1String("POST") && c.path == kApi + QStringLiteral("/upload")) {
        const QString sid = c.query.value(QStringLiteral("sessionId"));
        const QString fid = c.query.value(QStringLiteral("fileId"));
        const QString token = c.query.value(QStringLiteral("token"));
        if (sid.isEmpty() || fid.isEmpty() || token.isEmpty()) { reply(s, 400); return; }
        if (!m_session.active || sid != m_session.id) { reply(s, 409); return; }
        const QString ip = Lan::text(s->peerAddress());
        auto f = m_session.files.find(fid);
        if (f == m_session.files.end() || f->token != token || ip != m_session.ip) { reply(s, 403); return; }
        // Never more than the size it was accepted at: a sender can't fill the disk past what the person agreed to.
        if (c.bodyLeft > f->size) { reply(s, 413); return; }
        f->savedAs = savePath(f->name);
        QDir().mkpath(QFileInfo(f->savedAs).absolutePath());
        c.file = new QFile(f->savedAs, this);
        if (!c.file->open(QIODevice::WriteOnly)) {
            delete c.file;
            c.file = nullptr;
            reply(s, 500);
            return;
        }
        c.fileId = fid;
        f->received = 0;
        f->failed = false;
        if (!f->sha.isEmpty())
            c.hash = new QCryptographicHash(QCryptographicHash::Sha256);
        m_session.touched = QDateTime::currentDateTime();
        if (c.bodyLeft == 0)
            onBody(s, c);
        return;
    }
    if (c.bodyLeft == 0) {
        c.bodyLeft = -1;
        onBody(s, c);
    }
}

void LocalSend::onBody(QTcpSocket *s, Conn &c)
{
    const QString ip = Lan::text(s->peerAddress());
    const QString path = c.path;

    if (c.file) {
        c.file->close();
        auto f = m_session.files.find(c.fileId);
        const bool shaOk = !c.hash || f == m_session.files.end() || f->sha.isEmpty()
                           || QString::fromLatin1(c.hash->result().toHex()) == f->sha.toLower();
        delete c.file;
        c.file = nullptr;
        delete c.hash;
        c.hash = nullptr;
        if (f == m_session.files.end()) { reply(s, 409); return; }
        if (!shaOk) {
            QFile::remove(f->savedAs);
            f->failed = true;
            reply(s, 422);
        } else {
            f->done = true;
            reply(s, 200);
        }
        Q_EMIT progressChanged();
        bool all = true, anyFailed = false;
        for (const InFile &x : std::as_const(m_session.files)) {
            all = all && (x.done || x.failed);
            anyFailed = anyFailed || x.failed;
        }
        if (all) {
            int done = 0;
            for (const InFile &x : std::as_const(m_session.files))
                done += x.done ? 1 : 0;
            endSession(!anyFailed, anyFailed ? QStringLiteral("%1 of %2 files from %3 arrived. The rest didn't make it; send them again.")
                                                   .arg(done).arg(m_session.files.size()).arg(m_session.from)
                                             : QStringLiteral("Received %1 from %2. They're in %3.")
                                                   .arg(done == 1 ? QStringLiteral("1 file") : QStringLiteral("%1 files").arg(done), m_session.from,
                                                        System::displayPath(QUrl::fromLocalFile(m_settings->transferFolder()))));
        }
        return;
    }

    if (path == QStringLiteral("/api/huginn/v1/move")) {
        const QPair<int, QByteArray> r = moveReceive ? moveReceive(c.body) : qMakePair(404, QByteArrayLiteral("{}"));
        reply(s, r.first, r.second);
        return;
    }
    if (path == QStringLiteral("/api/huginn/v1/message")) {
        reply(s, chatReceive ? chatReceive(c.body, ip) : 404, "{}");
        return;
    }
    const QJsonObject body = QJsonDocument::fromJson(c.body).object();
    // A sender that showed a certificate must claim that certificate's fingerprint, or it is
    // pretending to be another device: answer, but don't list it (LocalSend does the same).
    // One that showed none proves nothing: it's asked for its certificate instead (register), never just believed.
    const QString peerFp = c.peerFp;
    const auto learn = [this, ip, peerFp](const QJsonObject &o) {
        if (peerFp.isEmpty()) {
            if (o.value(QStringLiteral("protocol")).toString(QStringLiteral("https")) != QLatin1String("https"))
                remember(o, ip, QStringLiteral("http"));
            else if (asking(ip))
                registerWith(ip, o.value(QStringLiteral("port")).toInt(kPort), QStringLiteral("https"), false, o.value(QStringLiteral("fingerprint")).toString());
        } else if (Tls::sameFingerprint(o.value(QStringLiteral("fingerprint")).toString(), peerFp)) {
            remember(o, ip, QStringLiteral("https"), peerFp);
        }
    };
    // Hidden from people nearby (21.3): no answer to a scan or an info request, as if nothing were here.
    const bool hidden = silent && silent();
    if (hidden && (path == kApi + QStringLiteral("/register") || path == QStringLiteral("/api/localsend/v1/register")
                   || path == kApi + QStringLiteral("/info") || path == QStringLiteral("/api/localsend/v1/info"))) {
        reply(s, 404);
        return;
    }
    if (path == kApi + QStringLiteral("/register") || path == QStringLiteral("/api/localsend/v1/register")) {
        learn(body);
        reply(s, 200, QJsonDocument(info(false, false)).toJson(QJsonDocument::Compact));
        return;
    }
    if (path == kApi + QStringLiteral("/info") || path == QStringLiteral("/api/localsend/v1/info")) {
        reply(s, 200, QJsonDocument(info(false, false)).toJson(QJsonDocument::Compact));
        return;
    }
    if (path == kApi + QStringLiteral("/prepare-upload")) {
        if (m_session.active || m_pending.socket) { reply(s, 409); return; }
        // Asking again and again from one address would keep a box up for a minute each time: after 3 turned down (or
        // left unanswered) in 10 minutes, it has to wait.
        {
            QList<qint64> &q = m_prompts[ip];
            const qint64 t = QDateTime::currentMSecsSinceEpoch();
            while (!q.isEmpty() && t - q.first() > 600000)
                q.removeFirst();
            if (q.size() >= 3) { reply(s, 429); return; }
        }
        const QJsonObject from = body.value(QStringLiteral("info")).toObject();
        const QJsonObject files = body.value(QStringLiteral("files")).toObject();
        if (files.isEmpty()) { reply(s, 400); return; }
        learn(from);
        Pending p;
        p.socket = s;
        p.from = from.value(QStringLiteral("alias")).toString().trimmed();
        if (p.from.isEmpty())
            p.from = QStringLiteral("A nearby device");
        p.ip = ip;
        qint64 total = 0;
        for (auto it = files.begin(); it != files.end(); ++it) {
            const QJsonObject f = it.value().toObject();
            InFile in;
            in.name = f.value(QStringLiteral("fileName")).toString(QStringLiteral("file"));
            in.size = qint64(f.value(QStringLiteral("size")).toDouble());
            in.sha = f.value(QStringLiteral("sha256")).toString();
            total += in.size;
            p.files.insert(it.key(), in);
        }
        const QString dir = m_settings->transferFolder();
        QDir().mkpath(dir);
        const QStorageInfo si(dir);
        if (si.isValid() && total + 64LL * 1024 * 1024 > si.bytesAvailable()) {
            reply(s, 403);
            Q_EMIT finished(false, QStringLiteral("%1 tried to send %2, but there's only %3 free. Free up space and try again.")
                                       .arg(p.from, System::formatSize(total), System::formatSize(si.bytesAvailable())));
            return;
        }
        m_pending = p;
        m_requestTimer.start();
        Q_EMIT requestChanged();
        return;   // answered by accept() or decline()
    }
    if (path == kApi + QStringLiteral("/cancel")) {
        if (m_session.active && c.query.value(QStringLiteral("sessionId")) == m_session.id)
            endSession(false, QStringLiteral("%1 cancelled sending.").arg(m_session.from));
        reply(s, 200);
        return;
    }
    reply(s, 404);
}

QVariantMap LocalSend::request() const
{
    if (!m_pending.socket)
        return {{QStringLiteral("active"), false}};
    qint64 total = 0;
    QStringList names;
    for (const InFile &f : m_pending.files) {
        total += f.size;
        names << QFileInfo(f.name).fileName();
    }
    names.sort(Qt::CaseInsensitive);
    QString shown = names.mid(0, 3).join(QStringLiteral(", "));
    if (names.size() > 3)
        shown += QStringLiteral(" and %1 more").arg(names.size() - 3);
    return {{QStringLiteral("active"), true}, {QStringLiteral("from"), m_pending.from},
            {QStringLiteral("count"), int(m_pending.files.size())}, {QStringLiteral("sizeText"), System::formatSize(total)},
            {QStringLiteral("names"), shown}};
}

void LocalSend::accept()
{
    if (!m_pending.socket)
        return;
    m_requestTimer.stop();
    m_session = {};
    m_session.active = true;
    m_session.id = randomToken();
    m_session.from = m_pending.from;
    m_session.ip = m_pending.ip;
    m_session.touched = QDateTime::currentDateTime();
    QJsonObject tokens;
    for (auto it = m_pending.files.begin(); it != m_pending.files.end(); ++it) {
        InFile f = it.value();
        f.token = randomToken();
        tokens.insert(it.key(), f.token);
        m_session.files.insert(it.key(), f);
    }
    m_status = QStringLiteral("Receiving from %1…").arg(m_session.from);
    reply(m_pending.socket, 200, QJsonDocument(QJsonObject{{QStringLiteral("sessionId"), m_session.id}, {QStringLiteral("files"), tokens}})
                                     .toJson(QJsonDocument::Compact));
    m_pending = {};
    Q_EMIT requestChanged();
    Q_EMIT progressChanged();
}

void LocalSend::decline()
{
    if (m_pending.socket) {
        turnedDown(m_pending.ip);
        reply(m_pending.socket, 403);
    }
    m_requestTimer.stop();
    m_pending = {};
    Q_EMIT requestChanged();
}

void LocalSend::endSession(bool ok, const QString &message)
{
    for (const InFile &f : std::as_const(m_session.files)) {
        if (f.done)
            m_history << QVariantMap{{QStringLiteral("name"), QFileInfo(f.savedAs).fileName()}, {QStringLiteral("status"), QStringLiteral("Done")},
                                     {QStringLiteral("failed"), false}, {QStringLiteral("fraction"), 1.0}};
        else if (!f.savedAs.isEmpty() || f.failed)
            m_history << QVariantMap{{QStringLiteral("name"), QFileInfo(f.name).fileName()}, {QStringLiteral("status"), QStringLiteral("Didn't arrive")},
                                     {QStringLiteral("failed"), true}, {QStringLiteral("fraction"), 0.0}};
    }
    // Close any upload still streaming and drop its partial file. Abort after the loop: aborting
    // removes the connection from m_conns.
    QList<QTcpSocket *> drop;
    for (auto it = m_conns.begin(); it != m_conns.end(); ++it) {
        if (it->file) {
            it->file->close();
            it->file->remove();
            delete it->file;
            it->file = nullptr;
            drop << it.key();
        }
    }
    while (m_history.size() > 30)
        m_history.removeFirst();
    m_session = {};
    m_status.clear();
    for (QTcpSocket *s : std::as_const(drop))
        s->abort();
    Q_EMIT progressChanged();
    Q_EMIT finished(ok, message);
}

QStringList LocalSend::safeParts(const QString &name)
{
    // Senders may include folders ("Photos/a.jpg"). Keep them, but never let a name climb out of the save folder:
    // split on both slashes (Windows takes "\" as one), and drop "." and "..", and anything with a ":" (a drive
    // letter like "C:", or a Windows stream like "a.txt:x") so no part can make the path absolute.
    QStringList parts;
    static const QRegularExpression seps(QStringLiteral("[/\\\\]"));
    for (const QString &p : name.split(seps, Qt::SkipEmptyParts))
        if (p != QLatin1String(".") && p != QLatin1String("..") && !p.contains(QLatin1Char(':')))
            parts << p;
    if (parts.isEmpty())
        parts << QStringLiteral("from-phone");
    return parts;
}

bool LocalSend::selfTest()
{
    const auto is = [](const QString &name, const QString &want) { return safeParts(name).join(QLatin1Char('/')) == want; };
    return is(QStringLiteral("Photos/a.jpg"), QStringLiteral("Photos/a.jpg"))
        && is(QStringLiteral("..\\..\\Startup\\x.bat"), QStringLiteral("Startup/x.bat"))
        && is(QStringLiteral("../../.bashrc"), QStringLiteral(".bashrc"))
        && is(QStringLiteral("C:/Windows/x.dll"), QStringLiteral("Windows/x.dll"))
        && is(QStringLiteral("C:\\Users\\x.exe"), QStringLiteral("Users/x.exe"))
        && is(QStringLiteral("\\\\server\\share\\x"), QStringLiteral("server/share/x"))
        && is(QStringLiteral("a.txt:secret"), QStringLiteral("from-phone"))
        && is(QStringLiteral("/etc/passwd"), QStringLiteral("etc/passwd"))
        && is(QStringLiteral(".."), QStringLiteral("from-phone"));
}

QString LocalSend::savePath(const QString &name) const
{
    QStringList parts = safeParts(name);
    const QString file = parts.takeLast();
    const QString dir = QDir(m_settings->transferFolder()).filePath(parts.join(QLatin1Char('/')));
    QString path = QDir(dir).filePath(file);
    const QFileInfo fi(file);
    for (int n = 1; QFileInfo::exists(path); ++n) {
        const QString suffix = fi.suffix().isEmpty() ? QString() : QLatin1Char('.') + fi.suffix();
        path = QDir(dir).filePath(fi.completeBaseName() + QStringLiteral(" (%1)").arg(n) + suffix);
    }
    return path;
}

void LocalSend::reply(QTcpSocket *s, int code, const QByteArray &body)
{
    if (!s)
        return;
    auto conn = m_conns.find(s);
    if (conn != m_conns.end())
        conn->replied = true;
    QByteArray reason = "OK";
    switch (code) {
    case 204: reason = "No Content"; break;
    case 400: reason = "Bad Request"; break;
    case 401: reason = "Unauthorized"; break;
    case 403: reason = "Forbidden"; break;
    case 413: reason = "Payload Too Large"; break;
    case 429: reason = "Too Many Requests"; break;
    case 404: reason = "Not Found"; break;
    case 409: reason = "Conflict"; break;
    case 422: reason = "Unprocessable Entity"; break;
    case 500: reason = "Internal Server Error"; break;
    }
    s->write("HTTP/1.1 " + QByteArray::number(code) + ' ' + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
             + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
    s->disconnectFromHost();
}

QVariantList LocalSend::items() const
{
    QVariantList out;
    // In progress first, then the finished ones, newest first.
    for (const InFile &f : m_session.files) {
        const double frac = f.size > 0 ? double(f.received) / f.size : (f.done ? 1.0 : 0.0);
        out << QVariantMap{{QStringLiteral("name"), QFileInfo(f.name).fileName()},
                           {QStringLiteral("status"), f.done ? QStringLiteral("Done") : f.failed ? QStringLiteral("Didn't arrive")
                                                     : f.received == 0 ? QStringLiteral("Waiting")
                                                     : QStringLiteral("%1 of %2").arg(System::formatSize(f.received), System::formatSize(f.size))},
                           {QStringLiteral("failed"), f.failed},
                           {QStringLiteral("fraction"), frac}};
    }
    for (int i = int(m_history.size()) - 1; i >= 0; --i)
        out << m_history.at(i);
    return out;
}

double LocalSend::progress() const
{
    if (m_send.active)
        return m_send.total > 0 ? double(m_send.sent + m_send.current) / m_send.total : 0;
    if (m_session.active) {
        qint64 total = 0, got = 0;
        for (const InFile &f : m_session.files) {
            total += f.size;
            got += f.done ? f.size : f.received;
        }
        return total > 0 ? double(got) / total : 0;
    }
    return 0;
}

// ------------------------------------------------------------------ sending

void LocalSend::send(const QString &deviceId, const QStringList &paths, const QString &pin)
{
    if (m_send.active)
        return;
    const auto dev = m_devices.constFind(deviceId);
    if (dev == m_devices.constEnd()) {
        Q_EMIT finished(false, QStringLiteral("That device isn't nearby anymore. Look again and pick it."));
        return;
    }
    QMimeDatabase mime;
    m_send = {};
    m_send.deviceId = deviceId;
    m_send.alias = dev->alias;
    m_send.base = QStringLiteral("%1://%2:%3%4").arg(dev->protocol, Lan::urlHost(dev->ip)).arg(dev->port).arg(kApi);
    // Over HTTPS, every request checks the device's certificate against the fingerprint it announced.
    m_send.expected = dev->protocol == QLatin1String("https") ? dev->id : QString();
    QJsonObject files;
    int n = 0;
    for (const QString &p : paths) {
        const QFileInfo fi(p);
        if (!fi.isFile())
            continue;
        OutFile f;
        f.id = QStringLiteral("f%1").arg(n++);
        f.path = p;
        f.size = fi.size();
        m_send.total += f.size;
        m_send.files << f;
        files.insert(f.id, QJsonObject{{QStringLiteral("id"), f.id}, {QStringLiteral("fileName"), fi.fileName()},
                                       {QStringLiteral("size"), double(f.size)}, {QStringLiteral("fileType"), mime.mimeTypeForFile(fi).name()},
                                       {QStringLiteral("sha256"), QJsonValue::Null}, {QStringLiteral("preview"), QJsonValue::Null}});
    }
    if (m_send.files.isEmpty()) {
        m_send = {};
        Q_EMIT finished(false, QStringLiteral("Pick some files first: in Files, choose Pick files to send."));
        return;
    }
    m_send.active = true;
    m_status = QStringLiteral("Waiting for %1 to accept…").arg(m_send.alias);
    Q_EMIT progressChanged();

    QUrl url(m_send.base + QStringLiteral("/prepare-upload"));
    if (!pin.isEmpty())
        url.setQuery(QStringLiteral("pin=") + QString::fromLatin1(QUrl::toPercentEncoding(pin)));
    QNetworkRequest req = makeRequest(url, m_send.expected);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(5 * 60 * 1000);   // the other person has to tap Accept
    QNetworkReply *r = m_net.post(req, QJsonDocument(QJsonObject{{QStringLiteral("info"), info(false, false)}, {QStringLiteral("files"), files}})
                                           .toJson(QJsonDocument::Compact));
    checkPeer(r, m_send.expected);
    m_send.reply = r;
    connect(r, &QNetworkReply::finished, this, [this, r, pin, deviceId] {
        r->deleteLater();
        if (m_send.reply != r)
            return;
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (r->property("pinFailed").toBool()) {
            endSend(false, QStringLiteral("Didn't send: the device answering isn't the %1 that was found earlier. Look again and pick it.").arg(m_send.alias));
            return;
        }
        if (r->error() == QNetworkReply::OperationCanceledError && code == 0) {
            endSend(false, QStringLiteral("Sending stopped."));
            return;
        }
        switch (code) {
        case 200: {
            const QJsonObject o = QJsonDocument::fromJson(r->readAll()).object();
            m_send.sessionId = o.value(QStringLiteral("sessionId")).toString();
            const QJsonObject tokens = o.value(QStringLiteral("files")).toObject();
            QList<OutFile> accepted;
            qint64 total = 0;
            for (OutFile f : std::as_const(m_send.files)) {
                if (!tokens.contains(f.id))
                    continue;
                f.token = tokens.value(f.id).toString();
                total += f.size;
                accepted << f;
            }
            m_send.files = accepted;
            m_send.total = total;
            m_send.next = 0;
            uploadNext();
            return;
        }
        case 204: endSend(true, QStringLiteral("%1 already has these files.").arg(m_send.alias)); return;
        case 401: {
            m_send = {};
            m_status.clear();
            Q_EMIT progressChanged();
            Q_EMIT pinNeeded(deviceId, !pin.isEmpty());
            return;
        }
        case 403: endSend(false, QStringLiteral("%1 turned the files down.").arg(m_send.alias)); return;
        case 409: endSend(false, QStringLiteral("%1 is busy with another transfer. Try again in a moment.").arg(m_send.alias)); return;
        case 429: endSend(false, QStringLiteral("%1 asked to wait a bit before sending again.").arg(m_send.alias)); return;
        default:
            endSend(false, code ? QStringLiteral("%1 couldn't take the files (error %2).").arg(m_send.alias).arg(code)
                                : QStringLiteral("Couldn't reach %1. Is LocalSend open on it, on the same Wi-Fi?").arg(m_send.alias));
        }
    });
}

void LocalSend::uploadNext()
{
    if (m_send.next >= m_send.files.size()) {
        const int n = int(m_send.files.size());
        endSend(true, QStringLiteral("Sent %1 to %2.").arg(n == 1 ? QStringLiteral("1 file") : QStringLiteral("%1 files").arg(n), m_send.alias));
        return;
    }
    const OutFile f = m_send.files.at(m_send.next);
    auto *file = new QFile(f.path);
    if (!file->open(QIODevice::ReadOnly)) {
        delete file;
        endSend(false, QStringLiteral("Couldn't read %1, so sending stopped.").arg(QFileInfo(f.path).fileName()));
        return;
    }
    m_status = QStringLiteral("Sending %1 to %2…").arg(QFileInfo(f.path).fileName(), m_send.alias);
    m_send.current = 0;
    Q_EMIT progressChanged();
    QUrl url(m_send.base + QStringLiteral("/upload"));
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("sessionId"), m_send.sessionId);
    q.addQueryItem(QStringLiteral("fileId"), f.id);
    q.addQueryItem(QStringLiteral("token"), f.token);
    url.setQuery(q);
    QNetworkRequest req = makeRequest(url, m_send.expected);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/octet-stream"));
    req.setHeader(QNetworkRequest::ContentLengthHeader, f.size);
    req.setTransferTimeout(60000);   // no progress for a minute means the connection is gone
    QNetworkReply *r = m_net.post(req, file);
    file->setParent(r);
    checkPeer(r, m_send.expected);
    m_send.reply = r;
    connect(r, &QNetworkReply::uploadProgress, this, [this, r](qint64 sent, qint64) {
        if (m_send.reply != r)
            return;
        m_send.current = sent;
        Q_EMIT progressChanged();
    });
    connect(r, &QNetworkReply::finished, this, [this, r, f] {
        r->deleteLater();
        if (m_send.reply != r)
            return;
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (r->property("pinFailed").toBool()) {
            endSend(false, QStringLiteral("Sending stopped: the device answering isn't the %1 that was found earlier.").arg(m_send.alias));
            return;
        }
        if (r->error() != QNetworkReply::NoError || code != 200) {
            QStringList left;
            for (int i = m_send.next; i < m_send.files.size(); ++i)
                left << QFileInfo(m_send.files.at(i).path).fileName();
            endSend(false, (r->error() == QNetworkReply::OperationCanceledError ? QStringLiteral("Sending stopped.")
                                                                                 : QStringLiteral("The connection to %1 dropped.").arg(m_send.alias))
                               + QStringLiteral(" Sent %1 of %2. Not sent: %3.").arg(m_send.next).arg(m_send.files.size())
                                     .arg(left.mid(0, 4).join(QStringLiteral(", ")) + (left.size() > 4 ? QStringLiteral(" and more") : QString())));
            return;
        }
        m_send.sent += f.size;
        m_send.current = 0;
        ++m_send.next;
        uploadNext();
    });
}

void LocalSend::cancelSend()
{
    if (!m_send.active)
        return;
    if (!m_send.sessionId.isEmpty()) {
        QNetworkRequest req = makeRequest(QUrl(m_send.base + QStringLiteral("/cancel?sessionId=") + m_send.sessionId), m_send.expected);
        req.setTransferTimeout(3000);
        QNetworkReply *c = m_net.post(req, QByteArray());
        checkPeer(c, m_send.expected);
        connect(c, &QNetworkReply::finished, c, &QObject::deleteLater);
    }
    if (m_send.reply)
        m_send.reply->abort();
    else
        endSend(false, QStringLiteral("Sending stopped."));
}

void LocalSend::endSend(bool ok, const QString &message)
{
    m_send = {};
    m_status.clear();
    Q_EMIT progressChanged();
    Q_EMIT finished(ok, message);
}
