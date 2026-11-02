// Windows: Mute and Deafen through RegisterHotKey, push to talk only while Huginn is in front (see globalkeys.h).
// Neither sees any other key, and neither is a keyboard hook.
#include "../globalkeys.h"

#include <QAbstractNativeEventFilter>
#include <QChar>
#include <QCoreApplication>
#include <QGuiApplication>
#include <QTimer>

#include <windows.h>

namespace
{
const int kMuteId = 0x4D31;     // RegisterHotKey ids, Huginn's own (0x0000 to 0xBFFF)
const int kDeafenId = 0x4D32;

// TalkKey's Qt key as a Windows virtual key (0 when there is none).
int virtualKey(int k)
{
    if ((k >= Qt::Key_A && k <= Qt::Key_Z) || (k >= Qt::Key_0 && k <= Qt::Key_9))
        return k;   // the same codes
    if (k >= Qt::Key_F1 && k <= Qt::Key_F24)
        return VK_F1 + (k - Qt::Key_F1);
    switch (k) {
    case Qt::Key_Space: return VK_SPACE;
    case Qt::Key_Backspace: return VK_BACK;
    case Qt::Key_Insert: return VK_INSERT;
    case Qt::Key_Delete: return VK_DELETE;
    case Qt::Key_Home: return VK_HOME;
    case Qt::Key_End: return VK_END;
    case Qt::Key_PageUp: return VK_PRIOR;
    case Qt::Key_PageDown: return VK_NEXT;
    case Qt::Key_Left: return VK_LEFT;
    case Qt::Key_Right: return VK_RIGHT;
    case Qt::Key_Up: return VK_UP;
    case Qt::Key_Down: return VK_DOWN;
    case Qt::Key_CapsLock: return VK_CAPITAL;
    case Qt::Key_ScrollLock: return VK_SCROLL;
    case Qt::Key_NumLock: return VK_NUMLOCK;
    case Qt::Key_Pause: return VK_PAUSE;
    case Qt::Key_Menu: return VK_APPS;
    default: break;
    }
    // A printed key (` [ ; and so on): wherever this keyboard layout has it.
    if (k > 0x20 && k < 0xFFFF) {
        const SHORT v = VkKeyScanW(wchar_t(QChar(char16_t(k)).toLower().unicode()));
        if (v != -1)
            return LOBYTE(v);
    }
    return 0;
}

class WindowsKeys : public GlobalKeys::Backend, public QAbstractNativeEventFilter
{
public:
    explicit WindowsKeys(GlobalKeys *owner) : m_owner(owner)
    {
        qApp->installNativeEventFilter(this);
        QObject::connect(qApp, &QGuiApplication::applicationStateChanged, owner, [this] { follow(); });
    }
    ~WindowsKeys() override
    {
        setRegistered(false);
        qApp->removeNativeEventFilter(this);
    }

    void want(bool voice, bool talk, int qtKey) override
    {
        setRegistered(voice);
        m_talk = talk;
        m_vk = virtualKey(qtKey);
        follow();
    }
    bool everywhere() const override { return true; }
    QString taken() const override
    {
        if (m_muteTaken && m_deafenTaken)
            return QStringLiteral("Ctrl+Shift+M and Ctrl+Shift+D");
        return m_muteTaken ? QStringLiteral("Ctrl+Shift+M") : m_deafenTaken ? QStringLiteral("Ctrl+Shift+D") : QString();
    }

    bool nativeEventFilter(const QByteArray &type, void *message, qintptr *) override
    {
        if (type != "windows_generic_MSG")
            return false;
        const MSG *m = static_cast<const MSG *>(message);
        if (m->message != WM_HOTKEY)
            return false;
        if (m->wParam == WPARAM(kMuteId))
            m_owner->key(GlobalKeys::Mute, true);
        else if (m->wParam == WPARAM(kDeafenId))
            m_owner->key(GlobalKeys::Deafen, true);
        else
            return false;
        return true;
    }

private:
    // Registered only in a voice room or a call, so the two combinations stay free for other apps otherwise.
    void setRegistered(bool on)
    {
        if (on == m_registered)
            return;
        m_registered = on;
        const bool wasTaken = m_muteTaken || m_deafenTaken;
        if (on) {
            m_muteTaken = !RegisterHotKey(nullptr, kMuteId, MOD_CONTROL | MOD_SHIFT | MOD_NOREPEAT, 'M');
            m_deafenTaken = !RegisterHotKey(nullptr, kDeafenId, MOD_CONTROL | MOD_SHIFT | MOD_NOREPEAT, 'D');
        } else {
            if (!m_muteTaken)
                UnregisterHotKey(nullptr, kMuteId);
            if (!m_deafenTaken)
                UnregisterHotKey(nullptr, kDeafenId);
        }
        if (wasTaken != (m_muteTaken || m_deafenTaken))
            m_owner->backendChanged();
    }
    // Push to talk is only TalkKey's, while Huginn is in front: Huginn never asks Windows about keys it doesn't own
    // (asking would look like watching the keyboard).
    void follow() {}

    GlobalKeys *m_owner;
    bool m_registered = false, m_muteTaken = false, m_deafenTaken = false;
    bool m_talk = false, m_down = false;
    int m_vk = 0;
};
}

GlobalKeys::Backend *makeGlobalKeysBackend(GlobalKeys *owner)
{
    return new WindowsKeys(owner);
}
