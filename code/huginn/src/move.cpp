#include "move.h"

#include "chat.h"
#include "chatcrypto.h"
#include "groupwire.h"
#include "link.h"
#include "localsend.h"
#include "trust.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMessageAuthenticationCode>
#include <QNetworkInterface>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QSysInfo>
#include <QUrl>
#include <cstdio>
#include <functional>
#include <memory>

#include <openssl/evp.h>
#include <openssl/x509.h>

const char MoveWire::kPrefix[] = "MUNINNM1:";
const char MoveWire::kPath[] = "/api/huginn/v1/move";

namespace
{
const QByteArray kInfo = QByteArrayLiteral("huginn-muninn move v1");
const QString kLinkAad = QStringLiteral("move|link");
const qint64 kLinkMs = 10 * 60 * 1000;
const qint64 kGiveUpMs = 60 * 60 * 1000;
const QString kCommon = QStringLiteral("@move.json");

QByteArray sha(const QByteArray &b) { return QCryptographicHash::hash(b, QCryptographicHash::Sha256); }
QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QByteArray b64d(const QString &s) { return QByteArray::fromBase64(s.toLatin1()); }
QString b64url(const QByteArray &b) { return QString::fromLatin1(b.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals)); }
QByteArray b64urlD(const QString &s) { return QByteArray::fromBase64(s.toLatin1(), QByteArray::Base64UrlEncoding); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

QByteArray ecdh(const QByteArray &privDer, const QByteArray &pubDer)
{
    const unsigned char *p = reinterpret_cast<const unsigned char *>(privDer.constData());
    EVP_PKEY *mine = d2i_AutoPrivateKey(nullptr, &p, privDer.size());
    const unsigned char *q = reinterpret_cast<const unsigned char *>(pubDer.constData());
    EVP_PKEY *theirs = d2i_PUBKEY(nullptr, &q, pubDer.size());
    QByteArray out;
    EVP_PKEY_CTX *ctx = mine ? EVP_PKEY_CTX_new(mine, nullptr) : nullptr;
    size_t len = 0;
    if (ctx && theirs && EVP_PKEY_derive_init(ctx) > 0 && EVP_PKEY_derive_set_peer(ctx, theirs) > 0 && EVP_PKEY_derive(ctx, nullptr, &len) > 0) {
        out.resize(int(len));
        if (EVP_PKEY_derive(ctx, reinterpret_cast<unsigned char *>(out.data()), &len) <= 0)
            out.clear();
    }
    EVP_PKEY_CTX_free(ctx);
    EVP_PKEY_free(mine);
    EVP_PKEY_free(theirs);
    return out;
}

// AES-256-GCM with additional data; out is ciphertext then the 16-byte tag.
bool gcm(bool enc, const QByteArray &key, const QByteArray &nonce, const QByteArray &in, const QByteArray &aad, QByteArray *out)
{
    if (key.size() != 32 || nonce.size() != 12 || (!enc && in.size() < 16))
        return false;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    const QByteArray body = enc ? in : in.left(in.size() - 16);
    QByteArray tag = enc ? QByteArray(16, 0) : in.right(16);
    out->resize(body.size() + 16);
    int n = 0, m = 0;
    bool ok = ctx && (enc ? EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) : EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr)) > 0
        && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) > 0;
    const auto *k = reinterpret_cast<const unsigned char *>(key.constData());
    const auto *iv = reinterpret_cast<const unsigned char *>(nonce.constData());
    ok = ok && (enc ? EVP_EncryptInit_ex(ctx, nullptr, nullptr, k, iv) : EVP_DecryptInit_ex(ctx, nullptr, nullptr, k, iv)) > 0;
    int dummy = 0;
    if (ok && !aad.isEmpty())
        ok = (enc ? EVP_EncryptUpdate(ctx, nullptr, &dummy, reinterpret_cast<const unsigned char *>(aad.constData()), aad.size())
                  : EVP_DecryptUpdate(ctx, nullptr, &dummy, reinterpret_cast<const unsigned char *>(aad.constData()), aad.size())) > 0;
    auto *o = reinterpret_cast<unsigned char *>(out->data());
    if (ok && !body.isEmpty())
        ok = (enc ? EVP_EncryptUpdate(ctx, o, &n, reinterpret_cast<const unsigned char *>(body.constData()), body.size())
                  : EVP_DecryptUpdate(ctx, o, &n, reinterpret_cast<const unsigned char *>(body.constData()), body.size())) > 0;
    if (ok && enc) {
        ok = EVP_EncryptFinal_ex(ctx, o + n, &m) > 0 && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag.data()) > 0;
        if (ok) {
            out->resize(n + m);
            out->append(tag);
        }
    } else if (ok) {
        ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag.data()) > 0 && EVP_DecryptFinal_ex(ctx, o + n, &m) > 0;
        if (ok)
            out->resize(n + m);
    }
    EVP_CIPHER_CTX_free(ctx);
    if (!ok)
        out->clear();
    return ok;
}

void put32(QByteArray &out, quint32 v)
{
    const char b[4] = {char((v >> 24) & 0xff), char((v >> 16) & 0xff), char((v >> 8) & 0xff), char(v & 0xff)};
    out.append(b, 4);
}

// Ratchet state: Muninn names the one-off key "dhsPriv"/"dhsPub" and writes null where Huginn writes "".
QJsonObject sessionToMuninn(QJsonObject s)
{
    s.insert(QStringLiteral("dhsPriv"), s.take(QStringLiteral("dhsPrivate")));
    s.insert(QStringLiteral("dhsPub"), s.take(QStringLiteral("dhsPublic")));
    for (const char *k : {"dhr", "cks", "ckr"})
        if (s.value(QLatin1String(k)).toString().isEmpty())
            s.insert(QLatin1String(k), QJsonValue::Null);
    return s;
}

QJsonObject sessionFromMuninn(QJsonObject s)
{
    s.insert(QStringLiteral("dhsPrivate"), s.take(QStringLiteral("dhsPriv")));
    s.insert(QStringLiteral("dhsPublic"), s.take(QStringLiteral("dhsPub")));
    for (const char *k : {"dhr", "cks", "ckr"})
        if (s.value(QLatin1String(k)).isNull())
            s.insert(QLatin1String(k), QString());
    return s;
}

QJsonObject peerConvert(const QJsonObject &p, bool toMuninn)
{
    QJsonArray list;
    for (const QJsonValue &v : p.value(QStringLiteral("sessions")).toArray())
        list << (toMuninn ? sessionToMuninn(v.toObject()) : sessionFromMuninn(v.toObject()));
    QJsonValue active = p.value(QStringLiteral("active"));
    if (toMuninn && active.toString().isEmpty())
        active = QJsonValue::Null;
    if (!toMuninn && active.isNull())
        active = QString();
    return QJsonObject{{QStringLiteral("active"), active}, {QStringLiteral("sessions"), list}};
}

// A photo, voice or video message becomes words: the media itself stays behind.
QString words(const QVariantMap &m, const QString &text)
{
    if (m.value(QStringLiteral("voice")).toInt() > 0)
        return QStringLiteral("Voice message");
    if (m.value(QStringLiteral("video")).toInt() > 0 || m.value(QStringLiteral("vid")).toInt() > 0)
        return QStringLiteral("Video message");
    if (!str(m, "fn").isEmpty())
        return QStringLiteral("File: ") + str(m, "fn");
    if (!str(m, "photo").isEmpty())
        return QStringLiteral("Photo");
    return text;
}
}

// ------------------------------------------------------------------ the wire

