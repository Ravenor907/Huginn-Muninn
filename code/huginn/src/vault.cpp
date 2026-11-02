#include "vault.h"
#include "busy.h"
#include "settings.h"
#include "system.h"
#include "platform/crashlog.h"
#include "platform/portable.h"
#include "platform/singleinstance.h"
#include "gamepad.h"

#include <QCoreApplication>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFileDialog>
#include <QJsonArray>
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
#include <QDeadlineTimer>
#include <QMutex>
#include <QMutexLocker>
#include <QWaitCondition>
#include <algorithm>
#include <atomic>
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
    const QString dir = System::dataDir() + QStringLiteral("/chat");
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
    static QSettings s = Settings::store();
    return s;
}

// A sealed file's contents with key; ok false when it isn't sealed with it (or isn't sealed at all: another program
// could have put it there).
QByteArray openSealed(const QByteArray &key, const QByteArray &b, bool *ok)
{
    *ok = false;
    if (!b.startsWith(kMagic))
        return {};
    return Vault::open(key, b.mid(kMagic.size()), ok);
}
}

struct Vault::Shared
{
    QMutex lock;
    QByteArray key;
};

// The saving thread ("huginn-saving"): sealing and writing the files Chat saves often (Vault::writeLater), so the
// window never waits on them. One thread, one queue: a file's saves are written in the order they were asked for (a
// newer one still waiting takes the older one's place). It stops only once the queue is empty, so nothing asked of it is
// lost when Huginn closes. It never waits on the window's thread; the window's thread waits on it only with a limit.
class SaveQueue : public QThread
{
public:
    struct Job {
        QString name, file;
        QByteArray key;
        std::function<QByteArray()> make;
    };
    SaveQueue() { setObjectName(QStringLiteral("huginn-saving")); }

    void put(Job job)
    {
        QMutexLocker l(&m_lock);
        for (Job &q : m_jobs)
            if (q.name == job.name) {
                q = std::move(job);   // the newest wins and keeps the older one's place
                Busy::count("saves replaced by a newer one before they were written");
                return;
            }
        m_jobs.push_back(std::move(job));
        m_wake.wakeOne();
    }
    // Until nothing for name (empty: anything) waits or is being written, at most ms. False when time ran out.
    bool waitFor(const QString &name, int ms)
    {
        QDeadlineTimer deadline(ms);
        QMutexLocker l(&m_lock);
        while (busyWith(name))
            if (!m_idle.wait(&m_lock, deadline))
                return !busyWith(name);
        return true;
    }
    bool pending(const QString &name)
    {
        QMutexLocker l(&m_lock);
        return busyWith(name);
    }
    // Erase everything, a restore, a move: what waits is dropped (it would only be replaced), the one being written ends.
    void dropAll(int ms)
    {
        {
            QMutexLocker l(&m_lock);
            m_jobs.clear();
        }
        waitFor(QString(), ms);
    }
    // Writes everything still waiting, then ends. False when it hadn't ended within ms.
    bool finish(int ms)
    {
        {
            QMutexLocker l(&m_lock);
            m_stopping = true;
            m_wake.wakeAll();
        }
        return wait(QDeadlineTimer(ms));
    }
    int failures()
    {
        QMutexLocker l(&m_lock);
        return m_failures;
    }
protected:
    void run() override
    {
        for (;;) {
            Job job;
            {
                QMutexLocker l(&m_lock);
                while (m_jobs.empty() && !m_stopping)
                    m_wake.wait(&m_lock);
                if (m_jobs.empty())
                    return;   // stopping, and nothing left
                job = std::move(m_jobs.front());
                m_jobs.erase(m_jobs.begin());
                m_current = job.name;
            }
            const bool ok = writeNow(job);
            QMutexLocker l(&m_lock);
            m_current.clear();
            if (!ok)
                ++m_failures;
            m_idle.wakeAll();
        }
    }

private:
    bool busyWith(const QString &name) const
    {
        if (name.isEmpty())
            return !m_jobs.empty() || !m_current.isEmpty();
        if (m_current == name)
            return true;
        for (const Job &j : m_jobs)
            if (j.name == name)
                return true;
        return false;
    }
    static bool writeNow(const Job &job)
    {
        Busy::ThreadScope busy("saving thread: making, sealing and writing files");
        if (const int ms = s_slow(); ms > 0)
            QThread::msleep(ms);
        QByteArray data = job.make ? job.make() : QByteArray();
        const QByteArray sealed = kMagic + Vault::seal(job.key, data);
        data.fill(0);
        // A file another program holds for a moment gets a second try.
        for (int attempt = 0; attempt < 2; ++attempt) {
            QDir().mkpath(QFileInfo(job.file).absolutePath());
            QSaveFile f(job.file);
            if (f.open(QIODevice::WriteOnly) && f.write(sealed) >= 0 && f.commit())
                return true;
            QThread::msleep(150);
        }
        qWarning("vault: the saving thread couldn't write a file");
        Busy::count("saves that couldn't be written");
        return false;
    }
    static int s_slow();

    QMutex m_lock;
    QWaitCondition m_wake, m_idle;
    std::vector<Job> m_jobs;
    QString m_current;
    bool m_stopping = false;
    int m_failures = 0;
};

namespace
{
std::atomic<int> s_slowWrites{0};   // --perf-selftest only
}
int SaveQueue::s_slow() { return s_slowWrites.load(); }

Vault::Vault(QObject *parent)
    : QObject(parent), m_shared(std::make_shared<Shared>())
{
    m_saves = new SaveQueue;
    m_saves->start();
    // Every change of the key (unlock, panic, erase, restore) is followed by changed(): the copy for other threads follows.
    connect(this, &Vault::changed, this, &Vault::shareKey);
    // Huginn never locks on its own: Lock now, the panic lock and Lock and close do. An old "Lock when I leave"
    // setting is dropped.
    settings().remove(QStringLiteral("chatlock/leave"));
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
}

Vault::~Vault()
{
    // An unlock still working out the key posts its answer here: it is waited for (a second or two at most).
    if (m_unlockThread)
        m_unlockThread->wait();
    // Everything asked of the saving thread is written before Huginn ends; one stuck on a file for half a minute is
    // left behind rather than waited on forever (destroying a running thread would end Huginn badly).
    if (m_saves->finish(30000))
        delete m_saves;
    else
        qWarning("vault: the saving thread didn't finish in time");
    for (auto it = m_prefetched.begin(); it != m_prefetched.end(); ++it)
        it.value().fill(0);
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
    if (!writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact)))
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
    // Restored after "Forgot it?": a new lock comes first (the lock screen asks for one, with no Skip).
    m_lockNeeded = o.value(QStringLiteral("needsLock")).toBool();
    Q_EMIT changed();
    Q_EMIT opened();
}

bool Vault::startWithoutLock()
{
    if (exists() || !m_key.isEmpty() || m_restarting)
        return false;
    m_key = random(32);
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("open")}, {QStringLiteral("key"), QString::fromLatin1(m_key.toBase64())}};
    if (!writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact))) {
        m_key.fill(0);
        m_key.clear();
        return false;
    }
    m_weak = false;
    m_screenLocked = false;
    Q_EMIT changed();
    Q_EMIT opened();
    return true;
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
    const bool first = m_key.isEmpty();
    m_key = key;
    m_weak = !strongSecret(secret);
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

