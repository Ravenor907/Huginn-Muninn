#include "chat.h"
#include "profile.h"
#include "proof.h"
#include "richwire.h"
#include "morewire.h"
#include "ble.h"
#include "chatcrypto.h"
#include "ring.h"
#include "call/callcrypto.h"
#include "filelink.h"
#include "link.h"
#include "trust.h"
#include "groupwire.h"
#include "labs.h"
#include "msglimits.h"
#include "localsend.h"
#include "nostr.h"
#include "photos.h"
#include "vault.h"
#include "platform/scratch.h"

#include <QPointer>
#include <QThreadPool>
#include <QBuffer>
#include <QClipboard>
#include <QMimeData>
#include <QCryptographicHash>
#include <QDirIterator>
#include <QFileInfo>
#include <QGuiApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMetaProperty>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <algorithm>
#include <memory>

namespace
{
const QString kPath = QStringLiteral("/api/huginn/v1/message");
const QString kPrefix = QStringLiteral("MUNINN1:");
const QString kPage = QStringLiteral("https://ravenor907.github.io/Huginn-Muninn/add/#");
const int kKeepAfter = 10;
const qint64 kDay = 86400000;
const qint64 kTrashDays = 30;   // removed people stay in the trash this long, then go for good
// Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min.
const qint64 kRetry[] = {5000, 10000, 20000, 60000, 300000};

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
int rank(const QString &s)
{
    if (s == QLatin1String("sending")) return 1;
    if (s == QLatin1String("sent")) return 2;
    if (s == QLatin1String("delivered")) return 3;
    if (s == QLatin1String("read")) return 4;
    return 0;
}
qint64 retryDelay(int count) { return kRetry[qBound(0, count - 1, 4)]; }
QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
const Ratchet::NewKey kNewKey = [] { return ChatCrypto::newKeyPair(); };
// Our own message ids are UUIDs; anything else stays out of file names.
bool fileSafe(const QString &id)
{
    if (id.isEmpty() || id.size() > 80)
        return false;
    for (const QChar c : id)
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-')))
            return false;
    return true;
}
}

Chat::Chat(LocalSend *net, Vault *vault, Nostr *nostr, Ble *ble, QObject *parent)
    : QObject(parent)
    , m_net(net)
    , m_vault(vault)
    , m_nostr(nostr)
    , m_ble(ble)
    , m_settings(QStringLiteral("Huginn"), QStringLiteral("Huginn"))
{
    // Link previews are off for new installs. An install that already has chat settings keeps them on unless it turned them off.
    if (m_settings.contains(QStringLiteral("chat/pub")) && !m_settings.contains(QStringLiteral("chat/linkPreviews")))
        m_settings.setValue(QStringLiteral("chat/linkPreviews"), true);
    connect(&m_http, &QNetworkAccessManager::sslErrors, this, [](QNetworkReply *r, const QList<QSslError> &) { r->ignoreSslErrors(); });
    // Hidden from people nearby: nearby sharing sends nothing about this computer on its own (even while locked).
    m_net->silent = [this] { return !visibleNearby(); };
    // Nothing is readable until the vault is open: start then.
    connect(m_vault, &Vault::opened, this, &Chat::start);
    // Erase everything: the ratchet state and kept envelopes go with the vault's files.
    connect(m_vault, &Vault::changed, this, [this] {
        if (!m_vault->exists()) {
            m_peers.clear();
            m_capable.clear();
            m_caps.clear();
            m_wrapKeys.reset();
            m_peerKeys.reset();
            m_outbox.clear();
            m_unsentKeeps.clear();
            m_groups.clear();
            m_gidx.clear();
            m_gpeers.clear();
            m_ginvites.clear();
            m_joining.clear();
            m_gtokens.clear();
            m_gout.clear();
            m_folders.clear();
            m_topOrder.clear();
            m_trash.clear();
            m_mxOut.clear();
            m_pins.clear();
            m_prefs = QJsonObject();
            m_prefsLoaded = false;
            m_tempName.clear();
        }
    });
    if (!m_vault->locked())
        start();
}

void Chat::start()
{
    if (m_ready)
        return;
    // Keys live in the vault. Keys found in plain settings are moved in.
    QJsonObject secrets = QJsonDocument::fromJson(m_vault->read(QStringLiteral("secrets.json"))).object();
    if (secrets.value(QStringLiteral("chatPriv")).toString().isEmpty()) {
        QByteArray priv = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/priv")).toByteArray());
        QByteArray pub = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/pub")).toByteArray());
        if (priv.isEmpty() || pub.isEmpty()) {
            const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
            priv = kp.privateDer;
            pub = kp.publicDer;
        }
        secrets.insert(QStringLiteral("chatPriv"), b64(priv));
        m_settings.setValue(QStringLiteral("chat/pub"), pub.toBase64());
        // Only drop the plain copy once the vault really has it, or the identity could be lost.
        if (m_vault->write(QStringLiteral("secrets.json"), QJsonDocument(secrets).toJson(QJsonDocument::Compact)))
            m_settings.remove(QStringLiteral("chat/priv"));
    }
    m_priv = QByteArray::fromBase64(secrets.value(QStringLiteral("chatPriv")).toString().toLatin1());
    m_pub = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/pub")).toByteArray());
    // Caps b1 and e1: my day keys and what contacts announced, read from the vault now it's open.
    m_wrapKeys = std::make_unique<WrapKeys>([this] { return m_vault->read(QStringLiteral("wrapkeys.json")); },
                                            [this](const QByteArray &b) { m_vault->write(QStringLiteral("wrapkeys.json"), b); });
    m_peerKeys = std::make_unique<PeerKeys>([this] { return m_vault->read(QStringLiteral("peerkeys.json")); },
                                            [this](const QByteArray &b) { m_vault->write(QStringLiteral("peerkeys.json"), b); });
    loadPrefs();
    load();
    m_seen.load(m_vault->read(QStringLiteral("seen.json")), now(), 2 * kDay);
    // Cap s1 for everyone (contacts, linked devices, group peers): who said it, and since when their envelopes come bound.
    {
        const QJsonObject s1 = QJsonDocument::fromJson(m_vault->read(QStringLiteral("s1.json"))).object();
        for (const QJsonValue &k : s1.value(QStringLiteral("said")).toArray())
            m_s1.insert(k.toString());
        const QJsonObject b = s1.value(QStringLiteral("bound")).toObject();
        for (auto it = b.begin(); it != b.end(); ++it)
            m_bound.insert(it.key(), qint64(it.value().toDouble()));
    }
    // People in the trusted list become contacts.
    const QVariantMap trusted = m_settings.value(QStringLiteral("chat/trusted")).toMap();
    for (auto it = trusted.begin(); it != trusted.end(); ++it)
        if (!m_contacts.contains(it.key()))
            m_contacts.insert(it.key(), {{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), it.value()}, {QStringLiteral("verified"), true}});
    if (!trusted.isEmpty()) {
        saveContacts();
        m_settings.remove(QStringLiteral("chat/trusted"));
    }

    m_net->chatInfo = [this] {
        QJsonObject o{{QStringLiteral("chatApp"), QStringLiteral("huginn")}};
        // Hidden from people nearby: no name at all, not even the computer's (LocalSend's alias), no chat key,
        // no mailbox and no hint either. Nearby sharing announces nothing then (LocalSend::silent).
        if (visibleNearby()) {
            o.insert(QStringLiteral("chatKey"), myKey());
            o.insert(QStringLiteral("mailbox"), m_nostr->publicKey());
        } else {
            o.insert(QStringLiteral("alias"), QStringLiteral("Huginn"));
        }
        if (nearbyOn())
            o.insert(QStringLiteral("chatName"), username());
        return o;
    };
    // Cap s1: my chat key vouches for my certificate, and a hidden contact's daily hint is matched to their key.
    m_net->chatProve = [this](const QString &fp) {
        if (fp != m_proofFp || m_proof.isEmpty()) {
            m_proofFp = fp;
            m_proof = Proof::route(m_priv, myKey(), fp);
        }
        return m_proof;
    };
    m_net->hintKey = [this](const QString &h) { return Proof::keyForHint(h, m_contacts.keys(), now()); };
    m_ble->hintKey = m_net->hintKey;
    m_ble->prove = [this](const QString &n) { return Proof::close(m_priv, myKey(), n); };
    m_net->chatReceive = [this](const QByteArray &body, const QString &ip) { return receive(body, QStringLiteral("wifi"), QStringLiteral("ip:") + ip); };
    connect(m_net, &LocalSend::devicesChanged, this, &Chat::changed);
    connect(m_ble, &Ble::message, this, [this](const QString &key, const QByteArray &json) { receive(json, QStringLiteral("bluetooth"), QStringLiteral("ble:") + key); });
    connect(m_ble, &Ble::changed, this, &Chat::changed);
    connect(m_nostr, &Nostr::connectedChanged, this, &Chat::changed);
    m_nostr->onEnvelope = [this](const QString &content) {
        bool unknownDay = false;
        const QByteArray body = unwrap(content, &unknownDay);
        if (!body.isEmpty())
            receive(body, QStringLiteral("internet"));
        // Cap e1 after a restore: a wrapping for a day key the backup didn't have is never shown, and not marked
        // seen for 7 days (it is tried again each time a relay sends it).
        else if (unknownDay && justRestored())
            return false;
        return true;
    };
    // Cap m1: what's on the way, pins, and saying "active" while Huginn is in front.
    const QJsonObject mx = QJsonDocument::fromJson(m_vault->read(QStringLiteral("mxout.json"))).object();
    for (auto it = mx.begin(); it != mx.end(); ++it)
        m_mxOut.insert(it.key(), it.value().toObject().toVariantMap());
    const QJsonObject pins = QJsonDocument::fromJson(m_vault->read(QStringLiteral("pins.json"))).object();
    for (auto it = pins.begin(); it != pins.end(); ++it)
        m_pins.insert(it.key(), it.value().toVariant().toStringList());
    m_front = QGuiApplication::applicationState() == Qt::ApplicationActive;
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState st) {
        const bool front = st == Qt::ApplicationActive;
        if (front == m_front)
            return;
        m_front = front;
        if (activeStatus())
            sendActive(front);
    });
    m_retry.setInterval(1000);
    connect(&m_retry, &QTimer::timeout, this, &Chat::retryLoop);
    m_retry.start();
    m_prune.setInterval(60000);
    connect(&m_prune, &QTimer::timeout, this, [this] {
        prune();
        sweepSealed();
        if (m_wrapKeys)
            m_wrapKeys->prune(now() / 1000);
        m_seen.prune(now(), 2 * kDay, 5000);
        // Kept on disk too (written at most once a minute), so a restart doesn't let an old copy in again.
        if (m_seen.dirty())
            m_vault->write(QStringLiteral("seen.json"), m_seen.save());
    });
    m_prune.start();
    m_ready = true;
    m_net->announce();
    // Restored from a backup: its day keys may be older than what contacts hold, so a new one goes at once to every
    // contact whose app says e1 (an empty receipt with "wk" and "wt").
    if (m_settings.value(QStringLiteral("restore/announce")).toBool()) {
        m_settings.remove(QStringLiteral("restore/announce"));
        m_wrapKeys->renew(now() / 1000);
        for (const QString &k : m_contacts.keys())
            if (hasCap(k, QLatin1String(RingWire::kWrapCap)) && !isBlocked(k))
                sendKeys(k, true);
    }
    Q_EMIT changed();
}

bool Chat::justRestored() const
{
    const qint64 at = m_settings.value(QStringLiteral("restore/at"), 0).toLongLong();
    return at > 0 && now() - at < 7 * kDay;
}

// ------------------------------------------------------------------ identity

void Chat::loadPrefs()
{
    m_prefs = QJsonDocument::fromJson(m_vault->read(QStringLiteral("prefs.json"))).object();
    m_prefsLoaded = true;
    // Found in plain settings (a restore or a move puts them there, so they win): moved into the
    // vault, then removed there.
    bool moved = false;
    const QString name = m_settings.value(QStringLiteral("chat/username")).toString();
    if (!name.isEmpty()) {
        m_prefs.insert(QStringLiteral("username"), name);
        moved = true;
    }
    const QStringList plainBlocked = m_settings.value(QStringLiteral("chat/muted")).toStringList();
    if (!plainBlocked.isEmpty()) {
        QStringList all = blockedKeys();
        for (const QString &k : plainBlocked)
            if (!all.contains(k))
                all << k;
        m_prefs.insert(QStringLiteral("muted"), QJsonArray::fromStringList(all));
        moved = true;
    }
    // Only dropped from plain settings once the vault really has them.
    if (moved && !m_vault->write(QStringLiteral("prefs.json"), QJsonDocument(m_prefs).toJson(QJsonDocument::Compact)))
        return;
    m_settings.remove(QStringLiteral("chat/username"));
    m_settings.remove(QStringLiteral("chat/muted"));
}

void Chat::savePrefs() const
{
    if (m_prefsLoaded)
        m_vault->write(QStringLiteral("prefs.json"), QJsonDocument(m_prefs).toJson(QJsonDocument::Compact));
}

QString Chat::username() const
{
    QString n = m_prefs.value(QStringLiteral("username")).toString();
    if (n.isEmpty()) {
        // Random until the person picks one, so nobody is named by default. Kept once the vault is open.
        if (m_tempName.isEmpty())
            m_tempName = QStringLiteral("Raven %1").arg(QRandomGenerator::global()->bounded(1000, 10000));
        n = m_tempName;
        if (m_prefsLoaded) {
            m_prefs.insert(QStringLiteral("username"), n);
            savePrefs();
        }
    }
    return n;
}

void Chat::setUsername(const QString &v)
{
    const QString t = v.trimmed().left(24);
    if (t.isEmpty() || t == username())
        return;
    m_tempName = t;
    m_prefs.insert(QStringLiteral("username"), t);
    savePrefs();
    m_net->announce();
    Q_EMIT changed();
}

void Chat::setNearbyOn(bool v)
{
    m_settings.setValue(QStringLiteral("chat/nearbyOn"), v);
    m_net->announce();
    Q_EMIT changed();
}

void Chat::setVisibleNearby(bool v)
{
    if (v == visibleNearby())
        return;
    m_settings.setValue(QStringLiteral("chat/visibleNearby"), v);
    // Hidden, Bluetooth goes silent at once (links end, no scanning); visible again, it starts looking.
    m_ble->hiddenChanged();
    m_net->hiddenChanged();
    m_net->announce();
    Q_EMIT changed();
}

void Chat::setKeepNearby(bool v)
{
    m_settings.setValue(QStringLiteral("chat/keepNearby"), v);
    if (!v) {
        m_nearby.clear();
        m_vault->remove(QStringLiteral("nearby.json"));
    } else {
        save();
    }
    Q_EMIT changed();
}

int Chat::internet() const { return m_nostr->connected(); }

