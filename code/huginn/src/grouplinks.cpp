// Group invite links (cap j1, shared/docs/protocol.md section 17), the same as Muninn's Groups.kt.
// A link is a group code after "#" on the add page; the admin it names counts its uses and is the
// only one that lets people in by it, so the count never goes over. Kept in the vault (grouplinks.json).

#include "chat.h"
#include "groupwire.h"
#include "msglimits.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>

namespace {
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
const QString kFile = QStringLiteral("grouplinks.json");
}

bool Chat::hasJ1(const QString &key) const
{
    // Someone joining by a link isn't kept here yet: what the envelope being handled said counts.
    return m_caps.value(key).contains(QStringLiteral("j1")) || (key == m_openedFrom && m_openedCaps.contains(QStringLiteral("j1")));
}

void Chat::loadLinks()
{
    const QJsonObject o = QJsonDocument::fromJson(m_vault->read(kFile)).object();
    const QJsonObject counted = o.value(QStringLiteral("counted")).toObject();
    for (auto it = counted.begin(); it != counted.end(); ++it)
        m_glinks.insert(it.key(), it.value().toObject().toVariantMap());
    const QJsonObject lists = o.value(QStringLiteral("lists")).toObject();
    for (auto it = lists.begin(); it != lists.end(); ++it)
        m_glinkLists.insert(it.key(), it.value().toArray().toVariantList());
    const QJsonObject made = o.value(QStringLiteral("made")).toObject();
    for (auto it = made.begin(); it != made.end(); ++it)
        m_gmade.insert(it.key(), it.value().toString());
}

void Chat::saveLinks()
{
    const qint64 t = nowMs();
    // Links done for a day (expired, used up or revoked) are forgotten.
    for (const QString &k : m_glinks.keys()) {
        const QVariantMap l = m_glinks.value(k);
        const qint64 x = l.value(QStringLiteral("x")).toLongLong();
        const int max = l.value(QStringLiteral("max")).toInt();
        if (l.value(QStringLiteral("off")).toBool() || (x != 0 && x < t - 86400000) || (max != 0 && l.value(QStringLiteral("uses")).toInt() >= max))
            m_glinks.remove(k);
    }
    QJsonObject counted, lists, made;
    for (auto it = m_glinks.cbegin(); it != m_glinks.cend(); ++it)
        counted.insert(it.key(), QJsonObject::fromVariantMap(it.value()));
    for (auto it = m_glinkLists.cbegin(); it != m_glinkLists.cend(); ++it)
        lists.insert(it.key(), QJsonArray::fromVariantList(it.value()));
    for (auto it = m_gmade.cbegin(); it != m_gmade.cend(); ++it)
        made.insert(it.key(), it.value());
    m_vault->write(kFile, QJsonDocument(QJsonObject{{QStringLiteral("counted"), counted}, {QStringLiteral("lists"), lists}, {QStringLiteral("made"), made}})
                              .toJson(QJsonDocument::Compact));
    Q_EMIT changed();
}

// A link this computer counts that still lets someone in; its token, or "".
QString Chat::liveLink(const QString &token, const QString &g) const
{
    const QVariantMap l = m_glinks.value(token);
    const auto it = m_groups.constFind(g);
    // A Family group takes nobody by a link (17.1).
    if (l.isEmpty() || str(l, "g") != g || it == m_groups.cend() || it->state.isEmpty() || !Limits::linksAllowed(it->state))
        return QString();
    const QString by = str(l, "by");
    const bool may = GroupWire::isAdmin(it->state, by) || Limits::membersMayInvite(it->state);
    return GroupWire::linkOk(l.value(QStringLiteral("x")).toLongLong(), l.value(QStringLiteral("max")).toInt(), l.value(QStringLiteral("uses")).toInt(),
                             l.value(QStringLiteral("off")).toBool(), GroupWire::isMember(it->state, by), may, nowMs())
               ? token : QString();
}

