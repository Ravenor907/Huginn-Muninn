#include "dictation.h"

#include "readaloud.h"
#include "settings.h"
#include "sherpaasr.h"
#include "system.h"
#include "ultraprivacy.h"

#include <QAudioDevice>
#include <QAudioSource>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDeadlineTimer>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QLibrary>
#include <QMediaDevices>
#include <QNetworkAccessManager>
#include <QNetworkInformation>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSet>
#include <QStorageInfo>
#include <QThread>
#include <QUrl>
#include <algorithm>
#include <climits>
#include <cstdio>
#include <cstring>
#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {

// The dictation-1 release (code/shared/docs/dictation.md). A pre-release: the update checks never look at it.
const char *kDownload = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/dictation-1/dictation-1.tar";
// A plain agent with no app name or version, as the updater and Read aloud send.
const char *kAgent = "Mozilla/5.0";
// The model's folder, named for its release, so a later dictation-2 never mixes with these files. The tar's entries
// are dictation-1/<file>.
const char *kFolder = "dictation-1";
const char *kTar = "dictation-1.tar";
// Room for the download, unpacking and the rest of the disk.
constexpr qint64 kRoom = 300LL * 1024 * 1024;
// How long a download may get no bytes before it's cut and resumed.
constexpr int kStallMs = 60 * 1000;
// Silero VAD's window at 16 kHz.
constexpr int kWindow = 512;
// The audio goes to the engine's thread in pieces of about 100 ms.
constexpr size_t kChunk = 1600;

// Every file of the model, each with its line in dictation.sha256.
const QStringList &needed()
{
    static const QStringList n{QStringLiteral("preprocess.onnx"), QStringLiteral("encode.int8.onnx"),
                               QStringLiteral("uncached_decode.int8.onnx"), QStringLiteral("cached_decode.int8.onnx"),
                               QStringLiteral("tokens.txt"), QStringLiteral("silero_vad.onnx"), QStringLiteral("LICENSE-moonshine.txt")};
    return n;
}

QString noMic() { return QStringLiteral("The microphone isn't available."); }

// Settings › Calls › Microphone while it's plugged in, the system default otherwise (as calls and voice messages).
QAudioDevice micWanted()
{
    QAudioDevice dev = QMediaDevices::defaultAudioInput();
    const QString mic = Settings::savedMic();
    for (const QAudioDevice &d : QMediaDevices::audioInputs())
        if (!mic.isEmpty() && QString::fromLatin1(d.id()) == mic)
            dev = d;
    return dev;
}

// [frames] of [f] as mono floats from -1 to 1 (the channels averaged), added to [out].
void toMono(const QAudioFormat &f, const char *data, int frames, std::vector<float> *out)
{
    const int ch = qMax(1, f.channelCount());
    const int bps = f.bytesPerSample();
    for (int i = 0; i < frames; ++i) {
        float sum = 0;
        for (int c = 0; c < ch; ++c) {
            const char *p = data + (i * ch + c) * bps;
            switch (f.sampleFormat()) {
            case QAudioFormat::UInt8: sum += (float(quint8(*p)) - 128.f) / 128.f; break;
            case QAudioFormat::Int16: { qint16 v; std::memcpy(&v, p, 2); sum += float(v) / 32768.f; break; }
            case QAudioFormat::Int32: { qint32 v; std::memcpy(&v, p, 4); sum += float(double(v) / 2147483648.0); break; }
            case QAudioFormat::Float: { float v; std::memcpy(&v, p, 4); sum += v; break; }
            default: break;
            }
        }
        out->push_back(sum / float(ch));
    }
}
QString readyLine() { return QStringLiteral("Ready. The microphone is in the message box"); }

// A path the engine (plain char* file names) can open: on Windows the short 8.3 form, as Read aloud does.
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

// The model unpacked into "<dir>.new" (marked ".unpacked" once whole and checked) goes into its place, the old folder
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

} // namespace

// ------------------------------------------------------------------ the recognizer, on its own thread

class DictationEngine : public QObject
{
public:
    DictationEngine(std::shared_ptr<std::atomic<int>> dropped, std::shared_ptr<std::atomic<bool>> gone, Dictation *to)
        : m_dropped(std::move(dropped)), m_gone(std::move(gone)), m_to(to)
    {
    }
    // On the engine's thread (deleted there when the thread ends): the model is let go of cleanly.
    ~DictationEngine() override { release(); }

    // A new session: the model opens (the first time), and the detector starts fresh.
    void begin(int session, const QString &dir)
    {
        if (dropped(session))
            return;
        QString why;
        if (!open(dir, &why)) {
            post([session, why](Dictation *d) { d->onFailed(session, why); });
            return;
        }
        m_clear(m_vad);
        m_reset(m_vad);
        m_carry.clear();
    }

    // Audio at 16 kHz: into the detector one window at a time; each finished phrase is turned into text.
    void feed(int session, const std::vector<float> &samples)
    {
        if (dropped(session) || !m_vad)
            return;
        m_carry.insert(m_carry.end(), samples.begin(), samples.end());
        size_t at = 0;
        while (m_carry.size() - at >= size_t(kWindow) && !dropped(session)) {
            m_accept(m_vad, m_carry.data() + at, kWindow);
            at += kWindow;
            drain(session);
        }
        m_carry.erase(m_carry.begin(), m_carry.begin() + qsizetype(at));
    }

    // Stop: what is still held (speech with no pause after it yet) is turned into text too.
    void finish(int session)
    {
        if (m_vad && !dropped(session)) {
            if (!m_carry.empty()) {
                m_carry.resize(size_t(kWindow), 0.0f);
                m_accept(m_vad, m_carry.data(), kWindow);
            }
            m_flush(m_vad);
            drain(session);
        }
        m_carry.clear();
        if (m_vad) {
            m_clear(m_vad);
            m_reset(m_vad);
        }
        post([session](Dictation *d) { d->onFinished(session); });
    }

