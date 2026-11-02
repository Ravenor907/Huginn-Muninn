#pragma once

#include <QBuffer>
#include <QObject>
#include <QPointer>
#include <QTimer>

class QAudioInput;
class QAudioOutput;
class QCamera;
class QMediaCaptureSession;
class QMediaPlayer;
class QMediaRecorder;
class Chat;

// Video messages (cap m2, shared/docs/protocol.md 12.2), the same as Muninn's Videos.kt and CameraScreen.kt:
//  - Recorded with the camera as an MP4 of at most a minute, small enough to go through the relays like a
//    photo (about 2 MB). While recording it's written to Huginn's runtime folder, read back and wiped at once.
//  - After recording it waits: the person watches it and picks Send or Discard. Never sent by itself.
//  - Received ones stay encrypted in the vault and play from memory, never written out in the clear.
class VideoMsg : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool cameraOn READ cameraOn NOTIFY changed)
    Q_PROPERTY(bool recording READ recording NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(bool reviewing READ reviewing NOTIFY changed)       // a recorded video waits for Send or Discard
    Q_PROPERTY(int reviewMs READ reviewMs NOTIFY changed)
    Q_PROPERTY(QString playing READ playing NOTIFY changed)        // a message id, "review", or ""
    Q_PROPERTY(bool paused READ paused NOTIFY changed)
    Q_PROPERTY(qreal progress READ progress NOTIFY tick)
    Q_PROPERTY(QString error READ error NOTIFY changed)

public:
    explicit VideoMsg(Chat *chat, QObject *parent = nullptr);
    ~VideoMsg() override;

    static const int kVideoBitrate = 220000;   // with the sound, about 2 MB a minute: the most the relays carry
    static const int kAudioBitrate = 32000;
    static const int kSide = 640;              // the long side of the picture at most

    bool cameraOn() const { return m_camera != nullptr; }
    bool recording() const { return m_recording; }
    int elapsed() const;
    bool reviewing() const { return !m_clip.isEmpty(); }
    int reviewMs() const { return m_clipMs; }
    QString playing() const { return m_playing; }
    bool paused() const { return m_paused; }
    qreal progress() const;
    QString error() const { return m_error; }

    Q_INVOKABLE bool hasCamera() const;
    // The camera's picture in [videoOutput] (a QML VideoOutput). False, with error set, when there's none.
    Q_INVOKABLE bool openCamera(QObject *videoOutput);
    Q_INVOKABLE void closeCamera();
    Q_INVOKABLE void record();
    // Stops; the video then waits for Send or Discard (too short: nothing).
    Q_INVOKABLE void stopRecording();
    // The recorded video to that chat (a contact or "g:<group>:<channel>"): what to tell the person, "" when sent.
    Q_INVOKABLE QString send(const QString &where);
    Q_INVOKABLE void discard();
    // A video message ([id] its stored photo id), or the recorded one ("review"), in [videoOutput].
    Q_INVOKABLE void play(const QString &id, QObject *videoOutput);
    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void stop();
    // A video file from this computer: picked, then checked and sent. "" when sent.
    Q_INVOKABLE QString pickFile() const;
    Q_INVOKABLE QString sendFile(const QString &where, const QString &path);
    // It can go as a video message: an MP4 of at most kMaxPieces pieces and a minute. Anything bigger or longer goes
    // as a file instead (drag and drop, Video file), never refused.
    Q_INVOKABLE bool fitsMessage(const QString &path) const;

Q_SIGNALS:
    void changed();
    void tick();

private:
    QString tempFile() const;
    void setError(const QString &e);
    Chat *m_chat;
    QMediaCaptureSession *m_session = nullptr;
    QCamera *m_camera = nullptr;
    QAudioInput *m_input = nullptr;
    QMediaRecorder *m_recorder = nullptr;
    QMediaPlayer *m_player = nullptr;
    QAudioOutput *m_output = nullptr;
    QPointer<QObject> m_view;
    QBuffer m_buffer;
    QByteArray m_clip, m_shown;
    int m_clipMs = 0;
    QTimer m_ticker;
    bool m_recording = false, m_paused = false;
    qint64 m_started = 0;
    QString m_playing, m_error;
};
