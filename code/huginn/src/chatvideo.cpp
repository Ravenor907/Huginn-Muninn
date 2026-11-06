// Cap v3 (shared/docs/protocol.md section 29): a video picked from this computer, to a group or one person. The same
// choices as Muninn's VideoSend.kt (1.42 rules: never cut), except that Huginn converts nothing (29.6): it sends full
// size direct only a plain H.264/AAC video at 1080p30 at most, the 720p copy (through FileLink, Tor when there's no
// direct path) only a plain one at 720p30 at most and up to 5 minutes, and the relay copy (older apps only) only one
// that already fits that. Anything else can go as a file. Over 5 minutes with no direct path: "Video too long".
// Round 19: the plan reads only the file's boxes (no whole-file read on the window's thread); the copy that goes is
// made off it, with its location and camera details taken out (GroupVideo::scrub), as Huginn does for pictures. One
// cleaned copy serves everyone getting it by FileLink.
#include "settings.h"
#include "bigvideo.h"
#include "chat.h"
#include "filelink.h"
#include "groupvideo.h"
#include "groupwire.h"
#include "moderation.h"
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
#include <QStorageInfo>
#include <QTimer>
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
    m.family = m_files && m_files->relayPlan(key, QString()) && !m_files->relayOver() && !Settings::dataSaverOn();
    m.reach = m.family || (m_files && m_files->reachNote(key).isEmpty());
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
    // As small as a photo (one message over the relays, up to a minute) and everyone's app plays video messages: it goes
    // into the chat at once, like a photo, at its own quality, cleaned of location and camera details.
    if (p.info.ms <= MoreWire::kVideoMaxMs && QFileInfo(path).size() > 0 && QFileInfo(path).size() <= Photos::kVideoSendBytes
        && std::all_of(members.cbegin(), members.cend(), [this](const GroupVideo::Member &m) { return hasM2(m.k); })) {
        QFile f(path);
        const QByteArray clean = f.open(QIODevice::ReadOnly) ? GroupVideo::scrubbed(f.read(Photos::kVideoSendBytes + 1)) : QByteArray();
        if (!clean.isEmpty() && clean.size() <= Photos::kVideoSendBytes && sendVideo(where, clean, qMax(1, p.info.ms)).isEmpty())
            return result(true, QString());
    }
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
        // Family and trusted only, up to 3 minutes, every copy full size to an app with the new paths: the video shows in
        // the chat at once like a photo (bigvideo.h), "sending" until a copy arrives, and each FileLink card follows it
        // (shown only if it fails). Nothing changes on the wire: an older app sees the same file offer.
        const bool asBubble = !copy.isEmpty() && smallNow.isEmpty() && relayNow.isEmpty() && !fullNow.isEmpty() && BigVideo::inlineOk(ms, true)
            && std::all_of(fullNow.cbegin(), fullNow.cend(), [&newApps, &self](const QString &k) { return newApps.contains(k) && self->m_files->mayAlwaysFrom(k); });
        const QString chatKey = group ? QStringLiteral("g:") + g : where;
        auto go = [self, where, group, g, ch, newApps, ms, relayMs, smallOk, clipBytes, copy, to, fullNow, smallNow, relayNow, chatKey](const QString &bubble) {
            if (!self || !self->m_files)
                return;
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
                        back = [me, k, copy, to, bubble, chatKey] {
                            if (!me || !me->m_files)
                                return;
                            if (!bubble.isEmpty())
                                me->m_files->linkNext(chatKey, bubble);
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
                if (!bubble.isEmpty())
                    self->m_files->linkNext(chatKey, bubble);
                self->m_files->sendVideo(k, copy, to, ms, back);
            }
            // The 720p copy: through FileLink by its best path (Tor when there's no direct one), never the relays.
            for (const QString &k : smallNow)
                self->m_files->send(k, copy, to);
            QStringList parts;
            if (!fullNow.isEmpty())
                parts << QStringLiteral("full size to %1").arg(fullNow.size());
            if (!smallNow.isEmpty())
                parts << QStringLiteral("a 720p copy to %1").arg(smallNow.size());
            if (!relayNow.isEmpty())
                parts << QStringLiteral("a smaller copy to %1 with older apps").arg(relayNow.size());
            // In the chat as a bubble: it says Sending there, so nothing more is said here.
            if (!bubble.isEmpty())
                return;
            if (!group && !fullNow.isEmpty())
                Q_EMIT self->notice(QStringLiteral("Sending the video full size."));
            else if (!group && !smallNow.isEmpty())
                Q_EMIT self->notice(QStringLiteral("Sending a 720p copy of the video."));
            else if (!group)
                Q_EMIT self->notice(QStringLiteral("Sending the video. Its pieces go a few a second, so it can take a few minutes."));
            else
                Q_EMIT self->notice(QStringLiteral("Sending the video: ") + parts.join(QStringLiteral(", ")) + QLatin1Char('.'));
        };
        if (!asBubble) {
            go(QString());
            return;
        }
        // Kept first (a few seconds for a long one), then sent: each card is linked to the message it follows. When it
        // can't be kept, it goes as before.
        self->ownBigVideo(chatKey, copy, ms, go);
    });
    t->start();
    return result(true, QStringLiteral("Getting the video ready."));
}

