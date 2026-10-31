#include "vault.h"
#include "platform/powerwatch.h"
#include "platform/singleinstance.h"
#include "gamepad.h"

#include <QCoreApplication>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QIODevice>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QProcess>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QThread>
#include <QVariant>
#include <QLocale>
#include <algorithm>
#include <cstdio>
#include <memory>

#include <QElapsedTimer>

#include <openssl/core_names.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/rand.h>

namespace
{
const QByteArray kMagic = QByteArrayLiteral("MUNV1");
const int kRounds = 310000;
// New locks: at least this many pattern dots or password characters. Shorter ones already set keep working.
const int kMinSecret = 6;
// Argon2id: 64 MiB, 3 passes, 4 lanes (a moment on a handheld; very slow to guess on a graphics card).
const quint32 kArgonMem = 65536, kArgonIter = 3, kArgonLanes = 4;
// scrypt, when this OpenSSL has no Argon2id: N = 2^17, r = 8, p = 1 (128 MiB).
const quint64 kScryptN = quint64(1) << 17;
const quint64 kScryptR = 8, kScryptP = 1;

// Argon2id from OpenSSL 3.2 or later; empty when this OpenSSL doesn't have it.
QByteArray argon2id(const QByteArray &pw, const QByteArray &salt, quint32 mem, quint32 iter, quint32 lanes, int len = 32,
                    const QByteArray &secret = QByteArray(), const QByteArray &ad = QByteArray())
{
    EVP_KDF *kdf = EVP_KDF_fetch(nullptr, "ARGON2ID", nullptr);
    if (!kdf)
        return {};
    EVP_KDF_CTX *ctx = EVP_KDF_CTX_new(kdf);
    EVP_KDF_free(kdf);
    if (!ctx)
        return {};
    quint32 threads = 1;
    QByteArray p = pw, sa = salt, se = secret, a = ad;
    OSSL_PARAM params[9];
    int n = 0;
    params[n++] = OSSL_PARAM_construct_octet_string("pass", p.data(), size_t(p.size()));
    params[n++] = OSSL_PARAM_construct_octet_string("salt", sa.data(), size_t(sa.size()));
    params[n++] = OSSL_PARAM_construct_uint32("iter", &iter);
    params[n++] = OSSL_PARAM_construct_uint32("threads", &threads);
    params[n++] = OSSL_PARAM_construct_uint32("lanes", &lanes);
    params[n++] = OSSL_PARAM_construct_uint32("memcost", &mem);
    if (!se.isEmpty())
        params[n++] = OSSL_PARAM_construct_octet_string("secret", se.data(), size_t(se.size()));
    if (!a.isEmpty())
        params[n++] = OSSL_PARAM_construct_octet_string("ad", a.data(), size_t(a.size()));
    params[n] = OSSL_PARAM_construct_end();
    QByteArray out(len, 0);
    const bool ok = EVP_KDF_derive(ctx, reinterpret_cast<unsigned char *>(out.data()), size_t(len), params) == 1;
    EVP_KDF_CTX_free(ctx);
    return ok ? out : QByteArray();
}

QByteArray scrypt(const QByteArray &pw, const QByteArray &salt, quint64 N, quint64 r, quint64 p, int len = 32)
{
    QByteArray out(len, 0);
    if (EVP_PBE_scrypt(pw.constData(), size_t(pw.size()), reinterpret_cast<const unsigned char *>(salt.constData()), size_t(salt.size()),
                       N, r, p, 512ull * 1024 * 1024, reinterpret_cast<unsigned char *>(out.data()), size_t(len)) != 1)
        return {};
    return out;
}

// The key a lock file's secret makes, by its "kdf" (none: PBKDF2). Empty if it can't.
QByteArray lockKey(const QJsonObject &o, const QString &secret)
{
    const QByteArray salt = QByteArray::fromBase64(o.value(QStringLiteral("salt")).toString().toLatin1());
    const QString kdf = o.value(QStringLiteral("kdf")).toString();
    if (kdf == QLatin1String("argon2id"))
        return argon2id(secret.toUtf8(), salt, quint32(o.value(QStringLiteral("m")).toInt()), quint32(o.value(QStringLiteral("t")).toInt()),
                        quint32(o.value(QStringLiteral("p")).toInt()));
    if (kdf == QLatin1String("scrypt"))
        return scrypt(secret.toUtf8(), salt, quint64(o.value(QStringLiteral("N")).toDouble()), quint64(o.value(QStringLiteral("r")).toInt()),
                      quint64(o.value(QStringLiteral("p")).toInt()));
    return Vault::derive(secret, salt, o.value(QStringLiteral("rounds")).toInt(kRounds));
}

// The vault key from a lock file and its secret. Only the lock guards it: nothing outside Huginn is asked.
QByteArray openLock(const QJsonObject &o, const QString &secret, bool *ok)
{
    *ok = false;
    const QByteArray kek = lockKey(o, secret);
    const QByteArray wrapped = QByteArray::fromBase64(o.value(QStringLiteral("wrapped")).toString().toLatin1());
    return kek.isEmpty() || wrapped.isEmpty() ? QByteArray() : Vault::open(kek, wrapped, ok);
}

// "pattern:0,3,6,..." counts dots; a password counts characters.
bool strongSecret(const QString &secret)
{
    if (secret.startsWith(QLatin1String("pattern:")))
        return secret.mid(8).split(QLatin1Char(','), Qt::SkipEmptyParts).size() >= kMinSecret;
    return secret.size() >= kMinSecret;
}

// The wrong-try wait runs on the time since start-up, which a changed clock doesn't move. Its start is known
// as (clock - time since start-up): when that moved by more than two minutes (a restart, or the clock
// changed), the whole wait begins again rather than ending.
qint64 sinceStart()
{
    QElapsedTimer t;
    t.start();
    return t.msecsSinceReference();
}

QString dataDir()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/chat");
    QDir().mkpath(dir);
    return dir;
}

QByteArray random(int n)
{
    QByteArray b(n, 0);
    RAND_bytes(reinterpret_cast<unsigned char *>(b.data()), n);
    return b;
}

QSettings &settings()
{
    static QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    return s;
}

void restartHuginn();
}

