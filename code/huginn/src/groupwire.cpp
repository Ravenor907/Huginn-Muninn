#include "groupwire.h"
#include "sharing.h"

#include <QCryptographicHash>
#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRandomGenerator>
#include <QStringList>
#include <algorithm>
#include <cstdio>
#include <openssl/evp.h>
#include <openssl/x509.h>

const char GroupWire::kDomain[] = "huginn-muninn group v1";
const char GroupWire::kCodePrefix[] = "MUNINNG1:";
const char GroupWire::kAboutCap[] = "g3";

namespace
{
const qint64 kMaxInt = qint64(1) << 53;

QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QString b64url(const QByteArray &b) { return QString::fromLatin1(b.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals)); }

QByteArray randomBytes(int n)
{
    QByteArray out(n, '\0');
    for (int i = 0; i < n; ++i)
        out[i] = char(QRandomGenerator::system()->bounded(256));
    return out;
}

bool urlSafe(const QString &s)
{
    for (int i = 0; i < s.size(); ++i) {
        const ushort c = s.at(i).unicode();
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'))
            return false;
    }
    return !s.isEmpty();
}

// The decoded size of base64url text (no padding), or -1 when it isn't base64url.
int urlBytes(const QString &s)
{
    if (!urlSafe(s) || s.size() % 4 == 1)
        return -1;
    return int(QByteArray::fromBase64(s.toLatin1(), QByteArray::Base64UrlEncoding).size());
}

bool goodText(const QString &s)
{
    for (int i = 0; i < s.size(); ++i) {
        const ushort c = s.at(i).unicode();
        if (c < 0x20 || c == 0x7F)
            return false;
    }
    return true;
}

void canonInto(const QJsonValue &v, QByteArray &out, bool &ok)
{
    if (!ok)
        return;
    switch (v.type()) {
    case QJsonValue::Bool:
        out += v.toBool() ? "true" : "false";
        return;
    case QJsonValue::Double: {
        const qint64 n = v.toInteger(-1);
        if (n < 0 || n >= kMaxInt) {
            ok = false;
            return;
        }
        out += QByteArray::number(n);
        return;
    }
    case QJsonValue::String: {
        const QString s = v.toString();
        if (!goodText(s)) {
            ok = false;
            return;
        }
        out += '"';
        const QByteArray u = s.toUtf8();
        for (int i = 0; i < u.size(); ++i) {
            const char c = u.at(i);
            if (c == '"' || c == '\\')
                out += '\\';
            out += c;
        }
        out += '"';
        return;
    }
    case QJsonValue::Array: {
        const QJsonArray a = v.toArray();
        out += '[';
        for (int i = 0; i < int(a.size()); ++i) {
            if (i)
                out += ',';
            canonInto(a.at(i), out, ok);
        }
        out += ']';
        return;
    }
    case QJsonValue::Object: {
        const QJsonObject o = v.toObject();
        QStringList keys = o.keys();
        for (const QString &k : keys)
            for (int i = 0; i < k.size(); ++i)
                if (k.at(i).unicode() >= 0x80) {
                    ok = false;
                    return;
                }
        std::sort(keys.begin(), keys.end());   // ASCII: UTF-16 order is byte order
        out += '{';
        for (int i = 0; i < int(keys.size()); ++i) {
            if (i)
                out += ',';
            canonInto(QJsonValue(keys.at(i)), out, ok);
            out += ':';
            canonInto(o.value(keys.at(i)), out, ok);
        }
        out += '}';
        return;
    }
    default:
        ok = false;   // null, undefined
    }
}

EVP_PKEY *readPublic(const QByteArray &der)
{
    const unsigned char *p = reinterpret_cast<const unsigned char *>(der.constData());
    return der.isEmpty() ? nullptr : d2i_PUBKEY(nullptr, &p, long(der.size()));
}

EVP_PKEY *readPrivate(const QByteArray &der)
{
    const unsigned char *p = reinterpret_cast<const unsigned char *>(der.constData());
    return der.isEmpty() ? nullptr : d2i_AutoPrivateKey(nullptr, &p, long(der.size()));
}

QStringList strings(const QJsonValue &v, bool *ok)
{
    QStringList out;
    if (!v.isArray()) {
        *ok = false;
        return out;
    }
    const QJsonArray a = v.toArray();
    for (int i = 0; i < int(a.size()); ++i) {
        if (!a.at(i).isString())
            *ok = false;
        out << a.at(i).toString();
    }
    return out;
}

bool sortedUnique(const QStringList &l)
{
    for (int i = 1; i < int(l.size()); ++i)
        if (!(l.at(i - 1) < l.at(i)))
            return false;
    return true;
}

bool sameFields(const QJsonObject &o, QStringList want)
{
    QStringList got = o.keys();
    std::sort(got.begin(), got.end());
    std::sort(want.begin(), want.end());
    return got == want;
}

bool isWhole(const QJsonValue &v) { return v.isDouble() && v.toInteger(-1) >= 0 && v.toInteger(-1) < kMaxInt; }
}

