#pragma once

#include <functional>

#include <QCryptographicHash>
#include <QDateTime>
#include <QHash>
#include <QHostAddress>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QObject>
#include <QPointer>
#include <QSslConfiguration>
#include <QSslServer>
#include <QTcpServer>
#include <QTimer>
#include <QUdpSocket>
#include <QVariantList>

#include <utility>
#include <vector>

#include "tls.h"

class QFile;
class QNetworkReply;
class QTcpSocket;
class Settings;

// Nearby sharing that speaks the LocalSend protocol (v2), so the free LocalSend app on a phone,
// tablet or PC finds the handheld by itself and sends or receives with no codes to type.
// Discovery: UDP multicast 224.0.0.167:53317 (shared with the LocalSend app on the same device) plus a scan
// of the home network.
// Receiving: a small HTTPS server on the first free port from 53318 (announced as "port", so the LocalSend
// app keeps 53317) with our own self-signed certificate, whose SHA-256 is the fingerprint we announce;
// every incoming transfer is accepted on screen.
// Sending: HTTPS to the other device (HTTP only for devices that have encryption off), checking
// that its certificate matches the fingerprint it announced.
class LocalSend : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    // Settings › Privacy › This computer's name.
    Q_PROPERTY(QString alias READ alias NOTIFY aliasChanged)
    Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
    Q_PROPERTY(bool scanning READ scanning NOTIFY devicesChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY progressChanged)
    Q_PROPERTY(QString status READ status NOTIFY progressChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(bool sending READ sending NOTIFY progressChanged)
    Q_PROPERTY(QVariantMap request READ request NOTIFY requestChanged)
    Q_PROPERTY(QVariantList items READ items NOTIFY progressChanged)
    // While files go: "2.1 MB/s · 12 s left" (empty until there's enough to tell), and each file going out.
    Q_PROPERTY(QString pace READ pace NOTIFY progressChanged)
    Q_PROPERTY(QVariantList outgoing READ outgoing NOTIFY progressChanged)
    // How the last transfer ended, until the next one starts or it's put away.
    Q_PROPERTY(QString lastResult READ lastResult NOTIFY progressChanged)
    Q_PROPERTY(bool lastOk READ lastOk NOTIFY progressChanged)

public:
    explicit LocalSend(Settings *settings, QObject *parent = nullptr);

    // On (asked to share). It listens only on the home network's own addresses (see lan.h), and
    // follows them as Wi-Fi comes and goes; listening() says whether any is open right now.
    bool running() const { return m_on; }
    bool listening() const { return !m_servers.empty(); }
    QString error() const { return m_error; }
    QString alias() const { return m_alias; }
    QVariantList devices() const;
    bool scanning() const { return m_scanLeft > 0; }
    bool busy() const { return sending() || m_session.active; }
    bool sending() const { return m_send.active; }
    QString status() const { return m_status; }
    double progress() const;
    QVariantMap request() const;
    QVariantList items() const;
    QString pace() const;
    QVariantList outgoing() const;
    QString lastResult() const { return m_last; }
    bool lastOk() const { return m_lastOk; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    // Battery saver: the home network's addresses are looked at every 20 seconds instead of every 5.
    void setRelaxed(bool relaxed) { m_lanTimer.setInterval(relaxed ? 20000 : 5000); }
    Q_INVOKABLE void refresh();
    // Round 19 (interop 2): a contact met on this Wi-Fi before 1.42 whose certificate was never saved and who hasn't
    // named one (an older app): with Nearby off they aren't asked here until they update or Nearby is on once. For a
    // note in their panel.
    Q_INVOKABLE bool waitsForPin(const QString &key) const;
    Q_INVOKABLE void accept();
    Q_INVOKABLE void decline();
    Q_INVOKABLE void send(const QString &deviceId, const QStringList &paths, const QString &pin = QString());
    Q_INVOKABLE void cancelSend();
    // Cancel on the receiving side: what came so far is thrown away.
    Q_INVOKABLE void cancelReceive();
    Q_INVOKABLE void clearResult();

Q_SIGNALS:
    void stateChanged();
    void aliasChanged();
    void devicesChanged();
    void progressChanged();
    void requestChanged();
    void pinNeeded(const QString &deviceId, bool wrong);
    void finished(bool ok, const QString &message);
    // Cap h1 (1.42): the hidden certificate changed (its next one is new), so Chat tells contacts ("hf" in receipts).
    void hiddenRotated();

private:
    struct Device {
        QString id, alias, model, type, ip, protocol;
        QString chatKey, chatName, app;   // set by Huginn and Muninn; plain LocalSend apps don't chat
        int port = 53318;
        QDateTime seen;
        bool proven = false;              // cap s1: its chat key signed this certificate (false: no s1 proof, only extra)
    };
public:
    // Chat (see chat.h) rides on the same port: what to add to our info, and who can be messaged.
    std::function<QJsonObject()> chatInfo;
    // Nearby off (Chat's setting, Settings › Privacy › Nearby): no multicast announcement at all, no scan of the home
    // network, nobody but contacts listed or answered. A contact's own announcement, and each contact where it was last
    // seen (askContacts), get this computer's info straight: the daily hint, never a name (protocol.md 21.3).
    std::function<bool()> silent;
    // Whether a chat key is one of my contacts (with Nearby off only they are listed and answered).
    std::function<bool(const QString &key)> isContact;
    // Nearby off: where contacts were last reached on the home Wi-Fi, kept sealed in the vault by Chat. Never shown.
    std::function<void(const QByteArray &json)> keepPeers;
    void loadPeers(const QByteArray &json);
    // Nearby off: each contact last seen on the home Wi-Fi (and not heard from lately) is asked straight there.
    void askContacts();
    // Nearby changed (Chat): from now on the server shows a throwaway certificate while off (never the lasting one,
    // which anyone close by could follow), and the lasting one again once on. Off: nobody but contacts stays listed.
    // 1.42 (cap h1): the hidden certificate is kept ("now" and "next", in the data folder) and named to contacts before
    // it's shown; turning Nearby off, and every day while it's off, "next" takes over and a new "next" is made.
    void hiddenChanged();
    // Cap h1: every certificate this computer's server may show (lasting, hidden now and next), for "hf" to contacts.
    QStringList myFingerprints();
    // Cap h1: the fingerprints a contact named ("hf"), to pin its saved address with (Chat sets it).
    std::function<QStringList(const QString &key)> pinsFor;
    // Cap h1: the fingerprints a saved address is asked with, comma-separated: the one it showed there and every one the
    // contact named. Empty: never asked (an address kept before 1.41 is asked once the contact names one).
    static QString pins(const QString &saved, const QStringList &named);
    // Cap h1: whether the hidden certificate that took over at since changes now (daily while off; at once when turned
    // off unless it changed in the last hour; when the clock is a day behind it).
    static bool rotateDue(qint64 since, qint64 nowMs, bool turningOff);
    // --stability-selftest: pins and rotateDue, the same as Muninn's Fix142DTest.
    static bool hiddenSelfTest();
    // Our own server's port: the first free one from 53318.
    int serverPort() const { return m_port; }
    std::function<int(const QByteArray &body, const QString &ip)> chatReceive;
    // Moving to another device (cap x1): its own path on the same port, with an answer.
    std::function<QPair<int, QByteArray>(const QByteArray &body)> moveReceive;
    // Cap s1: my chat key's signature over my certificate's fingerprint, and the contact a hidden device's hint stands for.
    std::function<QString(const QString &fingerprint)> chatProve;
    std::function<QString(const QString &hint)> hintKey;
    struct Peer { QString base, chatKey, chatName, alias, type; bool proven = false; };
    QList<Peer> chatPeers() const;
    // Where to reach chatKey: a device whose chat key proved its certificate, newest first; one that didn't (an
    // app without s1) only when not strict. Never by name order, which anyone can game.
    QString baseFor(const QString &chatKey, bool strict = false) const;
    bool provenFor(const QString &chatKey) const;
    // A sent file name as folders under the save folder: never "..", a drive or an absolute path.
    static QStringList safeParts(const QString &name);
    static bool selfTest();
    void announce();
    // For chat's own requests to a peer's base URL: peerRequest() adds our certificate, and
    // pinReply() aborts the reply if the peer's certificate isn't the one it announced.
    QNetworkRequest peerRequest(const QUrl &url) const;
    void pinReply(QNetworkReply *r);
private:
    struct Conn {
        QString peerFp;   // fingerprint of the certificate the client showed, if any
        QByteArray head;
        bool headDone = false;
        QString method, path;
        QHash<QString, QString> query, headers;
        qint64 bodyLeft = 0;
        QByteArray body;
        QFile *file = nullptr;
        QString fileId;
        QCryptographicHash *hash = nullptr;
        bool replied = false;
    };
    struct InFile {
        QString name;
        qint64 size = 0;
        qint64 received = 0;
        QString token, sha;
        QString savedAs;
        bool done = false, failed = false;
    };
    struct Session {
        bool active = false;
        QString id, from, ip;
        QHash<QString, InFile> files;
        QDateTime touched;
    };
    struct Pending {
        QPointer<QTcpSocket> socket;
        QString from, ip;
        QHash<QString, InFile> files;
    };
    struct OutFile { QString id, path; qint64 size = 0; QString token; };
    struct Outgoing {
        bool active = false;
        QString deviceId, alias, base, sessionId;
        QString expected;   // the device's fingerprint when it uses HTTPS: its certificate must match
        QList<OutFile> files;
        int next = 0;
        qint64 total = 0, sent = 0, current = 0;
        QPointer<QNetworkReply> reply;
    };

    QJsonObject info(bool withAnnounce, bool announce) const;
    void onDatagrams();
    void remember(const QJsonObject &o, const QString &ip, const QString &protocolFallback, const QString &provenFp = {});
    void registerWith(const QString &ip, int port, const QString &protocol, bool scan, const QString &expected = {});
    // anonymous: no client certificate (a register sent while hidden).
    QNetworkRequest makeRequest(const QUrl &url, const QString &expected, bool anonymous = false) const;
    void checkPeer(QNetworkReply *r, const QString &expected);
    QString expectedFor(const QUrl &url) const;
    void onConnection(QTcpServer *server);
    void onData(QTcpSocket *s);
    void route(QTcpSocket *s, Conn &c);
    void onBody(QTcpSocket *s, Conn &c);
    void reply(QTcpSocket *s, int code, const QByteArray &body = {});
    void endSession(bool ok, const QString &message);
    void uploadNext();
    void endSend(bool ok, const QString &message);
    QString savePath(const QString &name) const;
    // Where files from the device sending now are saved: Downloads/Huginn/<its name>.
    QString folder() const;
    void bindLan();

    Settings *m_settings;
    Tls::Identity m_tls;
    bool m_secure = false;      // false only if no certificate could be made: then plain HTTP
    QSslConfiguration m_serverTls;
    QSslConfiguration m_hiddenTls;   // while hidden: the hidden certificate now
    QString m_hiddenFp;              // its fingerprint (empty while visible)
    Tls::Identity m_hiddenNow, m_hiddenNext;   // cap h1: kept in the data folder (tls/hidden/now and next)
    qint64 m_hiddenSince = 0;                  // when "now" took over (ms)
    void loadHidden();
    void rotateHidden(bool turningOff);
    QSslConfiguration serverTls();
    void applyTls();   // the servers show serverTls() (the throwaway certificate while Nearby is off)
    bool off() const { return silent && silent(); }
    // The certificate this computer shows now: the throwaway one while Nearby is off.
    QString myFingerprint() const { return off() && !m_hiddenFp.isEmpty() ? m_hiddenFp : m_fingerprint; }
    bool mine(const QString &fp) const;
    // The contact a device's info stands for (its chat key, or a hidden one's hint), or empty.
    QString contactIn(const QJsonObject &o) const;
    struct KnownPeer { QString ip, protocol; int port = 0; qint64 seen = 0; QString fp; };   // fp: the certificate it showed (cap h1)
    QHash<QString, KnownPeer> m_peers;   // contact's chat key -> where it was last reached
    void keepPeer(const QString &key, const Device &d);
    int m_port = 53318;                 // our server's port (the same on every home-network address)
    bool m_on = false;
    std::vector<std::pair<QHostAddress, QTcpServer *>> m_servers;   // one per home-network address
    QStringList m_joined;                                           // interfaces in the multicast group
    QTimer m_lanTimer;
    QUdpSocket m_udp;
    QNetworkAccessManager m_net;
    QHash<QTcpSocket *, Conn> m_conns;
    QHash<QString, Device> m_devices;
    QTimer m_announceTimer;
    QTimer m_requestTimer;
    QTimer m_sessionTimer;
    Session m_session;
    Pending m_pending;
    Outgoing m_send;
    QString m_fingerprint;
    QString m_alias;
    QString m_error;
    QString m_status;
    int m_scanLeft = 0;
    QHash<QString, qint64> m_asked;              // address -> last register sent there (at most one every 10 s)
    QHash<QString, QList<qint64>> m_prompts;     // address -> "wants to send" boxes lately
    bool asking(const QString &ip);
    void turnedDown(const QString &ip);
    QList<QVariantMap> m_history;   // finished incoming files, newest last
    qint64 m_started = 0;           // when bytes began to move (speed and time left)
    QString m_last;
    bool m_lastOk = false;
};
