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
#include <QStorageInfo>
#include <QTemporaryDir>
#include <QThread>
#include <QVariant>
#include <QLocale>
#include <QDeadlineTimer>
#include <QMutex>
#include <QMutexLocker>
#include <QWaitCondition>
#include <algorithm>
#include <iterator>
#include <limits>
#include <atomic>
#include <cstdio>
#include <memory>

#include <QElapsedTimer>
#include <QEventLoop>
#include <QCryptographicHash>
#include <QSysInfo>

#include <openssl/core_names.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/opensslv.h>
#include <openssl/params.h>
#include <openssl/rand.h>
#if OPENSSL_VERSION_NUMBER >= 0x30200000L
#include <openssl/thread.h>
#endif

#if defined(Q_OS_LINUX)
#include <time.h>
#elif defined(Q_OS_WIN)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace
{
const QByteArray kMagic = QByteArrayLiteral("MUNV1");
const int kRounds = 310000;
// New locks: at least this many pattern dots or password characters. Shorter ones already set keep working.
const int kMinSecret = 6;
// Round 19 (security 5): new wraps on a PC: Argon2id with 256 MiB, 3 passes, 4 lanes (worked on 4 threads where this
// OpenSSL can, so it stays about a second); four times the memory of 1.42's first locks, which makes each offline guess
// that much dearer. A machine that can't spare it falls back to the old 64 MiB. Wraps made before keep their own
// numbers (they are in vault.key) and open as before; the lock screen makes them again with these once it opens
// (lockIsOld). No new minimum length.
const quint32 kArgonMem = 262144, kArgonIter = 3, kArgonLanes = 4;
const quint32 kArgonMemLow = 65536;
// scrypt, when this OpenSSL has no Argon2id: N = 2^18, r = 8, p = 1 (256 MiB; 1.42's first locks used 2^17).
const quint64 kScryptN = quint64(1) << 18;
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
    // Round 19: the lanes worked on side by side where this OpenSSL has a thread pool (the result is the same either
    // way); one thread when it hasn't, or when its threads are busy.
    quint32 threads = 1;
#if OPENSSL_VERSION_NUMBER >= 0x30200000L
    static const bool pool = OSSL_set_max_threads(nullptr, 4) == 1;
    if (pool)
        threads = qMin<quint32>(lanes, 4);
#endif
    QByteArray p = pw, sa = salt, se = secret, a = ad;
    QByteArray out(len, 0);
    bool ok = false;
    for (;;) {
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
        ok = EVP_KDF_derive(ctx, reinterpret_cast<unsigned char *>(out.data()), size_t(len), params) == 1;
        if (ok || threads == 1)
            break;
        ERR_clear_error();
        threads = 1;   // the pool was busy or missing: the same work on one thread
    }
    if (!ok)
        ERR_clear_error();
    EVP_KDF_CTX_free(ctx);
    p.fill(0);
    return ok ? out : QByteArray();
}

bool hasArgon2id()
{
    static const bool there = [] {
        EVP_KDF *kdf = EVP_KDF_fetch(nullptr, "ARGON2ID", nullptr);
        const bool found = kdf != nullptr;
        EVP_KDF_free(kdf);
        return found;
    }();
    return there;
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

// 1.42: the wrap a secret is for. A drawn pattern ("pattern:...") opens the lock's own wrap; with a pattern lock,
// anything typed is the unlock password and opens only its wrap ("spare"; a pattern set before 1.42 has none: its own
// wrap then). Only one wrap is ever tried: no double wait, nothing to learn from timing.
QJsonObject wrapFor(const QJsonObject &o, const QString &secret)
{
    if (o.value(QStringLiteral("kind")).toString() == QLatin1String("pattern") && !secret.startsWith(QLatin1String("pattern:"))
        && o.value(QStringLiteral("spare")).isObject())
        return o.value(QStringLiteral("spare")).toObject();
    return o;
}

// The vault key from a lock file and its secret. Only the lock guards it: nothing outside Huginn is asked.
QByteArray openLock(const QJsonObject &o, const QString &secret, bool *ok)
{
    *ok = false;
    const QJsonObject w = wrapFor(o, secret);
    QByteArray kek = lockKey(w, secret);
    const QByteArray wrapped = QByteArray::fromBase64(w.value(QStringLiteral("wrapped")).toString().toLatin1());
    const QByteArray key = kek.isEmpty() || wrapped.isEmpty() ? QByteArray() : Vault::open(kek, wrapped, ok);
    kek.fill(0);
    return key;
}

// "pattern:0,3,6,..." counts dots; a password counts characters.
bool strongSecret(const QString &secret)
{
    if (secret.startsWith(QLatin1String("pattern:")))
        return secret.mid(8).split(QLatin1Char(','), Qt::SkipEmptyParts).size() >= kMinSecret;
    return secret.size() >= kMinSecret;
}

// Round 19: a wrap made with less than new ones get (an older lock, or 1.42's first ones): made again once it opens.
bool lockIsOld(const QJsonObject &w)
{
    const QString kdf = w.value(QStringLiteral("kdf")).toString();
    if (kdf == QLatin1String("argon2id"))
        return w.value(QStringLiteral("m")).toInt() < int(kArgonMem) || w.value(QStringLiteral("t")).toInt() < int(kArgonIter);
    if (kdf == QLatin1String("scrypt"))
        return hasArgon2id() || quint64(w.value(QStringLiteral("N")).toDouble()) < kScryptN;
    return true;   // PBKDF2
}

// The wrong-try wait runs on the time since start-up, which a changed clock doesn't move. Round 19 (M2 handheld, m3
// Linux): the clock that also counts sleep (Linux's CLOCK_BOOTTIME, Windows' tick count), so a handheld or laptop
// that sleeps through a wait finds it over, as a phone does.
qint64 sinceStart()
{
#if defined(Q_OS_LINUX)
    timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) == 0)
        return qint64(ts.tv_sec) * 1000 + qint64(ts.tv_nsec) / 1000000;
#elif defined(Q_OS_WIN)
    return qint64(GetTickCount64());
#endif
    QElapsedTimer t;
    t.start();
    return t.msecsSinceReference();
}

// Round 19 (M3 Windows): which computer, and which start of it, the time since start-up belongs to (a hash, never the
// ids themselves). A portable folder carried to another computer, or a restart, has another: its waits go on from what
// was left, never from that computer's own time since start-up.
QString bootTag()
{
    static const QString tag = QString::fromLatin1(
        QCryptographicHash::hash(QSysInfo::machineUniqueId() + QByteArrayLiteral("|") + QSysInfo::bootUniqueId(), QCryptographicHash::Sha256).toHex().left(16));
    return tag;
}

// Held while a restore (or a new lock) swaps the chat folder: nothing may make an empty "chat" in the moment it's
// away (dataDir() and the saving thread wait for it), or the swap couldn't finish (round 15, R6). Recursive: the
// thread swapping may ask for dataDir() itself.
QRecursiveMutex s_swap;

QString dataDir()
{
    const QString dir = System::dataDir() + QStringLiteral("/chat");
    QMutexLocker hold(&s_swap);
    QDir().mkpath(dir);
    return dir;
}

// A "chat" folder with no vault.key and no restore mark: one something made again while it was away (it holds
// nothing of anyone's), never a folder of chats.
bool strayChat(const QString &dir)
{
    return QFileInfo(dir).isDir() && !QFileInfo::exists(dir + QStringLiteral("/vault.key")) && !QFileInfo::exists(dir + QStringLiteral("/.restore-complete"));
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

// ---------------------------------------------------------------- 1.42: wrong tries at the lock
// The count and its wait, in the lock-state file beside the chat folder (never in a backup, a move or a restore's swap)
// and mirrored in settings ("vault/..."): the higher count of the two is used, so removing one doesn't reset it.
// Anyone who can copy or change Huginn's files can still set them back (and guess against a copy of vault.key without
// Huginn at all): the waits and the erase guard against guessing at the screen, not against a copied folder.
struct LockState
{
    int fails = 0;
    qint64 at = 0;        // the wait ends here, in time since start-up (ms) of the start named by boot
    qint64 wait = 0;      // how long it is (ms); 0: none
    qint64 started = 0;   // clock minus time since start-up, the last time it was looked at
    bool erase = false;   // Erase after too many tries
    // Round 19 (FIX 14): what was left of the wait the last time it was saved (-1: not known, a file from before), the
    // clock time it ends (ms since 1970), and the start of the computer it was counted on (bootTag).
    qint64 left = -1;
    qint64 end = 0;
    QString boot;
};

QString lockStatePath() { return System::dataDir() + QStringLiteral("/lock-state.json"); }

bool readLockFile(const QString &file, LockState *s)
{
    QFile f(file);
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    if (!o.value(QStringLiteral("fails")).isDouble())
        return false;
    s->fails = qMax(0, o.value(QStringLiteral("fails")).toInt());
    s->at = qint64(o.value(QStringLiteral("at")).toDouble());
    s->wait = qMax<qint64>(0, qint64(o.value(QStringLiteral("wait")).toDouble()));
    s->started = qint64(o.value(QStringLiteral("started")).toDouble());
    s->erase = o.value(QStringLiteral("erase")).toBool();
    s->left = o.value(QStringLiteral("left")).isDouble() ? qint64(o.value(QStringLiteral("left")).toDouble()) : -1;
    s->end = qint64(o.value(QStringLiteral("end")).toDouble());
    s->boot = o.value(QStringLiteral("boot")).toString();
    return true;
}

// Written in one step (a temporary file flushed to the disk, then renamed), before it returns.
bool writeLockFile(const QString &file, const LockState &s)
{
    const QJsonObject o{{QStringLiteral("fails"), s.fails}, {QStringLiteral("at"), double(s.at)}, {QStringLiteral("wait"), double(s.wait)},
                        {QStringLiteral("started"), double(s.started)}, {QStringLiteral("erase"), s.erase}, {QStringLiteral("left"), double(s.left)},
                        {QStringLiteral("end"), double(s.end)}, {QStringLiteral("boot"), s.boot}};
    const QByteArray bytes = QJsonDocument(o).toJson(QJsonDocument::Compact);
    QSaveFile f(file);
    if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size())
        return false;
    return f.commit();
}

LockState fromStore(QSettings &st, bool *there)
{
    LockState s;
    *there = st.contains(QStringLiteral("vault/fails")) || st.contains(QStringLiteral("vault/erase"));
    if (!*there)
        return s;
    s.fails = qMax(0, st.value(QStringLiteral("vault/fails"), 0).toInt());
    s.at = st.value(QStringLiteral("vault/at"), 0).toLongLong();
    s.wait = qMax<qint64>(0, st.value(QStringLiteral("vault/wait"), 0).toLongLong());
    s.started = st.value(QStringLiteral("vault/started"), 0).toLongLong();
    s.erase = st.value(QStringLiteral("vault/erase"), false).toBool();
    s.left = st.value(QStringLiteral("vault/left"), -1).toLongLong();
    s.end = st.value(QStringLiteral("vault/end"), 0).toLongLong();
    s.boot = st.value(QStringLiteral("vault/boot")).toString();
    return s;
}

void toStore(QSettings &st, const LockState &s)
{
    st.setValue(QStringLiteral("vault/fails"), s.fails);
    st.setValue(QStringLiteral("vault/at"), s.at);
    st.setValue(QStringLiteral("vault/wait"), s.wait);
    st.setValue(QStringLiteral("vault/started"), s.started);
    st.setValue(QStringLiteral("vault/erase"), s.erase);
    st.setValue(QStringLiteral("vault/left"), s.left);
    st.setValue(QStringLiteral("vault/end"), s.end);
    st.setValue(QStringLiteral("vault/boot"), s.boot);
    st.sync();
}

LockState loadLock()
{
    LockState fromFile;
    const bool file = readLockFile(lockStatePath(), &fromFile);
    bool mirror = false;
    const LockState fromSettings = fromStore(settings(), &mirror);
    if (!file)
        return fromSettings;
    if (!mirror)
        return fromFile;
    LockState best = fromSettings.fails > fromFile.fails ? fromSettings : fromFile;
    best.erase = fromFile.erase || fromSettings.erase;
    return best;
}

// Round 19 (FIX 15): true only when both copies reached the disk. A try is never checked uncounted (beginTry).
bool saveLock(const LockState &s)
{
    const bool file = writeLockFile(lockStatePath(), s);
    if (!file)
        qWarning("vault: couldn't write the lock-state file");
    QSettings &st = settings();
    toStore(st, s);
    // status() keeps the first error for good, so whether the file takes writes is asked now.
    const bool mirror = st.isWritable();
    if (!mirror)
        qWarning("vault: couldn't write the lock state to the settings");
    return file && mirror;
}

// Round 19 (FIX 12): a right try clears the count on the thread that found it right, before anything else, so closing
// Huginn in that moment (or the answer never reaching the window) can't leave it counted as wrong. Its own QSettings
// (they may be used side by side on different threads).
bool clearCountNow(bool erase)
{
    LockState fresh;
    fresh.erase = erase;
    const bool file = writeLockFile(lockStatePath(), fresh);
    QSettings st = Settings::store();
    toStore(st, fresh);
    return file && st.isWritable();
}