Vault::Vault(QObject *parent)
    : QObject(parent)
{
    // Away from Chat: look every few seconds, so the lock is up before anyone comes back.
    m_awayTimer.setInterval(5000);
    connect(&m_awayTimer, &QTimer::timeout, this, [this] { if (!m_awaySince || checkAway()) m_awayTimer.stop(); });
    // Panic lock: both stick clicks held this long.
    m_combo.setSingleShot(true);
    m_combo.setInterval(1000);
    connect(&m_combo, &QTimer::timeout, this, &Vault::panic);
    // The controller: main.cpp makes it before the vault, as a child of the app.
    if (auto *pad = qApp ? qApp->findChild<Gamepad *>(QString(), Qt::FindDirectChildrenOnly) : nullptr) {
        connect(pad, &Gamepad::pressed, this, [this](const QString &b) { button(b, true); });
        connect(pad, &Gamepad::released, this, [this](const QString &b) { button(b, false); });
        connect(pad, &Gamepad::connectedChanged, this, [this] { m_l3 = m_r3 = false; m_combo.stop(); });
    }
    // "Lock when I leave Huginn": another app, or the computer going to sleep.
    // Otherwise the privacy cover goes up at once and the away clock is checked right away.
    if (qGuiApp)
        connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
            if (state == Qt::ApplicationActive) {
                returning();
                return;
            }
            if (lockOnLeave() && hasLock() && !locked() && !m_screenLocked) {
                lockScreen();
                Q_EMIT panicked();
            }
            leaving();
        });
    // Windows can sleep and wake with the window still in front, so Qt's state never changes.
    Platform::watchPower(this, [this] { sleeping(); }, [this] { woke(); });
}

void Vault::leaving()
{
    checkAway();
    if (!m_covered && exists() && !locked()) {
        m_covered = true;
        Q_EMIT changed();
    }
}

void Vault::returning()
{
    checkAway();   // past the relock time: the lock is up before the cover comes off
    if (m_covered) {
        m_covered = false;
        Q_EMIT changed();
    }
}

void Vault::sleeping()
{
    if (m_asleep)
        return;
    m_asleep = true;
    // Covered before Windows takes the picture it shows on wake. The away clock runs while asleep
    // even with Chat on screen; what it was before comes back on wake.
    m_awayBeforeSleep = m_awaySince;
    if (!locked() && !m_awaySince) {
        m_awaySince = QDateTime::currentMSecsSinceEpoch();
        m_awayTimer.start();
    }
    leaving();
}

void Vault::woke()
{
    if (!m_asleep)
        return;
    m_asleep = false;
    checkAway();
    if (!m_screenLocked) {
        m_awaySince = m_awayBeforeSleep;
        if (m_awaySince)
            m_awayTimer.start();
        else
            m_awayTimer.stop();
    }
    m_awayBeforeSleep = 0;
    // Still in front: uncover. In the background: the cover stays until Huginn is back in front.
    if (!qGuiApp || QGuiApplication::applicationState() == Qt::ApplicationActive)
        returning();
}

QString Vault::keyFile() const { return dataDir() + QStringLiteral("/vault.key"); }
QString Vault::path(const QString &name) const { return dataDir() + QLatin1Char('/') + name; }

bool Vault::exists() const { return QFile::exists(keyFile()); }

bool Vault::hasLock() const
{
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    return QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("mode")).toString() != QLatin1String("open");
}

bool Vault::turnOffLock(const QString &secret)
{
    // Only while open on screen too: not from behind the lock screen, and only with the lock given again.
    if (m_restarting || m_screenLocked || m_key.size() != 32 || !exists() || !hasLock() || waitSeconds() > 0)
        return false;
    {
        QFile lock(keyFile());
        if (!lock.open(QIODevice::ReadOnly))
            return false;
        bool ok = false;
        QByteArray given = openLock(QJsonDocument::fromJson(lock.readAll()).object(), secret, &ok);
        const bool same = ok && given == m_key;
        given.fill(0);
        if (!same) {
            wrongTry();
            Q_EMIT changed();
            return false;
        }
        settings().setValue(QStringLiteral("vault/fails"), 0);
    }
    // No secret: the data key itself goes in vault.key. Nothing outside Huginn keeps it.
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("open")}, {QStringLiteral("key"), QString::fromLatin1(m_key.toBase64())}};
    QSaveFile f(keyFile());
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
    if (!f.commit())
        return false;
    m_weak = false;
    m_screenLocked = false;
    Q_EMIT changed();
    return true;
}

void Vault::openWithoutLock()
{
    if (!m_key.isEmpty() || m_restarting)
        return;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    if (o.value(QStringLiteral("mode")).toString() != QLatin1String("open"))
        return;
    const QByteArray key = QByteArray::fromBase64(o.value(QStringLiteral("key")).toString().toLatin1());
    if (key.size() != 32)
        return;
    m_key = key;
    m_screenLocked = false;
    Q_EMIT changed();
    Q_EMIT opened();
}

bool Vault::usesPattern() const
{
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    return QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("kind")).toString() == QLatin1String("pattern");
}

int Vault::waitSeconds() const
{
    const qint64 wait = settings().value(QStringLiteral("vault/wait"), 0).toLongLong();
    if (wait <= 0)
        return 0;
    const qint64 mono = sinceStart(), started = QDateTime::currentMSecsSinceEpoch() - mono;
    const qint64 at = settings().value(QStringLiteral("vault/at"), 0).toLongLong();
    const qint64 was = settings().value(QStringLiteral("vault/started"), 0).toLongLong();
    if (mono < at - wait) {
        // Restarted (the time since start-up went back): the whole wait again, from now.
        settings().setValue(QStringLiteral("vault/at"), mono + wait);
        settings().setValue(QStringLiteral("vault/started"), started);
        return int((wait + 999) / 1000);
    }
    // The clock moved, or the computer slept: the wait runs on time since start-up, so neither changes it.
    if (qAbs(started - was) > 120000)
        settings().setValue(QStringLiteral("vault/started"), started);
    const qint64 left = qBound<qint64>(0, at - mono, wait);
    return left > 0 ? int((left + 999) / 1000) : 0;
}