QByteArray GroupWire::canon(const QJsonValue &v, bool *ok)
{
    QByteArray out;
    bool good = true;
    canonInto(v, out, good);
    if (ok)
        *ok = good;
    return good ? out : QByteArray();
}

QByteArray GroupWire::signedBytes(const QString &domain, const QJsonObject &obj)
{
    QJsonObject o = obj;
    o.remove(QStringLiteral("sig"));
    bool ok = false;
    const QByteArray c = canon(o, &ok);
    return ok ? domain.toUtf8() + '\n' + c : QByteArray();
}

QString GroupWire::hash(const QJsonObject &obj)
{
    bool ok = false;
    const QByteArray c = canon(obj, &ok);
    return ok ? QString::fromLatin1(QCryptographicHash::hash(c, QCryptographicHash::Sha256).toHex()) : QString();
}

QJsonObject GroupWire::sign(const QByteArray &privateDer, const QString &domain, const QJsonObject &obj, bool *ok)
{
    if (ok)
        *ok = false;
    const QByteArray msg = signedBytes(domain, obj);
    EVP_PKEY *key = readPrivate(privateDer);
    if (msg.isEmpty() || !key) {
        EVP_PKEY_free(key);
        return obj;
    }
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    size_t len = 0;
    QByteArray sig;
    bool good = ctx && EVP_DigestSignInit(ctx, nullptr, EVP_sha256(), nullptr, key) > 0
        && EVP_DigestSign(ctx, nullptr, &len, reinterpret_cast<const unsigned char *>(msg.constData()), size_t(msg.size())) > 0;
    if (good) {
        sig.resize(qsizetype(len));
        good = EVP_DigestSign(ctx, reinterpret_cast<unsigned char *>(sig.data()), &len, reinterpret_cast<const unsigned char *>(msg.constData()),
                              size_t(msg.size())) > 0;
        sig.resize(qsizetype(len));
    }
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    if (!good)
        return obj;
    QJsonObject out = obj;
    out.insert(QStringLiteral("sig"), b64(sig));
    if (ok)
        *ok = true;
    return out;
}

bool GroupWire::verify(const QString &publicKeyB64, const QString &domain, const QJsonObject &obj)
{
    const QJsonValue s = obj.value(QStringLiteral("sig"));
    if (!s.isString())
        return false;
    const QByteArray sig = QByteArray::fromBase64(s.toString().toLatin1());
    const QByteArray msg = signedBytes(domain, obj);
    EVP_PKEY *key = readPublic(QByteArray::fromBase64(publicKeyB64.toLatin1()));
    if (msg.isEmpty() || sig.isEmpty() || !key) {
        EVP_PKEY_free(key);
        return false;
    }
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    const bool good = ctx && EVP_DigestVerifyInit(ctx, nullptr, EVP_sha256(), nullptr, key) > 0
        && EVP_DigestVerify(ctx, reinterpret_cast<const unsigned char *>(sig.constData()), size_t(sig.size()),
                            reinterpret_cast<const unsigned char *>(msg.constData()), size_t(msg.size())) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return good;
}

int GroupWire::length(const QString &s)
{
    int n = 0;
    for (int i = 0; i < s.size(); ++i) {
        const ushort c = s.at(i).unicode();
        if (c < 0xDC00 || c > 0xDFFF)
            ++n;   // a low surrogate belongs to the character before it
    }
    return n;
}

QString GroupWire::clip(const QString &s, int max)
{
    int n = 0;
    for (int i = 0; i < s.size(); ++i) {
        const ushort c = s.at(i).unicode();
        if (c >= 0xDC00 && c <= 0xDFFF)
            continue;   // a low surrogate belongs to the character before it
        if (n == max)
            return s.left(i);
        ++n;
    }
    return s;
}

// ------------------------------------------------------------------ group state

