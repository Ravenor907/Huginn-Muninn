#include "filelink.h"
#include "busy.h"
#include "chat.h"
#include "call/directpath.h"
#include "call/turnclient.h"
#include "groupwire.h"
#include "localsend.h"
#include "msglimits.h"
#include "photos.h"
#include "settings.h"
#include "sharing.h"
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
#include <QStorageInfo>
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

// Cap f2 (27.1): the "lan" candidate of the other side, paired with where they already are on this Wi-Fi (host, from
// a device whose chat key proved its certificate). The candidate itself never carries an address. Invalid when either
// is missing.
Stun::Address lanTarget(const QString &host, const QJsonArray &a)
{
    QHostAddress h;
    if (host.isEmpty() || !h.setAddress(host))
        return {};
    for (const QJsonValue &v : a) {
        const QJsonObject c = v.toObject();
        if (jstr(c, "t") != QLatin1String("lan"))
            continue;
        const double p = c.value(QStringLiteral("p")).toDouble(-1);
        if (p < 1 || p > 65535 || p != double(int(p)))
            continue;
        return CallNet::fromHost(h, quint16(p));
    }
    return {};
}

QJsonObject lanCandidate(quint16 port) { return QJsonObject{{QStringLiteral("t"), QStringLiteral("lan")}, {QStringLiteral("p"), int(port)}}; }

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
    t->setObjectName(QStringLiteral("huginn-file-hash"));
    QObject::connect(t, &QThread::finished, ctx, [t, result, done] {
        t->deleteLater();
        done(*result);
    });
    t->start();
}
}

