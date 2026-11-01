#include "updater.h"
#include "settings.h"
#include "system.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QProcess>
#include <QFileInfo>
#include <QTimer>
#include "platform/singleinstance.h"
#include "platform/winupdate.h"
#include <QRegularExpression>
#include <QStandardPaths>
#include <QTimer>
#include <QStringList>
#include <QVariantMap>
#include <openssl/evp.h>
#include <cstdio>

namespace
{
const char *kReleases = "https://api.github.com/repos/Ravenor907/Huginn-Muninn/releases?per_page=100";
const char *kLatestPage = "https://github.com/Ravenor907/Huginn-Muninn/releases/latest";
const char *kDownload = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/";
// A release's web page, read for its signatures block when the API said no.
const char *kTagPage = "https://github.com/Ravenor907/Huginn-Muninn/releases/tag/";
// The signatures block at the end of the release notes (shared/docs/releases.md): its first line, and the most of a
// body or page and of the block itself that Huginn reads.
const char *kSignaturesFirst = "huginn-muninn signatures v1";
constexpr qsizetype kMaxNotes = 4 * 1024 * 1024;
constexpr qsizetype kMaxBlock = 2048;
// The one pre-release (Labs' Pre-release channel), always under this tag; its signatures block (or version.txt) holds the label.
const char *kBetaTag = "beta";
// The project's release key (Ed25519, shared/docs/release-key.pem). CI signs every release file with its private half.
const char *kReleaseKey = "705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06";

// Versions have one dot and read as decimals: 0.3 < 0.31 < 0.32 < 0.4 < 1.0. For the same version the betas
// come first, by number ("0.99 beta 3" < "0.99 beta 12"; an old test build "0.3 Test 1" sorts like a beta), then
// the stable release itself.
struct Rank {
    qint64 number = 0;   // the version as a decimal, six places
    int kind = 0;        // 1 beta (or an old test build), 2 stable
    qint64 n = 0;        // the test or beta number
};
Rank rank(const QString &v)
{
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^(\\d{1,6})\\.(\\d{1,6})(?: (Test|beta) (\\d{1,6}))?")).match(v);
    if (!m.hasMatch())
        return {};
    Rank r;
    r.number = m.captured(1).toLongLong() * 1000000 + (m.captured(2) + QStringLiteral("000000")).left(6).toLongLong();
    if (m.captured(3).isEmpty())
        r.kind = 2;
    else {
        r.kind = 1;   // a test build sorts like a beta
        r.n = m.captured(4).toLongLong();
    }
    return r;
}
}

