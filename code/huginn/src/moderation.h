#pragma once

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QVariantList>

class Chat;

// Muting and volume per person, and group timeouts and bans (cap g2, shared/docs/protocol.md section 26), for QML
// (the "Mod" singleton). Muninn's twin is net/Mod.kt.
//  - Mute for me: their voice is silent here in calls and rooms, and with "Hide messages" their group messages
//    show as "Muted, tap to show". Only on this computer, saved per person (in the vault, mod.json).
//  - Volume: 0 to 200 % per person, applied to their decoded sound before it's mixed (CallMedia), saved per person.
//  - Timeout (10 minutes to a week) and Ban: an admin signs a record (ModWire) and sends it in room "group"
//    (t "mod") to every member whose app says g2. Every app with g2 drops posts, files, reactions and votes from
//    someone timed out or banned, and doesn't let them into the group's voice rooms. A ban also removes them (a
//    signed state, 3.4), and admins' apps refuse them by a code, a link or an invite until they're unbanned.
// Chat (a friend) hands it room "group" t "mod" and asks barred() before keeping a post.
class Moderation : public QObject
{
    Q_OBJECT
    // Bumps on every change, for bindings in QML.
    Q_PROPERTY(int serial READ serial NOTIFY changed)

public:
    explicit Moderation(Chat *chat, QObject *parent = nullptr);

    int serial() const { return m_serial; }

    // ---- per person, only here
    Q_INVOKABLE bool mutedForMe(const QString &key) const;
    Q_INVOKABLE void setMutedForMe(const QString &key, bool on);
    // With Mute for me on: their group messages are folded away ("Muted, tap to show"). On by default.
    Q_INVOKABLE bool hidesMessages(const QString &key) const;
    Q_INVOKABLE void setHidesMessages(const QString &key, bool on);
    Q_INVOKABLE int volume(const QString &key) const;              // 0 to 200, 100 when never set
    Q_INVOKABLE void setVolume(const QString &key, int pct);
    // What their sound is played at: their volume, or 0 while muted for me in a voice room (room). A 1:1 call never
    // uses Mute for me, only the volume, as in Muninn.
    int gainFor(const QString &key, bool room) const;
    // A group message of theirs to fold away.
    bool foldMessages(const QString &key) const { return mutedForMe(key) && hidesMessages(key); }

    // ---- group timeouts and bans (admins). "" when done, else what to tell the person.
    Q_INVOKABLE bool canModerate(const QString &g, const QString &key) const;
    Q_INVOKABLE QString timeout(const QString &g, const QString &key, qint64 ms);
    Q_INVOKABLE QString cancelTimeout(const QString &g, const QString &key);
    Q_INVOKABLE QString ban(const QString &g, const QString &key);
    Q_INVOKABLE QString unban(const QString &g, const QString &key);
    // Delete for everyone (26.2 "remove"): an admin blanks someone's message on every member's app with g2.
    Q_INVOKABLE QString removeForEveryone(const QString &g, const QString &msgId, const QString &author);
    // Members (not me) whose app has no g2, so a removed message stays on their screen until they update.
    Q_INVOKABLE int withoutRemove(const QString &g) const;
    // Ban's "Also remove their messages from the last day": a remove record for each of their messages in the last
    // ms (at most 50, newest first), sent in one "mod" list. How many were removed.
    Q_INVOKABLE int removeRecent(const QString &g, const QString &author, qint64 ms);
    // For the group's settings: [{key, name}] and [{key, name, until, left}].
    Q_INVOKABLE QVariantList bannedList(const QString &g) const;
    Q_INVOKABLE QVariantList timeoutList(const QString &g) const;
    Q_INVOKABLE bool isBanned(const QString &g, const QString &key) const;
    Q_INVOKABLE qint64 timedOutUntil(const QString &g, const QString &key) const;   // 0 when not timed out
    // "You're timed out until 3:40 PM", "You were banned from Family", or "".
    Q_INVOKABLE QString myStatus(const QString &g) const;
    // The menu's timeouts: [{label, ms}].
    Q_INVOKABLE QVariantList timeoutChoices() const;

    // ---- for Chat and the call code
    bool barred(const QString &g, const QString &key) const { return isBanned(g, key) || timedOutUntil(g, key) > 0; }
    QString postBlock(const QString &g) const { return myStatus(g); }
    // Room "group" t "mod", opened, from a member: false when malformed (400).
    bool receive(const QString &g, const QString &from, const QJsonObject &p);
    // Every record still in force for g, to one member (after a sync or when they were just added).
    void sendRecords(const QString &g, const QString &to);
    void forgetGroup(const QString &g);

    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();

private:
    struct Person {
        bool muted = false, show = false;   // show: their messages aren't folded while muted
        int volume = 100;
    };
    void ensureLoaded() const;
    void save();
    void bump();
    // Signs a record, keeps it and sends it to everyone with g2 (and to who, when they aren't in the state anymore).
    QString act(const QString &g, const QString &key, const QString &action, qint64 until);
    bool take(const QString &g, const QJsonObject &rec, bool announce);
    // A "remove" taken: the message blanked here ("Removed by an admin"), or never shown when it comes later.
    void blank(const QString &g, const QJsonObject &rec);
    QJsonObject record(const QString &g, const QString &key, const QString &slot) const;
    QString whenText(qint64 ms) const;
    QString leftText(qint64 ms) const;
    bool hasG2(const QString &key) const;

    Chat *m_chat;
    mutable bool m_loaded = false;
    mutable QHash<QString, Person> m_people;
    // group -> person -> slot ("ban" or "timeout") -> the newest record taken
    mutable QHash<QString, QHash<QString, QHash<QString, QJsonObject>>> m_records;
    int m_serial = 0;
};