bool MoveWire::lanIp(const QString &ip)
{
    const QStringList p = ip.split(QLatin1Char('.'));
    if (p.size() != 4)
        return false;
    for (const QString &x : p) {
        bool ok = false;
        const int v = x.toInt(&ok);
        if (!ok || x.isEmpty() || x.size() > 3 || v < 0 || v > 255 || x.at(0) == QLatin1Char('+') || x.at(0) == QLatin1Char('-'))
            return false;
    }
    const int a = p.at(0).toInt(), b = p.at(1).toInt();
    return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
}

bool MoveWire::lanAddr(const QString &addr)
{
    const int i = int(addr.lastIndexOf(QLatin1Char(':')));
    if (i < 0)
        return false;
    bool ok = false;
    const int port = addr.mid(i + 1).toInt(&ok);
    return ok && port >= 1 && port <= 65535 && lanIp(addr.left(i));
}

bool MoveWire::sameNetwork(const QString &ip, const QString &mine, int prefix)
{
    if (!lanIp(ip) || !lanIp(mine) || prefix < 8 || prefix > 32)
        return false;
    auto n = [](const QString &s) {
        quint32 v = 0;
        for (const QString &x : s.split(QLatin1Char('.')))
            v = (v << 8) | quint32(x.toInt());
        return v;
    };
    const quint32 mask = prefix == 32 ? 0xFFFFFFFFu : ~(0xFFFFFFFFu >> prefix);
    return (n(ip) & mask) == (n(mine) & mask);
}

QString MoveWire::code(const Code &c)
{
    const QJsonObject o{{QStringLiteral("v"), 1}, {QStringLiteral("a"), QJsonArray::fromStringList(c.a)}, {QStringLiteral("e"), c.e},
                        {QStringLiteral("s"), b64url(c.s)}, {QStringLiteral("n"), c.n}, {QStringLiteral("app"), c.app}};
    return QLatin1String(kPrefix) + b64url(compact(o));
}

bool MoveWire::parse(const QString &text, Code *c)
{
    const int at = int(text.indexOf(QLatin1String(kPrefix)));
    if (at < 0)
        return false;
    QString body;
    for (int i = at + int(qstrlen(kPrefix)); i < text.size(); ++i) {
        const QChar ch = text.at(i);
        if (!(ch.isLetterOrNumber() || ch == QLatin1Char('-') || ch == QLatin1Char('_')))
            break;
        body += ch;
    }
    const QJsonObject o = QJsonDocument::fromJson(b64urlD(body)).object();
    c->a.clear();
    // Only home-network addresses (18.1): never a name or a public address.
    for (const QJsonValue &v : o.value(QStringLiteral("a")).toArray())
        if (c->a.size() < 8 && lanAddr(v.toString()))
            c->a << v.toString();
    c->e = jstr(o, "e");
    c->s = b64urlD(jstr(o, "s"));
    c->n = jstr(o, "n").left(40).trimmed();
    if (c->n.isEmpty())
        c->n = QStringLiteral("your other device");
    c->app = jstr(o, "app");
    const QByteArray pub = b64d(c->e);
    const unsigned char *p = reinterpret_cast<const unsigned char *>(pub.constData());
    EVP_PKEY *k = d2i_PUBKEY(nullptr, &p, pub.size());
    const bool real = k != nullptr;
    EVP_PKEY_free(k);
    return o.value(QStringLiteral("v")).toInt() == 1 && real && c->s.size() == 16 && !c->a.isEmpty()
        && (c->app == QLatin1String("muninn") || c->app == QLatin1String("huginn"));
}

QByteArray MoveWire::key(const QByteArray &myPrivDer, const QString &theirPub, const QByteArray &secret, const QString &newPub, const QString &oldPub)
{
    const QByteArray z = ecdh(myPrivDer, b64d(theirPub));
    if (z.isEmpty())
        return QByteArray();
    return sha(kInfo + z + secret + sha(b64d(newPub)) + sha(b64d(oldPub)));
}

QString MoveWire::emoji(const QByteArray &k) { return Trust::emojiCode(k); }

QString MoveWire::mac(const QByteArray &k, const QString &text) { return b64(QMessageAuthenticationCode::hash(text.toUtf8(), k, QCryptographicHash::Sha256)); }

bool MoveWire::same(const QString &a, const QString &b)
{
    const QByteArray x = a.toLatin1(), y = b.toLatin1();
    if (x.size() != y.size() || x.isEmpty())
        return false;
    int d = 0;
    for (int i = 0; i < x.size(); ++i)
        d |= x.at(i) ^ y.at(i);
    return d == 0;
}

QString MoveWire::partAad(int i, int n, const QString &h) { return QStringLiteral("move|%1|%2|%3").arg(i).arg(n).arg(h); }

int MoveWire::parts(qint64 size) { return int(qMax<qint64>(1, (size + kPart - 1) / kPart)); }

QString MoveWire::seal(const QByteArray &k, const QByteArray &plain, const QString &aad, const QByteArray &nonce)
{
    QByteArray n = nonce;
    if (n.isEmpty()) {
        n.resize(12);
        for (char &c : n)
            c = char(QRandomGenerator::system()->bounded(256));
    }
    QByteArray out;
    if (!gcm(true, k, n, plain, aad.toUtf8(), &out))
        return QString();
    return b64(n + out);
}

QByteArray MoveWire::open(const QByteArray &k, const QString &d, const QString &aad, bool *ok)
{
    const QByteArray raw = b64d(d);
    QByteArray out;
    *ok = raw.size() >= 28 && gcm(false, k, raw.left(12), raw.mid(12), aad.toUtf8(), &out);
    return *ok ? out : QByteArray();
}

QByteArray MoveWire::pack(const Vault::Files &entries)
{
    QByteArray out;
    for (const auto &e : entries) {
        const QByteArray n = e.first.toUtf8();
        put32(out, quint32(n.size()));
        out += n;
        put32(out, quint32(e.second.size()));
        out += e.second;
    }
    return out;
}

bool MoveWire::unpack(const QByteArray &blob, Vault::Files *out)
{
    out->clear();
    qsizetype at = 0;
    auto get = [&blob, &at](quint32 *v) {
        if (at + 4 > blob.size())
            return false;
        const auto *p = reinterpret_cast<const unsigned char *>(blob.constData() + at);
        *v = (quint32(p[0]) << 24) | (quint32(p[1]) << 16) | (quint32(p[2]) << 8) | quint32(p[3]);
        at += 4;
        return true;
    };
    while (at < blob.size()) {
        quint32 ln = 0, ld = 0;
        if (!get(&ln) || ln > 4096 || at + ln > blob.size())
            return false;
        const QString name = QString::fromUtf8(blob.mid(at, ln));
        at += ln;
        if (!get(&ld) || at + qsizetype(ld) > blob.size())
            return false;
        out->emplace_back(name, blob.mid(at, ld));
        at += ld;
    }
    return true;
}

