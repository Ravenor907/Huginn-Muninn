#include "filelink.h"
#include "chat.h"
#include "call/directpath.h"
#include "call/turnclient.h"
#include "groupwire.h"
#include "photos.h"
#include "settings.h"
#include "vault.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QNetworkDatagram>
#include <QProcess>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>
#include <QUrl>
#include <QUuid>
#include <QUdpSocket>
#include <qendian.h>
#include <cstdio>
#include <memory>
#include <openssl/evp.h>

namespace
{
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }

QByteArray nonceOf(quint32 dir, quint64 seq)
{
    QByteArray n(12, 0);
    qToBigEndian(dir, n.data());
    qToBigEndian(seq, n.data() + 4);
    return n;
}

// AES-256-GCM with a 16-byte tag after the ciphertext.
QByteArray gcm(bool enc, const QByteArray &key, const QByteArray &nonce, const QByteArray &aad, const QByteArray &in, bool *ok)
{
    *ok = false;
    if (key.size() != 32 || (!enc && in.size() < 16))
        return {};
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    if (!c)
        return {};
    QByteArray out(in.size() + 16, 0);
    int len = 0, total = 0;
    const int body = enc ? int(in.size()) : int(in.size()) - 16;
    auto *k = reinterpret_cast<const unsigned char *>(key.constData());
    auto *iv = reinterpret_cast<const unsigned char *>(nonce.constData());
    auto *o = reinterpret_cast<unsigned char *>(out.data());
    bool good = (enc ? EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) : EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr)) == 1
        && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) == 1
        && (enc ? EVP_EncryptInit_ex(c, nullptr, nullptr, k, iv) : EVP_DecryptInit_ex(c, nullptr, nullptr, k, iv)) == 1;
    if (good && !aad.isEmpty())
        good = (enc ? EVP_EncryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(aad.constData()), int(aad.size()))
                    : EVP_DecryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(aad.constData()), int(aad.size()))) == 1;
    if (good && body > 0) {
        good = (enc ? EVP_EncryptUpdate(c, o, &len, reinterpret_cast<const unsigned char *>(in.constData()), body)
                    : EVP_DecryptUpdate(c, o, &len, reinterpret_cast<const unsigned char *>(in.constData()), body)) == 1;
        total = len;
    }
    if (good && !enc) {
        QByteArray tag = in.right(16);
        good = EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, tag.data()) == 1 && EVP_DecryptFinal_ex(c, o + total, &len) == 1;
        total += len;
    } else if (good) {
        good = EVP_EncryptFinal_ex(c, o + total, &len) == 1;
        total += len;
        good = good && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, o + total) == 1;
        total += 16;
    }
    EVP_CIPHER_CTX_free(c);
    if (!good)
        return {};
    out.resize(total);
    *ok = true;
    return out;
}

// The address candidates of the other side (5.8.2): srflx and host, literal IPs, never a local one.
QList<Stun::Address> directOf(const QJsonArray &a)
{
    QList<Stun::Address> out;
    for (const QJsonValue &v : a) {
        const QJsonObject c = v.toObject();
        const QString t = jstr(c, "t");
        if (t != QLatin1String("srflx") && t != QLatin1String("host"))
            continue;
        const double p = c.value(QStringLiteral("p")).toDouble(-1);
        QHostAddress h;
        if (p < 1 || p > 65535 || p != double(int(p)) || !h.setAddress(jstr(c, "a")) || !DirectPath::usable(h))
            continue;
        const Stun::Address d = CallNet::fromHost(h, quint16(p));
        if (d.valid() && !out.contains(d))
            out << d;
        if (out.size() >= 3)
            break;
    }
    return out;
}

// SHA-256 of a file in lowercase hex, off the main thread; empty when it can't be read.
void hashFile(QObject *ctx, const QString &path, std::function<void(QString)> done)
{
    auto result = std::make_shared<QString>();
    QThread *t = QThread::create([path, result] {
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly))
            return;
        QCryptographicHash h(QCryptographicHash::Sha256);
        if (h.addData(&f))
            *result = QString::fromLatin1(h.result().toHex());
    });
    QObject::connect(t, &QThread::finished, ctx, [t, result, done] {
        t->deleteLater();
        done(*result);
    });
    t->start();
}
}

const char FileLink::kCap[] = "f1";
const qint64 FileLink::kRelayMax = qint64(Photos::kInternetPieces) * Photos::kPiece;

// ------------------------------------------------------------------ what a file on its way says

int FileLink::percent(qint64 done, qint64 size)
{
    return size <= 0 ? 0 : int(qBound<qint64>(0, done, size) * 100 / size);
}

QString FileLink::statusText(const QVariantMap &t, const QString &who)
{
    const QString state = t.value(QStringLiteral("state")).toString(), note = t.value(QStringLiteral("note")).toString();
    const bool mine = t.value(QStringLiteral("mine")).toBool();
    const qint64 done = qint64(t.value(QStringLiteral("done")).toDouble()), size = qint64(t.value(QStringLiteral("size")).toDouble());
    if (state == QLatin1String("connecting"))
        return mine ? QStringLiteral("Getting ready") : QStringLiteral("Receiving");
    if (state == QLatin1String("offered"))
        return QStringLiteral("Waiting for %1 to accept").arg(who);
    if (state == QLatin1String("asking"))
        return QStringLiteral("Wants to send you this");
    if (state == QLatin1String("waiting"))
        return QStringLiteral("On mobile data. Download it?");
    if (state == QLatin1String("sending")) {
        if (!mine && !note.isEmpty())
            return note;
        return (mine ? QStringLiteral("Sending ") : QStringLiteral("Receiving ")) + QString::number(percent(done, size)) + QLatin1Char('%');
    }
    if (state == QLatin1String("done"))
        return mine ? QStringLiteral("Sent · Saved in their Downloads") : QStringLiteral("Saved to Downloads");
    if (state == QLatin1String("failed"))
        return note.isEmpty() ? QStringLiteral("Didn't go through") : note;
    return note;
}

QString FileLink::relayText(const QString &state, bool failed, double fraction)
{
    if (state == QLatin1String("delivered") || state == QLatin1String("read"))
        return QStringLiteral("Sent · Saved in their Downloads");
    if (state == QLatin1String("sent"))
        return QStringLiteral("Sent · Arrives when they're online");
    if (failed)
        return QStringLiteral("Not sent · Tap to retry");
    if (fraction >= 0)
        return QStringLiteral("Sending %1%").arg(int(qBound(0.0, fraction, 1.0) * 100));
    return QStringLiteral("Sending");
}

QString FileLink::tooBig()
{
    return QStringLiteral("Too big for the internet route: up to %1. Send it on the same Wi-Fi, or turn on Direct calls for each other.").arg(sizeText(kRelayMax));
}

QString FileLink::sizeText(qint64 b)
{
    if (b >= (qint64(1) << 30))
        return QString::number(b / 1073741824.0, 'f', 1) + QStringLiteral(" GB");
    if (b >= (qint64(1) << 20))
        return QString::number(b / 1048576.0, 'f', 1) + QStringLiteral(" MB");
    if (b >= 1024)
        return QString::number(b / 1024.0, 'f', 0) + QStringLiteral(" KB");
    return QStringLiteral("%1 bytes").arg(b);
}