QString Chat::code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite, qint64 expires)
{
    QJsonObject o{{QStringLiteral("n"), name}, {QStringLiteral("k"), chatKey}, {QStringLiteral("p"), mailbox}};
    if (!invite.isEmpty()) {
        o.insert(QStringLiteral("i"), invite);
        o.insert(QStringLiteral("x"), double(expires / 1000));
    }
    return kPrefix + QString::fromLatin1(QJsonDocument(o).toJson(QJsonDocument::Compact).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

QString Chat::mailbox() const { return m_nostr->publicKey(); }

QString Chat::newLink()
{
    if (!m_ready)
        return QString();
    const QString token = QUuid::createUuid().toString(QUuid::Id128).left(16);
    m_linkExpires = now() + 3600000;
    m_invites.insert(token, m_linkExpires);
    for (auto it = m_invites.begin(); it != m_invites.end();)
        it = it.value() < now() ? m_invites.erase(it) : std::next(it);
    saveInvites();
    m_link = kPage + code(username(), myKey(), m_nostr->publicKey(), token, m_linkExpires);
    Q_EMIT changed();
    return m_link;
}

void Chat::saveInvites()
{
    QJsonObject o;
    for (auto it = m_invites.begin(); it != m_invites.end(); ++it)
        o.insert(it.key(), double(it.value()));
    m_vault->write(QStringLiteral("invites.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool Chat::inviteOk(const QString &token) const { return !token.isEmpty() && m_invites.value(token, 0) > now(); }

// Over the internet the whole message is sealed to their chat key with a one-off key, so relays
// see only which inbox it's for: not who sent it, not their name. The same as Muninn's wrap().
QString Chat::wrap(const QString &peerKey, const QByteArray &body) const
{
    // Cap e1: sealed to one of their day keys too, so their chat key alone never opens it once that day key is gone.
    const QString day = m_peerKeys && hasCap(peerKey, QLatin1String(RingWire::kWrapCap)) ? m_peerKeys->wrapFor(peerKey, now() / 1000) : QString();
    if (!day.isEmpty()) {
        const QJsonObject w = RingWire::sealWrap(peerKey, day, body);
        if (!w.isEmpty())
            return b64(Nostr::pad(QJsonDocument(w).toJson(QJsonDocument::Compact)));
    }
    const ChatCrypto::KeyPair eph = ChatCrypto::newKeyPair();
    const QByteArray key = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, QByteArray::fromBase64(peerKey.toLatin1()));
    QByteArray nonce, data;
    ChatCrypto::seal(key, body, &nonce, &data);
    const QJsonObject o{{QStringLiteral("e"), b64(eph.publicDer)}, {QStringLiteral("n"), b64(nonce)}, {QStringLiteral("d"), b64(data)}};
    return b64(Nostr::pad(QJsonDocument(o).toJson(QJsonDocument::Compact)));   // padded to a fixed size (section 20)
}

QByteArray Chat::unwrap(const QString &content, bool *unknownDay) const
{
    if (unknownDay)
        *unknownDay = false;
    const QByteArray raw = QByteArray::fromBase64(content.toLatin1());
    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    if (!o.contains(QStringLiteral("e")))
        return raw;   // not sealed
    bool ok = false;
    // Cap e1: wrapped for one of my day keys as well; one that's gone (or never was) doesn't open.
    if (o.contains(QStringLiteral("w"))) {
        if (!m_wrapKeys)
            return QByteArray();
        WrapKeys *days = m_wrapKeys.get();
        if (unknownDay && days->find(o.value(QStringLiteral("w")).toString(), now() / 1000).privateDer.isEmpty())
            *unknownDay = true;
        const QByteArray plain = RingWire::openWrap(o, ChatCrypto::KeyPair{m_priv, m_pub},
                                                    [days](const QString &id) { return days->find(id, now() / 1000); }, &ok);
        return ok ? plain : QByteArray();
    }
    const QByteArray plain = ChatCrypto::open(keyWith(o.value(QStringLiteral("e")).toString()), QByteArray::fromBase64(o.value(QStringLiteral("n")).toString().toLatin1()),
                                              QByteArray::fromBase64(o.value(QStringLiteral("d")).toString().toLatin1()), &ok);
    return ok ? plain : QByteArray();
}

QVariantMap Chat::parseCode(const QString &link) const
{
    // A bare code, or the add page's link with the code after "#" (some apps write its ":" as "%3A").
    QString text = link;
    text.replace(QStringLiteral("MUNINN1%3A"), kPrefix, Qt::CaseInsensitive);
    const int i = text.indexOf(kPrefix);
    if (i < 0)
        return {};
    QString body;
    for (const QChar c : text.mid(i + kPrefix.size())) {
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    const QJsonObject o = QJsonDocument::fromJson(QByteArray::fromBase64(body.toLatin1(), QByteArray::Base64UrlEncoding)).object();
    const QString key = o.value(QStringLiteral("k")).toString();
    if (key.isEmpty() || keyWith(key).isEmpty())
        return {};   // must be a real key
    QString name = o.value(QStringLiteral("n")).toString().left(40).trimmed();
    if (name.isEmpty())
        name = QStringLiteral("Someone");
    const QString mailbox = o.value(QStringLiteral("p")).toString();
    const qint64 expires = qint64(o.value(QStringLiteral("x")).toDouble()) * 1000;
    return {{QStringLiteral("key"), key}, {QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox.size() == 64 ? mailbox : QString()},
            {QStringLiteral("mine"), key == myKey()}, {QStringLiteral("invite"), o.value(QStringLiteral("i")).toString()},
            {QStringLiteral("expired"), expires > 0 && expires < now()}};
}

void Chat::addContact(const QString &text, const QString &sameAs)
{
    const QVariantMap c = parseCode(text);
    if (c.isEmpty() || c.value(QStringLiteral("mine")).toBool() || c.value(QStringLiteral("expired")).toBool())
        return;
    const QString key = str(c, "key");
    // The same inbox as a contact but another key: that contact's key is being replaced, never
    // silently. So is one the person picked ("the same person" for a contact with that name).
    QString old = isContact(key) ? QString() : contactWithMailbox(str(c, "mailbox"), key);
    if (old.isEmpty() && !sameAs.isEmpty() && sameAs != key && isContact(sameAs))
        old = sameAs;
    if (!old.isEmpty()) {
        replaceKey(old, key, str(c, "name"), str(c, "mailbox"));
        m_contacts[key].insert(QStringLiteral("invite"), c.value(QStringLiteral("invite")));
        saveContacts();
        return;
    }
    // Whoever gave you their code is who they say: the code itself carries their key.
    const QString nick = nicknameOf(key);
    QVariantMap entry{{QStringLiteral("key"), key}, {QStringLiteral("name"), c.value(QStringLiteral("name"))},
                      {QStringLiteral("mailbox"), c.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), true},
                      {QStringLiteral("invite"), c.value(QStringLiteral("invite"))}};
    if (!nick.isEmpty())
        entry.insert(QStringLiteral("nick"), nick);
    const QString note = noteOf(key);
    if (!note.isEmpty())
        entry.insert(QStringLiteral("note"), note);
    const bool fresh = !m_contacts.contains(key);
    // What they may do stays as chosen when they're added again.
    for (const char *k : {"noCalls", "noFiles", "hideActive"})
        if (m_contacts.value(key).value(QLatin1String(k)).toBool())
            entry.insert(QLatin1String(k), true);
    m_contacts.insert(key, entry);
    if (fresh)
        sayHello(key);
    m_met.remove(key);
    m_trash.remove(key);   // adding someone from the trash again takes them out of it
    saveContacts();
    saveMet();
    Q_EMIT changed();
}

QVariantMap Chat::replaces(const QString &text) const
{
    const QVariantMap c = parseCode(text);
    const QString key = str(c, "key");
    if (c.isEmpty() || c.value(QStringLiteral("mine")).toBool() || isContact(key))
        return {};
    auto found = [this](const QString &k, bool sameInbox) {
        return QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(k)}, {QStringLiteral("sameInbox"), sameInbox}};
    };
    const QString byMailbox = contactWithMailbox(str(c, "mailbox"), key);
    if (!byMailbox.isEmpty())
        return found(byMailbox, true);
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (it.key() != key && str(it.value(), "name").compare(str(c, "name"), Qt::CaseInsensitive) == 0)
            return found(it.key(), false);
    return {};
}

QString Chat::contactWithMailbox(const QString &mailbox, const QString &notKey) const
{
    if (mailbox.size() != 64)
        return QString();
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (it.key() != notKey && str(it.value(), "mailbox") == mailbox)
            return it.key();
    return QString();
}

bool Chat::keyChanged(const QString &key) const { return m_contacts.value(key).value(QStringLiteral("keyChanged")).toBool(); }

void Chat::replaceKey(const QString &oldKey, const QString &newKey, const QString &name, const QString &mailbox)
{
    if (oldKey == newKey || !m_contacts.contains(oldKey))
        return;
    QVariantMap c = m_contacts.take(oldKey);
    c.insert(QStringLiteral("key"), newKey);
    if (!name.isEmpty())
        c.insert(QStringLiteral("name"), name);
    if (!mailbox.isEmpty())
        c.insert(QStringLiteral("mailbox"), mailbox);
    c.insert(QStringLiteral("verified"), false);
    c.insert(QStringLiteral("keyChanged"), true);
    c.remove(QStringLiteral("invite"));
    if (str(c, "nick").isEmpty() && !str(m_met.value(newKey), "nick").isEmpty())
        c.insert(QStringLiteral("nick"), str(m_met.value(newKey), "nick"));
    m_contacts.insert(newKey, c);
    m_met.remove(oldKey);
    m_met.remove(newKey);
    // The conversation stays. Whatever was still on its way to the old key stops: Try again
    // sends it to the new one, so nothing goes to the new key without the person choosing to.
    QVariantList msgs = m_private.take(oldKey);
    msgs += m_private.take(newKey);
    for (QVariant &v : msgs) {
        QVariantMap m = v.toMap();
        m.insert(QStringLiteral("peer"), newKey);
        const QString id = str(m, "id");
        if (m.value(QStringLiteral("mine")).toBool()) {
            if (str(m, "state") == QLatin1String("unsending")) {
                m.insert(QStringLiteral("state"), QStringLiteral("unsent"));
            } else if (str(m, "unsent").isEmpty() && (rank(str(m, "state")) == 1 || rank(str(m, "state")) == 2)) {
                m.insert(QStringLiteral("state"), QString());
                m.insert(QStringLiteral("failed"), true);
            }
            m_tries.remove(id);
            dropSealed(id);
            dropSealed(QStringLiteral("u-") + id);
        }
        v = m;
    }
    std::stable_sort(msgs.begin(), msgs.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("time")).toLongLong() < b.toMap().value(QStringLiteral("time")).toLongLong();
    });
    m_private.insert(newKey, msgs);
    if (m_unread.remove(oldKey))
        m_unread.insert(newKey);
    m_unsentKeeps.remove(oldKey);
    m_requests.remove(newKey);
    m_held.remove(newKey);
    if (m_openChat == oldKey)
        m_openChat = newKey;
    forgetRatchet(oldKey);
    forgetRatchet(newKey);
    saveContacts();
    saveMet();
    const QString text = QStringLiteral("%1's security key changed. Check the code with them before trusting new messages.").arg(nameOf(newKey));
    sayHello(newKey);
    systemNote(newKey, text);
    Q_EMIT notice(text);
    Q_EMIT changed();
}

// A line in the conversation from Huginn itself (not from them, never sent anywhere).
void Chat::systemNote(const QString &key, const QString &text)
{
    QVariantMap m = message(QUuid::createUuid().toString(QUuid::WithoutBraces), key, QString(), text, false, QString());
    m.insert(QStringLiteral("system"), true);
    m.insert(QStringLiteral("state"), QStringLiteral("read"));   // never answered with a receipt
    addPrivate(key, m);
}

void Chat::noteCall(const QString &where, const QString &text, bool missed)
{
    if (!m_ready || where.isEmpty() || text.isEmpty())
        return;
    if (where.startsWith(QLatin1String("g:"))) {
        const QString g = where.mid(2);
        if (m_groups.contains(g))
            groupNote(g, text);
        return;
    }
    systemNote(where, text);
    if (missed) {
        m_unread.insert(where);
        Q_EMIT changed();
    }
}

QByteArray Chat::keyWith(const QString &theirKey) const
{
    return ChatCrypto::sharedKey(m_priv, m_pub, QByteArray::fromBase64(theirKey.toLatin1()));
}

QString Chat::codeWith(const QString &key) const { return ChatCrypto::pairingCode(keyWith(key)); }

QString Chat::nameOf(const QString &key) const
{
    const QString nick = nicknameOf(key);
    return nick.isEmpty() ? realNameOf(key) : nick;
}

QString Chat::nicknameOf(const QString &key) const
{
    const QString n = str(m_contacts.value(key), "nick");
    return n.isEmpty() ? str(m_met.value(key), "nick") : n;
}

void Chat::setNickname(const QString &key, const QString &nick)
{
    if (key.isEmpty() || !m_ready)
        return;
    const QString t = nick.simplified().left(40);
    if (m_contacts.contains(key)) {
        if (t.isEmpty())
            m_contacts[key].remove(QStringLiteral("nick"));
        else
            m_contacts[key].insert(QStringLiteral("nick"), t);
        saveContacts();
    } else {
        // Someone met close by (or a chat kept after removing them): kept with what's known about them.
        QVariantMap m = m_met.value(key, {{QStringLiteral("name"), realNameOf(key)}, {QStringLiteral("dismissed"), true}});
        if (t.isEmpty())
            m.remove(QStringLiteral("nick"));
        else
            m.insert(QStringLiteral("nick"), t);
        putMet(key, m);
    }
    Q_EMIT changed();
}

QString Chat::noteOf(const QString &key) const
{
    const QString n = str(m_contacts.value(key), "note");
    return n.isEmpty() ? str(m_met.value(key), "note") : n;
}

void Chat::setNote(const QString &key, const QString &note)
{
    if (key.isEmpty() || !m_ready)
        return;
    const QString t = note.trimmed().left(500);
    if (m_contacts.contains(key)) {
        if (t.isEmpty())
            m_contacts[key].remove(QStringLiteral("note"));
        else
            m_contacts[key].insert(QStringLiteral("note"), t);
        saveContacts();
    } else {
        QVariantMap m = m_met.value(key, {{QStringLiteral("name"), realNameOf(key)}, {QStringLiteral("dismissed"), true}});
        if (t.isEmpty())
            m.remove(QStringLiteral("note"));
        else
            m.insert(QStringLiteral("note"), t);
        putMet(key, m);
    }
    Q_EMIT changed();
}

QString Chat::realNameOf(const QString &key) const
{
    if (m_contacts.contains(key))
        return str(m_contacts.value(key), "name");
    if (m_met.contains(key))
        return str(m_met.value(key), "name");
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            return str(p.toMap(), "name");
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (p.chatKey == key)
            return p.chatName.isEmpty() ? p.alias : p.chatName;
    return QStringLiteral("Someone");
}

QString Chat::mailboxOf(const QString &key) const
{
    QString c = str(m_contacts.value(key), "mailbox");
    if (c.isEmpty())
        c = str(m_met.value(key), "mailbox");
    if (c.isEmpty())
        c = str(m_gpeers.value(key), "mailbox");
    if (c.isEmpty())
        for (const QVariantMap &j : m_joining)
            if (str(j, "admin") == key)
                c = str(j, "mailbox");
    return c;
}

QString Chat::netStatus() const
{
    return internet() > 0 ? QStringLiteral("Internet: connected") : QStringLiteral("Internet: offline");
}

bool Chat::sendInternet(const QString &key, const QList<QByteArray> &bodies, const QString &mailbox, std::function<void()> sent, int days)
{
    if (bodies.isEmpty() || mailbox.isEmpty() || m_moving)
        return false;
    // Relays may drop it after the longest message limit (Nostr::expiration): it's gone from both sides by then.
    const int keep = days > 0 ? days : chatDays(key);
    for (const QByteArray &b : bodies)
        m_nostr->publish(mailbox, wrap(key, b), keep);
    if (sent)
        sent();
    return true;
}

// ------------------------------------------------------------------ caps

bool Chat::canGroup(const QString &key) const { return isGroupPeer(key) || m_caps.value(key).contains(QStringLiteral("g1")); }

void Chat::noteCaps(const QString &key, bool present, const QStringList &caps)
{
    // From authentic envelopes only, and only for people kept here. An envelope without "caps"
    // (an app without caps) teaches nothing; tokens are never taken away (a key change starts over:
    // forgetRatchet).
    if (!present || (!isContact(key) && !m_met.contains(key) && !isGroupPeer(key)))
        return;
    const bool known = m_caps.contains(key);
    QStringList have = m_caps.value(key);
    bool grew = false;
    for (const QString &c : caps)
        if (!have.contains(c)) {
            have << c;
            grew = true;
        }
    if (known && !grew)
        return;
    const QStringList before = m_caps.value(key);
    m_caps.insert(key, have);
    saveRatchet(key);
    Q_EMIT changed();
    // Caps b1 and e1: their app says so now, so my ring key and day key go to them.
    const QString ringCap = QLatin1String(RingWire::kRingCap), wrapCap = QLatin1String(RingWire::kWrapCap);
    if ((have.contains(ringCap) && !before.contains(ringCap)) || (have.contains(wrapCap) && !before.contains(wrapCap)))
        QTimer::singleShot(0, this, [this, key] { sendKeys(key); });
}

void Chat::probeCaps(const QString &key)
{
    if (!m_ready || key.isEmpty() || capsKnown(key) || now() - m_probed.value(key, 0) < 3600000)
        return;
    m_probed.insert(key, now());
    const QJsonObject r{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}, {QStringLiteral("q"), QStringLiteral("caps")}};
    deliver(key, envelope(QStringLiteral("receipt"), key, compact(r), QUuid::createUuid().toString(QUuid::WithoutBraces)), mailboxOf(key));
}

// ------------------------------------------------------------------ calls

void Chat::sendCall(const QString &key, const QJsonObject &msg)
{
    if (!m_ready || key.isEmpty() || isBlocked(key) || isTrashed(key) || m_moving)
        return;
    // Sealed once (a ratchet message moves the session on) and the same copy sent on every
    // route at once: the other side keeps the first and drops the rest. Never kept for resending.
    const QByteArray json = compact(envelope(QStringLiteral("call"), key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    sendBle(key, json);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, json);
    sendInternet(key, {json}, mailboxOf(key));
    ring(key, msg);
}

void Chat::ring(const QString &key, const QJsonObject &msg)
{
    // Cap b1 (section 23): their device rings even while their app is locked. Once per call, 1:1 offers only.
    if (msg.value(QStringLiteral("t")).toString() != QLatin1String("offer") || msg.contains(QStringLiteral("g")) || !m_peerKeys
        || !hasCap(key, QLatin1String(RingWire::kRingCap)))
        return;
    const RingWire::Announce k = m_peerKeys->get(key);
    const QString call = msg.value(QStringLiteral("call")).toString();
    if (k.rk.isEmpty() || k.rp.isEmpty() || call.isEmpty() || m_rung.contains(call))
        return;
    if (m_rung.size() > 200)
        m_rung.clear();
    m_rung.insert(call);
    const qint64 ts = msg.contains(QStringLiteral("ts")) ? msg.value(QStringLiteral("ts")).toInteger() : now();
    const QString content = RingWire::ringContent(k.rk, call, ts);
    if (!content.isEmpty())
        m_nostr->publish(k.rp, content, Nostr::kLongestDays);
}

QJsonObject Chat::withKeys(const QString &key, QJsonObject o) const
{
    // Cap b1: the ring key only to contacts, so a group-only peer (or someone blocked) can't ring this device.
    if (m_ringKey && isContact(key) && !isBlocked(key) && hasCap(key, QLatin1String(RingWire::kRingCap))) {
        o.insert(QStringLiteral("rk"), m_ringKey->publicKey());
        o.insert(QStringLiteral("rp"), m_ringKey->mailbox());
    }
    if (m_wrapKeys && hasCap(key, QLatin1String(RingWire::kWrapCap))) {
        const WrapKeys::Day d = m_wrapKeys->current(now() / 1000);
        o.insert(QStringLiteral("wk"), d.pub);
        o.insert(QStringLiteral("wt"), d.t);
    }
    return o;
}

void Chat::sendKeys(const QString &key, bool again)
{
    if (m_keysSent.contains(key) && !again)
        return;
    m_keysSent.insert(key);
    const QJsonObject r = withKeys(key, QJsonObject{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}});
    deliver(key, envelope(QStringLiteral("receipt"), key, compact(r), QUuid::createUuid().toString(QUuid::WithoutBraces)), mailboxOf(key));
}

bool Chat::fileRatchet(const QString &room, const QString &key) const
{
    return room == QLatin1String("file") && isContact(key) && m_capable.value(key) && hasCap(key, QLatin1String(RingWire::kWrapCap));
}

QByteArray Chat::signWithChatKey(const QByteArray &bytes) const { return CallCrypto::signDer(m_priv, bytes); }