QString GroupWire::checkState(const QJsonObject &s)
{
    // Cap d1 (section 16.2): a Family or Work group also has "kind" and maybe "days".
    QJsonObject base = s;
    base.remove(QStringLiteral("kind"));
    base.remove(QStringLiteral("days"));
    // Cap j1 (17.1): "Members can invite", only ever true (off is left out).
    base.remove(QStringLiteral("mi"));
    if (s.contains(QStringLiteral("mi")) && s.value(QStringLiteral("mi")) != QJsonValue(true))
        return QStringLiteral("mi");
    // Cap f2 (27.3): the group's file rules, "fs" and "fm", each left out when not set.
    base.remove(QStringLiteral("fs"));
    base.remove(QStringLiteral("fm"));
    if (const QString rules = Sharing::checkRules(s); !rules.isEmpty())
        return rules;
    // Cap g3 (28): the group's about text, left out when there is none.
    base.remove(QStringLiteral("about"));
    if (s.contains(QStringLiteral("about"))) {
        const QJsonValue t = s.value(QStringLiteral("about"));
        if (!t.isString() || t.toString().trimmed().isEmpty() || !goodText(t.toString()) || length(t.toString()) > kMaxAbout)
            return QStringLiteral("about");
    }
    if (!sameFields(base, {QStringLiteral("g"), QStringLiteral("v"), QStringLiteral("owner"), QStringLiteral("by"), QStringLiteral("name"),
                           QStringLiteral("admins"), QStringLiteral("members"), QStringLiteral("channels"), QStringLiteral("ts"), QStringLiteral("sig")}))
        return QStringLiteral("fields");
    if (s.contains(QStringLiteral("kind")) && s.value(QStringLiteral("kind")) != QJsonValue(QStringLiteral("family"))
        && s.value(QStringLiteral("kind")) != QJsonValue(QStringLiteral("work")))
        return QStringLiteral("kind");
    if (s.contains(QStringLiteral("days"))) {
        if (!s.contains(QStringLiteral("kind")))
            return QStringLiteral("days without kind");
        const qint64 d = isWhole(s.value(QStringLiteral("days"))) ? s.value(QStringLiteral("days")).toInteger() : -1;
        if (d < 7 || d > 30)
            return QStringLiteral("days");
    }
    if (!isGroupId(s.value(QStringLiteral("g")).toString()))
        return QStringLiteral("group id");
    if (!isWhole(s.value(QStringLiteral("v"))) || s.value(QStringLiteral("v")).toInteger() < 1)
        return QStringLiteral("version");
    if (!isWhole(s.value(QStringLiteral("ts"))) || !s.value(QStringLiteral("sig")).isString())
        return QStringLiteral("fields");
    const QJsonValue nv = s.value(QStringLiteral("name"));
    if (!nv.isString() || !goodText(nv.toString()) || length(nv.toString()) > kMaxName)
        return QStringLiteral("name");
    if (nv.toString().trimmed().isEmpty())
        return QStringLiteral("empty name");
    if (!s.value(QStringLiteral("members")).isArray())
        return QStringLiteral("members");
    const QJsonArray members = s.value(QStringLiteral("members")).toArray();
    QStringList keys;
    for (int i = 0; i < int(members.size()); ++i) {
        if (!members.at(i).isObject())
            return QStringLiteral("member fields");
        const QJsonObject m = members.at(i).toObject();
        if (!sameFields(m, {QStringLiteral("k"), QStringLiteral("n"), QStringLiteral("p")}) || !m.value(QStringLiteral("k")).isString()
            || !m.value(QStringLiteral("n")).isString() || !m.value(QStringLiteral("p")).isString())
            return QStringLiteral("member fields");
        keys << m.value(QStringLiteral("k")).toString();
    }
    if (!sortedUnique(keys))
        return QStringLiteral("members not sorted/unique");
    if (keys.isEmpty() || keys.size() > kMaxMembers)
        return QStringLiteral("member count");
    for (int i = 0; i < int(members.size()); ++i) {
        const QJsonObject m = members.at(i).toObject();
        const QString n = m.value(QStringLiteral("n")).toString();
        if (!goodText(n) || length(n) > kMaxName)
            return QStringLiteral("member name");
        const QString p = m.value(QStringLiteral("p")).toString();
        if (!p.isEmpty() && !isMailbox(p))
            return QStringLiteral("mailbox");
    }
    bool ok = true;
    const QStringList admins = strings(s.value(QStringLiteral("admins")), &ok);
    if (!ok || !sortedUnique(admins))
        return QStringLiteral("admins");
    for (const QString &a : admins)
        if (!keys.contains(a))
            return QStringLiteral("admins");
    if (!admins.contains(s.value(QStringLiteral("owner")).toString()))
        return QStringLiteral("owner not admin");
    if (!admins.contains(s.value(QStringLiteral("by")).toString()))
        return QStringLiteral("signer not admin in the new state");
    if (!s.value(QStringLiteral("channels")).isArray())
        return QStringLiteral("channels");
    const QJsonArray channels = s.value(QStringLiteral("channels")).toArray();
    QStringList ids;
    for (int i = 0; i < int(channels.size()); ++i) {
        if (!channels.at(i).isObject())
            return QStringLiteral("channel fields");
        const QString id = channels.at(i).toObject().value(QStringLiteral("id")).toString();
        if (ids.contains(id))
            return QStringLiteral("channels");
        ids << id;
    }
    if (ids.isEmpty() || ids.size() > kMaxChannels)
        return QStringLiteral("channels");
    const QJsonObject first = channels.at(0).toObject();
    if (first.value(QStringLiteral("id")).toString() != QLatin1String("main") || first.value(QStringLiteral("t")).toString() != QLatin1String("text")
        || !sameFields(first, {QStringLiteral("id"), QStringLiteral("n"), QStringLiteral("t")}))
        return QStringLiteral("main channel first");
    for (int i = 0; i < int(channels.size()); ++i) {
        const QJsonObject c = channels.at(i).toObject();
        const QString t = c.value(QStringLiteral("t")).toString();
        // Cap g3 (28.2): "ao" (only admins post) on a text channel, only ever true.
        QJsonObject plain = c;
        plain.remove(QStringLiteral("ao"));
        if (!sameFields(plain, {QStringLiteral("id"), QStringLiteral("n"), QStringLiteral("t")}) || (t != QLatin1String("text") && t != QLatin1String("voice")))
            return QStringLiteral("channel fields");
        if (c.contains(QStringLiteral("ao")) && (c.value(QStringLiteral("ao")) != QJsonValue(true) || t != QLatin1String("text")))
            return QStringLiteral("channel fields");
        const QString id = c.value(QStringLiteral("id")).toString();
        if (id != QLatin1String("main") && !isChannelId(id))
            return QStringLiteral("channel id");
        const QJsonValue n = c.value(QStringLiteral("n"));
        if (!n.isString() || !goodText(n.toString()) || length(n.toString()) > kMaxChannelName || n.toString().isEmpty())
            return QStringLiteral("channel name");
    }
    bool cok = false;
    const QByteArray c = canon(s, &cok);
    if (!cok)
        return QStringLiteral("not canonical");
    if (c.size() > kMaxStateBytes)
        return QStringLiteral("too big");
    return QString();
}

