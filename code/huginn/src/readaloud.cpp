#include "readaloud.h"

#include "settings.h"
#include "sherpatts.h"
#include "system.h"
#include "ultraprivacy.h"

#include <QAudioDevice>
#include <QAudioOutput>
#include <QBuffer>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDataStream>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QLibrary>
#include <QMediaDevices>
#include <QMediaPlayer>
#include <QNetworkAccessManager>
#include <QNetworkInformation>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSettings>
#include <QStorageInfo>
#include <QThread>
#include <QPointer>
#include <QUrl>
#include <QtEndian>
#include <cstdio>
#include <cstring>
#ifdef HUGINN_HAVE_QTSPEECH
#include <QTextToSpeech>
#include <QVoice>
#endif
#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {

// The voices-1 release (code/shared/docs/voices.md). A pre-release: the update checks never look at it.
const char *kDownload = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/voices-1/";
// A plain agent with no app name or version, as the updater sends.
const char *kAgent = "Mozilla/5.0";
// The voice's folder name, with its release, so a later voices-2 never mixes with these files.
QString folderFor(const QString &voice) { return voice == QLatin1String("m") ? QStringLiteral("male-1") : QStringLiteral("female-1"); }
// Room for the download, unpacking and the rest of the disk.
constexpr qint64 kRoom = 300LL * 1024 * 1024;

// Where the bundled engine is: lib\ beside Huginn.exe (Windows), /app/lib (the Flatpak), or lib/ beside a local build's
// bin/. Full paths only: never a copy found by name on the library path, whose structs could differ.
QStringList engineCandidates()
{
    const QString app = QCoreApplication::applicationDirPath();
#ifdef Q_OS_WIN
    return {app + QStringLiteral("/lib/sherpa-onnx-c-api.dll"), app + QStringLiteral("/sherpa-onnx-c-api.dll")};
#else
    return {QStringLiteral("/app/lib/libsherpa-onnx-c-api.so"), QDir::cleanPath(app + QStringLiteral("/../lib/libsherpa-onnx-c-api.so"))};
#endif
}

#ifdef Q_OS_WIN
// Loads a DLL by its full path with its imports found only in its own folder and System32 (never the current folder or
// PATH). It stays loaded for the life of Huginn, so QLibrary's load of the same path gets this copy.
bool loadPinned(const QString &path)
{
    const std::wstring wide = QDir::toNativeSeparators(path).toStdWString();
    return LoadLibraryExW(wide.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS) != nullptr;
}
#endif

// Inside the Flatpak, and whether the host's speech-dispatcher is reachable there (the manifest shares only its
// folder in the runtime directory, read-only). Without it, speech-dispatcher would start a copy inside the sandbox
// that has no voice, so nothing would be heard.
[[maybe_unused]] bool inFlatpak()
{
#ifdef Q_OS_LINUX
    return QFileInfo::exists(QStringLiteral("/.flatpak-info"));
#else
    return false;
#endif
}

[[maybe_unused]] bool speechdThere()
{
    const QString run = qEnvironmentVariable("XDG_RUNTIME_DIR");
    return !run.isEmpty() && QFileInfo::exists(run + QStringLiteral("/speech-dispatcher/speechd.sock"));
}

// A downloaded voice unpacked into "<dir>.new" (marked ".unpacked" once whole) goes into its place: the old folder is
// moved aside first, so a crash in between never leaves neither (the next start finishes the move).
bool placeUnpacked(const QString &dir)
{
    const QString fresh = dir + QStringLiteral(".new");
    const QString old = dir + QStringLiteral(".old");
    QDir(old).removeRecursively();
    if (QFileInfo::exists(dir) && !QDir().rename(dir, old))
        QDir(dir).removeRecursively();
    if (!QDir().rename(fresh, dir)) {
        if (QFileInfo::exists(old) && !QFileInfo::exists(dir))
            QDir().rename(old, dir);
        return false;
    }
    QFile::remove(dir + QStringLiteral("/.unpacked"));
    QDir(old).removeRecursively();
    return true;
}

// Settings' line while voices.sha256 has no line for the voice (before the voices are published).
QString notReadyYet(bool device)
{
    return device ? QStringLiteral("Natural voices aren't ready yet. The computer's own voice reads")
                  : QStringLiteral("Natural voices aren't ready yet");
}

// How long a download may get no bytes before it's cut and resumed (sleep, a dead Wi-Fi, a stuck Tor circuit).
constexpr int kStallMs = 60 * 1000;

bool engineFileThere()
{
    for (const QString &c : engineCandidates())
        if (QFileInfo::exists(c))
            return true;
    return false;
}

// A path the engine (plain char* file names) can open: on Windows the short 8.3 form, so a folder with letters outside
// the system's code page still opens.
QByteArray enginePath(const QString &path)
{
#ifdef Q_OS_WIN
    const std::wstring wide = QDir::toNativeSeparators(path).toStdWString();
    const DWORD n = GetShortPathNameW(wide.c_str(), nullptr, 0);
    if (n > 0) {
        std::wstring shortPath(n, L'\0');
        const DWORD got = GetShortPathNameW(wide.c_str(), shortPath.data(), n);
        if (got > 0 && got < n) {
            shortPath.resize(got);
            return QString::fromStdWString(shortPath).toLocal8Bit();
        }
    }
    return QDir::toNativeSeparators(path).toLocal8Bit();
#else
    return QFile::encodeName(path);
#endif
}

// 16-bit mono WAV of [samples] (-1..1) at [rate].
QByteArray wavOf(const float *samples, int n, int rate)
{
    QByteArray pcm(n * 2, Qt::Uninitialized);
    for (int i = 0; i < n; ++i) {
        const float s = qBound(-1.0f, samples[i], 1.0f);
        qToLittleEndian<qint16>(qint16(s * 32767.0f), pcm.data() + 2 * i);
    }
    QByteArray wav;
    QDataStream out(&wav, QIODevice::WriteOnly);
    out.setByteOrder(QDataStream::LittleEndian);
    out.writeRawData("RIFF", 4);
    out << quint32(36 + pcm.size());
    out.writeRawData("WAVEfmt ", 8);
    out << quint32(16) << quint16(1) << quint16(1) << quint32(rate) << quint32(rate * 2) << quint16(2) << quint16(16);
    out.writeRawData("data", 4);
    out << quint32(pcm.size());
    out.writeRawData(pcm.constData(), pcm.size());
    return wav;
}

bool isDropped(char32_t c)
{
    // Emoji, pictographs, flags, skin tones, joiners, variation selectors, keycaps and tags: never read out.
    return (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0x2600 && c <= 0x27BF) || (c >= 0x2B00 && c <= 0x2BFF)
        || (c >= 0xFE00 && c <= 0xFE0F) || c == 0x200D || c == 0x20E3 || (c >= 0xE0000 && c <= 0xE007F);
}

} // namespace

// ------------------------------------------------------------------ the speech engine, on its own thread

class VoiceEngine : public QObject
{
public:
    explicit VoiceEngine(std::shared_ptr<std::atomic<quint64>> token) : m_token(std::move(token)), m_idle(new QTimer(this))
    {
        // A child, so it moves to the engine's thread with it.
        m_idle->setSingleShot(true);
        m_idle->setInterval(60 * 1000);
        QObject::connect(m_idle, &QTimer::timeout, this, [this] { unload(); });
    }
    ~VoiceEngine() override { unload(); }

