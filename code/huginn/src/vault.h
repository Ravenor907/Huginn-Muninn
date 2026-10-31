#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QObject>
#include <QString>
#include <QTimer>
#include <QVariant>
#include <functional>
#include <utility>
#include <vector>

// Everything Huginn's chat keeps (messages, photos, voice, contacts, keys) is encrypted on disk with
// one random 256-bit data key (AES-256-GCM). That key is locked by the person's pattern or password
// (Argon2id, or scrypt where OpenSSL has no Argon2id; a PBKDF2 lock is rewrapped on the next unlock).
// Nothing outside Huginn is involved. A lock is set up first; while locked, nothing is readable, even
// by Huginn. The lock can be turned off in Settings (with a warning): the data key then sits in
// vault.key itself ("mode": "open"), in Huginn's own data folder, and Huginn opens without asking.
class Vault : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool exists READ exists NOTIFY changed)
    Q_PROPERTY(bool locked READ locked NOTIFY changed)
    Q_PROPERTY(bool usesPattern READ usesPattern NOTIFY changed)
    // A pattern or password guards the data key (false: no vault yet, or the lock is off).
    Q_PROPERTY(bool hasLock READ hasLock NOTIFY changed)
    Q_PROPERTY(int waitSeconds READ waitSeconds NOTIFY changed)

