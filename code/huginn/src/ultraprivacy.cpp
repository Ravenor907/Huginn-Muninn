#include "ultraprivacy.h"
#include "settings.h"
#include "system.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QHostAddress>
#include <QNetworkAccessManager>
#include <QProcess>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSettings>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QUrl>
#include <QWebSocket>
#include <atomic>
#include <iterator>
#include <cstdio>

namespace {
std::atomic<bool> s_on{false};
std::atomic<int> s_port{0};
UltraPrivacy *s_instance = nullptr;
const char *kKey = "privacy/ultraPrivacy";

QString bullets(const QStringList &lines)
{
    QStringList out;
    for (const QString &l : lines)
        out << QStringLiteral("• ") + l;
    return out.join(QLatin1Char('\n'));
}

// Round 19: started again by itself this long after Tor ended or couldn't start (then the banner's Try again).
const int kRetryMs[] = {5000, 30000, 120000};
// While ready, Tor is asked how it's doing every this many seconds (each second while it connects).
const int kReadyPollEvery = 10;

// "127.0.0.1:43215" (or quoted, or the first of several) to its port when it's on 127.0.0.1, else 0.
int loopbackPort(QString v)
{
    v = v.trimmed();
    if (v.startsWith(QLatin1Char('"')))
        v = v.mid(1, v.indexOf(QLatin1Char('"'), 1) - 1);
    v = v.section(QLatin1Char(' '), 0, 0);
    if (!v.startsWith(QLatin1String("127.0.0.1:")))
        return 0;
    bool ok = false;
    const int p = v.mid(10).toInt(&ok);
    return ok && p > 0 && p < 65536 ? p : 0;
}
}

UltraPrivacy::UltraPrivacy(QObject *parent)
    : QObject(parent)
{
    if (!s_instance)
        s_instance = this;
}

UltraPrivacy::~UltraPrivacy()
{
    if (m_proc) {
        QObject::disconnect(m_proc, nullptr, this, nullptr);
        m_proc->kill();
        m_proc->waitForFinished(2000);
    }
    if (s_instance == this)
        s_instance = nullptr;
}

UltraPrivacy *UltraPrivacy::instance() { return s_instance; }

bool UltraPrivacy::on() { return s_on.load(); }

QNetworkProxy UltraPrivacy::proxy()
{
    if (!s_on.load())
        return QNetworkProxy(QNetworkProxy::DefaultProxy);
    const int p = s_port.load();
    QNetworkProxy x(QNetworkProxy::Socks5Proxy, QStringLiteral("127.0.0.1"), quint16(p > 0 && p < 65536 ? p : kClosedPort));
    // Through Tor only: names go to Tor as names (never looked up here), and no UDP (Tor carries none).
    x.setCapabilities(QNetworkProxy::TunnelingCapability | QNetworkProxy::HostNameLookupCapability);
    return x;
}

void UltraPrivacy::prepare(QWebSocket *ws)
{
    if (ws)
        ws->setProxy(proxy());
}

void UltraPrivacy::prepare(QNetworkAccessManager *nam)
{
    if (nam)
        nam->setProxy(proxy());
}

bool UltraPrivacy::looksLikeVideo(const QByteArray &h)
{
    if (h.size() < 12)
        return false;
    const QByteArray box = h.mid(4, 4);
    if (box == "ftyp") {
        // Photos (HEIC, AVIF) and sound (M4A) use the same box: only those aren't videos.
        static const QList<QByteArray> notVideo{"heic", "heix", "heim", "heis", "mif1", "msf1", "avif", "M4A ", "M4B ", "M4P ", "F4A ", "F4B "};
        return !notVideo.contains(h.mid(8, 4));
    }
    if (box == "moov" || box == "mdat" || box == "wide")
        return true;   // MP4, MOV, 3GP
    if ((box == "free" || box == "skip") && (h.contains("moov") || h.contains("mdat") || h.contains("ftyp")))
        return true;   // an older QuickTime file
    if (h.startsWith(QByteArray("\x1A\x45\xDF\xA3", 4)))
        return true;   // Matroska, WebM
    if (h.startsWith("RIFF") && h.mid(8, 4) == "AVI ")
        return true;
    if (h.startsWith(QByteArray("\x30\x26\xB2\x75\x8E\x66\xCF\x11", 8)))
        return true;   // ASF, WMV
    if (h.startsWith("FLV\x01"))
        return true;
    if (h.startsWith(QByteArray("\x00\x00\x01\xBA", 4)) || h.startsWith(QByteArray("\x00\x00\x01\xB3", 4)))
        return true;   // MPEG program or video stream
    return false;
}