const char FileLink::kCap[] = "f1";
const char FileLink::kResumeCap[] = "f3";
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
    if (state == QLatin1String("sending") && t.value(QStringLiteral("paused")).toBool())
        return QStringLiteral("Paused");
    if (state == QLatin1String("sending") && t.value(QStringLiteral("held")).toBool())
        return QStringLiteral("Paused on their side");
    if (state == QLatin1String("sending")) {
        if (!note.isEmpty())
            return note;   // "Checking it", or (cap f3) "Waiting for Ana to come back"
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
    return QStringLiteral("Too big to go without Direct: up to %1. Turn on Direct calls and files with each other, or send it on the same Wi-Fi.").arg(sizeText(kRelayMax));
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
        // Cap f3: pieces the receiver already had from an earlier try are acked before they go.
        if (m_got.testBit(m_next)) {
            m_next++;
            continue;
        }
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

void FileLink::Receiver::preload(int below)
{
    for (int i = 0; i < qBound(0, below, m_n); ++i)
        take(i);
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
    // Cap f2 (27.2): a group's file names its group.
    if (p.contains(QStringLiteral("g")) && !GroupWire::isGroupId(p.value(QStringLiteral("g")).toString()))
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
    // Cap f2 (section 27): the group it's for, its route ("direct" or "internet", empty until known), where theirs is
    // saved, and the same Wi-Fi: lan is their address there (never sent), lanOnly when no internet address is swapped.
    QString group, route, folder;
    Stun::Address lan;
    bool lanOnly = false;
    // Paused here, or held on their side (Hold, 27.5).
    bool paused = false, held = false;
    qint64 started = 0, pausedAt = 0, pausedFor = 0, lastHold = 0;
    // Cap f3: bytes already there when it began (resumed), quiet (nothing heard for kQuietMs) and the last keepalive,
    // and a card that needs Direct calls and files with them.
    qint64 base = 0, lastKeep = 0;
    bool quiet = false, direct = false, baseSet = false;
    int resumeBelow = -1;               // theirs: the pieces a kept part already has, -1 when it starts fresh
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
    allArrive = [chat](const QString &peer) { return chat->inFamilyGroup(peer); };
    relay = [chat](const QString &peer, const QByteArray &bytes, const QString &name) { return chat->sendFileBlob(peer, bytes, name); };
    nameOf = [chat](const QString &peer) { return chat->nameOf(peer); };
    // Cap f2 (section 27): the same Wi-Fi first, group files and their rules, "Always from this person".
    lanHost = [chat](const QString &peer) { return chat->m_net ? QUrl(chat->m_net->baseFor(peer, true)).host() : QString(); };
    hasF2 = [chat](const QString &peer) { return chat->hasCap(peer, QLatin1String(Sharing::kCap)); };
    resumes = [chat](const QString &peer) { return chat->hasCap(peer, QLatin1String(kResumeCap)); };
    groupRefusal = [chat](const QString &g, const QString &from, qint64 size) { return chat->groupFileRefusal(g, from, size); };
    groupArrives = [chat](const QString &g, qint64 size) { return chat->groupFilesArrive(g, size); };
    groupName = [chat](const QString &g) { return chat->groupName(g); };
    groupMembers = [chat](const QString &g) { return chat->groupFileMembers(g); };
    myKey = [chat] { return chat->myKey(); };
    alwaysFrom = [chat](const QString &peer) { return chat->filesAlways(peer); };
    setAlways = [chat](const QString &peer) { chat->setFilesAlways(peer, true); };
    isContact = [chat](const QString &peer) { return chat->isContact(peer); };
    // A file of mine going as a message: its card follows the message (how far, then sent or not).
    connect(chat, &Chat::uploadsChanged, this, &FileLink::relayTick);
    connect(chat, &Chat::changed, this, &FileLink::relayTick);
    connect(this, &FileLink::notice, chat, &Chat::notice);
    // Labs › Performance check: the transfers going on at the end of a check (no names, no file names).
    Busy::addStatus(this, [this] {
        QStringList out;
        for (const Xfer *x : std::as_const(m_x))
            out << QStringLiteral("file transfer: %1, %2, %3%, %4").arg(x->mine ? QStringLiteral("sending") : QStringLiteral("receiving"), x->state)
                       .arg(x->size > 0 ? int(100 * x->done / x->size) : 0)
                       .arg(x->moving ? QStringLiteral("moving") : x->punching ? QStringLiteral("finding a way") : QStringLiteral("waiting"));
        if (out.isEmpty())
            out << QStringLiteral("file transfers: none");
        return out;
    });
}

FileLink::~FileLink()
{
    // Kept parts go when Huginn closes (Transfers is this run only).
    for (const Partial &p : std::as_const(m_partial))
        QFile::remove(p.path);
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

namespace
{
// One card's fields (FileLink::transfers): every card has all of them.
QVariantMap cardOf(const QString &id, const QString &peer, const QString &group, const QString &chat, const QString &name, qint64 size, bool mine,
                   const QString &state, qint64 done, const QString &note, const QString &path)
{
    return {{QStringLiteral("id"), id}, {QStringLiteral("peer"), peer}, {QStringLiteral("group"), group},
            {QStringLiteral("where"), group.isEmpty() ? peer : QStringLiteral("g:") + group}, {QStringLiteral("chat"), chat},
            {QStringLiteral("name"), name}, {QStringLiteral("size"), double(size)}, {QStringLiteral("mine"), mine},
            {QStringLiteral("state"), state}, {QStringLiteral("done"), double(done)}, {QStringLiteral("note"), note},
            {QStringLiteral("path"), path}, {QStringLiteral("route"), QString()}, {QStringLiteral("pace"), QString()},
            {QStringLiteral("paused"), false}, {QStringLiteral("held"), false}, {QStringLiteral("kind"), Sharing::kindOf(name)},
            {QStringLiteral("folder"), QString()}, {QStringLiteral("relay"), false}, {QStringLiteral("wifi"), false}, {QStringLiteral("direct"), false},
            {QStringLiteral("time"), double(QDateTime::currentMSecsSinceEpoch())}};
}
}

QString FileLink::chatOf(const QString &peer, const QString &group) const
{
    if (group.isEmpty())
        return who(peer);
    const QString n = groupName ? groupName(group) : QString();
    return n.isEmpty() ? QStringLiteral("Group") : n;
}

QString FileLink::folderOf(const QString &peer, const QString &group) const
{
    if (!nameOf)
        return saveDir;   // the self-test: straight into its own folder
    return Sharing::folderFor(base(), QStringLiteral("Huginn"), chatOf(peer, group));
}

QString FileLink::base() const
{
    // Settings › Files › Save files to (the same folder LocalSend saves in): files go in <it>/Huginn/<chat>.
    if (!m_chat)
        return saveDir;
    const QString v = Settings::saved(QStringLiteral("transferFolder"), saveDir).toString();
    return v.isEmpty() ? saveDir : v;
}

QString FileLink::shownFolder(const QString &dir) const
{
    return QDir::toNativeSeparators(QFileInfo(base()).dir().relativeFilePath(dir));
}

int FileLink::activeCount() const
{
    int n = 0;
    for (const QVariantMap &e : m_list) {
        const QString st = e.value(QStringLiteral("state")).toString();
        if (st == QLatin1String("connecting") || st == QLatin1String("offered") || st == QLatin1String("asking") || st == QLatin1String("sending"))
            n++;
    }
    return n;
}

int FileLink::runningCount() const
{
    int n = 0;
    for (const Xfer *x : std::as_const(m_x))
        if (x->state == QLatin1String("connecting") || x->state == QLatin1String("sending"))
            n++;
    return n;
}

QString FileLink::closeWarning() const
{
    QStringList moving, waiting;
    for (const Xfer *x : std::as_const(m_x)) {
        const QString pct = QString::number(percent(x->done, x->size)) + QLatin1Char('%');
        if (x->state == QLatin1String("connecting") || x->state == QLatin1String("sending"))
            moving << (x->mine ? QStringLiteral("Sending %1 to %2 (%3).") : QStringLiteral("Receiving %1 from %2 (%3)."))
                          .arg(x->name, chatOf(x->peer, x->group), pct);
        else if (x->state == QLatin1String("offered"))
            waiting << QStringLiteral("%1 is waiting for %2 to accept.").arg(x->name, who(x->peer));
    }
    if (moving.size() > 2)
        moving = QStringList{moving.first(), QStringLiteral("And %1 more.").arg(moving.size() - 1)};
    if (!moving.isEmpty())
        return moving.join(QLatin1Char(' ')) + QStringLiteral(" Closing stops it.");
    if (!waiting.isEmpty())
        return (waiting.size() > 1 ? QStringLiteral("%1 files are waiting to be accepted.").arg(waiting.size()) : waiting.first())
            + QStringLiteral(" Closing cancels the offer.");
    return {};
}

bool FileLink::needsDirect(const QString &id) const
{
    for (const QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == id)
            return e.value(QStringLiteral("direct")).toBool() && e.value(QStringLiteral("state")).toString() == QLatin1String("failed");
    return false;
}

bool FileLink::expects(const QString &peer, const QString &id) const
{
    const Xfer *x = m_x.value(id);
    return x && x->peer == peer;
}

void FileLink::put(Xfer *x, bool force)
{
    QVariantMap m = cardOf(x->id, x->peer, x->group, chatOf(x->peer, x->group), x->name, x->size, x->mine, x->state, x->done, x->note,
                           x->mine || x->state == QLatin1String("done") ? x->path : QString());
    if (!x->route.isEmpty())
        m.insert(QStringLiteral("route"), Sharing::routeLabel(x->route));
    if (x->state == QLatin1String("sending") && x->started > 0 && !x->paused && !x->held)
        m.insert(QStringLiteral("pace"), paceText(x->done - x->base, x->size - x->base, now() - x->started - x->pausedFor));
    m.insert(QStringLiteral("paused"), x->paused);
    m.insert(QStringLiteral("direct"), x->direct);
    m.insert(QStringLiteral("held"), x->held);
    if (!x->mine)
        m.insert(QStringLiteral("folder"), shownFolder(x->folder.isEmpty() ? folderOf(x->peer, x->group) : x->folder));
    bool found = false;
    for (QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == x->id) {
            m.insert(QStringLiteral("time"), e.value(QStringLiteral("time")));
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

QString FileLink::partialFor(const Xfer *x) const
{
    if (!hasF3(x->peer))
        return {};
    for (auto it = m_partial.cbegin(); it != m_partial.cend(); ++it)
        if (it->peer == x->peer && it->sha == x->sha && it->size == x->size && it->group == x->group && QFileInfo(it->path).isFile())
            return it.key();
    return {};
}

void FileLink::dropPartial(const QString &id)
{
    const auto it = m_partial.constFind(id);
    if (it == m_partial.cend())
        return;
    QFile::remove(it->path);
    m_partial.erase(it);
}

void FileLink::dismiss(const QString &id)
{
    if (m_x.contains(id))
        return;
    dropPartial(id);   // closing a stopped file of theirs lets go of what came of it
    for (int i = 0; i < m_list.size(); ++i)
        if (m_list.at(i).value(QStringLiteral("id")).toString() == id) {
            m_list.removeAt(i);
            Q_EMIT changed();
            return;
        }
}

QString FileLink::send(const QString &peer, const QString &path, const QString &group)
{
    const QFileInfo fi(path);
    if (!fi.isFile() || !fi.isReadable())
        return QStringLiteral("That file can't be read.");
    if (fi.size() < 1)
        return QStringLiteral("That file is empty.");
    if (fi.size() > kMaxBytes)
        return QStringLiteral("Files can be up to 4 GB.");
    QString name;
    for (const QChar c : fi.fileName())
        name += c.unicode() < 0x20 || c == QLatin1Char('/') || c == QLatin1Char('\\') ? QLatin1Char('_') : c;
    name = name.left(200).trimmed();
    if (name.isEmpty())
        name = QStringLiteral("file");
    // From here on its card says how it goes; nothing fails out of sight.
    if (!hasF1(peer) || (!group.isEmpty() && !(hasF2 && hasF2(peer)))) {
        if (m_chat)
            m_chat->probeCaps(peer);   // their app may just not have said yet: it answers with its caps
        addFailed(peer, name, fi.size(), QStringLiteral("%1 needs the latest Muninn or Huginn for %2.").arg(who(peer), group.isEmpty() ? QStringLiteral("files")
                                                                                                                                     : QStringLiteral("files in groups")),
                  path, false, QString(), group);
        return QString();
    }
    // The route (27.1): the same Wi-Fi first (their app must know the "lan" candidate), then straight over the
    // internet with trust, then the relays for a small file.
    const bool trust = trusted && trusted(peer);
    const bool sameWifi = !lanOf(peer).isEmpty() && hasF2 && hasF2(peer);
    const QString route = Sharing::route(sameWifi, trust, fi.size(), kRelayMax);
    if (route == QLatin1String("relay")) {
        // A group's file goes over the relays only to a contact, into the chat with them.
        if (!group.isEmpty() && !(isContact && isContact(peer))) {
            addFailed(peer, name, fi.size(), QStringLiteral("Can't reach %1 straight. Files in a group go when you're on the same Wi-Fi, or with Direct calls and files on for each other.").arg(who(peer)),
                      path, false, QString(), group, !trust);
            return QString();
        }
        if (!relay) {
            addFailed(peer, name, fi.size(), tooBig(), path, true, QString(), group, !trust);
            return QString();
        }
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly))
            return QStringLiteral("That file can't be read.");
        lastRelayId.clear();
        const QString why = relay(peer, f.readAll(), name);
        if (!why.isEmpty())
            addFailed(peer, name, fi.size(), why, path);
        else
            addRelay(peer, QString(), name, fi.size(), path);
        return QString();
    }
    if (route.isEmpty()) {
        addFailed(peer, name, fi.size(), tooBig(), path, true, QString(), group, !trust);
        return QString();
    }
    auto *x = new Xfer;
    x->id = GroupWire::newLogicalId();
    x->peer = peer;
    x->group = group;
    x->name = name;
    x->path = path;
    x->size = fi.size();
    x->mine = true;
    x->dir = 0;
    // Close by and not trusted with each other's internet addresses: only the same Wi-Fi.
    x->lanOnly = route == QLatin1String("direct") && !trust;
    if (x->lanOnly)
        x->route = route;
    x->state = QStringLiteral("connecting");
    x->note = QStringLiteral("Getting ready");
    m_x.insert(x->id, x);
    put(x, true);
    const QString id = x->id;
    // Retry of the same file (same size, not changed since): its SHA-256 is known, so it goes at once.
    const qint64 changed = fi.lastModified().toMSecsSinceEpoch();
    const Hashed known = m_hashed.value(path);
    auto hashed = [this, id, path, changed](const QString &sha) {
        Xfer *x = m_x.value(id);
        if (!x)
            return;
        if (sha.isEmpty()) {
            fail(x, QStringLiteral("That file can't be read."));
            return;
        }
        m_hashed.insert(path, Hashed{x->size, changed, sha});
        x->sha = sha;
        startSocket(x, [this, id](const QJsonArray &cands) {
            if (Xfer *x = m_x.value(id))
                sendOffer(x, cands);
        });
    };
    if (!known.sha.isEmpty() && known.size == x->size && known.changed == changed)
        QTimer::singleShot(0, this, [hashed, sha = known.sha] { hashed(sha); });
    else
        hashFile(this, path, hashed);
    return QString();
}

QString FileLink::sendTo(const QString &where, const QVariantList &urls)
{
    QStringList paths;
    for (const QVariant &v : urls) {
        const QUrl u = v.toUrl();
        const QString p = u.isLocalFile() ? u.toLocalFile() : v.toString();
        if (!p.isEmpty() && !paths.contains(p))
            paths << p;
    }
    if (paths.isEmpty())
        return QStringLiteral("Pick a file first.");
    QStringList why;
    if (!where.startsWith(QLatin1String("g:"))) {
        for (const QString &p : std::as_const(paths))
            if (const QString w = send(where, p); !w.isEmpty() && !why.contains(w))
                why << w;
        return why.join(QLatin1Char(' '));
    }
    // A group's file: the group's own rules first (as every member's app checks them), then once to each member.
    const QString g = where.mid(2).section(QLatin1Char(':'), 0, 0);
    const QStringList members = groupMembers ? groupMembers(g) : QStringList();
    if (members.isEmpty())
        return QStringLiteral("Nobody else is in this group yet.");
    const QString me = myKey ? myKey() : QString();
    for (const QString &p : std::as_const(paths)) {
        const QFileInfo fi(p);
        if (!fi.isFile())
            return QStringLiteral("That file can't be read.");
        if (groupRefusal) {
            if (const QString w = groupRefusal(g, me, fi.size()); !w.isEmpty())
                return w;
        }
        for (const QString &k : members)
            if (const QString w = send(k, p, g); !w.isEmpty() && !why.contains(w))
                why << w;
    }
    return why.join(QLatin1Char(' '));
}

// A new UDP socket for this transfer, and its candidates: what the STUN server saw and the global IPv6
// addresses (only 127.0.0.1 in the self-test).
void FileLink::startSocket(Xfer *x, std::function<void(QJsonArray)> ready)
{
    x->sock = new QUdpSocket(this);
    Busy::nameSocket(x->sock, "file link");
    if (!x->sock->bind(testLocal ? QHostAddress(QHostAddress::LocalHost) : QHostAddress(QHostAddress::Any), 0)) {
        if (x->mine)
            fallBack(x, QStringLiteral("No internet connection that can go direct. Try on the same Wi-Fi."));
        else
            fail(x, QStringLiteral("No internet connection that can go direct."));
        return;
    }
    const QString id = x->id;
    connect(x->sock, &QUdpSocket::readyRead, this, [this, id] {
        Busy::Scope busy("file transfers");
        if (Xfer *x = m_x.value(id))
            onDatagram(x);
    });
    const quint16 port = x->sock->localPort();
    if (testLocal) {
        ready(QJsonArray{QJsonObject{{QStringLiteral("t"), QStringLiteral("srflx")}, {QStringLiteral("a"), QStringLiteral("127.0.0.1")}, {QStringLiteral("p"), int(port)}}});
        return;
    }
    // Cap f2 (27.1): on the same Wi-Fi, a "lan" candidate (the port only) comes first. Without trust it's the only
    // one, and no STUN server is asked.
    if (x->lanOnly) {
        ready(QJsonArray{lanCandidate(port)});
        return;
    }
    if (!lanOf(x->peer).isEmpty() && hasF2 && hasF2(x->peer))
        x->afterStun = [ready, port](QJsonArray c) {
            c.prepend(lanCandidate(port));
            ready(c);
        };
    else
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
    QJsonObject offer{{QStringLiteral("t"), QStringLiteral("offer")}, {QStringLiteral("id"), x->id}, {QStringLiteral("name"), x->name},
                      {QStringLiteral("size"), double(x->size)}, {QStringLiteral("sha"), x->sha},
                      {QStringLiteral("e"), QString::fromLatin1(x->eph.publicDer.toBase64())}, {QStringLiteral("c"), cands}};
    if (!x->group.isEmpty())
        offer.insert(QStringLiteral("g"), x->group);   // cap f2 (27.2): the group it's for
    signal(x->peer, offer);
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
            fallBack(x, hasF3(x->peer) && x->size > kRelayMax ? QStringLiteral("Not accepted within a day. Try again.")
                                                               : QStringLiteral("They're offline. Try again when they're back."));
    });
    x->wait->start(answerMs(x->size, hasF3(x->peer)));
    // Cap f3: a big file may wait a day. A STUN ask a minute keeps this socket's way through the router open, so the
    // address in the offer still works when they accept.
    if (!testLocal && !x->lanOnly && x->size > kRelayMax && hasF3(x->peer)) {
        auto *keep = new QTimer(x->wait);
        connect(keep, &QTimer::timeout, this, [this, id] {
            Xfer *x = m_x.value(id);
            if (!x || x->state != QLatin1String("offered") || x->probe || !x->sock)
                return;
            x->probe = new StunProbe(x->sock, this);
            connect(x->probe, &StunProbe::finished, this, [this, id] {
                Xfer *x = m_x.value(id);
                if (x && x->probe) {
                    x->probe->deleteLater();
                    x->probe = nullptr;
                }
            });
            x->probe->start();
        });
        keep->start(60000);
    }
}