public:
    explicit Vault(QObject *parent = nullptr);

    bool exists() const;          // a lock has been set
    bool locked() const { return m_key.isEmpty(); }
    bool usesPattern() const;
    bool hasLock() const;
    // Turns the lock off (must be unlocked, and the current pattern or password given again; a wrong one counts as a
    // wrong try): the data key is kept beside the chats, unprotected. Panic lock and locking when away stop working.
    // setSecret() turns it on again, with a new data key (everything is sealed again with it).
    Q_INVOKABLE bool turnOffLock(const QString &secret);
    // At start (main.cpp, once everything listens for opened()): a vault without a lock opens by itself.
    void openWithoutLock();
    int waitSeconds() const;      // after too many wrong tries, seconds until the next try

    // Opens with the pattern ("pattern:0,3,6,...") or password. False if wrong.
    Q_INVOKABLE bool unlock(const QString &secret);
    // Sets the lock the first time (creating the data key), or changes it (must be unlocked). Set again after it was
    // off: a new data key, and every file is sealed again with it (the old key sat unprotected on disk).
    // At least 6 pattern dots or 6 characters.
    Q_INVOKABLE bool setSecret(const QString &secret, bool pattern);
    // True once, after unlocking with a lock shorter than new ones may be: suggest a stronger one.
    Q_INVOKABLE bool askStronger();
    // Shows the lock screen again; the data key stays in memory while Huginn runs, so incoming
    // messages are still saved (encrypted) in the background.
    // Without a lock there is nothing to unlock with, so nothing happens.
    Q_INVOKABLE void lockScreen() { if (!hasLock()) return; m_screenLocked = true; Q_EMIT changed(); }
    // Leaving Chat (or Huginn going to the background) starts the clock; back after a minute = locked again.
    // The lock also goes up while still away, once the minute is past, so the window's last picture
    // (shown again on wake before Huginn draws) is already the lock and never the chat.
    Q_INVOKABLE void away() { if (!locked()) { m_awaySince = QDateTime::currentMSecsSinceEpoch(); m_awayTimer.start(); } }
    Q_INVOKABLE void back() { checkAway(); m_awaySince = 0; m_awayTimer.stop(); }
    // Wall clock, so time asleep counts. True when the lock went up.
    bool checkAway() { if (m_awaySince && !m_screenLocked && QDateTime::currentMSecsSinceEpoch() - m_awaySince > kRelockMs) { lockScreen(); return true; } return false; }
    static constexpr qint64 kRelockMs = 60000;
    Q_PROPERTY(bool screenLocked READ screenLocked NOTIFY changed)
    bool screenLocked() const { return m_screenLocked || locked(); }
    // The privacy cover: from the moment Huginn isn't the app in front (or the computer goes to
    // sleep) while Chat is open behind a lock, the window shows nothing of it, so a minimized
    // window, a taskbar preview or the picture shown on wake can't give a chat away. Back within
    // the relock time it simply goes; past it, the lock is up instead.
    Q_PROPERTY(bool covered READ covered NOTIFY changed)
    bool covered() const { return m_covered && exists() && !screenLocked(); }
    // Erases every message, photo, voice message, contact and key, the cache and a move's state. Huginn's other settings stay.
    Q_INVOKABLE void eraseAll();

    // Encrypted files in Huginn's data folder. read() returns empty when missing, locked or damaged;
    // plain files are read once and saved encrypted.
    bool write(const QString &name, const QByteArray &data) const;
    QByteArray read(const QString &name) const;
    void remove(const QString &name) const;
    QString path(const QString &name) const;

    // The math, separate so the self-test can check it.
    static QByteArray seal(const QByteArray &key, const QByteArray &plain);
    static QByteArray open(const QByteArray &key, const QByteArray &data, bool *ok);
    static QByteArray derive(const QString &secret, const QByteArray &salt, int rounds);
    static bool selfTest();

    // ---------------------------------------------------------------- encrypted backup
    // The same file Muninn makes: "MUNB1", a 4-byte big-endian header length, the header JSON, then
    // AES-256-GCM (iv, ciphertext, tag, like seal()) of the files one after another:
    // [uint32 BE name length][name][uint32 BE data length][data]. Names are paths inside the chat folder;
    // data is each file's plain contents. The header says how the password makes the key:
    //   {"v":2, salt, "kdf":"argon2id", m, t, p, created, app}   Argon2id, like the lock (what Huginn writes)
    //   {"v":2, salt, "kdf":"scrypt", N, r, p, created, app}     scrypt (what Muninn writes; Huginn too without Argon2id)
    //   {"v":1, salt, rounds, created, app}                      PBKDF2-SHA256 (still restored)
    // New backups need a password of at least kMinBackupPassword characters.
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString backupFolder READ backupFolder CONSTANT)
    bool busy() const { return m_busy; }
    QString backupFolder() const;
    // Writes huginn-backup-YYYY-MM-DD.mbk to Documents. Answers with done("backup", ok, message).
    Q_INVOKABLE void backup(const QString &password);
    // Called first: Chat drops what is past each chat's message limit, so the backup never holds it.
    std::function<void()> beforeBackup;
    // The .mbk files in Documents, newest first: [{ name, detail, url }] (url is the full path).
    Q_INVOKABLE QVariantList backups() const;
    // Checks the password and reads the backup into memory; done("open", ok, message). Nothing
    // changes until restoreOpened(); forgetOpened() drops it.
    Q_INVOKABLE void openBackup(const QString &file, const QString &password);
    // Replaces everything in Chat with the backup just opened, then restarts Huginn to load it.
    Q_INVOKABLE void restoreOpened();
    Q_INVOKABLE void forgetOpened();

    using Files = std::vector<std::pair<QString, QByteArray>>;
    // The format, separate so --backup-selftest can run it on a temp folder.
    // kdf "pbkdf2" writes the v1 header (310000 rounds), only so --backup-selftest can check it still restores.
    static QByteArray packBackup(const QString &password, const Files &files, const QString &app, const QString &kdf = QString());
    static constexpr int kMinBackupPassword = 12;
    // 0 ok, 1 not a backup or damaged, 2 wrong password (or changed), 3 another app's backup.
    static int unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error);
    static bool safeName(const QString &name);
    // Every file in a chat folder, opened with the data key (vault.key itself stays out).
    static Files collect(const QByteArray &key, const QString &dir);
    // Writes the files, sealed with the data key, into a fresh folder next to dir, then swaps
    // them in: everything in dir except vault.key is replaced.
    static bool replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error);
    static bool backupSelfTest();

    // ---------------------------------------------------------------- moving to another device (cap x1, move.cpp)
    // Everything a backup holds (the settings blob too), read now.
    Files everything() const;
    // Replaces everything here with files (like a restore) and restarts Huginn. False with error when it can't.
    bool takeEverything(const Files &files, QString *error);
    // This computer's chat settings as a backup carries them, with set put in and drop taken out.
    static QByteArray settingsWith(const QVariantMap &set, const QStringList &drop);
    static QString settingsEntry();

    // ---------------------------------------------------------------- panic lock
    // Hold L3 and R3 for a second anywhere in Huginn: Chat locks at once. Main.qml passes every
    // controller press and release here.
    Q_PROPERTY(bool panicLock READ panicLock WRITE setPanicLock NOTIFY changed)
    // Lock the moment Huginn stops being the app in front.
    Q_PROPERTY(bool lockOnLeave READ lockOnLeave WRITE setLockOnLeave NOTIFY changed)
    bool panicLock() const;
    void setPanicLock(bool on);
    bool lockOnLeave() const;
    void setLockOnLeave(bool on);
    Q_INVOKABLE void button(const QString &name, bool down);
    // Lock at once (the keyboard's Ctrl+Shift+Q, or L3 and R3 held for a second). The key is wiped from
    // memory and Huginn starts again, like Muninn.
    Q_INVOKABLE void panic();

Q_SIGNALS:
    void changed();
    void opened();   // the data key is available (first unlock)
    void erased();   // Erase everything (also Move, once done): what lives outside the vault goes too
    void done(const QString &what, bool ok, const QString &message);   // backup, open, restore
    void panicked(); // the panic combo just locked Chat: close anything that could show it

private:
    QString keyFile() const;
    bool writeLock(const QString &secret, bool pattern) const;
    void wrongTry();   // counts a wrong pattern or password (the wait doubles every 5)
    QByteArray m_key;
    bool m_weak = false;   // the lock just opened is shorter than new ones may be
    void leaving();   // Huginn went to the background: cover at once
    void returning(); // in front again: lock if away too long, then uncover
    void sleeping();  // Windows is going to sleep
    void woke();      // and woke up
    bool m_screenLocked = false;
    bool m_covered = false;
    qint64 m_awaySince = 0;
    qint64 m_awayBeforeSleep = 0;   // the away clock as it was when sleep started (0: Chat was open)
    bool m_asleep = false;
    bool m_busy = false;
    bool m_restarting = false;   // restored: nothing may be saved until Huginn starts again
    Files m_opened;
    QTimer m_combo;
    QTimer m_awayTimer;
    bool m_l3 = false;
    bool m_r3 = false;
};
