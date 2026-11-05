#include "chime.h"

#include <QAudioDevice>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QMediaDevices>
#include <QSoundEffect>
#include <QUrl>

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
    , m_effect(new QSoundEffect(this))
{
    // Wherever the build put the file. ":/x" is "qrc:/x" to QSoundEffect.
    const QString path = Platform::resource(QFileInfo(resource).fileName());
    m_effect->setSource(QUrl(QStringLiteral("qrc") + path));
    QFile f(path);
    if (f.open(QIODevice::ReadOnly))
        m_wav = f.read(12);   // only to tell the self-test the file is there and is a WAV
}

Chime::~Chime() = default;

void Chime::play()
{
    m_effect->play();
}

void Chime::stop()
{
    m_effect->stop();
}

bool Chime::ready() const
{
    if (!m_wav.startsWith("RIFF") || m_wav.mid(8, 4) != "WAVE") {
        qWarning("raven call: %s isn't there or isn't a WAV", qPrintable(m_effect->source().toString()));
        return false;
    }
    // With no sound output at all (a CI machine) QSoundEffect reports an error; that is the machine, not the sound.
    if (QMediaDevices::audioOutputs().isEmpty())
        return true;
    if (m_effect->status() == QSoundEffect::Error) {
        qWarning("raven call: the sound didn't load (%d sound outputs)", int(QMediaDevices::audioOutputs().size()));
        return false;
    }
    return true;
}
