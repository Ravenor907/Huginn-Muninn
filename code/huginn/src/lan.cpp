#include "lan.h"

#include <QElapsedTimer>
#include <QMutex>
#include <QStringList>
#include <cstdio>

namespace
{
QHostAddress plain(const QHostAddress &a)
{
    // An IPv4 peer seen through an IPv6 socket (::ffff:a.b.c.d) is an IPv4 peer.
    bool v4 = false;
    const quint32 ip = a.toIPv4Address(&v4);
    return v4 ? QHostAddress(ip) : a;
}

bool nameSaysNotLan(const QString &name)
{
    const QString n = name.toLower();
    // Interface names (Linux: tun0, wg0, wwan0; Windows: tunnel_32512, ppp_32768, mbb_...) and
    // the names people see (Windows: "OpenVPN TAP-Windows6", "Cellular", "WireGuard Tunnel").
    static const QStringList prefixes{QStringLiteral("tun"), QStringLiteral("tap"), QStringLiteral("wg"), QStringLiteral("ppp"),
                                      QStringLiteral("utun"), QStringLiteral("ipsec"), QStringLiteral("wwan"), QStringLiteral("rmnet"),
                                      QStringLiteral("ccmni"), QStringLiteral("mbb"), QStringLiteral("docker"), QStringLiteral("veth"),
                                      QStringLiteral("virbr"), QStringLiteral("br-"), QStringLiteral("lxc"), QStringLiteral("lxd"),
                                      QStringLiteral("vmnet"), QStringLiteral("vboxnet"), QStringLiteral("zt"), QStringLiteral("ham"),
                                      QStringLiteral("tailscale"), QStringLiteral("nordlynx"), QStringLiteral("cni"), QStringLiteral("flannel"),
                                      QStringLiteral("podman"),
                                      // Windows names unusual adapter kinds by number (Wintun is iftype53, cellular iftype243).
                                      QStringLiteral("iftype")};
    static const QStringList words{QStringLiteral("vpn"), QStringLiteral("tap-"), QStringLiteral("wireguard"), QStringLiteral("tailscale"),
                                   QStringLiteral("zerotier"), QStringLiteral("nordlynx"), QStringLiteral("mullvad"), QStringLiteral("proton"),
                                   QStringLiteral("wintun"), QStringLiteral("cellular"), QStringLiteral("mobile broadband"),
                                   QStringLiteral("tunnel"), QStringLiteral("virtualbox"), QStringLiteral("vmware"), QStringLiteral("hamachi"),
                                   // Hyper-V's virtual switches on Windows, which WSL and Docker use: "vEthernet (WSL)",
                                   // "vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter".
                                   QStringLiteral("vethernet"), QStringLiteral("hyper-v"), QStringLiteral("wsl"), QStringLiteral("default switch")};
    for (const QString &p : prefixes)
        if (n.startsWith(p))
            return true;
    for (const QString &w : words)
        if (n.contains(w))
            return true;
    return false;
}

bool lanKind(QNetworkInterface::InterfaceType type, QNetworkInterface::InterfaceFlags flags, const QString &name, const QString &human)
{
    if (!(flags & QNetworkInterface::IsUp) || !(flags & QNetworkInterface::IsRunning) || (flags & QNetworkInterface::IsLoopBack)
        || (flags & QNetworkInterface::IsPointToPoint))
        return false;
    // Ethernet and Wi-Fi; Unknown too, since some Linux Wi-Fi drivers report nothing (the name rules still apply).
    if (type != QNetworkInterface::Ethernet && type != QNetworkInterface::Wifi && type != QNetworkInterface::Ieee80211
        && type != QNetworkInterface::Unknown)
        return false;
    return !nameSaysNotLan(name) && !nameSaysNotLan(human);
}
}

