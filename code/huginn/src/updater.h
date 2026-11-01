#pragma once

#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QObject>
#include <QUrl>
#include <functional>

class Settings;

// Updates from GitHub releases (tagged v0.3, v0.31...). Updates install the
// release's Flatpak bundle for this user, then Huginn restarts. Only a file that matches the release's
// SHA-256, carries the project's signature (.sig) and the project's signature of its version (.vsig, for the
// release tag being installed) and is newer than this copy is installed.
// The Pre-release channel (Labs) also reads the "beta" pre-release (its version.txt holds a label like
// "0.99 beta 12") and installs whichever of the two is newest. Go back to stable installs the latest
// stable even though it's older, the only time the newer check is skipped; every check of the file stays.
class Updater : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool available READ available NOTIFY changed)
    Q_PROPERTY(QString latest READ latest NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool autoUpdating READ autoUpdating NOTIFY changed)

public:
    explicit Updater(Settings *settings, QObject *parent = nullptr);

    bool busy() const { return m_busy; }
    bool available() const { return !m_latestTag.isEmpty() && newer(m_latest, m_current); }
    QString latest() const { return m_latest; }
    QString status() const { return m_status; }
    bool autoUpdating() const { return m_auto; }

    // At launch: if a newer release is out, install it and restart.
    void startAutoUpdate();

    Q_INVOKABLE void check();
    Q_INVOKABLE void install();
    // Labs: the latest stable release, even though it's older than this pre-release (asked first on screen).
    Q_INVOKABLE void goBackToStable();
    // Runs once the stable file checked out, before it's installed (main.cpp: the channel back to Stable and Labs
    // off). False stops the install.
    std::function<bool()> beforeGoingBack;

    static QString versionFromTag(const QString &tag);   // v0.31 -> "0.31"; anything else -> empty
    // Numeric version first; for the same version a stable release is newer than any of its betas, and
    // betas go by number: 0.98 < 0.99 beta 3 < 0.99 beta 7 < 0.99. (An old "0.3 Test 1" sorts like a beta.)
    static bool newer(const QString &a, const QString &b);
    static int compareVersions(const QString &a, const QString &b);
    // A label as version.txt and HUGINN_VERSION_LABEL give it: "0.98" or "0.99 beta 12".
    static bool isLabel(const QString &s);
    // True when sig is an Ed25519 signature (RFC 8032) of data by publicKey (32 bytes). Every release file is
    // signed with the project's release key (shared/docs/releases.md); Huginn installs nothing without it.
    static bool signedBy(const QByteArray &data, const QByteArray &sig, const QByteArray &publicKey);
    static bool signedByProject(const QByteArray &data, const QByteArray &sig);
    // <file>.vsig signs this text (shared/docs/releases.md): "huginn-muninn release v1\n" version "\n" file name "\n"
    // lowercase hex SHA-256 of the file. Built from the tag being installed, so an older signed file posted again under
    // a newer tag doesn't check out.
    static QByteArray versionMessage(const QString &version, const QString &file, const QByteArray &shaHex);
    static bool versionSignedBy(const QString &version, const QString &file, const QByteArray &shaHex, const QByteArray &vsig,
                                const QByteArray &publicKey);
    // Checks signedBy() against shared/vectors/release-sig-vectors.json (for CI).
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();

private:
    void fetch(bool installAfter, bool autoMode = false);
    // GitHub's API limits each address to 60 questions an hour: when it refuses, the "latest release" page (a redirect to the tag) says the same thing.
    void fetchLatestPage(bool installAfter, bool autoMode);
    // The Pre-release channel: after the best stable one is known, version.txt of the "beta" release.
    void fetchBeta(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    QString labelFor(const QString &tag) const;
    QString assetName() const;
    void chosen(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    QNetworkRequest request(const QUrl &url) const;
    void download(const QString &url, const QString &tag);
    // Checks the download against the release's SHA-256 (GitHub's digest or the .sha256 file), the project's
    // signature (the .sig file beside it) and its signature of the version (the .vsig file), then installs it.
    void verifyThenInstall(const QString &path, const QString &tag);
    void checkThenInstall(const QString &path, const QString &tag, const QString &name, const QByteArray &expected, const QByteArray &sig,
                          const QByteArray &vsig);
    void installFlatpak(const QString &path);
    void failed(const QString &status);
    Settings *m_settings;
    QNetworkAccessManager m_net;
    QString m_current;
    QString m_latest;
    QString m_latestTag;
    QString m_assetUrl;
    QByteArray m_assetDigest;   // SHA-256 (hex) of m_assetUrl's file, when GitHub said it
    QByteArray m_betaDigest;    // the same for the beta release's file, when the API listed it
    QString m_betaLabel;        // the label in the beta release's version.txt
    bool m_goingBack = false;   // Go back to stable is under way
    QString m_status;
    bool m_busy = false;
    bool m_auto = false;
};
