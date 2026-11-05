#pragma once

#include <QHash>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QTimer>
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
    // Windows: the last update couldn't go in because something (antivirus, almost always) took Tor's tor.exe away.
    Q_PROPERTY(bool torBlocked READ torBlocked NOTIFY changed)

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
    bool torBlocked() const { return m_torBlocked; }
    // The one-time choice after torBlocked (Main.qml asks once): the update goes in without Tor (Ultra privacy then
    // can't start until Tor is back: allow tor.exe in the antivirus, then Check now puts it back).
    Q_INVOKABLE void updateWithoutTor();
    // A sentence for torBlocked's question.
    Q_INVOKABLE QString torBlockedText() const;
    // Ultra privacy turned on or off, or Tor's port changed (main.cpp): what is under way stops and a check comes again
    // later through the new way, never on through the old one.
    void redial();
    // A request through Tor may stall for good: no bytes for this long ends it.
    static constexpr int kStallMs = 60000;
    // The update file is written to disk as it comes, never more than this.
    static constexpr qint64 kMaxDownload = 128LL * 1024 * 1024;
    // "update/failed"'s sentence names Tor's folder.
    static bool namesTor(const QString &why);

    // At launch: if a newer release is out, download and check it; it installs only on Restart now. While Huginn runs
    // it checks again every 6 hours (12 with Battery saver on), give or take up to 30 minutes, and a minute after the
    // computer wakes from sleep. Each check uses the same flow and never restarts Huginn by itself.
    void startAutoUpdate();
    // Set by main.cpp: Battery saver is on now (System::batterySaving).
    std::function<bool()> saving;
    static constexpr qint64 kEveryMs = 6LL * 3600 * 1000;
    static constexpr qint64 kEverySavingMs = 12LL * 3600 * 1000;
    static constexpr qint64 kJitterMs = 30LL * 60 * 1000;
    // The wait before the next check: kEveryMs (kEverySavingMs while saving), moved by jitter in [-1, 1] times kJitterMs.
    static qint64 nextCheckMs(bool savingNow, double jitter);
    // A clock tick that came this much later than asked means the computer slept in between.
    static bool sleptBetween(qint64 expectedMs, qint64 wallMs) { return wallMs - expectedMs > 5 * 60000; }
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
    // The timed check found the ready update still waiting (the update bar shows again).
    void remind();
    // Something to say on screen at once (the update went in but Huginn couldn't start again, or it failed).
    void notice(const QString &text);
    // torBlocked became true: Main.qml asks once whether to update without Tor.
    void torChoice();

private:
    void installNow(const QString &path);
    void scheduleNext();
    void periodicCheck();     // the timed (or after sleep) check: only when nothing else is going on
    QTimer m_every;           // the next timed check
    QTimer m_wake;            // a tick every minute, to notice the computer woke from sleep
    qint64 m_wakeWall = 0;    // the wall clock (ms) at the last tick
    bool m_periodic = false;  // startAutoUpdate ran: timed checks are on
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
    // Every request: Ultra privacy's proxy as it is now, and kept so redial() can stop it.
    QNetworkReply *get(const QNetworkRequest &req);
    QList<QPointer<QNetworkReply>> m_replies;
    bool m_redialed = false;   // the last stop was redial()'s: "tries again later", and it does
    bool m_torBlocked = false;
    void noteTorFailure(const QString &why);
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
    // Windows: the version whose swap failed last time ("?" when not known): not downloaded again by itself.
    QString m_failedVersion;
    void forgetFailure();
    bool m_installing = false;
    QString m_readyPath;
};
