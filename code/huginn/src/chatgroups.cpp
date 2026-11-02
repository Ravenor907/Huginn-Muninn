// Group chats (shared/docs/protocol.md, section 3), the Chat side: keeping states, fan-out to every
// member over their own session, receipts, unsend, photos and voice, invites and group codes.
// What is deterministic (canonical JSON, signatures, acceptance) lives in GroupWire.
#include "chat.h"
#include "busy.h"
#include "profile.h"
#include "richwire.h"
#include "morewire.h"
#include "ble.h"
#include "groupwire.h"
#include "sharing.h"
#include "msglimits.h"
#include "moderation.h"
#include "localsend.h"
#include "nostr.h"
#include "photos.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>
#include <QTimer>
#include <QUuid>
#include <algorithm>
#include <memory>

namespace
{
const qint64 kDay = 86400000;
const qint64 kRetry[] = {5000, 10000, 20000, 60000, 300000};
const int kMaxGroupMessages = 2000;

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
qint64 retryDelay(int count) { return kRetry[qBound(0, count - 1, 4)]; }
qint64 ver(const QJsonObject &s) { return s.value(QStringLiteral("v")).toInteger(); }
QString uuid() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
QJsonObject parse(const QByteArray &b)
{
    const QJsonDocument d = QJsonDocument::fromJson(b);
    return d.isObject() ? d.object() : QJsonObject();
}
int rank(const QString &s)
{
    if (s == QLatin1String("sending")) return 1;
    if (s == QLatin1String("sent")) return 2;
    if (s == QLatin1String("delivered")) return 3;
    if (s == QLatin1String("read")) return 4;
    return 0;
}
QString initialOf(const QString &name)
{
    const QString t = name.trimmed();
    if (t.isEmpty())
        return QStringLiteral("?");
    if (t.at(0).isHighSurrogate() && t.size() > 1)
        return t.left(2);
    return t.left(1).toUpper();
}
QStringList without(QStringList l, const QString &k)
{
    l.removeAll(k);
    return l;
}
// Cap g3 (protocol 28): a state with an about text or an admin-only channel, which apps without g3 refuse.
bool usesG3(const QJsonObject &s)
{
    if (s.contains(QStringLiteral("about")))
        return true;
    for (const QJsonValue &c : s.value(QStringLiteral("channels")).toArray())
        if (c.toObject().contains(QStringLiteral("ao")))
            return true;
    return false;
}
// Cap g3 (28.2): channel ch of state s takes posts only from admins, and key isn't one.
bool adminsOnlyFor(const QJsonObject &s, const QString &ch, const QString &key)
{
    return GroupWire::channel(s, ch).value(QStringLiteral("ao")).toBool() && !GroupWire::isAdmin(s, key);
}
}

// ------------------------------------------------------------------ lists for the screen

namespace
{
// How many of a channel's unread messages @mention me: the newest `unread` from others.
int unreadMentions(const QVariantList &messages, const QString &ch, int unread, const QString &me)
{
    int seen = 0, mentions = 0;
    for (auto it = messages.crbegin(); it != messages.crend() && seen < unread; ++it) {
        const QVariantMap m = it->toMap();
        if (m.value(QStringLiteral("ch")).toString() != ch || m.value(QStringLiteral("mine")).toBool() || m.value(QStringLiteral("system")).toBool())
            continue;
        ++seen;
        if (RichWire::mentions(m.value(QStringLiteral("text")).toString(), me))
            ++mentions;
    }
    return mentions;
}
}

QVariantList Chat::groups() const
{
    Busy::Scope busy("chat lists");
    QList<QPair<qint64, QVariantMap>> rows;
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it) {
        const Group &gr = it.value();
        if (gr.state.isEmpty())
            continue;
        int unread = 0, mentions = 0;
        for (auto u = gr.unread.cbegin(); u != gr.unread.cend(); ++u) {
            unread += u.value();
            if (u.value() > 0)
                mentions += unreadMentions(gr.messages, u.key(), u.value(), username());
        }
        const QString name = gr.state.value(QStringLiteral("name")).toString();
        rows << qMakePair(gr.since, QVariantMap{{QStringLiteral("id"), it.key()},
                                                {QStringLiteral("name"), name},
                                                {QStringLiteral("letter"), initialOf(name)},
                                                {QStringLiteral("unread"), unread},
                                                {QStringLiteral("mentions"), mentions},
                                                {QStringLiteral("readOnly"), gr.removed || gr.left},
                                                {QStringLiteral("count"), int(GroupWire::keysOf(gr.state).size())}});
    }
    std::stable_sort(rows.begin(), rows.end(), [](const auto &a, const auto &b) { return a.first < b.first; });
    QVariantList out;
    for (const auto &r : rows)
        out << r.second;
    return out;
}

QVariantList Chat::groupInvites() const
{
    QVariantList out;
    for (const QVariantMap &i : m_ginvites)
        out << QVariantMap{{QStringLiteral("g"), i.value(QStringLiteral("g"))}, {QStringLiteral("name"), i.value(QStringLiteral("name"))},
                           {QStringLiteral("from"), i.value(QStringLiteral("from"))}, {QStringLiteral("fromName"), nameOf(str(i, "from"))},
                           {QStringLiteral("count"), i.value(QStringLiteral("count"))}, {QStringLiteral("waiting"), false}};
    for (const QVariantMap &j : m_joining)
        out << QVariantMap{{QStringLiteral("g"), j.value(QStringLiteral("g"))}, {QStringLiteral("name"), j.value(QStringLiteral("name"))},
                           {QStringLiteral("from"), j.value(QStringLiteral("admin"))}, {QStringLiteral("fromName"), j.value(QStringLiteral("adminName"))},
                           {QStringLiteral("count"), 0}, {QStringLiteral("waiting"), true}};
    return out;
}

QString Chat::groupName(const QString &g) const { return m_groups.value(g).state.value(QStringLiteral("name")).toString(); }

QString Chat::memberName(const QString &g, const QString &key) const
{
    if (key == myKey())
        return username();
    if (isContact(key))
        return nameOf(key);
    const QString n = GroupWire::memberEntry(m_groups.value(g).state, key).value(QStringLiteral("n")).toString();
    if (!n.isEmpty())
        return n;
    const QString p = str(m_gpeers.value(key), "name");
    return p.isEmpty() ? QStringLiteral("Someone") : p;
}

QVariantMap Chat::groupInfo(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return {};
    const QJsonObject &s = it->state;
    const QString me = myKey(), owner = s.value(QStringLiteral("owner")).toString();
    const QStringList admins = GroupWire::adminsOf(s);
    QVariantList members;
    for (const QString &k : GroupWire::keysOf(s))
        members << QVariantMap{{QStringLiteral("key"), k},
                               {QStringLiteral("name"), memberName(g, k)},
                               {QStringLiteral("admin"), admins.contains(k)},
                               {QStringLiteral("owner"), k == owner},
                               {QStringLiteral("me"), k == me},
                               {QStringLiteral("contact"), isContact(k)},
                               {QStringLiteral("checked"), k != me && isVerified(k)},   // safety code matched (Family)
                               {QStringLiteral("route"), k == me ? QString() : route(k)}};
    // The owner first, then admins, then everyone else, each by name.
    std::stable_sort(members.begin(), members.end(), [](const QVariant &a, const QVariant &b) {
        const QVariantMap x = a.toMap(), y = b.toMap();
        const int rx = x.value(QStringLiteral("owner")).toBool() ? 0 : x.value(QStringLiteral("admin")).toBool() ? 1 : 2;
        const int ry = y.value(QStringLiteral("owner")).toBool() ? 0 : y.value(QStringLiteral("admin")).toBool() ? 1 : 2;
        if (rx != ry)
            return rx < ry;
        return str(x, "name").toLower() < str(y, "name").toLower();
    });
    QVariantList channels;
    const QJsonArray ch = s.value(QStringLiteral("channels")).toArray();
    for (const QJsonValue &v : ch) {
        const QJsonObject c = v.toObject();
        const QString id = c.value(QStringLiteral("id")).toString();
        channels << QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("name"), c.value(QStringLiteral("n")).toString()},
                                {QStringLiteral("type"), c.value(QStringLiteral("t")).toString()}, {QStringLiteral("unread"), it->unread.value(id)},
                                {QStringLiteral("adminsOnly"), c.value(QStringLiteral("ao")).toBool()},   // cap g3 (28.2)
                                {QStringLiteral("mentions"), it->unread.value(id) > 0 ? unreadMentions(it->messages, id, it->unread.value(id), username()) : 0}};
    }
    return {{QStringLiteral("g"), g},
            {QStringLiteral("name"), s.value(QStringLiteral("name")).toString()},
            {QStringLiteral("v"), ver(s)},
            {QStringLiteral("amAdmin"), admins.contains(me)},
            {QStringLiteral("amOwner"), owner == me},
            {QStringLiteral("readOnly"), it->removed || it->left},
            {QStringLiteral("removed"), it->removed},
            {QStringLiteral("left"), it->left},
            {QStringLiteral("count"), int(members.size())},
            {QStringLiteral("type"), Limits::groupType(s)},   // "folk" or "family" (16.2)
            {QStringLiteral("about"), s.value(QStringLiteral("about")).toString()},   // cap g3 (28.1)
            {QStringLiteral("members"), members},
            {QStringLiteral("channels"), channels}};
}

QVariantList Chat::groupMessages(const QString &g, const QString &ch) const
{
    Busy::Scope busy("chat lists");
    QVariantList out;
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend())
        return out;
    const M1Index idx = m1Index(it->messages);
    for (const QVariant &v : it->messages) {
        if (str(v.toMap(), "ch") != ch)
            continue;
        // Cap m1: the quoted message, reactions, edits and @mentions of me. No read marks in groups on Huginn.
        QVariantMap m = withM1(v.toMap(), it->messages, idx, QString());
        m.insert(QStringLiteral("readCount"), 0);
        if (str(m, "state") == QLatin1String("read"))
            m.insert(QStringLiteral("state"), QStringLiteral("delivered"));
        m.insert(QStringLiteral("mention"), !m.value(QStringLiteral("mine")).toBool() && RichWire::mentions(str(m, "text"), username()));
        if (m.value(QStringLiteral("mine")).toBool()) {
            const int to = int(m.value(QStringLiteral("to")).toStringList().size());
            const int dlv = int(m.value(QStringLiteral("dlv")).toStringList().size());
            QString receipt;
            if (to > 0 && str(m, "unsent").isEmpty()) {
                if (dlv > 0)
                    receipt = dlv == to ? QStringLiteral("Delivered to everyone") : QStringLiteral("Delivered to %1 of %2").arg(dlv).arg(to);
            }
            m.insert(QStringLiteral("receipt"), receipt);
            m.insert(QStringLiteral("name"), username());
        } else if (!m.value(QStringLiteral("system")).toBool()) {
            const QString shown = memberName(g, str(m, "from"));
            m.insert(QStringLiteral("name"), shown);
            // Muted for me with Hide messages: folded as "Muted, tap to show" (cap g2's menus, only here).
            if (m_mod && m_mod->foldMessages(str(m, "from")))
                m.insert(QStringLiteral("folded"), true);
            if (!str(m, "unsent").isEmpty() && !m.value(QStringLiteral("removed")).toBool())
                m.insert(QStringLiteral("unsent"), shown);
        }
        out << m;
    }
    return withM2(out, g);   // cap m2: albums together, polls counted, previews ready to show
}

// ------------------------------------------------------------------ making and changing groups

QString Chat::createGroup(const QString &name, const QString &kind)
{
    m_saveFailed = false;
    if (!m_ready)
        return QString();
    const QString n = GroupWire::cleanName(name, GroupWire::kMaxName);
    if (n.trimmed().isEmpty())
        return QString();
    const QString g = GroupWire::newGroupId(), me = myKey();
    QJsonObject s{{QStringLiteral("g"), g}, {QStringLiteral("v"), 1}, {QStringLiteral("owner"), me}, {QStringLiteral("by"), me},
                  {QStringLiteral("name"), n}, {QStringLiteral("ts"), now()},
                  {QStringLiteral("channels"), QJsonArray{QJsonObject{{QStringLiteral("id"), QStringLiteral("main")}, {QStringLiteral("n"), QStringLiteral("general")},
                                                                      {QStringLiteral("t"), QStringLiteral("text")}},
                                                          // A voice room from the start, as Muninn makes it.
                                                          QJsonObject{{QStringLiteral("id"), GroupWire::newChannelId()}, {QStringLiteral("n"), QStringLiteral("Voice")},
                                                                      {QStringLiteral("t"), QStringLiteral("voice")}}}}};
    s = GroupWire::withAdmins(s, {me});
    s = GroupWire::withMembers(s, {GroupWire::member(me, username(), mailbox())});
    // Cap d1 (16.2): Folk (no "kind", members can invite) or Family ("kind":"family", only admins add people).
    s = Limits::withType(s, kind == QLatin1String(Limits::kFamily) ? QString::fromLatin1(Limits::kFamily) : QString::fromLatin1(Limits::kFolk),
                         Limits::kDefault, true);
    bool ok = false;
    s = GroupWire::sign(m_priv, QLatin1String(GroupWire::kDomain), s, &ok);
    if (!ok || !GroupWire::checkState(s).isEmpty())
        return QString();
    Group gr;
    gr.state = s;
    gr.history << s;
    gr.since = now();
    m_groups.insert(g, gr);
    if (!saveGroups()) {
        // Not kept: not shown either ("Couldn't save. Try again." on screen).
        m_groups.remove(g);
        m_groupsDirty = false;
        m_saveFailed = true;
        return QString();
    }
    groupNote(g, QStringLiteral("You made %1. Invite people from the group's menu (its three dots). Talk in the Voice room; add channels and voice rooms from Channels.").arg(n));
    Q_EMIT changed();
    return g;
}