GroupWire::Verdict GroupWire::accept(const QJsonObject *cur, const QJsonObject &next)
{
    const QString bad = checkState(next);
    if (!bad.isEmpty())
        return {false, QStringLiteral("malformed: ") + bad};
    const QString by = next.value(QStringLiteral("by")).toString();
    if (!verify(by, QLatin1String(kDomain), next))
        return {false, QStringLiteral("bad signature")};
    if (!cur)
        return {true, QStringLiteral("first state, signed by one of its own admins")};
    if (next.value(QStringLiteral("g")).toString() != cur->value(QStringLiteral("g")).toString())
        return {false, QStringLiteral("other group")};
    if (!adminsOf(*cur).contains(by))
        return {false, QStringLiteral("signer is not an admin in the current state")};
    const QString owner = cur->value(QStringLiteral("owner")).toString();
    if (next.value(QStringLiteral("owner")).toString() != owner && by != owner)
        return {false, QStringLiteral("only the owner can hand over ownership")};
    const qint64 nv = next.value(QStringLiteral("v")).toInteger(), cv = cur->value(QStringLiteral("v")).toInteger();
    // Cap s1 (section 21): never more than 1000 versions ahead, so no admin can push v out of everyone else's reach.
    if (nv > cv + kMaxAhead)
        return {false, QStringLiteral("too far ahead")};
    if (nv > cv)
        return {true, QStringLiteral("higher version")};
    if (nv == cv) {
        // At the same version a state its owner signed wins, so the owner can always settle things.
        if (byOwner(next) != byOwner(*cur))
            return byOwner(next) ? Verdict{true, QStringLiteral("same version, signed by the owner")} : Verdict{false, QStringLiteral("the owner's stands")};
        if (hash(next) < hash(*cur))
            return {true, QStringLiteral("same version, lower hash")};
    }
    return {false, QStringLiteral("not newer")};
}

bool GroupWire::byOwner(const QJsonObject &s)
{
    return s.value(QStringLiteral("by")).toString() == s.value(QStringLiteral("owner")).toString();
}

bool GroupWire::wins(const QJsonObject &a, const QJsonObject &b)
{
    const qint64 av = a.value(QStringLiteral("v")).toInteger(), bv = b.value(QStringLiteral("v")).toInteger();
    if (av != bv)
        return av > bv;
    if (byOwner(a) != byOwner(b))
        return byOwner(a);
    return hash(a) <= hash(b);
}

// ------------------------------------------------------------------ ids

QString GroupWire::envelopeId(const QString &logicalId, const QString &recipientKey)
{
    return logicalId + QLatin1Char('-')
        + QString::fromLatin1(QCryptographicHash::hash(recipientKey.toUtf8(), QCryptographicHash::Sha256).toHex().left(8));
}

QString GroupWire::newGroupId() { return b64url(randomBytes(16)); }
QString GroupWire::newChannelId() { return b64url(randomBytes(8)); }
QString GroupWire::newLogicalId() { return QString::fromLatin1(randomBytes(16).toHex()); }
QString GroupWire::newToken() { return b64url(randomBytes(16)); }
bool GroupWire::isGroupId(const QString &g) { return g.size() == 22 && urlBytes(g) == 16; }
bool GroupWire::isChannelId(const QString &c) { return c.size() == 11 && urlBytes(c) == 8; }

bool GroupWire::isLogicalId(const QString &id)
{
    if (id.size() != 32)
        return false;
    for (int i = 0; i < id.size(); ++i) {
        const ushort c = id.at(i).unicode();
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            return false;
    }
    return true;
}

bool GroupWire::isMailbox(const QString &p)
{
    if (p.size() != 64)
        return false;
    for (int i = 0; i < p.size(); ++i) {
        const ushort c = p.at(i).unicode();
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            return false;
    }
    return true;
}

// ------------------------------------------------------------------ building states

QJsonObject GroupWire::member(const QString &key, const QString &name, const QString &mailbox)
{
    return QJsonObject{{QStringLiteral("k"), key}, {QStringLiteral("n"), cleanName(name, kMaxName)}, {QStringLiteral("p"), isMailbox(mailbox) ? mailbox : QString()}};
}