// ------------------------------------------------------------------ longer videos in the chat (Family and trusted)

bool Chat::isBigVideo(const QString &id) const
{
    return m_ready && !id.isEmpty() && !id.contains(QLatin1Char('/')) && QFileInfo::exists(m_vault->path(BigVideo::sizeName(id)));
}

QIODevice *Chat::bigVideoDevice(const QString &id, QObject *parent) const
{
    if (!isBigVideo(id))
        return nullptr;
    const qint64 total = m_vault->read(BigVideo::sizeName(id)).trimmed().toLongLong();
    if (total <= 0)
        return nullptr;
    // Read on the media player's own thread too: anyThreadReader never waits on the window's thread.
    const std::function<QByteArray(const QString &)> read = m_vault->anyThreadReader();
    return new BigVideo::Device(total, [read, id](int i) { return read(BigVideo::piece(id, i)); }, parent);
}

void Chat::dropBig(const QString &id)
{
    if (id.isEmpty() || id.contains(QLatin1Char('/')))
        return;
    const QString dir = m_vault->path(BigVideo::dir(id));
    const QStringList files = QDir(dir).entryList(QDir::Files | QDir::Hidden);
    for (const QString &f : files)
        m_vault->remove(BigVideo::dir(id) + QLatin1Char('/') + f);
    QDir(dir).removeRecursively();   // anything left over (a save that was cut off)
}

void Chat::sweepBig()
{
    const qint64 t = QDateTime::currentMSecsSinceEpoch();
    if (!m_ready || m_vault->locked() || t - m_bigSwept < 3600000)
        return;
    m_bigSwept = t;
    const QString photos = m_vault->path(QStringLiteral("photos"));
    const QStringList dirs = QDir(photos).entryList({QStringLiteral("*.v")}, QDir::Dirs | QDir::NoDotAndDotDot);
    for (const QString &d : dirs) {
        const QString id = d.chopped(2);
        if (!id.isEmpty() && !m_bigStoring.contains(id) && !QFileInfo::exists(photos + QLatin1Char('/') + id))
            dropBig(id);
    }
}

