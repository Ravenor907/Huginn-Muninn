#include "activitylog.h"

#include <QAtomicInt>
#include <QMutex>
#include <QTime>
#include <deque>

namespace
{
QAtomicInt g_on;
QMutex g_lock;
std::deque<QString> g_lines;
}

void ActivityLog::setOn(bool on)
{
    if (g_on.fetchAndStoreOrdered(on ? 1 : 0) == (on ? 1 : 0))
        return;
    if (!on) {
        QMutexLocker l(&g_lock);
        g_lines.clear();
    }
}

bool ActivityLog::on() { return g_on.loadAcquire() != 0; }

void ActivityLog::add(const QString &tag, const QString &text)
{
    if (!on())
        return;
    QString t = text;
    t.replace(QLatin1Char('\n'), QLatin1Char(' '));
    const QString line = QTime::currentTime().toString(QStringLiteral("HH:mm:ss.zzz")) + QLatin1Char(' ') + tag + QStringLiteral(": ") + t.left(240);
    QMutexLocker l(&g_lock);
    g_lines.push_back(line);
    while (int(g_lines.size()) > kMax)
        g_lines.pop_front();
}

QString ActivityLog::id(const QString &s)
{
    QString out;
    for (const QChar c : s) {
        if (c.isLetterOrNumber() && c.unicode() < 128)
            out += c;
        if (out.size() == 6)
            break;
    }
    return out;
}

int ActivityLog::count()
{
    QMutexLocker l(&g_lock);
    return int(g_lines.size());
}

QString ActivityLog::dump(const QStringList &head)
{
    std::deque<QString> all;
    {
        QMutexLocker l(&g_lock);
        all = g_lines;
    }
    const QString top = head.join(QLatin1Char('\n'));
    qsizetype room = kMaxChars - top.size() - 1;
    std::deque<QString> keep;
    for (auto it = all.crbegin(); it != all.crend(); ++it) {
        if (it->size() + 1 > room)
            break;
        keep.push_front(*it);
        room -= it->size() + 1;
    }
    QStringList lines(keep.begin(), keep.end());
    return top + QLatin1Char('\n') + lines.join(QLatin1Char('\n'));
}
