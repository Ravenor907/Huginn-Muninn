#include "chime.h"

#include <QDirIterator>
#include <QFile>
#include <QFileInfo>

#include <windows.h>
#include <mmsystem.h>

namespace
{
// PlaySound plays one sound per program at a time: the Chime that started the one playing now, so a
// Chime only stops (or, going away, frees the bytes of) its own sound.
const Chime *s_playing = nullptr;
}

QString Platform::resource(const QString &name)
{
    // Where the build puts the module's assets (qml/assets/, as in the source), or the short alias. Looking through
    // every resource took about 20 ms each time, and seven sounds are made at start.
    for (const QLatin1String dir : {QLatin1String(":/qt/qml/Huginn/qml/assets/"), QLatin1String(":/qt/qml/Huginn/assets/")}) {
        const QString path = dir + name;
        if (QFileInfo::exists(path))
            return path;
    }
    QDirIterator it(QStringLiteral(":/"), {name}, QDir::Files, QDirIterator::Subdirectories);
    return it.hasNext() ? it.next() : QString();
}

Chime::Chime(const QString &resource, QObject *parent)
    : QObject(parent)
{
    QFile f(Platform::resource(QFileInfo(resource).fileName()));
    if (f.open(QIODevice::ReadOnly))
        m_wav = f.readAll();
    if (!ready())
        qWarning("raven call: %s couldn't be read (%d bytes)", qPrintable(resource), int(m_wav.size()));
}

Chime::~Chime()
{
    stop();   // before the bytes go away
}

void Chime::stop()
{
    if (s_playing != this)
        return;
    PlaySoundW(nullptr, nullptr, 0);
    s_playing = nullptr;
}

void Chime::play()
{
    if (m_wav.isEmpty() || volume() <= 0)
        return;
    // PlaySound has no volume: below 100% a scaled copy plays. The copy it may still be reading is never changed:
    // this Chime's sound is stopped first.
    if (volume() < 100 && (m_scaled.isEmpty() || m_scaledAt != volume())) {
        if (s_playing == this) {
            PlaySoundW(nullptr, nullptr, 0);
            s_playing = nullptr;
        }
        m_scaled = Platform::scaledWav(m_wav, volume() / 100.0);
        m_scaledAt = volume();
    }
    const QByteArray &bytes = volume() < 100 && !m_scaled.isEmpty() ? m_scaled : m_wav;
    // Asynchronous: returns at once. A new call cuts off one still playing, which is fine for a chime.
    if (PlaySoundW(reinterpret_cast<LPCWSTR>(bytes.constData()), nullptr, SND_MEMORY | SND_ASYNC | SND_NODEFAULT))
        s_playing = this;
}

bool Chime::ready() const
{
    return m_wav.startsWith("RIFF");
}
