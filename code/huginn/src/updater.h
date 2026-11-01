#pragma once

#include <QHash>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QObject>
#include <QUrl>
#include <functional>

class Settings;

// Updates from GitHub releases (tagged v0.3, v0.31...). Updates install the
// release's Flatpak bundle for this user, then Huginn restarts. Only a file that matches the release's
// SHA-256, carries the project's signature (.sig) and the project's signature of its version (.vsig, for the
// release tag being installed) and is newer than this copy is installed. All three come from the signatures block at
// the end of the release notes (shared/docs/releases.md), or from the separate files of a release without one.
// The Pre-release channel (Labs) also reads the "beta" pre-release (its block's label line, or its version.txt, holds
// a label like "0.99 beta 12") and installs whichever of the two is newest. Go back to stable installs the latest
// stable even though it's older, the only time the newer check is skipped; every check of the file stays.
class Updater : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool available READ available NOTIFY changed)
    Q_PROPERTY(QString latest READ latest NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool autoUpdating READ autoUpdating NOTIFY changed)
    // A checked update is waiting for Restart now (an automatic update never closes Huginn by itself).
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    // Installing now: Huginn restarts on the new version after.
    Q_PROPERTY(bool installing READ installing NOTIFY changed)

public:
    explicit Updater(Settings *settings, QObject *parent = nullptr);

    bool busy() const { return m_busy; }
    bool available() const { return !m_latestTag.isEmpty() && newer(m_latest, m_current); }
    QString latest() const { return m_latest; }
    QString status() const { return m_status; }
    bool autoUpdating() const { return m_auto; }
    bool ready() const { return !m_readyPath.isEmpty(); }
    // The checked update waiting for Restart now ("" when none): App storage leaves it alone.
    QString readyPath() const { return m_readyPath; }
    bool installing() const { return m_installing; }

    // At launch: if a newer release is out, download and check it; it installs only on Restart now.
    void startAutoUpdate();
    Q_INVOKABLE void restartToUpdate();

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
    // One download's line in a release's signatures block: SHA-256 (lowercase hex), .sig and .vsig (64 bytes each).
    struct SignedFile {
        QByteArray sha;
        QByteArray sig;
        QByteArray vsig;
    };
    struct Signatures {
        bool ok = false;
        QString label;
        QHash<QString, SignedFile> files;
    };
    // The signatures block (shared/docs/releases.md) in a release body from the API (page false: markdown) or in the
    // release's web page (page true: HTML). Strict: the first line exactly once (starting a line in a body, right
    // after a ">" in a page), only the expected line shapes, size caps. Anything else gives ok == false.
    static Signatures parseSignatures(const QByteArray &text, bool page);
    // Checks signedBy() against shared/vectors/release-sig-vectors.json (for CI).
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();
    void aboutToRestart();
    // Something to say on screen at once (the update went in but Huginn couldn't start again, or it failed).
    void notice(const QString &text);

private:
    void installNow(const QString &path);
    void fetch(bool installAfter, bool autoMode = false);
    // GitHub's API limits each address to 60 questions an hour: when it refuses, the "latest release" page (a redirect to the tag) says the same thing.
    void fetchLatestPage(bool installAfter, bool autoMode);
    // The Pre-release channel: after the best stable one is known, the "beta" release's label (its signatures block,
    // else its version.txt).
    void fetchBeta(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    void fetchBetaLabel(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    void betaOrStable(const QString &label, const SignedFile &signedFile, const QString &ver, const QString &tag, const QString &asset,
                      bool installAfter, bool autoMode);
    // When the API said no: the release's web page (github.com/.../releases/tag/<tag>), for its signatures block.
    void fetchPage(const QString &tag, const std::function<void(const Signatures &)> &done);
    QString labelFor(const QString &tag) const;
    QString assetName() const;
    void chosen(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    QNetworkRequest request(const QUrl &url) const;
    void download(const QString &url, const QString &tag);
    // Checks the download against the release's SHA-256 (its signatures block, and GitHub's digest when there is one),
    // the project's signature (.sig) and its signature of the version (.vsig), from the block or else the separate
    // files beside the download, then installs it.
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
    QString m_betaLabel;        // the beta release's label (its signatures block, or version.txt)
    SignedFile m_signed;        // this download's line in the chosen release's signatures block (empty: none)
    QString m_signedTag;        // the tag m_signed belongs to
    Signatures m_betaBlock;     // the beta release's signatures block, from the API
    bool m_viaPage = false;     // the API said no: read the release pages instead
    bool m_goingBack = false;   // Go back to stable is under way
    QString m_status;
    bool m_busy = false;
    bool m_auto = false;
    bool m_installing = false;
    QString m_readyPath;
};