    // Lets go of the voice now (its files are about to be deleted). Runs on the engine's thread.
    void release()
    {
        m_idle->stop();
        unload();
    }

    // Opens the engine and [dir]'s voice (once), then speaks [pieces] one at a time while [token] is current.
    void say(quint64 token, const QString &dir, const QStringList &pieces, ReadAloud *to)
    {
        m_idle->stop();
        QString why;
        if (!open(dir, &why)) {
            QMetaObject::invokeMethod(to, [to, token, why] { to->onEngineFailed(token, why); }, Qt::QueuedConnection);
            return;
        }
        // Marked while the engine works: a mark left at the next start means it stopped Huginn mid-sentence.
        const QString mark = QFileInfo(dir).path() + QStringLiteral("/reading");
        {
            QFile m(mark);
            if (m.open(QIODevice::WriteOnly))
                m.close();
        }
        for (const QString &p : pieces) {
            if (m_token->load() != token)
                break;
            // Only plain letters, digits and simple punctuation reach the engine (ReadAloud::forEngine).
            const QString clean = ReadAloud::forEngine(p);
            if (clean.isEmpty())
                continue;
            const QByteArray utf8 = clean.toUtf8();
            const SherpaOnnxGeneratedAudio *a = m_generate(m_tts, utf8.constData(), 0, 1.0f);
            if (!a)
                continue;
            const QByteArray wav = (a->n > 0 && a->sample_rate > 0) ? wavOf(a->samples, a->n, a->sample_rate) : QByteArray();
            m_free(a);
            if (wav.isEmpty())
                continue;
            QMetaObject::invokeMethod(to, [to, token, wav] { to->onSpoken(token, wav); }, Qt::QueuedConnection);
        }
        QFile::remove(mark);
        QMetaObject::invokeMethod(to, [to, token] { to->onSpoken(token, QByteArray()); }, Qt::QueuedConnection);
        // Let go of the model (and its memory) after a minute with nothing to read.
        m_idle->start();
    }

    // The library loads and has every function Huginn calls (the self-test).
    static bool libraryLoads(QString *why)
    {
        VoiceEngine e(std::make_shared<std::atomic<quint64>>(0));
        return e.load(why);
    }

private:
    bool load(QString *why)
    {
        if (m_lib.isLoaded() && m_create)
            return true;
#ifdef Q_OS_WIN
        // ONNX Runtime first, from lib\ by its full path: a different onnxruntime.dll elsewhere (Windows has one) is never used.
        const QString ort = QCoreApplication::applicationDirPath() + QStringLiteral("/lib/onnxruntime.dll");
        if (QFileInfo::exists(ort) && loadPinned(ort)) {
            m_ort.setFileName(ort);
            m_ort.load();
        }
#endif
        for (const QString &c : engineCandidates()) {
            if (!QFileInfo::exists(c))
                continue;
#ifdef Q_OS_WIN
            if (!loadPinned(c))
                continue;
#endif
            m_lib.setFileName(c);
            if (m_lib.load())
                break;
        }
        if (!m_lib.isLoaded()) {
            if (why) *why = QStringLiteral("The speech engine isn't in this copy of Huginn") + (m_lib.errorString().isEmpty() ? QString() : QStringLiteral(" (") + m_lib.errorString() + QStringLiteral(")"));
            return false;
        }
        m_create = reinterpret_cast<SherpaOnnxCreateOfflineTtsFn>(m_lib.resolve("SherpaOnnxCreateOfflineTts"));
        m_destroy = reinterpret_cast<SherpaOnnxDestroyOfflineTtsFn>(m_lib.resolve("SherpaOnnxDestroyOfflineTts"));
        m_generate = reinterpret_cast<SherpaOnnxOfflineTtsGenerateFn>(m_lib.resolve("SherpaOnnxOfflineTtsGenerate"));
        m_free = reinterpret_cast<SherpaOnnxDestroyOfflineTtsGeneratedAudioFn>(m_lib.resolve("SherpaOnnxDestroyOfflineTtsGeneratedAudio"));
        if (!m_create || !m_destroy || !m_generate || !m_free) {
            if (why) *why = QStringLiteral("The speech engine is missing a part");
            m_create = nullptr;
            m_lib.unload();
            return false;
        }
        // Only the exact version sherpatts.h was copied from: another one's structs could differ.
        const auto version = reinterpret_cast<SherpaOnnxGetVersionStrFn>(m_lib.resolve("SherpaOnnxGetVersionStr"));
        const QByteArray have = version ? QByteArray(version()) : QByteArray();
        if (have != HUGINN_SHERPA_ONNX_VERSION) {
            if (why) *why = QStringLiteral("The speech engine is a different version (%1)").arg(have.isEmpty() ? QStringLiteral("unknown") : QString::fromLatin1(have));
            m_create = nullptr;
            m_lib.unload();
            return false;
        }
        return true;
    }

    bool open(const QString &dir, QString *why)
    {
        if (m_tts && m_dir == dir)
            return true;
        unloadVoice();
        if (!load(why))
            return false;
        const QByteArray model = enginePath(dir + QStringLiteral("/model.onnx"));
        const QByteArray tokens = enginePath(dir + QStringLiteral("/tokens.txt"));
        const QByteArray data = enginePath(dir + QStringLiteral("/espeak-ng-data"));
        // In a zeroed buffer 4 KB larger than the struct: a library whose struct were bigger reads zeros, never the stack.
        alignas(SherpaOnnxOfflineTtsConfig) unsigned char room[sizeof(SherpaOnnxOfflineTtsConfig) + 4096];
        std::memset(room, 0, sizeof room);
        SherpaOnnxOfflineTtsConfig &c = *reinterpret_cast<SherpaOnnxOfflineTtsConfig *>(room);
        c.model.vits.model = model.constData();
        c.model.vits.tokens = tokens.constData();
        c.model.vits.data_dir = data.constData();
        c.model.vits.lexicon = "";
        c.model.vits.dict_dir = "";
        c.model.vits.noise_scale = 0.667f;
        c.model.vits.noise_scale_w = 0.8f;
        c.model.vits.length_scale = 1.0f;
        c.model.num_threads = qBound(1, QThread::idealThreadCount() / 2, 4);
        c.model.debug = 0;
        c.model.provider = "cpu";
        c.rule_fsts = "";
        c.rule_fars = "";
        c.max_num_sentences = 1;
        c.silence_scale = 0.2f;
        m_tts = m_create(&c);
        if (!m_tts) {
            if (why) *why = QStringLiteral("The natural voice couldn't start");
            return false;
        }
        m_dir = dir;
        return true;
    }

    void unloadVoice()
    {
        if (m_tts && m_destroy)
            m_destroy(m_tts);
        m_tts = nullptr;
        m_dir.clear();
    }

    void unload()
    {
        unloadVoice();
        // The library stays loaded: unloading ONNX Runtime while its threads wind down isn't safe.
    }

    std::shared_ptr<std::atomic<quint64>> m_token;
    QLibrary m_lib;
#ifdef Q_OS_WIN
    QLibrary m_ort;
#endif
    SherpaOnnxCreateOfflineTtsFn m_create = nullptr;
    SherpaOnnxDestroyOfflineTtsFn m_destroy = nullptr;
    SherpaOnnxOfflineTtsGenerateFn m_generate = nullptr;
    SherpaOnnxDestroyOfflineTtsGeneratedAudioFn m_free = nullptr;
    const SherpaOnnxOfflineTts *m_tts = nullptr;
    QString m_dir;
    QTimer *m_idle;
};

