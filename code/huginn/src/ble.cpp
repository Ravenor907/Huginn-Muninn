#include "ble.h"
#include "busy.h"
#include "proof.h"

#include <QBluetoothDeviceDiscoveryAgent>
#include <QBluetoothLocalDevice>
#include <QBluetoothUuid>
#ifdef Q_OS_LINUX
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDBusVariant>
#endif
#include <QDateTime>
#include <QGuiApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLowEnergyController>
#include <QLowEnergyDescriptor>
#include <QLowEnergyService>
#include <QPointer>
#include <QThread>
#include <QXmlStreamReader>
#include <QtGlobal>

#include <memory>

namespace
{
const QBluetoothUuid kService(QStringLiteral("7b1e0001-6d75-6e69-6e6e-48756767696e"));
const QBluetoothUuid kHello(QStringLiteral("7b1e0002-6d75-6e69-6e6e-48756767696e"));
const QBluetoothUuid kInbox(QStringLiteral("7b1e0003-6d75-6e69-6e6e-48756767696e"));
const QBluetoothUuid kOutbox(QStringLiteral("7b1e0004-6d75-6e69-6e6e-48756767696e"));

// Scanning keeps BlueZ and Qt busy with every device around (each advertisement is a D-Bus message Qt handles on the
// window's thread), so it comes in short bursts: 10 seconds every 2 minutes in front, every 10 minutes otherwise.
constexpr int kScanEveryMs = 120000;
constexpr int kScanRelaxedMs = 600000;
constexpr int kScanForMs = 10000;
constexpr qint64 kShortLinkMs = 30000;   // a link that ends sooner than this counts as failed (backs off)
constexpr int kTickMs = 5000;
constexpr qint64 kForgetAfterMs = 120000;
constexpr int kSetupTimeoutMs = 25000;   // connect, discover, HELLO and notifications all inside this
constexpr qint64 kMaxBackoffMs = 120000;
constexpr int kMaxLinks = 3;

qint64 nowMs()
{
    return QDateTime::currentMSecsSinceEpoch();
}

#ifdef Q_OS_LINUX
const QString kBluez = QStringLiteral("org.bluez");

// Small blocking D-Bus helpers with a short timeout. Used only off the window's thread (lookUpMtu).
QDBusMessage bluezCall(const QString &path, const QString &iface, const QString &method, const QVariantList &args)
{
    QDBusMessage m = QDBusMessage::createMethodCall(kBluez, path, iface, method);
    m.setArguments(args);
    return QDBusConnection::systemBus().call(m, QDBus::Block, 800);
}

QStringList childNodes(const QString &path)
{
    const QDBusMessage r = bluezCall(path, QStringLiteral("org.freedesktop.DBus.Introspectable"), QStringLiteral("Introspect"), {});
    QStringList out;
    if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
        return out;
    // Child objects are the named <node> elements; the root <node> has no name.
    QXmlStreamReader xml(r.arguments().constFirst().toString());
    while (!xml.atEnd()) {
        if (xml.readNext() == QXmlStreamReader::StartElement && xml.name() == QLatin1String("node")) {
            const QString name = xml.attributes().value(QLatin1String("name")).toString();
            if (!name.isEmpty())
                out << name;
        }
    }
    return out;
}

QVariant bluezProperty(const QString &path, const QString &iface, const QString &name)
{
    const QDBusMessage r = bluezCall(path, QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("Get"), {iface, name});
    if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
        return {};
    return r.arguments().constFirst().value<QDBusVariant>().variant();
}

// The ATT MTU BlueZ negotiated with this device, read from the INBOX characteristic's MTU
// property (BlueZ 5.62 and later). Qt's mtu() only works in the peripheral role on BlueZ, so this
// is the only way to learn it as a central. 0 when it can't be found.
int bluezMtu(const QString &address)
{
    const QString dev = QStringLiteral("dev_") + QString(address).replace(QLatin1Char(':'), QLatin1Char('_')).toUpper();
    for (const QString &hci : childNodes(QStringLiteral("/org/bluez"))) {
        const QString devPath = QStringLiteral("/org/bluez/%1/%2").arg(hci, dev);
        for (const QString &svc : childNodes(devPath)) {
            const QString svcPath = devPath + QLatin1Char('/') + svc;
            if (QBluetoothUuid(bluezProperty(svcPath, QStringLiteral("org.bluez.GattService1"), QStringLiteral("UUID")).toString()) != kService)
                continue;
            for (const QString &ch : childNodes(svcPath)) {
                const QString chPath = svcPath + QLatin1Char('/') + ch;
                const QString iface = QStringLiteral("org.bluez.GattCharacteristic1");
                if (QBluetoothUuid(bluezProperty(chPath, iface, QStringLiteral("UUID")).toString()) == kInbox)
                    return bluezProperty(chPath, iface, QStringLiteral("MTU")).toInt();
            }
        }
    }
    return 0;
}

#endif
// Windows (and anything else): Qt talks to the system's Bluetooth stack itself (WinRT on Windows),
// and QLowEnergyController::mtu() reports the negotiated MTU for a central there.
}

