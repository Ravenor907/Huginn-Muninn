#include "talkkey.h"
#include "settings.h"
#include "call/roommanager.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QKeySequence>
#include <QSettings>
#include <QStringList>
#include <QTimer>
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
    m_padWait = new QTimer(this);
    m_padWait->setSingleShot(true);
    m_padWait->setInterval(150);
    connect(m_padWait, &QTimer::timeout, this, [this] {
        if (m_padDown && !m_padHushed)
            m_rooms->setTalking(true);
    });
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
        if (!m_rooms->inRoom()) {
            release();
            // The next room decides afresh: voice activity at join time if there's still no controller.
            if (m_padGone) {
                m_padGone = false;
                Q_EMIT changed();
            }
        }
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
    m_padTook = false;
    m_pad.clear();
    Settings::store().remove(QStringLiteral("talkPad"));
    Q_EMIT changed();
}

void TalkKey::setPadConnected(bool connected)
{
    if (connected == m_padConnected)
        return;
    m_padConnected = connected;
    // The controller went away: its talk button can't be seen going up, so let go.
    if (!connected && m_padDown) {
        m_padDown = false;
        m_padTook = false;
        m_rooms->setTalking(m_down);
    }
    // Gone (or asleep) mid-room while it held the only talk button: push to talk stays on (main.cpp) and the mic is
    // muted, so whatever is said off-mic never goes out. Set before changed(), so push to talk never drops for a moment.
    bool gone = false;
    bool back = false;
    if (!connected && m_key == 0 && !m_pad.isEmpty() && m_rooms->inRoom() && m_rooms->pushToTalk()) {
        m_padGone = true;
        gone = true;
    } else if (connected && m_padGone) {
        m_padGone = false;
        back = true;
    }
    Q_EMIT changed();
    if (gone)
        Q_EMIT controllerGone();
    if (back)
        Q_EMIT controllerBack();
}

void TalkKey::clearKey()
{
    if (m_key == 0)
        return;
    if (m_down) {
        m_down = false;
        m_rooms->setTalking(m_padDown);
    }
    m_key = 0;
    Settings::store().setValue(QStringLiteral("talkKey"), 0);
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
        // A back paddle has no other job. LT opens the options, which X opens too, so it can talk in a voice room (the
        // legend then shows X for them). Y and LB can be picked, but in a voice room they talk instead (Settings says so,
        // and LB with Up or Down still mutes and deafens there). A, B, X, the D-pad, Start and View, the sticks, and RB
        // and RT (the stick pointer's clicks) can't be used: wait for another. Settings says why (not for the D-pad,
        // which repeats).
        static const QStringList usable{QStringLiteral("lt"), QStringLiteral("p1"), QStringLiteral("p2"), QStringLiteral("p3"),
                                        QStringLiteral("p4"), QStringLiteral("y"), QStringLiteral("lb")};
        static const QStringList dpad{QStringLiteral("up"), QStringLiteral("down"), QStringLiteral("left"), QStringLiteral("right")};
        if (!usable.contains(button)) {
            if (!dpad.contains(button))
                Q_EMIT padRefused(button);
            return true;
        }
        m_pad = button;
        Settings::store().setValue(QStringLiteral("talkPad"), button);
        stopCapture();
        Q_EMIT padPicked(button);
        return true;
    }
    // A release: the talk button's only when its press talked (a press that did the button's usual job, outside a
    // voice room, gets its usual release too).
    if (!down) {
        if (m_pad.isEmpty() || button != m_pad || !m_padTook)
            return false;
        m_padTook = false;
        if (m_padDown) {
            m_padDown = false;
            m_rooms->setTalking(m_down);
        }
        return true;
    }
    // LB held as the talk button, then Up or Down (mute or deafen, Main.qml): the mic stays closed for the rest of this
    // hold. The press still goes on to do its job.
    if (m_padDown && m_pad == QLatin1String("lb") && (button == QLatin1String("up") || button == QLatin1String("down"))) {
        m_padHushed = true;
        m_padWait->stop();
        m_rooms->setTalking(m_down);
        return false;
    }
    // A press: the talk button talks only in a voice room with push to talk on; anywhere else it does its usual job.
    if (m_pad.isEmpty() || button != m_pad)
        return false;
    if (!m_settings->pushToTalk() || !m_rooms->inRoom()) {
        m_padTook = false;
        return false;
    }
    m_padTook = true;
    if (!m_padDown) {
        m_padDown = true;
        m_padHushed = false;
        if (m_pad == QLatin1String("lb"))
            m_padWait->start();
        else
            m_rooms->setTalking(true);
    }
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