bool UltraPrivacy::blocksPath(const QString &)
{
    // 1.42: videos go over Tor or on the same Wi-Fi under Ultra privacy too; nothing is held back.
    return false;
}

bool UltraPrivacy::isVideoName(const QString &name)
{
    static const QStringList ext{QStringLiteral("mp4"), QStringLiteral("m4v"), QStringLiteral("mov"), QStringLiteral("webm"),
                                 QStringLiteral("mkv"), QStringLiteral("3gp"), QStringLiteral("3g2"), QStringLiteral("avi"),
                                 QStringLiteral("wmv"), QStringLiteral("flv"), QStringLiteral("mpg"), QStringLiteral("mpeg"),
                                 QStringLiteral("ogv"), QStringLiteral("hevc"), QStringLiteral("h264"), QStringLiteral("h265")};
    const int dot = name.lastIndexOf(QLatin1Char('.'));
    return dot >= 0 && ext.contains(name.mid(dot + 1).toLower());
}

QString UltraPrivacy::torState() const
{
    switch (m_tor) {
    case Tor::Off: return QStringLiteral("off");
    case Tor::Starting: return QStringLiteral("starting");
    case Tor::Ready: return QStringLiteral("ready");
    case Tor::Slow: return QStringLiteral("slow");
    case Tor::Blocked: return QStringLiteral("blocked");
    case Tor::Missing: return QStringLiteral("missing");
    }
    return QString();
}

QString UltraPrivacy::statusLine() const
{
    switch (m_tor) {
    case Tor::Off: return QStringLiteral("Off. Hides your internet address using Tor");
    case Tor::Starting: return QStringLiteral("Connecting to Tor… %1%").arg(m_progress);
    case Tor::Ready: return QStringLiteral("On. Your internet address is hidden");
    case Tor::Slow: return QStringLiteral("Tor is slow to connect… %1%").arg(m_progress);
    case Tor::Blocked: return QStringLiteral("Tor can't connect on this network");
    case Tor::Missing: return m_noBinary ? QStringLiteral("Tor isn't in this copy of Huginn") : QStringLiteral("Tor couldn't start");
    }
    return QString();
}

QString UltraPrivacy::bannerText() const
{
    switch (m_tor) {
    case Tor::Slow: return QStringLiteral("Tor is slow to connect. Messages wait until it does.");
    case Tor::Blocked: return QStringLiteral("Tor can't connect here. It may be blocked on this network. Messages wait until it connects.");
    case Tor::Missing:
#ifdef Q_OS_WIN
        // Round 19 (FIX 8): antivirus took tor.exe away (or the update left it out). Said plainly, with the way back.
        if (m_noBinary)
            return QStringLiteral("Tor isn't in this copy of Huginn: antivirus may have removed lib\\tor\\tor.exe. Nothing goes out until it's "
                                  "back. Allow tor.exe in your antivirus, then use Check now in Settings › Updates and about, or turn Ultra "
                                  "privacy off.");
#else
        if (m_noBinary)
            return QStringLiteral("Tor isn't installed with this copy of Huginn. Nothing goes out until it is, or you turn Ultra privacy off.");
#endif
        return QStringLiteral("Tor couldn't start on this computer. Nothing goes out until it does. Try again, or turn Ultra privacy off.");
    default: return QString();
    }
}

QString UltraPrivacy::nearbyNote() const
{
    return QStringLiteral("Still on with Ultra privacy: Nearby and the same Wi-Fi. They stay on this Wi-Fi, so on a public Wi-Fi others there can see Nearby");
}

QString UltraPrivacy::whatItDoes() const
{
    return bullets({QStringLiteral("Hides your internet address from the relays, from GitHub and from everyone you message."),
                    QStringLiteral("Messages, update checks and downloads go through Tor, a volunteer network that hides where they come from."),
                    QStringLiteral("Videos send over Tor. Calls are off."),
                    QStringLiteral("Tor adds too much delay for a call, and the call relay would see your address."),
                    QStringLiteral("Anything that would still show your address is turned off while it's on."),
                    QStringLiteral("Nearby and the same Wi-Fi keep working: they stay on this Wi-Fi.")});
}