bool Chat::canCall(const QString &key) const
{
    if (key.isEmpty() || isBlocked(key) || isTrashed(key))
        return false;
    // Unknown caps count as a current app (one without c1 just doesn't answer); a group peer is "r1 g1 c1" (3.7).
    const bool c1 = !capsKnown(key) || capIn(m_caps.value(key), QStringLiteral("c1"));
    if (isContact(key) || isGroupPeer(key))
        return c1;
    // Someone met close by: only while on the same Wi-Fi or close by.
    if (m_met.contains(key)) {
        const QString r = route(key);
        return c1 && (r == QLatin1String("wifi") || r == QLatin1String("close"));
    }
    return false;
}

QString Chat::lanHost(const QString &key) const
{
    const QString base = baseTo(key);
    return base.isEmpty() ? QString() : QUrl(base).host();
}

// ------------------------------------------------------------------ contact folders

void Chat::loadFolders()
{
    m_folders.clear();
    const QByteArray raw = m_vault->read(QStringLiteral("folders.json"));
    if (raw.isEmpty()) {
        // First time: three to start with. Rename, delete or add your own.
        for (const char *n : {"Family", "Work", "Folk"})
            m_folders << QVariantMap{{QStringLiteral("id"), QUuid::createUuid().toString(QUuid::Id128).left(12)}, {QStringLiteral("name"), QString::fromLatin1(n)},
                                     {QStringLiteral("keys"), QStringList()}};
        saveFolders();
        return;
    }
    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    bool renamed = false;
    for (const QJsonValue &v : o.value(QStringLiteral("folders")).toArray()) {
        QVariantMap f = v.toObject().toVariantMap();
        // A starting folder named Kindred is renamed Folk.
        if (f.value(QStringLiteral("name")).toString() == QLatin1String("Kindred")) {
            f.insert(QStringLiteral("name"), QStringLiteral("Folk"));
            renamed = true;
        }
        m_folders << f;
    }
    m_topOrder = o.value(QStringLiteral("top")).toVariant().toStringList();
    if (renamed)
        saveFolders();
}

void Chat::saveFolders()
{
    QJsonArray a;
    for (const QVariantMap &f : std::as_const(m_folders))
        a << QJsonObject::fromVariantMap(f);
    m_vault->write(QStringLiteral("folders.json"), compact(QJsonObject{{QStringLiteral("folders"), a}, {QStringLiteral("top"), QJsonArray::fromStringList(m_topOrder)}}));
}

QVariantList Chat::folders() const
{
    QVariantList out;
    for (const QVariantMap &f : m_folders)
        out << QVariantMap{{QStringLiteral("id"), f.value(QStringLiteral("id"))}, {QStringLiteral("name"), f.value(QStringLiteral("name"))},
                           {QStringLiteral("count"), f.value(QStringLiteral("keys")).toStringList().size()},
                           {QStringLiteral("keys"), f.value(QStringLiteral("keys")).toStringList()},
                           {QStringLiteral("collapsed"), f.value(QStringLiteral("collapsed")).toBool()}};
    return out;
}

QString Chat::addFolder(const QString &name)
{
    const QString n = name.simplified().left(24);
    if (n.isEmpty() || !m_ready || m_folders.size() >= 30)
        return QString();
    const QString id = QUuid::createUuid().toString(QUuid::Id128).left(12);
    m_folders << QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("name"), n}, {QStringLiteral("keys"), QStringList()}};
    saveFolders();
    Q_EMIT changed();
    return id;
}

void Chat::renameFolder(const QString &id, const QString &name)
{
    const QString n = name.simplified().left(24);
    if (n.isEmpty())
        return;
    for (QVariantMap &f : m_folders)
        if (str(f, "id") == id)
            f.insert(QStringLiteral("name"), n);
    saveFolders();
    Q_EMIT changed();
}

void Chat::deleteFolder(const QString &id)
{
    for (int i = int(m_folders.size()) - 1; i >= 0; --i)
        if (str(m_folders.at(i), "id") == id)
            m_folders.removeAt(i);
    saveFolders();
    Q_EMIT changed();
}

void Chat::setInFolder(const QString &id, const QString &key, bool on)
{
    for (QVariantMap &f : m_folders) {
        if (str(f, "id") != id)
            continue;
        QStringList keys = f.value(QStringLiteral("keys")).toStringList();
        keys.removeAll(key);
        if (on)
            keys << key;
        f.insert(QStringLiteral("keys"), keys);
    }
    saveFolders();
    Q_EMIT changed();
}

QStringList Chat::foldersOf(const QString &key) const
{
    QStringList out;
    for (const QVariantMap &f : m_folders)
        if (f.value(QStringLiteral("keys")).toStringList().contains(key))
            out << str(f, "id");
    return out;
}

void Chat::placeIn(const QString &key, const QString &from, const QString &to, const QStringList &order)
{
    if (key.isEmpty() || !m_ready)
        return;
    QStringList next;
    for (const QString &k : order)
        if (!k.isEmpty() && !next.contains(k))
            next << k;
    if (!next.contains(key))
        next << key;
    for (QVariantMap &f : m_folders) {
        const QString id = str(f, "id");
        QStringList keys = f.value(QStringLiteral("keys")).toStringList();
        if (!to.isEmpty() && id == to) {
            // Anyone in it who isn't shown (blocked, or in the trash) keeps their place at the end.
            QStringList all = next;
            for (const QString &k : std::as_const(keys))
                if (!all.contains(k))
                    all << k;
            keys = all;
        } else if (to.isEmpty() || id == from) {
            keys.removeAll(key);   // no folder means out of every one
        }
        f.insert(QStringLiteral("keys"), keys);
    }
    if (to.isEmpty())
        m_topOrder = next;
    saveFolders();
    Q_EMIT changed();
}

void Chat::setCollapsed(const QString &id, bool on)
{
    for (QVariantMap &f : m_folders)
        if (str(f, "id") == id) {
            if (on)
                f.insert(QStringLiteral("collapsed"), true);
            else
                f.remove(QStringLiteral("collapsed"));
        }
    saveFolders();
    Q_EMIT changed();
}

void Chat::moveFolder(const QString &id, int by)
{
    int i = 0;
    while (i < m_folders.size() && str(m_folders.at(i), "id") != id)
        ++i;
    const int j = i + by;
    if (i >= m_folders.size() || j < 0 || j >= m_folders.size())
        return;
    m_folders.move(i, j);
    saveFolders();
    Q_EMIT changed();
}

QString Chat::route(const QString &key) const
{
    if (m_ble->has(key, strict(key)))
        return QStringLiteral("bluetooth");
    if (!baseTo(key).isEmpty())
        return QStringLiteral("wifi");
    if (!str(m_contacts.value(key), "mailbox").isEmpty() || !str(m_gpeers.value(key), "mailbox").isEmpty())
        return QStringLiteral("internet");
    return QString();
}

bool Chat::isVerified(const QString &key) const
{
    return m_contacts.value(key).value(QStringLiteral("verified")).toBool() || m_met.value(key).value(QStringLiteral("verified")).toBool();
}

void Chat::verify(const QString &key)
{
    if (m_contacts.contains(key)) {
        m_contacts[key].insert(QStringLiteral("verified"), true);
        m_contacts[key].remove(QStringLiteral("keyChanged"));
        saveContacts();
    } else {
        QVariantMap m = m_met.value(key, {{QStringLiteral("name"), nameOf(key)}});
        m.insert(QStringLiteral("verified"), true);
        putMet(key, m);
    }
    Q_EMIT changed();
}

void Chat::meet(const QString &key, const QString &name)
{
    if (isContact(key) || m_met.contains(key))
        return;
    QString mailbox;
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            mailbox = str(p.toMap(), "mailbox");
    putMet(key, {{QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox}});
}

bool Chat::shouldAskKeep(const QString &key) const
{
    if (isContact(key) || !m_met.contains(key))
        return false;
    const QVariantMap m = m_met.value(key);
    if (m.value(QStringLiteral("dismissed")).toBool() || m.value(QStringLiteral("iSaid")).toBool())
        return false;
    return m.value(QStringLiteral("theySaid")).toBool() || m_private.value(key).size() >= kKeepAfter || route(key).isEmpty();
}

bool Chat::saidKeep(const QString &key) const { return m_met.value(key).value(QStringLiteral("iSaid")).toBool(); }
bool Chat::theySaidKeep(const QString &key) const { return m_met.value(key).value(QStringLiteral("theySaid")).toBool(); }

void Chat::keepInTouch(const QString &key)
{
    if (!m_met.contains(key))
        return;
    QVariantMap m = m_met.value(key);
    m.insert(QStringLiteral("iSaid"), true);
    m.insert(QStringLiteral("dismissed"), false);
    putMet(key, m);
    // Sealed once: if nobody's in reach now, these same bytes go when they are.
    const QJsonObject keep = envelope(QStringLiteral("keep"), key, QByteArrayLiteral("yes"), QUuid::createUuid().toString(QUuid::WithoutBraces));
    if (deliver(key, keep, str(m, "mailbox")).isEmpty())
        m_unsentKeeps.insert(key, keep);
    if (m.value(QStringLiteral("theySaid")).toBool())
        becomeContacts(key);
    else
        Q_EMIT notice(QStringLiteral("Asked %1. Once they say yes too, you can message from anywhere.").arg(nameOf(key)));
    Q_EMIT changed();
}

void Chat::notNow(const QString &key)
{
    if (!m_met.contains(key))
        return;
    QVariantMap m = m_met.value(key);
    m.insert(QStringLiteral("dismissed"), true);
    putMet(key, m);
    Q_EMIT changed();
}

void Chat::becomeContacts(const QString &key)
{
    const QVariantMap m = m_met.take(key);
    QVariantMap c{{QStringLiteral("key"), key}, {QStringLiteral("name"), m.value(QStringLiteral("name"))},
                  {QStringLiteral("mailbox"), m.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), m.value(QStringLiteral("verified"))}};
    if (!str(m, "nick").isEmpty())
        c.insert(QStringLiteral("nick"), m.value(QStringLiteral("nick")));
    m_contacts.insert(key, c);
    saveContacts();
    saveMet();
    sayHello(key);
    Q_EMIT notice(c.value(QStringLiteral("verified")).toBool()
                      ? QStringLiteral("You and %1 are in touch. Message each other from anywhere.").arg(nameOf(key))
                      : QStringLiteral("You and %1 are in touch. Check the 4 pictures with them to be sure.").arg(nameOf(key)));
    Q_EMIT changed();
}

void Chat::putMet(const QString &key, const QVariantMap &m)
{
    m_met.insert(key, m);
    saveMet();
}

void Chat::acceptRequest(const QString &key)
{
    const QVariantMap r = m_requests.take(key);
    if (r.isEmpty())
        return;
    const QVariantMap held = m_held.take(key);
    const QString old = str(r, "replaces");
    if (!old.isEmpty() && isContact(old)) {
        // They said they're a contact with a new security key, and the person accepted.
        replaceKey(old, key, str(r, "name"), str(r, "mailbox"));
    } else {
        m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), r.value(QStringLiteral("name"))},
                                {QStringLiteral("mailbox"), r.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), false}});
        saveContacts();
        sayHello(key);
    }
    QStringList ids;
    for (const QVariant &v : held) {
        addPrivate(key, v.toMap());
        ids << str(v.toMap(), "id");
    }
    receipt(key, ids, QStringLiteral("delivered"), str(r, "mailbox"));
    Q_EMIT changed();
}

void Chat::declineRequest(const QString &key)
{
    m_requests.remove(key);
    m_held.remove(key);
    Q_EMIT changed();
}

bool Chat::isBlocked(const QString &key) const { return blockedKeys().contains(key); }

void Chat::block(const QString &key, bool on)
{
    const bool was = isBlocked(key);
    QStringList m = blockedKeys();
    m.removeAll(key);
    if (on)
        m << key;
    m_prefs.insert(QStringLiteral("muted"), QJsonArray::fromStringList(m));
    savePrefs();
    if (on) {
        m_requests.remove(key);
        m_held.remove(key);
        QVariantList kept;
        for (const QVariant &v : std::as_const(m_nearby))
            if (str(v.toMap(), "peer") != key || v.toMap().value(QStringLiteral("mine")).toBool())
                kept << v;
        m_nearby = kept;
        // Cap b1: they may hold my ring key, so a new one (and a new ring mailbox) goes to everyone else who may ring
        // this device. The old ring mailbox is still read for two days (main.cpp), then dropped.
        if (!was && m_ringKey) {
            m_ringKey->renew();
            m_nostr->resubscribe();
            for (const QString &k : m_contacts.keys())
                if (k != key && !isBlocked(k) && hasCap(k, QLatin1String(RingWire::kRingCap)))
                    sendKeys(k, true);
        }
    }
    Q_EMIT changed();
}

void Chat::removeContact(const QString &key)
{
    if (key.isEmpty() || m_trash.contains(key) || (!isContact(key) && !m_met.contains(key) && m_private.value(key).isEmpty()))
        return;
    // Everything stays as it was (conversation, folders, the ratchet), so restore() brings it all back.
    const bool contact = isContact(key);
    const QVariantMap met = m_met.take(key);
    QVariantMap t = contact ? m_contacts.take(key)
                            : QVariantMap{{QStringLiteral("key"), key}, {QStringLiteral("name"), met.value(QStringLiteral("name"), nameOf(key))},
                                          {QStringLiteral("mailbox"), met.value(QStringLiteral("mailbox"))}};
    if (!contact && !str(met, "nick").isEmpty())
        t.insert(QStringLiteral("nick"), met.value(QStringLiteral("nick")));
    t.insert(QStringLiteral("trashed"), now());
    t.insert(QStringLiteral("contact"), contact);
    if (!met.isEmpty())
        t.insert(QStringLiteral("met"), met);
    m_trash.insert(key, t);
    m_unsentKeeps.remove(key);
    m_requests.remove(key);
    m_held.remove(key);
    m_unread.remove(key);
    m_typing.remove(key);
    m_active.remove(key);
    saveContacts();
    saveMet();
    refreshGroupPeers();
    Q_EMIT liveChanged();
    Q_EMIT changed();
}

QVariantList Chat::trash() const
{
    QVariantList out;
    for (auto it = m_trash.cbegin(); it != m_trash.cend(); ++it) {
        const qint64 at = it.value().value(QStringLiteral("trashed")).toLongLong();
        const QString nick = str(it.value(), "nick");
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), nick.isEmpty() ? str(it.value(), "name") : nick},
                           {QStringLiteral("trashed"), at}, {QStringLiteral("daysLeft"), qMax<qint64>(1, (at + kTrashDays * kDay - now() + kDay - 1) / kDay)}};
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("trashed")).toLongLong() > b.toMap().value(QStringLiteral("trashed")).toLongLong();
    });
    return out;
}

void Chat::restore(const QString &key)
{
    if (!m_trash.contains(key))
        return;
    QVariantMap t = m_trash.take(key);
    const bool contact = t.value(QStringLiteral("contact")).toBool();
    const QVariantMap met = t.value(QStringLiteral("met")).toMap();
    t.remove(QStringLiteral("trashed"));
    t.remove(QStringLiteral("contact"));
    t.remove(QStringLiteral("met"));
    if (contact)
        m_contacts.insert(key, t);
    if (!met.isEmpty())
        m_met.insert(key, met);
    saveContacts();
    saveMet();
    refreshGroupPeers();
    Q_EMIT changed();
}

void Chat::deleteForGood(const QString &key)
{
    if (!m_trash.contains(key))
        return;
    m_trash.remove(key);
    forgetPerson(key);
    Q_EMIT changed();
}

void Chat::emptyTrash()
{
    const QStringList keys = m_trash.keys();
    for (const QString &k : keys) {
        m_trash.remove(k);
        forgetPerson(k);
    }
    Q_EMIT changed();
}