Updater::Updater(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_current(QCoreApplication::applicationVersion())
    , m_status(QStringLiteral("Up to date as far as Huginn knows"))
{
}

void Updater::startAutoUpdate()
{
    if (!System::inFlatpak() && !WinUpdate::supported())
        return;   // a development build updates by rebuilding
    if (m_settings && !m_settings->autoUpdate())
        return;   // "Check for updates automatically" is off: nothing asks GitHub until Check now
    m_goingBack = false;
    fetch(false, true);
}

QString Updater::versionFromTag(const QString &tag)
{
    // Releases are tagged v0.3, v0.31 and so on. Test tags (v0.3-test1) don't match and are skipped.
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^v(\\d+\\.\\d+)$")).match(tag);
    return m.hasMatch() ? m.captured(1) : QString();
}

int Updater::compareVersions(const QString &a, const QString &b)
{
    const Rank x = rank(a), y = rank(b);
    if (x.number != y.number)
        return x.number < y.number ? -1 : 1;
    if (x.kind != y.kind)
        return x.kind < y.kind ? -1 : 1;
    if (x.n != y.n)
        return x.n < y.n ? -1 : 1;
    return 0;
}

bool Updater::newer(const QString &a, const QString &b)
{
    return compareVersions(a, b) > 0;
}

bool Updater::isLabel(const QString &s)
{
    static const QRegularExpression re(QRegularExpression::anchoredPattern(QStringLiteral("\\d{1,6}\\.\\d{1,6}( beta [1-9]\\d{0,5})?")));
    return re.match(s).hasMatch();
}

QString Updater::labelFor(const QString &tag) const
{
    return tag == QLatin1String(kBetaTag) ? m_betaLabel : versionFromTag(tag);
}

QString Updater::assetName() const
{
    return WinUpdate::supported() ? WinUpdate::assetName() : QStringLiteral("Huginn.flatpak");
}

void Updater::check()
{
    m_goingBack = false;
    fetch(false);
}

void Updater::goBackToStable()
{
    if (m_busy)
        return;
    if (!System::inFlatpak() && !WinUpdate::supported()) {
        m_status = QStringLiteral("This build updates by rebuilding.");
        Q_EMIT changed();
        return;
    }
    m_goingBack = true;
    fetch(true);
}

QNetworkRequest Updater::request(const QUrl &url) const
{
    QNetworkRequest req(url);
    // A plain agent with no app name or version, so GitHub can't tell which app or version is asking.
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0"));
    return req;
}

void Updater::fetch(bool installAfter, bool autoMode)
{
    if (m_busy)
        return;
    m_busy = true;
    m_status = QStringLiteral("Checking for updates…");
    Q_EMIT changed();
    m_signed = {};
    m_signedTag.clear();
    m_betaBlock = {};
    m_viaPage = false;
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kReleases)));
    req.setRawHeader("Accept", "application/vnd.github+json");
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        if (r->error() != QNetworkReply::NoError) {
            m_viaPage = true;
            fetchLatestPage(installAfter, autoMode);
            return;
        }
        QString bestVer, bestTag, bestAsset;
        QByteArray bestDigest;
        SignedFile bestSigned;
        const bool pre = !m_goingBack && m_settings && m_settings->preRelease();
        m_betaDigest.clear();
        for (const QJsonValue &v : QJsonDocument::fromJson(r->readAll()).array()) {
            const QJsonObject rel = v.toObject();
            if (rel.value(QStringLiteral("draft")).toBool())
                continue;
            if (rel.value(QStringLiteral("prerelease")).toBool()) {
                // The Pre-release channel only: GitHub's SHA-256 of the beta release's file, when it lists one.
                if (pre && rel.value(QStringLiteral("tag_name")).toString() == QLatin1String(kBetaTag)) {
                    for (const QJsonValue &a : rel.value(QStringLiteral("assets")).toArray())
                        if (a.toObject().value(QStringLiteral("name")).toString() == assetName())
                            m_betaDigest = WinUpdate::parseDigest(a.toObject().value(QStringLiteral("digest")).toString().toLatin1());
                    // Its signatures block (label and signatures), when its notes carry one.
                    m_betaBlock = parseSignatures(rel.value(QStringLiteral("body")).toString().toUtf8(), false);
                }
                continue;
            }
            const QString tag = rel.value(QStringLiteral("tag_name")).toString();
            if (!tag.startsWith(QLatin1Char('v')))   // Muninn (the phone app) releases are muninn-v…
                continue;
            const QString ver = versionFromTag(tag);
            if (ver.isEmpty())
                continue;
            QString asset;
            QByteArray digest;
            for (const QJsonValue &a : rel.value(QStringLiteral("assets")).toArray())
                if (a.toObject().value(QStringLiteral("name")).toString().endsWith(WinUpdate::supported() ? WinUpdate::assetName() : QStringLiteral(".flatpak"))) {
                    asset = a.toObject().value(QStringLiteral("browser_download_url")).toString();
                    // GitHub's own SHA-256 of the file ("sha256:..."), when it has one.
                    digest = WinUpdate::parseDigest(a.toObject().value(QStringLiteral("digest")).toString().toLatin1());
                }
            if (asset.isEmpty())
                continue;
            if (bestVer.isEmpty() || newer(ver, bestVer)) {
                bestVer = ver;
                bestTag = tag;
                bestAsset = asset;
                bestDigest = digest;
                // This download's line in the release notes' signatures block; none means the separate files.
                const Signatures block = parseSignatures(rel.value(QStringLiteral("body")).toString().toUtf8(), false);
                bestSigned = block.ok ? block.files.value(assetName()) : SignedFile();
            }
        }
        m_assetDigest = bestDigest;
        m_signed = bestSigned;
        m_signedTag = bestTag;
        if (pre) {
            fetchBeta(bestVer, bestTag, bestAsset, installAfter, autoMode);
            return;
        }
        chosen(bestVer, bestTag, bestAsset, installAfter, autoMode);
    });
}

void Updater::fetchBeta(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode)
{
    // The label line of the beta release's signatures block, from the API.
    if (m_betaBlock.ok) {
        betaOrStable(m_betaBlock.label, m_betaBlock.files.value(assetName()), ver, tag, asset, installAfter, autoMode);
        return;
    }
    // The API said no: the beta release's page.
    if (m_viaPage) {
        fetchPage(QString::fromLatin1(kBetaTag), [this, ver, tag, asset, installAfter, autoMode](const Signatures &block) {
            if (block.ok)
                betaOrStable(block.label, block.files.value(assetName()), ver, tag, asset, installAfter, autoMode);
            else
                fetchBetaLabel(ver, tag, asset, installAfter, autoMode);
        });
        return;
    }
    fetchBetaLabel(ver, tag, asset, installAfter, autoMode);
}

