#include "livelist.h"
#include "busy.h"

#include <QHash>
#include <QSet>
#include <QVariantMap>

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