QJsonObject Chat::nextState(const QString &g) const
{
    QJsonObject s = m_groups.value(g).state;
    s.remove(QStringLiteral("sig"));
    s.insert(QStringLiteral("v"), ver(s) + 1);
    s.insert(QStringLiteral("by"), myKey());
    s.insert(QStringLiteral("ts"), now());
    return s;
}

// Every change is a whole new state, one version up, signed here and sent to everyone in the old
// and the new state (so someone removed hears it too).
QString Chat::commit(const QString &g, QJsonObject next, const QJsonObject &op, const QStringList &alsoTo)
{
    if (!m_groups.contains(g))
        return QStringLiteral("That group isn't here anymore.");
    const QJsonObject old = m_groups.value(g).state;
    bool ok = false;
    next = GroupWire::sign(m_priv, QLatin1String(GroupWire::kDomain), next, &ok);
    if (!ok)
        return QStringLiteral("That change couldn't be signed.");
    const GroupWire::Verdict v = GroupWire::accept(&old, next);
    if (!v.ok)
        return GroupWire::checkState(next).contains(QLatin1String("too big")) ? QStringLiteral("The group is too big for that change.")
                                                                               : QStringLiteral("That change isn't possible.");
    Group &gr = m_groups[g];
    gr.state = next;
    gr.history << next;
    while (gr.history.size() > GroupWire::kHistory)
        gr.history.removeFirst();
    QJsonObject done = op;
    done.insert(QStringLiteral("v"), ver(next));
    gr.lastOp = done;
    refreshGroupPeers();   // someone just added is reachable from the first try
    QStringList to = GroupWire::keysOf(old) + GroupWire::keysOf(next) + alsoTo;
    to.removeDuplicates();
    to.removeAll(myKey());
    const QString hint = op.value(QStringLiteral("op")).toString();
    for (const QString &k : std::as_const(to))
        sendControl(g, k, QJsonObject{{QStringLiteral("t"), QStringLiteral("state")}, {QStringLiteral("op"), hint}, {QStringLiteral("state"), next}});
    stateAccepted(g, old, false);
    return QString();
}

QString Chat::change(const QString &g, const QJsonObject &op)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return QStringLiteral("That group isn't here anymore.");
    if (it->removed || it->left)
        return QStringLiteral("You're no longer in this group.");
    const QJsonObject cur = it->state;
    const QString me = myKey(), owner = cur.value(QStringLiteral("owner")).toString();
    if (!GroupWire::isAdmin(cur, me))
        return QStringLiteral("Only the group's admins can do that.");
    QJsonObject next = nextState(g);
    const QString what = op.value(QStringLiteral("op")).toString();
    const QString k = op.value(QStringLiteral("k")).toString();
    QJsonArray channels = cur.value(QStringLiteral("channels")).toArray();
    auto channelAt = [&channels](const QString &id) {
        for (int i = 0; i < int(channels.size()); ++i)
            if (channels.at(i).toObject().value(QStringLiteral("id")).toString() == id)
                return i;
        return -1;
    };
    QList<QJsonObject> members;
    for (const QJsonValue &v : cur.value(QStringLiteral("members")).toArray())
        members << v.toObject();
    if (what == QLatin1String("rename")) {
        const QString n = GroupWire::cleanName(op.value(QStringLiteral("name")).toString(), GroupWire::kMaxName);
        if (n.trimmed().isEmpty())
            return QStringLiteral("Give the group a name.");
        if (n == cur.value(QStringLiteral("name")).toString())
            return QString();
        next.insert(QStringLiteral("name"), n);
    } else if (what == QLatin1String("addch")) {
        if (channels.size() >= GroupWire::kMaxChannels)
            return QStringLiteral("A group has room for 20 channels.");
        const QString n = GroupWire::cleanName(op.value(QStringLiteral("n")).toString(), GroupWire::kMaxChannelName);
        const QString id = op.value(QStringLiteral("id")).toString();
        if (n.trimmed().isEmpty())
            return QStringLiteral("Give the channel a name.");
        if (channelAt(id) >= 0)
            return QString();
        channels.append(QJsonObject{{QStringLiteral("id"), id}, {QStringLiteral("n"), n}, {QStringLiteral("t"), op.value(QStringLiteral("t")).toString()}});
        next.insert(QStringLiteral("channels"), channels);
    } else if (what == QLatin1String("rnch")) {
        const int i = channelAt(op.value(QStringLiteral("id")).toString());
        const QString n = GroupWire::cleanName(op.value(QStringLiteral("n")).toString(), GroupWire::kMaxChannelName);
        if (i < 0)
            return QStringLiteral("That channel isn't there anymore.");
        if (n.trimmed().isEmpty())
            return QStringLiteral("Give the channel a name.");
        QJsonObject c = channels.at(i).toObject();
        c.insert(QStringLiteral("n"), n);
        channels.replace(i, c);
        next.insert(QStringLiteral("channels"), channels);
    } else if (what == QLatin1String("rmch")) {
        const QString id = op.value(QStringLiteral("id")).toString();
        if (id == QLatin1String("main"))
            return QStringLiteral("The first channel always stays. You can rename it.");
        const int i = channelAt(id);
        if (i < 0)
            return QString();
        channels.removeAt(i);
        next.insert(QStringLiteral("channels"), channels);
    } else if (what == QLatin1String("about") || what == QLatin1String("ao")) {
        // Cap g3 (28.1, 28.2): the about text, or a text channel's "Only admins post".
        if (what == QLatin1String("about")) {
            const QString t = GroupWire::cleanName(op.value(QStringLiteral("about")).toString(), GroupWire::kMaxAbout).trimmed();
            if (t == cur.value(QStringLiteral("about")).toString())
                return QString();
            if (t.isEmpty())
                next.remove(QStringLiteral("about"));
            else
                next.insert(QStringLiteral("about"), t);
        } else {
            const int i = channelAt(op.value(QStringLiteral("id")).toString());
            if (i < 0)
                return QStringLiteral("That channel isn't there anymore.");
            QJsonObject c = channels.at(i).toObject();
            const bool on = op.value(QStringLiteral("on")).toBool();
            if (on && (c.value(QStringLiteral("id")).toString() == QLatin1String("main") || c.value(QStringLiteral("t")).toString() != QLatin1String("text")))
                return QStringLiteral("Only a text channel other than the first can be admin-only.");
            if (on == c.value(QStringLiteral("ao")).toBool())
                return QString();
            if (on)
                c.insert(QStringLiteral("ao"), true);
            else
                c.remove(QStringLiteral("ao"));
            channels.replace(i, c);
            next.insert(QStringLiteral("channels"), channels);
        }
        if (usesG3(next)) {
            QStringList old;
            for (const QJsonObject &m : std::as_const(members))
                if (m.value(QStringLiteral("k")).toString() != me && !hasCap(m.value(QStringLiteral("k")).toString(), QLatin1String(GroupWire::kAboutCap)))
                    old << m.value(QStringLiteral("n")).toString();
            if (!old.isEmpty())
                return QStringLiteral("Everyone needs an update first. Still waiting for: %1.").arg(old.join(QStringLiteral(", ")));
        }
    } else if (what == QLatin1String("admin")) {
        if (!GroupWire::isMember(cur, k))
            return QStringLiteral("They aren't in the group anymore.");
        next = GroupWire::withAdmins(next, GroupWire::adminsOf(cur) << k);
    } else if (what == QLatin1String("unadmin")) {
        if (me != owner)
            return QStringLiteral("Only the owner can take admin away.");
        if (k == owner)
            return QStringLiteral("The owner is always an admin.");
        next = GroupWire::withAdmins(next, without(GroupWire::adminsOf(cur), k));
    } else if (what == QLatin1String("remove") || what == QLatin1String("leave")) {
        if (!GroupWire::isMember(cur, k))
            return QString();
        if (k == owner)
            return QStringLiteral("The owner can't be removed. They can hand the group to someone else first.");
        if (what == QLatin1String("remove") && GroupWire::isAdmin(cur, k) && me != owner && k != me)
            return QStringLiteral("Only the owner can remove an admin.");
        QList<QJsonObject> kept;
        for (const QJsonObject &m : std::as_const(members))
            if (m.value(QStringLiteral("k")).toString() != k)
                kept << m;
        next = GroupWire::withMembers(next, kept);
        next = GroupWire::withAdmins(next, without(GroupWire::adminsOf(cur), k));
    } else if (what == QLatin1String("owner")) {
        if (me != owner)
            return QStringLiteral("Only the owner can hand the group over.");
        if (!GroupWire::isMember(cur, k))
            return QStringLiteral("They aren't in the group anymore.");
        next.insert(QStringLiteral("owner"), k);
        next = GroupWire::withAdmins(next, GroupWire::adminsOf(cur) << k);
    } else if (what == QLatin1String("limit")) {
        // Cap d1 (section 16.2): the group's type, Folk or Family, and how many days messages stay, the same for everyone.
        const QString type = op.value(QStringLiteral("kind")).toString() == QLatin1String(Limits::kFamily) ? QString::fromLatin1(Limits::kFamily)
                                                                                                           : QString::fromLatin1(Limits::kFolk);
        const QString why = limitBlocked(g, type == QLatin1String(Limits::kFamily) ? type : QString());
        if (!why.isEmpty())
            return why;
        // Folk: members can invite, when every other member's app takes it (17.1).
        bool miOk = true;
        for (const QJsonObject &m : std::as_const(members))
            if (m.value(QStringLiteral("k")).toString() != me && !hasJ1(m.value(QStringLiteral("k")).toString()))
                miOk = false;
        const bool wasFolk = Limits::groupType(cur) == QLatin1String(Limits::kFolk);
        const QJsonObject want = Limits::withType(next, type, op.value(QStringLiteral("days")).toInt(), miOk && !(wasFolk && type == QLatin1String(Limits::kFolk)));
        if (want.value(QStringLiteral("kind")) == cur.value(QStringLiteral("kind")) && want.value(QStringLiteral("days")) == cur.value(QStringLiteral("days"))
            && want.value(QStringLiteral("mi")) == cur.value(QStringLiteral("mi")))
            return QString();
        next = want;
    } else if (what == QLatin1String("invites")) {
        // Cap j1 (17.1): members may make invite links too. An app without j1 refuses a state with "mi", so everyone needs j1 first.
        const bool on = op.value(QStringLiteral("on")).toBool();
        if (on == cur.value(QStringLiteral("mi")).toBool())
            return QString();
        if (on && !Limits::linksAllowed(cur))
            return QStringLiteral("In a Family group only admins add people.");
        if (on) {
            QStringList old;
            for (const QJsonObject &m : std::as_const(members))
                if (m.value(QStringLiteral("k")).toString() != me && !hasJ1(m.value(QStringLiteral("k")).toString()))
                    old << m.value(QStringLiteral("n")).toString();
            if (!old.isEmpty())
                return QStringLiteral("Everyone needs an update first. Still waiting for: %1.").arg(old.join(QStringLiteral(", ")));
            next.insert(QStringLiteral("mi"), true);
        } else {
            next.remove(QStringLiteral("mi"));
        }
    } else if (what == QLatin1String("add")) {
        if (GroupWire::isMember(cur, k))
            return QString();
        if (cur.value(QStringLiteral("mi")).toBool() && !hasJ1(k))
            return QStringLiteral("They need an update to join, because members can invite in this group.");
        // Cap f2 (27.3): an app without f2 refuses a state with file rules.
        if (Sharing::hasRules(cur) && !hasCap(k, QLatin1String(Sharing::kCap)))
            return QStringLiteral("They need an update to join, because this group has file rules.");
        // Cap g3 (28.4): an app without g3 refuses a state with "about" or "ao".
        if (usesG3(cur) && !hasCap(k, QLatin1String(GroupWire::kAboutCap)))
            return QStringLiteral("They need an update to join, because this group has an about text or admin-only channels.");
        // An app without d1 refuses a Family or Work group's state.
        if (cur.contains(QStringLiteral("kind")) && !hasD1(k))
            return QStringLiteral("They need an update to be in a %1 group.").arg(Limits::kindName(cur.value(QStringLiteral("kind")).toString()));
        if (members.size() >= GroupWire::kMaxMembers)
            return QStringLiteral("This group is full: 50 people at most.");
        members << GroupWire::member(k, op.value(QStringLiteral("n")).toString(), op.value(QStringLiteral("p")).toString());
        next = GroupWire::withMembers(next, members);
    } else {
        return QStringLiteral("Huginn can't do that.");
    }
    return commit(g, next, op);
}