// ------------------------------------------------------------------ ReadAloud

ReadAloud::ReadAloud(QObject *parent)
    : QObject(parent), m_token(std::make_shared<std::atomic<quint64>>(1)), m_engineThere(engineFileThere())
{
    QFile f(QStringLiteral(":/huginn/voices.sha256"));
    if (f.open(QIODevice::ReadOnly))
        m_hashes = hashes(f.readAll());
    m_retry.setSingleShot(true);
    connect(&m_retry, &QTimer::timeout, this, &ReadAloud::startDownload);
    // A metered connection that ends (back on Wi-Fi) starts a waiting download. A new network (another Wi-Fi, back
    // online after sleep) cuts a download that may be talking to a dead connection and resumes it at once.
    if (QNetworkInformation::loadBackendByFeatures(QNetworkInformation::Feature::Metered) || QNetworkInformation::loadDefaultBackend()) {
        if (auto *ni = QNetworkInformation::instance()) {
            connect(ni, &QNetworkInformation::isMeteredChanged, this, [this](bool metered) {
                if (!metered && !m_downloading.isEmpty() && !m_reply)
                    startDownload();
            });
            connect(ni, &QNetworkInformation::reachabilityChanged, this, [this](QNetworkInformation::Reachability r) {
                if (r == QNetworkInformation::Reachability::Online)
                    networkMoved();
            });
            connect(ni, &QNetworkInformation::transportMediumChanged, this, [this] { networkMoved(); });
        }
    }
    // Ultra privacy: a download waiting for Tor goes once it's ready; one made the old way starts again through Tor.
    if (UltraPrivacy *u = UltraPrivacy::instance()) {
        connect(u, &UltraPrivacy::changed, this, [this] {
            if (!m_downloading.isEmpty() && !m_reply && UltraPrivacy::on() && UltraPrivacy::instance()->torState() == QLatin1String("ready"))
                startDownload();
        });
        connect(u, &UltraPrivacy::redial, this, [this] {
            if (!m_reply)
                return;
            m_reply->abort();
            QTimer::singleShot(0, this, &ReadAloud::startDownload);
        });
    }
    // A voice unpacked just before Huginn stopped goes into its place now.
    recoverUnpacked();
    // The engine stopped Huginn mid-sentence (a mark is left): twice in a row and the computer's own voice reads.
    const QString mark = voicesDir() + QStringLiteral("/reading");
    if (QFileInfo::exists(mark)) {
        QFile::remove(mark);
        const int n = Settings::store().value(QStringLiteral("readAloud/cutShort"), 0).toInt() + 1;
        Settings::store().setValue(QStringLiteral("readAloud/cutShort"), n);
    }
    m_engineBroken = Settings::store().value(QStringLiteral("readAloud/cutShort"), 0).toInt() >= 2;
    if (m_engineBroken)
        m_status = QStringLiteral("The natural voice closed Huginn twice, so the computer's own voice reads. Delete voices to try it again");
    else if (ready())
        m_status = QStringLiteral("Ready");
    else if (natural())
        m_status = QStringLiteral("Downloads the first time something is read aloud");
    else
        m_status = notReadyYet(deviceCan());
}

ReadAloud::~ReadAloud()
{
    m_token->fetch_add(1);
    if (m_reply)
        m_reply->abort();
    if (m_thread) {
        // No time limit: the sentence being made always ends (the token above stops the rest), and the thread must be
        // gone before the engine or this object is.
        m_thread->quit();
        m_thread->wait();
    }
    delete m_part;
    delete m_sha;
}

bool ReadAloud::natural() const
{
    return m_engineThere && !m_engineBroken && (ready() || m_hashes.contains(fileFor(voice())));
}

bool ReadAloud::available() const
{
    return natural() || deviceCan();
}

bool ReadAloud::deviceCan() const
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (m_deviceFailed)
        return false;
    // The Flatpak: the host's speech-dispatcher, or Qt's own offline engine (flite) when the runtime has it.
    if (inFlatpak()) {
        static const bool flite = QTextToSpeech::availableEngines().contains(QStringLiteral("flite"));
        return speechdThere() || flite;
    }
    return true;
#else
    return false;
#endif
}

void ReadAloud::recoverUnpacked()
{
    for (const QString &v : {QStringLiteral("f"), QStringLiteral("m")}) {
        const QString dir = voiceDir(v);
        if (QFileInfo::exists(dir + QStringLiteral(".new/.unpacked")) && !QFileInfo::exists(dir + QStringLiteral("/model.onnx")))
            placeUnpacked(dir);
    }
}

QString ReadAloud::voice() const
{
    return Settings::store().value(QStringLiteral("readAloud/voice"), QStringLiteral("f")).toString() == QLatin1String("m")
        ? QStringLiteral("m") : QStringLiteral("f");
}

void ReadAloud::setVoice(const QString &v)
{
    const QString pick = v == QLatin1String("m") ? QStringLiteral("m") : QStringLiteral("f");
    if (pick == voice())
        return;
    Settings::store().setValue(QStringLiteral("readAloud/voice"), pick);
    stop();
    // The other voice's download (if one ran) gives way to this one.
    m_downloading.clear();
    if (m_reply)
        m_reply->abort();
    if (ready()) {
        setStatus(QStringLiteral("Ready"));
        cleanOther();
    } else if (!natural()) {
        setStatus(m_engineBroken ? m_status : notReadyYet(deviceCan()));
    } else if (hasFiles()) {
        // Switching downloads the new voice now; the old one is deleted once the new one is in.
        prepare();
    } else {
        setStatus(QStringLiteral("Downloads the first time something is read aloud"));
    }
    Q_EMIT changed();
}

bool ReadAloud::mobileData() const { return Settings::store().value(QStringLiteral("readAloud/mobileData"), false).toBool(); }

void ReadAloud::setMobileData(bool on)
{
    if (on == mobileData())
        return;
    Settings::store().setValue(QStringLiteral("readAloud/mobileData"), on);
    if (on && !m_downloading.isEmpty() && !m_reply)
        startDownload();
    Q_EMIT changed();
}

QString ReadAloud::voicesDir() const { return System::dataDir() + QStringLiteral("/voices"); }
QString ReadAloud::voiceDir(const QString &v) const { return voicesDir() + QLatin1Char('/') + folderFor(v); }

bool ReadAloud::ready() const
{
    const QString d = voiceDir(voice());
    return QFileInfo::exists(d + QStringLiteral("/model.onnx")) && QFileInfo::exists(d + QStringLiteral("/tokens.txt"))
        && QFileInfo::exists(d + QStringLiteral("/espeak-ng-data/phontab"));
}

bool ReadAloud::hasFiles() const
{
    const QDir d(voicesDir());
    return d.exists() && !d.entryList(QDir::Dirs | QDir::Files | QDir::NoDotAndDotDot).isEmpty();
}

void ReadAloud::setStatus(const QString &s)
{
    if (s == m_status)
        return;
    m_status = s;
    Q_EMIT statusChanged();
}