// Gone for good: the contact, their conversation with its photos and voice, folders, pins and the
// ratchet. Someone you share a group with stays reachable there.
void Chat::forgetPerson(const QString &key)
{
    forgetFlags(key);
    if (m_peerKeys && !isGroupPeer(key))
        m_peerKeys->forget(key);
    m_contacts.remove(key);
    m_met.remove(key);
    // Not in touch any way now: their profile picture goes too (a group peer keeps it while the group lasts).
    if (m_profile && !isGroupPeer(key))
        m_profile->forget(key);
    m_unsentKeeps.remove(key);
    m_unread.remove(key);
    forgetRatchet(key);
    for (QVariantMap &f : m_folders) {
        QStringList keys = f.value(QStringLiteral("keys")).toStringList();
        if (keys.removeAll(key))
            f.insert(QStringLiteral("keys"), keys);
    }
    m_topOrder.removeAll(key);
    for (const QVariant &v : m_private.take(key)) {
        const QVariantMap m = v.toMap();
        if (!str(m, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        m_tries.remove(str(m, "id"));
        dropSealed(str(m, "id"));
    }
    if (m_pins.remove(key))
        savePins();
    save();
    saveFolders();
    saveContacts();
    saveMet();
    refreshGroupPeers();
}

void Chat::pruneTrash()
{
    const qint64 cutoff = now() - kTrashDays * kDay;
    QStringList old;
    for (auto it = m_trash.cbegin(); it != m_trash.cend(); ++it)
        if (it.value().value(QStringLiteral("trashed")).toLongLong() < cutoff)
            old << it.key();
    for (const QString &k : std::as_const(old)) {
        m_trash.remove(k);
        forgetPerson(k);
    }
    if (!old.isEmpty())
        Q_EMIT changed();
}

// ------------------------------------------------------------------ receiving

int Chat::receive(const QByteArray &body, const QString &via, const QString &from)
{
    if (!m_ready)
        return 503;
    m_pendingPeer.reset();
    m_openedFrom.clear();
    const QJsonObject o = QJsonDocument::fromJson(body).object();
    // The Nearby room: at most 10 messages in 10 seconds from one address or link, whatever keys they use.
    if (!from.isEmpty() && o.value(QStringLiteral("room")).toString() == QLatin1String("nearby") && !roomRate(from))
        return 429;
    const int code = receiveEnvelope(o, via);
    // A ratchet message that wasn't accepted leaves no trace, so the same envelope can open later.
    m_pendingPeer.reset();
    if (code == 200 && !m_openedFrom.isEmpty()) {
        noteCapable(m_openedFrom, m_openedCap);
        noteCaps(m_openedFrom, m_openedHasCaps, m_openedCaps);
        noteDays(m_openedFrom, m_openedDays);
        noteS1(m_openedFrom, m_openedCaps.contains(QLatin1String(Proof::kCap)), m_openedBound);
    }
    m_openedFrom.clear();
    return code;
}

bool Chat::roomRate(const QString &from)
{
    const qint64 t = now();
    if (m_roomTimes.size() > 256)
        m_roomTimes.removeIf([t](const QHash<QString, QList<qint64>>::iterator it) { return it.value().isEmpty() || t - it.value().last() > 10000; });
    QList<qint64> &q = m_roomTimes[from];
    while (!q.isEmpty() && t - q.first() > 10000)
        q.removeFirst();
    if (q.size() >= 10)
        return false;
    q << t;
    return true;
}

void Chat::noteS1(const QString &key, bool said, bool bound)
{
    bool changed = false;
    if (said && !m_s1.contains(key) && m_s1.size() < 2000) {
        m_s1.insert(key);
        changed = true;
    }
    if (bound && !m_bound.contains(key) && m_bound.size() < 2000) {
        m_bound.insert(key, now());
        changed = true;
    }
    if (!changed)
        return;
    QJsonArray said1;
    for (const QString &k : std::as_const(m_s1))
        said1 << k;
    QJsonObject b;
    for (auto it = m_bound.cbegin(); it != m_bound.cend(); ++it)
        b.insert(it.key(), double(it.value()));
    m_vault->write(QStringLiteral("s1.json"), compact(QJsonObject{{QStringLiteral("said"), said1}, {QStringLiteral("bound"), b}}));
}

bool Chat::strict(const QString &key) const { return m_s1.contains(key) || hasCap(key, QLatin1String(Proof::kCap)); }

QString Chat::baseTo(const QString &key) const { return m_net->baseFor(key, strict(key)); }

bool Chat::sendBle(const QString &key, const QByteArray &json) { return m_ble->send(key, json, strict(key)); }

bool Chat::staleSealed(const QString &key, const QByteArray &body) const
{
    const QJsonObject o = QJsonDocument::fromJson(body).object();
    if (o.isEmpty())
        return false;
    if (strict(key) && !Proof::isBound(o))
        return true;
    return !ChatWire::isRatchet(o) && (ChatWire::useRatchet(o.value(QStringLiteral("room")).toString(), isContact(key) || isGroupPeer(key),
                                                            m_capable.value(key) || isGroupPeer(key))
                                       || fileRatchet(o.value(QStringLiteral("room")).toString(), key));
}

QString Chat::stranger(const QString &name) { return QStringLiteral("\u201C%1\u201D \u00B7 Not a contact \u00B7 nearby").arg(name); }

// Opens v1 or r1. An r1 message's peer is only kept by commitPeer(), once the message is accepted
// and before anything is sent back (a reply moves the same peer on).
QByteArray Chat::open(const QJsonObject &o, const QString &fromKey, bool *ok)
{
    *ok = false;
    const QByteArray sk = keyWith(fromKey);
    if (sk.isEmpty())
        return QByteArray();
    const ChatCrypto::KeyPair me{m_priv, m_pub};
    // Cap s1: a day after someone's envelopes first came sealed with their clear fields, an unsealed one (an earlier copy,
    // changed and sent again) is refused, and so is v1 in a room their app always ratchets.
    const qint64 boundAt = m_bound.value(fromKey, 0);
    if (boundAt > 0 && now() - boundAt > kDay
        && (!Proof::isBound(o) || (!ChatWire::isRatchet(o) && ChatWire::useRatchet(o.value(QStringLiteral("room")).toString(),
                                                                                    isContact(fromKey) || isGroupPeer(fromKey),
                                                                                    m_capable.value(fromKey) || isGroupPeer(fromKey)))))
        return QByteArray();
    QByteArray plain;
    if (ChatWire::isRatchet(o)) {
        // Many that don't open from one key: stop trying for a while before any ratchet work (key making, ECDH).
        QList<qint64> &fails = m_failures[fromKey];
        while (!fails.isEmpty() && now() - fails.first() > 60000)
            fails.removeFirst();
        if (fails.size() >= 20)
            return QByteArray();
        Ratchet::Peer work = m_peers.value(fromKey);
        plain = ChatWire::open(o, sk, me, &work, kNewKey, ok, myKey());
        if (*ok) {
            m_pendingKey = fromKey;
            m_pendingPeer = work;
        } else {
            if (m_failures.size() > 1000)
                m_failures.clear();
            m_failures[fromKey] << now();
        }
    } else {
        plain = ChatWire::open(o, sk, me, nullptr, kNewKey, ok, myKey());
    }
    if (*ok) {
        m_openedBound = Proof::isBound(o);
        m_openedFrom = fromKey;
        m_openedCap = ChatWire::saysCapable(o);
        m_openedCaps = ChatWire::capsOf(o, &m_openedHasCaps);
        m_openedDays = Limits::envelopeDays(o);
    }
    return *ok ? plain : QByteArray();
}

void Chat::commitPeer()
{
    if (!m_pendingPeer)
        return;
    m_peers.insert(m_pendingKey, *m_pendingPeer);
    m_pendingPeer.reset();
    saveRatchet(m_pendingKey);
}

void Chat::noteCapable(const QString &key, bool capable)
{
    // Only from a message that proved it's really them; only kept for people we know.
    if (!capable || m_capable.value(key) || (!isContact(key) && !m_met.contains(key) && !isGroupPeer(key)))
        return;
    m_capable.insert(key, true);
    saveRatchet(key);
}

int Chat::receiveEnvelope(const QJsonObject &o, const QString &via)
{
    // Moving to another device: nothing comes in here (it waits for the new device).
    if (m_moving)
        return 503;
    const QString fromKey = o.value(QStringLiteral("fromKey")).toString();
    QString name = o.value(QStringLiteral("fromName")).toString().left(40);
    if (name.isEmpty())
        name = QStringLiteral("Someone");
    if (fromKey.isEmpty() || fromKey == myKey())
        return 400;
    if (isBlocked(fromKey) || isTrashed(fromKey))
        return 200;   // blocked or in the trash: dropped quietly
    const QString room = o.value(QStringLiteral("room")).toString();
    // Labs bug reports ("lb", labs.cpp): the Huginn with the Labs key takes them from anyone; every other one drops
    // them quietly. 0: not a report.
    if (m_labs) {
        const int code = m_labs->receive(o, fromKey, name);
        if (code != 0)
            return code;
    }
    if (room == QLatin1String("photo"))
        return receivePiece(o, fromKey, name, via);
    QString mailbox = o.value(QStringLiteral("mailbox")).toString();
    if (mailbox.size() != 64)
        mailbox.clear();
    if (room == QLatin1String("group") || room == QLatin1String("gmsg") || room == QLatin1String("greceipt"))
        return receiveGroup(o, fromKey, name, mailbox, via);
    if (room == QLatin1String("gphoto"))
        return receiveGroupPiece(o, fromKey, name, mailbox, via);
    const QString sentId = o.value(QStringLiteral("id")).toString();
    // A copy that's already here (another route, or a resend): confirm again, don't count it.
    // Always before opening: a ratchet message only opens once.
    if ((room == QLatin1String("private") || room == QLatin1String("rich")) && !sentId.isEmpty() && known(fromKey, sentId)) {
        if (!m_held.value(fromKey).contains(sentId))
            receipt(fromKey, {sentId}, QStringLiteral("delivered"), mailbox);
        return 200;
    }
    if (room == QLatin1String("nearby") && !sentId.isEmpty()) {
        if (m_deleted.contains(sentId))
            return 200;
        for (const QVariant &v : std::as_const(m_nearby))
            if (str(v.toMap(), "id") == sentId)
                return 200;
    }
    const bool once = room == QLatin1String("receipt") || room == QLatin1String("unsend") || room == QLatin1String("keep") || room == QLatin1String("call")
        || room == QLatin1String("mx") || room == QLatin1String("live")
        || room == QLatin1String("intro") || room == QLatin1String("file") || room == QLatin1String("link") || room == QLatin1String("profile");
    if (once && m_seen.has(fromKey, sentId)) {
        // An unsend's resend: say "unsent" again in case the first answer was lost.
        const QString gone = m_seen.note(fromKey, sentId);
        if (room == QLatin1String("unsend") && !gone.isEmpty())
            receipt(fromKey, {gone}, QStringLiteral("unsent"), mailbox);
        // A reaction or edit sent again: my receipt didn't get there.
        if (room == QLatin1String("mx"))
            receipt(fromKey, {sentId}, QStringLiteral("delivered"), mailbox);
        return 200;
    }
    bool ok = false;
    const QByteArray plain = open(o, fromKey, &ok);
    if (!ok)
        return keyWith(fromKey).isEmpty() ? 400 : 403;
    // Calls: only from contacts and group peers, at most 30 signals in 10 seconds each.
    if (room == QLatin1String("call")) {
        // Contacts and group peers; someone met only while close by or on the same Wi-Fi.
        if (!isContact(fromKey) && !isGroupPeer(fromKey) && !(via != QLatin1String("internet") && m_met.contains(fromKey))) {
            m_pendingPeer.reset();
            return 403;
        }
        if (m_recentCall.size() > 500)
            m_recentCall.clear();
        QList<qint64> &q = m_recentCall[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= 30) {
            m_pendingPeer.reset();
            return 429;
        }
        q << now();
        const QJsonObject msg = QJsonDocument::fromJson(plain).object();
        if (msg.isEmpty()) {
            m_pendingPeer.reset();
            return 400;
        }
        commitPeer();
        if (!sentId.isEmpty())
            m_seen.add(fromKey, sentId, now());   // the same signal on another route is dropped
        // Calls turned off for them (the press-and-hold sheet): their ring never reaches this computer.
        if (!callsAllowed(fromKey) && isRing(msg))
            return 200;
        Q_EMIT callMessage(fromKey, msg);
        return 200;
    }
    // Caps i1 and f1 and linked devices (trust.cpp): introductions, files straight between devices
    // and a phone's companion, each with its own checks and limits.
    if (room == QLatin1String("intro") || room == QLatin1String("file") || room == QLatin1String("link")) {
        const int code = receiveExtra(room, fromKey, name, mailbox, QJsonDocument::fromJson(plain).object());
        if (code != 200) {
            m_pendingPeer.reset();
            return code;
        }
        commitPeer();
        if (!sentId.isEmpty())
            m_seen.add(fromKey, sentId, now());
        return 200;
    }
    // Kids' TV card (7.6): a contact invited this person to talk with their kids' TV and sent the
    // TV's contact code. Taken only from a contact, never as a key change; anything else in the room is
    // for Muninn (TVs and the parents who run them) and refused.
    if (room == QLatin1String("tvconfig")) {
        const QJsonObject msg = QJsonDocument::fromJson(plain).object();
        if (!isContact(fromKey) || msg.value(QStringLiteral("t")).toString() != QLatin1String("card")) {
            m_pendingPeer.reset();
            return 403;
        }
        commitPeer();
        const QString code = msg.value(QStringLiteral("code")).toString().left(2000);
        const QVariantMap tv = parseCode(code);
        const QString tvKey = str(tv, "key");
        if (!tv.isEmpty() && !tv.value(QStringLiteral("mine")).toBool() && !tv.value(QStringLiteral("expired")).toBool()
            && !isContact(tvKey) && contactWithMailbox(str(tv, "mailbox"), tvKey).isEmpty()) {
            addContact(code);
            const QString who = nameOf(fromKey), tvName = str(tv, "name");
            systemNote(tvKey, QStringLiteral("%1 added you to %2. You can talk with the kids here.").arg(who, tvName));
            Q_EMIT notice(QStringLiteral("%1 added you to %2").arg(who, tvName));
            Q_EMIT arrived(who, QStringLiteral("Added you to %1: you can talk with the kids there now").arg(tvName), tvKey);
        }
        return 200;
    }
    // Cap p1: a profile picture and colour, from a contact or group peer, one in 10 s each.
    if (room == QLatin1String("profile")) {
        const int code = receiveProfile(fromKey, sentId, QJsonDocument::fromJson(plain).object());
        if (code != 200)
            m_pendingPeer.reset();
        return code;
    }
    // Cap m1: reactions and edits ("mx"), typing and active status ("live"), with their own limits.
    if (room == QLatin1String("mx") || room == QLatin1String("live")) {
        const int code = receiveM1(room, fromKey, name, mailbox, sentId, QJsonDocument::fromJson(plain).object());
        if (code != 200)
            m_pendingPeer.reset();
        return code;
    }
    // A reply ("rich", cap m1) is a private message with the id it quotes; everything else is the same.
    QByteArray body = plain;
    QString re;
    QJsonObject richP;
    if (room == QLatin1String("rich")) {
        const QJsonObject p = QJsonDocument::fromJson(plain).object();
        if (!RichWire::ok(QStringLiteral("rich"), p)) {
            m_pendingPeer.reset();
            return 400;
        }
        body = p.value(QStringLiteral("text")).toString().toUtf8();
        re = p.value(QStringLiteral("re")).toString();
        richP = p;
    }
    // No more than 5 messages in 10 seconds from anyone, so nobody can flood a room. Counted only
    // once a message proved it's really from them, so nobody can use it to block a real contact.
    if (room != QLatin1String("receipt")) {
        // Only those quiet for 10 s go: clearing them all would let a flood of new keys wipe everyone's count.
        if (m_recent.size() > 500) {
            const qint64 t = now();
            m_recent.removeIf([t](const QHash<QString, QList<qint64>>::iterator it) { return it.value().isEmpty() || t - it.value().last() > 10000; });
        }
        QList<qint64> &q = m_recent[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= 5)
            return 429;
        q << now();
    }
    const QString text = QString::fromUtf8(body);
    // Section 9: text over the limit is malformed and refused, never cut.
    if ((room == QLatin1String("private") || room == QLatin1String("rich") || room == QLatin1String("nearby")) && GroupWire::length(text) > kMax) {
        m_pendingPeer.reset();
        return 400;
    }
    const QString msgId = sentId.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces) : sentId;
    QVariantMap incoming = message(msgId, fromKey, name, text, false, via);
    if (!re.isEmpty())
        incoming.insert(QStringLiteral("re"), re);
    MoreWire::keep(incoming, QStringLiteral("rich"), richP);   // cap m2: a poll or a link preview

    if (room == QLatin1String("nearby")) {
        if (!nearbyOn() || via == QLatin1String("internet"))
            return 403;
        commitPeer();
        addNearby(message(msgId, fromKey, name, text, false, via));
        return 200;
    }
    if (room == QLatin1String("private") || room == QLatin1String("rich")) {
        // A message from them ends their "typing".
        if (m_typing.remove(fromKey))
            Q_EMIT liveChanged();
        const QString claims = isContact(fromKey) || m_met.contains(fromKey) ? QString() : contactWithMailbox(mailbox, fromKey);
        if (isContact(fromKey)) {
            commitPeer();
            if (!mailbox.isEmpty() && str(m_contacts.value(fromKey), "mailbox") != mailbox) {
                m_contacts[fromKey].insert(QStringLiteral("mailbox"), mailbox);
                saveContacts();
            }
            if (addPrivate(fromKey, incoming)) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(nameOf(fromKey), text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (!claims.isEmpty()) {
            // A new key with a contact's inbox: maybe that contact with a new security key, maybe
            // not. Never taken on quietly: held until the person accepts (and is warned).
            commitPeer();
            m_held[fromKey].insert(msgId, incoming);
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name},
                                            {QStringLiteral("code"), codeWith(fromKey)}, {QStringLiteral("mailbox"), mailbox},
                                            {QStringLiteral("replaces"), claims}});
                Q_EMIT notice(QStringLiteral("Someone says they're %1 with a new security key. Check Chat before accepting.").arg(nameOf(claims)));
                Q_EMIT arrived(nameOf(claims), QStringLiteral("Says their security key changed"), fromKey);
            }
        } else if ((via != QLatin1String("internet") && visibleNearby()) || m_met.contains(fromKey)) {
            // Close by or on the same Wi-Fi (or someone met that way): no asking first. Hidden from
            // people nearby, only those already met get through this way.
            commitPeer();
            QVariantMap m = m_met.value(fromKey);
            m.insert(QStringLiteral("name"), name);
            if (!mailbox.isEmpty())
                m.insert(QStringLiteral("mailbox"), mailbox);
            putMet(fromKey, m);
            if (addPrivate(fromKey, incoming)) {
                m_unread.insert(fromKey);
                // Not a contact: the name they chose never shows as if it were one.
                Q_EMIT arrived(stranger(nameOf(fromKey)), text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (m_trust && m_trust->useOnce(o.value(QStringLiteral("invite")).toString())) {
            // My one-time invite link (i1, 13.6): whoever used it first is a contact now, no asking.
            commitPeer();
            m_contacts.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox},
                                        {QStringLiteral("verified"), false}});
            m_met.remove(fromKey);
            saveContacts();
            saveMet();
            sayHello(fromKey);
            if (addPrivate(fromKey, incoming)) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(nameOf(fromKey), text, fromKey);
            }
            Q_EMIT notice(QStringLiteral("%1 added you with your invite link.").arg(name));
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (m_labs && m_labs->replyAllowed(fromKey)) {
            // The Labs contact answering a bug report sent from here: an ordinary request, under its own name.
            commitPeer();
            const QString labsName = QString::fromLatin1(Labs::kName);
            incoming.insert(QStringLiteral("name"), labsName);
            m_held[fromKey].insert(msgId, incoming);
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), labsName},
                                            {QStringLiteral("code"), codeWith(fromKey)}, {QStringLiteral("mailbox"), mailbox}});
                Q_EMIT notice(labsName + QStringLiteral(" wants to chat"));
                Q_EMIT arrived(labsName, QStringLiteral("Wants to chat with you"), fromKey);
            }
        } else if (!inviteOk(o.value(QStringLiteral("invite")).toString())) {
            return 403;   // from far away with no valid invite (an expired or leaked code): dropped quietly
        } else {
            // Someone with this computer's code, from far away: ask first.
            commitPeer();
            m_held[fromKey].insert(msgId, incoming);
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name},
                                            {QStringLiteral("code"), codeWith(fromKey)}, {QStringLiteral("mailbox"), mailbox}});
                Q_EMIT notice(name + QStringLiteral(" wants to chat"));
                Q_EMIT arrived(name, QStringLiteral("Wants to chat with you"), fromKey);
            }
        }
        Q_EMIT changed();
        return 200;
    }
    if (room == QLatin1String("receipt")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey) && !isGroupPeer(fromKey))
            return 403;
        const QJsonObject r = QJsonDocument::fromJson(text.toUtf8()).object();
        const QString st = r.value(QStringLiteral("s")).toString();
        if (st != QLatin1String("delivered") && st != QLatin1String("read") && st != QLatin1String("unsent"))
            return 400;
        // Section 9: at most 40 ids a receipt; more is malformed and refused.
        const QJsonArray a = r.value(QStringLiteral("ids")).toArray();
        if (a.size() > GroupWire::kMaxReceiptIds)
            return 400;
        commitPeer();
        m_seen.add(fromKey, sentId, now());
        // A caps probe: answer with an empty receipt, whose envelope carries our caps.
        // Caps b1 and e1: their ring key and newest day key, from a contact or group peer.
        if (m_peerKeys && (isContact(fromKey) || isGroupPeer(fromKey)))
            m_peerKeys->take(fromKey, RingWire::announceOf(r, now() / 1000));
        if (r.value(QStringLiteral("q")).toString() == QLatin1String("caps")) {
            const QJsonObject a = withKeys(fromKey, QJsonObject{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}});
            deliver(fromKey, envelope(QStringLiteral("receipt"), fromKey, compact(a), QUuid::createUuid().toString(QUuid::WithoutBraces)),
                    mailbox.isEmpty() ? mailboxOf(fromKey) : mailbox);
        }
        QSet<QString> ids;
        for (const QJsonValue &v : a)
            ids.insert(v.toString());
        // A reaction or edit (cap m1) got there: stop resending it.
        bool mxDone = false;
        for (const QString &id : std::as_const(ids))
            if (st == QLatin1String("delivered") && str(m_mxOut.value(id), "key") == fromKey) {
                m_mxOut.remove(id);
                m_tries.remove(id);
                dropSealed(id);
                mxDone = true;
            }
        if (mxDone)
            saveMx();
        QVariantList &l = m_private[fromKey];
        bool any = false;
        QStringList done;
        for (QVariant &v : l) {
            QVariantMap m = v.toMap();
            if (!m.value(QStringLiteral("mine")).toBool() || !ids.contains(str(m, "id")))
                continue;
            if (st == QLatin1String("unsent")) {
                if (str(m, "state") == QLatin1String("unsending")) {
                    m.insert(QStringLiteral("state"), QStringLiteral("unsent"));
                    m_tries.remove(str(m, "id"));
                    done << QStringLiteral("u-") + str(m, "id");
                }
            } else if (str(m, "unsent").isEmpty() && rank(st) > rank(str(m, "state"))) {
                m.insert(QStringLiteral("state"), st);
                m.insert(QStringLiteral("failed"), false);
                done << str(m, "id");
            }
            v = m;
            any = true;
        }
        for (const QString &id : std::as_const(done))
            dropSealed(id);   // there: no more resends
        if (any) {
            save();
            Q_EMIT changed();
        }
        return 200;
    }
    if (room == QLatin1String("unsend")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey))
            return 403;
        commitPeer();
        const QString gone = text.trimmed();
        m_seen.add(fromKey, sentId, now(), gone);
        bool found = false;
        QVariantList &l = m_private[fromKey];
        for (QVariant &v : l) {
            QVariantMap m = v.toMap();
            if (str(m, "id") != gone || m.value(QStringLiteral("mine")).toBool())
                continue;
            found = true;
            if (!str(m, "photo").isEmpty())
                m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
            m.insert(QStringLiteral("text"), QString());
            m.insert(QStringLiteral("photo"), QString());
            m.insert(QStringLiteral("voice"), 0);
            m.insert(QStringLiteral("unsent"), name);   // shown with their current name or nickname (messagesWith)
            v = m;
        }
        if (!found) {
            // Not here yet (still on its way): make sure it never shows.
            forget({gone});
            if (m_held.contains(fromKey))
                m_held[fromKey].remove(gone);
        }
        save();
        receipt(fromKey, {gone}, QStringLiteral("unsent"), mailbox);
        Q_EMIT changed();
        return 200;
    }
    if (room == QLatin1String("keep")) {
        if (isContact(fromKey)) {
            commitPeer();
            m_seen.add(fromKey, sentId, now());
            // Already a contact here, so my own yes went missing (or they were added another way) and they wait
            // forever: say yes back, once, and they become a contact too.
            if (text == QLatin1String("yes") && !m_keptBack.contains(fromKey)) {
                m_keptBack.insert(fromKey);
                deliver(fromKey, envelope(QStringLiteral("keep"), fromKey, QByteArrayLiteral("yes"), QUuid::createUuid().toString(QUuid::WithoutBraces)),
                        mailbox.isEmpty() ? mailboxOf(fromKey) : mailbox);
            }
            return 200;
        }
        if (!m_met.contains(fromKey) && (via == QLatin1String("internet") || !visibleNearby() || !contactWithMailbox(mailbox, fromKey).isEmpty()))
            return 403;   // a new key with a contact's inbox goes through a request (a private message) instead
        commitPeer();
        m_seen.add(fromKey, sentId, now());
        QVariantMap m = m_met.value(fromKey, {{QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox}});
        if (text != QLatin1String("yes") || m.value(QStringLiteral("theySaid")).toBool())
            return 200;
        m.insert(QStringLiteral("theySaid"), true);
        if (!mailbox.isEmpty())
            m.insert(QStringLiteral("mailbox"), mailbox);
        putMet(fromKey, m);
        if (m.value(QStringLiteral("iSaid")).toBool())
            becomeContacts(fromKey);
        else {
            Q_EMIT notice(nameOf(fromKey) + QStringLiteral(" wants to keep in touch"));
            Q_EMIT arrived(nameOf(fromKey), QStringLiteral("Wants to keep in touch"), fromKey);
        }
        Q_EMIT changed();
        return 200;
    }
    return 400;
}

