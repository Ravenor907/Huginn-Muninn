#pragma once

#include <QSslCertificate>
#include <QSslKey>
#include <QString>

// This device's identity for encrypted nearby sharing (LocalSend's HTTPS mode): a self-signed
// certificate made once and kept. Its SHA-256 is the "fingerprint" other devices know us by,
// so it must never change once made.
namespace Tls
{
struct Identity {
    QSslCertificate cert;
    QSslKey key;
    QString fingerprint;   // uppercase hex SHA-256 of the certificate (DER), like the LocalSend apps
    bool isValid() const { return !cert.isNull() && !key.isNull() && !fingerprint.isEmpty(); }
};

// Loads the saved identity from the app data folder ("tls"), or makes and saves a new one.
// Returns an invalid Identity if neither works.
Identity load();

// A new identity kept only in memory, never saved: what a hidden device shows to whoever connects, so nobody close by
// can follow its lasting certificate from place to place. Invalid if it couldn't be made.
Identity throwaway();

// Uppercase hex SHA-256 of a certificate's DER bytes; empty for a null certificate.
QString fingerprintOf(const QSslCertificate &cert);

// Fingerprints compare without regard to case: some apps send lowercase hex.
inline bool sameFingerprint(const QString &a, const QString &b)
{
    return !a.isEmpty() && a.compare(b, Qt::CaseInsensitive) == 0;
}
}
