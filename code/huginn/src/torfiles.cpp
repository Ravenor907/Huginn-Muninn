#include "torfiles.h"

#include <QDateTime>
#include <QEventLoop>
#include <QFile>
#include <QHostAddress>
#include <QNetworkProxy>
#include <QRandomGenerator>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTimer>
#include <qendian.h>
#include <cstdio>

namespace
{
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
}

const char TorFiles::kCap[] = "f4";

QString TorFiles::nearbyLabel() { return QStringLiteral("Nearby"); }
QString TorFiles::trustedLabel() { return QStringLiteral("Trusted direct"); }

QString TorFiles::torLabel(qint64 size)
{
    const QString tor = QStringLiteral("Tor, slower");
    if (size < 100LL * 1024 * 1024)
        return tor;
    const qint64 min = (size / kTorBytesPerSec + 59) / 60;
    if (min < 60)
        return tor + QStringLiteral(" (about %1 min)").arg(min);
    return min % 60 == 0 ? tor + QStringLiteral(" (about %1 h)").arg(min / 60) : tor + QStringLiteral(" (about %1 h %2 min)").arg(min / 60).arg(min % 60);
}

QString TorFiles::warning(qint64 size, bool likelyTor)
{
    if (!likelyTor || size <= kWarnBytes)
        return {};
    QString est = torLabel(size);
    est = est.mid(est.indexOf(QLatin1String("about ")) + 6);
    est.chop(1);
    return QStringLiteral("It goes over Tor, which is slower. %1 may take about %2.").arg(FileLink::sizeText(size), est);
}

bool TorFiles::onionOk(const QString &h)
{
    if (h.size() != 56 || !h.endsWith(QLatin1Char('d')))
        return false;
    for (const QChar c : h)
        if (!((c >= QLatin1Char('a') && c <= QLatin1Char('z')) || (c >= QLatin1Char('2') && c <= QLatin1Char('7'))))
            return false;
    return true;
}

QJsonObject TorFiles::torMessage(const QString &id, const QString &host, int port)
{
    return QJsonObject{{QStringLiteral("t"), QStringLiteral("tor")}, {QStringLiteral("id"), id}, {QStringLiteral("tor"), host}, {QStringLiteral("p"), port}};
}

bool TorFiles::torOk(const QJsonObject &p)
{
    const double port = p.value(QStringLiteral("p")).toDouble(-1);
    return onionOk(p.value(QStringLiteral("tor")).toString()) && port >= 1 && port <= 65535 && port == double(int(port));
}

QByteArray TorFiles::socksRequest(const QString &host, int port)
{
    const QByteArray name = (host + QStringLiteral(".onion")).toLatin1();
    QByteArray r("\x05\x01\x00\x03", 4);
    r.append(char(name.size()));
    r.append(name);
    r.append(char((port >> 8) & 0xFF));
    r.append(char(port & 0xFF));
    return r;
}

QByteArray TorFiles::frame(const QByteArray &packet)
{
    QByteArray f(2, 0);
    qToBigEndian(quint16(packet.size()), f.data());
    return f + packet;
}

// ------------------------------------------------------------------ the one-time onion service

TorOnion::TorOnion(QObject *parent) : QObject(parent) {}

TorOnion::~TorOnion() { close(); }