bool MoveWire::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::fprintf(stderr, "p95: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    int bad = 0, good = 0;
    auto check = [&bad, &good](bool ok, const QString &what) {
        if (ok)
            good++;
        else {
            bad++;
            std::fprintf(stderr, "p95 FAIL: %s\n", qPrintable(what));
        }
    };
    const QJsonObject m = v.value(QStringLiteral("move")).toObject();
    const QByteArray secret = b64urlD(jstr(m, "secret"));
    const QByteArray k = key(b64d(jstr(m, "newPriv")), jstr(m, "oldPub"), secret, jstr(m, "newPub"), jstr(m, "oldPub"));
    check(k.toHex() == jstr(m, "key").toLatin1(), QStringLiteral("move key, new side"));
    check(key(b64d(jstr(m, "oldPriv")), jstr(m, "newPub"), secret, jstr(m, "newPub"), jstr(m, "oldPub")) == k, QStringLiteral("move key, old side"));
    check(emoji(k) == jstr(m, "emoji"), QStringLiteral("move emoji"));
    check(mac(k, QStringLiteral("hello|") + jstr(m, "oldPub")) == jstr(m, "helloMac"), QStringLiteral("hello check"));
    check(mac(k, QStringLiteral("ready|") + jstr(m, "oldPub")) == jstr(m, "readyMac"), QStringLiteral("ready check"));
    check(mac(k, QStringLiteral("status|done|") + jstr(m, "sha")) == jstr(m, "statusDone"), QStringLiteral("status check"));
    Code c;
    check(parse(QStringLiteral("scan: ") + jstr(m, "code"), &c) && c.e == jstr(m.value(QStringLiteral("codeFields")).toObject(), "e")
              && c.a == QStringList{QStringLiteral("192.168.1.20:53317")} && c.n == QLatin1String("Tablet") && c.s == secret,
          QStringLiteral("move code"));
    // Written by Huginn (keys in another order, as Qt sorts them): read back the same.
    Code back;
    check(parse(code(c), &back) && back.e == c.e && back.s == c.s && back.a == c.a && back.n == c.n && back.app == c.app, QStringLiteral("move code written"));
    const QByteArray blob = b64d(jstr(m, "payload"));
    Vault::Files entries;
    bool same = unpack(blob, &entries) && int(entries.size()) == m.value(QStringLiteral("entries")).toArray().size();
    for (int i = 0; same && i < int(entries.size()); ++i) {
        const QJsonArray e = m.value(QStringLiteral("entries")).toArray().at(i).toArray();
        same = entries[size_t(i)].first == e.at(0).toString() && entries[size_t(i)].second == b64d(e.at(1).toString());
    }
    check(same && pack(entries) == blob, QStringLiteral("payload layout"));
    check(sha(blob).toHex() == jstr(m, "sha").toLatin1(), QStringLiteral("payload hash"));
    check(seal(k, blob, jstr(m, "aad0"), b64d(jstr(m, "nonce"))) == jstr(m, "part0"), QStringLiteral("sealed part"));
    bool ok = false;
    check(open(k, jstr(m, "part0"), jstr(m, "aad0"), &ok) == blob && ok, QStringLiteral("part opens"));
    open(k, jstr(m, "part0"), partAad(1, m.value(QStringLiteral("parts")).toInt(), jstr(m, "sha")), &ok);
    check(!ok, QStringLiteral("part refused in another place"));
    check(QString::fromUtf8(open(k, jstr(m, "link"), kLinkAad, &ok)) == jstr(m, "linkText") && ok, QStringLiteral("sealed link code"));
    for (const QJsonValue &x : m.value(QStringLiteral("partCounts")).toArray())
        check(parts(x.toArray().at(0).toInteger()) == x.toArray().at(1).toInt(), QStringLiteral("part count"));
    for (const QJsonValue &x : m.value(QStringLiteral("lan")).toArray())
        check(lanAddr(x.toArray().at(0).toString()) == x.toArray().at(1).toBool(), QStringLiteral("home address ") + x.toArray().at(0).toString());
    for (const QJsonValue &x : m.value(QStringLiteral("sameNetwork")).toArray()) {
        const QJsonArray a = x.toArray();
        check(sameNetwork(a.at(0).toString(), a.at(1).toString(), a.at(2).toInt()) == a.at(3).toBool(), QStringLiteral("same network ") + a.at(0).toString());
    }
    // Cap j1: link ids and whether a link lets someone in (section 17).
    const QJsonObject l = v.value(QStringLiteral("links")).toObject();
    for (const QJsonValue &x : l.value(QStringLiteral("ids")).toArray())
        check(GroupWire::linkId(x.toArray().at(0).toString()) == x.toArray().at(1).toString(), QStringLiteral("link id"));
    for (const QJsonValue &x : l.value(QStringLiteral("ok")).toArray()) {
        const QJsonArray a = x.toArray();
        check(GroupWire::linkOk(a.at(0).toInteger(), a.at(1).toInt(), a.at(2).toInt(), a.at(3).toBool(), a.at(4).toBool(), a.at(5).toBool(), a.at(6).toInteger())
                  == a.at(7).toBool(),
              QStringLiteral("link ok ") + QString::fromUtf8(compact(QJsonObject{{QStringLiteral("c"), a}})));
    }
    // "mi" in a group state: only ever true (17.1).
    QFile pf(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("proto06-vectors.json")));
    if (pf.open(QIODevice::ReadOnly)) {
        const QJsonObject s = QJsonDocument::fromJson(pf.readAll()).object().value(QStringLiteral("group")).toObject().value(QStringLiteral("states")).toObject()
                                  .value(QStringLiteral("s1")).toObject().value(QStringLiteral("state")).toObject();
        QJsonObject on = s;
        on.insert(QStringLiteral("mi"), true);
        check(GroupWire::checkState(on).isEmpty(), QStringLiteral("mi true taken"));
        for (const QJsonValue &b : {QJsonValue(false), QJsonValue(1), QJsonValue(QStringLiteral("yes"))}) {
            QJsonObject x = s;
            x.insert(QStringLiteral("mi"), b);
            check(!GroupWire::checkState(x).isEmpty(), QStringLiteral("mi refused"));
        }
    } else {
        check(false, QStringLiteral("proto06-vectors.json next to p95-vectors.json"));
    }
    std::printf("p95 self-test: move key, emoji, checks, code, payload and parts; invite links; mi in a group state: %d ok, %d failed\n", good, bad);
    return bad == 0;
}

// ------------------------------------------------------------------ the app side

Move::Move(Chat *chat, Vault *vault, Link *link, LocalSend *net, QObject *parent)
    : QObject(parent), m_chat(chat), m_vault(vault), m_link(link), m_net(net)
{
    m_timer.setInterval(1500);
    connect(&m_timer, &QTimer::timeout, this, &Move::tick);
    // After a move, K is kept only in memory for 10 minutes ("Use as a linked device"), then dropped.
    m_linkTimer.setSingleShot(true);
    m_linkTimer.setInterval(int(kLinkMs));
    connect(&m_linkTimer, &QTimer::timeout, this, [this] { m_movedK.clear(); publish(); });
    connect(m_vault, &Vault::opened, this, &Move::vaultOpened);
    if (!m_vault->locked())
        vaultOpened();
    else
        publish();
}

void Move::resume()
{
    if (m_locked)
        return;
    const QJsonObject out = load(QStringLiteral("out"));
    if (jstr(out, "state") == QLatin1String("moving")) {
        // Moving was under way when Huginn closed: still locked, and it goes on.
        m_locked = true;
        m_chat->setMoving(true);
        m_lastHeard = QDateTime::currentMSecsSinceEpoch();
        m_timer.start();
    }
}

// Everything about a move is kept in the vault ("move.json"); what is stored while the vault is locked waits in
// memory until it opens.
void Move::vaultOpened()
{
    QJsonObject all = QJsonDocument::fromJson(m_vault->read(QStringLiteral("move.json"))).object();
    bool changedAll = false;
    for (const QString &k : {QStringLiteral("in"), QStringLiteral("out")}) {
        if (m_mem.contains(k)) {
            const QJsonObject o = m_mem.take(k);
            if (o.isEmpty())
                all.remove(k);
            else
                all.insert(k, o);
            changedAll = true;
        }
    }
    if (changedAll && all.isEmpty())
        m_vault->remove(QStringLiteral("move.json"));
    else if (changedAll)
        m_vault->write(QStringLiteral("move.json"), compact(all));
    resume();
    publish();
}