void Vault::unlockInBackground(const QString &secret)
{
    if (m_unlocking)
        return;
    const auto fail = [this] { QMetaObject::invokeMethod(this, [this] { Q_EMIT unlockFinished(false); }, Qt::QueuedConnection); };
    if (m_restarting || waitSeconds() > 0)
        return fail();
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return fail();
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    f.close();
    // The first unlock reads and opens the saved chat files there too (not pictures: they are read when shown).
    const bool first = m_key.isEmpty();
    const QString dir = dataDir();
    m_unlocking = true;
    Q_EMIT changed();
    // The thread uses only what it was given and answers with a queued call; ~Vault waits for it, so "this" is
    // still here when the answer comes.
    QThread *t = QThread::create([this, o, secret, first, dir] {
        Busy::ThreadScope busy("unlock thread: working out the key and reading files");
        bool ok = false;
        const QByteArray key = openLock(o, secret, &ok);
        const bool good = ok && key.size() == 32;
        Files read;
        if (good && first) {
            qint64 total = 0;
            QDirIterator it(dir, {QStringLiteral("*.json")}, QDir::Files);
            while (it.hasNext()) {
                const QFileInfo fi(it.next());
                if (fi.fileName() == QLatin1String("vault.key") || total + fi.size() > (qint64(96) << 20))
                    continue;
                QFile in(fi.filePath());
                if (!in.open(QIODevice::ReadOnly))
                    continue;
                bool opened = false;
                const QByteArray plain = openSealed(key, in.readAll(), &opened);
                if (opened) {
                    total += fi.size();
                    read.emplace_back(fi.fileName(), plain);
                }
            }
        }
        const bool weak = !strongSecret(secret);
        QMetaObject::invokeMethod(this, [this, good, key, weak, read] { finishUnlock(good, key, weak, read); }, Qt::QueuedConnection);
    });
    t->setObjectName(QStringLiteral("huginn-unlock"));
    connect(t, &QThread::finished, t, &QObject::deleteLater);
    m_unlockThread = t;
    t->start();
}

void Vault::finishUnlock(bool ok, const QByteArray &key, bool weak, const Files &read)
{
    m_unlocking = false;
    if (m_restarting || !ok) {
        if (!ok)
            wrongTry();
        Q_EMIT changed();
        Q_EMIT unlockFinished(false);
        return;
    }
    settings().setValue(QStringLiteral("vault/fails"), 0);
    settings().remove(QStringLiteral("vault/wait"));
    settings().remove(QStringLiteral("vault/at"));
    settings().remove(QStringLiteral("vault/started"));
    const bool first = m_key.isEmpty();
    m_key = key;
    m_weak = weak;
    m_screenLocked = false;
    // Only for the first unlock, and only while opened() runs: what the thread read is what is on disk (nothing
    // can be saved while there is no key). Whatever nobody asked for is wiped.
    if (first)
        for (const auto &e : read)
            m_prefetched.insert(e.first, e.second);
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    for (auto it = m_prefetched.begin(); it != m_prefetched.end(); ++it)
        it.value().fill(0);
    m_prefetched.clear();
    Q_EMIT unlockFinished(true);
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
        drainSaves(30000);   // everything sealed with the old key is on disk before it is sealed again
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
    m_lockNeeded = false;   // the new lock is set
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
    return writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

// vault.key, readable and writable by this user only (like ring-key.json and the TLS key): with the lock off it
// holds the data key itself.
bool Vault::writeKeyFile(const QByteArray &bytes) const
{
    const QFileDevice::Permissions owner = QFileDevice::ReadOwner | QFileDevice::WriteOwner;
    QSaveFile f(keyFile());
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.setPermissions(owner);
    f.write(bytes);
    if (!f.commit())
        return false;
    QFile::setPermissions(keyFile(), owner);
    return true;
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
    // What waits to be saved would only bring files back: dropped (the one being written ends first).
    m_saves->dropAll(30000);
    QDir(dataDir()).removeRecursively();
    // Copies kept before a restore (keepCopy) go too.
    for (const QFileInfo &fi : QDir(System::dataDir()).entryInfoList({QStringLiteral("chat-kept-*")}, QDir::Dirs | QDir::NoDotAndDotDot))
        QDir(fi.absoluteFilePath()).removeRecursively();
    // Files waiting in the cache (downloads, parts of a move) and a move's saved state go too.
    QDir(System::cacheDir()).removeRecursively();
    settings().remove(QStringLiteral("move"));
    settings().remove(QStringLiteral("chatlock/strongerAsked"));
    m_key.fill(0);
    m_key.clear();
    m_unreadable.clear();
    settings().remove(QStringLiteral("vault"));
    // The inbox address and chat identity go too. The day keys were vault files (gone above); the ring key and its
    // ring mailbox live outside the vault and go on erased() (main.cpp, RingKey::forget), so an erased computer
    // keeps none of its old mailboxes.
    settings().remove(QStringLiteral("nostr"));
    settings().remove(QStringLiteral("chat"));
    settings().remove(QStringLiteral("restore"));
    // The plain "linked to a phone" note (Link::linkedWhileLocked): the link went with the vault.
    settings().remove(QStringLiteral("link"));
    // An erased computer starts over: first setup runs again at the next start.
    settings().remove(QStringLiteral("setup"));
    // Written now, so what Move saves right after (its "Moved to" note, through its own QSettings) isn't removed later.
    settings().sync();
    // The Windows portable build: the whole data folder beside Huginn.exe is emptied (every setting too).
    Portable::eraseAll();
    Q_EMIT erased();
    Q_EMIT changed();
}

void Vault::eraseForRelink()
{
    eraseAll();
    // Only yes/no, beside the vault (gone above), and after the portable build emptied its settings. Link::confirm
    // reads it once and removes it; a plain eraseAll removes it too (the "link" group).
    QSettings s = Settings::store();
    s.setValue(QStringLiteral("link/relink"), true);
    s.sync();
}

namespace
{
// What kind of file it is, for Labs › Performance check's counts: "chats", "photos", "ratchet" (never a name).
QString kindOf(const QString &name)
{
    const QString first = name.section(QLatin1Char('/'), 0, 0);
    qsizetype n = 0;
    while (n < first.size() && n < 24 && ((first.at(n) >= QLatin1Char('a') && first.at(n) <= QLatin1Char('z')) || first.at(n) == QLatin1Char('-')))
        ++n;
    return n > 0 ? first.left(n) : QStringLiteral("other");
}
}

bool Vault::write(const QString &name, const QByteArray &data) const
{
    Busy::Scope busy("saving files");
    Busy::count(QStringLiteral("files saved: ") + kindOf(name));
    if (m_key.isEmpty() || m_restarting)
        return false;
    // Loaded as empty because it couldn't be read: saving now would replace what is really there.
    if (m_unreadable.contains(name)) {
        qWarning("vault: not saving over a file that couldn't be read");
        return false;
    }
    // A save of the same file still waiting on the saving thread goes first: a file's saves stay in order.
    m_saves->waitFor(name, 10000);
    QDir().mkpath(QFileInfo(path(name)).absolutePath());
    QSaveFile f(path(name));
    if (!f.open(QIODevice::WriteOnly))
        return false;
    if (f.write(kMagic + seal(m_key, data)) < 0)
        return false;
    return f.commit();
}

void Vault::writeLater(const QString &name, std::function<QByteArray()> make) const
{
    Busy::count(QStringLiteral("files saved on the saving thread: ") + kindOf(name));
    if (m_key.isEmpty() || m_restarting)
        return;
    // Loaded as empty because it couldn't be read: saving now would replace what is really there.
    if (m_unreadable.contains(name)) {
        qWarning("vault: not saving over a file that couldn't be read");
        return;
    }
    SaveQueue::Job job;
    job.name = name;
    job.file = path(name);
    job.key = m_key;
    job.make = std::move(make);
    m_saves->put(std::move(job));
}

bool Vault::drainSaves(int ms) const
{
    Busy::Scope busy("waiting for the saving thread");
    const bool done = m_saves->waitFor(QString(), ms);
    if (!done)
        qWarning("vault: the saving thread is still writing");
    return done;
}

Vault::ReadStatus Vault::readOnly(const QString &name) const
{
    if (m_key.isEmpty())
        return ReadFailed;
    m_saves->waitFor(name, 10000);
    QFile f(path(name));
    if (!f.exists())
        return ReadMissing;
    if (!f.open(QIODevice::ReadOnly))
        return ReadFailed;
    bool ok = false;
    QByteArray plain = openSealed(m_key, f.readAll(), &ok);
    plain.fill(0);
    return ok ? ReadOk : ReadFailed;
}

QByteArray Vault::read(const QString &name, ReadStatus *status) const
{
    Busy::Scope busy("reading files");
    Busy::count(QStringLiteral("files read: ") + kindOf(name));
    if (status)
        *status = ReadFailed;
    if (m_key.isEmpty())
        return {};   // locked: nothing is noted (the files are fine)
    // Read and opened already by the unlock's own thread (only while opened() runs).
    const auto pre = m_prefetched.find(name);
    if (pre != m_prefetched.end()) {
        const QByteArray plain = pre.value();
        m_prefetched.erase(pre);
        if (status)
            *status = ReadOk;
        if (m_unreadable.remove(name) && m_unreadable.isEmpty())
            QMetaObject::invokeMethod(const_cast<Vault *>(this), [v = const_cast<Vault *>(this)] { Q_EMIT v->changed(); }, Qt::QueuedConnection);
        return plain;
    }
    // A save of it still on the saving thread is written first, so what is read is what was last saved.
    m_saves->waitFor(name, 10000);
    const QString file = path(name);
    // A few short tries: another program (a virus scanner, a copy of Huginn closing) may hold it for a moment.
    for (int attempt = 0; attempt < 3; ++attempt) {
        if (!QFileInfo::exists(file)) {
            if (status)
                *status = ReadMissing;
            return {};
        }
        QFile f(file);
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray b = f.readAll();
            f.close();
            bool ok = false;
            const QByteArray plain = openSealed(m_key, b, &ok);
            if (ok) {
                if (status)
                    *status = ReadOk;
                if (m_unreadable.remove(name) && m_unreadable.isEmpty())
                    QMetaObject::invokeMethod(const_cast<Vault *>(this), [v = const_cast<Vault *>(this)] { Q_EMIT v->changed(); }, Qt::QueuedConnection);
                return plain;
            }
        }
        if (attempt < 2)
            QThread::msleep(80 * (attempt + 1));
    }
    // There, but not readable: noted, so nothing is saved over it, and said on screen. Pictures stand alone (each
    // is written once under its own name), so only the chat files count.
    if (!name.startsWith(QLatin1String("photos/")) && !m_unreadable.contains(name)) {
        m_unreadable.insert(name);
        qWarning("vault: a saved file couldn't be read: %s", qPrintable(name));
        CrashLog::note(QStringLiteral("a saved file couldn't be read: ") + name.section(QLatin1Char('/'), 0, 0));
        QMetaObject::invokeMethod(const_cast<Vault *>(this), [v = const_cast<Vault *>(this)] { Q_EMIT v->changed(); }, Qt::QueuedConnection);
    }
    return {};
}

