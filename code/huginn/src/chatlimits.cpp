// Message limits (cap d1, shared/docs/protocol.md section 16), the Chat side: every chat deletes messages
// older than its limit, with their photos and voice; a Family group's limit rides in its signed
// state, each side of a 1:1 chat says its own in "days" and the shorter one counts. Limits has the rules.
#include "chat.h"
#include "groupwire.h"
#include "link.h"
#include "msglimits.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTimer>
#include <QUuid>

namespace
{
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
}

bool Chat::hasD1(const QString &key) const
{
    // Someone joining by code isn't kept here yet: what the envelope being handled said counts.
    return m_caps.value(key).contains(QLatin1String(Limits::kCap)) || (key == m_openedFrom && m_openedCaps.contains(QLatin1String(Limits::kCap)));
}

bool Chat::isClose(const QString &key) const
{
    for (const QVariantMap &f : m_folders) {
        const QString n = str(f, "name");
        if ((n == QLatin1String("Family") || n == QLatin1String("Work")) && f.value(QStringLiteral("keys")).toStringList().contains(key))
            return true;
    }
    return false;
}

int Chat::myDays(const QString &key) const { return Limits::myDays(m_contacts.value(key).value(QStringLiteral("keep")).toInt(), isClose(key)); }

int Chat::chatDays(const QString &key) const
{
    return Limits::pairDays(myDays(key), hasD1(key), m_contacts.value(key).value(QStringLiteral("days")).toInt());
}

int Chat::groupDays(const QString &g) const { return Limits::groupDays(m_groups.value(g).state); }

QVariantMap Chat::limitInfo(const QString &key) const
{
    const int theirs = m_contacts.value(key).value(QStringLiteral("days")).toInt();
    return {{QStringLiteral("days"), chatDays(key)},
            {QStringLiteral("mine"), myDays(key)},
            {QStringLiteral("theirs"), theirs > 0 ? theirs : Limits::kDefault},
            {QStringLiteral("close"), isClose(key)},
            {QStringLiteral("d1"), hasD1(key)},
            // Their app said what it speaks and d1 isn't in it: it keeps messages.
            {QStringLiteral("older"), capsKnown(key) && !hasD1(key)},
            {QStringLiteral("choices"), QVariantList{7, 14, 30}}};
}

void Chat::setKeep(const QString &key, int days)
{
    if (!isContact(key))
        return;
    m_contacts[key].insert(QStringLiteral("keep"), qBound(Limits::kDefault, days, Limits::kLongest));
    saveContacts();
    prune();
    Q_EMIT changed();
    // An empty receipt carries my new "days" to them at once.
    if (hasD1(key)) {
        const QJsonObject r{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}};
        deliver(key, envelope(QStringLiteral("receipt"), key, QJsonDocument(r).toJson(QJsonDocument::Compact), QUuid::createUuid().toString(QUuid::WithoutBraces)),
                mailboxOf(key));
    }
}

void Chat::noteDays(const QString &key, int days)
{
    // From authentic envelopes of an app with d1 only; anything unusable (0) leaves the old value.
    if (days <= 0 || !isContact(key) || !hasD1(key) || m_contacts.value(key).value(QStringLiteral("days")).toInt() == days)
        return;
    m_contacts[key].insert(QStringLiteral("days"), days);
    saveContacts();
    QTimer::singleShot(0, this, [this] { prune(); });
}

QVariantMap Chat::groupLimit(const QString &g) const
{
    const QJsonObject s = m_groups.value(g).state;
    const QString kind = s.value(QStringLiteral("kind")).toString();
    QStringList older;
    for (const QString &k : GroupWire::keysOf(s))
        if (k != myKey() && capsKnown(k) && !hasD1(k))
            older << memberName(g, k);
    const QString type = Limits::groupType(s);
    // Family: each member's safety code is checked once ("Checked" in the member list).
    QStringList unchecked;
    if (type == QLatin1String(Limits::kFamily))
        for (const QString &k : GroupWire::keysOf(s))
            if (k != myKey() && !isVerified(k))
                unchecked << k;
    return {{QStringLiteral("kind"), kind},
            {QStringLiteral("kindName"), Limits::kindName(kind)},
            {QStringLiteral("type"), type},
            {QStringLiteral("typeName"), Limits::typeName(type)},
            {QStringLiteral("line"), Limits::typeLine(type)},
            {QStringLiteral("links"), Limits::linksAllowed(s)},
            {QStringLiteral("unchecked"), unchecked},
            {QStringLiteral("days"), Limits::groupDays(s)},
            {QStringLiteral("older"), older},
            {QStringLiteral("choices"), QVariantList{7, 14, 30}}};
}

QVariantList Chat::groupTypes() const
{
    QVariantList out;
    for (const char *t : {Limits::kFolk, Limits::kFamily}) {
        const QString type = QString::fromLatin1(t);
        out << QVariantMap{{QStringLiteral("type"), type}, {QStringLiteral("name"), Limits::typeName(type)},
                           {QStringLiteral("line"), Limits::typeLine(type)}, {QStringLiteral("rules"), Limits::typeRules(type)}};
    }
    return out;
}

bool Chat::inFamilyGroup(const QString &key) const
{
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it)
        if (!it->state.isEmpty() && !it->removed && !it->left && Limits::groupType(it->state) == QLatin1String(Limits::kFamily)
            && GroupWire::isMember(it->state, key))
            return true;
    return false;
}

