// Cap v3 (shared/docs/protocol.md section 29): a video picked from this computer, to a group or one person. The same
// choices as Muninn's VideoSend.kt, except that Huginn converts nothing (29.6): a video goes full size only if it's
// already 1080p30 at most, and as the relay copy only if it already fits that.
#include "chat.h"
#include "filelink.h"
#include "groupvideo.h"
#include "groupwire.h"
#include "morewire.h"
#include "photos.h"
#include "ultraprivacy.h"
#include "vault.h"

#include <QFile>
#include <QFileInfo>
#include <QNetworkInformation>
#include <QPointer>
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

// What a picked video can do here, worked out once for planVideo and sendPickedVideo.
struct Picked {
    bool ok = false, asFile = false, fullOk = false, relayOk = false, inFull = false;
    QString why, length;
    GroupVideo::Info info;
    QHash<QString, QString> plan;
    QByteArray clipBytes;   // the video itself when it fits the relay copy
};
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
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        p.why = QStringLiteral("That video can't be read.");
        return p;
    }
    const QByteArray mp4 = f.readAll();
    if (!MoreWire::isVideo(mp4)) {
        p.asFile = true;
        p.why = QStringLiteral("Only MP4 videos can be sent as videos. It can go as a file.");
        return p;
    }
    p.info = GroupVideo::probe(mp4);
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
    p.fullOk = GroupVideo::fitsFull(p.info);
    p.relayOk = GroupVideo::fitsRelay(p.info);
    // A longer video: only in full, when direct (Huginn can't trim).
    p.inFull = inFull || p.length == QLatin1String("long");
    if (p.relayOk && !p.inFull)
        p.clipBytes = mp4;
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
    const int ms = qBound(1, p.info.ms, MoreWire::kVideoMaxMs);
    const QVariantMap extra{{QStringLiteral("video"), ms}};
    const QByteArray clipBytes = p.clipBytes;
    QPointer<Chat> self(this);
    QString g, ch;
    if (groupOf(where, &g, &ch)) {
        auto gid = std::make_shared<QString>();
        if (!relay.isEmpty() && !clipBytes.isEmpty())
            *gid = sendGroupBlob(g, ch, clipBytes, 0, extra, relay);
        for (const QString &k : full) {
            std::function<void()> back;
            if (!clipBytes.isEmpty())
                back = [self, g, ch, clipBytes, extra, k, gid] {
                    if (!self)
                        return;
                    if (gid->isEmpty())
                        *gid = self->sendGroupBlob(g, ch, clipBytes, 0, extra, {k});
                    else
                        self->addGroupBlobMember(g, *gid, k);
                };
            m_files->sendVideo(k, path, g, ms, back);
        }
    } else {
        if (!full.isEmpty()) {
            std::function<void()> back;
            if (!clipBytes.isEmpty())
                back = [self, where, clipBytes, extra] {
                    if (self && self->hasM2(where))
                        self->sendBlob(where, clipBytes, 0, extra);
                };
            m_files->sendVideo(where, path, QString(), ms, back);
        } else if (!clipBytes.isEmpty()) {
            if (!hasM2(where)) {
                probeCaps(where);
                return result(false, nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to get videos."));
            }
            sendBlob(where, clipBytes, 0, extra);
        }
    }
    if (full.isEmpty())
        return result(true, QStringLiteral("Sending. Its pieces go a few a second, so it can take a few minutes."));
    if (relay.isEmpty())
        return result(true, QStringLiteral("Sending full size straight."));
    return result(true, QStringLiteral("Sending: full size straight to %1, a smaller copy to %2.").arg(full.size()).arg(relay.size()));
}