QJsonObject Move::load(const QString &k) const
{
    if (!m_vault->locked()) {
        const QJsonObject all = QJsonDocument::fromJson(m_vault->read(QStringLiteral("move.json"))).object();
        if (all.contains(k))
            return all.value(k).toObject();
    }
    return m_mem.value(k);
}

void Move::store(const QString &k, const QJsonObject &o)
{
    if (!m_vault->locked()) {
        QJsonObject all = QJsonDocument::fromJson(m_vault->read(QStringLiteral("move.json"))).object();
        if (o.isEmpty())
            all.remove(k);
        else
            all.insert(k, o);
        if (all.isEmpty())
            m_vault->remove(QStringLiteral("move.json"));
        else
            m_vault->write(QStringLiteral("move.json"), compact(all));
        m_mem.remove(k);
    } else {
        m_mem.insert(k, o);   // an empty one too: cleared when the vault opens
    }
    publish();
}

void Move::publish()
{
    const QJsonObject i = load(QStringLiteral("in")), o = load(QStringLiteral("out"));
    m_in = {{QStringLiteral("state"), jstr(i, "state")}, {QStringLiteral("emoji"), jstr(i, "emoji")}, {QStringLiteral("from"), jstr(i, "from")},
            {QStringLiteral("have"), int(missing().isEmpty() ? i.value(QStringLiteral("n")).toInt() : i.value(QStringLiteral("n")).toInt() - missing().size())},
            {QStringLiteral("of"), i.value(QStringLiteral("n")).toInt()}, {QStringLiteral("confirmed"), i.value(QStringLiteral("ok")).toBool()},
            {QStringLiteral("code"), jstr(i, "code")}};
    const QString note = m_out.value(QStringLiteral("note")).toString();
    const int sent = m_out.value(QStringLiteral("sent")).toInt();
    m_out = {{QStringLiteral("state"), jstr(o, "state")}, {QStringLiteral("emoji"), jstr(o, "emoji")}, {QStringLiteral("to"), jstr(o, "to")},
             {QStringLiteral("sent"), sent}, {QStringLiteral("of"), o.value(QStringLiteral("n")).toInt()}, {QStringLiteral("note"), note},
             {QStringLiteral("canLink"), !m_movedK.isEmpty()}};
    Q_EMIT changed();
}

QString Move::partsDir() const { return QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + QStringLiteral("/move-in"); }

QStringList Move::missing() const
{
    const QJsonObject s = load(QStringLiteral("in"));
    const int n = s.value(QStringLiteral("n")).toInt();
    QStringList out;
    for (int i = 0; i < n; ++i)
        if (!QFile::exists(partsDir() + QLatin1Char('/') + QString::number(i)))
            out << QString::number(i);
    return out;
}

// ---- the new device

QString Move::showCode()
{
    const QJsonObject cur = load(QStringLiteral("in"));
    if (jstr(cur, "state") == QLatin1String("hello") || jstr(cur, "state") == QLatin1String("receiving"))
        return jstr(cur, "code");
    const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
    QByteArray secret(16, 0);
    for (char &c : secret)
        c = char(QRandomGenerator::system()->bounded(256));
    QStringList addrs;
    for (const auto &[ip, prefix] : mine())
        addrs << ip + QStringLiteral(":53317");
    // Never the computer's name: the person's own chat name.
    const QString c = addrs.isEmpty() ? QString() : MoveWire::code({addrs, b64(kp.publicDer), secret, m_chat->username().left(40), QStringLiteral("huginn")});
    QDir(partsDir()).removeRecursively();
    // The Wi-Fi side must be up to be reached.
    if (!m_net->running())
        m_net->start();
    store(QStringLiteral("in"), {{QStringLiteral("state"), QStringLiteral("shown")}, {QStringLiteral("code"), c}, {QStringLiteral("priv"), b64(kp.privateDer)},
                                 {QStringLiteral("pub"), b64(kp.publicDer)}, {QStringLiteral("s"), b64(secret)}});
    return c;
}

void Move::confirmHere()
{
    QJsonObject s = load(QStringLiteral("in"));
    if (s.isEmpty())
        return;
    s.insert(QStringLiteral("ok"), true);
    store(QStringLiteral("in"), s);
    finishIfReady();
}

void Move::cancelHere()
{
    QDir(partsDir()).removeRecursively();
    store(QStringLiteral("in"), {});
}

QPair<int, QByteArray> Move::handle(const QByteArray &body)
{
    if (!m_chat->ready() || m_vault->locked())
        return {503, "{}"};
    const QJsonObject p = QJsonDocument::fromJson(body).object();
    QJsonObject s = load(QStringLiteral("in"));
    if (s.isEmpty())
        return {404, "{}"};
    const QString t = jstr(p, "t");
    const QByteArray k = b64d(jstr(s, "k"));
    const bool done = jstr(s, "state") == QLatin1String("done");
    if (done && (t == QLatin1String("hello") || t == QLatin1String("part")))
        return {409, "{}"};
    if (t == QLatin1String("hello")) {
        const QString e = jstr(p, "e");
        if (s.contains(QStringLiteral("old")) && jstr(s, "old") != e && jstr(s, "state") != QLatin1String("shown"))
            return {409, "{}"};
        const QByteArray key = MoveWire::key(b64d(jstr(s, "priv")), e, b64d(jstr(s, "s")), jstr(s, "pub"), e);
        if (key.isEmpty())
            return {400, "{}"};
        if (!MoveWire::same(jstr(p, "m"), MoveWire::mac(key, QStringLiteral("hello|") + e)))
            return {403, "{}"};
        if (jstr(s, "old") != e) {
            s.insert(QStringLiteral("old"), e);
            s.insert(QStringLiteral("k"), b64(key));
            s.insert(QStringLiteral("from"), jstr(p, "n").left(40));
            s.insert(QStringLiteral("emoji"), MoveWire::emoji(key));
            s.insert(QStringLiteral("state"), QStringLiteral("hello"));
            s.insert(QStringLiteral("ok"), false);
            store(QStringLiteral("in"), s);
        }
        return {200, compact({{QStringLiteral("m"), MoveWire::mac(key, QStringLiteral("ready|") + e)}})};
    }
    if (k.isEmpty())
        return {403, "{}"};
    if (t == QLatin1String("part")) {
        const int i = p.value(QStringLiteral("i")).toInt(-1), n = p.value(QStringLiteral("n")).toInt();
        const QString h = jstr(p, "h");
        if (n < 1 || n > 100000 || i < 0 || i >= n || h.size() != 64)
            return {400, "{}"};
        bool ok = false;
        MoveWire::open(k, jstr(p, "d"), MoveWire::partAad(i, n, h), &ok);
        if (!ok)
            return {403, "{}"};
        if (jstr(s, "h") != h) {
            QDir(partsDir()).removeRecursively();
            s.insert(QStringLiteral("h"), h);
            s.insert(QStringLiteral("n"), n);
        }
        QDir().mkpath(partsDir());
        // Kept as it came (still sealed with K) until it's all here.
        QFile f(partsDir() + QLatin1Char('/') + QString::number(i));
        if (f.open(QIODevice::WriteOnly))
            f.write(jstr(p, "d").toLatin1());
        f.close();
        if (jstr(s, "state") == QLatin1String("hello"))
            s.insert(QStringLiteral("state"), QStringLiteral("receiving"));
        store(QStringLiteral("in"), s);
        return {200, compact({{QStringLiteral("have"), n - int(missing().size())}})};
    }
    if (t == QLatin1String("status")) {
        const QString h = jstr(s, "h");
        QString st;
        const QStringList miss = missing();
        if (jstr(s, "state") == QLatin1String("done"))
            st = QStringLiteral("done");
        else if (h.isEmpty() || !miss.isEmpty())
            st = QStringLiteral("need");
        else if (!s.value(QStringLiteral("ok")).toBool())
            st = QStringLiteral("confirm");
        else {
            st = QStringLiteral("taking");
            QTimer::singleShot(0, this, &Move::finishIfReady);
        }
        QJsonArray m;
        for (int i = 0; i < qMin(64, int(miss.size())); ++i)
            m << miss.at(i).toInt();
        return {200, compact({{QStringLiteral("state"), st}, {QStringLiteral("missing"), m}, {QStringLiteral("m"), MoveWire::mac(k, QStringLiteral("status|") + st + QLatin1Char('|') + h)}})};
    }
    if (t == QLatin1String("link")) {
        // Only right after the move (10 minutes), and only once the person says yes to "Link [name]?".
        if (!done || QDateTime::currentMSecsSinceEpoch() - s.value(QStringLiteral("at")).toInteger() > kLinkMs) {
            store(QStringLiteral("in"), {});
            return {403, "{}"};
        }
        bool ok = false;
        const QString code = QString::fromUtf8(MoveWire::open(k, jstr(p, "d"), kLinkAad, &ok));
        if (!ok)
            return {403, "{}"};
        m_linkCode = code;
        m_linkAsk = jstr(s, "from").isEmpty() ? QStringLiteral("your old device") : jstr(s, "from");
        Q_EMIT changed();
        return {200, "{}"};
    }
    return {400, "{}"};
}

