#include "chatwire.h"
#include "proof.h"

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonValue>
#include <cstdio>
#include <iterator>
#include <set>

const char ChatWire::kCap[] = "r1";
const char ChatWire::kCaps[] = "r1 g1 c1 c2 t1 v1 m1 m2 d1 i1 f1 j1 x1 s1 b1 e1 a1 p1 v2 g2 f2 g3 f3 c3 g4 n1 n2 p2 v3 h1 r2";

namespace
{
QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QByteArray unb64(const QJsonValue &v) { return QByteArray::fromBase64(v.toString().toLatin1()); }
// Seen keeps a hash of "from|id", never the key itself (it's written to disk).
QString key2(const QString &from, const QString &id)
{
    return QString::fromLatin1(QCryptographicHash::hash((from + QStringLiteral("|") + id).toUtf8(), QCryptographicHash::Sha256).toHex().left(32));
}
}

bool ChatWire::useRatchet(const QString &room, bool contact, bool capable)
{
    if (!contact || !capable)
        return false;
    return room == QLatin1String("private") || room == QLatin1String("receipt") || room == QLatin1String("unsend")
        || room == QLatin1String("keep") || room == QLatin1String("photo") || room == QLatin1String("group") || room == QLatin1String("gmsg")
        || room == QLatin1String("gphoto") || room == QLatin1String("greceipt") || room == QLatin1String("call") || room == QLatin1String("tvconfig")
        // Cap m1: replies, reactions and edits. Typing and active status ("live") stay on v1, so they never move the ratchet.
        || room == QLatin1String("rich") || room == QLatin1String("mx")
        // Cap p1: profile pictures (section 22).
        || room == QLatin1String("profile");
}

bool ChatWire::isRatchet(const QJsonObject &o) { return o.value(QStringLiteral("rh")).type() != QJsonValue::Undefined; }

bool ChatWire::saysCapable(const QJsonObject &o) { return o.value(QStringLiteral("cap")).toString() == QLatin1String(kCap); }

QStringList ChatWire::capsOf(const QJsonObject &o, bool *present)
{
    const QJsonValue v = o.value(QStringLiteral("caps"));
    if (present)
        *present = v.isString();
    QStringList out;
    for (const QString &t : v.toString().split(QLatin1Char(' '), Qt::SkipEmptyParts))
        // Muninn sends over 32 tokens: keep up to 48.
        if (t.size() <= 8 && !out.contains(t) && out.size() < 48)
            out << t;
    return out;
}

QJsonObject ChatWire::seal(QJsonObject o, const QByteArray &sk, const QString &theirKey, const QByteArray &plain, Ratchet::Peer *peer,
                           const Ratchet::NewKey &newKey, bool bind)
{
    o.insert(QStringLiteral("cap"), QLatin1String(kCap));
    o.insert(QStringLiteral("caps"), QLatin1String(kCaps));
    // Cap s1: the clear fields go into the seal too (and "aad":"s1" says so).
    QByteArray text;
    o.remove(QLatin1String(Proof::kMark));
    if (bind) {
        o.insert(QLatin1String(Proof::kMark), QLatin1String(Proof::kCap));
        bool ok = false;
        text = Proof::bound(o, theirKey, &ok);
        if (!ok) {
            text.clear();
            o.remove(QLatin1String(Proof::kMark));
        }
    }
    if (peer) {
        const std::pair<Ratchet::Header, QByteArray> out = peer->encrypt(sk, theirKey, plain, newKey, text);
        if (!out.second.isEmpty()) {
            o.insert(QStringLiteral("rh"), out.first.toJson());
            o.insert(QStringLiteral("data"), b64(out.second));
            return o;
        }
    }
    QByteArray nonce, data;
    ChatCrypto::seal(sk, plain, &nonce, &data, text.isEmpty() ? QByteArray() : Proof::aad(text));
    o.insert(QStringLiteral("nonce"), b64(nonce));
    o.insert(QStringLiteral("data"), b64(data));
    return o;
}

