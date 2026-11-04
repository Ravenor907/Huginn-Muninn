#include "dailyverse.h"
#include "settings.h"
#include "vault.h"

#include <QDate>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSettings>
#include <QTemporaryDir>
#include <QUrl>
#include <cstdio>
#include <iterator>

namespace {
const QString kOn = QStringLiteral("dailyVerse/on");
const QString kHidden = QStringLiteral("dailyVerse/hidden");
const QString kWording = QStringLiteral("dailyVerse/wording");
const QString kStart = QStringLiteral("dailyVerse/start");
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
    QList<int> out;
    if (size <= 0) return out;
    const int now = indexFor(today, size);
    for (qint64 d = today - 1; d >= start && d >= today - max && out.size() < max; --d) {
        const int i = indexFor(d, size);
        if (i != now && !out.contains(i)) out.push_back(i);
    }
    return out;
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

QVariantMap DailyVerse::today() const
{
    if (m_verses.isEmpty()) return {};
    return toMap(m_verses.at(indexFor(dayNumber(), int(m_verses.size()))));
}

QString DailyVerse::mineLine() const
{
    if (m_mine.isEmpty()) return QString();
    return QStringLiteral("Imported: %1 of %2 verses matched").arg(m_mine.size()).arg(m_mineOf > 0 ? m_mineOf : int(m_verses.size()));
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
    for (const int i : earlierFor(today, start, int(m_verses.size())))
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
    check("the shared verses are built in (60, each with what it means)", verses.size() == 60);
    bool ordered = !verses.isEmpty(), noDash = true;
    for (qsizetype i = 0; i < verses.size(); ++i) {
        if (i > 0 && verses.at(i).n <= verses.at(i - 1).n) ordered = false;
        for (const QString &t : { verses.at(i).verse, verses.at(i).means })
            if (t.contains(QChar(0x2014)) || t.contains(QChar(0x2013))) noDash = false;
    }
    check("in stanza order, no dashes", ordered && noDash);

    // The verse of the day: Muninn's rule (days since 1970-01-01, cycling), the same all day, each verse in turn.
    QSet<int> seen;
    for (qint64 d = 20400; d < 20460; ++d) seen.insert(indexFor(d, 60));
    check("every verse in turn", seen.size() == 60 && indexFor(0, 60) == 0 && indexFor(-1, 60) == 59 && indexFor(20460, 60) == indexFor(20400, 60));
    check("Earlier verses: newest first, only since it came on",
          earlierFor(20403, 20400, 60) == QList<int>({ indexFor(20402, 60), indexFor(20401, 60), indexFor(20400, 60) })
              && earlierFor(20400, 20400, 60).isEmpty() && earlierFor(20500, 20400, 60).size() == kEarlier);

    // An import keeps only the numbered stanzas on the list.
    if (verses.size() >= 2) {
        QSet<int> wanted;
        for (const Verse &v : verses) wanted.insert(v.n);
        const int a = verses.at(0).n, b = verses.at(1).n;
        const QString text = QStringLiteral("Title line\n%1. One line\nand another\n\nSt. %2\nThe second one\n999. Not on the list\n").arg(a).arg(b);
        const QMap<int, QString> got = parseImport(text, wanted);
        check("an import keeps the numbered stanzas on the list", got.size() == 2 && got.value(a) == QStringLiteral("One line\nand another")
                                                                   && got.value(b) == QStringLiteral("The second one"));
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
    }
    return all;
}
