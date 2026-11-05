#pragma once
// Someone's app version (1.45, the same as Muninn's AppVer): "av" in an empty receipt (a caps probe, its answer and
// key announcements), inside the sealed payload. An older app ignores the field. Kept per contact only.
// Form: "Huginn 1.45 · Windows/Linux", "Muninn 1.45 · Android TV", "Muninn · Android phone/tablet".

#include <QRegularExpression>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QPair>
#include <QList>

namespace AppVer {

inline const QString &dot() { static const QString d = QStringLiteral(" · "); return d; }
constexpr int kMax = 48;

struct Parsed { QString app, version, platform; bool ok = false; };

inline Parsed parse(const QString &raw)
{
    Parsed p;
    const QString t = raw.trimmed();
    if (t.isEmpty() || t.size() > kMax)
        return p;
    const int i = t.indexOf(dot());
    if (i <= 0)
        return p;
    const QString plat = t.mid(i + dot().size());
    static const QStringList plats{QStringLiteral("Android TV"), QStringLiteral("Android phone/tablet"),
                                   QStringLiteral("Windows/Linux"), QStringLiteral("Windows"), QStringLiteral("Linux")};
    if (!plats.contains(plat))
        return p;
    static const QRegularExpression form(QStringLiteral("^(Muninn|Huginn)(?: (\\d+\\.\\d+(?: (?:beta|Test) \\d+)?))?$"));
    const auto m = form.match(t.left(i));
    if (!m.hasMatch())
        return p;
    p.app = m.captured(1); p.version = m.captured(2); p.platform = plat; p.ok = true;
    return p;
}

inline QString shown(const Parsed &p) { return (p.version.isEmpty() ? p.app : p.app + QLatin1Char(' ') + p.version) + dot() + p.platform; }
inline QString clean(const QString &s) { const Parsed p = parse(s); return p.ok ? shown(p) : QString(); }

// (number, stable 1 or beta 0, beta number), as Updater::compareVersions sorts.
inline QList<qint64> key(const QString &v)
{
    static const QRegularExpression f(QStringLiteral("^(\\d+)\\.(\\d+)(?: (?:beta|Test) (\\d+))?$"));
    const auto m = f.match(v.trimmed());
    if (!m.hasMatch())
        return {-1, 0, 0};
    const qint64 num = m.captured(1).toLongLong() * 1000000 + (m.captured(2) + QStringLiteral("000000")).left(6).toLongLong();
    return m.captured(3).isEmpty() ? QList<qint64>{num, 1, 0} : QList<qint64>{num, 0, m.captured(3).toLongLong()};
}
inline int compare(const QString &a, const QString &b)
{
    const auto x = key(a), y = key(b);
    for (int i = 0; i < 3; ++i)
        if (x[i] != y[i])
            return x[i] < y[i] ? -1 : 1;
    return 0;
}

inline QString make(const QString &app, const QString &version, const QString &platform)
{
    const bool known = key(version).at(0) >= 0;
    return (known ? app + QLatin1Char(' ') + version.trimmed() : app) + dot() + platform;
}

inline bool older(const QString &theirs, const QString &mine)
{
    const Parsed p = parse(theirs);
    return p.ok && !p.version.isEmpty() && compare(p.version, mine) < 0;
}
inline bool atLeast(const QString &theirs, const QString &min)
{
    const Parsed p = parse(theirs);
    return p.ok && !p.version.isEmpty() && compare(p.version, min) >= 0;
}
inline QString askText(const QString &theirs)
{
    const Parsed p = parse(theirs);
    return QStringLiteral("Could you update %1? You're on an older version.").arg(p.ok ? p.app : QStringLiteral("Muninn"));
}

// User-facing features both apps have, by cap, in plain words (kids' TV caps are Muninn only, never listed).
inline const QList<QPair<QString, QString>> &features()
{
    static const QList<QPair<QString, QString>> f{
        {QStringLiteral("g1"), QStringLiteral("group chats")}, {QStringLiteral("c1"), QStringLiteral("calls")},
        {QStringLiteral("c2"), QStringLiteral("video calls")}, {QStringLiteral("v1"), QStringLiteral("voice rooms")},
        {QStringLiteral("m1"), QStringLiteral("replies and reactions")}, {QStringLiteral("m2"), QStringLiteral("albums, polls and video messages")},
        {QStringLiteral("i1"), QStringLiteral("introductions")}, {QStringLiteral("f1"), QStringLiteral("files over the internet")},
        {QStringLiteral("j1"), QStringLiteral("group invite links")}, {QStringLiteral("x1"), QStringLiteral("moving to another device")},
        {QStringLiteral("p1"), QStringLiteral("profile pictures")}, {QStringLiteral("a1"), QStringLiteral("GIFs")},
        {QStringLiteral("f2"), QStringLiteral("file sharing")}, {QStringLiteral("f3"), QStringLiteral("resuming files")},
        {QStringLiteral("g2"), QStringLiteral("group timeouts and bans")}, {QStringLiteral("n1"), QStringLiteral("nudges")},
        {QStringLiteral("f4"), QStringLiteral("encrypted file sharing")}, {QStringLiteral("v3"), QStringLiteral("picked videos")}};
    return f;
}
inline QStringList missing(const QStringList &theirs, const QStringList &mine)
{
    QStringList out;
    if (theirs.isEmpty())
        return out;
    for (const auto &f : features())
        if (mine.contains(f.first) && !theirs.contains(f.first))
            out << f.second;
    return out;
}

} // namespace AppVer
