#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QString>
#include <QStringList>

// Cap s1 (shared/docs/protocol.md section 21; shared/tools/s1_ref.py writes shared/vectors/s1-vectors.json), the same as Muninn's Proof.kt:
//  - route proofs: a device signs, with its chat key, the TLS certificate it answers on (Wi-Fi) or the other side's
//    one-off number (Bluetooth LE). Only a proven route is taken as a contact's; one without a proof (an app without s1)
//    is only ever an extra route next to the internet.
//  - hidden hint: hidden from people nearby, a daily hint goes out instead of the chat key and mailbox.
//  - bound envelopes: room, id, fromKey, toKey, mailbox, caps, days and invite are sealed in as AES-GCM associated
//    data, so none of them can be changed and an envelope can't be sent again as someone else's or to someone else.
namespace Proof
{
extern const char kCap[];       // "s1"
extern const char kMark[];      // "aad": the marker field, "aad":"s1"

// Wi-Fi: my chat key vouches for my certificate (fp, its SHA-256 in hex). Empty if it can't be made.
QString route(const QByteArray &privateDer, const QString &key, const QString &fp);
bool routeOk(const QString &key, const QString &fp, const QString &sig);
// Bluetooth LE: my chat key answers the number the other side picked (32 lowercase hex). Empty for anything else.
QString close(const QByteArray &privateDer, const QString &key, const QString &nonce);
bool closeOk(const QString &key, const QString &nonce, const QString &sig);
bool isNonce(const QString &n);
QString newNonce();

// What a hidden device sends instead of its chat key on day (days since 1970, UTC).
QString hint(const QString &key, qint64 day);
QString hintNow(const QString &key);
// Yesterday, today and tomorrow all match.
bool hintMatches(const QString &key, const QString &h, qint64 nowMs);
// The key among keys whose hint h is, or empty.
QString keyForHint(const QString &h, const QStringList &keys, qint64 nowMs);

bool isBound(const QJsonObject &o);
// The clear fields a bound envelope's seal covers, as canonical JSON; ok false when one has the wrong type.
QByteArray bound(const QJsonObject &o, const QString &toKey, bool *ok);
// v1's associated data for a bound envelope (the ratchet's is its header, a line break, then this).
QByteArray aad(const QByteArray &boundText);

// Checks shared/vectors/s1-vectors.json (chat-vectors.json next to it): proofs, hints, bound envelopes both ways, group rules.
bool selfTest(const QString &vectorsPath);
}
