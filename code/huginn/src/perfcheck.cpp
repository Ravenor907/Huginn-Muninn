#include "perfcheck.h"
#include "busy.h"
#include "settings.h"
#include "system.h"

#include "platform/crashlog.h"
#include "platform/singleinstance.h"

#include <QClipboard>
#include <QCoreApplication>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QGuiApplication>
#include <QHash>
#include <QList>
#include <QMetaEnum>
#include <QPair>
#include <QQuickWindow>
#include <QRegularExpression>
#include <QScreen>
#include <QSettings>
#include <QSysInfo>
#include <QThread>
#include <QTimer>

#include <algorithm>
#include <cmath>
#include <cstdio>

#ifdef Q_OS_WIN
#ifndef PSAPI_VERSION
#define PSAPI_VERSION 2
#endif
#include <windows.h>
#include <psapi.h>
#else
#include <sys/resource.h>
#include <unistd.h>
#endif

bool PerfCheck::s_animationsOff = false;
QVariantMap PerfCheck::s_started;

namespace
{
const int kWarmMs = 800;        // each phase settles before it is measured
const int kMeasureMs = 5800;    // then is measured this long: three phases and the idle sample, about 25 seconds in all
const int kPhaseMs = kWarmMs + kMeasureMs;
const int kIdleMs = 5000;       // then 5 seconds with nothing asked to draw: what Huginn does by itself
const int kTotalMs = 3 * kPhaseMs + kWarmMs + kIdleMs;
const char *const kKeys[] = {"display", "drawing", "loop", "animations"};

QSettings store() { return QSettings(QStringLiteral("Huginn"), QStringLiteral("Huginn")); }
QString key(const char *k) { return QStringLiteral("labs/perf/") + QLatin1String(k); }
QString defaultOf(const QString &k) { return k == QLatin1String("animations") ? QStringLiteral("on") : QStringLiteral("auto"); }

bool valid(const QString &k, const QString &v)
{
    if (k == QLatin1String("display")) return v == QLatin1String("auto") || v == QLatin1String("x11") || v == QLatin1String("wayland");
    if (k == QLatin1String("drawing"))
        return v == QLatin1String("auto") || v == QLatin1String("opengl") || v == QLatin1String("vulkan") || v == QLatin1String("software");
    if (k == QLatin1String("loop")) return v == QLatin1String("auto") || v == QLatin1String("threaded") || v == QLatin1String("basic");
    if (k == QLatin1String("animations")) return v == QLatin1String("on") || v == QLatin1String("off");
    return false;
}

QVariantMap saved()
{
    QSettings s = store();
    QVariantMap m;
    for (const char *k : kKeys) {
        const QString name = QLatin1String(k);
        const QString v = s.value(key(k), defaultOf(name)).toString();
        m.insert(name, valid(name, v) ? v : defaultOf(name));
    }
#ifndef Q_OS_LINUX
    m.insert(QStringLiteral("display"), QStringLiteral("auto"));   // X11 and Wayland are Linux's
#endif
    return m;
}

bool anyTried(const QVariantMap &m)
{
    for (auto it = m.cbegin(); it != m.cend(); ++it)
        if (it.value().toString() != defaultOf(it.key()))
            return true;
    return false;
}

QString describe(const QVariantMap &m)
{
    QStringList out;
    const QString d = m.value(QStringLiteral("display")).toString(), g = m.value(QStringLiteral("drawing")).toString(),
                  l = m.value(QStringLiteral("loop")).toString(), a = m.value(QStringLiteral("animations")).toString();
    if (d == QLatin1String("x11")) out << QStringLiteral("Display mode X11");
    if (d == QLatin1String("wayland")) out << QStringLiteral("Display mode Wayland");
    if (g == QLatin1String("opengl")) out << QStringLiteral("Drawing OpenGL");
    if (g == QLatin1String("vulkan")) out << QStringLiteral("Drawing Vulkan");
    if (g == QLatin1String("software")) out << QStringLiteral("Drawing Software");
    if (l == QLatin1String("threaded")) out << QStringLiteral("Render loop Threaded");
    if (l == QLatin1String("basic")) out << QStringLiteral("Render loop Basic");
    if (a == QLatin1String("off")) out << QStringLiteral("Animations off");
    return out.isEmpty() ? QStringLiteral("Automatic") : out.join(QStringLiteral(", "));
}

// What applyAtStart() changed in the environment, to put back (name, was set, old value).
struct EnvWas {
    QByteArray name;
    bool had;
    QByteArray value;
};
QList<EnvWas> s_env;
bool s_droppedNow = false;

void setEnv(const char *name, const QByteArray &value)
{
    s_env.append({QByteArray(name), qEnvironmentVariableIsSet(name), qgetenv(name)});
    qputenv(name, value);
}

QString fixed(double v, int decimals = 1) { return QString::number(v, 'f', decimals); }

// The crash.log time at the start of a line stays as it is (a scrub would read it as an address).
const QRegularExpression &stampRe()
{
    static const QRegularExpression re(QStringLiteral("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} "));
    return re;
}
}

// ------------------------------------------------------------------ Busiest (busy.h)