void ReadAloud::speak(const QString &text)
{
    stop();
    // Never while the lock is up, the screen lock shows or a call is on (main.cpp says which), whatever asked.
    if (m_blocked && m_blocked())
        return;
    const QStringList parts = pieces(plain(text));
    m_lastParts = parts;
    if (parts.isEmpty())
        return;
    // Text with nothing the engine reads (another script only) goes to the computer's own voice.
    bool engineReads = false;
    for (const QString &p : parts)
        engineReads = engineReads || !forEngine(p).isEmpty();
    if (natural() && ready() && !m_deleting && engineReads) {
        const quint64 token = m_token->load();
        if (!m_thread) {
            m_thread = new QThread(this);
            m_thread->setObjectName(QStringLiteral("readaloud"));
            m_engine = new VoiceEngine(m_token);
            m_engine->moveToThread(m_thread);
            connect(m_thread, &QThread::finished, m_engine, &QObject::deleteLater);
            m_thread->start(QThread::LowPriority);
        }
        m_speaking = true;
        m_pending = 1;
        Q_EMIT changed();
        const QString dir = voiceDir(voice());
        VoiceEngine *engine = m_engine;
        QMetaObject::invokeMethod(engine, [engine, token, dir, parts, this] { engine->say(token, dir, parts, this); }, Qt::QueuedConnection);
        return;
    }
    // Not ready yet: the download starts (on the network rules), and the computer's own voice reads meanwhile.
    prepare();
    speakDevice(parts);
}

void ReadAloud::speakDevice(const QStringList &parts)
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (deviceCan())
        loadDeviceVoice();
    if (m_device && m_deviceOk) {
        m_speaking = true;
        Q_EMIT changed();
        m_device->say(parts.join(QLatin1Char(' ')));
        return;
    }
#else
    Q_UNUSED(parts)
#endif
    // Nothing on this computer can read yet: the status says why (the menu shows it).
    m_speaking = false;
    if (!m_reply)
        setStatus(natural() ? QStringLiteral("Read aloud starts once the natural voice is downloaded")
                            : QStringLiteral("No voice to read with on this computer yet"));
    Q_EMIT changed();
}

void ReadAloud::loadDeviceVoice()
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (m_device)
        return;
    // In the Flatpak without the host's speech-dispatcher, Qt's own offline engine (deviceCan() checked it's there).
    if (inFlatpak() && !speechdThere())
        m_device = new QTextToSpeech(QStringLiteral("flite"), this);
    else
        m_device = new QTextToSpeech(this);
    const auto take = [this](QTextToSpeech::State s) {
        const bool ok = s != QTextToSpeech::Error;
        if (!ok && !m_deviceFailed) {
            // No speech service here: Read aloud isn't offered again this run unless the natural voice comes.
            m_deviceFailed = true;
            if (m_speaking && m_pending == 0 && m_queue.isEmpty())
                m_speaking = false;
            if (!m_reply && !ready())
                setStatus(natural() ? QStringLiteral("Read aloud starts once the natural voice is downloaded")
                                    : QStringLiteral("No voice to read with on this computer yet"));
            Q_EMIT changed();
        }
        if (ok != m_deviceOk) {
            m_deviceOk = ok;
            Q_EMIT changed();
        }
        if (s == QTextToSpeech::Ready && m_speaking && m_queue.isEmpty() && m_pending == 0) {
            m_speaking = false;
            Q_EMIT changed();
        }
    };
    connect(m_device, &QTextToSpeech::stateChanged, this, take);
    // An English voice where there is one; the engines Qt has here (SAPI, WinRT, speech-dispatcher) speak on this computer.
    const QList<QVoice> voices = m_device->availableVoices();
    for (const QVoice &v : voices)
        if (v.locale().language() == QLocale::English) {
            m_device->setVoice(v);
            break;
        }
    take(m_device->state());
#endif
}

void ReadAloud::stop()
{
    m_token->fetch_add(1);
    m_queue.clear();
    m_pending = 0;
    if (m_player)
        m_player->stop();
    if (m_buffer)
        m_buffer->close();
    m_playing.clear();
#ifdef HUGINN_HAVE_QTSPEECH
    if (m_device)
        m_device->stop();
#endif
    if (m_speaking) {
        m_speaking = false;
        Q_EMIT changed();
    }
}

void ReadAloud::onSpoken(quint64 token, const QByteArray &wav)
{
    if (token != m_token->load())
        return;
    if (wav.isEmpty()) {
        // The engine finished this text (so it didn't stop Huginn).
        if (Settings::store().value(QStringLiteral("readAloud/cutShort"), 0).toInt() != 0)
            Settings::store().setValue(QStringLiteral("readAloud/cutShort"), 0);
        m_pending = 0;
        if (m_queue.isEmpty() && m_playing.isEmpty()) {
            m_speaking = false;
            Q_EMIT changed();
        }
        return;
    }
    m_queue.append(wav);
    if (m_playing.isEmpty())
        playNext();
}

void ReadAloud::onEngineFailed(quint64 token, const QString &why)
{
    if (token != m_token->load())
        return;
    std::fprintf(stderr, "Read aloud: %s\n", qPrintable(why));
    m_pending = 0;
    m_speaking = false;
    // The engine won't load or start here (a missing or blocked library, a CPU it can't use): the computer's own voice
    // reads this text and the next ones, this run.
    m_engineBroken = true;
    setStatus(why + QStringLiteral(". The computer's own voice reads"));
    Q_EMIT changed();
    speakDevice(m_lastParts);
}

void ReadAloud::playNext()
{
    if (m_queue.isEmpty()) {
        m_playing.clear();
        if (m_pending == 0 && m_speaking) {
            m_speaking = false;
            Q_EMIT changed();
        }
        return;
    }
    if (!m_player) {
        m_player = new QMediaPlayer(this);
        m_output = new QAudioOutput(this);
        m_buffer = new QBuffer(this);
        m_player->setAudioOutput(m_output);
        connect(m_player, &QMediaPlayer::mediaStatusChanged, this, [this](QMediaPlayer::MediaStatus s) {
            if (s == QMediaPlayer::EndOfMedia || s == QMediaPlayer::InvalidMedia) {
                m_playing.clear();
                // The next piece once the player has finished with this one (never from inside its own signal).
                QTimer::singleShot(0, this, [this] { if (m_playing.isEmpty()) playNext(); });
            }
        });
    }
    // Settings › Calls › Speaker, while it's plugged in (as voice messages play).
    QAudioDevice out = QMediaDevices::defaultAudioOutput();
    const QString outId = Settings::savedSpeaker();
    for (const QAudioDevice &d : QMediaDevices::audioOutputs())
        if (!outId.isEmpty() && QString::fromLatin1(d.id()) == outId)
            out = d;
    m_output->setDevice(out);
    m_playing = m_queue.takeFirst();
    m_player->setSource(QUrl());
    m_buffer->close();
    m_buffer->setData(m_playing);
    m_buffer->open(QIODevice::ReadOnly);
    m_player->setSourceDevice(m_buffer, QUrl(QStringLiteral("speech.wav")));
    m_player->play();
}

// ------------------------------------------------------------------ the download

bool ReadAloud::meteredNow() const
{
    auto *ni = QNetworkInformation::instance();
    return ni && ni->supports(QNetworkInformation::Feature::Metered) && ni->isMetered();
}

