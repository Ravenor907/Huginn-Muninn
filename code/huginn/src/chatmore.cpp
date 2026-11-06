// Cap m2 (shared/docs/protocol.md, section 12), the Chat side, the same as Muninn's net/Chat.kt and Groups.kt:
// photo albums, video messages, polls and votes, link previews (made here only, when Settings › Privacy allows
// it) and the Shared page. What both apps must agree on lives in MoreWire.
#include "chat.h"
#include "settings.h"
#include "groupwire.h"
#include "linkpreview.h"
#include "morewire.h"
#include "photos.h"
#include "ultraprivacy.h"
#include "vault.h"

#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QFileDialog>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSet>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <algorithm>

namespace
{
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

// "g:<group>:<channel>" is a group channel; anything else a contact's key.
bool inGroup(const QString &where, QString *g, QString *ch)
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

QList<int> sortedChoices(const QVariantList &choices)
{
    QList<int> v;
    for (const QVariant &x : choices)
        v << x.toInt();
    std::sort(v.begin(), v.end());
    v.erase(std::unique(v.begin(), v.end()), v.end());
    return v;
}
}

QStringList Chat::pickPictures() const
{
    return QFileDialog::getOpenFileNames(nullptr, QStringLiteral("Send pictures (location and camera details are removed)"),
                                         QStandardPaths::writableLocation(QStandardPaths::PicturesLocation),
                                         QStringLiteral("Pictures (*.jpg *.jpeg *.png *.webp *.bmp *.gif *.heic)"));
}

QString Chat::sendPhotos(const QString &where, const QStringList &paths, bool hd)
{
    QList<QByteArray> jpegs;
    int bad = 0, gifs = 0;
    for (const QString &p : paths) {
        // Cap a1: a GIF goes on its own, animated (never in an album).
        if (Photos::isGifFile(p)) {
            if (sendPhoto(where, p, hd))
                ++gifs;
            else
                ++bad;
            continue;
        }
        const QByteArray j = Photos::prepare(p, hd);
        if (j.isEmpty())
            ++bad;
        else
            jpegs << j;
    }
    if (jpegs.isEmpty() && gifs == 0)
        return paths.size() == 1 ? QStringLiteral("That picture couldn't be opened.") : QStringLiteral("Those pictures couldn't be opened.");
    QString g, ch;
    const bool group = inGroup(where, &g, &ch);
    // Up to 10 in an album; each photo is still its own message with its own receipts.
    for (int at = 0; at < jpegs.size(); at += MoreWire::kAlbumMax) {
        const QList<QByteArray> part = jpegs.mid(at, MoreWire::kAlbumMax);
        const QString album = part.size() > 1 ? QUuid::createUuid().toString(QUuid::WithoutBraces) : QString();
        for (int i = 0; i < part.size(); ++i) {
            QVariantMap extra;
            if (!album.isEmpty())
                extra = {{QStringLiteral("al"), album}, {QStringLiteral("ai"), i}, {QStringLiteral("an"), int(part.size())}};
            if (group)
                sendGroupBlob(g, ch, part.at(i), 0, extra);
            else
                sendBlob(where, part.at(i), 0, extra);
        }
    }
    return bad == 0 ? QString() : bad == 1 ? QStringLiteral("One picture couldn't be opened; the rest are on their way.")
                                           : QStringLiteral("%1 pictures couldn't be opened; the rest are on their way.").arg(bad);
}

QString Chat::sendVideo(const QString &where, const QByteArray &mp4, int ms)
{
    if (!UltraPrivacy::videoSendAllowed())
        return UltraPrivacy::videoOffText();   // Ultra privacy: receiving still works
    if (!MoreWire::isVideo(mp4) || mp4.size() > Photos::kVideoSendBytes)
        return QStringLiteral("That video is too big to send. Videos can be up to 2 MB.");
    const int length = qBound(1, ms, MoreWire::kVideoMaxMs);
    const QVariantMap extra{{QStringLiteral("video"), length}};
    QString g, ch;
    if (inGroup(where, &g, &ch)) {
        const auto it = m_groups.constFind(g);
        if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
            return QStringLiteral("You're no longer in this group.");
        int older = 0;
        for (const QString &k : GroupWire::keysOf(it->state))
            if (k != myKey() && !hasM2(k)) {
                probeCaps(k);
                ++older;
            }
        if (sendGroupBlob(g, ch, mp4, 0, extra).isEmpty())
            return QStringLiteral("That video didn't send.");
        return older == 0 ? QString()
                          : QStringLiteral("%1 %2 a newer Huginn or Muninn to see video messages.").arg(older).arg(older == 1 ? QStringLiteral("person needs") : QStringLiteral("people need"));
    }
    // Only to an app that plays it: any other would try to read it as a photo and refuse it.
    if (!hasM2(where)) {
        probeCaps(where);
        return nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to get video messages.");
    }
    sendBlob(where, mp4, 0, extra);
    return QString();
}