QString Chat::setGroupAbout(const QString &g, const QString &text)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("about")}, {QStringLiteral("about"), text}});
}

QString Chat::setChannelAdminsOnly(const QString &g, const QString &ch, bool on)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("ao")}, {QStringLiteral("id"), ch}, {QStringLiteral("on"), on}});
}

int Chat::reportToAdmins(const QString &g, const QString &msgId)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left || !GroupWire::isLogicalId(msgId))
        return 0;
    QVariantMap m;
    for (const QVariant &v : it->messages)
        if (str(v.toMap(), "id") == msgId)
            m = v.toMap();
    const QString author = str(m, "from");
    if (m.isEmpty() || author.isEmpty() || m.value(QStringLiteral("mine")).toBool())
        return 0;
    const QString text = GroupWire::clip(str(m, "text"), 500), me = myKey();
    int sent = 0;
    for (const QString &a : GroupWire::adminsOf(it->state)) {
        if (a == me || a == author)
            continue;
        // Cap g3 (28.3): through the group, contact or not; an admin's older app gets it in a private message.
        if (hasCap(a, QLatin1String(GroupWire::kAboutCap)))
            sendControl(g, a, {{QStringLiteral("t"), QStringLiteral("report")}, {QStringLiteral("msg"), msgId}, {QStringLiteral("k"), author},
                               {QStringLiteral("text"), text}});
        else if (isContact(a))
            sendPrivate(a, QStringLiteral("Report from %1: %2 wrote: %3")
                               .arg(groupName(g), memberName(g, author), text.isEmpty() ? QStringLiteral("(a photo or file)") : text));
        else
            continue;
        ++sent;
    }
    return sent;
}

QString Chat::renameGroup(const QString &g, const QString &name) { return change(g, {{QStringLiteral("op"), QStringLiteral("rename")}, {QStringLiteral("name"), name}}); }

QString Chat::addChannel(const QString &g, const QString &name, bool voice)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("addch")}, {QStringLiteral("id"), GroupWire::newChannelId()}, {QStringLiteral("n"), name},
                      {QStringLiteral("t"), voice ? QStringLiteral("voice") : QStringLiteral("text")}});
}

QString Chat::renameChannel(const QString &g, const QString &ch, const QString &name)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("rnch")}, {QStringLiteral("id"), ch}, {QStringLiteral("n"), name}});
}

QString Chat::removeChannel(const QString &g, const QString &ch) { return change(g, {{QStringLiteral("op"), QStringLiteral("rmch")}, {QStringLiteral("id"), ch}}); }

QString Chat::setAdmin(const QString &g, const QString &key, bool on)
{
    return change(g, {{QStringLiteral("op"), on ? QStringLiteral("admin") : QStringLiteral("unadmin")}, {QStringLiteral("k"), key}});
}

QString Chat::removeMember(const QString &g, const QString &key) { return change(g, {{QStringLiteral("op"), QStringLiteral("remove")}, {QStringLiteral("k"), key}}); }
QString Chat::handOver(const QString &g, const QString &key) { return change(g, {{QStringLiteral("op"), QStringLiteral("owner")}, {QStringLiteral("k"), key}}); }

QString Chat::leaveGroup(const QString &g)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return QString();
    if (it->removed || it->left) {
        forgetGroup(g);
        return QString();
    }
    const QJsonObject s = it->state;
    const QString me = myKey();
    const QStringList others = without(GroupWire::keysOf(s), me);
    if (others.isEmpty()) {
        forgetGroup(g);
        return QString();
    }
    if (s.value(QStringLiteral("owner")).toString() == me)
        return QStringLiteral("You own this group. Hand it to someone first: Members, then Make owner.");
    for (const QString &k : others)
        sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("leave")}});
    // An admin can sign the state without themself straight away.
    if (GroupWire::isAdmin(s, me))
        change(g, {{QStringLiteral("op"), QStringLiteral("leave")}, {QStringLiteral("k"), me}});
    Group &gr = m_groups[g];
    gr.left = true;
    gr.removed = false;
    groupNote(g, QStringLiteral("You left %1.").arg(groupName(g)));
    saveGroups();
    Q_EMIT changed();
    return QString();
}

void Chat::forgetGroup(const QString &g)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend())
        return;
    for (const QVariant &v : it->messages) {
        const QVariantMap m = v.toMap();
        if (!str(m, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        m_gidx.remove(str(m, "id"));
    }
    m_groups.remove(g);
    if (m_mod)
        m_mod->forgetGroup(g);
    for (const QString &id : m_gout.keys())
        if (m_gout.value(id).g == g) {
            m_gout.remove(id);
            dropSealed(id);
        }
    for (const QString &t : m_gtokens.keys())
        if (str(m_gtokens.value(t), "g") == g)
            m_gtokens.remove(t);
    refreshGroupPeers();
    saveGroups();
    saveGroupChats();
    Q_EMIT changed();
}

// ------------------------------------------------------------------ invites and group codes

bool Chat::tokenOk(const QString &token, const QString &g) const
{
    const QVariantMap t = m_gtokens.value(token);
    return !token.isEmpty() && !t.isEmpty() && str(t, "g") == g && t.value(QStringLiteral("x")).toLongLong() > now();
}

QString Chat::inviteToGroup(const QString &g, const QString &key)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return QStringLiteral("You're no longer in this group.");
    if (!GroupWire::isAdmin(it->state, myKey()))
        return QStringLiteral("Only the group's admins can invite people.");
    if (GroupWire::isMember(it->state, key))
        return QStringLiteral("%1 is already in %2.").arg(nameOf(key), groupName(g));
    if (m_mod && m_mod->isBanned(g, key))
        return QStringLiteral("%1 is banned from %2. Unban them in the group's settings first.").arg(nameOf(key), groupName(g));
    if (!isContact(key))
        return QStringLiteral("Only contacts can be invited. Show them the group code instead.");
    if (!canGroup(key)) {
        probeCaps(key);   // the answer shows up next time, if their app has caps
        return QStringLiteral("Needs an update");
    }
    if (it->state.contains(QStringLiteral("kind")) && !hasD1(key)) {
        probeCaps(key);
        return QStringLiteral("Needs an update");
    }
    if (GroupWire::keysOf(it->state).size() >= GroupWire::kMaxMembers)
        return QStringLiteral("This group is full: 50 people at most.");
    const QString token = GroupWire::newToken();
    const qint64 x = now() + GroupWire::kTokenLife;
    m_gtokens.insert(token, {{QStringLiteral("g"), g}, {QStringLiteral("x"), x}, {QStringLiteral("for"), key}});
    sendControl(g, key, {{QStringLiteral("t"), QStringLiteral("invite")}, {QStringLiteral("state"), it->state}, {QStringLiteral("inv"), token},
                         {QStringLiteral("x"), x / 1000}});
    saveGroups();
    return QString();
}

QString Chat::groupCode(const QString &g)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left || !GroupWire::isAdmin(it->state, myKey()))
        return QString();
    const QString token = GroupWire::newToken();
    const qint64 x = now() + GroupWire::kTokenLife;
    m_gtokens.insert(token, {{QStringLiteral("g"), g}, {QStringLiteral("x"), x}});
    saveGroups();
    return GroupWire::code(g, groupName(g), myKey(), username(), mailbox(), token, x);
}

QVariantMap Chat::parseGroupCode(const QString &text) const
{
    QVariantMap c = GroupWire::parseCode(text, now());
    if (c.isEmpty())
        return c;
    const QString g = str(c, "g");
    const auto it = m_groups.constFind(g);
    c.insert(QStringLiteral("mine"), str(c, "admin") == myKey());
    c.insert(QStringLiteral("member"), it != m_groups.cend() && !it->state.isEmpty() && !it->removed && !it->left && GroupWire::isMember(it->state, myKey()));
    return c;
}

QString Chat::joinWithCode(const QString &text)
{
    if (!m_ready)
        return QStringLiteral("Open Huginn and unlock it first.");
    const QVariantMap c = parseGroupCode(text);
    if (c.isEmpty())
        return QStringLiteral("That isn't a group code. It starts with MUNINNG1.");
    if (c.value(QStringLiteral("mine")).toBool())
        return QStringLiteral("That's the code for your own group.");
    if (c.value(QStringLiteral("member")).toBool())
        return QStringLiteral("You're already in %1.").arg(str(c, "name"));
    if (c.value(QStringLiteral("expired")).toBool())
        return QStringLiteral("That group code has expired (they last an hour). Ask for a new one.");
    const QString g = str(c, "g"), admin = str(c, "admin");
    // Back into a group you left or were taken out of: start from what the admin sends.
    if (m_groups.contains(g)) {
        m_groups[g].state = QJsonObject();
        m_groups[g].history.clear();
    }
    m_joining.insert(g, {{QStringLiteral("g"), g}, {QStringLiteral("admin"), admin}, {QStringLiteral("adminName"), str(c, "adminName")},
                         {QStringLiteral("mailbox"), str(c, "mailbox")},
                         {QStringLiteral("token"), str(c, "token")}, {QStringLiteral("name"), str(c, "name")}, {QStringLiteral("since"), now()}});
    sendControl(g, admin, {{QStringLiteral("t"), QStringLiteral("join")}, {QStringLiteral("inv"), str(c, "token")}, {QStringLiteral("n"), username()},
                           {QStringLiteral("p"), mailbox()}});
    saveGroups();
    Q_EMIT changed();
    return QString();
}

QString Chat::acceptGroupInvite(const QString &g)
{
    const QVariantMap inv = m_ginvites.take(g);
    if (inv.isEmpty())
        return QStringLiteral("That invite isn't here anymore.");
    const QString from = str(inv, "from");
    if (m_groups.contains(g)) {
        m_groups[g].state = QJsonObject();
        m_groups[g].history.clear();
    }
    m_joining.insert(g, {{QStringLiteral("g"), g}, {QStringLiteral("admin"), from}, {QStringLiteral("adminName"), nameOf(from)},
                         {QStringLiteral("mailbox"), mailboxOf(from)}, {QStringLiteral("token"), str(inv, "inv")},
                         {QStringLiteral("name"), str(inv, "name")}, {QStringLiteral("since"), now()}});
    sendControl(g, from, {{QStringLiteral("t"), QStringLiteral("join")}, {QStringLiteral("inv"), str(inv, "inv")}, {QStringLiteral("n"), username()},
                          {QStringLiteral("p"), mailbox()}});
    saveGroups();
    Q_EMIT changed();
    return QString();
}

void Chat::declineGroupInvite(const QString &g)
{
    const QVariantMap inv = m_ginvites.take(g);
    if (inv.isEmpty()) {
        if (m_joining.remove(g)) {   // stop waiting to be let in
            saveGroups();
            Q_EMIT changed();
        }
        return;
    }
    sendControl(g, str(inv, "from"), {{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("inv"), str(inv, "inv")}});
    saveGroups();
    Q_EMIT changed();
}

// ------------------------------------------------------------------ sending

void Chat::sendControl(const QString &g, const QString &to, QJsonObject msg)
{
    msg.insert(QStringLiteral("g"), g);
    if (!msg.contains(QStringLiteral("id")))
        msg.insert(QStringLiteral("id"), GroupWire::newLogicalId());
    queueGroup(g, to, QStringLiteral("group"), msg.value(QStringLiteral("id")).toString(), compact(msg));
}

void Chat::queueGroup(const QString &g, const QString &to, const QString &room, const QString &logical, const QByteArray &plain)
{
    if (m_moving)
        return;
    if (to.isEmpty() || to == myKey())
        return;
    const QString envId = GroupWire::envelopeId(logical, to);
    queueGroupBodies(g, to, room, logical, {compact(envelope(room, to, plain, envId))});
}