QString FileLink::leftText(qint64 s)
{
    if (s < 60)
        return QStringLiteral("%1 s left").arg(qMax<qint64>(1, s));
    if (s < 3600)
        return QStringLiteral("%1 min left").arg((s + 59) / 60);
    return QStringLiteral("%1 h %2 min left").arg(s / 3600).arg((s % 3600 + 59) / 60);
}

QString FileLink::paceText(qint64 done, qint64 total, qint64 ms)
{
    if (ms < 1000 || done <= 0)
        return {};
    const qint64 perSec = done * 1000 / ms;
    if (perSec <= 0)
        return {};
    const QString speed = sizeText(perSec) + QStringLiteral("/s");
    if (total <= done)
        return speed;
    return speed + QStringLiteral(" · ") + leftText((total - done + perSec - 1) / perSec);
}

QString FileLink::iconFor(const QString &name)
{
    const QString ext = QFileInfo(name).suffix().toLower();
    static const QStringList pics{QStringLiteral("jpg"), QStringLiteral("jpeg"), QStringLiteral("png"), QStringLiteral("gif"), QStringLiteral("webp"),
                                  QStringLiteral("heic"), QStringLiteral("bmp")};
    static const QStringList videos{QStringLiteral("mp4"), QStringLiteral("m4v"), QStringLiteral("mov"), QStringLiteral("mkv"), QStringLiteral("webm"),
                                    QStringLiteral("avi")};
    static const QStringList sounds{QStringLiteral("mp3"), QStringLiteral("m4a"), QStringLiteral("aac"), QStringLiteral("ogg"), QStringLiteral("opus"),
                                    QStringLiteral("wav"), QStringLiteral("flac")};
    if (pics.contains(ext))
        return QStringLiteral("image");
    if (videos.contains(ext))
        return QStringLiteral("video");
    if (sounds.contains(ext))
        return QStringLiteral("headphones");
    return QStringLiteral("file");
}

// ------------------------------------------------------------------ the parts that are the same everywhere

FileLink::Sender::Sender(int pieces) : m_n(pieces), m_got(pieces) {}

QList<int> FileLink::Sender::due(qint64 t, qint64 rto)
{
    QList<int> out;
    for (auto it = m_sentAt.cbegin(); it != m_sentAt.cend(); ++it)
        if (t - it.value() >= rto)
            out << it.key();
    std::sort(out.begin(), out.end());
    qsizetype inflight = m_sentAt.size();
    while (m_next < m_n && inflight < kWindow) {
        out << m_next++;
        inflight++;
    }
    for (int i : std::as_const(out))
        m_sentAt.insert(i, t);
    return out;
}

void FileLink::Sender::mark(int i)
{
    if (i < 0 || i >= m_n || m_got.testBit(i))
        return;
    m_got.setBit(i);
    m_sentAt.remove(i);
    m_acked++;
}

void FileLink::Sender::ack(int below, quint64 bits)
{
    below = qBound(0, below, m_n);
    while (m_below < below)
        mark(m_below++);
    for (int b = 0; b < 64; ++b)
        if ((bits >> b) & 1)
            mark(below + b);
}

FileLink::Receiver::Receiver(int pieces) : m_n(pieces), m_got(pieces) {}

bool FileLink::Receiver::take(int i)
{
    if (i < 0 || i >= m_n || m_got.testBit(i))
        return false;
    m_got.setBit(i);
    m_have++;
    while (m_below < m_n && m_got.testBit(m_below))
        m_below++;
    return true;
}

void FileLink::Receiver::ack(int *below, quint64 *bits) const
{
    *below = m_below;
    *bits = 0;
    for (int b = 0; b < 64 && m_below + b < m_n; ++b)
        if (m_got.testBit(m_below + b))
            *bits |= quint64(1) << b;
}

int FileLink::pieces(qint64 size) { return int(qMax<qint64>(1, (size + kChunk - 1) / kChunk)); }

QByteArray FileLink::key(const QByteArray &shared, const QString &id)
{
    return QCryptographicHash::hash(QByteArray("muninn file v1") + shared + id.toUtf8(), QCryptographicHash::Sha256);
}

QByteArray FileLink::seal(const QByteArray &key, const QString &id, quint8 type, quint32 dir, quint64 seq, const QByteArray &payload)
{
    QByteArray head(9, 0);
    head[0] = char(type);
    qToBigEndian(seq, head.data() + 1);
    bool ok = false;
    const QByteArray c = gcm(true, key, nonceOf(dir, seq), head.left(1) + id.toUtf8(), payload, &ok);
    return ok ? head + c : QByteArray();
}

bool FileLink::open(const QByteArray &key, const QString &id, quint32 dir, const QByteArray &p, quint8 *type, QByteArray *payload)
{
    if (p.size() < 9 + 16)
        return false;
    const quint64 seq = qFromBigEndian<quint64>(p.constData() + 1);
    bool ok = false;
    const QByteArray plain = gcm(false, key, nonceOf(dir, seq), p.left(1) + id.toUtf8(), p.mid(9), &ok);
    if (!ok)
        return false;
    *type = quint8(p.at(0));
    *payload = plain;
    return true;
}

bool FileLink::nameOk(const QJsonValue &v)
{
    if (!v.isString() || v.toString().trimmed().isEmpty() || v.toString().size() > 200)
        return false;
    for (const QChar c : v.toString())
        if (c.unicode() < 0x20 || c == QLatin1Char('/') || c == QLatin1Char('\\'))
            return false;
    return true;
}

bool FileLink::ok(const QJsonObject &p)
{
    if (!GroupWire::isLogicalId(jstr(p, "id")))
        return false;
    const QString t = jstr(p, "t");
    if (t == QLatin1String("offer")) {
        const QJsonValue size = p.value(QStringLiteral("size")), sha = p.value(QStringLiteral("sha"));
        if (!nameOk(p.value(QStringLiteral("name"))))
            return false;
        const double s = size.toDouble(-1);
        if (!size.isDouble() || s < 1 || s > double(kMaxBytes) || s != double(qint64(s)))
            return false;
        if (!sha.isString() || sha.toString().size() != 64)
            return false;
        for (const QChar c : sha.toString())
            if (!((c >= QLatin1Char('0') && c <= QLatin1Char('9')) || (c >= QLatin1Char('a') && c <= QLatin1Char('f'))))
                return false;
        return p.value(QStringLiteral("e")).isString() && p.value(QStringLiteral("c")).isArray();
    }
    if (t == QLatin1String("accept"))
        return p.value(QStringLiteral("e")).isString() && p.value(QStringLiteral("c")).isArray();
    return t == QLatin1String("decline") || t == QLatin1String("done");
}

// ------------------------------------------------------------------ transfers

struct FileLink::Xfer {
    QString id, peer, name, path, sha, theirE, state, note;
    qint64 size = 0, done = 0;
    bool mine = false;
    quint32 dir = 0;                    // 0 from the sender, 1 from the receiver
    ChatCrypto::KeyPair eph;
    QByteArray key;
    QList<Stun::Address> theirs;
    QUdpSocket *sock = nullptr;
    StunProbe *probe = nullptr;
    QFile *file = nullptr;
    QTimer *tick = nullptr, *wait = nullptr;
    Stun::Address to;
    bool found = false, punching = false, moving = false, ended = false;
    int heardBack = 0, fresh = 0;
    quint64 seq = 1;
    qint64 until = 0, lastHeard = 0, lastAck = 0;
    std::unique_ptr<Sender> s;
    std::unique_ptr<Receiver> r;
    QSet<int> dropped;                  // self-test only
    std::function<void(QJsonArray)> afterStun;
};