QString UltraPrivacy::downsides() const
{
    return QStringLiteral("Ultra privacy has these downsides:\n")
        + bullets({QStringLiteral("Messages are slower, and may arrive much later while your computer sleeps."),
                   QStringLiteral("Videos send over Tor. Calls are off."),
                   QStringLiteral("Calls are off, both ways, because Tor adds too much delay and the call relay sees your address. Calls to you show as missed."),
                   QStringLiteral("Direct transfers straight over the internet are off: videos and files go over Tor (slower) or on the same Wi-Fi."),
                   QStringLiteral("Link previews and GIF search are off."),
                   QStringLiteral("Update checks and downloads are slower."),
                   QStringLiteral("Uses more power."),
                   QStringLiteral("Tor is built in, which makes Huginn's download a few MB bigger."),
                   QStringLiteral("Tor is blocked in some places. If it can't connect, you can wait or turn Ultra privacy off."),
                   QStringLiteral("Tor is run by volunteers, so its speed is out of our hands."),
                   QStringLiteral("It adds no encryption. Messages are already end-to-end encrypted; Ultra privacy hides where they come from, not who you talk to.")});
}

QString UltraPrivacy::setupText() const
{
    return bullets({QStringLiteral("Messages and calls are end-to-end encrypted. Only the people in the chat can read or hear them."),
                    QStringLiteral("Relays only carry sealed messages. They can't read them or see who sent them."),
                    QStringLiteral("Photos are sent without their location or camera details."),
                    QStringLiteral("No phone number, no account.")});
}

QString UltraPrivacy::setupUltra() const
{
    return QStringLiteral("Want more? Ultra privacy hides your internet address too, using Tor. Videos send over Tor. Calls are off. It's in "
                          "Settings › Privacy and lock, off until you turn it on. It has a few trade-offs, explained before you turn it on.");
}

// ------------------------------------------------------------------ the switch

void UltraPrivacy::start()
{
    const bool want = Settings::store().value(QLatin1String(kKey), false).toBool();
    if (!want)
        return;
    use(true);
    startTor();
}

void UltraPrivacy::turnOn()
{
    if (on())
        return;
    {
        QSettings s = Settings::store();
        s.setValue(QLatin1String(kKey), true);
        s.sync();
    }
    use(true);
    startTor();
}

void UltraPrivacy::turnOff()
{
    {
        QSettings s = Settings::store();
        s.setValue(QLatin1String(kKey), false);
        s.sync();
    }
    // A file still needs Tor (cap f4): it keeps running until that one is done, but nothing else goes through it.
    if (m_demand.isEmpty())
        stopTor();
    use(false);
}

void UltraPrivacy::needTor(const QString &tag)
{
    UltraPrivacy *u = s_instance;
    if (!u || tag.isEmpty())
        return;
    if (u->m_demand.isEmpty())
        u->m_filesFailed = false;
    u->m_demand.insert(tag);
    if (!u->m_proc) {
        if (!u->on())
            u->m_retries = 0;
        u->startTor();
    } else if (u->m_filesPort > 0) {
        Q_EMIT u->filesTorChanged();
    }
}

void UltraPrivacy::releaseTor(const QString &tag)
{
    UltraPrivacy *u = s_instance;
    if (!u || !u->m_demand.remove(tag))
        return;
    if (!u->wanted())
        u->stopTor();
}

int UltraPrivacy::filesPort() { return s_instance ? s_instance->m_filesPort : 0; }
bool UltraPrivacy::filesFailed() { return s_instance && s_instance->m_filesFailed; }

void UltraPrivacy::setFilesPort(int p)
{
    if (p == m_filesPort)
        return;
    m_filesPort = p;
    Q_EMIT filesTorChanged();
}

void UltraPrivacy::stopNow()
{
    QProcess *p = m_proc;
    stopTor();
    s_port.store(0);
    if (p && p->state() != QProcess::NotRunning && !p->waitForFinished(3000)) {
        p->kill();
        p->waitForFinished(2000);
    }
    if (on())
        setTor(Tor::Missing);
    m_retries = int(std::size(kRetryMs));   // not started again by itself
    Q_EMIT redial();
}

void UltraPrivacy::waitLonger()
{
    m_since.start();
    if (m_tor == Tor::Slow || m_tor == Tor::Blocked)
        setTor(Tor::Starting);
}

void UltraPrivacy::tryAgain()
{
    if (!on())
        return;
    if (m_proc) {
        waitLonger();
        return;
    }
    m_retries = 0;
    startTor();
}

void UltraPrivacy::use(bool want)
{
    if (s_on.load() == want && (want || m_tor == Tor::Off))
        return;
    const bool turning = want && !s_on.load();
    s_on.store(want);
    s_port.store(0);
    m_progress = 0;
    m_circuit = false;
    m_warned = false;
    m_since.start();
    m_retries = 0;
    setTor(want ? Tor::Starting : Tor::Off);
    Q_EMIT redial();
    if (turning)
        Q_EMIT turnedOn();
}