QString Chat::sendPoll(const QString &where, const QString &question, const QStringList &answers, bool multi)
{
    QStringList a;
    for (const QString &x : answers)
        if (!x.trimmed().isEmpty())
            a << x.trimmed();
    const QString q = question.trimmed();
    const QJsonObject poll = MoreWire::pollJson(q, a, multi);
    const QString text = MoreWire::fallback(q, a);
    if (!MoreWire::ok(QStringLiteral("rich"), {{QStringLiteral("text"), text}, {QStringLiteral("poll"), poll}}))
        return QStringLiteral("A poll needs a question and 2 to %1 different answers.").arg(MoreWire::kOptionsMax);
    QString g, ch;
    if (inGroup(where, &g, &ch)) {
        const auto it = m_groups.constFind(g);
        if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
            return QStringLiteral("You're no longer in this group.");
        sendGroupWith(g, ch, text, QString(), poll);   // members whose apps lack m2 see its text
        return QString();
    }
    if (!hasM2(where)) {
        probeCaps(where);
        return nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to answer polls.");
    }
    sendPrivateWith(where, text, QString(), poll);
    return QString();
}

QString Chat::vote(const QString &where, const QString &id, const QVariantList &choices)
{
    const QList<int> v = sortedChoices(choices);
    const qint64 ts = now();
    QList<int> sent;
    bool done = false;
    // One vote per person: a single choice poll takes the first option only.
    auto apply = [&](QVariantMap &m) {
        if (!str(m, "unsent").isEmpty())
            return;
        const QVariantMap poll = m.value(QStringLiteral("poll")).toMap();
        if (poll.isEmpty())
            return;
        const QList<int> vv = poll.value(QStringLiteral("m")).toBool() ? v : v.mid(0, 1);
        QVariantMap all = m.value(QStringLiteral("votes")).toMap();
        if (!MoreWire::vote(all, int(poll.value(QStringLiteral("a")).toList().size()), myKey(), vv, ts))
            return;
        m.insert(QStringLiteral("votes"), all);
        sent = vv;
        done = true;
    };
    QString g, ch;
    if (inGroup(where, &g, &ch)) {
        const auto it = m_groups.constFind(g);
        if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
            return QStringLiteral("You're no longer in this group.");
        updateGroupMessage(g, id, apply);
        if (!done)
            return QString();
        QJsonArray a;
        for (int x : std::as_const(sent))
            a << x;
        const QString lid = GroupWire::newLogicalId();
        for (const QString &k : GroupWire::keysOf(m_groups.value(g).state))
            if (k != myKey() && hasM2(k))
                sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("vote")}, {QStringLiteral("id"), lid}, {QStringLiteral("msg"), id},
                                   {QStringLiteral("v"), a}, {QStringLiteral("ts"), ts}});
        return QString();
    }
    if (!hasM2(where)) {
        probeCaps(where);
        return nameOf(where) + QStringLiteral(" needs a newer Huginn or Muninn to see votes.");
    }
    update(where, id, apply);
    if (done)
        queueMx(where, MoreWire::voteMsg(id, sent, ts));
    return QString();
}