namespace Busy
{
std::atomic<bool> on{false};
int depth = 0;
namespace
{
// Events, by who got them and what kind they were.
struct EventKey {
    const QMetaObject *receiver = nullptr;
    const QMetaObject *parent = nullptr;
    int type = 0;
    int interval = -1;   // a QTimer's, so timers without a parent can still be told apart
    bool operator==(const EventKey &o) const
    {
        return receiver == o.receiver && parent == o.parent && type == o.type && interval == o.interval;
    }
};
size_t qHash(const EventKey &k, size_t seed = 0) noexcept { return qHashMulti(seed, k.receiver, k.parent, k.type, k.interval); }
struct EventCount {
    QString what;
    qint64 ns = 0, longest = 0;
    int times = 0;
};
QHash<EventKey, int> s_eventIndex;
QList<EventCount> s_events;
qint64 s_eventNs = 0;
int s_eventDepth = 0;

QString eventWords(const EventKey &k)
{
    const QMetaEnum types = QMetaEnum::fromType<QEvent::Type>();
    const char *type = types.valueToKey(k.type);
    QString out = (type ? QString::fromLatin1(type) : QStringLiteral("Event %1").arg(k.type)) + QStringLiteral(" to ")
        + QString::fromLatin1(k.receiver ? k.receiver->className() : "?");
    if (k.parent)
        out += QStringLiteral(" in ") + QString::fromLatin1(k.parent->className());
    if (k.interval >= 0)
        out += QStringLiteral(" (every %1 ms)").arg(k.interval);
    return out;
}
struct Count {
    qint64 ns = 0, longest = 0;
    int times = 0;
};
QHash<const char *, Count> s_counts;   // by the name's address: each kind of work names itself with one literal
QElapsedTimer &timer()
{
    static QElapsedTimer t;
    if (!t.isValid())
        t.start();
    return t;
}
}

qint64 clockNs() { return timer().nsecsElapsed(); }

void add(const char *what, qint64 ns)
{
    Count &c = s_counts[what];
    c.ns += ns;
    c.longest = qMax(c.longest, ns);
    ++c.times;
}

void start()
{
    s_counts.clear();
    s_eventIndex.clear();
    s_events.clear();
    s_eventNs = 0;
    timer();
    on = true;
}

void stop() { on = false; }

QList<Entry> sorted()
{
    QHash<QString, Entry> byName;
    for (auto it = s_counts.cbegin(); it != s_counts.cend(); ++it) {
        const QString name = QString::fromUtf8(it.key());
        Entry &e = byName[name];
        e.what = name;
        e.ms += it.value().ns / 1e6;
        e.longest = qMax(e.longest, it.value().longest / 1e6);
        e.times += it.value().times;
    }
    QList<Entry> out = byName.values();
    std::sort(out.begin(), out.end(), [](const Entry &a, const Entry &b) { return a.ms > b.ms; });
    return out;
}

Event::Event(QObject *receiver, QEvent *event)
{
    if (!on || !receiver || !event || !QThread::isMainThread())
        return;
    m_in = true;
    // Only the outermost event is counted; the ones handled inside it are part of its time.
    if (s_eventDepth++ > 0)
        return;
    EventKey k;
    k.receiver = receiver->metaObject();
    k.parent = receiver->parent() ? receiver->parent()->metaObject() : nullptr;
    k.type = int(event->type());
    if (event->type() == QEvent::Timer)
        if (QTimer *t = qobject_cast<QTimer *>(receiver))
            k.interval = t->interval();
    auto it = s_eventIndex.constFind(k);
    if (it == s_eventIndex.cend()) {
        EventCount c;
        c.what = eventWords(k);
        s_events.append(c);
        it = s_eventIndex.insert(k, int(s_events.size() - 1));
    }
    m_key = it.value();
    m_start = clockNs();
}

Event::~Event()
{
    if (!m_in)
        return;
    --s_eventDepth;
    // A check started inside this event cleared the counts.
    if (m_key < 0 || m_key >= s_events.size())
        return;
    const qint64 ns = clockNs() - m_start;
    EventCount &c = s_events[m_key];
    c.ns += ns;
    c.longest = qMax(c.longest, ns);
    ++c.times;
    s_eventNs += ns;
}

namespace
{
QList<EventEntry> eventEntries()
{
    QList<EventEntry> out;
    for (const EventCount &c : std::as_const(s_events))
        if (c.times > 0)
            out.append(EventEntry{c.what, c.ns / 1e6, c.longest / 1e6, c.times});
    return out;
}
}

QList<EventEntry> slowestEvents()
{
    QList<EventEntry> out = eventEntries();
    std::sort(out.begin(), out.end(), [](const EventEntry &a, const EventEntry &b) { return a.longest > b.longest; });
    return out;
}

QList<EventEntry> busiestEvents()
{
    QList<EventEntry> out = eventEntries();
    std::sort(out.begin(), out.end(), [](const EventEntry &a, const EventEntry &b) { return a.ms > b.ms; });
    return out;
}

double eventMs() { return s_eventNs / 1e6; }
}

