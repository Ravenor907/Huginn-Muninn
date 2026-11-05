#include "stun.h"

#include <cstdio>

#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

namespace
{
quint16 get16(const QByteArray &b, int at) { return quint16((quint8(b[at]) << 8) | quint8(b[at + 1])); }
quint32 get32(const QByteArray &b, int at)
{
    return (quint32(quint8(b[at])) << 24) | (quint32(quint8(b[at + 1])) << 16) | (quint32(quint8(b[at + 2])) << 8) | quint32(quint8(b[at + 3]));
}
void put16(QByteArray &b, int at, quint16 v)
{
    b[at] = char(v >> 8);
    b[at + 1] = char(v);
}
QByteArray be16(quint16 v)
{
    QByteArray b(2, 0);
    put16(b, 0, v);
    return b;
}
QByteArray be32(quint32 v)
{
    QByteArray b(4, 0);
    b[0] = char(v >> 24);
    b[1] = char(v >> 16);
    b[2] = char(v >> 8);
    b[3] = char(v);
    return b;
}
int padded(int n) { return (n + 3) & ~3; }

QByteArray hmacSha1(const QByteArray &key, const QByteArray &data)
{
    QByteArray out(20, 0);
    unsigned int len = 20;
    HMAC(EVP_sha1(), key.constData(), int(key.size()), reinterpret_cast<const unsigned char *>(data.constData()), size_t(data.size()),
         reinterpret_cast<unsigned char *>(out.data()), &len);
    return out;
}

// Offset of the first attribute of this type in a well-formed message, or -1.
int findAttr(const QByteArray &b, quint16 type)
{
    if (b.size() < Stun::kHeader)
        return -1;
    const int end = qMin(int(b.size()), Stun::kHeader + int(get16(b, 2)));
    int at = Stun::kHeader;
    while (at + 4 <= end) {
        const quint16 t = get16(b, at), len = get16(b, at + 2);
        if (t == type)
            return at;
        at += 4 + padded(len);
    }
    return -1;
}
}

quint16 Stun::messageType(quint16 method, int cls)
{
    // M11..M7 C1 M6..M4 C0 M3..M0 in the low 14 bits.
    return quint16(((method & 0x0F80) << 2) | ((method & 0x0070) << 1) | (method & 0x000F) | ((cls & 2) << 7) | ((cls & 1) << 4));
}

QByteArray Stun::newTid()
{
    QByteArray t(12, 0);
    RAND_bytes(reinterpret_cast<unsigned char *>(t.data()), 12);
    return t;
}

void Stun::Message::addU32(quint16 type, quint32 v) { add(type, be32(v)); }

void Stun::Message::addAddress(quint16 type, const Address &a) { add(type, xorAddress(a, tid)); }

bool Stun::Message::has(quint16 type) const
{
    for (const auto &a : attrs)
        if (a.first == type)
            return true;
    return false;
}

QByteArray Stun::Message::get(quint16 type) const
{
    for (const auto &a : attrs)
        if (a.first == type)
            return a.second;
    return QByteArray();
}

quint32 Stun::Message::u32(quint16 type, quint32 fallback) const
{
    const QByteArray v = get(type);
    return v.size() >= 4 ? get32(v, 0) : fallback;
}

Stun::Address Stun::Message::address(quint16 type) const { return unxorAddress(get(type), tid); }

int Stun::Message::errorCode(QString *reason) const
{
    const QByteArray v = get(ErrorCode);
    if (v.size() < 4)
        return 0;
    if (reason)
        *reason = QString::fromUtf8(v.mid(4));
    return (quint8(v[2]) & 0x07) * 100 + quint8(v[3]);
}

QByteArray Stun::xorAddress(const Address &a, const QByteArray &tid)
{
    if (a.ip.size() != 4 && a.ip.size() != 16)
        return QByteArray();
    QByteArray v(4, 0);
    v[1] = char(a.ip.size() == 4 ? 0x01 : 0x02);
    put16(v, 2, quint16(a.port ^ (kCookie >> 16)));
    const QByteArray mask = be32(kCookie) + tid;
    for (int i = 0; i < a.ip.size(); ++i)
        v += char(a.ip[i] ^ mask[i]);
    return v;
}