// A vote from them (room "mx"): checked, counted, and confirmed by its envelope id like a reaction.
int Chat::takeVote(const QString &fromKey, const QString &mailbox, const QString &sentId, const QJsonObject &p)
{
    if (!MoreWire::ok(QStringLiteral("mx"), p))
        return 400;
    commitPeer();
    if (!sentId.isEmpty())
        m_seen.add(fromKey, sentId, now());
    const QList<int> v = MoreWire::toInts(p.value(QStringLiteral("v")).toVariant());
    const qint64 ts = qint64(p.value(QStringLiteral("ts")).toDouble());
    update(fromKey, p.value(QStringLiteral("msg")).toString(), [&](QVariantMap &m) {
        const QVariantMap poll = m.value(QStringLiteral("poll")).toMap();
        if (poll.isEmpty() || !str(m, "unsent").isEmpty())
            return;
        QVariantMap all = m.value(QStringLiteral("votes")).toMap();
        if (MoreWire::vote(all, int(poll.value(QStringLiteral("a")).toList().size()), fromKey, poll.value(QStringLiteral("m")).toBool() ? v : v.mid(0, 1), ts))
            m.insert(QStringLiteral("votes"), all);
    });
    receipt(fromKey, {sentId}, QStringLiteral("delivered"), mailbox);
    return 200;
}

void Chat::takeGroupVote(const QString &g, const QString &from, const QJsonObject &p)
{
    const QList<int> v = MoreWire::toInts(p.value(QStringLiteral("v")).toVariant());
    const qint64 ts = qint64(p.value(QStringLiteral("ts")).toDouble());
    updateGroupMessage(g, p.value(QStringLiteral("msg")).toString(), [&](QVariantMap &m) {
        const QVariantMap poll = m.value(QStringLiteral("poll")).toMap();
        if (poll.isEmpty() || !str(m, "unsent").isEmpty())
            return;
        QVariantMap all = m.value(QStringLiteral("votes")).toMap();
        if (MoreWire::vote(all, int(poll.value(QStringLiteral("a")).toList().size()), from, poll.value(QStringLiteral("m")).toBool() ? v : v.mid(0, 1), ts))
            m.insert(QStringLiteral("votes"), all);
    });
}

// A link in a message to an m1 app gets its preview first. It's looked up once, before the message is
// sealed, so every try and route sends the same bytes.
void Chat::attemptWithPreview(const QString &key, const QString &id)
{
    QVariantMap m;
    for (const QVariant &v : m_private.value(key))
        if (str(v.toMap(), "id") == id)
            m = v.toMap();
    const QString link = linkPreviews() && !Settings::dataSaverOn() && UltraPrivacy::previewsAllowed() && !m.contains(QStringLiteral("poll")) && hasM1(key) ? MoreWire::firstLink(str(m, "text")) : QString();
    if (link.isEmpty()) {
        attempt(key, id);
        return;
    }
    m_tries[id].next = now() + 30000;   // the retry loop waits for the preview
    LinkPreview::fetch(link, this, [this, key, id](const QJsonObject &lp) {
        bool gone = true;
        for (const QVariant &v : m_private.value(key))
            if (str(v.toMap(), "id") == id)
                gone = !str(v.toMap(), "unsent").isEmpty() || str(v.toMap(), "state") == QLatin1String("unsending");
        if (gone)
            return;
        if (!lp.isEmpty())
            update(key, id, [&lp](QVariantMap &x) { x.insert(QStringLiteral("lp"), lp.toVariantMap()); });
        attempt(key, id);
    });
}

void Chat::queueGroupMessage(const QString &g, const QString &id, const QStringList &to, const QJsonObject &p)
{
    auto go = [this, g, id, to](const QJsonObject &body) {
        const QByteArray plain = compact(body);
        for (const QString &k : to)
            queueGroup(g, k, QStringLiteral("gmsg"), id, plain);
    };
    const QString link = linkPreviews() && !Settings::dataSaverOn() && UltraPrivacy::previewsAllowed() && !p.contains(QStringLiteral("poll")) && !to.isEmpty() ? MoreWire::firstLink(p.value(QStringLiteral("text")).toString()) : QString();
    if (link.isEmpty()) {
        go(p);
        return;
    }
    LinkPreview::fetch(link, this, [this, g, id, p, go](const QJsonObject &lp) {
        // Unsent while the preview was being made: it never goes out.
        bool here = false;
        for (const QVariant &v : m_groups.value(g).messages)
            if (str(v.toMap(), "id") == id)
                here = str(v.toMap(), "unsent").isEmpty();
        if (!here)
            return;
        QJsonObject body = p;
        if (!lp.isEmpty()) {
            body.insert(QStringLiteral("lp"), lp);
            updateGroupMessage(g, id, [&lp](QVariantMap &x) { x.insert(QStringLiteral("lp"), lp.toVariantMap()); });
        }
        go(body);
    });
}