void Updater::fetchBetaLabel(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode)
{
    // A beta release without a signatures block: its version.txt, and the separate .sig and .vsig files.
    m_busy = true;
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kDownload) + QLatin1String(kBetaTag) + QStringLiteral("/version.txt")));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, ver, tag, asset, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        const QString label = r->error() == QNetworkReply::NoError ? QString::fromUtf8(r->read(64)).trimmed() : QString();
        betaOrStable(label, SignedFile(), ver, tag, asset, installAfter, autoMode);
    });
}

void Updater::betaOrStable(const QString &label, const SignedFile &signedFile, const QString &ver, const QString &tag, const QString &asset,
                           bool installAfter, bool autoMode)
{
    // A beta newer than the newest stable release (or no stable one found): that one.
    if (isLabel(label) && (tag.isEmpty() || newer(label, ver))) {
        m_betaLabel = label;
        m_assetDigest = m_betaDigest;   // may be empty: then the block's SHA-256 or the file's own, bound by .sig and .vsig
        m_signed = signedFile;
        m_signedTag = QString::fromLatin1(kBetaTag);
        chosen(label, QString::fromLatin1(kBetaTag), QString::fromLatin1(kDownload) + QLatin1String(kBetaTag) + QLatin1Char('/') + assetName(),
               installAfter, autoMode);
        return;
    }
    chosen(ver, tag, asset, installAfter, autoMode);
}

void Updater::fetchPage(const QString &tag, const std::function<void(const Signatures &)> &done)
{
    m_busy = true;
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kTagPage) + tag));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    // A page over the cap is never read whole.
    connect(r, &QNetworkReply::downloadProgress, r, [r](qint64 got, qint64) {
        if (got > kMaxNotes)
            r->abort();
    });
    connect(r, &QNetworkReply::finished, this, [this, r, done] {
        r->deleteLater();
        m_busy = false;
        done(r->error() == QNetworkReply::NoError ? parseSignatures(r->read(kMaxNotes + 1), true) : Signatures());
    });
}

void Updater::fetchLatestPage(bool installAfter, bool autoMode)
{
    m_busy = true;
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kLatestPage)));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        // .../releases/latest answers with a redirect to .../releases/tag/v0.6
        const QUrl to = r->attribute(QNetworkRequest::RedirectionTargetAttribute).toUrl();
        const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("/releases/tag/([^/?#]+)$")).match(to.path());
        const QString tag = m.hasMatch() ? m.captured(1) : QString();
        const QString ver = versionFromTag(tag);
        if (ver.isEmpty()) {
            m_goingBack = false;
            m_status = QStringLiteral("Couldn't reach GitHub. Check the connection.");
            Q_EMIT changed();
            return;
        }
        // Every release carries Huginn.flatpak (see .github/workflows/build.yml).
        m_assetDigest.clear();   // the page doesn't list digests; the signatures block (or the .sha256 file) does
        const QString asset = QString::fromLatin1(kDownload) + tag + (WinUpdate::supported() ? QStringLiteral("/") + WinUpdate::assetName() : QStringLiteral("/Huginn.flatpak"));
        // The release's page, for its signatures block; without one, the separate files.
        fetchPage(tag, [this, ver, tag, asset, installAfter, autoMode](const Signatures &block) {
            m_signed = block.ok ? block.files.value(assetName()) : SignedFile();
            m_signedTag = tag;
            if (!m_goingBack && m_settings && m_settings->preRelease()) {
                m_betaDigest.clear();
                fetchBeta(ver, tag, asset, installAfter, autoMode);
                return;
            }
            chosen(ver, tag, asset, installAfter, autoMode);
        });
    });
}

void Updater::chosen(const QString &bestVer, const QString &bestTag, const QString &bestAsset, bool installAfter, bool autoMode)
{
    m_latest = bestVer;
    m_latestTag = bestTag;
    m_assetUrl = bestAsset;
    if (installAfter) {
        if (bestTag.isEmpty()) {
            m_goingBack = false;
            m_status = QStringLiteral("No release found on GitHub.");
            Q_EMIT changed();
            return;
        }
        download(bestAsset, bestTag);
        return;
    }
    if (autoMode && available()) {
        m_auto = true;
        download(bestAsset, bestTag);
        return;
    }
    m_status = available() ? QStringLiteral("Huginn %1 is ready to install").arg(bestVer) : QStringLiteral("Huginn is up to date");
    Q_EMIT changed();
}

