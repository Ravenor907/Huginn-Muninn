// Cap v3 (shared/docs/protocol.md section 29): a video picked from this computer, to a group or one person. The same
// choices as Muninn's VideoSend.kt (1.42 rules: never cut), except that Huginn converts nothing (29.6): it sends full
// size direct only a plain H.264/AAC video at 1080p30 at most, the 720p copy (through FileLink, Tor when there's no
// direct path) only a plain one at 720p30 at most and up to 5 minutes, and the relay copy (older apps only) only one
// that already fits that. Anything else can go as a file. Over 5 minutes with no direct path: "Video too long".
// Round 19: the plan reads only the file's boxes (no whole-file read on the window's thread); the copy that goes is
// made off it, with its location and camera details taken out (GroupVideo::scrub), as Huginn does for pictures. One
// cleaned copy serves everyone getting it by FileLink.
#include "settings.h"
#include "chat.h"
#include "filelink.h"
#include "groupvideo.h"
#include "groupwire.h"
#include "morewire.h"
#include "photos.h"
#include "system.h"
#include "ultraprivacy.h"
#include "vault.h"

#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QNetworkInformation>
#include <QPointer>
#include <QThread>
#include <QUuid>
#include <QSet>
#include <algorithm>
#include <memory>


namespace
{
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }

bool groupOf(const QString &where, QString *g, QString *ch)
{
    if (!where.startsWith(QLatin1String("g:")))
        return false;
    const QStringList parts = where.split(QLatin1Char(':'));
    if (parts.size() != 3)
        return false;
    *g = parts.at(1);
    *ch = parts.at(2);
    return true;
}

// This computer's network isn't charged by the byte (or the system doesn't say): full copies may go to trusted people.
bool unmetered()
{
    if (!QNetworkInformation::instance())
        QNetworkInformation::loadBackendByFeatures(QNetworkInformation::Feature::Metered);
    const QNetworkInformation *n = QNetworkInformation::instance();
    return !n || !n->supports(QNetworkInformation::Feature::Metered) || !n->isMetered();
}

// What a picked video can do here, worked out for planVideo and again for sendPickedVideo (from its boxes only).
struct Picked {
    bool ok = false, asFile = false, tooLong = false, fullOk = false, smallOk = false, relayOk = false;
    QString why, length;
    GroupVideo::Info info;
    QHash<QString, QString> plan;
};

// Cleaned copies waiting to go (FileLink sends from here, for up to a day), each in its own folder so it keeps a plain
// name: in the cache folder, which the erase removes; one older than two days is no longer needed.
QString videosOut() { return System::cacheDir() + QStringLiteral("/videos-out"); }

void clearOldVideos()
{
    const QDateTime old = QDateTime::currentDateTimeUtc().addDays(-2);
    QDirIterator it(videosOut(), QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QFileInfo f(it.next());
        if (f.lastModified().toUTC() < old)
            QFile::remove(f.absoluteFilePath());
    }
    const QFileInfoList dirs = QDir(videosOut()).entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot);
    for (const QFileInfo &d : dirs)
        QDir().rmdir(d.absoluteFilePath());   // only when empty
}
}

GroupVideo::Member Chat::videoMember(const QString &key, bool group) const
{
    GroupVideo::Member m;
    m.k = key;
    m.files = hasCap(key, QString::fromLatin1(FileLink::kCap)) && (!group || hasCap(key, QStringLiteral("f2")));
    m.sameWifi = m_files && m_files->lanHost && !m_files->lanHost(key).isEmpty() && hasCap(key, QStringLiteral("f2"));
    // Ultra privacy: never straight over the internet (the same Wi-Fi or Tor only).
    m.trusted = m_files && m_files->trusted && m_files->trusted(key) && UltraPrivacy::directAllowed();
    m.v3 = hasCap(key, QString::fromLatin1(GroupVideo::kCap));
    m.newPath = hasCap(key, QString::fromLatin1(GroupVideo::kNewPathCap));
    // Family and trusted, with an app that can go through the call relay: full quality, whatever the length and the network,
    // until the relay's month is used up or Data saver says otherwise.
    m.family = m_files && m_files->relayPlan(key, QString()) && !Settings::dataSaverOn();
    return m;
}

QList<GroupVideo::Member> Chat::videoMembers(const QString &where) const
{
    QList<GroupVideo::Member> out;
    QString g, ch;
    if (!groupOf(where, &g, &ch)) {
        out << videoMember(where, false);
        return out;
    }
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return out;
    for (const QString &k : GroupWire::keysOf(it->state))
        if (k != myKey())
            out << videoMember(k, true);
    return out;
}