FileLink::FileLink(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    saveDir = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    if (!chat)
        return;
    signal = [chat](const QString &peer, const QJsonObject &msg) { chat->sendEverywhere(peer, QStringLiteral("file"), msg, QString()); };
    peerHasF1 = [chat](const QString &peer) { return chat->hasCap(peer, QString::fromLatin1(kCap)); };
    waitsForAsk = [chat](const QString &peer) { return chat->hasCap(peer, QString::fromLatin1(RingWire::kWrapCap)); };
    relay = [chat](const QString &peer, const QByteArray &bytes, const QString &name) { return chat->sendFileBlob(peer, bytes, name); };
    nameOf = [chat](const QString &peer) { return chat->nameOf(peer); };
    connect(this, &FileLink::notice, chat, &Chat::notice);
}

FileLink::~FileLink()
{
    for (Xfer *x : std::as_const(m_x)) {
        delete x->file;
        delete x;
    }
}

QVariantList FileLink::transfers() const
{
    QVariantList out;
    for (const QVariantMap &m : m_list)
        out << m;
    return out;
}

void FileLink::put(Xfer *x, bool force)
{
    const QVariantMap m{{QStringLiteral("id"), x->id}, {QStringLiteral("peer"), x->peer}, {QStringLiteral("name"), x->name},
                        {QStringLiteral("size"), double(x->size)}, {QStringLiteral("mine"), x->mine}, {QStringLiteral("state"), x->state},
                        {QStringLiteral("done"), double(x->done)}, {QStringLiteral("note"), x->note},
                        {QStringLiteral("path"), x->mine || x->state == QLatin1String("done") ? x->path : QString()}};
    bool found = false;
    for (QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == x->id) {
            e = m;
            found = true;
        }
    if (!found)
        m_list << m;
    while (m_list.size() > 50)
        m_list.removeFirst();
    // Progress at most 4 times a second; anything else at once.
    if (force || now() - m_lastShown >= 250) {
        m_lastShown = now();
        Q_EMIT changed();
    }
}

void FileLink::dismiss(const QString &id)
{
    if (m_x.contains(id))
        return;
    for (int i = 0; i < m_list.size(); ++i)
        if (m_list.at(i).value(QStringLiteral("id")).toString() == id) {
            m_list.removeAt(i);
            Q_EMIT changed();
            return;
        }
}

QString FileLink::send(const QString &peer, const QString &path)
{
    const QFileInfo fi(path);
    if (!fi.isFile() || !fi.isReadable())
        return QStringLiteral("That file can't be read.");
    if (fi.size() < 1)
        return QStringLiteral("That file is empty.");
    if (fi.size() > kMaxBytes)
        return QStringLiteral("Files over the internet can be up to 4 GB.");
    QString name;
    for (const QChar c : fi.fileName())
        name += c.unicode() < 0x20 || c == QLatin1Char('/') || c == QLatin1Char('\\') ? QLatin1Char('_') : c;
    name = name.left(200).trimmed();
    if (name.isEmpty())
        name = QStringLiteral("file");
    // From here on its card in the chat says how it goes; nothing fails out of sight.
    if (!hasF1(peer)) {
        if (m_chat)
            m_chat->probeCaps(peer);   // their app may just not have said yet: it answers with its caps
        addFailed(peer, name, fi.size(), QStringLiteral("%1 needs the latest Muninn or Huginn for files.").arg(who(peer)), path);
        return QString();
    }
    if (!trusted || !trusted(peer)) {
        // No direct path without trust: a small file goes through the relays as sealed pieces (its own message).
        if (fi.size() <= kRelayMax && relay) {
            QFile f(path);
            if (!f.open(QIODevice::ReadOnly))
                return QStringLiteral("That file can't be read.");
            const QString why = relay(peer, f.readAll(), name);
            if (!why.isEmpty())
                addFailed(peer, name, fi.size(), why, path);
            return QString();
        }
        addFailed(peer, name, fi.size(), tooBig(), path, true);
        return QString();
    }
    auto *x = new Xfer;
    x->id = GroupWire::newLogicalId();
    x->peer = peer;
    x->name = name;
    x->path = path;
    x->size = fi.size();
    x->mine = true;
    x->dir = 0;
    x->state = QStringLiteral("connecting");
    x->note = QStringLiteral("Getting ready");
    m_x.insert(x->id, x);
    put(x, true);
    const QString id = x->id;
    hashFile(this, path, [this, id](const QString &sha) {
        Xfer *x = m_x.value(id);
        if (!x)
            return;
        if (sha.isEmpty()) {
            fail(x, QStringLiteral("That file can't be read."));
            return;
        }
        x->sha = sha;
        startSocket(x, [this, id](const QJsonArray &cands) {
            if (Xfer *x = m_x.value(id))
                sendOffer(x, cands);
        });
    });
    return QString();
}

// A new UDP socket for this transfer, and its candidates: what the STUN server saw and the global IPv6
// addresses (only 127.0.0.1 in the self-test).
void FileLink::startSocket(Xfer *x, std::function<void(QJsonArray)> ready)
{
    x->sock = new QUdpSocket(this);
    if (!x->sock->bind(testLocal ? QHostAddress(QHostAddress::LocalHost) : QHostAddress(QHostAddress::Any), 0)) {
        if (x->mine)
            fallBack(x, QStringLiteral("No internet connection that can go direct. Try on the same Wi-Fi."));
        else
            fail(x, QStringLiteral("No internet connection that can go direct."));
        return;
    }
    const QString id = x->id;
    connect(x->sock, &QUdpSocket::readyRead, this, [this, id] {
        if (Xfer *x = m_x.value(id))
            onDatagram(x);
    });
    const quint16 port = x->sock->localPort();
    if (testLocal) {
        ready(QJsonArray{QJsonObject{{QStringLiteral("t"), QStringLiteral("srflx")}, {QStringLiteral("a"), QStringLiteral("127.0.0.1")}, {QStringLiteral("p"), int(port)}}});
        return;
    }
    x->afterStun = ready;
    x->probe = new StunProbe(x->sock, this);
    connect(x->probe, &StunProbe::finished, this, [this, id, port] {
        Xfer *x = m_x.value(id);
        if (!x || !x->probe)
            return;
        QJsonArray c;
        const Stun::Address m = x->probe->mapped();
        if (m.valid() && DirectPath::usable(CallNet::toHost(m)))
            c.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("srflx")}, {QStringLiteral("a"), CallNet::toHost(m).toString()}, {QStringLiteral("p"), int(m.port)}});
        for (const QHostAddress &a : DirectPath::globalIpv6())
            c.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("host")}, {QStringLiteral("a"), a.toString()}, {QStringLiteral("p"), int(port)}});
        x->probe->deleteLater();
        x->probe = nullptr;
        const auto next = x->afterStun;
        x->afterStun = nullptr;
        next(c);
    });
    x->probe->start();
}

