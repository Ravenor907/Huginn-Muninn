#pragma once

#include "call/audiodsp.h"

#include <QAudioFormat>
#include <QByteArray>
#include <QHash>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <atomic>
#include <functional>
#include <memory>
#include <vector>

class QAudioSource;
class QFile;
class QIODevice;
class QNetworkAccessManager;
class QNetworkReply;
class QThread;
class DictationEngine;

// Dictation (code/shared/docs/dictation.md), the QML singleton "Dictation": speech to text in the message box, all on
// this computer.
//
// Settings › Look › Accessibility › Dictation (off until turned on). Turning it on downloads the speech model once
// (Moonshine Tiny with Silero VAD, about 125 MB) from the dictation-1 release, on Read aloud's network rules (unmetered
// unless "Allow on mobile data" is on, only through Tor while Ultra privacy is on, resumed after a cut), keeps it only
// when every file matches its line in code/shared/dictation.sha256 (built in as :/huginn/dictation.sha256), and puts it
// beside the voices in the data folder. Turning it off deletes it.
//
// With it in, every message box shows a microphone. start() opens the microphone at 16 kHz mono; the voice activity
// detector finds each phrase (it ends at a short pause), and the recognizer turns it into text on its own thread,
// handed to the box that started it (heard). stop() transcribes what's left. Never during a call or voice room.
class Dictation : public QObject
{
    Q_OBJECT
    // This copy can dictate: the speech engine is here and dictation.sha256 lists every file.
    Q_PROPERTY(bool offered READ offered CONSTANT)
    // The Dictation switch (off until turned on). On downloads the model; off deletes it.
    Q_PROPERTY(bool on READ isOn WRITE setOn NOTIFY changed)
    // The model is downloaded and checked.
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    // The message boxes show the microphone (on and ready, or the screenshot tour).
    Q_PROPERTY(bool shown READ shown NOTIFY changed)
    // The microphone is open for session [session].
    Q_PROPERTY(bool listening READ listening NOTIFY changed)
    Q_PROPERTY(int session READ session NOTIFY changed)
    // Stopped, and the last words are still being turned into text.
    Q_PROPERTY(bool working READ working NOTIFY changed)
    // The screenshot tour: the microphone shows as listening in every box, nothing is recorded or downloaded.
    Q_PROPERTY(bool tour READ tour WRITE setTour NOTIFY changed)
    // Settings' line: what it is while off, then "Downloading, 40%", "Ready..." and so on.
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
public:
    explicit Dictation(QObject *parent = nullptr);
    ~Dictation() override;

    bool offered() const;
    bool isOn() const;
    void setOn(bool on);
    bool ready() const;
    bool shown() const { return (isOn() && ready()) || m_tour; }
    bool listening() const { return m_listening || m_tour; }
    int session() const { return m_session; }
    bool working() const { return m_finishing != 0; }
    bool tour() const { return m_tour; }
    void setTour(bool on);
    QString status() const;

    // Opens the microphone and starts a new session (session). "" when listening; otherwise the line to show
    // ("Not during a call.", "The microphone isn't available.").
    Q_INVOKABLE QString start();
    // Closes the microphone; what was said since the last pause is still turned into text (heard), then working ends.
    Q_INVOKABLE void stop();
    // Closes the microphone and drops whatever wasn't turned into text yet (the chat changed, the box closed).
    Q_INVOKABLE void cancel();
    // What to put in at the cursor for [heard], after [before] (the text before the cursor): see joined().
    Q_INVOKABLE QString join(const QString &before, const QString &heard) const { return joined(before, heard); }
    // main.cpp: true while the microphone may not be used for this (a call or a voice room).
    void setBlocker(std::function<bool()> blocked) { m_blocked = std::move(blocked); }
    // Huginn is closing: the microphone closes, the download stops, and the recognizer is let go of on its own thread
    // (waited for about 2 s at most, as Chat::stopBigThreads does).
    void shutdown();