void Updater::install()
{
    if (!m_readyPath.isEmpty()) {
        restartToUpdate();
        return;
    }
    if (m_busy || m_assetUrl.isEmpty())
        return;
    m_goingBack = false;
    download(m_assetUrl, m_latestTag);
}

void Updater::download(const QString &url, const QString &tag)
{
    m_busy = true;
    m_status = QStringLiteral("Downloading Huginn %1…").arg(labelFor(tag));
    Q_EMIT changed();
    QNetworkRequest req = request(QUrl(url));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, tag] {
        r->deleteLater();
        if (r->error() != QNetworkReply::NoError) {
            m_busy = false;
            m_auto = false;
            m_goingBack = false;
            m_status = QStringLiteral("The download failed. Try again.");
            Q_EMIT changed();
            return;
        }
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
        QDir().mkpath(dir);
        const QString path = dir + QStringLiteral("/Huginn-") + tag + (WinUpdate::supported() ? QStringLiteral(".zip") : QStringLiteral(".flatpak"));
        QFile f(path);
        if (!f.open(QIODevice::WriteOnly)) {
            m_busy = false;
            m_auto = false;
            m_goingBack = false;
            m_status = QStringLiteral("Couldn't save the update.");
            Q_EMIT changed();
            return;
        }
        f.write(r->readAll());
        f.close();
        // Only a file whose SHA-256 matches the release's is installed: the Windows zip and the Flatpak alike.
        verifyThenInstall(path, tag);
    });
}

void Updater::installFlatpak(const QString &path)
{
    m_status = QStringLiteral("Installing…");
    Q_EMIT changed();
    auto *p = new QProcess(this);
    QStringList args{QStringLiteral("flatpak"), QStringLiteral("install"), QStringLiteral("--user"), QStringLiteral("--noninteractive"),
                     QStringLiteral("--reinstall"), QStringLiteral("--bundle"), path};
    QString program = QStringLiteral("flatpak-spawn");
    if (System::inFlatpak())
        args.prepend(QStringLiteral("--host"));
    else
        program = args.takeFirst();
    connect(p, &QProcess::finished, this, [this, p](int code, QProcess::ExitStatus st) {
        p->deleteLater();
        m_busy = false;
        if (st != QProcess::NormalExit || code != 0) {
            m_installing = false;
            m_auto = false;
            m_goingBack = false;
            m_status = QStringLiteral("Installing the update failed.");
            Q_EMIT changed();
            Q_EMIT notice(m_status);
            return;
        }
        // The new copy waits until this one has saved and closed. Closed only once it really started.
        if (!SingleInstance::startAgain()) {
            m_installing = false;
            m_auto = false;
            m_goingBack = false;
            m_status = QStringLiteral("Updated. Open Huginn again.");
            Q_EMIT changed();
            Q_EMIT notice(m_status);
            return;
        }
        m_status = QStringLiteral("Updated. Restarting…");
        Q_EMIT changed();
        QTimer::singleShot(300, qApp, [] { QCoreApplication::exit(0); });   // past the window's close question
    });
    p->start(program, args);
}

void Updater::failed(const QString &status)
{
    m_installing = false;
    m_busy = false;
    m_auto = false;
    m_goingBack = false;
    m_status = status;
    Q_EMIT changed();
}