    // Lets go of the model (Dictation turned off, or Huginn closing). The library stays loaded.
    void release()
    {
        if (m_vad && m_destroyVad)
            m_destroyVad(m_vad);
        m_vad = nullptr;
        if (m_rec && m_destroyRec)
            m_destroyRec(m_rec);
        m_rec = nullptr;
        m_carry.clear();
    }

    // The bundled library has every function dictation calls (the self-test).
    static bool libraryHas(QString *why)
    {
        DictationEngine e(std::make_shared<std::atomic<int>>(0), std::make_shared<std::atomic<bool>>(true), nullptr);
        return e.load(why);
    }

private:
    bool dropped(int session) const { return session <= m_dropped->load(); }

    template <typename F>
    void post(F f)
    {
        if (m_gone->load() || !m_to)
            return;
        Dictation *to = m_to;
        QMetaObject::invokeMethod(to, [to, f] { f(to); }, Qt::QueuedConnection);
    }

    template <typename T>
    bool resolve(QLibrary *lib, T *fn, const char *name)
    {
        *fn = reinterpret_cast<T>(lib->resolve(name));
        return *fn != nullptr;
    }

    bool load(QString *why)
    {
        if (m_loaded)
            return true;
        // The same library Read aloud uses, loaded and version-checked once.
        QLibrary *lib = ReadAloud::engineLibrary(why);
        if (!lib)
            return false;
        const bool all = resolve(lib, &m_createRec, "SherpaOnnxCreateOfflineRecognizer")
            && resolve(lib, &m_destroyRec, "SherpaOnnxDestroyOfflineRecognizer")
            && resolve(lib, &m_createStream, "SherpaOnnxCreateOfflineStream")
            && resolve(lib, &m_destroyStream, "SherpaOnnxDestroyOfflineStream")
            && resolve(lib, &m_acceptOffline, "SherpaOnnxAcceptWaveformOffline")
            && resolve(lib, &m_decode, "SherpaOnnxDecodeOfflineStream")
            && resolve(lib, &m_result, "SherpaOnnxGetOfflineStreamResult")
            && resolve(lib, &m_freeResult, "SherpaOnnxDestroyOfflineRecognizerResult")
            && resolve(lib, &m_createVad, "SherpaOnnxCreateVoiceActivityDetector")
            && resolve(lib, &m_destroyVad, "SherpaOnnxDestroyVoiceActivityDetector")
            && resolve(lib, &m_accept, "SherpaOnnxVoiceActivityDetectorAcceptWaveform")
            && resolve(lib, &m_empty, "SherpaOnnxVoiceActivityDetectorEmpty")
            && resolve(lib, &m_pop, "SherpaOnnxVoiceActivityDetectorPop")
            && resolve(lib, &m_clear, "SherpaOnnxVoiceActivityDetectorClear")
            && resolve(lib, &m_front, "SherpaOnnxVoiceActivityDetectorFront")
            && resolve(lib, &m_freeSegment, "SherpaOnnxDestroySpeechSegment")
            && resolve(lib, &m_reset, "SherpaOnnxVoiceActivityDetectorReset")
            && resolve(lib, &m_flush, "SherpaOnnxVoiceActivityDetectorFlush");
        if (!all) {
            if (why) *why = QStringLiteral("The speech engine is missing a part");
            return false;
        }
        m_loaded = true;
        return true;
    }

    bool open(const QString &dir, QString *why)
    {
        if (m_rec && m_vad)
            return true;
        release();
        if (!load(why))
            return false;
        const auto at = [&dir](const char *name) { return enginePath(dir + QLatin1Char('/') + QLatin1String(name)); };
        const QByteArray pre = at("preprocess.onnx"), enc = at("encode.int8.onnx"), unc = at("uncached_decode.int8.onnx"),
                         cac = at("cached_decode.int8.onnx"), tok = at("tokens.txt"), vad = at("silero_vad.onnx");
        const int threads = qBound(1, QThread::idealThreadCount() / 2, 4);
        {
            // In a zeroed buffer 4 KB larger than the struct: a library whose struct were bigger reads zeros, never the stack.
            alignas(SherpaOnnxOfflineRecognizerConfig) unsigned char room[sizeof(SherpaOnnxOfflineRecognizerConfig) + 4096];
            std::memset(room, 0, sizeof room);
            SherpaOnnxOfflineRecognizerConfig &c = *reinterpret_cast<SherpaOnnxOfflineRecognizerConfig *>(room);
            c.feat_config.sample_rate = Dictation::kRate;
            c.feat_config.feature_dim = 80;
            c.model_config.moonshine.preprocessor = pre.constData();
            c.model_config.moonshine.encoder = enc.constData();
            c.model_config.moonshine.uncached_decoder = unc.constData();
            c.model_config.moonshine.cached_decoder = cac.constData();
            c.model_config.moonshine.merged_decoder = "";
            c.model_config.tokens = tok.constData();
            c.model_config.num_threads = threads;
            c.model_config.debug = 0;
            c.model_config.provider = "cpu";
            c.model_config.model_type = "";
            c.model_config.modeling_unit = "";
            c.model_config.bpe_vocab = "";
            c.model_config.telespeech_ctc = "";
            c.lm_config.model = "";
            c.lm_config.scale = 1.0f;
            c.decoding_method = "greedy_search";
            c.max_active_paths = 4;
            c.hotwords_file = "";
            c.hotwords_score = 1.5f;
            c.rule_fsts = "";
            c.rule_fars = "";
            c.blank_penalty = 0.0f;
            c.hr.dict_dir = "";
            c.hr.lexicon = "";
            c.hr.rule_fsts = "";
            m_rec = m_createRec(&c);
        }
        if (!m_rec) {
            if (why) *why = QStringLiteral("The speech model couldn't start");
            return false;
        }
        {
            alignas(SherpaOnnxVadModelConfig) unsigned char room[sizeof(SherpaOnnxVadModelConfig) + 4096];
            std::memset(room, 0, sizeof room);
            SherpaOnnxVadModelConfig &v = *reinterpret_cast<SherpaOnnxVadModelConfig *>(room);
            v.silero_vad.model = vad.constData();
            v.silero_vad.threshold = 0.5f;
            // A phrase ends at a pause of 0.4 s; shorter bits than 0.25 s are noise; one longer than 20 s is split.
            v.silero_vad.min_silence_duration = 0.4f;
            v.silero_vad.min_speech_duration = 0.25f;
            v.silero_vad.window_size = kWindow;
            v.silero_vad.max_speech_duration = 20.0f;
            v.sample_rate = Dictation::kRate;
            v.num_threads = 1;
            v.provider = "cpu";
            v.debug = 0;
            v.ten_vad.model = "";
            m_vad = m_createVad(&v, 30.0f);
        }
        if (!m_vad) {
            release();
            if (why) *why = QStringLiteral("The speech model couldn't start");
            return false;
        }
        return true;
    }