    // ---- the parts the self-test checks
    // [heard] trimmed; "" when nothing is left. A space goes first when [before] isn't empty and doesn't end in one.
    static QString joined(const QString &before, const QString &heard);
    // A name in the tar: "dictation-1/<file>" with <file> in [wanted] (no folders, no "..", nothing absolute).
    // Answers <file>, or "" for anything else.
    static QString fileIn(const QString &name, const QStringList &wanted);
    // Unpacks the release's tar into [dir]: regular files named in [hashes] only, each at most kMaxFile, each hashed
    // as it's written; folders and pax headers are passed over, anything else refuses it. True only when every file in
    // [hashes] came and matched. [stop] is asked between pieces (Huginn closing).
    static bool unpack(const QString &tarPath, const QString &dir, const QHash<QString, QString> &hashes, QString *why,
                       const std::function<bool()> &stop = {});
    // Run with --readaloud-selftest (CI): the rules above, the built-in hashes, and that the bundled engine has every
    // function dictation calls.
    static bool selfTest();

    static constexpr int kRate = 16000;
    static constexpr qint64 kMaxDownload = 160 * 1024 * 1024;
    static constexpr qint64 kMaxFile = 80 * 1024 * 1024;

Q_SIGNALS:
    void changed();
    void statusChanged();
    // Text for the box that started [session].
    void heard(int session, const QString &text);
    // Said once by the box that started [session] (the microphone stopped, the recognizer couldn't start).
    void problem(int session, const QString &text);

private:
    friend class DictationEngine;
    QString rootDir() const;
    QString modelDir() const;
    void setStatus(const QString &s);
    void prepare();
    void startDownload();
    void downloadDone();
    void downloadFailed(const QString &why, int retryMs);
    void unpacked(bool ok, const QString &why);
    void networkMoved();
    bool meteredNow() const;
    void closeMic();
    void onAudio();
    void sendPending(bool all);
    void ensureEngine();
    // The engine lets go of the model on its thread, then [then] runs here.
    void releaseEngine(std::function<void()> then);
    void deleteModel();
    // From the engine's thread.
    void onHeard(int session, const QString &text);
    void onFinished(int session);
    void onFailed(int session, const QString &why);

    QHash<QString, QString> m_hashes;
    const bool m_engineThere;
    bool m_tour = false;
    QString m_status;
    std::function<bool()> m_blocked;
    // the microphone
    QAudioSource *m_source = nullptr;
    QIODevice *m_in = nullptr;
    QAudioFormat m_format;
    AudioDsp::Resampler m_resample;
    // A short running average before the resampler when the microphone runs faster than 16 kHz (no aliasing).
    std::vector<float> m_smooth;
    int m_smoothAt = 0;
    double m_smoothSum = 0;
    std::vector<float> m_mono, m_down, m_pending;
    bool m_listening = false;
    int m_session = 0;
    int m_lastSession = 0;
    int m_finishing = 0;
    // the recognizer, on its own thread
    QThread *m_thread = nullptr;
    DictationEngine *m_engine = nullptr;
    // Sessions up to this one are dropped by the engine (cancelled, or Huginn closing).
    std::shared_ptr<std::atomic<int>> m_dropped;
    // Huginn is closing and the engine's thread was left behind: it never calls back.
    std::shared_ptr<std::atomic<bool>> m_gone;
    bool m_shutDown = false;
    // the download
    QNetworkAccessManager *m_net = nullptr;
    QPointer<QNetworkReply> m_reply;
    QFile *m_part = nullptr;
    QPointer<QThread> m_unpacker;
    std::shared_ptr<std::atomic<bool>> m_stopUnpack;
    bool m_wanted = false;
    int m_failures = 0;
    bool m_resumeNow = false;
    bool m_checked = false;
    qint64 m_got = 0;
    qint64 m_base = 0;
    QTimer m_retry;
};
