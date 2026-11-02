#include "audioio.h"
#include "../settings.h"

#include <QAudioSink>
#include <QAudioSource>
#include <QDateTime>
#include <QIODevice>
#include <QMediaDevices>
#include <cstring>

#ifdef HUGINN_HAVE_VOICE
#include "tv_voice.h"
#endif

namespace
{
const int kSinkAheadMs = 60;    // keep this much queued in the speaker
const int kStep = AudioDsp::kRate / 100;   // the echo canceller works in 10 ms steps
}

AudioIO::AudioIO(QObject *parent) : QObject(parent)
{
    // Settings › Calls › Speaker: where calls start (the Speaker button still steps through them).
    m_outputId = Settings::savedSpeaker();
    m_devices = new QMediaDevices(this);
    connect(m_devices, &QMediaDevices::audioOutputsChanged, this, [this] {
        // The chosen output went away (headphones unplugged): back to the default.
        bool still = m_outputId.isEmpty();
        for (const QAudioDevice &d : QMediaDevices::audioOutputs())
            if (QString::fromLatin1(d.id()) == m_outputId)
                still = true;
        if (!still)
            setOutput(QString());
        else
            followOutputs();
        Q_EMIT outputsChanged();
    });
    // A microphone plugged in or pulled out mid-call: a call that had none picks it up, and one whose microphone
    // went away moves to the default (or back to Settings' pick when it returns).
    connect(m_devices, &QMediaDevices::audioInputsChanged, this, &AudioIO::followInputs);
    m_pump.setTimerType(Qt::PreciseTimer);
    m_pump.setInterval(10);
    connect(&m_pump, &QTimer::timeout, this, &AudioIO::pump);
}

AudioIO::~AudioIO()
{
    stop();
}

QAudioFormat AudioIO::wanted()
{
    QAudioFormat f;
    f.setSampleRate(AudioDsp::kRate);
    f.setChannelCount(1);
    f.setSampleFormat(QAudioFormat::Int16);
    return f;
}

QAudioFormat AudioIO::pick(const QAudioDevice &dev)
{
    const QAudioFormat w = wanted();
    if (dev.isFormatSupported(w))
        return w;
    QAudioFormat f = dev.preferredFormat();
    if (f.sampleFormat() == QAudioFormat::Unknown)
        f.setSampleFormat(QAudioFormat::Int16);
    return f;
}

bool AudioIO::start()
{
    if (m_running)
        return true;
    m_running = true;
#ifdef HUGINN_HAVE_VOICE
    // Echo canceller, WebRTC's noise suppressor and its level control, all at the call's 16 kHz. Its first guess
    // at the speaker-to-microphone delay: what is queued in the speaker plus the device's own latency.
    // Settings › Calls › Noise removal off leaves the noise suppressor out (read when the call starts).
    const int noise = Settings::saved(QStringLiteral("noiseRemoval"), true).toBool() ? TVV_NS : 0;
    m_voice = tvv_create(AudioDsp::kRate, AudioDsp::kRate, AudioDsp::kRate, AudioDsp::kRate, TVV_AEC | noise | TVV_AGC);
    if (m_voice)
        tvv_set_delay(m_voice, kSinkAheadMs + 40);
#endif
    const bool in = startInput(), out = startOutput();
    if (!in)
        Q_EMIT error(QStringLiteral("The microphone isn't available right now."));
    if (!out)
        Q_EMIT error(QStringLiteral("No speaker or headphones to play the call on."));
    m_pump.start();
    return in || out;
}

void AudioIO::stop()
{
    m_running = false;
    m_pump.stop();
    stopInput();
    stopOutput();
    m_mono.clear();
    m_frame.clear();
#ifdef HUGINN_HAVE_VOICE
    if (m_voice)
        tvv_destroy(m_voice);
#endif
    m_voice = nullptr;
}

QAudioDevice AudioIO::inputWanted()
{
    // Settings › Calls › Microphone, while it's plugged in; the system default otherwise.
    QAudioDevice dev = QMediaDevices::defaultAudioInput();
    const QString mic = Settings::savedMic();
    for (const QAudioDevice &d : QMediaDevices::audioInputs())
        if (!mic.isEmpty() && QString::fromLatin1(d.id()) == mic)
            dev = d;
    return dev;
}

QAudioDevice AudioIO::outputWanted() const
{
    QAudioDevice dev = QMediaDevices::defaultAudioOutput();
    for (const QAudioDevice &d : QMediaDevices::audioOutputs())
        if (!m_outputId.isEmpty() && QString::fromLatin1(d.id()) == m_outputId)
            dev = d;
    return dev;
}

