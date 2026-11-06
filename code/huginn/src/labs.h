#pragma once

#include <QHash>
#include <QImage>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QVariantList>

class Chat;
class QQuickWindow;
class Settings;
class Vault;

// Labs mode (ten presses on the raven in Settings): voluntary bug reports and the way back to stable.
//  - A report is an ordinary private message to the Labs contact, sealed to its chat key like a first
//    message to a newly added contact and sent over the internet to its mailbox, with "lb" (app, version
//    label, system, device type, and "s", its screenshot's id) beside it. One screenshot of Huginn's own window
//    may go with it, as a photo is sent to a contact (scrubbed JPEG pieces, "lb" without "s" on each). No addresses.
//    The raven held 7 seconds in Labs mode adds the activity log (ActivityLog) after the text, behind kLogMark, as
//    Muninn does. shared/docs/protocol.md section 24.
//  - The Labs contact is never added to this computer's contacts. After a report, a message from it comes
//    in as an ordinary request.
//  - The Huginn whose chat key is the Labs key takes reports from anyone (no code needed), at most 10 a
//    day from each sender, into Labs reports (kept 30 days). Reply makes the sender a contact. Every other
//    Huginn drops envelopes with "lb" quietly.
class Labs : public QObject
{
    Q_OBJECT
    // This Huginn holds the Labs key: it gets the reports.
    Q_PROPERTY(bool inbox READ inbox NOTIFY changed)
    Q_PROPERTY(QVariantList reports READ reports NOTIFY changed)
    Q_PROPERTY(bool hasShot READ hasShot NOTIFY shotChanged)
    Q_PROPERTY(QString shotUrl READ shotUrl NOTIFY shotChanged)
    // The activity log taken with the screenshot (the raven held 7 seconds in Labs mode), and how many lines it has.
    Q_PROPERTY(bool hasActivity READ hasActivity NOTIFY shotChanged)
    Q_PROPERTY(int activityLines READ activityLines NOTIFY shotChanged)
    Q_PROPERTY(QString label READ label CONSTANT)
    Q_PROPERTY(QString os READ os CONSTANT)
    Q_PROPERTY(QString model READ model CONSTANT)

public:
    Labs(Chat *chat, Vault *vault, Settings *settings, QObject *parent = nullptr);

    static const char kName[];       // "Huginn Muninn Labs"
    static const char kKey[];        // its chat key (SPKI, base64)
    static const char kMailbox[];    // its internet mailbox
    static const int kMaxText = 2000;
    static const int kPerDay = 10;
    // Where a report's activity log starts, after what the person wrote ("\n\n[Activity log]\n"), and its most
    // characters (UTF-16 units, as Muninn counts them). The same as Muninn's LabsWire.LOG_MARK and MAX_LOG.
    static const char kLogMark[];
    static const int kMaxLog = 24000;
    // What goes: the written text, then the activity log after kLogMark when there is one.
    static QString withLog(const QString &text, const QString &log);
    // A report as it came, split into what they wrote and the activity log (empty without one). False when the written
    // part is empty or too long, or the log is too long (Muninn's LabsWire.split).
    static bool split(const QString &full, QString *text, QString *log);

    bool inbox() const;
    QVariantList reports() const;
    bool hasShot() const { return !m_shot.isNull(); }
    QString shotUrl() const { return m_shotUrl; }
    bool hasActivity() const { return !m_activity.isEmpty(); }
    int activityLines() const { return m_activityLines; }
    QString label() const;
    QString os() const;
    QString model() const;

    // The window as it is now, before the report screen opens over it. Only Huginn's own window.
    Q_INVOKABLE void capture(QQuickWindow *window);
    Q_INVOKABLE void clearShot();
    // The activity log as it is now, with the facts that explain it (version, system, network, Ultra privacy, update
    // channel), kept for the report that opens next. Call after capture(), which clears it.
    Q_INVOKABLE void takeActivity();
    // A line for the activity log from QML (screens, what the app said); nothing unless Labs mode is on.
    Q_INVOKABLE void note(const QString &tag, const QString &text) const;
    // A short, safe form of an id for the activity log.
    Q_INVOKABLE QString shortId(const QString &id) const;
    // Sends the report; "" when it went, otherwise what to tell the person. withLog: the last lines of crash.log go
    // after the text (only when the person ticked it; it holds no message text, names or addresses). perf: Labs ›
    // Performance check's short report, after the text and before the crash log, in whatever room is left.
    // withActivity: the activity log takeActivity() kept goes after it all, after kLogMark.
    Q_INVOKABLE QString send(const QString &text, bool withShot, bool withLog = false, const QString &perf = QString(), bool withActivity = false);
    // crash.log has something in it (Report a bug then offers to attach its last lines).
    Q_INVOKABLE bool hasCrashLog() const;
    Q_INVOKABLE QString shotOf(const QString &id) const;   // a report's screenshot as a data URL, or ""
    // Makes the sender a contact (an ordinary chat from then on) and answers its key, or "" when it can't.
    Q_INVOKABLE QString reply(const QString &id);
    Q_INVOKABLE void remove(const QString &id);

    // Chat::receiveEnvelope: a report for the Labs inbox. 0 when it isn't one (Chat carries on), else the answer.
    int receive(const QJsonObject &o, const QString &fromKey, const QString &name);
    // A message from the Labs key may come in as a request: this computer sent a report in the last 30 days.
    bool replyAllowed(const QString &fromKey) const;
    // Go back to stable, once the stable file checked out: the channel back to Stable and Labs off (the stable
    // release reads the vault as this one does).
    bool beforeGoingBack();

Q_SIGNALS:
    void changed();
    void shotChanged();

private:
    struct Incoming {
        QString from, name, mailbox, id;
        QJsonObject lb;
        QList<QByteArray> parts;
        int have = 0;
        qint64 started = 0;
    };
    QJsonObject lbField() const;
    static bool lbOk(const QJsonObject &lb, bool piece);
    static bool idOk(const QJsonValue &v);
    void prune() const;
    void load() const;
    void save() const;
    int sentToday(const QString &fromKey) const;
    int matchFor(const QString &fromKey, bool forText, const QString &shotId) const;
    bool seen(const QString &fromKey, const QString &id) const;
    int takePiece(const QJsonObject &o, const QString &fromKey, const QString &name);
    void store(const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &lb, const QString &sentId,
               const QString *text, const QByteArray *jpeg);

    Chat *m_chat;
    Vault *m_vault;
    Settings *m_settings;
    QImage m_shot;
    QString m_shotUrl;
    QString m_activity;
    int m_activityLines = 0;
    qint64 m_lastSent = 0;
    bool m_wasInbox = false;
    mutable bool m_loaded = false;
    // Oldest first: {id, from, name, mailbox, app, label, os, model, text, hasText, shot (vault file), shotId, wants (an
    // awaited screenshot's id), time, ids (the envelope ids taken)}.
    mutable QList<QJsonObject> m_reports;
    QHash<QString, Incoming> m_incoming;     // screenshot pieces on their way: from|id
};
