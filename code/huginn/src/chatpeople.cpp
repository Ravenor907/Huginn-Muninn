// What a person's app speaks as the screens see it, what they may do here, and where their chat sits
// (pinned, archived, muted, marked unread). The same as Muninn's Chat.kt, Permissions.kt and ChatFlags.
// Everything here is local to this computer and kept in the vault.

#include "chat.h"
#include "richwire.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QUuid>

namespace {
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
const QString kFlags = QStringLiteral("chatflags.json");
// How a chat notifies, the same words as Muninn's ChatFlags.
const QString kEvery = QStringLiteral("every");
const QString kOne = QStringLiteral("one");
const QString kMentions = QStringLiteral("mentions");
// A key as flags keep it: a contact's, or "g:<group>" for any of a group's channels ("g:<group>:<channel>").
QString flagKey(const QString &key)
{
    return key.startsWith(QLatin1String("g:")) ? QStringLiteral("g:") + key.mid(2).section(QLatin1Char(':'), 0, 0) : key;
}
}

// Tokens that bring others with them (shared/docs/protocol.md 1.1): c2 and v1 mean c1, m2 means m1.
bool Chat::capIn(const QStringList &caps, const QString &cap)
{
    if (caps.contains(cap))
        return true;
    if (cap == QLatin1String("c1"))
        return caps.contains(QStringLiteral("c2")) || caps.contains(QStringLiteral("v1"));
    if (cap == QLatin1String("m1"))
        return caps.contains(QStringLiteral("m2"));
    return false;
}

// A call signal that would ring here: a 1:1 offer outside a voice room (5.2), or a group call's ring (6.5).
bool Chat::isRing(const QJsonObject &msg)
{
    const QString t = msg.value(QStringLiteral("t")).toString();
    return (t == QLatin1String("offer") && !msg.contains(QStringLiteral("g"))) || t == QLatin1String("vring");
}

// Just added (a code, an introduction, a Family offer, Keep in touch, an invite link): a small hello that
// carries my caps and asks for theirs (the 1.2 caps probe), so Call, Video and video messages show at once
// on both sides. Not limited like probeCaps: it goes once per new contact.
void Chat::sayHello(const QString &key)
{
    if (!m_ready || key.isEmpty())
        return;
    m_probed.insert(key, nowMs());
    const QJsonObject r{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}, {QStringLiteral("q"), QStringLiteral("caps")}};
    deliver(key, envelope(QStringLiteral("receipt"), key, QJsonDocument(r).toJson(QJsonDocument::Compact), QUuid::createUuid().toString(QUuid::WithoutBraces)),
            mailboxOf(key));
}

// For the screens: unknown caps count as a current app, and a probe asks. The wire still only sends a
// room to an app whose caps list it.
bool Chat::mayHave(const QString &key, const QString &cap)
{
    if (!capsKnown(key)) {
        probeCaps(key);
        return true;
    }
    return capIn(m_caps.value(key), cap);
}

// ------------------------------------------------------------------ per person (the press-and-hold sheet)

bool Chat::callsAllowed(const QString &key) const { return !m_contacts.value(key).value(QStringLiteral("noCalls")).toBool(); }
bool Chat::filesAllowed(const QString &key) const { return !m_contacts.value(key).value(QStringLiteral("noFiles")).toBool(); }
bool Chat::showsActive(const QString &key) const { return !m_contacts.value(key).value(QStringLiteral("hideActive")).toBool(); }

void Chat::setAllowed(const QString &key, const QString &what, bool on)
{
    if (!m_contacts.contains(key))
        return;
    if (on)
        m_contacts[key].remove(what);
    else
        m_contacts[key].insert(what, true);
    saveContacts();
    Q_EMIT changed();
}

void Chat::setCallsAllowed(const QString &key, bool on) { setAllowed(key, QStringLiteral("noCalls"), on); }
void Chat::setFilesAllowed(const QString &key, bool on) { setAllowed(key, QStringLiteral("noFiles"), on); }

void Chat::setShowsActive(const QString &key, bool on)
{
    setAllowed(key, QStringLiteral("hideActive"), on);
    // Hidden now: my green dot on their side goes at once.
    if (!on && activeStatus() && hasM1(key))
        sendLive(key, RichWire::liveMsg(QStringLiteral("active"), false, nowMs()));
}

// ------------------------------------------------------------------ pinned, archived, muted, marked unread

