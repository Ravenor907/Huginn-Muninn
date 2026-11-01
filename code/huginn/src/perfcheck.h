#pragma once

#include <QElapsedTimer>
#include <QMutex>
#include <QObject>
#include <QPointer>
#include <QStringList>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <QVector>

#include <atomic>
#include <functional>

class QQuickWindow;
class Settings;
class System;

// Labs › Performance check: about 20 seconds of measuring in three phases (still, scrolling a long list, an
// animation): frame rate, frame-time spikes, drawing time, Huginn's processor time and memory, with the facts
// that decide how Huginn draws and sizes itself. A short verdict names the main suspect. Tried settings (display
// mode, drawing, render loop, animations) are kept as Labs overrides and used from the next start; one that
// keeps the window from starting is dropped on the start after, which says so. The report holds no names,
// contact details, message text, file names, network addresses or device ids (scrub()).
class PerfCheck : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY changed)
    // -1 before and after; 0 still, 1 scrolling, 2 animation. The panel scrolls its list or runs its test animation.
    Q_PROPERTY(int phase READ phase NOTIFY changed)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(bool hasResult READ hasResult NOTIFY changed)
    Q_PROPERTY(QString verdict READ verdict NOTIFY changed)
    Q_PROPERTY(QString suspect READ suspect NOTIFY changed)
    // [{name, fps, median, worst, spikes, draw, cpu}], numbers rounded for showing.
    Q_PROPERTY(QVariantList phases READ phases NOTIFY changed)
    // What is saved for the next start: {display, drawing, loop, animations}.
    Q_PROPERTY(QVariantMap overrides READ overrides NOTIFY overridesChanged)
    Q_PROPERTY(bool overridesOn READ overridesOn NOTIFY overridesChanged)
    // The saved overrides differ from what this run started with.
    Q_PROPERTY(bool restartNeeded READ restartNeeded NOTIFY overridesChanged)
    // What this run started with ("Automatic" when nothing was tried), in words.
    Q_PROPERTY(QString inUse READ inUse CONSTANT)
    // Not empty: the tried settings kept the window from starting, so they were dropped (what they were).
    Q_PROPERTY(QString fellBack READ fellBack NOTIFY overridesChanged)
    // This start dropped them (Main.qml says so once).
    Q_PROPERTY(bool droppedNow READ droppedNow CONSTANT)
    // Labs override: nothing moves by itself this run (Theme.motion).
    Q_PROPERTY(bool animationsOff READ animationsOff CONSTANT)
    // Display mode applies only on Linux.
    Q_PROPERTY(bool linux READ isLinux CONSTANT)

public:
    PerfCheck(Settings *settings, System *system, QObject *parent = nullptr);

    // Before QApplication: the saved overrides go into the environment (only with Labs on, never for the tour or a
    // self-test). A start that never drew its window drops them first.
    static void applyAtStart(int argc, char **argv);
    // Once the main window exists: the environment goes back to how it was, so a copy started later (an update, a
    // restart) reads the overrides again itself instead of inheriting them.
    static void forgetEnv();
    // A copy that quits at once (another one runs): its start isn't counted as a failed one.
    static void notStarting();
    // After CrashLog::start: notes the tried settings, or that they were dropped.
    static void noteStart();
    // Follows the main window's frames; its first frame shows the tried settings started.
    void watch(QQuickWindow *window);
    // Names and similar words that never go into a report (the chat name, device name, account, computer name).
    std::function<QStringList()> privateWords;

    bool running() const { return m_running; }
    int phase() const { return m_phase; }
    double progress() const { return m_progress; }
    bool hasResult() const { return !m_results.isEmpty(); }
    QString verdict() const { return m_verdict; }
    QString suspect() const { return m_suspect; }
    QVariantList phases() const;
    QVariantMap overrides() const;
    bool overridesOn() const;
    bool restartNeeded() const;
    QString inUse() const;
    QString fellBack() const;
    bool droppedNow() const;
    bool animationsOff() const { return s_animationsOff; }
    bool isLinux() const;

    // seasonal: the Seasonal animation is moving on screen (the third phase measures it; otherwise the panel's own).
    Q_INVOKABLE void start(bool seasonal);
    Q_INVOKABLE void cancel();
    // The full plain-text report. ui: what only the QML side knows ({look}).
    Q_INVOKABLE QString report(const QVariantMap &ui) const;
    // A shorter one that fits in a bug report beside what the person wrote.
    Q_INVOKABLE QString shortReport(const QVariantMap &ui) const;
    Q_INVOKABLE void copy(const QString &text) const;
    // key: "display" (auto, x11, wayland), "drawing" (auto, opengl, vulkan, software), "loop" (auto, threaded,
    // basic), "animations" (on, off).
    Q_INVOKABLE void setOverride(const QString &key, const QString &value);
    Q_INVOKABLE void undoAll();
    Q_INVOKABLE void dismissFellBack();
    // Starts Huginn again with the saved overrides; false when it couldn't (then this copy stays open).
    Q_INVOKABLE bool restart();

    // Takes out anything that could name or locate someone: network and hardware addresses, ids, e-mail
    // addresses, the home folder, file paths and the given words.
    static QString scrub(const QString &text, const QStringList &words);
    // --privacy-selftest: the scrubber keeps facts and drops addresses, ids, names and paths.
    static bool selfTest();

Q_SIGNALS:
    void changed();
    void progressChanged();
    void overridesChanged();

private:
    struct Result {
        QString name;
        double fps = 0, median = 0, p99 = 0, worst = 0, spikes = 0, draw = 0, cpu = 0;
        int frames = 0;
        double memMb = 0;
    };
    void tick();
    void beginMeasure();
    void endMeasure();
    void finish();
    void judge();
    QString factsText(const QVariantMap &ui, bool full) const;
    QString scrubbed(const QString &text) const;
    static double cpuMs();
    static double memoryMb();
    static QString words(const QString &key, const QString &value);

    Settings *m_settings;
    System *m_system;
    QPointer<QQuickWindow> m_window;
    QTimer m_timer;
    QElapsedTimer m_clock;
    bool m_running = false;
    int m_phase = -1;
    double m_progress = 0;
    bool m_measuring = false;
    qint64 m_phaseStart = 0;
    double m_cpu0 = 0;
    qint64 m_wall0 = 0;
    QVector<Result> m_results;
    QString m_verdict, m_suspect;

    // Written on the render thread, read on the main thread.
    QMutex m_lock;
    std::atomic<bool> m_active{false};      // keeps the window drawing while the check runs
    std::atomic<bool> m_recording{false};   // frames are counted (a phase's measured part)
    QVector<qint64> m_stamps;
    qint64 m_drawStart = 0;
    double m_drawTotal = 0;
    int m_drawCount = 0;
    std::atomic<bool> m_firstFrame{true};
    bool m_seasonal = false;

    static bool s_animationsOff;
    static QVariantMap s_started;
};