void UltraPrivacy::ready(int socksPort)
{
    if (!s_on.load() || socksPort <= 0 || socksPort > 65535)
        return;
    const bool again = s_port.load() != socksPort || m_tor != Tor::Ready;
    s_port.store(socksPort);
    m_progress = 100;
    m_retries = 0;
    setTor(Tor::Ready);
    if (again)
        Q_EMIT redial();
}

void UltraPrivacy::setTor(Tor t)
{
    m_tor = t;
    m_shownProgress = m_progress;
    Q_EMIT changed();
}

UltraPrivacy::Tor UltraPrivacy::startState(qint64 ms, bool warned)
{
    if (ms >= kBlockedMs || (warned && ms >= kSlowMs))
        return Tor::Blocked;
    if (ms >= kSlowMs)
        return Tor::Slow;
    return Tor::Starting;
}

int UltraPrivacy::percentOf(const QString &bootstrapPhase)
{
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("PROGRESS=(\\d+)")).match(bootstrapPhase);
    return m.hasMatch() ? m.captured(1).toInt() : 0;
}

QByteArray UltraPrivacy::hashPassword(const QByteArray &secret, const QByteArray &salt)
{
    // Tor's secret_to_key_rfc2440 with its fixed count byte 0x60: 65536 bytes of salt+secret, repeated, through SHA-1.
    const quint8 c = 0x60;
    qint64 count = qint64(16 + (c & 15)) << ((c >> 4) + 6);
    const QByteArray tmp = salt.left(8) + secret;
    QCryptographicHash h(QCryptographicHash::Sha1);
    while (count > 0) {
        if (count >= tmp.size()) {
            h.addData(tmp);
            count -= tmp.size();
        } else {
            h.addData(tmp.left(int(count)));
            count = 0;
        }
    }
    QByteArray spec = salt.left(8);
    spec.append(char(c));
    return QByteArrayLiteral("16:") + (spec + h.result()).toHex().toUpper();
}

QString UltraPrivacy::torBinary()
{
#ifdef Q_OS_WIN
    const QString p = QCoreApplication::applicationDirPath() + QStringLiteral("/lib/tor/tor.exe");
#ifdef HUGINN_TOR_SHA256
    // Round 19 (security 15): when the build pins tor.exe's SHA-256, a changed or partly quarantined copy is never run.
    QFile f(p);
    if (!f.open(QIODevice::ReadOnly))
        return QString();
    QCryptographicHash h(QCryptographicHash::Sha256);
    if (!h.addData(&f) || h.result().toHex() != QByteArray(HUGINN_TOR_SHA256).toLower())
        return QString();
    return p;
#else
    return QFileInfo::exists(p) ? p : QString();
#endif
#else
    // The Flatpak's own (built from source in the manifest), else one the system installed. Only these known places,
    // never whatever "tor" comes first on PATH.
    for (const char *p : {"/app/bin/tor", "/usr/bin/tor", "/usr/sbin/tor", "/usr/local/bin/tor"}) {
        const QFileInfo fi(QString::fromLatin1(p));
        if (fi.isFile() && fi.isExecutable())
            return fi.absoluteFilePath();
    }
    return QString();
#endif
}

// ------------------------------------------------------------------ Tor

void UltraPrivacy::startTor()
{
    if (m_proc)
        return;
    const QString bin = torBinary();
    m_dir = System::dataDir() + QStringLiteral("/tor");
    const QString dir = m_dir;
    m_socksPort = 0;
    m_ctlPort = 0;
    m_pidOk = false;
    m_progress = 0;
    m_since.start();
    m_noBinary = bin.isEmpty();
    if (bin.isEmpty() || !QDir().mkpath(dir + QStringLiteral("/data"))) {
        torLost();
        return;
    }
    // Empty settings files of its own: nothing from a Tor installed on the system is read. The port file of the last
    // run goes, so only this run's is read; Tor's own log starts over each time (kept in its folder, for "couldn't start").
    for (const QString &f : {QStringLiteral("/torrc"), QStringLiteral("/torrc-defaults")}) {
        QFile t(dir + f);
        if (t.open(QIODevice::WriteOnly | QIODevice::Truncate))
            t.close();
    }
    QFile::remove(dir + QStringLiteral("/ctl"));
    QFile::remove(dir + QStringLiteral("/tor.log"));
    // A new random password every start; Tor only ever sees its hash.
    QByteArray secret(32, Qt::Uninitialized), salt(8, Qt::Uninitialized);
    QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(secret.data()), secret.size() / 4);
    QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(salt.data()), salt.size() / 4);
    m_password = secret.toHex();
    m_authed = false;
    m_circuit = false;
    m_warned = false;
    m_ctlBuffer.clear();

    m_proc = new QProcess(this);
    m_proc->setProgram(bin);
