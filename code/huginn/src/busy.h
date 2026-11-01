#pragma once

#include <QList>
#include <QString>
#include <QThread>

#include <atomic>

class QEvent;
class QObject;

// Labs › Performance check: how long Huginn's own work held the window's thread, by kind, so a report names what kept
// it busy ("Busiest"). Counted only while a check runs and only on the window's thread; a part inside another counts
// toward the outer one. Off, a Scope costs one check of a flag.
namespace Busy
{
extern std::atomic<bool> on;
extern int depth;
qint64 clockNs();
void add(const char *what, qint64 ns);

class Scope
{
public:
    explicit Scope(const char *what)
    {
        if (!on || !QThread::isMainThread())
            return;
        m_in = true;
        if (depth++ == 0) {
            m_what = what;
            m_start = clockNs();
        }
    }
    ~Scope()
    {
        if (!m_in)
            return;
        --depth;
        if (m_what)
            add(m_what, clockNs() - m_start);
    }
    Scope(const Scope &) = delete;
    Scope &operator=(const Scope &) = delete;

private:
    const char *m_what = nullptr;
    qint64 m_start = 0;
    bool m_in = false;
};

struct Entry {
    QString what;
    double ms = 0;        // in all
    double longest = 0;   // the longest single time
    int times = 0;
};
// Clears the counts and starts counting.
void start();
void stop();
// Most time first.
QList<Entry> sorted();

// Every event the window's thread handles while a check runs (main.cpp's notify): how long each took, by who got it
// and what kind it was, so a hold-up that none of the parts above explains still has a name.
class Event
{
public:
    // Before the event is handled (the receiver may be gone after it).
    Event(QObject *receiver, QEvent *event);
    ~Event();
    Event(const Event &) = delete;
    Event &operator=(const Event &) = delete;

private:
    qint64 m_start = 0;
    int m_key = -1;
    bool m_in = false;
};
struct EventEntry {
    QString what;   // "Timer to QTimer (every 5000 ms)", "MetaCall to Ble"
    double ms = 0, longest = 0;
    int times = 0;
};
// Slowest single event first.
QList<EventEntry> slowestEvents();
// Most time in all first.
QList<EventEntry> busiestEvents();
// Time spent handling events (the outermost ones) since start(), in ms.
double eventMs();
}
