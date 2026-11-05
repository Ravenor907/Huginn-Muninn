#pragma once

#include <QImage>
#include <QObject>
#include <QString>
#include <QVariantList>

// Saved media (the Send menu's picker, SavedMedia.qml): the photos, videos and GIFs saved on this computer, newest
// first, from the Pictures and Videos folders. Found on a worker thread (a big folder takes a while); ready() brings
// them back as [{path, url, name, kind ("photo", "video" or "gif"), time, size, ms (a video's length, 0 unknown)}].
// A video's length is read from its own MP4 boxes (GroupVideo::probeFile), never decoded.
// Also the camera's photo: kept for the moment it takes to send (the same scratch folder as a pasted picture),
// then shredded.
// QML: the singleton MediaFiles (registered here at start, so main.cpp needs nothing).
class SavedMedia : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged)

public:
    static constexpr int kMost = 400;          // the newest this many are listed
    static constexpr int kLook = 8000;         // files looked at in all, at most
    static constexpr int kProbe = 150;         // videos whose length is read, newest first

    explicit SavedMedia(QObject *parent = nullptr);

    bool loading() const { return m_loading; }

    // Starts a fresh look; ready() follows (once, even when one is already running).
    Q_INVOKABLE void load();
    // "photo", "video", "gif" or "" (not something Saved media lists), from the name alone.
    Q_INVOKABLE QString kindOf(const QString &name) const { return kind(name); }
    // The camera's photo, written to the scratch folder: its path, "" when it couldn't be. keepFrame takes the frame a
    // QML VideoOutput shows right now (its videoSink), full size and without the bars around it.
    Q_INVOKABLE QString keep(const QImage &image) const;
    Q_INVOKABLE QString keepFrame(QObject *videoOutput) const;
    // Overwrites and deletes a file keep() wrote (anything else is left alone).
    Q_INVOKABLE void shred(const QString &path) const;
    // "2:14", "0:08", "1:02:05"; "" when unknown.
    Q_INVOKABLE QString lengthText(int ms) const { return clock(ms); }

    static QString kind(const QString &name);
    static QString clock(int ms);
    static QVariantList find(const QStringList &roots);
    static bool selfTest();

Q_SIGNALS:
    void ready(const QVariantList &items);
    void loadingChanged();

private:
    bool m_loading = false;
    int m_run = 0;
};