    // Every finished phrase the detector holds, in order, as text for the box.
    void drain(int session)
    {
        while (m_vad && !m_empty(m_vad)) {
            const SherpaOnnxSpeechSegment *seg = m_front(m_vad);
            QString text;
            if (seg && seg->samples && seg->n > 0 && !dropped(session))
                text = recognize(seg->samples, seg->n);
            if (seg)
                m_freeSegment(seg);
            m_pop(m_vad);
            text = text.trimmed();
            if (!text.isEmpty())
                post([session, text](Dictation *d) { d->onHeard(session, text); });
        }
    }

    QString recognize(const float *samples, int n)
    {
        const SherpaOnnxOfflineStream *stream = m_createStream(m_rec);
        if (!stream)
            return QString();
        m_acceptOffline(stream, Dictation::kRate, samples, n);
        m_decode(m_rec, stream);
        QString text;
        if (const SherpaOnnxOfflineRecognizerResult *r = m_result(stream)) {
            if (r->text)
                text = QString::fromUtf8(r->text);
            m_freeResult(r);
        }
        m_destroyStream(stream);
        return text;
    }

    std::shared_ptr<std::atomic<int>> m_dropped;
    std::shared_ptr<std::atomic<bool>> m_gone;
    Dictation *m_to;
    bool m_loaded = false;
    std::vector<float> m_carry;
    const SherpaOnnxOfflineRecognizer *m_rec = nullptr;
    const SherpaOnnxVoiceActivityDetector *m_vad = nullptr;
    SherpaOnnxCreateOfflineRecognizerFn m_createRec = nullptr;
    SherpaOnnxDestroyOfflineRecognizerFn m_destroyRec = nullptr;
    SherpaOnnxCreateOfflineStreamFn m_createStream = nullptr;
    SherpaOnnxDestroyOfflineStreamFn m_destroyStream = nullptr;
    SherpaOnnxAcceptWaveformOfflineFn m_acceptOffline = nullptr;
    SherpaOnnxDecodeOfflineStreamFn m_decode = nullptr;
    SherpaOnnxGetOfflineStreamResultFn m_result = nullptr;
    SherpaOnnxDestroyOfflineRecognizerResultFn m_freeResult = nullptr;
    SherpaOnnxCreateVoiceActivityDetectorFn m_createVad = nullptr;
    SherpaOnnxDestroyVoiceActivityDetectorFn m_destroyVad = nullptr;
    SherpaOnnxVoiceActivityDetectorAcceptWaveformFn m_accept = nullptr;
    SherpaOnnxVoiceActivityDetectorEmptyFn m_empty = nullptr;
    SherpaOnnxVoiceActivityDetectorPopFn m_pop = nullptr;
    SherpaOnnxVoiceActivityDetectorClearFn m_clear = nullptr;
    SherpaOnnxVoiceActivityDetectorFrontFn m_front = nullptr;
    SherpaOnnxDestroySpeechSegmentFn m_freeSegment = nullptr;
    SherpaOnnxVoiceActivityDetectorResetFn m_reset = nullptr;
    SherpaOnnxVoiceActivityDetectorFlushFn m_flush = nullptr;
};

// ------------------------------------------------------------------ Dictation