bool Vault::unreadable() const { return !m_unreadable.isEmpty() && !locked(); }

bool Vault::tryReadAgain()
{
    if (m_unreadable.isEmpty())
        return true;
    for (const QString &name : std::as_const(m_unreadable))
        if (readOnly(name) == ReadFailed)
            return false;
    // Everything reads now. What this run holds in memory was loaded without them, so it is never saved over them:
    // Huginn starts again and loads it all (the files stay blocked until this copy has closed).
    restartHuginn(QStringLiteral("Close Huginn and open it again to load your chats."));
    return true;
}

void Vault::shareKey()
{
    QMutexLocker l(&m_shared->lock);
    if (m_shared->key == m_key)
        return;
    m_shared->key.fill(0);
    m_shared->key = m_key;
    m_shared->key.detach();   // its own copy: wiping one never touches the other
}

std::function<QByteArray(const QString &)> Vault::anyThreadReader() const
{
    const std::shared_ptr<Shared> shared = m_shared;
    const QString dir = dataDir();
    return [shared, dir](const QString &name) -> QByteArray {
        QByteArray key;
        {
            QMutexLocker l(&shared->lock);
            key = shared->key;
            key.detach();
        }
        if (key.isEmpty() || !Vault::safeName(name))
            return {};
        QFile f(dir + QLatin1Char('/') + name);
        if (!f.open(QIODevice::ReadOnly))
            return {};
        bool ok = false;
        const QByteArray plain = openSealed(key, f.readAll(), &ok);
        key.fill(0);
        return ok ? plain : QByteArray();
    };
}

void Vault::remove(const QString &name) const
{
    m_saves->waitFor(name, 10000);   // a save of it still waiting never brings it back
    QFile::remove(path(name));
}

std::function<QByteArray()> Vault::reader(const QString &name) const
{
    const QByteArray key = m_key;
    const QString file = path(name);
    return [key, file]() -> QByteArray {
        if (key.isEmpty())
            return {};
        QFile f(file);
        if (!f.open(QIODevice::ReadOnly))
            return {};
        bool ok = false;
        const QByteArray plain = openSealed(key, f.readAll(), &ok);
        return ok ? plain : QByteArray();
    };
}

// ------------------------------------------------------------------ panic lock

bool Vault::panicLock() const { return settings().value(QStringLiteral("chatlock/panic"), true).toBool(); }
void Vault::setPanicLock(bool on)
{
    settings().setValue(QStringLiteral("chatlock/panic"), on);
    Q_EMIT changed();
}
void Vault::panic()
{
    // Without a lock there is nothing to lock with: panic lock does nothing then.
    if (!panicLock() || !exists() || locked() || !hasLock())
        return;
    lockScreen();
    Q_EMIT panicked();
}