void Move::finishIfReady()
{
    QJsonObject s = load(QStringLiteral("in"));
    if (!s.value(QStringLiteral("ok")).toBool() || jstr(s, "state") != QLatin1String("receiving") || !missing().isEmpty() || m_busy)
        return;
    const int n = s.value(QStringLiteral("n")).toInt();
    const QString h = jstr(s, "h");
    const QByteArray k = b64d(jstr(s, "k"));
    QByteArray blob;
    for (int i = 0; i < n; ++i) {
        QFile f(partsDir() + QLatin1Char('/') + QString::number(i));
        bool ok = false;
        const QByteArray part = f.open(QIODevice::ReadOnly) ? MoveWire::open(k, QString::fromLatin1(f.readAll()), MoveWire::partAad(i, n, h), &ok) : QByteArray();
        if (!ok) {
            f.remove();
            return;
        }
        blob += part;
    }
    if (sha(blob).toHex() != h.toLatin1()) {
        QDir(partsDir()).removeRecursively();
        return;
    }
    Vault::Files entries;
    if (!MoveWire::unpack(blob, &entries))
        return;
    QString error;
    Vault::Files files = entries;
    for (const auto &e : entries)
        if (e.first == kCommon)
            files = fromCommon(QJsonDocument::fromJson(e.second).object(), &error);
    if (files.empty()) {
        Q_EMIT notice(QStringLiteral("The move couldn't be read: ") + error, true);
        return;
    }
    m_busy = true;
    // Done, and when: kept in the restored vault, so the old device hears "done" and may ask to link for 10 minutes.
    s.insert(QStringLiteral("state"), QStringLiteral("done"));
    s.insert(QStringLiteral("at"), QDateTime::currentMSecsSinceEpoch());
    store(QStringLiteral("in"), s);
    Vault::Files kept;
    for (const auto &e : files)
        if (e.first != QLatin1String("move.json") && e.first != QLatin1String("move-out.bin"))
            kept.push_back(e);
    kept.emplace_back(QStringLiteral("move.json"), compact({{QStringLiteral("in"), s}}));
    files = kept;
    QDir(partsDir()).removeRecursively();
    if (!m_vault->takeEverything(files, &error)) {
        s.insert(QStringLiteral("state"), QStringLiteral("receiving"));
        store(QStringLiteral("in"), s);
        m_busy = false;
        Q_EMIT notice(error, true);
    }
}

// ---- the old device

void Move::post(const QString &addr, const QJsonObject &body, std::function<void(int, const QByteArray &)> done, bool http)
{
    QNetworkRequest rq(QUrl((http ? QStringLiteral("http://") : QStringLiteral("https://")) + addr + QLatin1String(MoveWire::kPath)));
    rq.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    rq.setTransferTimeout(30000);
    QNetworkReply *r = m_http.post(rq, compact(body));
    // Any certificate: everything inside is sealed with K.
    connect(r, &QNetworkReply::sslErrors, r, [r] { r->ignoreSslErrors(); });
    connect(r, &QNetworkReply::finished, this, [this, r, addr, body, done, http] {
        r->deleteLater();
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (code == 0 && !http) {
            post(addr, body, done, true);
            return;
        }
        done(code, r->readAll());
    });
}

void Move::take(const QString &text)
{
    MoveWire::Code c;
    if (!MoveWire::parse(text, &c)) {
        Q_EMIT notice(QStringLiteral("That isn't a move code from a device on your Wi-Fi. On the new device: Move here."), true);
        return;
    }
    // Only addresses on this computer's own network.
    QStringList addrs;
    const auto nets = this->mine();
    for (const QString &a : std::as_const(c.a))
        for (const auto &[ip, prefix] : nets)
            if (MoveWire::sameNetwork(a.section(QLatin1Char(':'), 0, 0), ip, prefix) && !addrs.contains(a))
                addrs << a;
    if (addrs.isEmpty()) {
        Q_EMIT notice(QStringLiteral("%1 isn't on the same Wi-Fi as this device. Moving only works on your own network.").arg(c.n), true);
        return;
    }
    c.a = addrs;
    const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
    const QString mine = b64(kp.publicDer);
    const QByteArray k = MoveWire::key(kp.privateDer, c.e, c.s, c.e, mine);
    const QJsonObject hello{{QStringLiteral("t"), QStringLiteral("hello")}, {QStringLiteral("e"), mine}, {QStringLiteral("n"), m_chat->username().left(40)},
                            {QStringLiteral("app"), QStringLiteral("huginn")}, {QStringLiteral("m"), MoveWire::mac(k, QStringLiteral("hello|") + mine)}};
    auto left = std::make_shared<int>(int(c.a.size()));
    auto found = std::make_shared<bool>(false);
    for (const QString &addr : std::as_const(c.a))
        post(addr, hello, [this, addr, c, k, mine, left, found](int code, const QByteArray &reply) {
            --*left;
            if (!*found && code == 200 && MoveWire::same(jstr(QJsonDocument::fromJson(reply).object(), "m"), MoveWire::mac(k, QStringLiteral("ready|") + mine))) {
                *found = true;
                store(QStringLiteral("out"), {{QStringLiteral("state"), QStringLiteral("checking")}, {QStringLiteral("addr"), addr},
                                              {QStringLiteral("all"), QJsonArray::fromStringList(c.a)}, {QStringLiteral("k"), b64(k)}, {QStringLiteral("to"), c.n},
                                              {QStringLiteral("app"), c.app}, {QStringLiteral("emoji"), MoveWire::emoji(k)}});
            }
            if (*left == 0 && !*found)
                Q_EMIT notice(QStringLiteral("Couldn't reach %1. Both need to be on the same Wi-Fi, with the Move here screen open there.").arg(c.n), true);
        });
}