void TorOnion::start(int ctlPort, const QByteArray &password)
{
    m_server = new QTcpServer(this);
    m_server->setProxy(QNetworkProxy::NoProxy);
    if (ctlPort <= 0 || password.isEmpty() || !m_server->listen(QHostAddress::LocalHost, 0)) {
        QTimer::singleShot(0, this, &TorOnion::failed);
        return;
    }
    connect(m_server, &QTcpServer::newConnection, this, [this] {
        while (QTcpSocket *s = m_server->nextPendingConnection())
            Q_EMIT incoming(s);
    });
    m_virtual = 1024 + int(QRandomGenerator::system()->bounded(64000));
    m_ctl = new QTcpSocket(this);
    m_ctl->setProxy(QNetworkProxy::NoProxy);
    const quint16 local = m_server->serverPort();
    connect(m_ctl, &QTcpSocket::connected, this, [this, password, local] {
        m_ctl->write("AUTHENTICATE \"" + password + "\"\r\n");
        m_ctl->write(QStringLiteral("ADD_ONION NEW:ED25519-V3 Flags=DiscardPK Port=%1,127.0.0.1:%2\r\n").arg(m_virtual).arg(local).toLatin1());
    });
    connect(m_ctl, &QTcpSocket::readyRead, this, [this] {
        m_buf += m_ctl->readAll();
        int nl;
        while ((nl = int(m_buf.indexOf("\r\n"))) >= 0) {
            const QString line = QString::fromLatin1(m_buf.left(nl));
            m_buf.remove(0, nl + 2);
            if (!line.startsWith(QLatin1Char('2'))) {
                Q_EMIT failed();
                return;
            }
            if (line.startsWith(QLatin1String("250-ServiceID="))) {
                const QString h = line.mid(14).trimmed();
                if (!TorFiles::onionOk(h)) {
                    Q_EMIT failed();
                    return;
                }
                m_host = h;
            } else if (line == QLatin1String("250 OK") && !m_authed) {
                m_authed = true;
            } else if (line == QLatin1String("250 OK") && !m_host.isEmpty()) {
                Q_EMIT published();
            }
        }
    });
    connect(m_ctl, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        if (m_host.isEmpty())
            Q_EMIT failed();
    });
    m_ctl->connectToHost(QHostAddress::LocalHost, quint16(ctlPort));
}

void TorOnion::close()
{
    if (m_ctl) {
        // Not detached: closing the control connection removes the service; DEL_ONION says so first.
        if (!m_host.isEmpty() && m_ctl->state() == QAbstractSocket::ConnectedState) {
            m_ctl->write(QStringLiteral("DEL_ONION %1\r\n").arg(m_host).toLatin1());
            m_ctl->flush();
        }
        m_ctl->disconnect(this);
        m_ctl->abort();
        m_ctl->deleteLater();
        m_ctl = nullptr;
    }
    if (m_server) {
        m_server->close();
        m_server->deleteLater();
        m_server = nullptr;
    }
}

// ------------------------------------------------------------------ one stream

TorStream::TorStream(QTcpSocket *s, bool sender, const QByteArray &key, const QString &id, qint64 size, FileLink::Sender *snd, FileLink::Receiver *rcv,
                     QFile *file, quint64 *seq, QObject *parent, int socks, const QString &host, int port, qint64 idleMs)
    : QObject(parent), m_s(s), m_sender(sender), m_key(key), m_id(id), m_host(host), m_size(size), m_idle(idleMs), m_snd(snd), m_rcv(rcv),
      m_file(file), m_seq(seq), m_socks(socks), m_port(port)
{
    m_s->setParent(this);
    m_s->setProxy(QNetworkProxy::NoProxy);
    m_s->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    m_lastHeard = m_stuckSince = nowMs();
    connect(m_s, &QTcpSocket::readyRead, this, &TorStream::readSome);
    connect(m_s, &QTcpSocket::bytesWritten, this, [this] { if (m_sender) pump(); });
    connect(m_s, &QTcpSocket::disconnected, this, [this] { end(m_proven ? Broken : Stranger); });
    connect(m_s, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) { end(m_proven ? Broken : Stranger); });
    m_tick = new QTimer(this);
    connect(m_tick, &QTimer::timeout, this, &TorStream::tick);
    m_tick->start(50);
    if (m_socks > 0) {
        connect(m_s, &QTcpSocket::connected, this, [this] { m_s->write(QByteArray("\x05\x01\x00", 3)); m_socksStep = 1; });
        m_s->connectToHost(QHostAddress::LocalHost, quint16(m_socks));
    } else {
        QTimer::singleShot(0, this, &TorStream::begin);
    }
}

TorStream::~TorStream() = default;

void TorStream::begin()
{
    m_socksStep = 3;
    m_lastHeard = nowMs();
    // The receiver speaks first: what it already has (and the proof it has the key).
    if (!m_sender) {
        ack();
        m_proven = true;
    }
    readSome();
}