void ReadAloud::prepare()
{
    if (ready() || m_reply)
        return;
    if (!natural()) {
        // Nothing to download yet (or the engine can't run here): say so, never a download that can't start.
        setStatus(m_engineBroken ? m_status : notReadyYet(deviceCan()));
        return;
    }
    m_downloading = voice();
    startDownload();
}

void ReadAloud::startDownload()
{
    if (m_downloading.isEmpty() || m_reply || ready())
        return;
    const QString file = fileFor(m_downloading);
    const QString sum = m_hashes.value(file);
    if (sum.isEmpty()) {
        setStatus(notReadyYet(deviceCan()));
        return;
    }
    if (meteredNow() && !mobileData()) {
        setStatus(QStringLiteral("Waits for Wi-Fi or a wired connection (or allow mobile data)"));
        return;
    }
    // Ultra privacy: only through Tor (UltraPrivacy::prepare); until Tor is ready, wait and say so.
    if (UltraPrivacy::on() && (!UltraPrivacy::instance() || UltraPrivacy::instance()->torState() != QLatin1String("ready"))) {
        setStatus(QStringLiteral("Waits for Ultra privacy's Tor connection. The computer's own voice reads meanwhile"));
        return;
    }
    QDir().mkpath(voicesDir());
    if (QStorageInfo(voicesDir()).bytesAvailable() < kRoom) {
        setStatus(QStringLiteral("Not enough free space for the natural voice (about 300 MB needed)"));
        return;
    }
    if (!m_net)
        m_net = new QNetworkAccessManager(this);
    // Tor's SOCKS5 while Ultra privacy is on, the normal way while it's off (set again on every start).
    UltraPrivacy::prepare(m_net);
    m_retry.stop();
    // Picks up where a cut-off download stopped: the part kept is hashed again, then the rest is asked for.
    const QString partPath = voicesDir() + QLatin1Char('/') + file + QStringLiteral(".part");
    delete m_part;
    delete m_sha;
    m_part = new QFile(partPath);
    m_sha = new QCryptographicHash(QCryptographicHash::Sha256);
    m_got = 0;
    if (m_part->exists() && m_part->size() > 0 && m_part->size() < kMaxDownload && m_part->open(QIODevice::ReadOnly)) {
        while (!m_part->atEnd())
            m_sha->addData(m_part->read(1 << 20));
        m_got = m_part->size();
        m_part->close();
    } else {
        QFile::remove(partPath);
    }
    if (!m_part->open(QIODevice::WriteOnly | QIODevice::Append)) {
        downloadFailed(QStringLiteral("Can't save the voice in the data folder"), 15 * 60 * 1000);
        return;
    }
    QNetworkRequest req(QUrl(QString::fromLatin1(kDownload) + file));
    req.setHeader(QNetworkRequest::UserAgentHeader, QString::fromLatin1(kAgent));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    // No bytes for a minute (sleep, another network, a stuck Tor circuit): cut, and resumed from the part kept.
    req.setTransferTimeout(kStallMs);
    m_resumeNow = false;
    if (m_got > 0)
        req.setRawHeader("Range", "bytes=" + QByteArray::number(m_got) + "-");
    m_base = m_got;
    m_checked = false;
    m_reply = m_net->get(req);
    setStatus(QStringLiteral("Downloading the natural voice"));
    connect(m_reply, &QNetworkReply::readyRead, this, [this] {
        if (!m_reply)
            return;
        const int code = m_reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (code != 200 && code != 206) {
            // An error page (a 416 for a part that is already whole, a 404, a 5xx) never goes into the part.
            m_reply->readAll();
            return;
        }
        if (!m_checked) {
            m_checked = true;
            if (code == 200 && m_base > 0) {
                // The server sent the whole file again: start the part over.
                m_part->resize(0);
                m_sha->reset();
                m_got = 0;
                m_base = 0;
            }
        }
        const QByteArray chunk = m_reply->readAll();
        if (m_got + chunk.size() > kMaxDownload) {
            m_got = kMaxDownload + 1;   // never right: the part is thrown away
            m_reply->abort();
            return;
        }
        m_part->write(chunk);
        m_sha->addData(chunk);
        m_got += chunk.size();
        const qint64 total = m_reply->header(QNetworkRequest::ContentLengthHeader).toLongLong() + m_base;
        if (total > 0) {
            m_total = total;
            setStatus(QStringLiteral("Downloading the natural voice, %1%").arg(qBound(qint64(0), qint64(m_got * 100 / qMax(qint64(1), qint64(total))), qint64(100))));
        }
    });
    connect(m_reply, &QNetworkReply::finished, this, &ReadAloud::downloadDone);
}

void ReadAloud::downloadDone()
{
    QNetworkReply *r = m_reply;
    m_reply = nullptr;
    if (!r)
        return;
    r->deleteLater();
    if (m_part)
        m_part->close();
    if (m_downloading.isEmpty())
        return;   // switched voices or deleted meanwhile
    const QString file = fileFor(m_downloading);
    const QString partPath = voicesDir() + QLatin1Char('/') + file + QStringLiteral(".part");
    // 416: the part kept was already the whole file (Huginn closed before checking it): checked now.
    const bool whole = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() == 416 && m_base > 0;
    if (r->error() != QNetworkReply::NoError && !whole) {
        // A size over the limit is never right: start over next time. Anything else picks up where it stopped: at
        // once after a network change, within a minute for the first few cuts (a stall, sleep), later after that.
        if (m_got > kMaxDownload || r->error() == QNetworkReply::ContentNotFoundError)
            QFile::remove(partPath);
        ++m_failures;
        const int wait = m_resumeNow ? 3 * 1000 : m_failures <= 3 ? 60 * 1000 : 15 * 60 * 1000;
        m_resumeNow = false;
        downloadFailed(QStringLiteral("The natural voice couldn't download. It tries again soon"), wait);
        return;
    }
    const QString got = QString::fromLatin1(m_sha->result().toHex());
    if (got != m_hashes.value(file)) {
        QFile::remove(partPath);
        downloadFailed(QStringLiteral("The natural voice didn't match its check, so it wasn't used. It tries again later"), 15 * 60 * 1000);
        return;
    }
    m_failures = 0;
    // Unpacked beside its final place (marked once whole), then put there.
    const QString dir = voiceDir(m_downloading);
    const QString fresh = dir + QStringLiteral(".new");
    QDir(fresh).removeRecursively();
    QString why;
    if (!unpack(partPath, fresh, &why)) {
        QDir(fresh).removeRecursively();
        QFile::remove(partPath);
        downloadFailed(QStringLiteral("The natural voice couldn't be unpacked (%1)").arg(why), 15 * 60 * 1000);
        return;
    }
    {
        QFile done(fresh + QStringLiteral("/.unpacked"));
        if (done.open(QIODevice::WriteOnly))
            done.close();
    }
    if (!placeUnpacked(dir)) {
        QDir(fresh).removeRecursively();
        downloadFailed(QStringLiteral("Can't save the voice in the data folder"), 15 * 60 * 1000);
        return;
    }
    QFile::remove(partPath);
    m_downloading.clear();
    // A fresh voice gets a fresh start.
    if (m_engineBroken || Settings::store().value(QStringLiteral("readAloud/cutShort"), 0).toInt() != 0) {
        Settings::store().setValue(QStringLiteral("readAloud/cutShort"), 0);
        m_engineBroken = false;
    }
    cleanOther();
    setStatus(QStringLiteral("Ready"));
    Q_EMIT changed();
}