bool Chat::addGroupBlobMember(const QString &g, const QString &gid, const QString &k)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left || k == myKey() || !GroupWire::keysOf(it->state).contains(k))
        return false;
    QVariantMap m;
    for (int i = int(it->messages.size()) - 1; i >= 0 && m.isEmpty(); --i) {
        const QVariantMap x = it->messages.at(i).toMap();
        if (str(x, "id") == gid)
            m = x;
    }
    if (m.isEmpty() || !m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty() || str(m, "photo").isEmpty())
        return false;
    const int ms = m.value(QStringLiteral("video")).toInt();
    if (m.value(QStringLiteral("to")).toStringList().contains(k) || (ms > 0 && !hasM2(k)))
        return false;
    const QByteArray bytes = blob(str(m, "photo"));
    if (bytes.isEmpty())
        return false;
    updateGroupMessage(g, gid, [k](QVariantMap &x) { x.insert(QStringLiteral("to"), x.value(QStringLiteral("to")).toStringList() << k); });
    QVariantMap extra;
    if (ms > 0)
        extra.insert(QStringLiteral("video"), ms);
    queueGroupBodies(g, k, QStringLiteral("gphoto"), gid, groupPieces(g, str(m, "ch"), gid, k, bytes, m.value(QStringLiteral("voice")).toInt(), extra));
    return true;
}

namespace
{
Picked pick(const QString &path, const QList<GroupVideo::Member> &members)
{
    Picked p;
    const QFileInfo fi(path);
    if (!fi.isFile() || fi.size() < 1) {
        p.why = QStringLiteral("That video can't be read.");
        return p;
    }
    if (fi.size() > GroupVideo::kFullMaxBytes) {
        p.why = QStringLiteral("Videos and files can be up to 4 GB.");
        return p;
    }
    // Only the boxes are read here (the window waits for this): never the whole file.
    QByteArray head;
    p.info = GroupVideo::probeFile(path, &head);
    if (head.isEmpty()) {
        p.why = QStringLiteral("That video can't be read.");
        return p;
    }
    if (!MoreWire::isVideo(head)) {
        p.asFile = true;
        p.why = QStringLiteral("Only MP4 videos can be sent as videos. It can go as a file.");
        return p;
    }
    if (p.info.w <= 0 || p.info.ms <= 0) {
        p.asFile = true;
        p.why = QStringLiteral("Huginn can't read that video's size or length. It can go as a file.");
        return p;
    }
    p.length = GroupVideo::length(p.info.ms);
    p.plan = GroupVideo::plan(members, unmetered(), p.info.ms);
    // Never cut: over 5 minutes and someone isn't direct.
    if (GroupVideo::tooLong(p.info.ms, p.plan)) {
        p.asFile = true;
        p.tooLong = true;
        p.why = QString::fromLatin1(GroupVideo::kTooLongText);
        return p;
    }
    // Only H.264 and AAC in MP4 (29.1): anything else may not play on a TV, an older phone or a computer without the codec.
    if (!p.info.plain) {
        p.asFile = true;
        p.why = QStringLiteral("Not every device can play this video's format (like HEVC or HDR), and Huginn can't convert it. It can go as a file.");
        return p;
    }
    // Its location and camera details must come out before it goes as a video (checked here, done when it's sent).
    {
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly) || GroupVideo::scrub(&f, false) <= 0) {
            p.asFile = true;
            p.why = QStringLiteral("Huginn can't take this video's location and camera details out, so it won't send it as a video. "
                                   "As a file, those details go with it.");
            return p;
        }
    }
    p.fullOk = GroupVideo::fitsFull(p.info);
    p.smallOk = GroupVideo::fitsSmall(p.info);
    p.relayOk = GroupVideo::fitsRelay(p.info);
    // What Huginn can't make (it converts nothing): no full copy of a 4K or 60 fps video, no 720p copy of a bigger one,
    // no relay copy of one that doesn't already fit it.
    bool any = false;
    for (auto it = p.plan.begin(); it != p.plan.end(); ++it) {
        if ((it.value() == QLatin1String("full") && !p.fullOk) || (it.value() == QLatin1String("small") && !p.smallOk)
            || (it.value() == QLatin1String("relay") && !p.relayOk))
            it.value() = QStringLiteral("none");
        any = any || it.value() != QLatin1String("none");
    }
    if (!any && !p.plan.isEmpty()) {
        p.asFile = true;
        p.why = GroupVideo::biggerThan720(p.info) || !GroupVideo::withinFps(p.info.fps, GroupVideo::kFullFps)
            ? QStringLiteral("Over the internet, videos go at 720p and 30 frames a second at most, and Huginn can't shrink videos. It can go as a file.")
            : QStringLiteral("Their app is older and gets videos up to 20 seconds. It can go as a file.");
        return p;
    }
    p.ok = true;
    return p;
}

int count(const QHash<QString, QString> &plan, const char *how)
{
    int n = 0;
    for (const QString &v : plan)
        n += v == QLatin1String(how) ? 1 : 0;
    return n;
}
}

