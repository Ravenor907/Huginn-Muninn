#include "trust.h"
#include "chat.h"
#include "ble.h"
#include "localsend.h"
#include "chatcrypto.h"
#include "filelink.h"
#include "groupwire.h"
#include "link.h"
#include "nostr.h"
#include "vault.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUuid>
#include <cstdio>
#include <algorithm>

namespace
{
const QString kPrefix = QStringLiteral("MUNINN1:");
const QString kPage = QStringLiteral("https://ravenor907.github.io/Huginn-Muninn/add/#");

// ChatCrypto.EMOJI in Muninn: the order is fixed (13.5).
const char *const kEmoji[64] = {
    "🦊", "🌙", "⚓", "🎈", "🐢", "🍎", "🚲", "🔑", "🌵", "🐝", "🎸", "🍄", "🚀", "🐙", "🌻", "🧲",
    "🐘", "🍋", "⛵", "🔔", "🦉", "🍇", "🚂", "🎩", "🐬", "🌽", "🏀", "🪁", "🦋", "🍒", "🛶", "📚",
    "🐧", "🥕", "🏰", "🎺", "🐳", "🍉", "🚁", "🧩", "🦒", "🥨", "🗿", "🎯", "🐞", "🍓", "🛸", "🧭",
    "🐿️", "🍍", "🏕️", "🥁", "🦀", "🌶️", "🚜", "🪐", "🦓", "🍩", "⛺", "🎲", "🐌", "🥑", "🗻", "🧸"};

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
QString vstr(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }

bool keyOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString k = v.toString();
    if (k.size() < 40 || k.size() > 200)
        return false;
    // A real P-256 public key: it must work for ECDH with a throwaway key.
    const ChatCrypto::KeyPair t = ChatCrypto::newKeyPair();
    return !ChatCrypto::sharedKey(t.privateDer, t.publicDer, QByteArray::fromBase64(k.toLatin1(), QByteArray::AbortOnBase64DecodingErrors)).isEmpty();
}

bool rateOk(QHash<QString, QList<qint64>> &all, const QString &key, int max)
{
    if (all.size() > 500)
        all.clear();
    QList<qint64> &q = all[key];
    while (!q.isEmpty() && now() - q.first() > 10000)
        q.removeFirst();
    if (q.size() >= max)
        return false;
    q << now();
    return true;
}
}

const char Trust::kCap[] = "i1";

Trust::Trust(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    // Read once the vault is open; Chat's changes (contacts, groups) can change what's offered.
    connect(m_chat, &Chat::changed, this, [this] {
        if (!m_chat->ready()) {
            m_loaded = false;
            return;
        }
        Q_EMIT changed();
    });
}

void Trust::ensureLoaded() const
{
    if (m_loaded || !m_chat->ready())
        return;
    m_loaded = true;
    m_intros.clear();
    m_once.clear();
    const QJsonArray a = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("intros.json"))).array();
    for (const QJsonValue &v : a) {
        const QJsonObject o = v.toObject();
        Intro i;
        i.id = jstr(o, "id");
        i.by = jstr(o, "by");
        i.k = jstr(o, "k");
        i.n = jstr(o, "n");
        i.p = jstr(o, "p");
        i.iSaid = o.value(QStringLiteral("i")).toBool();
        i.theySaid = o.value(QStringLiteral("t")).toBool();
        i.declined = o.value(QStringLiteral("d")).toBool();
        i.at = qint64(o.value(QStringLiteral("at")).toDouble());
        if (!i.id.isEmpty() && !i.k.isEmpty())
            m_intros << i;
    }
    for (const QJsonValue &v : QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("invites-once.json"))).array())
        m_once.insert(v.toString());
}