void AudioIO::stopInput()
{
    if (m_source) {
        m_source->disconnect(this);
        m_source->stop();
        m_source->deleteLater();
        m_source = nullptr;
    }
    m_in = nullptr;
    m_inDev = QAudioDevice();
    m_frame.clear();
}

void AudioIO::followInputs()
{
    if (!m_running)
        return;
    const QAudioDevice want = inputWanted();
    if (m_source && !want.isNull() && want.id() == m_inDev.id())
        return;
    const bool had = m_source != nullptr;
    const QByteArray before = m_lastMic;
    stopInput();
    if (want.isNull()) {
        if (had)
            Q_EMIT deviceNotice(QStringLiteral("Microphone unplugged. Plug one in to be heard."));
        m_lastMic.clear();
        return;
    }
    if (startInput() && m_inDev.id() != before)
        Q_EMIT deviceNotice(QStringLiteral("Microphone: ") + m_inDev.description());
}

void AudioIO::followOutputs()
{
    // Following the default (no output picked): docked, the call moves to the new default (HDMI, a dock's jack).
    if (!m_running || !m_outputId.isEmpty())
        return;
    const QAudioDevice want = outputWanted();
    if (want.isNull() || (m_sink && want.id() == m_outDev.id()))
        return;
    stopOutput();
    if (startOutput())
        Q_EMIT deviceNotice(QStringLiteral("Sound: ") + m_outDev.description());
}

bool AudioIO::startInput()
{
    const QAudioDevice dev = inputWanted();
    if (dev.isNull())
        return false;
    m_inFormat = pick(dev);
    m_down.setRates(m_inFormat.sampleRate(), AudioDsp::kRate);
    m_source = new QAudioSource(dev, m_inFormat, this);
    m_source->setBufferSize(m_inFormat.bytesForDuration(40000));
    m_in = m_source->start();
    if (!m_in) {
        delete m_source;
        m_source = nullptr;
        return false;
    }
    m_inDev = dev;
    m_lastMic = dev.id();
    connect(m_in, &QIODevice::readyRead, this, &AudioIO::onInput);
    // The microphone stopped by itself (pulled out, or lost over sleep): open it, or the default, again.
    connect(m_source, &QAudioSource::stateChanged, this, [this](QAudio::State st) {
        if (st != QAudio::StoppedState || !m_source || m_source->error() == QAudio::NoError)
            return;
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        const int wait = now - m_inRestartAt < 1000 ? 1000 : 0;
        m_inRestartAt = now + wait;
        QTimer::singleShot(wait, this, [this] {
            if (!m_running)
                return;
            stopInput();
            followInputs();
        });
    });
    return true;
}

bool AudioIO::startOutput()
{
    const QAudioDevice dev = outputWanted();
    if (dev.isNull())
        return false;
    m_outFormat = pick(dev);
    m_up.setRates(AudioDsp::kRate, m_outFormat.sampleRate());
    m_sink = new QAudioSink(dev, m_outFormat, this);
    m_sink->setBufferSize(m_outFormat.bytesForDuration(120000));
    m_out = m_sink->start();
    if (!m_out) {
        delete m_sink;
        m_sink = nullptr;
        return false;
    }
    m_pending.clear();
    m_outDev = dev;
    // The speaker stopped by itself (unplugged, or lost over sleep): open the one wanted now again, once a second at most.
    connect(m_sink, &QAudioSink::stateChanged, this, [this](QAudio::State st) {
        if (st != QAudio::StoppedState || !m_sink || m_sink->error() == QAudio::NoError || m_sink->error() == QAudio::UnderrunError)
            return;
        QTimer::singleShot(1000, this, [this] {
            if (!m_running || (m_sink && m_sink->state() != QAudio::StoppedState))
                return;
            stopOutput();
            startOutput();
        });
    });
    return true;
}

void AudioIO::stopOutput()
{
    if (m_sink) {
        m_sink->disconnect(this);
        m_sink->stop();
        delete m_sink;
        m_sink = nullptr;
    }
    m_outDev = QAudioDevice();
    m_pending.clear();
}

void AudioIO::toMono(const QAudioFormat &f, const char *data, int frames, std::vector<float> *out)
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

QByteArray AudioIO::fromMono(const QAudioFormat &f, const float *data, int n)
{
    const int ch = qMax(1, f.channelCount());
    const int bps = f.bytesPerSample();
    QByteArray out(n * ch * bps, 0);
    char *p = out.data();
    for (int i = 0; i < n; ++i) {
        const float s = qBound(-1.f, data[i], 1.f);
        for (int c = 0; c < ch; ++c, p += bps) {
            switch (f.sampleFormat()) {
            case QAudioFormat::UInt8: *p = char(quint8(qRound(s * 127.f + 128.f))); break;
            case QAudioFormat::Int16: { const qint16 v = qint16(qRound(s * 32767.f)); std::memcpy(p, &v, 2); break; }
            case QAudioFormat::Int32: { const qint32 v = qint32(double(s) * 2147483647.0); std::memcpy(p, &v, 4); break; }
            case QAudioFormat::Float: std::memcpy(p, &s, 4); break;
            default: break;
            }
        }
    }
    return out;
}

