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
    const QString usual = QStringLiteral(":/qt/qml/Huginn/assets/") + name;
    if (QFileInfo::exists(usual))
        return usual;
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
    if (m_wav.isEmpty())
        return;
    // Asynchronous: returns at once. A new call cuts off one still playing, which is fine for a chime.
    if (PlaySoundW(reinterpret_cast<LPCWSTR>(m_wav.constData()), nullptr, SND_MEMORY | SND_ASYNC | SND_NODEFAULT))
        s_playing = this;
}

bool Chime::ready() const
{
    return m_wav.startsWith("RIFF");
}
