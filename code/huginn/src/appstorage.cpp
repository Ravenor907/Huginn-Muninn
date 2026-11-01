#include "appstorage.h"
#include "updater.h"

#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QSet>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QVariantMap>
#include <algorithm>
#include <cstdio>

namespace
{
// One place that is safe to empty: everything under [dir] in [root], or with [dir] empty, files right in [root]
// whose names start with [prefix] and end with [suffix] ([exact]: named exactly [prefix]).
struct Spot
{
    char root;
    const char *dir;
    const char *kind;
    const char *folder;   // what the screen calls it
    const char *prefix;
    const char *suffix;
    bool exact;
};

const Spot kSpots[] = {
    // Update downloads (Updater::download): the Flatpak bundle or the Windows zip.
    {'c', "", "update", "updates", "Huginn-", ".flatpak", false},
    {'c', "", "update", "updates", "Huginn-", ".zip", false},
    // Qt's compiled QML cache: made again when needed.
    {'c', "qmlcache", "cache", "qmlcache", "", "", false},
    // crash.log (platform/crashlog.cpp). running.mark beside it stays.
    {'d', "", "crash", "crash log", "crash.log", "", true},
};

const int kMaxId = 400;
const int kMaxItems = 2000;
const int kMaxDepth = 8;
const qint64 kBusyMs = 60000;   // changed in the last minute: still being written, not listed

QString word(const QString &kind)
{
    if (kind == QLatin1String("received")) return QStringLiteral("Received");
    if (kind == QLatin1String("sent")) return QStringLiteral("Sent copy");
    if (kind == QLatin1String("update")) return QStringLiteral("Update");
    if (kind == QLatin1String("crash")) return QStringLiteral("Crash log");
    return QStringLiteral("Cache");
}

// The spot [id] is in, or null.
const Spot *spotOf(const QString &id)
{
    if (id.size() < 3 || id.size() > kMaxId || id.at(1) != QLatin1Char('/'))
        return nullptr;
    for (const QChar c : id)
        if (c == QLatin1Char('\\') || c == QLatin1Char(':') || c.unicode() < 0x20 || c.unicode() == 0x7f)
            return nullptr;
    const QStringList segs = id.mid(2).split(QLatin1Char('/'));
    if (segs.size() > kMaxDepth + 1)
        return nullptr;
    for (const QString &s : segs)
        if (s.isEmpty() || s == QLatin1String(".") || s == QLatin1String(".."))
            return nullptr;
    if (id.at(0).unicode() > 0x7e)
        return nullptr;
    const char root = char(id.at(0).unicode());
    for (const Spot &s : kSpots) {
        if (s.root != root)
            continue;
        const QString dir = QString::fromLatin1(s.dir);
        if (dir.isEmpty()) {
            if (segs.size() != 1)
                continue;
            const QString n = segs.first(), pre = QString::fromLatin1(s.prefix), suf = QString::fromLatin1(s.suffix);
            if (s.exact ? n == pre : (n.startsWith(pre) && n.endsWith(suf) && n.size() > pre.size() + suf.size()))
                return &s;
        } else if (segs.size() >= 2 && segs.first() == dir) {
            return &s;
        }
    }
    return nullptr;
}
}

AppStorage::AppStorage(Updater *updater, QObject *parent)
    : QObject(parent)
    , m_updater(updater)
{
}

bool AppStorage::allowed(const QString &id) { return spotOf(id) != nullptr; }

AppStorage::Roots AppStorage::roots()
{
    Roots r;
    const QString c = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
    const QString d = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (!c.isEmpty()) r.insert(QLatin1Char('c'), c);
    if (!d.isEmpty()) r.insert(QLatin1Char('d'), d);
    return r;
}

QString AppStorage::keep() const
{
    return m_updater ? m_updater->readyPath() : QString();
}

double AppStorage::total() const
{
    double t = 0;
    for (const QVariant &v : m_items)
        t += v.toMap().value(QStringLiteral("size")).toDouble();
    return t;
}

QString AppStorage::resolve(const Roots &roots, const QString &id)
{
    if (!spotOf(id) || !roots.contains(id.at(0)))
        return {};
    const QString base = QFileInfo(roots.value(id.at(0))).canonicalFilePath();
    if (base.isEmpty())
        return {};
    const QString want = base + QLatin1Char('/') + id.mid(2);
    const QFileInfo fi(want);
    // A link anywhere along the way would point somewhere else: refused.
    if (!fi.exists() || fi.isSymLink() || !fi.isFile() || fi.canonicalFilePath() != want)
        return {};
    return want;
}