Ble::Ble(std::function<QByteArray()> hello, QObject *parent)
    : QObject(parent)
    , m_hello(std::move(hello))
{
    // Qt decides between BlueZ over D-Bus and its own raw ATT sockets by asking bluetoothd for its
    // version through /proc. Inside a Flatpak that check fails and Qt falls back to raw sockets
    // (or turns LE off), so tell it BlueZ is new enough. Must happen before any controller exists.
    if (qEnvironmentVariableIsEmpty("BLUETOOTH_FORCE_DBUS_LE_VERSION"))
        qputenv("BLUETOOTH_FORCE_DBUS_LE_VERSION", "5.64.0");

    m_scanTimer.setInterval(kScanEveryMs);
    connect(&m_scanTimer, &QTimer::timeout, this, &Ble::scan);
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, &Ble::retime);
    m_tickTimer.setInterval(kTickMs);
    connect(&m_tickTimer, &QTimer::timeout, this, &Ble::tick);
}

Ble::~Ble()
{
    m_running = false;
    // Children are deleted after our members are gone, so unhook them first.
    if (m_agent) {
        QObject::disconnect(m_agent, nullptr, this, nullptr);
        delete m_agent;
    }
    if (m_local) {
        QObject::disconnect(m_local, nullptr, this, nullptr);
        delete m_local;
    }
    for (Link *l : std::as_const(m_links)) {
        QObject::disconnect(l->ctl, nullptr, l->ctx, nullptr);
        if (l->svc)
            QObject::disconnect(l->svc, nullptr, l->ctx, nullptr);
        delete l->svc;
        delete l->ctl;   // deleting the controller also disconnects the device
        delete l->ctx;
        delete l;
    }
    m_links.clear();
}

QVariantList Ble::peers() const
{
    QVariantList out;
    for (const Link *l : m_links) {
        if (!l->ready || !l->said)
            continue;
        out << QVariantMap{{QStringLiteral("address"), l->address},
                           {QStringLiteral("name"), l->name},
                           {QStringLiteral("chatKey"), l->chatKey},
                           {QStringLiteral("mailbox"), l->mailbox},
                           {QStringLiteral("proven"), l->proven}};
    }
    return out;
}

// Bluetooth can't be used at all right now: end every link and forget what we saw.
void Ble::unusable(const QString &why)
{
    dropAll();
    m_seen.clear();
    setError(why);
}

void Ble::setError(const QString &e)
{
    if (m_error == e)
        return;
    m_error = e;
    Q_EMIT changed();
}

void Ble::setRelaxed(bool relaxed)
{
    if (relaxed == m_relaxed)
        return;
    m_relaxed = relaxed;
    retime();
}

void Ble::retime()
{
    const bool front = QGuiApplication::applicationState() == Qt::ApplicationActive;
    const int ms = front && !m_relaxed ? kScanEveryMs : kScanRelaxedMs;
    if (m_scanTimer.interval() != ms)
        m_scanTimer.setInterval(ms);
}