void ReadAloud::downloadFailed(const QString &why, int retryMs)
{
    setStatus(why);
    if (retryMs > 0)
        m_retry.start(retryMs);
}

void ReadAloud::networkMoved()
{
    if (m_downloading.isEmpty())
        return;
    if (m_reply) {
        // The connection may be dead without saying so: cut it, and the part kept resumes in a moment.
        m_resumeNow = true;
        m_reply->abort();
    } else if (m_retry.isActive()) {
        m_retry.start(3 * 1000);
    }
}

void ReadAloud::releaseEngine(std::function<void()> then)
{
    // The engine lets go of the voice's files (Windows can't delete files in use) on its own thread, after the
    // sentence it may be making; then [then] runs here. The window never waits for it.
    if (m_engine && m_thread && m_thread->isRunning()) {
        VoiceEngine *engine = m_engine;
        QPointer<ReadAloud> self(this);
        QMetaObject::invokeMethod(engine, [engine, self, then] {
            engine->release();
            if (self)
                QMetaObject::invokeMethod(self.data(), then, Qt::QueuedConnection);
        }, Qt::QueuedConnection);
        return;
    }
    then();
}

void ReadAloud::cleanOther()
{
    releaseEngine([this] {
        // Only the chosen voice is kept: the other one (and any leftovers) go once this one is in. A download under
        // way (its part and its unpack folder) stays.
        const QString keep = folderFor(voice());
        const QDir d(voicesDir());
        for (const QString &e : d.entryList(QDir::Dirs | QDir::Files | QDir::NoDotAndDotDot)) {
            if (e == keep || (!m_downloading.isEmpty() && (e.startsWith(fileFor(m_downloading)) || e.startsWith(folderFor(m_downloading)))))
                continue;
            const QString p = d.filePath(e);
            if (QFileInfo(p).isDir())
                QDir(p).removeRecursively();
            else
                QFile::remove(p);
        }
        Q_EMIT changed();
    });
}

void ReadAloud::deleteVoices()
{
    stop();
    m_token->fetch_add(1);
    m_downloading.clear();
    if (m_reply)
        m_reply->abort();
    m_retry.stop();
    if (m_part)
        m_part->close();
    // Meanwhile anything read uses the computer's own voice.
    m_deleting = true;
    setStatus(QStringLiteral("Deleting the voices"));
    // The engine lets go of the files first (on its thread), then they go.
    releaseEngine([this] {
        QDir(voicesDir()).removeRecursively();
        m_deleting = false;
        Settings::store().setValue(QStringLiteral("readAloud/cutShort"), 0);
        m_engineBroken = false;
        m_failures = 0;
        setStatus(natural() ? QStringLiteral("Deleted. Downloads again the next time something is read aloud") : notReadyYet(deviceCan()));
        Q_EMIT changed();
    });
}

// ------------------------------------------------------------------ the rules (also in Muninn's ReadAloudText)

QString ReadAloud::plain(const QString &text)
{
    QString t = text.left(kMaxText);
    static const QRegularExpression link(QStringLiteral("(?:https?://|www\\.)\\S+"), QRegularExpression::CaseInsensitiveOption);
    t.replace(link, QStringLiteral(" link "));
    // Marks people type for emphasis or code, and quotes.
    static const QRegularExpression marks(QStringLiteral("[*~`|]+"));
    t.replace(marks, QString());
    t.replace(QLatin1Char('_'), QLatin1Char(' '));
    static const QRegularExpression quote(QStringLiteral("(^|\\n)\\s*>+\\s?"));
    t.replace(quote, QStringLiteral("\\1"));
    static const QRegularExpression at(QStringLiteral("(^|\\s)@(\\w)"), QRegularExpression::UseUnicodePropertiesOption);
    t.replace(at, QStringLiteral("\\1\\2"));
    QString out;
    out.reserve(t.size());
    int run = 0;
    for (const char32_t c : t.toUcs4()) {
        if (isDropped(c))
            continue;
        if (c == U'\n') {
            out.append(QLatin1Char('\n'));
            run = 0;
            continue;
        }
        // Control and format characters, unpaired halves, private and unassigned ones, and markup (<, >, [, ], {, },
        // backslash): a space. Nothing that could steer a speech engine gets through.
        const QChar::Category cat = QChar::category(c);
        const bool space = cat == QChar::Other_Control || cat == QChar::Other_Format || cat == QChar::Other_Surrogate
            || cat == QChar::Other_PrivateUse || cat == QChar::Other_NotAssigned || cat == QChar::Separator_Line
            || cat == QChar::Separator_Paragraph || cat == QChar::Separator_Space || c == U'<' || c == U'>' || c == U'['
            || c == U']' || c == U'{' || c == U'}' || c == U'\\';
        if (space) {
            out.append(QLatin1Char(' '));
            run = 0;
            continue;
        }
        // Very long "words" are cut into pieces of kMaxWord.
        if (run == kMaxWord) {
            out.append(QLatin1Char(' '));
            run = 0;
        }
        out.append(QString::fromUcs4(&c, 1));
        ++run;
    }
    // Lines stay (each is read on its own); spaces within a line are tidied.
    QStringList lines = out.split(QLatin1Char('\n'));
    QStringList kept;
    static const QRegularExpression spaces(QStringLiteral(" +"));
    for (QString &l : lines) {
        l.replace(spaces, QStringLiteral(" "));
        l = l.trimmed();
        if (!l.isEmpty())
            kept.append(l);
    }
    return kept.join(QLatin1Char('\n'));
}

QString ReadAloud::forEngine(const QString &piece)
{
    QString out;
    out.reserve(piece.size());
    bool afterLetter = false;
    int accents = 0;
    for (const char32_t c : piece.toUcs4()) {
        const QChar::Category cat = QChar::category(c);
        if (cat == QChar::Mark_NonSpacing || cat == QChar::Mark_SpacingCombining || cat == QChar::Mark_Enclosing) {
            // An accent on a letter (a typed "a" plus its ring): at most two on one letter.
            if (afterLetter && accents < 2) {
                out.append(QString::fromUcs4(&c, 1));
                ++accents;
            }
            continue;
        }
        accents = 0;
        afterLetter = false;
        if (QChar::isLetter(c) && QChar::script(c) == QChar::Script_Latin) {
            out.append(QString::fromUcs4(&c, 1));
            afterLetter = true;
        } else if (c >= U'0' && c <= U'9') {
            out.append(QChar(char16_t(c)));
        } else if (c == U'&') {
            out.append(QStringLiteral(" and "));
        } else if (c == 0x2018 || c == 0x2019) {
            out.append(QLatin1Char('\''));
        } else if (c == 0x201C || c == 0x201D) {
            out.append(QLatin1Char('"'));
        } else if (c == 0x2026) {
            out.append(QStringLiteral("..."));
        } else if (c < 0x80 && std::strchr(".,!?;:'\"()-/%+", char(c)) && c != 0) {
            out.append(QChar(char16_t(c)));
        } else {
            out.append(QLatin1Char(' '));
        }
    }
    static const QRegularExpression spaces(QStringLiteral(" +"));
    out.replace(spaces, QStringLiteral(" "));
    return out.trimmed();
}