bool Vault::unlock(const QString &secret)
{
    if (m_restarting || waitSeconds() > 0)
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    f.close();
    bool ok = false;
    const QByteArray key = openLock(o, secret, &ok);
    if (!ok || key.size() != 32) {
        wrongTry();
        Q_EMIT changed();
        return false;
    }
    settings().setValue(QStringLiteral("vault/fails"), 0);
    settings().remove(QStringLiteral("vault/wait"));
    settings().remove(QStringLiteral("vault/at"));
    settings().remove(QStringLiteral("vault/started"));
    settings().remove(QStringLiteral("vault/until"));   // a clock-based wait, not used
    const bool first = m_key.isEmpty();
    m_key = key;
    m_weak = !strongSecret(secret);
    // A PBKDF2-only lock: store it again with Argon2id or scrypt, now that the secret is known.
    const bool redo = o.value(QStringLiteral("kdf")).toString().isEmpty();
    if (redo)
        writeLock(secret, o.value(QStringLiteral("kind")).toString() == QLatin1String("pattern"));
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

void Vault::wrongTry()
{
    // Every 5 wrong tries the wait doubles: 30 s, 1 min, 2 min ... up to an hour (kept across restarts).
    const int fails = settings().value(QStringLiteral("vault/fails"), 0).toInt() + 1;
    settings().setValue(QStringLiteral("vault/fails"), fails);
    if (fails % 5 == 0) {
        const qint64 wait = qMin<qint64>(3600000, qint64(30000) << qMin(fails / 5 - 1, 7)), mono = sinceStart();
        settings().setValue(QStringLiteral("vault/wait"), wait);
        settings().setValue(QStringLiteral("vault/at"), mono + wait);
        settings().setValue(QStringLiteral("vault/started"), QDateTime::currentMSecsSinceEpoch() - mono);
    }
}

bool Vault::setSecret(const QString &secret, bool pattern)
{
    if (!strongSecret(secret))
        return false;
    if (exists() && m_key.isEmpty())
        return false;   // changing needs the old lock opened first
    if (m_restarting)
        return false;
    const bool first = m_key.isEmpty();
    // The lock was off: the data key sat unprotected in vault.key, so a copy of it may be out there. A new one, and
    // every file sealed again with it, before the new lock guards it.
    const bool wasOff = !first && exists() && !hasLock();
    if (wasOff && m_busy)
        return false;   // a backup is reading the files with the old key
    QByteArray old;
    Files files;
    if (first) {
        m_key = random(32);
    } else if (wasOff) {
        old = m_key;
        files = collect(old, dataDir());
        const QByteArray fresh = random(32);
        QString error;
        if (!replaceAll(fresh, dataDir(), files, &error)) {
            replaceAll(old, dataDir(), files, &error);   // a swap that stopped partway: put the old sealing back
            for (auto &e : files)
                e.second.fill(0);
            return false;
        }
        m_key = fresh;
    }
    if (!writeLock(secret, pattern)) {
        if (first)
            m_key.clear();
        if (wasOff) {
            // vault.key still holds the old key: the files go back to it.
            QString error;
            replaceAll(old, dataDir(), files, &error);
            m_key = old;
        }
        for (auto &e : files)
            e.second.fill(0);
        return false;
    }
    for (auto &e : files)
        e.second.fill(0);
    old.fill(0);
    m_weak = false;
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

bool Vault::writeLock(const QString &secret, bool pattern) const
{
    const QByteArray salt = random(16);
    QJsonObject o{{QStringLiteral("mode"), QStringLiteral("password")}, {QStringLiteral("kind"), pattern ? QStringLiteral("pattern") : QStringLiteral("text")},
                  {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}};
    // Argon2id when this OpenSSL has it, scrypt otherwise: both make guessing on a graphics card slow.
    QByteArray kek = argon2id(secret.toUtf8(), salt, kArgonMem, kArgonIter, kArgonLanes);
    if (!kek.isEmpty()) {
        o.insert(QStringLiteral("kdf"), QStringLiteral("argon2id"));
        o.insert(QStringLiteral("m"), int(kArgonMem));
        o.insert(QStringLiteral("t"), int(kArgonIter));
        o.insert(QStringLiteral("p"), int(kArgonLanes));
    } else {
        kek = scrypt(secret.toUtf8(), salt, kScryptN, kScryptR, kScryptP);
        if (kek.isEmpty())
            return false;
        o.insert(QStringLiteral("kdf"), QStringLiteral("scrypt"));
        o.insert(QStringLiteral("N"), double(kScryptN));
        o.insert(QStringLiteral("r"), int(kScryptR));
        o.insert(QStringLiteral("p"), int(kScryptP));
    }
    // Only the lock itself guards the key: nothing outside Huginn (no Windows account binding), so the folder
    // opens on any computer with the right pattern or password.
    const QByteArray wrapped = seal(kek, m_key);
    o.insert(QStringLiteral("wrapped"), QString::fromLatin1(wrapped.toBase64()));
    QSaveFile f(keyFile());
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
    return f.commit();
}

bool Vault::askStronger()
{
    if (!m_weak || settings().value(QStringLiteral("chatlock/strongerAsked"), false).toBool())
        return false;
    settings().setValue(QStringLiteral("chatlock/strongerAsked"), true);
    return true;
}

void Vault::eraseAll()
{
    QDir(dataDir()).removeRecursively();
    // Files waiting in the cache (downloads, parts of a move) and a move's saved state go too.
    QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation)).removeRecursively();
    settings().remove(QStringLiteral("move"));
    settings().remove(QStringLiteral("chatlock/strongerAsked"));
    m_key.fill(0);
    m_key.clear();
    settings().remove(QStringLiteral("vault"));
    // The inbox address and chat identity go too. The day keys were vault files (gone above); the ring key and its
    // ring mailbox live outside the vault and go on erased() (main.cpp, RingKey::forget), so an erased computer
    // keeps none of its old mailboxes.
    settings().remove(QStringLiteral("nostr"));
    settings().remove(QStringLiteral("chat"));
    settings().remove(QStringLiteral("restore"));
    // Written now, so what Move saves right after (its "Moved to" note, through its own QSettings) isn't removed later.
    settings().sync();
    Q_EMIT erased();
    Q_EMIT changed();
}

bool Vault::write(const QString &name, const QByteArray &data) const
{
    if (m_key.isEmpty() || m_restarting)
        return false;
    QDir().mkpath(QFileInfo(path(name)).absolutePath());
    QSaveFile f(path(name));
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(kMagic + seal(m_key, data));
    return f.commit();
}

QByteArray Vault::read(const QString &name) const
{
    if (m_key.isEmpty())
        return {};
    QFile f(path(name));
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const QByteArray b = f.readAll();
    f.close();
    if (!b.startsWith(kMagic))
        return {};   // never take a file that isn't sealed: another program could have put it there
    bool ok = false;
    const QByteArray plain = open(m_key, b.mid(kMagic.size()), &ok);
    return ok ? plain : QByteArray();
}

void Vault::remove(const QString &name) const { QFile::remove(path(name)); }

// ------------------------------------------------------------------ panic lock

bool Vault::panicLock() const { return settings().value(QStringLiteral("chatlock/panic"), true).toBool(); }
void Vault::setPanicLock(bool on)
{
    settings().setValue(QStringLiteral("chatlock/panic"), on);
    Q_EMIT changed();
}
bool Vault::lockOnLeave() const { return settings().value(QStringLiteral("chatlock/leave"), false).toBool(); }
void Vault::setLockOnLeave(bool on)
{
    settings().setValue(QStringLiteral("chatlock/leave"), on);
    Q_EMIT changed();
}