#ifdef Q_OS_WIN
    // tor.exe reads its arguments in the ANSI code page: a folder name with other letters (or a long path) would break.
    // It starts in its own folder and is given names relative to it.
    m_proc->setWorkingDirectory(dir);
    const QString at;
#else
    const QString at = dir + QLatin1Char('/');
#endif
    // Tor picks both ports itself (no gap where another program could take one) and writes the control port to "ctl";
    // the SOCKS port is asked for once connected. Each relay and site gets its own circuit (IsolateDestAddr).
    m_proc->setArguments({QStringLiteral("-f"), QDir::toNativeSeparators(at + QStringLiteral("torrc")),
                          QStringLiteral("--defaults-torrc"), QDir::toNativeSeparators(at + QStringLiteral("torrc-defaults")),
                          QStringLiteral("--ignore-missing-torrc"),
                          QStringLiteral("--DataDirectory"), QDir::toNativeSeparators(at + QStringLiteral("data")),
                          QStringLiteral("--SocksPort"), QStringLiteral("127.0.0.1:auto IsolateDestAddr"),
                          QStringLiteral("--ControlPort"), QStringLiteral("127.0.0.1:auto"),
                          QStringLiteral("--ControlPortWriteToFile"), QDir::toNativeSeparators(at + QStringLiteral("ctl")),
                          QStringLiteral("--Log"), QStringLiteral("notice file ") + QDir::toNativeSeparators(at + QStringLiteral("tor.log")),
                          QStringLiteral("--HashedControlPassword"), QString::fromLatin1(hashPassword(m_password, salt)),
                          QStringLiteral("--ClientOnly"), QStringLiteral("1"),
                          QStringLiteral("--AvoidDiskWrites"), QStringLiteral("1"),
                          QStringLiteral("--__OwningControllerProcess"), QString::number(QCoreApplication::applicationPid())});
    m_proc->setStandardOutputFile(QProcess::nullDevice());
    m_proc->setStandardErrorFile(QProcess::nullDevice());
    QProcess *proc = m_proc;
    connect(m_proc, &QProcess::errorOccurred, this, [this, proc](QProcess::ProcessError e) {
        if (e == QProcess::FailedToStart && m_proc == proc) {
            m_proc = nullptr;
            proc->deleteLater();
            torLost();
        }
    });
    connect(m_proc, &QProcess::finished, this, [this, proc] {
        if (m_proc != proc)
            return;
        // Tor ended while Ultra privacy is on: nothing goes out (closed port) until it's back.
        m_proc = nullptr;
        proc->deleteLater();
        torLost();
    });
    if (on() && m_tor != Tor::Starting)
        setTor(Tor::Starting);
    m_proc->start();

    if (!m_timer) {
        m_timer = new QTimer(this);
        m_timer->setInterval(1000);
        connect(m_timer, &QTimer::timeout, this, &UltraPrivacy::poll);
    }
    m_ticks = 0;
    m_timer->start();
}

void UltraPrivacy::torLost()
{
    if (m_ctl) {
        m_ctl->abort();
        m_ctl->deleteLater();
        m_ctl = nullptr;
    }
    m_authed = false;
    m_password.clear();
    m_filesPort = 0;
    if (!on()) {
        // Started on demand for a file: it says so (the file's card), and isn't started again by itself.
        if (!m_demand.isEmpty()) {
            m_filesFailed = true;
            Q_EMIT filesTorChanged();
        }
        return;
    }
    s_port.store(0);
    setTor(Tor::Missing);
    Q_EMIT redial();
    // Round 19: a port clash, an old Tor still holding its folder, or a scan holding tor.exe passes: started again by
    // itself a few times before only the banner's Try again is left.
    if (!m_noBinary && m_retries < int(std::size(kRetryMs))) {
        const int wait = kRetryMs[m_retries++];
        QTimer::singleShot(wait, this, [this] {
            if (on() && !m_proc && m_tor == Tor::Missing)
                startTor();
        });
    }
}

void UltraPrivacy::notOurs()
{
    // The password wasn't taken, or another program answers on the port: not the Tor this Huginn started. Nothing goes
    // out through it.
    stopTor();
    m_retries = int(std::size(kRetryMs));   // never by itself: the banner's Try again
    s_port.store(0);
    m_filesFailed = true;
    Q_EMIT filesTorChanged();
    if (!on())
        return;
    setTor(Tor::Missing);
    Q_EMIT redial();
}

