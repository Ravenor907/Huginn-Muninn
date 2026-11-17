#include "chime.h"

#include <QAudioDevice>
#include <QDirIterator>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QMediaDevices>
#include <QSoundEffect>
#include <QTimer>
#include <QUrl>

#include <utility>

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
    , m_letGo(new QTimer(this))
{
    // Wherever the build put the file. ":/x" is "qrc:/x" to QSoundEffect.
    const QString path = Platform::resource(QFileInfo(resource).fileName());
    m_source = QStringLiteral("qrc") + path;
    QFile f(path);
    if (f.open(QIODevice::ReadOnly))
        m_wav = f.read(12);   // only to tell the self-test the file is there and is a WAV
    // A loaded QSoundEffect keeps a sound stream open, asking for sound every few milliseconds even in silence (about
    // 190 wake-ups a second, and the system's audio kept awake, on battery too). So it is made only to play, and let go of
    // 10 seconds after it last played (a ring plays again every 3 seconds and keeps the same one).
    m_letGo->setSingleShot(true);
    m_letGo->setInterval(10000);
    connect(m_letGo, &QTimer::timeout, this, [this] {
        if (!m_effect || m_effect->isPlaying())
            return;   // playing again: it lets go once that ends
        delete std::exchange(m_effect, nullptr);
    });
}

Chime::~Chime() = default;

QSoundEffect *Chime::effect()
{
    // One that failed to load (no sound output then, say) is made again with what is there now.
    if (m_effect && m_effect->status() == QSoundEffect::Error)
        delete std::exchange(m_effect, nullptr);
    if (!m_effect) {
        m_effect = new QSoundEffect(this);
        connect(m_effect, &QSoundEffect::playingChanged, m_letGo, [this] {
            if (m_effect && !m_effect->isPlaying())
                m_letGo->start();
        });
        m_effect->setSource(QUrl(m_source));
    }
    m_letGo->start();   // also let go of one that never gets to play (the self-test's)
    return m_effect;
}

void Chime::play()
{
    if (volume() <= 0)
        return;
    QSoundEffect *e = effect();
    m_letGo->stop();   // started again when it ends
    e->setVolume(volume() / 100.0);
    e->play();   // a new one plays as soon as it has loaded (a moment)
}

void Chime::stop()
{
    if (m_effect)
        m_effect->stop();
}

bool Chime::ready() const
{
    if (!m_wav.startsWith("RIFF") || m_wav.mid(8, 4) != "WAVE") {
        qWarning("raven call: %s isn't there or isn't a WAV", qPrintable(m_source));
        return false;
    }
    // With no sound output at all (a CI machine) QSoundEffect reports an error; that is the machine, not the sound.
    if (QMediaDevices::audioOutputs().isEmpty())
        return true;
    // Loaded now, as it is to play (for the self-test), waiting up to 3 seconds for it.
    QSoundEffect *e = const_cast<Chime *>(this)->effect();
    if (e->status() == QSoundEffect::Loading || e->status() == QSoundEffect::Null) {
        QEventLoop wait;
        QTimer::singleShot(3000, &wait, &QEventLoop::quit);
        connect(e, &QSoundEffect::statusChanged, &wait, [e, &wait] {
            if (e->status() != QSoundEffect::Loading)
                wait.quit();
        });
        wait.exec();
    }
    if (e->status() == QSoundEffect::Error) {
        qWarning("raven call: the sound didn't load (%d sound outputs)", int(QMediaDevices::audioOutputs().size()));
        return false;
    }
    return true;
}