void TorStream::send(quint8 type, const QByteArray &payload)
{
    const QByteArray p = FileLink::seal(m_key, m_id, type, m_sender ? 0 : 1, (*m_seq)++, payload);
    if (!p.isEmpty())
        m_s->write(TorFiles::frame(p));
}

void TorStream::ack()
{
    int below = 0;
    quint64 bits = 0;
    m_rcv->ack(&below, &bits);
    QByteArray pl(12, 0);
    qToBigEndian(quint32(below), pl.data());
    qToBigEndian(bits, pl.data() + 4);
    send(FileLink::Ack, pl);
    m_lastAck = nowMs();
    m_fresh = 0;
    Q_EMIT progress(qMin(m_size, qint64(below) * FileLink::kChunk));
}

void TorStream::readSome()
{
    if (m_ended)
        return;
    m_buf += m_s->readAll();
    // SOCKS5: the greeting's answer, then the connect's (4 bytes, the bound address, the port).
    if (m_socksStep == 1) {
        if (m_buf.size() < 2)
            return;
        if (m_buf.at(0) != 5 || m_buf.at(1) != 0)
            return end(Stranger);
        m_buf.remove(0, 2);
        m_s->write(TorFiles::socksRequest(m_host, m_port));
        m_socksStep = 2;
    }
    if (m_socksStep == 2) {
        if (m_buf.size() < 5)
            return;
        if (m_buf.at(0) != 5 || m_buf.at(1) != 0)
            return end(Stranger);
        const int atyp = m_buf.at(3);
        const int len = atyp == 1 ? 4 : atyp == 4 ? 16 : atyp == 3 ? 1 + quint8(m_buf.at(4)) : -1;
        if (len < 0)
            return end(Stranger);
        if (m_buf.size() < 4 + len + 2)
            return;
        m_buf.remove(0, 4 + len + 2);
        begin();
        return;
    }
    if (m_socksStep != 3 && m_socks > 0)
        return;
    while (m_buf.size() >= 2 && !m_ended) {
        const int n = qFromBigEndian<quint16>(m_buf.constData());
        if (n < 1 || n > TorFiles::kMaxFrame)
            return end(m_proven ? Broken : Stranger);
        if (m_buf.size() < 2 + n)
            break;
        const QByteArray p = m_buf.mid(2, n);
        m_buf.remove(0, 2 + n);
        onPacket(p);
    }
}

void TorStream::onPacket(const QByteArray &p)
{
    quint8 t = 0;
    QByteArray pl;
    if (!FileLink::open(m_key, m_id, m_sender ? 1 : 0, p, &t, &pl))
        return end(m_proven ? Broken : Stranger);
    m_proven = true;
    m_lastHeard = nowMs();
    if (t == FileLink::Hold && !pl.isEmpty()) {
        m_held = pl.at(0) == 1;
        Q_EMIT held(m_held);
        return;
    }
    if (m_sender) {
        if (t == FileLink::Ack && pl.size() >= 12) {
            const int below = int(qFromBigEndian<quint32>(pl.constData()));
            m_snd->ack(below, qFromBigEndian<quint64>(pl.constData() + 4));
            if (below > m_lastBelow) {
                m_lastBelow = below;
                m_stuckSince = m_lastHeard;
            }
            if (m_next < below)
                m_next = below;
            if (m_held) {
                m_held = false;
                Q_EMIT held(false);
            }
            Q_EMIT progress(qMin(m_size, qint64(m_snd->confirmedBelow()) * FileLink::kChunk));
        } else if (t == FileLink::Done) {
            m_snd->ack(FileLink::pieces(m_size), 0);
        }
        if (m_snd->done()) {
            Q_EMIT progress(m_size);
            return end(Done);
        }
        pump();
        return;
    }
    if (t == FileLink::Data && pl.size() > 4) {
        const int i = int(qFromBigEndian<quint32>(pl.constData()));
        const qint64 at = qint64(i) * FileLink::kChunk;
        if (i >= 0 && i < FileLink::pieces(m_size) && pl.size() - 4 == qMin<qint64>(FileLink::kChunk, m_size - at) && m_rcv->take(i)) {
            if (!m_file->seek(at) || m_file->write(pl.constData() + 4, pl.size() - 4) != pl.size() - 4)
                return end(Broken);
            m_fresh++;
        }
        if (m_rcv->done()) {
            ack();
            send(FileLink::Done, QByteArray());
            m_s->flush();
            return end(Done);
        }
        if (m_fresh >= 64 && !(paused && paused()))
            ack();
    }
}