void Ble::start()
{
    if (m_running)
        return;
    m_running = true;
    retime();
    m_scanTimer.start();
    m_tickTimer.start();
    Q_EMIT changed();
    scan();
}

void Ble::stop()
{
    if (!m_running)
        return;
    m_running = false;
    m_scanTimer.stop();
    m_tickTimer.stop();
    if (m_agent && m_agent->isActive())
        m_agent->stop();
    dropAll();
    m_seen.clear();
    m_error.clear();
    Q_EMIT changed();
}

// Works out whether Bluetooth can be used right now and says why not in plain English.
bool Ble::checkAdapter()
{
    if (!m_bluezUp) {
        unusable(QStringLiteral("Huginn can't reach the Bluetooth service on this device. Bluetooth may not be set up, "
                                "or Huginn wasn't given permission to use it."));
        return false;
    }
    // The adapter can appear later (a USB dongle, or BlueZ restarting), so look again until we have one.
    if (!m_local || !m_local->isValid()) {
        delete m_local;
        m_local = new QBluetoothLocalDevice(this);
        if (m_local->isValid()) {
            connect(m_local, &QBluetoothLocalDevice::hostModeStateChanged, this, [this](QBluetoothLocalDevice::HostMode mode) {
                if (!m_running)
                    return;
                if (mode == QBluetoothLocalDevice::HostPoweredOff) {
                    unusable(QStringLiteral("Bluetooth is turned off. Turn it on to text nearby phones without Wi-Fi."));
                } else {
                    scan();
                }
            });
        }
    }
    if (!m_local->isValid()) {
        unusable(QStringLiteral("No Bluetooth adapter was found."));
        return false;
    }
    if (m_local->hostMode() == QBluetoothLocalDevice::HostPoweredOff) {
        unusable(QStringLiteral("Bluetooth is turned off. Turn it on to text nearby phones without Wi-Fi."));
        return false;
    }
    setError(QString());
    return true;
}

void Ble::hiddenChanged()
{
    if (!m_running)
        return;
    if (silent()) {
        if (m_agent && m_agent->isActive())
            m_agent->stop();
        dropAll();
        m_seen.clear();
        Q_EMIT changed();
        return;
    }
    scan();
}

void Ble::scan()
{
    if (!m_running || silent())
        return;
#ifdef Q_OS_LINUX
    // Is BlueZ there? Asked without waiting: a busy bluetoothd never holds up the window.
    if (m_askingBluez)
        return;
    m_askingBluez = true;
    QDBusMessage m = QDBusMessage::createMethodCall(QStringLiteral("org.freedesktop.DBus"), QStringLiteral("/org/freedesktop/DBus"),
                                                    QStringLiteral("org.freedesktop.DBus"), QStringLiteral("NameHasOwner"));
    m.setArguments({kBluez});
    auto *w = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(m, 3000), this);
    connect(w, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *done) {
        done->deleteLater();
        m_askingBluez = false;
        const QDBusMessage r = done->reply();
        m_bluezUp = r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty() && r.arguments().constFirst().toBool();
        scanNow();
    });
#else
    m_bluezUp = true;
    scanNow();
#endif
}