Stun::Address Stun::unxorAddress(const QByteArray &v, const QByteArray &tid)
{
    Address a;
    if (v.size() < 8)
        return a;
    const int family = quint8(v[1]);
    const int n = family == 0x01 ? 4 : family == 0x02 ? 16 : 0;
    if (!n || v.size() < 4 + n || tid.size() != 12)
        return a;
    const QByteArray mask = be32(kCookie) + tid;
    a.port = quint16(get16(v, 2) ^ (kCookie >> 16));
    for (int i = 0; i < n; ++i)
        a.ip += char(v[4 + i] ^ mask[i]);
    return a;
}

QByteArray Stun::encode(const Message &m, const QByteArray &integrityKey, bool fingerprint, char pad)
{
    QByteArray b(kHeader, 0);
    put16(b, 0, messageType(m.method, m.cls));
    QByteArray cookie = be32(kCookie);
    for (int i = 0; i < 4; ++i)
        b[4 + i] = cookie[i];
    const QByteArray tid = m.tid.size() == 12 ? m.tid : QByteArray(12, 0);
    for (int i = 0; i < 12; ++i)
        b[8 + i] = tid[i];
    for (const auto &a : m.attrs) {
        if (a.first == MessageIntegrity || a.first == Fingerprint)
            continue;   // always computed here
        b += be16(a.first) + be16(quint16(a.second.size())) + a.second;
        b += QByteArray(padded(int(a.second.size())) - int(a.second.size()), pad);
    }
    if (!integrityKey.isEmpty()) {
        // The length covers the attributes up to and including MESSAGE-INTEGRITY.
        put16(b, 2, quint16(b.size() - kHeader + 24));
        b += be16(MessageIntegrity) + be16(20) + hmacSha1(integrityKey, b);
    }
    if (fingerprint) {
        put16(b, 2, quint16(b.size() - kHeader + 8));
        b += be16(Fingerprint) + be16(4) + be32(crc32(b) ^ 0x5354554E);
    }
    put16(b, 2, quint16(b.size() - kHeader));
    return b;
}

bool Stun::parse(const QByteArray &b, Message *out)
{
    if (!isStun(b))
        return false;
    const int len = get16(b, 2);
    if ((len & 3) || kHeader + len != b.size())
        return false;
    Message m;
    const quint16 type = get16(b, 0);
    m.method = quint16(((type >> 2) & 0x0F80) | ((type >> 1) & 0x0070) | (type & 0x000F));
    m.cls = ((type >> 7) & 2) | ((type >> 4) & 1);
    m.tid = b.mid(8, 12);
    int at = kHeader;
    while (at < b.size()) {
        if (at + 4 > b.size())
            return false;
        const quint16 t = get16(b, at), l = get16(b, at + 2);
        if (at + 4 + l > b.size())
            return false;
        m.attrs.emplace_back(t, b.mid(at + 4, l));
        at += 4 + padded(l);
    }
    if (out)
        *out = m;
    return true;
}

bool Stun::hasAttribute(const QByteArray &bytes, quint16 type) { return findAttr(bytes, type) >= 0; }

bool Stun::checkIntegrity(const QByteArray &b, const QByteArray &key)
{
    const int at = findAttr(b, MessageIntegrity);
    if (at < 0 || at + 24 > b.size() || get16(b, at + 2) != 20)
        return false;
    QByteArray pre = b.left(at);
    put16(pre, 2, quint16(at + 24 - kHeader));
    return hmacSha1(key, pre) == b.mid(at + 4, 20);
}

bool Stun::checkFingerprint(const QByteArray &b)
{
    const int at = findAttr(b, Fingerprint);
    if (at < 0)
        return true;
    if (at + 8 != b.size() || get16(b, at + 2) != 4)
        return false;
    QByteArray pre = b.left(at);
    put16(pre, 2, quint16(at + 8 - kHeader));
    return (crc32(pre) ^ 0x5354554E) == get32(b, at + 4);
}

QByteArray Stun::longTermKey(const QString &username, const QString &realm, const QString &password)
{
    const QByteArray in = username.toUtf8() + ":" + realm.toUtf8() + ":" + password.toUtf8();
    QByteArray out(16, 0);
    unsigned int len = 16;
    EVP_Digest(in.constData(), size_t(in.size()), reinterpret_cast<unsigned char *>(out.data()), &len, EVP_md5(), nullptr);
    return out;
}