namespace Lan
{
bool isPrivate(const QHostAddress &address)
{
    const QHostAddress a = plain(address);
    if (a.protocol() == QAbstractSocket::IPv4Protocol) {
        const quint32 ip = a.toIPv4Address();
        return (ip >> 24) == 10 || (ip >> 20) == ((172u << 4) | 1u) || (ip >> 16) == ((192u << 8) | 168u);
    }
    if (a.protocol() == QAbstractSocket::IPv6Protocol) {
        const Q_IPV6ADDR v6 = a.toIPv6Address();
        return (v6[0] == 0xfe && (v6[1] & 0xc0) == 0x80) || v6[0] == 0xfd;
    }
    return false;
}

bool isLanInterface(const QNetworkInterface &iface)
{
    return lanKind(iface.type(), iface.flags(), iface.name(), iface.humanReadableName());
}

QList<QNetworkInterface> interfaces()
{
    QList<QNetworkInterface> out;
    for (const QNetworkInterface &iface : QNetworkInterface::allInterfaces())
        if (isLanInterface(iface))
            out << iface;
    return out;
}

QList<QHostAddress> addresses()
{
    QList<QHostAddress> v4, v6;
    for (const QNetworkInterface &iface : interfaces()) {
        for (const QNetworkAddressEntry &e : iface.addressEntries()) {
            QHostAddress a = e.ip();
            if (!isPrivate(a))
                continue;
            if (a.protocol() == QAbstractSocket::IPv4Protocol) {
                if (!v4.contains(a))
                    v4 << a;
            } else {
                if (a.isLinkLocal() && a.scopeId().isEmpty())
                    a.setScopeId(iface.name());
                if (!v6.contains(a))
                    v6 << a;
            }
        }
    }
    return v4 + v6;
}

QString firstIPv4()
{
    for (const QHostAddress &a : addresses())
        if (a.protocol() == QAbstractSocket::IPv4Protocol)
            return a.toString();
    return {};
}

namespace
{
struct Subnet {
    QHostAddress ip;
    int prefix = 0;
};
QMutex s_subnetLock;
QList<Subnet> s_subnets;
QElapsedTimer s_subnetAge;
}

void refresh()
{
    QMutexLocker l(&s_subnetLock);
    s_subnetAge.invalidate();
}

bool isNeighbour(const QHostAddress &peer)
{
    const QHostAddress p = plain(peer);
    if (!isPrivate(p))
        return false;
    QList<Subnet> nets;
    {
        QMutexLocker l(&s_subnetLock);
        if (!s_subnetAge.isValid() || s_subnetAge.elapsed() > 30000) {
            s_subnets.clear();
            for (const QNetworkInterface &iface : interfaces())
                for (const QNetworkAddressEntry &e : iface.addressEntries())
                    if (isPrivate(e.ip()) && e.prefixLength() > 0)
                        s_subnets.append(Subnet{e.ip(), e.prefixLength()});
            s_subnetAge.start();
        }
        nets = s_subnets;
    }
    for (const Subnet &n : std::as_const(nets))
        if (p.protocol() == n.ip.protocol() && p.isInSubnet(n.ip, n.prefix))
            return true;
    return false;
}

QString text(const QHostAddress &peer)
{
    return plain(peer).toString();
}

QString urlHost(const QString &ip)
{
    if (!ip.contains(QLatin1Char(':')))
        return ip;
    QString h = ip;
    h.replace(QLatin1Char('%'), QStringLiteral("%25"));
    return QLatin1Char('[') + h + QLatin1Char(']');
}

bool selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("lan %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    const auto priv = [](const char *s) { return isPrivate(QHostAddress(QString::fromLatin1(s))); };
    check("private IPv4 ranges", priv("10.0.0.1") && priv("10.255.255.255") && priv("172.16.0.1") && priv("172.31.255.254")
                                     && priv("192.168.1.20"));
    check("not private: public, CGNAT, loopback, edges", !priv("8.8.8.8") && !priv("100.64.0.1") && !priv("127.0.0.1")
                                                             && !priv("172.15.255.255") && !priv("172.32.0.0") && !priv("192.169.0.1")
                                                             && !priv("11.0.0.1"));
    check("private IPv6: link-local and fd00::/8", priv("fe80::1") && priv("febf::1") && priv("fd12:3456::1") && !priv("fc00::1")
                                                       && !priv("2001:db8::1") && !priv("::1") && !priv("fec0::1"));
    check("IPv4 seen through IPv6", priv("::ffff:192.168.0.5") && !priv("::ffff:8.8.8.8"));
    using F = QNetworkInterface::InterfaceFlag;
    const QNetworkInterface::InterfaceFlags up(F::IsUp | F::IsRunning | F::CanMulticast);
    check("Wi-Fi and Ethernet count", lanKind(QNetworkInterface::Wifi, up, QStringLiteral("wlan0"), QStringLiteral("wlan0"))
                                          && lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32769"), QStringLiteral("Ethernet"))
                                          && lanKind(QNetworkInterface::Wifi, up, QStringLiteral("wireless_32768"), QStringLiteral("Wi-Fi")));
    check("VPN, cellular, containers, Hyper-V/WSL and loopback don't",
          !lanKind(QNetworkInterface::Unknown, up, QStringLiteral("wg0"), QStringLiteral("wg0"))
              && !lanKind(QNetworkInterface::Unknown, up, QStringLiteral("tun0"), QStringLiteral("tun0"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32770"), QStringLiteral("OpenVPN TAP-Windows6"))
              && !lanKind(QNetworkInterface::Virtual, up, QStringLiteral("unknown_1"), QStringLiteral("Home"))
              && !lanKind(QNetworkInterface::Unknown, up, QStringLiteral("wwan0"), QStringLiteral("wwan0"))
              && !lanKind(QNetworkInterface::Unknown, up, QStringLiteral("iftype53_32768"), QStringLiteral("home"))
              && !lanKind(QNetworkInterface::Unknown, up, QStringLiteral("mbb_32768"), QStringLiteral("Cellular"))
              && !lanKind(QNetworkInterface::Ppp, up, QStringLiteral("ppp_32768"), QStringLiteral("Dial-up"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("docker0"), QStringLiteral("docker0"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32771"), QStringLiteral("vEthernet (WSL)"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32772"), QStringLiteral("vEthernet (WSL (Hyper-V firewall))"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32773"), QStringLiteral("vEthernet (Default Switch)"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32774"), QStringLiteral("Hyper-V Virtual Ethernet Adapter"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32775"), QStringLiteral("DEFAULT SWITCH"))
              && !lanKind(QNetworkInterface::Ethernet, up, QStringLiteral("ethernet_32776"), QStringLiteral("vEthernet (DockerNAT)"))
              && !lanKind(QNetworkInterface::Ethernet, up | F::IsPointToPoint, QStringLiteral("eth1"), QStringLiteral("eth1"))
              && !lanKind(QNetworkInterface::Loopback, up | F::IsLoopBack, QStringLiteral("lo"), QStringLiteral("lo"))
              && !lanKind(QNetworkInterface::Wifi, F::IsUp, QStringLiteral("wlan0"), QStringLiteral("wlan0")));
    const QList<QHostAddress> here = addresses();
    QStringList shown;
    for (const QHostAddress &a : here)
        shown << a.toString();
    std::printf("lan addresses here: %s\n", shown.isEmpty() ? "none" : qPrintable(shown.join(QStringLiteral(", "))));
    bool onlyPrivate = true;
    for (const QHostAddress &a : here)
        onlyPrivate &= isPrivate(a);
    check("only private addresses listed", onlyPrivate);
    return all;
}
}
