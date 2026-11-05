// Chat's side of file sharing (cap f2, shared/docs/protocol.md section 27): "Always from this person" and a group's
// file rules, which every member's app enforces on the files it receives.
#include "chat.h"
#include "filelink.h"
#include "groupwire.h"
#include "msglimits.h"
#include "sharing.h"

#include <QJsonArray>

bool Chat::filesAlways(const QString &key) const { return m_contacts.value(key).value(QStringLiteral("filesAlways")).toBool(); }

void Chat::setFilesAlways(const QString &key, bool on)
{
    if (!m_contacts.contains(key) || filesAlways(key) == on)
        return;
    if (on)
        m_contacts[key].insert(QStringLiteral("filesAlways"), true);
    else
        m_contacts[key].remove(QStringLiteral("filesAlways"));
    saveContacts();
    Q_EMIT changed();
}

QVariantMap Chat::groupFiles(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return {};
    const QJsonObject s = it->state;
    return {{QStringLiteral("who"), Sharing::whoMaySend(s)}, {QStringLiteral("mb"), Sharing::limitMb(s)},
            {QStringLiteral("line"), Sharing::rulesLine(s)},
            {QStringLiteral("canChange"), !it->removed && !it->left && GroupWire::isAdmin(s, myKey())}};
}

QString Chat::setGroupFiles(const QString &g, const QString &who, int mb)
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return QStringLiteral("That group isn't here anymore.");
    if (it->removed || it->left)
        return QStringLiteral("You're no longer in this group.");
    const QJsonObject cur = it->state;
    if (!GroupWire::isAdmin(cur, myKey()))
        return QStringLiteral("Only the group's admins can do that.");
    const QJsonObject next = Sharing::withRules(nextState(g), who, mb);
    if (Sharing::whoMaySend(next) == Sharing::whoMaySend(cur) && Sharing::limitMb(next) == Sharing::limitMb(cur))
        return QString();
    // An app without f2 refuses a state with "fs" or "fm" (3.2), so everyone needs f2 first.
    if (Sharing::hasRules(next)) {
        QStringList old;
        for (const QJsonValue &v : cur.value(QStringLiteral("members")).toArray()) {
            const QJsonObject m = v.toObject();
            const QString k = m.value(QStringLiteral("k")).toString();
            if (k != myKey() && !hasCap(k, QLatin1String(Sharing::kCap)))
                old << m.value(QStringLiteral("n")).toString();
        }
        if (!old.isEmpty())
            return QStringLiteral("Everyone needs an update first. Still waiting for: %1.").arg(old.join(QStringLiteral(", ")));
    }
    return commit(g, next, QJsonObject{{QStringLiteral("op"), QStringLiteral("files")}, {QStringLiteral("fs"), Sharing::whoMaySend(next)},
                                       {QStringLiteral("fm"), Sharing::limitMb(next)}});
}

QString Chat::groupFileRefusal(const QString &g, const QString &from, qint64 size) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return QStringLiteral("Refused: you aren't in that group.");
    return Sharing::refusal(it->state, from, size);
}

bool Chat::groupFilesArrive(const QString &g, qint64 size) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty())
        return false;
    return Limits::filesArrive(Limits::groupType(it->state), size, FileLink::kAutoBytes);
}

QStringList Chat::groupFileMembers(const QString &g) const
{
    const auto it = m_groups.constFind(g);
    if (it == m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return {};
    QStringList out = GroupWire::keysOf(it->state);
    out.removeAll(myKey());
    return out;
}
