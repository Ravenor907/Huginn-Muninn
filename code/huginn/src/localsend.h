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
// Discovery: UDP multicast 224.0.0.167:53317 plus a scan of the home network.
// Receiving: a small HTTPS server on port 53317 with our own self-signed certificate, whose
// SHA-256 is the fingerprint we announce; every incoming transfer is accepted on screen.
// Sending: HTTPS to the other device (HTTP only for devices that have encryption off), checking
// that its certificate matches the fingerprint it announced.
class LocalSend : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    Q_PROPERTY(QString alias READ alias CONSTANT)
    Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
    Q_PROPERTY(bool scanning READ scanning NOTIFY devicesChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY progressChanged)
    Q_PROPERTY(QString status READ status NOTIFY progressChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(bool sending READ sending NOTIFY progressChanged)
    Q_PROPERTY(QVariantMap request READ request NOTIFY requestChanged)
    Q_PROPERTY(QVariantList items READ items NOTIFY progressChanged)

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

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void refresh();
    Q_INVOKABLE void accept();
    Q_INVOKABLE void decline();
    Q_INVOKABLE void send(const QString &deviceId, const QStringList &paths, const QString &pin = QString());
    Q_INVOKABLE void cancelSend();

Q_SIGNALS:
    void stateChanged();
    void devicesChanged();
    void progressChanged();
    void requestChanged();
    void pinNeeded(const QString &deviceId, bool wrong);
    void finished(bool ok, const QString &message);

private:
    struct Device {
        QString id, alias, model, type, ip, protocol;
        QString chatKey, chatName, app;   // set by Huginn and Muninn; plain LocalSend apps don't chat
        int port = 53317;
        QDateTime seen;
        bool proven = false;              // cap s1: its chat key signed this certificate (false: no s1 proof, only extra)
    };
public:
    // Chat (see chat.h) rides on the same port: what to add to our info, and who can be messaged.
    std::function<QJsonObject()> chatInfo;
    // Hidden from people nearby (Chat's setting): no multicast announcement at all, no answer to anyone
    // else's, and no chat proof or hint in what is sent. Look again still finds devices and files still go out.
    std::function<bool()> silent;
    // Hidden changed (Chat): from now on the server shows a throwaway certificate while hidden (never the lasting one,
    // which anyone close by could follow), and the lasting one again once visible.
    void hiddenChanged();
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
    void bindLan();

    Settings *m_settings;
    Tls::Identity m_tls;
    bool m_secure = false;      // false only if no certificate could be made: then plain HTTP
    QSslConfiguration m_serverTls;
    QSslConfiguration m_hiddenTls;   // while hidden: a certificate made for this time only, kept in memory
    QSslConfiguration serverTls();
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
};