void Move::go()
{
    QJsonObject o = load(QStringLiteral("out"));
    if (jstr(o, "state") != QLatin1String("checking"))
        return;
    m_locked = true;
    m_chat->setMoving(true);
    Vault::Files entries;
    if (jstr(o, "app") == QLatin1String("huginn"))
        entries = m_vault->everything();
    else
        entries.emplace_back(kCommon, compact(common()));
    if (entries.empty()) {
        m_locked = false;
        m_chat->setMoving(false);
        Q_EMIT notice(QStringLiteral("Couldn't gather your chats. Unlock Chat and try again."), true);
        return;
    }
    Vault::Files keep;
    for (const auto &e : entries)
        if (e.first != QLatin1String("move.json") && e.first != QLatin1String("move-out.bin"))
            keep.push_back(e);
    const QByteArray blob = MoveWire::pack(keep);
    // Kept in the vault until the move is done, so an interrupted one goes on after a restart.
    m_vault->write(QStringLiteral("move-out.bin"), blob);
    o.insert(QStringLiteral("state"), QStringLiteral("moving"));
    o.insert(QStringLiteral("h"), QString::fromLatin1(sha(blob).toHex()));
    o.insert(QStringLiteral("n"), MoveWire::parts(blob.size()));
    store(QStringLiteral("out"), o);
    m_blob = blob;
    m_lastHeard = QDateTime::currentMSecsSinceEpoch();
    m_timer.start();
}

void Move::cancelOut()
{
    if (!m_locked)
        store(QStringLiteral("out"), {});
}

void Move::tick()
{
    const QJsonObject o = load(QStringLiteral("out"));
    if (jstr(o, "state") != QLatin1String("moving")) {
        m_timer.stop();
        return;
    }
    if (m_busy)
        return;
    const QByteArray k = b64d(jstr(o, "k"));
    const QString h = jstr(o, "h");
    const int n = o.value(QStringLiteral("n")).toInt();
    if (m_blob.isEmpty()) {
        // After a restart: the payload comes back from the vault once it's open.
        if (m_vault->locked())
            return;
        m_blob = m_vault->read(QStringLiteral("move-out.bin"));
        if (m_blob.isEmpty())
            return;
    }
    m_busy = true;
    const QString addr = jstr(o, "addr");
    post(addr, {{QStringLiteral("t"), QStringLiteral("status")}}, [this, k, h, n, addr, o](int code, const QByteArray &reply) {
        const QJsonObject j = QJsonDocument::fromJson(reply).object();
        const QString st = jstr(j, "state");
        const bool good = code == 200 && (MoveWire::same(jstr(j, "m"), MoveWire::mac(k, QStringLiteral("status|") + st + QLatin1Char('|') + h))
                                          || (st == QLatin1String("need") && MoveWire::same(jstr(j, "m"), MoveWire::mac(k, QStringLiteral("status|need|")))));
        if (!good) {
            m_busy = false;
            if (QDateTime::currentMSecsSinceEpoch() - m_lastHeard > kGiveUpMs) {
                stopMoving(QStringLiteral("%1 wasn't heard from for an hour, so moving stopped. This computer is yours again.").arg(jstr(o, "to")));
                return;
            }
            m_out.insert(QStringLiteral("note"), QStringLiteral("Waiting for %1 on the Wi-Fi…").arg(jstr(o, "to")));
            Q_EMIT changed();
            return;
        }
        m_lastHeard = QDateTime::currentMSecsSinceEpoch();
        if (st == QLatin1String("done")) {
            finishMoved();
            return;
        }
        if (st == QLatin1String("confirm")) {
            m_out.insert(QStringLiteral("sent"), n);
            m_out.insert(QStringLiteral("note"), QStringLiteral("Tap \"They match\" on %1.").arg(jstr(o, "to")));
            Q_EMIT changed();
            m_busy = false;
            return;
        }
        if (st != QLatin1String("need")) {
            m_busy = false;
            return;
        }
        QList<int> want;
        for (const QJsonValue &v : j.value(QStringLiteral("missing")).toArray())
            want << v.toInt();
        if (want.isEmpty())
            for (int i = 0; i < n; ++i)
                want << i;
        // One part at a time, then ask again.
        auto next = std::make_shared<std::function<void()>>();
        auto at = std::make_shared<int>(0);
        *next = [this, want, at, next, k, h, n, addr] {
            if (*at >= want.size()) {
                m_busy = false;
                return;
            }
            const int i = want.at((*at)++);
            const QByteArray part = m_blob.mid(qsizetype(i) * MoveWire::kPart, MoveWire::kPart);
            post(addr, {{QStringLiteral("t"), QStringLiteral("part")}, {QStringLiteral("i"), i}, {QStringLiteral("n"), n}, {QStringLiteral("h"), h},
                        {QStringLiteral("d"), MoveWire::seal(k, part, MoveWire::partAad(i, n, h))}},
                 [this, next](int c, const QByteArray &r) {
                     if (c == 200) {
                         m_out.insert(QStringLiteral("sent"), QJsonDocument::fromJson(r).object().value(QStringLiteral("have")).toInt());
                         m_out.insert(QStringLiteral("note"), QString());
                         Q_EMIT changed();
                     }
                     (*next)();
                 });
        };
        (*next)();
    });
}

void Move::finishMoved()
{
    const QJsonObject o = load(QStringLiteral("out"));
    m_timer.stop();
    m_vault->remove(QStringLiteral("move-out.bin"));
    // Only what becoming a linked device needs, in memory for 10 minutes: where the new device is and K.
    m_movedK = b64d(jstr(o, "k"));
    m_movedAddr = jstr(o, "addr");
    m_movedTo = jstr(o, "to");
    m_linkTimer.start();
    m_vault->remove(QStringLiteral("move.json"));
    m_vault->eraseAll();
    // The vault is gone: "Moved to" is shown from memory for the rest of this run, and nothing about
    // where the chats went stays on this computer.
    m_mem.insert(QStringLiteral("out"), QJsonObject{{QStringLiteral("state"), QStringLiteral("moved")}, {QStringLiteral("to"), m_movedTo}});
    m_blob.clear();
    m_locked = false;
    m_busy = false;
    m_chat->setMoving(false);
    publish();
}

void Move::stopMoving(const QString &note)
{
    m_timer.stop();
    m_vault->remove(QStringLiteral("move-out.bin"));
    m_blob.clear();
    m_locked = false;
    m_busy = false;
    m_chat->setMoving(false);
    store(QStringLiteral("out"), {});
    Q_EMIT notice(note, false);
}

bool Move::abort(const QString &secret)
{
    // Stopping a move under way needs the person's own password or pattern.
    if (!m_locked || !m_vault->unlock(secret))
        return false;
    stopMoving(QStringLiteral("Moving stopped. This computer is yours again."));
    return true;
}

void Move::answerLink(bool yes)
{
    const QString code = m_linkCode;
    m_linkCode.clear();
    m_linkAsk.clear();
    if (yes && !code.isEmpty()) {
        const QString err = m_link->link(code, true);
        if (!err.isEmpty())
            Q_EMIT notice(err, true);
    }
    store(QStringLiteral("in"), {});
}