void Ble::scanNow()
{
    Busy::Scope busy("Bluetooth");
    // Hidden from people nearby: nothing at all goes out over Bluetooth.
    if (!m_running || silent() || !checkAdapter())
        return;
    if (!m_agent) {
        m_agent = new QBluetoothDeviceDiscoveryAgent(this);
        m_agent->setLowEnergyDiscoveryTimeout(kScanForMs);
        auto found = [this](const QBluetoothDeviceInfo &info) {
            Busy::Scope seen("Bluetooth");
            if (!(info.coreConfigurations() & QBluetoothDeviceInfo::LowEnergyCoreConfiguration) || !info.serviceUuids().contains(kService))
                return;
            const QString address = info.address().toString();
            const bool known = m_seen.contains(address);
            Seen &s = m_seen[address];
            s.info = info;
            s.seenMs = nowMs();
            // Updates come with every advertisement: only a phone not seen before is worth a connect attempt now
            // (the others are tried by the tick when their wait is over).
            if (!known)
                tick();
        };
        connect(m_agent, &QBluetoothDeviceDiscoveryAgent::deviceDiscovered, this, found);
        connect(m_agent, &QBluetoothDeviceDiscoveryAgent::deviceUpdated, this,
                [found](const QBluetoothDeviceInfo &info, QBluetoothDeviceInfo::Fields) { found(info); });
        connect(m_agent, &QBluetoothDeviceDiscoveryAgent::finished, this, [this] {
            if (m_running)
                setError(QString());
        });
        connect(m_agent, &QBluetoothDeviceDiscoveryAgent::errorOccurred, this, [this](QBluetoothDeviceDiscoveryAgent::Error e) {
            if (!m_running)
                return;
            switch (e) {
            case QBluetoothDeviceDiscoveryAgent::PoweredOffError:
                unusable(QStringLiteral("Bluetooth is turned off. Turn it on to text nearby phones without Wi-Fi."));
                break;
            case QBluetoothDeviceDiscoveryAgent::InvalidBluetoothAdapterError:
                unusable(QStringLiteral("No Bluetooth adapter was found."));
                break;
            case QBluetoothDeviceDiscoveryAgent::MissingPermissionsError:
                setError(QStringLiteral("Huginn doesn't have permission to use Bluetooth."));
                break;
            case QBluetoothDeviceDiscoveryAgent::UnsupportedPlatformError:
            case QBluetoothDeviceDiscoveryAgent::UnsupportedDiscoveryMethod:
                setError(QStringLiteral("This copy of Huginn was built without Bluetooth support."));
                break;
            default:
                setError(QStringLiteral("Bluetooth stopped responding while looking for phones. Huginn will try again shortly."));
                break;
            }
        });
    }
    if (!m_agent->isActive())
        m_agent->start(QBluetoothDeviceDiscoveryAgent::LowEnergyMethod);
}

// Every few seconds: forget phones that went quiet, and connect to ones we're not linked to yet.
void Ble::tick()
{
    if (!m_running)
        return;
    Busy::Scope busy("Bluetooth");
    const qint64 now = nowMs();
    for (auto it = m_seen.begin(); it != m_seen.end();) {
        // A linked phone may stop showing up in scans while connected, so the link itself counts as seen.
        if (m_links.contains(it.key()))
            it->seenMs = now;
        if (now - it->seenMs > kForgetAfterMs)
            it = m_seen.erase(it);
        else
            ++it;
    }
    if (!m_error.isEmpty())
        return;   // Bluetooth isn't usable; the next scan checks again
    if (silent()) {
        if (!m_links.isEmpty())
            dropAll();   // hidden from people nearby: no links at all
        return;
    }
    for (auto it = m_seen.cbegin(); it != m_seen.cend() && m_links.size() < kMaxLinks; ++it) {
        if (!m_links.contains(it.key()) && it->retryAtMs <= now)
            connectTo(it.key());
    }
}