void FileLink::onSignal(const QString &from, const QJsonObject &p)
{
    const QString id = jstr(p, "id"), t = jstr(p, "t");
    if (t == QLatin1String("offer")) {
        if (m_x.contains(id))
            return;
        const QJsonArray cands = p.value(QStringLiteral("c")).toArray();
        const bool trust = trusted && trusted(from);
        // Cap f2 (27.1): close by on the same Wi-Fi it can come straight without trust (no internet address swapped).
        const Stun::Address lan = lanTarget(lanOf(from), cands);
        const QString g = jstr(p, "g");
        const QString name = jstr(p, "name");
        const qint64 size = qint64(p.value(QStringLiteral("size")).toDouble());
        auto refuse = [&](const QString &why, const QString &shown) {
            signal(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}, {QStringLiteral("why"), why}});
            addRefused(from, g, id, name, size, shown);
        };
        // A group's file (27.2, 27.3): only for a group we're both in, and only within its file rules. Dropped, and said.
        if (!g.isEmpty()) {
            const QString why = groupRefusal ? groupRefusal(g, from, size) : QStringLiteral("Refused: you aren't in that group.");
            if (!why.isEmpty()) {
                refuse(QStringLiteral("rules"), why);
                Q_EMIT notice(QStringLiteral("A file from %1 in %2 was dropped. %3").arg(who(from), chatOf(from, g), why));
                return;
            }
        }
        if (!trust && !lan.valid()) {
            // Addresses are only swapped with someone trusted: say no, and the sender knows why.
            signal(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}});
            return;
        }
        // Not enough room for it (and a little to spare): said on both sides, never a half file.
        const QString folder = folderOf(from, g);
        QDir().mkpath(folder);
        const QStorageInfo disk(folder);
        if (!testLocal && disk.isValid() && disk.bytesAvailable() >= 0 && size + 64LL * 1024 * 1024 > disk.bytesAvailable()) {
            refuse(QStringLiteral("space"), QStringLiteral("Not enough space for it on this computer (%1 free).").arg(sizeText(disk.bytesAvailable())));
            Q_EMIT notice(QStringLiteral("%1 tried to send you %2, but there isn't enough space.").arg(who(from), name));
            return;
        }
        auto *x = new Xfer;
        x->id = id;
        x->peer = from;
        x->group = g;
        x->folder = folder;
        x->name = name;
        x->size = size;
        x->sha = jstr(p, "sha");
        x->theirE = jstr(p, "e");
        if (trust)
            x->theirs = directOf(cands);
        if (lan.valid()) {
            x->lan = lan;
            x->theirs.prepend(lan);
        }
        x->lanOnly = !trust;
        if (x->lanOnly)
            x->route = QStringLiteral("direct");
        x->mine = false;
        x->dir = 1;
        m_x.insert(id, x);
        // Settings › Data saver: every file asks first (only when their app waits for the answer).
        const bool saving = !testLocal && Settings::dataSaverOn();
        // Someone in one of my Family groups, or "Always from this person": every file arrives by itself. A group's own
        // file follows the group's type (16.2: Folk small ones, Family all). Data saver still asks.
        const bool always = alwaysFrom && alwaysFrom(from);
        const bool big = g.isEmpty() ? x->size >= kAutoBytes && !(allArrive && allArrive(from)) && !always
                                     : !(groupArrives && groupArrives(g, x->size)) && !always;
        // Cap f3: the rest of a file already accepted (it stopped part way) doesn't ask again.
        const bool resuming = !partialFor(x).isEmpty();
        if (((big && !resuming) || saving) && (!waitsForAsk || waitsForAsk(from))) {
            // A big file asks first, as from anyone else. The sender gives up after kAskAnswerMs.
            // Cap f3: it waits a day (their app waits as long), and the card says so.
            const bool day = hasF3(from);
            x->state = QStringLiteral("asking");
            x->note = day ? QStringLiteral("Wants to send you this. Waits a day for your answer") : QStringLiteral("Wants to send you this. Accept or decline");
            put(x, true);
            const QString text = QStringLiteral("%1 wants to send you %2 (%3). Accept or decline it in the chat or Transfers.").arg(who(from), x->name, sizeText(x->size));
            Q_EMIT notice(text);
            Q_EMIT offered(id, g.isEmpty() ? from : QStringLiteral("g:") + g, text);
            x->wait = new QTimer(this);
            x->wait->setSingleShot(true);
            connect(x->wait, &QTimer::timeout, this, [this, id] {
                Xfer *x = m_x.value(id);
                if (x && x->state == QLatin1String("asking"))
                    fail(x, QStringLiteral("Not taken in time. Ask them to send it again."), false);
            });
            x->wait->start(day ? kLongAnswerMs : kAskAnswerMs);
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
        const QJsonArray cands = p.value(QStringLiteral("c")).toArray();
        x->theirs = x->lanOnly ? QList<Stun::Address>() : directOf(cands);
        // Cap f2 (27.1): their "lan" candidate, where they are on this Wi-Fi, comes first.
        const Stun::Address lan = lanTarget(lanOf(from), cands);
        if (lan.valid()) {
            x->lan = lan;
            x->theirs.prepend(lan);
        }
        if (x->wait)
            x->wait->stop();
        startPunch(x);
    } else if (t == QLatin1String("decline") && x->state == QLatin1String("offered")) {
        // Refused (the group's file rules, no space, they said no: 27.4) ends it. Otherwise they don't trust me with
        // their address: a small file can still go through the relays.
        const QString why = jstr(p, "why");
        if (!why.isEmpty())
            fail(x, Sharing::declineText(why));
        else {
            x->direct = !x->lanOnly;   // its card offers to ask them for Direct calls and files
            fallBack(x, x->lanOnly ? QStringLiteral("Couldn't reach them on this Wi-Fi.") : Sharing::declineText(QString()));
        }
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
        // Written straight into the chat's folder (Downloads/Huginn/<chat>) as name.part, renamed once it all arrived and
        // checked out. Never a hidden app folder.
        if (x->folder.isEmpty())
            x->folder = folderOf(x->peer, x->group);
        QDir().mkpath(x->folder);
        // Cap f3: the same file from the same person stopped part way before. It goes on from there.
        const QString kept = partialFor(x);
        if (!kept.isEmpty()) {
            const Partial p = m_partial.take(kept);   // this transfer owns the part now
            drop(kept);                               // its old card goes: this one takes over
            x->path = p.path;
            x->resumeBelow = p.below;
            x->base = qMin(x->size, qint64(p.below) * kChunk);
        } else
            x->path = QDir(x->folder).filePath(freeName(x->folder, x->name) + QStringLiteral(".part"));
        x->file = new QFile(x->path);
        const QIODevice::OpenMode mode = kept.isEmpty() ? QIODevice::ReadWrite | QIODevice::Truncate : QIODevice::ReadWrite;
        if (!x->file->open(mode) || !x->file->resize(x->size)) {
            fail(x, QStringLiteral("Not enough space to save %1.").arg(x->name));
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
    x->note = x->lanOnly ? QStringLiteral("Connecting to %1 on this Wi-Fi").arg(who(x->peer)) : QStringLiteral("Connecting straight to %1").arg(who(x->peer));
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
            fallBack(x, x->lanOnly ? QStringLiteral("Couldn't reach them on this Wi-Fi.") : QStringLiteral("Couldn't connect straight to them. Try on the same Wi-Fi."));
        else
            fail(x, x->lanOnly ? QStringLiteral("Couldn't reach them on this Wi-Fi. Ask them to send it again.")
                               : QStringLiteral("Couldn't connect straight to them. It may come another way."));
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
    x->started = now();
    x->lastAck = 0;
    const int n = pieces(x->size);
    if (x->mine)
        x->s = std::make_unique<Sender>(n);
    else {
        x->r = std::make_unique<Receiver>(n);
        // Cap f3: what an earlier try left is in; the first ack tells the sender to go on from there.
        if (x->resumeBelow > 0) {
            x->r->preload(x->resumeBelow);
            x->done = x->base;
        }
    }
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
    // Cap f3: two minutes of silence (a blip, a laptop waking up) before it stops, and Retry goes on from there.
    const bool f3 = hasF3(x->peer);
    if (t - x->lastHeard > (f3 ? kLongIdleMs : kIdleMs)) {
        if (x->mine)
            fail(x, f3 ? QStringLiteral("%1 went offline at %2%. Retry goes on from there.").arg(who(x->peer)).arg(percent(x->done, x->size))
                       : QStringLiteral("They went offline. Try again."));
        else if (f3 && x->file && x->r) {
            // Kept: when they send it again it goes on from here.
            x->file->flush();
            Partial keep{x->peer, x->sha, x->path, x->folder, x->name, x->group, x->size, x->r->below()};
            x->file->close();
            const QString id = x->id, why = QStringLiteral("%1 went offline at %2%. It goes on from there when they send it again.")
                                                .arg(who(x->peer)).arg(percent(x->done, x->size));
            x->state = QStringLiteral("failed");
            x->note = why;
            put(x, true);
            end(x);
            m_partial.insert(id, keep);
            Q_EMIT notice(why);
        } else
            fail(x, QStringLiteral("They went offline. Ask them to send it again."));
        return;
    }
    // Quiet for a moment: one packet a second keeps the line open, and the card says it's waiting.
    const bool quiet = f3 && t - x->lastHeard > kQuietMs;
    if (quiet != x->quiet) {
        x->quiet = quiet;
        x->note = quiet ? QStringLiteral("Waiting for %1 to come back").arg(who(x->peer)) : QString();
        put(x, true);
    }
    if (quiet && !x->paused) {
        if (t - x->lastKeep >= 1000) {
            x->lastKeep = t;
            if (x->mine)
                sendPacket(x, Probe, QByteArray(1, 1), x->to);
            else
                sendAck(x);
        }
        return;
    }
    // Paused here (27.5): Hold every second instead of data or acks.
    if (x->paused) {
        if (t - x->lastHold >= 1000) {
            sendPacket(x, Hold, QByteArray(1, 1), x->to);
            x->lastHold = t;
        }
        return;
    }
    if (!x->mine) {
        // Paused on their side: an ack a second keeps the line open.
        if (x->fresh >= 16 || t - x->lastAck >= (x->held ? 1000 : 40))
            sendAck(x);
        return;
    }
    // Held by them: no data, a probe a second so they know this side is still here.
    if (x->held) {
        if (t - x->lastHold >= 1000) {
            sendPacket(x, Probe, QByteArray(1, 1), x->to);
            x->lastHold = t;
        }
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
        Busy::count("file link datagrams received");
        if (!d.isValid())
            break;   // couldn't be read: readyRead comes again for what's left
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
            // Cap f2: their address on this Wi-Fi answered, so it goes Direct; anything else is over the internet.
            x->route = x->lan.valid() && x->to == x->lan ? QStringLiteral("direct") : QStringLiteral("internet");
            // Data or an ack already means the other side found this one too.
            if ((type != Probe || ++x->heardBack >= 3) && !startData(x))
                return;
            if (!x->moving)
                continue;
        }
        if (!x->moving)
            continue;
        x->lastHeard = now();
        // Cap f2 (27.5): they paused, or go on again.
        if (type == Hold && !payload.isEmpty()) {
            x->held = payload.at(0) == 1;
            put(x, true);
            continue;
        }
        if (x->mine) {
            if (type == Ack && x->held) {
                x->held = false;   // acks again: they went on (their Hold 0 may have been lost)
                put(x, true);
            }
            if (type == Ack && payload.size() >= 12) {
                x->s->ack(int(qFromBigEndian<quint32>(payload.constData())), qFromBigEndian<quint64>(payload.constData() + 4));
                x->done = qMin(x->size, qint64(x->s->confirmedBelow()) * kChunk);
                if (!x->baseSet) {
                    x->baseSet = true;   // cap f3: what they already had (a resumed file) doesn't count in the speed
                    x->base = x->done;
                }
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
                    signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), x->id}});
                    fail(x, QStringLiteral("Not enough space to save %1.").arg(x->name));
                    return;
                }
                if (++x->fresh >= 16 && !x->paused)
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
        const QString final = QFile::exists(planned) ? QDir(x->folder).filePath(freeName(x->folder, x->name)) : planned;
        if (!QFile::rename(x->path, final)) {
            QFile::remove(x->path);
            fail(x, QStringLiteral("%1 couldn't be saved.").arg(x->name));
            return;
        }
        x->name = QFileInfo(final).fileName();
        x->path = final;
        x->note.clear();
        Q_EMIT notice(QStringLiteral("File from %1: %2 · Saved in %3").arg(who(x->peer), x->name, shownFolder(x->folder)));
        finish(x);
    });
}