// 1.42: sending videos is allowed under Ultra privacy (over Tor or the same Wi-Fi only), so it's never off.
bool Chat::videoSendingOff() const { return false; }

QVariantMap Chat::planVideo(const QString &where, const QString &path, bool inFull)
{
    Q_UNUSED(inFull)   // 1.42: no "in full later": a video is never cut, and full copies wait as any file does
    const Picked p = pick(path, videoMembers(where));
    QVariantMap out{{QStringLiteral("ok"), p.ok}, {QStringLiteral("why"), p.why}, {QStringLiteral("asFile"), p.asFile},
                    {QStringLiteral("tooLong"), p.tooLong}, {QStringLiteral("ms"), p.info.ms},
                    {QStringLiteral("title"), p.tooLong ? QString::fromLatin1(GroupVideo::kTooLongTitle) : QString()}};
    if (!p.ok)
        return out;
    QString said = GroupVideo::summary(p.plan, [this](const QString &k) { return nameOf(k); });
    const int none = count(p.plan, "none");
    if (none > 0 && !p.fullOk)
        said += QStringLiteral(" It's over 1080p or 30 frames a second, and Huginn can't convert videos: as a file it reaches everyone.");
    else if (none > 0 && count(p.plan, "small") == 0 && !p.smallOk && GroupVideo::biggerThan720(p.info))
        said += QStringLiteral(" Over the internet videos go at 720p, and Huginn can't shrink them: as a file it reaches everyone.");
    out.insert(QStringLiteral("length"), p.length);
    out.insert(QStringLiteral("summary"), said);
    out.insert(QStringLiteral("full"), count(p.plan, "full"));
    out.insert(QStringLiteral("small"), count(p.plan, "small"));
    // "relay" counts every copy that isn't full size (the 720p copy and the old relay copy), so a sheet that adds full
    // and relay knows something goes.
    out.insert(QStringLiteral("relay"), count(p.plan, "relay") + count(p.plan, "small"));
    out.insert(QStringLiteral("none"), none);
    out.insert(QStringLiteral("relayOk"), p.relayOk);
    out.insert(QStringLiteral("inFull"), false);
    return out;
}