// Ms left of s's wait at mono (time since start-up, sleep included) and clock (ms since 1970), on the start of the
// computer named boot. *moved: s changed (save it).
// - The same start: the time since start-up says, and a changed clock changes nothing.
// - Another start (a restart, the folder on another computer, or the clock moved by more than two minutes): it goes on
//   from what was left when it was last saved, or from when the clock says it ends, whichever is longer. Never from 0.
// While it runs, what's left is noted at least every 30 seconds (save it), so a restart loses little.
// A wait that's over is forgotten.
qint64 lockLeft(LockState *s, qint64 mono, qint64 clock, const QString &boot, bool *moved)
{
    *moved = false;
    if (s->wait <= 0)
        return 0;
    const bool same = !s->boot.isEmpty() && s->boot == boot && mono >= s->at - s->wait && qAbs((clock - mono) - s->started) <= 2 * 60000;
    qint64 left;
    if (same) {
        left = qBound<qint64>(0, s->at - mono, s->wait);
    } else {
        const qint64 kept = s->left >= 0 ? qMin(s->left, s->wait) : s->wait;
        const qint64 byClock = s->end > 0 ? qBound<qint64>(0, s->end - clock, s->wait) : 0;
        left = qMax(kept, byClock);
        s->at = mono + left;
        s->started = clock - mono;
        s->boot = boot;
        s->left = left;
        s->end = clock + left;
        *moved = true;
    }
    if (left == 0) {
        s->at = 0;
        s->wait = 0;
        s->left = -1;
        s->end = 0;
        *moved = true;
    } else if (s->left < 0 || s->left - left >= 30000) {
        s->left = left;
        s->end = clock + left;
        *moved = true;
    }
    return left;
}

// A try counted before its check: the new state (and the wait it brings, from now). *lockApp: an in-app try stopped
// one short of the erase.
LockState countedTry(LockState s, bool inApp, qint64 mono, qint64 clock, const QString &boot, bool *lockApp)
{
    const int n = Vault::nextFails(s.fails, s.erase, inApp, lockApp);
    const qint64 w = n != s.fails ? Vault::waitAfter(n, s.erase) : 0;
    s.fails = n;
    if (w > 0) {
        s.wait = w;
        s.at = mono + w;
        s.started = clock - mono;
        s.left = w;
        s.end = clock + w;
        s.boot = boot;
    }
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
    // anyThreadWriter: held while one writes a file; gen goes up on every key change and when sealing for a new lock
    // begins, so a writer made before then writes nothing more.
    QMutex writing;
    int gen = 0;
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
        int tries = 0;             // failed writes so far
        qint64 notBefore = 0;      // a retry waits until then (steadyMs())
    };
    SaveQueue() { setObjectName(QStringLiteral("huginn-saving")); }
    // A file that still couldn't be written after every retry (called on the saving thread).
    std::function<void(const QString &)> lost;
    // Waits between the tries of a file that couldn't be written (another program holding it, a full disk).
    static constexpr int kRetryMs[] = {1000, 3000, 10000};
    static qint64 steadyMs() { return QDeadlineTimer::current().deadline(); }

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
                for (;;) {
                    if (m_jobs.empty()) {
                        if (m_stopping)
                            return;   // stopping, and nothing left
                        m_wake.wait(&m_lock);
                        continue;
                    }
                    // The first one due (a retry waits its turn; closing, nothing waits).
                    const qint64 now = steadyMs();
                    auto due = std::find_if(m_jobs.begin(), m_jobs.end(), [&](const Job &j) { return m_stopping || j.notBefore <= now; });
                    if (due != m_jobs.end()) {
                        job = std::move(*due);
                        m_jobs.erase(due);
                        break;
                    }
                    qint64 soonest = std::numeric_limits<qint64>::max();
                    for (const Job &j : m_jobs)
                        soonest = std::min(soonest, j.notBefore);
                    m_wake.wait(&m_lock, QDeadlineTimer(std::max<qint64>(1, soonest - now)));
                }
                m_current = job.name;
            }
            const bool ok = writeNow(job);
            QMutexLocker l(&m_lock);
            m_current.clear();
            if (!ok) {
                ++m_failures;
                // Tried again after a while, unless a newer save of the same file is already waiting (it replaces this).
                const bool newer = std::any_of(m_jobs.begin(), m_jobs.end(), [&](const Job &j) { return j.name == job.name; });
                if (!newer && job.tries < int(std::size(kRetryMs))) {
                    job.notBefore = steadyMs() + (s_quick() ? 20 : kRetryMs[job.tries]);
                    ++job.tries;
                    m_jobs.push_back(std::move(job));
                } else if (!newer && lost) {
                    // Given up: said on screen, and what it held is saved again from memory later (Chat).
                    const QString name = job.name;
                    l.unlock();
                    lost(name);
                    l.relock();
                }
            }
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
            {
                // Never while the chat folder is being swapped (it would make an empty one in its place).
                QMutexLocker hold(&s_swap);
                QDir().mkpath(QFileInfo(job.file).absolutePath());
                QSaveFile f(job.file);
                if (f.open(QIODevice::WriteOnly) && f.write(sealed) >= 0 && f.commit())
                    return true;
            }
            QThread::msleep(150);
        }
        qWarning("vault: the saving thread couldn't write a file");
        Busy::count("saves that couldn't be written");
        return false;
    }
    static int s_slow();
    static bool s_quick();

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
std::atomic<bool> s_quickRetries{false};   // --perf-selftest only: retries a moment apart
}
int SaveQueue::s_slow() { return s_slowWrites.load(); }
bool SaveQueue::s_quick() { return s_quickRetries.load(); }

Vault::Vault(QObject *parent)
    : QObject(parent), m_shared(std::make_shared<Shared>())
{
    // Round 19 (FIX 16): an erase that left something behind (a file another program held) is finished first.
    finishErase();
    // A restore cut off last time (killed, power lost) is finished or undone before anything reads the chats.
    if (const QString said = finishSwap(System::dataDir() + QStringLiteral("/chat")); !said.isEmpty())
        QTimer::singleShot(3000, this, [this, said] { Q_EMIT notice(said); });
    m_saves = new SaveQueue;
    // A save that still couldn't be written after its retries: said on the window's thread (Chat saves it again).
    m_saves->lost = [this](const QString &name) {
        QMetaObject::invokeMethod(this, [this, name] { Q_EMIT saveLost(name); }, Qt::QueuedConnection);
    };
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
    // 1.42: Erase after too many tries was due when Huginn stopped (killed in the middle of the last check, which counted
    // first): it was wrong, so everything goes now, once everything listens for erased().
    QTimer::singleShot(0, this, [this] {
        const LockState st = loadLock();
        if (exists() && hasLock() && erasesAt(st.fails, st.erase))
            eraseForTries();
    });
    // Round 17: how far sealing for a new lock is, a few times a second (sealProgress).
    m_sealTick.setInterval(250);
    connect(&m_sealTick, &QTimer::timeout, this, &Vault::sealProgressChanged);
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
    // Round 17: sealing for a new lock stops (the old folder and its key stay as they were); what was saved meanwhile
    // is written with the key still in use, before the saving thread finishes.
    if (m_sealThread) {
        m_sealStop->store(true);
        m_sealThread->wait();
    }
    if (m_sealing) {
        m_sealing = false;
        if (!m_key.isEmpty() && !m_restarting)
            flushHeld();
    }
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

// Round 19 (m14 Linux): vault.key as read last, read again only when it changed (the lock screen asks often).
QJsonObject Vault::keyObject(bool *there) const
{
    const QFileInfo fi(keyFile());
    if (!fi.exists()) {
        m_keyCache = {};
        if (there)
            *there = false;
        return {};
    }
    const QDateTime mod = fi.lastModified();
    if (!m_keyCache.there || m_keyCache.size != fi.size() || m_keyCache.mod != mod) {
        QFile f(fi.filePath());
        if (!f.open(QIODevice::ReadOnly)) {
            m_keyCache = {};
            if (there)
                *there = false;
            return {};
        }
        m_keyCache.o = QJsonDocument::fromJson(f.readAll()).object();
        m_keyCache.size = fi.size();
        m_keyCache.mod = mod;
        m_keyCache.there = true;
    }
    if (there)
        *there = true;
    return m_keyCache.o;
}

bool Vault::hasLock() const
{
    bool there = false;
    const QJsonObject o = keyObject(&there);
    return there && o.value(QStringLiteral("mode")).toString() != QLatin1String("open");
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
        const QJsonObject o = QJsonDocument::fromJson(lock.readAll()).object();
        lock.close();
        // 1.42: counted on disk before the check; inside the open app it never erases.
        bool lockApp = false;
        if (!beginTry(true, &lockApp))
            return false;
        bool ok = false;
        QByteArray given = openLock(o, secret, &ok);
        const bool same = ok && given == m_key;
        given.fill(0);
        if (!same) {
            wrongTry(lockApp);
            Q_EMIT changed();
            return false;
        }
        rightTry();
    }
    // No secret: the data key itself goes in vault.key. Nothing outside Huginn keeps it.
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("open")}, {QStringLiteral("key"), QString::fromLatin1(m_key.toBase64())}};
    if (!writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact)))
        return false;
    m_weak = false;
    m_screenLocked = false;
    m_grantFor.clear();
    Q_EMIT changed();
    Q_EMIT lockChanged();
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
    return keyObject().value(QStringLiteral("kind")).toString() == QLatin1String("pattern");
}

int Vault::waitSeconds() const
{
    // 1.42: the lock-state file (and its copy in settings); see lockLeft.
    LockState st = loadLock();
    if (st.wait <= 0)
        return 0;
    bool moved = false;
    const qint64 left = lockLeft(&st, sinceStart(), QDateTime::currentMSecsSinceEpoch(), bootTag(), &moved);
    if (moved)
        saveLock(st);
    return left > 0 ? int((left + 999) / 1000) : 0;
}

// ---------------------------------------------------------------- 1.42: wrong tries and the unlock password

qint64 Vault::waitAfter(int fails, bool erase)
{
    static const qint64 waits[] = {5 * 60000, 3600000, 12 * qint64(3600000), 24 * qint64(3600000)};
    if (fails <= 0 || fails % 5 != 0 || erasesAt(fails, erase))
        return 0;
    return waits[qMin(fails / 5, 4) - 1];
}

bool Vault::erasesAt(int fails, bool erase) { return erase && fails >= 25; }

int Vault::lastLeft(int fails, bool erase) { return erase && fails >= 20 && fails < 25 ? 25 - fails : 0; }

int Vault::nextFails(int fails, bool erase, bool inApp, bool *lockApp)
{
    if (lockApp)
        *lockApp = false;
    if (inApp && erasesAt(fails + 1, erase)) {
        if (lockApp)
            *lockApp = true;
        return qMin(fails, 24);
    }
    return fails + 1;
}

namespace
{
// "5 minutes", "1 hour", "12 hours", "24 hours".
QString spanWords(qint64 ms)
{
    if (ms >= 3600000 && ms % 3600000 == 0) {
        const qint64 h = ms / 3600000;
        return h == 1 ? QStringLiteral("1 hour") : QStringLiteral("%1 hours").arg(h);
    }
    const qint64 m = (ms + 59999) / 60000;
    return m == 1 ? QStringLiteral("1 minute") : QStringLiteral("%1 minutes").arg(m);
}
}

QString Vault::waitText(int fails, bool erase)
{
    const QString now = QStringLiteral("Wait %1.").arg(spanWords(waitAfter(fails, erase)));
    if (erasesAt(fails + 5, erase))
        return now + QStringLiteral(" Next: the last 5 tries. If all 5 are wrong, everything is erased for good.");
    const qint64 next = waitAfter(fails + 5, erase);
    return now + QStringLiteral(" Next: %1%2 after 5 more wrong tries.").arg(spanWords(next), next == waitAfter(fails, erase) ? QStringLiteral(" again") : QString());
}

QString Vault::triesText(int fails, bool erase)
{
    const int last = lastLeft(fails, erase);
    if (last == 1)
        return QStringLiteral("Last try. If it's wrong, everything is erased for good.");
    if (last > 1)
        return QStringLiteral("Last %1 tries. If all %1 are wrong, everything is erased for good.").arg(last);
    if (fails <= 0)
        return QString();
    const int left = 5 - fails % 5;
    const QString wait = spanWords(waitAfter(fails + left, erase));
    return left == 1 ? QStringLiteral("1 more wrong try, then a wait of %1.").arg(wait)
                     : QStringLiteral("%1 more wrong tries, then a wait of %2.").arg(left).arg(wait);
}

QString Vault::leftText(qint64 ms)
{
    if (ms < 60000) {
        const qint64 sec = qMax<qint64>(1, (ms + 999) / 1000);
        return sec == 1 ? QStringLiteral("Try again in 1 second.") : QStringLiteral("Try again in %1 seconds.").arg(sec);
    }
    const qint64 mins = (ms + 59999) / 60000, h = mins / 60, m = mins % 60;
    const QString hours = h == 1 ? QStringLiteral("1 hour") : QStringLiteral("%1 hours").arg(h);
    const QString minutes = m == 1 ? QStringLiteral("1 minute") : QStringLiteral("%1 minutes").arg(m);
    const QString both = hours + QLatin1Char(' ') + minutes;
    return QStringLiteral("Try again in %1.").arg(h == 0 ? minutes : m == 0 ? hours : both);
}

int Vault::wrongTries() const { return loadLock().fails; }

bool Vault::eraseAfterTries() const { return loadLock().erase; }

void Vault::setEraseAfterTries(bool on)
{
    LockState st = loadLock();
    if (on == st.erase)
        return;
    // On: only with a lock, and only after it was confirmed for this (Settings asks first).
    if (on && (!hasLock() || !takeGrant(QStringLiteral("erase")))) {
        Q_EMIT changed();
        return;
    }
    st.erase = on;
    saveLock(st);
    Q_EMIT changed();
}

QString Vault::triesWords() const
{
    const LockState st = loadLock();
    const int wait = waitSeconds();
    if (wait > 0)
        return waitText(st.fails, st.erase) + QLatin1Char(' ') + leftText(qint64(wait) * 1000);
    return triesText(st.fails, st.erase);
}

QString Vault::triesWordsFor(int fails, bool erase) const
{
    const qint64 wait = waitAfter(fails, erase);
    return wait > 0 ? waitText(fails, erase) + QLatin1Char(' ') + leftText(wait - 1000) : triesText(fails, erase);
}

