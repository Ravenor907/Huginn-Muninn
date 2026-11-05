#include "dailyverse.h"
#include "settings.h"
#include "vault.h"

#include <QDate>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSettings>
#include <QTemporaryDir>
#include <QUrl>
#include <cstdio>
#include <iterator>
#include <numeric>
#include <utility>

namespace {
const QString kOn = QStringLiteral("dailyVerse/on");
const QString kHidden = QStringLiteral("dailyVerse/hidden");
const QString kWording = QStringLiteral("dailyVerse/wording");
const QString kStart = QStringLiteral("dailyVerse/start");
const QString kOrder = QStringLiteral("dailyVerse/order");
const QString kRandomFrom = QStringLiteral("dailyVerse/randomFrom");
const QString kSeed = QStringLiteral("dailyVerse/seed");
const QString kSkipDay = QStringLiteral("dailyVerse/skipDay");
const QString kSkip = QStringLiteral("dailyVerse/skip");
const QString kInOrder = QStringLiteral("o");
const QString kRandom = QStringLiteral("r");

// SplitMix64: the same numbers from the same seed on every computer and Qt version (the shuffle must never change
// under an update, or Earlier verses would).
quint64 nextRandom(quint64 &x)
{
    x += 0x9E3779B97F4A7C15ULL;
    quint64 z = x;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

QList<int> rawShuffle(quint64 seed, qint64 cycle, int size)
{
    QList<int> p(size);
    std::iota(p.begin(), p.end(), 0);
    quint64 x = seed ^ (quint64(cycle) * 0xD1B54A32D192ED03ULL);
    for (int i = size - 1; i > 0; --i)
        std::swap(p[i], p[int(nextRandom(x) % quint64(i + 1))]);
    return p;
}

qint64 floorDiv(qint64 a, qint64 b)
{
    const qint64 q = a / b;
    return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q;
}
const QString kBellows = QStringLiteral("b");
const QString kMine = QStringLiteral("y");

QString firstLine(const QString &t)
{
    for (const QString &l : t.split(QLatin1Char('\n')))
        if (!l.trimmed().isEmpty())
            return l.trimmed();
    return QString();
}
} // namespace

DailyVerse::DailyVerse(Vault *vault, QObject *parent)
    : QObject(parent)
    , m_vault(vault)
{
    QFile f(QStringLiteral(":/huginn/havamal.json"));
    if (f.open(QIODevice::ReadOnly))
        m_verses = parse(f.readAll());
    m_day = dayNumber();
    // A new day at midnight: the next verse, and yesterday's X is forgotten.
    m_clock.setInterval(60000);
    connect(&m_clock, &QTimer::timeout, this, [this] {
        const qint64 d = dayNumber();
        if (d == m_day) return;
        m_day = d;
        Q_EMIT changed();
    });
    m_clock.start();
    if (m_vault) {
        connect(m_vault, &Vault::opened, this, &DailyVerse::loadMine);
        // Round 19: your own translation is vault data, so it isn't kept in memory while the lock is up or after an
        // erase. It's read again on the next unlock (opened() comes only with the first one).
        const auto drop = [this](bool again) {
            if (m_mine.isEmpty() && m_mineOf == 0) { m_mineDropped = again; return; }
            m_mine.clear();
            m_mineOf = 0;
            m_mineDropped = again;
            Q_EMIT changed();
        };
        connect(m_vault, &Vault::lockedNow, this, [drop] { drop(true); });
        connect(m_vault, &Vault::panicked, this, [drop] { drop(true); });
        connect(m_vault, &Vault::erased, this, [drop] { drop(false); });
        connect(m_vault, &Vault::changed, this, [this] {
            if (!m_mineDropped || m_vault->locked() || m_vault->screenLocked()) return;
            m_mineDropped = false;
            loadMine();
        });
        if (!m_vault->locked())
            loadMine();
    }
}

// ------------------------------------------------------------- the rules

QList<DailyVerse::Verse> DailyVerse::parse(const QByteArray &json)
{
    QList<Verse> out;
    const QJsonArray a = QJsonDocument::fromJson(json).object().value(QStringLiteral("verses")).toArray();
    QSet<int> seen;
    for (const QJsonValue &e : a) {
        const QJsonObject o = e.toObject();
        Verse v;
        v.n = o.value(QStringLiteral("n")).toInt();
        v.verse = o.value(QStringLiteral("verse")).toString().trimmed();
        v.means = o.value(QStringLiteral("means")).toString().trimmed();
        if (v.n <= 0 || v.verse.isEmpty() || v.means.isEmpty() || seen.contains(v.n))
            return {};
        seen.insert(v.n);
        out.push_back(v);
    }
    return out;
}

int DailyVerse::indexFor(qint64 day, int size)
{
    if (size <= 0) return 0;
    const qint64 m = day % size;
    return int(m < 0 ? m + size : m);
}

QList<int> DailyVerse::earlierFor(qint64 today, qint64 start, int size, int max)
{
    if (size <= 0) return {};
    return earlierBy(today, start, indexFor(today, size), [size](qint64 d) { return indexFor(d, size); }, max);
}

QList<int> DailyVerse::earlierBy(qint64 today, qint64 start, int now, const std::function<int(qint64)> &at, int max)
{
    QList<int> out;
    for (qint64 d = today - 1; d >= start && d >= today - max && out.size() < max; --d) {
        const int i = at(d);
        if (i >= 0 && i != now && !out.contains(i)) out.push_back(i);
    }
    return out;
}

QList<int> DailyVerse::shuffled(quint64 seed, qint64 cycle, int size)
{
    if (size <= 0) return {};
    QList<int> p = rawShuffle(seed, cycle, size);
    // Never the same verse two days running where one cycle ends and the next begins.
    if (size > 1 && p.first() == rawShuffle(seed, cycle - 1, size).last())
        std::swap(p[0], p[1]);
    return p;
}

int DailyVerse::indexAt(qint64 pos, int size, bool random, quint64 seed)
{
    if (size <= 0) return 0;
    if (!random) return indexFor(pos, size);
    const qint64 cycle = floorDiv(pos, size);
    return shuffled(seed, cycle, size).at(int(pos - cycle * size));
}

QMap<int, QString> DailyVerse::parseImport(const QString &text, const QSet<int> &wanted)
{
    // A number at the start of a line ("1", "1.", "1)", "St. 1", "Stanza 1"), the stanza after it on that line or the
    // lines below, until the next number. Numbers must go up, so a number inside a verse doesn't start one.
    static const QRegularExpression mark(QStringLiteral("^\\s*(?:(?:st|stanza|str|strophe)\\.?\\s*)?(\\d{1,3})\\s*[.):]?(?:\\s+(.*))?$"),
                                         QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression blankLines(QStringLiteral("\n{2,}"));
    QMap<int, QString> out;
    int cur = -1, last = 0;
    QStringList lines;
    const auto close = [&] {
        if (wanted.contains(cur)) {
            QString t = lines.join(QLatin1Char('\n')).trimmed();
            t.replace(blankLines, QStringLiteral("\n"));
            if (!t.isEmpty() && !out.contains(cur)) out.insert(cur, t.left(kMaxStanza));
        }
        lines.clear();
    };
    QString all = text;
    if (all.startsWith(QChar(0xFEFF))) all.remove(0, 1);
    all.replace(QStringLiteral("\r\n"), QStringLiteral("\n"));
    all.replace(QLatin1Char('\r'), QLatin1Char('\n'));
    for (const QString &raw : all.split(QLatin1Char('\n'))) {
        QString line;
        line.reserve(raw.size());
        for (const QChar c : raw) {
            const char16_t u = c.unicode();
            if (u == u'\t') line += QLatin1Char(' ');
            else if (u >= 0x20 && u != 0x7F) line += c;
        }
        qsizetype end = line.size();
        while (end > 0 && line.at(end - 1).isSpace()) --end;
        line.truncate(end);
        const QRegularExpressionMatch m = mark.match(line);
        const int n = m.hasMatch() ? m.captured(1).toInt() : 0;
        if (m.hasMatch() && n > last && n <= 999) {
            close();
            cur = n;
            last = n;
            const QString rest = m.captured(2).trimmed();
            if (!rest.isEmpty()) lines << rest;
        } else if (cur > 0) {
            lines << line.trimmed();
        }
    }
    close();
    while (out.size() > kMaxStanzas) out.erase(std::prev(out.end()));
    return out;
}

QString DailyVerse::wordingOf(const Verse &v, const QString &wording, const QMap<int, QString> &mine)
{
    if (wording == kMine) {
        const QString t = mine.value(v.n);
        if (!t.trimmed().isEmpty()) return t;
    }
    return v.verse;
}

qint64 DailyVerse::dayNumber() { return QDate(1970, 1, 1).daysTo(QDate::currentDate()); }

bool DailyVerse::onIn(const QSettings &s) { return s.value(kOn, kDefaultOn).toBool(); }

void DailyVerse::setOnIn(QSettings &s, bool v, qint64 today)
{
    s.setValue(kOn, v);
    if (v) {
        bool ok = false;
        s.value(kStart).toLongLong(&ok);
        if (!ok) s.setValue(kStart, today);
    } else {
        // Off: Earlier verses starts over and today's X is forgotten.
        s.remove(kStart);
        s.remove(kHidden);
    }
}

void DailyVerse::hideIn(QSettings &s, qint64 today) { s.setValue(kHidden, today); }

bool DailyVerse::shownIn(const QSettings &s, qint64 today)
{
    bool ok = false;
    const qint64 hidden = s.value(kHidden).toLongLong(&ok);
    return onIn(s) && !(ok && hidden == today);
}

qint64 DailyVerse::startIn(QSettings &s, qint64 today)
{
    // Never after today: a clock set back starts it again from today.
    bool ok = false;
    const qint64 v = s.value(kStart).toLongLong(&ok);
    if (ok && v <= today) return v;
    s.setValue(kStart, today);
    return today;
}

QString DailyVerse::wordingIn(const QSettings &s) { return s.value(kWording).toString() == kMine ? kMine : kBellows; }

QString DailyVerse::orderIn(const QSettings &s) { return s.value(kOrder).toString() == kRandom ? kRandom : kInOrder; }

void DailyVerse::setOrderIn(QSettings &s, const QString &v, qint64 today)
{
    const QString pick = v == kRandom ? kRandom : kInOrder;
    if (pick == orderIn(s)) return;
    s.setValue(kOrder, pick);
    // Random's first cycle starts today, so no verse comes again until all have shown. Today's Next verse starts over.
    if (pick == kRandom) {
        s.setValue(kRandomFrom, today);
        seedIn(s);
    }
    s.remove(kSkipDay);
    s.remove(kSkip);
}

quint64 DailyVerse::seedIn(QSettings &s)
{
    bool ok = false;
    const quint64 have = s.value(kSeed).toString().toULongLong(&ok, 16);
    if (ok) return have;
    const quint64 fresh = QRandomGenerator::system()->generate64();
    s.setValue(kSeed, QString::number(fresh, 16));
    return fresh;
}

qint64 DailyVerse::posIn(QSettings &s, qint64 day)
{
    if (orderIn(s) != kRandom) return day;
    bool ok = false;
    const qint64 from = s.value(kRandomFrom).toLongLong(&ok);
    return ok ? day - from : day;
}

int DailyVerse::skipIn(const QSettings &s, qint64 today)
{
    bool ok = false, ok2 = false;
    const qint64 day = s.value(kSkipDay).toLongLong(&ok);
    const int n = s.value(kSkip).toInt(&ok2);
    return ok && ok2 && day == today && n > 0 ? n : 0;
}

void DailyVerse::skipNextIn(QSettings &s, qint64 today)
{
    const int n = skipIn(s, today) + 1;
    s.setValue(kSkipDay, today);
    s.setValue(kSkip, n);
}

// ------------------------------------------------------------- what QML uses

bool DailyVerse::verseOn() const { return m_tour || onIn(Settings::store()); }

void DailyVerse::setVerseOn(bool v)
{
    if (m_tour) return;
    QSettings s = Settings::store();
    setOnIn(s, v, dayNumber());
    Q_EMIT changed();
}

QString DailyVerse::verseWording() const { return m_tour ? kBellows : wordingIn(Settings::store()); }

void DailyVerse::setVerseWording(const QString &v)
{
    if (m_tour) return;
    Settings::store().setValue(kWording, v == kMine ? kMine : kBellows);
    Q_EMIT changed();
}

bool DailyVerse::cardShown() const { return !m_verses.isEmpty() && (m_tour || shownIn(Settings::store(), dayNumber())); }

QVariantMap DailyVerse::toMap(const Verse &v) const
{
    const QString text = wordingOf(v, verseWording(), m_mine);
    return { { QStringLiteral("n"), v.n }, { QStringLiteral("verse"), text }, { QStringLiteral("means"), v.means },
             { QStringLiteral("first"), firstLine(text) } };
}

QString DailyVerse::verseOrder() const { return m_tour ? kInOrder : orderIn(Settings::store()); }

void DailyVerse::setVerseOrder(const QString &v)
{
    if (m_tour) return;
    QSettings s = Settings::store();
    setOrderIn(s, v, dayNumber());
    Q_EMIT changed();
}

int DailyVerse::indexOfDay(qint64 day) const
{
    const int size = int(m_verses.size());
    if (size <= 0) return 0;
    const qint64 today = dayNumber();
    if (m_tour) return indexFor(day + (day == today ? m_tourSkip : 0), size);
    QSettings s = Settings::store();
    const bool random = orderIn(s) == kRandom;
    const qint64 skip = day == today ? skipIn(s, today) : 0;
    return indexAt(posIn(s, day) + skip, size, random, random ? seedIn(s) : 0);
}

QVariantMap DailyVerse::today() const
{
    if (m_verses.isEmpty()) return {};
    return toMap(m_verses.at(indexOfDay(dayNumber())));
}

void DailyVerse::nextVerse()
{
    if (m_verses.isEmpty()) return;
    if (m_tour) ++m_tourSkip;
    else { QSettings s = Settings::store(); skipNextIn(s, dayNumber()); }
    Q_EMIT changed();
}

QString DailyVerse::mineLine() const
{
    if (m_mine.isEmpty()) return QString();
    // Out of every verse there is now (an import made when there were 60 says "of 165" too).
    return QStringLiteral("Imported: %1 of %2 verses matched").arg(m_mine.size()).arg(m_verses.isEmpty() ? m_mineOf : int(m_verses.size()));
}

void DailyVerse::setTour(bool v)
{
    if (m_tour == v) return;
    m_tour = v;
    Q_EMIT changed();
}

void DailyVerse::hideToday()
{
    if (m_tour) return;
    QSettings s = Settings::store();
    hideIn(s, dayNumber());
    Q_EMIT changed();
}

QVariantList DailyVerse::earlier()
{
    QVariantList out;
    if (m_verses.isEmpty()) return out;
    const qint64 today = dayNumber();
    qint64 start = today;
    if (m_tour) start = today - 3;   // the tour shows a few
    else { QSettings s = Settings::store(); start = startIn(s, today); }
    // Each day's verse in the order picked now (In order or Random), newest first, without the one shown today.
    for (const int i : earlierBy(today, start, indexOfDay(today), [this](qint64 d) { return indexOfDay(d); }))
        out.push_back(toMap(m_verses.at(i)));
    return out;
}

void DailyVerse::loadMine()
{
    if (!m_vault) return;
    const QByteArray raw = m_vault->read(QString::fromLatin1(kFile));
    m_mine.clear();
    m_mineOf = 0;
    if (!raw.isEmpty()) {
        const QJsonObject o = QJsonDocument::fromJson(raw).object();
        const QJsonObject t = o.value(QStringLiteral("text")).toObject();
        for (auto it = t.begin(); it != t.end(); ++it) {
            bool ok = false;
            const int n = it.key().toInt(&ok);
            const QString v = it.value().toString();
            if (ok && n >= 1 && n <= 999 && it.key() == QString::number(n) && v.size() <= kMaxStanza && !v.isEmpty())
                m_mine.insert(n, v);
            if (m_mine.size() >= kMaxStanzas) break;
        }
        m_mineOf = o.value(QStringLiteral("of")).toInt();
    }
    Q_EMIT changed();
}

QString DailyVerse::importFile(const QString &path)
{
    if (m_verses.isEmpty()) return QStringLiteral("Can't read the verses");
    if (!m_vault || m_vault->screenLocked()) return QStringLiteral("Unlock Huginn first.");
    const QString local = path.startsWith(QLatin1String("file:")) ? QUrl(path).toLocalFile() : path;
    QFile f(local);
    if (!f.open(QIODevice::ReadOnly)) return QStringLiteral("Can't open that file");
    if (f.size() > kMaxFile) return QStringLiteral("That file is too big");
    const QByteArray bytes = f.read(kMaxFile + 1);
    if (bytes.size() > kMaxFile) return QStringLiteral("That file is too big");
    QSet<int> wanted;
    for (const Verse &v : m_verses) wanted.insert(v.n);
    const QMap<int, QString> map = parseImport(QString::fromUtf8(bytes), wanted);
    if (map.isEmpty()) return QStringLiteral("No verses matched. The file needs stanza numbers, like \"1.\" or \"St. 1\".");
    QJsonObject text;
    for (auto it = map.begin(); it != map.end(); ++it) text.insert(QString::number(it.key()), it.value());
    QJsonObject o;
    o.insert(QStringLiteral("text"), text);
    o.insert(QStringLiteral("of"), int(m_verses.size()));
    if (!m_vault->write(QString::fromLatin1(kFile), QJsonDocument(o).toJson(QJsonDocument::Compact)))
        return QStringLiteral("Can't save it");
    m_mine = map;
    m_mineOf = int(m_verses.size());
    Q_EMIT changed();
    return mineLine();
}

void DailyVerse::removeMine()
{
    if (m_vault) m_vault->remove(QString::fromLatin1(kFile));
    m_mine.clear();
    m_mineOf = 0;
    if (!m_tour) Settings::store().setValue(kWording, kBellows);
    Q_EMIT changed();
}

// ------------------------------------------------------------- --privacy-selftest

bool DailyVerse::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("daily verse self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    QFile f(QStringLiteral(":/huginn/havamal.json"));
    const QList<Verse> verses = f.open(QIODevice::ReadOnly) ? parse(f.readAll()) : QList<Verse>();
    check("the shared verses are built in (165, each with what it means)", verses.size() == 165);
    bool ordered = !verses.isEmpty(), noDash = true;
    for (qsizetype i = 0; i < verses.size(); ++i) {
        if (i > 0 && verses.at(i).n <= verses.at(i - 1).n) ordered = false;
        for (const QString &t : { verses.at(i).verse, verses.at(i).means })
            if (t.contains(QChar(0x2014)) || t.contains(QChar(0x2013))) noDash = false;
    }
    check("in stanza order, no dashes", ordered && noDash);

    // The verse of the day: Muninn's rule (days since 1970-01-01, cycling), the same all day, each verse in turn.
    const int n = 165;
    QSet<int> seen;
    for (qint64 d = 20400; d < 20400 + n; ++d) seen.insert(indexFor(d, n));
    check("every verse in turn", seen.size() == n && indexFor(0, n) == 0 && indexFor(-1, n) == n - 1 && indexFor(20400 + n, n) == indexFor(20400, n));
    check("Earlier verses: newest first, only since it came on",
          earlierFor(20403, 20400, n) == QList<int>({ indexFor(20402, n), indexFor(20401, n), indexFor(20400, n) })
              && earlierFor(20400, 20400, n).isEmpty() && earlierFor(20500, 20400, n).size() == kEarlier);

    // 1.43, Random: each cycle of 165 days shows every verse once, the same for the same seed, another for another
    // seed, never the same verse two days running at the seam, and days before Random began still give a verse.
    const quint64 seed = 0x1234ABCDULL;
    bool cycles = true, seams = true;
    for (qint64 c = -1; c <= 2; ++c) {
        QSet<int> once;
        for (qint64 p = c * n; p < (c + 1) * n; ++p) {
            const int i = indexAt(p, n, true, seed);
            if (i < 0 || i >= n) cycles = false;
            once.insert(i);
        }
        cycles = cycles && once.size() == n;
        seams = seams && indexAt(c * n - 1, n, true, seed) != indexAt(c * n, n, true, seed);
    }
    check("Random: every verse once a cycle, no repeat at the seam", cycles && seams);
    QList<int> a1, a2, b1, inOrder;
    for (qint64 p = 0; p < 20; ++p) {
        a1 << indexAt(p, n, true, seed);
        a2 << indexAt(p, n, true, seed);
        b1 << indexAt(p, n, true, seed + 1);
        inOrder << indexAt(p, n, false, seed);
    }
    check("Random: the same seed gives the same order, another seed another", a1 == a2 && a1 != b1 && a1 != inOrder
                                                                                 && inOrder.first() == 0 && inOrder.last() == 19);
    check("Earlier verses follow the order", earlierBy(20403, 20400, -1, [&](qint64 d) { return indexAt(d - 20400, n, true, seed); })
                                                 == QList<int>({ a1.at(2), a1.at(1), a1.at(0) }));

    // An import keeps only the numbered stanzas on the list.
    if (verses.size() >= 2) {
        QSet<int> wanted;
        for (const Verse &v : verses) wanted.insert(v.n);
        const int a = verses.at(0).n, b = verses.at(1).n;
        const QString text = QStringLiteral("Title line\n%1. One line\nand another\n\nSt. %2\nThe second one\n120. Late one\n165) The last\n999. Not on the list\n").arg(a).arg(b);
        const QMap<int, QString> got = parseImport(text, wanted);
        check("an import keeps the numbered stanzas on the list (any of the 165)", got.size() == 4 && got.value(a) == QStringLiteral("One line\nand another")
                                                                   && got.value(b) == QStringLiteral("The second one") && got.value(120) == QStringLiteral("Late one")
                                                                   && got.value(165) == QStringLiteral("The last"));
        check("your translation where it has the stanza, Bellows elsewhere",
              wordingOf(verses.at(0), kMine, got) == got.value(a) && wordingOf(verses.at(2), kMine, got) == verses.at(2).verse
                  && wordingOf(verses.at(0), kBellows, got) == verses.at(0).verse);
    }

    // Off by default; X hides it for today only; off forgets the X and the start.
    QTemporaryDir dir;
    {
        QSettings s(dir.filePath(QStringLiteral("verse.ini")), QSettings::IniFormat);
        const bool offFirst = !onIn(s) && !shownIn(s, 20400);
        setOnIn(s, true, 20400);
        const bool onNow = shownIn(s, 20400) && startIn(s, 20405) == 20400;
        hideIn(s, 20400);
        const bool hidden = !shownIn(s, 20400) && shownIn(s, 20401);
        setOnIn(s, false, 20400);
        setOnIn(s, true, 20409);
        const bool fresh = shownIn(s, 20400) && startIn(s, 20409) == 20409 && startIn(s, 20402) == 20402 && wordingIn(s) == kBellows;
        check("off until it's turned on", offFirst && onNow);
        check("X hides it until tomorrow; off forgets it", hidden && fresh);
        // In order until Random is picked; Random starts its cycle that day with one seed kept; Next verse is for
        // today only.
        const bool orderFirst = orderIn(s) == kInOrder && posIn(s, 20410) == 20410;
        setOrderIn(s, kRandom, 20410);
        const quint64 kept = seedIn(s);
        const bool randomNow = orderIn(s) == kRandom && posIn(s, 20410) == 0 && posIn(s, 20412) == 2 && seedIn(s) == kept;
        const bool noSkip = skipIn(s, 20410) == 0;
        skipNextIn(s, 20410);
        skipNextIn(s, 20410);
        const bool skipped = skipIn(s, 20410) == 2 && skipIn(s, 20411) == 0;
        skipNextIn(s, 20411);
        const bool nextDay = skipIn(s, 20411) == 1 && skipIn(s, 20410) == 0;
        setOrderIn(s, kInOrder, 20411);
        const bool back = orderIn(s) == kInOrder && skipIn(s, 20411) == 0 && posIn(s, 20411) == 20411;
        check("In order until Random is picked; one seed", orderFirst && randomNow);
        check("Next verse is for today only", noSkip && skipped && nextDay && back);
    }
    return all;
}