Dictation::Dictation(QObject *parent)
    : QObject(parent), m_engineThere(ReadAloud::engineFilePresent()), m_dropped(std::make_shared<std::atomic<int>>(0)),
      m_gone(std::make_shared<std::atomic<bool>>(false)), m_stopUnpack(std::make_shared<std::atomic<bool>>(false))
{
    QFile f(QStringLiteral(":/huginn/dictation.sha256"));
    if (f.open(QIODevice::ReadOnly))
        m_hashes = ReadAloud::hashes(f.readAll());
    m_retry.setSingleShot(true);
    connect(&m_retry, &QTimer::timeout, this, &Dictation::startDownload);
    // Read aloud's network rules: a metered connection that ends starts a waiting download; a new network cuts one
    // that may be talking to a dead connection and resumes it at once.
    if (QNetworkInformation::instance() || QNetworkInformation::loadBackendByFeatures(QNetworkInformation::Feature::Metered)
        || QNetworkInformation::loadDefaultBackend()) {
        if (auto *ni = QNetworkInformation::instance()) {
            connect(ni, &QNetworkInformation::isMeteredChanged, this, [this](bool metered) {
                if (!metered && m_wanted && !m_reply)
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
            if (m_wanted && !m_reply && UltraPrivacy::on() && UltraPrivacy::instance()->torState() == QLatin1String("ready"))
                startDownload();
        });
        connect(u, &UltraPrivacy::redial, this, [this] {
            if (!m_reply)
                return;
            m_reply->abort();
            QTimer::singleShot(0, this, &Dictation::startDownload);
        });
    }
    if (!isOn()) {
        // Turned off, but Huginn closed before the model was deleted: it goes now.
        if (QFileInfo::exists(rootDir()))
            QDir(rootDir()).removeRecursively();
    } else {
        // A model unpacked and checked just before Huginn stopped goes into its place now.
        if (!ready() && QFileInfo::exists(modelDir() + QStringLiteral(".new/.unpacked")))
            placeUnpacked(modelDir());
        m_status = ready() ? readyLine() : QStringLiteral("Downloading");
        // Turned on, but the download didn't finish: it carries on.
        if (!ready())
            QTimer::singleShot(0, this, &Dictation::prepare);
    }
}

Dictation::~Dictation()
{
    shutdown();
    delete m_part;
}

bool Dictation::offered() const
{
    if (!m_engineThere)
        return false;
    for (const QString &n : needed())
        if (!m_hashes.contains(n))
            return false;
    return true;
}

bool Dictation::isOn() const { return Settings::store().value(QStringLiteral("dictation/on"), false).toBool(); }

void Dictation::setOn(bool on)
{
    if (on == isOn())
        return;
    Settings::store().setValue(QStringLiteral("dictation/on"), on);
    if (on) {
        m_failures = 0;
        m_status = ready() ? readyLine() : QStringLiteral("Downloading");
        prepare();
    } else {
        // Off: the microphone closes, any download stops, and the model is deleted (the space is freed).
        cancel();
        deleteModel();
    }
    Q_EMIT changed();
    Q_EMIT statusChanged();
}

void Dictation::setTour(bool on)
{
    if (on == m_tour)
        return;
    m_tour = on;
    Q_EMIT changed();
}

QString Dictation::rootDir() const { return System::dataDir() + QStringLiteral("/dictation"); }
QString Dictation::modelDir() const { return rootDir() + QLatin1Char('/') + QLatin1String(kFolder); }

bool Dictation::ready() const
{
    if (!offered())
        return false;
    const QString d = modelDir();
    for (const QString &n : needed())
        if (!QFileInfo::exists(d + QLatin1Char('/') + n))
            return false;
    return true;
}

QString Dictation::status() const
{
    if (!isOn())
        return QStringLiteral("Speech to text in the message box. It downloads once, about 125 MB");
    if (!offered())
        return QStringLiteral("Dictation isn't in this copy of Huginn");
    return m_status;
}

void Dictation::setStatus(const QString &s)
{
    if (s == m_status)
        return;
    m_status = s;
    Q_EMIT statusChanged();
}

// ------------------------------------------------------------------ the microphone

QString Dictation::start()
{
    if (m_tour)
        return QString();   // the tour only shows it
    if (!isOn() || !ready())
        return QStringLiteral("Turn on Dictation first: Settings › Look › Accessibility.");
    if (m_blocked && m_blocked())
        return QStringLiteral("Not during a call.");
    if (m_listening)
        return QString();
    // Settings › Calls › Microphone, while it's plugged in; the system default otherwise.
    const QAudioDevice dev = micWanted();
    if (dev.isNull())
        return noMic();
    // 16 kHz mono floats, what the detector and the model take. A microphone that won't do that gives its own
    // format, made 16 kHz mono here.
    QAudioFormat f;
    f.setSampleRate(kRate);
    f.setChannelCount(1);
    f.setSampleFormat(QAudioFormat::Float);
    if (!dev.isFormatSupported(f)) {
        f.setSampleFormat(QAudioFormat::Int16);
        if (!dev.isFormatSupported(f)) {
            f = dev.preferredFormat();
            if (f.sampleFormat() == QAudioFormat::Unknown)
                f.setSampleFormat(QAudioFormat::Int16);
        }
    }
    if (f.sampleRate() <= 0 || f.channelCount() <= 0)
        return noMic();
    m_source = new QAudioSource(dev, f, this);
    m_source->setBufferSize(f.bytesForDuration(200000));
    m_in = m_source->start();
    if (!m_in) {
        delete m_source;
        m_source = nullptr;
        return noMic();
    }
    m_format = f;
    m_resample.setRates(f.sampleRate(), kRate);
    // Faster than 16 kHz: a running average over one output sample's worth of input first.
    const int k = f.sampleRate() / kRate;
    m_smooth.assign(k >= 2 ? size_t(k) : 0, 0.0f);
    m_smoothAt = 0;
    m_smoothSum = 0;
    m_pending.clear();
    m_session = ++m_lastSession;
    ensureEngine();
    DictationEngine *engine = m_engine;
    const int s = m_session;
    const QString dir = modelDir();
    QMetaObject::invokeMethod(engine, [engine, s, dir] { engine->begin(s, dir); }, Qt::QueuedConnection);
    connect(m_in, &QIODevice::readyRead, this, &Dictation::onAudio);
    // The microphone stopped by itself (pulled out, taken by the system): said once, and what was heard is kept.
    connect(m_source, &QAudioSource::stateChanged, this, [this, s](QAudio::State st) {
        if (st != QAudio::StoppedState || !m_source || m_source->error() == QAudio::NoError || !m_listening || m_session != s)
            return;
        QTimer::singleShot(0, this, [this, s] {
            if (!m_listening || m_session != s)
                return;
            stop();
            Q_EMIT problem(s, noMic());
        });
    });
    m_listening = true;
    Q_EMIT changed();
    return QString();
}

void Dictation::onAudio()
{
    if (!m_in)
        return;
    const QByteArray raw = m_in->readAll();
    const int frameBytes = m_format.bytesPerFrame();
    if (frameBytes <= 0 || raw.isEmpty())
        return;
    m_mono.clear();
    toMono(m_format, raw.constData(), int(raw.size() / frameBytes), &m_mono);
    if (!m_smooth.empty()) {
        const size_t k = m_smooth.size();
        for (float &v : m_mono) {
            m_smoothSum += double(v) - double(m_smooth[size_t(m_smoothAt)]);
            m_smooth[size_t(m_smoothAt)] = v;
            m_smoothAt = int((size_t(m_smoothAt) + 1) % k);
            v = float(m_smoothSum / double(k));
        }
    }
    m_down.clear();
    m_resample.process(m_mono.data(), int(m_mono.size()), &m_down);
    m_pending.insert(m_pending.end(), m_down.begin(), m_down.end());
    sendPending(false);
}

void Dictation::sendPending(bool all)
{
    if (!m_engine || m_pending.empty() || (!all && m_pending.size() < kChunk))
        return;
    DictationEngine *engine = m_engine;
    const int s = m_session;
    const std::vector<float> chunk = m_pending;
    m_pending.clear();
    QMetaObject::invokeMethod(engine, [engine, s, chunk] { engine->feed(s, chunk); }, Qt::QueuedConnection);
}

void Dictation::closeMic()
{
    if (m_source) {
        m_source->disconnect(this);
        if (m_in)
            m_in->disconnect(this);
        m_source->stop();
        m_source->deleteLater();
    }
    m_source = nullptr;
    m_in = nullptr;
}

void Dictation::stop()
{
    if (!m_listening)
        return;
    // What the microphone still holds, then it closes; the rest is turned into text.
    onAudio();
    closeMic();
    sendPending(true);
    const int s = m_session;
    m_listening = false;
    m_finishing = s;
    if (DictationEngine *engine = m_engine)
        QMetaObject::invokeMethod(engine, [engine, s] { engine->finish(s); }, Qt::QueuedConnection);
    else
        m_finishing = 0;
    Q_EMIT changed();
}

void Dictation::cancel()
{
    const bool was = m_listening || m_finishing != 0;
    closeMic();
    m_pending.clear();
    // Every session so far is dropped: the engine skips what's queued, and text already on its way is ignored.
    m_dropped->store(qMax(m_dropped->load(), m_lastSession));
    m_listening = false;
    m_finishing = 0;
    if (was)
        Q_EMIT changed();
}

void Dictation::onHeard(int session, const QString &text)
{
    if (session <= m_dropped->load())
        return;
    Q_EMIT heard(session, text);
}

void Dictation::onFinished(int session)
{
    if (session != m_finishing)
        return;
    m_finishing = 0;
    Q_EMIT changed();
}

void Dictation::onFailed(int session, const QString &why)
{
    std::fprintf(stderr, "Dictation: %s\n", qPrintable(why));
    if (session <= m_dropped->load())
        return;
    if (m_listening && m_session == session) {
        closeMic();
        m_pending.clear();
        m_listening = false;
    }
    if (m_finishing == session)
        m_finishing = 0;
    m_dropped->store(qMax(m_dropped->load(), session));
    Q_EMIT changed();
    Q_EMIT problem(session, why + QLatin1Char('.'));
}

void Dictation::ensureEngine()
{
    if (m_thread)
        return;
    m_thread = new QThread(this);
    m_thread->setObjectName(QStringLiteral("dictation"));
    m_engine = new DictationEngine(m_dropped, m_gone, this);
    m_engine->moveToThread(m_thread);
    // The engine is deleted on its own thread when the thread ends, letting go of the model there.
    connect(m_thread, &QThread::finished, m_engine, &QObject::deleteLater);
    m_thread->start(QThread::LowPriority);
}

void Dictation::releaseEngine(std::function<void()> then)
{
    // The engine lets go of the model's files (Windows can't delete files in use) on its own thread, after the phrase
    // it may be turning into text; then [then] runs here. The window never waits for it.
    if (m_engine && m_thread && m_thread->isRunning()) {
        DictationEngine *engine = m_engine;
        QPointer<Dictation> self(this);
        QMetaObject::invokeMethod(engine, [engine, self, then] {
            engine->release();
            if (self)
                QMetaObject::invokeMethod(self.data(), then, Qt::QueuedConnection);
        }, Qt::QueuedConnection);
        return;
    }
    then();
}

void Dictation::shutdown()
{
    if (m_shutDown)
        return;
    m_shutDown = true;
    cancel();
    m_dropped->store(INT_MAX);
    m_wanted = false;
    m_retry.stop();
    if (m_reply)
        m_reply->abort();
    m_stopUnpack->store(true);
    // About 2 s in all, as Chat::stopBigThreads: one still busy after that is left behind rather than waited on forever.
    const QDeadlineTimer until(2000);
    if (m_unpacker && m_unpacker->isRunning() && !m_unpacker->wait(until))
        qWarning("dictation: the download check didn't stop in time; leaving it");
    if (m_thread) {
        m_thread->quit();
        if (m_thread->wait(until)) {
            delete m_thread;   // the engine was deleted on its thread as it ended, and let go of the model there
        } else {
            qWarning("dictation: the recognizer didn't stop in time; leaving it");
            m_gone->store(true);
            m_thread->setParent(nullptr);
        }
        m_thread = nullptr;
        m_engine = nullptr;
    }
}

// ------------------------------------------------------------------ the download

bool Dictation::meteredNow() const
{
    auto *ni = QNetworkInformation::instance();
    return ni && ni->supports(QNetworkInformation::Feature::Metered) && ni->isMetered();
}

void Dictation::prepare()
{
    if (!isOn() || ready() || m_reply || m_unpacker || m_shutDown)
        return;
    if (!offered()) {
        setStatus(QStringLiteral("Dictation isn't in this copy of Huginn"));
        return;
    }
    m_wanted = true;
    startDownload();
}

void Dictation::startDownload()
{
    if (!m_wanted || m_reply || m_unpacker || !isOn() || ready() || m_shutDown)
        return;
    // Read aloud's "Allow on mobile data" covers this download too.
    if (meteredNow() && !Settings::store().value(QStringLiteral("readAloud/mobileData"), false).toBool()) {
        setStatus(QStringLiteral("Waits for Wi-Fi or a wired connection (or Allow on mobile data in Read aloud)"));
        return;
    }
    // Ultra privacy: only through Tor (UltraPrivacy::prepare); until Tor is ready, wait and say so.
    if (UltraPrivacy::on() && (!UltraPrivacy::instance() || UltraPrivacy::instance()->torState() != QLatin1String("ready"))) {
        setStatus(QStringLiteral("Waits for Ultra privacy's Tor connection"));
        return;
    }
    QDir().mkpath(rootDir());
    if (QStorageInfo(rootDir()).bytesAvailable() < kRoom) {
        setStatus(QStringLiteral("Not enough free space (about 300 MB needed while it downloads)"));
        return;
    }
    if (!m_net)
        m_net = new QNetworkAccessManager(this);
    // Tor's SOCKS5 while Ultra privacy is on, the normal way while it's off (set again on every start).
    UltraPrivacy::prepare(m_net);
    m_retry.stop();
    // Picks up where a cut-off download stopped.
    const QString partPath = rootDir() + QLatin1Char('/') + QLatin1String(kTar) + QStringLiteral(".part");
    delete m_part;
    m_part = new QFile(partPath);
    m_got = 0;
    if (m_part->exists() && m_part->size() > 0 && m_part->size() < kMaxDownload)
        m_got = m_part->size();
    else
        QFile::remove(partPath);
    if (!m_part->open(QIODevice::WriteOnly | QIODevice::Append)) {
        downloadFailed(QStringLiteral("Can't save it in the data folder"), 15 * 60 * 1000);
        return;
    }
    QNetworkRequest req(QUrl(QString::fromLatin1(kDownload)));
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
    setStatus(QStringLiteral("Downloading"));
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
        m_got += chunk.size();
        const qint64 total = m_reply->header(QNetworkRequest::ContentLengthHeader).toLongLong() + m_base;
        if (total > 0)
            setStatus(QStringLiteral("Downloading, %1%").arg(qBound(qint64(0), qint64(m_got * 100 / qMax(qint64(1), total)), qint64(100))));
    });
    connect(m_reply, &QNetworkReply::finished, this, &Dictation::downloadDone);
}