void Updater::verifyThenInstall(const QString &path, const QString &tag)
{
    m_status = QStringLiteral("Checking the download…");
    Q_EMIT changed();
    const QString name = assetName();
    // The release notes' signatures block: the SHA-256, .sig and .vsig in one place. GitHub's digest, when the API
    // gave one, must say the same.
    if (m_signedTag == tag && m_signed.sha.size() == 64) {
        if (!m_assetDigest.isEmpty() && m_assetDigest != m_signed.sha) {
            qWarning("update: GitHub says SHA-256 %s, the signatures block %s", m_assetDigest.constData(), m_signed.sha.constData());
            QFile::remove(path);
            failed(QStringLiteral("The download didn't match the release, so it wasn't installed. Try again."));
            return;
        }
        checkThenInstall(path, tag, name, m_signed.sha, m_signed.sig, m_signed.vsig);
        return;
    }
    // A release without a block: the separate files beside the download.
    auto get = [this, tag](const QString &file) {
        QNetworkRequest req = request(QUrl(QString::fromLatin1(kDownload) + tag + QLatin1Char('/') + file));
        req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
        return m_net.get(req);
    };
    // Huginn-Windows.zip.sig or Huginn.flatpak.sig: the project's signature, published by the release job; and .vsig,
    // its signature of this release's version. Both always required.
    QNetworkReply *s = get(name + QStringLiteral(".sig"));
    connect(s, &QNetworkReply::finished, this, [this, s, path, name, tag, get] {
        s->deleteLater();
        const QByteArray sig = s->error() == QNetworkReply::NoError ? s->read(256) : QByteArray();
        QNetworkReply *vs = get(name + QStringLiteral(".vsig"));
        connect(vs, &QNetworkReply::finished, this, [this, vs, path, name, tag, get, sig] {
            vs->deleteLater();
            const QByteArray vsig = vs->error() == QNetworkReply::NoError ? vs->read(256) : QByteArray();
            if (!m_assetDigest.isEmpty()) {
                checkThenInstall(path, tag, name, m_assetDigest, sig, vsig);
                return;
            }
            // The beta release with no digest from the API: the file's own SHA-256. .sig still has to sign these
            // bytes and .vsig this SHA-256 with the label, so nothing unsigned gets through.
            if (tag == QLatin1String(kBetaTag)) {
                QFile f(path);
                const QByteArray own = f.open(QIODevice::ReadOnly) ? QCryptographicHash::hash(f.readAll(), QCryptographicHash::Sha256).toHex() : QByteArray();
                checkThenInstall(path, tag, name, own, sig, vsig);
                return;
            }
            // Huginn-Windows.zip.sha256 or Huginn.flatpak.sha256, published by the release job beside the file.
            QNetworkReply *r = get(name + QStringLiteral(".sha256"));
            connect(r, &QNetworkReply::finished, this, [this, r, path, name, tag, sig, vsig] {
                r->deleteLater();
                checkThenInstall(path, tag, name, r->error() == QNetworkReply::NoError ? WinUpdate::parseDigest(r->read(4096)) : QByteArray(), sig, vsig);
            });
        });
    });
}

void Updater::checkThenInstall(const QString &path, const QString &tag, const QString &name, const QByteArray &expected, const QByteArray &sig,
                               const QByteArray &vsig)
{
    QFile f(path);
    if (expected.size() != 64 || !f.open(QIODevice::ReadOnly)) {
        QFile::remove(path);
        failed(QStringLiteral("The update couldn't be checked, so it wasn't installed. Try again later."));
        return;
    }
    const QByteArray data = f.readAll();
    f.close();
    const QByteArray got = QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex();
    if (got != expected) {
        qWarning("update: SHA-256 %s, release says %s", got.constData(), expected.constData());
        QFile::remove(path);
        failed(QStringLiteral("The download didn't match the release, so it wasn't installed. Try again."));
        return;
    }
    if (!signedByProject(data, sig)) {
        qWarning("update: no valid project signature (%d bytes of .sig)", int(sig.size()));
        QFile::remove(path);
        failed(QStringLiteral("This update isn't signed with the project's key, so it wasn't installed."));
        return;
    }
    // The version signed in .vsig must be the one this release's tag names (the beta release: its label),
    // and newer than this copy: an older signed file posted again under a newer tag is never installed.
    const QString version = labelFor(tag);
    if (version.isEmpty() || !versionSignedBy(version, name, got, vsig, QByteArray::fromHex(kReleaseKey))) {
        qWarning("update: no valid version signature (%d bytes of .vsig)", int(vsig.size()));
        QFile::remove(path);
        failed(QStringLiteral("This update's version isn't signed with the project's key, so it wasn't installed."));
        return;
    }
    // Go back to stable (asked on screen) is the one install that may be older; it's still a stable release.
    const bool goingBack = m_goingBack && tag != QLatin1String(kBetaTag);
    if (!goingBack && !newer(version, m_current)) {
        QFile::remove(path);
        failed(QStringLiteral("This update isn't newer than the Huginn you have, so it wasn't installed."));
        return;
    }
    if (goingBack && beforeGoingBack && !beforeGoingBack()) {
        QFile::remove(path);
        failed(QStringLiteral("Unlock Huginn first, then try again."));
        return;
    }
    // An automatic update never closes Huginn by itself: it waits, checked, until the person picks Restart now.
    if (m_auto && !goingBack) {
        m_auto = false;
        m_busy = false;
        m_readyPath = path;
        m_status = QStringLiteral("Huginn %1 is ready. It installs when you restart.").arg(version);
        Q_EMIT changed();
        return;
    }
    installNow(path);
}