// ------------------------------------------------------------------ photos and voice
// Shrunk on the sending side, cut into 16 KB pieces, each sealed and sent like a message
// (room "photo"); the other side puts them back together and answers "delivered".

QString Chat::sendBlob(const QString &key, const QByteArray &bytes, int voiceMs, const QVariantMap &extra)
{
    const QString name = nameOf(key);
    if (!isContact(key))
        meet(key, name);
    const QString r = route(key);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_vault->write(QStringLiteral("photos/") + id, bytes);
    QVariantMap mine = message(id, key, username(), QString(), true, r);
    mine.insert(QStringLiteral("state"), QStringLiteral("sending"));
    mine.insert(QStringLiteral("photo"), id);
    mine.insert(QStringLiteral("voice"), voiceMs);
    mine.insert(extra);
    m_tries.insert(id, Tries{0, now() + kRetry[0]});
    addPrivate(key, mine);
    if (r.isEmpty() && backupMailbox(mine).isEmpty()) {
        update(key, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), true); });
        Q_EMIT notice(name + QStringLiteral(" is out of range. Say yes to Keep in touch to send from anywhere."));
        return id;
    }
    attempt(key, id);
    return id;
}

bool Chat::sendPhoto(const QString &key, const QString &path, bool hd)
{
    if (key.startsWith(QLatin1String("g:"))) {
        const QStringList parts = key.split(QLatin1Char(':'));
        return parts.size() == 3 && sendGroupPhoto(parts.at(1), parts.at(2), path, hd);
    }
    const QByteArray jpeg = Photos::prepare(path, hd);
    if (jpeg.isEmpty()) {
        Q_EMIT notice(QStringLiteral("That picture couldn't be opened."));
        return false;
    }
    sendBlob(key, jpeg, 0);
    return true;
}

void Chat::sendVoice(const QString &key, const QByteArray &aac, int ms)
{
    if (!Photos::isAdts(aac))
        return;
    // A group channel: "g:<group>:<channel>" (the group page records for it).
    if (key.startsWith(QLatin1String("g:"))) {
        const QStringList parts = key.split(QLatin1Char(':'));
        if (parts.size() == 3)
            sendGroupBlob(parts.at(1), parts.at(2), aac, qBound(1, ms, Photos::kMaxVoiceMs));
        return;
    }
    sendBlob(key, aac, qBound(1, ms, Photos::kMaxVoiceMs));
}

QByteArray Chat::blob(const QString &id) const { return id.isEmpty() ? QByteArray() : m_vault->read(QStringLiteral("photos/") + id); }

QString Chat::photoUrl(const QString &id)
{
    // QML shows pictures from a URL: a data URL keeps it in memory, never on disk unencrypted.
    const QByteArray b = blob(id);
    return b.isEmpty() ? QString() : QStringLiteral("data:image/jpeg;base64,") + b64(b);
}

bool Chat::savePhoto(const QString &id)
{
    const QByteArray b = blob(id);
    if (b.isEmpty())
        return false;
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation) + QStringLiteral("/Huginn");
    QDir().mkpath(dir);
    QFile f(dir + QStringLiteral("/Huginn-%1.jpg").arg(now()));
    return f.open(QIODevice::WriteOnly) && f.write(b) == b.size();
}

namespace
{
// The Pictures folder, the 40 newest. Runs on a worker thread: a big folder takes a while.
QVariantList findRecentPictures(const QString &root)
{
    QList<QFileInfo> found;
    if (!root.isEmpty() && QDir(root).exists()) {
        QDirIterator it(root, {QStringLiteral("*.jpg"), QStringLiteral("*.jpeg"), QStringLiteral("*.png"), QStringLiteral("*.webp")}, QDir::Files,
                        QDirIterator::Subdirectories);
        int n = 0;
        while (it.hasNext() && n < 4000) {
            const QFileInfo f(it.next());
            ++n;
            if (!f.path().contains(QLatin1String("/thumbnails")))
                found << f;
        }
    }
    std::sort(found.begin(), found.end(), [](const QFileInfo &a, const QFileInfo &b) { return a.lastModified() > b.lastModified(); });
    QVariantList out;
    for (const QFileInfo &f : found.mid(0, 40))
        out << QVariantMap{{QStringLiteral("name"), f.fileName()}, {QStringLiteral("detail"), f.lastModified().toString(QStringLiteral("MMM d, h:mm AP"))},
                           {QStringLiteral("url"), f.absoluteFilePath()}};
    return out;
}
}

void Chat::loadRecentPictures()
{
    const QString root = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation);
    QPointer<Chat> self(this);
    QThreadPool::globalInstance()->start([self, root] {
        const QVariantList list = findRecentPictures(root);
        QMetaObject::invokeMethod(qApp, [self, list] {
            if (self)
                Q_EMIT self->recentPicturesReady(list);
        }, Qt::QueuedConnection);
    });
}

void Chat::copyLink() const
{
    if (auto *c = QGuiApplication::clipboard())
        c->setText(myLink());
}

// Messages and codes copied from Chat: kept out of Windows clipboard history and cloud clipboard, and out of
// KDE's clipboard history, and cleared after a minute if they're still there.
void Chat::copyText(const QString &text) const
{
    auto *c = QGuiApplication::clipboard();
    if (!c)
        return;
    auto *data = new QMimeData;
    data->setText(text);
    const QByteArray no(4, 0);   // a DWORD 0
    data->setData(QStringLiteral("application/x-qt-windows-mime;value=\"CanIncludeInClipboardHistory\""), no);
    data->setData(QStringLiteral("application/x-qt-windows-mime;value=\"CanUploadToCloudClipboard\""), no);
    data->setData(QStringLiteral("application/x-qt-windows-mime;value=\"ExcludeClipboardContentFromMonitorProcessing\""), QByteArray(1, 0));
    data->setData(QStringLiteral("x-kde-passwordManagerHint"), QByteArrayLiteral("secret"));
    c->setMimeData(data);
    QTimer::singleShot(60000, qApp, [text] {
        if (auto *c = QGuiApplication::clipboard(); c && c->text() == text)
            c->clear();
    });
}

QList<QJsonObject> Chat::pieces(const QVariantMap &m)
{
    const QByteArray bytes = blob(str(m, "photo"));
    if (bytes.isEmpty())
        return {};
    const QString peer = str(m, "peer");
    const QList<QByteArray> parts = Photos::split(bytes);
    QList<QJsonObject> out;
    const int voice = m.value(QStringLiteral("voice")).toInt();
    for (int i = 0; i < parts.size(); ++i) {
        QJsonObject o{{QStringLiteral("room"), QStringLiteral("photo")}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                      {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), str(m, "id")}, {QStringLiteral("i"), i},
                      {QStringLiteral("n"), int(parts.size())}};
        if (voice > 0) {
            o.insert(QStringLiteral("kind"), QStringLiteral("voice"));
            o.insert(QStringLiteral("ms"), voice);
        }
        if (!str(m, "file").isEmpty()) {   // cap f1: a small file through the relays (14.2)
            o.insert(QStringLiteral("kind"), QStringLiteral("file"));
            o.insert(QStringLiteral("fn"), str(m, "file"));
        }
        MoreWire::addPieceFields(o, m);   // cap m2: a video message, or a photo in an album
        out << seal(o, peer, parts.at(i));   // each piece is its own message (r1: its own ratchet step)
    }
    return out;
}

int Chat::receivePiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &via)
{
    const bool contact = isContact(fromKey);
    if (!contact && (via == QLatin1String("internet") || !visibleNearby()) && !m_met.contains(fromKey))
        return 403;
    const QString id = o.value(QStringLiteral("id")).toString();
    const int n = o.value(QStringLiteral("n")).toInt(), i = o.value(QStringLiteral("i")).toInt(-1);
    if (id.isEmpty() || n < 1 || n > Photos::kMaxPieces || i < 0 || i >= n || !MoreWire::ok(QStringLiteral("piece"), o))
        return 400;
    // Files turned off for them: a file's pieces are dropped quietly.
    if (o.value(QStringLiteral("kind")).toString() == QLatin1String("file") && !filesAllowed(fromKey))
        return 200;
    QString mailbox = o.value(QStringLiteral("mailbox")).toString();
    if (mailbox.size() != 64)
        mailbox.clear();
    // Already have it (a copy from another route, or a resend): just confirm again.
    if (known(fromKey, id)) {
        receipt(fromKey, {id}, QStringLiteral("delivered"), mailbox);
        return 200;
    }
    // A piece already here: dropped before opening (a ratchet message only opens once).
    const auto have = m_incoming.constFind(id);
    if (have != m_incoming.cend() && have->from == fromKey && i < have->parts.size() && !have->parts.at(i).isEmpty())
        return 200;
    // A new key with a contact's inbox: only through a request (see receive).
    if (!contact && !m_met.contains(fromKey) && !contactWithMailbox(mailbox, fromKey).isEmpty())
        return 403;
    if (m_pieceTimes.size() > 500)
        m_pieceTimes.clear();
    QList<qint64> &q = m_pieceTimes[fromKey];
    while (!q.isEmpty() && now() - q.first() > 60000)
        q.removeFirst();
    if (q.size() >= 1000)
        return 429;   // about 15 MB a minute at most
    q << now();
    if (m_incoming.size() > 20 && !m_incoming.contains(id))
        return 429;
    bool ok = false;
    const QByteArray part = open(o, fromKey, &ok);
    if (!ok)
        return 403;
    Incoming &inc = m_incoming[id];
    if (inc.parts.isEmpty()) {
        inc.from = fromKey;
        inc.started = now();
        for (int k = 0; k < n; ++k)
            inc.parts << QByteArray();
    }
    if (inc.from != fromKey || inc.parts.size() != n)
        return 400;
    commitPeer();
    if (inc.parts.at(i).isEmpty()) {
        inc.parts[i] = part;
        inc.have++;
    }
    if (inc.have < n)
        return 200;
    const QList<QByteArray> parts = m_incoming.take(id).parts;
    const int voiceMs = o.value(QStringLiteral("kind")).toString() == QLatin1String("voice") ? qBound(1, o.value(QStringLiteral("ms")).toInt(), Photos::kMaxVoiceMs) : 0;
    // Cap f1: a file's name (14.2) was checked with the piece (MoreWire); its bytes are kept as they came.
    const QString fileName = o.value(QStringLiteral("kind")).toString() == QLatin1String("file") ? o.value(QStringLiteral("fn")).toString() : QString();
    if (o.value(QStringLiteral("kind")).toString() == QLatin1String("file") && !FileLink::nameOk(o.value(QStringLiteral("fn"))))
        return 400;
    const QVariantMap more = MoreWire::pieceFields(o);   // cap m2: a video message, or a photo in an album
    const QByteArray joined = !fileName.isEmpty() ? parts.join() : voiceMs > 0 ? Photos::joinVoice(parts)
                            : more.contains(QStringLiteral("video")) ? MoreWire::joinVideo(parts) : Photos::join(parts);   // join() also strips any metadata
    if (joined.isEmpty())
        return 400;
    m_vault->write(QStringLiteral("photos/") + id, joined);
    if (!contact) {
        QVariantMap m = m_met.value(fromKey);
        m.insert(QStringLiteral("name"), name);
        if (!mailbox.isEmpty())
            m.insert(QStringLiteral("mailbox"), mailbox);
        putMet(fromKey, m);
    }
    QVariantMap msg = message(id, fromKey, name, fileName.isEmpty() ? QString() : QStringLiteral("File: ") + fileName, false, via);
    msg.insert(QStringLiteral("photo"), id);
    msg.insert(QStringLiteral("voice"), voiceMs);
    msg.insert(more);
    if (!fileName.isEmpty())
        msg.insert(QStringLiteral("file"), fileName);
    if (addPrivate(fromKey, msg)) {
        m_unread.insert(fromKey);
        Q_EMIT arrived(nameOf(fromKey), !fileName.isEmpty() ? QStringLiteral("File: ") + fileName : voiceMs > 0 ? QStringLiteral("Voice message")
                                        : more.contains(QStringLiteral("video")) ? QStringLiteral("Video message")
                                        : more.contains(QStringLiteral("al")) ? QStringLiteral("Photos") : QStringLiteral("Photo"), fromKey);
    }
    receipt(fromKey, {id}, QStringLiteral("delivered"), mailbox);
    return 200;
}