void Dictation::downloadDone()
{
    QNetworkReply *r = m_reply;
    m_reply = nullptr;
    if (!r)
        return;
    r->deleteLater();
    if (m_part)
        m_part->close();
    if (!m_wanted || !isOn() || m_shutDown)
        return;   // turned off meanwhile (the files are being deleted), or Huginn is closing
    const QString partPath = rootDir() + QLatin1Char('/') + QLatin1String(kTar) + QStringLiteral(".part");
    // 416: the part kept was already the whole file (Huginn closed before checking it): checked now.
    const bool whole = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() == 416 && m_base > 0;
    if (r->error() != QNetworkReply::NoError && !whole) {
        // A size over the limit is never right: start over next time. Anything else picks up where it stopped: at
        // once after a network change, within a minute for the first few cuts, later after that.
        if (m_got > kMaxDownload || r->error() == QNetworkReply::ContentNotFoundError)
            QFile::remove(partPath);
        ++m_failures;
        const int wait = m_resumeNow ? 3 * 1000 : m_failures <= 3 ? 60 * 1000 : 15 * 60 * 1000;
        m_resumeNow = false;
        downloadFailed(QStringLiteral("It couldn't download. It tries again soon"), wait);
        return;
    }
    // Unpacked beside its final place and every file checked, on its own thread (125 MB is never hashed on the
    // window's thread), then put in place here.
    setStatus(QStringLiteral("Checking the download"));
    const QString dir = modelDir();
    const QString fresh = dir + QStringLiteral(".new");
    const QHash<QString, QString> hashes = m_hashes;
    const std::shared_ptr<std::atomic<bool>> stopNow = m_stopUnpack;
    stopNow->store(false);
    QPointer<Dictation> self(this);
    QThread *t = QThread::create([partPath, fresh, hashes, stopNow, self] {
        QDir(fresh).removeRecursively();
        QString why;
        const bool ok = unpack(partPath, fresh, hashes, &why, [stopNow] { return stopNow->load(); });
        if (ok) {
            QFile done(fresh + QStringLiteral("/.unpacked"));
            if (done.open(QIODevice::WriteOnly))
                done.close();
        } else {
            QDir(fresh).removeRecursively();
        }
        if (QCoreApplication *app = QCoreApplication::instance())
            QMetaObject::invokeMethod(app, [self, ok, why] { if (self) self->unpacked(ok, why); }, Qt::QueuedConnection);
    });
    t->setObjectName(QStringLiteral("dictation-check"));
    connect(t, &QThread::finished, t, &QObject::deleteLater);
    m_unpacker = t;
    t->start(QThread::LowPriority);
}

