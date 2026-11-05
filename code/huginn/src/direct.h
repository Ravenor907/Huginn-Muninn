#pragma once

#include <QObject>

#include <functional>
#include <QString>

// Direct connect: the handheld makes its own private Wi-Fi (NetworkManager hotspot, no internet
// shared) and shows a QR code. A phone's camera joins it, then LocalSend, Muninn and chat work
// as on any Wi-Fi. While it's on, the handheld is off its usual Wi-Fi (one radio); stopping it
// lets NetworkManager reconnect as normal.
class Direct : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool on READ on NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString ssid READ ssid NOTIFY changed)
    Q_PROPERTY(QString password READ password NOTIFY changed)
    Q_PROPERTY(QString qr READ qr NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)

public:
    explicit Direct(QObject *parent = nullptr);
    ~Direct() override;

    bool on() const { return !m_active.isEmpty(); }
    bool busy() const { return m_busy; }
    QString ssid() const { return m_ssid; }
    QString password() const { return m_password; }
    QString qr() const;
    QString error() const { return m_error; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    // At start: a Direct connect hotspot left behind by a crash is removed (NetworkManager keeps it otherwise).
    void clearLeftovers();

Q_SIGNALS:
    void changed();

private:
    // Finds the Wi-Fi adapter without waiting on the window's thread, then calls done with its path (empty: none, error said).
    void findWifiDevice(std::function<void(const QString &path, const QString &error)> done);
    void activate(const QString &dev);
    // Takes the hotspot down; wait: only as Huginn closes (the messages must go before it ends).
    void takeDown(bool wait);

    bool m_busy = false;
    QString m_ssid, m_password, m_error, m_connection, m_active;
};