void Vault::button(const QString &name, bool down)
{
    // The controller reaches Huginn even while a game is in front: only presses made in Huginn count.
    if (qGuiApp && QGuiApplication::applicationState() != Qt::ApplicationActive) {
        m_l3 = m_r3 = false;
        m_combo.stop();
        return;
    }
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
// The plain "linked to a phone" note (Link::linkedWhileLocked) for what was just put back: set from the restored
// link.json (a phone there that isn't gone), so the next "Forgot it?" shows the right way back.
void noteLinkedFrom(const std::vector<std::pair<QString, QByteArray>> &files)
{
    bool linked = false;
    for (const auto &e : files) {
        if (e.first != QLatin1String("link.json"))
            continue;
        const QJsonObject phone = QJsonDocument::fromJson(e.second).object().value(QStringLiteral("phone")).toObject();
        linked = !phone.value(QStringLiteral("k")).toString().isEmpty() && !phone.value(QStringLiteral("gone")).toBool();
    }
    if (linked)
        settings().setValue(QStringLiteral("link/phone"), true);
    else
        settings().remove(QStringLiteral("link/phone"));
    settings().sync();
}
// v3 backups, what both apps write: scrypt with 64 MiB and two lanes (Muninn has no Argon2id), and the tag covers the
// header too. "@about.json" says which app and device made it; "@move.json" is the shared form (Move).
const quint64 kBackupN = 65536, kBackupR = 8, kBackupP = 2;
const QString kAbout = QStringLiteral("@about.json");
const QString kShared = QStringLiteral("@move.json");

// AES-256-GCM with additional data, in seal()'s layout: nonce, ciphertext, tag.
QByteArray sealWith(const QByteArray &key, const QByteArray &plain, const QByteArray &aad)
{
    const QByteArray iv = random(12);
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_EncryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(aad.constData()), int(aad.size()));
    EVP_EncryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(plain.constData()), int(plain.size()));
    total = len;
    EVP_EncryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len);
    total += len;
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total);
    EVP_CIPHER_CTX_free(c);
    out.resize(total + 16);
    return iv + out;
}

QByteArray openWith(const QByteArray &key, const QByteArray &data, const QByteArray &aad, bool *ok)
{
    *ok = false;
    if (key.size() != 32 || data.size() < 12 + 16)
        return {};
    const QByteArray iv = data.left(12), tag = data.right(16), ct = data.mid(12, data.size() - 28);
    QByteArray out(ct.size(), 0);
    int len = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_DecryptUpdate(c, nullptr, &len, reinterpret_cast<const unsigned char *>(aad.constData()), int(aad.size()));
    EVP_DecryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(ct.constData()), int(ct.size()));
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, const_cast<char *>(tag.constData()));
    const bool good = EVP_DecryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + len, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    *ok = good;
    return good ? out : QByteArray();
}

// The suggested name: "Huginn backup 2026-11-02.hmbak".
QString backupName() { return QStringLiteral("Huginn backup ") + QDate::currentDate().toString(Qt::ISODate) + QStringLiteral(".hmbak"); }

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

}

void Vault::restartHuginn(const QString &ifNot)
{
    // The new copy waits until this one has saved and closed: the two never use the files at the same time.
    if (SingleInstance::startAgain()) {
        QTimer::singleShot(300, qApp, [] { QCoreApplication::exit(0); });   // past the window's close question
        return;
    }
    m_busy = false;
    Q_EMIT changed();
    Q_EMIT notice(ifNot);
}

Vault::Files Vault::everything() const
{
    if (m_key.isEmpty())
        return {};
    if (beforeBackup)
        beforeBackup();
    drainSaves(30000);
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
    m_saves->dropAll(30000);   // the old chats' saves still waiting never land on the new files
    if (!replaceAll(m_key, dataDir(), files, error))
        return false;
    for (const auto &e : files)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    noteLinkedFrom(files);
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    QTimer::singleShot(3000, this, [this] { restartHuginn(QStringLiteral("Moved here. Close Huginn and open it again to load your chats.")); });
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
    // The folder a backup was last saved in or restored from, while it's still there; Documents at first.
    const QString last = Settings::saved(QStringLiteral("backupFolder"), QString()).toString();
    if (!last.isEmpty() && QFileInfo(last).isDir())
        return last;
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
    int v = 3;
    if (kdf == QLatin1String("pbkdf2")) {
        v = 1;
        h.insert(QStringLiteral("rounds"), kRounds);
        key = derive(password, salt, kRounds);
    } else {
        // v3: scrypt, which both apps have. "v2" (older, no header in the tag) only for the self-test.
        v = kdf == QLatin1String("v2") ? 2 : 3;
        const quint64 p = v == 2 ? 1 : kBackupP;
        key = scrypt(password.toUtf8(), salt, kBackupN, kBackupR, p);
        h.insert(QStringLiteral("kdf"), QStringLiteral("scrypt"));
        h.insert(QStringLiteral("N"), double(kBackupN));
        h.insert(QStringLiteral("r"), int(kBackupR));
        h.insert(QStringLiteral("p"), int(p));
    }
    h.insert(QStringLiteral("v"), v);
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
    out.append(v >= 3 ? sealWith(key, body, out) : seal(key, body));
    key.fill(0);
    body.fill(0);
    return out;
}

int Vault::unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error,
                        QJsonObject *header)
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
    if (v < 1 || v > 3) {
        *error = h.isEmpty() ? QStringLiteral("This backup is damaged.") : QStringLiteral("This backup is from a newer version. Update Huginn first.");
        return 1;
    }
    // The key's settings come from the unprotected header: a weakened one is refused rather than trusted.
    const QString kdf = h.value(QStringLiteral("kdf")).toString();
    const QByteArray salt = QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1());
    const qint64 rounds = h.value(QStringLiteral("rounds")).toInteger();
    if (salt.size() < 16 || (v == 1 && (rounds < 100000 || rounds > 10000000))
        || (v == 2 && kdf != QLatin1String("argon2id") && kdf != QLatin1String("scrypt")) || (v == 3 && kdf != QLatin1String("scrypt")))
        return damaged();
    if (created)
        *created = h.value(QStringLiteral("created")).toString();
    // A v3 backup opens in either app; an older one only where it was made.
    const QString from = h.value(QStringLiteral("app")).toString();
    if (v < 3 && from != app) {
        *error = from == QLatin1String("muninn") ? QStringLiteral("This older backup was made in Muninn. Restore it in Muninn.")
                                                 : QStringLiteral("This isn't a Huginn or Muninn backup.");
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
    QByteArray plain = v >= 3 ? openWith(key, bytes.mid(at), bytes.left(at), &ok) : open(key, bytes.mid(at), &ok);
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
    if (header)
        *header = h;
    return 0;
}

QVariantMap Vault::backupSummary(const Files &files, const QJsonObject &header)
{
    QJsonObject about, shared;
    int items = 0;
    for (const auto &e : files) {
        if (e.first == kAbout)
            about = QJsonDocument::fromJson(e.second).object();
        else if (e.first == kShared)
            shared = QJsonDocument::fromJson(e.second).object();
        else if (!e.first.startsWith(QLatin1Char('@')) && e.first != kSettingsEntry)
            ++items;
    }
    const auto count = [&shared](const QJsonValue &v) { return shared.isEmpty() ? -1 : int(v.isArray() ? v.toArray().size() : v.toObject().size()); };
    const QString app = about.value(QStringLiteral("app")).toString(header.value(QStringLiteral("app")).toString());
    return {{QStringLiteral("app"), app}, {QStringLiteral("device"), about.value(QStringLiteral("device")).toString()},
            {QStringLiteral("created"), about.value(QStringLiteral("created")).toString(header.value(QStringLiteral("created")).toString())},
            {QStringLiteral("v"), header.value(QStringLiteral("v")).toInt()},
            // "yes", "no", or "" when the backup doesn't say (older ones).
            {QStringLiteral("files"), about.contains(QStringLiteral("files")) ? (about.value(QStringLiteral("files")).toBool() ? QStringLiteral("yes") : QStringLiteral("no")) : QString()},
            {QStringLiteral("contacts"), count(shared.value(QStringLiteral("contacts")))}, {QStringLiteral("chats"), count(shared.value(QStringLiteral("chats")))},
            {QStringLiteral("groups"), count(shared.value(QStringLiteral("groups")))},
            {QStringLiteral("folders"), count(shared.value(QStringLiteral("folders")).toObject().value(QStringLiteral("folders")))},
            {QStringLiteral("items"), items}};
}