bool Vault::beginTry(bool inApp, bool *lockApp)
{
    if (lockApp)
        *lockApp = false;
    m_tryError.clear();
    LockState st = loadLock();
    bool moved = false;
    const qint64 mono = sinceStart(), clock = QDateTime::currentMSecsSinceEpoch();
    if (lockLeft(&st, mono, clock, bootTag(), &moved) > 0) {
        if (moved)
            saveLock(st);
        return false;
    }
    // On disk before the check runs: stopping Huginn in the middle of it can't skip the count.
    // Round 19 (FIX 15): and only then. A count that can't be written (a read-only file, a folder in its place, no
    // room) refuses the try: nobody gets tries that aren't counted.
    const LockState counted = countedTry(st, inApp, mono, clock, bootTag(), lockApp);
    LockState back;
    if (!saveLock(counted) || !readLockFile(lockStatePath(), &back) || back.fails != counted.fails) {
        m_tryError = QStringLiteral("Huginn can't save its count of tries, so it can't check this now. Make sure Huginn's data folder can be written to, then try again.");
        CrashLog::note(QStringLiteral("vault: a try was refused: the lock state couldn't be saved"));
        if (lockApp)
            *lockApp = false;
        return false;
    }
    return true;
}

void Vault::rightTry()
{
    LockState fresh;
    fresh.erase = loadLock().erase;
    saveLock(fresh);
}

void Vault::wrongTry(bool lockApp)
{
    const LockState st = loadLock();
    if (erasesAt(st.fails, st.erase) && !lockApp) {
        eraseForTries();
        return;
    }
    // Inside the open app, one more would erase: the lock goes up instead (the lock screen says what's left).
    if (lockApp && hasLock())
        lockScreen();
}