void Chat::queueGroupBodies(const QString &g, const QString &to, const QString &room, const QString &logical, const QList<QByteArray> &bodies)
{
    if (bodies.isEmpty())
        return;
    const QString envId = GroupWire::envelopeId(logical, to);
    keepSealed(envId, to, bodies);   // every try and route sends these same bytes
    GroupOut o;
    o.g = g;
    o.to = to;
    o.logical = logical;
    o.room = room;
    o.time = now();
    m_gout.insert(envId, o);
    groupAttempt(envId);
    saveGroupsSoon();
}

void Chat::groupAttempt(const QString &envId)
{
    if (m_moving)
        return;
    const auto it = m_gout.find(envId);
    if (it == m_gout.end())
        return;
    it->count++;
    it->next = now() + retryDelay(it->count);
    const ChatWire::Outbox::Entry *e = m_outbox.find(envId);
    if (!e || e->bodies.empty()) {
        m_gout.erase(it);
        return;
    }
    const QList<QByteArray> bodies(e->bodies.begin(), e->bodies.end());
    const GroupOut o = *it;
    const bool media = o.room == QLatin1String("gphoto");
    if (media)
        it->next = qMax(it->next, now() + qint64(bodies.size()) * 200);
    const bool message = o.room == QLatin1String("gmsg") || media;
    auto sent = [this, o, message] {
        if (!message)
            return;
        updateGroupMessage(o.g, o.logical, [](QVariantMap &x) {
            if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < 2) {
                x.insert(QStringLiteral("state"), QStringLiteral("sent"));
                x.insert(QStringLiteral("failed"), false);
            }
        });
    };
    // Cap s1: only a route that proved it's them keeps a big photo off the internet or counts as sent.
    bool any = false;
    bool ble = true;
    for (const QByteArray &b : bodies)
        ble = ble && sendBle(o.to, b);
    if (ble && m_ble->proven(o.to)) {
        any = true;
        sent();
        if (media)
            m_gout[envId].next = qMax(m_gout[envId].next, now() + qint64(bodies.size()) * 3000);
    }
    const QString base = baseTo(o.to);
    if (!base.isEmpty()) {
        const bool proven = m_net->provenFor(o.to);
        any = any || proven;
        for (const QByteArray &b : bodies)
            post(base, b, [sent, proven](int code) {
                if (code == 200 && proven)
                    sent();
            });
    }
    // Cap a1: a GIF may use all its pieces over the internet (8 MB); other media 2 MB.
    if ((!media || !any || o.count >= 2) && (bodies.size() <= Photos::kInternetPieces || (media && groupGif(o.g, o.logical) && bodies.size() <= Photos::kGifPieces)))
        sendInternet(o.to, bodies, mailboxOf(o.to), sent, groupDays(o.g));
}

void Chat::groupRetry()
{
    const qint64 t0 = now();
    QStringList due, gone;
    for (auto it = m_gout.cbegin(); it != m_gout.cend(); ++it) {
        const GroupOut &o = it.value();
        const auto gr = m_groups.constFind(o.g);
        const bool message = o.room == QLatin1String("gmsg") || o.room == QLatin1String("gphoto");
        if (t0 - o.time > kDay || (message && (gr == m_groups.cend() || !GroupWire::isMember(gr->state, o.to))))
            gone << it.key();   // a day, or they're not in the group
        else if (t0 >= o.next)
            due << it.key();
    }
    for (const QString &id : std::as_const(gone)) {
        m_gout.remove(id);
        dropSealed(id);
    }
    for (const QString &id : std::as_const(due))
        groupAttempt(id);
    if (!gone.isEmpty())
        saveGroupsSoon();
}

void Chat::groupReceipt(const QString &g, const QString &to, const QStringList &ids, const QString &state)
{
    if (ids.isEmpty() || to.isEmpty())
        return;
    for (int i = 0; i < ids.size(); i += GroupWire::kMaxReceiptIds) {
        QJsonObject r{{QStringLiteral("g"), g}, {QStringLiteral("ids"), QJsonArray::fromStringList(ids.mid(i, GroupWire::kMaxReceiptIds))},
                      {QStringLiteral("s"), state}};
        if (state == QLatin1String("delivered"))
            r.insert(QStringLiteral("nr"), 1);   // 3.10: Huginn never sends group "read"
        deliver(to, envelope(QStringLiteral("greceipt"), to, compact(r), uuid()), mailboxOf(to));
    }
}

void Chat::sendSync(const QString &g, const QString &to)
{
    const QString k = g + QLatin1Char('|') + to;
    if (now() - m_syncAsked.value(k, 0) < 30000 || !m_groups.contains(g))
        return;
    m_syncAsked.insert(k, now());
    sendControl(g, to, {{QStringLiteral("t"), QStringLiteral("sync")}, {QStringLiteral("have"), ver(m_groups.value(g).state)}});
}

void Chat::sendStateTo(const QString &g, const QString &to)
{
    const QString k = g + QStringLiteral("|s|") + to;
    if (now() - m_syncAsked.value(k, 0) < 30000 || !m_groups.contains(g) || m_groups.value(g).state.isEmpty())
        return;
    m_syncAsked.insert(k, now());
    sendControl(g, to, {{QStringLiteral("t"), QStringLiteral("state")}, {QStringLiteral("op"), QStringLiteral("sync")}, {QStringLiteral("state"), m_groups.value(g).state}});
}

void Chat::sendGroup(const QString &g, const QString &ch, const QString &text, const QString &re) { sendGroupWith(g, ch, text, re, QJsonObject()); }

// poll: cap m2, a poll whose text says the same for apps without m2.
void Chat::sendGroupWith(const QString &g, const QString &ch, const QString &text, const QString &re, const QJsonObject &poll)
{
    const QString t = GroupWire::clip(text.trimmed(), kMax);
    const auto it = m_groups.constFind(g);
    if (t.isEmpty() || !m_ready || it == m_groups.cend() || it->state.isEmpty())
        return;
    if (it->removed || it->left) {
        Q_EMIT notice(QStringLiteral("You're no longer in this group."));
        return;
    }
    // Cap g2: timed out or banned here, nothing goes out.
    if (m_mod && !m_mod->postBlock(g).isEmpty()) {
        Q_EMIT notice(m_mod->postBlock(g));
        return;
    }
    // Cap g3 (28.2): only admins post in this channel.
    if (adminsOnlyFor(it->state, ch, myKey())) {
        Q_EMIT notice(QStringLiteral("Only admins post here."));
        return;
    }
    const QJsonObject s = it->state;
    const QJsonObject c = GroupWire::channel(s, ch);
    const QString channel = c.value(QStringLiteral("t")).toString() == QLatin1String("text") ? ch : QStringLiteral("main");
    const QString id = GroupWire::newLogicalId();
    const qint64 ts = now();
    const QStringList to = without(GroupWire::keysOf(s), myKey());
    QVariantMap m{{QStringLiteral("id"), id}, {QStringLiteral("ch"), channel}, {QStringLiteral("from"), myKey()}, {QStringLiteral("name"), username()},
                  {QStringLiteral("text"), t}, {QStringLiteral("mine"), true}, {QStringLiteral("time"), ts},
                  {QStringLiteral("state"), to.isEmpty() ? QStringLiteral("delivered") : QStringLiteral("sending")}, {QStringLiteral("to"), to},
                  {QStringLiteral("dlv"), QStringList()}, {QStringLiteral("rd"), QStringList()}, {QStringLiteral("photo"), QString()},
                  {QStringLiteral("voice"), 0}, {QStringLiteral("unsent"), QString()}, {QStringLiteral("failed"), false}};
    // A reply (cap m1): "re" names the message it answers; apps without m1 just show the text.
    const bool quoting = GroupWire::isLogicalId(re) && m_gidx.value(re) == g;
    if (quoting)
        m.insert(QStringLiteral("re"), re);
    if (!poll.isEmpty())
        m.insert(QStringLiteral("poll"), poll.toVariantMap());
    storeGroupMessage(g, m, false);
    QJsonObject p{{QStringLiteral("g"), g}, {QStringLiteral("ch"), channel}, {QStringLiteral("id"), id}, {QStringLiteral("sv"), ver(s)},
                  {QStringLiteral("text"), t}, {QStringLiteral("ts"), ts}};
    if (quoting)
        p.insert(QStringLiteral("re"), re);
    if (!poll.isEmpty())
        p.insert(QStringLiteral("poll"), poll);   // apps without m2 ignore it and show the text
    queueGroupMessage(g, id, to, p);   // a link gets its preview first (cap m2)
}

// Cap m1: my reaction to a group message ("" takes it away), to every member whose app has m1.
QString Chat::reactGroup(const QString &g, const QString &id, const QString &emoji)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return QStringLiteral("You're no longer in this group.");
    if (!RichWire::emojiOk(emoji))
        return QStringLiteral("That reaction can't be sent.");
    const qint64 ts = now();
    bool done = false;
    updateGroupMessage(g, id, [&](QVariantMap &m) {
        if (!str(m, "unsent").isEmpty() || m.value(QStringLiteral("system")).toBool())
            return;
        QVariantMap rx = m.value(QStringLiteral("rx")).toMap();
        if (RichWire::react(rx, myKey(), emoji, ts)) {
            m.insert(QStringLiteral("rx"), rx);
            done = true;
        }
    });
    if (!done)
        return QString();
    const QString lid = GroupWire::newLogicalId();
    for (const QString &k : without(GroupWire::keysOf(m_groups.value(g).state), myKey()))
        if (hasM1(k))
            sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("react")}, {QStringLiteral("id"), lid}, {QStringLiteral("msg"), id},
                               {QStringLiteral("e"), emoji}, {QStringLiteral("ts"), ts}});
    return QString();
}

// Cap m1: a new text for my message, for everyone who got it and has m1.
QString Chat::editGroup(const QString &g, const QString &id, const QString &text)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return QStringLiteral("You're no longer in this group.");
    const QString t = GroupWire::clip(text.trimmed(), kMax);
    if (t.isEmpty())
        return QStringLiteral("A message can't be empty. Unsend it instead.");
    qint64 at = 0;
    QStringList to;
    updateGroupMessage(g, id, [&](QVariantMap &m) {
        if (!m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty() || !str(m, "photo").isEmpty() || str(m, "text") == t)
            return;
        at = qMax(now(), m.value(QStringLiteral("edited")).toLongLong() + 1);
        m.insert(QStringLiteral("text"), t);
        m.insert(QStringLiteral("edited"), at);
        to = m.value(QStringLiteral("to")).toStringList();
    });
    if (at == 0)
        return QString();
    const QJsonObject s = m_groups.value(g).state;
    const QString lid = GroupWire::newLogicalId();
    for (const QString &k : std::as_const(to))
        if (GroupWire::isMember(s, k) && hasM1(k))
            sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("edit")}, {QStringLiteral("id"), lid}, {QStringLiteral("msg"), id},
                               {QStringLiteral("text"), t}, {QStringLiteral("ts"), at}});
    return QString();
}

// A reaction or edit (cap m1, already checked) from a member.
void Chat::takeGroupChange(const QString &g, const QString &from, const QJsonObject &p)
{
    const QString msg = p.value(QStringLiteral("msg")).toString();
    const qint64 ts = RichWire::ts(p);
    const bool reaction = p.value(QStringLiteral("t")).toString() == QLatin1String("react");
    QString note, ch;
    updateGroupMessage(g, msg, [&](QVariantMap &m) {
        if (!str(m, "unsent").isEmpty() || m.value(QStringLiteral("system")).toBool())
            return;
        ch = str(m, "ch");
        if (reaction) {
            QVariantMap rx = m.value(QStringLiteral("rx")).toMap();
            const QString e = p.value(QStringLiteral("e")).toString();
            if (RichWire::react(rx, from, e, ts)) {
                m.insert(QStringLiteral("rx"), rx);
                if (m.value(QStringLiteral("mine")).toBool() && !e.isEmpty())
                    note = QStringLiteral("Reacted ") + e + QStringLiteral(" to your message");
            }
        } else if (!m.value(QStringLiteral("mine")).toBool() && str(m, "photo").isEmpty()
                   && RichWire::editWins(str(m, "from"), from, ts, m.value(QStringLiteral("edited")).toLongLong())) {
            m.insert(QStringLiteral("text"), p.value(QStringLiteral("text")).toString().left(kMax));
            m.insert(QStringLiteral("edited"), ts);
        }
    });
    if (!note.isEmpty())
        Q_EMIT arrived(QStringLiteral("%1 in %2").arg(memberName(g, from), groupName(g)), note, QStringLiteral("g:%1:%2").arg(g, ch));
}