// What the screens need: an album's photos together in its first one's place (in their order, as they
// arrive), a poll's counts and who voted for what, and a preview's site and picture.
QVariantList Chat::withM2(const QVariantList &list, const QString &g) const
{
    QHash<QString, QList<int>> albums;
    for (int i = 0; i < list.size(); ++i) {
        const QVariantMap m = list.at(i).toMap();
        const QString al = str(m, "al");
        if (!al.isEmpty() && !str(m, "photo").isEmpty() && str(m, "unsent").isEmpty())
            albums[(m.value(QStringLiteral("mine")).toBool() ? QStringLiteral("me") : str(m, "from")) + QLatin1Char('|') + al] << i;
    }
    QSet<int> hidden;
    QHash<int, QVariantList> grids;
    for (auto it = albums.cbegin(); it != albums.cend(); ++it) {
        if (it.value().size() < 2)
            continue;
        QList<int> l = it.value();
        const int first = l.first();
        std::stable_sort(l.begin(), l.end(), [&list](int a, int b) { return list.at(a).toMap().value(QStringLiteral("ai")).toInt() < list.at(b).toMap().value(QStringLiteral("ai")).toInt(); });
        QVariantList grid;
        for (int i : std::as_const(l)) {
            const QVariantMap m = list.at(i).toMap();
            grid << QVariantMap{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("photo"), str(m, "photo")}};
            if (i != first)
                hidden.insert(i);
        }
        grids.insert(first, grid);
    }
    QVariantList out;
    out.reserve(list.size() - hidden.size());
    for (int i = 0; i < list.size(); ++i) {
        if (hidden.contains(i))
            continue;
        QVariantMap m = list.at(i).toMap();
        if (grids.contains(i))
            m.insert(QStringLiteral("album"), grids.value(i));
        const QVariantMap poll = m.value(QStringLiteral("poll")).toMap();
        if (!poll.isEmpty()) {
            const QStringList a = poll.value(QStringLiteral("a")).toStringList();
            const QVariantMap votes = m.value(QStringLiteral("votes")).toMap();
            const QList<int> n = MoreWire::counts(votes, int(a.size()));
            const QList<int> mine = MoreWire::choices(votes, myKey());
            int total = 0;
            for (auto v = votes.cbegin(); v != votes.cend(); ++v)
                if (!MoreWire::toInts(v.value().toMap().value(QStringLiteral("v"))).isEmpty())
                    ++total;
            QVariantList options;
            for (int k = 0; k < a.size(); ++k) {
                QStringList who;
                for (auto v = votes.cbegin(); v != votes.cend(); ++v)
                    if (MoreWire::toInts(v.value().toMap().value(QStringLiteral("v"))).contains(k))
                        who << (v.key() == myKey() ? QStringLiteral("You") : g.isEmpty() ? nameOf(v.key()) : memberName(g, v.key()));
                options << QVariantMap{{QStringLiteral("text"), a.at(k)}, {QStringLiteral("n"), n.value(k)}, {QStringLiteral("mine"), mine.contains(k)},
                                       {QStringLiteral("who"), who.join(QStringLiteral(", "))}};
            }
            m.insert(QStringLiteral("pollView"), QVariantMap{{QStringLiteral("q"), poll.value(QStringLiteral("q"))}, {QStringLiteral("multi"), poll.value(QStringLiteral("m")).toBool()},
                                                             {QStringLiteral("total"), total}, {QStringLiteral("options"), options}});
        }
        const QVariantMap lp = m.value(QStringLiteral("lp")).toMap();
        if (!lp.isEmpty()) {
            m.insert(QStringLiteral("lpHost"), MoreWire::host(str(lp, "u")));
            if (!str(lp, "i").isEmpty())
                m.insert(QStringLiteral("lpThumb"), QStringLiteral("data:image/jpeg;base64,") + str(lp, "i"));
        }
        out << m;
    }
    return out;
}