quint32 Stun::crc32(const QByteArray &data)
{
    static quint32 table[256];
    static bool made = false;
    if (!made) {
        for (quint32 i = 0; i < 256; ++i) {
            quint32 c = i;
            for (int k = 0; k < 8; ++k)
                c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        made = true;
    }
    quint32 c = 0xFFFFFFFFu;
    for (int i = 0; i < data.size(); ++i)
        c = table[(c ^ quint8(data[i])) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

bool Stun::isStun(const QByteArray &b)
{
    return b.size() >= kHeader && (quint8(b[0]) & 0xC0) == 0 && get32(b, 4) == kCookie;
}

bool Stun::isChannelData(const QByteArray &b)
{
    return b.size() >= 4 && quint8(b[0]) >= 0x40 && quint8(b[0]) <= 0x4F;
}

QByteArray Stun::channelData(quint16 channel, const QByteArray &data, bool pad)
{
    QByteArray b = be16(channel) + be16(quint16(data.size())) + data;
    if (pad)
        b += QByteArray(padded(int(data.size())) - int(data.size()), 0);
    return b;
}

bool Stun::parseChannelData(const QByteArray &b, quint16 *channel, QByteArray *data)
{
    if (!isChannelData(b))
        return false;
    const int len = get16(b, 2);
    if (4 + len > b.size())
        return false;
    *channel = get16(b, 0);
    *data = b.mid(4, len);
    return true;
}

int Stun::frameLength(const QByteArray &buf)
{
    if (buf.size() < 4)
        return 0;
    const int first = quint8(buf[0]);
    if (first >= 0x40 && first <= 0x4F)
        return 4 + padded(get16(buf, 2));
    if ((first & 0xC0) == 0) {
        if (buf.size() >= 8 && get32(buf, 4) != kCookie)
            return -1;
        return kHeader + get16(buf, 2);
    }
    return -1;
}

// ------------------------------------------------------------------ self-test

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}
QByteArray h(const char *hex) { return QByteArray::fromHex(QByteArray(hex)); }
}

bool Stun::selfTest()
{
    g_fail = 0;
    std::printf("stun: RFC 5769 test vectors\n");
    const QByteArray shortKey("VOkJxbRl1RmTxUk/WvJxBt");

    // 2.1 Sample request (padding with spaces).
    const QByteArray req = h("000100582112a442b7e7a701bc34d686fa87dfae802200105354554e207465737420636c69656e74"
                             "002400046e0001ff80290008932ff9b151263b36000600096576746a3a6836765920202000080014"
                             "9aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a280280004e57a3bcf");
    Message m;
    check(parse(req, &m) && m.method == Binding && m.cls == Request && m.text(Username) == QLatin1String("evtj:h6vY")
              && m.text(Software) == QLatin1String("STUN test client") && m.u32(Priority) == 0x6e0001ff,
          "2.1 request parses");
    check(checkIntegrity(req, shortKey) && checkFingerprint(req), "2.1 integrity and fingerprint");
    check(!checkIntegrity(req, QByteArray("wrong password")), "2.1 refused with the wrong password");
    QByteArray bad = req;
    bad[30] = char(bad[30] ^ 1);
    check(!checkIntegrity(bad, shortKey) && !checkFingerprint(bad), "2.1 refused when a byte changes");
    // Rebuilt from its attributes with space padding, it is the same message to the byte.
    Message again;
    again.method = Binding;
    again.cls = Request;
    again.tid = m.tid;
    for (const auto &a : m.attrs)
        again.add(a.first, a.second);
    check(encode(again, shortKey, true, ' ') == req, "2.1 rebuilt byte for byte");

    // 2.2 IPv4 response.
    const QByteArray v4 = h("0101003c2112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220002000080001a147e112a643"
                            "000800142b91f599fd9e90c38c7489f92af9ba53f06be7d780280004c07d4c96");
    check(parse(v4, &m) && m.cls == Success && checkIntegrity(v4, shortKey) && checkFingerprint(v4), "2.2 parses, integrity, fingerprint");
    Address a4 = m.address(XorMappedAddress);
    check(a4.ip == h("c0000201") && a4.port == 32853, "2.2 XOR-MAPPED-ADDRESS is 192.0.2.1:32853");
    check(xorAddress(a4, m.tid) == m.get(XorMappedAddress), "2.2 address encodes back");

    // 2.3 IPv6 response.
    const QByteArray v6 = h("010100482112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220002000140002a1470113a9fa"
                            "a5d3f179bc25f4b5bed2b9d900080014a382954e4be67bf11784c97c8292c275bfe3ed4180280004c8fb0b4c");
    check(parse(v6, &m) && checkIntegrity(v6, shortKey) && checkFingerprint(v6), "2.3 parses, integrity, fingerprint");
    Address a6 = m.address(XorMappedAddress);
    check(a6.ip == h("20010db8123456780011223344556677") && a6.port == 32853, "2.3 XOR-MAPPED-ADDRESS is 2001:db8:1234:5678:11:2233:4455:6677");
    check(xorAddress(a6, m.tid) == m.get(XorMappedAddress), "2.3 address encodes back");

    // 2.4 Long-term credentials (padding with zeros): the way TURN authenticates.
    const QByteArray lt = h("000100602112a44278ad3433c6ad72c029da412e00060012e3839ee38388e383aae38383e382afe382b90000"
                            "0015001c662f2f3439396b39353464364f4c33346f4c39465354767936347341"
                            "0014000b6578616d706c652e6f726700"
                            "00080014f67024656dd64a3e02b8e0712e85c9a28ca89666");
    const QString user = QString::fromUtf8("\xe3\x83\x9e\xe3\x83\x88\xe3\x83\xaa\xe3\x83\x83\xe3\x82\xaf\xe3\x82\xb9");   // マトリックス
    const QByteArray ltKey = longTermKey(user, QStringLiteral("example.org"), QStringLiteral("TheMatrIX"));
    check(parse(lt, &m) && m.text(Username) == user && m.text(Realm) == QLatin1String("example.org")
              && m.text(Nonce) == QLatin1String("f//499k954d6OL34oL9FSTvy64sA"),
          "2.4 parses");
    check(checkIntegrity(lt, ltKey), "2.4 long-term integrity (MD5 key)");
    Message built;
    built.method = Binding;
    built.cls = Request;
    built.tid = m.tid;
    built.addText(Username, user);
    built.addText(Nonce, QStringLiteral("f//499k954d6OL34oL9FSTvy64sA"));
    built.addText(Realm, QStringLiteral("example.org"));
    check(encode(built, ltKey) == lt, "2.4 built byte for byte");

    // Message types: Allocate request 0x0003, success 0x0103, error 0x0113, ChannelBind 0x0009.
    check(messageType(Allocate, Request) == 0x0003 && messageType(Allocate, Success) == 0x0103 && messageType(Allocate, Error) == 0x0113
              && messageType(ChannelBind, Request) == 0x0009 && messageType(Data, Indication) == 0x0017 && messageType(Send, Indication) == 0x0016,
          "TURN message types");
    Message e;
    e.method = Allocate;
    e.cls = Error;
    e.tid = newTid();
    e.add(ErrorCode, h("00000401") + QByteArray("Unauthorized"));
    Message pe;
    QString why;
    check(parse(encode(e), &pe) && pe.method == Allocate && pe.cls == Error && pe.errorCode(&why) == 401 && why == QLatin1String("Unauthorized"),
          "error codes");

    // ChannelData and stream framing.
    const QByteArray cd = channelData(0x4001, QByteArray("abcde"), true);
    quint16 ch = 0;
    QByteArray data;
    check(cd.size() == 12 && frameLength(cd) == 12 && parseChannelData(cd, &ch, &data) && ch == 0x4001 && data == QByteArray("abcde"),
          "ChannelData, padded for TLS");
    check(channelData(0x4001, QByteArray("abcde"), false).size() == 9 && isChannelData(cd) && !isStun(cd), "ChannelData over UDP");
    check(frameLength(req) == req.size() && frameLength(req.left(3)) == 0 && frameLength(QByteArray("\x81\x00\x00\x00", 4)) == -1,
          "stream framing");
    const QByteArray media = QByteArray(1, char(0x81)) + QByteArray(27, 0);
    check(!isStun(media) && !isChannelData(media), "media packets are neither");
    std::printf("stun: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