void FileLink::sendOffer(Xfer *x, const QJsonArray &cands)
{
    if (cands.isEmpty()) {
        fallBack(x, QStringLiteral("No internet connection that can go direct. Try on the same Wi-Fi."));
        return;
    }
    x->eph = ChatCrypto::newKeyPair();
    signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("offer")}, {QStringLiteral("id"), x->id}, {QStringLiteral("name"), x->name},
                                {QStringLiteral("size"), double(x->size)}, {QStringLiteral("sha"), x->sha},
                                {QStringLiteral("e"), QString::fromLatin1(x->eph.publicDer.toBase64())}, {QStringLiteral("c"), cands}});
    x->state = QStringLiteral("offered");
    x->note.clear();
    put(x, true);
    // Nothing back in time: a small file goes over the internet route (it arrives when they're back); a bigger
    // one waits as long as the other side may ask first (Data saver, or a big file), then says so.
    x->wait = new QTimer(this);
    x->wait->setSingleShot(true);
    const QString id = x->id;
    connect(x->wait, &QTimer::timeout, this, [this, id] {
        Xfer *x = m_x.value(id);
        if (x && x->state == QLatin1String("offered"))
            fallBack(x, QStringLiteral("They're offline. Try again when they're back."));
    });
    x->wait->start(answerMs(x->size));
}

void FileLink::onSignal(const QString &from, const QJsonObject &p)
{
    const QString id = jstr(p, "id"), t = jstr(p, "t");
    if (t == QLatin1String("offer")) {
        if (!trusted || !trusted(from)) {
            // Addresses are only swapped with someone trusted: say no, and the sender knows why.
            signal(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}});
            return;
        }
        if (m_x.contains(id))
            return;
        auto *x = new Xfer;
        x->id = id;
        x->peer = from;
        x->name = jstr(p, "name");
        x->size = qint64(p.value(QStringLiteral("size")).toDouble());
        x->sha = jstr(p, "sha");
        x->theirE = jstr(p, "e");
        x->theirs = directOf(p.value(QStringLiteral("c")).toArray());
        x->mine = false;
        x->dir = 1;
        m_x.insert(id, x);
        // Settings › Data saver: every file asks first (only when their app waits for the answer).
        const bool saving = !testLocal && Settings::dataSaverOn();
        if ((x->size >= kAutoBytes || saving) && (!waitsForAsk || waitsForAsk(from))) {
            // A big file asks first, as from anyone else. The sender gives up after kAskAnswerMs.
            x->state = QStringLiteral("asking");
            x->note = x->size >= kAutoBytes ? QStringLiteral("Wants to send you a big file. Accept or decline")
                                            : QStringLiteral("Wants to send you a file. Accept or decline");
            put(x, true);
            Q_EMIT notice(QStringLiteral("%1 wants to send you %2. Accept or decline it in your chat with them.").arg(who(from), x->name));
            x->wait = new QTimer(this);
            x->wait->setSingleShot(true);
            connect(x->wait, &QTimer::timeout, this, [this, id] {
                Xfer *x = m_x.value(id);
                if (x && x->state == QLatin1String("asking"))
                    fail(x, QStringLiteral("Not taken in time. Ask them to send it again."), false);
            });
            x->wait->start(kAskAnswerMs);
            return;
        }
        x->state = QStringLiteral("connecting");
        x->note = QStringLiteral("From %1").arg(who(from));
        put(x, true);
        Q_EMIT notice(QStringLiteral("%1 is sending you %2.").arg(who(from), x->name));
        acceptOffer(x);
        return;
    }
    Xfer *x = m_x.value(id);
    if (!x || x->peer != from || !x->mine)
        return;
    if (t == QLatin1String("accept") && x->state == QLatin1String("offered")) {
        const QByteArray shared = ChatCrypto::sharedKey(x->eph.privateDer, x->eph.publicDer, QByteArray::fromBase64(jstr(p, "e").toLatin1()));
        if (shared.isEmpty())
            return;
        x->key = key(shared, id);
        x->theirs = directOf(p.value(QStringLiteral("c")).toArray());
        if (x->wait)
            x->wait->stop();
        startPunch(x);
    } else if (t == QLatin1String("decline") && x->state == QLatin1String("offered")) {
        // They said no, or don't trust me with their address. A small file can still go through the relays.
        fallBack(x, QStringLiteral("They declined, or Direct calls is off on their side."));
    } else if (t == QLatin1String("decline")) {
        fail(x, QStringLiteral("They stopped it."));   // stopped on their side while it was on its way
    }
}

void FileLink::acceptOffer(Xfer *x)
{
    const QString id = x->id;
    startSocket(x, [this, id](const QJsonArray &cands) {
        Xfer *x = m_x.value(id);
        if (!x)
            return;
        x->eph = ChatCrypto::newKeyPair();
        const QByteArray shared = ChatCrypto::sharedKey(x->eph.privateDer, x->eph.publicDer, QByteArray::fromBase64(x->theirE.toLatin1()));
        if (shared.isEmpty()) {
            fail(x, QStringLiteral("That file didn't arrive. Ask them to send it again."));
            return;
        }
        x->key = key(shared, id);
        signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("accept")}, {QStringLiteral("id"), id},
                                    {QStringLiteral("e"), QString::fromLatin1(x->eph.publicDer.toBase64())}, {QStringLiteral("c"), cands}});
        // Written straight into Downloads as name.part, renamed once it all arrived and checked out.
        QDir().mkpath(saveDir);
        x->path = QDir(saveDir).filePath(freeName(x->name) + QStringLiteral(".part"));
        x->file = new QFile(x->path);
        if (!x->file->open(QIODevice::ReadWrite | QIODevice::Truncate) || !x->file->resize(x->size)) {
            fail(x, QStringLiteral("%1 couldn't be saved: is Downloads full?").arg(x->name));
            return;
        }
        startPunch(x);
    });
}

// Both sides send probes to every candidate of the other at once, which opens both routers. The first
// authentic probe that comes back says which address works.
void FileLink::startPunch(Xfer *x)
{
    if (x->theirs.isEmpty()) {
        if (x->mine)
            fallBack(x, QStringLiteral("Couldn't connect straight to them. Try on the same Wi-Fi."));
        else
            fail(x, QStringLiteral("Couldn't connect straight to them. It may come another way."));
        return;
    }
    x->state = QStringLiteral("connecting");
    x->note = QStringLiteral("Connecting straight to %1").arg(who(x->peer));
    put(x, true);
    x->punching = true;
    x->until = now() + kPunchMs;
    x->tick = new QTimer(this);
    const QString id = x->id;
    connect(x->tick, &QTimer::timeout, this, [this, id] {
        if (Xfer *x = m_x.value(id))
            x->punching ? punchTick(x) : dataTick(x);
    });
    x->tick->start(100);
    punchTick(x);
}

void FileLink::punchTick(Xfer *x)
{
    if (now() > x->until) {
        if (x->found)
            startData(x);
        else if (x->mine)
            fallBack(x, QStringLiteral("Couldn't connect straight to them. Try on the same Wi-Fi."));
        else
            fail(x, QStringLiteral("Couldn't connect straight to them. It may come another way."));
        return;
    }
    const QList<Stun::Address> targets = x->found ? QList<Stun::Address>{x->to} : x->theirs;
    for (const Stun::Address &a : targets)
        sendPacket(x, Probe, QByteArray(1, 1), a);
}

void FileLink::sendPacket(Xfer *x, quint8 type, const QByteArray &payload, const Stun::Address &to)
{
    const QByteArray p = seal(x->key, x->id, type, x->dir, x->seq++, payload);
    if (!p.isEmpty() && x->sock)
        x->sock->writeDatagram(p, CallNet::toHost(to), to.port);
}