void Move::becomeLinked()
{
    if (m_movedK.isEmpty()) {
        Q_EMIT notice(QStringLiteral("That was more than 10 minutes ago. Link it from Linked devices instead."), true);
        return;
    }
    if (!m_chat->ready()) {
        Q_EMIT notice(QStringLiteral("Set up Huginn's lock first, then tap this again."), true);
        return;
    }
    const QString code = m_link->newCode();
    const QJsonObject body{{QStringLiteral("t"), QStringLiteral("link")}, {QStringLiteral("d"), MoveWire::seal(m_movedK, code.toUtf8(), kLinkAad)}};
    const QString to = m_movedTo;
    post(m_movedAddr, body, [this, to](int c, const QByteArray &) {
        if (c == 200) {
            startFresh();
            Q_EMIT notice(QStringLiteral("Asked %1. Say yes there, and your chats show up here.").arg(to), false);
        } else {
            Q_EMIT notice(QStringLiteral("Couldn't reach %1. Open it on the same Wi-Fi and try again.").arg(to), true);
        }
    });
}

void Move::startFresh()
{
    m_movedK.clear();
    m_linkTimer.stop();
    store(QStringLiteral("out"), {});
}

QList<QPair<QString, int>> Move::mine()
{
    QList<QPair<QString, int>> out;
    for (const QNetworkInterface &nif : QNetworkInterface::allInterfaces()) {
        if (!(nif.flags() & QNetworkInterface::IsUp) || (nif.flags() & QNetworkInterface::IsLoopBack))
            continue;
        for (const QNetworkAddressEntry &e : nif.addressEntries())
            if (e.ip().protocol() == QAbstractSocket::IPv4Protocol && MoveWire::lanIp(e.ip().toString()))
                out << qMakePair(e.ip().toString(), e.prefixLength());
    }
    return out;
}

// ------------------------------------------------------------------ between Huginn and Muninn (18.4)

QJsonObject Move::common() const
{
    const Chat *c = m_chat;
    const QJsonObject secrets = QJsonDocument::fromJson(m_vault->read(QStringLiteral("secrets.json"))).object();
    QJsonArray contacts;
    QJsonObject ratchets;
    auto addContact = [&](const QString &key, const QVariantMap &m, bool peer) {
        const QStringList caps = c->m_caps.value(key);
        QJsonObject o{{QStringLiteral("k"), key}, {QStringLiteral("n"), str(m, "name")}, {QStringLiteral("p"), str(m, "mailbox")},
                      {QStringLiteral("v"), !peer && m.value(QStringLiteral("verified")).toBool()}, {QStringLiteral("i"), str(m, "invite")},
                      {QStringLiteral("r"), peer || c->m_capable.value(key)}, {QStringLiteral("nick"), str(m, "nick")},
                      {QStringLiteral("kc"), m.value(QStringLiteral("keyChanged")).toBool()}, {QStringLiteral("go"), peer},
                      {QStringLiteral("cp"), (peer && caps.isEmpty() ? QStringList{QStringLiteral("c1"), QStringLiteral("g1"), QStringLiteral("r1")} : caps).join(QLatin1Char(' '))}};
        for (const auto &[from, to] : {std::pair{"noCalls", "xc"}, std::pair{"noFiles", "xf"}, std::pair{"hideActive", "xa"}})
            if (m.value(QLatin1String(from)).toBool())
                o.insert(QLatin1String(to), true);
        contacts << o;
        const auto p = c->m_peers.constFind(key);
        if (p != c->m_peers.cend())
            ratchets.insert(key, peerConvert(p->toJson(), true));
    };
    for (auto it = c->m_contacts.cbegin(); it != c->m_contacts.cend(); ++it)
        addContact(it.key(), it.value(), false);
    for (auto it = c->m_gpeers.cbegin(); it != c->m_gpeers.cend(); ++it)
        addContact(it.key(), it.value(), true);
    QJsonObject chats;
    for (auto it = c->m_private.cbegin(); it != c->m_private.cend(); ++it) {
        QJsonArray list;
        for (const QVariant &v : it.value()) {
            const QVariantMap m = v.toMap();
            list << QJsonObject{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("peer"), it.key()}, {QStringLiteral("name"), str(m, "name")},
                                {QStringLiteral("text"), words(m, str(m, "text"))}, {QStringLiteral("mine"), m.value(QStringLiteral("mine")).toBool()},
                                {QStringLiteral("time"), m.value(QStringLiteral("time")).toLongLong()}, {QStringLiteral("failed"), m.value(QStringLiteral("failed")).toBool()},
                                {QStringLiteral("via"), str(m, "via")}, {QStringLiteral("state"), str(m, "state")}, {QStringLiteral("photo"), QString()},
                                {QStringLiteral("unsent"), str(m, "unsent")}, {QStringLiteral("voice"), 0}};
        }
        chats.insert(it.key(), list);
    }
    QJsonArray groups;
    QJsonObject gchats;
    for (auto it = c->m_groups.cbegin(); it != c->m_groups.cend(); ++it) {
        if (it->state.isEmpty() || it->removed || it->left)
            continue;
        QJsonArray h;
        for (const QJsonObject &s : it->history)
            h << s;
        groups << QJsonObject{{QStringLiteral("s"), it->state}, {QStringLiteral("h"), h}};
        QJsonArray list;
        for (const QVariant &v : it->messages) {
            const QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("system")).toBool())
                continue;
            list << QJsonObject{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("ch"), str(m, "ch")}, {QStringLiteral("f"), str(m, "from")},
                                {QStringLiteral("n"), str(m, "name")}, {QStringLiteral("x"), words(m, str(m, "text"))}, {QStringLiteral("m"), m.value(QStringLiteral("mine")).toBool()},
                                {QStringLiteral("t"), m.value(QStringLiteral("time")).toLongLong()}, {QStringLiteral("st"), str(m, "state")}};
        }
        gchats.insert(it.key(), list);
    }
    QJsonArray folders;
    for (const QVariantMap &f : c->m_folders)
        folders << QJsonObject{{QStringLiteral("id"), str(f, "id")}, {QStringLiteral("n"), str(f, "name")},
                               {QStringLiteral("k"), QJsonArray::fromStringList(f.value(QStringLiteral("keys")).toStringList())},
                               {QStringLiteral("c"), f.value(QStringLiteral("collapsed")).toBool()}};
    const QJsonObject me{{QStringLiteral("name"), c->username()}, {QStringLiteral("pub"), b64(c->m_pub)}, {QStringLiteral("priv"), b64(c->m_priv)},
                         {QStringLiteral("nostr"), jstr(secrets, "nostrSecret")}};
    QJsonObject out{{QStringLiteral("v"), 1}, {QStringLiteral("me"), me}, {QStringLiteral("contacts"), contacts}, {QStringLiteral("ratchets"), ratchets},
                    {QStringLiteral("chats"), chats}, {QStringLiteral("groups"), groups}, {QStringLiteral("gchats"), gchats},
                    {QStringLiteral("folders"), QJsonObject{{QStringLiteral("folders"), folders}, {QStringLiteral("top"), QJsonArray::fromStringList(c->m_topOrder)}}}};
    // My profile picture, colour and status (cap p1), as Profile keeps them.
    const QJsonObject p = QJsonDocument::fromJson(m_vault->read(QStringLiteral("profile/me.json"))).object();
    if (!p.isEmpty())
        out.insert(QStringLiteral("profile"), QJsonObject{{QStringLiteral("v"), p.value(QStringLiteral("v")).toInteger()}, {QStringLiteral("col"), jstr(p, "col")},
                                                          {QStringLiteral("pic"), jstr(p, "pic")}, {QStringLiteral("st"), jstr(p, "st")}, {QStringLiteral("cs"), jstr(p, "cs")}});
    return out;
}