// ------------------------------------------------------------------ sending

QVariantMap Chat::message(const QString &id, const QString &peer, const QString &name, const QString &text, bool mine, const QString &via) const
{
    return {{QStringLiteral("id"), id}, {QStringLiteral("peer"), peer}, {QStringLiteral("name"), name}, {QStringLiteral("text"), text},
            {QStringLiteral("mine"), mine}, {QStringLiteral("time"), now()}, {QStringLiteral("failed"), false}, {QStringLiteral("via"), via},
            {QStringLiteral("state"), QString()}, {QStringLiteral("photo"), QString()}, {QStringLiteral("unsent"), QString()}, {QStringLiteral("voice"), 0}};
}

QJsonObject Chat::envelope(const QString &room, const QString &theirKey, const QByteArray &plain, const QString &msgId)
{
    // Only these go in the clear: never a nickname or anything else kept about them here.
    QJsonObject o{{QStringLiteral("room"), room}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                  {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), msgId}};
    // The invite from their code, until they accept: proves they handed it out within the hour.
    const QString invite = str(m_contacts.value(theirKey), "invite");
    if (!invite.isEmpty())
        o.insert(QStringLiteral("invite"), invite);
    return seal(o, theirKey, plain);
}

// r1 (the ratchet) for private-room payloads to a contact whose app said it can; v1 otherwise.
QJsonObject Chat::seal(const QJsonObject &fields, const QString &theirKey, const QByteArray &plain)
{
    // A message from them that just opened moves the same peer: keep that first.
    if (m_pendingPeer && m_pendingKey == theirKey)
        commitPeer();
    // Cap e1: room "file" goes through the ratchet too, for an app that says e1.
    const bool r1 = ChatWire::useRatchet(fields.value(QStringLiteral("room")).toString(), isContact(theirKey) || isGroupPeer(theirKey),
                                         m_capable.value(theirKey) || isGroupPeer(theirKey))
        || fileRatchet(fields.value(QStringLiteral("room")).toString(), theirKey);
    Ratchet::Peer *peer = r1 ? &m_peers[theirKey] : nullptr;
    // Cap d1: how long I keep our chat, next to "caps" (only to an app that reads it).
    QJsonObject f = fields;
    if (hasD1(theirKey))
        f.insert(QStringLiteral("days"), myDays(theirKey));
    // Cap s1: the clear fields are sealed in too, for an app that reads them.
    const QJsonObject o = ChatWire::seal(f, keyWith(theirKey), theirKey, plain, peer, kNewKey, strict(theirKey));
    if (peer && ChatWire::isRatchet(o))
        saveRatchet(theirKey);   // saved before it leaves, so a key is never used twice
    return o;
}

void Chat::post(const QString &base, const QByteArray &body, std::function<void(int)> done)
{
    // The same certificate pinning as file transfers: only the device that announced itself.
    QNetworkRequest req = m_net->peerRequest(QUrl(base + kPath));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(6000);
    QNetworkReply *r = m_http.post(req, body);
    m_net->pinReply(r);
    connect(r, &QNetworkReply::finished, this, [r, done] {
        r->deleteLater();
        if (done)
            done(r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt());
    });
}

// Every route at once and always the internet too (protocol 0), so nobody on the Wi-Fi or close by can swallow it.
// Returns the best route that took it; one that didn't prove it's them (cap s1) never counts on its own.
QString Chat::deliver(const QString &key, const QJsonObject &body, const QString &mailbox)
{
    if (m_moving)   // moving to another device: nothing more is sealed here
        return QString();
    const QByteArray json = compact(body);
    QString how;
    if (sendBle(key, json) && m_ble->proven(key))
        how = QStringLiteral("bluetooth");
    const QString base = baseTo(key);
    if (!base.isEmpty()) {
        post(base, json);
        if (how.isEmpty() && m_net->provenFor(key))
            how = QStringLiteral("wifi");
    }
    const bool online = sendInternet(key, {json}, mailbox);
    return !how.isEmpty() ? how : online ? QStringLiteral("internet") : QString();
}

QString Chat::backupMailbox(const QVariantMap &m) const
{
    // Contacts always; someone met close by only for messages sent while they were in range.
    const QString key = str(m, "peer");
    const QString c = str(m_contacts.value(key), "mailbox");
    if (!c.isEmpty())
        return c;
    const QString via = str(m, "via");
    return via == QLatin1String("bluetooth") || via == QLatin1String("wifi") ? str(m_met.value(key), "mailbox") : QString();
}

void Chat::sendNearby(const QString &text)
{
    const QString t = GroupWire::clip(text.trimmed(), kMax);
    if (t.isEmpty() || !nearbyOn() || !m_ready)
        return;
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    addNearby(message(id, myKey(), username(), t, true, QString()));
    const QStringList muted = blockedKeys();
    QSet<QString> done;
    for (const QVariant &v : m_ble->peers()) {
        const QString k = str(v.toMap(), "chatKey");
        if (!k.isEmpty() && !muted.contains(k) && !done.contains(k)) {
            done.insert(k);
            m_ble->send(k, compact(envelope(QStringLiteral("nearby"), k, t.toUtf8(), id)));
        }
    }
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty() && !muted.contains(p.chatKey) && !done.contains(p.chatKey)) {
            done.insert(p.chatKey);
            post(p.base, compact(envelope(QStringLiteral("nearby"), p.chatKey, t.toUtf8(), id)));
        }
    if (done.isEmpty())
        Q_EMIT notice(QStringLiteral("Nobody nearby got that yet. They need Muninn or Huginn open close by or on the same Wi-Fi."));
}

void Chat::sendPrivate(const QString &key, const QString &text, const QString &re) { sendPrivateWith(key, text, re, QJsonObject()); }

// poll: cap m2, a poll whose text says the same for apps without m2.
QString Chat::sendPrivateWith(const QString &key, const QString &text, const QString &re, const QJsonObject &poll)
{
    const QString t = GroupWire::clip(text.trimmed(), kMax);
    if (t.isEmpty() || !m_ready)
        return QString();
    const QString name = nameOf(key);
    if (!isContact(key))
        meet(key, name);
    const QString r = route(key);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QVariantMap mine = message(id, key, username(), t, true, r);
    mine.insert(QStringLiteral("state"), QStringLiteral("sending"));
    if (RichWire::isMsgId(re))
        mine.insert(QStringLiteral("re"), re);
    if (!poll.isEmpty())
        mine.insert(QStringLiteral("poll"), poll.toVariantMap());
    m_typingSent.remove(key);
    m_tries.insert(id, Tries{0, now() + kRetry[0]});
    addPrivate(key, mine);
    if (r.isEmpty() && backupMailbox(mine).isEmpty()) {
        update(key, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), true); });
        Q_EMIT notice(isContact(key) ? name + QStringLiteral(" can't be reached: they're not nearby and have no internet mailbox yet.")
                                     : name + QStringLiteral(" is out of range. Say yes to Keep in touch to message from anywhere."));
        return id;
    }
    attemptWithPreview(key, id);   // a link gets its preview first (cap m2)
    return id;
}

// Every message goes out on every route at once (Bluetooth, Wi-Fi and the internet); the other
// side keeps the first copy. Until "delivered" comes back it's sent again after 5 s, then less often.
void Chat::attempt(const QString &key, const QString &id)
{
    if (m_moving)   // moving to another device: nothing more is sealed here
        return;
    QVariantMap m;
    for (const QVariant &v : m_private.value(key))
        if (str(v.toMap(), "id") == id)
            m = v.toMap();
    if (m.isEmpty())
        return;
    Tries &t = m_tries[id];
    t.count++;
    t.next = now() + retryDelay(t.count);
    const bool photo = !str(m, "photo").isEmpty();
    // Sealed once and kept: every try and every route sends these same bytes.
    const QList<QByteArray> bodies = sealedFor(key, id, m);
    if (bodies.isEmpty())
        return;
    if (photo)
        t.next = qMax(t.next, now() + bodies.size() * 200);   // don't start again while a big one is on its way
    bool any = false;
    auto markSent = [this, key, id](const QString &how) {
        update(key, id, [how](QVariantMap &x) {
            if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < 2) {
                x.insert(QStringLiteral("state"), QStringLiteral("sent"));
                x.insert(QStringLiteral("failed"), false);
                if (str(x, "via").isEmpty())
                    x.insert(QStringLiteral("via"), how);
            }
        });
    };
    // Cap s1: only a route that proved it's them counts as sent; one that can't (an app without s1) is just extra.
    bool ble = true;
    for (const QByteArray &b : bodies)
        ble = ble && sendBle(key, b);
    if (ble && m_ble->proven(key)) {
        any = true;
        markSent(QStringLiteral("bluetooth"));
        if (photo)
            t.next = qMax(t.next, now() + bodies.size() * 3000);   // about 3 s a piece over Bluetooth: don't pile up resends
    }
    const QString base = baseTo(key);
    if (!base.isEmpty()) {
        const bool proven = m_net->provenFor(key);
        any = any || proven;
        auto left = std::make_shared<int>(int(bodies.size()));
        auto good = std::make_shared<bool>(proven);
        for (const QByteArray &b : bodies)
            post(base, b, [this, key, id, left, good](int code) {
                *good = *good && code == 200;
                if (--*left > 0 || !*good)
                    return;
                // "Sent" only: a 200 just means some device answered. "Delivered" comes from their sealed
                // receipt, which only the real person can make.
                update(key, id, [](QVariantMap &x) {
                    if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < 2) {
                        x.insert(QStringLiteral("state"), QStringLiteral("sent"));
                        x.insert(QStringLiteral("via"), QStringLiteral("wifi"));
                        x.insert(QStringLiteral("failed"), false);
                    }
                });
            });
    }
    const QString mailbox = backupMailbox(m);
    // Big photos only go over the internet if the close routes didn't deliver them within 5 s.
    if (!mailbox.isEmpty() && (!photo || !any || t.count >= 2) && bodies.size() <= Photos::kInternetPieces) {
        if (sendInternet(key, bodies, mailbox, [markSent] { markSent(QStringLiteral("internet")); }))
            any = true;
    }
    if (!any && t.count == 1)
        update(key, id, [](QVariantMap &x) { x.insert(QStringLiteral("failed"), true); });
}

void Chat::retryLoop()
{
    const qint64 t0 = now();
    for (auto it = m_incoming.begin(); it != m_incoming.end();)
        it = t0 - it.value().started > 15 * 60000 ? m_incoming.erase(it) : std::next(it);   // half a photo that never finished
    // Someone said yes while out of range: say it again when they're back.
    for (const QString &k : m_unsentKeeps.keys())
        if (!route(k).isEmpty() && !deliver(k, m_unsentKeeps.value(k), mailboxOf(k)).isEmpty())
            m_unsentKeeps.remove(k);
    QList<QPair<QString, QString>> due, unsends;
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        for (const QVariant &v : it.value()) {
            const QVariantMap m = v.toMap();
            const QString id = str(m, "id");
            if (!m.value(QStringLiteral("mine")).toBool() || m_trash.contains(it.key()))
                continue;
            if (str(m, "state") == QLatin1String("unsending")) {
                Tries &t = m_tries[id];
                if (t.next == 0)
                    t = Tries{1, t0 + kRetry[0]};
                if (t.count > 300) {   // about a day: their app may not answer unsends
                    unsends << qMakePair(it.key(), QStringLiteral("!") + id);
                    continue;
                }
                if (t0 >= t.next)
                    unsends << qMakePair(it.key(), id);
                continue;
            }
            // State "" has no receipts: those were sent already, leave them be.
            if (str(m, "state").isEmpty() || !str(m, "unsent").isEmpty() || rank(str(m, "state")) >= 3 || t0 - m.value(QStringLiteral("time")).toLongLong() > kDay) {
                m_tries.remove(id);
                dropSealed(id);
                continue;
            }
            if (!m_tries.contains(id)) {
                m_tries.insert(id, Tries{1, t0 + kRetry[0]});   // after a restart: wait one round
                continue;
            }
            if (t0 >= m_tries.value(id).next)
                due << qMakePair(it.key(), id);
        }
    for (const auto &d : due)
        attempt(d.first, d.second);
    groupRetry();
    // Cap m1: reactions and edits still on their way; typing and active status that ran out.
    QStringList mxDue, mxGone;
    for (auto it = m_mxOut.cbegin(); it != m_mxOut.cend(); ++it) {
        if (t0 - it.value().value(QStringLiteral("time")).toLongLong() > kDay)
            mxGone << it.key();
        else if (!m_tries.contains(it.key()))
            m_tries.insert(it.key(), Tries{1, t0 + kRetry[0]});
        else if (t0 >= m_tries.value(it.key()).next)
            mxDue << it.key();
    }
    for (const QString &e : std::as_const(mxGone)) {
        m_mxOut.remove(e);
        m_tries.remove(e);
        dropSealed(e);
    }
    if (!mxGone.isEmpty())
        saveMx();
    for (const QString &e : std::as_const(mxDue))
        attemptMx(e);
    liveTick();
    for (const auto &u : unsends) {
        if (u.second.startsWith(QLatin1Char('!'))) {
            const QString id = u.second.mid(1);
            update(u.first, id, [](QVariantMap &x) { x.insert(QStringLiteral("state"), QStringLiteral("unsent")); });
            m_tries.remove(id);
            dropSealed(QStringLiteral("u-") + id);
        } else {
            attemptUnsend(u.first, u.second);
        }
    }
}

void Chat::retry(const QString &key, const QString &id)
{
    m_tries.remove(id);
    update(key, id, [](QVariantMap &x) {
        x.insert(QStringLiteral("failed"), false);
        x.insert(QStringLiteral("state"), QStringLiteral("sending"));
        x.insert(QStringLiteral("time"), now());
    });
    attempt(key, id);
}

void Chat::receipt(const QString &key, const QStringList &ids, const QString &state, const QString &mailbox)
{
    if (ids.isEmpty())
        return;
    const QString box = mailbox.isEmpty() ? mailboxOf(key) : mailbox;
    // 40 ids a receipt keeps each one small on every route.
    const QStringList last = ids.mid(qMax(0, int(ids.size()) - 200));
    for (int i = 0; i < last.size(); i += 40) {
        const QJsonObject r = withKeys(key, QJsonObject{{QStringLiteral("ids"), QJsonArray::fromStringList(last.mid(i, 40))}, {QStringLiteral("s"), state}});
        deliver(key, envelope(QStringLiteral("receipt"), key, QJsonDocument(r).toJson(QJsonDocument::Compact), QUuid::createUuid().toString(QUuid::WithoutBraces)), box);
    }
}

void Chat::markRead(const QString &key)
{
    m_openChat = key;
    if (m_unread.remove(key))
        Q_EMIT changed();
    if (!readReceipts())
        return;
    QStringList fresh;
    QVariantList &l = m_private[key];
    for (QVariant &v : l) {
        QVariantMap m = v.toMap();
        if (!m.value(QStringLiteral("mine")).toBool() && !m.value(QStringLiteral("system")).toBool() && str(m, "state") != QLatin1String("read")) {
            fresh << str(m, "id");
            m.insert(QStringLiteral("state"), QStringLiteral("read"));
            v = m;
        }
    }
    if (fresh.isEmpty())
        return;
    save();
    receipt(key, fresh, QStringLiteral("read"), QString());
}