void TorStream::pump()
{
    if (m_ended || !m_proven || m_held || (paused && paused()))
        return;
    const int n = FileLink::pieces(m_size);
    if (m_next >= n && nowMs() - m_stuckSince > 10000) {
        m_next = m_snd->confirmedBelow();   // everything went once and some isn't confirmed: from the first gap again
        m_stuckSince = nowMs();
    }
    bool sent = false;
    while (m_next < n && m_next - m_snd->confirmedBelow() < TorFiles::kStreamWindow && m_s->bytesToWrite() < 256 * 1024) {
        const int i = m_next++;
        if (m_snd->has(i))
            continue;
        const qint64 at = qint64(i) * FileLink::kChunk;
        const int len = int(qMin<qint64>(FileLink::kChunk, m_size - at));
        QByteArray chunk;
        if (!m_file->seek(at) || (chunk = m_file->read(len)).size() != len)
            return end(Broken);
        QByteArray pl(4, 0);
        qToBigEndian(quint32(i), pl.data());
        send(FileLink::Data, pl + chunk);
        sent = true;
    }
    if (sent)
        m_lastKeep = nowMs();
}

void TorStream::tick()
{
    if (m_ended)
        return;
    const qint64 t = nowMs();
    if (!m_proven && t - m_lastHeard > 30000 && m_socksStep == 3)
        return end(Stranger);
    if (m_proven && t - m_lastHeard > m_idle)
        return end(Broken);
    if (m_socksStep != 3 && m_socks > 0) {
        if (t - m_lastHeard > 120000)
            end(Broken);   // Tor couldn't reach the service in time
        return;
    }
    const bool p = paused && paused();
    if (m_sender) {
        if (!m_proven)
            return;
        if ((p || m_held) && t - m_lastKeep >= 1000) {
            send(p ? FileLink::Hold : FileLink::Probe, QByteArray(1, 1));
            m_lastKeep = t;
        } else if (!p && !m_held) {
            pump();
            if (t - m_lastKeep >= 5000) {
                send(FileLink::Probe, QByteArray(1, 1));
                m_lastKeep = t;
            }
        }
        return;
    }
    if (p) {
        if (!m_wasPaused || t - m_lastAck >= 1000) {
            send(FileLink::Hold, QByteArray(1, 1));
            m_lastAck = t;
        }
    } else {
        if (m_wasPaused)
            send(FileLink::Hold, QByteArray(1, 0));
        if (m_fresh >= 64 || t - m_lastAck >= 1000)
            ack();
    }
    m_wasPaused = p;
}

void TorStream::end(End e)
{
    if (m_ended)
        return;
    m_ended = true;
    m_tick->stop();
    if (e != Done)
        m_s->abort();
    else
        m_s->disconnectFromHost();
    QTimer::singleShot(0, this, [this, e] { Q_EMIT finished(int(e)); });
}

// ------------------------------------------------------------------ self-test

