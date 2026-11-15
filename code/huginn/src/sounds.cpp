#include "sounds.h"
#include "settings.h"
#include "platform/chime.h"

#include <QDateTime>
#include <QLocale>
#include <QSettings>
#include <QTime>
#include <QtEndian>

#include <cmath>
#include <cstdio>

namespace
{
Sounds *s_sounds = nullptr;
int s_volume = 100;
constexpr int kRecentMax = 20;

QSettings store() { return Settings::store(); }
}

// ------------------------------------------------------------------ the volume (every Chime)

void Chime::setVolume(int percent) { s_volume = qBound(0, percent, 100); }
int Chime::volume() { return s_volume; }

QByteArray Platform::scaledWav(const QByteArray &wav, double volume)
{
    if (wav.size() < 12 || !wav.startsWith("RIFF") || wav.mid(8, 4) != "WAVE")
        return wav;
    volume = qBound(0.0, volume, 1.0);
    QByteArray out = wav;
    int bits = 0;
    bool pcm = false;
    qsizetype at = 12;
    while (at + 8 <= out.size()) {
        const QByteArray id = out.mid(at, 4);
        const quint32 size = qFromLittleEndian<quint32>(out.constData() + at + 4);
        const qsizetype body = at + 8;
        const qsizetype end = qMin<qsizetype>(out.size(), body + qsizetype(size));
        if (id == "fmt " && end - body >= 16) {
            const quint16 format = qFromLittleEndian<quint16>(out.constData() + body);
            bits = qFromLittleEndian<quint16>(out.constData() + body + 14);
            pcm = format == 1 || format == 0xFFFE;   // plain PCM, or "extensible" (which Huginn's files would be PCM in)
        } else if (id == "data") {
            if (!pcm || (bits != 8 && bits != 16 && bits != 24 && bits != 32))
                return wav;   // not something to scale safely: as it was
            char *d = out.data();
            const int bytes = bits / 8;
            for (qsizetype i = body; i + bytes <= end; i += bytes) {
                if (bits == 8) {   // unsigned, 128 is silence
                    const int v = int(quint8(d[i])) - 128;
                    d[i] = char(quint8(qBound(0, int(std::lround(v * volume)) + 128, 255)));
                } else if (bits == 16) {
                    const qint16 v = qFromLittleEndian<qint16>(d + i);
                    qToLittleEndian<qint16>(qint16(std::lround(v * volume)), d + i);
                } else if (bits == 24) {
                    qint32 v = qint32(quint8(d[i])) | (qint32(quint8(d[i + 1])) << 8) | (qint32(qint8(d[i + 2])) << 16);
                    v = qint32(std::lround(v * volume));
                    d[i] = char(v & 0xFF);
                    d[i + 1] = char((v >> 8) & 0xFF);
                    d[i + 2] = char((v >> 16) & 0xFF);
                } else {
                    const qint32 v = qFromLittleEndian<qint32>(d + i);
                    qToLittleEndian<qint32>(qint32(std::llround(double(v) * volume)), d + i);
                }
            }
        }
        at = body + qsizetype(size) + (size & 1);
    }
    return out;
}

// ------------------------------------------------------------------ the gate

bool Sounds::Gate::take(qint64 now)
{
    if (last >= 0 && now - last < kGapMs && now >= last)
        return false;
    if (now < catchUpUntil) {
        if (catchUpSounded)
            return false;
        catchUpSounded = true;
    }
    last = now;
    return true;
}

void Sounds::Gate::catchUp(qint64 now)
{
    // A reconnect inside a catch-up already going on doesn't allow another sound.
    if (now >= catchUpUntil)
        catchUpSounded = false;
    catchUpUntil = now + kCatchUpMs;
}

// ------------------------------------------------------------------ Sounds

Sounds::Sounds(QObject *parent)
    : QObject(parent)
{
    s_sounds = this;
    Chime::setVolume(volume());
    m_raven = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/raven_message.wav"), this);
    // Starting is a catch-up too: what came while Huginn was closed.
    m_gate.catchUp(QDateTime::currentMSecsSinceEpoch());
}

Sounds *Sounds::instance() { return s_sounds; }

bool Sounds::messageSound() const { return Settings::messageSoundOn(); }
bool Sounds::ringSound() const { return ringOn(); }
bool Sounds::fileSound() const { return store().value(QStringLiteral("fileSound"), true).toBool(); }
int Sounds::volume() const { return qBound(0, store().value(QStringLiteral("soundVolume"), 100).toInt(), 100); }
bool Sounds::ringOn() { return Settings::saved(QStringLiteral("ringSound"), true).toBool(); }

void Sounds::setMessageSound(bool v)
{
    if (v == messageSound())
        return;
    store().setValue(QStringLiteral("messageSound"), v);
    Q_EMIT changed();
}

void Sounds::setRingSound(bool v)
{
    if (v == ringSound())
        return;
    store().setValue(QStringLiteral("ringSound"), v);
    Q_EMIT changed();
}

void Sounds::setFileSound(bool v)
{
    if (v == fileSound())
        return;
    store().setValue(QStringLiteral("fileSound"), v);
    Q_EMIT changed();
}

void Sounds::setVolume(int v)
{
    v = qBound(0, v, 100);
    if (v == volume())
        return;
    store().setValue(QStringLiteral("soundVolume"), v);
    Chime::setVolume(v);
    Q_EMIT changed();
}

