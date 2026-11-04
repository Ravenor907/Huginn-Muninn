// Cap v3 (shared/docs/protocol.md section 29): a video picked from this computer, to a group or one person. The same
// choices as Muninn's VideoSend.kt, except that Huginn converts nothing (29.6): a video goes full size only if it's
// already plain H.264 at 1080p30 at most, and as the relay copy only if it already fits that.
// Round 19: the plan reads only the file's boxes (no whole-file read on the window's thread); the copies that go are
// made off it, with their location and camera details taken out (GroupVideo::scrub), as Huginn does for pictures.
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
#include <QFile>
#include <QFileInfo>
#include <QNetworkInformation>
#include <QPointer>
#include <QThread>
#include <QUuid>
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

// Ultra privacy: sending videos is off entirely (receiving still works).
bool ultraOn() { return UltraPrivacy::on(); }

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
    bool ok = false, asFile = false, fullOk = false, relayOk = false, inFull = false;
    bool clipOk = false;   // the video itself goes as the relay copy
    QString why, length;
    GroupVideo::Info info;
    QHash<QString, QString> plan;
};

// Cleaned full copies waiting to go straight (FileLink sends from here, for up to a day): in the cache folder, which
// the erase removes; one older than two days is no longer needed.
QString videosOut() { return System::cacheDir() + QStringLiteral("/videos-out"); }

void clearOldVideos()
{
    const QDateTime old = QDateTime::currentDateTimeUtc().addDays(-2);
    const QFileInfoList list = QDir(videosOut()).entryInfoList(QDir::Files);
    for (const QFileInfo &f : list)
        if (f.lastModified().toUTC() < old)
            QFile::remove(f.absoluteFilePath());
}
}