void UltraPrivacy::stopTor()
{
    if (m_timer)
        m_timer->stop();
    if (m_ctl) {
        // TAKEOWNERSHIP: Tor ends when its controller goes.
        if (m_authed)
            m_ctl->write("SIGNAL SHUTDOWN\r\n");
        m_ctl->flush();
        m_ctl->disconnectFromHost();
        m_ctl->deleteLater();
        m_ctl = nullptr;
    }
    m_authed = false;
    m_pidOk = false;
    m_filesPort = 0;
    if (m_proc) {
        QProcess *proc = m_proc;
        m_proc = nullptr;
        QObject::disconnect(proc, nullptr, this, nullptr);
        connect(proc, &QProcess::finished, proc, &QObject::deleteLater);
        QTimer::singleShot(3000, proc, [proc] {
            if (proc->state() != QProcess::NotRunning)
                proc->kill();
        });
    }
    m_password.clear();
}

void UltraPrivacy::readControlFile()
{
    // "PORT=127.0.0.1:43215", written by Tor once its control port listens.
    QFile f(m_dir + QStringLiteral("/ctl"));
    if (!f.open(QIODevice::ReadOnly))
        return;
    const QList<QByteArray> lines = f.read(4096).split('\n');
    for (const QByteArray &l : lines)
        if (l.trimmed().startsWith("PORT="))
            m_ctlPort = loopbackPort(QString::fromLatin1(l.trimmed().mid(5)));
}

void UltraPrivacy::connectControl()
{
    if (m_ctl || m_ctlPort <= 0)
        return;
    m_ctl = new QTcpSocket(this);
    m_ctl->setProxy(QNetworkProxy::NoProxy);
    QTcpSocket *ctl = m_ctl;
    connect(m_ctl, &QTcpSocket::connected, this, [this, ctl] {
        if (m_ctl != ctl)
            return;
        ctl->write("AUTHENTICATE \"" + m_password + "\"\r\nTAKEOWNERSHIP\r\n");
    });
    connect(m_ctl, &QTcpSocket::readyRead, this, &UltraPrivacy::controlRead);
    connect(m_ctl, &QTcpSocket::errorOccurred, this, [this, ctl](QAbstractSocket::SocketError) {
        if (m_ctl != ctl)
            return;
        // Tor isn't listening yet: tried again on the next poll.
        m_ctl = nullptr;
        m_authed = false;
        ctl->deleteLater();
    });
    m_ctl->connectToHost(QHostAddress::LocalHost, quint16(m_ctlPort));
}

void UltraPrivacy::poll()
{
    if (!wanted() || !m_proc)
        return;
    if (m_ctlPort <= 0)
        readControlFile();
    if (!m_ctl) {
        connectControl();
    } else if (m_authed && m_ctl->state() == QAbstractSocket::ConnectedState) {
        // Each second while it connects; every few seconds once ready (enough to see lost circuits).
        if (m_tor != Tor::Ready || ++m_ticks % kReadyPollEvery == 0)
            m_ctl->write("GETINFO process/pid net/listeners/socks status/bootstrap-phase status/circuit-established\r\n");
    }
    if (on() && m_tor != Tor::Ready && m_tor != Tor::Missing) {
        const Tor t = startState(m_since.elapsed(), m_warned);
        if (t != m_tor)
            setTor(t);
        else if (m_progress != m_shownProgress) {
            m_shownProgress = m_progress;
            Q_EMIT changed();   // the percentage, only when it moved
        }
    }
}