void Updater::restartToUpdate()
{
    if (m_busy || m_readyPath.isEmpty() || !QFileInfo::exists(m_readyPath))
        return;
    m_busy = true;
    const QString path = m_readyPath;
    m_readyPath.clear();
    installNow(path);
}

void Updater::installNow(const QString &path)
{
    m_installing = true;
    Q_EMIT aboutToRestart();   // everything is saved before the update replaces Huginn
    if (!WinUpdate::supported()) {
        installFlatpak(path);
        return;
    }
    m_status = QStringLiteral("Installing…");
    Q_EMIT changed();
    WinUpdate::install(path, this, [this](bool ok, bool restarting, const QString &msg) {
        m_busy = false;
        // Closed only when the new copy really started; otherwise Huginn stays open and says so.
        if (!ok || !restarting) {
            m_installing = false;
            m_auto = false;
            m_goingBack = false;
        }
        m_status = msg;
        Q_EMIT changed();
        if (ok && restarting)
            QTimer::singleShot(300, qApp, [] { QCoreApplication::exit(0); });   // past the window's close question
        else
            Q_EMIT notice(msg);
    });
}

bool Updater::signedBy(const QByteArray &data, const QByteArray &sig, const QByteArray &publicKey)
{
    if (publicKey.size() != 32 || sig.size() != 64)
        return false;
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, reinterpret_cast<const unsigned char *>(publicKey.constData()), publicKey.size());
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    // Ed25519 takes the whole message at once (no digest of its own to stream into).
    const bool ok = key && ctx && EVP_DigestVerifyInit(ctx, nullptr, nullptr, nullptr, key) == 1
        && EVP_DigestVerify(ctx, reinterpret_cast<const unsigned char *>(sig.constData()), sig.size(),
                            reinterpret_cast<const unsigned char *>(data.constData()), data.size()) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok;
}

bool Updater::signedByProject(const QByteArray &data, const QByteArray &sig)
{
    return signedBy(data, sig, QByteArray::fromHex(kReleaseKey));
}

QByteArray Updater::versionMessage(const QString &version, const QString &file, const QByteArray &shaHex)
{
    return QByteArrayLiteral("huginn-muninn release v1\n") + version.toUtf8() + '\n' + file.toUtf8() + '\n' + shaHex.toLower();
}

bool Updater::versionSignedBy(const QString &version, const QString &file, const QByteArray &shaHex, const QByteArray &vsig,
                              const QByteArray &publicKey)
{
    return signedBy(versionMessage(version, file, shaHex), vsig, publicKey);
}

Updater::Signatures Updater::parseSignatures(const QByteArray &text, bool page)
{
    Signatures out;
    const QByteArray first(kSignaturesFirst);
    if (text.size() > kMaxNotes)
        return out;
    // The first line, exactly once where it may start: a line of the body, or right after a ">" in the page (the start
    // of its code element; the page's descriptions and copy button hold the text too, never right after a ">").
    qsizetype start = -1;
    int found = 0;
    for (qsizetype i = text.indexOf(first); i >= 0; i = text.indexOf(first, i + 1)) {
        const char before = i > 0 ? text.at(i - 1) : '\0';
        if (page ? before == '>' : (i == 0 || before == '\n')) {
            start = i;
            ++found;
        }
    }
    if (found != 1)
        return out;
    // It ends at the first "<" or "```" after it, or at the end.
    qsizetype end = text.size();
    for (const char *t : {"<", "```"}) {
        const qsizetype j = text.indexOf(t, start);
        if (j >= 0 && j < end)
            end = j;
    }
    if (end - start > kMaxBlock)
        return out;
    QList<QByteArray> lines = text.mid(start, end - start).split('\n');
    for (QByteArray &l : lines)
        if (l.endsWith('\r'))
            l.chop(1);
    while (!lines.isEmpty() && lines.last().isEmpty())
        lines.removeLast();
    if (lines.size() < 3 || lines.size() > 5 || lines.first() != first)
        return out;
    static const QRegularExpression labelLine(QRegularExpression::anchoredPattern(
        QStringLiteral("label ([0-9]{1,6}\\.[0-9]{1,6}(?: beta [1-9][0-9]{0,5})?)")));
    static const QRegularExpression fileLine(QRegularExpression::anchoredPattern(
        QStringLiteral("(Huginn-Windows\\.zip|Huginn\\.flatpak|Muninn\\.apk) sha256 ([0-9a-f]{64}) sig ([A-Za-z0-9+/]{86}==) vsig ([A-Za-z0-9+/]{86}==)")));
    const QRegularExpressionMatch lm = labelLine.match(QString::fromLatin1(lines.at(1)));
    if (!lm.hasMatch())
        return out;
    // A signature: the one canonical base64 spelling of 64 bytes.
    auto sig64 = [](const QString &b64, QByteArray *raw) {
        const QByteArray in = b64.toLatin1();
        const auto r = QByteArray::fromBase64Encoding(in, QByteArray::AbortOnBase64DecodingErrors);
        if (!r || r->size() != 64 || r->toBase64() != in)
            return false;
        *raw = *r;
        return true;
    };
    QHash<QString, SignedFile> files;
    for (qsizetype i = 2; i < lines.size(); ++i) {
        const QRegularExpressionMatch m = fileLine.match(QString::fromLatin1(lines.at(i)));
        if (!m.hasMatch() || files.contains(m.captured(1)))
            return out;
        SignedFile f;
        f.sha = m.captured(2).toLatin1();
        if (!sig64(m.captured(3), &f.sig) || !sig64(m.captured(4), &f.vsig))
            return out;
        files.insert(m.captured(1), f);
    }
    out.ok = true;
    out.label = lm.captured(1);
    out.files = files;
    return out;
}