void Trust::save()
{
    QJsonArray a;
    for (const Intro &i : std::as_const(m_intros))
        a.append(QJsonObject{{QStringLiteral("id"), i.id}, {QStringLiteral("by"), i.by}, {QStringLiteral("k"), i.k}, {QStringLiteral("n"), i.n},
                             {QStringLiteral("p"), i.p}, {QStringLiteral("i"), i.iSaid}, {QStringLiteral("t"), i.theySaid},
                             {QStringLiteral("d"), i.declined}, {QStringLiteral("at"), double(i.at)}});
    m_chat->m_vault->write(QStringLiteral("intros.json"), QJsonDocument(a).toJson(QJsonDocument::Compact));
    QJsonArray o;
    for (const QString &t : std::as_const(m_once))
        o.append(t);
    m_chat->m_vault->write(QStringLiteral("invites-once.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

const Trust::Intro *Trust::find(const QString &id) const
{
    for (const Intro &i : std::as_const(m_intros))
        if (i.id == id)
            return &i;
    return nullptr;
}

void Trust::put(const Intro &i)
{
    for (Intro &x : m_intros)
        if (x.id == i.id) {
            x = i;
            save();
            Q_EMIT changed();
            return;
        }
    m_intros << i;
    // Old answered ones go first: at most 200 kept.
    while (m_intros.size() > 200)
        m_intros.removeFirst();
    save();
    Q_EMIT changed();
}

bool Trust::hasI1(const QString &key) const { return m_chat->hasCap(key, QString::fromLatin1(kCap)); }

void Trust::sendTo(const QString &key, const QString &mailbox, const QJsonObject &msg)
{
    // Sealed like a private message with v1 and sent once, on the first route that takes it.
    const QJsonObject env = m_chat->envelope(QStringLiteral("intro"), key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces));
    m_chat->deliver(key, env, mailbox.isEmpty() ? m_chat->mailboxOf(key) : mailbox);
}

// ------------------------------------------------------------------ introducing two contacts

QString Trust::introduce(const QString &a, const QString &b)
{
    ensureLoaded();
    if (!m_chat->ready() || !m_chat->isContact(a) || !m_chat->isContact(b))
        return QStringLiteral("Pick two of your contacts.");
    if (a == b)
        return QStringLiteral("Pick two different people.");
    for (const QString &k : {a, b})
        if (!hasI1(k)) {
            m_chat->probeCaps(k);
            return QStringLiteral("%1 needs a newer Muninn or Huginn for introductions.").arg(m_chat->nameOf(k));
        }
    const QString id = GroupWire::newLogicalId();
    for (const auto &[to, other] : {std::pair{a, b}, std::pair{b, a}}) {
        // Their own name (never my nickname for them) and their mailbox.
        QString n = GroupWire::cleanName(m_chat->realNameOf(other), 40).trimmed();
        if (n.isEmpty())
            n = QStringLiteral("Someone");
        sendTo(to, QString(), QJsonObject{{QStringLiteral("t"), QStringLiteral("intro")}, {QStringLiteral("id"), id}, {QStringLiteral("k"), other},
                                          {QStringLiteral("n"), n}, {QStringLiteral("p"), m_chat->mailboxOf(other)}});
    }
    return QString();
}

void Trust::answer(const QString &id, bool yes)
{
    ensureLoaded();
    const Intro *found = find(id);
    Intro i;
    if (found) {
        i = *found;
    } else {
        // A Family offer: kept from now on, so it isn't offered again after a no.
        bool offer = false;
        for (const Intro &o : familyOffers())
            if (o.id == id) {
                i = o;
                i.at = now();
                offer = true;
            }
        if (!offer)
            return;
    }
    if (!yes) {
        i.declined = true;
        put(i);
        return;   // saying no sends nothing
    }
    i.iSaid = true;
    i.declined = false;
    put(i);
    const QJsonObject msg = i.by.startsWith(QLatin1String("g:"))
        ? QJsonObject{{QStringLiteral("t"), QStringLiteral("add")}, {QStringLiteral("g"), i.by.mid(2)}}
        : QJsonObject{{QStringLiteral("t"), QStringLiteral("yes")}, {QStringLiteral("id"), i.id}};
    sendTo(i.k, i.p, msg);
    if (i.theySaid)
        finish(i);
}

QString Trust::askToAdd(const QString &g, const QString &key)
{
    ensureLoaded();
    const auto it = m_chat->m_groups.constFind(g);
    if (it == m_chat->m_groups.cend() || it->removed || it->left || !GroupWire::isMember(it->state, key))
        return QStringLiteral("They aren't in that group any more.");
    if (m_chat->isContact(key))
        return QString();
    if (!hasI1(key))
        return QStringLiteral("%1 needs a newer Muninn or Huginn for this.").arg(m_chat->memberName(g, key));
    // The local id of a member's "add", kept apart from introducers' ids so none can be planted (13).
    const QString id = QStringLiteral("add:") + groupId(g, m_chat->myKey(), key);
    if (!find(id)) {
        const QJsonObject m = GroupWire::memberEntry(it->state, key);
        Intro i;
        i.id = id;
        i.by = QStringLiteral("g:") + g;
        i.k = key;
        i.n = m_chat->memberName(g, key);
        i.p = jstr(m, "p");
        i.at = now();
        put(i);
    }
    answer(id, true);
    return QString();
}

// Both said yes: a contact here (their app does the same), not verified until they check the emoji.
void Trust::finish(const Intro &i)
{
    m_intros.erase(std::remove_if(m_intros.begin(), m_intros.end(), [&](const Intro &x) { return x.id == i.id; }), m_intros.end());
    save();
    if (!m_chat->isContact(i.k) && i.k != m_chat->myKey()) {
        m_chat->m_contacts.insert(i.k, {{QStringLiteral("key"), i.k}, {QStringLiteral("name"), i.n}, {QStringLiteral("mailbox"), i.p},
                                         {QStringLiteral("verified"), false}});
        m_chat->m_met.remove(i.k);
        m_chat->m_trash.remove(i.k);
        m_chat->saveContacts();
        m_chat->saveMet();
        m_chat->refreshGroupPeers();
        m_chat->sayHello(i.k);
        Q_EMIT m_chat->notice(QStringLiteral("You and %1 are in touch now. Check the 4 pictures with them to be sure.").arg(i.n));
        Q_EMIT m_chat->changed();
    }
    Q_EMIT changed();
}

int Trust::receive(const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &p)
{
    if (!ok(p))
        return 400;
    if (!rateOk(m_recent, fromKey, 20))
        return 429;
    ensureLoaded();
    const QString me = m_chat->myKey(), t = jstr(p, "t");
    if (t == QLatin1String("intro")) {
        if (!m_chat->isContact(fromKey))
            return 403;
        const QString k = jstr(p, "k"), id = jstr(p, "id");
        if (k == me || m_chat->isContact(k) || find(id))
            return 200;
        Intro i;
        i.id = id;
        i.by = fromKey;
        i.k = k;
        i.n = jstr(p, "n");
        i.p = jstr(p, "p");
        i.at = now();
        put(i);
        Q_EMIT m_chat->arrived(m_chat->nameOf(fromKey), QStringLiteral("Wants to introduce you to %1").arg(i.n), fromKey);
        return 200;
    }
    if (t == QLatin1String("yes")) {
        const Intro *f = find(jstr(p, "id"));
        if (!f || f->k != fromKey || f->by.startsWith(QLatin1String("g:")))
            return m_chat->isContact(fromKey) ? 200 : 403;
        Intro i = *f;
        i.theySaid = true;
        if (i.p.isEmpty())
            i.p = mailbox;
        if (i.iSaid)
            finish(i);
        else
            put(i);
        return 200;
    }
    // "add": both of us in the receiver's current state of the group.
    const QString g = jstr(p, "g");
    const auto it = m_chat->m_groups.constFind(g);
    if (it == m_chat->m_groups.cend() || it->removed || it->left || !GroupWire::isMember(it->state, fromKey) || !GroupWire::isMember(it->state, me))
        return 403;
    if (m_chat->isContact(fromKey))
        return 200;
    const QString id = QStringLiteral("add:") + groupId(g, me, fromKey);
    Intro i;
    if (const Intro *f = find(id); f && f->by == QStringLiteral("g:") + g && f->k == fromKey) {
        i = *f;
    } else {
        const QJsonObject m = GroupWire::memberEntry(it->state, fromKey);
        i.id = id;
        i.by = QStringLiteral("g:") + g;
        i.k = fromKey;
        i.n = jstr(m, "n").isEmpty() ? name : jstr(m, "n");
        i.p = mailbox.isEmpty() ? jstr(m, "p") : mailbox;
        i.at = now();
    }
    const bool wasAsked = i.theySaid;
    i.theySaid = true;
    if (i.p.isEmpty())
        i.p = mailbox;
    if (i.iSaid) {
        finish(i);
    } else {
        put(i);
        if (!wasAsked)
            Q_EMIT m_chat->arrived(i.n, QStringLiteral("From %1: wants to be in touch").arg(m_chat->groupName(g)), fromKey);
    }
    return 200;
}

// People in my Family groups (named Family, or in my Family folder) I'm not in touch with yet and
// haven't been asked about, whose app can take "add".
QList<Trust::Intro> Trust::familyOffers() const
{
    QList<Intro> out;
    if (!m_chat->ready())
        return out;
    const QString me = m_chat->myKey();
    QStringList familyKeys;
    for (const QVariantMap &f : std::as_const(m_chat->m_folders))
        if (vstr(f, "name").trimmed().compare(QLatin1String("Family"), Qt::CaseInsensitive) == 0)
            familyKeys << f.value(QStringLiteral("keys")).toStringList();
    QSet<QString> known, seen;
    for (const Intro &i : std::as_const(m_intros))
        known.insert(i.k);
    for (auto it = m_chat->m_groups.cbegin(); it != m_chat->m_groups.cend(); ++it) {
        if (it->state.isEmpty() || it->removed || it->left)
            continue;
        if (!it->state.value(QStringLiteral("name")).toString().contains(QLatin1String("family"), Qt::CaseInsensitive)
            && !familyKeys.contains(QStringLiteral("g:") + it.key()))
            continue;
        for (const QJsonValue &v : it->state.value(QStringLiteral("members")).toArray()) {
            const QJsonObject m = v.toObject();
            const QString k = jstr(m, "k");
            if (k.isEmpty() || k == me || m_chat->isContact(k) || known.contains(k) || seen.contains(k) || !hasI1(k))
                continue;
            seen.insert(k);
            Intro i;
            i.id = QStringLiteral("add:") + groupId(it.key(), me, k);
            i.by = QStringLiteral("g:") + it.key();
            i.k = k;
            i.n = jstr(m, "n");
            i.p = jstr(m, "p");
            out << i;
        }
    }
    return out;
}

QVariantList Trust::pending() const
{
    ensureLoaded();
    QVariantList out;
    if (!m_chat->ready())
        return out;
    for (const Intro &i : std::as_const(m_intros)) {
        if (i.iSaid || i.declined || m_chat->isContact(i.k))
            continue;
        const bool group = i.by.startsWith(QLatin1String("g:"));
        if (group && !i.theySaid)
            continue;
        out << QVariantMap{{QStringLiteral("id"), i.id}, {QStringLiteral("kind"), group ? QStringLiteral("group") : QStringLiteral("intro")},
                           {QStringLiteral("key"), i.k}, {QStringLiteral("name"), i.n},
                           {QStringLiteral("detail"), group ? QStringLiteral("From %1: wants to be in touch").arg(m_chat->groupName(i.by.mid(2)))
                                                            : QStringLiteral("%1 wants to introduce you").arg(m_chat->nameOf(i.by))}};
    }
    for (const Intro &i : familyOffers())
        out << QVariantMap{{QStringLiteral("id"), i.id}, {QStringLiteral("kind"), QStringLiteral("offer")}, {QStringLiteral("key"), i.k},
                           {QStringLiteral("name"), i.n},
                           {QStringLiteral("detail"), QStringLiteral("In %1 with you: add as a contact?").arg(m_chat->groupName(i.by.mid(2)))}};
    return out;
}

// ------------------------------------------------------------------ one-time invites and the emoji

QString Trust::inviteLink(int hours)
{
    ensureLoaded();
    if (!m_chat->ready())
        return QString();
    const QString token = QUuid::createUuid().toString(QUuid::Id128).left(16);
    const qint64 until = now() + qBound(1, hours, 24 * 7) * 3600000LL;
    m_chat->m_invites.insert(token, until);
    for (auto it = m_chat->m_invites.begin(); it != m_chat->m_invites.end();)
        it = it.value() < now() ? m_chat->m_invites.erase(it) : std::next(it);
    m_once.insert(token);
    for (auto it = m_once.begin(); it != m_once.end();)
        it = m_chat->m_invites.contains(*it) ? std::next(it) : m_once.erase(it);
    m_chat->saveInvites();
    save();
    // Chat's own code, with "1": true added (13.6).
    const QString code = Chat::code(m_chat->username(), m_chat->myKey(), m_chat->mailbox(), token, until);
    QJsonObject o = QJsonDocument::fromJson(QByteArray::fromBase64(code.mid(kPrefix.size()).toLatin1(), QByteArray::Base64UrlEncoding)).object();
    o.insert(QStringLiteral("1"), true);
    return kPage + kPrefix + QString::fromLatin1(compact(o).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

bool Trust::isOnceCode(const QString &text) const
{
    QString t = text;
    t.replace(QStringLiteral("MUNINN1%3A"), kPrefix, Qt::CaseInsensitive);
    const int i = t.indexOf(kPrefix);
    if (i < 0)
        return false;
    QString body;
    for (const QChar c : t.mid(i + kPrefix.size())) {
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    return QJsonDocument::fromJson(QByteArray::fromBase64(body.toLatin1(), QByteArray::Base64UrlEncoding)).object().value(QStringLiteral("1")).toBool();
}

bool Trust::useOnce(const QString &token)
{
    ensureLoaded();
    if (token.isEmpty() || !m_once.contains(token) || m_chat->m_invites.value(token, 0) <= now())
        return false;
    m_once.remove(token);
    save();
    return true;
}

void Trust::markOnce(const QString &token)
{
    ensureLoaded();
    if (token.isEmpty() || !m_chat->ready())
        return;
    m_once.insert(token);
    for (auto it = m_once.begin(); it != m_once.end();)
        it = m_chat->m_invites.contains(*it) ? std::next(it) : m_once.erase(it);
    save();
}

QString Trust::emojiWith(const QString &key) const
{
    const QByteArray k = m_chat->ready() ? m_chat->keyWith(key) : QByteArray();
    return k.isEmpty() ? QString() : emojiCode(k);
}

QString Trust::emojiCode(const QByteArray &sharedKey)
{
    const QByteArray d = QCryptographicHash::hash(QByteArray("emoji code") + sharedKey, QCryptographicHash::Sha256);
    QStringList out;
    for (int i = 0; i < 4; ++i)
        out << QString::fromUtf8(kEmoji[quint8(d.at(i)) % 64]);
    return out.join(QLatin1Char(' '));
}

QString Trust::groupId(const QString &g, const QString &a, const QString &b)
{
    const QString lo = std::min(a, b), hi = std::max(a, b);
    const QByteArray d = QCryptographicHash::hash((QStringLiteral("add|") + g + QLatin1Char('|') + lo + QLatin1Char('|') + hi).toUtf8(), QCryptographicHash::Sha256);
    return QString::fromLatin1(d.toHex().left(32));
}

bool Trust::ok(const QJsonObject &p)
{
    const QString t = jstr(p, "t");
    if (t == QLatin1String("intro")) {
        const QJsonValue n = p.value(QStringLiteral("n")), mb = p.value(QStringLiteral("p"));
        if (!GroupWire::isLogicalId(jstr(p, "id")) || !keyOk(p.value(QStringLiteral("k"))) || !n.isString() || !mb.isString())
            return false;
        const QString name = n.toString();
        if (name.trimmed().isEmpty() || GroupWire::length(name) > 40)
            return false;
        for (const QChar c : name)
            if (c.unicode() < 0x20)
                return false;
        return mb.toString().isEmpty() || GroupWire::isMailbox(mb.toString());
    }
    if (t == QLatin1String("yes"))
        return GroupWire::isLogicalId(jstr(p, "id"));
    if (t == QLatin1String("add")) {
        const QJsonValue g = p.value(QStringLiteral("g"));
        return g.isString() && !g.toString().isEmpty() && g.toString().size() <= 40;
    }
    return false;
}

// ------------------------------------------------------------------ Chat's side of rooms intro, file and link

int Chat::receiveExtra(const QString &room, const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &p, const QString &via)
{
    if (p.isEmpty())
        return 400;
    if (room == QLatin1String("intro"))
        return m_trust ? m_trust->receive(fromKey, name, mailbox, p) : 400;
    if (room == QLatin1String("file")) {
        // Between contacts (14.1), and between members of a group for that group's files (cap f2, 27.2): their offer
        // names the group, their answer is to a file of mine on its way to them. At most 30 in 10 seconds each.
        const QString g = p.value(QStringLiteral("g")).toString();
        const bool member = (!g.isEmpty() && groupFileMembers(g).contains(fromKey))
            || (m_files && m_files->expects(fromKey, p.value(QStringLiteral("id")).toString()));
        if (!isContact(fromKey) && !member)
            return 403;
        if (!m_files || !FileLink::ok(p))
            return 400;
        // Files turned off for them: their offers are dropped quietly.
        if (isContact(fromKey) && !filesAllowed(fromKey))
            return 200;
        if (!rateOk(m_recentCall, QStringLiteral("file|") + fromKey, 30))
            return 429;
        m_files->onSignal(fromKey, p);
        return 200;
    }
    if (room == QLatin1String("link"))
        return m_linked && m_linked->receive(fromKey, p, via) ? 200 : 403;
    return 400;
}

void Chat::sendEverywhere(const QString &key, const QString &room, const QJsonObject &msg, const QString &mailbox)
{
    if (!m_ready || key.isEmpty())
        return;
    // Sealed once and the same copy on every route at once (5.1); the other side keeps the first.
    const QByteArray json = compact(envelope(room, key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    sendBle(key, json);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, json);
    sendInternet(key, {json}, mailbox.isEmpty() ? mailboxOf(key) : mailbox);
}

void Chat::sendLink(const QString &key, const QJsonObject &msg, const QString &mailbox)
{
    if (!m_ready || key.isEmpty() || m_moving)
        return;
    const QByteArray json = compact(envelope(QStringLiteral("link"), key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    sendBle(key, json);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, json);
    const QString to = mailbox.isEmpty() ? mailboxOf(key) : mailbox;
    if (!to.isEmpty())
        m_nostr->publishTo(to, wrap(key, json), chatDays(key), {}, 2, true);
}

// ------------------------------------------------------------------ self-test (shared/vectors/i1f1-vectors.json)

bool Trust::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::fprintf(stderr, "i1f1: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    int bad = 0;
    auto check = [&bad](bool ok, const QString &what) {
        if (!ok) {
            bad++;
            std::fprintf(stderr, "i1f1 FAIL: %s\n", qPrintable(what));
        }
    };
    const QJsonObject pair = v.value(QStringLiteral("pair")).toObject();
    const QByteArray aPriv = QByteArray::fromBase64(jstr(pair, "aPriv").toLatin1()), aPub = QByteArray::fromBase64(jstr(pair, "aPub").toLatin1());
    const QByteArray shared = ChatCrypto::sharedKey(aPriv, aPub, QByteArray::fromBase64(jstr(pair, "bPub").toLatin1()));
    check(shared.toHex() == jstr(pair, "sharedHex").toLatin1(), QStringLiteral("shared key"));
    for (const QJsonValue &x : v.value(QStringLiteral("emoji")).toArray()) {
        const QJsonObject c = x.toObject();
        check(emojiCode(QByteArray::fromHex(jstr(c, "keyHex").toLatin1())) == jstr(c, "emoji"), QStringLiteral("emoji ") + jstr(c, "keyHex"));
    }
    for (const QJsonValue &x : v.value(QStringLiteral("groupId")).toArray()) {
        const QJsonObject c = x.toObject();
        check(groupId(jstr(c, "g"), jstr(c, "a"), jstr(c, "b")) == jstr(c, "id"), QStringLiteral("group id"));
    }
    const QJsonObject intro = v.value(QStringLiteral("intro")).toObject();
    for (const QJsonValue &x : intro.value(QStringLiteral("good")).toArray())
        check(ok(x.toObject()), QStringLiteral("intro should pass ") + QString::fromUtf8(compact(x.toObject())));
    for (const QJsonValue &x : intro.value(QStringLiteral("bad")).toArray())
        check(!ok(x.toObject()), QStringLiteral("intro should fail ") + QString::fromUtf8(compact(x.toObject())));
    const bool files = FileLink::selfTest(v, shared);
    const bool links = Link::selfTest(v);
    std::fprintf(stderr, "i1f1: %s\n", bad == 0 && files && links ? "all checks passed" : "FAILED");
    return bad == 0 && files && links;
}
