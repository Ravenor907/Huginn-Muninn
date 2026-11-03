#pragma once

#include <QByteArray>
#include <QString>

// Messages between Huginn and Muninn: P-256 ECDH, HKDF-SHA256 and AES-256-GCM, exactly as
// Muninn's ChatCrypto.kt does it. shared/vectors/chat-vectors.json proves the two agree (huginn --chat-selftest).
namespace ChatCrypto
{
struct KeyPair {
    QByteArray privateDer;   // PKCS#8
    QByteArray publicDer;    // SubjectPublicKeyInfo, the same bytes Java's getEncoded() gives
};

KeyPair newKeyPair();
// The public half (SubjectPublicKeyInfo) worked out from a PKCS#8 private key (empty on bad input).
QByteArray publicFromPrivate(const QByteArray &privateDer);
// Round 17: the same public key, however it is written (another app may write the same point another way).
bool samePublic(const QByteArray &a, const QByteArray &b);
// The shared 32-byte key between my private key and their public key (empty on bad input).
QByteArray sharedKey(const QByteArray &myPrivateDer, const QByteArray &myPublicDer, const QByteArray &theirPublicDer);
QString pairingCode(const QByteArray &key);
// AES-256-GCM with a random 12-byte nonce; data is ciphertext followed by the 16-byte tag.
// aad: cap s1's bound clear fields (Proof::aad); empty means the default constant.
void seal(const QByteArray &key, const QByteArray &plain, QByteArray *nonce, QByteArray *data, const QByteArray &aad = QByteArray());
// Empty (and ok false) when the message was tampered with or isn't for this key.
QByteArray open(const QByteArray &key, const QByteArray &nonce, const QByteArray &data, bool *ok, const QByteArray &aad = QByteArray());
// Checks shared/vectors/chat-vectors.json; prints what it found. Returns true when everything matches.
bool selfTest(const QString &vectorsPath);
}