void Dictation::unpacked(bool ok, const QString &why)
{
    m_unpacker.clear();
    const QString partPath = rootDir() + QLatin1Char('/') + QLatin1String(kTar) + QStringLiteral(".part");
    const QString dir = modelDir();
    if (m_shutDown)
        return;
    if (!isOn() || !m_wanted) {
        // Turned off while it was being checked.
        if (!isOn())
            QDir(rootDir()).removeRecursively();
        return;
    }
    QFile::remove(partPath);
    if (!ok) {
        downloadFailed(QStringLiteral("The download didn't pass its check (%1), so it wasn't kept. It tries again later").arg(why), 15 * 60 * 1000);
        return;
    }
    if (!placeUnpacked(dir)) {
        QDir(dir + QStringLiteral(".new")).removeRecursively();
        downloadFailed(QStringLiteral("Can't save it in the data folder"), 15 * 60 * 1000);
        return;
    }
    m_wanted = false;
    m_failures = 0;
    setStatus(readyLine());
    Q_EMIT changed();
}

void Dictation::downloadFailed(const QString &why, int retryMs)
{
    setStatus(why);
    if (retryMs > 0)
        m_retry.start(retryMs);
}

void Dictation::networkMoved()
{
    if (!m_wanted)
        return;
    if (m_reply) {
        // The connection may be dead without saying so: cut it, and the part kept resumes in a moment.
        m_resumeNow = true;
        m_reply->abort();
    } else if (m_retry.isActive()) {
        m_retry.start(3 * 1000);
    }
}

