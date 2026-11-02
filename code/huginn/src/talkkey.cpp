#include "talkkey.h"
#include "settings.h"
#include "call/roommanager.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QKeySequence>
#include <QSettings>
#include <QVariant>
#include <QWindow>

TalkKey::TalkKey(Settings *settings, RoomManager *rooms, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_rooms(rooms)
    , m_key(firstKey())
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
    // Huginn in the background: the key can't be seen going up, so let go now.
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState s) {
        if (s != Qt::ApplicationActive)
            release();
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
    if (!m_down)
        return;
    m_down = false;
    m_rooms->setTalking(false);
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
