#include "labs.h"
#include "chat.h"
#include "chatcrypto.h"
#include "chatwire.h"
#include "groupwire.h"
#include "photos.h"
#include "platform/crashlog.h"
#include "settings.h"
#include "vault.h"

#include <QBuffer>
#include <QCoreApplication>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QQuickWindow>
#include <QSettings>
#include <QSysInfo>
#include <QUuid>

const char Labs::kName[] = "Huginn Muninn Labs";
const char Labs::kKey[] = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5ihedM8ypmMLI817zKk9CbxL0Tl3Q67Orwj8/UUpgOH9PlIIlw7FQDuIfKBa6BuK2fjw6Iu2RKxNvLdEFfVK+w==";
const char Labs::kMailbox[] = "5ee40952b01671ffec761fc6ff65529fac098b03aed4b7a0b5d69d86e2e93529";

namespace
{
const qint64 kDay = 86400000;
const qint64 kMerge = 15 * 60000;      // a report's text and its screenshot meet within this long
const int kKeepReports = 300;
const qint64 kKeepDays = 30;
const int kShotSide = 1600;
const int kShotBytes = 600 * 1024;     // about 38 pieces over the relays
const QString kFile = QStringLiteral("labs/reports.json");

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
bool shortText(const QJsonValue &v, int max) { return v.isString() && v.toString().size() <= max; }
// A report carries only its text, its screenshot, lb (app, version, system, model) and the way back (chat key and
// mailbox): no name, no caps list.
QJsonObject bare(QJsonObject o)
{
    o.remove(QStringLiteral("caps"));
    o.remove(QStringLiteral("cap"));
    return o;
}
}