bool Chat::sendGroupPhoto(const QString &g, const QString &ch, const QString &path, bool hd)
{
    // Cap a1 (section 25): a GIF goes animated when it's within the limits; over them, as a still picture.
    if (Photos::isGifFile(path)) {
        QString why;
        const QByteArray gif = Photos::readGif(path, &why);
        if (!gif.isEmpty())
            return !sendGroupBlob(g, ch, gif, 0, QVariantMap{{QStringLiteral("gif"), true}}).isEmpty();
        Q_EMIT notice(Photos::gifWhy(why) + QStringLiteral(", so it goes as a still picture."));
    }
    const QByteArray jpeg = Photos::prepare(path, hd);
    if (jpeg.isEmpty()) {
        Q_EMIT notice(QStringLiteral("That picture couldn't be opened."));
        return false;
    }
    return !sendGroupBlob(g, ch, jpeg, 0).isEmpty();
}

QString Chat::sendGroupBlob(const QString &g, const QString &ch, const QByteArray &bytes, int voiceMs, const QVariantMap &extra)
{
    const auto it = m_groups.constFind(g);
    if (!m_ready || it == m_groups.cend() || it->state.isEmpty())
        return QString();
    if (it->removed || it->left) {
        Q_EMIT notice(QStringLiteral("You're no longer in this group."));
        return QString();
    }
    // Cap g2: no photos, voice messages or files while timed out or banned.
    if (m_mod && !m_mod->postBlock(g).isEmpty()) {
        Q_EMIT notice(m_mod->postBlock(g));
        return QString();
    }
    // Cap g3 (28.2): only admins post in this channel.
    if (adminsOnlyFor(it->state, ch, myKey())) {
        Q_EMIT notice(QStringLiteral("Only admins post here."));
        return QString();
    }
    const QJsonObject s = it->state;
    const QString channel = GroupWire::channel(s, ch).value(QStringLiteral("t")).toString() == QLatin1String("text") ? ch : QStringLiteral("main");
    const QString gid = GroupWire::newLogicalId();
    m_vault->write(QStringLiteral("photos/") + gid, bytes);
    QStringList to = without(GroupWire::keysOf(s), myKey());
    // Cap m2: a video message goes only to members whose app plays it; an album's fields are ignored by apps without m2.
    if (extra.value(QStringLiteral("video")).toInt() > 0)
        to.erase(std::remove_if(to.begin(), to.end(), [this](const QString &k) { return !hasM2(k); }), to.end());
    QVariantMap m{{QStringLiteral("id"), gid}, {QStringLiteral("ch"), channel}, {QStringLiteral("from"), myKey()}, {QStringLiteral("name"), username()},
                  {QStringLiteral("text"), QString()}, {QStringLiteral("mine"), true}, {QStringLiteral("time"), now()},
                  {QStringLiteral("state"), to.isEmpty() ? QStringLiteral("delivered") : QStringLiteral("sending")}, {QStringLiteral("to"), to},
                  {QStringLiteral("dlv"), QStringList()}, {QStringLiteral("rd"), QStringList()}, {QStringLiteral("photo"), gid},
                  {QStringLiteral("voice"), voiceMs}, {QStringLiteral("unsent"), QString()}, {QStringLiteral("failed"), false}};
    m.insert(extra);
    storeGroupMessage(g, m, false);
    for (const QString &k : to)
        queueGroupBodies(g, k, QStringLiteral("gphoto"), gid, groupPieces(g, channel, gid, k, bytes, voiceMs, extra));
    return gid;
}

// The private chat's photo pieces, plus g, ch and gid in the clear; each member's copy has its own id (2.3).
QList<QByteArray> Chat::groupPieces(const QString &g, const QString &ch, const QString &gid, const QString &to, const QByteArray &bytes, int voiceMs,
                                    const QVariantMap &extra)
{
    // Cap a1: the GIF to a member whose app shows it, its first frame as a photo to anyone else.
    const bool gif = extra.value(QStringLiteral("gif")).toBool() && hasCap(to, QLatin1String(Photos::kGifCap));
    const QList<QByteArray> parts = Photos::split(extra.value(QStringLiteral("gif")).toBool() && !gif ? Photos::stillOfGif(bytes) : bytes);
    const QString envId = GroupWire::envelopeId(gid, to);
    QList<QByteArray> out;
    for (int i = 0; i < parts.size(); ++i) {
        QJsonObject o{{QStringLiteral("room"), QStringLiteral("gphoto")}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                      {QStringLiteral("mailbox"), mailbox()}, {QStringLiteral("id"), envId}, {QStringLiteral("i"), i}, {QStringLiteral("n"), int(parts.size())},
                      {QStringLiteral("g"), g}, {QStringLiteral("ch"), ch}, {QStringLiteral("gid"), gid}};
        if (voiceMs > 0) {
            o.insert(QStringLiteral("kind"), QStringLiteral("voice"));
            o.insert(QStringLiteral("ms"), voiceMs);
        }
        MoreWire::addPieceFields(o, extra);   // cap m2: a video message, or a photo in an album
        if (gif)
            o.insert(QStringLiteral("kind"), QStringLiteral("gif"));
        out << compact(seal(o, to, parts.at(i)));
    }
    return out;
}

void Chat::markGroupRead(const QString &g, const QString &ch)
{
    if (!m_groups.contains(g))
        return;
    Group &gr = m_groups[g];
    if (gr.unread.remove(ch)) {
        saveGroupsSoon();
        Q_EMIT changed();
    }
    // No group read receipts from Huginn: its "delivered" receipts carry nr (protocol 3.10), so Muninn shows "Not shown".
}

void Chat::unsendGroup(const QString &g, const QString &id)
{
    QStringList to;
    const QString u = GroupWire::newLogicalId();
    updateGroupMessage(g, id, [this, &to, u](QVariantMap &m) {
        if (!m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty())
            return;
        if (!str(m, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        m.insert(QStringLiteral("text"), QString());
        m.insert(QStringLiteral("photo"), QString());
        m.insert(QStringLiteral("voice"), 0);
        m.insert(QStringLiteral("unsent"), QStringLiteral("You"));
        m.insert(QStringLiteral("unsendId"), u);
        to = m.value(QStringLiteral("to")).toStringList();
    });
    // The message itself stops going out; everyone who may have it hears to blank it.
    for (const QString &k : std::as_const(to)) {
        const QString envId = GroupWire::envelopeId(id, k);
        if (m_gout.remove(envId))
            dropSealed(envId);
        sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("unsend")}, {QStringLiteral("id"), u}, {QStringLiteral("msg"), id}});
    }
}

void Chat::deleteGroupMessage(const QString &g, const QString &id)
{
    if (!m_groups.contains(g))
        return;
    forget({id});
    QVariantList &l = m_groups[g].messages;
    for (int i = int(l.size()) - 1; i >= 0; --i)
        if (str(l.at(i).toMap(), "id") == id) {
            const QString p = str(l.at(i).toMap(), "photo");
            if (!p.isEmpty())
                m_vault->remove(QStringLiteral("photos/") + p);
            l.removeAt(i);
        }
    m_gidx.remove(id);
    saveGroupChats();
    Q_EMIT changed();
}

void Chat::retryGroup(const QString &g, const QString &id)
{
    for (auto it = m_gout.begin(); it != m_gout.end(); ++it)
        if (it->g == g && it->logical == id) {
            it->next = 0;
            it->time = now();
        }
    updateGroupMessage(g, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), false); });
    groupRetry();
}

bool Chat::groupGif(const QString &g, const QString &id) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend())
        return false;
    for (int i = int(it->messages.size()) - 1; i >= 0; --i) {
        const QVariantMap m = it->messages.at(i).toMap();
        if (str(m, "id") == id)
            return m.value(QStringLiteral("gif")).toBool();
    }
    return false;
}

void Chat::updateGroupMessage(const QString &g, const QString &id, const std::function<void(QVariantMap &)> &f)
{
    if (!m_groups.contains(g))
        return;
    bool touched = false;
    QVariantList &l = m_groups[g].messages;
    for (int i = int(l.size()) - 1; i >= 0; --i) {
        QVariantMap m = l.at(i).toMap();
        if (str(m, "id") != id)
            continue;
        const QVariantMap before = m;
        f(m);
        if (m != before) {
            l[i] = m;
            touched = true;
        }
        break;
    }
    if (touched) {
        saveGroupChatsSoon();
        noteChanged();
    }
}

