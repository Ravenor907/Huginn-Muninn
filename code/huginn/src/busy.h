#pragma once

#include <QList>
#include <QObject>
#include <QPair>
#include <QPointer>
#include <QString>
#include <QThread>

#include <atomic>
#include <functional>

class QEvent;
class QObject;

// Labs › Performance check: how long Huginn's own work held the window's thread, by kind, so a report names what kept
// it busy ("Busiest"). Counted only while a check runs and only on the window's thread; a part inside another counts
// toward the outer one there (the report's Details lists every part on its own too). Off, a Scope costs one check of a flag.
namespace Busy
{
extern std::atomic<bool> on;
extern int depth;
qint64 clockNs();
// The outermost part only (Busiest).
void add(const char *what, qint64 ns);
// Every part, inside another or not (the report's Details).
void addAll(const char *what, qint64 ns);

class Scope
{
public:
    explicit Scope(const char *what)
    {
        if (!on || !QThread::isMainThread())
            return;
        m_in = true;
        m_what = what;
        m_outer = depth++ == 0;
        m_start = clockNs();
    }
    ~Scope()
    {
        if (!m_in)
            return;
        --depth;
        const qint64 ns = clockNs() - m_start;
        addAll(m_what, ns);
        if (m_outer)
            add(m_what, ns);
    }
    Scope(const Scope &) = delete;
    Scope &operator=(const Scope &) = delete;

private:
    const char *m_what = nullptr;
    qint64 m_start = 0;
    bool m_in = false;
    bool m_outer = false;
};

// How often something happened during a check ("relay EVENTs received"), on the window's thread. Off, one check of a flag.
void count(const char *what, int n = 1);
void count(const QString &what, int n = 1);
// Gives a socket (or server, or QWebSocket) its name for reports and lists it among the open sockets at the end of a
// check. Call on the window's thread.
void nameSocket(QObject *socket, const char *name);
// Lines on what owner is doing now (transfers, calls), asked for at the end of a check while owner still exists.
void addStatus(QObject *owner, std::function<QStringList()> lines);

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
// Every part, nested ones too: most time first.
QList<Entry> allParts();
// Everything count() was told, by name.
QList<QPair<QString, qint64>> counts();
// Each socket the window's thread handled events for: who owns it, its events, time and bytes.
struct SocketEntry {
    QString what;   // "QSslSocket in QWebSocket 'nostr relay' in Nostr, peer port 443"
    int events = 0;
    double ms = 0, longest = 0;
    qint64 in = 0, out = 0;
};
// Most time first.
QList<SocketEntry> sockets();
// The named sockets and the ones events came for that are still there: kind, name, state, port.
QStringList openSockets();
// addStatus()'s lines, from the owners still there.
QStringList statusLines();

// Every event the window's thread handles while a check runs (main.cpp's notify): how long each took, by who got it
// and what kind it was, so a hold-up that none of the parts above explains still has a name. A socket's event names
// the socket and what owns it.
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
    bool m_outer = false;
    int m_gen = 0;                // which check it began in
    int m_socket = -1;            // in the sockets list
    qint64 m_bytesIn = 0;         // waiting to be read when the event came
    qint64 m_toWrite = -1;        // waiting to be written when the event came
    QPointer<QObject> m_sock;     // the QAbstractSocket, for what it wrote
};
struct EventEntry {
    QString what;   // "Timer to QTimer (every 5000 ms)", "MetaCall to Ble"
    double ms = 0, longest = 0;
    int times = 0;
    bool timer = false;
};
// Slowest single event first.
QList<EventEntry> slowestEvents();
// Most time in all first.
QList<EventEntry> busiestEvents();
// Time spent handling events (the outermost ones) since start(), in ms.
double eventMs();
}