Labs::Labs(Chat *chat, Vault *vault, Settings *settings, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
    , m_vault(vault)
    , m_settings(settings)
{
    // Chat becomes ready (or is erased) after this is made: say so when that changes whether this is the Labs inbox.
    connect(m_chat, &Chat::changed, this, [this] {
        const bool is = inbox();
        if (is != m_wasInbox) {
            m_wasInbox = is;
            Q_EMIT changed();
        }
    });
    connect(m_vault, &Vault::changed, this, [this] {
        if (m_vault->locked() || !m_vault->exists()) {
            m_loaded = false;
            m_reports.clear();
            m_incoming.clear();
        }
    });
}

bool Labs::inbox() const
{
    return m_chat->ready() && m_chat->chatKey() == QLatin1String(kKey);
}

QString Labs::label() const { return QCoreApplication::applicationVersion(); }
QString Labs::os() const { return QSysInfo::prettyProductName(); }
QString Labs::model() const { return QSysInfo::productType() + QLatin1Char(' ') + QSysInfo::currentCpuArchitecture(); }

QJsonObject Labs::lbField() const
{
    return {{QStringLiteral("app"), QStringLiteral("huginn")}, {QStringLiteral("v"), label()}, {QStringLiteral("os"), os().left(80)},
            {QStringLiteral("model"), model().left(80)}};
}

bool Labs::idOk(const QJsonValue &v)
{
    const QString id = v.toString();
    if (!v.isString() || id.isEmpty() || id.size() > 64)
        return false;
    for (const QChar c : id)
        if (!(c.isLetterOrNumber() && c.unicode() < 128) && c != QLatin1Char('-'))
            return false;
    return true;
}

// shared/docs/protocol.md section 24: app, v, os and model (each at most 80 characters), and s (a screenshot's id)
// only on the report itself.
bool Labs::lbOk(const QJsonObject &lb, bool piece)
{
    const QString app = lb.value(QStringLiteral("app")).toString();
    if (app != QLatin1String("huginn") && app != QLatin1String("muninn"))
        return false;
    for (const char *k : {"v", "os", "model"})
        if (!shortText(lb.value(QLatin1String(k)), 80))
            return false;
    if (lb.contains(QStringLiteral("s")) && (piece || !idOk(lb.value(QStringLiteral("s")))))
        return false;
    return true;
}

// ------------------------------------------------------------------ sending (any Huginn in Labs mode)

void Labs::capture(QQuickWindow *window)
{
    m_shot = window ? window->grabWindow() : QImage();
    m_shotUrl.clear();
    if (!m_shot.isNull()) {
        // The preview: a small JPEG in memory, never written to disk.
        QByteArray jpeg;
        QBuffer buf(&jpeg);
        buf.open(QIODevice::WriteOnly);
        m_shot.scaled(960, 960, Qt::KeepAspectRatio, Qt::SmoothTransformation).convertToFormat(QImage::Format_RGB32).save(&buf, "JPEG", 80);
        m_shotUrl = QStringLiteral("data:image/jpeg;base64,") + QString::fromLatin1(jpeg.toBase64());
    }
    Q_EMIT shotChanged();
}

void Labs::clearShot()
{
    m_shot = QImage();
    m_shotUrl.clear();
    Q_EMIT shotChanged();
}

bool Labs::hasCrashLog() const { return CrashLog::hasLines(); }

QString Labs::send(const QString &text, bool withShot, bool withLog, const QString &perf)
{
    QString t = text.trimmed();
    if (t.isEmpty())
        return QStringLiteral("Say what went wrong first.");
    if (GroupWire::length(t) > kMaxText)
        return QStringLiteral("That's too long. 2000 characters at most.");
    // The performance check's report (already scrubbed), cut to the room the 2000 characters leave.
    if (!perf.trimmed().isEmpty()) {
        const QString head = QStringLiteral("\n\nPerformance check:\n");
        const int room = kMaxText - int(GroupWire::length(t)) - int(head.size());
        QString add = room > 80 ? perf.trimmed().left(room) : QString();
        while (!add.isEmpty() && GroupWire::length(t + head + add) > kMaxText)
            add.chop(qMin<qsizetype>(add.size(), 40));
        if (add.size() > 80)
            t += head + add;
    }
    // The crash log's last lines, in whatever room the 2000 characters leave.
    if (withLog) {
        const QString head = QStringLiteral("\n\nCrash log, last lines:\n");
        const int room = kMaxText - int(GroupWire::length(t)) - int(head.size());
        const QString tail = room > 80 ? CrashLog::tail(room) : QString();
        if (!tail.isEmpty() && GroupWire::length(t + head + tail) <= kMaxText)
            t += head + tail;
    }
    if (!m_vault->exists())
        return QStringLiteral("Set a lock in Chats first.");
    if (!m_chat->ready() || m_vault->screenLocked())
        return QStringLiteral("Unlock Huginn first.");
    if (m_chat->moving())
        return QStringLiteral("Not while moving to another device.");
    if (now() - m_lastSent < 30000)
        return QStringLiteral("Wait a moment, then send again.");
    const QString key = QString::fromLatin1(kKey);
    const QByteArray sk = m_chat->keyWith(key);
    if (sk.isEmpty())
        return QStringLiteral("The report couldn't be sealed.");
    // Only while a relay is reachable: the report goes once and isn't retried later.
    if (m_chat->internet() <= 0)
        return QStringLiteral("No internet right now. Try again later.");
    const Ratchet::NewKey newKey = [] { return ChatCrypto::newKeyPair(); };
    QJsonObject lb = lbField();
    auto fields = [this, &lb](const QString &room, const QString &id) {
        return QJsonObject{{QStringLiteral("room"), room}, {QStringLiteral("fromKey"), m_chat->chatKey()},
                           {QStringLiteral("mailbox"), m_chat->mailbox()}, {QStringLiteral("id"), id}, {QStringLiteral("lb"), lb}};
    };
    QList<QByteArray> bodies;
    QString shotId;
    // The screenshot first, as a photo goes to a contact: a scrubbed JPEG in pieces, each sealed on its own.
    if (withShot && !m_shot.isNull()) {
        const QByteArray jpeg = Photos::prepareImage(m_shot, kShotSide, kShotBytes);
        const QList<QByteArray> parts = Photos::split(jpeg);
        if (jpeg.isEmpty() || parts.isEmpty() || parts.size() > Photos::kInternetPieces)
            return QStringLiteral("The screenshot couldn't be added.");
        shotId = QUuid::createUuid().toString(QUuid::WithoutBraces);
        for (int i = 0; i < parts.size(); ++i) {
            QJsonObject f = fields(QStringLiteral("photo"), shotId);
            f.insert(QStringLiteral("i"), i);
            f.insert(QStringLiteral("n"), int(parts.size()));
            bodies << compact(bare(ChatWire::seal(f, sk, key, parts.at(i), nullptr, newKey)));
        }
    }
    // The report itself: an ordinary private message (v1, unbound, as to a newly added contact), naming its screenshot.
    if (!shotId.isEmpty())
        lb.insert(QStringLiteral("s"), shotId);
    bodies << compact(bare(ChatWire::seal(fields(QStringLiteral("private"), QUuid::createUuid().toString(QUuid::WithoutBraces)), sk, key, t.toUtf8(), nullptr, newKey)));
    if (!m_chat->sendInternet(key, bodies, QString::fromLatin1(kMailbox), nullptr, 7))
        return QStringLiteral("The report couldn't be sent. Try again.");
    m_lastSent = now();
    Settings::store().setValue(QStringLiteral("labs/reportedAt"), m_lastSent);
    clearShot();
    return QString();
}

bool Labs::replyAllowed(const QString &fromKey) const
{
    if (fromKey != QLatin1String(kKey))
        return false;
    const qint64 at = Settings::store().value(QStringLiteral("labs/reportedAt"), 0).toLongLong();
    return at > 0 && now() - at < 30 * kDay;
}

// ------------------------------------------------------------------ going back to stable

bool Labs::beforeGoingBack()
{
    // The stable release reads the vault as this one does: only Labs and the beta channel are turned off.
    m_settings->setUpdateChannel(QStringLiteral("stable"));
    m_settings->setLabs(false);
    return true;
}

// ------------------------------------------------------------------ the Labs inbox (the Huginn with the Labs key)

void Labs::load() const
{
    if (m_loaded || m_vault->locked())
        return;
    m_loaded = true;
    m_reports.clear();
    for (const QJsonValue &v : QJsonDocument::fromJson(m_vault->read(kFile)).array())
        if (v.isObject())
            m_reports << v.toObject();
    const int before = int(m_reports.size());
    prune();
    if (m_reports.size() != before)
        save();
}

void Labs::save() const
{
    QJsonArray a;
    for (const QJsonObject &r : m_reports)
        a << r;
    m_vault->write(kFile, QJsonDocument(a).toJson(QJsonDocument::Compact));
}

QVariantList Labs::reports() const
{
    QVariantList out;
    if (!inbox())
        return out;
    load();
    for (auto it = m_reports.crbegin(); it != m_reports.crend(); ++it) {
        const QJsonObject &r = *it;
        out << QVariantMap{{QStringLiteral("id"), jstr(r, "id")}, {QStringLiteral("key"), jstr(r, "from")},
                           {QStringLiteral("name"), m_chat->isContact(jstr(r, "from")) ? m_chat->nameOf(jstr(r, "from")) : jstr(r, "name")},
                           {QStringLiteral("app"), jstr(r, "app")}, {QStringLiteral("label"), jstr(r, "label")}, {QStringLiteral("os"), jstr(r, "os")},
                           {QStringLiteral("model"), jstr(r, "model")}, {QStringLiteral("text"), jstr(r, "text")},
                           {QStringLiteral("hasShot"), !jstr(r, "shot").isEmpty()}, {QStringLiteral("time"), r.value(QStringLiteral("time")).toDouble()}};
    }
    return out;
}

int Labs::sentToday(const QString &fromKey) const
{
    int n = 0;
    for (const QJsonObject &r : std::as_const(m_reports))
        if (jstr(r, "from") == fromKey && now() - qint64(r.value(QStringLiteral("time")).toDouble()) < kDay)
            ++n;
    return n;
}

// The report a report's text or screenshot belongs with, or -1: by the screenshot's id ("s") first, otherwise the
// newest one from the same sender in the last 15 minutes still missing that half (and not waiting for another id).
int Labs::matchFor(const QString &fromKey, bool forText, const QString &shotId) const
{
    for (int i = int(m_reports.size()) - 1; i >= 0; --i) {
        const QJsonObject &r = m_reports.at(i);
        if (jstr(r, "from") != fromKey || shotId.isEmpty())
            continue;
        if (forText ? (!r.value(QStringLiteral("hasText")).toBool() && jstr(r, "shotId") == shotId)
                    : (r.value(QStringLiteral("hasText")).toBool() && jstr(r, "shot").isEmpty() && jstr(r, "wants") == shotId))
            return i;
    }
    for (int i = int(m_reports.size()) - 1; i >= 0; --i) {
        const QJsonObject &r = m_reports.at(i);
        if (now() - qint64(r.value(QStringLiteral("time")).toDouble()) > kMerge)
            break;
        if (jstr(r, "from") != fromKey)
            continue;
        if (forText ? (!r.value(QStringLiteral("hasText")).toBool() && shotId.isEmpty())
                    : (r.value(QStringLiteral("hasText")).toBool() && jstr(r, "shot").isEmpty() && jstr(r, "wants").isEmpty()))
            return i;
    }
    return -1;
}

bool Labs::seen(const QString &fromKey, const QString &id) const
{
    for (const QJsonObject &r : std::as_const(m_reports))
        if (jstr(r, "from") == fromKey && r.value(QStringLiteral("ids")).toArray().contains(QJsonValue(id)))
            return true;
    return false;
}

int Labs::receive(const QJsonObject &o, const QString &fromKey, const QString &name)
{
    if (!o.value(QStringLiteral("lb")).isObject())
        return 0;
    // Only the Labs contact's app takes reports; every other app drops them quietly.
    if (!inbox())
        return 200;
    if (m_vault->locked())
        return 503;
    load();
    const QString room = jstr(o, "room");
    if (room == QLatin1String("photo"))
        return takePiece(o, fromKey, name);
    if (room != QLatin1String("private"))
        return 400;
    const QJsonObject lb = o.value(QStringLiteral("lb")).toObject();
    const QString sentId = jstr(o, "id");
    if (!lbOk(lb, false) || sentId.isEmpty() || sentId.size() > 80 || ChatWire::isRatchet(o))
        return 400;
    if (seen(fromKey, sentId))
        return 200;
    const QString shotId = jstr(lb, "s");
    if (matchFor(fromKey, true, shotId) < 0 && sentToday(fromKey) >= kPerDay)
        return 429;
    bool ok = false;
    const QByteArray plain = m_chat->open(o, fromKey, &ok);   // v1 with the sender's chat key
    if (!ok)
        return 403;
    const QString text = QString::fromUtf8(plain).trimmed();
    if (text.isEmpty() || GroupWire::length(text) > kMaxText)
        return 400;
    QString mailbox = jstr(o, "mailbox");
    if (mailbox.size() != 64)
        mailbox.clear();
    store(fromKey, name, mailbox, lb, sentId, &text, nullptr);
    return 200;
}

int Labs::takePiece(const QJsonObject &o, const QString &fromKey, const QString &name)
{
    const QJsonObject lb = o.value(QStringLiteral("lb")).toObject();
    const QString id = jstr(o, "id");
    const int n = o.value(QStringLiteral("n")).toInt(), i = o.value(QStringLiteral("i")).toInt(-1);
    if (!lbOk(lb, true) || !idOk(o.value(QStringLiteral("id"))) || n < 1 || n > Photos::kInternetPieces || i < 0 || i >= n
        || o.contains(QStringLiteral("kind")) || ChatWire::isRatchet(o))
        return 400;
    if (seen(fromKey, id))
        return 200;
    const QString slot = fromKey + QLatin1Char('|') + id;
    // Half a screenshot that never finished goes after 15 minutes.
    for (auto it = m_incoming.begin(); it != m_incoming.end();)
        it = now() - it.value().started > kMerge ? m_incoming.erase(it) : std::next(it);
    const auto have = m_incoming.constFind(slot);
    if (have != m_incoming.cend() && i < have->parts.size() && !have->parts.at(i).isEmpty())
        return 200;
    if (have == m_incoming.cend()) {
        if (m_incoming.size() >= 10)
            return 429;
        if (matchFor(fromKey, false, id) < 0 && sentToday(fromKey) >= kPerDay)
            return 429;
    }
    bool ok = false;
    const QByteArray part = m_chat->open(o, fromKey, &ok);
    if (!ok)
        return 403;
    Incoming &inc = m_incoming[slot];
    if (inc.parts.isEmpty()) {
        inc.from = fromKey;
        inc.id = id;
        inc.lb = lb;
        inc.started = now();
        for (int k = 0; k < n; ++k)
            inc.parts << QByteArray();
    }
    if (inc.parts.size() != n)
        return 400;
    inc.name = name;
    inc.mailbox = jstr(o, "mailbox").size() == 64 ? jstr(o, "mailbox") : QString();
    if (inc.parts.at(i).isEmpty()) {
        inc.parts[i] = part;
        inc.have++;
    }
    if (inc.have < n)
        return 200;
    const Incoming done = m_incoming.take(slot);
    const QByteArray jpeg = Photos::join(done.parts);   // a JPEG, metadata scrubbed; empty otherwise
    if (jpeg.isEmpty())
        return 400;
    store(fromKey, done.name, done.mailbox, done.lb, id, nullptr, &jpeg);
    return 200;
}

// A report's text or its screenshot: joined to the other half when that came first, else a new report.
void Labs::store(const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &lb, const QString &sentId,
                 const QString *text, const QByteArray *jpeg)
{
    const int at = text ? matchFor(fromKey, true, jstr(lb, "s")) : matchFor(fromKey, false, sentId);
    QJsonObject r = at >= 0 ? m_reports.at(at)
                            : QJsonObject{{QStringLiteral("id"), QUuid::createUuid().toString(QUuid::WithoutBraces)}, {QStringLiteral("from"), fromKey},
                                          {QStringLiteral("time"), double(now())}};
    r.insert(QStringLiteral("name"), name.left(40));
    if (!mailbox.isEmpty())
        r.insert(QStringLiteral("mailbox"), mailbox);
    // The report's own lb wins over its screenshot's.
    if (text || !r.value(QStringLiteral("hasText")).toBool()) {
        r.insert(QStringLiteral("app"), jstr(lb, "app"));
        r.insert(QStringLiteral("label"), jstr(lb, "v"));
        r.insert(QStringLiteral("os"), jstr(lb, "os"));
        r.insert(QStringLiteral("model"), jstr(lb, "model"));
    }
    QJsonArray ids = r.value(QStringLiteral("ids")).toArray();
    ids << sentId;
    r.insert(QStringLiteral("ids"), ids);
    if (text) {
        r.insert(QStringLiteral("text"), *text);
        r.insert(QStringLiteral("hasText"), true);
        if (!jstr(lb, "s").isEmpty() && jstr(r, "shot").isEmpty())
            r.insert(QStringLiteral("wants"), jstr(lb, "s"));
    }
    if (jpeg) {
        const QString file = QStringLiteral("labs/shot-") + jstr(r, "id");
        if (m_vault->write(file, *jpeg)) {
            r.insert(QStringLiteral("shot"), file);
            r.insert(QStringLiteral("shotId"), sentId);
            r.remove(QStringLiteral("wants"));
        }
    }
    if (at >= 0)
        m_reports[at] = r;
    else
        m_reports << r;
    prune();
    save();
    if (at < 0)
        Q_EMIT m_chat->notice(QStringLiteral("New Labs report from %1").arg(name.left(40)));
    Q_EMIT changed();
}

// Reports keep 30 days (and 300 at most), their screenshots with them.
void Labs::prune() const
{
    for (int i = int(m_reports.size()) - 1; i >= 0; --i)
        if (now() - qint64(m_reports.at(i).value(QStringLiteral("time")).toDouble()) > kKeepDays * kDay) {
            const QJsonObject gone = m_reports.takeAt(i);
            if (!jstr(gone, "shot").isEmpty())
                m_vault->remove(jstr(gone, "shot"));
        }
    while (m_reports.size() > kKeepReports) {
        const QJsonObject gone = m_reports.takeFirst();
        if (!jstr(gone, "shot").isEmpty())
            m_vault->remove(jstr(gone, "shot"));
    }
}

QString Labs::shotOf(const QString &id) const
{
    load();
    for (const QJsonObject &r : std::as_const(m_reports))
        if (jstr(r, "id") == id && !jstr(r, "shot").isEmpty()) {
            const QByteArray b = m_vault->read(jstr(r, "shot"));
            return b.isEmpty() ? QString() : QStringLiteral("data:image/jpeg;base64,") + QString::fromLatin1(b.toBase64());
        }
    return QString();
}

QString Labs::reply(const QString &id)
{
    load();
    for (const QJsonObject &r : std::as_const(m_reports)) {
        if (jstr(r, "id") != id)
            continue;
        const QString key = jstr(r, "from");
        if (m_chat->isBlocked(key) || m_chat->isTrashed(key))
            return QString();
        if (!m_chat->isContact(key)) {
            if (jstr(r, "mailbox").isEmpty())
                return QString();   // nowhere to answer
            // An ordinary contact from now on; on their side the first message comes in as a request.
            m_chat->m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), jstr(r, "name")},
                                            {QStringLiteral("mailbox"), jstr(r, "mailbox")}, {QStringLiteral("verified"), false}});
            m_chat->m_met.remove(key);
            m_chat->saveContacts();
            m_chat->saveMet();
            Q_EMIT m_chat->changed();
        }
        return key;
    }
    return QString();
}

void Labs::remove(const QString &id)
{
    load();
    for (int i = 0; i < m_reports.size(); ++i)
        if (jstr(m_reports.at(i), "id") == id) {
            const QJsonObject r = m_reports.takeAt(i);
            if (!jstr(r, "shot").isEmpty())
                m_vault->remove(jstr(r, "shot"));
            save();
            Q_EMIT changed();
            return;
        }
}