void Ble::connectTo(const QString &address)
{
    Link *l = new Link;
    l->id = ++m_nextId;
    l->address = address;
    l->ctx = new QObject(this);
    l->ctl = QLowEnergyController::createCentral(m_seen.value(address).info, this);
    m_links.insert(address, l);

    QLowEnergyController *ctl = l->ctl;
    connect(ctl, &QLowEnergyController::connected, l->ctx, [ctl] {
        Busy::Scope busy("Bluetooth");
        ctl->discoverServices();
    });
    connect(ctl, &QLowEnergyController::discoveryFinished, l->ctx, [this, l] {
        Busy::Scope busy("Bluetooth");
        if (!l->ctl->services().contains(kService)) {
            drop(l, true);
            return;
        }
        l->svc = l->ctl->createServiceObject(kService, this);
        if (!l->svc) {
            drop(l, true);
            return;
        }
        connect(l->svc, &QLowEnergyService::stateChanged, l->ctx, [this, l] { onServiceState(l); });
        connect(l->svc, &QLowEnergyService::characteristicRead, l->ctx, [this, l](const QLowEnergyCharacteristic &c, const QByteArray &value) {
            if (c.uuid() == kHello)
                onHello(l, value);
        });
        connect(l->svc, &QLowEnergyService::descriptorWritten, l->ctx, [this, l](const QLowEnergyDescriptor &d, const QByteArray &) {
            if (d.uuid() == QBluetoothUuid(QBluetoothUuid::DescriptorType::ClientCharacteristicConfiguration) && !l->ready)
                onReady(l);
        });
        connect(l->svc, &QLowEnergyService::characteristicWritten, l->ctx, [this, l](const QLowEnergyCharacteristic &c, const QByteArray &) {
            if (c.uuid() != kInbox || !l->writing)
                return;
            l->writing = false;
            if (!l->frames.isEmpty())
                l->frames.removeFirst();
            pump(l);
        });
        connect(l->svc, &QLowEnergyService::characteristicChanged, l->ctx, [this, l](const QLowEnergyCharacteristic &c, const QByteArray &value) {
            Busy::Scope busy("Bluetooth");
            // Taken as soon as we know who it is: the phone may send before our notification setup reports back.
            if (c.uuid() != kOutbox || l->chatKey.isEmpty())
                return;
            std::string msg;
            if (l->in.add(value.toStdString(), &msg) != BleFrames::Reassembler::Complete)
                return;
            const QByteArray json = QByteArray::fromStdString(msg);
            // Cap s1: the phone signs the number in our hello. A wrong signature: not who it says, so no chat key at all.
            if (json.size() < 1024 && json.contains("\"proof\"")) {
                const QJsonObject o = QJsonDocument::fromJson(json).object();
                if (o.value(QStringLiteral("t")).toString() == QLatin1String("proof")) {
                    l->proven = Proof::closeOk(l->chatKey, l->myNonce, o.value(QStringLiteral("sig")).toString());
                    if (!l->proven)
                        l->chatKey.clear();
                    Q_EMIT changed();
                    return;
                }
            }
            Q_EMIT message(l->chatKey, json);
        });
        connect(l->svc, &QLowEnergyService::errorOccurred, l->ctx, [this, l](QLowEnergyService::ServiceError) { drop(l, true); });
        // Values are skipped here because only HELLO is needed, and it's read on its own next.
        l->svc->discoverDetails(QLowEnergyService::SkipValueDiscovery);
    });
    connect(ctl, &QLowEnergyController::disconnected, l->ctx, [this, l] { drop(l, !l->ready); });
    connect(ctl, &QLowEnergyController::errorOccurred, l->ctx, [this, l](QLowEnergyController::Error) { drop(l, true); });
    connect(ctl, &QLowEnergyController::mtuChanged, l->ctx, [this, l](int) { l->mtu = linkMtu(l); });

    // BlueZ can sit on a connect attempt for a long time; give up and back off instead.
    // Captures the id, not the pointer, because this can fire after the link is gone.
    QTimer::singleShot(kSetupTimeoutMs, this, [this, address, id = l->id] {
        Link *now = m_links.value(address);
        if (now && now->id == id && !now->ready)
            drop(now, true);
    });
    ctl->connectToDevice();
}

void Ble::onServiceState(Link *l)
{
    Busy::Scope busy("Bluetooth");
    if (l->svc->state() != QLowEnergyService::RemoteServiceDiscovered)
        return;
    const QLowEnergyCharacteristic hello = l->svc->characteristic(kHello);
    l->inbox = l->svc->characteristic(kInbox);
    if (!hello.isValid() || !l->inbox.isValid() || !l->svc->characteristic(kOutbox).isValid()) {
        drop(l, true);
        return;
    }
    l->svc->readCharacteristic(hello);
}

