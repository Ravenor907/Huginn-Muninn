#pragma once

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <atomic>
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
    // Something can read right now, or will once the natural voice is in (the engine is bundled).
    Q_PROPERTY(bool available READ available NOTIFY changed)
    Q_PROPERTY(bool speaking READ speaking NOTIFY changed)
    // "f" (Female, Alba) or "m" (Male, northern English).
    Q_PROPERTY(QString voice READ voice WRITE setVoice NOTIFY changed)
    // Download the voice on a metered connection (a phone's hotspot) too.
    Q_PROPERTY(bool mobileData READ mobileData WRITE setMobileData NOTIFY changed)
    // The chosen natural voice is downloaded and checked.
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    // Any voice files are kept (Delete voices shows).
    Q_PROPERTY(bool hasFiles READ hasFiles NOTIFY changed)
    // One line for Settings: "Ready", "Downloading, 40%", "Waits for Wi-Fi"...
    Q_PROPERTY(QString status READ status NOTIFY changed)
public:
    explicit ReadAloud(QObject *parent = nullptr);
    ~ReadAloud() override;

    bool available() const;
    bool speaking() const { return m_speaking; }
    QString voice() const;
    void setVoice(const QString &v);
    bool mobileData() const;
    void setMobileData(bool on);
    bool ready() const;
    bool hasFiles() const;
    QString status() const { return m_status; }

    // Reads the plain text of [text] (links say "link", no emoji or marks). Stops whatever was reading first.
    Q_INVOKABLE void speak(const QString &text);
    Q_INVOKABLE void stop();
    // Starts the chosen voice's download now (Settings), on the same network rules.
    Q_INVOKABLE void prepare();
    // Deletes every downloaded voice (the computer's own voice reads until it's downloaded again).
    Q_INVOKABLE void deleteVoices();

    // ---- the parts the self-test checks (the same rules as Muninn's ReadAloudText)
    // What is read for a message: links as "link", no emoji, marks or @, spaces tidied.
    static QString plain(const QString &text);
    // [text] in pieces to speak one at a time: sentences and lines, none longer than kMaxPiece.
    static QStringList pieces(const QString &text);
    // code/shared/voices.sha256: file name to SHA-256 (lowercase hex). Comments and odd lines are skipped.
    static QHash<QString, QString> hashes(const QByteArray &text);
    // The release file for voice "f" or "m".
    static QString fileFor(const QString &voice);
    // Unpacks a voice's tar (ustar) into [dir]: regular files and folders only, no "..", no absolute paths,
    // at most kMaxFile a file. False (and [why]) for anything else.
    static bool unpack(const QString &tarPath, const QString &dir, QString *why);
    // Whether a name in the tar is one this unpacks (relative, plain, under the allowed names).
    static bool safeName(const QString &name);
    // Command line --readaloud-selftest (CI): the rules above, and that the bundled engine loads.
    static bool selfTest();

    static constexpr int kMaxPiece = 400;
    static constexpr qint64 kMaxDownload = 160 * 1024 * 1024;
    static constexpr qint64 kMaxFile = 120 * 1024 * 1024;

Q_SIGNALS:
    void changed();

private:
    friend class VoiceEngine;
    QString voicesDir() const;
    QString voiceDir(const QString &voice) const;
    void setStatus(const QString &s);
    void speakDevice(const QStringList &pieces);
    void playNext();
    void onSpoken(quint64 token, const QByteArray &wav);
    void onEngineFailed(quint64 token, const QString &why);
    void startDownload();
    void downloadDone();
    void downloadFailed(const QString &why, bool later);
    void cleanOther();
    void releaseEngine();
    bool meteredNow() const;
    void loadDeviceVoice();

    QThread *m_thread = nullptr;
    VoiceEngine *m_engine = nullptr;
    std::shared_ptr<std::atomic<quint64>> m_token;
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
    const bool m_engineThere;
};