QString FileLink::freeName(const QString &folder, const QString &name) const
{
    const QDir dir(folder);
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
    x->paused = x->held = false;
    put(x, true);
    // The chat's Files list: what was sent from where, what came and where it was saved.
    logFile(x->group.isEmpty() ? x->peer : QStringLiteral("g:") + x->group, x->name, x->size, x->path, x->mine);
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
    signal(x->peer, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("id"), id}, {QStringLiteral("why"), QStringLiteral("no")}});
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
    // A group's file goes over the relays only to a contact (into the chat with them).
    const bool groupOk = x->group.isEmpty() || (isContact && isContact(x->peer));
    if (!x->mine || x->moving || x->size > kRelayMax || !relay || !groupOk || !f.open(QIODevice::ReadOnly)) {
        fail(x, why);
        return;
    }
    const QString id = x->id, peer = x->peer, name = x->name, path = x->path;
    const qint64 size = x->size;
    const QByteArray bytes = f.readAll();
    end(x);
    drop(id);   // the chat's own message takes over from here; its card follows it
    lastRelayId.clear();
    const QString r = relay(peer, bytes, name);
    if (!r.isEmpty())
        addFailed(peer, name, size, r, path, false, id);
    else
        addRelay(peer, QString(), name, size, path);
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

void FileLink::addFailed(const QString &peer, const QString &name, qint64 size, const QString &why, const QString &path, bool wifi, const QString &id,
                         const QString &group, bool direct)
{
    const QString key = id.isEmpty() ? GroupWire::newLogicalId() : id;
    drop(key);
    QVariantMap m = cardOf(key, peer, group, chatOf(peer, group), name, size, true, QStringLiteral("failed"), 0, why, path);
    m.insert(QStringLiteral("wifi"), wifi);
    m.insert(QStringLiteral("direct"), direct);
    m_list << m;
    while (m_list.size() > 50)
        m_list.removeFirst();
    Q_EMIT changed();
    Q_EMIT notice(QStringLiteral("%1 didn't go to %2. %3").arg(name, who(peer), why));
}