QStringList ReadAloud::pieces(const QString &text)
{
    QStringList out;
    static const QRegularExpression end(QStringLiteral("(?<=[.!?\\x{2026}])\\s+"));
    for (const QString &line : text.split(QLatin1Char('\n'), Qt::SkipEmptyParts)) {
        for (QString s : line.split(end, Qt::SkipEmptyParts)) {
            s = s.trimmed();
            while (s.size() > kMaxPiece) {
                // Cut long runs at the last comma, else the last space, before the limit.
                int cut = s.lastIndexOf(QStringLiteral(", "), kMaxPiece - 1);
                if (cut > 0)
                    cut += 1;
                else
                    cut = s.lastIndexOf(QLatin1Char(' '), kMaxPiece - 1);
                if (cut <= 0)
                    cut = kMaxPiece;
                out.append(s.left(cut).trimmed());
                s = s.mid(cut).trimmed();
            }
            if (!s.isEmpty())
                out.append(s);
        }
    }
    return out;
}

QHash<QString, QString> ReadAloud::hashes(const QByteArray &text)
{
    QHash<QString, QString> out;
    static const QRegularExpression line(QStringLiteral("^([0-9a-f]{64})  ([A-Za-z0-9._-]{1,128})$"));
    for (const QByteArray &raw : text.split('\n')) {
        const QString l = QString::fromUtf8(raw).trimmed();
        if (l.isEmpty() || l.startsWith(QLatin1Char('#')))
            continue;
        const QRegularExpressionMatch m = line.match(l);
        if (m.hasMatch())
            out.insert(m.captured(2), m.captured(1));
    }
    return out;
}

QString ReadAloud::fileFor(const QString &voice)
{
    return voice == QLatin1String("m") ? QStringLiteral("voice-male-1.tar") : QStringLiteral("voice-female-1.tar");
}

bool ReadAloud::safeName(const QString &name)
{
    if (name.isEmpty() || name.size() > 200 || name.startsWith(QLatin1Char('/')) || name.contains(QLatin1Char('\\'))
        || name.contains(QLatin1Char(':')) || name.contains(QChar(0)))
        return false;
    const QStringList parts = name.split(QLatin1Char('/'), Qt::SkipEmptyParts);
    if (parts.isEmpty())
        return false;
    for (const QString &p : parts)
        if (p == QLatin1String("..") || p == QLatin1String("."))
            return false;
    static const QStringList top = {QStringLiteral("model.onnx"), QStringLiteral("tokens.txt"), QStringLiteral("MODEL_CARD"),
                                    QStringLiteral("natural-voices.txt"), QStringLiteral("espeak-ng-data")};
    return top.contains(parts.first()) && (parts.size() == 1 || parts.first() == QLatin1String("espeak-ng-data"));
}

bool ReadAloud::unpack(const QString &tarPath, const QString &dir, QString *why)
{
    QFile f(tarPath);
    if (!f.open(QIODevice::ReadOnly)) {
        if (why) *why = QStringLiteral("can't open it");
        return false;
    }
    if (!QDir().mkpath(dir)) {
        if (why) *why = QStringLiteral("can't make its folder");
        return false;
    }
    const auto field = [](const QByteArray &h, int at, int len) {
        QByteArray v = h.mid(at, len);
        const int z = v.indexOf('\0');
        return z >= 0 ? v.left(z) : v;
    };
    int files = 0;
    while (true) {
        const QByteArray h = f.read(512);
        if (h.size() < 512) {
            if (why) *why = QStringLiteral("it ends early");
            return false;
        }
        if (h.count('\0') == 512)
            break;   // the end
        // The header's checksum: the sum of its bytes with the checksum field read as spaces.
        bool ok = false;
        const qint64 sum = field(h, 148, 8).trimmed().toLongLong(&ok, 8);
        qint64 have = 0;
        for (int i = 0; i < 512; ++i)
            have += (i >= 148 && i < 156) ? 0x20 : quint8(h.at(i));
        if (!ok || sum != have) {
            if (why) *why = QStringLiteral("a damaged header");
            return false;
        }
        const qint64 size = field(h, 124, 12).trimmed().toLongLong(&ok, 8);
        if (!ok || size < 0 || size > kMaxFile) {
            if (why) *why = QStringLiteral("a file too big");
            return false;
        }
        const char type = h.at(156);
        QString name = QString::fromUtf8(field(h, 0, 100));
        const QByteArray prefix = field(h, 345, 155);
        if (h.mid(257, 5) == "ustar" && !prefix.isEmpty())
            name = QString::fromUtf8(prefix) + QLatin1Char('/') + name;
        while (name.endsWith(QLatin1Char('/')))
            name.chop(1);
        if (!safeName(name)) {
            if (why) *why = QStringLiteral("an unexpected name");
            return false;
        }
        const QString target = dir + QLatin1Char('/') + name;
        if (type == '5') {
            if (!QDir().mkpath(target)) {
                if (why) *why = QStringLiteral("can't make a folder");
                return false;
            }
        } else if (type == '0' || type == '\0') {
            QDir().mkpath(QFileInfo(target).absolutePath());
            QFile out(target);
            if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
                if (why) *why = QStringLiteral("can't write a file");
                return false;
            }
            qint64 left = size;
            while (left > 0) {
                const QByteArray chunk = f.read(qMin<qint64>(left, 1 << 20));
                if (chunk.isEmpty() || out.write(chunk) != chunk.size()) {
                    if (why) *why = QStringLiteral("it ends early");
                    return false;
                }
                left -= chunk.size();
            }
            out.close();
            ++files;
            const qint64 pad = (512 - size % 512) % 512;
            if (pad && f.read(pad).size() != pad) {
                if (why) *why = QStringLiteral("it ends early");
                return false;
            }
        } else {
            // Links, devices and anything else: never in a voice.
            if (why) *why = QStringLiteral("something that isn't a file");
            return false;
        }
    }
    if (files == 0 || !QFileInfo::exists(dir + QStringLiteral("/model.onnx")) || !QFileInfo::exists(dir + QStringLiteral("/tokens.txt"))) {
        if (why) *why = QStringLiteral("parts of the voice are missing");
        return false;
    }
    return true;
}

// ------------------------------------------------------------------ the self-test

namespace {

QByteArray tarEntry(const QByteArray &name, const QByteArray &data, char type)
{
    QByteArray h(512, '\0');
    std::memcpy(h.data(), name.constData(), qMin<qsizetype>(name.size(), 100));
    std::memcpy(h.data() + 100, "0000644", 7);
    std::memcpy(h.data() + 108, "0000000", 7);
    std::memcpy(h.data() + 116, "0000000", 7);
    const QByteArray size = QByteArray::number(data.size(), 8).rightJustified(11, '0');
    std::memcpy(h.data() + 124, size.constData(), 11);
    std::memcpy(h.data() + 136, "00000000000", 11);
    h[156] = type;
    std::memcpy(h.data() + 257, "ustar\0" "00", 8);
    std::memset(h.data() + 148, ' ', 8);
    qint64 sum = 0;
    for (int i = 0; i < 512; ++i)
        sum += quint8(h.at(i));
    const QByteArray s = QByteArray::number(sum, 8).rightJustified(6, '0');
    std::memcpy(h.data() + 148, s.constData(), 6);
    h[154] = '\0';
    h[155] = ' ';
    QByteArray body = data;
    body.append(QByteArray((512 - data.size() % 512) % 512, '\0'));
    return h + body;
}

} // namespace