// The links this computer counts for g, to every admin and each link's maker (their own), with j1.
void Chat::sendLinks(const QString &g)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return;
    const QString me = myKey();
    QList<QVariantMap> mine;
    for (auto l = m_glinks.cbegin(); l != m_glinks.cend(); ++l)
        if (str(l.value(), "g") == g && !liveLink(l.key(), g).isEmpty()) {
            QVariantMap e = l.value();
            e.insert(QStringLiteral("lid"), GroupWire::linkId(l.key()));
            mine << e;
        }
    QVariantList shown;
    for (const QVariant &v : m_glinkLists.value(g))
        if (str(v.toMap(), "counter") != me)
            shown << v;
    for (const QVariantMap &e : std::as_const(mine))
        shown << QVariantMap{{QStringLiteral("id"), str(e, "lid")}, {QStringLiteral("by"), str(e, "by")}, {QStringLiteral("byName"), memberName(g, str(e, "by"))},
                             {QStringLiteral("x"), e.value(QStringLiteral("x"))}, {QStringLiteral("max"), e.value(QStringLiteral("max"))},
                             {QStringLiteral("uses"), e.value(QStringLiteral("uses"))}, {QStringLiteral("counter"), me}};
    m_glinkLists.insert(g, shown);
    QSet<QString> to;
    for (const QString &a : GroupWire::adminsOf(it->state))
        to.insert(a);
    for (const QVariantMap &e : std::as_const(mine))
        to.insert(str(e, "by"));
    for (const QString &k : std::as_const(to)) {
        if (k == me || !GroupWire::isMember(it->state, k) || !hasJ1(k))
            continue;
        QJsonArray list;
        for (const QVariantMap &e : std::as_const(mine))
            if (GroupWire::isAdmin(it->state, k) || str(e, "by") == k)
                list << QJsonObject{{QStringLiteral("lid"), str(e, "lid")}, {QStringLiteral("by"), str(e, "by")}, {QStringLiteral("x"), e.value(QStringLiteral("x")).toLongLong()},
                                    {QStringLiteral("max"), e.value(QStringLiteral("max")).toInt()}, {QStringLiteral("uses"), e.value(QStringLiteral("uses")).toInt()}};
        sendControl(g, k, {{QStringLiteral("t"), QStringLiteral("links")}, {QStringLiteral("list"), list}});
    }
    saveLinks();
}

// Control messages link, linkoff and links (17.2). Returns false when the sender may not send it.
bool Chat::takeLinkControl(const QString &g, const QString &from, const QJsonObject &p)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left || !GroupWire::isMember(it->state, from))
        return false;
    const QJsonObject s = it->state;
    const QString t = p.value(QStringLiteral("t")).toString(), me = myKey();
    if (t == QLatin1String("link")) {
        // A member (or another admin) made a link that names this computer as its counter. A Family group has none.
        if (!GroupWire::isAdmin(s, me) || !(GroupWire::isAdmin(s, from) || Limits::membersMayInvite(s)))
            return false;
        if (!Limits::linksAllowed(s))
            return true;
        const QString inv = p.value(QStringLiteral("inv")).toString();
        const qint64 x = p.value(QStringLiteral("lx")).toInteger(-1);
        const int max = int(p.value(QStringLiteral("max")).toInteger(-1));
        if (QByteArray::fromBase64(inv.toLatin1(), QByteArray::Base64UrlEncoding).size() != 16 || x < 0 || max < 0 || max > GroupWire::kMaxLinkUses)
            return true;
        if (!m_glinks.contains(inv))
            m_glinks.insert(inv, {{QStringLiteral("g"), g}, {QStringLiteral("by"), from}, {QStringLiteral("x"), x}, {QStringLiteral("max"), max},
                                  {QStringLiteral("uses"), 0}, {QStringLiteral("off"), false}});
        sendLinks(g);
    } else if (t == QLatin1String("linkoff")) {
        const QString lid = p.value(QStringLiteral("lid")).toString();
        for (auto l = m_glinks.begin(); l != m_glinks.end(); ++l)
            if (str(l.value(), "g") == g && GroupWire::linkId(l.key()) == lid && (str(l.value(), "by") == from || GroupWire::isAdmin(s, from))) {
                l.value().insert(QStringLiteral("off"), true);
                sendLinks(g);
                break;
            }
    } else if (t == QLatin1String("links")) {
        if (!GroupWire::isAdmin(s, from))
            return false;
        QVariantList shown;
        for (const QVariant &v : m_glinkLists.value(g))
            if (str(v.toMap(), "counter") != from)
                shown << v;
        const QJsonArray list = p.value(QStringLiteral("list")).toArray();
        for (int i = 0; i < qMin(int(list.size()), GroupWire::kMaxLinks); ++i) {
            const QJsonObject e = list.at(i).toObject();
            const QString id = e.value(QStringLiteral("lid")).toString();
            shown << QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("by"), e.value(QStringLiteral("by")).toString()},
                                 {QStringLiteral("byName"), memberName(g, e.value(QStringLiteral("by")).toString())},
                                 {QStringLiteral("x"), e.value(QStringLiteral("x")).toInteger()}, {QStringLiteral("max"), e.value(QStringLiteral("max")).toInt()},
                                 {QStringLiteral("uses"), e.value(QStringLiteral("uses")).toInt()}, {QStringLiteral("counter"), from}};
        }
        m_glinkLists.insert(g, shown);
        saveLinks();
    }
    return true;
}