void Vault::eraseForTries()
{
    {
        // Pieces still being kept on another thread (anyThreadWriter) stop here.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
    if (m_restarting)
        return;
    CrashLog::note(QStringLiteral("vault: erased after too many wrong tries"));
    // The same as Erase everything: Huginn's data only (on Windows too, the program stays), then a fresh start.
    eraseAll();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    QTimer::singleShot(500, this, [this] { restartHuginn(QStringLiteral("Everything was erased after too many wrong tries. Close Huginn and open it again.")); });
}

bool Vault::hasUnlockPassword() const
{
    const QJsonObject o = keyObject();
    return o.value(QStringLiteral("mode")).toString() == QLatin1String("password") && o.value(QStringLiteral("spare")).isObject();
}

QString Vault::spareStep() const
{
    if (m_key.size() != 32 || m_screenLocked || m_lockNeeded || m_sealing || m_restarting || !hasLock() || !usesPattern())
        return QString();
    if (m_spareChange || !hasUnlockPassword())
        return QStringLiteral("set");
    // During first setup its last page asks (FirstSetup.qml); the lock screen asks only once setup is over.
    if (settings().value(QStringLiteral("chatlock/recall"), false).toBool()
        && settings().value(QStringLiteral("setup/state")).toString() != QLatin1String("started"))
        return QStringLiteral("recall");
    return QString();
}

bool Vault::setUnlockPassword(const QString &password)
{
    if (m_restarting || m_sealing || m_key.size() != 32 || m_screenLocked || password.size() < kMinSecret || !hasLock() || !usesPattern())
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    f.close();
    const QByteArray spare = lockBlob(password, false, m_key);
    if (spare.isEmpty())
        return false;
    QJsonObject w = QJsonDocument::fromJson(spare).object();
    w.remove(QStringLiteral("mode"));
    w.remove(QStringLiteral("kind"));
    o.insert(QStringLiteral("spare"), w);
    if (!writeKeyFile(QJsonDocument(o).toJson(QJsonDocument::Compact)))
        return false;
    m_spareChange = false;
    settings().setValue(QStringLiteral("chatlock/recall"), true);
    settings().sync();
    Q_EMIT changed();
    return true;
}

bool Vault::recallUnlockPassword(const QString &password)
{
    // Round 19 (m10 Windows, security 9): only for the step that asks it (just set, Huginn open on screen), never as a
    // way to test guesses at other times. Not counted (in the open app; it was set a moment ago, and "Set a new one"
    // is right there).
    if (m_key.size() != 32 || password.isEmpty() || m_screenLocked || m_restarting
        || !settings().value(QStringLiteral("chatlock/recall"), false).toBool())
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    if (!o.value(QStringLiteral("spare")).isObject())
        return false;
    bool ok = false;
    QByteArray key = openLock(o.value(QStringLiteral("spare")).toObject(), password, &ok);
    const bool same = ok && key == m_key;
    key.fill(0);
    if (!same)
        return false;
    settings().remove(QStringLiteral("chatlock/recall"));
    settings().sync();
    Q_EMIT changed();
    return true;
}

bool Vault::startUnlockPasswordChange()
{
    if (!hasLock() || !usesPattern() || !takeGrant(QStringLiteral("spare")))
        return false;
    m_spareChange = true;
    Q_EMIT changed();
    return true;
}

void Vault::cancelUnlockPasswordChange()
{
    if (!m_spareChange)
        return;
    m_spareChange = false;
    Q_EMIT changed();
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
    // 1.42: counted on disk before the check, so stopping Huginn in the middle can't skip it.
    if (!beginTry(false))
        return false;
    bool ok = false;
    const QByteArray key = openLock(o, secret, &ok);
    if (!ok || key.size() != 32) {
        wrongTry(false);
        Q_EMIT changed();
        return false;
    }
    rightTry();
    const bool first = m_key.isEmpty();
    // Already open: the same data key opened again, nothing to replace.
    if (first)
        m_key = key;
    m_weak = !strongSecret(secret);
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

bool Vault::checkLock(const QString &secret)
{
    if (m_restarting)
        return false;
    if (!hasLock())
        return true;   // nothing to ask with
    // Behind the lock screen it's a try at the lock, counted as one.
    if (m_key.size() != 32 || m_screenLocked || !spareStep().isEmpty())
        return unlock(secret);
    if (secret.isEmpty())
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    f.close();
    // Round 19 (FIX 11, owner decision): inside the open app, never counted: no wait, no erase.
    bool ok = false;
    QByteArray given = openLock(o, secret, &ok);
    const bool same = ok && given == m_key;
    given.fill(0);
    return same;
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
    // 1.42: the try is counted and on disk before the key is worked out: stopping Huginn meanwhile can't skip it.
    if (!beginTry(false))
        return fail();
    // The first unlock reads and opens the saved chat files there too (not pictures: they are read when shown).
    const bool first = m_key.isEmpty();
    const QString dir = dataDir();
    const bool erase = loadLock().erase;
    QFile again(keyFile());
    const QByteArray keyBytes = again.open(QIODevice::ReadOnly) ? again.readAll() : QByteArray();
    again.close();
    m_unlocking = true;
    Q_EMIT changed();
    // The thread uses only what it was given and answers with a queued call; ~Vault waits for it, so "this" is
    // still here when the answer comes.
    QThread *t = QThread::create([this, o, secret, first, dir, erase, keyBytes] {
        Busy::ThreadScope busy("unlock thread: working out the key and reading files");
        bool ok = false;
        const QByteArray key = openLock(o, secret, &ok);
        const bool good = ok && key.size() == 32;
        // Round 19 (FIX 12): right: the count is cleared here and now, before anything else. Closing Huginn in the
        // next moment (the answer below then never arrives) can't leave a right try counted as wrong.
        if (good && !clearCountNow(erase))
            qWarning("vault: couldn't clear the count after a right try (cleared again when the answer arrives)");
        // Round 19 (FIX 15): a wrap made with less than new ones get is made again with today's numbers (only the wrap
        // just opened: the other one is made again when it's used). Written by the window's thread, and only when
        // vault.key is still what was read.
        QByteArray newKeyFile;
        const QJsonObject used = wrapFor(o, secret);
        if (good && lockIsOld(used)) {
            QJsonObject w = QJsonDocument::fromJson(lockBlob(secret, false, key)).object();
            if (w.value(QStringLiteral("wrapped")).isString() && !lockIsOld(w)) {
                w.remove(QStringLiteral("mode"));
                w.remove(QStringLiteral("kind"));
                QJsonObject n = o;
                if (used != o) {
                    n.insert(QStringLiteral("spare"), w);
                } else {
                    for (const char *k : {"kdf", "m", "t", "p", "N", "r", "rounds", "salt", "wrapped"})
                        n.remove(QLatin1String(k));
                    for (auto it = w.constBegin(); it != w.constEnd(); ++it)
                        n.insert(it.key(), it.value());
                }
                bool back = false;
                QByteArray check = openLock(n, secret, &back);
                if (back && check == key)
                    newKeyFile = QJsonDocument(n).toJson(QJsonDocument::Compact);
                check.fill(0);
            }
        }
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
        QMetaObject::invokeMethod(this, [this, good, key, weak, read, newKeyFile, keyBytes] {
            // The wrap made again goes in only over the vault.key it was made from (a lock changed meanwhile wins).
            if (good && !newKeyFile.isEmpty() && !m_restarting && !m_sealing) {
                QFile now(keyFile());
                if (now.open(QIODevice::ReadOnly) && now.readAll() == keyBytes) {
                    now.close();
                    if (writeKeyFile(newKeyFile))
                        CrashLog::note(QStringLiteral("vault: a lock was made again with stronger numbers"));
                }
            }
            finishUnlock(good, key, weak, read);
        }, Qt::QueuedConnection);
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
        Q_EMIT changed();
        Q_EMIT unlockFinished(false);
        if (!ok && !m_restarting)
            wrongTry(false);   // may erase (Erase after too many tries), after the lock screen heard how it went
        return;
    }
    rightTry();
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

bool Vault::setSecret(const QString &secret, bool pattern, const QString &spare)
{
    m_secretError.clear();
    if (!strongSecret(secret))
        return false;
    // 1.42: a pattern needs its unlock password (at least 6 characters, never a pattern). A password lock has none.
    if (pattern && (spare.size() < kMinSecret || spare.startsWith(QLatin1String("pattern:")))) {
        m_secretError = QStringLiteral("Set an unlock password too (6 or more characters).");
        return false;
    }
    const QString second = pattern ? spare : QString();
    if (exists() && m_key.isEmpty())
        return false;   // changing needs the old lock opened first
    if (m_restarting)
        return false;
    const bool first = m_key.isEmpty();
    // The lock was off: the data key sat unprotected in vault.key, so a copy of it may be out there. A new one, and
    // every file sealed again with it, before the new lock guards it.
    const bool wasOff = !first && exists() && !hasLock();
    if (m_sealing)
        return false;   // already sealing (LockPanel shows how far)
    if (wasOff && m_busy) {
        m_secretError = QStringLiteral("A backup is being made. Set the lock once it's done.");
        return false;   // a backup is reading the files with the old key
    }
    if (first) {
        m_key = random(32);
    } else if (wasOff) {
        // Round 17: everything is sealed again one file at a time on a thread of its own (startSeal), so the window
        // never freezes and the photos are never all in memory at once. setSecret answers false; sealing says it's
        // under way and secretFinished() how it ended.
        // Minor (round 17): a save still waiting or being retried would land in the new folder sealed with the old
        // key. Everything sealed with the old key must be on disk first, or nothing starts.
        if (!drainSaves(30000)) {
            m_secretError = QStringLiteral("Huginn is still saving. Try again in a moment.");
            return false;
        }
        startSeal(secret, pattern, second);
        return false;
    }
    if (!writeLock(secret, pattern, second)) {
        if (first)
            m_key.clear();
        return false;
    }
    m_weak = false;
    m_screenLocked = false;
    m_lockNeeded = false;   // the new lock is set
    m_spareChange = false;
    m_grantFor.clear();
    // The unlock password is asked once more from memory (spareStep "recall"; during first setup, its last page).
    if (pattern)
        settings().setValue(QStringLiteral("chatlock/recall"), true);
    else
        settings().remove(QStringLiteral("chatlock/recall"));
    Q_EMIT changed();
    Q_EMIT lockChanged();
    if (first)
        Q_EMIT opened();
    return true;
}

QString Vault::sealProgress() const
{
    if (!m_sealing || !m_sealTotal)
        return {};
    const int total = m_sealTotal->load(), done = m_sealDone->load();
    if (total <= 0)
        return QStringLiteral("Getting ready to seal your chats...");
    const QLocale l;
    return QStringLiteral("Sealing %1 of %2 files. Keep Huginn open.").arg(l.toString(qMin(done, total)), l.toString(total));
}

bool Vault::confirmLock(const QString &secret, const QString &what)
{
    if (!hasLock())
        return true;   // nothing to ask with
    if (m_restarting || m_key.size() != 32 || m_screenLocked || waitSeconds() > 0)
        return false;
    QFile lock(keyFile());
    if (!lock.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(lock.readAll()).object();
    lock.close();
    // 1.42: counted on disk before the check; inside the open app it never erases (the lock goes up instead).
    bool lockApp = false;
    if (!beginTry(true, &lockApp))
        return false;
    bool ok = false;
    QByteArray given = openLock(o, secret, &ok);
    const bool same = ok && given == m_key;
    given.fill(0);
    if (!same) {
        wrongTry(lockApp);
        Q_EMIT changed();
        return false;
    }
    rightTry();
    m_grantFor = what;
    // Long enough to pick a file and type a backup password; used once.
    m_grantUntil = QDeadlineTimer::current().deadline() + 10 * 60 * 1000;
    return true;
}

bool Vault::lockConfirmed(const QString &what) const
{
    if (!hasLock())
        return true;
    return !m_screenLocked && m_grantFor == what && QDeadlineTimer::current().deadline() < m_grantUntil;
}

bool Vault::takeGrant(const QString &what)
{
    if (!lockConfirmed(what))
        return false;
    m_grantFor.clear();
    m_grantUntil = 0;
    return true;
}

bool Vault::writeLock(const QString &secret, bool pattern, const QString &spare) const
{
    const QByteArray blob = lockBlob(secret, pattern, m_key, spare);
    return !blob.isEmpty() && writeKeyFile(blob);
}

QByteArray Vault::lockBlob(const QString &secret, bool pattern, const QByteArray &key, const QString &spare)
{
    const QByteArray salt = random(16);
    QJsonObject o{{QStringLiteral("mode"), QStringLiteral("password")}, {QStringLiteral("kind"), pattern ? QStringLiteral("pattern") : QStringLiteral("text")},
                  {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}};
    // Argon2id when this OpenSSL has it, scrypt otherwise: both make guessing on a graphics card slow. Round 19: a
    // machine that can't spare 256 MiB right now gets the 64 MiB of 1.42's first locks (made again later).
    quint32 mem = kArgonMem;
    QByteArray kek = argon2id(secret.toUtf8(), salt, mem, kArgonIter, kArgonLanes);
    if (kek.isEmpty() && hasArgon2id()) {
        mem = kArgonMemLow;
        kek = argon2id(secret.toUtf8(), salt, mem, kArgonIter, kArgonLanes);
    }
    if (!kek.isEmpty()) {
        o.insert(QStringLiteral("kdf"), QStringLiteral("argon2id"));
        o.insert(QStringLiteral("m"), int(mem));
        o.insert(QStringLiteral("t"), int(kArgonIter));
        o.insert(QStringLiteral("p"), int(kArgonLanes));
    } else {
        quint64 n = kScryptN;
        kek = scrypt(secret.toUtf8(), salt, n, kScryptR, kScryptP);
        if (kek.isEmpty()) {
            n = kScryptN >> 1;
            kek = scrypt(secret.toUtf8(), salt, n, kScryptR, kScryptP);
        }
        if (kek.isEmpty())
            return QByteArray();
        o.insert(QStringLiteral("kdf"), QStringLiteral("scrypt"));
        o.insert(QStringLiteral("N"), double(n));
        o.insert(QStringLiteral("r"), int(kScryptR));
        o.insert(QStringLiteral("p"), int(kScryptP));
    }
    // Only the lock itself guards the key: nothing outside Huginn (no Windows account binding), so the folder
    // opens on any computer with the right pattern or password.
    const QByteArray wrapped = seal(kek, key);
    kek.fill(0);
    o.insert(QStringLiteral("wrapped"), QString::fromLatin1(wrapped.toBase64()));
    // 1.42: a pattern's unlock password: a second wrap of the same key, its own salt, the same KDF. Never a hash of it
    // anywhere else, never the password itself.
    if (pattern && !spare.isEmpty()) {
        QJsonObject w = QJsonDocument::fromJson(lockBlob(spare, false, key)).object();
        if (!w.value(QStringLiteral("wrapped")).isString())
            return QByteArray();
        w.remove(QStringLiteral("mode"));
        w.remove(QStringLiteral("kind"));
        o.insert(QStringLiteral("spare"), w);
    }
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

// vault.key, readable and writable by this user only (like ring-key.json and the TLS key): with the lock off it
// holds the data key itself.
bool Vault::writeKeyFile(const QByteArray &bytes) const
{
    const QFileDevice::Permissions owner = QFileDevice::ReadOwner | QFileDevice::WriteOwner;
    m_keyCache = {};
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

namespace
{
// Round 19 (FIX 16): what Erase everything leaves nowhere. Key copies are made unreadable first (written over in place
// with random bytes, which needs only the write sharing antivirus and indexers allow), then everything is deleted and
// checked. What couldn't go is noted in erase-pending, and goes at the next start, before anything uses it.
QString erasePendingPath() { return System::dataDir() + QStringLiteral("/erase-pending"); }

void shred(const QString &file)
{
    QFile f(file);
    const qint64 n = f.size();
    if (n <= 0 || !f.open(QIODevice::ReadWrite))
        return;
    QByteArray noise = random(int(qMin<qint64>(n, 1 << 20)));
    f.write(noise);
    f.flush();
    f.close();
}

// Huginn's folders in its data folder that erase empties: the chats and their copies (vault.key in each), the
// certificates (the lasting one and the hidden ones), Tor's folder and the voices.
QStringList eraseNames(const QString &root)
{
    QStringList names{QStringLiteral("chat"), QStringLiteral("chat.restore"), QStringLiteral("chat.old"), QStringLiteral("tls"), QStringLiteral("tor"),
                      QStringLiteral("voices")};
    for (const QFileInfo &fi : QDir(root).entryInfoList({QStringLiteral("chat-kept-*")}, QDir::Dirs | QDir::NoDotAndDotDot | QDir::Hidden))
        names << fi.fileName();
    return names;
}

bool isChatFolder(const QString &name) { return name.startsWith(QLatin1String("chat")); }

// A vault.key that still opens anything (not one written over by shred, and not missing).
bool liveKeyFile(const QString &file)
{
    QFile f(file);
    if (!f.open(QIODevice::ReadOnly))
        return false;
    return QJsonDocument::fromJson(f.readAll()).object().contains(QStringLiteral("mode"));
}

// Deletes names in root (folders, and files), vault.key in each written over first. What's still there afterwards.
QStringList wipe(const QString &root, const QStringList &names)
{
    for (const QString &n : names)
        if (isChatFolder(n))
            for (const QString &k : {QStringLiteral("/vault.key"), QStringLiteral("/vault.key.bak")})
                shred(root + QLatin1Char('/') + n + k);
    QStringList left;
    for (const QString &n : names) {
        const QString path = root + QLatin1Char('/') + n;
        const QFileInfo fi(path);
        if (fi.isDir() && !fi.isSymLink())
            QDir(path).removeRecursively();
        else if (fi.exists() || fi.isSymLink())
            QFile::remove(path);
        if (QFileInfo::exists(path) || QFileInfo(path).isSymLink())
            left << n;
    }
    // A save cut off by a crash leaves its temporary copy beside the file (QSaveFile): "ring-key.json.XXXXXX".
    for (const QFileInfo &fi : QDir(root).entryInfoList(QDir::Files | QDir::Hidden | QDir::System)) {
        const QString n = fi.fileName();
        for (const char *p : {"lock-state.json.", "ring-key.json.", "nudge-key.json.", "ring-allow.json.", "vault.key."})
            if (n.startsWith(QLatin1String(p)) && !QFile::remove(fi.absoluteFilePath()))
                left << n;
    }
    return left;
}

void notePending(const QStringList &left)
{
    const QString file = erasePendingPath();
    if (left.isEmpty()) {
        QFile::remove(file);
        return;
    }
    QSaveFile f(file);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(QJsonObject{{QStringLiteral("left"), QJsonArray::fromStringList(left)}}).toJson(QJsonDocument::Compact));
        f.commit();
    }
}
}

void Vault::finishErase()
{
    const QString file = erasePendingPath();
    if (!QFileInfo::exists(file))
        return;
    QFile f(file);
    QStringList names;
    if (f.open(QIODevice::ReadOnly)) {
        for (const QJsonValue &v : QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("left")).toArray())
            names << v.toString();
        f.close();
    }
    // Only once per note: a note that can't be removed never wipes anything again (whatever is set up after).
    if (!QFile::remove(file)) {
        CrashLog::note(QStringLiteral("vault: an erase left things behind, and its note couldn't be removed"));
        return;
    }
    const QString root = System::dataDir();
    QStringList todo;
    for (const QString &n : names) {
        if (n.isEmpty() || n.contains(QLatin1Char('/')) || n.contains(QLatin1Char('\\')) || n.startsWith(QLatin1Char('.')))
            continue;
        // A chat folder whose vault.key opens again was set up since: never touched.
        if (isChatFolder(n) && liveKeyFile(root + QLatin1Char('/') + n + QStringLiteral("/vault.key")))
            continue;
        todo << n;
    }
    const QStringList left = wipe(root, todo);
    // Only what nothing set up since could be using is tried yet again: Tor's folder and the voices.
    QStringList again;
    for (const QString &n : left)
        if (n == QLatin1String("tor") || n == QLatin1String("voices"))
            again << n;
    notePending(again);
    CrashLog::note(left.isEmpty() ? QStringLiteral("vault: finished an erase that left things behind")
                                  : QStringLiteral("vault: an erase still left %1 things behind").arg(left.size()));
}

void Vault::eraseAll()
{
    {
        // Pieces still being kept on another thread (anyThreadWriter) stop here.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
    // Round 19: Tor and the voice engine stop first where main.cpp listens, so their files can go.
    Q_EMIT aboutToErase();
    // What waits to be saved would only bring files back: dropped (the one being written ends first).
    m_saves->dropAll(30000);
    // Round 17: sealing for a new lock stops, and what it held goes too (finishSeal swaps nothing in).
    if (m_sealStop)
        m_sealStop->store(true);
    for (auto it = m_held.begin(); it != m_held.end(); ++it)
        it->data.fill(0);
    m_held.clear();
    m_grantFor.clear();
    const QString root = System::dataDir();
    // Round 19 (FIX 16): the chats and their copies (vault.key written over first), the certificates (the hidden ones
    // too), Tor's folder and the voices, checked afterwards; what couldn't go now goes at the next start.
    const bool torUsed = QFileInfo::exists(root + QStringLiteral("/tor"));
    QStringList left;
    {
        QMutexLocker hold(&s_swap);
        left = wipe(root, eraseNames(root));
    }
    QFile::remove(root + QStringLiteral("/chat.old.replaced"));
    // Files waiting in the cache (downloads, parts of a move) and a move's saved state go too.
    QDir(System::cacheDir()).removeRecursively();
    settings().remove(QStringLiteral("move"));
    settings().remove(QStringLiteral("chatlock/strongerAsked"));
    m_key.fill(0);
    m_key.clear();
    m_unreadable.clear();
    m_dirty.clear();
    m_spareChange = false;
    m_keyCache = {};
    settings().remove(QStringLiteral("vault"));
    // 1.42: the wrong tries, their wait and the erase switch (the lock-state file beside the chat folder), and a
    // pending "type your unlock password from memory".
    QFile::remove(lockStatePath());
    settings().remove(QStringLiteral("chatlock/recall"));
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
    // Round 19: what Daily Havamal and Read aloud noted (which verse, which voice) goes too.
    settings().remove(QStringLiteral("dailyVerse"));
    settings().remove(QStringLiteral("readAloud"));
    // The saved Ultra privacy switch goes too (Tor was stopped first, through aboutToErase): an erased computer starts
    // with it off, like a new one. This run stays as it was until Huginn starts again.
    settings().remove(QStringLiteral("privacy/ultraPrivacy"));
    // Written now, so what Move saves right after (its "Moved to" note, through its own QSettings) isn't removed later.
    settings().sync();
    // The Windows portable build: the whole data folder beside Huginn.exe is emptied (every setting too). Never the
    // program: only Huginn's own names in its data folder.
    Portable::eraseAll();
    // Tor writes its state as it stops, which may be after this: its folder goes again at the next start anyway.
    if (torUsed && !left.contains(QStringLiteral("tor")))
        left << QStringLiteral("tor");
    notePending(left);
    if (left.size() > (torUsed ? 1 : 0))
        CrashLog::note(QStringLiteral("vault: erase left %1 things behind; they go at the next start").arg(left.size()));
    Q_EMIT erased();
    Q_EMIT changed();
}

void Vault::eraseForRelink()
{
    {
        // Pieces still being kept on another thread (anyThreadWriter) stop here.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
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
    // Round 18 m2: sealing for a new lock: written now with the key in use (the old one), and sealed again into the new
    // folder just before it's swapped in (sealDirty). Never held in memory only, so a crash meanwhile loses nothing.
    if (m_sealing)
        m_dirty.insert(name);
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
    if (m_sealing)   // round 18 m2: on disk with the old key now, sealed again for the new lock at the end (write())
        m_dirty.insert(name);
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
    // Round 17: saved while sealing for a new lock, and not on disk yet.
    if (m_sealing) {
        const auto held = m_held.constFind(name);
        if (held != m_held.constEnd()) {
            if (status)
                *status = held->removed ? ReadMissing : ReadOk;
            return held->removed ? QByteArray() : held->make ? held->make() : held->data;
        }
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
    {
        // Writers on other threads stop before the key changes, never after (no piece lands with the old key).
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
    m_shared->key.fill(0);
    m_shared->key = m_key;
    m_shared->key.detach();   // its own copy: wiping one never touches the other
    l.unlock();
    QMutexLocker w(&m_shared->writing);
    ++m_shared->gen;
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

std::function<bool(const QString &, const QByteArray &)> Vault::anyThreadWriter() const
{
    const std::shared_ptr<Shared> shared = m_shared;
    const QString dir = dataDir();
    int gen = 0;
    {
        QMutexLocker w(&shared->writing);
        gen = shared->gen;
    }
    const bool usable = !m_key.isEmpty() && !m_restarting && !m_sealing;
    return [shared, dir, gen, usable](const QString &name, const QByteArray &data) -> bool {
        if (!usable || !Vault::safeName(name))
            return false;
        QByteArray key;
        {
            QMutexLocker l(&shared->lock);
            key = shared->key;
            key.detach();
        }
        if (key.isEmpty())
            return false;
        const QByteArray sealed = kMagic + Vault::seal(key, data);
        key.fill(0);
        // Written next to its place with no lock held (a slow disk never holds up the window's thread, or call sound),
        // then moved into place under the locks: never while the chat folder is being swapped (a restore or a new
        // lock). In this order: the swap may change the key, which takes writing too.
        const QString file = dir + QLatin1Char('/') + name;
        const QString tmp = file + QStringLiteral(".keep");
        QDir().mkpath(QFileInfo(file).absolutePath());
        {
            QFile t(tmp);
            if (!t.open(QIODevice::WriteOnly | QIODevice::Truncate) || t.write(sealed) != sealed.size() || !t.flush()) {
                t.close();
                QFile::remove(tmp);
                return false;
            }
            t.close();
        }
        QMutexLocker hold(&s_swap);
        QMutexLocker w(&shared->writing);
        if (shared->gen != gen || !QFileInfo::exists(QFileInfo(file).absolutePath())) {
            QFile::remove(tmp);
            return false;
        }
        QFile::remove(file);
        return QFile::rename(tmp, file);
    };
}

void Vault::remove(const QString &name) const
{
    if (m_sealing)   // round 18 m2: gone from the new folder too, just before it's swapped in (sealDirty)
        m_dirty.insert(name);
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

Vault::Files Vault::everything(QString *why) const
{
    if (m_key.isEmpty() || m_sealing)
        return {};
    if (beforeBackup)
        beforeBackup();
    drainSaves(30000);
    QStringList unreadable;
    Files files = collect(m_key, dataDir(), &unreadable);
    // Round 17: a file held by another program just now would be left behind (and erased with this device): stop.
    if (!unreadable.isEmpty()) {
        for (auto &e : files)
            e.second.fill(0);
        if (why)
            *why = QStringLiteral("Huginn couldn't read %1 just now, so nothing moved. Another program, like antivirus or a backup tool, may be "
                                  "using it. Try again in a moment.").arg(fileWords(unreadable.first()));
        return {};
    }
    files.emplace_back(kSettingsEntry, settingsBlob());
    return files;
}

bool Vault::takeEverything(const Files &files, QString *error)
{
    if (m_key.isEmpty()) {
        *error = QStringLiteral("Open Chat and unlock it first.");
        return false;
    }
    if (m_sealing) {
        *error = QStringLiteral("Huginn is sealing your chats for the new lock. Try again once it's done.");
        return false;
    }
    // Round 17 (security m6): chats already here are kept first, as a restore keeps them, so a mistaken Move here
    // never loses this device's own account.
    drainSaves(30000);
    if (QFileInfo::exists(dataDir() + QStringLiteral("/contacts.json")) || QFileInfo::exists(dataDir() + QStringLiteral("/chats.json"))
        || QFileInfo::exists(dataDir() + QStringLiteral("/groups.json"))) {
        const QString kept = keepCopy(dataDir());
        if (kept.isEmpty()) {
            *error = QStringLiteral("Couldn't keep a copy of what's here, so nothing changed. Is the disk full?");
            return false;
        }
        CrashLog::note(QStringLiteral("vault: chats here before a move were kept as %1").arg(QFileInfo(kept).fileName()));
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

bool Vault::isBigPiece(const QString &name)
{
    return name.startsWith(QLatin1String("photos/")) && name.section(QLatin1Char('/'), 1, 1).endsWith(QLatin1String(".v"))
        && name.count(QLatin1Char('/')) >= 2;
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

namespace
{
// A file's bytes, with a few short tries (another program, like antivirus, may hold it for a moment). False when it
// still couldn't be read.
bool readTrying(const QString &file, QByteArray *out)
{
    for (int attempt = 0; attempt < 4; ++attempt) {
        QFile f(file);
        if (f.open(QIODevice::ReadOnly)) {
            *out = f.readAll();
            if (f.error() == QFileDevice::NoError)
                return true;
        }
        QThread::msleep(100 * (attempt + 1));
    }
    return false;
}
}

QString Vault::fileWords(const QString &name)
{
    if (name == QLatin1String("chats.json") || name == QLatin1String("gchats.json"))
        return QStringLiteral("your chat list");
    if (name == QLatin1String("contacts.json"))
        return QStringLiteral("your contacts");
    if (name == QLatin1String("groups.json"))
        return QStringLiteral("your groups");
    if (name == QLatin1String("secrets.json") || name.startsWith(QLatin1String("ratchet/")) || name.endsWith(QLatin1String("keys.json")))
        return QStringLiteral("your keys");
    if (isMedia(name))
        return QStringLiteral("a photo or video");
    return QStringLiteral("one of its files");
}

Vault::Files Vault::collect(const QByteArray &key, const QString &dir, QStringList *unreadable, QStringList *damaged)
{
    Files out;
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString full = it.next();
        const QString name = base.relativeFilePath(full);
        if (name == QLatin1String("vault.key") || !safeName(name) || isBigPiece(name))
            continue;
        QByteArray b;
        if (!readTrying(full, &b)) {
            // Round 17: never left out without a word (the caller stops; nothing is changed or deleted).
            CrashLog::note(QStringLiteral("vault: a file couldn't be read just now: ") + kindOf(name));
            if (unreadable)
                unreadable->append(name);
            continue;
        }
        if (!b.startsWith(kMagic))
            continue;   // not sealed, so not one of Huginn's saved files (a half-written temp file, a swap's mark)
        bool ok = false;
        const QByteArray plain = open(key, b.mid(kMagic.size()), &ok);
        if (ok) {
            out.emplace_back(name, plain);
        } else {
            CrashLog::note(QStringLiteral("vault: a saved file doesn't open: ") + kindOf(name));
            if (damaged)
                damaged->append(name);
        }
    }
    std::sort(out.begin(), out.end(), [](const auto &a, const auto &b) { return a.first < b.first; });
    return out;
}

namespace
{
// In a restore's new folder once every file is written; gone once the swap is done.
const QString kComplete = QStringLiteral(".restore-complete");
// Round 16: a file beside the folder a swap put aside ("chat.old.replaced"), written once the new folder is in place:
// that folder is done with, and goes (now, or at the next start or swap if a file in it was held). Beside it, not in
// it, so it stays while the folder is half deleted. Only a folder with it is ever deleted without being asked.
QString replacedMark(const QString &old) { return old + QStringLiteral(".replaced"); }

// Round 16: a folder a swap put aside (old, "chat.old") goes. One marked done with is deleted; one without the mark (it
// may hold someone's chats) is never deleted: it is moved aside as "chat-kept-<date and time>" beside it. *kept: where
// it went then. False when it is still there (a file in it is held), with *why for the person.
bool clearOld(const QString &old, QString *kept, QString *why)
{
    if (!QFileInfo(old).isDir()) {
        QFile::remove(replacedMark(old));
        return true;
    }
    if (QFileInfo::exists(replacedMark(old))) {
        if (QDir(old).removeRecursively()) {
            QFile::remove(replacedMark(old));
            return true;
        }
        CrashLog::note(QStringLiteral("vault: a folder a swap put aside couldn't be deleted yet"));
    } else {
        const QString aside = QFileInfo(old).absolutePath() + QStringLiteral("/chat-kept-")
                              + QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd-HHmmss"));
        if (!QFileInfo::exists(aside) && QDir().rename(old, aside)) {
            if (kept)
                *kept = aside;
            CrashLog::note(QStringLiteral("vault: chats a swap put aside were kept as %1").arg(QFileInfo(aside).fileName()));
            return true;
        }
    }
    if (why)
        *why = QStringLiteral("Huginn couldn't clear %1, a folder left from an earlier restore or lock change. Another program, like "
                              "antivirus or the search indexer, may be using a file in it. Close Huginn, delete or move that folder, "
                              "then open Huginn and try again.")
                   .arg(QDir::toNativeSeparators(old));
    return false;
}

// A folder that was in use a moment ago (a file another program, like antivirus, looks at) can take a moment to let go.
bool renameDir(const QString &from, const QString &to)
{
    for (int i = 0; i < 12; ++i) {
        if (QDir().rename(from, to))
            return true;
        QThread::msleep(250);
    }
    return false;
}
}

bool Vault::replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error, const QByteArray &keyFile)
{
    // Everything is written and checked in a new folder first, so a failure leaves Chat as it was.
    const QString clean = QDir::cleanPath(dir);
    const QString stage = clean + QStringLiteral(".restore"), old = clean + QStringLiteral(".old");
    // Round 16: a folder left aside by an earlier swap never blocks this one. One marked done with goes; chats left
    // aside by a swap that couldn't finish are never deleted: moved aside as chat-kept-<date> (they open with the lock
    // they had). Only when neither works is the reason given.
    {
        QString why;
        if (!clearOld(old, nullptr, &why)) {
            *error = why;
            return false;
        }
    }
    QDir(stage).removeRecursively();
    auto failed = [&](const QString &why) {
        QDir(stage).removeRecursively();
        *error = why;
        return false;
    };
    for (const auto &f : files) {
        if (!safeName(f.first) || f.first == QLatin1String("vault.key") || f.first == kSettingsEntry)
            continue;
        const QString target = stage + QLatin1Char('/') + f.first;
        QDir().mkpath(QFileInfo(target).absolutePath());
        QSaveFile out(target);
        if (!out.open(QIODevice::WriteOnly) || out.write(kMagic + seal(key, f.second)) < 0 || !out.commit())
            return failed(QStringLiteral("Couldn't write the restored chats. Is the disk full?"));
    }
    // The lock goes along: a new one (a restore behind the lock), or the one that is there now.
    QDir().mkpath(stage);
    const QString stagedKey = stage + QStringLiteral("/vault.key");
    if (!keyFile.isEmpty()) {
        const QFileDevice::Permissions owner = QFileDevice::ReadOwner | QFileDevice::WriteOwner;
        QSaveFile k(stagedKey);
        if (!k.open(QIODevice::WriteOnly))
            return failed(QStringLiteral("Couldn't write the restored chats. Is the disk full?"));
        k.setPermissions(owner);
        if (k.write(keyFile) != keyFile.size() || !k.commit())
            return failed(QStringLiteral("Couldn't write the restored chats. Is the disk full?"));
        QFile::setPermissions(stagedKey, owner);
    } else if (QFileInfo::exists(clean + QStringLiteral("/vault.key")) && !QFile::copy(clean + QStringLiteral("/vault.key"), stagedKey)) {
        return failed(QStringLiteral("Couldn't write the restored chats. Is the disk full?"));
    }
    {
        QFile done(stage + QLatin1Char('/') + kComplete);
        if (!done.open(QIODevice::WriteOnly) || done.write("1") != 1 || !done.flush())
            return failed(QStringLiteral("Couldn't write the restored chats. Is the disk full?"));
    }
    return swapIn(clean, QStringLiteral("restore"), error);
}

bool Vault::swapIn(const QString &dir, const QString &what, QString *error)
{
    const QString clean = QDir::cleanPath(dir);
    const QString stage = clean + QStringLiteral(".restore"), old = clean + QStringLiteral(".old");
    auto failed = [&](const QString &why) {
        QDir(stage).removeRecursively();
        *error = why;
        return false;
    };
    // Swap whole folders (renames on the same disk): the old one aside, the new one in, and only then the old one goes.
    // Cut off anywhere, the next start finishes or undoes it (finishSwap). Nothing in Huginn makes a "chat" folder
    // meanwhile (s_swap); one something else made (it has no vault.key) is cleared away, never swapped in over.
    QMutexLocker hold(&s_swap);
    const bool hadDir = QFileInfo(clean).isDir();
    if (hadDir && !renameDir(clean, old))
        return failed(QStringLiteral("Another program is using Huginn's chat folder, so nothing changed. Close it and try again."));
    const auto moveIn = [&] {
        if (renameDir(stage, clean))
            return true;
        if (!hadDir || !strayChat(clean))
            return false;
        CrashLog::note(QStringLiteral("vault: an empty chat folder appeared during a swap; cleared"));
        return QDir(clean).removeRecursively() && renameDir(stage, clean);
    };
    if (!moveIn()) {
        // The old folder back in place; the new one stays beside it for the next start to finish (it is complete).
        if (hadDir && strayChat(clean))
            QDir(clean).removeRecursively();
        if (hadDir && !renameDir(old, clean)) {
            *error = what == QLatin1String("lock") ? QStringLiteral("Setting the lock couldn't finish. Close Huginn and open it again to finish it.")
                                                    : QStringLiteral("The restore couldn't finish. Close Huginn and open it again to finish it.");
            return false;
        }
        return failed(QStringLiteral("Another program is using Huginn's chat folder, so nothing changed. Close it and try again."));
    }
    // The old folder goes first: a start that finds the mark still here only tidies up. Round 16: it is marked done
    // with first, so if a file in it is held (antivirus, the indexer) it goes at the next start or swap, and the mark in
    // the new folder stays until it has.
    {
        QFile replaced(replacedMark(old));
        if (replaced.open(QIODevice::WriteOnly))
            replaced.write("1");
    }
    if (QDir(old).removeRecursively()) {
        QFile::remove(replacedMark(old));
        QFile::remove(clean + QLatin1Char('/') + kComplete);
    } else
        CrashLog::note(QStringLiteral("vault: the folder a swap put aside couldn't be deleted yet; it goes at the next start"));
    return true;
}

// Round 17: Set a lock after it was off. Every file is read, opened with the old key, sealed with a new one and written
// into the stage one at a time on this thread (never the whole folder in memory), after checking there is room for the
// second copy. Any file that can't be read stops it, and nothing changes. Only the two renames run on the window's
// thread (finishSeal, under s_swap), as a restore's do; cut off anywhere, the next start finishes or undoes it.
void Vault::startSeal(const QString &secret, bool pattern, const QString &spare)
{
    const QString dir = QDir::cleanPath(dataDir());
    const QByteArray oldKey = m_key;
    m_sealDone = std::make_shared<std::atomic<int>>(0);
    m_sealTotal = std::make_shared<std::atomic<int>>(0);
    m_sealStop = std::make_shared<std::atomic<bool>>(false);
    const auto done = m_sealDone, total = m_sealTotal;
    const auto stop = m_sealStop;
    m_sealing = true;
    {
        // Pieces still being sealed on another thread (anyThreadWriter) stop here: none is left with the old key.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
    m_dirty.clear();
    m_secretError.clear();
    Q_EMIT changed();
    Q_EMIT sealProgressChanged();
    m_sealTick.start();
    QThread *t = QThread::create([this, dir, oldKey, secret, pattern, spare, done, total, stop] {
        Busy::ThreadScope busy("sealing thread: sealing every file again for a new lock");
        const QString stage = dir + QStringLiteral(".restore"), old = dir + QStringLiteral(".old");
        QByteArray fresh = random(32);
        QString error;
        const auto fail = [&](const QString &why) {
            QDir(stage).removeRecursively();
            error = why;
            return false;
        };
        const auto run = [&]() -> bool {
            QStringList names;
            qint64 bytes = 0;
            {
                const QDir base(dir);
                QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
                while (it.hasNext()) {
                    const QString full = it.next();
                    const QString name = base.relativeFilePath(full);
                    if (name == QLatin1String("vault.key") || name == kComplete || !safeName(name))
                        continue;
                    names << name;
                    bytes += it.fileInfo().size();
                }
            }
            total->store(int(names.size()));
            // Room for a second copy for a moment (and a little more): said before anything is written.
            QStorageInfo disk(QFileInfo(dir).absolutePath());
            disk.refresh();
            const qint64 need = bytes + (qint64(64) << 20);
            if (disk.isValid() && disk.bytesAvailable() >= 0 && disk.bytesAvailable() < need)
                return fail(QStringLiteral("Setting a lock needs about %1 free on this drive for a moment, while your chats are sealed again. "
                                           "Free up space, then try again. Nothing changed.")
                                .arg(QLocale().formattedDataSize(need)));
            const QByteArray blob = lockBlob(secret, pattern, fresh, spare);
            if (blob.isEmpty())
                return fail(QStringLiteral("Couldn't make the lock on this computer. Nothing changed."));
            QString why;
            if (!clearOld(old, nullptr, &why))
                return fail(why);
            QDir(stage).removeRecursively();
            for (const QString &name : std::as_const(names)) {
                if (stop->load())
                    return fail(QStringLiteral("Stopped. Nothing changed."));
                const QString from = dir + QLatin1Char('/') + name;
                QByteArray b;
                if (!QFileInfo::exists(from)) {
                    done->fetch_add(1);
                    continue;   // gone since the list was made (only Huginn's own tidying does that)
                }
                if (!readTrying(from, &b))
                    return fail(QStringLiteral("Huginn couldn't read %1 just now, so nothing changed. Another program, like antivirus or a backup "
                                               "tool, may be using it. Try again in a moment.")
                                    .arg(fileWords(name)));
                QByteArray out;
                bool opened = false;
                if (b.startsWith(kMagic)) {
                    QByteArray plain = open(oldKey, b.mid(kMagic.size()), &opened);
                    if (opened)
                        out = kMagic + seal(fresh, plain);
                    plain.fill(0);
                }
                // Never dropped: a file that isn't sealed (not one of Huginn's saved files) or doesn't open (damaged: it
                // didn't open before either) goes across as it is.
                if (!opened) {
                    CrashLog::note(QStringLiteral("vault: a file was carried across a new lock as it was: ") + kindOf(name));
                    out = b;
                }
                b.fill(0);
                const QString target = stage + QLatin1Char('/') + name;
                QDir().mkpath(QFileInfo(target).absolutePath());
                QSaveFile f(target);
                if (!f.open(QIODevice::WriteOnly) || f.write(out) != out.size() || !f.commit())
                    return fail(QStringLiteral("Couldn't write your chats sealed with the new lock, so nothing changed. Is the disk full?"));
                done->fetch_add(1);
            }
            QDir().mkpath(stage);
            const QString stagedKey = stage + QStringLiteral("/vault.key");
            const QFileDevice::Permissions owner = QFileDevice::ReadOwner | QFileDevice::WriteOwner;
            QSaveFile k(stagedKey);
            if (!k.open(QIODevice::WriteOnly))
                return fail(QStringLiteral("Couldn't write the new lock, so nothing changed. Is the disk full?"));
            k.setPermissions(owner);
            if (k.write(blob) != blob.size() || !k.commit())
                return fail(QStringLiteral("Couldn't write the new lock, so nothing changed. Is the disk full?"));
            QFile::setPermissions(stagedKey, owner);
            QFile mark(stage + QLatin1Char('/') + kComplete);
            if (!mark.open(QIODevice::WriteOnly) || mark.write("1") != 1 || !mark.flush())
                return fail(QStringLiteral("Couldn't write the new lock, so nothing changed. Is the disk full?"));
            return true;
        };
        const bool ok = run();
        QMetaObject::invokeMethod(this, [this, ok, error, fresh] { finishSeal(ok, error, fresh); }, Qt::QueuedConnection);
        fresh.fill(0);
    });
    t->setObjectName(QStringLiteral("huginn-sealing"));
    connect(t, &QThread::finished, t, &QObject::deleteLater);
    m_sealThread = t;
    t->start();
}

void Vault::finishSeal(bool ok, const QString &error, QByteArray fresh)
{
    m_sealTick.stop();
    QString why = error;
    const QString dir = System::dataDir() + QStringLiteral("/chat");
    const bool stopped = (m_sealStop && m_sealStop->load()) || m_restarting || m_key.isEmpty();
    if (ok && !stopped) {
        // Round 18 m2: what was saved meanwhile (with the old key, on disk) is sealed again into the new folder first.
        // Until the swap the old folder holds everything, so a crash anywhere before it loses nothing.
        ok = drainSaves(30000) && sealDirty(QDir::cleanPath(dir), m_key, fresh, &why);
        if (!ok && why.isEmpty())
            why = QStringLiteral("Huginn is still saving. Try again in a moment. Nothing changed.");
        if (ok)
            ok = swapIn(dir, QStringLiteral("lock"), &why);
        else
            QDir(QDir::cleanPath(dir) + QStringLiteral(".restore")).removeRecursively();
    } else {
        if (ok)
            why = QStringLiteral("Stopped. Nothing changed.");
        ok = false;
        QDir(QDir::cleanPath(dir) + QStringLiteral(".restore")).removeRecursively();
    }
    if (ok) {
        m_key.fill(0);
        m_key = fresh;
        m_key.detach();
        m_weak = false;
        m_screenLocked = false;
        m_lockNeeded = false;
        m_spareChange = false;
        m_grantFor.clear();
        // 1.42: a pattern's unlock password is asked once more from memory.
        if (usesPattern())
            settings().setValue(QStringLiteral("chatlock/recall"), true);
    } else {
        CrashLog::note(QStringLiteral("vault: setting a lock didn't finish: %1").arg(why));
        m_secretError = why;   // LockPanel says why
    }
    fresh.fill(0);
    m_sealing = false;
    m_dirty.clear();
    // Round 18 m2: nothing is held any more (saves went to disk meanwhile); an older build's held saves would go here.
    if (!m_key.isEmpty())
        flushHeld();
    m_held.clear();
    Q_EMIT changed();
    Q_EMIT sealProgressChanged();
    if (ok)
        Q_EMIT lockChanged();
    Q_EMIT secretFinished(ok, ok ? QString() : why);
}

bool Vault::sealDirty(const QString &dir, const QByteArray &oldKey, const QByteArray &fresh, QString *why)
{
    const QString stage = dir + QStringLiteral(".restore");
    for (const QString &name : std::as_const(m_dirty)) {
        if (!safeName(name) || name == QLatin1String("vault.key"))
            continue;
        const QString from = dir + QLatin1Char('/') + name, target = stage + QLatin1Char('/') + name;
        if (!QFileInfo::exists(from)) {
            QFile::remove(target);   // removed meanwhile: gone from the new folder too
            continue;
        }
        QByteArray b;
        if (!readTrying(from, &b)) {
            *why = QStringLiteral("Huginn couldn't read %1 just now, so nothing changed. Try again in a moment.").arg(fileWords(name));
            return false;
        }
        QByteArray out = b;
        if (b.startsWith(kMagic)) {
            bool opened = false;
            QByteArray plain = open(oldKey, b.mid(kMagic.size()), &opened);
            if (opened)
                out = kMagic + seal(fresh, plain);
            plain.fill(0);
        }
        b.fill(0);
        QDir().mkpath(QFileInfo(target).absolutePath());
        QSaveFile f(target);
        if (!f.open(QIODevice::WriteOnly) || f.write(out) != out.size() || !f.commit()) {
            *why = QStringLiteral("Couldn't write your chats sealed with the new lock, so nothing changed. Is the disk full?");
            return false;
        }
    }
    // Saves went on beside the sealing thread: anything it carried across that isn't here any more (a save's temporary
    // file, a file removed meanwhile) goes from the new folder too.
    if (!m_dirty.isEmpty()) {
        const QDir base(stage);
        QDirIterator it(stage, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
        while (it.hasNext()) {
            const QString full = it.next();
            const QString name = base.relativeFilePath(full);
            if (name != QLatin1String("vault.key") && name != kComplete && !QFileInfo::exists(dir + QLatin1Char('/') + name))
                QFile::remove(full);
        }
    }
    return true;
}

void Vault::flushHeld()
{
    QHash<QString, Held> held;
    held.swap(m_held);
    for (auto it = held.begin(); it != held.end(); ++it) {
        if (it->removed) {
            remove(it.key());
        } else if (it->make) {
            writeLater(it.key(), std::move(it->make));
        } else {
            if (!write(it.key(), it->data))
                Q_EMIT saveLost(it.key());
            it->data.fill(0);
        }
    }
}

QString Vault::finishSwap(const QString &dir)
{
    const QString clean = QDir::cleanPath(dir);
    const QString stage = clean + QStringLiteral(".restore"), old = clean + QStringLiteral(".old");
    const bool hasOld = QFileInfo(old).isDir(), hasStage = QFileInfo(stage).isDir();
    const bool stageComplete = hasStage && QFileInfo::exists(stage + QLatin1Char('/') + kComplete);
    // A "chat" folder made again while the real one was aside (no vault.key, no mark) holds nothing: it counts as
    // absent, so the swap is finished or undone as if cut off between the renames.
    if (hasOld && strayChat(clean)) {
        QDir(clean).removeRecursively();
        CrashLog::note(QStringLiteral("vault: an empty chat folder made during a swap was cleared"));
    }
    const bool hasDir = QFileInfo(clean).isDir();
    QString said;
    if (hasDir && QFileInfo::exists(clean + QLatin1Char('/') + kComplete)) {
        // Swapped in; only the tidying was left (round 16: the mark stays until the old folder is really gone).
        if (!hasOld || QDir(old).removeRecursively()) {
            QFile::remove(replacedMark(old));
            QFile::remove(clean + QLatin1Char('/') + kComplete);
            CrashLog::note(QStringLiteral("vault: finished tidying up after a restore"));
        } else {
            CrashLog::note(QStringLiteral("vault: the folder a restore put aside couldn't be deleted yet; tried again next time"));
        }
    } else if (!hasDir && hasOld) {
        if (stageComplete && QDir().rename(stage, clean)) {
            // Cut off between the two renames, with every new file written: finished (round 16: marked done with
            // first, so a held file only delays its going).
            QFile mark(replacedMark(old));
            if (mark.open(QIODevice::WriteOnly))
                mark.write("1");
            mark.close();
            if (QDir(old).removeRecursively()) {
                QFile::remove(replacedMark(old));
                QFile::remove(clean + QLatin1Char('/') + kComplete);
            }
            CrashLog::note(QStringLiteral("vault: finished a restore that was cut off"));
            said = QStringLiteral("A restore was cut off, and is finished now.");
        } else if (QDir().rename(old, clean)) {
            QDir(stage).removeRecursively();
            CrashLog::note(QStringLiteral("vault: undid a restore that was cut off"));
            said = QStringLiteral("A restore didn't finish, so your chats are as they were before it. Restore again to try once more.");
        } else {
            said = QStringLiteral("A restore didn't finish. Your previous chats are in %1.").arg(QDir::toNativeSeparators(old));
        }
    } else if (hasDir && hasOld) {
        // The old folder aside but not yet deleted, with no sign the new one is complete. Round 16: marked done with
        // (the swap finished, a held file kept it): it goes now. Otherwise kept, never deleted unseen, but moved out of
        // the way (chat-kept-<date>), so it never blocks a restore or Set a lock.
        QString kept, why;
        if (!clearOld(old, &kept, &why))
            said = why;
        else if (!kept.isEmpty())
            said = QStringLiteral("Chats from before an earlier restore or lock change were kept in %1. They open with the lock they had. "
                                  "If you don't need them, delete that folder.")
                       .arg(QDir::toNativeSeparators(kept));
    }
    if (QFileInfo(stage).isDir() && QFileInfo(clean).isDir()) {
        // A stage that never went in: what's in place is untouched; the backup can be restored again.
        QDir(stage).removeRecursively();
    }
    return said;
}

QVariantMap Vault::backupInfo() const
{
    qint64 all = 0, media = 0;
    const QString dir = dataDir();
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString full = it.next();
        const QString name = base.relativeFilePath(full);
        if (isBigPiece(name))
            continue;   // never in a backup
        const qint64 n = it.fileInfo().size();
        all += n;
        if (isMedia(name))
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
    if (m_sealing) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Huginn is sealing your chats for the new lock. Back up once it's done."));
        return;
    }
    // Round 17 (B1): only after the current lock was given again (Settings › Back up asks). Used up once it's saved.
    if (!lockConfirmed(QStringLiteral("backup"))) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Confirm your lock first: close this, then Back up again."));
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
    auto skipped = std::make_shared<int>(0);
    m_busy = true;
    m_lastBackup.clear();
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        QStringList unreadable, damaged;
        Files all = collect(key, dir, &unreadable, &damaged);
        // Round 17: a file held by another program just now is never left out without a word: nothing is saved.
        if (!unreadable.isEmpty()) {
            for (auto &e : all)
                e.second.fill(0);
            *result = QStringLiteral("Huginn couldn't read %1 just now, so no backup was saved. Another program, like antivirus or a backup "
                                     "tool, may be using it. Try again in a moment.").arg(fileWords(unreadable.first()));
            return;
        }
        *skipped = int(damaged.size());
        Files files;
        for (auto &e : all)
            // Daily Havamal's own translation (DailyVerse::kFile) stays on this computer only.
            if (e.first != QLatin1String("move-out.bin") && e.first != QLatin1String("havamal-own.json") && (withFiles || !isMedia(e.first)))
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
            // Round 17: Windows' Controlled folder access refuses unsigned programs in Documents, Desktop and Pictures.
            const bool denied = f.error() == QFileDevice::OpenError || f.error() == QFileDevice::PermissionsError;
            *result = denied && Portable::controlledFolder(path)
                          ? QStringLiteral("Windows didn't let Huginn save there. Controlled folder access (Windows Security › Virus & threat "
                                           "protection › Ransomware protection) protects that folder. Save to Downloads or a USB drive instead.")
                          : denied ? QStringLiteral("Huginn isn't allowed to save there. Pick another folder, like Downloads or a USB drive.")
                                   : QStringLiteral("Couldn't save it there. Is the disk full, or the folder read-only?");
            return;
        }
        *size = bytes.size();
        *good = true;
    });
    t->setObjectName(QStringLiteral("huginn-backup"));
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, result, good, size, skipped, path, withFiles] {
        t->deleteLater();
        if (!self)
            return;
        if (*good) {
            self->m_grantFor.clear();   // round 17: the lock given for it is used up
            self->m_grantUntil = 0;
        }
        if (*good)
            self->m_lastBackup = {{QStringLiteral("path"), QDir::toNativeSeparators(QFileInfo(path).absoluteFilePath())},
                                  {QStringLiteral("folder"), QDir::toNativeSeparators(QFileInfo(path).absolutePath())},
                                  {QStringLiteral("size"), double(*size)}, {QStringLiteral("files"), withFiles}};
        self->m_busy = false;
        Q_EMIT self->changed();
        const QString saved = *skipped > 0 ? QStringLiteral("Backup saved. %1 damaged %2 couldn't be opened, so %3 left out.")
                                                 .arg(*skipped).arg(*skipped == 1 ? QStringLiteral("file") : QStringLiteral("files"),
                                                                    *skipped == 1 ? QStringLiteral("it was") : QStringLiteral("they were"))
                                           : QStringLiteral("Backup saved.");
        Q_EMIT self->done(QStringLiteral("backup"), *good, *good ? saved : *result);
    });
    t->start();
}

void Vault::openBackup(const QString &file, const QString &password)
{
    if (m_busy)
        return;
    if (m_sealing) {
        Q_EMIT done(QStringLiteral("open"), false, QStringLiteral("Huginn is sealing your chats for the new lock. Try again once it's done."));
        return;
    }
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
    {
        // Pieces still being kept on another thread (anyThreadWriter) stop here.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
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
    if (m_sealing) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Huginn is sealing your chats for the new lock. Restore once it's done."));
        return;
    }
    // Round 17 (security m6): replacing this account needs the current lock given again (Settings › Restore asks).
    if (!takeGrant(QStringLiteral("restore"))) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Confirm your lock first: close this, then Restore again."));
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
    {
        // Pieces still being kept on another thread (anyThreadWriter) stop here.
        QMutexLocker w(&m_shared->writing);
        ++m_shared->gen;
    }
    if (m_busy || m_opened.empty()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Open the backup again first."));
        return;
    }
    // Only from behind the lock: open, the usual restore keeps a copy instead (Settings › Backup).
    if (m_restarting || !exists() || !hasLock() || !screenLocked()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Huginn isn't locked. Use Settings › Devices and backup instead."));
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
    // The backup has no lock in it: Huginn opens without one, and asks for a new one before anything shows. The new key
    // goes in with the restored files, in one swap, so the files and the key that opens them are never apart.
    QByteArray keyBytes = QJsonDocument(QJsonObject{{QStringLiteral("mode"), QStringLiteral("open")},
                                                    {QStringLiteral("key"), QString::fromLatin1(fresh.toBase64())},
                                                    {QStringLiteral("needsLock"), true}})
                              .toJson(QJsonDocument::Compact);
    const bool swapped = replaceAll(fresh, dataDir(), files, &error, keyBytes);
    keyBytes.fill(0);
    fresh.fill(0);
    if (!swapped) {
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
    // The wrong tries and their wait belonged to the old lock (this took the backup and its Backup password, and erased
    // what was here). Erase after too many tries stays as it was.
    rightTry();
    settings().remove(QStringLiteral("chatlock/strongerAsked"));
    settings().remove(QStringLiteral("chatlock/recall"));
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
        check("longer videos' pieces stay out", isBigPiece(QStringLiteral("photos/abc.v/00000")) && isBigPiece(QStringLiteral("photos/abc.v/size"))
                                                    && !isBigPiece(QStringLiteral("photos/abc")) && !isBigPiece(QStringLiteral("photos/abc.v")));
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
    kept.close();
    keptKey.close();
    // Whole folders swap: nothing is left beside it, and a new lock goes in with the files (a restore behind the lock).
    check("a restore swaps whole folders", replaceAll(keyB, dst, back, &error, QByteArrayLiteral("new lock")) && collect(keyB, dst) == want
                                               && !QFileInfo::exists(dst + QStringLiteral(".old")) && !QFileInfo::exists(dst + QStringLiteral(".restore"))
                                               && !QFileInfo::exists(dst + QStringLiteral("/.restore-complete")) && [&] {
                                                      QFile k(dst + QStringLiteral("/vault.key"));
                                                      return k.open(QIODevice::ReadOnly) && k.readAll() == "new lock";
                                                  }());
    // Cut off between the two renames: a complete new folder is finished at the next start, an incomplete one undone.
    {
        const QString cut = tmp.path() + QStringLiteral("/c/chat");
        check("cut-off setup", replaceAll(keyA, cut, stale, &error));
        QDir().rename(cut, cut + QStringLiteral(".old"));
        replaceAll(keyB, cut + QStringLiteral("-stage"), back, &error);
        QDir().rename(cut + QStringLiteral("-stage"), cut + QStringLiteral(".restore"));
        QFile mark(cut + QStringLiteral(".restore/.restore-complete"));
        if (mark.open(QIODevice::WriteOnly))
            mark.write("1");
        mark.close();
        const QString said = finishSwap(cut);
        check("a cut-off restore with every file written is finished", !said.isEmpty() && collect(keyB, cut) == want
                                                                            && !QFileInfo::exists(cut + QStringLiteral(".old"))
                                                                            && !QFileInfo::exists(cut + QStringLiteral("/.restore-complete")));
        QDir().rename(cut, cut + QStringLiteral(".old"));
        replaceAll(keyA, cut + QStringLiteral("-half"), stale, &error);
        QDir().rename(cut + QStringLiteral("-half"), cut + QStringLiteral(".restore"));   // no mark: not complete
        const QString undone = finishSwap(cut);
        check("a cut-off restore that wasn't complete is undone", !undone.isEmpty() && collect(keyB, cut) == want
                                                                       && !QFileInfo::exists(cut + QStringLiteral(".restore"))
                                                                       && !QFileInfo::exists(cut + QStringLiteral(".old")));
        check("nothing to finish says nothing", finishSwap(cut).isEmpty());
        // Round 15 (R6): an empty "chat" made again while the real one was aside never turns a finished restore into
        // "previous chats are in chat.old"; the complete stage goes in, with its new lock.
        QFile ownKey(cut + QStringLiteral("/vault.key"));
        if (ownKey.open(QIODevice::WriteOnly))
            ownKey.write("open key");
        ownKey.close();
        QDir().rename(cut, cut + QStringLiteral(".old"));
        replaceAll(keyB, cut + QStringLiteral("-again"), back, &error, QByteArrayLiteral("new lock"));
        QDir().rename(cut + QStringLiteral("-again"), cut + QStringLiteral(".restore"));
        QFile again(cut + QStringLiteral(".restore/.restore-complete"));
        if (again.open(QIODevice::WriteOnly))
            again.write("1");
        again.close();
        QDir().mkpath(cut + QStringLiteral("/photos"));   // what an image thread's dataDir() used to make
        const QString stray = finishSwap(cut);
        check("an empty chat folder made during a swap is cleared and the restore finished",
              stray.contains(QStringLiteral("finished")) && collect(keyB, cut) == want && !QFileInfo::exists(cut + QStringLiteral(".old"))
                  && !QFileInfo::exists(cut + QStringLiteral(".restore")) && [&] {
                         QFile k(cut + QStringLiteral("/vault.key"));
                         return k.open(QIODevice::ReadOnly) && k.readAll() == "new lock";
                     }());
        // Round 16: chats left aside are never deleted by the next swap, and never block it: they are moved aside as
        // chat-kept-<date> (they still open), and the swap goes ahead.
        const QString parent = QFileInfo(cut).absolutePath();
        const auto keptDirs = [&parent] { return QDir(parent).entryList({QStringLiteral("chat-kept-*")}, QDir::Dirs | QDir::NoDotAndDotDot); };
        QDir().mkpath(cut + QStringLiteral(".old"));
        QFile left(cut + QStringLiteral(".old/chats.json"));
        if (left.open(QIODevice::WriteOnly))
            left.write("previous");
        left.close();
        QString refused;
        const bool went = replaceAll(keyB, cut, back, &refused);
        const QStringList keptNow = keptDirs();
        check("a swap never deletes chats left aside, and isn't blocked by them",
              went && keptNow.size() == 1 && QFileInfo::exists(parent + QLatin1Char('/') + keptNow.first() + QStringLiteral("/chats.json"))
                  && !QFileInfo::exists(cut + QStringLiteral(".old")) && collect(keyB, cut) == want);
        for (const QString &k : keptNow)
            QDir(parent + QLatin1Char('/') + k).removeRecursively();
        // A folder a finished swap put aside but couldn't delete (marked done with) goes at the next start, with
        // nothing said; one without the mark is kept as chat-kept-<date>, and the start says where.
        QDir().mkpath(cut + QStringLiteral(".old/photos"));
        QFile done(cut + QStringLiteral(".old.replaced"));
        if (done.open(QIODevice::WriteOnly))
            done.write("1");
        done.close();
        const QString tidied = finishSwap(cut);
        check("a leftover a finished swap marked goes at the next start", tidied.isEmpty() && !QFileInfo::exists(cut + QStringLiteral(".old"))
                                                                               && !QFileInfo::exists(cut + QStringLiteral(".old.replaced")) && keptDirs().isEmpty());
        QDir().mkpath(cut + QStringLiteral(".old"));
        const QString keptSaid = finishSwap(cut);
        const QStringList keptStart = keptDirs();
        check("an unmarked leftover is kept aside at the start and said", keptSaid.contains(QStringLiteral("chat-kept-")) && keptStart.size() == 1
                                                                            && !QFileInfo::exists(cut + QStringLiteral(".old")) && collect(keyB, cut) == want);
        for (const QString &k : keptStart)
            QDir(parent + QLatin1Char('/') + k).removeRecursively();
        // Then Set a lock (a swap) works again.
        check("a swap works once the leftover is cleared", replaceAll(keyB, cut, back, &refused) && collect(keyB, cut) == want);
    }
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
    // 1.42: the wrong-try steps, the lock-state file and the unlock password.
    all &= lockRulesSelfTest();
    return all;
}

bool Vault::lockRulesSelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("lock rules %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    const qint64 min = 60000, hour = 60 * min;
    // The steps: 5 minutes, 1 hour, 12 hours, then 24 hours again and again.
    check("no wait before 5", waitAfter(4, false) == 0 && waitAfter(1, true) == 0);
    check("5 wrong: 5 minutes", waitAfter(5, false) == 5 * min && waitAfter(6, false) == 0);
    check("5 more: 1 hour", waitAfter(10, false) == hour);
    check("5 more: 12 hours", waitAfter(15, false) == 12 * hour);
    check("5 more: 24 hours", waitAfter(20, false) == 24 * hour && waitAfter(20, true) == 24 * hour);
    check("24 hours repeats with erase off", waitAfter(25, false) == 24 * hour && waitAfter(30, false) == 24 * hour && waitAfter(500, false) == 24 * hour
                                                 && !erasesAt(25, false) && !erasesAt(1000, false));
    check("erase on: the 5th try after the 24-hour wait erases", !erasesAt(24, true) && erasesAt(25, true) && waitAfter(25, true) == 0);
    check("last stage counts down", lastLeft(20, true) == 5 && lastLeft(24, true) == 1 && lastLeft(19, true) == 0 && lastLeft(22, false) == 0);
    bool lockApp = false;
    const int inApp = nextFails(24, true, true, &lockApp);
    check("an in-app try never erases: it locks instead", inApp == 24 && lockApp);
    check("a lock-screen try counts on", nextFails(24, true, false, &lockApp) == 25 && !lockApp && nextFails(24, false, true, &lockApp) == 25 && !lockApp);
    // The words: each step spelled out, never a total like "25".
    check("wait words", waitText(10, false) == QLatin1String("Wait 1 hour. Next: 12 hours after 5 more wrong tries.")
                            && waitText(20, false) == QLatin1String("Wait 24 hours. Next: 24 hours again after 5 more wrong tries.")
                            && waitText(20, true) == QLatin1String("Wait 24 hours. Next: the last 5 tries. If all 5 are wrong, everything is erased for good."));
    check("last tries words", triesText(20, true) == QLatin1String("Last 5 tries. If all 5 are wrong, everything is erased for good.")
                                  && triesText(23, true) == QLatin1String("Last 2 tries. If all 2 are wrong, everything is erased for good.")
                                  && triesText(24, true) == QLatin1String("Last try. If it's wrong, everything is erased for good."));
    check("tries words", triesText(0, false).isEmpty() && triesText(1, false) == QLatin1String("4 more wrong tries, then a wait of 5 minutes.")
                             && triesText(9, false) == QLatin1String("1 more wrong try, then a wait of 1 hour."));
    check("time left words", leftText(hour) == QLatin1String("Try again in 1 hour.") && leftText(24 * hour - 90000) == QLatin1String("Try again in 23 hours 59 minutes.")
                                 && leftText(39500) == QLatin1String("Try again in 40 seconds."));
    bool no25 = true;
    for (int f = 0; f <= 60; ++f)
        for (bool e : {false, true})
            no25 = no25 && !triesText(f, e).contains(QLatin1String("25")) && (waitAfter(f, e) == 0 || !waitText(f, e).contains(QLatin1String("25")));
    check("never a total", no25);

    // The lock-state file: a try is on disk before its check, so a kill mid-check still counts.
    QTemporaryDir tmp;
    const QString file = tmp.path() + QStringLiteral("/lock-state.json");
    const QString here = QStringLiteral("this-start"), there = QStringLiteral("another-start");
    qint64 mono = 3600000, clock = qint64(1700000000) * 1000;
    LockState st;
    bool moved = false;
    for (int i = 0; i < 4; ++i)
        st = countedTry(st, false, mono, clock, here, &lockApp);
    check("four wrong: no wait", st.fails == 4 && lockLeft(&st, mono, clock, here, &moved) == 0);
    st = countedTry(st, false, mono, clock, here, &lockApp);
    check("the try is written before the check", writeLockFile(file, st));
    LockState after;   // Huginn killed and started again: what's on disk
    check("a kill mid-check still counts, with its wait", readLockFile(file, &after) && after.fails == 5 && after.left == 5 * min
                                                              && [&] { LockState a = after; return lockLeft(&a, mono, clock, here, &moved) == 5 * min; }());
    // The clock moved a day back or forward: never shorter.
    check("a changed clock can't skip it", [&] {
        LockState a = after, b = after;
        return lockLeft(&a, mono + min, clock - 24 * hour, here, &moved) >= 4 * min && lockLeft(&b, mono + min, clock + 24 * hour, here, &moved) >= 4 * min;
    }());
    // Round 19: sleep counts (the time since start-up includes it), so 3 minutes asleep are 3 minutes of the wait.
    LockState slept = after;
    check("sleep counts toward it", lockLeft(&slept, mono + 3 * min, clock + 3 * min, here, &moved) == 2 * min && moved && slept.left == 2 * min);
    // A restart: it goes on from what was left when last saved, never from 0.
    check("a restart goes on from what was left", lockLeft(&slept, 5000, clock + 10 * min, there, &moved) == 2 * min && moved && slept.boot == there);
    check("then it runs out and is forgotten", lockLeft(&slept, 5000 + 2 * min, clock + 12 * min, there, &moved) == 0 && slept.wait == 0 && moved);
    // Round 19 (M3 Windows): the folder opened on another computer that has been on for days: the wait goes on.
    LockState carried = after;
    check("another computer's long time since start-up can't skip it", lockLeft(&carried, mono + 100 * hour, clock + min, there, &moved) == 5 * min);
    // The longer reading wins: what was left, or when the clock says it ends.
    LockState late = after;
    late.left = min;
    check("the longer of what was left and the clock is taken", lockLeft(&late, 5000, clock, there, &moved) == 5 * min);
    // On to the end with erase on: 24 hours after 20, then the 25th erases (and a kill there still erases on the next start).
    LockState on;
    on.erase = true;
    for (int i = 0; i < 20; ++i)
        on = countedTry(on, false, mono, clock, here, &lockApp);
    check("erase on: 24 hours after 20", on.fails == 20 && lockLeft(&on, mono, clock, here, &moved) == 24 * hour && !erasesAt(on.fails, on.erase));
    mono += 24 * hour;
    clock += 24 * hour;
    for (int i = 0; i < 4; ++i)
        on = countedTry(on, false, mono, clock, here, &lockApp);
    check("erase on: 24 wrong, nothing erased", on.fails == 24 && !erasesAt(on.fails, on.erase));
    const LockState inside = countedTry(on, true, mono, clock, here, &lockApp);
    check("erase on: an in-app try stops short and locks", inside.fails == 24 && lockApp);
    on = countedTry(on, false, mono, clock, here, &lockApp);
    LockState onDisk;
    check("erase on: the 25th erases, even after a kill", writeLockFile(file, on) && readLockFile(file, &onDisk) && erasesAt(onDisk.fails, onDisk.erase));
    LockState off;
    for (int i = 0; i < 40; ++i) {
        mono += 24 * hour;
        clock += 24 * hour;
        off = countedTry(off, false, mono, clock, here, &lockApp);
    }
    check("erase off: never erased, 24 hours again", off.fails == 40 && !erasesAt(off.fails, off.erase) && lockLeft(&off, mono, clock, here, &moved) == 24 * hour);
    // Round 19 (FIX 15): a count that can't be written is told apart (a folder where the file goes).
    QDir().mkpath(tmp.path() + QStringLiteral("/blocked/lock-state.json"));
    check("a count that can't be written fails", !writeLockFile(tmp.path() + QStringLiteral("/blocked/lock-state.json"), on));

    // The unlock password: a second wrap of the same key. The pattern opens its wrap, the typed password its own.
    {
        const QByteArray key = random(32);
        const QByteArray blob = lockBlob(QStringLiteral("pattern:0,1,2,5,8,7"), true, key, QStringLiteral("spare words"));
        const QJsonObject o = QJsonDocument::fromJson(blob).object();
        bool a = false, b = false, c = true, d = true;
        const QByteArray byPattern = openLock(o, QStringLiteral("pattern:0,1,2,5,8,7"), &a);
        const QByteArray bySpare = openLock(o, QStringLiteral("spare words"), &b);
        openLock(o, QStringLiteral("pattern:0,1,2,5,8,6"), &c);
        openLock(o, QStringLiteral("other words"), &d);
        check("either the pattern or the unlock password opens", a && b && byPattern == key && bySpare == key && !c && !d);
        check("never stored as it is", !blob.contains("spare words") && !blob.contains("0,1,2,5,8,7") && o.value(QStringLiteral("spare")).isObject());
        const QJsonObject plain = QJsonDocument::fromJson(lockBlob(QStringLiteral("a password"), false, key, QStringLiteral("ignored"))).object();
        check("a password lock has no unlock password", !plain.contains(QStringLiteral("spare")));
        // Round 19: new wraps get today's numbers; 1.42's first ones are made again once they open.
        check("new wraps aren't old", !lockIsOld(o) && !lockIsOld(o.value(QStringLiteral("spare")).toObject()) && !lockIsOld(plain));
        check("1.42's first wraps are old", lockIsOld(QJsonObject{{QStringLiteral("kdf"), QStringLiteral("argon2id")}, {QStringLiteral("m"), 65536}, {QStringLiteral("t"), 3}})
                                                && lockIsOld(QJsonObject{{QStringLiteral("rounds"), 310000}}));
    }
    // Round 19 (FIX 16): erase writes vault.key over before deleting, and checks what's left.
    {
        QTemporaryDir root;
        const auto put = [&](const QString &name, const QByteArray &b) {
            QDir().mkpath(QFileInfo(root.path() + QLatin1Char('/') + name).absolutePath());
            QFile f(root.path() + QLatin1Char('/') + name);
            return f.open(QIODevice::WriteOnly) && f.write(b) == b.size();
        };
        put(QStringLiteral("chat/vault.key"), QByteArrayLiteral("{\"mode\":\"password\"}"));
        put(QStringLiteral("chat-kept-2026/vault.key"), QByteArrayLiteral("{\"mode\":\"open\"}"));
        put(QStringLiteral("tls/hidden/now/key.pem"), "k");
        put(QStringLiteral("tor/data/state"), "s");
        put(QStringLiteral("voices/f/model.onnx"), "v");
        put(QStringLiteral("ring-key.json.a1b2c3"), "r");
        put(QStringLiteral("other.txt"), "not ours");
        check("a live key file is told from one written over", liveKeyFile(root.path() + QStringLiteral("/chat/vault.key")));
        shred(root.path() + QStringLiteral("/chat/vault.key"));
        check("written over, it opens nothing", !liveKeyFile(root.path() + QStringLiteral("/chat/vault.key")));
        const QStringList left = wipe(root.path(), eraseNames(root.path()));
        const QStringList now = QDir(root.path()).entryList(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden);
        check("erase takes the chats, kept copies, certificates, Tor and voices", left.isEmpty() && now == QStringList{QStringLiteral("other.txt")});
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

    // Round 17 (B1): Back up, Restore, Move and linking take a grant only the current lock gives, once, for that one thing.
    const QString secret = QStringLiteral("stability self-test only");
    if (v.hasLock()) {
        const bool none = !v.lockConfirmed(QStringLiteral("backup")) && !v.takeGrant(QStringLiteral("backup"));
        const bool wrong = !v.confirmLock(QStringLiteral("not it at all"), QStringLiteral("backup")) && !v.lockConfirmed(QStringLiteral("backup"));
        const bool right = v.confirmLock(secret, QStringLiteral("backup")) && v.lockConfirmed(QStringLiteral("backup"))
                           && !v.lockConfirmed(QStringLiteral("link")) && !v.takeGrant(QStringLiteral("moveaway"));
        const bool once = v.takeGrant(QStringLiteral("backup")) && !v.takeGrant(QStringLiteral("backup"));
        check("a backup, move or link needs the current lock given again, once", none && wrong && right && once);
    }

    // Round 17: setting a lock after it was off seals every file again on its own thread; what is saved meanwhile goes to
    // disk at once and is sealed again before the swap (round 18 m2); nothing is left behind, and every file opens with
    // the new key.
    if (v.hasLock() && v.turnOffLock(secret)) {
        const QString a = QStringLiteral("selftest-chats.json"), p = QStringLiteral("photos/selftest-photo");
        const QByteArray big(300000, 'p');
        v.write(a, QByteArrayLiteral("{\"a\":1}"));
        v.write(p, big);
        const QByteArray before = v.m_key;
        bool finished = false, ok = false;
        QEventLoop loop;
        QObject::connect(&v, &Vault::secretFinished, &loop, [&](bool good, const QString &) {
            finished = true;
            ok = good;
            loop.quit();
        });
        const bool started = !v.setSecret(secret, false) && v.sealing();
        // Saved while it seals: on disk at once (round 18 m2: a crash meanwhile loses nothing), read back as saved, and
        // sealed again with the new key just before the swap.
        const bool held = v.write(QStringLiteral("selftest-meanwhile.json"), QByteArrayLiteral("[1]"))
                          && QFileInfo::exists(v.path(QStringLiteral("selftest-meanwhile.json")))
                          && v.read(QStringLiteral("selftest-meanwhile.json")) == QByteArrayLiteral("[1]");
        if (started)
            QTimer::singleShot(60000, &loop, &QEventLoop::quit);
        if (started && !finished)
            loop.exec();
        check("setting a lock after it was off seals again in the background", started && held && finished && ok && !v.sealing() && v.hasLock());
        check("every file opens with the new key, the one saved meanwhile too",
              v.m_key != before && v.read(a) == QByteArrayLiteral("{\"a\":1}") && v.read(p) == big
                  && v.read(QStringLiteral("selftest-meanwhile.json")) == QByteArrayLiteral("[1]"));
        v.remove(a);
        v.remove(p);
        v.remove(QStringLiteral("selftest-meanwhile.json"));
    }

    // Round 17: collect() never leaves a file out without a word: a damaged one is named.
    {
        QTemporaryDir tmp;
        const QByteArray key(32, '\x33');
        const QString dir = tmp.path() + QStringLiteral("/chat");
        QDir().mkpath(dir);
        QFile good(dir + QStringLiteral("/good.json")), bad(dir + QStringLiteral("/bad.json"));
        if (good.open(QIODevice::WriteOnly))
            good.write(kMagic + seal(key, QByteArrayLiteral("1")));
        good.close();
        if (bad.open(QIODevice::WriteOnly))
            bad.write(kMagic + QByteArrayLiteral("not sealed with this key, long enough to look like it"));
        bad.close();
        QStringList unreadable, damaged;
        const Files got = collect(key, dir, &unreadable, &damaged);
        check("a file that doesn't open is named, never dropped silently",
              got.size() == 1 && got.front().first == QLatin1String("good.json") && damaged == QStringList{QStringLiteral("bad.json")} && unreadable.isEmpty());
    }

    // Locking: only when asked (Lock now, the panic lock, Lock and close).
    check("not locked on its own", !v.screenLocked());
    if (v.hasLock())
        v.confirmLock(secret, QStringLiteral("link"));
    v.lockScreen();
    check("locks when asked", v.screenLocked() == v.hasLock());
    check("locking drops a grant not yet used", !v.hasLock() || !v.lockConfirmed(QStringLiteral("link")));
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
    // A file that can't be written (here a folder that is really a file) is tried again, then said, never dropped silently.
    {
        s_quickRetries = true;
        SaveQueue q;
        std::atomic<int> lostCount{0}, made{0};
        q.lost = [&lostCount](const QString &) { ++lostCount; };
        q.start();
        {
            QFile blocker(tmp.filePath(QStringLiteral("blocker")));
            if (blocker.open(QIODevice::WriteOnly))
                blocker.write("x");
        }
        SaveQueue::Job j;
        j.name = QStringLiteral("stuck.json");
        j.file = tmp.filePath(QStringLiteral("blocker/stuck.json"));
        j.key = key;
        j.make = [&made] { ++made; return QByteArray("stuck"); };
        q.put(std::move(j));
        QElapsedTimer t;
        t.start();
        while (lostCount.load() == 0 && t.elapsed() < 15000)
            QThread::msleep(20);
        check("a save that can't be written is tried again, then said", lostCount.load() == 1 && made.load() == 4);
        check("the saving thread stops after a lost save", q.finish(5000));
        s_quickRetries = false;
    }
    return all;
}
