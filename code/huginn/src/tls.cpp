#include "tls.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QStandardPaths>

#include <memory>

#include <openssl/bio.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/x509.h>

namespace
{
struct BioFree { void operator()(BIO *p) const { BIO_free(p); } };
struct KeyFree { void operator()(EVP_PKEY *p) const { EVP_PKEY_free(p); } };
struct CertFree { void operator()(X509 *p) const { X509_free(p); } };
struct BnFree { void operator()(BIGNUM *p) const { BN_free(p); } };
using Bio = std::unique_ptr<BIO, BioFree>;
using Key = std::unique_ptr<EVP_PKEY, KeyFree>;
using Cert = std::unique_ptr<X509, CertFree>;
using Bn = std::unique_ptr<BIGNUM, BnFree>;

QByteArray bioBytes(BIO *b)
{
    char *data = nullptr;
    const long n = BIO_get_mem_data(b, &data);
    return n > 0 ? QByteArray(data, int(n)) : QByteArray();
}

// A new EC P-256 key and a self-signed X.509 v3 certificate for it, as PEM. P-256 because every
// TLS stack the LocalSend apps use takes it and it is quick to make; the LocalSend checks only
// want a valid self-signature and dates that cover today.
bool make(QByteArray &keyPem, QByteArray &certPem)
{
    Key key(EVP_EC_gen("P-256"));
    Cert x(X509_new());
    if (!key || !x)
        return false;
    if (!X509_set_version(x.get(), 2))   // 2 means v3; rustls refuses older versions
        return false;

    // Random positive serial: top bit clear so it stays positive, next bit set so it stays 16 bytes.
    unsigned char serial[16];
    if (RAND_bytes(serial, sizeof serial) != 1)
        return false;
    serial[0] = (serial[0] & 0x7f) | 0x40;
    Bn bn(BN_bin2bn(serial, sizeof serial, nullptr));
    if (!bn || !BN_to_ASN1_INTEGER(bn.get(), X509_get_serialNumber(x.get())))
        return false;

    // Fixed wide dates: a handheld whose clock is wrong must still accept its own certificate,
    // and peers check that today falls inside them.
    if (!ASN1_TIME_set_string_X509(X509_getm_notBefore(x.get()), "20000101000000Z")
        || !ASN1_TIME_set_string_X509(X509_getm_notAfter(x.get()), "20491231235959Z"))
        return false;

    X509_NAME *name = X509_get_subject_name(x.get());
    if (!X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, reinterpret_cast<const unsigned char *>("Huginn"), -1, -1, 0)
        || !X509_set_issuer_name(x.get(), name) || !X509_set_pubkey(x.get(), key.get())
        || !X509_sign(x.get(), key.get(), EVP_sha256()))
        return false;

    Bio kb(BIO_new(BIO_s_mem())), cb(BIO_new(BIO_s_mem()));
    if (!kb || !cb || !PEM_write_bio_PrivateKey(kb.get(), key.get(), nullptr, nullptr, 0, nullptr, nullptr)
        || !PEM_write_bio_X509(cb.get(), x.get()))
        return false;
    keyPem = bioBytes(kb.get());
    certPem = bioBytes(cb.get());
    return !keyPem.isEmpty() && !certPem.isEmpty();
}

// True when the PEMs parse and the key belongs to the certificate, so the server can use them.
bool matches(const QByteArray &keyPem, const QByteArray &certPem)
{
    Bio kb(BIO_new_mem_buf(keyPem.constData(), int(keyPem.size())));
    Bio cb(BIO_new_mem_buf(certPem.constData(), int(certPem.size())));
    if (!kb || !cb)
        return false;
    Key key(PEM_read_bio_PrivateKey(kb.get(), nullptr, nullptr, nullptr));
    Cert x(PEM_read_bio_X509(cb.get(), nullptr, nullptr, nullptr));
    return key && x && X509_check_private_key(x.get(), key.get()) == 1;
}

// Written readable by this user only: the key is what proves who this handheld is.
bool writePrivate(const QString &path, const QByteArray &data)
{
    QFile::remove(path);
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate, QFileDevice::ReadOwner | QFileDevice::WriteOwner))
        return false;
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const bool ok = f.write(data) == data.size();
    f.close();
    return ok && f.error() == QFileDevice::NoError;
}

Tls::Identity fromPem(const QByteArray &keyPem, const QByteArray &certPem)
{
    Tls::Identity id;
    id.cert = QSslCertificate(certPem, QSsl::Pem);
    id.key = QSslKey(keyPem, QSsl::Ec, QSsl::Pem, QSsl::PrivateKey);
    id.fingerprint = Tls::fingerprintOf(id.cert);
    return id;
}
}

QString Tls::fingerprintOf(const QSslCertificate &cert)
{
    if (cert.isNull())
        return QString();
    return QString::fromLatin1(cert.digest(QCryptographicHash::Sha256).toHex().toUpper());
}

Tls::Identity Tls::throwaway()
{
    QByteArray keyPem, certPem;
    if (!make(keyPem, certPem))
        return {};
    return fromPem(keyPem, certPem);
}

Tls::Identity Tls::load()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/tls");
    const QString keyPath = dir + QStringLiteral("/key.pem");
    const QString certPath = dir + QStringLiteral("/cert.pem");

    QFile kf(keyPath), cf(certPath);
    if (kf.open(QIODevice::ReadOnly) && cf.open(QIODevice::ReadOnly)) {
        const QByteArray keyPem = kf.readAll(), certPem = cf.readAll();
        kf.close();
        cf.close();
        if (matches(keyPem, certPem)) {
            const Identity id = fromPem(keyPem, certPem);
            if (id.isValid())
                return id;
        }
    }

    // Missing or damaged: make a new one. Other devices will see this handheld as new once.
    QByteArray keyPem, certPem;
    if (!make(keyPem, certPem))
        return Identity();
    QDir().mkpath(dir);
    QFile::setPermissions(dir, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner);
    // Even if saving fails, use the new identity for this run so sharing still works.
    writePrivate(keyPath, keyPem);
    writePrivate(certPath, certPem);
    return fromPem(keyPem, certPem);
}