QString GroupWire::cleanName(const QString &name, int max)
{
    // Names in a state are at most [max] characters and never hold control characters.
    QString n;
    int count = 0;
    const QString t = name.simplified();
    for (int i = 0; i < t.size() && count < max; ++i) {
        const QChar c = t.at(i);
        if (c.unicode() < 0x20 || c.unicode() == 0x7F)
            continue;
        if (c.isHighSurrogate()) {
            if (i + 1 >= t.size() || !t.at(i + 1).isLowSurrogate())
                continue;
            n += c;
            n += t.at(++i);
        } else if (!c.isLowSurrogate()) {
            n += c;
        } else {
            continue;
        }
        ++count;
    }
    return n;
}

QJsonObject GroupWire::withMembers(QJsonObject s, QList<QJsonObject> members)
{
    std::sort(members.begin(), members.end(), [](const QJsonObject &a, const QJsonObject &b) {
        return a.value(QStringLiteral("k")).toString() < b.value(QStringLiteral("k")).toString();
    });
    QJsonArray a;
    QStringList seen;
    for (const QJsonObject &m : members) {
        const QString k = m.value(QStringLiteral("k")).toString();
        if (seen.contains(k))
            continue;
        seen << k;
        a.append(m);
    }
    s.insert(QStringLiteral("members"), a);
    return s;
}

QJsonObject GroupWire::withAdmins(QJsonObject s, QStringList admins)
{
    admins.removeDuplicates();
    std::sort(admins.begin(), admins.end());
    s.insert(QStringLiteral("admins"), QJsonArray::fromStringList(admins));
    return s;
}

QStringList GroupWire::keysOf(const QJsonObject &s)
{
    QStringList out;
    const QJsonArray a = s.value(QStringLiteral("members")).toArray();
    for (int i = 0; i < int(a.size()); ++i)
        out << a.at(i).toObject().value(QStringLiteral("k")).toString();
    return out;
}

QStringList GroupWire::adminsOf(const QJsonObject &s)
{
    QStringList out;
    const QJsonArray a = s.value(QStringLiteral("admins")).toArray();
    for (int i = 0; i < int(a.size()); ++i)
        out << a.at(i).toString();
    return out;
}

bool GroupWire::isMember(const QJsonObject &s, const QString &key) { return keysOf(s).contains(key); }
bool GroupWire::isAdmin(const QJsonObject &s, const QString &key) { return adminsOf(s).contains(key); }

QJsonObject GroupWire::memberEntry(const QJsonObject &s, const QString &key)
{
    const QJsonArray a = s.value(QStringLiteral("members")).toArray();
    for (int i = 0; i < int(a.size()); ++i)
        if (a.at(i).toObject().value(QStringLiteral("k")).toString() == key)
            return a.at(i).toObject();
    return QJsonObject();
}

QJsonObject GroupWire::channel(const QJsonObject &s, const QString &id)
{
    const QJsonArray a = s.value(QStringLiteral("channels")).toArray();
    for (int i = 0; i < int(a.size()); ++i)
        if (a.at(i).toObject().value(QStringLiteral("id")).toString() == id)
            return a.at(i).toObject();
    return QJsonObject();
}

// ------------------------------------------------------------------ group codes

