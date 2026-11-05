#include "directpath.h"

#include "turnclient.h"

#include <QNetworkInterface>
#include <QUdpSocket>

QList<QPair<QString, quint16>> DirectPath::stunServers()
{
    QList<QPair<QString, quint16>> out;
    out.append(qMakePair(QStringLiteral("stun.cloudflare.com"), quint16(3478)));
    return out;
}

bool DirectPath::g_testLocal = false;

bool DirectPath::isLocal(const QHostAddress &h)
{
    bool v4 = false;
    const quint32 a = h.toIPv4Address(&v4);
    if (v4) {
        const quint32 b0 = a >> 24, b1 = (a >> 16) & 0xFF;
        return b0 == 10 || b0 == 127 || b0 == 0 || (b0 == 172 && (b1 & 0xF0) == 16) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
               || (b0 == 100 && (b1 & 0xC0) == 64) || b0 >= 224;
    }
    if (h.protocol() != QAbstractSocket::IPv6Protocol)
        return true;
    const Q_IPV6ADDR v6 = h.toIPv6Address();
    // Only 2000::/3 is global unicast; everything else (::1, fe80::/10, fc00::/7, ff00::/8) stays home.
    return (v6[0] & 0xE0) != 0x20;
}

QList<QHostAddress> DirectPath::globalIpv6()
{
    QList<QHostAddress> out;
    const auto ifaces = QNetworkInterface::allInterfaces();
    for (const QNetworkInterface &i : ifaces) {
        const auto f = i.flags();
        if (!(f & QNetworkInterface::IsUp) || !(f & QNetworkInterface::IsRunning) || (f & QNetworkInterface::IsLoopBack))
            continue;
        for (const QNetworkAddressEntry &e : i.addressEntries()) {
            QHostAddress a = e.ip();
            if (a.protocol() != QAbstractSocket::IPv6Protocol || isLocal(a))
                continue;
            a.setScopeId(QString());
            if (!out.contains(a))
                out << a;
            if (out.size() >= kMaxHost)
                return out;
        }
    }
    return out;
}

StunProbe::StunProbe(QUdpSocket *socket, QObject *parent) : QObject(parent), m_socket(socket)
{
    m_retry.setSingleShot(true);
    connect(&m_retry, &QTimer::timeout, this, &StunProbe::sendOnce);
    m_giveUp.setSingleShot(true);
    // No answer from this server in time: ask the next one, or give up after the last.
    connect(&m_giveUp, &QTimer::timeout, this, [this] {
        if (!m_done)
            tryNext();
    });
}

void StunProbe::start(const QList<QPair<QString, quint16>> &servers)
{
    m_servers = servers;
    m_next = 0;
    tryNext();
}

void StunProbe::finish()
{
    m_done = true;
    m_retry.stop();
    m_giveUp.stop();
    Q_EMIT finished();
}

void StunProbe::tryNext()
{
    m_retry.stop();
    if (m_next >= m_servers.size()) {
        finish();
        return;
    }
    const auto s = m_servers.at(m_next++);
    const int round = ++m_round;
    m_server = QHostAddress();
    m_port = s.second;
    m_tid = Stun::newTid();
    m_tries = 0;
    m_giveUp.start(3000);
    CallNet::resolve(s.first, this, [this, round](const QHostAddress &a) {
        if (m_done || round != m_round)
            return;
        if (a.isNull()) {
            m_giveUp.stop();
            tryNext();
            return;
        }
        m_server = a;
        sendOnce();
    }, 2);
}

void StunProbe::sendOnce()
{
    if (m_done || !m_socket || m_server.isNull())
        return;
    Stun::Message m;
    m.method = Stun::Binding;
    m.cls = Stun::Request;
    m.tid = m_tid;
    m_socket->writeDatagram(Stun::encode(m, QByteArray(), true), m_server, m_port);
    static const int kGaps[] = {300, 600, 900};
    if (m_tries < 3)
        m_retry.start(kGaps[m_tries++]);
}

bool StunProbe::take(const QByteArray &bytes, const QHostAddress &from, quint16 port)
{
    if (!Stun::isStun(bytes) || m_server.isNull() || port != m_port || CallNet::fromHost(from, port) != CallNet::fromHost(m_server, m_port))
        return false;
    Stun::Message m;
    if (!Stun::parse(bytes, &m) || m.tid != m_tid)
        return true;   // the server's, but not ours: nothing to do with it
    if (m_done || m.method != Stun::Binding || m.cls != Stun::Success)
        return true;
    Stun::Address a = m.address(Stun::XorMappedAddress);
    if (!a.valid())
        a = Stun::Address();
    // The address the server saw must be a real internet address (a local one is no use to anyone).
    if (a.valid() && !DirectPath::usable(CallNet::toHost(a)))
        a = Stun::Address();
    m_mapped = a;
    finish();
    return true;
}