bool Vault::isMedia(const QString &name)
{
    return name.startsWith(QLatin1String("photos/")) || name.startsWith(QLatin1String("mirror-media/"));
}

QString Vault::keepCopy(const QString &dir)
{
    const QString kept = QDir::cleanPath(dir) + QStringLiteral("-kept-") + QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd-HHmmss"));
    if (!QDir().mkpath(kept))
        return {};
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString from = it.next();
        const QString to = kept + QLatin1Char('/') + base.relativeFilePath(from);
        QDir().mkpath(QFileInfo(to).absolutePath());
        if (!QFile::copy(from, to)) {
            QDir(kept).removeRecursively();
            return {};
        }
    }
    return kept;
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

QVariantMap Vault::backupInfo() const
{
    qint64 all = 0, media = 0;
    const QString dir = dataDir();
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString full = it.next();
        const qint64 n = it.fileInfo().size();
        all += n;
        if (isMedia(base.relativeFilePath(full)))
            media += n;
    }
    return {{QStringLiteral("name"), backupName()}, {QStringLiteral("folder"), QDir::toNativeSeparators(backupFolder())},
            {QStringLiteral("all"), double(all)}, {QStringLiteral("media"), double(media)}};
}

QString Vault::pickBackupSave(const QString &name) const
{
    QString f = QFileDialog::getSaveFileName(nullptr, QStringLiteral("Save the backup"),
                                             backupFolder() + QLatin1Char('/') + (name.isEmpty() ? backupName() : name),
                                             QStringLiteral("Backups (*.hmbak);;All files (*)"));
    if (f.isEmpty())
        return {};
    if (QFileInfo(f).suffix().isEmpty())
        f += QStringLiteral(".hmbak");
    Settings::store().setValue(QStringLiteral("backupFolder"), QFileInfo(f).absolutePath());
    return f;
}

QString Vault::pickBackupOpen() const
{
    // Any file, any name: a backup made in Muninn and copied over keeps whatever name it was given.
    const QString f = QFileDialog::getOpenFileName(nullptr, QStringLiteral("Choose the backup file"), backupFolder(),
                                                   QStringLiteral("All files (*);;Backups (*.hmbak *.mbk)"));
    if (!f.isEmpty())
        Settings::store().setValue(QStringLiteral("backupFolder"), QFileInfo(f).absolutePath());
    return f;
}

void Vault::backupTo(const QString &path, const QString &password, bool withFiles)
{
    if (m_busy)
        return;
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Unlock Huginn first."));
        return;
    }
    if (path.isEmpty()) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Choose where to save it."));
        return;
    }
    if (password.size() < kMinBackupPassword) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("The backup password needs at least %1 characters.").arg(kMinBackupPassword));
        return;
    }
    if (beforeBackup)
        beforeBackup();
    drainSaves(30000);   // the backup reads the files from disk
    const QByteArray key = m_key;
    const QString dir = dataDir();
    const QByteArray extra = settingsBlob();
    // Read here, on the window's thread: the shared form comes from Chat.
    const QByteArray shared = sharedForm ? sharedForm() : QByteArray();
    const QByteArray about = QJsonDocument(QJsonObject{{QStringLiteral("v"), 1}, {QStringLiteral("app"), QStringLiteral("huginn")},
                                                       {QStringLiteral("device"), deviceName ? deviceName() : QString()},
                                                       {QStringLiteral("created"), QDateTime::currentDateTimeUtc().toString(Qt::ISODate)},
                                                       {QStringLiteral("files"), withFiles}}).toJson(QJsonDocument::Compact);
    auto result = std::make_shared<QString>();
    auto good = std::make_shared<bool>(false);
    auto size = std::make_shared<qint64>(0);
    m_busy = true;
    m_lastBackup.clear();
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        Files all = collect(key, dir);
        Files files;
        for (auto &e : all)
            if (e.first != QLatin1String("move-out.bin") && (withFiles || !isMedia(e.first)))
                files.push_back(std::move(e));
        files.emplace_back(kSettingsEntry, extra);
        if (!shared.isEmpty())
            files.emplace_back(kShared, shared);
        files.emplace_back(kAbout, about);
        const QByteArray bytes = packBackup(password, files, QStringLiteral("huginn"));
        for (auto &e : files)
            e.second.fill(0);
        if (bytes.isEmpty()) {
            *result = QStringLiteral("Couldn't make the backup's key on this computer.");
            return;
        }
        QDir().mkpath(QFileInfo(path).absolutePath());
        QSaveFile f(path);
        if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size() || !f.commit()) {
            *result = QStringLiteral("Couldn't save it there. Is the disk full, or the folder read-only?");
            return;
        }
        *size = bytes.size();
        *good = true;
    });
    t->setObjectName(QStringLiteral("huginn-backup"));
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, result, good, size, path, withFiles] {
        t->deleteLater();
        if (!self)
            return;
        if (*good)
            self->m_lastBackup = {{QStringLiteral("path"), QDir::toNativeSeparators(QFileInfo(path).absoluteFilePath())},
                                  {QStringLiteral("folder"), QDir::toNativeSeparators(QFileInfo(path).absolutePath())},
                                  {QStringLiteral("size"), double(*size)}, {QStringLiteral("files"), withFiles}};
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("backup"), *good, *good ? QStringLiteral("Backup saved.") : *result);
    });
    t->start();
}

void Vault::openBackup(const QString &file, const QString &password)
{
    if (m_busy)
        return;
    forgetOpened();
    // First setup can restore before there is a lock, and the lock screen's "Forgot it?" behind one (the backup has its
    // own password; nothing here is read). Otherwise Huginn must be open.
    if (exists() && (locked() || screenLocked()) && !hasLock()) {
        Q_EMIT done(QStringLiteral("open"), false, QStringLiteral("Unlock Huginn first."));
        return;
    }
    auto files = std::make_shared<Files>();
    auto header = std::make_shared<QJsonObject>();
    auto result = std::make_shared<QString>();
    auto code = std::make_shared<int>(1);
    m_busy = true;
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        QFile f(file);
        if (!f.open(QIODevice::ReadOnly)) {
            *result = QStringLiteral("Couldn't open ") + QFileInfo(file).fileName() + QLatin1Char('.');
            return;
        }
        *code = unpackBackup(f.readAll(), password, QStringLiteral("huginn"), files.get(), nullptr, result.get(), header.get());
    });
    t->setObjectName(QStringLiteral("huginn-restore"));
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, files, header, result, code] {
        t->deleteLater();
        if (!self)
            return;
        if (*code == 0) {
            self->m_opened = std::move(*files);
            self->m_openedHeader = *header;
            self->m_openedInfo = backupSummary(self->m_opened, self->m_openedHeader);
        } else {
            self->m_openedInfo = {{QStringLiteral("error"), *code == 2 ? QStringLiteral("password") : QStringLiteral("other")}};
        }
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("open"), *code == 0, *code == 0 ? QString() : *code == 2 ? QStringLiteral("Wrong password. Try again.") : *result);
    });
    t->start();
}