// One more use of a link, counted only here (this computer is its only counter).
void Chat::linkUsed(const QString &token, const QString &g)
{
    if (!m_glinks.contains(token))
        return;
    m_glinks[token].insert(QStringLiteral("uses"), m_glinks.value(token).value(QStringLiteral("uses")).toInt() + 1);
    sendLinks(g);
}

QVariantMap Chat::createGroupLink(const QString &g, qint64 ttlMs, int maxUses)
{
    auto fail = [](const QString &why) { return QVariantMap{{QStringLiteral("error"), why}}; };
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return fail(QStringLiteral("That group isn't here anymore."));
    if (it->removed || it->left)
        return fail(QStringLiteral("You're no longer in this group."));
    const QJsonObject s = it->state;
    const QString me = myKey();
    if (!Limits::linksAllowed(s))
        return fail(QStringLiteral("Family groups have no links. Admins add people with a code."));
    if (!GroupWire::isAdmin(s, me) && !Limits::membersMayInvite(s))
        return fail(QStringLiteral("Only admins can make invite links in %1.").arg(groupName(g)));
    if (ttlMs < 0 || maxUses < 0 || maxUses > GroupWire::kMaxLinkUses)
        return fail(QStringLiteral("Pick how long and how many people."));
    // An admin's link is counted here; a member's by the owner's device.
    const QString counter = GroupWire::isAdmin(s, me) ? me : s.value(QStringLiteral("owner")).toString();
    if (counter != me && !hasJ1(counter))
        return fail(QStringLiteral("%1 needs an update first.").arg(memberName(g, counter)));
    const QString token = GroupWire::newToken();
    const qint64 x = ttlMs == 0 ? 0 : nowMs() + ttlMs;
    const qint64 codeX = x == 0 ? (nowMs() / 1000 + GroupWire::kNeverS) * 1000 : x;
    const QJsonObject cm = GroupWire::memberEntry(s, counter);
    const QString link = QLatin1String(GroupWire::kAddPage)
        + GroupWire::code(g, groupName(g), counter, cm.value(QStringLiteral("n")).toString(), cm.value(QStringLiteral("p")).toString(), token, codeX);
    const QString id = GroupWire::linkId(token);
    m_gmade.insert(id, link);
    if (counter == me) {
        m_glinks.insert(token, {{QStringLiteral("g"), g}, {QStringLiteral("by"), me}, {QStringLiteral("x"), x}, {QStringLiteral("max"), maxUses},
                                {QStringLiteral("uses"), 0}, {QStringLiteral("off"), false}});
        sendLinks(g);
    } else {
        sendControl(g, counter, {{QStringLiteral("t"), QStringLiteral("link")}, {QStringLiteral("inv"), token}, {QStringLiteral("lx"), x}, {QStringLiteral("max"), maxUses}});
        QVariantList l = m_glinkLists.value(g);
        l << QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("by"), me}, {QStringLiteral("byName"), memberName(g, me)}, {QStringLiteral("x"), x},
                         {QStringLiteral("max"), maxUses}, {QStringLiteral("uses"), 0}, {QStringLiteral("counter"), counter}};
        m_glinkLists.insert(g, l);
        saveLinks();
    }
    return {{QStringLiteral("link"), link}, {QStringLiteral("id"), id}};
}