PerfCheck::PerfCheck(Settings *settings, System *system, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_system(system)
{
    if (s_started.isEmpty()) {
        for (const char *k : kKeys)
            s_started.insert(QLatin1String(k), defaultOf(QLatin1String(k)));
    }
    m_timer.setInterval(100);
    connect(&m_timer, &QTimer::timeout, this, &PerfCheck::tick);
    connect(settings, &Settings::changed, this, &PerfCheck::overridesChanged);   // Labs on or off
}

void PerfCheck::applyAtStart(int argc, char **argv)
{
    for (const char *k : kKeys)
        s_started.insert(QLatin1String(k), defaultOf(QLatin1String(k)));
    // The screenshot tour and the self-tests always start as Huginn would without Labs.
    for (int i = 1; i < argc; ++i) {
        const QByteArray a(argv[i]);
        if (a.startsWith("--tour") || a.contains("selftest"))
            return;
    }
    QSettings s = store();
    if (!s.value(QStringLiteral("labs/on"), false).toBool())
        return;
    const QVariantMap m = saved();
    if (!anyTried(m)) {
        s.remove(key("starting"));
        return;
    }
    // The last start with these never drew its window: back to Automatic, and Labs › Performance check says so.
    if (s.value(key("starting"), false).toBool()) {
        s.setValue(key("fellBack"), describe(m));
        for (const char *k : kKeys)
            s.remove(key(k));
        s.remove(key("starting"));
        s.sync();
        s_droppedNow = true;
        return;
    }
    s.setValue(key("starting"), true);
    s.sync();
    s_started = m;
#ifdef Q_OS_LINUX
    const QString d = m.value(QStringLiteral("display")).toString();
    if (d == QLatin1String("x11"))
        setEnv("QT_QPA_PLATFORM", "xcb");
    else if (d == QLatin1String("wayland"))
        setEnv("QT_QPA_PLATFORM", "wayland");
#endif
    const QString g = m.value(QStringLiteral("drawing")).toString();
    if (g == QLatin1String("opengl"))
        setEnv("QSG_RHI_BACKEND", "opengl");
    else if (g == QLatin1String("vulkan"))
        setEnv("QSG_RHI_BACKEND", "vulkan");
    else if (g == QLatin1String("software"))
        setEnv("QT_QUICK_BACKEND", "software");
    const QString l = m.value(QStringLiteral("loop")).toString();
    if (l == QLatin1String("threaded") || l == QLatin1String("basic"))
        setEnv("QSG_RENDER_LOOP", l.toLatin1());
    s_animationsOff = m.value(QStringLiteral("animations")).toString() == QLatin1String("off");
}

void PerfCheck::forgetEnv()
{
    for (auto it = s_env.crbegin(); it != s_env.crend(); ++it) {
        if (it->had)
            qputenv(it->name.constData(), it->value);
        else
            qunsetenv(it->name.constData());
    }
    s_env.clear();
}

void PerfCheck::notStarting()
{
    if (anyTried(s_started)) {
        QSettings s = store();
        s.remove(key("starting"));
    }
}

void PerfCheck::noteStart()
{
    if (s_droppedNow)
        CrashLog::note(QStringLiteral("tried settings dropped: the last start with them never drew its window"));
    else if (anyTried(s_started))
        CrashLog::note(QStringLiteral("tried settings: ") + describe(s_started));
}

void PerfCheck::watch(QQuickWindow *window)
{
    if (!window || m_window)
        return;
    m_window = window;
    // Render thread (threaded loop) or main thread (basic): only atomics and the lock here.
    connect(window, &QQuickWindow::frameSwapped, this, [this] {
        if (m_firstFrame.exchange(false)) {
            QMetaObject::invokeMethod(this, [] {
                QSettings s = store();
                s.remove(key("starting"));   // the tried settings drew the window
                forgetEnv();
            }, Qt::QueuedConnection);
        }
        if (m_recording.load()) {
            QMutexLocker l(&m_lock);
            m_stamps.append(m_clock.nsecsElapsed());
        }
        if (m_active.load() && m_window)
            QMetaObject::invokeMethod(m_window.data(), "update", Qt::QueuedConnection);
    }, Qt::DirectConnection);
    // Frames the window's thread got ready and frames handed to the drawing: in the idle sample, many of them with
    // nothing drawn means something keeps asking for frames that change nothing.
    connect(window, &QQuickWindow::afterAnimating, this, [this] {
        if (m_recording.load())
            ++m_asked;
    }, Qt::DirectConnection);
    connect(window, &QQuickWindow::beforeSynchronizing, this, [this] {
        if (m_recording.load())
            ++m_syncs;
    }, Qt::DirectConnection);
    connect(window, &QQuickWindow::beforeRendering, this, [this] {
        if (m_recording.load()) {
            QMutexLocker l(&m_lock);
            m_drawStart = m_clock.nsecsElapsed();
        }
    }, Qt::DirectConnection);
    connect(window, &QQuickWindow::afterRendering, this, [this] {
        if (m_recording.load()) {
            QMutexLocker l(&m_lock);
            if (m_drawStart > 0) {
                m_drawTotal += (m_clock.nsecsElapsed() - m_drawStart) / 1e6;
                ++m_drawCount;
                m_drawStart = 0;
            }
        }
    }, Qt::DirectConnection);
    // Tried settings that fail to draw are dropped for the next start.
    connect(window, &QQuickWindow::sceneGraphError, this, [this](QQuickWindow::SceneGraphError, const QString &) {
        if (!anyTried(s_started))
            return;
        QSettings s = store();
        s.setValue(key("fellBack"), describe(s_started));
        for (const char *k : kKeys)
            s.remove(key(k));
        s.remove(key("starting"));
        Q_EMIT overridesChanged();
    }, Qt::QueuedConnection);
    // Quitting before the first frame (Huginn was never shown) isn't a failed start.
    connect(qApp, &QCoreApplication::aboutToQuit, this, [] { QSettings s = store(); s.remove(key("starting")); });
}

bool PerfCheck::isLinux() const
{
#ifdef Q_OS_LINUX
    return true;
#else
    return false;
#endif
}

QVariantMap PerfCheck::overrides() const { return saved(); }
bool PerfCheck::overridesOn() const { return anyTried(saved()); }
bool PerfCheck::restartNeeded() const
{
    // With Labs off, nothing tried is used at the next start either.
    const bool labs = m_settings->labs();
    QVariantMap next = labs ? saved() : QVariantMap();
    if (!labs)
        for (const char *k : kKeys)
            next.insert(QLatin1String(k), defaultOf(QLatin1String(k)));
    return next != s_started;
}
QString PerfCheck::inUse() const { return describe(s_started); }
bool PerfCheck::droppedNow() const { return s_droppedNow; }
QString PerfCheck::fellBack() const { return store().value(key("fellBack")).toString(); }

void PerfCheck::setOverride(const QString &k, const QString &value)
{
    if (!valid(k, value))
        return;
    QSettings s = store();
    if (value == defaultOf(k))
        s.remove(QStringLiteral("labs/perf/") + k);
    else
        s.setValue(QStringLiteral("labs/perf/") + k, value);
    Q_EMIT overridesChanged();
}

void PerfCheck::undoAll()
{
    QSettings s = store();
    for (const char *k : kKeys)
        s.remove(key(k));
    Q_EMIT overridesChanged();
}

void PerfCheck::dismissFellBack()
{
    store().remove(key("fellBack"));
    Q_EMIT overridesChanged();
}

bool PerfCheck::restart()
{
    store().sync();
    forgetEnv();
    if (!SingleInstance::startAgain())
        return false;
    QTimer::singleShot(300, qApp, [] { QCoreApplication::exit(0); });
    return true;
}

// ------------------------------------------------------------------ measuring

void PerfCheck::start(bool seasonal)
{
    if (m_running || !m_window)
        return;
    m_seasonal = seasonal;
    m_results.clear();
    m_verdict.clear();
    m_suspect.clear();
    m_idleCpu = -1;
    m_idleFrames = 0;
    m_lastTick = 0;
    m_stalls = 0;
    m_stallMs = 0;
    m_stallLongest = 0;
    m_busiest.clear();
    m_busyTop.clear();
    m_slowLine.clear();
    m_mostLine.clear();
    m_threadsLine.clear();
    m_threads0.clear();
    m_idleTopThread.clear();
    m_idleEventPct = -1;
    m_idleAsked = m_idleSyncs = 0;
    Busy::start();
    m_running = true;
    m_phase = 0;
    m_progress = 0;
    m_measuring = false;
    m_phaseStart = 0;
    m_clock.start();
    m_active = true;
    m_timer.start();
    m_window->update();
    Q_EMIT changed();
    Q_EMIT progressChanged();
}

void PerfCheck::cancel()
{
    if (!m_running)
        return;
    m_timer.stop();
    Busy::stop();
    m_active = false;
    m_recording = false;
    m_running = false;
    m_measuring = false;
    m_phase = -1;
    m_progress = 0;
    m_results.clear();
    m_verdict.clear();
    m_suspect.clear();
    m_idleCpu = -1;
    m_busiest.clear();
    m_busyTop.clear();
    m_slowLine.clear();
    m_mostLine.clear();
    m_threadsLine.clear();
    m_idleEventPct = -1;
    Q_EMIT changed();
    Q_EMIT progressChanged();
}

void PerfCheck::tick()
{
    if (!m_running)
        return;
    const qint64 t = m_clock.elapsed();
    // This tick came late: something held the window's thread.
    if (m_lastTick > 0 && t - m_lastTick > 250) {
        ++m_stalls;
        m_stallMs += double(t - m_lastTick - m_timer.interval());
        m_stallLongest = qMax(m_stallLongest, double(t - m_lastTick));
    }
    m_lastTick = t;
    const qint64 in = t - m_phaseStart;
    const bool idle = m_phase == 3;
    // Idle: nothing is asked to draw, not even the progress bar.
    if (!idle) {
        m_progress = qBound(0.0, double(t) / kTotalMs, 1.0);
        Q_EMIT progressChanged();
        // A hidden or minimized window draws nothing by itself: asked again each tick.
        if (m_window)
            m_window->update();
    }
    if (!m_measuring && in >= kWarmMs)
        beginMeasure();
    else if (m_measuring && in >= (idle ? kWarmMs + kIdleMs : kPhaseMs)) {
        endMeasure();
        if (m_phase < 3) {
            ++m_phase;
            m_phaseStart = t;
            if (m_phase == 3)
                m_active = false;   // frames from here on are only the ones Huginn asks for itself
            Q_EMIT changed();
        } else {
            finish();
        }
    }
}

void PerfCheck::beginMeasure()
{
    {
        QMutexLocker l(&m_lock);
        m_stamps.clear();
        m_stamps.reserve(2048);
        m_drawTotal = 0;
        m_drawCount = 0;
        m_drawStart = 0;
    }
    m_asked = 0;
    m_syncs = 0;
    if (m_phase == 3) {
        m_threads0 = threadTimes();
        m_eventMs0 = Busy::eventMs();
    }
    m_cpu0 = cpuMs();
    m_wall0 = m_clock.elapsed();
    m_measuring = true;
    m_recording = true;
}

void PerfCheck::endMeasure()
{
    m_recording = false;
    m_measuring = false;
    QVector<qint64> stamps;
    double draw = 0;
    int drawn = 0;
    {
        QMutexLocker l(&m_lock);
        stamps.swap(m_stamps);
        draw = m_drawTotal;
        drawn = m_drawCount;
    }
    const double wall = qMax<qint64>(1, m_clock.elapsed() - m_wall0);
    if (m_phase == 3) {
        m_idleFrames = int(stamps.size());
        m_idleCpu = 100.0 * (cpuMs() - m_cpu0) / wall;
        m_idleAsked = m_asked.load();
        m_idleSyncs = m_syncs.load();
        m_idleEventPct = 100.0 * (Busy::eventMs() - m_eventMs0) / wall;
        // Each thread's share of the idle sample, the busiest three.
        const QHash<qint64, ThreadTime> now = threadTimes();
        QList<QPair<double, QString>> used;
        for (auto it = now.cbegin(); it != now.cend(); ++it) {
            const double ms = it.value().ms - m_threads0.value(it.key()).ms;
            if (ms > 0)
                used.append({100.0 * ms / wall, it.value().name});
        }
        std::sort(used.begin(), used.end(), [](const QPair<double, QString> &a, const QPair<double, QString> &b) { return a.first > b.first; });
        QStringList top;
        for (qsizetype i = 0; i < qMin<qsizetype>(3, used.size()); ++i)
            top << QStringLiteral("%1 %2%").arg(used.at(i).second).arg(qRound(used.at(i).first));
        m_idleTopThread = !used.isEmpty() && used.first().second != QLatin1String("window thread") && used.first().first >= 5 ? used.first().second : QString();
        if (!now.isEmpty())
            m_threadsLine = QStringLiteral("Threads at idle: ") + (top.isEmpty() ? QStringLiteral("none busy") : top.join(QStringLiteral(", ")));
        m_threads0.clear();
        return;
    }
    Result r;
    r.name = m_phase == 0 ? QStringLiteral("Still") : m_phase == 1 ? QStringLiteral("Scrolling") : QStringLiteral("Animation");
    QVector<double> gaps;
    for (int i = 1; i < stamps.size(); ++i)
        gaps.append((stamps.at(i) - stamps.at(i - 1)) / 1e6);
    r.frames = int(gaps.size());
    if (!gaps.isEmpty()) {
        const double span = (stamps.last() - stamps.first()) / 1e9;
        r.fps = span > 0 ? gaps.size() / span : 0;
        QVector<double> sorted = gaps;
        std::sort(sorted.begin(), sorted.end());
        r.median = sorted.at(sorted.size() / 2);
        r.p99 = sorted.at(qMin<qsizetype>(sorted.size() - 1, qsizetype(sorted.size() * 0.99)));
        r.worst = sorted.last();
        // A spike: a frame that took clearly longer than the usual one (about a missed screen refresh or more).
        const double limit = qMax(r.median * 1.8, r.median + 6.0);
        int spikes = 0;
        for (const double g : gaps)
            if (g > limit)
                ++spikes;
        r.spikes = 100.0 * spikes / gaps.size();
    }
    r.draw = drawn > 0 ? draw / drawn : -1;
    r.cpu = 100.0 * (cpuMs() - m_cpu0) / wall;
    r.memMb = memoryMb();
    m_results.append(r);
}

void PerfCheck::finish()
{
    m_timer.stop();
    Busy::stop();
    m_active = false;
    m_running = false;
    m_phase = -1;
    m_progress = 1;
    noteBusiest();
    judge();
    CrashLog::note(QStringLiteral("performance check: ") + m_verdict);
    Q_EMIT changed();
    Q_EMIT progressChanged();
}

void PerfCheck::judge()
{
    if (m_results.size() < 3)
        return;
    double target = m_window && m_window->screen() ? m_window->screen()->refreshRate() : 60.0;
    if (target < 20 || target > 500)
        target = 60;
    const Result &still = m_results.at(0), &scroll = m_results.at(1), &anim = m_results.at(2);
    const double budget = 1000.0 / target;
    auto slow = [target](const Result &r) { return r.fps < target * 0.85; };
    auto spiky = [](const Result &r) { return r.spikes >= 3.0; };
    const bool busy = still.cpu >= 80;
    // What held the window's thread the most, named in the suspect.
    const QString busyWords = m_busyName.isEmpty() ? QStringLiteral("Huginn keeps the processor busy")
                                                   : QStringLiteral("Huginn keeps the processor busy: ") + m_busyName;
    if (m_system->softwareGraphics()) {
        m_verdict = QStringLiteral("Slow drawing: the graphics chip isn't being used");
        m_suspect = QStringLiteral("Drawing on the processor. Try Drawing: OpenGL or Vulkan");
        return;
    }
    if (!slow(still) && !slow(scroll) && !slow(anim) && !spiky(still) && !spiky(scroll) && !spiky(anim)) {
        if (m_idleCpu >= 5) {
            m_verdict = QStringLiteral("Busy while idle: %1% CPU").arg(qRound(m_idleCpu));
            if (!m_busyName.isEmpty())
                m_suspect = m_busyName;
            else if (m_idleFrames > 0)
                m_suspect = QStringLiteral("Something keeps drawing");
            else if (m_idleAsked > 20)
                m_suspect = QStringLiteral("Something keeps asking for frames that change nothing");
            else if (!m_idleTopThread.isEmpty())
                m_suspect = QStringLiteral("The %1 thread").arg(m_idleTopThread);
            else
                m_suspect = QStringLiteral("Not found");
            return;
        }
        m_verdict = QStringLiteral("Smooth: %1 fps").arg(qRound(std::min({still.fps, scroll.fps, anim.fps})));
        m_suspect = QStringLiteral("Nothing found");
        return;
    }
    if (slow(still)) {
        m_verdict = QStringLiteral("Slow drawing: %1 fps standing still").arg(qRound(still.fps));
        if (still.draw >= budget * 0.7)
            m_suspect = QStringLiteral("Each frame takes %1 ms to draw. Try another Drawing").arg(fixed(still.draw));
        else if (busy)
            m_suspect = busyWords;
        else
            m_suspect = QStringLiteral("Frames wait on the screen. Try another Render loop or Display mode");
        return;
    }
    if (slow(scroll) || spiky(scroll)) {
        m_verdict = slow(scroll) ? QStringLiteral("Slow while scrolling: %1 fps").arg(qRound(scroll.fps)) : QStringLiteral("Spikes while scrolling");
        if (scroll.cpu >= 80)
            m_suspect = m_busyName.isEmpty() ? QStringLiteral("Laying out the list keeps the processor busy") : busyWords;
        else if (scroll.draw >= budget * 0.7)
            m_suspect = QStringLiteral("Drawing the list. Try another Drawing");
        else
            m_suspect = QStringLiteral("Uneven frames. Try another Render loop");
        return;
    }
    if (slow(anim) || spiky(anim)) {
        m_verdict = slow(anim) ? QStringLiteral("Slow during the animation: %1 fps").arg(qRound(anim.fps)) : QStringLiteral("Spikes during the animation");
        m_suspect = m_seasonal ? QStringLiteral("The Seasonal animation. Try a smaller Amount") : QStringLiteral("Animations. Try Animations: Off");
        return;
    }
    m_verdict = QStringLiteral("Spikes standing still");
    m_suspect = busy ? busyWords : QStringLiteral("Uneven frames. Try another Render loop");
}

void PerfCheck::noteBusiest()
{
    // Huginn's own work by kind (busy.h); a held-up thread that none of it explains is drawing and layout.
    const QList<Busy::Entry> top = Busy::sorted();
    m_busyTop.clear();
    for (qsizetype i = 0; i < qMin<qsizetype>(5, top.size()); ++i)
        if (top.at(i).ms >= 1)
            m_busyTop << QStringLiteral("%1: %2 ms in %3 times, longest %4 ms")
                             .arg(top.at(i).what).arg(qRound(top.at(i).ms)).arg(top.at(i).times).arg(qRound(top.at(i).longest));
    const bool measured = !top.isEmpty() && top.first().ms >= 1;
    const bool unexplained = m_stalls > 0 && (!measured || top.first().longest < m_stallLongest * 0.5);
    // Every event the window's thread handled, timed (busy.h): the slowest single ones, and the most in all.
    const QList<Busy::EventEntry> slow = Busy::slowestEvents();
    QStringList slowWords;
    for (qsizetype i = 0; i < qMin<qsizetype>(5, slow.size()); ++i)
        if (slow.at(i).longest >= 5)
            slowWords << QStringLiteral("%1 %2 ms (%3 times, %4 ms in all)")
                             .arg(slow.at(i).what).arg(qRound(slow.at(i).longest)).arg(slow.at(i).times).arg(qRound(slow.at(i).ms));
    m_slowLine = QStringLiteral("Slowest events: ") + (slowWords.isEmpty() ? QStringLiteral("none over 5 ms") : slowWords.join(QStringLiteral("; ")));
    const QList<Busy::EventEntry> most = Busy::busiestEvents();
    QStringList mostWords;
    for (qsizetype i = 0; i < qMin<qsizetype>(3, most.size()); ++i)
        if (most.at(i).ms >= 1)
            mostWords << QStringLiteral("%1 %2 ms in %3 times").arg(most.at(i).what).arg(qRound(most.at(i).ms)).arg(most.at(i).times);
    m_mostLine = mostWords.isEmpty() ? QString() : QStringLiteral("Most event time: ") + mostWords.join(QStringLiteral("; "));
    // A hold-up none of Huginn's parts explains: the slowest event names it when it was about that long.
    const QString slowName = !slow.isEmpty() && m_stallLongest > 0 && slow.first().longest >= m_stallLongest * 0.5 ? slow.first().what : QString();
    m_busyName.clear();
    if (unexplained)
        m_busyName = slowName.isEmpty() ? QStringLiteral("layout and bindings") : slowName;
    else if (measured && (top.first().ms >= 50 || m_stalls > 0))
        m_busyName = top.first().what;
    QString text;
    if (unexplained)
        text = slowName.isEmpty() ? QStringLiteral("layout and bindings (none of Huginn's measured parts)")
                                  : slowName + QStringLiteral(" (none of Huginn's measured parts)");
    else if (measured)
        text = QStringLiteral("%1 %2 ms (longest %3 ms)").arg(top.first().what).arg(qRound(top.first().ms)).arg(qRound(top.first().longest));
    else
        text = QStringLiteral("nothing measurable");
    if (m_stalls > 0)
        text += QStringLiteral("; held up %1 times, longest %2 ms").arg(m_stalls).arg(qRound(m_stallLongest));
    m_busiest = QStringLiteral("Busiest: ") + text;
}

QString PerfCheck::idleLine() const
{
    if (m_idleCpu < 0)
        return QString();
    QString out = QStringLiteral("Idle CPU: %1% (%2 frames drawn, %3 got ready, %4 handed to drawing").arg(qRound(m_idleCpu)).arg(m_idleFrames)
                      .arg(m_idleAsked).arg(m_idleSyncs);
    if (m_idleEventPct >= 0)
        out += QStringLiteral("; window thread in events %1%").arg(qRound(m_idleEventPct));
    return out + QLatin1Char(')');
}

QVariantList PerfCheck::phases() const
{
    QVariantList out;
    for (const Result &r : m_results)
        out.append(QVariantMap{{QStringLiteral("name"), r.name}, {QStringLiteral("fps"), qRound(r.fps)},
                               {QStringLiteral("median"), fixed(r.median)}, {QStringLiteral("worst"), fixed(r.worst)},
                               {QStringLiteral("spikes"), fixed(r.spikes)}, {QStringLiteral("draw"), r.draw < 0 ? QStringLiteral("n/a") : fixed(r.draw)},
                               {QStringLiteral("cpu"), qRound(r.cpu)}});
    return out;
}

double PerfCheck::cpuMs()
{
#ifdef Q_OS_WIN
    FILETIME c, e, k, u;
    if (!GetProcessTimes(GetCurrentProcess(), &c, &e, &k, &u))
        return 0;
    auto ms = [](const FILETIME &f) { return ((quint64(f.dwHighDateTime) << 32) | f.dwLowDateTime) / 10000.0; };
    return ms(k) + ms(u);
#else
    struct rusage ru;
    if (getrusage(RUSAGE_SELF, &ru) != 0)
        return 0;
    return (ru.ru_utime.tv_sec + ru.ru_stime.tv_sec) * 1000.0 + (ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) / 1000.0;
#endif
}

double PerfCheck::memoryMb()
{
#ifdef Q_OS_WIN
    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof pmc))
        return pmc.WorkingSetSize / 1048576.0;
    return 0;