QVariantMap Chat::shared(const QString &where) const
{
    QVariantList all;
    QString g, ch;
    const bool group = inGroup(where, &g, &ch);
    if (group) {
        for (const QVariant &v : m_groups.value(g).messages)
            if (str(v.toMap(), "ch") == ch)
                all << v;
    } else {
        all = m_private.value(where);
    }
    QVariantList media, links, voices;
    // here: its bytes are on this device (a tile without them says so, and can't be opened, saved or forwarded).
    const auto here = [this](const QString &id) { return QFileInfo::exists(m_vault->path(QStringLiteral("photos/") + id)); };
    for (int i = int(all.size()) - 1; i >= 0; --i) {
        const QVariantMap m = all.at(i).toMap();
        if (!str(m, "unsent").isEmpty() || m.value(QStringLiteral("system")).toBool())
            continue;
        const bool mine = m.value(QStringLiteral("mine")).toBool();
        const QString name = mine ? QStringLiteral("You") : group ? memberName(g, str(m, "from")) : nameOf(where);
        if (!str(m, "photo").isEmpty() && m.value(QStringLiteral("voice")).toInt() > 0) {
            voices << QVariantMap{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("photo"), str(m, "photo")}, {QStringLiteral("voice"), m.value(QStringLiteral("voice")).toInt()},
                                  {QStringLiteral("mine"), mine}, {QStringLiteral("name"), name}, {QStringLiteral("time"), m.value(QStringLiteral("time"))},
                                  {QStringLiteral("here"), here(str(m, "photo"))}};
            continue;
        }
        if (!str(m, "photo").isEmpty() && m.value(QStringLiteral("voice")).toInt() == 0) {
            media << QVariantMap{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("photo"), str(m, "photo")}, {QStringLiteral("video"), m.value(QStringLiteral("video")).toInt()},
                                 {QStringLiteral("mine"), mine}, {QStringLiteral("name"), name}, {QStringLiteral("time"), m.value(QStringLiteral("time"))},
                                 {QStringLiteral("gif"), m.value(QStringLiteral("gif")).toBool()}, {QStringLiteral("here"), here(str(m, "photo"))},
                                 {QStringLiteral("vb"), m.value(QStringLiteral("vb")).toBool()}};
            continue;
        }
        const QVariantMap lp = m.value(QStringLiteral("lp")).toMap();
        const QString url = lp.isEmpty() ? MoreWire::firstLink(str(m, "text")) : str(lp, "u");
        if (url.isEmpty())
            continue;
        links << QVariantMap{{QStringLiteral("id"), str(m, "id")}, {QStringLiteral("url"), url}, {QStringLiteral("title"), lp.isEmpty() ? MoreWire::host(url) : str(lp, "t")},
                             {QStringLiteral("description"), str(lp, "d")}, {QStringLiteral("host"), MoreWire::host(url)},
                             {QStringLiteral("thumb"), str(lp, "i").isEmpty() ? QString() : QStringLiteral("data:image/jpeg;base64,") + str(lp, "i")},
                             {QStringLiteral("name"), name}, {QStringLiteral("time"), m.value(QStringLiteral("time"))}};
    }
    return {{QStringLiteral("media"), media}, {QStringLiteral("links"), links}, {QStringLiteral("voices"), voices}};
}

// Media and files › Save: a photo or GIF goes to Pictures › Huginn (as Save to Pictures does); a video or voice message
// in Huginn in the folder Settings › Save files to names (Downloads unless changed), where received files go too. The
// path it was saved as, or empty when it isn't on this device or couldn't be written.
QString Chat::saveMedia(const QString &id)
{
    // A longer video (bigvideo.h): its blob is only the poster, so the video itself is written out piece by piece.
    const bool big = isBigVideo(id);
    QByteArray b = big ? QByteArray() : blob(id);
    if (b.isEmpty() && !big)
        return QString();
    const bool voice = !big && Photos::isAdts(b), video = big || (!voice && MoreWire::isVideo(b));
    QString dir;
    if (voice || video) {
        const QString downloads = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
        dir = Settings::saved(QStringLiteral("transferFolder"), downloads).toString();
        if (dir.isEmpty())
            dir = QDir::homePath();
        dir += QStringLiteral("/Huginn");
    } else {
        dir = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation) + QStringLiteral("/Huginn");
    }
    QDir().mkpath(dir);
    const QString ext = voice ? QStringLiteral("aac") : video ? QStringLiteral("mp4") : Photos::isGif(b) ? QStringLiteral("gif") : QStringLiteral("jpg");
    const QString stem = (voice ? QStringLiteral("Huginn voice-") : video ? QStringLiteral("Huginn video-") : QStringLiteral("Huginn-")) + QString::number(now());
    QString path = QDir(dir).filePath(stem + QLatin1Char('.') + ext);
    for (int n = 1; QFile::exists(path) && n < 1000; ++n)
        path = QDir(dir).filePath(stem + QStringLiteral(" (%1).").arg(n) + ext);
    if (big)
        return exportBig(id, path) ? path : QString();
    QFile f(path);
    const bool ok = f.open(QIODevice::WriteOnly) && f.write(b) == b.size();
    b.fill(0);
    return ok ? path : QString();
}