void Chat::loadFlags()
{
    const QJsonObject o = QJsonDocument::fromJson(m_vault->read(kFlags)).object();
    m_pinnedChats.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("pinned")).toArray())
        if (!m_pinnedChats.contains(v.toString()))
            m_pinnedChats << v.toString();
    m_archived.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("archived")).toArray())
        m_archived.insert(v.toString());
    m_markedUnread.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("unread")).toArray())
        m_markedUnread.insert(v.toString());
    m_mutedUntil.clear();
    const QJsonObject mu = o.value(QStringLiteral("muted")).toObject();
    for (auto it = mu.begin(); it != mu.end(); ++it)
        m_mutedUntil.insert(it.key(), it.value().toInteger());
    m_rightAway.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("rightAway")).toArray())
        m_rightAway.insert(v.toString());
    m_chatColors.clear();
    const QJsonObject co = o.value(QStringLiteral("colors")).toObject();
    for (auto it = co.begin(); it != co.end(); ++it)
        m_chatColors.insert(it.key(), it.value().toString());
    m_hiddenNearby.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("hidden")).toArray())
        m_hiddenNearby.insert(v.toString());
    m_clearedChats.clear();
    for (const QJsonValue &v : o.value(QStringLiteral("cleared")).toArray())
        m_clearedChats.insert(v.toString());
    m_notifyMode.clear();
    const QJsonObject nm = o.value(QStringLiteral("notify")).toObject();
    for (auto it = nm.begin(); it != nm.end(); ++it) {
        const QString mode = it.value().toString();
        if (mode == kEvery || mode == kOne || mode == kMentions)
            m_notifyMode.insert(it.key(), mode);
    }
}

void Chat::saveFlags()
{
    QJsonObject mu, co, nm;
    for (auto it = m_mutedUntil.cbegin(); it != m_mutedUntil.cend(); ++it)
        mu.insert(it.key(), it.value());
    for (auto it = m_chatColors.cbegin(); it != m_chatColors.cend(); ++it)
        co.insert(it.key(), it.value());
    for (auto it = m_notifyMode.cbegin(); it != m_notifyMode.cend(); ++it)
        nm.insert(it.key(), it.value());
    const QJsonObject o{{QStringLiteral("pinned"), QJsonArray::fromStringList(m_pinnedChats)},
                        {QStringLiteral("archived"), QJsonArray::fromStringList(QStringList(m_archived.cbegin(), m_archived.cend()))},
                        {QStringLiteral("unread"), QJsonArray::fromStringList(QStringList(m_markedUnread.cbegin(), m_markedUnread.cend()))},
                        {QStringLiteral("muted"), mu},
                        {QStringLiteral("rightAway"), QJsonArray::fromStringList(QStringList(m_rightAway.cbegin(), m_rightAway.cend()))},
                        {QStringLiteral("colors"), co},
                        {QStringLiteral("hidden"), QJsonArray::fromStringList(QStringList(m_hiddenNearby.cbegin(), m_hiddenNearby.cend()))},
                        {QStringLiteral("cleared"), QJsonArray::fromStringList(QStringList(m_clearedChats.cbegin(), m_clearedChats.cend()))},
                        {QStringLiteral("notify"), nm}};
    m_vault->write(kFlags, QJsonDocument(o).toJson(QJsonDocument::Compact));
    Q_EMIT changed();
}

void Chat::pinChat(const QString &key, bool on)
{
    m_pinnedChats.removeAll(key);
    if (on)
        m_pinnedChats.prepend(key);
    saveFlags();
}

void Chat::archiveChat(const QString &key, bool on)
{
    if (on)
        m_archived.insert(key);
    else
        m_archived.remove(key);
    saveFlags();
}

void Chat::muteChat(const QString &key, qint64 until)
{
    if (until <= 0)
        m_mutedUntil.remove(key);
    else
        m_mutedUntil.insert(key, until);
    saveFlags();
}

bool Chat::isMuted(const QString &key) const { return m_mutedUntil.value(key, 0) > nowMs(); }

void Chat::markUnread(const QString &key, bool on)
{
    if (m_markedUnread.contains(key) == on)
        return;
    if (on)
        m_markedUnread.insert(key);
    else
        m_markedUnread.remove(key);
    saveFlags();
}

void Chat::setRightAway(const QString &key, bool on)
{
    if (m_rightAway.contains(key) == on)
        return;
    if (on)
        m_rightAway.insert(key);
    else
        m_rightAway.remove(key);
    saveFlags();
}

