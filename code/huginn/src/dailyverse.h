#pragma once

#include <QList>
#include <QMap>
#include <QObject>
#include <QSet>
#include <QString>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <functional>

class QSettings;
class Vault;

// Daily Havamal (plan 1.42, section 1), the same as Muninn's standard app: one verse a day by this computer's own date,
// the same verse all day, going through all 165 verses in code/shared/havamal.json (Bellows, 1923, with the
// owner-approved "What it means" lines, one sentence a line; built in as :/huginn/havamal.json). Settings > Look switches
// it on (off until then) and picks In order or Random (1.43: a shuffle seeded on this computer, no verse again until
// all have shown). A card at the top of the chat list opens the verse, what it means, Read aloud and Earlier verses;
// Next verse skips to the next one in the order for today only (tomorrow has its own verse); X hides it for the rest of
// today. The wording is Bellows or "Your translation": a .txt imported here, kept in this computer's vault only, never
// sent, and left out of backups and moves.
class DailyVerse : public QObject
{
    Q_OBJECT
    // Settings > Look > Daily Havamal.
    Q_PROPERTY(bool verseOn READ verseOn WRITE setVerseOn NOTIFY changed)
    // "b" (Bellows) or "y" (Your translation; Bellows for a stanza it doesn't have).
    Q_PROPERTY(QString verseWording READ verseWording WRITE setVerseWording NOTIFY changed)
    // 1.43: "o" (In order, the default) or "r" (Random).
    Q_PROPERTY(QString verseOrder READ verseOrder WRITE setVerseOrder NOTIFY changed)
    // On and not hidden today (or the screenshot tour).
    Q_PROPERTY(bool cardShown READ cardShown NOTIFY changed)
    // Today's verse: { n, verse (in the wording), means, first (its first line) }; empty when the verses can't be read.
    Q_PROPERTY(QVariantMap today READ today NOTIFY changed)
    Q_PROPERTY(bool hasMine READ hasMine NOTIFY changed)
    // "Imported: 42 of 165 verses matched", or empty.
    Q_PROPERTY(QString mineLine READ mineLine NOTIFY changed)
    // The screenshot tour: shown and on, never saved.
    Q_PROPERTY(bool tour READ tour WRITE setTour NOTIFY changed)

public:
    struct Verse {
        int n = 0;
        QString verse;
        QString means;
    };
    static constexpr int kEarlier = 30;
    static constexpr int kMaxStanzas = 200;
    static constexpr int kMaxStanza = 1500;
    static constexpr qint64 kMaxFile = 2LL * 1024 * 1024;
    static constexpr bool kDefaultOn = false;

    explicit DailyVerse(Vault *vault, QObject *parent = nullptr);

    bool verseOn() const;
    void setVerseOn(bool v);
    QString verseWording() const;
    void setVerseWording(const QString &v);
    QString verseOrder() const;
    void setVerseOrder(const QString &v);
    bool cardShown() const;
    QVariantMap today() const;
    bool hasMine() const { return !m_mine.isEmpty(); }
    QString mineLine() const;
    bool tour() const { return m_tour; }
    void setTour(bool v);

    // X on the card: hidden until tomorrow.
    Q_INVOKABLE void hideToday();
    // Next verse (the card, the verse's view): the next one in the order, for today only.
    Q_INVOKABLE void nextVerse();
    // The verses shown before today since it came on, newest first, at most kEarlier: [{ n, verse, means, first }].
    Q_INVOKABLE QVariantList earlier();
    // Reads a picked .txt: only numbered stanzas on the list are kept. Answers the line to show (starts "Imported" when kept).
    Q_INVOKABLE QString importFile(const QString &path);
    // Erased here; the wording goes back to Bellows.
    Q_INVOKABLE void removeMine();

    // The rules, separate so the self-test (and Muninn's tests) agree on them.
    static QList<Verse> parse(const QByteArray &json);
    static int indexFor(qint64 day, int size);
    static QList<int> earlierFor(qint64 today, qint64 start, int size, int max = kEarlier);
    // Earlier verses by any order: [at] gives a day's verse, [now] (today's, left out) and repeats are skipped.
    static QList<int> earlierBy(qint64 today, qint64 start, int now, const std::function<int(qint64)> &at, int max = kEarlier);
    // Random: [size] verses shuffled for [cycle] (each [size] days in a row) from [seed]; a cycle never starts with the
    // verse the one before it ended on.
    static QList<int> shuffled(quint64 seed, qint64 cycle, int size);
    // The verse at place [pos] in the order: [pos] itself cycling (In order), or the shuffle's (Random).
    static int indexAt(qint64 pos, int size, bool random, quint64 seed);
    static QMap<int, QString> parseImport(const QString &text, const QSet<int> &wanted);
    static QString wordingOf(const Verse &v, const QString &wording, const QMap<int, QString> &mine);
    static qint64 dayNumber();   // today, days since 1970-01-01 by this computer's date
    // The saved values, in s (the self-test passes a throwaway file).
    static bool onIn(const QSettings &s);
    static void setOnIn(QSettings &s, bool v, qint64 today);
    static void hideIn(QSettings &s, qint64 today);
    static bool shownIn(const QSettings &s, qint64 today);
    static qint64 startIn(QSettings &s, qint64 today);
    static QString wordingIn(const QSettings &s);
    // 1.43: the order ("o" or "r"), where Random began (its first cycle starts that day) and this computer's seed (made
    // once, when Random is first picked).
    static QString orderIn(const QSettings &s);
    static void setOrderIn(QSettings &s, const QString &v, qint64 today);
    static quint64 seedIn(QSettings &s);
    // The place in the order for [day]: the day itself, or days since Random began.
    static qint64 posIn(QSettings &s, qint64 day);
    // How many times Next verse was pressed [today] (0 on any other day).
    static int skipIn(const QSettings &s, qint64 today);
    static void skipNextIn(QSettings &s, qint64 today);

    // --privacy-selftest: the shared verses, the verse of the day, Earlier verses, an import, off by default, X for today.
    static bool selfTest();

    static constexpr const char *kFile = "havamal-own.json";

Q_SIGNALS:
    void changed();

private:
    void loadMine();
    QVariantMap toMap(const Verse &v) const;
    // The verse shown on [day] (with today's skips when it's today).
    int indexOfDay(qint64 day) const;
    int m_tourSkip = 0;
    Vault *m_vault;
    QList<Verse> m_verses;
    QMap<int, QString> m_mine;
    int m_mineOf = 0;
    bool m_mineDropped = false;   // cleared while locked: read again at the next unlock
    bool m_tour = false;
    qint64 m_day = 0;
    QTimer m_clock;
};