void Chat::storeBigVideo(const QString &id, const QString &path, std::function<void(bool)> done)
{
    struct Job {
        QString id;
        QFile file;
        qint64 total = 0, written = 0;
        int i = 0;
        std::function<void(bool)> done;
    };
    auto job = std::make_shared<Job>();
    job->id = id;
    job->done = std::move(done);
    const QFileInfo fi(path);
    QStorageInfo disk(QFileInfo(m_vault->path(QStringLiteral("photos"))).absolutePath());
    disk.refresh();
    const bool room = !disk.isValid() || disk.bytesAvailable() < 0 || disk.bytesAvailable() >= fi.size() + (qint64(200) << 20);
    job->file.setFileName(path);
    if (!m_ready || m_vault->locked() || id.isEmpty() || id.contains(QLatin1Char('/')) || m_bigStoring.contains(id) || !fi.isFile()
        || fi.size() <= 0 || fi.size() > FileLink::kMaxBytes || !room || !job->file.open(QIODevice::ReadOnly)) {
        QTimer::singleShot(0, this, [job] { job->done(false); });
        return;
    }
    job->total = fi.size();
    dropBig(id);
    m_bigStoring.insert(id);
    QPointer<Chat> self(this);
    auto fail = [self, job] {
        job->file.close();
        if (self) {
            self->m_bigStoring.remove(job->id);
            self->dropBig(job->id);
            self->m_vault->remove(QStringLiteral("photos/") + job->id);
        }
        job->done(false);
    };
    // One sealed piece per turn of the event loop, so the window keeps moving while a long video is kept.
    auto step = std::make_shared<std::function<void()>>();
    std::weak_ptr<std::function<void()>> weak = step;
    *step = [self, job, fail, weak] {
        if (!self)
            return;
        Chat *c = self;
        if (!c->m_ready || c->m_vault->locked()) {
            fail();
            return;
        }
        QByteArray chunk = job->file.read(BigVideo::kPiece);
        if (!chunk.isEmpty()) {
            const bool ok = c->m_vault->write(BigVideo::piece(job->id, job->i), chunk);
            job->written += chunk.size();
            chunk.fill(0);
            ++job->i;
            if (!ok || job->written > job->total) {
                fail();
                return;
            }
            if (const std::shared_ptr<std::function<void()>> next = weak.lock())
                QTimer::singleShot(0, c, [next] { (*next)(); });
            return;
        }
        job->file.close();
        if (job->written != job->total || !c->m_vault->write(BigVideo::sizeName(job->id), QByteArray::number(job->total))) {
            fail();
            return;
        }
        // Its first frame, read from the sealed pieces: the bubble's picture.
        QIODevice *dev = c->bigVideoDevice(job->id);
        if (!dev) {
            fail();
            return;
        }
        BigVideo::poster(dev, c, [self, job, fail](const QByteArray &jpg) {
            if (!self || !self->m_ready || jpg.isEmpty() || !self->m_vault->write(QStringLiteral("photos/") + job->id, jpg)) {
                fail();
                return;
            }
            self->m_bigStoring.remove(job->id);
            job->done(true);
        });
    };
    QTimer::singleShot(0, this, [step] { (*step)(); });
}

bool Chat::exportBig(const QString &id, const QString &path) const
{
    if (!isBigVideo(id))
        return false;
    const qint64 total = m_vault->read(BigVideo::sizeName(id)).trimmed().toLongLong();
    QFile f(path);
    if (total <= 0 || !f.open(QIODevice::WriteOnly))
        return false;
    qint64 done = 0;
    for (int i = 0; done < total; ++i) {
        QByteArray b = m_vault->read(BigVideo::piece(id, i));
        const bool ok = !b.isEmpty() && f.write(b) == b.size();
        done += b.size();
        b.fill(0);
        if (!ok) {
            f.close();
            f.remove();
            return false;
        }
    }
    f.close();
    if (done != total) {
        f.remove();
        return false;
    }
    return true;
}

void Chat::inlineVideo(const QString &from, const QString &group, const QString &path, int ms, std::function<void(bool)> done)
{
    if (!m_ready || from.isEmpty() || from == myKey() || (group.isEmpty() && (!isContact(from) || isTrashed(from)))
        || (!group.isEmpty() && !m_groups.contains(group))) {
        QTimer::singleShot(0, this, [done] { done(false); });
        return;
    }
    const QString mid = group.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces) : GroupWire::newLogicalId();
    const int len = qBound(1, ms, BigVideo::kInlineMaxMs + BigVideo::kSlackMs);
    QPointer<Chat> self(this);
    storeBigVideo(mid, path, [self, from, group, mid, len, done](bool ok) {
        if (!self || !ok) {
            done(false);
            return;
        }
        Chat *c = self;
        bool added = false;
        if (group.isEmpty()) {
            const QString name = c->nameOf(from);
            QVariantMap m = c->message(mid, from, name, QString(), false, QString());
            m.insert(QStringLiteral("photo"), mid);
            m.insert(QStringLiteral("video"), len);
            m.insert(QStringLiteral("vb"), true);
            added = !c->isTrashed(from) && c->addPrivate(from, m);
            if (added) {
                c->m_unread.insert(from);
                Q_EMIT c->arrived(name, QStringLiteral("Video"), from);
            }
        } else {
            // The group's first channel, under the same rules as a photo from them (cap g2 and g3).
            const auto it = c->m_groups.constFind(group);
            if (it != c->m_groups.cend() && !it->state.isEmpty() && !it->removed && !it->left && GroupWire::isMember(it->state, from)
                && !(c->m_mod && c->m_mod->barred(group, from))
                && !(GroupWire::channel(it->state, QStringLiteral("main")).value(QStringLiteral("ao")).toBool() && !GroupWire::isAdmin(it->state, from))) {
                const QVariantMap m{{QStringLiteral("id"), mid}, {QStringLiteral("ch"), QStringLiteral("main")}, {QStringLiteral("from"), from},
                                    {QStringLiteral("name"), c->memberName(group, from)}, {QStringLiteral("text"), QString()},
                                    {QStringLiteral("mine"), false}, {QStringLiteral("time"), QDateTime::currentMSecsSinceEpoch()},
                                    {QStringLiteral("via"), QString()}, {QStringLiteral("state"), QString()}, {QStringLiteral("photo"), mid},
                                    {QStringLiteral("voice"), 0}, {QStringLiteral("unsent"), QString()}, {QStringLiteral("video"), len},
                                    {QStringLiteral("vb"), true}};
                c->storeGroupMessage(group, m, true);
                added = true;
            }
        }
        if (!added) {
            c->m_vault->remove(QStringLiteral("photos/") + mid);
            c->dropBig(mid);
        }
        done(added);
    });
}