bool ReadAloud::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("read aloud self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        if (!ok) all = false;
    };
    // Plain text, the same as Muninn's ReadAloudTextTest.
    check("links say link", plain(QStringLiteral("See https://example.com/a?b=1 now")) == QLatin1String("See link now"));
    check("marks and @ go", plain(QStringLiteral("*Hi* @ava, ~so~ `good` _day_")) == QLatin1String("Hi ava, so good day"));
    check("emoji go", plain(QStringLiteral("Dinner \U0001F355\U0001F44D\U0001F3FD ready ❤️")) == QLatin1String("Dinner ready"));
    check("lines stay, spaces tidied", plain(QStringLiteral("  one   two \n\n > three ")) == QLatin1String("one two\nthree"));
    check("only emoji is nothing", plain(QStringLiteral("\U0001F600 \U0001F600")).isEmpty());
    // Untrusted text: no control characters or markup, no endless words, a length cap; the engine gets plain Latin.
    check("controls and markup go", plain(QStringLiteral("a\u0007b<speak>c</speak>\u202Ed")) == QLatin1String("a b speak c /speak d"));
    check("long words are cut", plain(QString(100, QLatin1Char('x'))) == QString(40, QLatin1Char('x')) + QLatin1Char(' ') + QString(40, QLatin1Char('x')) + QLatin1Char(' ') + QString(20, QLatin1Char('x')));
    check("long text is capped", plain(QStringLiteral("ab ").repeated(5000)).size() <= kMaxText);
    check("phonemes and SSML never reach the engine", forEngine(QStringLiteral("[[h@'loU]] <speak>Hi</speak> \u00C5sa & co \u2019s")) == QStringLiteral("h 'loU speak Hi /speak \u00C5sa and co 's"));
    check("only Latin letters and digits for the engine", forEngine(QStringLiteral("\u041F\u0440\u0438 \u65E5\u672C 123")) == QLatin1String("123"));
    check("at most two accents on a letter", forEngine(QStringLiteral("a\u030A\u0301\u0300\u0302b")) == QStringLiteral("a\u030A\u0301b"));
    const QStringList p = pieces(QStringLiteral("Hello there. How are you? Fine!\nNew line"));
    check("sentences and lines", p == QStringList{QStringLiteral("Hello there."), QStringLiteral("How are you?"), QStringLiteral("Fine!"), QStringLiteral("New line")});
    const QString longText = QString(300, QLatin1Char('a')) + QStringLiteral(", ") + QString(300, QLatin1Char('b'));
    const QStringList lp = pieces(longText);
    check("long pieces cut at a comma", lp.size() == 2 && lp.first().size() == 301 && lp.last() == QString(300, QLatin1Char('b')));
    bool short_ = true;
    for (const QString &x : pieces(QString(1000, QLatin1Char('x'))))
        short_ = short_ && x.size() <= kMaxPiece;
    check("no piece over the limit", short_);
    // code/shared/voices.sha256.
    const QString a64 = QString(64, QLatin1Char('a'));
    const QHash<QString, QString> h = hashes(QStringLiteral("# comment\n%1  voice-female-1.tar\nnothex  voice-male-1.tar\n%2 one-space.tar\n")
                                                 .arg(a64, QString(64, QLatin1Char('b'))).toUtf8());
    check("hashes read", h.size() == 1 && h.value(QStringLiteral("voice-female-1.tar")) == a64);
    check("voice files", fileFor(QStringLiteral("f")) == QLatin1String("voice-female-1.tar") && fileFor(QStringLiteral("m")) == QLatin1String("voice-male-1.tar")
                             && fileFor(QStringLiteral("x")) == QLatin1String("voice-female-1.tar"));
    QFile built(QStringLiteral(":/huginn/voices.sha256"));
    check("voices.sha256 is built in", built.open(QIODevice::ReadOnly));
    // Names in a voice's tar.
    check("names allowed", safeName(QStringLiteral("model.onnx")) && safeName(QStringLiteral("espeak-ng-data/lang/gmw/en")) && safeName(QStringLiteral("espeak-ng-data")));
    check("names refused", !safeName(QStringLiteral("../x")) && !safeName(QStringLiteral("/etc/passwd")) && !safeName(QStringLiteral("espeak-ng-data/../../x"))
                               && !safeName(QStringLiteral("other.txt")) && !safeName(QStringLiteral("model.onnx/x")) && !safeName(QStringLiteral("C:x"))
                               && !safeName(QStringLiteral("espeak-ng-data\\x")) && !safeName(QString()));
    // Unpacking: a good voice, and ones with a link or a path out.
    QDir tmp(QDir::tempPath() + QStringLiteral("/huginn-readaloud-selftest-") + QString::number(QCoreApplication::applicationPid()));
    tmp.removeRecursively();
    QDir().mkpath(tmp.path());
    const auto writeTar = [&tmp](const QString &n, const QByteArray &bytes) {
        QFile t(tmp.filePath(n));
        if (t.open(QIODevice::WriteOnly)) t.write(bytes);
        return tmp.filePath(n);
    };
    const QByteArray end(1024, '\0');
    const QString good = writeTar(QStringLiteral("good.tar"), tarEntry("espeak-ng-data", {}, '5') + tarEntry("espeak-ng-data/phontab", "p", '0')
                                                              + tarEntry("model.onnx", QByteArray(700, 'm'), '0') + tarEntry("tokens.txt", "a 1\n", '0') + end);
    QString why;
    const bool goodOk = unpack(good, tmp.filePath(QStringLiteral("good")), &why);
    QFile model(tmp.filePath(QStringLiteral("good/model.onnx")));
    check("a voice unpacks", goodOk && model.open(QIODevice::ReadOnly) && model.readAll() == QByteArray(700, 'm'));
    const QString evil = writeTar(QStringLiteral("evil.tar"), tarEntry("model.onnx", "m", '0') + tarEntry("../evil.txt", "x", '0') + end);
    check("a path out is refused", !unpack(evil, tmp.filePath(QStringLiteral("evil")), &why) && !QFileInfo::exists(tmp.filePath(QStringLiteral("evil.txt"))));
    const QString linked = writeTar(QStringLiteral("link.tar"), tarEntry("tokens.txt", {}, '2') + end);
    check("a link is refused", !unpack(linked, tmp.filePath(QStringLiteral("link")), &why));
    QByteArray broken = tarEntry("model.onnx", "m", '0') + end;
    broken[3] = 'X';
    check("a damaged header is refused", !unpack(writeTar(QStringLiteral("broken.tar"), broken), tmp.filePath(QStringLiteral("broken")), &why));
    tmp.removeRecursively();
    // The bundled engine: the CI builds ship it, so it must load with every function used.
    QString engineWhy;
    const bool loads = VoiceEngine::libraryLoads(&engineWhy);
    if (!loads)
        std::printf("read aloud self-test: engine: %s\n", qPrintable(engineWhy));
    check("the speech engine loads", loads);
    // A WAV header the media player reads.
    const float tone[4] = {0.0f, 0.5f, -0.5f, 1.5f};
    const QByteArray w = wavOf(tone, 4, 22050);
    check("speech as WAV", w.size() == 44 + 8 && w.startsWith("RIFF") && w.mid(8, 4) == "WAVE" && qFromLittleEndian<qint16>(w.constData() + 44 + 6) == 32767);
    return all;
}
