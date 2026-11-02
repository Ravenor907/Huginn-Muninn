#include "livelist.h"
#include "busy.h"

#include <QHash>
#include <QSet>
#include <QVariantMap>

#include <cstdio>

LiveList::LiveList(QObject *parent)
    : QAbstractListModel(parent)
{
}

void LiveList::setKeys(const QStringList &keys)
{
    if (keys == m_keys)
        return;
    m_keys = keys;
    Q_EMIT keysChanged();
}

QString LiveList::keyOf(const QVariant &v, int at) const
{
    if (m_keys.isEmpty())
        return QString::number(at);
    const QVariantMap m = v.toMap();
    QString k;
    for (const QString &f : m_keys)
        k += m.value(f).toString() + QChar(0x1f);
    return k;
}

void LiveList::setItems(const QVariantList &next)
{
    Busy::Scope busy("list updates");
    // Keys of the rows on show now, kept in step with every change below.
    QStringList shown;
    shown.reserve(m_items.size());
    for (int i = 0; i < m_items.size(); ++i)
        shown << keyOf(m_items.at(i), i);
    QStringList wanted;
    wanted.reserve(next.size());
    for (int i = 0; i < next.size(); ++i)
        wanted << keyOf(next.at(i), i);
    // Two rows with the same key can't be told apart: start over instead.
    if (QSet<QString>(wanted.begin(), wanted.end()).size() != wanted.size()
        || QSet<QString>(shown.begin(), shown.end()).size() != shown.size()) {
        beginResetModel();
        m_items = next;
        endResetModel();
        Q_EMIT itemsChanged();
        return;
    }
    const QSet<QString> keep(wanted.begin(), wanted.end());
    // Rows that are gone.
    for (int i = int(shown.size()) - 1; i >= 0; --i) {
        if (keep.contains(shown.at(i)))
            continue;
        beginRemoveRows(QModelIndex(), i, i);
        m_items.removeAt(i);
        shown.removeAt(i);
        endRemoveRows();
    }
    // Then in order: each wanted row is moved up, added, or changed where it is.
    for (int i = 0; i < next.size(); ++i) {
        const QString &k = wanted.at(i);
        if (i < shown.size() && shown.at(i) == k) {
            if (m_items.at(i) != next.at(i)) {
                m_items[i] = next.at(i);
                Q_EMIT dataChanged(index(i), index(i));
            }
            continue;
        }
        const int from = int(shown.indexOf(k, i));
        if (from > i) {
            beginMoveRows(QModelIndex(), from, from, QModelIndex(), i);
            m_items.move(from, i);
            shown.move(from, i);
            endMoveRows();
            if (m_items.at(i) != next.at(i)) {
                m_items[i] = next.at(i);
                Q_EMIT dataChanged(index(i), index(i));
            }
        } else {
            beginInsertRows(QModelIndex(), i, i);
            m_items.insert(i, next.at(i));
            shown.insert(i, k);
            endInsertRows();
        }
    }
    Q_EMIT itemsChanged();
}

int LiveList::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : int(m_items.size());
}

QVariant LiveList::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_items.size() || role != Qt::UserRole)
        return {};
    return m_items.at(index.row());
}

QHash<int, QByteArray> LiveList::roleNames() const
{
    return {{Qt::UserRole, "item"}};
}

// ------------------------------------------------------------------ --perf-selftest

bool LiveList::selfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("perf self-test: %s: %s\n", what, ok ? "ok" : "FAILED");
        all = all && ok;
    };
    // A chat of 500 messages, as ChatPage gives it (rows told apart by id).
    LiveList list;
    list.setKeys({QStringLiteral("id")});
    QVariantList msgs;
    for (int i = 0; i < 500; ++i)
        msgs << QVariantMap{{QStringLiteral("id"), QString::number(i)}, {QStringLiteral("text"), QStringLiteral("message %1").arg(i)}};
    list.setItems(msgs);

    // What the list tells the view: rows added, rows changed (each changed row counted), and any start over.
    int inserted = 0, insertCalls = 0, changedRows = 0, removed = 0, moved = 0, resets = 0;
    connect(&list, &QAbstractItemModel::rowsInserted, &list, [&](const QModelIndex &, int first, int last) { inserted += last - first + 1; ++insertCalls; });
    connect(&list, &QAbstractItemModel::dataChanged, &list, [&](const QModelIndex &a, const QModelIndex &b) { changedRows += b.row() - a.row() + 1; });
    connect(&list, &QAbstractItemModel::rowsRemoved, &list, [&](const QModelIndex &, int first, int last) { removed += last - first + 1; });
    connect(&list, &QAbstractItemModel::rowsMoved, &list, [&] { ++moved; });
    connect(&list, &QAbstractItemModel::modelReset, &list, [&] { ++resets; });
    const auto clear = [&] { inserted = insertCalls = changedRows = removed = moved = resets = 0; };

    // A new message: one row added at the end, nothing else touched.
    msgs << QVariantMap{{QStringLiteral("id"), QStringLiteral("500")}, {QStringLiteral("text"), QStringLiteral("new")}};
    list.setItems(msgs);
    check("a new message adds one row and touches no other", inserted == 1 && insertCalls == 1 && changedRows == 0 && removed == 0 && moved == 0
                                                               && resets == 0 && list.rowCount() == 501);
    // A reaction or a read mark on one message: that row alone changes.
    clear();
    QVariantMap m = msgs.at(250).toMap();
    m.insert(QStringLiteral("rx"), QStringLiteral("+1"));
    msgs[250] = m;
    list.setItems(msgs);
    check("a changed message redraws its row alone", changedRows == 1 && inserted == 0 && removed == 0 && moved == 0 && resets == 0);
    // Deleted for everyone: one row goes.
    clear();
    msgs.removeAt(10);
    list.setItems(msgs);
    check("a removed message takes one row away", removed == 1 && inserted == 0 && changedRows == 0 && moved == 0 && resets == 0
                                                     && list.rowCount() == 500);
    // The same list again: nothing at all.
    clear();
    list.setItems(msgs);
    check("the same messages again change nothing", inserted == 0 && changedRows == 0 && removed == 0 && moved == 0 && resets == 0);
    return all;
}
