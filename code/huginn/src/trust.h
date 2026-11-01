#pragma once

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QSet>
#include <QString>
#include <QVariantList>

class Chat;

// Cap i1 (shared/docs/protocol.md section 13), the same as Muninn's Trust.kt: becoming contacts through
// someone you already trust.
//  - Room "intro" t "intro" {id, k, n, p}: a contact introduces me to k. Nothing happens until I say yes.
//  - t "yes" {id}: an introduced person says yes, straight to the other one. Both said yes: contacts.
//  - t "add" {g}: someone in a group with me asks to be contacts (offered for Family groups).
//  - The 4 emoji for a pair (13.5) and one-time invite links (13.6) are local only.
// Chat hands it room "intro" (Chat::receiveExtra in this file); shared/vectors/i1f1-vectors.json checks it.
class Trust : public QObject
{
    Q_OBJECT
    // What waits for an answer here: introductions, group members asking, and Family offers:
    // [{id, kind ("intro", "group" or "offer"), key, name, detail}].
    Q_PROPERTY(QVariantList pending READ pending NOTIFY changed)

public:
    explicit Trust(Chat *chat, QObject *parent = nullptr);

    static const char kCap[];   // "i1"

    Q_INVOKABLE bool hasI1(const QString &key) const;
    // Introduces two of my contacts to each other. "" when sent, else what to tell the person.
    Q_INVOKABLE QString introduce(const QString &a, const QString &b);
    // Yes or no to an introduction, a group member asking, or a Family offer.
    Q_INVOKABLE void answer(const QString &id, bool yes);
    // Asks someone in group g to be contacts (they say yes on their side too).
    Q_INVOKABLE QString askToAdd(const QString &g, const QString &key);
    // A link that works once, good for hours: whoever adds it first is a contact at once, no asking.
    Q_INVOKABLE QString inviteLink(int hours);
    // The code in this text is a one-time invite (the app that adds it says hi straight away).
    Q_INVOKABLE bool isOnceCode(const QString &text) const;
    // The 4 emoji both apps show for me and key: the same on each side means nobody is in between.
    Q_INVOKABLE QString emojiWith(const QString &key) const;
    QVariantList pending() const;

    // My one-time invite, still good: used up now (Chat calls this for a stranger's first message). The token stays a
    // valid invite until it runs out: someone else with the same code gets an ordinary request, never dropped.
    bool useOnce(const QString &token);
    // A token of a code shown here (Chat::newLink) counts as one-time too (13.6), so whoever adds it is a contact at once.
    void markOnce(const QString &token);
    // Room "intro", opened: 200, 400 (malformed), 403 (not from someone allowed to send it) or 429.
    int receive(const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &p);

    static bool ok(const QJsonObject &p);
    static QString groupId(const QString &g, const QString &a, const QString &b);
    static QString emojiCode(const QByteArray &sharedKey);
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();

private:
    struct Intro {
        QString id, by, k, n, p;       // by: the introducer's key, or "g:" + the group
        bool iSaid = false, theySaid = false, declined = false;
        qint64 at = 0;
    };
    void ensureLoaded() const;
    void save();
    void put(const Intro &i);
    void finish(const Intro &i);
    void sendTo(const QString &key, const QString &mailbox, const QJsonObject &msg);
    const Intro *find(const QString &id) const;
    QList<Intro> familyOffers() const;

    Chat *m_chat;
    mutable bool m_loaded = false;
    mutable QList<Intro> m_intros;
    mutable QSet<QString> m_once;
    QHash<QString, QList<qint64>> m_recent;
};