void Chat::storeGroupMessage(const QString &g, const QVariantMap &m, bool notify)
{
    const QString id = str(m, "id");
    if (!m_groups.contains(g) || (!m.value(QStringLiteral("system")).toBool() && gmsgKnown(id)))
        return;
    Group &gr = m_groups[g];
    gr.messages << m;
    while (gr.messages.size() > kMaxGroupMessages) {
        const QVariantMap old = gr.messages.takeFirst().toMap();
        m_gidx.remove(str(old, "id"));
        if (!str(old, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(old, "photo"));
    }
    if (!m.value(QStringLiteral("system")).toBool())
        m_gidx.insert(id, g);
    const QString ch = str(m, "ch");
    if (notify && !m.value(QStringLiteral("mine")).toBool()) {
        const QString key = QStringLiteral("g:%1:%2").arg(g, ch);
        if (m_openChat != key) {
            gr.unread[ch] += 1;
            saveGroupsSoon();
        }
        const QString what = m.value(QStringLiteral("voice")).toInt() > 0 ? QStringLiteral("Voice message")
                           : m.value(QStringLiteral("video")).toInt() > 0  ? QStringLiteral("Video message")
                           : m.value(QStringLiteral("gif")).toBool()       ? QStringLiteral("GIF")
                           : !str(m, "photo").isEmpty()                    ? QStringLiteral("Photo")
                                                                           : str(m, "text");
        // An @mention of me says so (and Main.qml makes it stand out).
        const bool mention = RichWire::mentions(str(m, "text"), username());
        Q_EMIT arrived((mention ? QStringLiteral("%1 mentioned you in %2") : QStringLiteral("%1 in %2")).arg(memberName(g, str(m, "from")), groupName(g)), what, key);
    }
    saveGroupChatsSoon();
    noteChanged();
}

// A line from Huginn itself in the group's first channel (never sent).
void Chat::groupNote(const QString &g, const QString &text, bool call)
{
    QVariantMap m{{QStringLiteral("id"), uuid()}, {QStringLiteral("ch"), QStringLiteral("main")}, {QStringLiteral("text"), text},
                  {QStringLiteral("system"), true}, {QStringLiteral("mine"), false}, {QStringLiteral("time"), now()}, {QStringLiteral("state"), QStringLiteral("read")}};
    if (call)
        m.insert(QStringLiteral("call"), true);
    storeGroupMessage(g, m, false);
}

// ------------------------------------------------------------------ receiving

int Chat::receiveGroup(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via)
{
    const QString room = o.value(QStringLiteral("room")).toString();
    const QString envId = o.value(QStringLiteral("id")).toString();
    // Copies already here (another route, or a resend): answer again, never open twice.
    if (m_seen.has(fromKey, envId)) {
        const QString note = m_seen.note(fromKey, envId);
        const int bar = int(note.indexOf(QLatin1Char('|')));
        if (room == QLatin1String("group") && bar > 0)
            groupReceipt(note.left(bar), fromKey, {note.mid(bar + 1)}, QStringLiteral("delivered"));
        return 200;
    }
    if (room == QLatin1String("gmsg")) {
        const QString logical = envId.section(QLatin1Char('-'), 0, 0);
        if (GroupWire::isLogicalId(logical) && (gmsgKnown(logical) || m_unsentBy.value(logical) == fromKey)) {
            const QString g = m_gidx.value(logical);
            if (!g.isEmpty())
                groupReceipt(g, fromKey, {logical}, QStringLiteral("delivered"));
            return 200;
        }
    }
    bool ok = false;
    const QByteArray plain = open(o, fromKey, &ok);
    if (!ok)
        return keyWith(fromKey).isEmpty() ? 400 : 403;
    const QJsonObject p = parse(plain);
    const QString g = p.value(QStringLiteral("g")).toString();
    if (!GroupWire::isGroupId(g))
        return 400;
    // Floods: gmsg like private messages (5 in 10 s), control messages 20 in 10 s, receipts free.
    if (room != QLatin1String("greceipt")) {
        const bool msg = room == QLatin1String("gmsg");
        QHash<QString, QList<qint64>> &rates = msg ? m_recent : m_recentGroup;
        if (rates.size() > 500)
            rates.clear();
        QList<qint64> &q = rates[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= (msg ? 5 : 20))
            return 429;
        q << now();
    }
    const auto git = m_groups.constFind(g);
    const bool have = git != m_groups.cend() && !git->state.isEmpty();
    const bool member = have && GroupWire::isMember(git->state, fromKey);
    const QString t = p.value(QStringLiteral("t")).toString();
    // Who may send what: contacts, members of that group, the admin being joined through, and
    // (for a group code) whoever brings a live token made here.
    bool allowed = isContact(fromKey) || member || str(m_joining.value(g), "admin") == fromKey;
    if (!allowed && room == QLatin1String("group") && t == QLatin1String("join")
        && (tokenOk(p.value(QStringLiteral("inv")).toString(), g) || !liveLink(p.value(QStringLiteral("inv")).toString(), g).isEmpty()))
        allowed = true;
    if (!allowed && room == QLatin1String("gmsg") && have && p.value(QStringLiteral("sv")).toInteger() > ver(git->state))
        allowed = true;   // maybe added in a version not seen here yet: held below
    if (!allowed)
        return 403;
    commitPeer();
    // A group peer's mailbox follows their authentic envelopes, like a contact's.
    if (!mailbox.isEmpty() && isGroupPeer(fromKey) && str(m_gpeers.value(fromKey), "mailbox") != mailbox) {
        m_gpeers[fromKey].insert(QStringLiteral("mailbox"), mailbox);
        saveGroupsSoon();
    }

    if (room == QLatin1String("greceipt")) {
        m_seen.add(fromKey, envId, now());
        const QString s = p.value(QStringLiteral("s")).toString();
        if (s != QLatin1String("delivered") && s != QLatin1String("read") && s != QLatin1String("unsent"))
            return 400;
        const QJsonArray idList = p.value(QStringLiteral("ids")).toArray();
        // Section 9: at most 40 ids a receipt; more is malformed and refused.
        if (idList.size() > GroupWire::kMaxReceiptIds)
            return 400;
        QSet<QString> ids;
        bool dropped = false;
        for (int i = 0; i < int(idList.size()); ++i) {
            const QString id = idList.at(i).toString();
            ids.insert(id);
            const QString env = GroupWire::envelopeId(id, fromKey);
            if (m_gout.contains(env) && m_gout.value(env).to == fromKey) {
                m_gout.remove(env);
                dropSealed(env);   // there: no more resends
                dropped = true;
            }
        }
        bool touched = false;
        if (have && !ids.isEmpty()) {
            QVariantList &l = m_groups[g].messages;
            for (QVariant &v : l) {
                QVariantMap m = v.toMap();
                if (!m.value(QStringLiteral("mine")).toBool() || !ids.contains(str(m, "id")) || !m.value(QStringLiteral("to")).toStringList().contains(fromKey))
                    continue;
                // "unsent" answers the unsend message, which has its own id.
                const QString unsendId = str(m, "unsendId");
                if (s == QLatin1String("unsent") && !unsendId.isEmpty()) {
                    const QString uenv = GroupWire::envelopeId(unsendId, fromKey);
                    if (m_gout.remove(uenv)) {
                        dropSealed(uenv);
                        dropped = true;
                    }
                }
                const QVariantMap before = m;
                QStringList dlv = m.value(QStringLiteral("dlv")).toStringList(), rd = m.value(QStringLiteral("rd")).toStringList();
                if (!dlv.contains(fromKey))
                    dlv << fromKey;
                if (s == QLatin1String("read") && !rd.contains(fromKey))
                    rd << fromKey;
                m.insert(QStringLiteral("dlv"), dlv);
                m.insert(QStringLiteral("rd"), rd);
                if (str(m, "unsent").isEmpty()) {
                    const QString st = rd.isEmpty() ? QStringLiteral("delivered") : QStringLiteral("read");
                    if (rank(st) > rank(str(m, "state")))
                        m.insert(QStringLiteral("state"), st);
                    m.insert(QStringLiteral("failed"), false);
                }
                if (m == before)
                    continue;   // already so: nothing to save or show
                v = m;
                touched = true;
            }
        }
        if (touched) {
            saveGroupChatsSoon();
            noteChanged();
        }
        if (dropped)
            saveGroupsSoon();
        return 200;
    }

    if (room == QLatin1String("gmsg"))
        return gmsgIn(g, fromKey, name, via, p);

    // room "group"
    const QString lid = p.value(QStringLiteral("id")).toString();
    if (!GroupWire::isLogicalId(lid))
        return 400;
    const QString me = myKey();
    if (t == QLatin1String("state")) {
        applyState(g, p.value(QStringLiteral("state")).toObject(), fromKey, p.value(QStringLiteral("op")).toString());
    } else if (t == QLatin1String("states")) {
        const QJsonArray list = p.value(QStringLiteral("list")).toArray();
        for (int i = 0; i < qMin(int(list.size()), GroupWire::kStatesAnswer); ++i)
            applyState(g, list.at(i).toObject(), fromKey, QStringLiteral("sync"));
    } else if (t == QLatin1String("sync")) {
        if (member) {
            const qint64 haveV = p.value(QStringLiteral("have")).toInteger();
            QJsonArray list;
            int bytes = 0;
            for (const QJsonObject &s : git->history) {
                if (ver(s) <= haveV)
                    continue;
                bytes += int(compact(s).size());
                if (list.size() >= GroupWire::kStatesAnswer || bytes > 48 * 1024)
                    break;   // the rest comes with the next sync
                list.append(s);
            }
            if (!list.isEmpty())
                sendControl(g, fromKey, {{QStringLiteral("t"), QStringLiteral("states")}, {QStringLiteral("list"), list}});
            if (m_mod)
                m_mod->sendRecords(g, fromKey);   // cap g2: timeouts and bans in force, to a member who was behind
        }
    } else if (t == QLatin1String("invite")) {
        const QJsonObject st = p.value(QStringLiteral("state")).toObject();
        const bool already = have && GroupWire::isMember(git->state, me) && !git->removed && !git->left;
        if (isContact(fromKey) && !already && GroupWire::accept(nullptr, st).ok && st.value(QStringLiteral("by")).toString() == fromKey
            && st.value(QStringLiteral("g")).toString() == g) {
            const QString gname = st.value(QStringLiteral("name")).toString();
            const bool fresh = !m_ginvites.contains(g);
            m_ginvites.insert(g, {{QStringLiteral("g"), g}, {QStringLiteral("from"), fromKey}, {QStringLiteral("name"), gname},
                                  {QStringLiteral("count"), int(GroupWire::keysOf(st).size())}, {QStringLiteral("inv"), p.value(QStringLiteral("inv")).toString()}});
            saveGroups();
            if (fresh) {
                Q_EMIT notice(QStringLiteral("%1 invited you to %2. Open Chats to answer.").arg(nameOf(fromKey), gname));
                Q_EMIT arrived(nameOf(fromKey), QStringLiteral("Invited you to ") + gname, QStringLiteral("invite:") + g);
            }
            noteChanged();
        }
    } else if (t == QLatin1String("join")) {
        const QString inv = p.value(QStringLiteral("inv")).toString();
        const QVariantMap tok = m_gtokens.value(inv);
        // Cap j1: an invite link this computer counts (17.3).
        const bool byLink = !tokenOk(inv, g) && !liveLink(inv, g).isEmpty();
        if (have && GroupWire::isAdmin(git->state, me) && (byLink || (tokenOk(inv, g) && (str(tok, "for").isEmpty() || str(tok, "for") == fromKey)))) {
            if (m_mod && m_mod->isBanned(g, fromKey)) {
                // Cap g2: banned people don't get back in by a code, a link or an invite until they're unbanned.
                Q_EMIT notice(QStringLiteral("%1 tried to join %2, but they're banned.").arg(name, groupName(g)));
            } else if (GroupWire::isMember(git->state, fromKey)) {
                sendStateTo(g, fromKey);   // they missed it: once more
            } else {
                QString pm = p.value(QStringLiteral("p")).toString();
                if (!GroupWire::isMailbox(pm))
                    pm = mailbox;
                QString n = p.value(QStringLiteral("n")).toString();
                if (GroupWire::cleanName(n, GroupWire::kMaxName).trimmed().isEmpty())
                    n = name;
                const QString err = change(g, {{QStringLiteral("op"), QStringLiteral("add")}, {QStringLiteral("k"), fromKey}, {QStringLiteral("n"), n},
                                               {QStringLiteral("p"), pm}});
                if (!err.isEmpty())
                    Q_EMIT notice(QStringLiteral("%1 tried to join %2: %3").arg(name, groupName(g), err));
                else if (byLink && m_groups.contains(g) && GroupWire::isMember(m_groups.value(g).state, fromKey))
                    linkUsed(inv, g);
                if (err.isEmpty() && m_mod)
                    m_mod->sendRecords(g, fromKey);   // cap g2: so their app knows who is timed out
            }
            if (!str(tok, "for").isEmpty())
                m_gtokens.remove(inv);   // a contact's invite is used once; a group code lasts its hour
            saveGroupsSoon();
        }
    } else if (t == QLatin1String("link") || t == QLatin1String("linkoff") || t == QLatin1String("links")) {
        if (!takeLinkControl(g, fromKey, p))
            return 403;
    } else if (t == QLatin1String("decline")) {
        const QString inv = p.value(QStringLiteral("inv")).toString();
        if (str(m_gtokens.value(inv), "for") == fromKey && str(m_gtokens.value(inv), "g") == g) {
            m_gtokens.remove(inv);
            saveGroupsSoon();
        }
    } else if (t == QLatin1String("leave")) {
        if (member && GroupWire::isAdmin(git->state, me))
            change(g, {{QStringLiteral("op"), QStringLiteral("leave")}, {QStringLiteral("k"), fromKey}});
    } else if (t == QLatin1String("mod")) {
        // Cap g2: timeouts and bans an admin signed (moderation.cpp checks each against the current state).
        if (!m_mod || !m_mod->receive(g, fromKey, p))
            return 400;
    } else if (t == QLatin1String("react") || t == QLatin1String("edit")) {
        // Cap m1: only from someone in the current state (cap g2: not timed out or banned).
        if (!RichWire::ok(QStringLiteral("group"), p))
            return 400;
        if (member && !git->removed && !git->left && !(m_mod && m_mod->barred(g, fromKey)))
            takeGroupChange(g, fromKey, p);
    } else if (t == QLatin1String("vote")) {
        // Cap m2: a vote on a poll, only from someone in the current state.
        if (!MoreWire::ok(QStringLiteral("group"), p))
            return 400;
        if (member && !git->removed && !git->left && !(m_mod && m_mod->barred(g, fromKey)))
            takeGroupVote(g, fromKey, p);
    } else if (t == QLatin1String("report")) {
        // Cap g3 (28.3): a member's report, to an admin; shown here only.
        const QString text = p.value(QStringLiteral("text")).toString(), about = p.value(QStringLiteral("k")).toString();
        if (!GroupWire::isLogicalId(p.value(QStringLiteral("msg")).toString()) || GroupWire::length(text) > 500 || about.isEmpty())
            return 400;
        if (!member || !GroupWire::isAdmin(git->state, myKey()))
            return 403;
        groupNote(g, QStringLiteral("Report from %1: %2 wrote: %3")
                         .arg(memberName(g, fromKey), memberName(g, about), text.isEmpty() ? QStringLiteral("(a photo or file)") : text));
    } else if (t == QLatin1String("unsend")) {
        const QString gone = p.value(QStringLiteral("msg")).toString();
        if (have && GroupWire::isLogicalId(gone)) {
            bool found = false;
            updateGroupMessage(g, gone, [&](QVariantMap &m) {
                if (str(m, "from") != fromKey || m.value(QStringLiteral("mine")).toBool())
                    return;
                found = true;
                if (!str(m, "photo").isEmpty())
                    m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
                m.insert(QStringLiteral("text"), QString());
                m.insert(QStringLiteral("photo"), QString());
                m.insert(QStringLiteral("voice"), 0);
                m.insert(QStringLiteral("unsent"), name);
            });
            // Still on its way: a later copy from that very sender never shows (from anyone else it still does).
            if (!found && !m_gidx.contains(gone)) {
                if (m_unsentBy.size() > 2000)
                    m_unsentBy.clear();
                m_unsentBy.insert(gone, fromKey);
            }
            groupReceipt(g, fromKey, {gone}, QStringLiteral("unsent"));
        }
    } else {
        return 400;
    }
    m_seen.add(fromKey, envId, now(), g + QLatin1Char('|') + lid);
    groupReceipt(g, fromKey, {lid}, QStringLiteral("delivered"));
    return 200;
}

int Chat::gmsgIn(const QString &g, const QString &from, const QString &name, const QString &via, const QJsonObject &p)
{
    if (!m_groups.contains(g) || m_groups.value(g).state.isEmpty())
        return 403;
    const QString id = p.value(QStringLiteral("id")).toString();
    const QJsonValue textValue = p.value(QStringLiteral("text"));
    if (!GroupWire::isLogicalId(id) || !textValue.isString() || GroupWire::length(textValue.toString()) > kMax || !RichWire::ok(QStringLiteral("gmsg"), p))
        return 400;
    if (gmsgKnown(id) || m_unsentBy.value(id) == from) {
        groupReceipt(g, from, {id}, QStringLiteral("delivered"));
        return 200;
    }
    Group &gr = m_groups[g];
    if (gr.removed || gr.left)
        return 403;
    const qint64 myv = ver(gr.state), sv = p.value(QStringLiteral("sv")).toInteger();
    QString ch = p.value(QStringLiteral("ch")).toString();
    const QJsonObject c = GroupWire::channel(gr.state, ch);
    // From someone not in the state, or for a channel not seen yet: if their version is newer,
    // hold it and catch up first; it shows once the state says who they are.
    if (!GroupWire::isMember(gr.state, from) || (c.isEmpty() && sv > myv)) {
        if (sv <= myv)
            return 403;
        bool held = false;
        for (const QJsonObject &h : std::as_const(gr.held))
            held = held || h.value(QStringLiteral("p")).toObject().value(QStringLiteral("id")).toString() == id;
        if (!held && gr.held.size() < GroupWire::kMaxHeld)
            gr.held << QJsonObject{{QStringLiteral("from"), from}, {QStringLiteral("name"), name}, {QStringLiteral("via"), via}, {QStringLiteral("p"), p}};
        sendSync(g, from);
        saveGroupsSoon();
        return 200;
    }
    // Cap g2: someone timed out or banned, or (cap g3, 28.2) not an admin in an admin-only channel. Dropped, and
    // answered so their app stops sending it again.
    if ((m_mod && m_mod->barred(g, from)) || adminsOnlyFor(gr.state, ch, from)) {
        groupReceipt(g, from, {id}, QStringLiteral("delivered"));
        return 200;
    }
    // A channel that was removed, or a voice room: shown in the first channel.
    if (c.value(QStringLiteral("t")).toString() != QLatin1String("text"))
        ch = QStringLiteral("main");
    if (sv > myv)
        sendSync(g, from);
    else if (sv < myv)
        sendStateTo(g, from);
    QVariantMap m{{QStringLiteral("id"), id}, {QStringLiteral("ch"), ch}, {QStringLiteral("from"), from}, {QStringLiteral("name"), name},
                  {QStringLiteral("text"), textValue.toString()}, {QStringLiteral("mine"), false}, {QStringLiteral("time"), now()},
                  {QStringLiteral("ts"), p.value(QStringLiteral("ts")).toInteger()}, {QStringLiteral("via"), via}, {QStringLiteral("state"), QString()},
                  {QStringLiteral("photo"), QString()}, {QStringLiteral("voice"), 0}, {QStringLiteral("unsent"), QString()}};
    if (p.contains(QStringLiteral("re")))
        m.insert(QStringLiteral("re"), p.value(QStringLiteral("re")).toString());
    MoreWire::keep(m, QStringLiteral("gmsg"), p);   // cap m2: a poll or a link preview
    storeGroupMessage(g, m, true);
    groupReceipt(g, from, {id}, QStringLiteral("delivered"));
    return 200;
}

void Chat::processHeld(const QString &g)
{
    if (!m_groups.contains(g))
        return;
    const QList<QJsonObject> held = m_groups.value(g).held;
    m_groups[g].held.clear();
    for (const QJsonObject &h : held) {
        const QJsonObject p = h.value(QStringLiteral("p")).toObject();
        const QString from = h.value(QStringLiteral("from")).toString();
        // Shown now that the state says who they are; held again while it still can't be.
        gmsgIn(g, from, h.value(QStringLiteral("name")).toString(), h.value(QStringLiteral("via")).toString(), p);
    }
}

int Chat::receiveGroupPiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via)
{
    Q_UNUSED(mailbox)
    const QString g = o.value(QStringLiteral("g")).toString(), gid = o.value(QStringLiteral("gid")).toString();
    const int n = o.value(QStringLiteral("n")).toInt(), i = o.value(QStringLiteral("i")).toInt(-1);
    const bool gif = o.value(QStringLiteral("kind")).toString() == QLatin1String("gif");   // cap a1: up to 8 MB
    if (!GroupWire::isGroupId(g) || !GroupWire::isLogicalId(gid) || n < 1 || n > (gif ? Photos::kGifPieces : Photos::kMaxPieces) || i < 0 || i >= n
        || !MoreWire::ok(QStringLiteral("piece"), o))
        return 400;
    if (o.value(QStringLiteral("id")).toString() != GroupWire::envelopeId(gid, myKey()))
        return 400;   // someone else's copy
    const auto git = m_groups.constFind(g);
    if (git == m_groups.cend() || git->state.isEmpty() || git->removed || git->left || !GroupWire::isMember(git->state, fromKey))
        return 403;
    // Cap g2: no photos, voice messages or files from someone timed out or banned (never opened or kept).
    // Cap g3 (28.2): nor from someone who isn't an admin, into an admin-only channel.
    if (gmsgKnown(gid) || m_unsentBy.value(gid) == fromKey || (m_mod && m_mod->barred(g, fromKey))
        || adminsOnlyFor(git->state, o.value(QStringLiteral("ch")).toString(), fromKey)) {
        groupReceipt(g, fromKey, {gid}, QStringLiteral("delivered"));
        return 200;
    }
    const QString key = fromKey + QLatin1Char('|') + gid;
    const auto have = m_incoming.constFind(key);
    if (have != m_incoming.cend() && i < have->parts.size() && !have->parts.at(i).isEmpty())
        return 200;   // a piece already here: dropped before opening
    if (m_pieceTimes.size() > 500)
        m_pieceTimes.clear();
    QList<qint64> &q = m_pieceTimes[fromKey];
    while (!q.isEmpty() && now() - q.first() > 60000)
        q.removeFirst();
    if (q.size() >= 1000)
        return 429;
    q << now();
    if (m_incoming.size() > 20 && !m_incoming.contains(key))
        return 429;
    if (n > Photos::kMaxPieces && !m_incoming.contains(key) && bigIncoming())
        return 429;
    bool ok = false;
    const QByteArray part = open(o, fromKey, &ok);
    if (!ok)
        return 403;
    Incoming &inc = m_incoming[key];
    if (inc.parts.isEmpty()) {
        inc.from = fromKey;
        inc.started = now();
        for (int k = 0; k < n; ++k)
            inc.parts << QByteArray();
    }
    if (inc.parts.size() != n)
        return 400;
    commitPeer();
    if (inc.parts.at(i).isEmpty()) {
        inc.parts[i] = part;
        inc.have++;
    }
    if (inc.have < n)
        return 200;
    const QList<QByteArray> parts = m_incoming.take(key).parts;
    const int voiceMs = o.value(QStringLiteral("kind")).toString() == QLatin1String("voice") ? qBound(1, o.value(QStringLiteral("ms")).toInt(), Photos::kMaxVoiceMs) : 0;
    const QVariantMap more = MoreWire::pieceFields(o);   // cap m2: a video message, or a photo in an album
    const QByteArray joined = voiceMs > 0 ? Photos::joinVoice(parts) : more.contains(QStringLiteral("video")) ? MoreWire::joinVideo(parts)
                            : gif ? Photos::joinGif(parts) : Photos::join(parts);
    if (joined.isEmpty())
        return 400;
    m_vault->write(QStringLiteral("photos/") + gid, joined);
    QString ch = o.value(QStringLiteral("ch")).toString();
    if (GroupWire::channel(git->state, ch).value(QStringLiteral("t")).toString() != QLatin1String("text"))
        ch = QStringLiteral("main");
    QVariantMap m{{QStringLiteral("id"), gid}, {QStringLiteral("ch"), ch}, {QStringLiteral("from"), fromKey}, {QStringLiteral("name"), name},
                  {QStringLiteral("text"), QString()}, {QStringLiteral("mine"), false}, {QStringLiteral("time"), now()}, {QStringLiteral("via"), via},
                  {QStringLiteral("state"), QString()}, {QStringLiteral("photo"), gid}, {QStringLiteral("voice"), voiceMs}, {QStringLiteral("unsent"), QString()}};
    m.insert(more);
    if (gif)
        m.insert(QStringLiteral("gif"), true);
    storeGroupMessage(g, m, true);
    groupReceipt(g, fromKey, {gid}, QStringLiteral("delivered"));
    return 200;
}

