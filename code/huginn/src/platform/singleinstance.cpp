#include "singleinstance.h"

#include <QCryptographicHash>
#include <QDir>
#include <QGuiApplication>
#include <QLocalServer>
#include <QLocalSocket>
#include <QLockFile>
#include <QStandardPaths>
#include <QThread>
#include <QWindow>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace
{
SingleInstance *s_current = nullptr;
}

SingleInstance::SingleInstance(QObject *parent)
    : QObject(parent)
{
    // Per user: the app data folder differs per account, so its hash names the socket.
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    const QByteArray h = QCryptographicHash::hash(dir.toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
    m_name = QStringLiteral("io.github.ravenor907.Huginn-") + QString::fromLatin1(h);
    m_lock = new QLockFile(dir + QStringLiteral("/instance.lock"));
    m_lock->setStaleLockTime(0);   // only a live process holds it; a crashed one's lock is taken over
}

SingleInstance::~SingleInstance()
{
    release();
    delete m_lock;
    if (s_current == this)
        s_current = nullptr;
}

bool SingleInstance::claim()
{
    if (m_lock->tryLock(200) || takeOverStaleLock()) {
        listen();
        return true;
    }
    return false;
}

void SingleInstance::listen()
{
    s_current = this;
    QLocalServer::removeServer(m_name);   // left behind by a crash (Unix sockets only)
    m_server = new QLocalServer(this);
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    connect(m_server, &QLocalServer::newConnection, this, [this] {
        while (QLocalSocket *s = m_server->nextPendingConnection()) {
            connect(s, &QLocalSocket::disconnected, s, &QObject::deleteLater);
            s->disconnectFromServer();
        }
        bringToFront();
        Q_EMIT activated();
    });
    if (!m_server->listen(m_name))
        qWarning("single instance: couldn't listen on %s", qPrintable(m_name));
}

bool SingleInstance::takeOverStaleLock()
{
    // Someone holds the lock: knock so they come forward. Connecting is the whole message.
#ifdef Q_OS_WIN
    // We were just started by the user, so we may hand the foreground to the running copy.
    AllowSetForegroundWindow(ASFW_ANY);
#endif
    // A copy that is still starting may not listen yet, so knock a few times.
    for (int i = 0; i < 4; ++i) {
        QLocalSocket s;
        s.connectToServer(m_name);
        if (s.waitForConnected(700)) {
            s.write("show\n");
            s.waitForBytesWritten(500);
            s.disconnectFromServer();
            return false;
        }
        QThread::msleep(300);
    }
    // Nobody answers: the lock was left by a copy that was killed.
    qWarning("single instance: the lock's owner doesn't answer; taking it over");
    m_lock->removeStaleLockFile();
    return m_lock->tryLock(200);
}

void SingleInstance::release()
{
    if (m_server) {
        m_server->close();
        delete m_server;
        m_server = nullptr;
    }
    if (m_lock && m_lock->isLocked())
        m_lock->unlock();
}

void SingleInstance::releaseForRestart()
{
    if (s_current)
        s_current->release();
}

void SingleInstance::bringToFront()
{
    for (QWindow *w : QGuiApplication::topLevelWindows()) {
        if (!w->isVisible() || w->type() == Qt::ToolTip || w->type() == Qt::Popup)
            continue;
        if (w->windowState() & Qt::WindowMinimized)
            w->setWindowStates(w->windowStates() & ~Qt::WindowMinimized);
        w->raise();
        w->requestActivate();
    }
}