void Dictation::deleteModel()
{
    m_wanted = false;
    m_retry.stop();
    if (m_reply)
        m_reply->abort();
    if (m_part)
        m_part->close();
    m_stopUnpack->store(true);
    m_status.clear();
    // The engine lets go of the model first (on its thread), then the files go. Turned on again meanwhile: kept.
    releaseEngine([this] {
        if (isOn())
            return;
        QDir(rootDir()).removeRecursively();
        Q_EMIT changed();
    });
}

// ------------------------------------------------------------------ the rules

QString Dictation::joined(const QString &before, const QString &heard)
{
    const QString t = heard.simplified();
    if (t.isEmpty())
        return QString();
    if (!before.isEmpty() && !before.back().isSpace())
        return QLatin1Char(' ') + t;
    return t;
}

QString Dictation::fileIn(const QString &name, const QStringList &wanted)
{
    if (name.isEmpty() || name.size() > 200 || name.startsWith(QLatin1Char('/')) || name.contains(QLatin1Char('\\'))
        || name.contains(QLatin1Char(':')) || name.contains(QChar(0)))
        return QString();
    const QStringList parts = name.split(QLatin1Char('/'));
    if (parts.size() != 2 || parts.first() != QLatin1String(kFolder) || !wanted.contains(parts.last()))
        return QString();
    return parts.last();
}

bool Dictation::unpack(const QString &tarPath, const QString &dir, const QHash<QString, QString> &hashes, QString *why,
                       const std::function<bool()> &stop)
{
    const auto fail = [why](const QString &w) {
        if (why) *why = w;
        return false;
    };
    if (hashes.isEmpty())
        return fail(QStringLiteral("no checks to match"));
    QFile f(tarPath);
    if (!f.open(QIODevice::ReadOnly))
        return fail(QStringLiteral("can't open it"));
    if (!QDir().mkpath(dir))
        return fail(QStringLiteral("can't make its folder"));
    const QStringList wanted = hashes.keys();
    const auto field = [](const QByteArray &h, int at, int len) {
        QByteArray v = h.mid(at, len);
        const int z = v.indexOf('\0');
        return z >= 0 ? v.left(z) : v;
    };
    QSet<QString> got;
    while (true) {
        if (stop && stop())
            return fail(QStringLiteral("stopped"));
        const QByteArray h = f.read(512);
        if (h.size() < 512)
            return fail(QStringLiteral("it ends early"));
        if (h.count('\0') == 512)
            break;   // the end
        // The header's checksum: the sum of its bytes with the checksum field read as spaces.
        bool ok = false;
        const qint64 sum = field(h, 148, 8).trimmed().toLongLong(&ok, 8);
        qint64 have = 0;
        for (int i = 0; i < 512; ++i)
            have += (i >= 148 && i < 156) ? 0x20 : quint8(h.at(i));
        if (!ok || sum != have)
            return fail(QStringLiteral("a damaged header"));
        const qint64 size = field(h, 124, 12).trimmed().toLongLong(&ok, 8);
        if (!ok || size < 0 || size > kMaxFile)
            return fail(QStringLiteral("a file too big"));
        const qint64 pad = (512 - size % 512) % 512;
        const char type = h.at(156);
        QString name = QString::fromUtf8(field(h, 0, 100));
        const QByteArray prefix = field(h, 345, 155);
        if (h.mid(257, 5) == "ustar" && !prefix.isEmpty())
            name = QString::fromUtf8(prefix) + QLatin1Char('/') + name;
        while (name.endsWith(QLatin1Char('/')))
            name.chop(1);
        if (type == '5') {
            // The model's own folder: nothing to make (the files go straight into [dir]).
            if (name != QLatin1String(kFolder))
                return fail(QStringLiteral("an unexpected name"));
            continue;
        }
        if (type == 'x' || type == 'g') {
            // A pax header (times, owners): passed over. The name that counts is the file's own, checked below.
            if (size > 64 * 1024 || f.read(size + pad).size() != size + pad)
                return fail(QStringLiteral("it ends early"));
            continue;
        }
        if (type != '0' && type != '\0')
            return fail(QStringLiteral("something that isn't a file"));   // links, devices and the rest: never
        const QString file = fileIn(name, wanted);
        if (file.isEmpty())
            return fail(QStringLiteral("an unexpected name"));
        if (got.contains(file))
            return fail(QStringLiteral("a file twice"));
        QFile out(dir + QLatin1Char('/') + file);
        if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate))
            return fail(QStringLiteral("can't write a file"));
        QCryptographicHash sha(QCryptographicHash::Sha256);
        qint64 left = size;
        while (left > 0) {
            if (stop && stop())
                return fail(QStringLiteral("stopped"));
            const QByteArray chunk = f.read(qMin<qint64>(left, 1 << 20));
            if (chunk.isEmpty() || out.write(chunk) != chunk.size())
                return fail(QStringLiteral("it ends early"));
            sha.addData(chunk);
            left -= chunk.size();
        }
        out.close();
        if (pad && f.read(pad).size() != pad)
            return fail(QStringLiteral("it ends early"));
        if (QString::fromLatin1(sha.result().toHex()) != hashes.value(file))
            return fail(QStringLiteral("a file didn't match"));
        got.insert(file);
    }
    if (got.size() != hashes.size())
        return fail(QStringLiteral("parts are missing"));
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