QVariantList Sounds::recent() const
{
    QVariantList out;
    for (auto it = m_recent.crbegin(); it != m_recent.crend(); ++it)
        out << *it;
    return out;
}

bool Sounds::raven(const QString &kind, const QString &chat, const QString &why)
{
    if (Settings::hushed())
        return false;   // Do not disturb or quiet hours
    if (kind == QLatin1String("File offer") ? !fileSound() : !messageSound())
        return false;
    if (!m_gate.take(QDateTime::currentMSecsSinceEpoch()))
        return false;
    m_raven->play();
    add(kind, chat, why);
    return true;
}

void Sounds::catchUp()
{
    m_gate.catchUp(QDateTime::currentMSecsSinceEpoch());
}

void Sounds::note(const QString &kind, const QString &chat, const QString &why)
{
    if (s_sounds)
        s_sounds->add(kind, chat, why);
}

void Sounds::test()
{
    m_raven->play();
}

void Sounds::clearRecent()
{
    if (m_recent.isEmpty())
        return;
    m_recent.clear();
    Q_EMIT recentChanged();
}

void Sounds::add(const QString &kind, const QString &chat, const QString &why)
{
    // A name only when notifications may show one now (Settings › Notifications › What notifications show).
    const bool named = previewMode && previewMode() >= 1;
    m_recent.append(QVariantMap{{QStringLiteral("time"), QLocale::system().toString(QTime::currentTime(), QLocale::ShortFormat)},
                                {QStringLiteral("kind"), kind},
                                {QStringLiteral("chat"), named ? chat.left(60) : QString()},
                                {QStringLiteral("why"), why}});
    while (m_recent.size() > kRecentMax)
        m_recent.removeFirst();
    Q_EMIT recentChanged();
}

bool Sounds::selfTest()
{
    bool ok = true;
    auto check = [&ok](const char *what, bool good) {
        std::printf("sounds: %s: %s\n", what, good ? "ok" : "FAILED");
        ok = ok && good;
    };
    // The gate: one at a time, a few seconds apart; a catch-up burst sounds once.
    {
        Gate g;
        const qint64 t = 1000000;
        check("the first sound plays", g.take(t));
        check("another within the gap doesn't", !g.take(t + 1000) && !g.take(t + Gate::kGapMs - 1));
        check("one after the gap does", g.take(t + Gate::kGapMs));
        g.catchUp(t + 20000);
        check("a catch-up's first sound plays", g.take(t + 20000));
        bool more = false;
        for (qint64 s = 1; s < 30; ++s)
            more = more || g.take(t + 20000 + s * 1000);
        check("the rest of a catch-up burst stays quiet", !more);
        check("after the catch-up, sounds play again", g.take(t + 20000 + Gate::kCatchUpMs + 1));
        Gate h;
        h.catchUp(t);
        h.take(t);
        h.catchUp(t + 10000);   // a reconnect during the catch-up
        check("a reconnect during a catch-up allows no second sound", !h.take(t + 16000));
        Gate c;
        c.take(t);
        check("a clock that went back doesn't hold sounds off", c.take(t - 60000));
    }
    // The volume on Windows: PCM samples scaled in memory, the header untouched.
    {
        QByteArray wav("RIFF\0\0\0\0WAVE", 12);   // its size goes in once it's all there
        QByteArray fmt(16, '\0');
        wav += "fmt ";
        QByteArray n(4, '\0');
        qToLittleEndian<quint32>(16, n.data());
        wav += n;
        qToLittleEndian<quint16>(1, fmt.data());        // PCM
        qToLittleEndian<quint16>(1, fmt.data() + 2);    // mono
        qToLittleEndian<quint32>(8000, fmt.data() + 4);
        qToLittleEndian<quint32>(16000, fmt.data() + 8);
        qToLittleEndian<quint16>(2, fmt.data() + 12);
        qToLittleEndian<quint16>(16, fmt.data() + 14);
        wav += fmt;
        wav += "data";
        qToLittleEndian<quint32>(6, n.data());
        wav += n;
        QByteArray samples(6, '\0');
        qToLittleEndian<qint16>(20000, samples.data());
        qToLittleEndian<qint16>(-20000, samples.data() + 2);
        qToLittleEndian<qint16>(101, samples.data() + 4);
        wav += samples;
        qToLittleEndian<quint32>(quint32(wav.size() - 8), wav.data() + 4);
        const QByteArray half = Platform::scaledWav(wav, 0.5);
        const qsizetype d = wav.size() - 6;
        check("half volume halves each sample", half.size() == wav.size() && half.left(d) == wav.left(d)
                                                    && qFromLittleEndian<qint16>(half.constData() + d) == 10000
                                                    && qFromLittleEndian<qint16>(half.constData() + d + 2) == -10000
                                                    && qAbs(qFromLittleEndian<qint16>(half.constData() + d + 4) - 50) <= 1);
        check("full volume leaves it as it was", Platform::scaledWav(wav, 1.0) == wav);
        check("silence is silence", qFromLittleEndian<qint16>(Platform::scaledWav(wav, 0.0).constData() + d) == 0);
        check("something that isn't a WAV is left alone", Platform::scaledWav(QByteArray("not a wav file"), 0.5) == QByteArray("not a wav file"));
        QByteArray cut = wav.left(wav.size() - 3);   // a data chunk that says more than is there
        check("a cut-off file doesn't read past its end", Platform::scaledWav(cut, 0.5).size() == cut.size());
    }
    return ok;
}