QByteArray ChatWire::open(const QJsonObject &o, const QByteArray &sk, const ChatCrypto::KeyPair &me, Ratchet::Peer *peer,
                          const Ratchet::NewKey &newKey, bool *ok, const QString &myKey)
{
    *ok = false;
    if (sk.isEmpty())
        return QByteArray();
    // Cap s1: a bound envelope opens only with the fields it was sealed with, for the key it was sealed to.
    QByteArray text;
    if (o.contains(QLatin1String(Proof::kMark))) {
        bool good = false;
        if (!Proof::isBound(o) || (text = Proof::bound(o, myKey, &good), !good))
            return QByteArray();
    }
    const QByteArray data = unb64(o.value(QStringLiteral("data")));
    if (isRatchet(o)) {
        if (!peer || data.isEmpty())
            return QByteArray();
        bool hok = false;
        Ratchet::Header h = Ratchet::Header::fromJson(o.value(QStringLiteral("rh")).toObject(), &hok);
        if (!hok)
            return QByteArray();
        h.bind = text;
        return peer->decrypt(sk, me, h, data, newKey, ok);
    }
    return ChatCrypto::open(sk, unb64(o.value(QStringLiteral("nonce"))), data, ok, text.isEmpty() ? QByteArray() : Proof::aad(text));
}

// ------------------------------------------------------------------ Outbox

const ChatWire::Outbox::Entry *ChatWire::Outbox::find(const QString &id) const
{
    const auto it = m_entries.find(id);
    return it == m_entries.end() ? nullptr : &it->second;
}

std::vector<QString> ChatWire::Outbox::ids() const
{
    std::vector<QString> out;
    for (const auto &e : m_entries)
        out.push_back(e.first);
    return out;
}

std::vector<QString> ChatWire::Outbox::expired(qint64 now, qint64 maxAge) const
{
    std::vector<QString> out;
    for (const auto &e : m_entries)
        if (now - e.second.time > maxAge)
            out.push_back(e.first);
    return out;
}

