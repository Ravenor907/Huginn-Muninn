#include "globalkeys.h"
#include "settings.h"
#include "talkkey.h"
#include "call/roommanager.h"

GlobalKeys::GlobalKeys(Settings *settings, RoomManager *rooms, TalkKey *talkKey, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_rooms(rooms)
    , m_talkKey(talkKey)
{
    m_backend.reset(makeGlobalKeysBackend(this));
    connect(m_rooms, &RoomManager::changed, this, &GlobalKeys::update);
    connect(m_settings, &Settings::changed, this, &GlobalKeys::update);
    connect(m_talkKey, &TalkKey::changed, this, &GlobalKeys::update);
    update();
}

GlobalKeys::~GlobalKeys() = default;

bool GlobalKeys::voice() const
{
    return m_rooms->inRoom() || (inCall && inCall());
}

void GlobalKeys::update()
{
    const bool talk = m_settings->pushToTalk() && m_rooms->inRoom();
    // Out of the room with the key still down: let go here too (TalkKey does the same for its own presses).
    if (!talk && m_talking) {
        m_talking = false;
        m_rooms->setTalking(false);
    }
    if (m_backend)
        m_backend->want(voice(), talk, m_talkKey->key());
}

void GlobalKeys::key(Key k, bool down)
{
    switch (k) {
    case Talk:
        if (!m_settings->pushToTalk() || !m_rooms->inRoom() || down == m_talking)
            return;
        m_talking = down;
        m_rooms->setTalking(down);
        return;
    case Mute:
        if (down && voice())
            Q_EMIT mutePressed();
        return;
    case Deafen:
        if (down && voice())
            Q_EMIT deafenPressed();
        return;
    }
}

bool GlobalKeys::everywhere() const
{
    return m_backend && m_backend->everywhere();
}

QString GlobalKeys::talkNote() const
{
#ifdef Q_OS_WIN
    // Huginn never watches the keyboard on Windows, so a held key only counts while Huginn is in front.
    return QStringLiteral("Works while Huginn is in front. In games, use voice activity or Ctrl+Shift+M to mute");
#endif
    return everywhere() ? QStringLiteral("Voice rooms: hold a key. Works while a game is in front")
                        : QStringLiteral("Works only while Huginn is in front here; use voice activity");
}

QString GlobalKeys::keysNote() const
{
    if (!everywhere())
        return QStringLiteral("Ctrl+Shift+M and Ctrl+Shift+D, only while Huginn is in front here");
    const QString taken = m_backend->taken();
    if (!taken.isEmpty())
        return QStringLiteral("%1 is in use by another app, so it works only while Huginn is in front").arg(taken);
    return QStringLiteral("Ctrl+Shift+M and Ctrl+Shift+D, even while a game is in front");
}