void Vault::forgetOpened()
{
    for (auto &e : m_opened)
        e.second.fill(0);
    m_opened.clear();
    m_openedHeader = {};
    m_openedInfo.clear();
}

void Vault::restoreOpened()
{
    if (m_busy || m_opened.empty()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Open the backup again first."));
        return;
    }
    // First setup: no lock yet, so Huginn starts without one (a lock can be set in Settings after).
    if (!exists() && !startWithoutLock()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Couldn't start Huginn's storage. Nothing changed."));
        return;
    }
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Unlock Huginn first."));
        return;
    }
    QString why;
    Files files = openedFiles(&why);
    if (files.empty()) {
        Q_EMIT done(QStringLiteral("restore"), false, why);
        return;
    }
    // Nothing is lost: a copy of what's here now goes next to the chat folder first.
    drainSaves(30000);
    const QString kept = keepCopy(dataDir());
    if (kept.isEmpty()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Couldn't keep a copy of what's here, so nothing changed. Is the disk full?"));
        return;
    }
    QString error;
    m_saves->dropAll(30000);   // the old chats' saves still waiting never land on the restored files
    if (!replaceAll(m_key, dataDir(), files, &error)) {
        forgetOpened();
        Q_EMIT done(QStringLiteral("restore"), false, error);
        return;
    }
    for (const auto &e : files)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    noteLinkedFrom(files);
    for (auto &e : files)
        e.second.fill(0);
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
    Q_EMIT done(QStringLiteral("restore"), true, QStringLiteral("Restored. What was here is kept in ") + QDir::toNativeSeparators(kept)
                                                     + QStringLiteral(". Huginn restarts to load your chats."));
    QTimer::singleShot(1500, this, [this] { restartHuginn(QStringLiteral("Restored. Close Huginn and open it again to load your chats.")); });
}

Vault::Files Vault::openedFiles(QString *why) const
{
    // A Muninn backup: Huginn's own files made from its shared form (text, contacts, groups, folders, profile).
    Files files;
    if (m_openedInfo.value(QStringLiteral("app")).toString() != QLatin1String("huginn")) {
        QByteArray shared;
        for (const auto &e : m_opened)
            if (e.first == kShared)
                shared = e.second;
        QString ignored;
        if (!shared.isEmpty() && fromSharedForm)
            files = fromSharedForm(shared, &ignored);
    } else {
        for (const auto &e : m_opened)
            if (!e.first.startsWith(QLatin1Char('@')))
                files.push_back(e);
    }
    if (files.empty())
        *why = QStringLiteral("This backup has nothing Huginn can read. Nothing changed.");
    return files;
}