void Chat::ownBigVideo(const QString &where, const QString &path, int ms, std::function<void(const QString &)> done)
{
    const bool group = where.startsWith(QLatin1String("g:"));
    const QString g = group ? where.mid(2) : QString();
    if (!m_ready || where.isEmpty() || (group && !m_groups.contains(g))) {
        QTimer::singleShot(0, this, [done] { done(QString()); });
        return;
    }
    const QString mid = group ? GroupWire::newLogicalId() : QUuid::createUuid().toString(QUuid::WithoutBraces);
    const int len = qBound(1, ms, BigVideo::kInlineMaxMs + BigVideo::kSlackMs);
    QPointer<Chat> self(this);
    storeBigVideo(mid, path, [self, where, group, g, mid, len, done](bool ok) {
        if (!self || !ok) {
            done(QString());
            return;
        }
        Chat *c = self;
        bool added = false;
        if (group) {
            const auto it = c->m_groups.constFind(g);
            if (it != c->m_groups.cend() && !it->state.isEmpty() && !it->removed && !it->left) {
                // Local only: each copy went as its own file, so nothing waits in the group's outbox for it.
                const QVariantMap m{{QStringLiteral("id"), mid}, {QStringLiteral("ch"), QStringLiteral("main")}, {QStringLiteral("from"), c->myKey()},
                                    {QStringLiteral("name"), c->username()}, {QStringLiteral("text"), QString()}, {QStringLiteral("mine"), true},
                                    {QStringLiteral("time"), QDateTime::currentMSecsSinceEpoch()}, {QStringLiteral("state"), QStringLiteral("sending")},
                                    {QStringLiteral("to"), QStringList()}, {QStringLiteral("dlv"), QStringList()}, {QStringLiteral("rd"), QStringList()},
                                    {QStringLiteral("photo"), mid}, {QStringLiteral("voice"), 0}, {QStringLiteral("unsent"), QString()},
                                    {QStringLiteral("failed"), false}, {QStringLiteral("video"), len}, {QStringLiteral("vb"), true}};
                c->storeGroupMessage(g, m, false);
                added = true;
            }
        } else {
            QVariantMap m = c->message(mid, where, c->username(), QString(), true, QString());
            m.insert(QStringLiteral("state"), QStringLiteral("sending"));
            m.insert(QStringLiteral("photo"), mid);
            m.insert(QStringLiteral("video"), len);
            m.insert(QStringLiteral("vb"), true);
            added = c->addPrivate(where, m);
        }
        if (!added) {
            c->m_vault->remove(QStringLiteral("photos/") + mid);
            c->dropBig(mid);
        }
        done(added ? mid : QString());
    });
}

void Chat::bigVideoSent(const QString &chat, const QString &msgId)
{
    const auto sent = [](QVariantMap &x) {
        if (x.value(QStringLiteral("mine")).toBool() && str(x, "state") == QLatin1String("sending")) {
            x.insert(QStringLiteral("state"), QStringLiteral("sent"));
            x.insert(QStringLiteral("failed"), false);
        }
    };
    if (chat.startsWith(QLatin1String("g:")))
        updateGroupMessage(chat.mid(2), msgId, sent);
    else
        update(chat, msgId, sent);
}
