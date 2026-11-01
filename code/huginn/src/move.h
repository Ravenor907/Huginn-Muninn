#pragma once

#include "vault.h"

#include <QHash>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QSettings>
#include <QTimer>
#include <QVariantMap>

class Chat;
class Link;
class LocalSend;

// Moving to another device (cap x1, shared/docs/protocol.md section 18), the same as Muninn's MoveWire.kt and Move.kt.
// The new device shows a code; the old one takes it, both show the same 4 emoji, the old one says go and is locked
// from then on: it sends everything straight over the home Wi-Fi in parts sealed with K, and once the new device has it
// all and took it, the old one erases its keys and shows "Moved to [name]", with one tap to become a linked device.
namespace MoveWire {
extern const char kPrefix[];   // "MUNINNM1:"
extern const char kPath[];     // "/api/huginn/v1/move"
const int kPart = 512 * 1024;
struct Code { QStringList a; QString e; QByteArray s; QString n, app; };
// An IPv4 literal on a home network (10/8, 172.16/12, 192.168/16, link-local 169.254/16), "ip:port" with one, and
// whether ip is on the same network as mine with prefix bits. A move only ever goes to those (18.1).
bool lanIp(const QString &ip);
bool lanAddr(const QString &addr);
bool sameNetwork(const QString &ip, const QString &mine, int prefix);
QString code(const Code &c);
bool parse(const QString &text, Code *c);
// K = SHA-256(info || ECDH x || secret || SHA-256(new one-off key) || SHA-256(old one-off key)).
QByteArray key(const QByteArray &myPrivDer, const QString &theirPub, const QByteArray &secret, const QString &newPub, const QString &oldPub);
QString emoji(const QByteArray &k);
QString mac(const QByteArray &k, const QString &text);
bool same(const QString &a, const QString &b);
QString partAad(int i, int n, const QString &h);
int parts(qint64 size);
QString seal(const QByteArray &k, const QByteArray &plain, const QString &aad, const QByteArray &nonce = QByteArray());
QByteArray open(const QByteArray &k, const QString &d, const QString &aad, bool *ok);
QByteArray pack(const Vault::Files &entries);
bool unpack(const QByteArray &blob, Vault::Files *out);
// shared/vectors/p95-vectors.json: moving, invite links (with GroupWire) and "mi" in a group state.
bool selfTest(const QString &vectorsPath);
}

class Move : public QObject
{
    Q_OBJECT
    // On the new device: state "" , "shown", "hello", "receiving", "done"; the emoji, who it comes from, parts.
    Q_PROPERTY(QVariantMap incoming READ incoming NOTIFY changed)
    // On the old device: state "", "checking", "moving", "moved"; the emoji, where it goes, parts, a note.
    Q_PROPERTY(QVariantMap outgoing READ outgoing NOTIFY changed)
    Q_PROPERTY(bool locked READ locked NOTIFY changed)
    // On the new device: the old one asks to become a linked device ("Link [name]?"), or "".
    Q_PROPERTY(QString linkAsk READ linkAsk NOTIFY changed)
public:
    Move(Chat *chat, Vault *vault, Link *link, LocalSend *net, QObject *parent = nullptr);
    QVariantMap incoming() const { return m_in; }
    QVariantMap outgoing() const { return m_out; }
    bool locked() const { return m_locked; }
    QString linkAsk() const { return m_linkAsk; }

    // The new device: the code to show (the same while a move to here is under way), They match, Cancel.
    Q_INVOKABLE QString showCode();
    Q_INVOKABLE void confirmHere();
    Q_INVOKABLE void cancelHere();
    // The old device: take the new one's code (answers with what went wrong, or "" once the emoji show), They match, Cancel.
    Q_INVOKABLE void take(const QString &code);
    Q_INVOKABLE void go();
    Q_INVOKABLE void cancelOut();
    // After "Moved to [name]": become a linked device of the new one, or start fresh.
    Q_INVOKABLE void becomeLinked();
    Q_INVOKABLE void startFresh();
    // Stop a move under way with the person's own password or pattern: false when it isn't theirs.
    Q_INVOKABLE bool abort(const QString &secret);
    // "Link [name]?" answered on the new device.
    Q_INVOKABLE void answerLink(bool yes);

    // POST kPath on this computer's Wi-Fi port: status code and the answer.
    QPair<int, QByteArray> handle(const QByteArray &body);

Q_SIGNALS:
    void changed();
    void notice(const QString &text, bool bad);

private:
    QJsonObject common() const;
    Vault::Files fromCommon(const QJsonObject &o, QString *error) const;
    void post(const QString &addr, const QJsonObject &body, std::function<void(int, const QByteArray &)> done, bool http = false);
    void tick();
    void finishIfReady();
    void finishMoved();
    void stopMoving(const QString &note);
    static QList<QPair<QString, int>> mine();
    QStringList missing() const;
    QString partsDir() const;
    QJsonObject load(const QString &k) const;
    void store(const QString &k, const QJsonObject &o);
    void publish();

    Chat *m_chat;
    Vault *m_vault;
    Link *m_link;
    LocalSend *m_net;
    QHash<QString, QJsonObject> m_mem;   // in and out while the vault is locked or erased (this run only)
    void vaultOpened();
    void resume();                 // a move that was under way when Huginn closed goes on
    QNetworkAccessManager m_http;
    QTimer m_timer;
    QVariantMap m_in, m_out;
    bool m_locked = false, m_busy = false;
    QByteArray m_blob;   // the old device's payload while it moves
    QTimer m_linkTimer;
    QByteArray m_movedK; // after a move, for 10 minutes only, in memory
    QString m_movedAddr, m_movedTo, m_linkCode, m_linkAsk;
    qint64 m_lastHeard = 0;
};