void Vault::panic()
{
    // Without a lock Huginn would only open again by itself: panic lock does nothing then.
    if (!panicLock() || !exists() || locked() || !hasLock())
        return;
    lockScreen();
    Q_EMIT panicked();
    // Like Muninn: the key is wiped from memory and Huginn starts again, so nothing readable is left behind.
    // Nothing may be saved meanwhile (it would need the key).
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    Q_EMIT changed();
    QTimer::singleShot(300, this, [] { restartHuginn(); });
}

void Vault::button(const QString &name, bool down)
{
    if (name == QLatin1String("ls"))
        m_l3 = down;
    else if (name == QLatin1String("rs"))
        m_r3 = down;
    else
        return;
    if (m_l3 && m_r3 && panicLock()) {
        if (!m_combo.isActive())
            m_combo.start();
    } else {
        m_combo.stop();
    }
}

// ------------------------------------------------------------------ encrypted backup

namespace
{
const QByteArray kBackupMagic = QByteArrayLiteral("MUNB1");
const int kMaxHeader = 4096;
const int kMaxName = 4096;
// Huginn's own chat settings (name, public key, inbox) ride along under this name; Muninn skips it.
const QString kSettingsEntry = QStringLiteral("huginn-settings.dat");
const QStringList kSettingsGroups{QStringLiteral("chat"), QStringLiteral("nostr")};

void put32(QByteArray &out, quint32 v)
{
    const char b[4] = {char((v >> 24) & 0xff), char((v >> 16) & 0xff), char((v >> 8) & 0xff), char(v & 0xff)};
    out.append(b, 4);
}

bool get32(const QByteArray &in, qsizetype at, quint32 *v)
{
    if (at < 0 || at + 4 > in.size())
        return false;
    const auto *p = reinterpret_cast<const unsigned char *>(in.constData() + at);
    *v = (quint32(p[0]) << 24) | (quint32(p[1]) << 16) | (quint32(p[2]) << 8) | quint32(p[3]);
    return true;
}

QString sizeText(qint64 n)
{
    if (n < 1024 * 1024)
        return QString::number(qMax<qint64>(1, n / 1024)) + QStringLiteral(" KB");
    return QString::number(double(n) / (1024.0 * 1024.0), 'f', 1) + QStringLiteral(" MB");
}

// The backup key from its header (see vault.h). The header isn't protected, so anything weaker than what the apps
// write, or so big it would exhaust memory, is refused: empty key and *bad set.
QByteArray backupKey(const QJsonObject &h, const QString &password, bool *bad)
{
    *bad = true;
    const QByteArray salt = QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1());
    if (salt.size() < 16)
        return {};
    const int v = h.value(QStringLiteral("v")).toInt();
    if (v == 1) {
        const int rounds = h.value(QStringLiteral("rounds")).toInt();
        if (rounds < 100000 || rounds > 10000000)
            return {};
        *bad = false;
        return Vault::derive(password, salt, rounds);
    }
    const QString kdf = h.value(QStringLiteral("kdf")).toString();
    if (kdf == QLatin1String("argon2id")) {
        const qint64 m = h.value(QStringLiteral("m")).toInteger(), t = h.value(QStringLiteral("t")).toInteger(), p = h.value(QStringLiteral("p")).toInteger();
        if (m < 65536 || m > 1048576 || t < 3 || t > 16 || p < 1 || p > 16)
            return {};
        *bad = false;
        return argon2id(password.toUtf8(), salt, quint32(m), quint32(t), quint32(p));
    }
    if (kdf == QLatin1String("scrypt")) {
        const qint64 n = h.value(QStringLiteral("N")).toInteger(), r = h.value(QStringLiteral("r")).toInteger(), p = h.value(QStringLiteral("p")).toInteger();
        if (n < 65536 || n > 1048576 || (n & (n - 1)) != 0 || r < 8 || r > 32 || p < 1 || p > 16 || n * r * 128 > (qint64(1) << 30))
            return {};
        *bad = false;
        return scrypt(password.toUtf8(), salt, quint64(n), quint64(r), quint64(p));
    }
    return {};
}

QByteArray settingsBlob()
{
    QVariantMap all;
    QSettings &s = settings();
    s.sync();
    for (const QString &g : kSettingsGroups) {
        s.beginGroup(g);
        const QStringList keys = s.allKeys();
        for (const QString &k : keys)
            all.insert(g + QLatin1Char('/') + k, s.value(k));
        s.endGroup();
    }
    QByteArray out;
    QDataStream ds(&out, QIODevice::WriteOnly);
    ds.setVersion(QDataStream::Qt_6_0);
    ds << all;
    return out;
}

void applySettingsBlob(const QByteArray &blob)
{
    QVariantMap all;
    QDataStream ds(blob);
    ds.setVersion(QDataStream::Qt_6_0);
    ds >> all;
    if (ds.status() != QDataStream::Ok)
        return;
    QSettings &s = settings();
    for (const QString &g : kSettingsGroups)
        s.remove(g);
    for (auto it = all.cbegin(); it != all.cend(); ++it) {
        const QString group = it.key().section(QLatin1Char('/'), 0, 0);
        if (kSettingsGroups.contains(group))
            s.setValue(it.key(), it.value());
    }
    s.sync();
}

// Starts Huginn again (to load restored chats) and closes this one.
void restartHuginn()
{
    SingleInstance::releaseForRestart();   // the new copy must not find this one still holding the lock
    if (QFile::exists(QStringLiteral("/.flatpak-info")))
        QProcess::startDetached(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("run"), QStringLiteral("io.github.ravenor907.Huginn")});
    else
        QProcess::startDetached(QCoreApplication::applicationFilePath(), QStringList());
    QTimer::singleShot(300, qApp, &QCoreApplication::quit);
}
}

Vault::Files Vault::everything() const
{
    if (m_key.isEmpty())
        return {};
    if (beforeBackup)
        beforeBackup();
    Files files = collect(m_key, dataDir());
    files.emplace_back(kSettingsEntry, settingsBlob());
    return files;
}

bool Vault::takeEverything(const Files &files, QString *error)
{
    if (m_key.isEmpty()) {
        *error = QStringLiteral("Open Chat and unlock it first.");
        return false;
    }
    if (!replaceAll(m_key, dataDir(), files, error))
        return false;
    for (const auto &e : files)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    QTimer::singleShot(3000, this, [] { restartHuginn(); });
    return true;
}

QByteArray Vault::settingsWith(const QVariantMap &set, const QStringList &drop)
{
    QVariantMap all;
    const QByteArray blob = settingsBlob();
    {
        QDataStream ds(blob);
        ds.setVersion(QDataStream::Qt_6_0);
        ds >> all;
    }
    for (const QString &k : drop)
        all.remove(k);
    for (auto it = set.cbegin(); it != set.cend(); ++it)
        all.insert(it.key(), it.value());
    QByteArray out;
    QDataStream ds(&out, QIODevice::WriteOnly);
    ds.setVersion(QDataStream::Qt_6_0);
    ds << all;
    return out;
}