// ------------------------------------------------------------------ states

bool Chat::applyState(const QString &g, const QJsonObject &next, const QString &from, const QString &op)
{
    const QString me = myKey();
    const bool first = !m_groups.contains(g) || m_groups.value(g).state.isEmpty();
    if (first) {
        // Joining: only a state signed by the admin we asked, with us in it (3.3 step 2).
        const QVariantMap j = m_joining.value(g);
        // Cap m1: a contact put me straight in (op "add", signed by them), no invite to accept.
        const bool added = j.isEmpty() && op == QLatin1String("add") && isContact(from) && next.value(QStringLiteral("by")).toString() == from;
        if ((!added && (j.isEmpty() || str(j, "admin") != next.value(QStringLiteral("by")).toString())) || next.value(QStringLiteral("g")).toString() != g)
            return false;
        if (!GroupWire::accept(nullptr, next).ok || !GroupWire::isMember(next, me))
            return false;
        Group &gr = m_groups[g];
        if (gr.since == 0)
            gr.since = now();
        gr.state = next;
        gr.history = {next};
        gr.removed = gr.left = false;
        gr.lastOp = QJsonObject();
        m_joining.remove(g);
        m_ginvites.remove(g);
        stateAccepted(g, QJsonObject(), true);
        if (added) {
            const QString who = nameOf(from), gname = next.value(QStringLiteral("name")).toString();
            Q_EMIT notice(QStringLiteral("%1 added you to %2.").arg(who, gname));
            Q_EMIT arrived(who, QStringLiteral("Added you to ") + gname, QStringLiteral("g:%1:main").arg(g));
        }
        return true;
    }
    const QJsonObject old = m_groups.value(g).state;
    const GroupWire::Verdict v = GroupWire::accept(&old, next);
    if (!v.ok) {
        // Behind: the signer became an admin in a version we missed. Ask for the ones between.
        if (v.why == QLatin1String("signer is not an admin in the current state") && ver(next) > ver(old) + 1 && !from.isEmpty())
            sendSync(g, from);
        else if (v.why == QLatin1String("not newer") && ver(next) < ver(old) && GroupWire::isMember(old, from))
            sendStateTo(g, from);   // they're the one behind
        return false;
    }
    Group &gr = m_groups[g];
    gr.state = next;
    gr.history << next;
    std::stable_sort(gr.history.begin(), gr.history.end(), [](const QJsonObject &a, const QJsonObject &b) { return ver(a) < ver(b); });
    while (gr.history.size() > GroupWire::kHistory)
        gr.history.removeFirst();
    // My change lost to another admin's at the same version: do it again on top (3.3).
    const QJsonObject mine = gr.lastOp;
    const bool lost = !mine.isEmpty() && mine.value(QStringLiteral("v")).toInteger() == ver(next) && next.value(QStringLiteral("by")).toString() != me;
    if (lost)
        gr.lastOp = QJsonObject();
    stateAccepted(g, old, false);
    if (lost) {
        QJsonObject again = mine;
        again.remove(QStringLiteral("v"));
        change(g, again);   // checks again: a removal is re-done only if they're still there
    }
    return true;
}

void Chat::stateAccepted(const QString &g, const QJsonObject &old, bool first)
{
    if (!m_groups.contains(g))
        return;
    const QString me = myKey();
    const QJsonObject s = m_groups.value(g).state;
    const QString name = s.value(QStringLiteral("name")).toString();
    const QStringList before = GroupWire::keysOf(old), after = GroupWire::keysOf(s);
    const bool inIt = after.contains(me);
    if (first) {
        groupNote(g, QStringLiteral("You joined %1.").arg(name));
        Q_EMIT notice(QStringLiteral("You joined %1.").arg(name));
    } else {
        for (const QString &k : after)
            if (!before.contains(k) && k != me)
                groupNote(g, QStringLiteral("%1 joined.").arg(memberName(g, k)));
        for (const QString &k : before)
            if (!after.contains(k) && k != me) {
                const QString was = isContact(k) ? nameOf(k) : GroupWire::memberEntry(old, k).value(QStringLiteral("n")).toString();
                groupNote(g, QStringLiteral("%1 is no longer in the group.").arg(was.isEmpty() ? QStringLiteral("Someone") : was));
            }
        if (old.value(QStringLiteral("name")).toString() != name)
            groupNote(g, QStringLiteral("The group is now called %1.").arg(name));
        if (old.value(QStringLiteral("owner")).toString() != s.value(QStringLiteral("owner")).toString())
            groupNote(g, QStringLiteral("%1 owns the group now.").arg(memberName(g, s.value(QStringLiteral("owner")).toString())));
        const QString type = Limits::groupType(s);
        if (!old.isEmpty() && Limits::groupType(old) != type)
            groupNote(g, QStringLiteral("This is a %1 group now.").arg(Limits::typeName(type)));
    }
    // A Family group has no invite links: the ones this computer counts stop (17.1).
    if (!Limits::linksAllowed(s))
        familyLinksOff(g);
    Group &gr = m_groups[g];
    if (inIt) {
        gr.removed = false;
        gr.left = false;
    } else if (before.contains(me) && !gr.left && !gr.removed && s.value(QStringLiteral("by")).toString() != me) {
        gr.removed = true;
        groupNote(g, QStringLiteral("You were removed from %1.").arg(name));
        Q_EMIT notice(QStringLiteral("You were removed from %1.").arg(name));
    }
    refreshGroupPeers();
    processHeld(g);
    saveGroups();
    familySync();   // members of a Family group go in the Family folder
    noteChanged();
}

