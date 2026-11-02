#pragma once

#include <QObject>
#include <QString>
#include <QStringList>

class QWindow;

class QLocalServer;
class QLockFile;

// One Huginn per user. A second copy (a double click on Huginn.exe while it already runs)
// asks the first one to come to the front and then quits, so two copies never fight over
// the chat folder and vault files.
// Works the same on Linux and Windows (a lock file in the app data folder plus a local socket).
// A restart (an update, a restore, the graphics falling back) starts the new copy with --restarted: it waits
// for the old copy to save and close (the lock is let go only when that process ends), so the two never
// read and write the vault at the same time.
class SingleInstance : public QObject
{
    Q_OBJECT

public:
    // dir: where the lock lives (the app data folder when empty; another folder only for the self-test).
    explicit SingleInstance(QObject *parent = nullptr, const QString &dir = QString());
    ~SingleInstance() override;

    // True: this is the only copy (keep going). False: another copy runs and was asked to show itself.
    // restarted: started by a copy that is closing; waits up to kRestartWaitMs for it to end first.
    bool claim(bool restarted = false);
    // Why claim() said false: another copy answered and came to the front (nothing to say), or it holds the lock but
    // doesn't answer (hung, or open on another computer or account using this folder), or the data folder can't be
    // written at all (a full disk, a read-only folder).
    enum Outcome { Claimed, Shown, NotAnswering, CantWrite };
    Outcome outcome() const { return m_outcome; }
    // What to say on screen for NotAnswering or CantWrite ("" otherwise).
    QString problemText() const;
    static constexpr int kRestartWaitMs = 15000;
    // A copy that doesn't answer the knock in this long is hung (or closing and stuck). Only a stuck closing one is
    // asked to end itself, and its lock is taken once it has.
    static constexpr int kAnswerMs = 2000;
    // Starts a new copy of Huginn that waits for this one to end (--restarted). program: Huginn.exe to start (this
    // program when empty); env: extra "NAME=value" settings for it. False when nothing could be started: then this
    // copy must stay open.
    static bool startAgain(const QString &program = QString(), const QString &workDir = QString(), const QStringList &env = QStringList(),
                           const QStringList &args = QStringList());
    // Huginn is closing (main.cpp, on aboutToQuit after everything is saved): marked beside the lock until this
    // process lets it go. A copy started meanwhile that gets no answer for kStuckMs from the mark asks this one to end,
    // and this one ends itself (a watch started here). No copy ever ends another on Windows.
    void markClosing();
    static constexpr int kStuckMs = 5000;
    // Unminimises, raises and focuses Huginn's windows (a window put away with tuck() is shown again first).
    static void bringToFront();
    // Wayland and X11: the activation token a launcher or a notification click gave (XDG_ACTIVATION_TOKEN or
    // DESKTOP_STARTUP_ID), set for this process just before bringToFront(), so the desktop lets the window come forward.
    static void useActivationToken(const QByteArray &token);
    // Hides window where it can't be minimized (Steam's game mode): Huginn keeps running, and bringToFront()
    // (a notification, the tray, or starting Huginn again) shows it as it was.
    static void tuck(QWindow *window);
    // --instance-selftest: a copy that waits for a closing one, a copy that finds a live one, a stuck one that makes way.
    static bool selfTest();
    // The helper copies selfTest() starts: holds the lock in dir, then exits after ms (hang: without answering).
    static int selfTestHolder(const QString &dir, int ms, bool hang);

Q_SIGNALS:
    void activated();   // another copy was started

private:
    void release();
    void listen();
    // Knocks on the running copy: true when it answers (it then comes to the front).
    bool knock();
    // The lock is held but nobody answers on the socket: a killed copy's lock, or a stuck closing one asked to end.
    bool takeOverStaleLock();
#ifndef Q_OS_WIN
    // Linux only: ends the copy named in the lock file when it is Huginn and didn't end itself when asked.
    void endHolder();
#endif
    QString m_name;
    QString m_closing;
    QString m_endAsk;
    QLockFile *m_lock = nullptr;
    QLocalServer *m_server = nullptr;
    QString m_dir;
    Outcome m_outcome = Claimed;
};