void Chat::unsend(const QString &key, const QString &id)
{
    bool found = false;
    update(key, id, [this, &found](QVariantMap &m) {
        if (!m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty())
            return;
        if (!str(m, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        m.insert(QStringLiteral("text"), QString());
        m.insert(QStringLiteral("photo"), QString());
        m.insert(QStringLiteral("voice"), 0);
        m.insert(QStringLiteral("unsent"), QStringLiteral("You"));
        m.insert(QStringLiteral("state"), QStringLiteral("unsending"));
        m.insert(QStringLiteral("failed"), false);
        found = true;
    });
    if (!found)
        return;
    m_tries.insert(id, Tries{0, 0});
    attemptUnsend(key, id);
}

void Chat::attemptUnsend(const QString &key, const QString &id)
{
    if (m_moving)   // moving to another device: nothing more is sealed here
        return;
    Tries &t = m_tries[id];
    t.count++;
    t.next = now() + retryDelay(t.count);
    // Sealed once (kept as "u-<id>"), then the same bytes on every try and route.
    const QString cid = QStringLiteral("u-") + id;
    QByteArray body;
    const ChatWire::Outbox::Entry *e = m_outbox.find(cid);
    if (e && e->key == key && !e->bodies.empty() && !staleSealed(key, e->bodies.front())) {
        body = e->bodies.front();
    } else {
        body = compact(envelope(QStringLiteral("unsend"), key, id.toUtf8(), QUuid::createUuid().toString(QUuid::WithoutBraces)));
        keepSealed(cid, key, {body});
    }
    sendBle(key, body);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, body);
    sendInternet(key, {body}, mailboxOf(key));
}

// ------------------------------------------------------------------ sealed envelopes and ratchet state

QList<QByteArray> Chat::sealedFor(const QString &key, const QString &id, const QVariantMap &m)
{
    const ChatWire::Outbox::Entry *e = m_outbox.find(id);
    // Sealed before their app said s1 (or had the ratchet): sealed again the way it reads.
    if (e && e->key == key && !e->bodies.empty() && !staleSealed(key, e->bodies.front()))
        return QList<QByteArray>(e->bodies.begin(), e->bodies.end());
    QList<QByteArray> bodies;
    if (!str(m, "photo").isEmpty()) {
        for (const QJsonObject &o : pieces(m))
            bodies << compact(o);
    } else {
        // A reply, poll or link preview goes as "rich" to an app with m1; the same text without them to any other.
        const bool rich = (!str(m, "re").isEmpty() || m.contains(QStringLiteral("poll")) || m.contains(QStringLiteral("lp"))) && hasM1(key);
        bodies << compact(rich ? envelope(QStringLiteral("rich"), key, MoreWire::richText(str(m, "text"), str(m, "re"), m.value(QStringLiteral("poll")).toMap(),
                                                                                           m.value(QStringLiteral("lp")).toMap()), id)
                               : envelope(QStringLiteral("private"), key, str(m, "text").toUtf8(), id));
    }
    if (!bodies.isEmpty())
        keepSealed(id, key, bodies);
    return bodies;
}

void Chat::keepSealed(const QString &id, const QString &key, const QList<QByteArray> &bodies)
{
    ChatWire::Outbox::Entry e;
    e.time = now();
    e.key = key;
    e.bodies.assign(bodies.begin(), bodies.end());
    m_outbox.put(id, e);
    // On disk too, so a restart resends the same bytes instead of sealing again.
    if (fileSafe(id))
        m_vault->write(QStringLiteral("outbox/") + id, ChatWire::Outbox::save(e));
}

void Chat::dropSealed(const QString &id)
{
    if (m_outbox.drop(id) && fileSafe(id))
        m_vault->remove(QStringLiteral("outbox/") + id);
}

// Kept envelopes go once their message is delivered, unsent, deleted, or a day old.
void Chat::sweepSealed()
{
    const qint64 t = now();
    for (const QString &id : m_outbox.ids()) {
        const ChatWire::Outbox::Entry *e = m_outbox.find(id);
        bool keep = m_gout.contains(id) || m_mxOut.contains(id);   // a group message waiting for that member, or a reaction or edit
        if (!keep && e && t - e->time <= kDay) {
            const bool unsend = id.startsWith(QLatin1String("u-"));
            const QString msgId = unsend ? id.mid(2) : id;
            for (const QVariant &v : m_private.value(e->key)) {
                const QVariantMap m = v.toMap();
                if (str(m, "id") != msgId || !m.value(QStringLiteral("mine")).toBool())
                    continue;
                keep = unsend ? str(m, "state") == QLatin1String("unsending")
                              : str(m, "unsent").isEmpty() && rank(str(m, "state")) >= 1 && rank(str(m, "state")) <= 2;
            }
        }
        if (!keep)
            dropSealed(id);
    }
}

QString Chat::ratchetFile(const QString &key) const
{
    return QStringLiteral("ratchet/") + QString::fromLatin1(QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256).toHex().left(32))
        + QStringLiteral(".json");
}

void Chat::loadRatchet()
{
    const QStringList files = QDir(m_vault->path(QStringLiteral("ratchet"))).entryList(QDir::Files);
    for (const QString &f : files) {
        const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QStringLiteral("ratchet/") + f)).object();
        const QString key = o.value(QStringLiteral("key")).toString();
        if (key.isEmpty())
            continue;
        if (o.value(QStringLiteral("cap")).toBool())
            m_capable.insert(key, true);
        if (o.value(QStringLiteral("caps")).isString())
            m_caps.insert(key, o.value(QStringLiteral("caps")).toString().split(QLatin1Char(' '), Qt::SkipEmptyParts));
        if (o.contains(QStringLiteral("peer"))) {
            bool ok = false;
            const Ratchet::Peer p = Ratchet::Peer::fromJson(o.value(QStringLiteral("peer")).toObject(), &ok);
            if (!p.sessions.empty())
                m_peers.insert(key, p);
        }
    }
    const QStringList kept = QDir(m_vault->path(QStringLiteral("outbox"))).entryList(QDir::Files);
    for (const QString &id : kept) {
        ChatWire::Outbox::Entry e;
        if (fileSafe(id) && ChatWire::Outbox::load(m_vault->read(QStringLiteral("outbox/") + id), &e))
            m_outbox.put(id, e);
        else
            m_vault->remove(QStringLiteral("outbox/") + id);
    }
}

void Chat::saveRatchet(const QString &key)
{
    const bool cap = m_capable.value(key);
    const auto p = m_peers.constFind(key);
    if (!cap && !m_caps.contains(key) && (p == m_peers.cend() || p->sessions.empty())) {
        m_vault->remove(ratchetFile(key));
        return;
    }
    QJsonObject o{{QStringLiteral("key"), key}, {QStringLiteral("cap"), cap}};
    if (m_caps.contains(key))
        o.insert(QStringLiteral("caps"), m_caps.value(key).join(QLatin1Char(' ')));
    if (p != m_peers.cend())
        o.insert(QStringLiteral("peer"), p->toJson());
    m_vault->write(ratchetFile(key), compact(o));
}

void Chat::forgetRatchet(const QString &key)
{
    m_peers.remove(key);
    m_capable.remove(key);
    m_caps.remove(key);
    if (m_pendingPeer && m_pendingKey == key)
        m_pendingPeer.reset();
    m_vault->remove(ratchetFile(key));
}

// ------------------------------------------------------------------ keeping

bool Chat::known(const QString &key, const QString &id) const
{
    if (m_deleted.contains(id) || m_held.value(key).contains(id))
        return true;
    for (const QVariant &v : m_private.value(key))
        if (str(v.toMap(), "id") == id)
            return true;
    return false;
}

void Chat::forget(const QStringList &ids)
{
    const qint64 t = now();
    for (const QString &id : ids)
        m_deleted.insert(id, t);
    for (auto it = m_deleted.begin(); it != m_deleted.end();)
        it = t - it.value() > 2 * kDay ? m_deleted.erase(it) : std::next(it);
    QJsonObject o;
    for (auto it = m_deleted.begin(); it != m_deleted.end(); ++it)
        o.insert(it.key(), double(it.value()));
    m_vault->write(QStringLiteral("deleted.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool Chat::addPrivate(const QString &peer, const QVariantMap &m)
{
    const QString id = str(m, "id");
    if (m_deleted.contains(id))
        return false;
    QVariantList &l = m_private[peer];
    for (const QVariant &v : std::as_const(l))
        if (str(v.toMap(), "id") == id)
            return false;
    l << m;
    while (l.size() > 1000)
        l.removeFirst();
    save();
    if (m_linked)
        m_linked->onMessage(peer, m);   // a linked companion gets it too
    Q_EMIT changed();
    return true;
}

void Chat::update(const QString &peer, const QString &id, const std::function<void(QVariantMap &)> &f)
{
    if (!m_private.contains(peer))
        return;
    QVariantList &l = m_private[peer];
    for (QVariant &v : l) {
        QVariantMap m = v.toMap();
        if (str(m, "id") == id) {
            f(m);
            v = m;
        }
    }
    save();
    Q_EMIT changed();
}

void Chat::addNearby(const QVariantMap &m)
{
    for (const QVariant &v : std::as_const(m_nearby))
        if (str(v.toMap(), "id") == str(m, "id"))
            return;
    m_nearby << m;
    while (m_nearby.size() > 300)
        m_nearby.removeFirst();
    if (keepNearby())
        save();
    Q_EMIT changed();
}

void Chat::deleteMessage(const QString &key, const QString &id)
{
    forget({id});
    QVariantList &l = m_private[key];
    for (int i = l.size() - 1; i >= 0; --i)
        if (str(l.at(i).toMap(), "id") == id) {
            const QString p = str(l.at(i).toMap(), "photo");
            if (!p.isEmpty())
                m_vault->remove(QStringLiteral("photos/") + p);
            l.removeAt(i);
        }
    save();
    Q_EMIT changed();
}

void Chat::deleteNearbyMessage(const QString &id)
{
    forget({id});
    for (int i = m_nearby.size() - 1; i >= 0; --i)
        if (str(m_nearby.at(i).toMap(), "id") == id)
            m_nearby.removeAt(i);
    if (keepNearby())
        save();
    Q_EMIT changed();
}

void Chat::deleteAll()
{
    if (!m_ready)
        return;   // locked: nothing could be saved, so don't pretend
    m_private.clear();
    m_nearby.clear();
    for (Group &gr : m_groups)
        gr.messages.clear();
    m_gidx.clear();
    saveGroupChats();
    QDir(m_vault->path(QStringLiteral("photos"))).removeRecursively();
    m_vault->remove(QStringLiteral("nearby.json"));
    save();
    sweepSealed();
    Q_EMIT changed();
}

void Chat::prune()
{
    if (m_ready)
        pruneTrash();
    // Every chat's message limit (cap d1, chatlimits.cpp); runs at start too.
    pruneToLimits();
    Q_EMIT pruned();
}

void Chat::save()
{
    QJsonObject priv;
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        priv.insert(it.key(), QJsonArray::fromVariantList(it.value()));
    m_vault->write(QStringLiteral("chats.json"), QJsonDocument(priv).toJson(QJsonDocument::Compact));
    if (keepNearby())
        m_vault->write(QStringLiteral("nearby.json"), QJsonDocument(QJsonArray::fromVariantList(m_nearby)).toJson(QJsonDocument::Compact));
}

void Chat::saveMet()
{
    QJsonObject o;
    for (auto it = m_met.begin(); it != m_met.end(); ++it)
        o.insert(it.key(), QJsonObject::fromVariantMap(it.value()));
    m_vault->write(QStringLiteral("met.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

void Chat::saveContacts()
{
    // Adding a group peer as a contact just makes them a contact (shared/docs/protocol-0.6.md 3.7).
    bool peers = false;
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (m_gpeers.remove(it.key()))
            peers = true;
    if (peers)
        saveGroups();
    QJsonArray a;
    for (const QVariantMap &c : std::as_const(m_contacts))
        a << QJsonObject::fromVariantMap(c);
    for (const QVariantMap &c : std::as_const(m_trash))
        a << QJsonObject::fromVariantMap(c);   // marked "trashed"
    m_vault->write(QStringLiteral("contacts.json"), QJsonDocument(a).toJson(QJsonDocument::Compact));
}

void Chat::load()
{
    const QJsonObject priv = QJsonDocument::fromJson(m_vault->read(QStringLiteral("chats.json"))).object();
    for (auto it = priv.begin(); it != priv.end(); ++it)
        m_private.insert(it.key(), it.value().toArray().toVariantList());
    // Chats in a plain file (with a "private" section) are brought into the vault.
    const QString old = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/chats.json");
    if (QFile::exists(old)) {
        QFile f(old);
        if (f.open(QIODevice::ReadOnly)) {
            const QJsonObject p = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("private")).toObject();
            for (auto it = p.begin(); it != p.end(); ++it)
                if (!m_private.contains(it.key()))
                    m_private.insert(it.key(), it.value().toArray().toVariantList());
            f.close();
        }
        QJsonObject check;
        for (auto it = m_private.begin(); it != m_private.end(); ++it)
            check.insert(it.key(), QJsonArray::fromVariantList(it.value()));
        if (m_vault->write(QStringLiteral("chats.json"), QJsonDocument(check).toJson(QJsonDocument::Compact)))
            QFile::remove(old);   // only once the encrypted copy is saved
    }
    // A message with a display time ("8:41 PM") gets a time in milliseconds.
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        for (QVariant &v : it.value()) {
            QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("time")).typeId() == QMetaType::QString) {
                m.insert(QStringLiteral("time"), now());
                v = m;
            }
        }
    const QJsonObject met = QJsonDocument::fromJson(m_vault->read(QStringLiteral("met.json"))).object();
    for (auto it = met.begin(); it != met.end(); ++it)
        m_met.insert(it.key(), it.value().toObject().toVariantMap());
    const QJsonArray contacts = QJsonDocument::fromJson(m_vault->read(QStringLiteral("contacts.json"))).array();
    for (const QJsonValue &c : contacts) {
        const QJsonObject o = c.toObject();
        (o.contains(QStringLiteral("trashed")) ? m_trash : m_contacts).insert(o.value(QStringLiteral("key")).toString(), o.toVariantMap());
    }
    const QJsonObject inv = QJsonDocument::fromJson(m_vault->read(QStringLiteral("invites.json"))).object();
    for (auto it = inv.begin(); it != inv.end(); ++it)
        m_invites.insert(it.key(), qint64(it.value().toDouble()));
    const QJsonObject del = QJsonDocument::fromJson(m_vault->read(QStringLiteral("deleted.json"))).object();
    for (auto it = del.begin(); it != del.end(); ++it)
        m_deleted.insert(it.key(), qint64(it.value().toDouble()));
    if (keepNearby())
        m_nearby = QJsonDocument::fromJson(m_vault->read(QStringLiteral("nearby.json"))).array().toVariantList();
    loadFolders();
    loadGroups();
    loadFlags();
    loadLinks();
    loadRatchet();
    noteLimitsOnce();
    prune();
    sweepSealed();
}

// ------------------------------------------------------------------ lists for the screen

QVariantList Chat::chats() const
{
    // Contacts, plus anyone else there's a conversation with (met close by, or removed as a contact).
    QSet<QString> keys;
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        keys.insert(it.key());
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        if (!it.value().isEmpty())
            keys.insert(it.key());
    QVariantList out;
    for (const QString &k : keys) {
        if (isBlocked(k) || isTrashed(k))
            continue;
        const QVariantList msgs = m_private.value(k);
        const QVariantMap last = msgs.isEmpty() ? QVariantMap() : msgs.last().toMap();
        QString preview;
        if (!last.isEmpty()) {
            const bool mine = last.value(QStringLiteral("mine")).toBool();
            if (!str(last, "unsent").isEmpty())
                preview = QStringLiteral("Message deleted");
            else if (last.value(QStringLiteral("voice")).toInt() > 0)
                preview = mine ? QStringLiteral("You sent a voice message") : QStringLiteral("Voice message");
            else if (!str(last, "file").isEmpty())
                preview = (mine ? QStringLiteral("You sent ") : QString()) + str(last, "file");
            else if (last.value(QStringLiteral("video")).toInt() > 0)
                preview = mine ? QStringLiteral("You sent a video message") : QStringLiteral("Video message");
            else if (last.contains(QStringLiteral("poll")))
                preview = QStringLiteral("Poll: ") + last.value(QStringLiteral("poll")).toMap().value(QStringLiteral("q")).toString();
            else if (!str(last, "photo").isEmpty())
                preview = mine ? QStringLiteral("You sent a photo") : QStringLiteral("Photo");
            else
                preview = str(last, "text");
        }
        // Not a contact: marked, so a name they chose ("Mom") never looks like one of mine.
        out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), isContact(k) ? nameOf(k) : stranger(nameOf(k))}, {QStringLiteral("last"), preview},
                           {QStringLiteral("time"), last.value(QStringLiteral("time"), 0)}, {QStringLiteral("route"), route(k)},
                           {QStringLiteral("unread"), m_unread.contains(k) || shouldAskKeep(k)}, {QStringLiteral("contact"), isContact(k)},
                           {QStringLiteral("folders"), foldersOf(k)}};
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("time")).toLongLong() > b.toMap().value(QStringLiteral("time")).toLongLong();
    });
    return out;
}

QVariantList Chat::requests() const
{
    QVariantList out;
    for (QVariantMap r : m_requests) {
        if (!str(r, "replaces").isEmpty())
            r.insert(QStringLiteral("replacesName"), nameOf(str(r, "replaces")));
        out << r;
    }
    return out;
}

QVariantList Chat::messagesWith(const QString &key) const
{
    // Their messages show the name they have here now (the nickname when there is one).
    QVariantList out = m_private.value(key);
    const QString shown = nameOf(key);
    const QVariantList all = out;
    const M1Index idx = m1Index(all);
    for (QVariant &v : out) {
        QVariantMap m = withM1(v.toMap(), all, idx, shown);
        if (m.value(QStringLiteral("mine")).toBool() || m.value(QStringLiteral("system")).toBool()) {
            v = m;
            continue;
        }
        m.insert(QStringLiteral("name"), shown);
        if (!str(m, "unsent").isEmpty())
            m.insert(QStringLiteral("unsent"), shown);
        v = m;
    }
    return withM2(out, QString());   // cap m2: albums together, polls counted, previews ready to show
}

QVariantList Chat::around() const
{
    // Everyone reachable right now that there's no chat with yet: Bluetooth first, then Wi-Fi.
    QVariantList out;
    if (!visibleNearby())
        return out;   // hidden from them, so they're hidden here too
    QSet<QString> seen;
    for (const QVariant &v : m_ble->peers()) {
        const QString k = str(v.toMap(), "chatKey");
        if (k.isEmpty() || seen.contains(k) || isBlocked(k) || isTrashed(k) || isContact(k) || !m_private.value(k).isEmpty())
            continue;
        seen.insert(k);
        out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), str(v.toMap(), "name")}, {QStringLiteral("route"), QStringLiteral("bluetooth")}};
    }
    for (const LocalSend::Peer &p : m_net->chatPeers()) {
        if (p.chatKey.isEmpty() || seen.contains(p.chatKey) || isBlocked(p.chatKey) || isTrashed(p.chatKey) || isContact(p.chatKey) || !m_private.value(p.chatKey).isEmpty())
            continue;
        seen.insert(p.chatKey);
        out << QVariantMap{{QStringLiteral("key"), p.chatKey}, {QStringLiteral("name"), p.chatName.isEmpty() ? p.alias : p.chatName}, {QStringLiteral("route"), QStringLiteral("wifi")}};
    }
    return out;
}

QVariantList Chat::people() const
{
    // Contacts: in touch, met close by, and blocked.
    QVariantList out;
    QSet<QString> seen;
    // "Nickname (their name)" when there's a nickname.
    auto shown = [this](const QString &key) {
        const QString nick = nicknameOf(key);
        return nick.isEmpty() ? realNameOf(key) : QStringLiteral("%1 (%2)").arg(nick, realNameOf(key));
    };
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it) {
        seen.insert(it.key());
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), shown(it.key())}, {QStringLiteral("contact"), true},
                           {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
    }
    for (auto it = m_met.begin(); it != m_met.end(); ++it)
        if (!seen.contains(it.key())) {
            seen.insert(it.key());
            out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), shown(it.key())}, {QStringLiteral("contact"), false},
                               {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
        }
    for (const QString &k : blockedKeys())
        if (!seen.contains(k) && !isTrashed(k))
            out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), shown(k)}, {QStringLiteral("contact"), false},
                               {QStringLiteral("verified"), false}, {QStringLiteral("blocked"), true}};
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return str(a.toMap(), "name").toLower() < str(b.toMap(), "name").toLower();
    });
    return out;
}

