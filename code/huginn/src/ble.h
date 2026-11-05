#pragma once

#include <algorithm>
#include <functional>

#include <QBluetoothDeviceInfo>
#include <QByteArray>
#include <QHash>
#include <QList>
#include <QLowEnergyCharacteristic>
#include <QObject>
#include <QTimer>
#include <QVariantList>

#include "bleframes.h"

class QBluetoothDeviceDiscoveryAgent;
class QBluetoothLocalDevice;
class QLowEnergyController;
class QLowEnergyService;

// Texting Muninn over Bluetooth LE when there is no Wi-Fi or internet.
// Muninn is the peripheral (GATT server); Huginn is only ever the central, because in a Flatpak
// Qt can reach BlueZ over D-Bus for central mode but peripheral mode needs raw HCI access.
//  - Scans for Muninn's service UUID for 10 seconds now and every 2 minutes while Huginn is in front (every 10
//    minutes behind other windows or with Battery saver). Links already made stay either way.
//  - Connects to a few at once, reads HELLO (who they are), turns on OUTBOX notifications,
//    then sends our own hello as the first message.
//  - Messages are split into frames (bleframes.h) and written one at a time with response.
class Ble : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList peers READ peers NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)
    Q_PROPERTY(bool running READ running NOTIFY changed)

public:
    // hello gives the JSON for our first message on every link:
    // {"t":"hello","name","chatKey","mailbox","app":"huginn"} (an "onion" field is ignored); empty sends none.
    explicit Ble(std::function<QByteArray()> hello, QObject *parent = nullptr);
    ~Ble() override;

    QVariantList peers() const;   // each {address, name, chatKey, mailbox}
    QString error() const { return m_error; }
    bool running() const { return m_running; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    // Chat just opened (unlocked): introduce ourselves on links made while it was locked.
    void sayHello();

    // Queues a message for the peer with this chatKey. False when it isn't connected or is over 64 KB.
    // True means queued, not delivered. A link that proved it's that key (cap s1) first; one that doesn't (a
    // Muninn without s1) only when not strict.
    bool send(const QString &chatKey, const QByteArray &json, bool strict = false);
    bool has(const QString &chatKey, bool strict = false) const;
    bool proven(const QString &chatKey) const;
    // Cap s1: signs the phone's number with my chat key (Proof::close); the contact a hidden phone's hint stands for.
    std::function<QString(const QString &nonce)> prove;
    std::function<QString(const QString &hint)> hintKey;
    // Hidden from people nearby (21.3): while it says true, Bluetooth is silent. No scanning (an active scan names this
    // computer's adapter), no connecting, no hello, and links already open end.
    std::function<bool()> hidden;
    // Hidden from people nearby was turned on or off: goes silent at once, or starts looking again.
    void hiddenChanged();
    // Battery saver (main.cpp follows System::batterySaving): scans further apart.
    void setRelaxed(bool relaxed);

Q_SIGNALS:
    void changed();
    void message(const QString &chatKey, const QByteArray &json);

private:
    struct Seen {
        QBluetoothDeviceInfo info;
        qint64 seenMs = 0;
        qint64 retryAtMs = 0;
        int failures = 0;
    };
    struct Link {
        quint64 id = 0;
        QString address;
        QObject *ctx = nullptr;   // every signal hookup for this link hangs off this, so one disconnect cuts them all
        QLowEnergyController *ctl = nullptr;
        QLowEnergyService *svc = nullptr;
        QLowEnergyCharacteristic inbox;
        QString name, chatKey, mailbox;
        QString theirNonce;         // cap s1: the number in the phone's HELLO, which our hello signs
        QString myNonce;            // and ours, which the phone's "proof" signs
        bool proven = false;
        bool ready = false;
        bool said = false;          // our hello went out: only then can messages use this link
        int mtu = BleFrames::kMinMtu;
        quint32 nextNumber = 0;
        QList<QByteArray> frames;   // waiting to be written, in order
        bool writing = false;
        qint64 readyMs = 0;         // when it became ready: one that ends soon after counts as failed
        BleFrames::Reassembler in;
    };

    // Asks D-Bus whether BlueZ is there (without waiting on it), then scanNow().
    void scan();
    void scanNow();
    bool checkAdapter();
    void retime();
    // The MTU BlueZ negotiated, looked up on another thread (BlueZ can take a while to answer) and kept by address.
    void lookUpMtu(const QString &address);
    void tick();
    void connectTo(const QString &address);
    void onServiceState(Link *l);
    void onHello(Link *l, const QByteArray &value);
    void onReady(Link *l);
    void queue(Link *l, const QByteArray &json);
    void pump(Link *l);
    void drop(Link *l, bool failed, qint64 retryInMs = -1);
    void dropAll();
    void setError(const QString &e);
    void unusable(const QString &why);
    int linkMtu(Link *l);
    QByteArray helloFor(Link *l) const;
    Link *linkFor(const QString &chatKey, bool strict) const;
    // Compares pointers only, so it's safe to ask about a link that was already deleted.
    bool alive(const Link *l) const { return l && std::find(m_links.cbegin(), m_links.cend(), l) != m_links.cend(); }
    bool silent() const { return hidden && hidden(); }

    std::function<QByteArray()> m_hello;
    QBluetoothDeviceDiscoveryAgent *m_agent = nullptr;
    QBluetoothLocalDevice *m_local = nullptr;
    QTimer m_scanTimer, m_tickTimer;
    QHash<QString, Seen> m_seen;     // Muninn devices heard advertising, by address
    QHash<QString, Link *> m_links;  // connecting or connected, by address
    QHash<QString, int> m_mtus;      // BlueZ's MTU by address, once looked up
    QHash<QString, bool> m_mtuLooking;
    QString m_error;
    bool m_bluezUp = false;          // BlueZ answered on the system bus the last time we asked
    bool m_askingBluez = false;
    bool m_relaxed = false;
    quint64 m_nextId = 0;
    bool m_running = false;
};