void Chat::setChatColor(const QString &key, const QString &color)
{
    // "" or a "#RRGGBB" color; anything else is ignored.
    static const QRegularExpression hex(QStringLiteral("^#[0-9A-Fa-f]{6}$"));
    if (color.isEmpty())
        m_chatColors.remove(key);
    else if (hex.match(color).hasMatch())
        m_chatColors.insert(key, color);
    else
        return;
    saveFlags();
}

void Chat::forgetFlags(const QString &key)
{
    m_notified.remove(key);
    m_alerted.remove(key);
    if (!m_pinnedChats.contains(key) && !m_archived.contains(key) && !m_markedUnread.contains(key) && !m_mutedUntil.contains(key)
        && !m_rightAway.contains(key) && !m_chatColors.contains(key) && !m_hiddenNearby.contains(key) && !m_clearedChats.contains(key)
        && !m_notifyMode.contains(key))
        return;
    m_hiddenNearby.remove(key);
    m_clearedChats.remove(key);
    m_notifyMode.remove(key);
    m_rightAway.remove(key);
    m_chatColors.remove(key);
    m_pinnedChats.removeAll(key);
    m_archived.remove(key);
    m_markedUnread.remove(key);
    m_mutedUntil.remove(key);
    saveFlags();
}

// A message arrived for key (a contact, or "g:<group>:<channel>"): an archived chat comes back unless it's
// muted. Returns whether to make a sound and a notification: not while muted, not from someone nearby put away with
// Hide, and with Reduce notifications only the first one until the chat is opened (a group set to mentions only: just
// those and replies to me). Calls never come this way, so they still ring.
bool Chat::notifyFor(const QString &key, const QString &name)
{
    const QString k = flagKey(key);
    bool alert = true;
    if (isMuted(k) && !m_rightAway.contains(k)) {
        alert = false;
    } else if (m_hiddenNearby.contains(k) && !isContact(k)) {
        alert = false;
    } else {
        if (m_archived.contains(k))
            archiveChat(k, false);
        const QString mode = notifyMode(k);
        if (mode == kMentions) {
            alert = toMe(key, name);
        } else if (mode == kOne) {
            alert = !m_notified.contains(k);
            m_notified.insert(k);
        }
    }
    m_alerted.insert(key, alert);
    return alert;
}

QString Chat::notifyMode(const QString &key) const
{
    const QString k = flagKey(key);
    const QString own = m_notifyMode.value(k);
    if (own == kMentions && !k.startsWith(QLatin1String("g:")) && !k.startsWith(QLatin1String("mirror:g:")))
        return kEvery;   // only groups have it (this computer's own, and a linked phone's)
    if (!own.isEmpty())
        return own;
    return reduceNotifications() ? kOne : kEvery;
}

void Chat::setNotifyMode(const QString &key, const QString &mode)
{
    const QString k = flagKey(key);
    if (mode == kEvery || mode == kOne || mode == kMentions)
        m_notifyMode.insert(k, mode);
    else
        m_notifyMode.remove(k);
    saveFlags();
}

void Chat::hideNearby(const QString &key)
{
    if (key.isEmpty() || m_hiddenNearby.contains(key))
        return;
    m_hiddenNearby.insert(key);
    m_unread.remove(key);
    saveFlags();
}

// A group message (key "g:<group>:<channel>") that is for me: an @mention (name says so), or the newest message from
// someone else in that channel answers one of mine.
bool Chat::toMe(const QString &key, const QString &name) const
{
    // Cap g3: a report to me as an admin counts as for me, like an @mention (set only while it is being announced, so a
    // member's name can't pass for one).
    if (name.contains(QLatin1String(" mentioned you in ")) || m_reportArriving)
        return true;
    if (!key.startsWith(QLatin1String("g:")))
        return false;
    const QString g = key.mid(2).section(QLatin1Char(':'), 0, 0), ch = key.mid(2).section(QLatin1Char(':'), 1);
    const auto it = m_groups.constFind(g);
    if (it == m_groups.constEnd())
        return false;
    const QVariantList &msgs = it->messages;
    for (qsizetype i = msgs.size() - 1; i >= 0; --i) {
        const QVariantMap m = msgs.at(i).toMap();
        if (m.value(QStringLiteral("mine")).toBool() || m.value(QStringLiteral("system")).toBool() || (!ch.isEmpty() && str(m, "ch") != ch))
            continue;
        const QString re = str(m, "re");
        if (re.isEmpty())
            return false;
        for (const QVariant &v : msgs) {
            const QVariantMap x = v.toMap();
            if (str(x, "id") == re)
                return x.value(QStringLiteral("mine")).toBool();
        }
        return false;
    }
    return false;
}
