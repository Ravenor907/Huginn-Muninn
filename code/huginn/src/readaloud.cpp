#include "readaloud.h"

#include "dailyverse.h"
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
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
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

    // Opens the engine and [dir]'s voice (once, again when the tuning changes), then speaks [phrases] one at a time
    // while [token] is current, each followed by its pause in silence (scaled by the cadence).
    void say(quint64 token, const QString &dir, float noise, float width, const QList<ReadAloud::Phrase> &phrases, ReadAloud *to)
    {
        m_idle->stop();
        QString why;
        if (!open(dir, noise, width, &why)) {
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
        for (const ReadAloud::Phrase &p : phrases) {
            if (m_token->load() != token)
                break;
            // Only plain letters, digits and simple punctuation reach the engine (ReadAloud::forEngine).
            const QString clean = ReadAloud::forEngine(p.text);
            if (clean.isEmpty())
                continue;
            const QByteArray utf8 = clean.toUtf8();
            // The speed picked (read for each phrase, so a move of the slider is heard in the next one).
            const SherpaOnnxGeneratedAudio *a = m_generate(m_tts, utf8.constData(), 0, ReadAloud::engineSpeed(to->m_speedNow.load()));
            if (!a)
                continue;
            QByteArray wav;
            if (a->n > 0 && a->sample_rate > 0) {
                // The phrase, then its pause as silence (the cadence read now, so a move is heard in the next pause).
                const qint64 gap = qint64(ReadAloud::pauseFor(p.pauseMs, to->m_cadenceNow.load())) * a->sample_rate / 1000;
                std::vector<float> samples(a->samples, a->samples + a->n);
                samples.resize(samples.size() + size_t(qBound(qint64(0), gap, qint64(10) * a->sample_rate)), 0.0f);
                wav = wavOf(samples.data(), int(samples.size()), a->sample_rate);
            }
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

    bool open(const QString &dir, float noise, float width, QString *why)
    {
        if (m_tts && m_dir == dir && m_noise == noise && m_width == width)
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
        // The voice's tuning (Labs › Voice tuning; preset A, 0.667 and 0.8, until another is used).
        c.model.vits.noise_scale = noise;
        c.model.vits.noise_scale_w = width;
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
        m_noise = noise;
        m_width = width;
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
    float m_noise = 0.0f;
    float m_width = 0.0f;
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
    // The computer's own voice: the pause between two phrases.
    m_devGap.setSingleShot(true);
    connect(&m_devGap, &QTimer::timeout, this, &ReadAloud::sayNextDevice);
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
    m_speedNow.store(speed());
    m_cadenceNow.store(cadence());
    if (useDevice())
        m_status = QStringLiteral("The computer's own voice reads");
    else if (m_engineBroken)
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
    return m_engineThere && !m_engineBroken && (ready() || m_hashes.contains(fileFor(naturalVoice())));
}

bool ReadAloud::available() const
{
    if (!isOn())
        return false;
    return (natural() && !useDevice()) || deviceCan();
}

bool ReadAloud::isOn() const { return Settings::store().value(QStringLiteral("readAloud/on"), true).toBool(); }

void ReadAloud::setOn(bool on)
{
    if (on == isOn())
        return;
    Settings::store().setValue(QStringLiteral("readAloud/on"), on);
    if (!on)
        stop();
    Q_EMIT changed();
}

bool ReadAloud::useDevice() const
{
    return Settings::store().value(QStringLiteral("readAloud/voice"), QStringLiteral("f")).toString() == QLatin1String("d");
}

double ReadAloud::speedOf(double pct)
{
    if (std::isnan(pct) || std::isinf(pct))
        return kSpeedDefault;
    return std::clamp(pct, kSpeedMin, kSpeedMax);
}

double ReadAloud::speedFrom142(double pct)
{
    // 80% was 1.42's default (a speed nobody picked): the new usual pace. Anything else was picked, so it stays.
    if (!std::isnan(pct) && std::abs(pct - 80.0) < 0.001)
        return kSpeedDefault;
    return speedOf(pct);
}

double ReadAloud::cadenceOf(double pct)
{
    if (std::isnan(pct) || std::isinf(pct))
        return kCadenceDefault;
    return std::clamp(pct, kCadenceMin, kCadenceMax);
}

int ReadAloud::pauseFor(int baseMs, double pct)
{
    return int(std::lround(double(qMax(0, baseMs)) * cadenceOf(pct) / 100.0));
}

QString ReadAloud::speedText(double pct) { return QString::number(qRound(speedOf(pct))) + QLatin1Char('%'); }
float ReadAloud::engineSpeed(double pct) { return float(speedOf(pct) / 100.0); }
double ReadAloud::qtRate(double pct) { return (speedOf(pct) - 100.0) / 50.0; }

double ReadAloud::speed() const
{
    // 1.43 keeps the speed as "readAloud/pace" (70 to 130). Before that, "readAloud/speed" (50 to 150, 80 the
    // default): 80 reads as 100, anything else is kept, held to the new range. Nothing saved reads 100.
    const QSettings s = Settings::store();
    bool ok = false;
    if (s.contains(QStringLiteral("readAloud/pace"))) {
        const double v = s.value(QStringLiteral("readAloud/pace")).toDouble(&ok);
        return ok ? speedOf(v) : kSpeedDefault;
    }
    if (s.contains(QStringLiteral("readAloud/speed"))) {
        const double v = s.value(QStringLiteral("readAloud/speed")).toDouble(&ok);
        return ok ? speedFrom142(v) : kSpeedDefault;
    }
    return kSpeedDefault;
}

double ReadAloud::cadence() const
{
    bool ok = false;
    const double v = Settings::store().value(QStringLiteral("readAloud/cadence"), kCadenceDefault).toDouble(&ok);
    return ok ? cadenceOf(v) : kCadenceDefault;
}

void ReadAloud::setCadence(double pct)
{
    const double v = cadenceOf(pct);
    Settings::store().setValue(QStringLiteral("readAloud/cadence"), v);
    m_cadenceNow.store(v);
    Q_EMIT speedChanged();
    Q_EMIT changed();
}

void ReadAloud::previewCadence(double pct)
{
    m_cadenceNow.store(cadenceOf(pct));
}

void ReadAloud::setSpeed(double pct)
{
    const double v = speedOf(pct);
    Settings::store().setValue(QStringLiteral("readAloud/pace"), v);
    m_speedNow.store(v);
    applyDeviceRate();
    Q_EMIT speedChanged();
    Q_EMIT changed();
}

void ReadAloud::previewSpeed(double pct)
{
    m_speedNow.store(speedOf(pct));
    applyDeviceRate();
}

void ReadAloud::applyDeviceRate()
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (m_device)
        m_device->setRate(qtRate(m_speedNow.load()));
#endif
}

void ReadAloud::sample()
{
    speak(QStringLiteral("This is how your messages sound when they're read aloud. Each pause, like this one, follows the cadence."));
}

// ------------------------------------------------------------------ Labs › Voice tuning

ReadAloud::Tune ReadAloud::tuneFor(const QString &id)
{
    for (const Tune &t : kTunes)
        if (id == QLatin1String(t.id))
            return t;
    return kTunes[0];
}

QString ReadAloud::tuneOf(const QString &voice) const
{
    const QString v = voice == QLatin1String("m") ? QStringLiteral("m") : QStringLiteral("f");
    return QString::fromLatin1(tuneFor(Settings::store().value(QStringLiteral("readAloud/tune/") + v).toString()).id);
}

QVariantList ReadAloud::tuningPresets() const
{
    QVariantList out;
    for (const Tune &t : kTunes)
        out.push_back(QVariantMap{{QStringLiteral("id"), QString::fromLatin1(t.id)},
                                  {QStringLiteral("noise"), double(t.noise)}, {QStringLiteral("width"), double(t.width)}});
    return out;
}

QString ReadAloud::tuningText(const QString &id) const
{
    const Tune t = tuneFor(id);
    return QStringLiteral("%1: noise %2, width %3").arg(QString::fromLatin1(t.id), QString::number(double(t.noise), 'g', 3),
                                                       QString::number(double(t.width), 'g', 3));
}

QString ReadAloud::tryTuning(const QString &id)
{
    if (useDevice())
        return QStringLiteral("Pick Female or Male in Read aloud first.");
    if (!natural())
        return QStringLiteral("Natural voices aren't ready yet.");
    if (!ready()) {
        prepare();
        return QStringLiteral("The natural voice isn't downloaded yet. ") + m_status;
    }
    // Hávamál stanza 3 and what it means, the same text every time.
    QFile f(QStringLiteral(":/huginn/havamal.json"));
    const QList<DailyVerse::Verse> verses = f.open(QIODevice::ReadOnly) ? DailyVerse::parse(f.readAll()) : QList<DailyVerse::Verse>();
    for (const DailyVerse::Verse &v : verses) {
        if (v.n != 3)
            continue;
        speakPhrases(versePhrases(v.verse, v.means, true), versePhrases(v.verse, v.means, false), QString::fromLatin1(tuneFor(id).id));
        return QString();
    }
    return QStringLiteral("Can't read the verses.");
}

void ReadAloud::useTuning(const QString &id)
{
    const QString pick = QString::fromLatin1(tuneFor(id).id);
    if (pick == tuneOf(naturalVoice()))
        return;
    Settings::store().setValue(QStringLiteral("readAloud/tune/") + naturalVoice(), pick);
    Q_EMIT tuningChanged();
}

QString ReadAloud::summary() const
{
    if (!isOn())
        return QStringLiteral("Off");
    const QString name = useDevice() || !natural() ? QStringLiteral("Device")
        : naturalVoice() == QLatin1String("m") ? QStringLiteral("Male") : QStringLiteral("Female");
    const QString pace = speedText(speed());
    if (name != QLatin1String("Device") && ready() && pace == QLatin1String("100%"))
        return name + QStringLiteral(" voice, ready");
    return name + QStringLiteral(" voice, ") + pace;
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

QString ReadAloud::naturalVoice() const
{
    return Settings::store().value(QStringLiteral("readAloud/voice"), QStringLiteral("f")).toString() == QLatin1String("m")
        ? QStringLiteral("m") : QStringLiteral("f");
}

QString ReadAloud::voice() const
{
    return useDevice() ? QStringLiteral("d") : naturalVoice();
}

void ReadAloud::setVoice(const QString &v)
{
    const QString pick = v == QLatin1String("m") ? QStringLiteral("m") : v == QLatin1String("d") ? QStringLiteral("d") : QStringLiteral("f");
    if (pick == voice())
        return;
    Settings::store().setValue(QStringLiteral("readAloud/voice"), pick);
    Q_EMIT tuningChanged();
    stop();
    // The other voice's download (if one ran) gives way to this one.
    m_downloading.clear();
    if (m_reply)
        m_reply->abort();
    if (useDevice()) {
        // The computer's own voice: nothing downloads (the voice kept stays until Delete voices).
        setStatus(QStringLiteral("The computer's own voice reads"));
    } else if (ready()) {
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
    const QString d = voiceDir(naturalVoice());
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
    speakPhrases(phrases(text, true), phrases(text, false));
}

QList<ReadAloud::Phrase> ReadAloud::versePhrases(const QString &verse, const QString &means, bool natural)
{
    // The verse ends with one full stop (never two), then the 900 ms gap; "What it means"; then the meaning, a pause
    // at each of its lines.
    QString v = verse.trimmed();
    static const QRegularExpression tail(QStringLiteral("[\\s.,;:\u2026]+$"));
    v.remove(tail);
    if (!v.isEmpty() && !v.endsWith(QLatin1Char('?')) && !v.endsWith(QLatin1Char('!')))
        v.append(QLatin1Char('.'));
    QList<Phrase> out = phrases(v, natural);
    if (out.isEmpty())
        return phrases(means, natural);
    out.last().pauseMs = kPauseMeans;
    out.append(Phrase{QStringLiteral("What it means."), kPauseStop});
    const QList<Phrase> m = phrases(means, natural);
    if (m.isEmpty()) {
        out.removeLast();
        out.last().pauseMs = 0;
        return out;
    }
    out.append(m);
    return out;
}

void ReadAloud::speakVerse(const QString &verse, const QString &means)
{
    speakPhrases(versePhrases(verse, means, true), versePhrases(verse, means, false));
}

void ReadAloud::speakAll(const QStringList &texts)
{
    QList<Phrase> nat, dev;
    for (const QString &t : texts) {
        const QList<Phrase> n = phrases(t, true), d = phrases(t, false);
        if (n.isEmpty() || d.isEmpty())
            continue;
        if (!nat.isEmpty()) nat.last().pauseMs = kPauseMessage;
        if (!dev.isEmpty()) dev.last().pauseMs = kPauseMessage;
        nat.append(n);
        dev.append(d);
    }
    speakPhrases(nat, dev);
}

void ReadAloud::speakPhrases(const QList<Phrase> &nat, const QList<Phrase> &dev, const QString &tune)
{
    stop();
    // Never while the lock is up, the screen lock shows or a call is on (main.cpp says which), whatever asked.
    if (m_blocked && m_blocked())
        return;
    m_lastParts = dev;
    if (dev.isEmpty() && nat.isEmpty())
        return;
    // Text with nothing the engine reads (another script only) goes to the computer's own voice.
    bool engineReads = false;
    for (const Phrase &p : nat)
        engineReads = engineReads || !forEngine(p.text).isEmpty();
    if (!useDevice() && natural() && ready() && !m_deleting && engineReads) {
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
        const QString dir = voiceDir(naturalVoice());
        const Tune t = tuneFor(tune.isEmpty() ? tuneOf(naturalVoice()) : tune);
        const float noise = t.noise, width = t.width;
        VoiceEngine *engine = m_engine;
        QMetaObject::invokeMethod(engine, [engine, token, dir, noise, width, nat, this] { engine->say(token, dir, noise, width, nat, this); }, Qt::QueuedConnection);
        return;
    }
    // Not ready yet: the download starts (on the network rules), and the computer's own voice reads meanwhile.
    if (!useDevice())
        prepare();
    speakDevice(dev);
}

void ReadAloud::speakDevice(const QList<Phrase> &parts)
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (deviceCan())
        loadDeviceVoice();
    if (m_device && m_deviceOk && !parts.isEmpty()) {
        // One phrase at a time, with Read aloud's pause (scaled by the cadence) between them.
        m_devQueue = parts;
        m_devGap.stop();
        m_speaking = true;
        Q_EMIT changed();
        sayNextDevice();
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

void ReadAloud::sayNextDevice()
{
#ifdef HUGINN_HAVE_QTSPEECH
    if (!m_device || m_devQueue.isEmpty()) {
        m_devSaying = false;
        if (m_speaking && m_queue.isEmpty() && m_pending == 0) {
            m_speaking = false;
            Q_EMIT changed();
        }
        return;
    }
    const Phrase p = m_devQueue.takeFirst();
    m_devPause = p.pauseMs;
    // The speed read for each phrase, so a move of the slider is heard in the next one.
    applyDeviceRate();
    m_devSaying = true;
    m_devStarted = false;
    m_device->say(p.text);
#endif
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
        if (!ok) {
            // A phrase failed: the rest isn't said.
            m_devQueue.clear();
            m_devGap.stop();
            m_devSaying = false;
        }
        // The phrase asked for has begun (a Ready left over from the one stopped before it never counts as its end).
        if (s == QTextToSpeech::Speaking && m_devSaying)
            m_devStarted = true;
        if (s == QTextToSpeech::Ready && m_devSaying && m_devStarted) {
            // A phrase is said: the next one after its pause.
            m_devSaying = false;
            m_devStarted = false;
            if (!m_devQueue.isEmpty()) {
                m_devGap.start(pauseFor(m_devPause, m_cadenceNow.load()));
                return;
            }
        }
        if (s == QTextToSpeech::Ready && m_speaking && m_queue.isEmpty() && m_pending == 0 && m_devQueue.isEmpty() && !m_devGap.isActive()) {
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
    m_devQueue.clear();
    m_devGap.stop();
    m_devSaying = false;
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
    if (useDevice()) {
        setStatus(QStringLiteral("The computer's own voice reads"));
        return;
    }
    if (ready() || m_reply)
        return;
    if (!natural()) {
        // Nothing to download yet (or the engine can't run here): say so, never a download that can't start.
        setStatus(m_engineBroken ? m_status : notReadyYet(deviceCan()));
        return;
    }
    m_downloading = naturalVoice();
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
        // Both natural voices stay, so a switch never downloads anything again; only leftovers go. A download under way
        // (its part and its unpack folder) stays.
        const QStringList keep{folderFor(QStringLiteral("f")), folderFor(QStringLiteral("m"))};
        const QDir d(voicesDir());
        for (const QString &e : d.entryList(QDir::Dirs | QDir::Files | QDir::NoDotAndDotDot)) {
            if (keep.contains(e) || (!m_downloading.isEmpty() && (e.startsWith(fileFor(m_downloading)) || e.startsWith(folderFor(m_downloading)))))
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
        setStatus(useDevice() ? QStringLiteral("The computer's own voice reads")
                  : natural() ? QStringLiteral("Deleted. Downloads again the next time something is read aloud") : notReadyYet(deviceCan()));
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

// ------------------------------------------------------------------ 1.43: the text before the voice

namespace {

// [s] with every match of [re] replaced by what [f] makes of it.
QString replaceEach(const QString &s, const QRegularExpression &re, const std::function<QString(const QRegularExpressionMatch &)> &f)
{
    QString out;
    qsizetype at = 0;
    QRegularExpressionMatchIterator it = re.globalMatch(s);
    while (it.hasNext()) {
        const QRegularExpressionMatch m = it.next();
        out += s.mid(at, m.capturedStart() - at);
        out += f(m);
        at = m.capturedEnd();
    }
    out += s.mid(at);
    return out;
}

// "forty-two" to "forty-second", "twelve" to "twelfth".
QString ordinalOf(const QString &words)
{
    static const QHash<QString, QString> odd = {
        {QStringLiteral("one"), QStringLiteral("first")}, {QStringLiteral("two"), QStringLiteral("second")},
        {QStringLiteral("three"), QStringLiteral("third")}, {QStringLiteral("five"), QStringLiteral("fifth")},
        {QStringLiteral("eight"), QStringLiteral("eighth")}, {QStringLiteral("nine"), QStringLiteral("ninth")},
        {QStringLiteral("twelve"), QStringLiteral("twelfth")}};
    qsizetype cut = qMax(words.lastIndexOf(QLatin1Char(' ')), words.lastIndexOf(QLatin1Char('-'))) + 1;
    const QString last = words.mid(cut);
    if (odd.contains(last))
        return words.left(cut) + odd.value(last);
    if (last.endsWith(QLatin1Char('y')))
        return words.left(cut) + last.chopped(1) + QStringLiteral("ieth");
    return words + QStringLiteral("th");
}

// Each digit on its own: "zero zero seven".
QString digitsOf(const QString &digits)
{
    QStringList out;
    for (const QChar c : digits)
        if (c.isDigit())
            out << ReadAloud::numberWords(c.digitValue());
    return out.join(QLatin1Char(' '));
}

// A whole number as it's said: 1100 to 1999 like a year ("nineteen twenty-three", "fifteen hundred"), long ones and
// ones with a leading zero digit by digit.
QString wholeOf(const QString &raw)
{
    QString d = raw;
    d.remove(QLatin1Char(','));
    if (d.size() > 9 || (d.size() > 1 && d.startsWith(QLatin1Char('0'))))
        return digitsOf(d);
    const qint64 n = d.toLongLong();
    if (!raw.contains(QLatin1Char(',')) && d.size() == 4 && n >= 1100 && n <= 1999) {
        const qint64 hi = n / 100, lo = n % 100;
        return ReadAloud::numberWords(hi) + (lo == 0 ? QStringLiteral(" hundred")
                                            : lo < 10 ? QStringLiteral(" oh ") + ReadAloud::numberWords(lo)
                                                      : QLatin1Char(' ') + ReadAloud::numberWords(lo));
    }
    return ReadAloud::numberWords(n);
}

// "am" or "pm" as it's said.
QString meridiem(const QString &letter)
{
    return letter.compare(QLatin1String("p"), Qt::CaseInsensitive) == 0 ? QStringLiteral("pee em") : QStringLiteral("ay em");
}

// A lone emoji's name (the emoji without its variation selector or skin tone).
const QHash<QString, QString> &emojiNames()
{
    static const QHash<QString, QString> names = [] {
        const struct { char32_t c; const char *name; } list[] = {
            {0x263A, "smiling face"}, {0x1F60A, "smiling face with smiling eyes"}, {0x1F642, "slightly smiling face"},
            {0x1F600, "grinning face"}, {0x1F603, "grinning face with big eyes"}, {0x1F604, "grinning face with smiling eyes"},
            {0x1F601, "beaming face"}, {0x1F605, "grinning face with sweat"}, {0x1F602, "face with tears of joy"},
            {0x1F923, "rolling on the floor laughing"}, {0x1F609, "winking face"}, {0x1F60D, "smiling face with heart eyes"},
            {0x1F970, "smiling face with hearts"}, {0x1F618, "face blowing a kiss"}, {0x1F60B, "face savoring food"},
            {0x1F60E, "smiling face with sunglasses"}, {0x1F917, "hugging face"}, {0x1F914, "thinking face"},
            {0x1F610, "neutral face"}, {0x1F644, "face with rolling eyes"}, {0x1F62C, "grimacing face"},
            {0x1F634, "sleeping face"}, {0x1F622, "crying face"}, {0x1F62D, "loudly crying face"},
            {0x1F62E, "face with open mouth"}, {0x1F631, "face screaming in fear"}, {0x1F620, "angry face"},
            {0x1F621, "angry face"}, {0x1F973, "partying face"}, {0x1F937, "person shrugging"},
            {0x1F926, "person facepalming"}, {0x2764, "red heart"}, {0x1F494, "broken heart"}, {0x1F44D, "thumbs up"},
            {0x1F44E, "thumbs down"}, {0x1F44F, "clapping hands"}, {0x1F64F, "folded hands"}, {0x1F44B, "waving hand"},
            {0x1F44C, "OK hand"}, {0x1F4AA, "flexed biceps"}, {0x1F389, "party popper"}, {0x1F525, "fire"},
            {0x1F4AF, "hundred points"}, {0x2705, "check mark"}, {0x2B50, "star"}, {0x1F480, "skull"},
            {0x1F43A, "wolf"}, {0x2600, "sun"}, {0x1F319, "crescent moon"}, {0x2744, "snowflake"},
            {0x1F355, "pizza"}, {0x2615, "hot drink"}, {0x1F382, "birthday cake"}};
        QHash<QString, QString> h;
        for (const auto &e : list)
            h.insert(QString::fromUcs4(&e.c, 1), QString::fromLatin1(e.name));
        return h;
    }();
    return names;
}

} // namespace

QString ReadAloud::numberWords(qint64 n)
{
    static const char *const ones[] = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                                       "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    static const char *const tens[] = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    if (n < 0)
        return QStringLiteral("minus ") + numberWords(-n);
    if (n > 999999999)
        return digitsOf(QString::number(n));
    if (n < 20)
        return QString::fromLatin1(ones[n]);
    if (n < 100)
        return QString::fromLatin1(tens[n / 10]) + (n % 10 ? QLatin1Char('-') + QString::fromLatin1(ones[n % 10]) : QString());
    if (n < 1000)
        return QString::fromLatin1(ones[n / 100]) + QStringLiteral(" hundred") + (n % 100 ? QLatin1Char(' ') + numberWords(n % 100) : QString());
    if (n < 1000000)
        return numberWords(n / 1000) + QStringLiteral(" thousand") + (n % 1000 ? QLatin1Char(' ') + numberWords(n % 1000) : QString());
    return numberWords(n / 1000000) + QStringLiteral(" million") + (n % 1000000 ? QLatin1Char(' ') + numberWords(n % 1000000) : QString());
}

QString ReadAloud::tidy(const QString &text)
{
    QString t = text;
    t.replace(QChar(0x2018), QLatin1Char('\'')).replace(QChar(0x2019), QLatin1Char('\''));
    t.replace(QChar(0x201C), QLatin1Char('"')).replace(QChar(0x201D), QLatin1Char('"'));
    // Three dots or more are a pause (one ellipsis mark); two are one full stop, never read as "dot".
    static const QRegularExpression three(QStringLiteral("\\.{3,}"));
    t.replace(three, QString(QChar(0x2026)));
    static const QRegularExpression two(QStringLiteral("\\.{2}"));
    t.replace(two, QStringLiteral("."));
    static const QRegularExpression many(QStringLiteral("\u2026(?:[ .]*\u2026)+"));
    t.replace(many, QString(QChar(0x2026)));
    return t;
}

QString ReadAloud::normalize(const QString &line)
{
    QString t = line;
    // Common abbreviations. "etc." keeps its full stop at the end of a sentence; "St." is Saint before a name, else
    // Street.
    static const QRegularExpression doctor(QStringLiteral("\\bDr\\.(?=\\s|$)"));
    t.replace(doctor, QStringLiteral("Doctor"));
    static const QRegularExpression missus(QStringLiteral("\\bMrs\\.(?=\\s|$)"));
    t.replace(missus, QStringLiteral("Missus"));
    static const QRegularExpression mister(QStringLiteral("\\bMr\\.(?=\\s|$)"));
    t.replace(mister, QStringLiteral("Mister"));
    static const QRegularExpression miz(QStringLiteral("\\bMs\\.(?=\\s|$)"));
    t.replace(miz, QStringLiteral("Miz"));
    static const QRegularExpression saint(QStringLiteral("\\bSt\\.(?=\\s+[A-Z])"));
    t.replace(saint, QStringLiteral("Saint"));
    static const QRegularExpression streetEnd(QStringLiteral("\\bSt\\.\\s*$"));
    t.replace(streetEnd, QStringLiteral("Street."));
    static const QRegularExpression street(QStringLiteral("\\bSt\\."));
    t.replace(street, QStringLiteral("Street"));
    static const QRegularExpression etcEnd(QStringLiteral("\\betc\\.(?=\\s*$|\\s+[A-Z])"));
    t.replace(etcEnd, QStringLiteral("et cetera."));
    static const QRegularExpression etc(QStringLiteral("\\betc\\."));
    t.replace(etc, QStringLiteral("et cetera"));
    static const QRegularExpression eg(QStringLiteral("\\be\\.g\\.(?=\\s|,|$)"), QRegularExpression::CaseInsensitiveOption);
    t.replace(eg, QStringLiteral("for example"));
    static const QRegularExpression ie(QStringLiteral("\\bi\\.e\\.(?=\\s|,|$)"), QRegularExpression::CaseInsensitiveOption);
    t.replace(ie, QStringLiteral("that is"));
    // Texting shorthand (not "Ty", a name).
    static const QHash<QString, QString> shorthand = {
        {QStringLiteral("lol"), QStringLiteral("laughing out loud")}, {QStringLiteral("brb"), QStringLiteral("be right back")},
        {QStringLiteral("idk"), QStringLiteral("I don't know")}, {QStringLiteral("omw"), QStringLiteral("on my way")},
        {QStringLiteral("tbh"), QStringLiteral("to be honest")}, {QStringLiteral("imo"), QStringLiteral("in my opinion")},
        {QStringLiteral("ty"), QStringLiteral("thank you")}, {QStringLiteral("np"), QStringLiteral("no problem")}};
    static const QRegularExpression texting(QStringLiteral("\\b(lol|brb|idk|omw|tbh|imo|ty|np)\\b"), QRegularExpression::CaseInsensitiveOption);
    t = replaceEach(t, texting, [](const QRegularExpressionMatch &m) {
        const QString w = m.captured(1);
        return w == QLatin1String("Ty") ? w : shorthand.value(w.toLower(), w);
    });
    // Times: "3:30" is "three thirty", "3:05" "three oh five", "7:00" "seven o'clock", with "am" or "pm" said.
    static const QRegularExpression time(QStringLiteral("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])(?:\\s?([AaPp])\\.?[Mm]\\b\\.?(?=\\s+[a-z]))?(?:\\s?([AaPp])\\.?[Mm]\\b)?"));
    t = replaceEach(t, time, [](const QRegularExpressionMatch &m) {
        const int h = m.captured(1).toInt(), min = m.captured(2).toInt();
        const QString ap = !m.captured(3).isEmpty() ? m.captured(3) : m.captured(4);
        QString out = numberWords(h == 0 && !ap.isEmpty() ? 12 : h);
        if (min == 0)
            out += ap.isEmpty() ? QStringLiteral(" o'clock") : QString();
        else
            out += (min < 10 ? QStringLiteral(" oh ") : QStringLiteral(" ")) + numberWords(min);
        if (!ap.isEmpty())
            out += QLatin1Char(' ') + meridiem(ap);
        return out;
    });
    // "3pm", "11 a.m."
    static const QRegularExpression hourAp(QStringLiteral("(?<![\\w:])(1[0-2]|0?[1-9])\\s?([AaPp])\\.?[Mm]\\b"));
    t = replaceEach(t, hourAp, [](const QRegularExpressionMatch &m) { return numberWords(m.captured(1).toInt()) + QLatin1Char(' ') + meridiem(m.captured(2)); });
    // Money and percents.
    static const QRegularExpression money(QStringLiteral("\\$(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{2}))?(?![\\d.]\\d)"));
    t = replaceEach(t, money, [](const QRegularExpressionMatch &m) {
        const QString whole = wholeOf(m.captured(1));
        QString out = whole + (whole == QLatin1String("one") ? QStringLiteral(" dollar") : QStringLiteral(" dollars"));
        const int cents = m.captured(2).toInt();
        if (cents > 0)
            out += QStringLiteral(" and ") + numberWords(cents) + (cents == 1 ? QStringLiteral(" cent") : QStringLiteral(" cents"));
        return out;
    });
    static const QRegularExpression percent(QStringLiteral("(?<=\\d)\\s?%"));
    t.replace(percent, QStringLiteral(" percent"));
    // "1st", "22nd", "3rd", "4th".
    static const QRegularExpression ordinal(QStringLiteral("(?<![\\w.])(\\d{1,6})(?:st|nd|rd|th)\\b"), QRegularExpression::CaseInsensitiveOption);
    t = replaceEach(t, ordinal, [](const QRegularExpressionMatch &m) { return ordinalOf(numberWords(m.captured(1).toLongLong())); });
    // "3.5" is "three point five".
    static const QRegularExpression decimal(QStringLiteral("(?<![\\w.,])(\\d+)\\.(\\d+)(?![\\w.]\\w|\\w)"));
    t = replaceEach(t, decimal, [](const QRegularExpressionMatch &m) { return wholeOf(m.captured(1)) + QStringLiteral(" point ") + digitsOf(m.captured(2)); });
    // Whole numbers, with or without thousands commas.
    static const QRegularExpression whole(QStringLiteral("(?<![\\w])(\\d{1,3}(?:,\\d{3})+|\\d+)(?!\\w|,\\d{3})"));
    t = replaceEach(t, whole, [](const QRegularExpressionMatch &m) { return wholeOf(m.captured(1)); });
    return t;
}

QString ReadAloud::emojiName(const QString &text)
{
    // The emoji without variation selectors and skin tones (one thumbs up is one, whatever its tone).
    QString core;
    for (const char32_t c : text.trimmed().toUcs4()) {
        if ((c >= 0xFE00 && c <= 0xFE0F) || (c >= 0x1F3FB && c <= 0x1F3FF))
            continue;
        core += QString::fromUcs4(&c, 1);
    }
    return emojiNames().value(core);
}

QString ReadAloud::respell(const QString &text)
{
    // Said right by the natural voices: each name as it sounds (lower case, so a capital never makes the voice spell
    // it out).
    static const struct { const char16_t *pattern; const char *say; } list[] = {
        {u"Muninn", "MOO-nin"}, {u"Huginn", "HOO-gin"}, {u"H\u00e1vam\u00e1l|Havamal", "HAH-vah-mawl"}, {u"ere", "air"},
        {u"Othin", "OH-thin"}, {u"Loddfafnir", "LODD-fahv-neer"}};
    static const QList<QPair<QRegularExpression, QString>> words = [] {
        QList<QPair<QRegularExpression, QString>> out;
        for (const auto &w : list)
            out.append({QRegularExpression(QStringLiteral("\\b(?:") + QString::fromUtf16(w.pattern) + QStringLiteral(")\\b"),
                                           QRegularExpression::CaseInsensitiveOption | QRegularExpression::UseUnicodePropertiesOption),
                        QString::fromLatin1(w.say).toLower()});
        return out;
    }();
    QString t = text;
    for (const auto &w : words)
        t.replace(w.first, w.second);
    return t;
}

QList<ReadAloud::Phrase> ReadAloud::phrases(const QString &text, bool natural)
{
    QList<Phrase> out;
    // A message that is one emoji says its name.
    const QString name = emojiName(text);
    const QString t = tidy(name.isEmpty() ? plain(text) : name);
    // A sentence ends at . ! ? or the ellipsis, also just inside a closing quote or bracket ("Hi." Then).
    static const QRegularExpression sentenceEnd(QStringLiteral("(?<=[.!?\u2026])\\s+|(?<=[.!?\u2026][\"')])\\s+"));
    static const QRegularExpression ends(QStringLiteral("[.!?\u2026][\"')]?$"));
    static const QRegularExpression ellipsisTail(QStringLiteral("[\\s\u2026]+$"));
    static const QRegularExpression afterEllipsis(QStringLiteral("\u2026(?=\\S)"));
    static const QRegularExpression comma(QStringLiteral("(?<=,)\\s+"));
    static const QRegularExpression longBreaks(QStringLiteral("(?<=[,;])\\s+|\\s+(?=because\\b)"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression wordy(QStringLiteral("[\\p{L}\\p{N}]"), QRegularExpression::UseUnicodePropertiesOption);
    const auto add = [&out](const QString &piece, int pause) {
        // Nothing but marks: its pause goes to the phrase before it.
        if (!piece.contains(wordy)) {
            if (!out.isEmpty())
                out.last().pauseMs = qMax(out.last().pauseMs, pause);
            return;
        }
        // Never longer than kMaxPiece (cut at a comma or a space, as pieces() does).
        const QStringList cut = pieces(piece);
        for (int i = 0; i < cut.size(); ++i)
            out.append(Phrase{cut.at(i), i + 1 < cut.size() ? kPauseComma : pause});
    };
    for (QString line : t.split(QLatin1Char('\n'), Qt::SkipEmptyParts)) {
        line = normalize(line);
        if (natural)
            line = respell(line);
        // "wait...what": the pause splits the words too.
        line.replace(afterEllipsis, QStringLiteral("\u2026 "));
        const qsizetype before = out.size();
        for (QString s : line.split(sentenceEnd, Qt::SkipEmptyParts)) {
            s = s.trimmed();
            if (s.isEmpty())
                continue;
            const int endPause = s.contains(ends) ? kPauseStop : 0;
            // The ellipsis is only a pause: its mark is never read.
            s.remove(ellipsisTail);
            if (s.isEmpty()) {
                add(QString(), endPause);
                continue;
            }
            const bool isLong = s.split(QLatin1Char(' '), Qt::SkipEmptyParts).size() > kLongSentence;
            const QStringList parts = s.split(isLong ? longBreaks : comma, Qt::SkipEmptyParts);
            for (int i = 0; i < parts.size(); ++i)
                add(parts.at(i).trimmed(), i + 1 < parts.size() ? kPauseComma : endPause);
        }
        // The end of a line: at least the line break's pause.
        if (out.size() > before)
            out.last().pauseMs = qMax(out.last().pauseMs, kPauseLine);
    }
    if (!out.isEmpty())
        out.last().pauseMs = 0;
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
    // The speed, the same as Muninn's ReadAloudTest.speedRules (1.43): 70 to 130, 100 until moved, Qt's rate 0 at 100.
    check("speed held to 70 to 130", speedOf(10) == 70.0 && speedOf(400) == 130.0 && speedOf(83.4) == 83.4 && speedOf(std::nan("")) == 100.0);
    check("speed shown rounded", speedText(83.4) == QLatin1String("83%") && speedText(83.6) == QLatin1String("84%") && speedText(kSpeedDefault) == QLatin1String("100%"));
    check("engine speed", std::abs(engineSpeed(80) - 0.8f) < 1e-6f && std::abs(engineSpeed(130) - 1.3f) < 1e-6f && std::abs(engineSpeed(150) - 1.3f) < 1e-6f);
    check("Qt rate", std::abs(qtRate(70) + 0.6) < 1e-9 && std::abs(qtRate(100)) < 1e-9 && std::abs(qtRate(130) - 0.6) < 1e-9 && std::abs(qtRate(50) + 0.6) < 1e-9);
    check("a 1.42 speed: 80 is 100, others kept in the range", speedFrom142(80) == 100.0 && speedFrom142(80.0004) == 100.0 && speedFrom142(90) == 90.0
                                                                   && speedFrom142(50) == 70.0 && speedFrom142(150) == 130.0 && speedFrom142(std::nan("")) == 100.0);
    // The cadence scales only the pauses: 50 to 200, 100 until moved.
    check("cadence held to 50 to 200", cadenceOf(10) == 50.0 && cadenceOf(500) == 200.0 && cadenceOf(std::nan("")) == 100.0);
    check("pauses scaled by the cadence", pauseFor(kPauseStop, 100) == 500 && pauseFor(kPauseStop, 200) == 1000 && pauseFor(kPauseComma, 50) == 125
                                              && pauseFor(kPauseMeans, 999) == 1800 && pauseFor(-5, 100) == 0);
    // 1.43: the text before the voice, the same cases as Muninn's ReadAloudText tests.
    using P = QList<Phrase>;
    check("curly quotes plain, two dots one, three a pause",
          tidy(QStringLiteral("It\u2019s \u201Cfine\u201D.. really... ok\u2026")) == QStringLiteral("It's \"fine\". really\u2026 ok\u2026"));
    check("phrases and their pauses", phrases(QStringLiteral("Hello there, friend. How are you?\nFine"), false)
                                          == P{{QStringLiteral("Hello there,"), 250}, {QStringLiteral("friend."), 500}, {QStringLiteral("How are you?"), 500}, {QStringLiteral("Fine"), 0}});
    check("a line break is a pause", phrases(QStringLiteral("one\ntwo"), false) == P{{QStringLiteral("one"), 400}, {QStringLiteral("two"), 0}});
    check("an ellipsis is a pause, never read", phrases(QStringLiteral("Well... I think so"), false) == P{{QStringLiteral("Well"), 500}, {QStringLiteral("I think so"), 0}}
                                                     && phrases(QStringLiteral("wait\u2026what"), false) == P{{QStringLiteral("wait"), 500}, {QStringLiteral("what"), 0}});
    check("two dots are never read as dot", phrases(QStringLiteral("Ok.. bye"), false) == P{{QStringLiteral("Ok."), 500}, {QStringLiteral("bye"), 0}});
    const QString longOne = QStringLiteral("I stayed home from the long trip to the coast with all of my friends and family this year because the car broke down again");
    check("a long sentence splits at because", phrases(longOne, false) == P{{QStringLiteral("I stayed home from the long trip to the coast with all of my friends and family this year"), 250},
                                                                             {QStringLiteral("because the car broke down again"), 0}});
    check("a short one doesn't", phrases(QStringLiteral("I stayed because it rained"), false).size() == 1);
    check("abbreviations, shorthand and times", normalize(QStringLiteral("Dr. Smith said lol at 3:30 pm and brb"))
                                                    == QLatin1String("Doctor Smith said laughing out loud at three thirty pee em and be right back"));
    check("numbers in words", normalize(QStringLiteral("I have 42 cats, 1,000 dogs and 3.5 birds, 50% of 1923 on the 21st at 7:00 or 3:05."))
                                  == QLatin1String("I have forty-two cats, one thousand dogs and three point five birds, fifty percent of nineteen twenty-three on the twenty-first at seven o'clock or three oh five."));
    check("names, Saint and Street", normalize(QStringLiteral("Meet Mr. and Mrs. Lee on St. Olaf Ave by 3pm, ty! Main St."))
                                         == QLatin1String("Meet Mister and Missus Lee on Saint Olaf Ave by three pee em, thank you! Main Street."));
    check("digits one by one, money", normalize(QStringLiteral("Call 0123, cost $5.25 and $1")) == QLatin1String("Call zero one two three, cost five dollars and twenty-five cents and one dollar"));
    check("number words", numberWords(0) == QLatin1String("zero") && numberWords(13) == QLatin1String("thirteen") && numberWords(105) == QLatin1String("one hundred five")
                              && numberWords(1234567) == QLatin1String("one million two hundred thirty-four thousand five hundred sixty-seven"));
    check("a lone emoji says its name", emojiName(QStringLiteral("\u263A\uFE0F")) == QLatin1String("smiling face") && emojiName(QStringLiteral(" \U0001F44D\U0001F3FD "))
                                            == QLatin1String("thumbs up") && emojiName(QStringLiteral("hi \U0001F44D")).isEmpty() && emojiName(QStringLiteral("\U0001F44D\U0001F44D")).isEmpty()
                                            && phrases(QStringLiteral("\U0001F602"), false) == P{{QStringLiteral("face with tears of joy"), 0}});
    check("names said right, natural voices only", respell(QStringLiteral("Huginn and Muninn read the H\u00E1vam\u00E1l ere Othin's friend Loddfafnir came"))
                                                       == QLatin1String("hoo-gin and moo-nin read the hah-vah-mawl air oh-thin's friend lodd-fahv-neer came")
                                                   && phrases(QStringLiteral("Huginn read it."), true) == P{{QStringLiteral("hoo-gin read it."), 0}}
                                                   && phrases(QStringLiteral("Huginn read it."), false) == P{{QStringLiteral("Huginn read it."), 0}}
                                                   && respell(QStringLiteral("there were")) == QLatin1String("there were"));
    check("the verse, 900 ms, What it means, one dot only", versePhrases(QStringLiteral("Fire he needs,\nThe man from the mountains come.."), QStringLiteral("Help them.\nBe kind."), false)
                                                               == P{{QStringLiteral("Fire he needs,"), 400}, {QStringLiteral("The man from the mountains come."), 900},
                                                                    {QStringLiteral("What it means."), 500}, {QStringLiteral("Help them."), 500}, {QStringLiteral("Be kind."), 0}});
    check("tuning presets", tuneFor(QStringLiteral("A")).noise == 0.667f && tuneFor(QStringLiteral("B")).width == 0.6f && tuneFor(QStringLiteral("D")).width == 1.0f
                                && tuneFor(QStringLiteral("E")).noise == 0.8f && QLatin1String(tuneFor(QStringLiteral("x")).id) == QLatin1String("A"));
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
