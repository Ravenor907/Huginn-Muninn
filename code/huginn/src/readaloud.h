#pragma once

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <QVariantList>
#include <atomic>
#include <functional>
#include <memory>

class QAudioOutput;
class QBuffer;
class QMediaPlayer;
class QNetworkAccessManager;
class QNetworkReply;
class QThread;
class QFile;
class QCryptographicHash;
class QTextToSpeech;
class VoiceEngine;

// Read aloud (code/shared/docs/voices.md), the QML context property "ReadAloud": speak(text), stop() and available.
// Every message's menu (right click on a computer, the message menu on a handheld) and Daily Hávamál read through it.
//
// It speaks with the natural voice picked in Settings › Look › Read aloud (Female, Alba, the default; or Male,
// northern English), synthesized here by the bundled speech engine (sherpa-onnx, loaded with QLibrary the first time it
// is needed). Only the chosen voice's data is downloaded, once, from the voices-1 release, checked against
// code/shared/voices.sha256 (built in as :/voices.sha256), and kept in the data folder across updates. It downloads in
// the background the first time something is read, on an unmetered network unless "Allow on mobile data" is on. Until
// it's ready the computer's own voice reads (Qt TextToSpeech, when this build has it; never a voice that sends the
// text away). It stops when the lock goes up and when a call starts.
class ReadAloud : public QObject
{
    Q_OBJECT
    // Something can read: the natural voice (in, or on its way: its line is in voices.sha256), or the computer's own
    // voice where one can speak (in the Flatpak only through the host's speech-dispatcher).
    Q_PROPERTY(bool available READ available NOTIFY changed)
    // The natural voice is offered in this build: the engine is here and the chosen voice has its line in
    // voices.sha256. False until the voices are published: Settings then hides the voice rows and says
    // "Natural voices aren't ready yet".
    Q_PROPERTY(bool natural READ natural NOTIFY changed)
    Q_PROPERTY(bool speaking READ speaking NOTIFY changed)
    // The Read aloud page's pick: "f" (Female, Alba), "m" (Male, northern English) or "d" (Device: the computer's own
    // voice; the natural voice never reads or downloads).
    Q_PROPERTY(QString voice READ voice WRITE setVoice NOTIFY changed)
    // The Read aloud switch (on until turned off). Off: available is false, so no menu offers Read aloud.
    Q_PROPERTY(bool on READ isOn WRITE setOn NOTIFY changed)
    // The speed (how fast words are spoken, the engine's speed): a percent of the usual pace from 70 to 130 (100 until
    // moved; 1.43 moved a saved 80, the old default, to 100). Writing it saves it (when the drag ends); previewSpeed()
    // applies a value while the slider moves.
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY speedChanged)
    // The cadence (1.43): how long the pauses between phrases and sentences are, 50 to 200 percent of the usual ones
    // (100 until moved). It scales only the pauses Read aloud adds, never the words. previewCadence() while it moves.
    Q_PROPERTY(double cadence READ cadence WRITE setCadence NOTIFY speedChanged)
    // Labs › Voice tuning (Pre-release only): the preset in use for the chosen natural voice ("A" to "E").
    Q_PROPERTY(QString tuning READ tuning NOTIFY tuningChanged)
    // Download the voice on a metered connection (a phone's hotspot) too.
    Q_PROPERTY(bool mobileData READ mobileData WRITE setMobileData NOTIFY changed)
    // The chosen natural voice is downloaded and checked.
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    // Any voice files are kept (Delete voices shows).
    Q_PROPERTY(bool hasFiles READ hasFiles NOTIFY changed)
    // One line for Settings: "Ready", "Downloading, 40%", "Waits for Wi-Fi"... Its own signal, so a download's
    // percentage doesn't rebuild every Settings row.
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
public:
    explicit ReadAloud(QObject *parent = nullptr);
    ~ReadAloud() override;

    bool available() const;
    bool natural() const;
    bool speaking() const { return m_speaking; }
    QString voice() const;
    void setVoice(const QString &v);
    bool isOn() const;
    void setOn(bool on);
    double speed() const;
    void setSpeed(double pct);
    double cadence() const;
    void setCadence(double pct);
    QString tuning() const { return tuneOf(naturalVoice()); }
    bool mobileData() const;
    void setMobileData(bool on);
    bool ready() const;
    bool hasFiles() const;
    QString status() const { return m_status; }

    // Reads the plain text of [text] (links say "link", no emoji or marks), phrase by phrase with Read aloud's own
    // pauses (phrases()). Stops whatever was reading first.
    Q_INVOKABLE void speak(const QString &text);
    // Daily Hávamál: the verse, a 900 ms gap, "What it means", then the meaning (each of its lines a pause).
    Q_INVOKABLE void speakVerse(const QString &verse, const QString &means);
    // Several messages in a row, with a 700 ms gap between them.
    Q_INVOKABLE void speakAll(const QStringList &texts);
    Q_INVOKABLE void stop();
    // Starts the chosen voice's download now (Settings), on the same network rules.
    Q_INVOKABLE void prepare();
    // Deletes every downloaded voice (the computer's own voice reads until it's downloaded again).
    Q_INVOKABLE void deleteVoices();
    // The speed slider moves: [pct] is used at once (the next piece read, and Hear a sample), nothing is saved.
    Q_INVOKABLE void previewSpeed(double pct);
    // The cadence slider moves: used for the next pause, nothing is saved.
    Q_INVOKABLE void previewCadence(double pct);
    // "Hear a sample": a short line with the voice and speed picked now.
    Q_INVOKABLE void sample();
    // The Read aloud row's line (Look › Accessibility): "Off", "Female voice, ready", "Device voice, 100%".
    Q_INVOKABLE QString summary() const;
    // Labs › Voice tuning: the five presets, [{ id, noise, width }] (A is the default).
    Q_INVOKABLE QVariantList tuningPresets() const;
    // Reads Hávamál stanza 3 and what it means with preset [id] on the chosen natural voice, without keeping it.
    // Answers "" when it reads, else the line to show (the natural voice isn't downloaded, Device is picked...).
    Q_INVOKABLE QString tryTuning(const QString &id);
    // "Use this": keeps preset [id] for the chosen natural voice on this computer.
    Q_INVOKABLE void useTuning(const QString &id);
    // "A: noise 0.667, width 0.8", for the owner to report.
    Q_INVOKABLE QString tuningText(const QString &id) const;
    // The natural voice's name for the tuning page: "Female" or "Male".
    Q_INVOKABLE QString naturalName() const { return naturalVoice() == QLatin1String("m") ? QStringLiteral("Male") : QStringLiteral("Female"); }
    // main.cpp: true while nothing may be read (the lock is up, the screen lock shows, a call is on). speak() checks it.
    void setBlocker(std::function<bool()> blocked) { m_blocked = std::move(blocked); }

    // One phrase to speak and the pause after it (the usual length in ms, before the cadence scales it).
    struct Phrase {
        QString text;
        int pauseMs = 0;
        bool operator==(const Phrase &o) const { return text == o.text && pauseMs == o.pauseMs; }
    };

    // ---- the parts the self-test checks (the same rules as Muninn's ReadAloudText)
    // What is read for a message: links as "link", no emoji, marks or @, no control characters or markup, words of at
    // most kMaxWord letters, spaces tidied, at most kMaxText long.
    static QString plain(const QString &text);
    // What the natural voice's engine (and its eSpeak NG) is given for one piece: Latin letters (with at most two
    // accents each), digits 0 to 9, spaces and . , ! ? ; : ' " ( ) - / % + only; anything else becomes a space. No
    // brackets, angle brackets or backslashes, so eSpeak's phoneme input ([[ ]]) and SSML can never be reached.
    static QString forEngine(const QString &piece);
    // [text] in pieces to speak one at a time: sentences and lines, none longer than kMaxPiece.
    static QStringList pieces(const QString &text);
    // 1.43, the text before the voice. Curly quotes made plain, ".." one dot, "..." and the ellipsis one pause mark
    // (U+2026).
    static QString tidy(const QString &text);
    // Numbers, times ("3:30" is "three thirty"), common abbreviations (Dr., Mr., Mrs., St., etc.) and texting
    // shorthand (lol, brb, idk, omw, tbh, imo, ty, np) in words. One line of plain text.
    static QString normalize(const QString &line);
    // A message that is one emoji: its name ("smiling face"); anything else (or an emoji not on the list): "".
    static QString emojiName(const QString &text);
    // The natural voices only: names said right (Muninn "MOO-nin", Hávamál "HAH-vah-mawl"...), as respellings.
    static QString respell(const QString &text);
    // A number in words ("forty-two"), 0 to 999,999,999.
    static QString numberWords(qint64 n);
    // What is spoken for a message: the phrases and their pauses (comma 250 ms, full stop, ? ! and ... 500 ms, line
    // break 400 ms, none after the last). Sentences over kLongSentence words are also split at ", and", ", but",
    // " because" and ";". [natural]: respelled for the natural voices. None longer than kMaxPiece.
    static QList<Phrase> phrases(const QString &text, bool natural);
    // Daily Hávamál read aloud: the verse (one full stop at its end, never two), a kPauseMeans gap, "What it means.",
    // then the meaning with a pause at each line.
    static QList<Phrase> versePhrases(const QString &verse, const QString &means, bool natural);
    // A pause of [baseMs] at cadence [pct].
    static int pauseFor(int baseMs, double pct);
    // code/shared/voices.sha256: file name to SHA-256 (lowercase hex). Comments and odd lines are skipped.
    static QHash<QString, QString> hashes(const QByteArray &text);
    // The release file for voice "f" or "m".
    static QString fileFor(const QString &voice);
    // The speed rules (the same as Muninn's ReadAloudText): [pct] held to 70 to 130 (NaN: 100); "83%"; the speech
    // engine's speed (1.0 the usual pace); QTextToSpeech's rate (-1 to 1): 100% is 0, 50 points either way is 1, so
    // 70% is -0.6 and 130% is 0.6.
    static double speedOf(double pct);
    // A speed saved before 1.43 (50 to 150, 80 the default): 80 becomes 100, anything else is kept, held to the range.
    static double speedFrom142(double pct);
    // The cadence held to 50 to 200 (NaN: 100).
    static double cadenceOf(double pct);
    static QString speedText(double pct);
    static float engineSpeed(double pct);
    static double qtRate(double pct);
    // Unpacks a voice's tar (ustar) into [dir]: regular files and folders only, no "..", no absolute paths,
    // at most kMaxFile a file. False (and [why]) for anything else.
    static bool unpack(const QString &tarPath, const QString &dir, QString *why);
    // Whether a name in the tar is one this unpacks (relative, plain, under the allowed names).
    static bool safeName(const QString &name);
    // Command line --readaloud-selftest (CI): the rules above, and that the bundled engine loads.
    static bool selfTest();

    static constexpr int kMaxPiece = 400;
    static constexpr int kMaxText = 4000;
    static constexpr int kMaxWord = 40;
    static constexpr qint64 kMaxDownload = 160 * 1024 * 1024;
    static constexpr qint64 kMaxFile = 120 * 1024 * 1024;
    static constexpr double kSpeedMin = 70.0;
    static constexpr double kSpeedMax = 130.0;
    static constexpr double kSpeedDefault = 100.0;
    static constexpr double kCadenceMin = 50.0;
    static constexpr double kCadenceMax = 200.0;
    static constexpr double kCadenceDefault = 100.0;
    // Read aloud's own pauses (ms at cadence 100%).
    static constexpr int kPauseComma = 250;
    static constexpr int kPauseStop = 500;
    static constexpr int kPauseLine = 400;
    static constexpr int kPauseMeans = 900;
    static constexpr int kPauseMessage = 700;
    static constexpr int kLongSentence = 18;
    // Labs › Voice tuning's presets: noise scale and noise width ("A" is the default).
    struct Tune { const char *id; float noise; float width; };
    static constexpr Tune kTunes[5] = {{"A", 0.667f, 0.8f}, {"B", 0.5f, 0.6f}, {"C", 0.75f, 0.9f}, {"D", 0.6f, 1.0f}, {"E", 0.8f, 0.7f}};
    static Tune tuneFor(const QString &id);