void FileLink::addRefused(const QString &peer, const QString &group, const QString &id, const QString &name, qint64 size, const QString &why)
{
    drop(id);
    QVariantMap m = cardOf(id, peer, group, chatOf(peer, group), name, size, false, QStringLiteral("failed"), 0, why, QString());
    m_list << m;
    while (m_list.size() > 50)
        m_list.removeFirst();
    Q_EMIT changed();
}

void FileLink::addRelay(const QString &peer, const QString &group, const QString &name, qint64 size, const QString &path)
{
    const QString id = lastRelayId;
    lastRelayId.clear();
    if (id.isEmpty())
        return;
    QVariantMap m = cardOf(id, peer, group, chatOf(peer, group), name, size, true, QStringLiteral("sending"), 0, QString(), path);
    m.insert(QStringLiteral("relay"), true);
    m.insert(QStringLiteral("route"), Sharing::routeLabel(QStringLiteral("relay")));
    drop(id);
    m_list << m;
    while (m_list.size() > 50)
        m_list.removeFirst();
    Q_EMIT changed();
    logFile(group.isEmpty() ? peer : QStringLiteral("g:") + group, name, size, path, true);
}

void FileLink::relayTick()
{
    if (!m_chat)
        return;
    bool moved = false;
    for (QVariantMap &e : m_list) {
        if (!e.value(QStringLiteral("relay")).toBool() || e.value(QStringLiteral("state")).toString() != QLatin1String("sending"))
            continue;
        const QString id = e.value(QStringLiteral("id")).toString(), peer = e.value(QStringLiteral("peer")).toString();
        QString state;
        bool failed = false, found = false;
        for (const QVariant &v : m_chat->m_private.value(peer)) {
            const QVariantMap msg = v.toMap();
            if (msg.value(QStringLiteral("id")).toString() == id) {
                state = msg.value(QStringLiteral("state")).toString();
                failed = msg.value(QStringLiteral("failed")).toBool();
                found = true;
                break;
            }
        }
        const double size = e.value(QStringLiteral("size")).toDouble();
        QVariantMap next = e;
        if (!found) {
            next.insert(QStringLiteral("state"), QStringLiteral("failed"));
            next.insert(QStringLiteral("note"), QStringLiteral("Its message was deleted before it went."));
        } else if (failed) {
            next.insert(QStringLiteral("state"), QStringLiteral("failed"));
            next.insert(QStringLiteral("note"), QStringLiteral("%1 can't be reached right now. Retry when they're online.").arg(who(peer)));
        } else if (state == QLatin1String("sent") || state == QLatin1String("delivered") || state == QLatin1String("read")) {
            next.insert(QStringLiteral("state"), QStringLiteral("done"));
            next.insert(QStringLiteral("done"), size);
            next.insert(QStringLiteral("note"), relayText(state, false, 1));
        } else if (m_chat->uploads().contains(id)) {
            next.insert(QStringLiteral("done"), size * qBound(0.0, m_chat->uploads().value(id).toDouble(), 1.0));
        }
        if (next != e) {
            e = next;
            moved = true;
        }
    }
    if (moved)
        Q_EMIT changed();
}