bool FileLink::startData(Xfer *x)
{
    x->punching = false;
    x->moving = true;
    x->lastHeard = now();
    x->lastAck = 0;
    const int n = pieces(x->size);
    if (x->mine)
        x->s = std::make_unique<Sender>(n);
    else
        x->r = std::make_unique<Receiver>(n);
    if (x->mine) {
        x->file = new QFile(x->path);
        if (!x->file->open(QIODevice::ReadOnly)) {
            fail(x, QStringLiteral("That file can't be read any more."));
            return false;
        }
    }
    x->state = QStringLiteral("sending");
    x->note.clear();
    put(x, true);
    x->tick->setTimerType(Qt::PreciseTimer);
    x->tick->start(5);
    return true;
}

void FileLink::dataTick(Xfer *x)
{
    const qint64 t = now();
    if (t - x->lastHeard > kIdleMs) {
        fail(x, x->mine ? QStringLiteral("The connection dropped. Try again.") : QStringLiteral("The connection dropped. Ask them to send it again."));
        return;
    }
    if (!x->mine) {
        if (x->fresh >= 16 || t - x->lastAck >= 40)
            sendAck(x);
        return;
    }
    QByteArray chunk;
    for (int i : x->s->due(t)) {
        const qint64 at = qint64(i) * kChunk;
        const int len = int(qMin<qint64>(kChunk, x->size - at));
        if (!x->file->seek(at) || (chunk = x->file->read(len)).size() != len) {
            fail(x, QStringLiteral("That file can't be read any more."));
            return;
        }
        if (testDropEvery > 0 && i % testDropEvery == 3 && !x->dropped.contains(i)) {
            x->dropped.insert(i);   // the self-test loses this one on the way
            continue;
        }
        QByteArray payload(4, 0);
        qToBigEndian(quint32(i), payload.data());
        sendPacket(x, Data, payload + chunk, x->to);
    }
}

void FileLink::onDatagram(Xfer *x)
{
    while (x->sock && x->sock->hasPendingDatagrams()) {
        const QNetworkDatagram d = x->sock->receiveDatagram(2048);
        const QByteArray bytes = d.data();
        const QHostAddress from = d.senderAddress();
        const quint16 port = quint16(d.senderPort());
        if (x->probe && x->probe->take(bytes, from, port))
            continue;
        if (x->key.isEmpty())
            continue;
        quint8 type = 0;
        QByteArray payload;
        if (!open(x->key, x->id, 1 - x->dir, bytes, &type, &payload))
            continue;
        if (x->punching) {
            QHostAddress h = from;
            bool v4 = false;
            const quint32 a4 = h.toIPv4Address(&v4);
            if (v4)
                h = QHostAddress(a4);   // ::ffff:a.b.c.d from a dual-stack socket
            x->to = CallNet::fromHost(h, port);
            x->found = true;
            // Data or an ack already means the other side found this one too.
            if ((type != Probe || ++x->heardBack >= 3) && !startData(x))
                return;
            if (!x->moving)
                continue;
        }
        if (!x->moving)
            continue;
        x->lastHeard = now();
        if (x->mine) {
            if (type == Ack && payload.size() >= 12) {
                x->s->ack(int(qFromBigEndian<quint32>(payload.constData())), qFromBigEndian<quint64>(payload.constData() + 4));
                x->done = qMin(x->size, qint64(x->s->confirmedBelow()) * kChunk);
                put(x);
            }
            if (type == Done)
                x->s->ack(pieces(x->size), 0);
            if (x->s->done()) {
                x->done = x->size;
                finish(x);
                return;
            }
        } else if (type == Data && payload.size() > 4) {
            const int i = int(qFromBigEndian<quint32>(payload.constData()));
            const int n = pieces(x->size);
            const qint64 at = qint64(i) * kChunk;
            if (i >= 0 && i < n && payload.size() - 4 == qMin<qint64>(kChunk, x->size - at) && x->r->take(i)) {
                if (!x->file->seek(at) || x->file->write(payload.constData() + 4, payload.size() - 4) != payload.size() - 4) {
                    fail(x, QStringLiteral("%1 couldn't be saved: is Downloads full?").arg(x->name));
                    return;
                }
                if (++x->fresh >= 16)
                    sendAck(x);
            }
            if (x->r->done()) {
                receivedAll(x);
                return;
            }
        }
    }
}

void FileLink::sendAck(Xfer *x)
{
    int below = 0;
    quint64 bits = 0;
    x->r->ack(&below, &bits);
    QByteArray payload(12, 0);
    qToBigEndian(quint32(below), payload.data());
    qToBigEndian(bits, payload.data() + 4);
    sendPacket(x, Ack, payload, x->to);
    x->lastAck = now();
    x->fresh = 0;
    x->done = qMin(x->size, qint64(below) * kChunk);
    put(x);
}

// Every piece is in: say done, check the SHA-256, then give it its real name.
void FileLink::receivedAll(Xfer *x)
{
    sendAck(x);
    for (int i = 0; i < 3; ++i)
        sendPacket(x, Done, QByteArray(), x->to);
    x->moving = false;
    x->tick->stop();
    x->file->close();
    x->state = QStringLiteral("sending");
    x->note = QStringLiteral("Checking it");
    x->done = x->size;
    put(x, true);
    const QString id = x->id;
    hashFile(this, x->path, [this, id](const QString &sha) {
        Xfer *x = m_x.value(id);
        if (!x)
            return;
        if (sha != x->sha) {
            QFile::remove(x->path);
            fail(x, QStringLiteral("%1 arrived damaged, so it was thrown away.").arg(x->name));
            return;
        }
        // The name its .part was given, unless something took that name meanwhile.
        const QString planned = x->path.chopped(5);
        const QString final = QFile::exists(planned) ? QDir(saveDir).filePath(freeName(x->name)) : planned;
        if (!QFile::rename(x->path, final)) {
            fail(x, QStringLiteral("%1 couldn't be saved.").arg(x->name));
            return;
        }
        x->name = QFileInfo(final).fileName();
        x->path = final;
        x->note.clear();
        Q_EMIT notice(QStringLiteral("File from %1: %2 · Saved to Downloads").arg(who(x->peer), x->name));
        finish(x);
    });
}

QString FileLink::freeName(const QString &name) const
{
    const QDir dir(saveDir);
    if (!dir.exists(name) && !dir.exists(name + QStringLiteral(".part")))
        return name;
    const QFileInfo fi(name);
    const QString base = fi.completeBaseName().isEmpty() ? name : fi.completeBaseName();
    const QString ext = fi.suffix().isEmpty() || fi.completeBaseName().isEmpty() ? QString() : QStringLiteral(".") + fi.suffix();
    for (int i = 2; i < 1000; ++i) {
        const QString n = QStringLiteral("%1 (%2)%3").arg(base).arg(i).arg(ext);
        if (!dir.exists(n) && !dir.exists(n + QStringLiteral(".part")))
            return n;
    }
    return QStringLiteral("%1 %2").arg(QDateTime::currentMSecsSinceEpoch()).arg(name);
}

void FileLink::finish(Xfer *x)
{
    x->state = QStringLiteral("done");
    put(x, true);
    end(x);
}

// Lets go of everything a transfer holds; x is gone afterwards.
void FileLink::end(Xfer *x)
{
    x->ended = true;
    m_x.remove(x->id);
    if (x->tick)
        x->tick->deleteLater();
    if (x->wait)
        x->wait->deleteLater();
    if (x->sock)
        x->sock->deleteLater();
    if (x->probe)
        x->probe->deleteLater();
    delete x->file;
    delete x;
}