QString Vault::settingsEntry() { return kSettingsEntry; }

QString Vault::backupFolder() const
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
    if (dir.isEmpty())
        dir = QDir::homePath() + QStringLiteral("/Documents");
    return dir;
}

bool Vault::safeName(const QString &name)
{
    if (name.isEmpty() || name.startsWith(QLatin1Char('/')) || name.contains(QLatin1Char('\\')) || name.contains(QChar(0)))
        return false;
    const QStringList parts = name.split(QLatin1Char('/'));
    for (const QString &p : parts)
        if (p.isEmpty() || p == QLatin1String(".") || p == QLatin1String(".."))
            return false;
    return true;
}

QByteArray Vault::packBackup(const QString &password, const Files &files, const QString &app, const QString &kdf)
{
    const QByteArray salt = random(16);
    QJsonObject h{{QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())},
                  {QStringLiteral("created"), QDateTime::currentDateTimeUtc().toString(Qt::ISODate)}, {QStringLiteral("app"), app}};
    QByteArray key;
    if (kdf == QLatin1String("pbkdf2")) {
        h.insert(QStringLiteral("v"), 1);
        h.insert(QStringLiteral("rounds"), kRounds);
        key = derive(password, salt, kRounds);
    } else {
        // Argon2id like the lock when this OpenSSL has it, scrypt otherwise: both slow to guess on a graphics card.
        h.insert(QStringLiteral("v"), 2);
        key = argon2id(password.toUtf8(), salt, kArgonMem, kArgonIter, kArgonLanes);
        if (!key.isEmpty()) {
            h.insert(QStringLiteral("kdf"), QStringLiteral("argon2id"));
            h.insert(QStringLiteral("m"), int(kArgonMem));
            h.insert(QStringLiteral("t"), int(kArgonIter));
            h.insert(QStringLiteral("p"), int(kArgonLanes));
        } else {
            key = scrypt(password.toUtf8(), salt, kScryptN, kScryptR, kScryptP);
            h.insert(QStringLiteral("kdf"), QStringLiteral("scrypt"));
            h.insert(QStringLiteral("N"), double(kScryptN));
            h.insert(QStringLiteral("r"), int(kScryptR));
            h.insert(QStringLiteral("p"), int(kScryptP));
        }
    }
    if (key.isEmpty())
        return {};
    const QByteArray header = QJsonDocument(h).toJson(QJsonDocument::Compact);
    QByteArray body;
    for (const auto &f : files) {
        const QByteArray name = f.first.toUtf8();
        put32(body, quint32(name.size()));
        body.append(name);
        put32(body, quint32(f.second.size()));
        body.append(f.second);
    }
    QByteArray out = kBackupMagic;
    put32(out, quint32(header.size()));
    out.append(header);
    out.append(seal(key, body));
    key.fill(0);
    body.fill(0);
    return out;
}

int Vault::unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error)
{
    const auto damaged = [error] {
        *error = QStringLiteral("This backup is damaged.");
        return 1;
    };
    files->clear();
    if (!bytes.startsWith(kBackupMagic)) {
        *error = QStringLiteral("This isn't a Huginn or Muninn backup.");
        return 1;
    }
    quint32 len = 0;
    if (!get32(bytes, kBackupMagic.size(), &len) || len == 0 || len > quint32(kMaxHeader) || kBackupMagic.size() + 4 + qsizetype(len) > bytes.size())
        return damaged();
    const QJsonObject h = QJsonDocument::fromJson(bytes.mid(kBackupMagic.size() + 4, len)).object();
    const int v = h.value(QStringLiteral("v")).toInt();
    if (v != 1 && v != 2) {
        *error = h.isEmpty() ? QStringLiteral("This backup is damaged.") : QStringLiteral("This backup is from a newer version. Update Huginn first.");
        return 1;
    }
    // The key's settings come from the unprotected header: a weakened one is refused rather than trusted.
    const QString kdf = h.value(QStringLiteral("kdf")).toString();
    const QByteArray salt = QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1());
    const qint64 rounds = h.value(QStringLiteral("rounds")).toInteger();
    if (salt.size() < 16 || (v == 1 && (rounds < 100000 || rounds > 10000000))
        || (v == 2 && kdf != QLatin1String("argon2id") && kdf != QLatin1String("scrypt")))
        return damaged();
    if (created)
        *created = h.value(QStringLiteral("created")).toString();
    const QString from = h.value(QStringLiteral("app")).toString();
    if (from != app) {
        *error = from == QLatin1String("muninn") ? QStringLiteral("This is a Muninn backup. Restore it in Muninn.") : QStringLiteral("This isn't a Huginn backup.");
        return 3;
    }
    const qsizetype at = kBackupMagic.size() + 4 + qsizetype(len);
    if (bytes.size() - at < 12 + 16)
        return damaged();
    bool bad = false;
    QByteArray key = backupKey(h, password, &bad);
    if (bad)
        return damaged();
    if (key.isEmpty()) {
        *error = QStringLiteral("This backup needs Argon2id, which this copy of Huginn doesn't have.");
        return 1;
    }
    bool ok = false;
    QByteArray plain = open(key, bytes.mid(at), &ok);
    key.fill(0);
    if (!ok) {
        *error = QStringLiteral("That password doesn't open this backup.");   // or the file was changed: GCM can't tell which
        return 2;
    }
    qsizetype p = 0;
    while (p < plain.size()) {
        quint32 n = 0, dl = 0;
        if (!get32(plain, p, &n) || n == 0 || n > quint32(kMaxName) || qsizetype(n) > plain.size() - p - 4)
            break;
        const QString name = QString::fromUtf8(plain.mid(p + 4, n));
        p += 4 + n;
        if (!get32(plain, p, &dl) || qsizetype(dl) > plain.size() - p - 4)
            break;
        files->emplace_back(name, plain.mid(p + 4, dl));
        p += 4 + dl;
    }
    plain.fill(0);
    if (p != plain.size()) {
        files->clear();
        return damaged();
    }
    return 0;
}

Vault::Files Vault::collect(const QByteArray &key, const QString &dir)
{
    Files out;
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString full = it.next();
        const QString name = base.relativeFilePath(full);
        if (name == QLatin1String("vault.key") || !safeName(name))
            continue;
        QFile f(full);
        if (!f.open(QIODevice::ReadOnly))
            continue;
        const QByteArray b = f.readAll();
        if (!b.startsWith(kMagic))
            continue;   // not ours (a half-written temp file): leave it out
        bool ok = false;
        const QByteArray plain = open(key, b.mid(kMagic.size()), &ok);
        if (ok)
            out.emplace_back(name, plain);
    }
    std::sort(out.begin(), out.end(), [](const auto &a, const auto &b) { return a.first < b.first; });
    return out;
}