QVariantList Chat::groupLinks(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    const bool admin = it != m_groups.cend() && GroupWire::isAdmin(it->state, myKey());
    QVariantList out;
    for (const QVariant &v : m_glinkLists.value(g)) {
        QVariantMap e = v.toMap();
        const qint64 x = e.value(QStringLiteral("x")).toLongLong();
        const int max = e.value(QStringLiteral("max")).toInt();
        if ((x != 0 && x <= nowMs()) || (max != 0 && e.value(QStringLiteral("uses")).toInt() >= max))
            continue;
        if (!admin && str(e, "by") != myKey())
            continue;
        e.insert(QStringLiteral("link"), m_gmade.value(str(e, "id")));
        e.insert(QStringLiteral("mine"), str(e, "by") == myKey());
        out << e;
    }
    return out;
}

QString Chat::revokeGroupLink(const QString &g, const QString &id)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return QStringLiteral("That group isn't here anymore.");
    QVariantMap info;
    for (const QVariant &v : m_glinkLists.value(g))
        if (str(v.toMap(), "id") == id)
            info = v.toMap();
    if (info.isEmpty())
        return QString();
    const QString me = myKey();
    if (str(info, "by") != me && !GroupWire::isAdmin(it->state, me))
        return QStringLiteral("Only admins can stop someone else's link.");
    if (str(info, "counter") == me) {
        for (auto l = m_glinks.begin(); l != m_glinks.end(); ++l)
            if (str(l.value(), "g") == g && GroupWire::linkId(l.key()) == id)
                l.value().insert(QStringLiteral("off"), true);
        sendLinks(g);
    } else {
        sendControl(g, str(info, "counter"), {{QStringLiteral("t"), QStringLiteral("linkoff")}, {QStringLiteral("lid"), id}});
        QVariantList kept;
        for (const QVariant &v : m_glinkLists.value(g))
            if (str(v.toMap(), "id") != id)
                kept << v;
        m_glinkLists.insert(g, kept);
        saveLinks();
    }
    return QString();
}

QString Chat::revokeGroupCodes(const QString &g)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return QStringLiteral("That group isn't here anymore.");
    const bool admin = GroupWire::isAdmin(it->state, myKey());
    // Group codes are the tokens made for no one in particular; a contact's own invite stays.
    for (const QString &t : m_gtokens.keys())
        if (str(m_gtokens.value(t), "g") == g && !m_gtokens.value(t).contains(QStringLiteral("for")))
            m_gtokens.remove(t);
    saveGroups();
    const QVariantList links = groupLinks(g);
    for (const QVariant &v : links) {
        const QVariantMap l = v.toMap();
        if (admin || l.value(QStringLiteral("mine")).toBool())
            revokeGroupLink(g, str(l, "id"));
    }
    Q_EMIT changed();
    return QString();
}

QString Chat::setMembersInvite(const QString &g, bool on)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("invites")}, {QStringLiteral("on"), on}});
}

bool Chat::membersInvite(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    return it != m_groups.cend() && Limits::membersMayInvite(it->state);
}

// A group that became Family: every link this computer counts for it stops, and none are shown (17.1).
void Chat::familyLinksOff(const QString &g)
{
    bool any = !m_glinkLists.value(g).isEmpty();
    for (auto l = m_glinks.begin(); l != m_glinks.end(); ++l)
        if (str(l.value(), "g") == g && !l.value().value(QStringLiteral("off")).toBool()) {
            l.value().insert(QStringLiteral("off"), true);
            any = true;
        }
    if (!any)
        return;
    m_glinkLists.remove(g);
    sendLinks(g);
}