void Ble::onHello(Link *l, const QByteArray &value)
{
    Busy::Scope busy("Bluetooth");
    const QJsonObject o = QJsonDocument::fromJson(value).object();
    // Hidden from people nearby, a phone sends a daily hint instead of its key: only a contact's can be matched.
    QString key = o.value(QStringLiteral("chatKey")).toString();
    if (key.isEmpty() && hintKey)
        key = hintKey(o.value(QStringLiteral("hint")).toString());
    if (o.value(QStringLiteral("app")).toString() != QLatin1String("muninn") || key.isEmpty()) {
        // Something else using our UUID, a broken Muninn, or a hidden phone that isn't a contact: leave it alone for a while.
        drop(l, true, 10 * 60 * 1000);
        return;
    }
    // Android changes its Bluetooth address now and then, so the same phone can show up twice. Only a link that
    // proved it's that key (cap s1) keeps a new one out: anyone can claim a key.
    for (const Link *other : std::as_const(m_links)) {
        if (other != l && other->ready && other->proven && other->chatKey == key) {
            drop(l, false, kForgetAfterMs);
            return;
        }
    }
    l->chatKey = key;
    const QString n = o.value(QStringLiteral("n")).toString();
    l->theirNonce = Proof::isNonce(n) ? n : QString();
    l->myNonce = Proof::newNonce();
    l->name = o.value(QStringLiteral("name")).toString();
    l->mailbox = o.value(QStringLiteral("mailbox")).toString();

    const QLowEnergyCharacteristic outbox = l->svc->characteristic(kOutbox);
    const QLowEnergyDescriptor cccd = outbox.clientCharacteristicConfiguration();
    if (!cccd.isValid()) {
        drop(l, true);
        return;
    }
    l->svc->writeDescriptor(cccd, QLowEnergyCharacteristic::CCCDEnableNotification);
}

void Ble::onReady(Link *l)
{
    Busy::Scope busy("Bluetooth");
    l->ready = true;
    l->readyMs = nowMs();
    // Looked up again for each new link (it can differ); the last one is used meanwhile.
    lookUpMtu(l->address);
    l->mtu = linkMtu(l);
    Seen &s = m_seen[l->address];
    s.failures = 0;
    s.retryAtMs = 0;
    // Our hello always goes first on a new link. While Chat is locked there's none yet: then the
    // link waits (unused) until sayHello() runs at unlock.
    const QByteArray hello = helloFor(l);
    if (!hello.isEmpty()) {
        queue(l, hello);
        l->said = true;
    }
    Q_EMIT changed();
}

void Ble::sayHello()
{
    for (Link *l : std::as_const(m_links))
        if (l->ready && !l->said) {
            const QByteArray hello = helloFor(l);
            if (hello.isEmpty())
                return;
            queue(l, hello);
            l->said = true;
        }
    Q_EMIT changed();
}