void FileLink::fail(Xfer *x, const QString &why, bool tell)
{
    x->state = QStringLiteral("failed");
    x->note = why;
    // A half-received file is never left behind.
    if (!x->mine && x->file) {
        x->file->close();
        QFile::remove(x->path);
    }
    put(x, true);
    end(x);
    if (tell)
        Q_EMIT notice(why);
}

void FileLink::accept(const QString &id)
{
    Xfer *x = m_x.value(id);
    if (!x || x->mine || x->state != QLatin1String("asking"))
        return;
    if (x->wait) {
        x->wait->stop();
        x->wait->deleteLater();
        x->wait = nullptr;
    }
    x->state = QStringLiteral("connecting");
    x->note = QStringLiteral("From %1").arg(who(x->peer));
    put(x, true);
    acceptOffer(x);
}

void FileLink::decline(const QString &id)
{
    Xfer *x = m_x.value(id);
    if (!x || x->mine || x->state != QLatin1String("asking"))
        return;
    signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}});
    end(x);
    dismiss(id);
}

void FileLink::cancel(const QString &id)
{
    Xfer *x = m_x.value(id);
    if (!x)
        return;
    // Theirs: the sender hears so; mine: the other side stops after 30 s of silence.
    if (!x->mine)
        signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}});
    fail(x, QStringLiteral("Stopped"), false);
}

void FileLink::fallBack(Xfer *x, const QString &why)
{
    QFile f(x->path);
    if (!x->mine || x->moving || x->size > kRelayMax || !relay || !f.open(QIODevice::ReadOnly)) {
        fail(x, why);
        return;
    }
    const QString id = x->id, peer = x->peer, name = x->name, path = x->path;
    const qint64 size = x->size;
    const QByteArray bytes = f.readAll();
    end(x);
    drop(id);   // the chat's own message takes over from here
    const QString r = relay(peer, bytes, name);
    if (!r.isEmpty())
        addFailed(peer, name, size, r, path, false, id);
}

void FileLink::drop(const QString &id)
{
    for (int i = 0; i < m_list.size(); ++i)
        if (m_list.at(i).value(QStringLiteral("id")).toString() == id) {
            m_list.removeAt(i);
            Q_EMIT changed();
            return;
        }
}

void FileLink::addFailed(const QString &peer, const QString &name, qint64 size, const QString &why, const QString &path, bool wifi, const QString &id)
{
    const QString key = id.isEmpty() ? GroupWire::newLogicalId() : id;
    drop(key);
    m_list << QVariantMap{{QStringLiteral("id"), key}, {QStringLiteral("peer"), peer}, {QStringLiteral("name"), name},
                          {QStringLiteral("size"), double(size)}, {QStringLiteral("mine"), true}, {QStringLiteral("state"), QStringLiteral("failed")},
                          {QStringLiteral("done"), 0.0}, {QStringLiteral("note"), why}, {QStringLiteral("path"), path}, {QStringLiteral("wifi"), wifi}};
    while (m_list.size() > 50)
        m_list.removeFirst();
    Q_EMIT changed();
}

void FileLink::relayIn(const QString &msgId, const QString &peer, const QString &name, int have, int n)
{
    const QVariantMap m{{QStringLiteral("id"), msgId}, {QStringLiteral("peer"), peer}, {QStringLiteral("name"), name},
                        {QStringLiteral("size"), double(qint64(n) * Photos::kPiece)}, {QStringLiteral("mine"), false},
                        {QStringLiteral("state"), QStringLiteral("sending")}, {QStringLiteral("done"), double(qint64(have) * Photos::kPiece)},
                        {QStringLiteral("note"), QString()}, {QStringLiteral("path"), QString()}};
    for (QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == msgId) {
            e = m;
            Q_EMIT changed();
            return;
        }
    m_list << m;
    while (m_list.size() > 50)
        m_list.removeFirst();
    Q_EMIT changed();
}

void FileLink::relayInDone(const QString &msgId, const QString &peer, const QString &name)
{
    QStringList gone;
    for (const QVariantMap &e : std::as_const(m_list)) {
        const QString id = e.value(QStringLiteral("id")).toString(), state = e.value(QStringLiteral("state")).toString();
        const bool same = !name.isEmpty() && !e.value(QStringLiteral("mine")).toBool() && e.value(QStringLiteral("peer")).toString() == peer
            && e.value(QStringLiteral("name")).toString() == name && (state == QLatin1String("asking") || state == QLatin1String("failed"));
        if (id == msgId || same)
            gone << id;
    }
    for (const QString &id : std::as_const(gone)) {
        if (Xfer *x = m_x.value(id); x && x->state == QLatin1String("asking"))
            end(x);   // the same file came another way: nothing to answer any more
        drop(id);
    }
}

QString FileLink::saveBytes(const QByteArray &bytes, const QString &name)
{
    if (bytes.isEmpty() || !nameOk(name))
        return {};
    QDir().mkpath(saveDir);
    const QString path = QDir(saveDir).filePath(freeName(name));
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size()) {
        f.remove();
        return {};
    }
    return path;
}

bool FileLink::canCancel(const QString &id) const
{
    const Xfer *x = m_x.value(id);
    return x && x->state != QLatin1String("done") && x->state != QLatin1String("failed") && x->state != QLatin1String("asking");
}

bool FileLink::canRetry(const QString &id) const
{
    if (m_x.contains(id))
        return false;
    for (const QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == id)
            return e.value(QStringLiteral("mine")).toBool() && e.value(QStringLiteral("state")).toString() == QLatin1String("failed")
                && !e.value(QStringLiteral("wifi")).toBool() && QFileInfo(e.value(QStringLiteral("path")).toString()).isFile();
    return false;
}

void FileLink::retry(const QString &id)
{
    if (!canRetry(id))
        return;
    QString peer, path;
    for (const QVariantMap &e : std::as_const(m_list))
        if (e.value(QStringLiteral("id")).toString() == id) {
            peer = e.value(QStringLiteral("peer")).toString();
            path = e.value(QStringLiteral("path")).toString();
        }
    drop(id);
    const QString why = send(peer, path);
    if (!why.isEmpty())
        Q_EMIT notice(why);
}

bool FileLink::openFile(const QString &path) const
{
    return QFileInfo(path).isFile() && QDesktopServices::openUrl(QUrl::fromLocalFile(path));
}

bool FileLink::showInFolder(const QString &path) const
{
    const QFileInfo fi(path);
    if (!fi.exists())
        return false;
#ifdef Q_OS_WIN
    // Explorer opens the folder with the file picked.
    return QProcess::startDetached(QStringLiteral("explorer.exe"), {QStringLiteral("/select,") + QDir::toNativeSeparators(fi.absoluteFilePath())});
#else
    // The desktop's file manager (inside the Flatpak through the OpenURI portal).
    return QDesktopServices::openUrl(QUrl::fromLocalFile(fi.absolutePath()));
#endif
}

// ------------------------------------------------------------------ files that came through the relays

