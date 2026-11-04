#pragma once

#include <QByteArray>
#include <QElapsedTimer>
#include <QNetworkProxy>
#include <QObject>
#include <QString>
#include <QStringList>

class QNetworkAccessManager;
class QProcess;
class QTcpSocket;
class QTimer;
class QWebSocket;

// Ultra privacy (Settings › Privacy and lock, off by default): the internet address stays hidden, as in Muninn
// (muninn/.../net/UltraPrivacy.kt).
//
// While it's on, the relays (every QWebSocket in nostr.cpp), update checks and downloads (updater.cpp), the call relay's
// Worker (call/turnclient.cpp) and the voice downloads go through Tor as SOCKS5, with the names looked up by Tor, never
// here (QNetworkProxy's host name lookup). Tor is bundled (lib/tor/tor.exe in the Windows zip, /app/bin/tor in the
// Flatpak) and started with QProcess (it lives in Qt6Core.dll, so Huginn.exe imports nothing to start programs) on a
// free 127.0.0.1 SOCKS5 port, with a control port that only takes a random password made at each start (Tor gets its
// hash). Tor ends with Huginn (TAKEOWNERSHIP, __OwningControllerProcess).
//
// Anything that would still show the address is off, each with offNote(): the direct call path (calls go through the
// call relay only), direct files and photos, sending videos (receiving still works), link previews and GIF search.
// Nearby and the same Wi-Fi stay available. Fails closed: on, with Tor not ready, the proxy points at a port where
// nothing listens, so nothing leaves directly meanwhile.
class UltraPrivacy : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool active READ active NOTIFY changed)
    // "off", "starting", "ready", "slow", "blocked" or "missing"
    Q_PROPERTY(QString torState READ torState NOTIFY changed)
    Q_PROPERTY(int progress READ progress NOTIFY changed)
    Q_PROPERTY(QString statusLine READ statusLine NOTIFY changed)
    Q_PROPERTY(bool bannerShows READ bannerShows NOTIFY changed)
    Q_PROPERTY(QString bannerText READ bannerText NOTIFY changed)
    Q_PROPERTY(QString offNote READ offNote CONSTANT)
    Q_PROPERTY(QString nearbyNote READ nearbyNote CONSTANT)
    Q_PROPERTY(QString whatItDoes READ whatItDoes CONSTANT)
    Q_PROPERTY(QString downsides READ downsides CONSTANT)
    Q_PROPERTY(QString setupTitle READ setupTitle CONSTANT)
    Q_PROPERTY(QString setupText READ setupText CONSTANT)
    Q_PROPERTY(QString setupUltra READ setupUltra CONSTANT)

public:
    enum class Tor { Off, Starting, Ready, Slow, Blocked, Missing };

    explicit UltraPrivacy(QObject *parent = nullptr);
    ~UltraPrivacy() override;
    // The one made in main(), or null before it is.
    static UltraPrivacy *instance();

    // ---- for everything that goes on the internet (any thread)
    static bool on();
    // QNetworkProxy::DefaultProxy while it's off (as before); Tor's SOCKS5 port on 127.0.0.1 while it's on, or a port
    // where nothing listens until Tor is ready.
    static QNetworkProxy proxy();
    // Sets proxy() on a socket or a network manager before it connects.
    static void prepare(QWebSocket *ws);
    static void prepare(QNetworkAccessManager *nam);

    // ---- what's off while it's on
    static bool directAllowed() { return !on(); }
    static bool videoSendAllowed() { return !on(); }
    static bool previewsAllowed() { return !on(); }
    static bool gifSearchAllowed() { return !on(); }
    static bool isVideoName(const QString &name);
    // A file that may not go now: a video while it's on.
    static bool blocksFile(const QString &name) { return on() && isVideoName(name); }
    static QString offNoteText() { return QStringLiteral("Off while Ultra privacy is on"); }
    static QString videoOffText() { return QStringLiteral("Sending videos: Off while Ultra privacy is on. You can still receive them."); }

    bool active() const { return on(); }
    QString torState() const;
    int progress() const { return m_progress; }
    QString statusLine() const;
    bool bannerShows() const { return m_tor == Tor::Slow || m_tor == Tor::Blocked || m_tor == Tor::Missing; }
    QString bannerText() const;
    QString offNote() const { return offNoteText(); }
    QString nearbyNote() const;
    QString whatItDoes() const;
    QString downsides() const;
    QString setupTitle() const { return QStringLiteral("Your privacy, built in"); }
    QString setupText() const;
    QString setupUltra() const;

    // Settings: on (after the two screens in SettingsPage.qml) or off. Saved at once.
    Q_INVOKABLE void turnOn();
    Q_INVOKABLE void turnOff();
    // The banner's Wait: the slow or blocked clock starts over.
    Q_INVOKABLE void waitLonger();
    // At start: the saved switch, and Tor if it's on (before the relays connect).
    void start();

    // ---- pure pieces, also for the self-test
    static Tor startState(qint64 ms, bool warned);
    static int percentOf(const QString &bootstrapPhase);
    // Tor's HashedControlPassword for secret with an 8-byte salt: "16:" and the hex of salt, 0x60 and the iterated SHA-1.
    static QByteArray hashPassword(const QByteArray &secret, const QByteArray &salt);
    static QString torBinary();
    static constexpr int kClosedPort = 1;
    static constexpr qint64 kSlowMs = 45000;
    static constexpr qint64 kBlockedMs = 120000;
    // Self-test (in --privacy-selftest): the switch gates each feature, the proxy goes on sockets, and a relay's
    // WebSocket reaches a pretend Tor with the relay's name, not an address.
    static bool selfTest();

    // Without Tor (the self-test): on or off, and Tor ready on a port.
    void use(bool want);
    void ready(int socksPort);

Q_SIGNALS:
    void changed();
    // On, off, Tor ready or lost: sockets made the old way must go (main.cpp: the relays connect again).
    void redial();

private:
    void setTor(Tor t);
    void startTor();
    void stopTor();
    void connectControl();
    void poll();
    void controlRead();

    Tor m_tor = Tor::Off;
    int m_progress = 0;
    QProcess *m_proc = nullptr;
    QTcpSocket *m_ctl = nullptr;
    QTimer *m_timer = nullptr;
    QElapsedTimer m_since;
    QByteArray m_password;
    QByteArray m_ctlBuffer;
    int m_socksPort = 0;
    int m_ctlPort = 0;
    bool m_authed = false;
    bool m_circuit = false;
    bool m_warned = false;
};