void UltraPrivacy::controlRead()
{
    if (!m_ctl)
        return;
    m_ctlBuffer += m_ctl->readAll();
    if (m_ctlBuffer.size() > 65536)
        m_ctlBuffer = m_ctlBuffer.right(4096);
    int nl;
    while ((nl = m_ctlBuffer.indexOf("\r\n")) >= 0) {
        const QString line = QString::fromUtf8(m_ctlBuffer.left(nl));
        m_ctlBuffer.remove(0, nl + 2);
        if (line.startsWith(QLatin1String("515")) || line.startsWith(QLatin1String("514"))) {
            notOurs();
            return;
        }
        if (!m_authed && line == QLatin1String("250 OK")) {
            m_authed = true;
            continue;
        }
        if (line.startsWith(QLatin1String("250-process/pid="))) {
            // The Tor this Huginn started, not another program that answers on that port.
            const qint64 pid = line.mid(16).trimmed().toLongLong();
            m_pidOk = m_proc && pid > 0 && pid == m_proc->processId();
            if (!m_pidOk) {
                notOurs();
                return;
            }
        } else if (line.startsWith(QLatin1String("250-net/listeners/socks="))) {
            const int p = loopbackPort(line.mid(24));
            if (p > 0)
                m_socksPort = p;
        } else if (line.startsWith(QLatin1String("250-status/bootstrap-phase="))) {
            m_progress = qBound(0, percentOf(line), 100);
            m_warned = line.contains(QLatin1String("=WARN "));
        } else if (line.startsWith(QLatin1String("250-status/circuit-established="))) {
            m_circuit = line.endsWith(QLatin1Char('1'));
        } else if (line == QLatin1String("250 OK") && m_authed) {
            // Cap f4: files use the same Tor, on demand too.
            setFilesPort(m_circuit && m_progress >= 100 && m_pidOk && m_socksPort > 0 ? m_socksPort : 0);
            if (!on())
                continue;
            if (m_circuit && m_progress >= 100 && m_pidOk && m_socksPort > 0)
                ready(m_socksPort);
            else if (m_tor == Tor::Ready && !m_circuit) {
                // Lost its circuits: closed until they're back.
                s_port.store(0);
                m_since.start();
                setTor(Tor::Starting);
                Q_EMIT redial();
            }
        }
    }
}

// ------------------------------------------------------------------ self-test