void FileLink::relayIn(const QString &msgId, const QString &peer, const QString &name, int have, int n)
{
    QVariantMap m = cardOf(msgId, peer, QString(), chatOf(peer, QString()), name, qint64(n) * Photos::kPiece, false, QStringLiteral("sending"),
                           qint64(have) * Photos::kPiece, QString(), QString());
    m.insert(QStringLiteral("route"), Sharing::routeLabel(QStringLiteral("relay")));
    m.insert(QStringLiteral("folder"), shownFolder(folderOf(peer, QString())));
    for (QVariantMap &e : m_list)
        if (e.value(QStringLiteral("id")).toString() == msgId) {
            m.insert(QStringLiteral("time"), e.value(QStringLiteral("time")));
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

QString FileLink::saveBytes(const QByteArray &bytes, const QString &name, const QString &peer)
{
    if (bytes.isEmpty() || !nameOk(name))
        return {};
    const QString dir = peer.isEmpty() ? base() : folderOf(peer, QString());
    QDir().mkpath(dir);
    const QString path = QDir(dir).filePath(freeName(dir, name));
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size()) {
        f.remove();
        return {};
    }
    if (!peer.isEmpty())
        logFile(peer, QFileInfo(path).fileName(), bytes.size(), path, false);
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
                && QFileInfo(e.value(QStringLiteral("path")).toString()).isFile();
    return false;
}

void FileLink::retry(const QString &id)
{
    if (!canRetry(id))
        return;
    QString peer, path, group;
    for (const QVariantMap &e : std::as_const(m_list))
        if (e.value(QStringLiteral("id")).toString() == id) {
            peer = e.value(QStringLiteral("peer")).toString();
            path = e.value(QStringLiteral("path")).toString();
            group = e.value(QStringLiteral("group")).toString();
        }
    drop(id);
    const QString why = send(peer, path, group);
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

// ------------------------------------------------------------------ pause, always, and each chat's Files (cap f2)

bool FileLink::canPause(const QString &id) const
{
    const Xfer *x = m_x.value(id);
    return x && x->moving && !x->paused;
}

bool FileLink::canResume(const QString &id) const
{
    const Xfer *x = m_x.value(id);
    return x && x->paused;
}

void FileLink::pause(const QString &id)
{
    Xfer *x = m_x.value(id);
    if (!x || !x->moving || x->paused)
        return;
    x->paused = true;
    x->pausedAt = now();
    x->lastHold = now();
    sendPacket(x, Hold, QByteArray(1, 1), x->to);
    put(x, true);
}

void FileLink::resume(const QString &id)
{
    Xfer *x = m_x.value(id);
    if (!x || !x->paused)
        return;
    x->paused = false;
    x->pausedFor += now() - x->pausedAt;
    x->lastHeard = now();
    for (int i = 0; i < 3; ++i)
        sendPacket(x, Hold, QByteArray(1, 0), x->to);
    if (!x->mine)
        sendAck(x);
    put(x, true);
}

void FileLink::acceptAlways(const QString &id)
{
    const Xfer *x = m_x.value(id);
    if (!x || x->mine || x->state != QLatin1String("asking"))
        return;
    if (setAlways && isContact && isContact(x->peer))
        setAlways(x->peer);
    accept(id);
}

QString FileLink::folderText(const QString &where) const
{
    if (where.startsWith(QLatin1String("g:")))
        return shownFolder(folderOf(QString(), where.mid(2).section(QLatin1Char(':'), 0, 0)));
    return shownFolder(folderOf(where, QString()));
}

QString FileLink::kind(const QString &name) const { return Sharing::kindOf(name); }

void FileLink::loadLog()
{
    if (m_logLoaded || !m_chat || !m_chat->ready() || !m_chat->m_vault)
        return;
    m_logLoaded = true;
    const QJsonArray a = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("fileslog.json"))).array();
    // Kept as long as the longest a chat keeps its messages (16): after that only the files themselves stay.
    const qint64 oldest = now() - qint64(Limits::kLongest) * Limits::kDay;
    for (const QJsonValue &v : a) {
        const QVariantMap m = v.toObject().toVariantMap();
        if (qint64(m.value(QStringLiteral("time")).toDouble()) >= oldest && !m.value(QStringLiteral("where")).toString().isEmpty())
            m_log << m;
    }
}