QString GroupWire::code(const QString &g, const QString &groupName, const QString &adminKey, const QString &adminName, const QString &mailbox,
                        const QString &token, qint64 expiresMs)
{
    QJsonObject o{{QStringLiteral("g"), g}, {QStringLiteral("gn"), groupName}, {QStringLiteral("a"), adminKey}, {QStringLiteral("an"), adminName},
                  {QStringLiteral("p"), mailbox}, {QStringLiteral("i"), token}, {QStringLiteral("x"), expiresMs / 1000}};
    return QLatin1String(kCodePrefix) + b64url(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

const char GroupWire::kAddPage[] = "https://ravenor907.github.io/Huginn-Muninn/add/#";
const qint64 GroupWire::kNeverS = qint64(100) * 365 * 86400;

QString GroupWire::linkId(const QString &token)
{
    return QString::fromLatin1(QCryptographicHash::hash(QByteArrayLiteral("link|") + token.toUtf8(), QCryptographicHash::Sha256).toHex().left(16));
}

bool GroupWire::linkOk(qint64 x, int max, int uses, bool off, bool makerIn, bool makerMay, qint64 nowMs)
{
    return !off && (x == 0 || x > nowMs) && (max == 0 || uses < max) && makerIn && makerMay;
}

QVariantMap GroupWire::parseCode(const QString &raw, qint64 nowMs)
{
    // A pasted invite link too (some apps write its ":" as "%3A").
    QString text = raw;
    text.replace(QStringLiteral("MUNINNG1%3A"), QLatin1String(kCodePrefix), Qt::CaseInsensitive);
    const QString prefix = QLatin1String(kCodePrefix);
    const int at = int(text.indexOf(prefix));
    if (at < 0)
        return {};
    QString body;
    for (int i = at + int(prefix.size()); i < text.size(); ++i) {
        const QChar c = text.at(i);
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    const QJsonObject o = QJsonDocument::fromJson(QByteArray::fromBase64(body.toLatin1(), QByteArray::Base64UrlEncoding)).object();
    const QString g = o.value(QStringLiteral("g")).toString(), a = o.value(QStringLiteral("a")).toString(), i = o.value(QStringLiteral("i")).toString();
    EVP_PKEY *key = readPublic(QByteArray::fromBase64(a.toLatin1()));
    const bool realKey = key != nullptr;
    EVP_PKEY_free(key);
    if (!isGroupId(g) || !realKey || i.isEmpty())
        return {};
    QString name = o.value(QStringLiteral("gn")).toString().trimmed();
    if (name.isEmpty() || !goodText(name))
        name = QStringLiteral("A group");
    QString adminName = o.value(QStringLiteral("an")).toString().trimmed();
    if (adminName.isEmpty() || !goodText(adminName))
        adminName = QStringLiteral("Someone");
    const qint64 x = o.value(QStringLiteral("x")).toInteger(0) * 1000;
    const QString p = o.value(QStringLiteral("p")).toString();
    return {{QStringLiteral("g"), g},
            {QStringLiteral("name"), name.left(kMaxName)},
            {QStringLiteral("admin"), a},
            {QStringLiteral("adminName"), adminName.left(kMaxName)},
            {QStringLiteral("mailbox"), isMailbox(p) ? p : QString()},
            {QStringLiteral("token"), i},
            {QStringLiteral("expires"), x},
            {QStringLiteral("expired"), x <= nowMs}};
}

// ------------------------------------------------------------------ self-test

namespace
{
QJsonObject readJson(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return QJsonObject();
    return QJsonDocument::fromJson(f.readAll()).object();
}
}

bool GroupWire::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("proto 0.6 self-test: FAILED %s\n", qPrintable(what));
    };
    const QJsonObject v = readJson(vectorsPath);
    const QJsonObject c = readJson(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("chat-vectors.json")));
    if (v.isEmpty() || c.isEmpty()) {
        std::printf("proto 0.6 self-test: can't read %s and chat-vectors.json next to it\n", qPrintable(vectorsPath));
        return false;
    }
    check(v.value(QStringLiteral("domains")).toObject().value(QStringLiteral("group")).toString() == QLatin1String(kDomain), "group domain");
    // Section 9: what is sent is cut at a whole character, never over the limit and never half an emoji.
    {
        const QString emoji = QString::fromUtf8("\xF0\x9F\xA6\x8A");   // one character, two UTF-16 units
        const QString cut = clip(QString(1999, QLatin1Char('a')) + emoji + emoji, 2000);
        check(clip(QStringLiteral("abc"), 2) == QLatin1String("ab") && length(cut) == 2000 && cut.endsWith(emoji)
                  && length(clip(emoji.repeated(2001), 2000)) == 2000 && clip(emoji.repeated(2001), 2000).size() == 4000,
              QStringLiteral("text is cut at whole characters"));
    }

    // Canonical JSON: the same bytes as the reference, and the same refusals.
    const QJsonArray good = v.value(QStringLiteral("canonical")).toObject().value(QStringLiteral("good")).toArray();
    for (int i = 0; i < int(good.size()); ++i) {
        const QJsonObject e = good.at(i).toObject();
        bool ok = false;
        const QByteArray out = canon(e.value(QStringLiteral("value")), &ok);
        check(ok && out == e.value(QStringLiteral("canonical")).toString().toUtf8(), QStringLiteral("canonical good %1").arg(i));
    }
    const QJsonArray refuse = v.value(QStringLiteral("canonical")).toObject().value(QStringLiteral("refuse")).toArray();
    for (int i = 0; i < int(refuse.size()); ++i) {
        bool ok = true;
        canon(QJsonDocument::fromJson(refuse.at(i).toString().toUtf8()).object(), &ok);
        check(!ok, QStringLiteral("canonical refuses %1").arg(refuse.at(i).toString()));
    }

    // Group states: signed bytes, hashes and signatures, then acceptance in order.
    const QJsonObject group = v.value(QStringLiteral("group")).toObject();
    const QJsonObject states = group.value(QStringLiteral("states")).toObject();
    QHash<QString, QJsonObject> st;
    for (const QString &name : states.keys()) {
        const QJsonObject e = states.value(name).toObject();
        const QJsonObject s = e.value(QStringLiteral("state")).toObject();
        st.insert(name, s);
        check(signedBytes(QLatin1String(kDomain), s) == e.value(QStringLiteral("signed")).toString().toUtf8(), "signed bytes of " + name);
        check(hash(s) == e.value(QStringLiteral("hash")).toString(), "hash of " + name);
        const bool tampered = name.startsWith(QLatin1String("tampered"));
        check(verify(s.value(QStringLiteral("by")).toString(), QLatin1String(kDomain), s) != tampered,
              tampered ? "tampered state fails its signature" : "signature of " + name);
        check(checkState(s).isEmpty(), "well-formed " + name);
    }
    check(st.size() == 9, "nine states in the vectors");
    const QJsonArray acc = group.value(QStringLiteral("accept")).toArray();
    for (int i = 0; i < int(acc.size()); ++i) {
        const QJsonObject a = acc.at(i).toObject();
        const QString curName = a.value(QStringLiteral("current")).toString(), newName = a.value(QStringLiteral("new")).toString();
        const QJsonObject cur = st.value(curName);
        const Verdict r = accept(curName.isEmpty() ? nullptr : &cur, st.value(newName));
        check(r.ok == a.value(QStringLiteral("accept")).toBool() && r.why == a.value(QStringLiteral("why")).toString(),
              QStringLiteral("accept %1 -> %2: got %3 (%4)").arg(curName.isEmpty() ? QStringLiteral("none") : curName, newName, r.ok ? QStringLiteral("yes") : QStringLiteral("no"), r.why));
    }
    check(acc.size() == 14, "fourteen acceptance steps");
    const QJsonObject conflict = group.value(QStringLiteral("conflict")).toObject();
    const QString wa = conflict.value(QStringLiteral("a")).toString(), wb = conflict.value(QStringLiteral("b")).toString();
    const QString winner = wins(st.value(wa), st.value(wb)) ? wa : wb;
    check(winner == conflict.value(QStringLiteral("winner")).toString(), "conflict winner");
    check(wins(st.value(wb), st.value(wa)) == (winner == wb), "conflict rule is symmetric");
    const QJsonArray ids = group.value(QStringLiteral("envelopeIds")).toArray();
    for (int i = 0; i < int(ids.size()); ++i) {
        const QJsonObject e = ids.at(i).toObject();
        check(envelopeId(e.value(QStringLiteral("gid")).toString(), e.value(QStringLiteral("to")).toString()) == e.value(QStringLiteral("id")).toString(),
              QStringLiteral("envelope id %1").arg(i));
    }
    check(ids.size() == 3 && isLogicalId(ids.at(0).toObject().value(QStringLiteral("gid")).toString()), "envelope ids present");
    check(group.value(QStringLiteral("g")).toString() == st.value(QStringLiteral("s1")).value(QStringLiteral("g")).toString() && isGroupId(group.value(QStringLiteral("g")).toString()),
          "group id");

    // Signing here (random k) verifies, and a state we build is well-formed and accepted.
    const QByteArray privA = QByteArray::fromBase64(c.value(QStringLiteral("privA")).toString().toLatin1());
    const QString kA = c.value(QStringLiteral("pubA")).toString(), kB = c.value(QStringLiteral("pubB")).toString();
    {
        QJsonObject s2 = st.value(QStringLiteral("s2"));
        s2.remove(QStringLiteral("sig"));
        s2.insert(QStringLiteral("v"), 9);
        s2.insert(QStringLiteral("name"), QStringLiteral("Ásgarð ᚺᚢᚷᛁᚾᚾ \"hall\""));
        bool ok = false;
        const QJsonObject mine = sign(privA, QLatin1String(kDomain), s2, &ok);
        check(ok && verify(kA, QLatin1String(kDomain), mine) && !verify(kB, QLatin1String(kDomain), mine), "a state signed here verifies, with A only");
        const QJsonObject cur = st.value(QStringLiteral("s2"));
        check(accept(&cur, mine).ok, "and is accepted over s2");
        // Through JSON text and back (how it travels): still the same bytes.
        const QJsonObject back = QJsonDocument::fromJson(QJsonDocument(mine).toJson(QJsonDocument::Compact)).object();
        check(signedBytes(QLatin1String(kDomain), back) == signedBytes(QLatin1String(kDomain), mine) && verify(kA, QLatin1String(kDomain), back),
              "a state survives its trip through JSON text");
        QJsonObject wrong = mine;
        wrong.insert(QStringLiteral("name"), QStringLiteral("Ásgarð"));
        check(!verify(kA, QLatin1String(kDomain), wrong), "a changed name breaks the signature");
    }

    // Limits: each makes s1 malformed.
    {
        const QJsonObject s1 = st.value(QStringLiteral("s1"));
        auto bad = [&](QJsonObject s, const char *what) { check(!checkState(s).isEmpty(), QStringLiteral("refuses ") + QLatin1String(what)); };
        QJsonObject s = s1;
        s.insert(QStringLiteral("name"), QString(41, QLatin1Char('x')));
        bad(s, "a 41 character name");
        s.insert(QStringLiteral("name"), QStringLiteral("   "));
        bad(s, "a name of only spaces");
        s = s1;
        s.insert(QStringLiteral("name"), QStringLiteral("Line\nbreak"));
        bad(s, "a control character");
        s = s1;
        s.insert(QStringLiteral("v"), 0);
        bad(s, "version 0");
        s = s1;
        s.insert(QStringLiteral("v"), 1.5);
        bad(s, "a fractional version");
        s = s1;
        s.insert(QStringLiteral("extra"), 1);
        bad(s, "an extra field");
        s = s1;
        QJsonArray m = s.value(QStringLiteral("members")).toArray();
        const QJsonValue firstMember = m.at(0);
        m.removeAt(0);
        m.append(firstMember);
        s.insert(QStringLiteral("members"), m);
        bad(s, "unsorted members");
        s = s1;
        s.insert(QStringLiteral("channels"), QJsonArray{QJsonObject{{QStringLiteral("id"), QStringLiteral("AAAAAAAAAAA")}, {QStringLiteral("n"), QStringLiteral("x")}, {QStringLiteral("t"), QStringLiteral("text")}}});
        bad(s, "no main channel first");
        s = s1;
        QJsonArray ch = s.value(QStringLiteral("channels")).toArray();
        ch.append(QJsonObject{{QStringLiteral("id"), QStringLiteral("short")}, {QStringLiteral("n"), QStringLiteral("x")}, {QStringLiteral("t"), QStringLiteral("text")}});
        s.insert(QStringLiteral("channels"), ch);
        bad(s, "a bad channel id");
        s = s1;
        ch = s.value(QStringLiteral("channels")).toArray();
        ch.append(QJsonObject{{QStringLiteral("id"), newChannelId()}, {QStringLiteral("n"), QStringLiteral("x")}, {QStringLiteral("t"), QStringLiteral("video")}});
        s.insert(QStringLiteral("channels"), ch);
        bad(s, "an unknown channel type");
        s = s1;
        s.insert(QStringLiteral("admins"), QJsonArray{kB});
        bad(s, "an owner who isn't an admin");
        QList<QJsonObject> many;
        for (int i = 0; i < 51; ++i)
            many << member(QStringLiteral("K%1").arg(i, 3, 10, QLatin1Char('0')), QStringLiteral("n"), QString());
        many << member(kA, QStringLiteral("Raven"), QString());
        bad(withMembers(s1, many), "51 members");
        many.removeFirst();
        many.removeFirst();
        check(checkState(withMembers(s1, many)).isEmpty(), "50 members are fine");
        check(length(QStringLiteral("ᚺ😀")) == 2 && member(kA, QString(45, QLatin1Char('a')), QString()).value(QStringLiteral("n")).toString().size() == 40,
              "lengths count characters, and names are cut to 40");
    }

    // Ids, tokens and group codes.
    {
        check(isGroupId(newGroupId()) && isChannelId(newChannelId()) && isLogicalId(newLogicalId()) && urlBytes(newToken()) == 16, "new ids have the right shape");
        check(!isGroupId(QStringLiteral("AAECAwQFBgcICQoLDA0OD+")) && !isLogicalId(QStringLiteral("000102030405060708090A0B0C0D0E0F")), "bad ids are refused");
        const QString g = newGroupId(), tok = newToken();
        const qint64 t0 = 1761500000000;
        const QString text = QStringLiteral("Join us: ") + code(g, QStringLiteral("Family"), kA, QStringLiteral("Raven"), QString(64, QLatin1Char('a')), tok, t0 + kTokenLife)
                             + QStringLiteral(" (an hour)");
        const QVariantMap p = parseCode(text, t0);
        check(p.value(QStringLiteral("g")).toString() == g && p.value(QStringLiteral("name")).toString() == QLatin1String("Family")
                  && p.value(QStringLiteral("admin")).toString() == kA && p.value(QStringLiteral("token")).toString() == tok
                  && p.value(QStringLiteral("mailbox")).toString().size() == 64 && !p.value(QStringLiteral("expired")).toBool(),
              "a group code reads back");
        const QString bare = code(g, QStringLiteral("Family"), kA, QStringLiteral("Raven"), QString(64, QLatin1Char('a')), tok, t0 + kTokenLife);
        const QJsonObject inside = QJsonDocument::fromJson(QByteArray::fromBase64(bare.mid(int(qstrlen(kCodePrefix))).toLatin1(), QByteArray::Base64UrlEncoding)).object();
        check(inside.value(QStringLiteral("g")).toString() == g && !inside.contains(QStringLiteral("o")), "a group code carries no onion address");
        // A code with an onion address ("o") reads; the address is ignored.
        const QJsonObject old{{QStringLiteral("g"), g}, {QStringLiteral("gn"), QStringLiteral("Family")}, {QStringLiteral("a"), kA}, {QStringLiteral("an"), QStringLiteral("Raven")},
                              {QStringLiteral("p"), QString(64, QLatin1Char('a'))}, {QStringLiteral("i"), tok}, {QStringLiteral("x"), (t0 + kTokenLife) / 1000},
                              {QStringLiteral("o"), QString(56, QLatin1Char('a')) + QStringLiteral(".onion")}};
        const QVariantMap po = parseCode(QLatin1String(kCodePrefix) + b64url(QJsonDocument(old).toJson(QJsonDocument::Compact)), t0);
        check(po.value(QStringLiteral("g")).toString() == g && po.value(QStringLiteral("token")).toString() == tok && !po.contains(QStringLiteral("onion")),
              "a 0.6 group code with an onion address still reads (the address is ignored)");
        check(parseCode(text, t0 + kTokenLife + 1000).value(QStringLiteral("expired")).toBool(), "a group code expires after an hour");
        check(parseCode(QStringLiteral("MUNINN1:abc"), t0).isEmpty() && parseCode(QStringLiteral("MUNINNG1:e30"), t0).isEmpty(), "anything else isn't a group code");
    }

    std::printf("proto 0.6 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