void AudioIO::onInput()
{
    if (!m_in)
        return;
    const QByteArray raw = m_in->readAll();
    const int frameBytes = m_inFormat.bytesPerFrame();
    if (frameBytes <= 0 || raw.isEmpty())
        return;
    const int frames = int(raw.size()) / frameBytes;
    m_mono.clear();
    toMono(m_inFormat, raw.constData(), frames, &m_mono);
    m_resampled.clear();
    m_down.process(m_mono.data(), int(m_mono.size()), &m_resampled);
    for (float s : m_resampled) {
        m_frame.push_back(qint16(qRound(qBound(-1.f, s, 1.f) * 32767.f)));
        if (int(m_frame.size()) == AudioDsp::kFrame) {
            const qint16 *frame = m_frame.data();
#ifdef HUGINN_HAVE_VOICE
            // Without what the speaker plays it can't cancel anything, so only while the speaker runs.
            if (m_voice && m_sink) {
                m_clean.resize(AudioDsp::kFrame);
                for (int i = 0; i < AudioDsp::kFrame; i += kStep)
                    tvv_capture(m_voice, m_frame.data() + i, m_clean.data() + i);
                frame = m_clean.data();
            }
#endif
            Q_EMIT captured(QByteArray(reinterpret_cast<const char *>(frame), AudioDsp::kFrame * 2));
            m_frame.clear();
        }
    }
}

void AudioIO::pump()
{
    if (!m_sink || !m_out || !pull)
        return;
    // Write what's left over first, then fresh frames while less than kSinkAheadMs is queued.
    for (int guard = 0; guard < 8; ++guard) {
        if (!m_pending.isEmpty()) {
            const qint64 n = m_out->write(m_pending);
            if (n > 0)
                m_pending.remove(0, int(n));
            if (!m_pending.isEmpty())
                return;
        }
        const qint64 queued = m_sink->bufferSize() - m_sink->bytesFree();
        if (queued >= m_outFormat.bytesForDuration(kSinkAheadMs * 1000))
            return;
        qint16 pcm[AudioDsp::kFrame];
        pull(pcm);
#ifdef HUGINN_HAVE_VOICE
        // The echo canceller hears exactly what goes to the speaker (the same sound comes back at 16 kHz).
        if (m_voice) {
            qint16 heard[kStep];
            for (int i = 0; i < AudioDsp::kFrame; i += kStep)
                tvv_render(m_voice, pcm + i, heard);
        }
#endif
        float f[AudioDsp::kFrame];
        for (int i = 0; i < AudioDsp::kFrame; ++i)
            f[i] = float(pcm[i]) / 32768.f;
        m_resampled.clear();
        m_up.process(f, AudioDsp::kFrame, &m_resampled);
        m_pending = fromMono(m_outFormat, m_resampled.data(), int(m_resampled.size()));
    }
}

QVariantList AudioIO::outputs() const
{
    QVariantList out;
    out << QVariantMap{{QStringLiteral("id"), QString()}, {QStringLiteral("name"), QStringLiteral("Default")}};
    for (const QAudioDevice &d : QMediaDevices::audioOutputs())
        out << QVariantMap{{QStringLiteral("id"), QString::fromLatin1(d.id())}, {QStringLiteral("name"), d.description()}};
    return out;
}

QString AudioIO::outputName() const
{
    if (m_outputId.isEmpty())
        return QStringLiteral("Default");
    for (const QAudioDevice &d : QMediaDevices::audioOutputs())
        if (QString::fromLatin1(d.id()) == m_outputId)
            return d.description();
    return QStringLiteral("Default");
}

void AudioIO::setOutput(const QString &id)
{
    if (id == m_outputId)
        return;
    m_outputId = id;
    if (m_running) {
        stopOutput();
        if (!startOutput())
            Q_EMIT error(QStringLiteral("That output isn't available."));
    }
    Q_EMIT outputsChanged();
}

void AudioIO::nextOutput()
{
    const QVariantList all = outputs();
    int at = 0;
    for (int i = 0; i < all.size(); ++i)
        if (all.at(i).toMap().value(QStringLiteral("id")).toString() == m_outputId)
            at = i;
    setOutput(all.at((at + 1) % all.size()).toMap().value(QStringLiteral("id")).toString());
}
