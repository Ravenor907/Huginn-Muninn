#pragma once

#include <QList>
#include <QMap>
#include <QObject>
#include <QSet>
#include <QString>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

class QSettings;
class Vault;

// Daily Havamal (plan 1.42, section 1), the same as Muninn's standard app: one verse a day by this computer's own date,
// the same verse all day, cycling through the verses in code/shared/havamal.json (Bellows, 1923, with the owner-approved
// "What it means" lines; built in as :/huginn/havamal.json). Settings > Look switches it on (off until then); a card at
// the top of the chat list opens the verse, what it means, Read aloud and Earlier verses; X hides it for the rest of
// today. The wording is Bellows or "Your translation": a .txt imported here, kept in this computer's vault only, never
// sent, and left out of backups and moves.
class DailyVerse : public QObject
{
    Q_OBJECT
    // Settings > Look > Daily Havamal.
    Q_PROPERTY(bool verseOn READ verseOn WRITE setVerseOn NOTIFY changed)
    // "b" (Bellows) or "y" (Your translation; Bellows for a stanza it doesn't have).
    Q_PROPERTY(QString verseWording READ verseWording WRITE setVerseWording NOTIFY changed)
    // On and not hidden today (or the screenshot tour).
    Q_PROPERTY(bool cardShown READ cardShown NOTIFY changed)
    // Today's verse: { n, verse (in the wording), means, first (its first line) }; empty when the verses can't be read.
    Q_PROPERTY(QVariantMap today READ today NOTIFY changed)
    Q_PROPERTY(bool hasMine READ hasMine NOTIFY changed)
    // "Imported: 42 of 60 verses matched", or empty.
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
    bool cardShown() const;
    QVariantMap today() const;
    bool hasMine() const { return !m_mine.isEmpty(); }
    QString mineLine() const;
    bool tour() const { return m_tour; }
    void setTour(bool v);

    // X on the card: hidden until tomorrow.
    Q_INVOKABLE void hideToday();
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

    // --privacy-selftest: the shared verses, the verse of the day, Earlier verses, an import, off by default, X for today.
    static bool selfTest();

    static constexpr const char *kFile = "havamal-own.json";

Q_SIGNALS:
    void changed();

private:
    void loadMine();
    QVariantMap toMap(const Verse &v) const;
    Vault *m_vault;
    QList<Verse> m_verses;
    QMap<int, QString> m_mine;
    int m_mineOf = 0;
    bool m_tour = false;
    qint64 m_day = 0;
    QTimer m_clock;
};