QVariantMap Chat::sendPickedVideo(const QString &where, const QString &path, bool inFull)
{
    Q_UNUSED(inFull)
    auto result = [](bool ok, const QString &said) { return QVariantMap{{QStringLiteral("ok"), ok}, {QStringLiteral("said"), said}}; };
    if (!m_files)
        return result(false, QStringLiteral("Not ready yet."));
    const QList<GroupVideo::Member> members = videoMembers(where);
    const Picked p = pick(path, members);
    if (!p.ok)
        return result(false, p.why);
    QSet<QString> newApps;
    for (const GroupVideo::Member &m : members)
        if (m.newPath)
            newApps.insert(m.k);
    QStringList full, small, relay;
    for (auto it = p.plan.cbegin(); it != p.plan.cend(); ++it) {
        if (it.value() == QLatin1String("full"))
            full << it.key();
        else if (it.value() == QLatin1String("small"))
            small << it.key();
        else if (it.value() == QLatin1String("relay"))
            relay << it.key();
    }
    if (full.isEmpty() && small.isEmpty() && relay.isEmpty())
        return result(false, QStringLiteral("Nobody here can get this video now. Their app is older: ask them to update."));
    QString g, ch;
    const bool group = groupOf(where, &g, &ch);
    if (!group && !relay.isEmpty() && !hasM2(where)) {
        probeCaps(where);
        return result(false, nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to get videos."));
    }
    // The copies that go, cleaned, made off the window's thread: the relay copy in memory (3 MB at most, older apps), and
    // one cleaned copy in videos-out for everyone getting it through FileLink (full size or the 720p copy: Huginn
    // converts nothing, so it's the same file). Then they're sent from here (finished).
    clearOldVideos();
    const bool oldFull = std::any_of(full.cbegin(), full.cend(), [&newApps](const QString &k) { return !newApps.contains(k); });
    const bool wantClip = p.relayOk && (!relay.isEmpty() || oldFull), wantCopy = !full.isEmpty() || !small.isEmpty();
    const QString out = videosOut() + QLatin1Char('/') + QUuid::createUuid().toString(QUuid::WithoutBraces) + QLatin1Char('/')
        + QStringLiteral("Video ") + QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HH.mm.ss")) + QStringLiteral(".mp4");
    auto clip = std::make_shared<QByteArray>();
    auto copyPath = std::make_shared<QString>();
    QThread *t = QThread::create([path, out, wantClip, wantCopy, clip, copyPath] {
        if (wantClip) {
            QFile f(path);
            if (f.open(QIODevice::ReadOnly) && f.size() <= GroupVideo::kRelayBytes)
                *clip = GroupVideo::scrubbed(f.read(GroupVideo::kRelayBytes));
        }
        if (wantCopy) {
            QDir().mkpath(QFileInfo(out).absolutePath());
            QFile::remove(out);
            if (QFile::copy(path, out) && QFile::setPermissions(out, QFile::ReadOwner | QFile::WriteOwner) && GroupVideo::scrubFile(out))
                *copyPath = out;
            else
                QFile::remove(out);
        }
    });
    t->setObjectName(QStringLiteral("huginn-video-copy"));
    const int ms = qMax(1, p.info.ms);
    const int relayMs = qBound(1, p.info.ms, MoreWire::kVideoMaxMs);
    const bool smallOk = p.smallOk;
    QPointer<Chat> self(this);
    connect(t, &QThread::finished, this, [self, t, where, group, g, ch, full, small, relay, newApps, ms, relayMs, smallOk, clip, copyPath] {
        t->deleteLater();
        if (!self)
            return;
        if (!self->m_files) {
            if (!copyPath->isEmpty())
                QFile::remove(*copyPath);
            Q_EMIT self->notice(QStringLiteral("The video didn't go. Try again."));
            return;
        }
        const QByteArray clipBytes = *clip;
        const QString copy = *copyPath;
        const QString to = group ? g : QString();
        // No cleaned copy: older apps among those it was for get the relay copy when there is one; new apps get nothing.
        const QStringList fullNow = copy.isEmpty() ? QStringList() : full;
        const QStringList smallNow = copy.isEmpty() ? QStringList() : small;
        QStringList relayNow = clipBytes.isEmpty() ? QStringList() : relay;
        if (copy.isEmpty() && !clipBytes.isEmpty())
            for (const QString &k : full)
                if (!newApps.contains(k))
                    relayNow << k;
        if (fullNow.isEmpty() && smallNow.isEmpty() && relayNow.isEmpty()) {
            Q_EMIT self->notice(QStringLiteral("The video didn't go: Huginn couldn't take its location and camera details out. It can go as a file."));
            return;
        }
        const QVariantMap extra{{QStringLiteral("video"), relayMs}};
        QPointer<Chat> me = self;
        auto gid = std::make_shared<QString>();
        if (group && !relayNow.isEmpty())
            *gid = self->sendGroupBlob(g, ch, clipBytes, 0, extra, relayNow);
        else if (!group && !relayNow.isEmpty())
            self->sendBlob(where, clipBytes, 0, extra);
        for (const QString &k : fullNow) {
            std::function<void()> back;
            if (newApps.contains(k)) {
                // A new app it can't reach directly after all (or that declines a full-size offer, a kids' device): the
                // same file, when it's 720p or smaller, as the 720p copy through FileLink (Tor).
                if (smallOk)
                    back = [me, k, copy, to] {
                        if (me && me->m_files)
                            me->m_files->send(k, copy, to);
                    };
            } else if (!clipBytes.isEmpty()) {
                back = [me, group, g, ch, where, clipBytes, extra, k, gid] {
                    if (!me)
                        return;
                    if (!group) {
                        if (me->hasM2(where))
                            me->sendBlob(where, clipBytes, 0, extra);
                    } else if (gid->isEmpty()) {
                        *gid = me->sendGroupBlob(g, ch, clipBytes, 0, extra, {k});
                    } else {
                        me->addGroupBlobMember(g, *gid, k);
                    }
                };
            }
            self->m_files->sendVideo(k, copy, to, ms, back);
        }
        // The 720p copy: through FileLink by its best path (Tor when there's no direct one), never the relays.
        for (const QString &k : smallNow)
            self->m_files->send(k, copy, to);
        QStringList parts;
        if (!fullNow.isEmpty())
            parts << QStringLiteral("full size to %1").arg(fullNow.size());
        if (!smallNow.isEmpty())
            parts << QStringLiteral("a 720p copy over Tor to %1").arg(smallNow.size());
        if (!relayNow.isEmpty())
            parts << QStringLiteral("a smaller copy to %1 with older apps").arg(relayNow.size());
        if (!group && !fullNow.isEmpty())
            Q_EMIT self->notice(QStringLiteral("Sending the video full size."));
        else if (!group && !smallNow.isEmpty())
            Q_EMIT self->notice(QStringLiteral("Sending a 720p copy of the video over Tor. It can take a while."));
        else if (!group)
            Q_EMIT self->notice(QStringLiteral("Sending the video. Its pieces go a few a second, so it can take a few minutes."));
        else
            Q_EMIT self->notice(QStringLiteral("Sending the video: ") + parts.join(QStringLiteral(", ")) + QLatin1Char('.'));
    });
    t->start();
    return result(true, QStringLiteral("Getting the video ready."));
}
