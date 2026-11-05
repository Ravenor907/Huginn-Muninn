#pragma once

#include <QByteArray>
#include <QString>

// BIP-340 Schnorr signatures on secp256k1: what Nostr relays require on every event. Follows the
// BIP's reference code step by step, like Muninn's Schnorr.kt; huginn --nostr-selftest checks it
// against the BIP's official values and events made by the reference code.
namespace Schnorr
{
// A random 32-byte secret key in the valid range.
QByteArray newSecret();
// The x-only public key (32 bytes), as Nostr uses it. Empty for a bad secret.
QByteArray publicKey(const QByteArray &secret);
// A 64-byte signature of msg. aux is 32 bytes of extra randomness; empty means fresh random bytes.
// Empty on a bad secret.
QByteArray sign(const QByteArray &msg, const QByteArray &secret, const QByteArray &aux = QByteArray());
bool verify(const QByteArray &msg, const QByteArray &pubkey, const QByteArray &sig);

QString hex(const QByteArray &bytes);
QByteArray unhex(const QString &text);
}
