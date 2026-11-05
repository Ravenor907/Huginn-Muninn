#pragma once

#include <QAbstractListModel>
#include <QStringList>
#include <QVariantList>

// A list for QML that changes in place: give it the whole new list each time and it works out
// which rows were added, removed, moved or changed, so a ListView keeps its place and only
// redraws what changed (a plain array makes it start over on every change). Rows are matched by
// the fields named in keys. Delegates read each row as modelData (its only role), as with an array.
class LiveList : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(QVariantList items READ items WRITE setItems NOTIFY itemsChanged)
    Q_PROPERTY(QStringList keys READ keys WRITE setKeys NOTIFY keysChanged)
    Q_PROPERTY(int count READ rowCount NOTIFY itemsChanged)

public:
    explicit LiveList(QObject *parent = nullptr);

    QVariantList items() const { return m_items; }
    void setItems(const QVariantList &items);
    QStringList keys() const { return m_keys; }
    void setKeys(const QStringList &keys);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    Q_INVOKABLE QVariant get(int row) const { return row >= 0 && row < m_items.size() ? m_items.at(row) : QVariant(); }

    // --perf-selftest: a new message adds one row, a changed one redraws one row, and nothing starts over.
    static bool selfTest();

Q_SIGNALS:
    void itemsChanged();
    void keysChanged();

private:
    QString keyOf(const QVariant &v, int at) const;
    QVariantList m_items;
    QStringList m_keys;
};
