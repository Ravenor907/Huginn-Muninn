#include "talkkey.h"
#include "settings.h"
#include "call/roommanager.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QKeySequence>
#include <QSettings>
#include <QStringList>
#include <QVariant>
#include <QWindow>

TalkKey::TalkKey(Settings *settings, RoomManager *rooms, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_rooms(rooms)
    , m_key(firstKey())
    , m_pad(Settings::store().value(QStringLiteral("talkPad")).toString())
{
    qApp->installEventFilter(this);
    // A text box taking or losing the focus changes what a key that types does (the voice line says so).
    connect(qApp, &QGuiApplication::focusObjectChanged, this, [this] {
        const bool now = boxFocused();
        if (now != m_inBox) {
            m_inBox = now;
            Q_EMIT changed();
        }
    });
    // Huginn in the background: the key can't be seen going up, so let go now. Picking a key stops too, so the first
    // key typed somewhere else later never becomes the talk key.
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState s) {
        if (s != Qt::ApplicationActive) {
            release();
            stopCapture();
        }
    });
    connect(m_rooms, &RoomManager::changed, this, [this] {
        if (!m_rooms->inRoom())
            release();
    });
}

// The saved key. A new install has none, so the first talk key never types into a message: Settings › Calls asks
// for one when push to talk goes on. Someone who had push to talk before keeps the old default, Backtick.
int TalkKey::firstKey()
{
    QSettings s = Settings::store();
    if (!s.contains(QStringLiteral("talkKey")))
        s.setValue(QStringLiteral("talkKey"), s.contains(QStringLiteral("pushToTalk")) ? int(Qt::Key_QuoteLeft) : 0);
    return s.value(QStringLiteral("talkKey"), 0).toInt();
}

QString TalkKey::keyName() const
{
    if (m_key == 0)
        return QStringLiteral("no key yet");
    if (m_key == Qt::Key_Space)
        return QStringLiteral("Space");
    // The old default: "`" alone is easy to miss in "hold `".
    if (m_key == Qt::Key_QuoteLeft)
        return QStringLiteral("Backtick (`)");
    const QString n = QKeySequence(m_key).toString(QKeySequence::NativeText);
    return n.isEmpty() ? QStringLiteral("Key %1").arg(m_key) : n;
}

void TalkKey::capture()
{
    m_capturing = true;
    Q_EMIT changed();
}

void TalkKey::stopCapture()
{
    if (!m_capturing)
        return;
    m_capturing = false;
    Q_EMIT changed();
}

void TalkKey::release()
{
    if (!m_down && !m_padDown)
        return;
    m_down = false;
    m_padDown = false;
    m_rooms->setTalking(false);
}

void TalkKey::clearPad()
{
    if (m_pad.isEmpty())
        return;
    release();
    m_pad.clear();
    Settings::store().remove(QStringLiteral("talkPad"));
    Q_EMIT changed();
}

bool TalkKey::pad(const QString &button, bool down)
{
    if (m_capturing) {
        if (!down)
            return true;
        // B keeps the old key and button.
        if (button == QLatin1String("b")) {
            stopCapture();
            return true;
        }
        // A, the D-pad, Start and View, the sticks, and RB and RT (the stick pointer's clicks) can't be used: wait for another.
        static const QStringList usable{QStringLiteral("lb"), QStringLiteral("lt"), QStringLiteral("x"), QStringLiteral("y")};
        if (!usable.contains(button))
            return true;
        m_pad = button;
        Settings::store().setValue(QStringLiteral("talkPad"), button);
        stopCapture();
        return true;
    }
    if (m_pad.isEmpty() || button != m_pad || !m_settings->pushToTalk() || !m_rooms->inRoom()) {
        // Let go of in a room that has just ended: nothing to do, but it's still the talk button's release.
        if (!down && button == m_pad && m_padDown) {
            release();
            return true;
        }
        return false;
    }
    if (down == m_padDown)
        return true;
    m_padDown = down;
    m_rooms->setTalking(down || m_down);
    return true;
}

// A key that puts a character in a text box (Backtick, Space, a letter). Caps Lock, F13, Insert, Pause and the
// like type nothing, so they always talk.
static bool typesText(const QKeyEvent *k)
{
    const QString t = k->text();
    return !t.isEmpty() && t.at(0).isPrint();
}

bool TalkKey::keyTypes() const
{
    if (m_key == 0)
        return false;
    // Qt's keys below 0x01000000 are characters; the ones above (F keys, Caps Lock, Insert) aren't.
    return m_key < 0x01000000;
}

// A text box has the focus (a message, a name, a search): a talk key that types goes there instead of talking, so a
// Backtick or Space in a message still works. A read-only text (a message being selected) doesn't count.
bool TalkKey::boxFocused()
{
    QObject *f = QGuiApplication::focusObject();
    if (!f || !(f->inherits("QQuickTextInput") || f->inherits("QQuickTextEdit")))
        return false;
    return !f->property("readOnly").toBool();
}

bool TalkKey::eventFilter(QObject *watched, QEvent *event)
{
    const QEvent::Type t = event->type();
    if (t != QEvent::KeyPress && t != QEvent::KeyRelease)
        return QObject::eventFilter(watched, event);
    // Each key reaches the window first, then the item with the focus: act on it once, at the window.
    if (!qobject_cast<QWindow *>(watched))
        return QObject::eventFilter(watched, event);
    auto *k = static_cast<QKeyEvent *>(event);
    const int key = k->key();
    if (m_capturing && t == QEvent::KeyPress) {
        if (key == Qt::Key_Escape) {
            stopCapture();
            return true;
        }
        // Keys that move around Huginn, or a modifier on its own, can't be used: wait for another.
        const bool bad = key == Qt::Key_Return || key == Qt::Key_Enter || key == Qt::Key_Tab || key == Qt::Key_Backtab
                      || key == Qt::Key_unknown || key == 0 || key == Qt::Key_Shift || key == Qt::Key_Control
                      || key == Qt::Key_Alt || key == Qt::Key_Meta || key == Qt::Key_AltGr;
        if (bad)
            return true;
        m_key = key;
        Settings::store().setValue(QStringLiteral("talkKey"), key);
        stopCapture();
        return true;
    }
    if (m_key == 0 || key != m_key || !m_settings->pushToTalk() || !m_rooms->inRoom())
        return QObject::eventFilter(watched, event);
    // Typing: a key that types goes to the text box. Held down from before the box took the focus, it still lets go
    // here. A key that types nothing talks even while a box has the focus.
    if (!m_down && typesText(k) && boxFocused()) {
        if (t == QEvent::KeyPress && !k->isAutoRepeat() && !m_toldTyped) {
            m_toldTyped = true;
            Q_EMIT typedInstead();
        }
        return QObject::eventFilter(watched, event);
    }
    if (k->isAutoRepeat())
        return true;
    if (t == QEvent::KeyPress) {
        m_down = true;
        m_rooms->setTalking(true);
    } else {
        release();
    }
    return true;
}
