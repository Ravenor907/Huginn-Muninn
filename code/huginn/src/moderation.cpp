#include "moderation.h"
#include "chat.h"
#include "groupwire.h"
#include "modwire.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocale>
#include <algorithm>

namespace
{
const QString kFile = QStringLiteral("mod.json");
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

Moderation::Moderation(Chat *chat, QObject *parent) : QObject(parent), m_chat(chat)
{
    // The vault opens after start: everything is read on first use once Chat is ready.
    connect(chat, &Chat::changed, this, [this] {
        if (!m_loaded && m_chat->ready()) {
            ensureLoaded();
            bump();
        }
    });
}

void Moderation::bump()
{
    ++m_serial;
    Q_EMIT changed();
}

void Moderation::ensureLoaded() const
{
    if (m_loaded || !m_chat->ready())
        return;
    m_loaded = true;
    m_people.clear();
    m_records.clear();
    const QJsonObject o = QJsonDocument::fromJson(m_chat->m_vault->read(kFile)).object();
    const QJsonObject people = o.value(QStringLiteral("people")).toObject();
    for (auto it = people.begin(); it != people.end(); ++it) {
        const QJsonObject p = it.value().toObject();
        Person x;
        x.muted = p.value(QStringLiteral("m")).toBool();
        x.show = p.value(QStringLiteral("s")).toBool();
        x.volume = ModWire::clampVolume(p.contains(QStringLiteral("v")) ? p.value(QStringLiteral("v")) : QJsonValue(100));
        m_people.insert(it.key(), x);
    }
    const QJsonObject groups = o.value(QStringLiteral("groups")).toObject();
    for (auto g = groups.begin(); g != groups.end(); ++g) {
        const QJsonObject who = g.value().toObject();
        for (auto k = who.begin(); k != who.end(); ++k) {
            const QJsonObject bySlot = k.value().toObject();
            for (auto s = bySlot.begin(); s != bySlot.end(); ++s) {
                const QJsonObject rec = s.value().toObject();
                // Kept as they were taken; checked again for shape (the signature was checked when it came).
                if (ModWire::check(rec).isEmpty() && ModWire::slot(rec) == s.key() && jstr(rec, "g") == g.key() && jstr(rec, "k") == k.key())
                    m_records[g.key()][k.key()].insert(s.key(), rec);
            }
        }
    }
}

void Moderation::save()
{
    QJsonObject people;
    for (auto it = m_people.cbegin(); it != m_people.cend(); ++it) {
        const Person &p = it.value();
        if (!p.muted && !p.show && p.volume == ModWire::kVolumeDefault)
            continue;
        QJsonObject x;
        if (p.muted)
            x.insert(QStringLiteral("m"), true);
        if (p.show)
            x.insert(QStringLiteral("s"), true);
        if (p.volume != ModWire::kVolumeDefault)
            x.insert(QStringLiteral("v"), p.volume);
        people.insert(it.key(), x);
    }
    QJsonObject groups;
    for (auto g = m_records.cbegin(); g != m_records.cend(); ++g) {
        QJsonObject who;
        for (auto k = g->cbegin(); k != g->cend(); ++k) {
            QJsonObject bySlot;
            for (auto s = k->cbegin(); s != k->cend(); ++s)
                bySlot.insert(s.key(), s.value());
            if (!bySlot.isEmpty())
                who.insert(k.key(), bySlot);
        }
        if (!who.isEmpty())
            groups.insert(g.key(), who);
    }
    m_chat->m_vault->write(kFile, QJsonDocument(QJsonObject{{QStringLiteral("people"), people}, {QStringLiteral("groups"), groups}})
                                      .toJson(QJsonDocument::Compact));
    bump();
}

// ------------------------------------------------------------------ per person

bool Moderation::mutedForMe(const QString &key) const
{
    ensureLoaded();
    return m_people.value(key).muted;
}

void Moderation::setMutedForMe(const QString &key, bool on)
{
    ensureLoaded();
    if (key.isEmpty() || !m_loaded || key == m_chat->myKey() || m_people.value(key).muted == on)
        return;
    m_people[key].muted = on;
    save();
    m_chat->noteChanged();   // their messages fold or unfold at once
}

bool Moderation::hidesMessages(const QString &key) const
{
    ensureLoaded();
    return !m_people.value(key).show;
}

void Moderation::setHidesMessages(const QString &key, bool on)
{
    ensureLoaded();
    if (key.isEmpty() || !m_loaded || m_people.value(key).show == !on)
        return;
    m_people[key].show = !on;
    save();
    m_chat->noteChanged();
}

int Moderation::volume(const QString &key) const
{
    ensureLoaded();
    return m_people.contains(key) ? m_people.value(key).volume : ModWire::kVolumeDefault;
}

void Moderation::setVolume(const QString &key, int pct)
{
    ensureLoaded();
    const int v = ModWire::clampVolume(pct);
    if (key.isEmpty() || !m_loaded || volume(key) == v)
        return;
    m_people[key].volume = v;
    save();
}

int Moderation::gainFor(const QString &key) const
{
    if (!m_loaded)
        return ModWire::kVolumeDefault;   // never reads the vault from the speaker's pull
    const auto it = m_people.constFind(key);
    if (it == m_people.cend())
        return ModWire::kVolumeDefault;
    return it->muted ? 0 : it->volume;
}

// ------------------------------------------------------------------ timeouts and bans

bool Moderation::hasG2(const QString &key) const { return m_chat->hasCap(key, QLatin1String(ModWire::kCap)); }

QJsonObject Moderation::record(const QString &g, const QString &key, const QString &slot) const
{
    ensureLoaded();
    const auto git = m_records.constFind(g);
    if (git == m_records.cend())
        return QJsonObject();
    return git->value(key).value(slot);
}

bool Moderation::isBanned(const QString &g, const QString &key) const { return ModWire::banned(record(g, key, QStringLiteral("ban"))); }

qint64 Moderation::timedOutUntil(const QString &g, const QString &key) const
{
    const QJsonObject r = record(g, key, QStringLiteral("timeout"));
    return ModWire::timedOut(r, now()) ? r.value(QStringLiteral("until")).toInteger() : 0;
}

bool Moderation::canModerate(const QString &g, const QString &key) const
{
    const auto it = m_chat->m_groups.constFind(g);
    if (it == m_chat->m_groups.cend() || it->state.isEmpty() || it->removed || it->left)
        return false;
    const QJsonObject &s = it->state;
    const QString me = m_chat->myKey(), owner = jstr(s, "owner");
    if (key.isEmpty() || key == me || key == owner || !GroupWire::isAdmin(s, me))
        return false;
    return !GroupWire::isAdmin(s, key) || me == owner;
}

QString Moderation::whenText(qint64 ms) const
{
    const QDateTime t = QDateTime::fromMSecsSinceEpoch(ms);
    const QLocale l;
    const QString time = l.toString(t.time(), QLocale::ShortFormat);
    if (t.date() == QDate::currentDate())
        return time;
    return l.toString(t.date(), QStringLiteral("MMM d")) + QStringLiteral(", ") + time;
}

QString Moderation::leftText(qint64 ms) const
{
    const qint64 left = qMax<qint64>(0, ms - now());
    const qint64 min = (left + 59999) / 60000;
    if (min < 60)
        return QStringLiteral("%1 min left").arg(qMax<qint64>(1, min));
    const qint64 hours = (min + 59) / 60;
    if (hours < 48)
        return QStringLiteral("%1 h left").arg(hours);
    return QStringLiteral("%1 days left").arg((hours + 23) / 24);
}

QVariantList Moderation::timeoutChoices() const
{
    return {QVariantMap{{QStringLiteral("label"), QStringLiteral("10 minutes")}, {QStringLiteral("ms"), ModWire::kTimeouts[0]}},
            QVariantMap{{QStringLiteral("label"), QStringLiteral("1 hour")}, {QStringLiteral("ms"), ModWire::kTimeouts[1]}},
            QVariantMap{{QStringLiteral("label"), QStringLiteral("1 day")}, {QStringLiteral("ms"), ModWire::kTimeouts[2]}},
            QVariantMap{{QStringLiteral("label"), QStringLiteral("1 week")}, {QStringLiteral("ms"), ModWire::kTimeouts[3]}}};
}

QString Moderation::act(const QString &g, const QString &key, const QString &action, qint64 until)
{
    ensureLoaded();
    if (!m_loaded)
        return QStringLiteral("Unlock Huginn first.");
    if (!canModerate(g, key))
        return QStringLiteral("Only the group's admins can do that.");
    const QJsonObject state = m_chat->m_groups.value(g).state;
    // A higher time than the record it replaces, even if this computer's clock is behind the last admin's.
    qint64 ts = now();
    const QJsonObject cur = record(g, key, ModWire::slot(ModWire::make(action, g, key, QString(), 1, 2)));
    if (!cur.isEmpty())
        ts = qMax(ts, cur.value(QStringLiteral("ts")).toInteger() + 1);
    if (action == QLatin1String("timeout"))
        until = qMax(until, ts + 1);
    bool ok = false;
    const QJsonObject rec = GroupWire::sign(m_chat->m_priv, QLatin1String(ModWire::kDomain),
                                            ModWire::make(action, g, key, m_chat->myKey(), ts, until), &ok);
    if (!ok || ModWire::accept(&state, rec) != QLatin1String("ok"))
        return QStringLiteral("That couldn't be signed.");
    take(g, rec, true);
    QStringList to = GroupWire::keysOf(state);
    to << key;
    to.removeDuplicates();
    to.removeAll(m_chat->myKey());
    for (const QString &k : std::as_const(to))
        if (hasG2(k))
            m_chat->sendControl(g, k, QJsonObject{{QStringLiteral("t"), QStringLiteral("mod")}, {QStringLiteral("list"), QJsonArray{rec}}});
    return QString();
}

QString Moderation::timeout(const QString &g, const QString &key, qint64 ms)
{
    if (ms <= 0 || ms > 7 * ModWire::kDay)
        return QStringLiteral("Pick 10 minutes, 1 hour, 1 day or 1 week.");
    return act(g, key, QStringLiteral("timeout"), now() + ms);
}

QString Moderation::cancelTimeout(const QString &g, const QString &key)
{
    if (timedOutUntil(g, key) == 0)
        return QString();
    return act(g, key, QStringLiteral("untimeout"), 0);
}

QString Moderation::ban(const QString &g, const QString &key)
{
    const QString why = act(g, key, QStringLiteral("ban"), 0);
    if (!why.isEmpty())
        return why;
    // Out of the group too: a signed state without them, sent to them as well (3.4).
    if (GroupWire::isMember(m_chat->m_groups.value(g).state, key))
        return m_chat->removeMember(g, key);
    return QString();
}

QString Moderation::unban(const QString &g, const QString &key)
{
    if (!isBanned(g, key))
        return QString();
    return act(g, key, QStringLiteral("unban"), 0);
}

QString Moderation::removeForEveryone(const QString &g, const QString &msgId, const QString &author)
{
    ensureLoaded();
    if (!m_loaded)
        return QStringLiteral("Unlock Huginn first.");
    if (!GroupWire::isLogicalId(msgId) || !canModerate(g, author))
        return QStringLiteral("Only the group's admins can do that.");
    const QJsonObject state = m_chat->m_groups.value(g).state;
    bool ok = false;
    const QJsonObject rec = GroupWire::sign(m_chat->m_priv, QLatin1String(ModWire::kDomain),
                                            ModWire::makeRemove(g, author, m_chat->myKey(), now(), msgId), &ok);
    if (!ok || ModWire::accept(&state, rec) != QLatin1String("ok"))
        return QStringLiteral("That couldn't be signed.");
    blank(g, rec);
    for (const QString &k : GroupWire::keysOf(state))
        if (k != m_chat->myKey() && hasG2(k))
            m_chat->sendControl(g, k, QJsonObject{{QStringLiteral("t"), QStringLiteral("mod")}, {QStringLiteral("list"), QJsonArray{rec}}});
    return QString();
}

void Moderation::blank(const QString &g, const QJsonObject &rec)
{
    const QString author = jstr(rec, "k"), id = jstr(rec, "m"), me = m_chat->myKey();
    bool found = false;
    m_chat->updateGroupMessage(g, id, [&](QVariantMap &m) {
        const bool mine = m.value(QStringLiteral("mine")).toBool();
        if (mine ? author != me : m.value(QStringLiteral("from")).toString() != author)
            return;
        found = true;
        const QString photo = m.value(QStringLiteral("photo")).toString();
        if (!photo.isEmpty())
            m_chat->m_vault->remove(QStringLiteral("photos/") + photo);
        m.insert(QStringLiteral("text"), QString());
        m.insert(QStringLiteral("photo"), QString());
        m.insert(QStringLiteral("voice"), 0);
        m.insert(QStringLiteral("unsent"), QStringLiteral("an admin"));
        m.insert(QStringLiteral("removed"), true);
    });
    // Still on its way: a later copy from its author never shows.
    if (!found && !m_chat->gmsgKnown(id)) {
        if (m_chat->m_unsentBy.size() > 2000)
            m_chat->m_unsentBy.clear();
        m_chat->m_unsentBy.insert(id, author);
    }
}

QVariantList Moderation::bannedList(const QString &g) const
{
    ensureLoaded();
    QVariantList out;
    const auto git = m_records.constFind(g);
    if (git == m_records.cend())
        return out;
    for (auto it = git->cbegin(); it != git->cend(); ++it)
        if (ModWire::banned(it->value(QStringLiteral("ban"))))
            out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), m_chat->memberName(g, it.key())}};
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("name")).toString().toLower() < b.toMap().value(QStringLiteral("name")).toString().toLower();
    });
    return out;
}