QString sha(const QByteArray &b) { return QString::fromLatin1(QCryptographicHash::hash(b, QCryptographicHash::Sha256).toHex()); }

} // namespace

bool Dictation::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("dictation self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        if (!ok) all = false;
    };
    // The words go in at the cursor: trimmed, a space first after a word.
    check("joined after a word", joined(QStringLiteral("Hello"), QStringLiteral("  there friend ")) == QLatin1String(" there friend"));
    check("joined after a space or nothing", joined(QStringLiteral("Hello "), QStringLiteral("there")) == QLatin1String("there")
                                                  && joined(QString(), QStringLiteral(" Hi.")) == QLatin1String("Hi.")
                                                  && joined(QStringLiteral("a\n"), QStringLiteral("b")) == QLatin1String("b"));
    check("nothing heard adds nothing", joined(QStringLiteral("Hello"), QStringLiteral("   ")).isEmpty());
    // Names in the release's tar.
    const QStringList w = needed();
    check("names allowed", fileIn(QStringLiteral("dictation-1/tokens.txt"), w) == QLatin1String("tokens.txt")
                               && fileIn(QStringLiteral("dictation-1/silero_vad.onnx"), w) == QLatin1String("silero_vad.onnx"));
    check("names refused", fileIn(QStringLiteral("tokens.txt"), w).isEmpty() && fileIn(QStringLiteral("dictation-1/../tokens.txt"), w).isEmpty()
                               && fileIn(QStringLiteral("/dictation-1/tokens.txt"), w).isEmpty() && fileIn(QStringLiteral("dictation-1/x/tokens.txt"), w).isEmpty()
                               && fileIn(QStringLiteral("dictation-1/other.txt"), w).isEmpty() && fileIn(QStringLiteral("dictation-2/tokens.txt"), w).isEmpty()
                               && fileIn(QStringLiteral("dictation-1\\tokens.txt"), w).isEmpty() && fileIn(QStringLiteral("C:dictation-1/tokens.txt"), w).isEmpty()
                               && fileIn(QString(), w).isEmpty());
    // code/shared/dictation.sha256, built in: a line for every file.
    QFile built(QStringLiteral(":/huginn/dictation.sha256"));
    const QHash<QString, QString> h = built.open(QIODevice::ReadOnly) ? ReadAloud::hashes(built.readAll()) : QHash<QString, QString>();
    bool every = !h.isEmpty();
    for (const QString &n : w)
        every = every && h.contains(n);
    check("dictation.sha256 is built in with every file", every && h.size() == w.size());
    // Unpacking: a good model, and ones with a wrong file, a missing file, a link or a path out.
    QDir tmp(QDir::tempPath() + QStringLiteral("/huginn-dictation-selftest-") + QString::number(QCoreApplication::applicationPid()));
    tmp.removeRecursively();
    QDir().mkpath(tmp.path());
    const auto writeTar = [&tmp](const QString &n, const QByteArray &bytes) {
        QFile t(tmp.filePath(n));
        if (t.open(QIODevice::WriteOnly)) t.write(bytes);
        return tmp.filePath(n);
    };
    const QByteArray end(1024, '\0');
    const QByteArray model(700, 'm'), tokens("a 1\n");
    const QHash<QString, QString> two{{QStringLiteral("encode.int8.onnx"), sha(model)}, {QStringLiteral("tokens.txt"), sha(tokens)}};
    const QByteArray pax = tarEntry("PaxHeader", "20 mtime=1700000000\n", 'x');
    const QString good = writeTar(QStringLiteral("good.tar"), tarEntry("dictation-1", {}, '5') + pax + tarEntry("dictation-1/encode.int8.onnx", model, '0')
                                                              + tarEntry("dictation-1/tokens.txt", tokens, '0') + end);
    QString why;
    const bool goodOk = unpack(good, tmp.filePath(QStringLiteral("good")), two, &why);
    QFile got(tmp.filePath(QStringLiteral("good/encode.int8.onnx")));
    check("a model unpacks and matches", goodOk && got.open(QIODevice::ReadOnly) && got.readAll() == model);
    const QString wrong = writeTar(QStringLiteral("wrong.tar"), tarEntry("dictation-1/encode.int8.onnx", "x", '0') + tarEntry("dictation-1/tokens.txt", tokens, '0') + end);
    check("a file that doesn't match is refused", !unpack(wrong, tmp.filePath(QStringLiteral("wrong")), two, &why));
    const QString missing = writeTar(QStringLiteral("missing.tar"), tarEntry("dictation-1/tokens.txt", tokens, '0') + end);
    check("a missing file is refused", !unpack(missing, tmp.filePath(QStringLiteral("missing")), two, &why));
    const QString evil = writeTar(QStringLiteral("evil.tar"), tarEntry("dictation-1/../evil.txt", "x", '0') + end);
    check("a path out is refused", !unpack(evil, tmp.filePath(QStringLiteral("evil")), two, &why) && !QFileInfo::exists(tmp.filePath(QStringLiteral("evil.txt"))));
    const QString linked = writeTar(QStringLiteral("link.tar"), tarEntry("dictation-1/tokens.txt", {}, '2') + end);
    check("a link is refused", !unpack(linked, tmp.filePath(QStringLiteral("link")), two, &why));
    const QString twice = writeTar(QStringLiteral("twice.tar"), tarEntry("dictation-1/tokens.txt", tokens, '0') + tarEntry("dictation-1/tokens.txt", tokens, '0') + end);
    check("a file twice is refused", !unpack(twice, tmp.filePath(QStringLiteral("twice")), two, &why));
    tmp.removeRecursively();
    // The bundled engine: the CI builds ship it, so it must have every function dictation calls.
    QString engineWhy;
    const bool has = DictationEngine::libraryHas(&engineWhy);
    if (!has)
        std::printf("dictation self-test: engine: %s\n", qPrintable(engineWhy));
    check("the speech engine has speech to text", has);
    return all;
}