// Everyone in a group with us who isn't a contact is a group peer: reachable for the group only.
void Chat::refreshGroupPeers()
{
    const QString me = myKey();
    QSet<QString> keep;
    // Someone just removed still has to hear it (and anything else on its way to them).
    for (const GroupOut &o : std::as_const(m_gout))
        keep.insert(o.to);
    bool any = false;
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it) {
        if (it->state.isEmpty() || it->removed || it->left)
            continue;
        const QJsonArray members = it->state.value(QStringLiteral("members")).toArray();
        for (const QJsonValue &v : members) {
            const QJsonObject m = v.toObject();
            const QString k = m.value(QStringLiteral("k")).toString();
            if (k == me || k.isEmpty())
                continue;
            // A contact in a group's state runs an app with groups and calls (3.7): what it speaks is known from that too.
            if (isContact(k)) {
                if (!m_caps.value(k).contains(QStringLiteral("g1")))
                    noteCaps(k, true, {QStringLiteral("r1"), QStringLiteral("g1"), QStringLiteral("c1")});
                continue;
            }
            keep.insert(k);
            QVariantMap pe = m_gpeers.value(k);
            const QVariantMap was = pe;
            pe.insert(QStringLiteral("key"), k);
            pe.insert(QStringLiteral("name"), m.value(QStringLiteral("n")).toString());
            if (str(pe, "mailbox").isEmpty())
                pe.insert(QStringLiteral("mailbox"), m.value(QStringLiteral("p")).toString());
            if (pe != was) {
                m_gpeers.insert(k, pe);
                any = true;
            }
        }
    }
    for (const QString &k : m_gpeers.keys())
        if (!keep.contains(k)) {
            m_gpeers.remove(k);
            // Not in a group together any more (and not a contact): their profile picture goes (cap p1).
            if (m_profile && !isContact(k) && !m_met.contains(k))
                m_profile->forget(k);
            any = true;
        }
    if (any)
        saveGroupsSoon();
}

// ------------------------------------------------------------------ keeping

void Chat::flushSaves()
{
    if (!m_ready)
        return;
    if (m_chatsDirty)
        save();
    if (m_groupsDirty)
        saveGroups();
    if (m_gchatsDirty)
        saveGroupChats();
    if (m_seen.dirty())
        m_vault->write(QStringLiteral("seen.json"), m_seen.save());
    // Before a restart, an update, a backup or closing: everything handed to the saving thread is on disk.
    m_vault->drainSaves(10000);
}

void Chat::saveSoon()
{
    if (m_chatsDirty)
        return;
    m_chatsDirty = true;
    QTimer::singleShot(400, this, [this] {
        if (m_chatsDirty && m_ready)
            save();
    });
}

void Chat::noteChanged()
{
    if (m_netDepth > 0)
        changedSoon();
    else
        Q_EMIT changed();
}

void Chat::changedSoon()
{
    if (m_changedSoon)
        return;
    m_changedSoon = true;
    QTimer::singleShot(250, this, [this] {
        m_changedSoon = false;
        Q_EMIT changed();
    });
}

void Chat::setRelaxed(bool relaxed)
{
    if (relaxed == m_relaxed)
        return;
    m_relaxed = relaxed;
    retimeRetry();
}

void Chat::retimeRetry()
{
    const int ms = m_front && !m_relaxed ? 1000 : 3000;
    if (m_retry.interval() != ms)
        m_retry.setInterval(ms);
}

void Chat::saveGroupsSoon()
{
    if (m_groupsDirty)
        return;
    m_groupsDirty = true;
    QTimer::singleShot(300, this, [this] {
        if (m_groupsDirty)
            saveGroups();
    });
}

bool Chat::saveGroups()
{
    m_groupsDirty = false;
    if (!m_ready)
        return false;
    QJsonObject gs;
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it) {
        QJsonArray history, held;
        for (const QJsonObject &s : it->history)
            history << s;
        for (const QJsonObject &h : it->held)
            held << h;
        QJsonObject unread;
        for (auto u = it->unread.cbegin(); u != it->unread.cend(); ++u)
            unread.insert(u.key(), u.value());
        gs.insert(it.key(), QJsonObject{{QStringLiteral("state"), it->state}, {QStringLiteral("history"), history}, {QStringLiteral("held"), held},
                                        {QStringLiteral("unread"), unread}, {QStringLiteral("lastOp"), it->lastOp}, {QStringLiteral("removed"), it->removed},
                                        {QStringLiteral("left"), it->left}, {QStringLiteral("since"), it->since}});
    }
    auto maps = [](const QHash<QString, QVariantMap> &h) {
        QJsonObject o;
        for (auto it = h.cbegin(); it != h.cend(); ++it)
            o.insert(it.key(), QJsonObject::fromVariantMap(it.value()));
        return o;
    };
    // Spent invites and codes go.
    for (const QString &t : m_gtokens.keys())
        if (m_gtokens.value(t).value(QStringLiteral("x")).toLongLong() < now())
            m_gtokens.remove(t);
    QJsonObject out;
    for (auto it = m_gout.cbegin(); it != m_gout.cend(); ++it)
        out.insert(it.key(), QJsonObject{{QStringLiteral("g"), it->g}, {QStringLiteral("to"), it->to}, {QStringLiteral("l"), it->logical},
                                         {QStringLiteral("room"), it->room}, {QStringLiteral("time"), it->time}, {QStringLiteral("count"), it->count}});
    const QJsonObject root{{QStringLiteral("groups"), gs},         {QStringLiteral("peers"), maps(m_gpeers)}, {QStringLiteral("invites"), maps(m_ginvites)},
                           {QStringLiteral("joining"), maps(m_joining)}, {QStringLiteral("tokens"), maps(m_gtokens)}, {QStringLiteral("out"), out}};
    const bool ok = m_vault->write(QStringLiteral("groups.json"), compact(root));
    m_saveFailed = !ok;
    if (!ok) {
        // Still waiting to be saved: tried again shortly (and before Huginn closes).
        m_groupsDirty = true;
        saveDidntGo(QStringLiteral("groups.json"), [this] { if (m_groupsDirty) saveGroups(); });
    }
    return ok;
}

void Chat::saveGroupChatsSoon()
{
    if (m_gchatsDirty)
        return;
    m_gchatsDirty = true;
    QTimer::singleShot(400, this, [this] {
        if (m_gchatsDirty)
            saveGroupChats();
    });
}

void Chat::saveGroupChats()
{
    m_gchatsDirty = false;
    if (!m_ready)
        return;
    // Copies of each group's messages: made into JSON, sealed and written on the saving thread.
    QHash<QString, QVariantList> all;
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it)
        all.insert(it.key(), it->messages);
    m_vault->writeLater(QStringLiteral("gchats.json"), [all] {
        QJsonObject o;
        for (auto it = all.cbegin(); it != all.cend(); ++it)
            o.insert(it.key(), QJsonArray::fromVariantList(it.value()));
        return compact(o);
    });
}

void Chat::loadGroups()
{
    const QJsonObject root = parse(m_vault->read(QStringLiteral("groups.json")));
    const QJsonObject chats = parse(m_vault->read(QStringLiteral("gchats.json")));
    const QJsonObject gs = root.value(QStringLiteral("groups")).toObject();
    for (auto it = gs.begin(); it != gs.end(); ++it) {
        const QJsonObject e = it.value().toObject();
        Group gr;
        gr.state = e.value(QStringLiteral("state")).toObject();
        for (const QJsonValue &v : e.value(QStringLiteral("history")).toArray())
            gr.history << v.toObject();
        for (const QJsonValue &v : e.value(QStringLiteral("held")).toArray())
            gr.held << v.toObject();
        const QJsonObject unread = e.value(QStringLiteral("unread")).toObject();
        for (auto u = unread.begin(); u != unread.end(); ++u)
            gr.unread.insert(u.key(), u.value().toInt());
        gr.lastOp = e.value(QStringLiteral("lastOp")).toObject();
        gr.removed = e.value(QStringLiteral("removed")).toBool();
        gr.left = e.value(QStringLiteral("left")).toBool();
        gr.since = e.value(QStringLiteral("since")).toInteger();
        gr.messages = chats.value(it.key()).toArray().toVariantList();
        for (const QVariant &v : std::as_const(gr.messages)) {
            const QVariantMap m = v.toMap();
            if (!m.value(QStringLiteral("system")).toBool())
                m_gidx.insert(str(m, "id"), it.key());
        }
        m_groups.insert(it.key(), gr);
    }
    auto maps = [](const QJsonObject &o) {
        QHash<QString, QVariantMap> h;
        for (auto it = o.begin(); it != o.end(); ++it)
            h.insert(it.key(), it.value().toObject().toVariantMap());
        return h;
    };
    m_gpeers = maps(root.value(QStringLiteral("peers")).toObject());
    m_ginvites = maps(root.value(QStringLiteral("invites")).toObject());
    m_joining = maps(root.value(QStringLiteral("joining")).toObject());
    m_gtokens = maps(root.value(QStringLiteral("tokens")).toObject());
    const QJsonObject out = root.value(QStringLiteral("out")).toObject();
    const qint64 t0 = now();
    for (auto it = out.begin(); it != out.end(); ++it) {
        const QJsonObject e = it.value().toObject();
        GroupOut o;
        o.g = e.value(QStringLiteral("g")).toString();
        o.to = e.value(QStringLiteral("to")).toString();
        o.logical = e.value(QStringLiteral("l")).toString();
        o.room = e.value(QStringLiteral("room")).toString();
        o.time = e.value(QStringLiteral("time")).toInteger();
        o.count = e.value(QStringLiteral("count")).toInt();
        o.next = t0 + kRetry[0];   // after a restart: wait one round
        if (!o.to.isEmpty() && o.time > 0)
            m_gout.insert(it.key(), o);
    }
}

void Chat::pruneGroups(const std::function<qint64(int)> &cutoff, QStringList *gone)
{
    bool any = false;
    for (auto it = m_groups.begin(); it != m_groups.end(); ++it) {
        const qint64 c = cutoff(Limits::groupDays(it->state));
        QVariantList kept;
        for (const QVariant &v : std::as_const(it->messages)) {
            const QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("time")).toLongLong() >= c) {
                kept << v;
                continue;
            }
            any = true;
            m_gidx.remove(str(m, "id"));
            gone->append(str(m, "id"));
            if (!str(m, "photo").isEmpty())
                m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        }
        it->messages = kept;
    }
    // Their copies still on the way (kept per member under envelope ids) aren't sent again either.
    const QSet<QString> goneIds(gone->cbegin(), gone->cend());
    bool outs = false;
    for (auto o = m_gout.begin(); o != m_gout.end();) {
        if (goneIds.contains(o->logical)) {
            dropSealed(o.key());
            o = m_gout.erase(o);
            outs = true;
        } else {
            ++o;
        }
    }
    if (outs)
        saveGroups();
    if (any)
        saveGroupChats();
}

// ------------------------------------------------------------------ voice rooms

QJsonObject Chat::groupState(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->removed || it->left)
        return QJsonObject();
    return it->state;
}

bool Chat::canRoom(const QString &key) const
{
    if (key.isEmpty() || isBlocked(key) || isTrashed(key) || (!isContact(key) && !isGroupPeer(key)))
        return false;
    // Not known yet (a group peer that hasn't sent anything since): try; an app without v1 drops it quietly.
    return !capsKnown(key) || m_caps.value(key).contains(QStringLiteral("v1"));
}

bool Chat::lacksRooms(const QString &key) const { return capsKnown(key) && !m_caps.value(key).contains(QStringLiteral("v1")); }