QString FileLink::saveReceived(const QString &peer, const QString &msgId)
{
    if (!m_chat)
        return QStringLiteral("Not ready.");
    QString name, blobId;
    for (const QVariant &v : m_chat->m_private.value(peer)) {
        const QVariantMap m = v.toMap();
        if (m.value(QStringLiteral("id")).toString() == msgId) {
            name = m.value(QStringLiteral("file")).toString();
            blobId = m.value(QStringLiteral("photo")).toString();
        }
    }
    const QByteArray bytes = m_chat->blob(blobId);
    if (name.isEmpty() || bytes.isEmpty())
        return QStringLiteral("That file isn't here any more.");
    const QString path = saveBytes(bytes, name);
    if (path.isEmpty())
        return QStringLiteral("Couldn't save it. Is Downloads full?");
    // Its card shows Open and Folder from now on.
    m_chat->update(peer, msgId, [path](QVariantMap &m) { m.insert(QStringLiteral("saved"), path); });
    return QString();
}

// Chat's side: a small file as sealed pieces with "kind": "file" and its name (14.2), like a photo.
QString Chat::sendFileBlob(const QString &key, const QByteArray &bytes, const QString &name)
{
    if (!m_ready)
        return QStringLiteral("Not ready.");
    if (bytes.isEmpty() || bytes.size() > FileLink::kRelayMax)
        return FileLink::tooBig();
    const QString r = route(key);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_vault->write(QStringLiteral("photos/") + id, bytes);
    QVariantMap mine = message(id, key, username(), QStringLiteral("File: ") + name, true, r);
    mine.insert(QStringLiteral("state"), QStringLiteral("sending"));
    mine.insert(QStringLiteral("photo"), id);
    mine.insert(QStringLiteral("file"), name);
    mine.insert(QStringLiteral("size"), double(bytes.size()));
    m_tries.insert(id, Tries{0, QDateTime::currentMSecsSinceEpoch() + 5000});
    addPrivate(key, mine);
    if (r.isEmpty() && backupMailbox(mine).isEmpty()) {
        // Its message shows "Not sent · Tap to retry".
        update(key, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), true); });
        Q_EMIT notice(QStringLiteral("%1 can't be reached right now.").arg(nameOf(key)));
        return QString();
    }
    attempt(key, id);
    return QString();
}

// ------------------------------------------------------------------ self-test

