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
}

void Chat::saveFlags()
{
    QJsonObject mu, co;
    for (auto it = m_mutedUntil.cbegin(); it != m_mutedUntil.cend(); ++it)
        mu.insert(it.key(), it.value());
    for (auto it = m_chatColors.cbegin(); it != m_chatColors.cend(); ++it)
        co.insert(it.key(), it.value());
    const QJsonObject o{{QStringLiteral("pinned"), QJsonArray::fromStringList(m_pinnedChats)},
                        {QStringLiteral("archived"), QJsonArray::fromStringList(QStringList(m_archived.cbegin(), m_archived.cend()))},
                        {QStringLiteral("unread"), QJsonArray::fromStringList(QStringList(m_markedUnread.cbegin(), m_markedUnread.cend()))},
                        {QStringLiteral("muted"), mu},
                        {QStringLiteral("rightAway"), QJsonArray::fromStringList(QStringList(m_rightAway.cbegin(), m_rightAway.cend()))},
                        {QStringLiteral("colors"), co}};
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
    if (!m_pinnedChats.contains(key) && !m_archived.contains(key) && !m_markedUnread.contains(key) && !m_mutedUntil.contains(key)
        && !m_rightAway.contains(key) && !m_chatColors.contains(key))
        return;
    m_rightAway.remove(key);
    m_chatColors.remove(key);
    m_pinnedChats.removeAll(key);
    m_archived.remove(key);
    m_markedUnread.remove(key);
    m_mutedUntil.remove(key);
    saveFlags();
}

// A message arrived for key (a contact, or "g:<group>:<channel>"): an archived chat comes back unless it's
// muted. Returns whether to make a sound and a notification.
bool Chat::notifyFor(const QString &key)
{
    QString k = key;
    if (k.startsWith(QLatin1String("g:")))
        k = QStringLiteral("g:") + k.mid(2).section(QLatin1Char(':'), 0, 0);
    if (isMuted(k) && !m_rightAway.contains(k))
        return false;
    if (m_archived.contains(k))
        archiveChat(k, false);
    return true;
}