bool TorFiles::selfTest()
{
    bool ok = true;
    auto check = [&ok](bool c, const char *what) {
        std::printf("tor files: %s: %s\n", what, c ? "ok" : "FAILED");
        ok = ok && c;
    };
    const QString onion = QString(55, QLatin1Char('a')) + QLatin1Char('d');
    check(onionOk(onion) && !onionOk(QString(56, QLatin1Char('a'))) && !onionOk(onion.toUpper()) && !onionOk(onion + QStringLiteral(".onion")),
          "onion addresses");
    const QByteArray req = socksRequest(onion, 4242);
    check(req.left(5) == QByteArray("\x05\x01\x00\x03\x3E", 5) && req.mid(5, 62) == (onion + QStringLiteral(".onion")).toLatin1()
              && quint8(req.at(67)) == 0x10 && quint8(req.at(68)) == 0x92,
          "SOCKS5 by name");
    const QString id = QStringLiteral("0123456789abcdef0123456789abcdef");
    check(torOk(torMessage(id, onion, 4242)) && FileLink::ok(torMessage(id, onion, 4242)) && !torOk(torMessage(id, onion, 0)), "the tor message");
    check(torLabel(10 * 1024 * 1024) == QLatin1String("Tor, slower") && torLabel(1200LL * 1024 * 1024) == QLatin1String("Tor, slower (about 21 min)"),
          "the card's Tor label");
    check(warning(kWarnBytes, true).isEmpty() && warning(2 * kWarnBytes, false).isEmpty()
              && warning(2 * kWarnBytes, true) == QLatin1String("It goes over Tor, which is slower. 2.0 GB may take about 36 min."),
          "the 1 GB question");

    QByteArray key(32, 0);
    for (int i = 0; i < 32; ++i)
        key[i] = char(i * 7 + 1);
    QTemporaryDir tmp;
    // One stream between a sender and a receiver here, the receiver [cutAfter] bytes in breaking it (0: never).
    auto run = [&](const QByteArray &data, FileLink::Sender &s, FileLink::Receiver &r, quint64 &sSeq, quint64 &rSeq, QFile &src, QFile &dst,
                   bool stranger) -> QPair<int, int> {
        QTcpServer server;
        server.listen(QHostAddress::LocalHost, 0);
        int sEnd = -1, rEnd = -1;
        QEventLoop loop;
        QObject::connect(&server, &QTcpServer::newConnection, &loop, [&] {
            QTcpSocket *c = server.nextPendingConnection();
            auto *st = new TorStream(c, true, key, id, data.size(), &s, nullptr, &src, &sSeq, &loop, 0, {}, 0, 3000);
            QObject::connect(st, &TorStream::finished, &loop, [&, st](int e) { sEnd = e; st->deleteLater(); if (rEnd >= 0 || stranger) loop.quit(); });
        });
        auto *c = new QTcpSocket;
        c->connectToHost(QHostAddress::LocalHost, server.serverPort());
        c->waitForConnected(3000);
        if (stranger) {
            QByteArray other(32, 9);
            c->write(frame(FileLink::seal(other, id, FileLink::Ack, 1, kSeqBase, QByteArray(12, 0))));
            c->setParent(&loop);
        } else {
            auto *rt = new TorStream(c, false, key, id, data.size(), nullptr, &r, &dst, &rSeq, &loop, 0, {}, 0, 3000);
            QObject::connect(rt, &TorStream::finished, &loop, [&, rt](int e) { rEnd = e; rt->deleteLater(); if (sEnd >= 0) loop.quit(); });
        }
        QTimer::singleShot(15000, &loop, &QEventLoop::quit);
        loop.exec();
        return {sEnd, rEnd};
    };
    {
        QByteArray data(300001, 0);
        for (int i = 0; i < data.size(); ++i)
            data[i] = char(QRandomGenerator::global()->generate());
        QFile src(tmp.filePath(QStringLiteral("src"))), dst(tmp.filePath(QStringLiteral("dst")));
        src.open(QIODevice::ReadWrite | QIODevice::Truncate);
        src.write(data);
        dst.open(QIODevice::ReadWrite | QIODevice::Truncate);
        dst.resize(data.size());
        // As after Nearby: the first 100 pieces came another way.
        dst.seek(0);
        dst.write(data.left(100 * FileLink::kChunk));
        FileLink::Sender s(FileLink::pieces(data.size()));
        FileLink::Receiver r(FileLink::pieces(data.size()));
        r.preload(100);
        quint64 sSeq = kSeqBase, rSeq = kSeqBase;
        const auto e = run(data, s, r, sSeq, rSeq, src, dst, false);
        dst.seek(0);
        check(e.first == TorStream::Done && e.second == TorStream::Done && dst.readAll() == data, "a file over one stream, going on from piece 100");
        FileLink::Sender s2(5);
        FileLink::Receiver r2(5);
        const auto x = run(QByteArray(5000, 0), s2, r2, sSeq, rSeq, src, dst, true);
        check(x.first == TorStream::Stranger, "a stranger is turned away");
    }
    return ok;
}