#else
    QFile f(QStringLiteral("/proc/self/statm"));
    if (!f.open(QIODevice::ReadOnly))
        return 0;
    const QList<QByteArray> parts = f.readAll().simplified().split(' ');
    if (parts.size() < 2)
        return 0;
    return parts.at(1).toDouble() * double(sysconf(_SC_PAGESIZE)) / 1048576.0;
#endif
}

QHash<qint64, PerfCheck::ThreadTime> PerfCheck::threadTimes()
{
    QHash<qint64, ThreadTime> out;
#ifdef Q_OS_LINUX
    const double tick = 1000.0 / double(qMax(1L, sysconf(_SC_CLK_TCK)));
    const qint64 self = qint64(getpid());
    const QDir dir(QStringLiteral("/proc/self/task"));
    for (const QString &tid : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        QFile f(dir.filePath(tid + QStringLiteral("/stat")));
        if (!f.open(QIODevice::ReadOnly))
            continue;
        // "tid (name) state ppid ...": the name may hold spaces and brackets, so it ends at the last ')'.
        const QByteArray line = f.readAll();
        const qsizetype open = line.indexOf('('), close = line.lastIndexOf(')');
        if (open < 0 || close < open)
            continue;
        const QList<QByteArray> rest = line.mid(close + 2).simplified().split(' ');
        // After the name: state is field 3, utime 14 and stime 15 (counted from 1).
        if (rest.size() < 13)
            continue;
        ThreadTime t;
        const qint64 id = tid.toLongLong();
        t.name = id == self ? QStringLiteral("window thread") : QString::fromUtf8(line.mid(open + 1, close - open - 1)).trimmed();
        t.ms = (rest.at(11).toDouble() + rest.at(12).toDouble()) * tick;
        out.insert(id, t);
    }
#endif
    return out;
}