void Vault::restoreErasing()
{
    if (m_busy || m_opened.empty()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Open the backup again first."));
        return;
    }
    // Only from behind the lock: open, the usual restore keeps a copy instead (Settings › Backup).
    if (m_restarting || !exists() || !hasLock() || !screenLocked()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Huginn isn't locked. Use Settings › Backup instead."));
        return;
    }
    QString why;
    Files files = openedFiles(&why);
    if (files.empty()) {
        Q_EMIT done(QStringLiteral("restore"), false, why);
        return;
    }
    // What's here can't be opened without the lock: replaced for good, under a new data key. Saves still waiting
    // (made with the old key) never land on the restored files.
    m_saves->dropAll(30000);
    QByteArray fresh = random(32);
    QString error;
    if (!replaceAll(fresh, dataDir(), files, &error)) {
        fresh.fill(0);
        forgetOpened();
        Q_EMIT done(QStringLiteral("restore"), false, error);
        return;
    }
    // The backup has no lock in it: Huginn opens without one, and asks for a new one before anything shows.
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("open")}, {QStringLiteral("key"), QString::fromLatin1(fresh.toBase64())},
                        {QStringLiteral("needsLock"), true}};
    const bool keyOk = writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact));
    fresh.fill(0);
    if (!keyOk) {
        forgetOpened();
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Couldn't save Huginn's new key. Open the backup and try again."));
        return;
    }
    for (const auto &e : files)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    noteLinkedFrom(files);
    for (auto &e : files)
        e.second.fill(0);
    forgetOpened();
    // The wrong tries and their wait belonged to the old lock.
    settings().remove(QStringLiteral("vault"));
    settings().remove(QStringLiteral("chatlock/strongerAsked"));
    // As after any restore: a new day key is made and announced at the next start (see restoreOpened).
    settings().setValue(QStringLiteral("restore/at"), QDateTime::currentMSecsSinceEpoch());
    settings().setValue(QStringLiteral("restore/announce"), true);
    settings().sync();
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    Q_EMIT done(QStringLiteral("restore"), true, QStringLiteral("Restored. Huginn restarts. Set a new lock next: your old one isn't in the backup."));
    QTimer::singleShot(1500, this, [this] { restartHuginn(QStringLiteral("Restored. Close Huginn and open it again, then set a new lock.")); });
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
    check("header", lenOk && h.value(QStringLiteral("v")).toInt() == 3 && !h.contains(QStringLiteral("rounds"))
                        && kdf == QLatin1String("scrypt") && h.value(QStringLiteral("N")).toInteger() == qint64(kBackupN)
                        && h.value(QStringLiteral("r")).toInt() == int(kBackupR) && h.value(QStringLiteral("p")).toInt() == int(kBackupP)
                        && h.value(QStringLiteral("app")).toString() == QLatin1String("huginn")
                        && QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1()).size() == 16
                        && QDateTime::fromString(h.value(QStringLiteral("created")).toString(), Qt::ISODate).isValid());
    check("names and data are not readable", !bytes.contains("chats.json") && !bytes.contains(QByteArray(64, '\x5a')));

    Files back;
    QString created;
    check("wrong password refused", unpackBackup(bytes, QStringLiteral("wrong horse"), QStringLiteral("huginn"), &back, &created, &error) == 2 && back.empty());
    check("opens where Muninn reads it too", unpackBackup(bytes, QStringLiteral("correct horse"), QStringLiteral("muninn"), &back, &created, &error) == 0 && back == want);
    QByteArray changedBytes = bytes;
    changedBytes[changedBytes.size() - 20] = char(changedBytes.at(changedBytes.size() - 20) ^ 1);
    check("changed file refused", unpackBackup(changedBytes, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 2);
    {
        // The tag covers the header: a changed date (or app) is refused like a changed body.
        QByteArray changedHead = bytes;
        const qsizetype at = changedHead.indexOf("\"created\":\"") + 11;
        changedHead[at + 3] = char(changedHead.at(at + 3) == '9' ? '8' : changedHead.at(at + 3) + 1);
        QByteArray otherApp = bytes;
        otherApp.replace(otherApp.indexOf("\"huginn\""), 8, "\"muninn\"");
        check("changed header refused", unpackBackup(changedHead, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 2
                                            && unpackBackup(otherApp, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 2);
    }
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
        // And an older scrypt one (v2, the header outside the tag), only where it was made.
        const QByteArray v2 = packBackup(QStringLiteral("correct horse"), got, QStringLiteral("huginn"), QStringLiteral("v2"));
        quint32 l2 = 0;
        const QJsonObject h2 = get32(v2, 5, &l2) ? QJsonDocument::fromJson(v2.mid(9, l2)).object() : QJsonObject();
        Files v2back;
        check("older scrypt backup restores", h2.value(QStringLiteral("v")).toInt() == 2
                                                  && unpackBackup(v2, QStringLiteral("correct horse"), QStringLiteral("huginn"), &v2back, &created, &error) == 0
                                                  && v2back == want
                                                  && unpackBackup(v2, QStringLiteral("correct horse"), QStringLiteral("muninn"), &v2back, &created, &error) == 3);
    }
    // What the screen before Restore shows, from @about.json and @move.json; older backups have no list.
    {
        Files withAbout = want;
        withAbout.emplace_back(kAbout, QByteArrayLiteral("{\"v\":1,\"app\":\"muninn\",\"device\":\"Pixel\",\"created\":\"2026-11-02T10:00:00Z\",\"files\":false}"));
        withAbout.emplace_back(kShared, QByteArrayLiteral("{\"v\":1,\"contacts\":[{},{}],\"chats\":{\"a\":[]},\"groups\":[],\"folders\":{\"folders\":[{}],\"top\":[]}}"));
        const QVariantMap m = backupSummary(withAbout, h);
        check("summary", m.value(QStringLiteral("app")).toString() == QLatin1String("muninn") && m.value(QStringLiteral("device")).toString() == QLatin1String("Pixel")
                             && m.value(QStringLiteral("files")).toString() == QLatin1String("no") && m.value(QStringLiteral("contacts")).toInt() == 2
                             && m.value(QStringLiteral("chats")).toInt() == 1 && m.value(QStringLiteral("groups")).toInt() == 0
                             && m.value(QStringLiteral("folders")).toInt() == 1 && m.value(QStringLiteral("items")).toInt() == int(want.size())
                             && m.value(QStringLiteral("v")).toInt() == 3);
        const QVariantMap old = backupSummary(want, h);
        check("older summary has no list", old.value(QStringLiteral("contacts")).toInt() == -1 && old.value(QStringLiteral("files")).toString().isEmpty()
                                               && old.value(QStringLiteral("app")).toString() == QLatin1String("huginn"));
        check("files and pictures", isMedia(QStringLiteral("photos/abc")) && isMedia(QStringLiteral("mirror-media/x")) && !isMedia(QStringLiteral("chats.json")));
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
    // Before a restore, a copy of what's there is kept beside it (the lock too), and it still opens.
    const QString keptDir = keepCopy(dst);
    QFile keptKey(keptDir + QStringLiteral("/vault.key"));
    check("copy kept first", !keptDir.isEmpty() && keptDir.startsWith(dst + QStringLiteral("-kept-")) && keptKey.open(QIODevice::ReadOnly)
                                 && keptKey.readAll() == "other lock" && collect(keyB, keptDir) == want);
    check("unsafe names refused", !safeName(QStringLiteral("../x")) && !safeName(QStringLiteral("/etc/x")) && !safeName(QStringLiteral("a//b"))
                                      && safeName(QStringLiteral("photos/abc")));
    return all;
}

bool Vault::backupVectorTest(const QString &path)
{
    bool all = true;
    const auto check = [&all](const QString &what, bool ok) {
        std::printf("backup vectors %s: %s\n", qPrintable(what), ok ? "ok" : "FAILED");
        all &= ok;
    };
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        check(QStringLiteral("read ") + path, false);
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    const QString pw = v.value(QStringLiteral("password")).toString(), wrong = v.value(QStringLiteral("wrong")).toString();
    const QJsonObject sum = v.value(QStringLiteral("summary")).toObject();
    const auto fileOf = [&v](const char *k) { return QByteArray::fromBase64(v.value(QLatin1String(k)).toObject().value(QStringLiteral("file")).toString().toLatin1()); };
    const auto wantOf = [&v](const char *k) {
        Files out;
        for (const QJsonValue &e : v.value(QLatin1String(k)).toObject().value(QStringLiteral("files")).toArray())
            out.emplace_back(e.toObject().value(QStringLiteral("name")).toString(),
                             QByteArray::fromBase64(e.toObject().value(QStringLiteral("data")).toString().toLatin1()));
        return out;
    };
    QString error, created;
    // Made as Huginn and as Muninn: both open here, the same files come out, with the same summary.
    for (const char *k : {"v3huginn", "v3muninn"}) {
        const QJsonObject e = v.value(QLatin1String(k)).toObject();
        Files got;
        QJsonObject header;
        const bool opens = unpackBackup(fileOf(k), pw, QStringLiteral("huginn"), &got, &created, &error, &header) == 0 && got == wantOf(k);
        check(QLatin1String(k) + QStringLiteral(" opens"), opens);
        const QVariantMap m = backupSummary(got, header);
        check(QLatin1String(k) + QStringLiteral(" summary"),
              m.value(QStringLiteral("app")).toString() == e.value(QStringLiteral("app")).toString()
                  && m.value(QStringLiteral("device")).toString() == e.value(QStringLiteral("device")).toString()
                  && m.value(QStringLiteral("created")).toString() == sum.value(QStringLiteral("created")).toString()
                  && m.value(QStringLiteral("contacts")).toInt() == sum.value(QStringLiteral("contacts")).toInt()
                  && m.value(QStringLiteral("chats")).toInt() == sum.value(QStringLiteral("chats")).toInt()
                  && m.value(QStringLiteral("groups")).toInt() == sum.value(QStringLiteral("groups")).toInt()
                  && m.value(QStringLiteral("folders")).toInt() == sum.value(QStringLiteral("folders")).toInt()
                  && m.value(QStringLiteral("items")).toInt() == sum.value(QStringLiteral("items")).toInt()
                  && m.value(QStringLiteral("files")).toString() == QLatin1String("yes"));
        Files none;
        check(QLatin1String(k) + QStringLiteral(" wrong password refused"), unpackBackup(fileOf(k), wrong, QStringLiteral("huginn"), &none, &created, &error) == 2);
        QByteArray changed = fileOf(k);
        changed[changed.size() - 20] = char(changed.at(changed.size() - 20) ^ 1);
        check(QLatin1String(k) + QStringLiteral(" changed file refused"), unpackBackup(changed, pw, QStringLiteral("huginn"), &none, &created, &error) == 2);
        QByteArray head = fileOf(k);
        head.replace(head.indexOf("2026-11-02"), 4, "2027");
        check(QLatin1String(k) + QStringLiteral(" changed header refused"), unpackBackup(head, pw, QStringLiteral("huginn"), &none, &created, &error) == 2);
    }
    // The old format: an older Huginn backup opens; an older Muninn one says to restore it in Muninn.
    Files old;
    check(QStringLiteral("older Huginn backup opens"), unpackBackup(fileOf("v2huginn"), pw, QStringLiteral("huginn"), &old, &created, &error) == 0 && old == wantOf("v2huginn"));
    check(QStringLiteral("older Muninn backup goes to Muninn"), unpackBackup(fileOf("v1muninn"), pw, QStringLiteral("huginn"), &old, &created, &error) == 3
                                                                    && error.contains(QLatin1String("Muninn")));
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

bool Vault::stabilitySelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("vault %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    // An old "Lock when I leave" setting: dropped, and Huginn never locks on its own.
    settings().setValue(QStringLiteral("chatlock/leave"), true);
    Vault v;
    check("an old Lock when I leave setting is dropped", !settings().contains(QStringLiteral("chatlock/leave")));
    if (!v.exists())
        v.setSecret(QStringLiteral("stability self-test only"), false);
    check("opens on the throwaway profile", !v.locked());
    if (v.locked())
        return false;
    const QString name = QStringLiteral("selftest-groups.json");
    check("writes a file", v.write(name, QByteArrayLiteral("{\"groups\":1}")));
    ReadStatus st = ReadMissing;
    check("reads it back", v.read(name, &st) == QByteArrayLiteral("{\"groups\":1}") && st == ReadOk);

    // Damaged on disk (or another program's file): read fails, and nothing is saved over it.
    const QByteArray damaged = kMagic + QByteArrayLiteral("not sealed with this key");
    {
        QFile f(v.path(name));
        if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
            f.write(damaged);
    }
    const QByteArray got = v.read(name, &st);
    check("a damaged file reads as failed, not missing", got.isEmpty() && st == ReadFailed);
    check("a damaged file is held back", v.blocked(name) && v.unreadable());
    check("an empty load is never saved over it", !v.write(name, QByteArrayLiteral("{}")));
    {
        QFile f(v.path(name));
        const bool same = f.open(QIODevice::ReadOnly) && f.readAll() == damaged;
        check("the file on disk is untouched", same);
    }
    check("Try again doesn't pass while it still can't be read", !v.tryReadAgain());

    // Missing is not failed: the first save of a new file goes through.
    const QString fresh = QStringLiteral("selftest-new.json");
    v.remove(fresh);
    v.read(fresh, &st);
    check("a missing file reads as missing", st == ReadMissing && !v.blocked(fresh));
    check("a missing file can be saved", v.write(fresh, QByteArrayLiteral("[]")));

    // Readable again (the other program let go): read works and it can be saved again.
    {
        QSaveFile f(v.path(name));
        if (f.open(QIODevice::WriteOnly)) {
            f.write(kMagic + seal(v.m_key, QByteArrayLiteral("{\"groups\":2}")));
            f.commit();
        }
    }
    check("reads again once readable", v.read(name, &st) == QByteArrayLiteral("{\"groups\":2}") && st == ReadOk);
    check("saves again once read", !v.blocked(name) && v.write(name, QByteArrayLiteral("{\"groups\":3}")));

    // The image providers' reader: the same files from another thread, with the key copied for it.
    QByteArray fromThread;
    const auto readAny = v.anyThreadReader();
    QThread *t = QThread::create([&] { fromThread = readAny(name); });
    t->start();
    t->wait(5000);
    delete t;
    check("reads from another thread without the main thread", fromThread == QByteArrayLiteral("{\"groups\":3}"));

    v.remove(name);
    v.remove(fresh);

    // Locking: only when asked (Lock now, the panic lock, Lock and close).
    check("not locked on its own", !v.screenLocked());
    v.lockScreen();
    check("locks when asked", v.screenLocked() == v.hasLock());
    return all;
}

bool Vault::saveQueueSelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("perf self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        check("a temporary folder", false);
        return false;
    }
    const QByteArray key = random(32);
    const auto readBack = [&key](const QString &file) {
        QFile f(file);
        if (!f.open(QIODevice::ReadOnly))
            return QByteArray();
        bool ok = false;
        const QByteArray plain = openSealed(key, f.readAll(), &ok);
        return ok ? plain : QByteArray();
    };
    const auto job = [&](SaveQueue &q, const QString &name, const QByteArray &data) {
        SaveQueue::Job j;
        j.name = name;
        j.file = tmp.filePath(name);
        j.key = key;
        j.make = [data] { return data; };
        q.put(std::move(j));
    };
    QThread *const gui = QThread::currentThread();

    // Order per file: many saves of two files, interleaved; each file ends with its last save, never an older one.
    {
        SaveQueue q;
        q.start();
        std::atomic<QThread *> madeOn{nullptr};
        for (int i = 1; i <= 200; ++i) {
            job(q, QStringLiteral("a.json"), "a" + QByteArray::number(i));
            job(q, QStringLiteral("b.json"), "b" + QByteArray::number(i));
        }
        SaveQueue::Job j;
        j.name = QStringLiteral("c.json");
        j.file = tmp.filePath(j.name);
        j.key = key;
        j.make = [&madeOn] { madeOn = QThread::currentThread(); return QByteArray("c"); };
        q.put(std::move(j));
        const bool drained = q.waitFor(QString(), 10000);
        check("saves of a file are written in order (the last one wins)",
              drained && readBack(tmp.filePath(QStringLiteral("a.json"))) == "a200" && readBack(tmp.filePath(QStringLiteral("b.json"))) == "b200");
        check("files are made and sealed on the saving thread", madeOn.load() && madeOn.load() != gui);
        check("the saving thread stops", q.finish(5000));
    }

    // A flush waits until the queue is empty: slow writes are on disk when waitFor returns, and a short limit says
    // when it isn't yet.
    {
        s_slowWrites = 150;
        SaveQueue q;
        q.start();
        job(q, QStringLiteral("slow1.json"), "one");
        job(q, QStringLiteral("slow2.json"), "two");
        const bool early = q.waitFor(QString(), 20);
        QElapsedTimer t;
        t.start();
        const bool drained = q.waitFor(QString(), 10000);
        check("a flush waits for the queue to drain (and says when time ran out)",
              !early && drained && t.elapsed() >= 100 && readBack(tmp.filePath(QStringLiteral("slow1.json"))) == "one"
                  && readBack(tmp.filePath(QStringLiteral("slow2.json"))) == "two");
        job(q, QStringLiteral("slow3.json"), "three");
        check("waiting for one file waits for its save", q.waitFor(QStringLiteral("slow3.json"), 10000) && readBack(tmp.filePath(QStringLiteral("slow3.json"))) == "three");
        q.finish(5000);
        s_slowWrites = 0;
    }

    // Closing: everything still waiting is written before the thread ends.
    {
        s_slowWrites = 20;
        SaveQueue q;
        q.start();
        for (int i = 0; i < 10; ++i)
            job(q, QStringLiteral("exit%1.json").arg(i), "x" + QByteArray::number(i));
        const bool ended = q.finish(10000);
        bool every = true;
        for (int i = 0; i < 10; ++i)
            every = every && readBack(tmp.filePath(QStringLiteral("exit%1.json").arg(i))) == "x" + QByteArray::number(i);
        check("nothing waiting is lost when the saving thread stops", ended && every);
        s_slowWrites = 0;
    }

    // Dropped saves (Erase everything, a restore) never land afterwards.
    {
        s_slowWrites = 100;
        SaveQueue q;
        q.start();
        job(q, QStringLiteral("drop1.json"), "1");
        job(q, QStringLiteral("drop2.json"), "2");
        job(q, QStringLiteral("drop3.json"), "3");
        QThread::msleep(30);
        q.dropAll(10000);
        q.finish(5000);
        check("dropped saves are never written", !QFile::exists(tmp.filePath(QStringLiteral("drop3.json"))));
        s_slowWrites = 0;
    }
    return all;
}