bool Vault::replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error)
{
    // Everything is written and checked in a new folder first, so a failure leaves Chat as it was.
    const QString stage = QDir::cleanPath(dir) + QStringLiteral(".restore");
    QDir(stage).removeRecursively();
    for (const auto &f : files) {
        if (!safeName(f.first) || f.first == QLatin1String("vault.key") || f.first == kSettingsEntry)
            continue;
        const QString target = stage + QLatin1Char('/') + f.first;
        QDir().mkpath(QFileInfo(target).absolutePath());
        QSaveFile out(target);
        if (!out.open(QIODevice::WriteOnly) || out.write(kMagic + seal(key, f.second)) < 0 || !out.commit()) {
            QDir(stage).removeRecursively();
            *error = QStringLiteral("Couldn't write the restored chats. Is the disk full?");
            return false;
        }
    }
    // Swap: clear Chat's folder except the lock, then move the new files in.
    QDir d(dir);
    d.mkpath(QStringLiteral("."));
    const QFileInfoList old = d.entryInfoList(QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot);
    for (const QFileInfo &fi : old) {
        if (fi.fileName() == QLatin1String("vault.key"))
            continue;
        if (fi.isDir() && !fi.isSymLink())
            QDir(fi.absoluteFilePath()).removeRecursively();
        else
            QFile::remove(fi.absoluteFilePath());
    }
    bool all = true;
    const QDir sd(stage);
    QDirIterator it(stage, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString from = it.next();
        const QString to = dir + QLatin1Char('/') + sd.relativeFilePath(from);
        QDir().mkpath(QFileInfo(to).absolutePath());
        QFile::remove(to);
        all &= QFile::rename(from, to);
    }
    QDir(stage).removeRecursively();
    if (!all)
        *error = QStringLiteral("Some restored files couldn't be moved into place.");
    return all;
}

void Vault::backup(const QString &password)
{
    if (m_busy)
        return;
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Unlock Huginn first."));
        return;
    }
    if (password.size() < kMinBackupPassword) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("The backup password needs at least %1 characters.").arg(kMinBackupPassword));
        return;
    }
    if (beforeBackup)
        beforeBackup();
    const QString folder = backupFolder();
    const QString file = folder + QStringLiteral("/huginn-backup-") + QDate::currentDate().toString(Qt::ISODate) + QStringLiteral(".mbk");
    const QByteArray key = m_key;
    const QString dir = dataDir();
    const QByteArray extra = settingsBlob();
    auto result = std::make_shared<QString>();
    auto good = std::make_shared<bool>(false);
    m_busy = true;
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        Files files = collect(key, dir);
        files.emplace_back(kSettingsEntry, extra);
        const QByteArray bytes = packBackup(password, files, QStringLiteral("huginn"));
        if (bytes.isEmpty()) {
            *result = QStringLiteral("Couldn't make the backup's key on this computer.");
            return;
        }
        QDir().mkpath(folder);
        QSaveFile f(file);
        if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size() || !f.commit()) {
            *result = QStringLiteral("Couldn't save the backup in ") + folder + QStringLiteral(". Is the disk full?");
            return;
        }
        *good = true;
        *result = QStringLiteral("Backed up to Documents as ") + QFileInfo(file).fileName() + QStringLiteral(" (") + sizeText(bytes.size()) + QStringLiteral(").");
    });
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, result, good] {
        t->deleteLater();
        if (!self)
            return;
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("backup"), *good, *result);
    });
    t->start();
}

QVariantList Vault::backups() const
{
    QVariantList out;
    const QFileInfoList list = QDir(backupFolder()).entryInfoList({QStringLiteral("*.mbk")}, QDir::Files, QDir::Time);
    for (const QFileInfo &fi : list) {
        // Only the header is readable without the password: which app made it, and when.
        QString app, when;
        QFile f(fi.absoluteFilePath());
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray head = f.read(kBackupMagic.size() + 4 + kMaxHeader);
            quint32 len = 0;
            if (head.startsWith(kBackupMagic) && get32(head, kBackupMagic.size(), &len) && len <= quint32(kMaxHeader)) {
                const QJsonObject h = QJsonDocument::fromJson(head.mid(kBackupMagic.size() + 4, len)).object();
                app = h.value(QStringLiteral("app")).toString();
                const QDateTime c = QDateTime::fromString(h.value(QStringLiteral("created")).toString(), Qt::ISODate);
                if (c.isValid())
                    when = QLocale().toString(c.toLocalTime(), QStringLiteral("d MMM yyyy, h:mm AP"));
            }
        }
        QString detail = app == QLatin1String("huginn") ? QStringLiteral("Huginn backup") : app == QLatin1String("muninn") ? QStringLiteral("Muninn backup (restore it in Muninn)") : QStringLiteral("Not a backup Huginn can read");
        if (!when.isEmpty())
            detail += QStringLiteral(" · ") + when;
        detail += QStringLiteral(" · ") + sizeText(fi.size());
        out.append(QVariantMap{{QStringLiteral("name"), fi.fileName()}, {QStringLiteral("detail"), detail}, {QStringLiteral("url"), fi.absoluteFilePath()},
                             {QStringLiteral("app"), app}});
    }
    return out;
}

void Vault::openBackup(const QString &file, const QString &password)
{
    if (m_busy)
        return;
    forgetOpened();
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("open"), false, QStringLiteral("Unlock Huginn first."));
        return;
    }
    auto files = std::make_shared<Files>();
    auto result = std::make_shared<QString>();
    auto good = std::make_shared<bool>(false);
    m_busy = true;
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        QFile f(file);
        if (!f.open(QIODevice::ReadOnly)) {
            *result = QStringLiteral("Couldn't open ") + QFileInfo(file).fileName() + QLatin1Char('.');
            return;
        }
        QString created;
        if (unpackBackup(f.readAll(), password, QStringLiteral("huginn"), files.get(), &created, result.get()) != 0)
            return;
        int count = 0;
        for (const auto &e : *files)
            count += e.first == kSettingsEntry ? 0 : 1;
        const QDateTime c = QDateTime::fromString(created, Qt::ISODate);
        *good = true;
        *result = QStringLiteral("Backup from ") + (c.isValid() ? QLocale().toString(c.toLocalTime(), QStringLiteral("d MMM yyyy, h:mm AP")) : QStringLiteral("an unknown date"))
            + QStringLiteral(", ") + QString::number(count) + (count == 1 ? QStringLiteral(" file.") : QStringLiteral(" files."));
    });
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, files, result, good] {
        t->deleteLater();
        if (!self)
            return;
        if (*good)
            self->m_opened = std::move(*files);
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("open"), *good, *result);
    });
    t->start();
}