// ------------------------------------------------------------------ the report

QString PerfCheck::factsText(const QVariantMap &ui, bool full) const
{
    QStringList out;
    auto yes = [](bool b) { return b ? QStringLiteral("yes") : QStringLiteral("no"); };
    const QString platform = QGuiApplication::platformName();
    QScreen *screen = m_window ? m_window->screen() : QGuiApplication::primaryScreen();
    const QString theme = m_settings->theme();
    const QString look = ui.value(QStringLiteral("look")).toString();
    QString state = QStringLiteral("unknown");
    QString winSize;
    if (m_window) {
        switch (m_window->visibility()) {
        case QWindow::FullScreen: state = QStringLiteral("full screen"); break;
        case QWindow::Maximized: state = QStringLiteral("maximized"); break;
        case QWindow::Minimized: state = QStringLiteral("minimized"); break;
        case QWindow::Hidden: state = QStringLiteral("hidden"); break;
        default: state = QStringLiteral("window"); break;
        }
        winSize = QStringLiteral("%1 x %2").arg(m_window->width()).arg(m_window->height());
    }
    out << QStringLiteral("Huginn performance check");
    out << QStringLiteral("Huginn: ") + QCoreApplication::applicationVersion();
    out << QStringLiteral("System: ") + QSysInfo::prettyProductName() + QStringLiteral(" (") + QSysInfo::kernelType() + QLatin1Char(' ')
               + QSysInfo::kernelVersion() + QStringLiteral(", ") + QSysInfo::currentCpuArchitecture() + QStringLiteral(", ")
               + QString::number(QThread::idealThreadCount()) + QStringLiteral(" threads)");
    out << QStringLiteral("Game mode: ") + yes(m_system->gameMode());
    out << QStringLiteral("Handheld mode: ") + m_system->handheldSummary();
    out << QStringLiteral("Power: ") + m_system->powerSummary();
    out << QStringLiteral("Display mode: ") + platform + (platform == QLatin1String("xcb") ? QStringLiteral(" (X11)") : QString());
    out << QStringLiteral("Drawing: ") + (m_system->graphics().isEmpty() ? QStringLiteral("not known yet") : m_system->graphics());
    out << QStringLiteral("Software drawing: ") + yes(m_system->softwareGraphics());
    out << QStringLiteral("Render loop: ") + (m_system->renderLoop().isEmpty() ? QStringLiteral("not known yet") : m_system->renderLoop());
    if (screen) {
        const QSizeF mm = screen->physicalSize();
        out << QStringLiteral("Screen: %1 x %2 px, %3 x %4 mm reported, %5 Hz")
                   .arg(screen->size().width()).arg(screen->size().height()).arg(qRound(mm.width())).arg(qRound(mm.height()))
                   .arg(qRound(screen->refreshRate()));
        out << QStringLiteral("DPI: %1 logical, %2 physical").arg(fixed(screen->logicalDotsPerInch())).arg(fixed(screen->physicalDotsPerInch()));
        const double dpr = m_window ? m_window->devicePixelRatio() : screen->devicePixelRatio();
        out << QStringLiteral("Pixel ratio: %1 (OS scale %2%)").arg(fixed(dpr, 2)).arg(qRound(dpr * 100))
                   + (qEnvironmentVariableIsSet("QT_SCALE_FACTOR") ? QString(QStringLiteral(", QT_SCALE_FACTOR ") + qEnvironmentVariable("QT_SCALE_FACTOR").left(8)) : QString());
    }
    out << QStringLiteral("Huginn fit: %1x, Size %2%, Text size %3%, everything %4x")
               .arg(fixed(m_system->screenScale(), 2), m_settings->uiSize(), m_settings->textSize(), fixed(m_system->uiScale(), 2));
    if (!winSize.isEmpty())
        out << QStringLiteral("Window: ") + winSize + QStringLiteral(", ") + state;
    // The Animation setting, and whether it's held still now (Theme.motion): Less motion, Battery saver or a tried
    // Animations: Off lay everything still whatever the setting says.
    QString seasonal = m_settings->seasonAnimation();
    if (seasonal != QLatin1String("off")) {
        const QString still = m_settings->reduceMotion() ? QStringLiteral("Less motion")
                              : m_system->batterySaving() ? QStringLiteral("Battery saver")
                              : s_animationsOff           ? QStringLiteral("Animations off")
                                                          : QString();
        if (!still.isEmpty())
            seasonal += QStringLiteral(" (held still: ") + still + QLatin1Char(')');
    }
    out << QStringLiteral("Theme: ") + theme + (look.isEmpty() || look == theme ? QString() : QString(QStringLiteral(" (") + look + QLatin1Char(')')))
               + QStringLiteral(", Seasonal animation ") + seasonal + QStringLiteral(", Less motion ")
               + (m_settings->reduceMotion() ? QStringLiteral("on") : QStringLiteral("off")) + QStringLiteral(", High contrast ")
               + (m_settings->highContrast() ? QStringLiteral("on") : QStringLiteral("off"));
    out << QStringLiteral("Tried settings in use: ") + describe(s_started);
    if (restartNeeded())
        out << QStringLiteral("Saved for the next start: ") + describe(m_settings->labs() ? saved() : QVariantMap());
    if (!fellBack().isEmpty())
        out << QStringLiteral("Dropped after a failed start: ") + fellBack();
    out << QStringLiteral("Memory: %1 MB").arg(qRound(memoryMb()));
    const qsizetype resultsAt = out.size();
    out << QString();
    if (m_results.size() < 3) {
        out << QStringLiteral("No check run yet.");
    } else {
        out << QStringLiteral("Verdict: ") + m_verdict;
        out << QStringLiteral("Main suspect: ") + m_suspect;
        if (!idleLine().isEmpty())
            out << idleLine();
        if (!m_busiest.isEmpty())
            out << m_busiest;
        if (!m_threadsLine.isEmpty())
            out << m_threadsLine;
        if (!m_slowLine.isEmpty())
            out << m_slowLine;
        if (full && !m_mostLine.isEmpty())
            out << m_mostLine;
        if (full) {
            out << QStringLiteral("Phase       fps  median ms  1% ms  worst ms  spikes %  draw ms  CPU %  MB");
            for (const Result &r : m_results)
                out << r.name.leftJustified(10) + QString::number(qRound(r.fps)).rightJustified(5) + fixed(r.median).rightJustified(11)
                           + fixed(r.p99).rightJustified(7) + fixed(r.worst).rightJustified(10) + fixed(r.spikes).rightJustified(10)
                           + (r.draw < 0 ? QStringLiteral("n/a") : fixed(r.draw)).rightJustified(9) + QString::number(qRound(r.cpu)).rightJustified(7)
                           + QString::number(qRound(r.memMb)).rightJustified(5);
            if (!m_busyTop.isEmpty()) {
                out << QStringLiteral("Window thread by part:");
                for (const QString &l : m_busyTop)
                    out << QStringLiteral("  ") + l;
            }
        } else {
            for (const Result &r : m_results)
                out << QStringLiteral("%1: %2 fps, median %3 ms, worst %4 ms, spikes %5%, draw %6 ms, CPU %7%")
                           .arg(r.name).arg(qRound(r.fps)).arg(fixed(r.median), fixed(r.worst), fixed(r.spikes),
                                                                (r.draw < 0 ? QStringLiteral("n/a") : fixed(r.draw)))
                           .arg(qRound(r.cpu));
        }
    }
    // The short one leads with the results (after the title and version), so a bug report's limit cuts the facts
    // instead.
    if (!full && resultsAt > 2) {
        const QStringList results = out.mid(resultsAt + 1);
        out = out.mid(0, 2) + results + QStringList{QString()} + out.mid(2, resultsAt - 2);
    }
    if (full) {
        // The last 50 graphics, warning and crash lines of crash.log.
        static const QRegularExpression wanted(
            QStringLiteral("graphic|warning|fatal|crash|render|vulkan|opengl|egl|glx|xcb|wayland|rhi|qsg|scene|gpu|driver|tried settings|performance check|ended without closing"),
            QRegularExpression::CaseInsensitiveOption);
        QStringList lines;
        for (const QString &l : CrashLog::tail(60000).split(QLatin1Char('\n'), Qt::SkipEmptyParts))
            if (l.contains(wanted))
                lines << l.trimmed().left(300);
        if (lines.size() > 50)
            lines = lines.mid(lines.size() - 50);
        out << QString();
        out << QStringLiteral("crash.log, graphics and warning lines (last %1):").arg(lines.size());
        if (lines.isEmpty())
            out << QStringLiteral("none");
        out << lines;
    }
    return out.join(QLatin1Char('\n'));
}

