#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QJsonObject>
#include <QHash>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QString>
#include <QThread>
#include <QTimer>
#include <QVariant>
#include <functional>
#include <memory>
#include <utility>
#include <vector>

class SaveQueue;

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
    // Waits for the saving thread to write everything it was given (and for an unlock still working out the key).
    ~Vault() override;

    bool exists() const;          // a lock has been set
    bool locked() const { return m_key.isEmpty(); }
    bool usesPattern() const;
    bool hasLock() const;
    // Turns the lock off (must be unlocked, and the current pattern or password given again; a wrong one counts as a
    // wrong try): the data key is kept beside the chats, unprotected. Panic lock and Lock now stop working.
    // setSecret() turns it on again, with a new data key (everything is sealed again with it).
    Q_INVOKABLE bool turnOffLock(const QString &secret);
    // At start (main.cpp, once everything listens for opened()): a vault without a lock opens by itself.
    void openWithoutLock();
    // First setup's Skip on the lock page: the data key is made and kept in vault.key itself, as with the lock turned
    // off. Only when there is no vault yet; setSecret() sets a lock later.
    Q_INVOKABLE bool startWithoutLock();
    int waitSeconds() const;      // after too many wrong tries, seconds until the next try

    // Opens with the pattern ("pattern:0,3,6,...") or password. False if wrong.
    Q_INVOKABLE bool unlock(const QString &secret);
    // The same, without holding up the window: the key is worked out (and, the first time, the saved files are read
    // and opened) on a thread of its own while the lock screen shows a ring; unlockFinished(ok) tells how it went.
    Q_INVOKABLE void unlockInBackground(const QString &secret);
    Q_PROPERTY(bool unlocking READ unlocking NOTIFY changed)
    bool unlocking() const { return m_unlocking; }
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
    // Huginn never locks on its own: only Lock now, the panic lock, or Lock and close when leaving (Main.qml) do.
    // Restored after "Forgot it?" also counts: nothing shows until a new lock is set (lockNeeded).
    Q_PROPERTY(bool screenLocked READ screenLocked NOTIFY changed)
    bool screenLocked() const { return m_screenLocked || m_lockNeeded || locked(); }
    // Restored after "Forgot it?": open, but a new lock must be set before any chat shows (no Skip). Kept in vault.key
    // ("needsLock", never in a backup), so a restart still asks; only setSecret() clears it.
    Q_PROPERTY(bool lockNeeded READ lockNeeded NOTIFY changed)
    bool lockNeeded() const { return m_lockNeeded; }
    // Erases every message, photo, voice message, contact and key, the cache and a move's state, and first setup runs
    // again. Huginn's other settings stay, except on the Windows portable build, where the whole data folder is emptied.
    Q_INVOKABLE void eraseAll();
    // Unlock › Forgot it? › "Erase and link my phone again": eraseAll, then a yes/no note (outside the vault) that the
    // next link's first "sync" says "relink", so the phone offers to replace this PC's old entry (protocol.md 15).
    Q_INVOKABLE void eraseForRelink();

    // Encrypted files in Huginn's data folder. read() returns empty when missing, locked or damaged.
    // A file that is there but couldn't be read (damaged, or held by another program for longer than a few short
    // tries) is never written over: write() refuses it until a read of it works again, so what was loaded as empty
    // can't replace what is on disk. False from write() means nothing was saved.
    enum ReadStatus { ReadOk, ReadMissing, ReadFailed };
    bool write(const QString &name, const QByteArray &data) const;
    // write() on the saving thread ("huginn-saving"): make() runs there (it may use only what it captured: copies,
    // never Chat itself), then the sealing and the safe write. Each file is written in the order asked; a newer save
    // of a file still waiting replaces the older one. read(), write() and remove() of that file wait for it first.
    void writeLater(const QString &name, std::function<QByteArray()> make) const;
    // Waits until everything given to the saving thread is on disk, at most ms. False when it isn't yet.
    bool drainSaves(int ms = 10000) const;
    // --perf-selftest: write order per file, a save that waits for the queue, nothing lost when it stops.
    static bool saveQueueSelfTest();
    QByteArray read(const QString &name) const { return read(name, nullptr); }
    QByteArray read(const QString &name, ReadStatus *status) const;
    bool blocked(const QString &name) const { return m_unreadable.contains(name); }
    // Some saved chat files couldn't be read this run (the screen says so; nothing was changed or erased).
    Q_PROPERTY(bool unreadable READ unreadable NOTIFY changed)
    bool unreadable() const;
    // Reads those files again: once they all read, Huginn starts again to load them. False when some still don't.
    Q_INVOKABLE bool tryReadAgain();
    // read() from any thread (the image providers): never waits on the main thread. The key it reads with is a copy
    // the main thread keeps current.
    std::function<QByteArray(const QString &)> anyThreadReader() const;
    void remove(const QString &name) const;
    QString path(const QString &name) const;
    // read() for another thread: the key and the file are taken now, the file is read and opened when it's called.
    std::function<QByteArray()> reader(const QString &name) const;

    // The math, separate so the self-test can check it.
    static QByteArray seal(const QByteArray &key, const QByteArray &plain);
    static QByteArray open(const QByteArray &key, const QByteArray &data, bool *ok);
    static QByteArray derive(const QString &secret, const QByteArray &salt, int rounds);
    static bool selfTest();
    // --stability-selftest (on a throwaway profile): a file that is there but can't be read is never written over,
    // a missing one is, and a file that reads again can be saved again.
    static bool stabilitySelfTest();

    // ---------------------------------------------------------------- encrypted backup
    // The same file Muninn makes (shared/docs/backup.md): "MUNB1", a 4-byte big-endian header length, the header JSON,
    // then AES-256-GCM (12-byte nonce, ciphertext, 16-byte tag, like seal()) of the files one after another:
    // [uint32 BE name length][name][uint32 BE data length][data]. Names are paths inside the chat folder; data is each
    // file's plain contents. The header says how the password makes the key:
    //   {"v":3, salt, "kdf":"scrypt", N:65536, r:8, p:2, created, app}   what both apps write; the tag also covers every
    //                                                                    byte before the nonce (a changed header is refused)
    //   {"v":2, salt, "kdf":"argon2id"|"scrypt", ...}                  older, restored here when Huginn made it
    //   {"v":1, salt, rounds, created, app}                             PBKDF2-SHA256, restored here when Huginn made it
    // A v3 backup also holds "@about.json" (app, device, created, files) and "@move.json" (the shared form, protocol.md
    // 18.4), so a Muninn backup restores here and a Huginn one in Muninn. New backups need kMinBackupPassword characters.
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString backupFolder READ backupFolder CONSTANT)
    bool busy() const { return m_busy; }
    QString backupFolder() const;
    // For the Back up screen: { name (the suggested file name), folder (Documents), all, media (bytes now) }.
    Q_INVOKABLE QVariantMap backupInfo() const;
    // Qt's file pickers (never the system's on Windows: portable.cpp): where to save (the suggested name in Documents),
    // and which backup to open (any file, any name). "" when cancelled.
    Q_INVOKABLE QString pickBackupSave(const QString &name) const;
    Q_INVOKABLE QString pickBackupOpen() const;
    // Writes the backup to path; files and pictures only when withFiles. Answers with done("backup", ok, message);
    // lastBackup() then says { path, folder, size, files }.
    Q_INVOKABLE void backupTo(const QString &path, const QString &password, bool withFiles);
    Q_INVOKABLE QVariantMap lastBackup() const { return m_lastBackup; }
    // Called first: Chat drops what is past each chat's message limit, so the backup never holds it.
    std::function<void()> beforeBackup;
    // Set in main.cpp: the shared form of what's here (Move), Huginn's files made from one (a Muninn backup), and
    // this computer's name.
    std::function<QByteArray()> sharedForm;
    std::function<std::vector<std::pair<QString, QByteArray>>(const QByteArray &, QString *)> fromSharedForm;
    std::function<QString()> deviceName;
    // Checks the password and reads the backup into memory; done("open", ok, message). Nothing changes until
    // restoreOpened(); forgetOpened() drops it. openedInfo(): { error ("password" for a wrong one), app, device,
    // created, v, files, contacts, chats, groups, folders, items } (counts are -1 when the backup has no list).
    Q_INVOKABLE void openBackup(const QString &file, const QString &password);
    Q_INVOKABLE QVariantMap openedInfo() const { return m_openedInfo; }
    // Keeps a copy of what's here (chat folder, next to it), then replaces everything with the backup just opened and
    // restarts Huginn to load it. On first setup (no lock yet) Huginn starts without a lock first.
    Q_INVOKABLE void restoreOpened();
    // The lock screen's "Forgot it?": what's here can't be opened, so it's erased (not kept), the backup just opened goes
    // in under a new data key, and Huginn restarts without a lock but with lockNeeded set. Only while locked.
    Q_INVOKABLE void restoreErasing();
    Q_INVOKABLE void forgetOpened();

    using Files = std::vector<std::pair<QString, QByteArray>>;
    // The format, separate so --backup-selftest can run it on a temp folder.
    // kdf "pbkdf2" writes a v1 header (310000 rounds) and "v2" a v2 scrypt one, only so the self-test can check they
    // still restore.
    static QByteArray packBackup(const QString &password, const Files &files, const QString &app, const QString &kdf = QString());
    static constexpr int kMinBackupPassword = 12;
    // 0 ok, 1 not a backup or damaged, 2 wrong password (or changed), 3 an older backup from another app.
    // header gets the header JSON when it reads.
    static int unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error,
                            QJsonObject *header = nullptr);
    // What openedInfo() says about files and their header.
    static QVariantMap backupSummary(const Files &files, const QJsonObject &header);
    // Photos, voice and video messages: in a backup only when files and pictures are.
    static bool isMedia(const QString &name);
    static bool safeName(const QString &name);
    // Every file in a chat folder, opened with the data key (vault.key itself stays out).
    static Files collect(const QByteArray &key, const QString &dir);
    // Writes the files, sealed with the data key, into a fresh folder next to dir, then swaps
    // them in: everything in dir except vault.key is replaced.
    static bool replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error);
    // A copy of dir (vault.key too) in a new "<dir>-kept-<date>" folder next to it. "" when it couldn't be made.
    static QString keepCopy(const QString &dir);
    static bool backupSelfTest();
    // shared/vectors/backup-vectors.json: backups made by the reference (as Huginn and as Muninn) open here.
    static bool backupVectorTest(const QString &path);

    // ---------------------------------------------------------------- moving to another device (cap x1, move.cpp)
    // Everything a backup holds (the settings blob too), read now.
    Files everything() const;
    // Replaces everything here with files (like a restore) and restarts Huginn. False with error when it can't.
    bool takeEverything(const Files &files, QString *error);
    // This computer's chat settings as a backup carries them, with set put in and drop taken out.
    static QByteArray settingsWith(const QVariantMap &set, const QStringList &drop);
    static QString settingsEntry();

    // ---------------------------------------------------------------- panic lock
    // Hold L3 and R3 for a second anywhere in Huginn (while it's the app in front): Chat locks at once.
    // Main.qml passes every controller press and release here.
    Q_PROPERTY(bool panicLock READ panicLock WRITE setPanicLock NOTIFY changed)
    bool panicLock() const;
    void setPanicLock(bool on);
    Q_INVOKABLE void button(const QString &name, bool down);
    // Lock at once (the keyboard's Ctrl+Shift+Q, or L3 and R3 held for a second): the lock screen goes up and
    // anything that could show Chat closes. Huginn keeps running (messages are still saved behind the lock).
    // The controller counts only while Huginn is the app in front, so a game played beside it never sets it off.
    Q_INVOKABLE void panic();