QVariantList AppStorage::scan(const Roots &roots, qint64 now, const QString &keep)
{
    QList<QVariantMap> out;
    const QString kept = keep.isEmpty() ? QString() : QFileInfo(keep).canonicalFilePath();
    auto add = [&](const Spot &s, const QString &base, const QFileInfo &fi) {
        if (out.size() >= kMaxItems || fi.isSymLink() || !fi.isFile())
            return;
        const qint64 at = fi.lastModified().toMSecsSinceEpoch();
        if (now - at >= 0 && now - at < kBusyMs)
            return;
        const QString path = fi.canonicalFilePath();
        if (path.isEmpty() || !path.startsWith(base + QLatin1Char('/')) || (!kept.isEmpty() && path == kept))
            return;
        const QString id = QString(QLatin1Char(s.root)) + QLatin1Char('/') + path.mid(base.size() + 1);
        if (spotOf(id) != &s)
            return;
        const QString kind = QString::fromLatin1(s.kind);
        out.append(QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("name"), fi.fileName()}, {QStringLiteral("size"), double(fi.size())},
                    {QStringLiteral("at"), double(at)}, {QStringLiteral("kind"), kind}, {QStringLiteral("word"), word(kind)},
                    {QStringLiteral("folder"), QString::fromLatin1(s.folder)}});
    };
    for (const Spot &s : kSpots) {
        const QString root = roots.value(QLatin1Char(s.root));
        if (root.isEmpty())
            continue;
        const QString base = QFileInfo(root).canonicalFilePath();
        if (base.isEmpty())
            continue;
        if (!*s.dir) {
            const QFileInfoList files = QDir(base).entryInfoList(QDir::Files | QDir::Hidden | QDir::NoSymLinks);
            for (const QFileInfo &fi : files)
                add(s, base, fi);
            continue;
        }
        const QFileInfo top(base + QLatin1Char('/') + QString::fromLatin1(s.dir));
        if (!top.isDir() || top.isSymLink())
            continue;
        // Never follows links (QDirIterator doesn't unless asked).
        QDirIterator it(top.filePath(), QDir::Files | QDir::Hidden | QDir::NoSymLinks, QDirIterator::Subdirectories);
        while (it.hasNext() && out.size() < kMaxItems)
            add(s, base, QFileInfo(it.next()));
    }
    std::sort(out.begin(), out.end(), [](const QVariantMap &a, const QVariantMap &b) {
        return a.value(QStringLiteral("at")).toDouble() > b.value(QStringLiteral("at")).toDouble();
    });
    QVariantList list;
    for (const QVariantMap &m : out)
        list.append(m);
    return list;
}

bool AppStorage::removeIn(const Roots &roots, const QString &id)
{
    const QString path = resolve(roots, id);
    if (path.isEmpty() || !QFile::remove(path))
        return false;
    const Spot *s = spotOf(id);
    if (!s || !*s->dir)
        return true;
    // Folders it leaves empty inside its spot go too; the spot's own folder stays.
    const QString top = QFileInfo(roots.value(id.at(0))).canonicalFilePath() + QLatin1Char('/') + QString::fromLatin1(s->dir);
    QString d = QFileInfo(path).absolutePath();
    while (d.startsWith(top + QLatin1Char('/'))) {
        QDir dir(d);
        if (!dir.isEmpty(QDir::AllEntries | QDir::NoDotAndDotDot | QDir::Hidden | QDir::System) || !QDir().rmdir(d))
            break;
        d = QFileInfo(d).absolutePath();
    }
    return true;
}

void AppStorage::refresh()
{
    m_items = scan(roots(), QDateTime::currentMSecsSinceEpoch(), keep());
    Q_EMIT changed();
}

bool AppStorage::remove(const QString &id)
{
    const QString path = resolve(roots(), id);
    if (path.isEmpty() || (!keep().isEmpty() && QFileInfo(keep()).canonicalFilePath() == path))
        return false;
    const bool ok = removeIn(roots(), id);
    refresh();
    return ok;
}

int AppStorage::removeKind(const QString &kind)
{
    if (kind != QLatin1String("received") && kind != QLatin1String("cache"))
        return 0;
    m_busy = true;
    Q_EMIT changed();
    const Roots r = roots();
    int n = 0;
    for (const QVariant &v : scan(r, QDateTime::currentMSecsSinceEpoch(), keep())) {
        const QVariantMap m = v.toMap();
        if (m.value(QStringLiteral("kind")).toString() == kind && removeIn(r, m.value(QStringLiteral("id")).toString()))
            n++;
    }
    m_busy = false;
    refresh();
    return n;
}