void Vault::forgetOpened()
{
    for (auto &e : m_opened)
        e.second.fill(0);
    m_opened.clear();
}

void Vault::restoreOpened()
{
    if (m_busy || m_opened.empty() || locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Open the backup again first."));
        return;
    }
    QString error;
    if (!replaceAll(m_key, dataDir(), m_opened, &error)) {
        forgetOpened();
        Q_EMIT done(QStringLiteral("restore"), false, error);
        return;
    }
    for (const auto &e : m_opened)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    forgetOpened();
    // The backup's day keys may be older than the ones contacts hold: Chat makes a new one and announces it at the
    // next start (restore/announce), and wrappings for day keys it no longer has wait 7 days (restore/at).
    settings().setValue(QStringLiteral("restore/at"), QDateTime::currentMSecsSinceEpoch());
    settings().setValue(QStringLiteral("restore/announce"), true);
    settings().sync();
    // Chat still holds the old conversations in memory: drop the key so nothing it saves in the
    // next moment can overwrite what was just restored, then start fresh on the restored files.
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    Q_EMIT done(QStringLiteral("restore"), true, QStringLiteral("Restored. Huginn restarts to load your chats."));
    QTimer::singleShot(1500, this, [] { restartHuginn(); });
}

bool Vault::backupSelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("backup %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        check("temp folder", false);
        return false;
    }
    const QByteArray keyA(32, '\x11'), keyB(32, '\x22');
    const QString src = tmp.path() + QStringLiteral("/a/chat"), dst = tmp.path() + QStringLiteral("/b/chat");
    const Files want{{QStringLiteral("chats.json"), QByteArrayLiteral("{\"k\":[{\"text\":\"hi\"}]}")},
                     {QStringLiteral("contacts.json"), QByteArrayLiteral("[]")},
                     {QStringLiteral("photos/abc"), QByteArray(70000, '\x5a')},
                     {QStringLiteral("secrets.json"), QByteArrayLiteral("{\"chatPriv\":\"x\"}")}};
    QString error;
    check("write source folder", replaceAll(keyA, src, want, &error));
    QFile key(src + QStringLiteral("/vault.key"));
    if (key.open(QIODevice::WriteOnly)) {
        key.write("{\"kind\":\"text\"}");
        key.close();
    }
    const Files got = collect(keyA, src);
    check("collect leaves out vault.key", got == want);

    const QByteArray bytes = packBackup(QStringLiteral("correct horse"), got, QStringLiteral("huginn"));
    quint32 len = 0;
    const bool lenOk = bytes.startsWith("MUNB1") && get32(bytes, 5, &len) && len > 0 && len <= 4096;
    const QJsonObject h = QJsonDocument::fromJson(bytes.mid(9, len)).object();
    const QString kdf = h.value(QStringLiteral("kdf")).toString();
    check("header", lenOk && h.value(QStringLiteral("v")).toInt() == 2 && !h.contains(QStringLiteral("rounds"))
                        && ((kdf == QLatin1String("argon2id") && h.value(QStringLiteral("m")).toInt() == int(kArgonMem)
                             && h.value(QStringLiteral("t")).toInt() == int(kArgonIter) && h.value(QStringLiteral("p")).toInt() == int(kArgonLanes))
                            || (kdf == QLatin1String("scrypt") && h.value(QStringLiteral("N")).toInteger() == qint64(kScryptN)))
                        && h.value(QStringLiteral("app")).toString() == QLatin1String("huginn")
                        && QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1()).size() == 16
                        && QDateTime::fromString(h.value(QStringLiteral("created")).toString(), Qt::ISODate).isValid());
    check("names and data are not readable", !bytes.contains("chats.json") && !bytes.contains(QByteArray(64, '\x5a')));

    Files back;
    QString created;
    check("wrong password refused", unpackBackup(bytes, QStringLiteral("wrong horse"), QStringLiteral("huginn"), &back, &created, &error) == 2 && back.empty());
    check("Muninn reader refuses a Huginn backup", unpackBackup(bytes, QStringLiteral("correct horse"), QStringLiteral("muninn"), &back, &created, &error) == 3);
    QByteArray changedBytes = bytes;
    changedBytes[changedBytes.size() - 20] = char(changedBytes.at(changedBytes.size() - 20) ^ 1);
    check("changed file refused", unpackBackup(changedBytes, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 2);
    check("not a backup refused", unpackBackup(QByteArrayLiteral("MUNV1 something else"), QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 1);
    check("round trip", unpackBackup(bytes, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 0 && back == want);
    // A PBKDF2 backup (v1) still restores.
    {
        Files v1back;
        const QByteArray v1 = packBackup(QStringLiteral("correct horse"), got, QStringLiteral("huginn"), QStringLiteral("pbkdf2"));
        quint32 l1 = 0;
        const QJsonObject h1 = get32(v1, 5, &l1) ? QJsonDocument::fromJson(v1.mid(9, l1)).object() : QJsonObject();
        check("PBKDF2 backup restores", h1.value(QStringLiteral("v")).toInt() == 1 && h1.value(QStringLiteral("rounds")).toInt() == 310000
                                            && unpackBackup(v1, QStringLiteral("correct horse"), QStringLiteral("huginn"), &v1back, &created, &error) == 0
                                            && v1back == want);
    }
    // Weakened settings in the (unprotected) header are refused, not used.
    {
        const auto withHeader = [&bytes, len](const QJsonObject &nh) {
            const QByteArray js = QJsonDocument(nh).toJson(QJsonDocument::Compact);
            QByteArray out = QByteArrayLiteral("MUNB1");
            put32(out, quint32(js.size()));
            return out + js + bytes.mid(9 + qsizetype(len));
        };
        QJsonObject weakA = h;
        weakA.insert(QStringLiteral("kdf"), QStringLiteral("argon2id"));
        weakA.insert(QStringLiteral("m"), 1024);
        weakA.insert(QStringLiteral("t"), 3);
        weakA.insert(QStringLiteral("p"), 1);
        QJsonObject weakS = h;
        weakS.insert(QStringLiteral("kdf"), QStringLiteral("scrypt"));
        weakS.insert(QStringLiteral("N"), 1024.0);
        weakS.insert(QStringLiteral("r"), 8);
        weakS.insert(QStringLiteral("p"), 1);
        QJsonObject none = h;
        none.remove(QStringLiteral("kdf"));
        Files refused;
        check("weakened key settings refused", unpackBackup(withHeader(weakA), QStringLiteral("correct horse"), QStringLiteral("huginn"), &refused, &created, &error) == 1
                                                   && unpackBackup(withHeader(weakS), QStringLiteral("correct horse"), QStringLiteral("huginn"), &refused, &created, &error) == 1
                                                   && unpackBackup(withHeader(none), QStringLiteral("correct horse"), QStringLiteral("huginn"), &refused, &created, &error) == 1);
    }

    // Restore onto another computer's chat: its own lock stays, everything else is replaced.
    const Files stale{{QStringLiteral("chats.json"), QByteArrayLiteral("old")}, {QStringLiteral("photos/old"), QByteArrayLiteral("old")}};
    replaceAll(keyB, dst, stale, &error);
    QFile otherKey(dst + QStringLiteral("/vault.key"));
    if (otherKey.open(QIODevice::WriteOnly)) {
        otherKey.write("other lock");
        otherKey.close();
    }
    check("restore", replaceAll(keyB, dst, back, &error));
    QFile kept(dst + QStringLiteral("/vault.key"));
    check("restore keeps this computer's lock", kept.open(QIODevice::ReadOnly) && kept.readAll() == "other lock");
    check("restored files open with this computer's key", collect(keyB, dst) == want);
    check("old files removed", !QFile::exists(dst + QStringLiteral("/photos/old")));
    check("unsafe names refused", !safeName(QStringLiteral("../x")) && !safeName(QStringLiteral("/etc/x")) && !safeName(QStringLiteral("a//b"))
                                      && safeName(QStringLiteral("photos/abc")));
    return all;
}

// ------------------------------------------------------------------ the math

QByteArray Vault::seal(const QByteArray &key, const QByteArray &plain)
{
    const QByteArray iv = random(12);
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_EncryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(plain.constData()), int(plain.size()));
    total = len;
    EVP_EncryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len);
    total += len;
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total);
    EVP_CIPHER_CTX_free(c);
    out.resize(total + 16);
    return iv + out;   // the same layout Java's AES/GCM gives: iv, ciphertext, tag
}

