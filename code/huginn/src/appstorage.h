#pragma once

#include <QHash>
#include <QObject>
#include <QVariantList>

class Updater;

// Labs › App storage: files Huginn keeps in its own folders that can go without losing anything that matters:
// update downloads, caches and the crash log. A hard allowlist: only the spots in appstorage.cpp are ever listed or
// deleted. The vault, keys, chats, settings and everything else in Huginn's folders stay hidden and can't be named.
//
// A file is named by an id: its root ('c' the cache folder, 'd' the app data folder), a slash, then its path there
// ("c/Huginn-v1.1.flatpak"). Only folder names are shown, never paths with the account name in them.
// Received files go straight to the transfer folder (Downloads by default), so none are kept here.
class AppStorage : public QObject
{
    Q_OBJECT
    // [{ id, name, size, at (ms), kind ("received", "sent", "update", "cache", "crash"), word, folder }], newest first.
    Q_PROPERTY(QVariantList items READ items NOTIFY changed)
    Q_PROPERTY(double total READ total NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)

public:
    explicit AppStorage(Updater *updater, QObject *parent = nullptr);

    QVariantList items() const { return m_items; }
    double total() const;
    bool busy() const { return m_busy; }

    // Reads the list again (off the main thread).
    Q_INVOKABLE void refresh();
    // Deletes one file on the allowlist; false when it isn't one or couldn't go.
    Q_INVOKABLE bool remove(const QString &id);
    // Deletes every listed file of [kind] ("received" or "cache"): how many went.
    Q_INVOKABLE int removeKind(const QString &kind);

    // The id names a file inside one of the allowlisted spots (checks the name only).
    static bool allowed(const QString &id);
    static bool selfTest();

Q_SIGNALS:
    void changed();

private:
    using Roots = QHash<QChar, QString>;
    static Roots roots();
    static QVariantList scan(const Roots &roots, qint64 now, const QString &keep);
    static QString resolve(const Roots &roots, const QString &id);
    static bool removeIn(const Roots &roots, const QString &id);
    QString keep() const;

    Updater *m_updater;
    QVariantList m_items;
    bool m_busy = false;
    int m_reads = 0;
};