// Media and files › Forward: a photo, GIF, video or voice message kept here goes again as a new message, by the same
// paths as one sent from here, to a contact's key, a group ("g:<group>", its first channel) or "g:<group>:<channel>".
// voiceMs: a voice message's length. Empty when it went, else what to say.
QString Chat::forwardMedia(const QString &id, const QString &to, int voiceMs)
{
    // A longer video's blob is only its poster: never sent as if it were the video.
    if (isBigVideo(id))
        return QStringLiteral("Longer videos can't be forwarded. Save it, then send it as a file.");
    const QByteArray b = blob(id);
    if (b.isEmpty())
        return QStringLiteral("It isn't on this device, so it can't be forwarded.");
    QString where = to;
    if (where.startsWith(QLatin1String("g:")) && where.count(QLatin1Char(':')) == 1)
        where += QStringLiteral(":main");
    QString g, ch;
    const bool group = inGroup(where, &g, &ch);
    if (Photos::isAdts(b)) {
        sendVoice(where, b, voiceMs > 0 ? voiceMs : 1000);
        return QString();
    }
    if (MoreWire::isVideo(b)) {
        const int ms = MoreWire::mp4Ms(b);
        return sendVideo(where, b, ms > 0 ? ms : 1000);
    }
    if (Photos::isGif(b))
        return sendGifTo(where, b) ? QString() : QStringLiteral("That didn't send.");
    if (group)
        return sendGroupBlob(g, ch, b, 0).isEmpty() ? QStringLiteral("That didn't send.") : QString();
    sendBlob(where, b, 0);
    return QString();
}

QString Chat::linkHost(const QString &url) const { return MoreWire::host(url); }

// Only after the person said yes to "Open example.com?": nothing here opens a link by itself.
bool Chat::openLink(const QString &url) const
{
    if (!url.startsWith(QLatin1String("https://")) && !url.startsWith(QLatin1String("http://")))
        return false;
    return QDesktopServices::openUrl(QUrl(url));
}

// Labs › Things to test: the list both apps ship (code/shared/beta-tests.json).
QVariantMap Chat::betaTests() const
{
    QFile f(QStringLiteral(":/huginn/beta-tests.json"));
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const QJsonDocument d = QJsonDocument::fromJson(f.readAll());
    if (!d.isObject() || !d.object().value(QStringLiteral("groups")).isArray())
        return {};
    return d.object().toVariantMap();
}

// The ticked checks, kept in this device's settings with the beta they were for: another beta starts with none.
QStringList Chat::betaTicks()
{
    const QString beta = betaTests().value(QStringLiteral("beta")).toString();
    if (m_settings.value(QStringLiteral("labs/betaTestsFor")).toString() != beta) {
        m_settings.setValue(QStringLiteral("labs/betaTestsFor"), beta);
        m_settings.remove(QStringLiteral("labs/betaTicks"));
    }
    return m_settings.value(QStringLiteral("labs/betaTicks")).toStringList();
}

void Chat::setBetaTick(const QString &id, bool on)
{
    if (id.isEmpty() || id.size() > 32)
        return;
    QStringList ticks = betaTicks();
    if (ticks.contains(id) == on)
        return;
    if (on)
        ticks.append(id);
    else
        ticks.removeAll(id);
    m_settings.setValue(QStringLiteral("labs/betaTicks"), ticks);
}