GroupVideo::Member Chat::videoMember(const QString &key, bool group) const
{
    GroupVideo::Member m;
    m.k = key;
    m.files = hasCap(key, QString::fromLatin1(FileLink::kCap)) && (!group || hasCap(key, QStringLiteral("f2")));
    m.sameWifi = m_files && m_files->lanHost && !m_files->lanHost(key).isEmpty() && hasCap(key, QStringLiteral("f2"));
    m.trusted = m_files && m_files->trusted && m_files->trusted(key);
    m.v3 = hasCap(key, QString::fromLatin1(GroupVideo::kCap));
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
Picked pick(const QString &path, bool inFull, const QList<GroupVideo::Member> &members)
{
    Picked p;
    const QFileInfo fi(path);
    if (!fi.isFile() || fi.size() < 1) {
        p.why = QStringLiteral("That video can't be read.");
        return p;
    }
    // Huginn can't make smaller copies: anything over the full-size limit can only go as a file.
    if (fi.size() > GroupVideo::kFullMaxBytes) {
        p.asFile = true;
        p.why = QStringLiteral("That video is too big to send as a video from Huginn (it can't make smaller copies). It can go as a file.");
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
    if (p.length == QLatin1String("too long")) {
        p.asFile = true;
        p.why = QStringLiteral("Videos can be up to a minute (and 20 seconds over the internet). A longer one can go as a file.");
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
    p.relayOk = GroupVideo::fitsRelay(p.info);
    // A longer video: only in full, when direct (Huginn can't trim).
    p.inFull = inFull || p.length == QLatin1String("long");
    p.clipOk = p.relayOk && !p.inFull;
    p.plan = GroupVideo::plan(members, unmetered(), p.inFull);
    // What Huginn can't make: no full copy of a 4K or 60 fps video, no relay copy of a video that doesn't already fit it.
    for (auto it = p.plan.begin(); it != p.plan.end(); ++it) {
        if (it.value() == QLatin1String("full") && !p.fullOk)
            it.value() = p.relayOk && !p.inFull ? QStringLiteral("relay") : QStringLiteral("none");
        if (it.value() == QLatin1String("relay") && !p.relayOk)
            it.value() = QStringLiteral("none");
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

bool Chat::videoSendingOff() const { return ultraOn(); }

QVariantMap Chat::planVideo(const QString &where, const QString &path, bool inFull)
{
    if (ultraOn())
        return {{QStringLiteral("ok"), false}, {QStringLiteral("why"), QStringLiteral("Off while Ultra privacy is on.")}};
    const Picked p = pick(path, inFull, videoMembers(where));
    QVariantMap out{{QStringLiteral("ok"), p.ok}, {QStringLiteral("why"), p.why}, {QStringLiteral("asFile"), p.asFile}};
    if (!p.ok)
        return out;
    QString said = GroupVideo::summary(p.plan, [this](const QString &k) { return nameOf(k); });
    if (!p.fullOk)
        said += QStringLiteral(" It's over 1080p or 30 frames a second, and Huginn can't convert videos: send it from your phone for full size.");
    else if (!p.relayOk && count(p.plan, "none") > 0)
        said += p.length == QLatin1String("long") ? QStringLiteral(" Over the internet, videos can be up to 20 seconds, and Huginn can't trim them.")
                                                  : QStringLiteral(" Huginn can't make a smaller copy for the internet: send it from your phone.");
    out.insert(QStringLiteral("ms"), p.info.ms);
    out.insert(QStringLiteral("length"), p.length);
    out.insert(QStringLiteral("summary"), said);
    out.insert(QStringLiteral("full"), count(p.plan, "full"));
    out.insert(QStringLiteral("relay"), count(p.plan, "relay"));
    out.insert(QStringLiteral("none"), count(p.plan, "none"));
    out.insert(QStringLiteral("relayOk"), p.relayOk);
    out.insert(QStringLiteral("inFull"), p.inFull);
    return out;
}

QVariantMap Chat::sendPickedVideo(const QString &where, const QString &path, bool inFull)
{
    auto result = [](bool ok, const QString &said) { return QVariantMap{{QStringLiteral("ok"), ok}, {QStringLiteral("said"), said}}; };
    if (ultraOn())
        return result(false, QStringLiteral("Off while Ultra privacy is on."));
    if (!m_files)
        return result(false, QStringLiteral("Not ready yet."));
    const Picked p = pick(path, inFull, videoMembers(where));
    if (!p.ok)
        return result(false, p.why);
    QStringList full, relay;
    for (auto it = p.plan.cbegin(); it != p.plan.cend(); ++it) {
        if (it.value() == QLatin1String("full"))
            full << it.key();
        else if (it.value() == QLatin1String("relay"))
            relay << it.key();
    }
    if (full.isEmpty() && relay.isEmpty())
        return result(false, QStringLiteral("Nobody here can get this video from Huginn now: they need the same Wi-Fi or Direct calls and files with you."));
    QString g, ch;
    const bool group = groupOf(where, &g, &ch);
    if (!group && full.isEmpty() && !hasM2(where)) {
        probeCaps(where);
        return result(false, nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to get videos."));
    }
    // The copies that go, cleaned, made off the window's thread: the relay copy in memory (3 MB at most), the full copy
    // in videos-out. Then they're sent from here (finished), as before.
    clearOldVideos();
    const bool wantClip = p.clipOk, wantFull = !full.isEmpty();
    const QString out = videosOut() + QLatin1Char('/') + QUuid::createUuid().toString(QUuid::WithoutBraces) + QStringLiteral(".mp4");
    auto clip = std::make_shared<QByteArray>();
    auto fullPath = std::make_shared<QString>();
    QThread *t = QThread::create([path, out, wantClip, wantFull, clip, fullPath] {
        if (wantClip) {
            QFile f(path);
            if (f.open(QIODevice::ReadOnly) && f.size() <= GroupVideo::kRelayBytes)
                *clip = GroupVideo::scrubbed(f.read(GroupVideo::kRelayBytes));
        }
        if (wantFull) {
            QDir().mkpath(QFileInfo(out).absolutePath());
            QFile::remove(out);
            if (QFile::copy(path, out) && QFile::setPermissions(out, QFile::ReadOwner | QFile::WriteOwner) && GroupVideo::scrubFile(out))
                *fullPath = out;
            else
                QFile::remove(out);
        }
    });
    t->setObjectName(QStringLiteral("huginn-video-copy"));
    const int ms = qBound(1, p.info.ms, MoreWire::kVideoMaxMs);
    QPointer<Chat> self(this);
    connect(t, &QThread::finished, this, [self, t, where, group, g, ch, full, relay, ms, clip, fullPath] {
        t->deleteLater();
        if (!self)
            return;
        const QByteArray clipBytes = *clip;
        const QString big = *fullPath;
        // Ultra privacy turned on meanwhile, or nothing could be cleaned: nothing goes.
        if (ultraOn() || !self->m_files) {
            if (!big.isEmpty())
                QFile::remove(big);
            Q_EMIT self->notice(ultraOn() ? QStringLiteral("The video didn't go: sending videos is off while Ultra privacy is on.")
                                          : QStringLiteral("The video didn't go. Try again."));
            return;
        }
        // No full copy: those it was for get the relay copy when there is one.
        const QStringList fullNow = big.isEmpty() ? QStringList() : full;
        QStringList relayNow = clipBytes.isEmpty() ? QStringList() : relay;
        if (big.isEmpty() && !clipBytes.isEmpty())
            relayNow += full;
        if (fullNow.isEmpty() && relayNow.isEmpty()) {
            Q_EMIT self->notice(QStringLiteral("The video didn't go: Huginn couldn't take its location and camera details out. It can go as a file."));
            return;
        }
        const QVariantMap extra{{QStringLiteral("video"), ms}};
        QPointer<Chat> me = self;
        if (group) {
            auto gid = std::make_shared<QString>();
            if (!relayNow.isEmpty())
                *gid = self->sendGroupBlob(g, ch, clipBytes, 0, extra, relayNow);
            for (const QString &k : fullNow) {
                std::function<void()> back;
                if (!clipBytes.isEmpty())
                    back = [me, g, ch, clipBytes, extra, k, gid] {
                        if (!me)
                            return;
                        if (gid->isEmpty())
                            *gid = me->sendGroupBlob(g, ch, clipBytes, 0, extra, {k});
                        else
                            me->addGroupBlobMember(g, *gid, k);
                    };
                self->m_files->sendVideo(k, big, g, ms, back);
            }
        } else if (!fullNow.isEmpty()) {
            std::function<void()> back;
            if (!clipBytes.isEmpty())
                back = [me, where, clipBytes, extra] {
                    if (me && me->hasM2(where))
                        me->sendBlob(where, clipBytes, 0, extra);
                };
            self->m_files->sendVideo(where, big, QString(), ms, back);
        } else if (self->hasM2(where)) {
            self->sendBlob(where, clipBytes, 0, extra);
        } else {
            Q_EMIT self->notice(self->nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to get videos."));
            return;
        }
        if (fullNow.isEmpty())
            Q_EMIT self->notice(QStringLiteral("Sending the video. Its pieces go a few a second, so it can take a few minutes."));
        else if (relayNow.isEmpty())
            Q_EMIT self->notice(QStringLiteral("Sending the video full size straight."));
        else
            Q_EMIT self->notice(QStringLiteral("Sending the video: full size straight to %1, a smaller copy to %2.").arg(fullNow.size()).arg(relayNow.size()));
    });
    t->start();
    return result(true, QStringLiteral("Getting the video ready."));
}
