#pragma once

#include <QByteArray>
#include <QString>
#include <utility>
#include <vector>

// STUN messages (RFC 8489) as TURN (RFC 8656) uses them, and TURN ChannelData. Only bytes here:
// no sockets, so --call-selftest can check it against the RFC 5769 test vectors.
namespace Stun
{
const quint32 kCookie = 0x2112A442;
const int kHeader = 20;

enum Method : quint16 {
    Binding = 0x001,
    Allocate = 0x003,
    Refresh = 0x004,
    Send = 0x006,
    Data = 0x007,
    CreatePermission = 0x008,
    ChannelBind = 0x009,
};
enum Class { Request = 0, Indication = 1, Success = 2, Error = 3 };
enum Attr : quint16 {
    MappedAddress = 0x0001,
    Username = 0x0006,
    MessageIntegrity = 0x0008,
    ErrorCode = 0x0009,
    UnknownAttributes = 0x000A,
    ChannelNumber = 0x000C,
    Lifetime = 0x000D,
    XorPeerAddress = 0x0012,
    DataAttr = 0x0013,
    Realm = 0x0014,
    Nonce = 0x0015,
    XorRelayedAddress = 0x0016,
    RequestedAddressFamily = 0x0017,
    RequestedTransport = 0x0019,
    DontFragment = 0x001A,
    XorMappedAddress = 0x0020,
    Priority = 0x0024,
    Software = 0x8022,
    Fingerprint = 0x8028,
    IceControlled = 0x8029,
};

// An IP address and port as raw bytes: 4 bytes for IPv4, 16 for IPv6.
struct Address {
    QByteArray ip;
    quint16 port = 0;
    bool valid() const { return (ip.size() == 4 || ip.size() == 16) && port != 0; }
    bool operator==(const Address &o) const { return ip == o.ip && port == o.port; }
    bool operator!=(const Address &o) const { return !(*this == o); }
};

struct Message {
    quint16 method = 0;
    int cls = Request;
    QByteArray tid;                                     // 12 bytes
    std::vector<std::pair<quint16, QByteArray>> attrs;  // in order, values unpadded

    void add(quint16 type, const QByteArray &value) { attrs.emplace_back(type, value); }
    void addText(quint16 type, const QString &text) { add(type, text.toUtf8()); }
    void addU32(quint16 type, quint32 v);
    void addAddress(quint16 type, const Address &a);    // XOR-encoded with this message's tid
    bool has(quint16 type) const;
    QByteArray get(quint16 type) const;                 // the first one, or empty
    QString text(quint16 type) const { return QString::fromUtf8(get(type)); }
    quint32 u32(quint16 type, quint32 fallback = 0) const;
    Address address(quint16 type) const;                // XOR-decoded; invalid when absent
    int errorCode(QString *reason = nullptr) const;     // 0 when there's none
};

quint16 messageType(quint16 method, int cls);
QByteArray newTid();

// Bytes on the wire. With a key, MESSAGE-INTEGRITY (HMAC-SHA1) goes after the attributes, then
// FINGERPRINT when asked. pad is the padding byte (RFC 5769 shows both spaces and zeros).
QByteArray encode(const Message &m, const QByteArray &integrityKey = QByteArray(), bool fingerprint = false, char pad = 0);
// Structure only: header, cookie, attribute lengths. Integrity and fingerprint are separate checks.
bool parse(const QByteArray &bytes, Message *out);
// MESSAGE-INTEGRITY over the bytes as received; false when it's missing or wrong.
bool checkIntegrity(const QByteArray &bytes, const QByteArray &key);
// FINGERPRINT, when there is one; true when there's none.
bool checkFingerprint(const QByteArray &bytes);
bool hasAttribute(const QByteArray &bytes, quint16 type);
// TURN long-term credential key: MD5(username ":" realm ":" password). Cloudflare's names are ASCII,
// so no SASLprep is done.
QByteArray longTermKey(const QString &username, const QString &realm, const QString &password);
quint32 crc32(const QByteArray &data);

QByteArray xorAddress(const Address &a, const QByteArray &tid);
Address unxorAddress(const QByteArray &value, const QByteArray &tid);

// What a datagram (or the start of a stream) is.
bool isStun(const QByteArray &b);          // first two bits 00 and the magic cookie
bool isChannelData(const QByteArray &b);   // first byte 0x40 to 0x4F (channels 0x4000 to 0x4FFF)
// ChannelData: channel, length, data, padded to 4 bytes over TCP/TLS (pad true).
QByteArray channelData(quint16 channel, const QByteArray &data, bool pad);
// Splits one ChannelData message; false when it's malformed.
bool parseChannelData(const QByteArray &b, quint16 *channel, QByteArray *data);
// For a TLS stream: the size of the frame at the front of buf (with ChannelData padding), 0 when
// not enough has arrived to tell, -1 when the stream is out of step.
int frameLength(const QByteArray &buf);

// RFC 5769 (all four vectors), address coding both families, ChannelData and stream framing.
bool selfTest();
}