void FileLink::saveLog()
{
    if (!m_chat || !m_chat->ready() || !m_chat->m_vault)
        return;
    QJsonArray a;
    for (const QVariantMap &m : std::as_const(m_log))
        a << QJsonObject::fromVariantMap(m);
    m_chat->m_vault->write(QStringLiteral("fileslog.json"), QJsonDocument(a).toJson(QJsonDocument::Compact));
}

void FileLink::logFile(const QString &where, const QString &name, qint64 size, const QString &path, bool mine)
{
    if (where.isEmpty() || path.isEmpty())
        return;
    loadLog();
    m_log << QVariantMap{{QStringLiteral("where"), where}, {QStringLiteral("name"), name}, {QStringLiteral("size"), double(size)},
                         {QStringLiteral("path"), path}, {QStringLiteral("mine"), mine}, {QStringLiteral("time"), double(now())}};
    while (m_log.size() > 2000)
        m_log.removeFirst();
    saveLog();
    Q_EMIT changed();
}

QVariantList FileLink::filesIn(const QString &where)
{
    loadLog();
    QVariantList out;
    for (int i = int(m_log.size()) - 1; i >= 0; --i) {
        QVariantMap m = m_log.at(i);
        if (m.value(QStringLiteral("where")).toString() != where)
            continue;
        const QString path = m.value(QStringLiteral("path")).toString();
        m.insert(QStringLiteral("there"), QFileInfo(path).isFile());
        m.insert(QStringLiteral("folder"), shownFolder(QFileInfo(path).absolutePath()));
        m.insert(QStringLiteral("icon"), iconFor(m.value(QStringLiteral("name")).toString()));
        out << m;
    }
    return out;
}