QByteArray ChatWire::Outbox::save(const Entry &e)
{
    QJsonArray bodies;
    for (const QByteArray &b : e.bodies)
        bodies.append(QString::fromUtf8(b));
    QJsonObject o;
    o.insert(QStringLiteral("t"), double(e.time));
    o.insert(QStringLiteral("key"), e.key);
    o.insert(QStringLiteral("b"), bodies);
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

bool ChatWire::Outbox::load(const QByteArray &json, Entry *e)
{
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    const QJsonArray bodies = o.value(QStringLiteral("b")).toArray();
    Entry out;
    out.time = qint64(o.value(QStringLiteral("t")).toDouble());
    out.key = o.value(QStringLiteral("key")).toString();
    for (int i = 0; i < int(bodies.size()); ++i)
        out.bodies.push_back(bodies.at(i).toString().toUtf8());
    if (out.key.isEmpty() || out.bodies.empty() || out.time <= 0)
        return false;
    *e = out;
    return true;
}

// ------------------------------------------------------------------ Seen

bool ChatWire::Seen::has(const QString &from, const QString &id) const { return !id.isEmpty() && m_ids.count(key2(from, id)) > 0; }

void ChatWire::Seen::add(const QString &from, const QString &id, qint64 now, const QString &note)
{
    if (!id.isEmpty()) {
        m_ids[key2(from, id)] = std::make_pair(now, note);
        m_dirty = true;
    }
}

QByteArray ChatWire::Seen::save()
{
    QJsonObject o;
    for (const auto &e : m_ids)
        o.insert(e.first, QJsonArray{double(e.second.first), e.second.second});
    m_dirty = false;
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

void ChatWire::Seen::load(const QByteArray &json, qint64 now, qint64 maxAge)
{
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    for (auto it = o.begin(); it != o.end(); ++it) {
        const QJsonArray a = it.value().toArray();
        const qint64 t = qint64(a.at(0).toDouble());
        if (now - t <= maxAge && it.key().size() == 32)
            m_ids[it.key()] = std::make_pair(t, a.at(1).toString());
    }
}

QString ChatWire::Seen::note(const QString &from, const QString &id) const
{
    const auto it = m_ids.find(key2(from, id));
    return it == m_ids.end() ? QString() : it->second.second;
}

void ChatWire::Seen::prune(qint64 now, qint64 maxAge, size_t max)
{
    for (auto it = m_ids.begin(); it != m_ids.end();)
        it = now - it->second.first > maxAge ? m_ids.erase(it) : std::next(it);
    while (m_ids.size() > max) {
        auto oldest = m_ids.begin();
        for (auto it = m_ids.begin(); it != m_ids.end(); ++it)
            if (it->second.first < oldest->second.first)
                oldest = it;
        m_ids.erase(oldest);
    }
}

// ------------------------------------------------------------------ self-test
// Two (or three) apps talking, each doing what Chat does with these pieces: decide v1 or r1,
// seal, keep the sealed bytes for resends, drop copies by id before opening, open on a copy of
// the peer and keep it only when the message opened, and note "cap" from authenticated messages.

namespace
{
struct Side {
    ChatCrypto::KeyPair id;
    QString name;
    bool newApp = true;                         // false: an app without the ratchet
    std::map<QString, QString> nick;            // local only
    std::map<QString, bool> capable;
    std::map<QString, Ratchet::Peer> peers;
    ChatWire::Seen seen;
    ChatWire::Outbox outbox;
    QString mailbox = QStringLiteral("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    QString key() const { return b64(id.publicDer); }
    QByteArray sk(const QString &theirKey) const { return ChatCrypto::sharedKey(id.privateDer, id.publicDer, QByteArray::fromBase64(theirKey.toLatin1())); }

    // Seals once, keeps the bytes, returns them (a resend returns the same bytes).
    QByteArray send(const Side &to, const QString &room, const QByteArray &plain, const QString &msgId)
    {
        if (const ChatWire::Outbox::Entry *e = outbox.find(msgId))
            return e->bodies.front();
        QJsonObject f;
        f.insert(QStringLiteral("room"), room);
        f.insert(QStringLiteral("fromKey"), key());
        f.insert(QStringLiteral("fromName"), name);
        f.insert(QStringLiteral("mailbox"), mailbox);
        f.insert(QStringLiteral("id"), msgId);
        const bool r1 = newApp && ChatWire::useRatchet(room, true, capable[to.key()]);
        QJsonObject o = ChatWire::seal(f, sk(to.key()), to.key(), plain, r1 ? &peers[to.key()] : nullptr, [] { return ChatCrypto::newKeyPair(); });
        if (!newApp) {
            o.remove(QStringLiteral("cap"));
            o.remove(QStringLiteral("caps"));
        }
        const QByteArray bytes = QJsonDocument(o).toJson(QJsonDocument::Compact);
        ChatWire::Outbox::Entry e;
        e.time = 1000;
        e.key = to.key();
        e.bodies.push_back(bytes);
        outbox.put(msgId, e);
        return bytes;
    }

    // "" when dropped (a copy already seen, or it didn't open).
    QByteArray receive(const QByteArray &bytes, bool *dup = nullptr, bool dedup = true)
    {
        if (dup)
            *dup = false;
        const QJsonObject o = QJsonDocument::fromJson(bytes).object();
        const QString from = o.value(QStringLiteral("fromKey")).toString(), msgId = o.value(QStringLiteral("id")).toString();
        if (dedup && seen.has(from, msgId)) {
            if (dup)
                *dup = true;
            return QByteArray();
        }
        if (!newApp && ChatWire::isRatchet(o))
            return QByteArray();   // an app without r1 has no idea what "rh" is
        Ratchet::Peer work = peers[from];
        bool ok = false;
        const QByteArray plain = ChatWire::open(o, sk(from), id, &work, [] { return ChatCrypto::newKeyPair(); }, &ok);
        if (!ok)
            return QByteArray();
        if (ChatWire::isRatchet(o))
            peers[from] = work;
        if (newApp && ChatWire::saysCapable(o))
            capable[from] = true;
        seen.add(from, msgId, 1000);
        return plain;
    }
};

std::vector<std::string> fieldNames(const QByteArray &bytes)
{
    std::vector<std::string> out;
    const QJsonObject o = QJsonDocument::fromJson(bytes).object();
    for (const char *k : {"room", "fromKey", "fromName", "mailbox", "id", "cap", "nonce", "data", "rh", "invite"})
        if (o.value(QLatin1String(k)).type() != QJsonValue::Undefined)
            out.push_back(k);
    return out;
}
bool hasOnly(const QByteArray &bytes, std::set<std::string> want)
{
    const QJsonObject o = QJsonDocument::fromJson(bytes).object();
    std::set<std::string> got;
    for (const QString &k : o.keys())
        got.insert(k.toStdString());
    return got == want;
}
}

bool ChatWire::capsDropped(const QString &before, const QString &now)
{
    const QStringList had = before.split(QLatin1Char(' '), Qt::SkipEmptyParts), has = now.split(QLatin1Char(' '), Qt::SkipEmptyParts);
    for (const QString &t : had)
        if (!has.contains(t))
            return true;
    return false;
}

bool ChatWire::selfTest()
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const char *what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("chat wire self-test: FAILED %s\n", what);
    };

    Side a, b, old;
    a.id = ChatCrypto::newKeyPair();
    b.id = ChatCrypto::newKeyPair();
    old.id = ChatCrypto::newKeyPair();
    a.name = QStringLiteral("Raven 1111");
    b.name = QStringLiteral("Raven 2222");
    old.name = QStringLiteral("Raven 3333");
    old.newApp = false;
    a.nick[b.key()] = QStringLiteral("Grandpa Bjorn");
    b.nick[a.key()] = QStringLiteral("Uncle Leif");

    // 1. First message: nobody knows the other can do r1 yet, so v1 with "cap".
    const std::set<std::string> v1Fields{"room", "fromKey", "fromName", "mailbox", "id", "cap", "caps", "nonce", "data"};
    const std::set<std::string> r1Fields{"room", "fromKey", "fromName", "mailbox", "id", "cap", "caps", "rh", "data"};
    QByteArray m = a.send(b, QStringLiteral("private"), "hello", QStringLiteral("m1"));
    check(hasOnly(m, v1Fields), "v1 envelope has exactly room, fromKey, fromName, mailbox, id, cap, caps, nonce, data");
    check(QJsonDocument::fromJson(m).object().value(QStringLiteral("cap")).toString() == QLatin1String("r1"), "cap is \"r1\" (apps without caps test exactly that)");
    {
        bool present = false;
        const QStringList caps = ChatWire::capsOf(QJsonDocument::fromJson(m).object(), &present);
        check(present && caps.contains(QStringLiteral("r1")) && caps.contains(QStringLiteral("g1")) && caps.contains(QStringLiteral("c1")) && ChatWire::saysCapable(QJsonDocument::fromJson(m).object()),
              "caps says r1 g1 c1 next to cap");
        QJsonObject odd;
        odd.insert(QStringLiteral("caps"), QStringLiteral("  g1 zz9  r1 g1 "));
        const QStringList t = ChatWire::capsOf(odd, &present);
        check(present && t.size() == 3 && t.contains(QStringLiteral("zz9")), "caps: any order, extra spaces, unknown tokens kept apart, duplicates once");
        odd.insert(QStringLiteral("caps"), QStringLiteral("r1 g1 c1 c2 k1 k2 k3 k4 k5 k6 t1 v1 m1 m2 i1 f1 d1 j1 x1 s1"));
        check(ChatWire::capsOf(odd, &present).contains(QStringLiteral("s1")), "caps: all of Muninn's tokens are kept");
        check(ChatWire::capsOf(QJsonObject(), &present).isEmpty() && !present, "no caps field: an app without caps");
        check(capsDropped(QStringLiteral("r1 g1 n2 zz9"), QStringLiteral("r1 g1 n2")) && !capsDropped(QStringLiteral("r1 g1"), QStringLiteral("g1 r1 n2"))
                  && !capsDropped(QString(), QLatin1String(kCaps)),
              "going back to an older release is noticed by the caps it no longer says");
        check(ChatWire::useRatchet(QStringLiteral("gmsg"), true, true) && ChatWire::useRatchet(QStringLiteral("group"), true, true)
                  && ChatWire::useRatchet(QStringLiteral("greceipt"), true, true) && ChatWire::useRatchet(QStringLiteral("gphoto"), true, true)
                  && !ChatWire::useRatchet(QStringLiteral("gmsg"), true, false),
              "group rooms use the ratchet with capable peers");
    }
    check(b.receive(m) == "hello", "v1 opens");
    check(b.capable[a.key()], "cap r1 from an authenticated message marks the sender capable");

    // 2. The capability upgrade: b now sends r1, and a learns b's cap from it.
    m = b.send(a, QStringLiteral("private"), "hi back", QStringLiteral("m2"));
    check(hasOnly(m, r1Fields), "r1 envelope has exactly room, fromKey, fromName, mailbox, id, cap, rh, data (no nonce)");
    const QJsonObject rh = QJsonDocument::fromJson(m).object().value(QStringLiteral("rh")).toObject();
    check(hasOnly(QJsonDocument(rh).toJson(QJsonDocument::Compact), {"b", "d", "pn", "n"}), "rh has exactly b, d, pn, n");
    check(rh.value(QStringLiteral("pn")).isDouble() && rh.value(QStringLiteral("n")).isDouble() && rh.value(QStringLiteral("b")).isString()
              && rh.value(QStringLiteral("d")).isString(),
          "rh types: b and d strings, pn and n numbers");
    const QString data = QJsonDocument::fromJson(m).object().value(QStringLiteral("data")).toString();
    check(data.size() % 4 == 0 && QString::fromLatin1(QByteArray::fromBase64(data.toLatin1()).toBase64()) == data, "data is standard base64 with padding");
    check(a.receive(m) == "hi back", "r1 opens");
    check(a.capable[b.key()], "the r1 message's cap marks it capable too");
    m = a.send(b, QStringLiteral("private"), "both on r1", QStringLiteral("m3"));
    check(ChatWire::isRatchet(QJsonDocument::fromJson(m).object()) && b.receive(m) == "both on r1", "both sides now send r1");

    // 3. v1 from a capable contact is still accepted.
    {
        QJsonObject f;
        f.insert(QStringLiteral("room"), QStringLiteral("private"));
        f.insert(QStringLiteral("fromKey"), b.key());
        f.insert(QStringLiteral("fromName"), b.name);
        f.insert(QStringLiteral("mailbox"), b.mailbox);
        f.insert(QStringLiteral("id"), QStringLiteral("m4"));
        const QByteArray v1 = QJsonDocument(ChatWire::seal(f, b.sk(a.key()), a.key(), "plain v1", nullptr, nullptr)).toJson(QJsonDocument::Compact);
        check(a.receive(v1) == "plain v1", "v1 from a ratchet-capable contact opens");
    }

    // 4. An app without the ratchet: never says cap, so it only ever gets v1.
    m = old.send(a, QStringLiteral("private"), "old app", QStringLiteral("o1"));
    check(fieldNames(m).size() == 7 && QJsonDocument::fromJson(m).object().value(QStringLiteral("cap")).type() == QJsonValue::Undefined
              && QJsonDocument::fromJson(m).object().value(QStringLiteral("caps")).type() == QJsonValue::Undefined,
          "an app without the ratchet: no cap and no caps");
    check(a.receive(m) == "old app", "v1 from an app without the ratchet opens");
    check(!a.capable[old.key()], "no cap: not marked capable");
    m = a.send(old, QStringLiteral("private"), "still v1", QStringLiteral("o2"));
    check(!ChatWire::isRatchet(QJsonDocument::fromJson(m).object()) && old.receive(m) == "still v1", "to an app without the ratchet: v1, and it opens");
    check(!ChatWire::useRatchet(QStringLiteral("nearby"), true, true) && !ChatWire::useRatchet(QStringLiteral("private"), false, true),
          "nearby room and non-contacts stay v1");

    // 5. Retries and other routes send the very same bytes; the second copy is dropped by id first.
    const QByteArray first = a.send(b, QStringLiteral("private"), "once", QStringLiteral("m5"));
    const QByteArray again = a.send(b, QStringLiteral("private"), "once", QStringLiteral("m5"));
    check(first == again, "a resend is byte for byte the same envelope");
    check(b.receive(first) == "once", "first copy opens");
    bool dup = false;
    check(b.receive(again, &dup).isEmpty() && dup, "second copy dropped by id before the ratchet");
    {
        // Without the id check the ratchet refuses it, and the peer is left as it was.
        const QByteArray before = QJsonDocument(b.peers[a.key()].toJson()).toJson(QJsonDocument::Compact);
        check(b.receive(again, nullptr, false).isEmpty(), "a replay doesn't open twice");
        check(QJsonDocument(b.peers[a.key()].toJson()).toJson(QJsonDocument::Compact) == before, "a failed open leaves the peer unchanged");
    }
    m = a.send(b, QStringLiteral("private"), "after the duplicate", QStringLiteral("m6"));
    check(b.receive(m) == "after the duplicate", "the conversation carries on after a duplicate");

    // 6. Tampering fails quietly and changes nothing.
    {
        m = a.send(b, QStringLiteral("receipt"), "{\"ids\":[\"m6\"],\"s\":\"read\"}", QStringLiteral("r1"));
        QJsonObject o = QJsonDocument::fromJson(m).object();
        QByteArray c = QByteArray::fromBase64(o.value(QStringLiteral("data")).toString().toLatin1());
        c[0] = char(c[0] ^ 1);
        o.insert(QStringLiteral("data"), b64(c));
        const QByteArray before = QJsonDocument(b.peers[a.key()].toJson()).toJson(QJsonDocument::Compact);
        check(b.receive(QJsonDocument(o).toJson(QJsonDocument::Compact)).isEmpty(), "a tampered r1 message is dropped");
        check(QJsonDocument(b.peers[a.key()].toJson()).toJson(QJsonDocument::Compact) == before, "and the peer is unchanged");
        b.seen = ChatWire::Seen();   // the tampered copy had the same id; let the real one in
        check(b.receive(m) == "{\"ids\":[\"m6\"],\"s\":\"read\"}", "the real receipt still opens");
    }

    // 7. Photo pieces out of order (each piece is its own ratchet message).
    {
        std::vector<QByteArray> parts;
        for (int i = 0; i < 6; ++i)
            parts.push_back(a.send(b, QStringLiteral("photo"), QByteArray(1, char('A' + i)), QStringLiteral("p") + QString::number(i)));
        bool all = true;
        for (int i = 5; i >= 0; --i)
            all = all && b.receive(parts[size_t(i)]) == QByteArray(1, char('A' + i));
        check(all, "pieces arriving in any order all open");
    }

    // 8. The peer survives a save and load, and so does a kept envelope.
    {
        bool pok = false;
        b.peers[a.key()] = Ratchet::Peer::fromJson(QJsonDocument::fromJson(QJsonDocument(b.peers[a.key()].toJson()).toJson(QJsonDocument::Compact)).object(), &pok);
        m = a.send(b, QStringLiteral("private"), "after reload", QStringLiteral("m7"));
        check(pok && b.receive(m) == "after reload", "peer saved and loaded keeps working");
        ChatWire::Outbox::Entry e;
        check(ChatWire::Outbox::load(ChatWire::Outbox::save(*a.outbox.find(QStringLiteral("m7"))), &e) && e.bodies.front() == m,
              "a kept envelope reads back as the same bytes");
        ChatWire::Outbox box;
        e.time = 1000;
        box.put(QStringLiteral("x"), e);
        check(box.expired(1000 + 86400000, 86400000).empty() && box.expired(1001 + 86400000, 86400000).size() == 1, "kept envelopes expire after a day");
    }

    // 9. Nicknames stay on this computer: never in any envelope.
    {
        bool clean = true;
        for (const QString &id : a.outbox.ids())
            for (const QByteArray &bytes : a.outbox.find(id)->bodies)
                clean = clean && !bytes.contains("Grandpa") && !bytes.contains(a.nick[b.key()].toUtf8());
        for (const QString &id : b.outbox.ids())
            for (const QByteArray &bytes : b.outbox.find(id)->bodies)
                clean = clean && !bytes.contains("Uncle Leif");
        check(clean, "no nickname in any envelope");
    }

    // 10. Seen keeps an unsend's target for answering a second copy, and forgets after a while.
    {
        ChatWire::Seen s;
        s.add(QStringLiteral("k"), QStringLiteral("u1"), 1000, QStringLiteral("m1"));
        check(s.has(QStringLiteral("k"), QStringLiteral("u1")) && s.note(QStringLiteral("k"), QStringLiteral("u1")) == QLatin1String("m1")
                  && !s.has(QStringLiteral("other"), QStringLiteral("u1")),
              "seen ids are per sender and keep their note");
        s.prune(1000 + 3 * 86400000LL, 2 * 86400000LL, 100);
        check(!s.has(QStringLiteral("k"), QStringLiteral("u1")), "seen ids are forgotten after two days");
    }

    std::printf("chat wire self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