int Chat::nearbyCount() const
{
    QSet<QString> keys;
    for (const QVariant &v : m_ble->peers())
        keys.insert(str(v.toMap(), "chatKey"));
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty())
            keys.insert(p.chatKey);
    keys.remove(QString());
    return int(keys.size());
}

// ------------------------------------------------------------------ cap m1: reactions, edits, typing, active status
// shared/docs/protocol-0.6.md section 11, the same as Muninn's net/Chat.kt and Rich.kt. Only ever sent to someone
// whose app said "m1".

QStringList Chat::quickReactions() const
{
    return QString::fromUtf8("❤️ 😂 👍 😮 😢").split(QLatin1Char(' '), Qt::SkipEmptyParts);
}

QStringList Chat::emojiList() const
{
    return QString::fromUtf8(
               "❤️ 😂 👍 😮 😢 🙏 😀 😃 😄 😁 😆 😅 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😋 😛 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 "
               "😣 😖 😫 😩 🥺 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😦 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 "
               "🤒 🤕 🤑 🤠 😈 👻 💀 🤖 🎃 😺 😸 😹 😻 😼 🙀 😿 😾 👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👎 ✊ 👊 🤛 🤜 👏 🙌 "
               "🤲 🤝 💪 🧠 👀 💋 💘 💖 💗 💓 💞 💕 💔 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟 ⚡ ☀️ 🌙 🌈 ☁️ ❄️ "
               "🌊 🌲 🌳 🌿 🍀 🍁 🍂 🌸 🌹 🌻 🐺 🦅 🐦 🐻 🐗 🐴 🐍 🐉 🐟 🐈 🐕 🍎 🍕 🍔 🌮 🍗 🍖 🧀 🍞 🍺 🍻 🍷 ☕ 🍵 🎂 🍰 🍪 🎉 🎊 🎁 🎈 🏆 🥇 "
               "⚔️ 🛡️ 🏹 🪓 🔨 ⚒️ 🔮 🕯️ 🎮 🕹️ 🎲 🎧 🎵 🎶 🎸 🥁 📷 📞 💻 📱 🔋 💡 📌 📎 ✏️ 📝 📚 🗺️ 🚗 🚀 ✈️ 🏠 ⛺ 🏔️ ⛰️ 🌋 🏕️ ✅ ❌ ❓ ❗ "
               "⚠️ 🚫 💤 🆗 🆒 🆕 ➕ ➖")
        .split(QLatin1Char(' '), Qt::SkipEmptyParts);
}

int Chat::receiveM1(const QString &room, const QString &fromKey, const QString &name, const QString &mailbox, const QString &sentId, const QJsonObject &p)
{
    Q_UNUSED(name)
    if (!isContact(fromKey) && !m_met.contains(fromKey))
        return 403;
    if (m_recentM1.size() > 500)
        m_recentM1.clear();
    QList<qint64> &q = m_recentM1[room + QLatin1Char('|') + fromKey];
    while (!q.isEmpty() && now() - q.first() > 10000)
        q.removeFirst();
    if (q.size() >= (room == QLatin1String("mx") ? 20 : 30))
        return 429;
    q << now();
    // Cap m2: a vote on a poll; everything else is m1.
    if (room == QLatin1String("mx") && p.value(QStringLiteral("t")).toString() == QLatin1String("vote"))
        return takeVote(fromKey, mailbox, sentId, p);
    if (!RichWire::ok(room, p))
        return 400;
    commitPeer();
    if (!sentId.isEmpty())
        m_seen.add(fromKey, sentId, now());
    const qint64 ts = RichWire::ts(p);
    const QString t = p.value(QStringLiteral("t")).toString();
    if (room == QLatin1String("live")) {
        if (!RichWire::fresh(ts, now()))
            return 200;
        const bool on = p.value(QStringLiteral("on")).toBool();
        QHash<QString, qint64> &map = t == QLatin1String("typing") ? m_typing : m_active;
        if (t == QLatin1String("active") && !activeStatus())
            return 200;   // with mine off, nobody's shows
        if (on)
            map.insert(fromKey, now() + (t == QLatin1String("typing") ? RichWire::kTypingMs : RichWire::kActiveMs));
        else
            map.remove(fromKey);
        // Cap p1: the status they chose rides on the same message.
        if (t == QLatin1String("active") && m_profile)
            m_profile->tookLive(fromKey, on, p);
        Q_EMIT liveChanged();
        return 200;
    }
    // "mx": a reaction or an edit of a message in our chat.
    const QString msgId = p.value(QStringLiteral("msg")).toString();
    QString reactedTo;
    update(fromKey, msgId, [&](QVariantMap &m) {
        if (!str(m, "unsent").isEmpty() || m.value(QStringLiteral("system")).toBool())
            return;
        if (t == QLatin1String("react")) {
            QVariantMap rx = m.value(QStringLiteral("rx")).toMap();
            const QString e = p.value(QStringLiteral("e")).toString();
            if (RichWire::react(rx, fromKey, e, ts)) {
                m.insert(QStringLiteral("rx"), rx);
                if (m.value(QStringLiteral("mine")).toBool() && !e.isEmpty())
                    reactedTo = e + QStringLiteral(" to “") + (str(m, "photo").isEmpty() ? str(m, "text").left(60) : QStringLiteral("your photo")) + QStringLiteral("”");
            }
        } else if (!m.value(QStringLiteral("mine")).toBool() && str(m, "photo").isEmpty()
                   && RichWire::editWins(fromKey, fromKey, ts, m.value(QStringLiteral("edited")).toLongLong())) {
            m.insert(QStringLiteral("text"), p.value(QStringLiteral("text")).toString().left(kMax));
            m.insert(QStringLiteral("edited"), ts);
        }
    });
    if (!reactedTo.isEmpty())
        Q_EMIT arrived(nameOf(fromKey), QStringLiteral("Reacted ") + reactedTo, fromKey);
    // Confirmed by its envelope id, so the sender stops resending it.
    receipt(fromKey, {sentId}, QStringLiteral("delivered"), mailbox);
    return 200;
}

QString Chat::react(const QString &key, const QString &id, const QString &emoji)
{
    if (!RichWire::emojiOk(emoji))
        return QStringLiteral("That reaction can't be sent.");
    const qint64 ts = now();
    bool done = false;
    update(key, id, [&](QVariantMap &m) {
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
    if (!hasM1(key)) {
        probeCaps(key);
        return nameOf(key) + QStringLiteral(" needs a newer Huginn or Muninn to see reactions.");
    }
    queueMx(key, RichWire::reactMsg(id, emoji, ts));
    return QString();
}

QString Chat::editMessage(const QString &key, const QString &id, const QString &text)
{
    const QString t = GroupWire::clip(text.trimmed(), kMax);
    if (t.isEmpty())
        return QStringLiteral("A message can't be empty. Unsend it instead.");
    if (!hasM1(key)) {
        probeCaps(key);
        return nameOf(key) + QStringLiteral(" needs a newer Huginn or Muninn to see edits.");
    }
    qint64 at = 0;
    update(key, id, [&](QVariantMap &m) {
        if (!m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty() || !str(m, "photo").isEmpty() || str(m, "text") == t)
            return;
        at = qMax(now(), m.value(QStringLiteral("edited")).toLongLong() + 1);
        m.insert(QStringLiteral("text"), t);
        m.insert(QStringLiteral("edited"), at);
    });
    if (at == 0)
        return QString();
    queueMx(key, RichWire::editMsg(id, t, at));
    return QString();
}

// Sealed once and kept until their receipt names its envelope id (a day at most).
void Chat::queueMx(const QString &key, const QByteArray &plain)
{
    const QString env = QUuid::createUuid().toString(QUuid::WithoutBraces);
    keepSealed(env, key, {compact(envelope(QStringLiteral("mx"), key, plain, env))});
    m_mxOut.insert(env, {{QStringLiteral("key"), key}, {QStringLiteral("time"), now()}});
    m_tries.insert(env, Tries{0, 0});
    saveMx();
    attemptMx(env);
}

void Chat::attemptMx(const QString &envId)
{
    const QString key = str(m_mxOut.value(envId), "key");
    const ChatWire::Outbox::Entry *e = m_outbox.find(envId);
    if (key.isEmpty() || !e || e->bodies.empty()) {
        m_mxOut.remove(envId);
        m_tries.remove(envId);
        saveMx();
        return;
    }
    Tries &t = m_tries[envId];
    t.count++;
    t.next = now() + retryDelay(t.count);
    const QByteArray body = e->bodies.front();
    sendBle(key, body);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, body);
    sendInternet(key, {body}, mailboxOf(key));
}

void Chat::saveMx()
{
    QJsonObject o;
    for (auto it = m_mxOut.cbegin(); it != m_mxOut.cend(); ++it)
        o.insert(it.key(), QJsonObject::fromVariantMap(it.value()));
    m_vault->write(QStringLiteral("mxout.json"), compact(o));
}

// Typing and active status: sealed with v1 (never the ratchet), once on every route, never retried.
void Chat::sendLive(const QString &key, const QByteArray &plain)
{
    const QByteArray body = compact(envelope(QStringLiteral("live"), key, plain, QUuid::createUuid().toString(QUuid::WithoutBraces)));
    sendBle(key, body);
    const QString base = baseTo(key);
    if (!base.isEmpty())
        post(base, body);
    sendInternet(key, {body}, mailboxOf(key));
}

void Chat::typingTo(const QString &key, bool on)
{
    if (!m_ready || !hasM1(key) || isBlocked(key))
        return;
    const qint64 t = now(), last = m_typingSent.value(key, 0);
    if (on && t - last < RichWire::kTypingEvery)
        return;
    if (!on && (last == 0 || t - last > RichWire::kTypingMs)) {
        m_typingSent.remove(key);
        return;
    }
    if (on)
        m_typingSent.insert(key, t);
    else
        m_typingSent.remove(key);
    sendLive(key, RichWire::liveMsg(QStringLiteral("typing"), on, t));
}

// To the 20 contacts with m1 chatted with most lately (each one is a relay event).
void Chat::sendActive(bool on)
{
    if (!m_ready)
        return;
    const qint64 t = now();
    if (on)
        m_activeSent = t;
    QList<QPair<qint64, QString>> who;
    for (auto it = m_contacts.cbegin(); it != m_contacts.cend(); ++it)
        if (hasM1(it.key()) && !isBlocked(it.key()) && showsActive(it.key())) {
            const QVariantList l = m_private.value(it.key());
            who << qMakePair(l.isEmpty() ? qint64(0) : l.last().toMap().value(QStringLiteral("time")).toLongLong(), it.key());
        }
    std::sort(who.begin(), who.end(), [](const QPair<qint64, QString> &a, const QPair<qint64, QString> &b) { return a.first > b.first; });
    // Cap p1: my status and custom status ride on it; invisible goes as not active, as an app without p1 reads it.
    const QByteArray plain = m_profile ? m_profile->activeMsg(on, t) : RichWire::liveMsg(QStringLiteral("active"), on, t);
    for (int i = 0; i < qMin(20, int(who.size())); ++i)
        sendLive(who.at(i).second, plain);
}

void Chat::announceStatus()
{
    if (m_ready && activeStatus())
        sendActive(m_front);
}

int Chat::receiveProfile(const QString &fromKey, const QString &sentId, const QJsonObject &p)
{
    if (!isContact(fromKey) && !isGroupPeer(fromKey))
        return 403;
    if (m_recentM1.size() > 500)
        m_recentM1.clear();
    QList<qint64> &q = m_recentM1[QStringLiteral("profile|") + fromKey];
    while (!q.isEmpty() && now() - q.first() > Profile::kRateMs)
        q.removeFirst();
    if (!q.isEmpty())
        return 429;
    q << now();
    if (!m_profile || !m_profile->receive(fromKey, p))
        return 400;
    commitPeer();
    if (!sentId.isEmpty())
        m_seen.add(fromKey, sentId, now());
    return 200;
}

void Chat::setActiveStatus(bool on)
{
    if (on == activeStatus())
        return;
    m_settings.setValue(QStringLiteral("chat/activeStatus"), on);
    if (!on) {
        m_active.clear();
        sendActive(false);
        Q_EMIT liveChanged();
    } else if (m_front) {
        sendActive(true);
    }
    Q_EMIT changed();
}

void Chat::liveTick()
{
    const qint64 t = now();
    bool gone = false;
    for (QHash<QString, qint64> *map : {&m_typing, &m_active})
        for (auto it = map->begin(); it != map->end();)
            if (it.value() < t) {
                it = map->erase(it);
                gone = true;
            } else {
                ++it;
            }
    if (gone)
        Q_EMIT liveChanged();
    // While invisible (cap p1) there is nothing to say again: "not active" went out when it was chosen.
    if (m_front && activeStatus() && !(m_profile && m_profile->invisible()) && t - m_activeSent > RichWire::kActiveEvery)
        sendActive(true);
}

QVariantMap Chat::typingMap() const
{
    QVariantMap out;
    for (auto it = m_typing.cbegin(); it != m_typing.cend(); ++it)
        out.insert(it.key(), it.value());
    return out;
}

QVariantMap Chat::activeMap() const
{
    QVariantMap out;
    for (auto it = m_active.cbegin(); it != m_active.cend(); ++it)
        out.insert(it.key(), it.value());
    return out;
}

void Chat::setPinned(const QString &where, const QString &id, bool on)
{
    QStringList &l = m_pins[where];
    l.removeAll(id);
    if (on)
        l.prepend(id);
    while (l.size() > 50)
        l.removeLast();
    if (l.isEmpty())
        m_pins.remove(where);
    savePins();
    Q_EMIT changed();
}

void Chat::savePins()
{
    QJsonObject o;
    for (auto it = m_pins.cbegin(); it != m_pins.cend(); ++it)
        o.insert(it.key(), QJsonArray::fromStringList(it.value()));
    m_vault->write(QStringLiteral("pins.json"), compact(o));
}

bool Chat::clipboardHasImage() const
{
    const QClipboard *c = QGuiApplication::clipboard();
    return c && !c->image().isNull();
}

// A pasted picture goes through the same shrinking and scrubbing as a picked one.
bool Chat::pasteImage(const QString &where)
{
    const QClipboard *c = QGuiApplication::clipboard();
    const QImage img = c ? c->image() : QImage();
    if (img.isNull())
        return false;
    // Linux: the memory-backed runtime folder. Windows: Huginn's private scratch folder (see Scratch).
    const QString path = Scratch::path(QStringLiteral("huginn-paste-%1.png").arg(QUuid::createUuid().toString(QUuid::WithoutBraces)));
    if (!img.save(path, "PNG")) {
        Scratch::shred(path);
        return false;
    }
    const bool ok = sendPhoto(where, path, false);   // a contact, or "g:<group>:<channel>"
    Scratch::shred(path);
    return ok;
}

bool Chat::isPicture(const QString &path) const
{
    static const QStringList kinds{QStringLiteral("jpg"), QStringLiteral("jpeg"), QStringLiteral("png"), QStringLiteral("webp"),
                                   QStringLiteral("bmp"), QStringLiteral("gif"), QStringLiteral("heic")};
    return kinds.contains(QFileInfo(path).suffix().toLower());
}

// What the screens need for replies, reactions and edits: the quoted message's name and text, the
// reaction counts (mine marked) and whether it was edited.
Chat::M1Index Chat::m1Index(const QVariantList &all)
{
    M1Index idx;
    idx.at.reserve(all.size());
    for (int i = 0; i < all.size(); ++i) {
        const QVariantMap q = all.at(i).toMap();
        const QString id = str(q, "id");
        if (!id.isEmpty() && !idx.at.contains(id))
            idx.at.insert(id, i);
        const QString re = str(q, "re");
        if (!re.isEmpty())
            ++idx.replies[re];
    }
    return idx;
}

QVariantMap Chat::withM1(const QVariantMap &in, const QVariantList &all, const M1Index &idx, const QString &theirName) const
{
    QVariantMap m = in;
    const QString re = str(m, "re");
    if (!re.isEmpty()) {
        QString who = QStringLiteral("Message"), what = QStringLiteral("Not here any more");
        const auto hit = idx.at.constFind(re);
        if (hit != idx.at.cend()) {
            const QVariantMap q = all.at(*hit).toMap();
            who = q.value(QStringLiteral("mine")).toBool() ? QStringLiteral("You") : theirName.isEmpty() ? str(q, "name") : theirName;
            what = !str(q, "unsent").isEmpty() ? QStringLiteral("Message deleted")
                 : q.value(QStringLiteral("voice")).toInt() > 0 ? QStringLiteral("Voice message")
                 : q.value(QStringLiteral("video")).toInt() > 0 ? QStringLiteral("Video message")
                 : !str(q, "photo").isEmpty() ? QStringLiteral("Photo")
                 : q.contains(QStringLiteral("poll")) ? QStringLiteral("Poll: ") + q.value(QStringLiteral("poll")).toMap().value(QStringLiteral("q")).toString()
                 : str(q, "text").left(120);
        }
        m.insert(QStringLiteral("reName"), who);
        m.insert(QStringLiteral("reText"), what);
    }
    m.insert(QStringLiteral("replies"), idx.replies.value(str(m, "id"), 0));
    m.insert(QStringLiteral("rxList"), RichWire::counts(m.value(QStringLiteral("rx")).toMap(), myKey()));
    m.insert(QStringLiteral("myReaction"), RichWire::shown(m.value(QStringLiteral("rx")).toMap()).value(myKey()).toString());
    m.insert(QStringLiteral("isEdited"), m.value(QStringLiteral("edited")).toLongLong() > 0 && str(m, "unsent").isEmpty());
    return m;
}