void Chat::familySync()
{
    // Members of my Family groups who are contacts go in my Family folder, once each: moved out by hand, they stay out
    // (the folder's "auto" list remembers who was put there).
    if (!m_ready)
        return;
    int at = -1;
    for (int i = 0; i < int(m_folders.size()); ++i)
        if (m_folders.at(i).value(QStringLiteral("name")).toString() == QLatin1String("Family"))
            at = i;
    QStringList want;
    for (auto it = m_groups.cbegin(); it != m_groups.cend(); ++it) {
        if (it->state.isEmpty() || it->removed || it->left || Limits::groupType(it->state) != QLatin1String(Limits::kFamily))
            continue;
        for (const QString &k : GroupWire::keysOf(it->state))
            if (k != myKey() && isContact(k) && !want.contains(k))
                want << k;
    }
    if (want.isEmpty() || at < 0)
        return;   // no Family folder (deleted by hand): nothing is made again
    QStringList done = m_folders.at(at).value(QStringLiteral("auto")).toStringList();
    const QString id = m_folders.at(at).value(QStringLiteral("id")).toString();
    bool any = false;
    for (const QString &k : std::as_const(want))
        if (!done.contains(k)) {
            done << k;
            any = true;
            if (!m_folders.at(at).value(QStringLiteral("keys")).toStringList().contains(k))
                setInFolder(id, k, true);
        }
    if (!any)
        return;
    m_folders[at].insert(QStringLiteral("auto"), done);
    saveFolders();
}

QString Chat::setGroupLimit(const QString &g, const QString &kind, int days)
{
    return change(g, {{QStringLiteral("op"), QStringLiteral("limit")}, {QStringLiteral("kind"), kind}, {QStringLiteral("days"), days}});
}

QString Chat::limitBlocked(const QString &g, const QString &kind) const
{
    // An app without d1 refuses a Family or Work group's state, so everyone needs it first.
    const QJsonObject s = m_groups.value(g).state;
    if (kind.isEmpty() || s.contains(QStringLiteral("kind")))
        return QString();
    QStringList older;
    for (const QString &k : GroupWire::keysOf(s))
        if (k != myKey() && !hasD1(k))
            older << memberName(g, k);
    if (older.isEmpty())
        return QString();
    return QStringLiteral("Everyone needs an update first. Still waiting for: %1.").arg(older.join(QStringLiteral(", ")));
}

bool Chat::limitsNote() const { return m_settings.value(QStringLiteral("chat/limitsNote"), false).toBool(); }

void Chat::dismissLimitsNote()
{
    m_settings.setValue(QStringLiteral("chat/limitsNote"), false);
    Q_EMIT changed();
}

void Chat::noteLimitsOnce()
{
    // Someone who had chats before message limits were set is told once, on the chat list.
    if (m_settings.contains(QStringLiteral("chat/limitsNote")))
        return;
    bool any = !m_groups.isEmpty();
    for (auto it = m_private.cbegin(); !any && it != m_private.cend(); ++it)
        any = !it.value().isEmpty();
    m_settings.setValue(QStringLiteral("chat/limitsNote"), any);
}

qint64 Chat::keepCutoff(const QString &key) const
{
    const qint64 t = now();
    const qint64 age = deleteAfter();
    const bool sooner = age > 0 && age < Limits::kDefault * Limits::kDay;
    return qMax(Limits::cutoff(t, chatDays(key)), sooner ? t - age : qint64(0));
}

void Chat::pruneToLimits()
{
    const qint64 t = now();
    // "Delete messages after: A day" only makes it sooner.
    const qint64 age = deleteAfter();
    const bool sooner = age > 0 && age < Limits::kDefault * Limits::kDay;
    const auto cutoff = [&](int days) { return qMax(Limits::cutoff(t, days), sooner ? t - age : qint64(0)); };
    QStringList gone;
    bool changedAny = false;
    for (auto it = m_private.begin(); it != m_private.end(); ++it) {
        const qint64 c = cutoff(chatDays(it.key()));
        bool due = false;
        for (const QVariant &v : std::as_const(it.value()))
            if (v.toMap().value(QStringLiteral("time")).toLongLong() < c) {
                due = true;
                break;
            }
        if (!due)
            continue;
        QVariantList kept;
        for (const QVariant &v : std::as_const(it.value())) {
            const QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("time")).toLongLong() >= c) {
                kept << v;
                continue;
            }
            gone << str(m, "id");
            if (!str(m, "photo").isEmpty())
                m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        }
        it.value() = kept;
        changedAny = true;
    }
    const int before = int(gone.size());
    pruneGroups(cutoff, &gone);
    const bool groupsChanged = gone.size() != before;
    const qint64 near = cutoff(Limits::kDefault);
    QVariantList n;
    for (const QVariant &v : std::as_const(m_nearby))
        if (v.toMap().value(QStringLiteral("time")).toLongLong() >= near)
            n << v;
    changedAny = changedAny || n.size() != m_nearby.size();
    m_nearby = n;
    if (!gone.isEmpty()) {
        // Late copies stay gone; nothing is sent again; pins of what went go too.
        forget(gone);
        for (const QString &id : std::as_const(gone)) {
            dropSealed(id);
            m_tries.remove(id);
        }
        bool pins = false;
        for (auto it = m_pins.begin(); it != m_pins.end(); ++it)
            for (const QString &id : std::as_const(gone))
                pins = it.value().removeAll(id) > 0 || pins;
        if (pins)
            savePins();
    }
    if (changedAny)
        save();
    // A linked phone's chats on this computer (companion) go by the phone's limits too.
    if (m_linked)
        m_linked->prune(t);
    familySync();
    if (changedAny || groupsChanged)
        Q_EMIT changed();
}