bool UltraPrivacy::selfTest()
{
    bool ok = true;
    auto check = [&ok](bool c, const char *what) {
        std::printf("ultra privacy: %s: %s\n", what, c ? "ok" : "FAILED");
        ok = ok && c;
    };
    UltraPrivacy u;   // never started: no saved switch read, no Tor
    u.use(false);
    check(!on() && proxy().type() == QNetworkProxy::DefaultProxy, "off by default, connections go as before");
    check(directAllowed() && videoSendAllowed() && previewsAllowed() && gifSearchAllowed() && !blocksFile(QStringLiteral("a.mp4")) && filesPort() == 0,
          "nothing is off while it's off");

    u.use(true);
    check(on() && u.active() && u.torState() == QLatin1String("starting"), "on: Tor starting");
    check(!directAllowed(), "direct calls, files and photos are off");
    check(videoSendAllowed(), "sending videos is allowed (Tor or the same Wi-Fi)");
    check(!previewsAllowed(), "link previews are off");
    check(!gifSearchAllowed(), "GIF search is off");
    check(!callsAllowed(), "calls are off, both ways");
    check(looksLikeVideo(QByteArray("\x00\x00\x00\x18" "ftypmp42" "\x00\x00\x00\x00", 16)) && looksLikeVideo(QByteArray("\x1A\x45\xDF\xA3" "\x9F\x42\x86\x81\x01\x42\xF7\x81", 12))
              && looksLikeVideo(QByteArray("RIFF\x10\x00\x00\x00" "AVI LIST", 16)) && !looksLikeVideo(QByteArray("\xFF\xD8\xFF\xE0\x00\x10" "JFIF\x00\x01\x01\x00", 14))
              && !looksLikeVideo(QByteArray("hello there, nothing here")) && !looksLikeVideo(QByteArray("\x00\x00\x00\x18" "ftypheic" "\x00\x00\x00\x00", 16))
              && !looksLikeVideo(QByteArray("\x00\x00\x00\x18" "ftypM4A " "\x00\x00\x00\x00", 16)),
          "a video renamed to another name is still held back");
    check(!blocksFile(QStringLiteral("clip.MP4")) && !blocksFile(QStringLiteral("photo.jpg")) && !blocksPath(QStringLiteral("a.webm")),
          "nothing is held back: videos go over Tor or on the same Wi-Fi");
    const QNetworkProxy closed = proxy();
    check(closed.type() == QNetworkProxy::Socks5Proxy && closed.hostName() == QLatin1String("127.0.0.1") && closed.port() == kClosedPort,
          "fails closed until Tor is ready");
    check(closed.capabilities().testFlag(QNetworkProxy::HostNameLookupCapability) && !closed.capabilities().testFlag(QNetworkProxy::UdpTunnelingCapability),
          "names are looked up by Tor, never here");
    check(u.offNote() == QLatin1String("Off while Ultra privacy is on") && videoOffText() == QLatin1String("Videos send over Tor. Calls are off."), "the note every feature shows");

    // The proxy goes on the sockets and network managers that use prepare() (nostr.cpp, updater.cpp, turnclient.cpp).
    {
        QWebSocket ws;
        prepare(&ws);
        QNetworkAccessManager nam;
        prepare(&nam);
        check(ws.proxy() == proxy() && nam.proxy() == proxy(), "a relay socket and a network manager get Tor's proxy");
    }

    // A relay's WebSocket reaches a pretend Tor, asking for the relay by name.
    {
        QTcpServer fake;
        QString asked;
        bool listening = fake.listen(QHostAddress::LocalHost, 0);
        QObject::connect(&fake, &QTcpServer::newConnection, &fake, [&fake, &asked] {
            while (QTcpSocket *s = fake.nextPendingConnection()) {
                QObject::connect(s, &QTcpSocket::readyRead, s, [s, &asked] {
                    QByteArray b = s->property("buf").toByteArray() + s->readAll();
                    if (!s->property("greeted").toBool()) {
                        if (b.size() < 2 || quint8(b.at(0)) != 5 || b.size() < 2 + quint8(b.at(1))) {
                            s->setProperty("buf", b);
                            return;
                        }
                        b.remove(0, 2 + quint8(b.at(1)));
                        s->setProperty("greeted", true);
                        s->write(QByteArray("\x05\x00", 2));
                    }
                    if (b.size() >= 5) {
                        if (quint8(b.at(3)) == 3) {
                            const int len = quint8(b.at(4));
                            if (b.size() < 5 + len) {
                                s->setProperty("buf", b);
                                return;
                            }
                            asked = QString::fromLatin1(b.mid(5, len));
                        } else {
                            asked = QStringLiteral("an address");
                        }
                        s->write(QByteArray("\x05\x01\x00\x01\x00\x00\x00\x00\x00\x00", 10));   // refused
                        s->disconnectFromHost();
                    }
                    s->setProperty("buf", b);
                });
            }
        });
        u.ready(listening ? fake.serverPort() : 0);
        check(listening && proxy().port() == fake.serverPort() && u.torState() == QLatin1String("ready"), "Tor ready: its port is used");
        QWebSocket ws;
        prepare(&ws);
        bool opened = false;
        QObject::connect(&ws, &QWebSocket::connected, &ws, [&opened] { opened = true; });
        QEventLoop loop;
        QTimer give;
        give.setSingleShot(true);
        QObject::connect(&give, &QTimer::timeout, &loop, &QEventLoop::quit);
        QTimer watch;
        QObject::connect(&watch, &QTimer::timeout, &loop, [&asked, &loop] {
            if (!asked.isEmpty())
                loop.quit();
        });
        watch.start(20);
        give.start(10000);
        ws.open(QUrl(QStringLiteral("wss://relay.example.invalid")));
        loop.exec();
        ws.abort();
        check(asked == QLatin1String("relay.example.invalid"), "the relay is asked for by name, through Tor");
        check(!opened, "refused by Tor, it never goes another way");
    }

    // Tor's start: slow, blocked, and its progress.
    check(startState(1000, false) == Tor::Starting && startState(kSlowMs, false) == Tor::Slow && startState(kSlowMs, true) == Tor::Blocked
              && startState(kBlockedMs, false) == Tor::Blocked,
          "slow and blocked starts show the banner");
    check(percentOf(QStringLiteral("NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors")) == 45 && percentOf(QString()) == 0, "Tor's progress is read");
    // Tor's HashedControlPassword (crypto_s2k.c, secret_to_key_rfc2440), same as a reference made from its description.
    check(hashPassword("huginn-test", QByteArray::fromHex("0102030405060708"))
              == QByteArrayLiteral("16:010203040506070860F6F12FF6139EF7FB7B9E7256C59907A8DF5B66B7"),
          "the control password's hash");
    // The wording: no dashes, every downside, the setup page's line.
    const QString all = u.whatItDoes() + u.downsides() + u.setupText() + u.setupUltra() + u.statusLine() + u.bannerText();
    check(!all.contains(QChar(0x2014)) && !all.contains(QChar(0x2013)), "no long dashes in the wording");
    const QString d = u.downsides().toLower();
    bool every = true;
    for (const char *w : {"slower", "calls are off", "calls to you show as missed", "direct", "videos send over tor", "link previews", "gif search", "update", "power",
                          "bigger", "blocked", "volunteers", "no encryption"})
        every = every && d.contains(QLatin1String(w));
    check(every, "every downside is listed");
    check(u.setupUltra().contains(QLatin1String("a few trade-offs, explained before you turn it on")) && u.setupUltra().contains(QLatin1String("Videos send over Tor. Calls are off.")),
          "first setup's line");
    check(u.whatItDoes().contains(QLatin1String("Videos send over Tor. Calls are off.")), "what it does says videos go over Tor");

    u.use(false);
    check(!on() && proxy().type() == QNetworkProxy::DefaultProxy, "off again: connections go as before");
    return ok;
}
