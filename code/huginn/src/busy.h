#pragma once

#include <QList>
#include <QString>
#include <QThread>

// Labs › Performance check: how long Huginn's own work held the window's thread, by kind, so a report names what kept
// it busy ("Busiest"). Counted only while a check runs and only on the window's thread; a part inside another counts
// toward the outer one. Off, a Scope costs one check of a flag.
namespace Busy
{
extern bool on;
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
}