Q_SIGNALS:
    void changed();
    void statusChanged();
    void speedChanged();
    void tuningChanged();

private:
    friend class VoiceEngine;
    // The natural voice to use or download: "f" or "m" (Female when Device is picked).
    QString naturalVoice() const;
    // Device was picked: the computer's own voice reads, nothing downloads.
    bool useDevice() const;
    void applyDeviceRate();
    QString voicesDir() const;
    QString voiceDir(const QString &voice) const;
    void setStatus(const QString &s);
    void speakDevice(const QList<Phrase> &phrases);
    // Reads [nat] with the natural voice when it can ([dev], the same without respellings, with the computer's own).
    void speakPhrases(const QList<Phrase> &nat, const QList<Phrase> &dev, const QString &tune = QString());
    // The computer's own voice: the next phrase, after the pause before it.
    void sayNextDevice();
    // The preset kept for natural voice "f" or "m" ("A" until one is used).
    QString tuneOf(const QString &voice) const;
    void playNext();
    void onSpoken(quint64 token, const QByteArray &wav);
    void onEngineFailed(quint64 token, const QString &why);
    void startDownload();
    void downloadDone();
    // Says [why], and tries again in [retryMs] (0: not on its own).
    void downloadFailed(const QString &why, int retryMs);
    void cleanOther();
    // The engine lets go of the voice's files on its thread, then [then] runs here (never blocking the window).
    void releaseEngine(std::function<void()> then);
    bool meteredNow() const;
    void loadDeviceVoice();
    bool deviceCan() const;
    void networkMoved();
    void recoverUnpacked();

    QThread *m_thread = nullptr;
    VoiceEngine *m_engine = nullptr;
    std::shared_ptr<std::atomic<quint64>> m_token;
    // The speed in use (percent), read by the engine's thread for each piece.
    std::atomic<double> m_speedNow{kSpeedDefault};
    // The cadence in use (percent), read by the engine's thread for each pause.
    std::atomic<double> m_cadenceNow{kCadenceDefault};
    // The computer's own voice: the phrases still to say, the pause after the one being said, and the gap timer.
    QList<Phrase> m_devQueue;
    int m_devPause = 0;
    bool m_devSaying = false;
    bool m_devStarted = false;
    QTimer m_devGap;
    bool m_speaking = false;
    QString m_status;
    QList<QByteArray> m_queue;
    int m_pending = 0;
    QMediaPlayer *m_player = nullptr;
    QAudioOutput *m_output = nullptr;
    QBuffer *m_buffer = nullptr;
    QByteArray m_playing;
    // the download
    QNetworkAccessManager *m_net = nullptr;
    QPointer<QNetworkReply> m_reply;
    QFile *m_part = nullptr;
    QCryptographicHash *m_sha = nullptr;
    QString m_downloading;
    // Download tries in a row that failed (the next try comes sooner for the first few).
    int m_failures = 0;
    // The network changed (or the computer woke) while downloading: the cut-off reply resumes at once.
    bool m_resumeNow = false;
    qint64 m_got = 0;
    qint64 m_base = 0;
    qint64 m_total = 0;
    bool m_checked = false;
    QTimer m_retry;
    QHash<QString, QString> m_hashes;
#ifdef HUGINN_HAVE_QTSPEECH
    QTextToSpeech *m_device = nullptr;
#endif
    bool m_deviceOk = false;
    // The computer's own voice failed to start (no speech service): not offered again this run.
    bool m_deviceFailed = false;
    const bool m_engineThere;
    // The engine wouldn't load or start, or stopped Huginn twice mid-sentence: the computer's own voice reads.
    bool m_engineBroken = false;
    // Voice files are being deleted (the engine is letting go of them first).
    bool m_deleting = false;
    QList<Phrase> m_lastParts;
    std::function<bool()> m_blocked;
};
