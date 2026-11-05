#pragma once

#include "chatcrypto.h"

#include <QByteArray>
#include <QJsonObject>
#include <QString>
#include <functional>
#include <utility>
#include <vector>

// Message ratchet (forward secrecy, Double Ratchet style) on top of the chat keys.
// shared/tools/ratchet_ref.py is the spec; shared/vectors/ratchet-vectors.json proves Huginn and Muninn agree
// (huginn --ratchet-selftest).
namespace Ratchet
{
// Makes the next ratchet key pair. ChatCrypto::newKeyPair in the app, fixed keys in the tests.
using NewKey = std::function<ChatCrypto::KeyPair()>;

// Sent in the clear next to each message, and bound to it as AAD.
struct Header {
    QString b;   // session base: the first sender's first ratchet key (base64 SPKI DER)
    QString d;   // the sender's current ratchet key (base64 SPKI DER)
    int pn = 0;  // how many messages the sender sent on its previous key
    int n = 0;   // this message's number on the current key
    // Cap s1: the envelope's bound clear fields (Proof::bound), sealed in too; never part of the header on the wire.
    QByteArray bind;

    QByteArray aad() const;
    QJsonObject toJson() const;
    static Header fromJson(const QJsonObject &o, bool *ok = nullptr);
};

class Session
{
public:
    QString base;
    QByteArray rk;
    ChatCrypto::KeyPair dhs;   // my current ratchet key
    QString dhr;               // their current ratchet key (base64), empty until known
    QByteArray cks, ckr;       // sending and receiving chain keys, empty until known
    int ns = 0, nr = 0, pn = 0;

    // Keys for messages that have not arrived yet, oldest first.
    struct Skipped {
        QString d;
        int n = 0;
        QByteArray mk;
    };
    std::vector<Skipped> skipped;

    // The first one to send. sk is the pair key from ChatCrypto::sharedKey.
    static Session alice(const QByteArray &sk, const QString &theirStaticB64, const ChatCrypto::KeyPair &eph);
    // The first one to receive (a message whose base we have never seen, with d == b).
    static Session bob(const QByteArray &sk, const ChatCrypto::KeyPair &myStatic, const QString &base);

    bool canSend() const { return cks.size() == 32; }
    // Empty cipher when this session can't send yet.
    std::pair<Header, QByteArray> encrypt(const QByteArray &plain, const QByteArray &bind = QByteArray());
    // Works on a copy and keeps it only when the message checks out; on any failure the
    // session is left exactly as it was and ok is false.
    QByteArray decrypt(const Header &h, const QByteArray &cipher, const NewKey &newKey, bool *ok);

    QJsonObject toJson() const;
    static Session fromJson(const QJsonObject &o, bool *ok = nullptr);
};

// Every session with one contact. Keeps the 3 most recently used.
class Peer
{
public:
    std::vector<Session> sessions;   // most recently used first
    QString active;                  // base of the session we send on

    // Sends on the active session, or starts a new one as Alice. Empty cipher on failure.
    std::pair<Header, QByteArray> encrypt(const QByteArray &sk, const QString &theirStaticB64, const QByteArray &plain,
                                          const NewKey &newKey, const QByteArray &bind = QByteArray());
    // Finds the session by header.b, or starts one as Bob. A message that opens makes its
    // session the active one, so two sides that started at once settle on one session.
    QByteArray decrypt(const QByteArray &sk, const ChatCrypto::KeyPair &myStatic, const Header &h, const QByteArray &cipher,
                       const NewKey &newKey, bool *ok);

    QJsonObject toJson() const;
    static Peer fromJson(const QJsonObject &o, bool *ok = nullptr);

private:
    Session *find(const QString &base);
    void touch(const QString &base);
};

// A P-256 key pair whose private key is the given number (tests only).
ChatCrypto::KeyPair keyFromNumber(quint64 n);
// Checks shared/vectors/ratchet-vectors.json (with shared/vectors/chat-vectors.json next to it); prints what it
// found. Returns true when everything matches.
bool selfTest(const QString &vectorsPath);
}