QVariantList Moderation::timeoutList(const QString &g) const
{
    ensureLoaded();
    QVariantList out;
    const auto git = m_records.constFind(g);
    if (git == m_records.cend())
        return out;
    const qint64 t = now();
    for (auto it = git->cbegin(); it != git->cend(); ++it) {
        const QJsonObject r = it->value(QStringLiteral("timeout"));
        if (!ModWire::timedOut(r, t) || ModWire::banned(it->value(QStringLiteral("ban"))))
            continue;
        const qint64 until = r.value(QStringLiteral("until")).toInteger();
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), m_chat->memberName(g, it.key())},
                           {QStringLiteral("until"), until}, {QStringLiteral("left"), leftText(until)}};
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("until")).toLongLong() < b.toMap().value(QStringLiteral("until")).toLongLong();
    });
    return out;
}

QString Moderation::myStatus(const QString &g) const
{
    if (!m_chat->ready())
        return QString();
    const QString me = m_chat->myKey();
    if (isBanned(g, me))
        return QStringLiteral("You were banned from %1").arg(m_chat->groupName(g));
    const qint64 until = timedOutUntil(g, me);
    if (until > 0)
        return QStringLiteral("You're timed out until %1").arg(whenText(until));
    return QString();
}

bool Moderation::take(const QString &g, const QJsonObject &rec, bool announce)
{
    const QString k = jstr(rec, "k"), slot = ModWire::slot(rec);
    if (!ModWire::newer(record(g, k, slot), rec))
        return false;
    m_records[g][k].insert(slot, rec);
    save();
    if (announce) {
        const QString me = m_chat->myKey(), a = jstr(rec, "a");
        const QString by = jstr(rec, "by") == me ? QStringLiteral("You") : m_chat->memberName(g, jstr(rec, "by"));
        const QString who = k == me ? QStringLiteral("you") : m_chat->memberName(g, k);
        const qint64 until = rec.value(QStringLiteral("until")).toInteger();
        QString line;
        if (a == QLatin1String("ban"))
            line = k == me ? QStringLiteral("You were banned from %1").arg(m_chat->groupName(g)) : QStringLiteral("%1 banned %2").arg(by, who);
        else if (a == QLatin1String("unban"))
            line = QStringLiteral("%1 unbanned %2").arg(by, who);
        else if (a == QLatin1String("timeout"))
            line = k == me ? QStringLiteral("You're timed out until %1").arg(whenText(until))
                           : QStringLiteral("%1 timed out %2 until %3").arg(by, who, whenText(until));
        else
            line = QStringLiteral("%1 ended the timeout for %2").arg(by, who);
        m_chat->groupNote(g, line);
        if (k == me)
            Q_EMIT m_chat->notice(line);
        m_chat->noteChanged();
    }
    return true;
}

