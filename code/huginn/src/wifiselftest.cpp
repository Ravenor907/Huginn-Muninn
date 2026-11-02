#include "wifiselftest.h"

#include "chat.h"
#include "chatcrypto.h"
#include "chatwire.h"
#include "lan.h"
#include "localsend.h"
#include "platform/chime.h"
#include "platform/notify.h"
#include "settings.h"

#include <QCoreApplication>
#include <QDir>
#include <QDirIterator>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QSettings>
#include <QSslConfiguration>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QUuid>
#include <cstdio>
#include <memory>

namespace
{
constexpr int kPort = 53317;

struct Run {
    Chat *chat = nullptr;
    LocalSend *net = nullptr;
    Chime *chime = nullptr;
    bool all = true;
    QString ip;
    ChatCrypto::KeyPair me;   // the pretend Muninn
    QString myKey, huginnKey;
    QByteArray sk;
    QNetworkAccessManager http;
    QTcpServer phone;         // the pretend Muninn's own endpoint, where receipts arrive
    QStringList arrivals;     // texts Chat said arrived
    QStringList receipts;     // ids Huginn said were delivered
    QString firstId;
    QTimer watchdog;

    void check(const char *what, bool ok)
    {
        std::printf("wifi %s: %s\n", what, ok ? "ok" : "FAILED");
        std::fflush(stdout);
        all &= ok;
    }
    void finish()
    {
        std::printf("wifi self-test: %s\n", all ? "all ok" : "FAILED");
        std::fflush(stdout);
        QCoreApplication::exit(all ? 0 : 1);
    }
    QNetworkRequest request(const QString &path) const
    {
        QNetworkRequest r(QUrl(QStringLiteral("https://%1:%2%3").arg(ip).arg(kPort).arg(path)));
        QSslConfiguration c = QSslConfiguration::defaultConfiguration();
        c.setPeerVerifyMode(QSslSocket::VerifyNone);
        r.setSslConfiguration(c);
        r.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
        r.setTransferTimeout(8000);
        return r;
    }
    QJsonObject envelope(const QString &id, const QString &text) const
    {
        const QJsonObject fields{{QStringLiteral("room"), QStringLiteral("private")}, {QStringLiteral("fromKey"), myKey},
                                 {QStringLiteral("fromName"), QStringLiteral("Muninn self-test")}, {QStringLiteral("id"), id}};
        QJsonObject o = ChatWire::seal(fields, sk, huginnKey, text.toUtf8(), nullptr, [] { return ChatCrypto::newKeyPair(); });
        // This pretend phone has no certificate, so it can't prove its route (cap s1): it plays a phone without s1.
        o.insert(QStringLiteral("caps"), QString::fromLatin1(ChatWire::kCaps).remove(QStringLiteral(" s1")));
        return o;
    }
};

// A tiny HTTP endpoint like Muninn's: reads one request, answers 200 {}.
void servePhone(Run *run)
{
    QObject::connect(&run->phone, &QTcpServer::newConnection, &run->phone, [run] {
        while (QTcpSocket *s = run->phone.nextPendingConnection()) {
            auto buf = std::make_shared<QByteArray>();
            QObject::connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
            QObject::connect(s, &QTcpSocket::readyRead, s, [run, s, buf] {
                *buf += s->readAll();
                const int end = buf->indexOf("\r\n\r\n");
                if (end < 0)
                    return;
                qint64 length = 0;
                for (const QByteArray &line : buf->left(end).split('\n'))
                    if (line.toLower().startsWith("content-length:"))
                        length = line.mid(15).trimmed().toLongLong();
                if (buf->size() < end + 4 + length)
                    return;
                const QByteArray head = buf->left(end), body = buf->mid(end + 4, int(length));
                s->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
                s->disconnectFromHost();
                if (!head.startsWith("POST /api/huginn/v1/message"))
                    return;
                const QJsonObject o = QJsonDocument::fromJson(body).object();
                if (o.value(QStringLiteral("room")).toString() != QLatin1String("receipt"))
                    return;
                bool ok = false;
                const QByteArray plain = ChatWire::open(o, run->sk, run->me, nullptr, [] { return ChatCrypto::newKeyPair(); }, &ok, run->myKey);
                const QJsonObject r = QJsonDocument::fromJson(plain).object();
                for (const QJsonValue &v : r.value(QStringLiteral("ids")).toArray())
                    run->receipts << v.toString();
            });
        }
    });
}

void sendSecond(Run *run)
{
    const QString text = QStringLiteral("Second one: emoji \U0001F426 and \u00C6\u00D8\u00C5, plus a long line ") + QString(300, QLatin1Char('x'));
    QNetworkReply *r = run->http.post(run->request(QStringLiteral("/api/huginn/v1/message")),
                                      QJsonDocument(run->envelope(QUuid::createUuid().toString(QUuid::WithoutBraces), text)).toJson(QJsonDocument::Compact));
    QObject::connect(r, &QNetworkReply::finished, r, [run, r, text] {
        r->deleteLater();
        run->check("second message accepted (HTTP 200)", r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() == 200);
        QTimer::singleShot(300, run->chat, [run, text] {
            run->check("second message arrived", run->arrivals.contains(text));
            // Leave the window, the raven call and the notification a moment to finish.
            QTimer::singleShot(2500, run->chat, [run] { run->finish(); });
        });
    });
}

void sendFirst(Run *run)
{
    run->firstId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const QString text = QStringLiteral("Hello from Muninn over Wi-Fi");
    QNetworkReply *r = run->http.post(run->request(QStringLiteral("/api/huginn/v1/message")),
                                      QJsonDocument(run->envelope(run->firstId, text)).toJson(QJsonDocument::Compact));
    QObject::connect(r, &QNetworkReply::finished, r, [run, r, text] {
        r->deleteLater();
        run->check("message accepted (HTTP 200)", r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() == 200);
        run->check("Chat says it arrived, with the right text", run->arrivals.contains(text));
        // What main() does for a message when Huginn isn't in front: the call and a notification.
        run->chime->play();
        Platform::notify(QStringLiteral("Muninn self-test"), text);
        run->check("notification shown without a crash", true);
        // Huginn answers "delivered" to the phone's own endpoint.
        auto *wait = new QTimer(run->chat);
        auto left = std::make_shared<int>(80);
        QObject::connect(wait, &QTimer::timeout, run->chat, [run, wait, left] {
            if (!run->receipts.contains(run->firstId) && --*left > 0)
                return;
            wait->deleteLater();
            run->check("delivered receipt came back over Wi-Fi", run->receipts.contains(run->firstId));
            sendSecond(run);
        });
        wait->start(100);
    });
}

void begin(Run *run)
{
    run->check("home-network address rules", Lan::selfTest());
    run->check("received file names stay in the save folder", LocalSend::selfTest());
    std::printf("wifi resources: %s, %s\n", qPrintable(Platform::resource(QStringLiteral("raven_message.wav"))),
                qPrintable(Platform::resource(QStringLiteral("raven-gold.png"))));
#ifdef Q_OS_WIN
    run->check("tray icon found", !Platform::resource(QStringLiteral("raven-gold.png")).isEmpty());
#endif
    run->check("raven call loaded", run->chime->ready());
    run->check("Huginn is listening on the home network", run->net->listening());
    run->ip = Lan::firstIPv4();
    std::printf("wifi home network address: %s\n", run->ip.isEmpty() ? "none" : qPrintable(run->ip));
    if (run->ip.isEmpty()) {
        run->check("this computer has a home-network (private) address", false);
        run->finish();
        return;
    }
    // Nothing but the home network: 127.0.0.1 must not answer.
    {
        QTcpSocket probe;
        probe.connectToHost(QHostAddress::LocalHost, kPort);
        run->check("not listening on 127.0.0.1", !probe.waitForConnected(2000));
        probe.abort();
    }
    // Groups and contact folders are on disk the moment they're made, so they're there after a restart.
    if (run->chat->ready()) {
        Chat *c = run->chat;
        const QString g = c->createGroup(QStringLiteral("Self-test group"), QString());
        run->check("a new group is made", !g.isEmpty());
        bool listed = false;
        for (const QVariant &v : c->groups())
            listed = listed || v.toMap().value(QStringLiteral("id")).toString() == g;
        run->check("a new group is listed", listed);
        run->check("a new group is kept on disk", !g.isEmpty() && c->savedFile(QStringLiteral("groups.json")).contains(g.toUtf8()));
        const QString f = c->addFolder(QStringLiteral("Self-test folder"));
        run->check("a new folder is made and kept on disk", !f.isEmpty() && c->savedFile(QStringLiteral("folders.json")).contains(f.toUtf8()));
        const QString someone = QStringLiteral("self-test-key");
        c->placeIn(someone, QString(), f, {someone});
        run->check("someone dropped in a folder is in it, on disk too",
                   c->foldersOf(someone).contains(f) && c->savedFile(QStringLiteral("folders.json")).contains(someone.toUtf8()));
        if (!g.isEmpty())
            c->forgetGroup(g);
        if (!f.isEmpty())
            c->deleteFolder(f);
        run->check("a forgotten group and a deleted folder are gone from disk",
                   (g.isEmpty() || !c->savedFile(QStringLiteral("groups.json")).contains(g.toUtf8()))
                       && (f.isEmpty() || !c->savedFile(QStringLiteral("folders.json")).contains(f.toUtf8())));
    } else {
        std::printf("wifi groups and folders: chat not open, not checked\n");
    }
    run->huginnKey = run->chat->chatKey();
    run->me = ChatCrypto::newKeyPair();
    run->myKey = QString::fromLatin1(run->me.publicDer.toBase64());
    run->sk = ChatCrypto::sharedKey(run->me.privateDer, run->me.publicDer, QByteArray::fromBase64(run->huginnKey.toLatin1()));
    run->check("shared key with Huginn", run->sk.size() == 32);
    run->check("pretend phone endpoint up", run->phone.listen(QHostAddress(run->ip), 0));
    servePhone(run);
    QObject::connect(&run->http, &QNetworkAccessManager::sslErrors, &run->http,
                     [](QNetworkReply *r, const QList<QSslError> &) { r->ignoreSslErrors(); });

    // Like Muninn: say who we are (and where our endpoint is), as LocalSend's register does.
    const QJsonObject info{{QStringLiteral("alias"), QStringLiteral("Muninn self-test")}, {QStringLiteral("version"), QStringLiteral("2.1")},
                           {QStringLiteral("deviceModel"), QStringLiteral("Muninn")}, {QStringLiteral("deviceType"), QStringLiteral("mobile")},
                           {QStringLiteral("fingerprint"), QUuid::createUuid().toString(QUuid::Id128)},
                           {QStringLiteral("port"), int(run->phone.serverPort())}, {QStringLiteral("protocol"), QStringLiteral("http")},
                           {QStringLiteral("download"), false}, {QStringLiteral("chatKey"), run->myKey},
                           {QStringLiteral("chatName"), QStringLiteral("Muninn self-test")}, {QStringLiteral("chatApp"), QStringLiteral("muninn")}};
    QNetworkReply *r = run->http.post(run->request(QStringLiteral("/api/localsend/v2/register")), QJsonDocument(info).toJson(QJsonDocument::Compact));
    QObject::connect(r, &QNetworkReply::finished, r, [run, r] {
        r->deleteLater();
        const QJsonObject o = QJsonDocument::fromJson(r->readAll()).object();
        if (r->error() != QNetworkReply::NoError)
            std::printf("wifi register error: %s\n", qPrintable(r->errorString()));
        run->check("HTTPS register answered with Huginn's chat key", o.value(QStringLiteral("chatKey")).toString() == run->huginnKey);
        sendFirst(run);
    });
}
}