Q_SIGNALS:
    void changed();
    void opened();   // the data key is available (first unlock)
    void erased();   // Erase everything (also Move, once done): what lives outside the vault goes too
    void done(const QString &what, bool ok, const QString &message);   // backup, open, restore
    void panicked(); // the panic combo just locked Chat: close anything that could show it
    void notice(const QString &text);   // something to say on screen at once (Huginn couldn't start again)
    void unlockFinished(bool ok);       // unlockInBackground() is done

private:
    // Starts Huginn again (it waits for this copy to close) and closes this one. When it can't start, this copy
    // stays open and says ifNot.
    void restartHuginn(const QString &ifNot);
    ReadStatus readOnly(const QString &name) const;   // read() without noting anything (tryReadAgain)
    struct Shared;
    std::shared_ptr<Shared> m_shared;   // the key for anyThreadReader(), copied on every change
    void shareKey();
    mutable QSet<QString> m_unreadable;
    QString keyFile() const;
    bool writeLock(const QString &secret, bool pattern) const;
    bool writeKeyFile(const QByteArray &bytes) const;
    void wrongTry();   // counts a wrong pattern or password (the wait doubles every 5)
    QByteArray m_key;
    SaveQueue *m_saves = nullptr;       // the saving thread
    bool m_unlocking = false;
    QPointer<QThread> m_unlockThread;
    // The first unlockInBackground(): the saved files read and opened there, handed to read() while opened() runs.
    mutable QHash<QString, QByteArray> m_prefetched;
    void finishUnlock(bool ok, const QByteArray &key, bool weak, const Files &read);
    bool m_weak = false;   // the lock just opened is shorter than new ones may be
    bool m_screenLocked = false;
    bool m_busy = false;
    bool m_restarting = false;   // restored: nothing may be saved until Huginn starts again
    bool m_lockNeeded = false;   // see lockNeeded()
    // The files the backup just opened puts here (a Muninn backup made into Huginn's own). Empty with why when none.
    Files openedFiles(QString *why) const;
    Files m_opened;
    QJsonObject m_openedHeader;
    QVariantMap m_openedInfo;
    QVariantMap m_lastBackup;
    QTimer m_combo;
    bool m_l3 = false;
    bool m_r3 = false;
};