bool Updater::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        fprintf(stderr, "release-sig self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonArray cases = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("cases")).toArray();
    auto b = [](const QJsonObject &o, const char *k) { return QByteArray::fromHex(o.value(QLatin1String(k)).toString().toLatin1()); };
    bool ok = !cases.isEmpty();
    for (const QJsonValue &v : cases) {
        const QJsonObject c = v.toObject();
        const bool want = c.value(QStringLiteral("valid")).toBool();
        if (signedBy(b(c, "message"), b(c, "signature"), b(c, "public")) != want) {
            fprintf(stderr, "release-sig self-test: case \"%s\" should be %s\n", qPrintable(c.value(QStringLiteral("name")).toString()), want ? "valid" : "refused");
            ok = false;
        }
    }
    // Version signatures (.vsig): the text each app builds, and which signatures check out for which tag.
    const QJsonObject all = QJsonDocument::fromJson([&] { f.seek(0); return f.readAll(); }()).object();
    if (all.contains(QStringLiteral("versioned"))) {
        const QJsonArray vc = all.value(QStringLiteral("versioned")).toArray();
        bool vok = !vc.isEmpty() && all.value(QStringLiteral("domain")).toString() == QLatin1String("huginn-muninn release v1");
        for (const QJsonValue &v : vc) {
            const QJsonObject c = v.toObject();
            const QString ver = c.value(QStringLiteral("version")).toString(), file = c.value(QStringLiteral("file")).toString();
            const QByteArray sha = c.value(QStringLiteral("sha256")).toString().toLatin1();
            const bool msgOk = versionMessage(ver, file, sha) == b(c, "message");
            const bool good = versionSignedBy(ver, file, sha, b(c, "signature"), b(c, "public")) == c.value(QStringLiteral("valid")).toBool();
            if (!msgOk || !good) {
                fprintf(stderr, "release-sig self-test: version case \"%s\" %s\n", qPrintable(c.value(QStringLiteral("name")).toString()),
                        msgOk ? "gave the wrong answer" : "builds a different text");
                vok = false;
            }
        }
        for (const QJsonValue &v : all.value(QStringLiteral("newer")).toArray()) {
            const QJsonObject c = v.toObject();
            if (newer(c.value(QStringLiteral("tag")).toString(), c.value(QStringLiteral("current")).toString()) != c.value(QStringLiteral("install")).toBool()) {
                fprintf(stderr, "release-sig self-test: %s over %s\n", qPrintable(c.value(QStringLiteral("tag")).toString()),
                        qPrintable(c.value(QStringLiteral("current")).toString()));
                vok = false;
            }
        }
        ok = ok && vok;
    }
    // The signatures block in release notes and pages: which texts give one, what it holds, and that its signatures check
    // out. (A file without "notes", like CI's freshly signed one, skips this.)
    if (all.contains(QStringLiteral("notes"))) {
        const QJsonArray nc = all.value(QStringLiteral("notes")).toArray();
        bool nok = !nc.isEmpty() && all.value(QStringLiteral("notes_first")).toString() == QLatin1String(kSignaturesFirst);
        for (const QJsonValue &v : nc) {
            const QJsonObject c = v.toObject();
            const QString name = c.value(QStringLiteral("name")).toString();
            const QByteArray text = c.value(QStringLiteral("text")).toString().toUtf8() + QByteArray(c.value(QStringLiteral("pad")).toInt(), ' ');
            const Signatures got = parseSignatures(text, c.value(QStringLiteral("page")).toBool());
            bool good = got.ok == c.value(QStringLiteral("valid")).toBool();
            if (good && got.ok) {
                const QJsonArray want = c.value(QStringLiteral("files")).toArray();
                const QByteArray pub = b(c, "public");
                const QString label = c.value(QStringLiteral("label")).toString();
                good = got.label == label && got.files.size() == want.size();
                for (const QJsonValue &w : want) {
                    const QJsonObject e = w.toObject();
                    const QString file = e.value(QStringLiteral("name")).toString();
                    const SignedFile f = got.files.value(file);
                    const QByteArray data = b(e, "data");
                    good = good && got.files.contains(file) && f.sha == e.value(QStringLiteral("sha256")).toString().toLatin1()
                        && f.sig == b(e, "sig") && f.vsig == b(e, "vsig")
                        && QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex() == f.sha && signedBy(data, f.sig, pub)
                        && versionSignedBy(label, file, f.sha, f.vsig, pub);
                }
            }
            if (!good) {
                fprintf(stderr, "release-sig self-test: notes case \"%s\" gave the wrong answer\n", qPrintable(name));
                nok = false;
            }
        }
        ok = ok && nok;
    }
    // Pre-release labels (Labs): numeric version first, a stable release above its betas, betas by number.
    {
        const char *const order[] = {"0.97", "0.98 Test 1", "0.98", "0.99 beta 3", "0.99 beta 7", "0.99 beta 12", "0.99", "0.991 beta 1", "1.0 beta 1", "1.0"};
        const int count = int(sizeof(order) / sizeof(order[0]));
        bool lok = true;
        for (int i = 0; i < count; ++i)
            for (int j = 0; j < count; ++j) {
                const int want = i < j ? -1 : i > j ? 1 : 0;
                if (compareVersions(QString::fromLatin1(order[i]), QString::fromLatin1(order[j])) != want) {
                    fprintf(stderr, "release-sig self-test: \"%s\" against \"%s\" is in the wrong order\n", order[i], order[j]);
                    lok = false;
                }
            }
        const char *const good[] = {"0.98", "0.99 beta 12", "1.0 beta 1"};
        const char *const bad[] = {"", "0.99 beta", "0.99 beta 0", "0.99 Beta 3", "0.99 beta 3 ", "v0.99", "0.99 beta 3\n1"};
        for (const char *g : good)
            lok = lok && isLabel(QString::fromLatin1(g));
        for (const char *b : bad)
            lok = lok && !isLabel(QString::fromLatin1(b));
        // A beta's .vsig signs its label as the version line.
        lok = lok && versionMessage(QStringLiteral("0.99 beta 12"), QStringLiteral("Huginn.flatpak"), QByteArrayLiteral("AB"))
                         == QByteArrayLiteral("huginn-muninn release v1\n0.99 beta 12\nHuginn.flatpak\nab");
        if (!lok)
            fprintf(stderr, "release-sig self-test: pre-release labels FAILED\n");
        ok = ok && lok;
    }
    // The built-in project key is a real Ed25519 key, and refuses a signature it didn't make.
    const QByteArray key = QByteArray::fromHex(kReleaseKey);
    EVP_PKEY *pk = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, reinterpret_cast<const unsigned char *>(key.constData()), key.size());
    const bool keyOk = key.size() == 32 && pk;
    EVP_PKEY_free(pk);
    const QJsonObject first = cases.isEmpty() ? QJsonObject() : cases.first().toObject();
    const bool refusesOthers = !signedByProject(b(first, "message"), b(first, "signature"));
    if (!keyOk || !refusesOthers) {
        fprintf(stderr, "release-sig self-test: the built-in project key %s\n", keyOk ? "accepted someone else's signature" : "isn't a valid Ed25519 key");
        ok = false;
    }
    fprintf(stderr, "release-sig self-test: %s (%d cases)\n", ok ? "passed" : "FAILED", int(cases.size()));
    return ok;
}