bool AppStorage::selfTest()
{
    bool all = true;
    auto check = [&](const char *what, bool ok) {
        std::printf("app storage self-test: %s %s\n", what, ok ? "ok" : "WRONG");
        all = all && ok;
    };

    bool yes = true;
    for (const char *id : {"c/Huginn-v1.1.flatpak", "c/Huginn-v1.1.zip", "c/qmlcache/a/b.qmlc", "d/crash.log"})
        yes = yes && allowed(QString::fromLatin1(id));
    check("allowlist takes the safe spots", yes);
    bool no = true;
    for (const char *id : {"d/chat/vault.key", "d/chat/chats.json", "d/tls/key.pem", "d/ring-key.json", "d/running.mark",
                           "d/labs/reports.json", "c/move-in/part", "c/Huginn-.zip", "c/Huginn-v1.exe", "d/Huginn-v1.zip",
                           "c/qmlcache/../../d/chat/vault.key", "c/qmlcache/./a", "c/qmlcache//a", "c/qmlcache", "c/qmlcache/",
                           "c\\qmlcache\\a", "c/qmlcache/C:x", "/c/qmlcache/a", "x/crash.log", "d/crash.log.old", "c/crash.log", "", "c"})
        no = no && !allowed(QString::fromLatin1(id));
    no = no && !allowed(QStringLiteral("c/qmlcache/") + QString(kMaxId, QLatin1Char('a')));
    check("allowlist refuses everything else", no);

    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        check("temp folder", false);
        return false;
    }
    const QString c = tmp.path() + QStringLiteral("/cache"), d = tmp.path() + QStringLiteral("/data");
    const QDateTime old = QDateTime::currentDateTime().addSecs(-600);
    auto put = [&](const QString &path) {
        QDir().mkpath(QFileInfo(path).absolutePath());
        QFile f(path);
        if (f.open(QIODevice::WriteOnly)) {
            f.write("x");
            f.flush();   // written first, so closing doesn't stamp it with the time now
            f.setFileTime(old, QFileDevice::FileModificationTime);
            f.close();
        }
    };
    put(c + QStringLiteral("/Huginn-v1.1.flatpak"));
    put(c + QStringLiteral("/Huginn-v1.2.flatpak"));
    put(c + QStringLiteral("/qmlcache/a/b.qmlc"));
    put(c + QStringLiteral("/move-in/part"));
    put(d + QStringLiteral("/crash.log"));
    put(d + QStringLiteral("/running.mark"));
    put(d + QStringLiteral("/chat/vault.key"));
    put(d + QStringLiteral("/chat/chats.json"));
    const Roots r{{QLatin1Char('c'), c}, {QLatin1Char('d'), d}};

    QSet<QString> ids;
    for (const QVariant &v : scan(r, QDateTime::currentMSecsSinceEpoch(), c + QStringLiteral("/Huginn-v1.2.flatpak")))
        ids.insert(v.toMap().value(QStringLiteral("id")).toString());
    check("lists only the allowlist, and leaves the waiting update", ids == QSet<QString>{QStringLiteral("c/Huginn-v1.1.flatpak"),
                                                                                         QStringLiteral("c/qmlcache/a/b.qmlc"), QStringLiteral("d/crash.log")});
    check("never deletes the vault", !removeIn(r, QStringLiteral("d/chat/vault.key")) && !removeIn(r, QStringLiteral("c/qmlcache/../../data/chat/chats.json"))
                                         && QFile::exists(d + QStringLiteral("/chat/vault.key")) && QFile::exists(d + QStringLiteral("/chat/chats.json")));
#ifndef Q_OS_WIN
    QDir().mkpath(c + QStringLiteral("/qmlcache/l"));
    if (QFile::link(d + QStringLiteral("/chat/vault.key"), c + QStringLiteral("/qmlcache/l/k")))
        check("a link to the vault is refused", resolve(r, QStringLiteral("c/qmlcache/l/k")).isEmpty() && !removeIn(r, QStringLiteral("c/qmlcache/l/k"))
                                                    && QFile::exists(d + QStringLiteral("/chat/vault.key")));
    QFile::remove(c + QStringLiteral("/qmlcache/l/k"));
#endif
    check("deletes a cache file and its empty folder", removeIn(r, QStringLiteral("c/qmlcache/a/b.qmlc")) && !QFileInfo::exists(c + QStringLiteral("/qmlcache/a"))
                                                          && QFileInfo::exists(c + QStringLiteral("/qmlcache")));
    check("deletes the crash log only", removeIn(r, QStringLiteral("d/crash.log")) && QFile::exists(d + QStringLiteral("/running.mark")));
    return all;
}