QString PerfCheck::scrubbed(const QString &text) const
{
    QStringList words;
    if (privateWords)
        words = privateWords();
    words << QSysInfo::machineHostName() << qEnvironmentVariable("USER") << qEnvironmentVariable("USERNAME") << QDir::home().dirName();
    return scrub(text, words);
}

QString PerfCheck::report(const QVariantMap &ui) const { return scrubbed(factsText(ui, true)); }
QString PerfCheck::shortReport(const QVariantMap &ui) const { return scrubbed(factsText(ui, false)).left(1100); }

void PerfCheck::copy(const QString &text) const
{
    if (QClipboard *c = QGuiApplication::clipboard())
        c->setText(text);
}

QString PerfCheck::scrub(const QString &text, const QStringList &words)
{
    // Most specific first: e-mail addresses before names, paths before file names.
    static const QRegularExpression email(QStringLiteral("[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+"));
    static const QRegularExpression winPath(QStringLiteral("\\b[A-Za-z]:[\\\\/][^\\s,;()]*"));
    static const QRegularExpression unixPath(QStringLiteral("(?<![\\w~])~?/[\\w.\\-]+(?:/[\\w.\\-]*)+"));
    static const QRegularExpression homeLeft(QStringLiteral("(?<![\\w])~/[^\\s,;()]*"));
    static const QRegularExpression fileName(QStringLiteral(
        "[\\w\\-]+\\.(?:jpe?g|png|gif|webp|heic|bmp|mp4|mov|mkv|webm|mp3|wav|ogg|opus|m4a|flac|pdf|txt|docx?|xlsx?|pptx?|odt|zip|7z|rar|tar|gz|apk|flatpak|exe|msi|iso|json|conf|ini)\\b"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression uuid(QStringLiteral("\\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\b"));
    static const QRegularExpression mac(QStringLiteral("\\b[0-9A-Fa-f]{2}(?:[:\\-][0-9A-Fa-f]{2}){5}\\b"));
    static const QRegularExpression v4(QStringLiteral("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"));
    // IPv6: written out (five groups or more; a time of day has three), or shortened with "::".
    static const QRegularExpression v6(QStringLiteral("(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}(?![\\w:])"));
    static const QRegularExpression v6short(
        QStringLiteral("(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?(?![\\w:])"));
    static const QRegularExpression v6lead(QStringLiteral("(?<![\\w:])::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})(?![\\w:])"));
    static const QRegularExpression hexId(QStringLiteral("\\b[0-9A-Fa-f]{16,}\\b"));
    // Not a long run of letters only: that's a name in code (OrgFreedesktopDBusPropertiesInterface), not an id.
    static const QRegularExpression b64Id(QStringLiteral("(?<![\\w+/])(?=[A-Za-z]*[0-9+/])[A-Za-z0-9+/]{32,}={0,2}"));
    static const QRegularExpression phone(QStringLiteral("\\+\\d[\\d \\-().]{7,}\\d|\\(\\d{3}\\)\\s?\\d{3}[\\-\\s]\\d{4}|\\b\\d{3}[\\-.]\\d{3}[\\-.]\\d{4}\\b"));

    QStringList lines = text.split(QLatin1Char('\n'));
    QList<QRegularExpression> named;
    for (const QString &w : words) {
        const QString t = w.trimmed();
        if (t.size() < 3 || t.compare(QLatin1String("huginn"), Qt::CaseInsensitive) == 0 || t.compare(QLatin1String("root"), Qt::CaseInsensitive) == 0)
            continue;
        named << QRegularExpression(QStringLiteral("(?<!\\w)") + QRegularExpression::escape(t) + QStringLiteral("(?!\\w)"),
                                    QRegularExpression::CaseInsensitiveOption);
    }
    const QString home = QDir::homePath();
    for (QString &line : lines) {
        QString stamp;
        const QRegularExpressionMatch m = stampRe().match(line);
        if (m.hasMatch()) {
            stamp = m.captured(0);
            line = line.mid(stamp.size());
        }
        if (home.size() > 1) {
            line.replace(home, QStringLiteral("~"), Qt::CaseInsensitive);
            line.replace(QDir::toNativeSeparators(home), QStringLiteral("~"), Qt::CaseInsensitive);
        }
        line.replace(email, QStringLiteral("[contact]"));
        line.replace(winPath, QStringLiteral("[path]"));
        line.replace(unixPath, QStringLiteral("[path]"));
        line.replace(homeLeft, QStringLiteral("[path]"));
        line.replace(fileName, QStringLiteral("[file]"));
        line.replace(uuid, QStringLiteral("[id]"));
        line.replace(mac, QStringLiteral("[id]"));
        line.replace(v4, QStringLiteral("[address]"));
        line.replace(v6, QStringLiteral("[address]"));
        line.replace(v6short, QStringLiteral("[address]"));
        line.replace(v6lead, QStringLiteral("[address]"));
        line.replace(hexId, QStringLiteral("[id]"));
        line.replace(b64Id, QStringLiteral("[id]"));
        line.replace(phone, QStringLiteral("[contact]"));
        for (const QRegularExpression &re : named)
            line.replace(re, QStringLiteral("[name]"));
        line = stamp + line;
    }
    return lines.join(QLatin1Char('\n'));
}

bool PerfCheck::selfTest()
{
    const QString home = QDir::homePath();
    const QStringList words{QStringLiteral("Mira Stone"), QStringLiteral("rogally"), QStringLiteral("Huginn on rogally")};
    const QString in = QStringLiteral("Huginn: 1.1 beta 3 on rogally\n"
                                      "Drawing: OpenGL, AMD Custom GPU 0405 (radeonsi, vangogh, LLVM 15.0.7, DRM 3.54)\n"
                                      "from 192.168.1.20 and fe80::1 and 2001:db8:0:0:0:0:0:7 and ::1\n"
                                      "mac 0a:1b:2c:3d:4e:5f id 123e4567-e89b-12d3-a456-426614174000 key 9f86d081884c7d659a2feaa0c55ad015\n"
                                      "chat key MCowBQYDK2VwAyEAq83vEu9F8pZzYbQmH1lO0hX5mF2s3T8r7c6u4w1y2z0=\n"
                                      "Most event time: MetaCall to OrgFreedesktopDBusPropertiesInterface in QBluetoothDeviceDiscoveryAgentPrivate\n"
                                      "Mira Stone wrote to mira@example.com, call +1 555-123-4567\n")
        + QStringLiteral("file ") + home + QStringLiteral("/Pictures/holiday photo.jpg and /var/tmp/x/y.log and C:\\Users\\mira\\a.txt and photo_12.jpg\n")
        + QStringLiteral("2026-11-01 14:09:50 graphics: OpenGL (threaded render loop, xcb platform)\n"
                         "Handheld mode: on (Automatic; detected: handheld, ROG Ally X, 6 to 9 inch screen, controller)\n"
                         "Screen: 1920 x 1080 px, 155 x 87 mm reported, 60 Hz\n"
                         "Scrolling    60       16.7     18.2      33.4       2.0      3.1     45  210");
    const QString out = scrub(in, words);
    bool ok = true;
    for (const char *gone : {"192.168", "fe80", "2001:db8", "::1", "0a:1b", "123e4567", "9f86d081", "MCowBQYDK2Vw", "mira", "Stone", "555-123",
                             "rogally", "Pictures", "holiday", ".jpg", "Users", "/var/tmp", "y.log"}) {
        if (out.contains(QLatin1String(gone), Qt::CaseInsensitive)) {
            std::printf("performance report scrub left in: %s\n", gone);
            ok = false;
        }
    }
    if (home.size() > 1 && out.contains(home)) {
        std::printf("performance report scrub left in the home folder\n");
        ok = false;
    }
    for (const char *kept : {"Huginn: 1.1 beta 3", "OpenGL, AMD Custom GPU 0405 (radeonsi, vangogh, LLVM 15.0.7, DRM 3.54)",
                             "2026-11-01 14:09:50 graphics: OpenGL (threaded render loop, xcb platform)", "1920 x 1080 px, 155 x 87 mm",
                             "Handheld mode: on (Automatic; detected: handheld, ROG Ally X, 6 to 9 inch screen, controller)",
                             "Scrolling    60       16.7     18.2      33.4       2.0      3.1     45  210",
                             "MetaCall to OrgFreedesktopDBusPropertiesInterface in QBluetoothDeviceDiscoveryAgentPrivate"}) {
        if (!out.contains(QLatin1String(kept))) {
            std::printf("performance report scrub lost: %s\n", kept);
            ok = false;
        }
    }
    std::printf("performance report keeps out names, addresses, ids and files: %s\n", ok ? "ok" : "FAILED");
    if (!ok)
        std::printf("%s\n", out.toLocal8Bit().constData());
    return ok;
}