// Our hello for this link (empty while Chat is locked). Cap s1: our number for the phone to sign, and our signature
// over the number in its HELLO.
QByteArray Ble::helloFor(Link *l) const
{
    if (silent())
        return QByteArray();   // hidden from people nearby: nothing that says who this is
    const QByteArray hello = m_hello ? m_hello() : QByteArray();
    if (hello.isEmpty())
        return hello;
    QJsonObject o = QJsonDocument::fromJson(hello).object();
    o.insert(QStringLiteral("n"), l->myNonce);
    const QString sig = prove && !l->theirNonce.isEmpty() ? prove(l->theirNonce) : QString();
    if (!sig.isEmpty())
        o.insert(QStringLiteral("proof"), sig);
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

Ble::Link *Ble::linkFor(const QString &chatKey, bool strict) const
{
    Link *best = nullptr;
    for (Link *l : std::as_const(m_links)) {
        if (!l->ready || !l->said || l->chatKey != chatKey || (strict && !l->proven))
            continue;
        if (!best || (l->proven && !best->proven))
            best = l;
    }
    return best;
}

bool Ble::has(const QString &chatKey, bool strict) const { return linkFor(chatKey, strict) != nullptr; }

bool Ble::proven(const QString &chatKey) const
{
    const Link *l = linkFor(chatKey, true);
    return l != nullptr;
}

int Ble::linkMtu(Link *l)
{
    // On BlueZ the MTU is negotiated by itself (BlueZ asks for 517); Qt can't request one as a central.
    int mtu = l->ctl ? l->ctl->mtu() : 0;
    if (mtu <= BleFrames::kMinMtu) {
        mtu = m_mtus.value(l->address, 0);
        if (mtu <= 0)
            lookUpMtu(l->address);
    }
    return qBound(BleFrames::kMinMtu, mtu, BleFrames::kMaxMtu);
}

void Ble::lookUpMtu(const QString &address)
{
#ifdef Q_OS_LINUX
    if (m_mtuLooking.value(address))
        return;
    m_mtuLooking.insert(address, true);
    auto result = std::make_shared<int>(0);
    QThread *t = QThread::create([address, result] { *result = bluezMtu(address); });
    t->setObjectName(QStringLiteral("huginn-ble-mtu"));
    QPointer<Ble> self(this);
    connect(t, &QThread::finished, this, [self, t, address, result] {
        t->deleteLater();
        if (!self)
            return;
        self->m_mtuLooking.remove(address);
        if (*result <= 0)
            return;
        self->m_mtus.insert(address, *result);
        if (Link *l = self->m_links.value(address))
            l->mtu = qBound(BleFrames::kMinMtu, *result, BleFrames::kMaxMtu);
    });
    t->start();
#else
    Q_UNUSED(address);
#endif
}

bool Ble::send(const QString &chatKey, const QByteArray &json, bool strict)
{
    if (json.size() > int(BleFrames::kMaxMessage))
        return false;
    Link *l = linkFor(chatKey, strict);
    // Bluetooth is slow: past about 2 MB waiting, say no so other routes carry it.
    if (!l || l->frames.size() > 4096)
        return false;
    queue(l, json);
    return true;
}

void Ble::queue(Link *l, const QByteArray &json)
{
    for (const std::string &f : BleFrames::split(json.toStdString(), l->nextNumber++, l->mtu))
        l->frames << QByteArray::fromStdString(f);
    pump(l);
}

// One write at a time: the next frame goes only after the last one was acknowledged.
void Ble::pump(Link *l)
{
    if (!alive(l) || l->writing || l->frames.isEmpty() || !l->svc)
        return;
    l->writing = true;
    l->svc->writeCharacteristic(l->inbox, l->frames.constFirst(), QLowEnergyService::WriteWithResponse);
}

// Ends a link. failed links wait longer each time before the next try; retryInMs overrides that.
void Ble::drop(Link *l, bool failed, qint64 retryInMs)
{
    if (!alive(l))
        return;
    Busy::Scope busy("Bluetooth");
    // A link that ends soon after it was made (the phone keeps closing it) backs off like a failed one, so it isn't
    // made again every 2 seconds.
    if (!failed && l->ready && nowMs() - l->readyMs < kShortLinkMs)
        failed = true;
    m_links.remove(l->address);
    const bool wasReady = l->ready;

    // Cut our signal hookups first so nothing fires into a half-gone link.
    QObject::disconnect(l->ctl, nullptr, l->ctx, nullptr);
    if (l->svc) {
        QObject::disconnect(l->svc, nullptr, l->ctx, nullptr);
        l->svc->deleteLater();
    }
    l->ctl->disconnectFromDevice();
    l->ctl->deleteLater();
    l->ctx->deleteLater();
    const QString address = l->address;
    delete l;

    auto it = m_seen.find(address);
    if (it != m_seen.end()) {
        if (retryInMs < 0) {
            if (failed) {
                // 4 s, 8 s, 16 s ... up to 2 minutes, so a phone that's out of range isn't hammered.
                it->failures = qMin(it->failures + 1, 10);
                retryInMs = qMin<qint64>(qint64(2000) << it->failures, kMaxBackoffMs);
            } else {
                retryInMs = 2000;   // a clean drop of a working link: try again soon
            }
        }
        it->retryAtMs = nowMs() + retryInMs;
    }
    if (wasReady)
        Q_EMIT changed();
}

void Ble::dropAll()
{
    const QList<Link *> all = m_links.values();
    for (Link *l : all)
        drop(l, false, 0);
}