bool FileLink::deleteFile(const QString &where, const QString &path)
{
    loadLog();
    bool mine = true, found = false;
    for (const QVariantMap &m : std::as_const(m_log))
        if (m.value(QStringLiteral("where")).toString() == where && m.value(QStringLiteral("path")).toString() == path) {
            found = true;
            mine = m.value(QStringLiteral("mine")).toBool();
        }
    if (!found)
        return false;
    // A received file is deleted from the disk, and only from inside the save folder; a sent one stays where it was.
    if (!mine && QFileInfo(path).isFile()) {
        // Inside the save folder as it is now, or Downloads (where files went before it was changed).
        const QString cur = QDir(base()).absolutePath() + QLatin1Char('/'), before = QDir(saveDir).absolutePath() + QLatin1Char('/');
        const QString abs = QFileInfo(path).absoluteFilePath();
        if (!(abs.startsWith(cur) || abs.startsWith(before)) || !QFile::remove(path))
            return false;
    }
    m_log.removeIf([&](const QVariantMap &m) { return m.value(QStringLiteral("where")).toString() == where && m.value(QStringLiteral("path")).toString() == path; });
    saveLog();
    Q_EMIT changed();
    return true;
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
    const QString path = saveBytes(bytes, name, peer);
    if (path.isEmpty())
        return QStringLiteral("Not enough space to save it.");
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
    if (m_files)
        m_files->lastRelayId = id;   // its card in Transfers follows this message
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

    // Cap f3: a receiver that kept 300 pieces says so in its first ack, and the sender goes on from 300.
    {
        Receiver r(500);
        r.preload(300);
        int below = 0;
        quint64 bits = 0;
        r.ack(&below, &bits);
        Sender s(500);
        s.ack(below, bits);
        const QList<int> next = s.due(0);
        check(below == 300 && !next.isEmpty() && next.first() == 300, QStringLiteral("resume goes on from the kept part"));
        check(answerMs(kRelayMax + 1, true) == kLongAnswerMs, QStringLiteral("a big file waits a day with f3"));
    }

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
    // Cap f2 (27.1): the "lan" candidate carries only a port, and pairs with where they already are on this Wi-Fi.
    {
        const QJsonArray c{lanCandidate(5000)};
        check(!c.at(0).toObject().contains(QStringLiteral("a")), QStringLiteral("lan: no address on the wire"));
        const Stun::Address a = lanTarget(QStringLiteral("192.168.1.20"), c);
        check(a.valid() && a.port == 5000 && CallNet::toHost(a) == QHostAddress(QStringLiteral("192.168.1.20")), QStringLiteral("lan: paired"));
        check(!lanTarget(QString(), c).valid() && !lanTarget(QStringLiteral("192.168.1.20"), QJsonArray()).valid(), QStringLiteral("lan: needs both"));
        QJsonObject offer{{QStringLiteral("t"), QStringLiteral("offer")}, {QStringLiteral("id"), GroupWire::newLogicalId()},
                          {QStringLiteral("name"), QStringLiteral("a.txt")}, {QStringLiteral("size"), 10}, {QStringLiteral("sha"), QString(64, QLatin1Char('a'))},
                          {QStringLiteral("e"), QStringLiteral("x")}, {QStringLiteral("c"), c}};
        check(ok(offer), QStringLiteral("offer with a lan candidate"));
        offer.insert(QStringLiteral("g"), GroupWire::newGroupId());
        check(ok(offer), QStringLiteral("offer for a group"));
        offer.insert(QStringLiteral("g"), QStringLiteral("../x"));
        check(!ok(offer), QStringLiteral("offer for a bad group id"));
    }

    // Cap f2 (27.3): a group's file outside its rules is dropped by the receiving member, and the sender hears why.
    {
        QTemporaryDir tmp;
        DirectPath::g_testLocal = true;
        FileLink a(nullptr), b(nullptr);
        a.testLocal = b.testLocal = true;
        a.saveDir = tmp.filePath(QStringLiteral("a"));
        b.saveDir = tmp.filePath(QStringLiteral("b"));
        a.peerHasF1 = b.peerHasF1 = [](const QString &) { return true; };
        a.hasF2 = b.hasF2 = [](const QString &) { return true; };
        a.trusted = b.trusted = [](const QString &) { return true; };
        b.groupRefusal = [](const QString &, const QString &, qint64) { return QStringLiteral("Refused by the group's file rules: only admins can send files."); };
        a.signal = [&b](const QString &, const QJsonObject &m) { QTimer::singleShot(0, &b, [&b, m] { if (ok(m)) b.onSignal(QStringLiteral("A"), m); }); };
        b.signal = [&a](const QString &, const QJsonObject &m) { QTimer::singleShot(0, &a, [&a, m] { if (ok(m)) a.onSignal(QStringLiteral("B"), m); }); };
        const QString src = tmp.filePath(QStringLiteral("notes.txt"));
        QFile out(src);
        check(out.open(QIODevice::WriteOnly) && out.write("group file") == 10, QStringLiteral("write the group test file"));
        out.close();
        const QString g = GroupWire::newGroupId();
        check(a.send(QStringLiteral("B"), src, g).isEmpty(), QStringLiteral("group send starts"));
        QEventLoop loop;
        QTimer give;
        give.setSingleShot(true);
        QObject::connect(&give, &QTimer::timeout, &loop, &QEventLoop::quit);
        auto failed = [](const FileLink &l) {
            const QVariantList t = l.transfers();
            return !t.isEmpty() && t.last().toMap().value(QStringLiteral("state")).toString() == QLatin1String("failed");
        };
        auto watch = [&] { if (failed(a) && failed(b)) loop.quit(); };
        QObject::connect(&a, &FileLink::changed, &loop, watch);
        QObject::connect(&b, &FileLink::changed, &loop, watch);
        give.start(15000);
        loop.exec();
        const QVariantMap ca = a.transfers().isEmpty() ? QVariantMap() : a.transfers().last().toMap();
        const QVariantMap cb = b.transfers().isEmpty() ? QVariantMap() : b.transfers().last().toMap();
        check(ca.value(QStringLiteral("state")).toString() == QLatin1String("failed")
                  && ca.value(QStringLiteral("note")).toString() == Sharing::declineText(QStringLiteral("rules")),
              QStringLiteral("group rules: the sender hears why (%1)").arg(ca.value(QStringLiteral("note")).toString()));
        check(ca.value(QStringLiteral("where")).toString() == QStringLiteral("g:") + g, QStringLiteral("group rules: the card is the group's"));
        check(cb.value(QStringLiteral("state")).toString() == QLatin1String("failed") && cb.value(QStringLiteral("note")).toString().startsWith(QLatin1String("Refused")),
              QStringLiteral("group rules: the receiver says why"));
        check(!QFile::exists(QDir(b.saveDir).filePath(QStringLiteral("notes.txt"))), QStringLiteral("group rules: nothing saved"));
        DirectPath::g_testLocal = false;
    }
    std::fprintf(stderr, "f1: %s\n", bad == 0 ? "all checks passed" : "FAILED");
    return bad == 0;
}
