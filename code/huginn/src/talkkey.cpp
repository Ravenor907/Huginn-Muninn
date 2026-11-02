#include "talkkey.h"
#include "settings.h"
#include "call/roommanager.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QKeySequence>
#include <QSettings>
#include <QWindow>

TalkKey::TalkKey(Settings *settings, RoomManager *rooms, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_rooms(rooms)
    , m_key(Settings::saved(QStringLiteral("talkKey"), int(Qt::Key_QuoteLeft)).toInt())
{
    qApp->installEventFilter(this);
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

QString TalkKey::keyName() const
{
    if (m_key == Qt::Key_Space)
        return QStringLiteral("Space");
    // The default: "`" alone is easy to miss in "hold `".
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
    if (key != m_key || !m_settings->pushToTalk() || !m_rooms->inRoom())
        return QObject::eventFilter(watched, event);
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