bool Moderation::receive(const QString &g, const QString &from, const QJsonObject &p)
{
    Q_UNUSED(from)
    ensureLoaded();
    const QJsonArray list = p.value(QStringLiteral("list")).toArray();
    if (list.isEmpty() || list.size() > ModWire::kMaxList)
        return false;
    if (!m_loaded)
        return true;
    const QJsonObject state = m_chat->m_groups.value(g).state;
    for (const QJsonValue &v : list) {
        const QJsonObject rec = v.toObject();
        if (jstr(rec, "g") != g || ModWire::accept(&state, rec) != QLatin1String("ok"))
            continue;
        if (ModWire::isRemove(rec))
            blank(g, rec);
        else
            take(g, rec, true);
    }
    return true;
}

void Moderation::sendRecords(const QString &g, const QString &to)
{
    ensureLoaded();
    if (!hasG2(to))
        return;
    const auto git = m_records.constFind(g);
    if (git == m_records.cend())
        return;
    const qint64 t = now();
    QJsonArray list;
    for (auto it = git->cbegin(); it != git->cend(); ++it) {
        const QJsonObject ban = it->value(QStringLiteral("ban")), timeout = it->value(QStringLiteral("timeout"));
        if (ModWire::banned(ban))
            list.append(ban);
        if (ModWire::timedOut(timeout, t))
            list.append(timeout);
    }
    for (int i = 0; i < int(list.size()); i += ModWire::kMaxList) {
        QJsonArray part;
        for (int j = i; j < qMin(int(list.size()), i + ModWire::kMaxList); ++j)
            part.append(list.at(j));
        m_chat->sendControl(g, to, QJsonObject{{QStringLiteral("t"), QStringLiteral("mod")}, {QStringLiteral("list"), part}});
    }
}

void Moderation::forgetGroup(const QString &g)
{
    ensureLoaded();
    if (m_records.remove(g) > 0)
        save();
}

bool Moderation::selfTest(const QString &vectorsPath) { return ModWire::selfTest(vectorsPath); }