Vault::Files Move::fromCommon(const QJsonObject &o, QString *error) const
{
    Vault::Files out;
    const QJsonObject me = o.value(QStringLiteral("me")).toObject();
    if (o.value(QStringLiteral("v")).toInt() != 1 || b64d(jstr(me, "priv")).isEmpty() || b64d(jstr(me, "pub")).isEmpty()) {
        *error = QStringLiteral("it has no identity in it.");
        return out;
    }
    out.emplace_back(QStringLiteral("secrets.json"), compact({{QStringLiteral("chatPriv"), jstr(me, "priv")}, {QStringLiteral("nostrSecret"), jstr(me, "nostr")}}));
    QJsonArray contacts;
    QJsonObject peers;
    const QJsonObject ratchets = o.value(QStringLiteral("ratchets")).toObject();
    for (const QJsonValue &v : o.value(QStringLiteral("contacts")).toArray()) {
        const QJsonObject c = v.toObject();
        const QString key = jstr(c, "k");
        if (key.isEmpty())
            continue;
        if (c.value(QStringLiteral("go")).toBool()) {
            peers.insert(key, QJsonObject{{QStringLiteral("key"), key}, {QStringLiteral("name"), jstr(c, "n")}, {QStringLiteral("mailbox"), jstr(c, "p")}});
        } else {
            QJsonObject e{{QStringLiteral("key"), key}, {QStringLiteral("name"), jstr(c, "n")}, {QStringLiteral("mailbox"), jstr(c, "p")},
                          {QStringLiteral("verified"), c.value(QStringLiteral("v")).toBool()}};
            if (!jstr(c, "nick").isEmpty())
                e.insert(QStringLiteral("nick"), jstr(c, "nick"));
            if (!jstr(c, "i").isEmpty())
                e.insert(QStringLiteral("invite"), jstr(c, "i"));
            if (c.value(QStringLiteral("kc")).toBool())
                e.insert(QStringLiteral("keyChanged"), true);
            for (const auto &[from, to] : {std::pair{"xc", "noCalls"}, std::pair{"xf", "noFiles"}, std::pair{"xa", "hideActive"}})
                if (c.value(QLatin1String(from)).toBool())
                    e.insert(QLatin1String(to), true);
            contacts << e;
        }
        // Huginn keeps caps and the ratchet together, per person.
        QJsonObject r{{QStringLiteral("key"), key}, {QStringLiteral("cap"), c.value(QStringLiteral("r")).toBool()}};
        if (!jstr(c, "cp").isEmpty())
            r.insert(QStringLiteral("caps"), jstr(c, "cp"));
        if (ratchets.contains(key))
            r.insert(QStringLiteral("peer"), peerConvert(ratchets.value(key).toObject(), false));
        out.emplace_back(QStringLiteral("ratchet/") + QString::fromLatin1(sha(key.toUtf8()).toHex().left(32)) + QStringLiteral(".json"), compact(r));
    }
    out.emplace_back(QStringLiteral("contacts.json"), QJsonDocument(contacts).toJson(QJsonDocument::Compact));
    out.emplace_back(QStringLiteral("chats.json"), compact(o.value(QStringLiteral("chats")).toObject()));
    QJsonObject groups, gchats;
    const QJsonObject gc = o.value(QStringLiteral("gchats")).toObject();
    for (const QJsonValue &v : o.value(QStringLiteral("groups")).toArray()) {
        const QJsonObject s = v.toObject().value(QStringLiteral("s")).toObject();
        const QString g = jstr(s, "g");
        if (g.isEmpty())
            continue;
        groups.insert(g, QJsonObject{{QStringLiteral("state"), s}, {QStringLiteral("history"), v.toObject().value(QStringLiteral("h")).toArray()},
                                     {QStringLiteral("held"), QJsonArray()}, {QStringLiteral("unread"), QJsonObject()}, {QStringLiteral("lastOp"), QJsonObject()},
                                     {QStringLiteral("removed"), false}, {QStringLiteral("left"), false}, {QStringLiteral("since"), 0}});
        QJsonArray list;
        for (const QJsonValue &x : gc.value(g).toArray()) {
            const QJsonObject m = x.toObject();
            list << QJsonObject{{QStringLiteral("id"), jstr(m, "id")}, {QStringLiteral("ch"), jstr(m, "ch").isEmpty() ? QStringLiteral("main") : jstr(m, "ch")},
                                {QStringLiteral("from"), jstr(m, "f")}, {QStringLiteral("name"), jstr(m, "n")}, {QStringLiteral("text"), jstr(m, "x")},
                                {QStringLiteral("mine"), m.value(QStringLiteral("m")).toBool()}, {QStringLiteral("time"), m.value(QStringLiteral("t")).toInteger()},
                                {QStringLiteral("ts"), m.value(QStringLiteral("t")).toInteger()}, {QStringLiteral("via"), QString()}, {QStringLiteral("state"), jstr(m, "st")},
                                {QStringLiteral("photo"), QString()}, {QStringLiteral("voice"), 0}, {QStringLiteral("unsent"), QString()}};
        }
        gchats.insert(g, list);
    }
    out.emplace_back(QStringLiteral("groups.json"), compact({{QStringLiteral("groups"), groups}, {QStringLiteral("peers"), peers}, {QStringLiteral("invites"), QJsonObject()},
                                                             {QStringLiteral("joining"), QJsonObject()}, {QStringLiteral("tokens"), QJsonObject()}, {QStringLiteral("out"), QJsonObject()}}));
    out.emplace_back(QStringLiteral("gchats.json"), compact(gchats));
    const QJsonObject fo = o.value(QStringLiteral("folders")).toObject();
    QJsonArray folders;
    for (const QJsonValue &v : fo.value(QStringLiteral("folders")).toArray()) {
        const QJsonObject f = v.toObject();
        folders << QJsonObject{{QStringLiteral("id"), jstr(f, "id")}, {QStringLiteral("name"), jstr(f, "n")}, {QStringLiteral("keys"), f.value(QStringLiteral("k")).toArray()},
                               {QStringLiteral("collapsed"), f.value(QStringLiteral("c")).toBool()}};
    }
    out.emplace_back(QStringLiteral("folders.json"), compact({{QStringLiteral("folders"), folders}, {QStringLiteral("top"), fo.value(QStringLiteral("top")).toArray()}}));
    // My profile picture, colour and status (cap p1), as Profile keeps them.
    const QJsonObject p = o.value(QStringLiteral("profile")).toObject();
    if (!p.isEmpty())
        out.emplace_back(QStringLiteral("profile/me.json"), compact({{QStringLiteral("k"), QStringLiteral("me")}, {QStringLiteral("v"), p.value(QStringLiteral("v")).toInteger()},
                                                                     {QStringLiteral("col"), jstr(p, "col")}, {QStringLiteral("pic"), jstr(p, "pic")},
                                                                     {QStringLiteral("st"), jstr(p, "st")}, {QStringLiteral("cs"), jstr(p, "cs")}}));
    // The name of the one that moved here, in the vault like everything else.
    const QString name = jstr(me, "name").left(24).trimmed();
    if (!name.isEmpty())
        out.emplace_back(QStringLiteral("prefs.json"), compact({{QStringLiteral("username"), name}}));
    // This device's settings, with the identity of the one that moved here (the inbox key is worked out again).
    const QVariantMap set{{QStringLiteral("chat/pub"), b64d(jstr(me, "pub")).toBase64()}};
    out.emplace_back(Vault::settingsEntry(), Vault::settingsWith(set, {QStringLiteral("nostr/pub"), QStringLiteral("chat/trusted"), QStringLiteral("chat/priv")}));
    return out;
}
