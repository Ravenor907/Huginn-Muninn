#include "globalkeys.h"
#include "settings.h"
#include "talkkey.h"
#include "call/roommanager.h"
#include "platform/chime.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QKeySequence>
#include <QSettings>
#include <QWindow>

namespace
{
// Where each combination is kept, and its default.
QString storeKey(GlobalKeys::Key k) { return k == GlobalKeys::Mute ? QStringLiteral("keys/mute") : QStringLiteral("keys/deafen"); }
QString fallback(GlobalKeys::Key k) { return k == GlobalKeys::Mute ? QStringLiteral("Ctrl+Shift+M") : QStringLiteral("Ctrl+Shift+D"); }
QKeyCombination combo(GlobalKeys::Key k)
{
    const QKeySequence s = QKeySequence::fromString(Settings::saved(storeKey(k), fallback(k)).toString(), QKeySequence::PortableText);
    if (s.count() == 1 && s[0].key() != Qt::Key_unknown && (s[0].keyboardModifiers() & (Qt::ControlModifier | Qt::AltModifier)))
        return s[0];
    return QKeySequence::fromString(fallback(k), QKeySequence::PortableText)[0];
}
}

GlobalKeys::GlobalKeys(Settings *settings, RoomManager *rooms, TalkKey *talkKey, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_rooms(rooms)
    , m_talkKey(talkKey)
{
    m_cueOn = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/cue_on.wav"), this);
    m_cueOff = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/cue_off.wav"), this);
    m_deafOn = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/cue_deaf_on.wav"), this);
    m_deafOff = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/cue_deaf_off.wav"), this);
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
    // Push to talk as the room uses it: off while no talk key is picked (voice activity then).
    const bool talk = m_rooms->pushToTalk() && m_rooms->inRoom();
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
    // A key that reached Huginn while another window was in front: from now on the notes may say so.
    if (down && !m_heardBehind && QGuiApplication::applicationState() != Qt::ApplicationActive) {
        m_heardBehind = true;
        Q_EMIT changed();
    }
    switch (k) {
    case Talk:
        if (!m_rooms->pushToTalk() || !m_rooms->inRoom() || down == m_talking)
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

int GlobalKeys::comboKey(Key k) const { return int(combo(k).key()); }
int GlobalKeys::comboMods(Key k) const { return int(combo(k).keyboardModifiers().toInt()); }
QString GlobalKeys::sequence(Key k) const { return QKeySequence(combo(k)).toString(QKeySequence::PortableText); }
QString GlobalKeys::comboName(Key k) const { return QKeySequence(combo(k)).toString(QKeySequence::NativeText); }

bool GlobalKeys::changeable() const
{
#ifdef Q_OS_WIN
    return true;
#else
    return false;
#endif
}

void GlobalKeys::capture(int which)
{
    if (which != 1 && which != 2)
        return;
    if (m_capturing == 0)
        qApp->installEventFilter(this);
    m_capturing = which;
    m_captureNote = QStringLiteral("Press the new keys, with Ctrl or Alt. Esc keeps %1").arg(comboName(which == 1 ? Mute : Deafen));
    Q_EMIT changed();
}

void GlobalKeys::stopCapture()
{
    if (m_capturing == 0)
        return;
    m_capturing = 0;
    m_captureNote.clear();
    qApp->removeEventFilter(this);
    Q_EMIT changed();
}

void GlobalKeys::resetKeys()
{
    QSettings s = Settings::store();
    s.remove(storeKey(Mute));
    s.remove(storeKey(Deafen));
    s.sync();
    if (m_backend)
        m_backend->rebind();
    Q_EMIT changed();
}

bool GlobalKeys::eventFilter(QObject *watched, QEvent *event)
{
    const QEvent::Type t = event->type();
    if (m_capturing == 0 || (t != QEvent::KeyPress && t != QEvent::ShortcutOverride))
        return QObject::eventFilter(watched, event);
    // While waiting, no shortcut fires (Ctrl+Shift+M itself may be what's pressed).
    if (t == QEvent::ShortcutOverride) {
        event->accept();
        return false;
    }
    // Each key reaches the window first, then the item with the focus: act on it once, at the window.
    if (!qobject_cast<QWindow *>(watched))
        return true;
    auto *e = static_cast<QKeyEvent *>(event);
    const int key = e->key();
    if (key == Qt::Key_Escape) {
        stopCapture();
        return true;
    }
    if (key == Qt::Key_Shift || key == Qt::Key_Control || key == Qt::Key_Alt || key == Qt::Key_Meta || key == Qt::Key_AltGr || key == 0
        || key == Qt::Key_unknown)
        return true;   // a modifier on its own: wait for the key
    const Qt::KeyboardModifiers mods = e->modifiers() & (Qt::ControlModifier | Qt::AltModifier | Qt::ShiftModifier | Qt::MetaModifier);
    if (!(mods & (Qt::ControlModifier | Qt::AltModifier))) {
        m_captureNote = QStringLiteral("Hold Ctrl or Alt with it, so it never gets in the way of typing or a game");
        Q_EMIT changed();
        return true;
    }
    const Key mine = m_capturing == 1 ? Mute : Deafen, other = m_capturing == 1 ? Deafen : Mute;
    const QKeyCombination c(mods, Qt::Key(key));
    if (c == combo(other)) {
        m_captureNote = QStringLiteral("%1 is already the %2 key. Press other keys").arg(QKeySequence(c).toString(QKeySequence::NativeText),
                                                                                         other == Mute ? QStringLiteral("Mute") : QStringLiteral("Deafen"));
        Q_EMIT changed();
        return true;
    }
    {
        QSettings s = Settings::store();
        s.setValue(storeKey(mine), QKeySequence(c).toString(QKeySequence::PortableText));
        s.sync();
    }
    stopCapture();
    if (m_backend)
        m_backend->rebind();
    Q_EMIT changed();
    return true;
}

void GlobalKeys::cue(bool on, bool deafen)
{
    if (!m_settings->muteSounds())
        return;
    if (deafen)
        (on ? m_deafOn : m_deafOff)->play();
    else
        (on ? m_cueOn : m_cueOff)->play();
}

bool GlobalKeys::everywhere() const
{
    return m_backend && m_backend->everywhere();
}

QString GlobalKeys::talkNote() const
{
#ifdef Q_OS_WIN
    // Huginn never watches the keyboard on Windows, so a held key only counts while Huginn is in front.
    return QStringLiteral("Voice rooms only, and only while Huginn is in front: it never watches keys in other apps. For games, turn this off to use voice activity, or mute with %1").arg(comboName(Mute));
#endif
    if (!everywhere())
        return QStringLiteral("Only while Huginn is in front. While you play, use voice activity");
    // The desktop offers the key, but only a press that arrived with Huginn behind proves it works while you play.
    return m_heardBehind ? QStringLiteral("Voice rooms: hold a key. Works while a game is in front")
                         : QStringLiteral("Voice rooms: hold a key. It may work while you play too; try it once");
}

QString GlobalKeys::keysNote() const
{
    const QString keys = QStringLiteral("%1 and %2").arg(comboName(Mute), comboName(Deafen));
    // Discord's own Toggle Mute and Toggle Deafen are the same two keys, and it may not show as "in use".
    const bool clash = changeable() && (muteSequence() == QLatin1String("Ctrl+Shift+M") || deafenSequence() == QLatin1String("Ctrl+Shift+D"));
    const QString discord = clash ? QStringLiteral(". Discord uses these too. If you run both, change them below") : QString();
    if (!everywhere())
        return QStringLiteral("Only while Huginn is in front: %1, or hold LB with Up or Down%2").arg(keys, discord);
    const QString taken = m_backend->taken();
    if (!taken.isEmpty())
        return QStringLiteral("%1 is in use by another app, so it works only while Huginn is in front.%2").arg(taken, changeable() ? QStringLiteral(" Change it below") : QString());
    return m_heardBehind ? QStringLiteral("%1, even while a game is in front%2").arg(keys, discord)
                         : QStringLiteral("%1. They may work while you play too; try them once%2").arg(keys, discord);
}