bool FileLink::selfTest(const QJsonObject &v, const QByteArray &shared)
{
    int bad = 0;
    auto check = [&bad](bool ok, const QString &what) {
        if (!ok) {
            bad++;
            std::fprintf(stderr, "f1 FAIL: %s\n", qPrintable(what));
        }
    };
    // What a file on its way says: the same words as Muninn's FileText (FileTextTest).
    {
        auto t = [](const char *state, bool mine, double done, double size, const char *note = "") {
            return QVariantMap{{QStringLiteral("state"), QString::fromLatin1(state)}, {QStringLiteral("mine"), mine}, {QStringLiteral("done"), done},
                               {QStringLiteral("size"), size}, {QStringLiteral("note"), QString::fromUtf8(note)}};
        };
        const QString ana = QStringLiteral("Ana");
        check(statusText(t("connecting", true, 0, 10), ana) == QLatin1String("Getting ready"), QStringLiteral("text: getting ready"));
        check(statusText(t("offered", true, 0, 10), ana) == QLatin1String("Waiting for Ana to accept"), QStringLiteral("text: waiting to accept"));
        check(statusText(t("sending", true, 42, 100), ana) == QLatin1String("Sending 42%"), QStringLiteral("text: sending"));
        check(statusText(t("sending", false, 42, 100), ana) == QLatin1String("Receiving 42%"), QStringLiteral("text: receiving"));
        check(statusText(t("sending", false, 100, 100, "Checking it"), ana) == QLatin1String("Checking it"), QStringLiteral("text: checking"));
        check(statusText(t("done", true, 10, 10), ana) == QStringLiteral("Sent · Saved in their Downloads"), QStringLiteral("text: sent"));
        check(statusText(t("done", false, 10, 10), ana) == QLatin1String("Saved to Downloads"), QStringLiteral("text: saved"));
        check(statusText(t("asking", false, 0, 10), ana) == QLatin1String("Wants to send you this"), QStringLiteral("text: asking"));
        check(statusText(t("failed", true, 0, 10), ana) == QStringLiteral("Didn't go through"), QStringLiteral("text: failed"));
        check(statusText(t("failed", true, 0, 10, "They're offline."), ana) == QStringLiteral("They're offline."), QStringLiteral("text: failed why"));
        check(percent(5, 0) == 0 && percent(-3, 10) == 0 && percent(20, 10) == 100 && percent(999, 1000) == 99, QStringLiteral("percent"));
        check(relayText(QStringLiteral("sending"), false, -1) == QLatin1String("Sending"), QStringLiteral("relay: sending"));
        check(relayText(QStringLiteral("sending"), false, 0.5) == QLatin1String("Sending 50%"), QStringLiteral("relay: half"));
        check(relayText(QStringLiteral("sending"), true, 0.5) == QStringLiteral("Not sent · Tap to retry"), QStringLiteral("relay: failed"));
        check(relayText(QStringLiteral("sent"), false, 1) == QStringLiteral("Sent · Arrives when they're online"), QStringLiteral("relay: sent"));
        check(relayText(QStringLiteral("delivered"), false, -1) == QStringLiteral("Sent · Saved in their Downloads"), QStringLiteral("relay: delivered"));
        check(tooBig().contains(QLatin1String("2.0 MB")), QStringLiteral("too big names the limit"));
        const qint64 mb = 1024 * 1024;
        check(paceText(0, 100, 5000).isEmpty() && paceText(10, 100, 500).isEmpty(), QStringLiteral("pace: too early"));
        check(paceText(10 * mb, 50 * mb, 5000) == QStringLiteral("2.0 MB/s · 20 s left"), QStringLiteral("pace: ") + paceText(10 * mb, 50 * mb, 5000));
        check(paceText(10 * mb, 10 * mb, 5000) == QLatin1String("2.0 MB/s"), QStringLiteral("pace: done"));
        check(leftText(0) == QLatin1String("1 s left") && leftText(59) == QLatin1String("59 s left") && leftText(60) == QLatin1String("1 min left")
                  && leftText(61) == QLatin1String("2 min left") && leftText(3900) == QLatin1String("1 h 5 min left"), QStringLiteral("time left"));
        check(iconFor(QStringLiteral("a.JPG")) == QLatin1String("image") && iconFor(QStringLiteral("clip.mp4")) == QLatin1String("video")
                  && iconFor(QStringLiteral("game.apk")) == QLatin1String("file"), QStringLiteral("icons"));
        check(answerMs(kRelayMax) == kRelayAfterMs && answerMs(kRelayMax + 1) == kAskAnswerMs, QStringLiteral("answer wait"));
        // Cards: one that can't go says why and can be closed; one coming over the internet route goes once it's in.
        FileLink l(nullptr);
        l.addFailed(QStringLiteral("P"), QStringLiteral("movie.mkv"), 900 * mb, tooBig(), QString(), true, QStringLiteral("card"));
        check(l.transfers().size() == 1 && !l.canRetry(QStringLiteral("card")) && !l.canCancel(QStringLiteral("card")), QStringLiteral("a failed card"));
        l.dismiss(QStringLiteral("card"));
        check(l.transfers().isEmpty(), QStringLiteral("close a card"));
        l.relayIn(QStringLiteral("m1"), QStringLiteral("P"), QStringLiteral("a.zip"), 3, 4);
        check(statusText(l.transfers().value(0).toMap(), ana) == QLatin1String("Receiving 75%"), QStringLiteral("receiving over the relays"));
        l.relayInDone(QStringLiteral("m1"), QStringLiteral("P"), QStringLiteral("a.zip"));
        check(l.transfers().isEmpty(), QStringLiteral("its message takes over"));
    }

    const QJsonObject f = v.value(QStringLiteral("file")).toObject();
    const QString id = jstr(f, "id");
    const QByteArray k = key(shared, id);
    check(k.toHex() == jstr(f, "keyHex").toLatin1(), QStringLiteral("file key"));
    for (const QJsonValue &x : f.value(QStringLiteral("pieces")).toArray())
        check(pieces(qint64(x.toArray().at(0).toDouble())) == x.toArray().at(1).toInt(), QStringLiteral("pieces"));
    for (const QJsonValue &x : f.value(QStringLiteral("packets")).toArray()) {
        const QJsonObject c = x.toObject();
        const QByteArray payload = QByteArray::fromHex(jstr(c, "payloadHex").toLatin1()), want = QByteArray::fromHex(jstr(c, "packetHex").toLatin1());
        const quint32 dir = quint32(c.value(QStringLiteral("dir")).toInt());
        const QByteArray p = seal(k, id, quint8(c.value(QStringLiteral("type")).toInt()), dir, quint64(c.value(QStringLiteral("seq")).toDouble()), payload);
        check(p == want, QStringLiteral("seal type %1").arg(c.value(QStringLiteral("type")).toInt()));
        quint8 t = 0;
        QByteArray back;
        check(open(k, id, dir, want, &t, &back) && t == c.value(QStringLiteral("type")).toInt() && back == payload, QStringLiteral("open"));
        check(!open(k, id, 1 - dir, want, &t, &back), QStringLiteral("the other direction must not open"));
        QByteArray changed = want;
        changed[changed.size() - 1] = char(changed.at(changed.size() - 1) ^ 1);
        check(!open(k, id, dir, changed, &t, &back), QStringLiteral("a changed packet must not open"));
    }
    for (const QJsonValue &x : f.value(QStringLiteral("acks")).toArray()) {
        const QJsonObject c = x.toObject();
        Receiver r(c.value(QStringLiteral("n")).toInt());
        for (const QJsonValue &h : c.value(QStringLiteral("have")).toArray())
            r.take(h.toInt());
        int below = 0;
        quint64 bits = 0;
        r.ack(&below, &bits);
        check(below == c.value(QStringLiteral("below")).toInt() && bits == quint64(c.value(QStringLiteral("bits")).toDouble()), QStringLiteral("ack"));
    }
    for (const QJsonValue &x : f.value(QStringLiteral("good")).toArray())
        check(ok(x.toObject()), QStringLiteral("should pass ") + QString::fromUtf8(QJsonDocument(x.toObject()).toJson(QJsonDocument::Compact)));
    for (const QJsonValue &x : f.value(QStringLiteral("bad")).toArray())
        check(!ok(x.toObject()), QStringLiteral("should fail ") + QString::fromUtf8(QJsonDocument(x.toObject()).toJson(QJsonDocument::Compact)));

    // The window and the acks: every piece arrives even when some are lost.
    {
        Sender s(500);
        Receiver r(500);
        qint64 t = 0;
        int rounds = 0;
        check(s.due(0).size() == kWindow, QStringLiteral("the window"));
        check(s.due(10).isEmpty(), QStringLiteral("nothing late yet"));
        Sender s2(500);
        while (!s2.done() && rounds < 10000) {
            for (int i : s2.due(t))
                if (i % 7 != 3 || t > 2000)
                    r.take(i);
            int below = 0;
            quint64 bits = 0;
            r.ack(&below, &bits);
            s2.ack(below, bits);
            t += 100;
            rounds++;
        }
        check(s2.done() && r.done(), QStringLiteral("every piece arrives"));
    }

    // A whole transfer between two of these on this computer, with every 10th piece lost at first.
    {
        QTemporaryDir tmp;
        DirectPath::g_testLocal = true;
        FileLink a(nullptr), b(nullptr);
        a.testLocal = b.testLocal = true;
        a.testDropEvery = 10;
        a.saveDir = tmp.filePath(QStringLiteral("a"));
        b.saveDir = tmp.filePath(QStringLiteral("b"));
        a.peerHasF1 = b.peerHasF1 = [](const QString &) { return true; };
        a.trusted = b.trusted = [](const QString &) { return true; };
        a.signal = [&b](const QString &, const QJsonObject &m) { QTimer::singleShot(0, &b, [&b, m] { if (ok(m)) b.onSignal(QStringLiteral("A"), m); }); };
        b.signal = [&a](const QString &, const QJsonObject &m) { QTimer::singleShot(0, &a, [&a, m] { if (ok(m)) a.onSignal(QStringLiteral("B"), m); }); };
        QByteArray data(300 * 1000 + 17, 0);
        for (int i = 0; i < data.size(); ++i)
            data[i] = char(QRandomGenerator::global()->generate());
        const QString src = tmp.filePath(QStringLiteral("report.bin"));
        QFile out(src);
        check(out.open(QIODevice::WriteOnly) && out.write(data) == data.size(), QStringLiteral("write the test file"));
        out.close();
        check(a.send(QStringLiteral("B"), src).isEmpty(), QStringLiteral("send starts"));
        QEventLoop loop;
        QTimer give;
        give.setSingleShot(true);
        QObject::connect(&give, &QTimer::timeout, &loop, &QEventLoop::quit);
        auto ended = [](const FileLink &l) {
            const QVariantList t = l.transfers();
            return !t.isEmpty() && t.last().toMap().value(QStringLiteral("state")).toString() != QLatin1String("connecting")
                && t.last().toMap().value(QStringLiteral("state")).toString() != QLatin1String("offered")
                && t.last().toMap().value(QStringLiteral("state")).toString() != QLatin1String("sending");
        };
        auto watch = [&] { if (ended(a) && ended(b)) loop.quit(); };
        QObject::connect(&a, &FileLink::changed, &loop, watch);
        QObject::connect(&b, &FileLink::changed, &loop, watch);
        give.start(40000);
        loop.exec();
        const QVariantList ta = a.transfers(), tb = b.transfers();
        const QString sa = ta.isEmpty() ? QString() : ta.last().toMap().value(QStringLiteral("state")).toString();
        const QString sb = tb.isEmpty() ? QString() : tb.last().toMap().value(QStringLiteral("state")).toString();
        check(sa == QLatin1String("done"), QStringLiteral("the sender finished (%1: %2)").arg(sa, ta.isEmpty() ? QString() : ta.last().toMap().value(QStringLiteral("note")).toString()));
        check(sb == QLatin1String("done"), QStringLiteral("the receiver finished (%1: %2)").arg(sb, tb.isEmpty() ? QString() : tb.last().toMap().value(QStringLiteral("note")).toString()));
        QFile got(QDir(b.saveDir).filePath(QStringLiteral("report.bin")));
        check(got.open(QIODevice::ReadOnly) && got.readAll() == data, QStringLiteral("the file arrived whole"));
        check(!QFile::exists(QDir(b.saveDir).filePath(QStringLiteral("report.bin.part"))), QStringLiteral("no .part left behind"));
        DirectPath::g_testLocal = false;
    }
    std::fprintf(stderr, "f1: %s\n", bad == 0 ? "all checks passed" : "FAILED");
    return bad == 0;
}
