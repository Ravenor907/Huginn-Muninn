#pragma once

#include "filelink.h"

#include <QByteArray>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <functional>

class QFile;
class QTcpServer;
class QTcpSocket;
class QTimer;

// Encrypted file sharing over Tor (cap f4, shared/docs/protocol.md section 30), the same as Muninn's TorFiles.kt. Never
// through the relays. The sender opens a one-time onion service for one file (TorOnion: ADD_ONION NEW:ED25519-V3 with
// Flags=DiscardPK on its own control connection, so the key is never kept and the service goes when that connection
// closes), on Tor started on demand (UltraPrivacy::needTor, with Ultra privacy off too), and tells the receiver its
// address only inside the end-to-end encrypted `file` message torMessage(). The receiver connects through its own Tor's
// SOCKS port. Over that stream go the same sealed packets as on the UDP path (FileLink::seal), each with a 2-byte length
// in front; the receiver speaks first with an ack of what it has, so a transfer that switched from Nearby or Trusted
// direct, or a stream that broke, goes on from the last piece (TorStream).
namespace TorFiles
{
extern const char kCap[];                       // "f4"
constexpr qint64 kIdleMs = 10 * 60000;          // a service (or a wait) with nothing moving this long is closed
constexpr qint64 kReadyMs = 3 * 60000;          // Tor this long without a circuit: the file says so
constexpr qint64 kWarnBytes = qint64(1) << 30;  // a Tor transfer bigger than this asks first
constexpr quint64 kSeqBase = quint64(1) << 40;  // packets on a stream count from here (never the UDP path's nonces)
constexpr int kStreamWindow = 512;              // pieces in flight beyond the last ack
constexpr qint64 kStreamIdleMs = 120000;        // nothing heard on a stream this long: dropped (connect again)
constexpr int kMaxFrame = 2048;
constexpr qint64 kTorBytesPerSec = 1000000;     // about how fast Tor carries a file, for the card's estimate

QString nearbyLabel();                          // "Nearby"
QString trustedLabel();                         // "Trusted direct"
QString torLabel(qint64 size);                  // "Tor, slower", or "Tor, slower (about 20 min)" from 100 MB
// The question before a file over 1 GB that will most likely go over Tor; empty when it needn't ask.
QString warning(qint64 size, bool likelyTor);
// A v3 onion service id: 56 base32 letters (no ".onion"), the last one "d".
bool onionOk(const QString &h);
// The `tor` message (room `file`, sealed end to end like every `file` message): never anywhere else.
QJsonObject torMessage(const QString &id, const QString &host, int port);
bool torOk(const QJsonObject &p);
// SOCKS5 CONNECT to <host>.onion:<port> by name (Tor looks it up; nothing is looked up here).
QByteArray socksRequest(const QString &host, int port);
QByteArray frame(const QByteArray &packet);
// Self-test (in --chat-selftest, beside FileLink's): the stream both ways on loopback, a broken one going on, a stranger
// turned away, and the words.
bool selfTest();
}

// One file's one-time onion service: a socket on 127.0.0.1 and, in front of it on Tor, NEW:ED25519-V3 with
// Flags=DiscardPK on its own control connection. Closing it (or deleting it) removes the service.
class TorOnion : public QObject
{
    Q_OBJECT
public:
    explicit TorOnion(QObject *parent = nullptr);
    ~TorOnion() override;
    // Tor's control port and password (UltraPrivacy): listens, then asks Tor for the service.
    void start(int ctlPort, const QByteArray &password);
    void close();
    QString host() const { return m_host; }
    int port() const { return m_virtual; }

Q_SIGNALS:
    void published();
    void failed();
    void incoming(QTcpSocket *s);

private:
    QTcpServer *m_server = nullptr;
    QTcpSocket *m_ctl = nullptr;
    QByteArray m_buf;
    QString m_host;
    int m_virtual = 0;
    bool m_authed = false;
};

// One end of one stream of a file over Tor. The sender waits for the receiver's first ack, then sends every piece that
// isn't confirmed, in order, at most kStreamWindow beyond the last ack; the receiver acks every 64 new pieces or second
// and says done. The sender's Sender, the receiver's Receiver, the file and the packet counter live as long as the
// transfer (another stream, or the UDP path before it, goes on from them).
class TorStream : public QObject
{
    Q_OBJECT
public:
    enum End { Done, Broken, Stranger };
    // socks > 0: the receiver connects to host.onion:port through Tor's SOCKS port on 127.0.0.1 first (s not connected
    // yet); otherwise s is already the stream.
    TorStream(QTcpSocket *s, bool sender, const QByteArray &key, const QString &id, qint64 size, FileLink::Sender *snd, FileLink::Receiver *rcv,
              QFile *file, quint64 *seq, QObject *parent = nullptr, int socks = 0, const QString &host = {}, int port = 0, qint64 idleMs = TorFiles::kStreamIdleMs);
    ~TorStream() override;
    std::function<bool()> paused;               // paused here (27.5)

Q_SIGNALS:
    void progress(qint64 done);
    void held(bool on);
    void finished(int end);

private:
    void begin();
    void readSome();
    void onPacket(const QByteArray &p);
    void tick();
    void pump();
    void send(quint8 type, const QByteArray &payload);
    void ack();
    void end(End e);

    QTcpSocket *m_s;
    bool m_sender;
    QByteArray m_key, m_buf;
    QString m_id, m_host;
    qint64 m_size, m_idle;
    FileLink::Sender *m_snd;
    FileLink::Receiver *m_rcv;
    QFile *m_file;
    quint64 *m_seq;
    QTimer *m_tick;
    int m_socks = 0, m_port = 0, m_socksStep = 0, m_next = 0, m_lastBelow = -1, m_fresh = 0;
    bool m_proven = false, m_held = false, m_ended = false, m_wasPaused = false;
    qint64 m_lastHeard = 0, m_lastKeep = 0, m_stuckSince = 0, m_lastAck = 0;
};