QByteArray Vault::open(const QByteArray &key, const QByteArray &data, bool *ok)
{
    *ok = false;
    if (key.size() != 32 || data.size() < 12 + 16)
        return {};
    const QByteArray iv = data.left(12), tag = data.right(16), ct = data.mid(12, data.size() - 28);
    QByteArray out(ct.size(), 0);
    int len = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_DecryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(ct.constData()), int(ct.size()));
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, const_cast<char *>(tag.constData()));
    const bool good = EVP_DecryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + len, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    *ok = good;
    return good ? out : QByteArray();
}

QByteArray Vault::derive(const QString &secret, const QByteArray &salt, int rounds)
{
    const QByteArray pw = secret.toUtf8();
    QByteArray out(32, 0);
    PKCS5_PBKDF2_HMAC(pw.constData(), int(pw.size()), reinterpret_cast<const unsigned char *>(salt.constData()), int(salt.size()), rounds, EVP_sha256(), 32,
                      reinterpret_cast<unsigned char *>(out.data()));
    return out;
}

bool Vault::selfTest()
{
    bool all = true;
    const QByteArray key(32, '\x07');
    bool ok = false;
    const QByteArray sealed = seal(key, "secret chats");
    const bool round = open(key, sealed, &ok) == "secret chats" && ok;
    std::printf("vault seal/open round trip: %s\n", round ? "ok" : "FAILED");
    all &= round;
    open(QByteArray(32, '\x08'), sealed, &ok);
    std::printf("vault wrong key rejected: %s\n", !ok ? "ok" : "FAILED");
    all &= !ok;
    // PBKDF2-HMAC-SHA256("password", "salt", 1 round) from RFC 7914 section 11.
    const bool kdf = derive(QStringLiteral("password"), "salt", 1).toHex() == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b";
    std::printf("vault PBKDF2 test vector: %s\n", kdf ? "ok" : "FAILED");
    all &= kdf;
    // scrypt("password", "NaCl", N=1024, r=8, p=16), RFC 7914 section 12.
    const bool sc = scrypt("password", "NaCl", 1024, 8, 16, 64).toHex()
        == "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640";
    std::printf("vault scrypt test vector: %s\n", sc ? "ok" : "FAILED");
    all &= sc;
    // Argon2id, RFC 9106 section 5.3 (only when this OpenSSL has it; new locks use scrypt otherwise).
    const QByteArray a2 = argon2id(QByteArray(32, '\x01'), QByteArray(16, '\x02'), 32, 3, 4, 32, QByteArray(8, '\x03'), QByteArray(12, '\x04'));
    if (a2.isEmpty()) {
        std::printf("vault Argon2id: not in this OpenSSL, new locks use scrypt\n");
    } else {
        const bool ar = a2.toHex() == "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659";
        std::printf("vault Argon2id test vector: %s\n", ar ? "ok" : "FAILED");
        all &= ar;
    }
    // New locks: at least 6 dots or 6 characters.
    const bool rules = !strongSecret(QStringLiteral("pattern:0,1,2,5,8")) && strongSecret(QStringLiteral("pattern:0,1,2,5,8,7"))
        && !strongSecret(QStringLiteral("abcde")) && strongSecret(QStringLiteral("abcdef"));
    std::printf("vault lock length rules: %s\n", rules ? "ok" : "FAILED");
    all &= rules;
    // A lock file opens with its secret alone (no "dpapi" or anything else outside Huginn), and not with a wrong one.
    {
        const QByteArray salt = random(16), vk = random(32);
        const QByteArray kek = scrypt(QByteArrayLiteral("pattern:0,1,2,5,8,7"), salt, 1024, 8, 1);
        const QJsonObject lock{{QStringLiteral("mode"), QStringLiteral("password")}, {QStringLiteral("kind"), QStringLiteral("pattern")},
                               {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}, {QStringLiteral("kdf"), QStringLiteral("scrypt")},
                               {QStringLiteral("N"), 1024.0}, {QStringLiteral("r"), 8}, {QStringLiteral("p"), 1},
                               {QStringLiteral("wrapped"), QString::fromLatin1(seal(kek, vk).toBase64())}};
        bool good = false, bad = true;
        const QByteArray got = openLock(lock, QStringLiteral("pattern:0,1,2,5,8,7"), &good);
        openLock(lock, QStringLiteral("pattern:0,1,2,5,8,6"), &bad);
        const bool lk = !kek.isEmpty() && good && got == vk && !bad;
        std::printf("vault lock opens with its secret only: %s\n", lk ? "ok" : "FAILED");
        all &= lk;
    }
    return all;
}