namespace WifiSelfTest
{
void isolate()
{
    // Settings::org(): the settings (the registry on Windows) under their own name, emptied first. Set before the
    // folders below, whose names follow it.
    QCoreApplication::setOrganizationName(QStringLiteral("HuginnSelfTest"));
    QStandardPaths::setTestModeEnabled(true);
    QSettings(Settings::org(), QStringLiteral("Huginn")).clear();
    for (const auto where : {QStandardPaths::AppDataLocation, QStandardPaths::AppLocalDataLocation})
        QDir(QStandardPaths::writableLocation(where)).removeRecursively();
    QDir(QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation) + QLatin1Char('/') + Settings::org()).removeRecursively();
}

void start(Chat *chat, LocalSend *net, Chime *chime)
{
    auto *run = new Run;
    run->chat = chat;
    run->net = net;
    run->chime = chime;
    QObject::connect(chat, &Chat::arrived, chat, [run](const QString &, const QString &text, const QString &) { run->arrivals << text; });
    run->watchdog.setSingleShot(true);
    QObject::connect(&run->watchdog, &QTimer::timeout, chat, [run] {
        run->check("finished within 90 seconds", false);
        run->finish();
    });
    run->watchdog.start(90000);
    // Give the window and the listeners a moment to come up first.
    QTimer::singleShot(1500, chat, [run] { begin(run); });
}
}
