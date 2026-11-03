#pragma once

#include "chatwire.h"
#include "ratchet.h"
#include "ring.h"

#include <QElapsedTimer>
#include <QHash>
#include <QJsonObject>
#include <functional>
#include <memory>
#include <optional>
#include <QNetworkAccessManager>
#include <QObject>
#include <QSet>
#include <QSettings>
#include <QTimer>
#include <QUrl>
#include <QVariantList>

class Ble;
class FileLink;
class Labs;
class Moderation;
class Link;
class LocalSend;
class Nostr;
class Profile;
class Trust;
class Vault;

// Huginn's messaging, the same as Muninn's (net/Chat.kt) so the two work together:
//  - Three ways to reach someone, all tried at once: Bluetooth (a Muninn phone close by),
//    the same Wi-Fi (the LocalSend port), and the internet (Nostr relays, for contacts).
//    The other side keeps the first copy; anything not "delivered" is sent again for a day.
//  - Rooms: Nearby (public, everyone close by or on the Wi-Fi) and Private (one person).
//    Every message is sealed end to end with the chat keys whichever way it travels.
//  - People close by can message straight away. After 10 messages, or once they're out of
//    range, both get "Keep in touch?"; when both say yes they become contacts (internet too).
//  - Receipts (Sending, Sent, Delivered, Read), unsend, photos and voice messages in pieces.
//  - Contacts whose app says "cap":"r1" get the message ratchet (forward secrecy) for private
//    messages; everyone else, and the Nearby room, stays on the pair key (v1). See ChatWire.
//  - Groups (chatgroups.cpp): a signed state per group, every message sealed separately to
//    each member over that member's own session. See shared/docs/protocol.md and GroupWire.
//  - Contact folders: local only, never sent.
//  - Everything kept is encrypted in the Vault.
class Chat : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    Q_PROPERTY(QVariantList nearby READ nearby NOTIFY changed)
    Q_PROPERTY(QVariantList chats READ chats NOTIFY changed)
    Q_PROPERTY(QVariantList requests READ requests NOTIFY changed)
    Q_PROPERTY(QVariantList around READ around NOTIFY changed)
    Q_PROPERTY(QVariantList people READ people NOTIFY changed)
    Q_PROPERTY(int nearbyCount READ nearbyCount NOTIFY changed)
    Q_PROPERTY(int unreadCount READ unreadCount NOTIFY changed)
    Q_PROPERTY(QString username READ username WRITE setUsername NOTIFY changed)
    Q_PROPERTY(bool nearbyOn READ nearbyOn WRITE setNearbyOn NOTIFY changed)
    Q_PROPERTY(bool visibleNearby READ visibleNearby WRITE setVisibleNearby NOTIFY changed)
    Q_PROPERTY(QVariantList trash READ trash NOTIFY changed)
    Q_PROPERTY(QStringList topOrder READ topOrder NOTIFY changed)
    Q_PROPERTY(bool keepNearby READ keepNearby WRITE setKeepNearby NOTIFY changed)
    Q_PROPERTY(bool readReceipts READ readReceipts WRITE setReadReceipts NOTIFY changed)
    Q_PROPERTY(bool showPreview READ showPreview WRITE setShowPreview NOTIFY changed)
    // Settings › Notifications › Show: 2 the name and a short preview, 1 the name only, 0 nothing ("New message").
    // A saved Show message text carries over (on: 2, off: 0); a new install shows the name only.
    Q_PROPERTY(int previewMode READ previewMode WRITE setPreviewMode NOTIFY changed)
    // Settings › Notifications › Reduce notifications: one per chat until it's opened, instead of one per message.
    Q_PROPERTY(bool reduceNotifications READ reduceNotifications WRITE setReduceNotifications NOTIFY changed)
    Q_PROPERTY(qint64 deleteAfter READ deleteAfter WRITE setDeleteAfter NOTIFY changed)
    Q_PROPERTY(int internet READ internet NOTIFY changed)
    Q_PROPERTY(QString myLink READ myLink NOTIFY changed)          // the code being shown (newLink makes one)
    Q_PROPERTY(qint64 linkExpires READ linkExpires NOTIFY changed)
    Q_PROPERTY(QString openChat MEMBER m_openChat NOTIFY changed)
    Q_PROPERTY(QVariantList groups READ groups NOTIFY changed)
    Q_PROPERTY(QVariantList groupInvites READ groupInvites NOTIFY changed)
    Q_PROPERTY(QVariantList folders READ folders NOTIFY changed)
    Q_PROPERTY(QString netStatus READ netStatus NOTIFY changed)
    // Cap m1: who is typing to me and whose app is open (key -> until, ms), and my own Active status.
    Q_PROPERTY(QVariantMap typing READ typingMap NOTIFY liveChanged)
    Q_PROPERTY(QVariantMap active READ activeMap NOTIFY liveChanged)
    Q_PROPERTY(bool activeStatus READ activeStatus WRITE setActiveStatus NOTIFY changed)
    // Cap m2: make link previews on this computer when sending (Settings › Privacy). Received ones always show.
    Q_PROPERTY(bool linkPreviews READ linkPreviews WRITE setLinkPreviews NOTIFY changed)
    // A file of mine going as a message: message id -> how much a route took so far (0 to 1), until it's sent.
    Q_PROPERTY(QVariantMap uploads READ uploads NOTIFY uploadsChanged)

public:
    Chat(LocalSend *net, Vault *vault, Nostr *nostr, Ble *ble, QObject *parent = nullptr);

    bool ready() const { return m_ready; }
    // A file another part keeps (Link's link-replay.json, ...): when the saving thread gives up on it, again() writes it
    // from memory (round 15, N2).
    void onSaveLost(const QString &name, std::function<void()> again) { m_resavers.insert(name, std::move(again)); }
    // For the self-tests: one of the vault's files as it is on disk now (what a restart reads back).
    QByteArray savedFile(const QString &name) const;
    QVariantList nearby() const { return m_nearby; }
    QVariantList chats() const;
    QVariantList requests() const;
    QVariantList around() const;
    QVariantList people() const;
    int nearbyCount() const;
    int unreadCount() const { return int((m_unread | m_markedUnread).size()); }
    QString username() const;
    void setUsername(const QString &v);
    // The Nearby room is off too while hidden from people nearby.
    bool nearbyOn() const { return visibleNearby() && m_settings.value(QStringLiteral("chat/nearbyOn"), true).toBool(); }
    void setNearbyOn(bool v);
    // "Visible to people nearby". Off: no Nearby room, nobody new close by can message, and the name
    // isn't sent out on Bluetooth or Wi-Fi. Contacts still reach this computer close by.
    bool visibleNearby() const { return m_settings.value(QStringLiteral("chat/visibleNearby"), true).toBool(); }
    void setVisibleNearby(bool v);
    bool keepNearby() const { return m_settings.value(QStringLiteral("chat/keepNearby"), false).toBool(); }
    void setKeepNearby(bool v);
    bool readReceipts() const { return m_settings.value(QStringLiteral("chat/readReceipts"), true).toBool(); }
    void setReadReceipts(bool v) { m_settings.setValue(QStringLiteral("chat/readReceipts"), v); Q_EMIT changed(); }
    bool showPreview() const { return previewMode() == 2; }
    // Who it's from may show (Name only, or Name and message).
    bool showName() const { return previewMode() >= 1; }
    int previewMode() const
    {
        if (m_settings.contains(QStringLiteral("chat/previewMode")))
            return qBound(0, m_settings.value(QStringLiteral("chat/previewMode")).toInt(), 2);
        if (m_settings.contains(QStringLiteral("chat/showPreview")))
            return m_settings.value(QStringLiteral("chat/showPreview")).toBool() ? 2 : 0;
        return 1;
    }
    void setPreviewMode(int v) { m_settings.setValue(QStringLiteral("chat/previewMode"), qBound(0, v, 2)); Q_EMIT changed(); }
    // Picked once (first setup, Settings or the one-time question): an older install with no previewMode key gets asked.
    Q_INVOKABLE bool previewModeChosen() const { return m_settings.contains(QStringLiteral("chat/previewMode")); }
    bool reduceNotifications() const { return m_settings.value(QStringLiteral("chat/reduceNotifications"), false).toBool(); }
    void setReduceNotifications(bool v) { m_settings.setValue(QStringLiteral("chat/reduceNotifications"), v); Q_EMIT changed(); }
    void setShowPreview(bool v) { setPreviewMode(v ? 2 : 0); }
    qint64 deleteAfter() const { return m_settings.value(QStringLiteral("chat/deleteAfter"), 0).toLongLong(); }
    void setDeleteAfter(qint64 v) { m_settings.setValue(QStringLiteral("chat/deleteAfter"), v); prune(); Q_EMIT changed(); }
    int internet() const;
    QString myLink() const { return m_link; }
    qint64 linkExpires() const { return m_linkExpires; }
    QString chatKey() const { return myKey(); }
    QString mailbox() const;
    // A new code with an invite good for an hour: a leaked code stops working after that.
    Q_INVOKABLE QString newLink();
    QString netStatus() const;

    // Messages
    Q_INVOKABLE QVariantList messagesWith(const QString &key) const;
    Q_INVOKABLE void sendNearby(const QString &text);
    // re: the id of the message this answers (a reply, cap m1; to an app without m1 it goes as plain text).
    Q_INVOKABLE void sendPrivate(const QString &key, const QString &text, const QString &re = QString());
    Q_INVOKABLE void markRead(const QString &key);
    Q_INVOKABLE void deleteMessage(const QString &key, const QString &id);
    Q_INVOKABLE void deleteNearbyMessage(const QString &id);
    Q_INVOKABLE void unsend(const QString &key, const QString &id);
    Q_INVOKABLE void retry(const QString &key, const QString &id);
    Q_INVOKABLE void deleteAll();
    // Photos (any picture file: shrunk, metadata removed) and voice messages
    Q_INVOKABLE bool sendPhoto(const QString &key, const QString &path, bool hd);
    Q_INVOKABLE void sendVoice(const QString &key, const QByteArray &aac, int ms);
    Q_INVOKABLE QByteArray blob(const QString &id) const;
    Q_INVOKABLE QString photoUrl(const QString &id);
    // Cap a1: a stored GIF as a data URL for AnimatedImage, only when it passes the GIF limits again; "" otherwise.
    Q_INVOKABLE QString gifUrl(const QString &id);
    Q_INVOKABLE bool savePhoto(const QString &id);
    // The newest pictures in Pictures, found on a worker thread; recentPicturesReady brings them back.
    Q_INVOKABLE void loadRecentPictures();
    Q_INVOKABLE void copyLink() const;

    // Cap m1 (shared/docs/protocol.md section 11, RichWire): reactions, edits, typing, active status. Calls that
    // can fail return what to tell the person, "" when done.
    Q_INVOKABLE bool hasM1(const QString &key) const { return m_caps.value(key).contains(QStringLiteral("m1")); }
    Q_INVOKABLE QString react(const QString &key, const QString &id, const QString &emoji);
    Q_INVOKABLE QString editMessage(const QString &key, const QString &id, const QString &text);
    Q_INVOKABLE void typingTo(const QString &key, bool on);
    Q_INVOKABLE QStringList quickReactions() const;
    Q_INVOKABLE QStringList emojiList() const;
    QVariantMap typingMap() const;
    QVariantMap activeMap() const;
    bool activeStatus() const { return m_settings.value(QStringLiteral("chat/activeStatus"), true).toBool(); }
    void setActiveStatus(bool on);
    // Cap p1 (profile.cpp): my status or custom status changed, so everyone who sees my active status hears it now.
    void announceStatus();
    // Pinned messages, only on this computer: where is a contact's key or "g:<group>:<channel>".
    Q_INVOKABLE void setPinned(const QString &where, const QString &id, bool on);
    Q_INVOKABLE QStringList pinned(const QString &where) const { return m_pins.value(where); }
    // A picture on the clipboard (pasted into the message box) or a dropped file, sent to a contact or
    // "g:<group>:<channel>". False when there's no picture.
    Q_INVOKABLE bool clipboardHasImage() const;
    // A group invite (a MUNINNG1 code or an invite link) on the clipboard, for Join a group to fill in; "" when none.
    Q_INVOKABLE QString clipboardInvite() const;
    Q_INVOKABLE bool pasteImage(const QString &where);
    Q_INVOKABLE QString localPath(const QUrl &url) const { return url.toLocalFile(); }
    Q_INVOKABLE bool isPicture(const QString &path) const;

    // Cap m2 (shared/docs/protocol.md section 12, chatmore.cpp): albums, video messages, polls, link previews and
    // the Shared page. where: a contact's key or "g:<group>:<channel>". Calls that can fail return what to tell
    // the person, "" when done.
    Q_INVOKABLE bool hasM2(const QString &key) const { return m_caps.value(key).contains(QStringLiteral("m2")); }
    bool linkPreviews() const { return m_settings.value(QStringLiteral("chat/linkPreviews"), false).toBool(); }
    void setLinkPreviews(bool on) { m_settings.setValue(QStringLiteral("chat/linkPreviews"), on); Q_EMIT changed(); }
    // Several pictures at once: albums of up to 10 (one picture goes on its own).
    Q_INVOKABLE QString sendPhotos(const QString &where, const QStringList &paths, bool hd);
    Q_INVOKABLE QStringList pickPictures() const;     // the system's file picker, several at once
    QString sendVideo(const QString &where, const QByteArray &mp4, int ms);
    Q_INVOKABLE QString sendPoll(const QString &where, const QString &question, const QStringList &answers, bool multi);
    // My vote on a poll ([] takes it back).
    Q_INVOKABLE QString vote(const QString &where, const QString &id, const QVariantList &choices);
    // {media: [{id, photo, video, gif, mine, name, time, here}], links: [{id, url, title, description, host, thumb, name, time}],
    // voices: [{id, photo, voice, mine, name, time, here}]}, newest first. here: its bytes are on this device.
    Q_INVOKABLE QVariantMap shared(const QString &where) const;
    // Media and files: save a photo, GIF, video or voice message kept here (the path, or empty), or send it again as a
    // new message to someone else (empty when it went, else what to say).
    Q_INVOKABLE QString saveMedia(const QString &id);
    Q_INVOKABLE QString forwardMedia(const QString &id, const QString &to, int voiceMs);
    Q_INVOKABLE QString linkHost(const QString &url) const;
    Q_INVOKABLE bool openLink(const QString &url) const;

    // People
    Q_INVOKABLE QString nameOf(const QString &key) const;          // the nickname when there is one
    Q_INVOKABLE QString realNameOf(const QString &key) const;      // the name they gave themselves
    Q_INVOKABLE QString nicknameOf(const QString &key) const;
    // Only on this computer, never sent. Empty goes back to their own name.
    Q_INVOKABLE void setNickname(const QString &key, const QString &nick);
    // A note about someone (Huginn's profile card), only on this computer, never sent.
    Q_INVOKABLE QString noteOf(const QString &key) const;
    Q_INVOKABLE void setNote(const QString &key, const QString &note);
    // An existing contact a code may be a new security key for, so the screen can ask before
    // replacing it: {key, name, sameInbox} (sameInbox: their inbox, so it can only be them or
    // someone pretending), or empty when there's none.
    Q_INVOKABLE QVariantMap replaces(const QString &text) const;
    Q_INVOKABLE bool keyChanged(const QString &key) const;
    Q_INVOKABLE QString route(const QString &key) const;
    Q_INVOKABLE bool isContact(const QString &key) const { return m_contacts.contains(key); }
    Q_INVOKABLE bool isVerified(const QString &key) const;
    Q_INVOKABLE QString codeWith(const QString &key) const;
    Q_INVOKABLE void verify(const QString &key);
    Q_INVOKABLE void meet(const QString &key, const QString &name);
    Q_INVOKABLE bool shouldAskKeep(const QString &key) const;
    Q_INVOKABLE bool theySaidKeep(const QString &key) const;
    Q_INVOKABLE void keepInTouch(const QString &key);
    Q_INVOKABLE void notNow(const QString &key);
    Q_INVOKABLE QVariantMap parseCode(const QString &text) const;   // {name, key} or empty
    // sameAs: the contact whose security key this code replaces (after asking), or empty.
    Q_INVOKABLE void addContact(const QString &text, const QString &sameAs = QString());
    Q_INVOKABLE void acceptRequest(const QString &key);
    Q_INVOKABLE void declineRequest(const QString &key);
    Q_INVOKABLE void block(const QString &key, bool on = true);
    Q_INVOKABLE bool isBlocked(const QString &key) const;
    // Removing someone puts them in the trash for 30 days: hidden, their conversation kept, and
    // nothing from them gets through (like blocked) until they're restored.
    Q_INVOKABLE void removeContact(const QString &key);
    Q_INVOKABLE bool isTrashed(const QString &key) const { return m_trash.contains(key); }
    QVariantList trash() const;                     // [{key, name, trashed, daysLeft}], newest first
    Q_INVOKABLE void restore(const QString &key);
    Q_INVOKABLE void deleteForGood(const QString &key);
    Q_INVOKABLE void emptyTrash();

    // What their app can do (the "caps" of their envelopes). Unknown until they send an envelope
    // with caps or answer probeCaps (at most once an hour each).
    Q_INVOKABLE bool capsKnown(const QString &key) const { return m_caps.contains(key); }
    // The envelope being handled counts too: someone joining by a link isn't kept here yet, but their envelope says what
    // their app takes (as hasJ1 and hasD1 do).
    bool hasCap(const QString &key, const QString &cap) const
    {
        return m_caps.value(key).contains(cap) || (key == m_openedFrom && m_openedCaps.contains(cap));
    }
    Q_INVOKABLE bool canGroup(const QString &key) const;
    Q_INVOKABLE void probeCaps(const QString &key);
    // For the screens (chatpeople.cpp): unknown caps count as a current app and a probe asks; the wire
    // still only sends what their caps list. Call (canCall), Video and video messages use these.
    Q_INVOKABLE bool mayHave(const QString &key, const QString &cap);
    static bool capIn(const QStringList &caps, const QString &cap);
    static bool isRing(const QJsonObject &msg);

    // Per person, set on the press-and-hold sheet, local only (in the encrypted contacts): they may call
    // me (voice and video), send me files, and see when I'm active. Default yes.
    Q_INVOKABLE bool callsAllowed(const QString &key) const;
    Q_INVOKABLE bool filesAllowed(const QString &key) const;
    Q_INVOKABLE bool showsActive(const QString &key) const;
    Q_INVOKABLE void setCallsAllowed(const QString &key, bool on);
    Q_INVOKABLE void setFilesAllowed(const QString &key, bool on);
    Q_INVOKABLE void setShowsActive(const QString &key, bool on);
    // Cap f2 (chatfiles.cpp, protocol.md section 27): "Always from this person", their files arrive without asking.
    Q_INVOKABLE bool filesAlways(const QString &key) const;
    Q_INVOKABLE void setFilesAlways(const QString &key, bool on);
    // A group's file rules: {who ("everyone", "admins", "nobody"), mb (0: no limit), line, canChange}. Admins change them.
    Q_INVOKABLE QVariantMap groupFiles(const QString &g) const;
    Q_INVOKABLE QString setGroupFiles(const QString &g, const QString &who, int mb);
    // Empty when a file of this size from this member is welcome in the group (every member's app checks), else why not.
    QString groupFileRefusal(const QString &g, const QString &from, qint64 size) const;
    bool groupFilesArrive(const QString &g, qint64 size) const;
    QStringList groupFileMembers(const QString &g) const;   // everyone in the group but me

    // Where a chat sits (key: a contact, or "g:" + group id), only on this computer: pinned to the top
    // (newest pin first), archived, muted until a time (ms; 0 unmutes, a huge value is for good), marked unread.
    Q_INVOKABLE void pinChat(const QString &key, bool on);
    Q_INVOKABLE bool isPinnedChat(const QString &key) const { return m_pinnedChats.contains(key); }
    Q_INVOKABLE void archiveChat(const QString &key, bool on);
    Q_INVOKABLE bool isArchived(const QString &key) const { return m_archived.contains(key); }
    Q_INVOKABLE void muteChat(const QString &key, qint64 until);
    Q_INVOKABLE bool isMuted(const QString &key) const;
    // When a muted chat speaks again (ms; 0 when it isn't muted, a huge value until it's turned back on).
    Q_INVOKABLE qint64 mutedUntil(const QString &key) const { return isMuted(key) ? m_mutedUntil.value(key) : 0; }
    // How a chat notifies (chatpeople.cpp): "every" message, "one" until it's opened, or for a group "mentions" (only
    // @mentions and replies to me). notifyMode: what applies now (its own choice, else Reduce notifications); "" to
    // setNotifyMode follows Settings again.
    Q_INVOKABLE QString notifyMode(const QString &key) const;
    Q_INVOKABLE void setNotifyMode(const QString &key, const QString &mode);
    // Hide: someone nearby who isn't a contact leaves the Nearby list for good, with no notifications from them.
    Q_INVOKABLE void hideNearby(const QString &key);
    Q_INVOKABLE bool isHiddenNearby(const QString &key) const { return m_hiddenNearby.contains(key); }
    // Delete chat (a person, someone nearby, a Kids' Device): the conversation and every message kept of it go from this
    // computer, and its row leaves the list until a message comes or goes again. A contact stays a contact; someone
    // nearby who isn't one is forgotten.
    Q_INVOKABLE void deleteChat(const QString &key);
    // Whether the last message for key made a notification (muted, hidden or reduced: no). Main.qml's toast follows it.
    Q_INVOKABLE bool alerted(const QString &key) const { return m_alerted.value(key, true); }
    Q_INVOKABLE void markUnread(const QString &key, bool on);
    Q_INVOKABLE bool isMarkedUnread(const QString &key) const { return m_markedUnread.contains(key); }
    // Always right away (as Muninn's list): their messages make a sound and a notification even while muted.
    Q_INVOKABLE bool isRightAway(const QString &key) const { return m_rightAway.contains(key); }
    Q_INVOKABLE void setRightAway(const QString &key, bool on);
    // Everyone set to Always right away, for the list in Settings › Notifications.
    Q_INVOKABLE QStringList rightAwayKeys() const { return QStringList(m_rightAway.cbegin(), m_rightAway.cend()); }
    // A color for this chat ("" for the usual, else "#RRGGBB"), only on this computer.
    Q_INVOKABLE QString chatColor(const QString &key) const { return m_chatColors.value(key); }
    Q_INVOKABLE void setChatColor(const QString &key, const QString &color);
    // A message arrived for key: false while that chat is muted (unless Always right away); an archived one comes back.
    // Muted, someone nearby put away with Hide, or Reduce notifications (one per chat until opened; a group set to
    // mentions only): false. name: what the notification says (an @mention says so). Calls don't come this way.
    bool notifyFor(const QString &key, const QString &name = QString());

    // Calls (shared/docs/protocol.md 5.2), for CallSignalHooks in main.cpp. sendCall seals msg as
    // room "call" and sends it on every route at once, never through the Outbox.
    void sendCall(const QString &key, const QJsonObject &msg);
    QByteArray signWithChatKey(const QByteArray &bytes) const;
    bool canCall(const QString &key) const;     // contact or group peer, caps c1, not blocked
    QString lanHost(const QString &key) const;  // the host of their Wi-Fi route, or ""
    // A call in a chat's timeline (main.cpp, when one ends): where is a contact's key or "g:<group>". Only on this
    // computer, never sent. A missed 1:1 call marks the chat unread.
    void noteCall(const QString &where, const QString &text, bool missed);

    // Contact folders, only on this computer: [{id, name, count, keys (in their order), collapsed}].
    QVariantList folders() const;
    // The order of the people in no folder (anyone not in it yet goes first, newest first).
    QStringList topOrder() const { return m_topOrder; }
    Q_INVOKABLE QString addFolder(const QString &name);
    Q_INVOKABLE void renameFolder(const QString &id, const QString &name);
    Q_INVOKABLE void deleteFolder(const QString &id);
    Q_INVOKABLE void setInFolder(const QString &id, const QString &key, bool on);
    Q_INVOKABLE QStringList foldersOf(const QString &key) const;
    // Moves someone from one folder to another ("" = no folder: out of every folder) and keeps the
    // target's order as shown (order: everyone shown there afterwards, top to bottom).
    Q_INVOKABLE void placeIn(const QString &key, const QString &from, const QString &to, const QStringList &order);
    Q_INVOKABLE void setCollapsed(const QString &id, bool on);
    Q_INVOKABLE void moveFolder(const QString &id, int by);

    // Groups (chatgroups.cpp). Calls that can fail return what to tell the person, "" when done.
    QVariantList groups() const;
    QVariantList groupInvites() const;
    // kind: "" or "folk" (Folk) or "family" (Family), cap d1.
    Q_INVOKABLE QString createGroup(const QString &name, const QString &kind = QString());
    // Writes everything still waiting to be saved (before Huginn closes or restarts for an update).
    void flushSaves();
    // Battery saver: retries and other background checks further apart (main.cpp follows System::batterySaving).
    void setRelaxed(bool relaxed);
    // True when the last save of the groups or folders didn't go through (createGroup and addFolder then return "").
    Q_INVOKABLE bool saveFailed() const { return m_saveFailed; }
    // The identity keys: made only when there are none yet. secrets.json there but unreadable (or not a JSON object)
    // never gets new ones written over it. --stability-selftest checks this.
    static bool mayMakeKeys(int readStatus, bool parsed, bool hasKey);
    static bool keySelfTest();
    Q_INVOKABLE QVariantMap groupInfo(const QString &g) const;
    Q_INVOKABLE QVariantList groupMessages(const QString &g, const QString &ch) const;
    Q_INVOKABLE void sendGroup(const QString &g, const QString &ch, const QString &text, const QString &re = QString());
    Q_INVOKABLE QString reactGroup(const QString &g, const QString &id, const QString &emoji);
    Q_INVOKABLE QString editGroup(const QString &g, const QString &id, const QString &text);
    Q_INVOKABLE bool sendGroupPhoto(const QString &g, const QString &ch, const QString &path, bool hd);
    Q_INVOKABLE void markGroupRead(const QString &g, const QString &ch);
    Q_INVOKABLE void unsendGroup(const QString &g, const QString &id);
    Q_INVOKABLE void deleteGroupMessage(const QString &g, const QString &id);
    Q_INVOKABLE void retryGroup(const QString &g, const QString &id);
    Q_INVOKABLE QString inviteToGroup(const QString &g, const QString &key);
    Q_INVOKABLE QString groupCode(const QString &g);              // a code good for an hour, or ""
    // secret: cleared from the clipboard after a minute and kept out of clipboard history (message text, codes).
    Q_INVOKABLE void copyText(const QString &text, bool secret = false) const;
    Q_INVOKABLE QVariantMap parseGroupCode(const QString &text) const;
    Q_INVOKABLE QString joinWithCode(const QString &text);
    Q_INVOKABLE QString acceptGroupInvite(const QString &g);
    Q_INVOKABLE void declineGroupInvite(const QString &g);
    Q_INVOKABLE QString renameGroup(const QString &g, const QString &name);
    Q_INVOKABLE QString addChannel(const QString &g, const QString &name, bool voice);
    Q_INVOKABLE QString renameChannel(const QString &g, const QString &ch, const QString &name);
    Q_INVOKABLE QString removeChannel(const QString &g, const QString &ch);
    // Cap g3 (protocol 28): the group's about text ("" takes it away), a text channel's "Only admins post", and a report
    // of someone's message to the group's admins (how many it went to).
    Q_INVOKABLE QString setGroupAbout(const QString &g, const QString &text);
    Q_INVOKABLE QString setChannelAdminsOnly(const QString &g, const QString &ch, bool on);
    Q_INVOKABLE int reportToAdmins(const QString &g, const QString &msgId);
    // Reports that reached me as an admin (newest first): [{id, from, fromName, k, kName, msg, ch, chName, text, time, read}].
    // Opening the list marks them read; Dismiss takes one away.
    Q_INVOKABLE QVariantList groupReports(const QString &g) const;
    Q_INVOKABLE void readGroupReports(const QString &g);
    Q_INVOKABLE void dismissReport(const QString &g, const QString &id);
    // Members whose apps need an update before the about text or "Only admins post" can go on (names), empty when none.
    Q_INVOKABLE QStringList needG3Update(const QString &g) const;
    Q_INVOKABLE QString setAdmin(const QString &g, const QString &key, bool on);
    Q_INVOKABLE QString removeMember(const QString &g, const QString &key);
    Q_INVOKABLE QString handOver(const QString &g, const QString &key);
    Q_INVOKABLE QString leaveGroup(const QString &g);
    Q_INVOKABLE void forgetGroup(const QString &g);
    // Cap j1 (grouplinks.cpp): invite links. ttlMs 0 never expires, maxUses 0 no limit; answers {link, id} or {error}.
    // groupLinks: the active ones [{id, by, byName, x (ms, 0 never), max, uses, counter, link (only my own), mine}];
    // admins see every link, members their own. Joining by a link is joinWithCode (a pasted link works too).
    Q_INVOKABLE QVariantMap createGroupLink(const QString &g, qint64 ttlMs, int maxUses);
    Q_INVOKABLE QVariantList groupLinks(const QString &g) const;
    Q_INVOKABLE QString revokeGroupLink(const QString &g, const QString &id);
    // "Stop this group's codes", as Muninn's Groups.revokeCodes: every group code this computer made for g stops
    // at once (only this computer checks them, protocol.md section 3), and so does every invite link I may stop.
    Q_INVOKABLE QString revokeGroupCodes(const QString &g);
    Q_INVOKABLE QString setMembersInvite(const QString &g, bool on);   // an admin's signed change
    Q_INVOKABLE bool membersInvite(const QString &g) const;

    // Voice rooms and group calls (call/roommanager.cpp, shared/docs/protocol.md section 6): the current
    // state of a group this device is still in (empty otherwise), a member's name in it, whether a
    // member's app can join rooms (caps v1, or not known yet) and whether it is known that it can't.
    QJsonObject groupState(const QString &g) const;
    QString groupMemberName(const QString &g, const QString &key) const { return memberName(g, key); }
    bool canRoom(const QString &key) const;
    bool lacksRooms(const QString &key) const;

    // Message limits (cap d1, chatlimits.cpp): how many days a chat keeps messages. limitInfo: {days, mine,
    // theirs, close (in my Family or Work folder), d1, older (their app keeps everything), choices}.
    Q_INVOKABLE int chatDays(const QString &key) const;
    // The oldest time (ms) a 1:1 chat with key keeps: its message limit, or sooner with "Delete messages
    // after: A day". Call history follows it too (main.cpp, on pruned()).
    qint64 keepCutoff(const QString &key) const;
    Q_INVOKABLE QVariantMap limitInfo(const QString &key) const;
    Q_INVOKABLE void setKeep(const QString &key, int days);
    // A group's {kind, kindName, type, typeName, line, days, older (names whose apps keep everything), choices,
    // links (invite links allowed), unchecked (Family: members whose safety code isn't checked yet)}; admins change it.
    Q_INVOKABLE QVariantMap groupLimit(const QString &g) const;
    Q_INVOKABLE QString setGroupLimit(const QString &g, const QString &kind, int days);
    // Group types, Folk and Family (16.2): [{type, name, line, rules}] for the new group screen and settings.
    Q_INVOKABLE QVariantList groupTypes() const;
    // Someone in one of my Family groups: their files and photos arrive by themselves.
    bool inFamilyGroup(const QString &key) const;
    // "Messages now disappear after 7 days", once on the chat list for someone who had chats already.
    Q_INVOKABLE bool limitsNote() const;
    Q_INVOKABLE void dismissLimitsNote();
    // The first time a chat ("<key>") or group ("g:<group>") is made or opened: how long its messages are kept, once
    // ("" every time after, and for an empty key). Only says; nothing changes.
    Q_INVOKABLE QString keepNoteOnce(const QString &where)
    {
        if (where.isEmpty())
            return {};
        QStringList told = m_settings.value(QStringLiteral("chat/keepTold")).toStringList();
        if (told.contains(where))
            return {};
        told << where;
        while (told.size() > 500)
            told.removeFirst();
        m_settings.setValue(QStringLiteral("chat/keepTold"), told);
        const int days = where.startsWith(QLatin1String("g:")) ? groupDays(where.mid(2)) : chatDays(where);
        if (days <= 0)
            return {};
        return days == 1 ? QStringLiteral("Messages here are kept for 1 day, then they go from every device.")
                         : QStringLiteral("Messages here are kept for %1 days, then they go from every device.").arg(days);
    }
    // Before a backup: nothing past a chat's limit goes in it.
    void pruneNow() { prune(); }
    // Moving to another device (cap x1, move.cpp): nothing is sealed, sent or taken in while true.
    void setMoving(bool on)
    {
        if (on)
            flushSaves();   // what goes to the other device is what's on disk
        m_moving = on;
    }
    bool moving() const { return m_moving; }
    // Caps i1 and f1 and linked devices live in their own classes (trust.*, filelink.*, link.*), made in main.cpp.
    void setExtras(Trust *trust, FileLink *files, Link *link) { m_trust = trust; m_files = files; m_linked = link; }
    // Cap p1, profile pictures and status (profile.*), made in main.cpp too.
    void setProfile(Profile *profile) { m_profile = profile; }
    // Cap b1: this device's ring key, outside the vault (main.cpp makes it).
    void setRingKey(RingKey *ring) { m_ringKey = ring; }
    // Cap n2: this device's nudge key, outside the vault (main.cpp makes it). It only ever takes nudges, never rings.
    void setNudgeKey(NudgeKey *key) { m_nudgeKey = key; }
    // Labs (labs.*): bug reports to the Labs contact, and on the Huginn with the Labs key, its inbox.
    void setLabs(Labs *labs) { m_labs = labs; }
    // Mute for me, volume, group timeouts and bans (cap g2, moderation.*), made in main.cpp.
    void setModeration(Moderation *mod) { m_mod = mod; }

    static const int kMax = 2000;
    // once: "1": true (13.6), the way Muninn's codes carry it.
    static QString code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite = QString(), qint64 expires = 0,
                        bool once = false);

    // Round 14: someone here is known to be on an app that can't nudge this device while it's locked (RingWire::cantNudge).
    bool someoneCantNudge() const;
Q_SIGNALS:
    void recentPicturesReady(const QVariantList &pictures);
    void changed();
    void notice(const QString &text);
    // A message arrived: for the toast, sound and desktop notification. For a group message the
    // key is "g:<group>:<channel>".
    void arrived(const QString &name, const QString &text, const QString &key);
    // An authentic room "call" envelope from a contact or group peer (at most 30 in 10 s each).
    void callMessage(const QString &fromKey, const QJsonObject &msg);
    void pruned();   // messages past their chats' limits went (every minute, and when a limit changes)
    void liveChanged();
    void uploadsChanged();
    // Open this chat on screen (a missed call's notification was clicked): a contact's key or "g:<group>".
    void showChat(const QString &where);
    // A system notification couldn't be shown (no notification service, as in some full-screen sessions): Main.qml
    // shows it as a banner in the window instead. where: as showChat's ("" when it opens nothing).
    void banner(const QString &title, const QString &body, const QString &where);
    // The tray icon's menu (Windows): "mute" (Do not disturb on or off), "lock" (Lock and close) or "quit".
    void trayAction(const QString &what);

private:
    // Caps i1 and f1 and linked devices (trust.cpp, filelink.cpp, link.cpp): rooms intro, file and
    // link, small files as pieces, and sending one message on every route.
    friend class Trust;
    friend class FileLink;
    friend class Link;
    friend class Move;   // moving to another device reads and locks everything (move.cpp)
    friend class Profile;   // sends my picture to contacts and group members, reads the active map (profile.cpp)
    Profile *m_profile = nullptr;
    friend class Labs;   // seals a report to the Labs key and sends it; the Labs inbox opens reports (labs.cpp)
    Labs *m_labs = nullptr;
    friend class Moderation;   // signs and sends group timeouts and bans, reads group states (moderation.cpp)
    Moderation *m_mod = nullptr;
    int receiveProfile(const QString &fromKey, const QString &sentId, const QJsonObject &p);
    Trust *m_trust = nullptr;
    FileLink *m_files = nullptr;
    Link *m_linked = nullptr;
    int receiveExtra(const QString &room, const QString &fromKey, const QString &name, const QString &mailbox, const QJsonObject &p, const QString &via);
    void sendEverywhere(const QString &key, const QString &room, const QJsonObject &msg, const QString &mailbox);
    // Room "link", a small live frame (section 15): Bluetooth, the Wi-Fi and at most 2 relays, never every relay, and in the
    // relays' outbox apart from real messages. History, media and a "me" with its picture never go this way (Link::sendLan).
    void sendLink(const QString &key, const QJsonObject &msg, const QString &mailbox);
    QString sendFileBlob(const QString &key, const QByteArray &bytes, const QString &name);

    void start();
    // from: the address or link it came over (Wi-Fi and Bluetooth), so one device can't flood the Nearby room under many keys.
    int receive(const QByteArray &body, const QString &via, const QString &from = QString());
    int receiveEnvelope(const QJsonObject &o, const QString &via);
    int receivePiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &via);
    QJsonObject envelope(const QString &room, const QString &theirKey, const QByteArray &plain, const QString &msgId);
    QJsonObject seal(const QJsonObject &fields, const QString &theirKey, const QByteArray &plain);
    QByteArray open(const QJsonObject &o, const QString &fromKey, bool *ok);
    void commitPeer();
    QString deliver(const QString &key, const QJsonObject &body, const QString &mailbox);
    // Cap s1 (shared/docs/protocol.md section 21): someone whose app proves its routes and seals its clear fields.
    bool strict(const QString &key) const;
    QString baseTo(const QString &key) const;         // their Wi-Fi address: proven first, an unproven one only when not strict
    bool sendBle(const QString &key, const QByteArray &json);
    bool staleSealed(const QString &key, const QByteArray &body) const;   // sealed before their app said s1 (or had the ratchet)
    void noteS1(const QString &key, bool said, bool bound);
    bool roomRate(const QString &from);
    // Someone who isn't a contact, in lists and notifications: the name they chose, marked so it never passes for a contact's.
    static QString stranger(const QString &name);
    void post(const QString &base, const QByteArray &body, std::function<void(int)> done = nullptr);
    void attempt(const QString &key, const QString &id);
    void attemptUnsend(const QString &key, const QString &id);
    void retryLoop();
    void receipt(const QString &key, const QStringList &ids, const QString &state, const QString &mailbox);
    QString backupMailbox(const QVariantMap &m) const;
    QVariantMap message(const QString &id, const QString &peer, const QString &name, const QString &text, bool mine, const QString &via) const;
    bool addPrivate(const QString &peer, const QVariantMap &m);
    void update(const QString &peer, const QString &id, const std::function<void(QVariantMap &)> &f);
    bool known(const QString &key, const QString &id) const;
    void forget(const QStringList &ids);
    void addNearby(const QVariantMap &m);
    void becomeContacts(const QString &key);
    void putMet(const QString &key, const QVariantMap &m);
    // extra: cap m2 fields kept with the message and sent with its pieces ("video", "al", "ai", "an").
    QString sendBlob(const QString &key, const QByteArray &bytes, int voiceMs, const QVariantMap &extra = QVariantMap());
    bool sendGifTo(const QString &key, const QByteArray &gif);   // cap a1: a cleaned GIF to a contact or "g:<group>:<channel>"
    bool bigIncoming() const;                                      // three GIFs over 3.2 MB already coming in
    bool groupGif(const QString &g, const QString &id) const;      // a group message of mine that is a GIF
    QList<QJsonObject> pieces(const QVariantMap &m);
    QList<QByteArray> sealedFor(const QString &key, const QString &id, const QVariantMap &m);
    void keepSealed(const QString &id, const QString &key, const QList<QByteArray> &bodies);
    void dropSealed(const QString &id);
    void sweepSealed();
    // Ratchet state per person, in the vault (ratchet/<hash>.json).
    void loadRatchet();
    void saveRatchet(const QString &key);
    void forgetRatchet(const QString &key);
    QString ratchetFile(const QString &key) const;
    void noteCapable(const QString &key, bool capable);
    // An existing contact's security key is being replaced: keep them, but unverified and warned.
    void replaceKey(const QString &oldKey, const QString &newKey, const QString &name, const QString &mailbox);
    QString contactWithMailbox(const QString &mailbox, const QString &notKey) const;
    void systemNote(const QString &key, const QString &text, bool call = false);   // call: a call's line (a click calls back)
    void prune();
    void save();
    // The chats saved a moment later, once for a burst of changes (flushSaves writes it at once).
    void saveSoon();
    // changed, once for a burst: what only the network side noticed (a relay, a nearby device, Bluetooth).
    void changedSoon();
    // Says changed: at once for what the person did; once for a burst (changedSoon) for what came over the network.
    void noteChanged();
    // While one lives, what changes came over the network (a message, a relay's OK).
    struct FromNetwork {
        explicit FromNetwork(Chat *c) : chat(c) { if (chat) ++chat->m_netDepth; }
        ~FromNetwork() { if (chat) --chat->m_netDepth; }
        FromNetwork(const FromNetwork &) = delete;
        FromNetwork &operator=(const FromNetwork &) = delete;
        Chat *chat;
    };
    int m_netDepth = 0;
    // chats(), kept until something changes (or a second has passed): many parts of the screen read it after each change.
    mutable QVariantList m_chatsCache;
    mutable QElapsedTimer m_chatsAge;
    // How often retryLoop runs: every second in front, every 3 seconds behind other windows or with Battery saver.
    void retimeRetry();
    void saveMet();
    void saveContacts();
    void forgetPerson(const QString &key);
    void pruneTrash();
    void load();
    QByteArray keyWith(const QString &theirKey) const;
    QString myKey() const { return QString::fromLatin1(m_pub.toBase64()); }
    QString wrap(const QString &peerKey, const QByteArray &body) const;
    // unknownDay: the wrapping names a day key this device doesn't have (or no longer has).
    QByteArray unwrap(const QString &content, bool *unknownDay = nullptr) const;
    // Within 7 days of restoring a backup (restore/at): a wrapping for an unknown day key is tried again later.
    bool justRestored() const;
    // Caps b1 and e1 (shared/docs/protocol.md section 23, ring.*): my day keys for the relay wrapping and what each
    // contact announced (both in the vault, made when it opens), my ring key (outside it).
    std::unique_ptr<WrapKeys> m_wrapKeys;
    std::unique_ptr<PeerKeys> m_peerKeys;
    RingKey *m_ringKey = nullptr;
    NudgeKey *m_nudgeKey = nullptr;   // cap n2
    QSet<QString> m_rung;       // call ids a ring envelope already went out for
    QSet<QString> m_keysSent;   // contacts my keys went to when their app first said b1 or e1
    // A receipt to an app that says b1 or e1 carries my day key ("wk", "wt"), and to a contact (not a group-only peer,
    // never someone blocked) my ring key and mailbox ("rk", "rp"). To a contact or group peer whose app says n2 (never
    // someone blocked): my nudge key and nudge mailbox ("nk", "np").
    QJsonObject withKeys(const QString &key, QJsonObject o) const;
    // An empty receipt with my keys; once per person unless again (a block renewed the ring key, a restore the day key).
    void sendKeys(const QString &key, bool again = false);
    // A 1:1 offer also goes as a ring envelope to their ring mailbox (b1).
    void ring(const QString &key, const QJsonObject &msg);
    // Caps n1 and n2 (section 23.3): a real message (RingWire::nudgeCounts) also sends a nudge. n2 (a contact or group
    // peer): my name, the group's name (chat, "" for 1:1) and the kind ("" for RingWire::nudgeKind(room)), to their nudge
    // mailbox. n1 only (a contact): the plain nudge to their ring mailbox. At most one a minute per person (and chat, n2).
    void nudge(const QString &key, const QString &room, const QByteArray &plain, const QString &kind = QString(), const QString &chat = QString());
    NudgeGate m_nudged;
    // Room "file" goes through the ratchet for a contact whose app says e1.
    bool fileRatchet(const QString &room, const QString &key) const;
    bool inviteOk(const QString &token) const;
    void saveInvites();
    QString mailboxOf(const QString &key) const;
    bool isGroupPeer(const QString &key) const { return m_gpeers.contains(key); }
    // Sends over the internet: to their mailbox on the relays. False when they have none.
    // days: how long relays may keep it (0: this chat's message limit).
    // step: after each piece a relay took, how many have been so far.
    bool sendInternet(const QString &key, const QList<QByteArray> &bodies, const QString &mailbox,
                      std::function<void()> sent = nullptr, int days = 0, std::function<void(int)> step = nullptr);
    QVariantMap uploads() const { return m_uploads; }
    // replace (round 14, 1.1): the caps came on an empty receipt (a caps probe or its answer), made now and never an old
    // copy sent again, so they are what the app says now: they replace the set instead of adding to it.
    void noteCaps(const QString &key, bool present, const QStringList &caps, bool replace = false);
    // chatpeople.cpp
    void sayHello(const QString &key);
    void setAllowed(const QString &key, const QString &what, bool on);
    void loadFlags();
    void saveFlags();
    void forgetFlags(const QString &key);
    void loadFolders();
    bool saveFolders();
    // A save didn't go through: said on screen (at most once a minute) and tried again in a few seconds, unless the
    // vault holds that file back (it couldn't be read this run).
    void saveDidntGo(const QString &file, const std::function<void()> &again);
    QHash<QString, std::function<void()>> m_resavers;   // onSaveLost: files other parts keep, written again from memory
    // cap m1
    int receiveM1(const QString &room, const QString &fromKey, const QString &name, const QString &mailbox, const QString &sentId, const QJsonObject &p);
    void queueMx(const QString &key, const QByteArray &plain);
    void attemptMx(const QString &envId);
    void saveMx();
    void sendLive(const QString &key, const QByteArray &plain);
    void sendActive(bool on);
    void liveTick();
    void savePins();
    // Built once per message list: where each id sits and how many messages reply to it.
    struct M1Index {
        QHash<QString, int> at;             // message id -> index in the list
        QHash<QString, int> replies;        // message id -> how many messages have it as "re"
    };
    static M1Index m1Index(const QVariantList &all);
    QVariantMap withM1(const QVariantMap &m, const QVariantList &all, const M1Index &idx, const QString &theirName) const;
    void takeGroupChange(const QString &g, const QString &from, const QJsonObject &p);
    // cap m2 (chatmore.cpp)
    QString sendPrivateWith(const QString &key, const QString &text, const QString &re, const QJsonObject &poll);
    void sendGroupWith(const QString &g, const QString &ch, const QString &text, const QString &re, const QJsonObject &poll);
    void attemptWithPreview(const QString &key, const QString &id);
    void queueGroupMessage(const QString &g, const QString &id, const QStringList &to, const QJsonObject &p);
    int takeVote(const QString &fromKey, const QString &mailbox, const QString &sentId, const QJsonObject &p);
    void takeGroupVote(const QString &g, const QString &from, const QJsonObject &p);
    QVariantList withM2(const QVariantList &list, const QString &g) const;

    // ---- groups (chatgroups.cpp)
    struct Group {
        QJsonObject state;                  // the current accepted state
        QList<QJsonObject> history;         // the last accepted states, oldest first
        QVariantList messages;              // every channel's, each with "ch"
        QHash<QString, int> unread;         // channel -> unread messages
        QList<QJsonObject> held;            // messages from someone not in the state yet
        QJsonObject lastOp;                 // my last change, re-done if it loses a conflict
        QVariantList reports;               // cap g3: reports that reached me as an admin, oldest first (at most 100)
        int reportsUnread = 0;              // of those, not seen in the Reports list yet
        bool removed = false, left = false;
        qint64 since = 0;
    };
    struct GroupOut {                       // one member's copy of a group message, until they confirm
        QString g, to, logical, room;
        qint64 time = 0, next = 0;
        int count = 0;
    };
    int receiveGroup(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via);
    int receiveGroupPiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via);
    bool applyState(const QString &g, const QJsonObject &next, const QString &from, const QString &op);
    void stateAccepted(const QString &g, const QJsonObject &old, bool first);
    QString change(const QString &g, const QJsonObject &op);
    QJsonObject nextState(const QString &g) const;
    QString commit(const QString &g, QJsonObject next, const QJsonObject &op, const QStringList &alsoTo = QStringList());
    void queueGroup(const QString &g, const QString &to, const QString &room, const QString &logical, const QByteArray &plain);
    void queueGroupBodies(const QString &g, const QString &to, const QString &room, const QString &logical, const QList<QByteArray> &bodies);
    void sendControl(const QString &g, const QString &to, QJsonObject msg);
    void groupReceipt(const QString &g, const QString &to, const QStringList &ids, const QString &state);
    void groupAttempt(const QString &envId);
    void groupRetry();
    void sendSync(const QString &g, const QString &to);
    void sendStateTo(const QString &g, const QString &to);
    void refreshGroupPeers();
    void storeGroupMessage(const QString &g, const QVariantMap &m, bool notify);
    bool gmsgKnown(const QString &id) const { return m_gidx.contains(id) || m_deleted.contains(id); }
    void processHeld(const QString &g);
    void groupNote(const QString &g, const QString &text, bool call = false);
    void takeReport(const QString &g, const QString &from, const QString &msgId, const QString &about, const QString &text);
    void refuseJoin(const QString &g, const QString &to, const QString &mailbox, const QString &name, const QString &why);
    static QString refusalText(const QString &why, const QString &group);
    QString groupName(const QString &g) const;
    QString memberName(const QString &g, const QString &key) const;
    QString sendGroupBlob(const QString &g, const QString &ch, const QByteArray &bytes, int voiceMs, const QVariantMap &extra = QVariantMap());
    QList<QByteArray> groupPieces(const QString &g, const QString &ch, const QString &gid, const QString &to, const QByteArray &bytes, int voiceMs,
                                  const QVariantMap &extra = QVariantMap());
    void updateGroupMessage(const QString &g, const QString &id, const std::function<void(QVariantMap &)> &f);
    bool tokenOk(const QString &token, const QString &g) const;
    int gmsgIn(const QString &g, const QString &from, const QString &name, const QString &via, const QJsonObject &p);
    void loadGroups();
    bool saveGroups();
    void saveGroupsSoon();
    void saveGroupChats();
    void saveGroupChatsSoon();   // once for a burst (flushSaves writes it at once)
    // Messages past each group's limit go; their ids are added to gone. cutoff: the oldest time kept for a limit in days.
    void pruneGroups(const std::function<qint64(int)> &cutoff, QStringList *gone);
    // cap j1 (grouplinks.cpp)
    bool hasJ1(const QString &key) const;
    void loadLinks();
    void saveLinks();
    QString liveLink(const QString &token, const QString &g) const;
    void sendLinks(const QString &g);
    bool takeLinkControl(const QString &g, const QString &from, const QJsonObject &p);
    void linkUsed(const QString &token, const QString &g);
    void familyLinksOff(const QString &g);
    QHash<QString, QVariantMap> m_glinks;               // links counted here: token -> {g, by, x, max, uses, off}
    QHash<QString, QVariantList> m_glinkLists;          // group -> links as their counters said
    QHash<QString, QString> m_gmade;                    // link id -> the links made here
    // cap d1 (chatlimits.cpp)
    bool hasD1(const QString &key) const;
    bool isClose(const QString &key) const;
    int myDays(const QString &key) const;
    int groupDays(const QString &g) const;
    void noteDays(const QString &key, int days);
    QString limitBlocked(const QString &g, const QString &kind) const;
    void noteLimitsOnce();
    void pruneToLimits();
    // Group types (chatlimits.cpp): members of my Family groups who are contacts go in my Family folder, once each.
    void familySync();

    LocalSend *m_net;
    Vault *m_vault;
    Nostr *m_nostr;
    Ble *m_ble;
    QSettings m_settings;
    // Your chat name and who you blocked: in the vault (prefs.json), read when it opens.
    mutable QJsonObject m_prefs;
    bool m_prefsLoaded = false;
    mutable QString m_tempName;   // a name for this run only, while the vault isn't open yet
    void loadPrefs();
    void savePrefs() const;
    QStringList blockedKeys() const { return m_prefs.value(QStringLiteral("muted")).toVariant().toStringList(); }
    QNetworkAccessManager m_http;
    QTimer m_retry, m_prune;
    bool m_ready = false;
    QString m_openChat;
    QByteArray m_priv, m_pub;
    // keyWith()'s shared keys by their key, made once (an ECDH and HKDF each); cleared when my key changes.
    mutable QHash<QString, QByteArray> m_sharedKeys;
    mutable QByteArray m_sharedFor;
    QVariantList m_nearby;
    QHash<QString, QVariantList> m_private;
    QHash<QString, QVariantMap> m_contacts;            // key -> {key, name, mailbox, verified}
    QHash<QString, QVariantMap> m_met;                 // key -> {name, mailbox, iSaid, theySaid, dismissed, verified}
    QHash<QString, QVariantMap> m_held;                // key -> {id -> message}, waiting on a request
    QHash<QString, QVariantMap> m_requests;            // key -> {key, name, code, mailbox}
    QHash<QString, qint64> m_deleted;
    QHash<QString, QString> m_unsentBy;        // group message id unsent before it got here -> who unsent it
    QHash<QString, qint64> m_invites;                  // invites in my codes: token -> expiry ms
    QString m_link;
    qint64 m_linkExpires = 0;                  // ids deleted here (two days): late copies stay gone
    QSet<QString> m_unread;
    QHash<QString, QJsonObject> m_unsentKeeps;          // key -> the sealed "keep" not delivered yet
    QHash<QString, bool> m_capable;                     // key -> their app said "cap":"r1"
    QHash<QString, Ratchet::Peer> m_peers;
    ChatWire::Outbox m_outbox;                          // sealed envelopes for resending, per message id
    ChatWire::Seen m_seen;
    // The sender's peer after a ratchet message opened, kept once the message is accepted.
    QString m_pendingKey;
    std::optional<Ratchet::Peer> m_pendingPeer;
    // Who the message being handled proved to be from, whether it said "cap":"r1", and its "caps".
    QString m_openedFrom;
    bool m_openedCap = false, m_openedHasCaps = false;
    bool m_openedReplaceCaps = false;                   // it was an empty receipt: its caps replace (noteCaps)
    int m_openedDays = 0;                               // its "days" (cap d1), 0 when none
    qint64 m_openedPv = 0;                              // its "pv" (cap p2), 0 when none
    QStringList m_openedCaps;
    bool m_openedBound = false;                         // it came sealed with its clear fields (cap s1)
    QString m_proofFp, m_proof;                         // cap s1: my chat key's signature over my certificate (made once)
    QSet<QString> m_s1;                                 // keys whose app said s1
    QHash<QString, qint64> m_bound;                     // key -> when their envelopes first came bound (cap s1)
    QHash<QString, QList<qint64>> m_failures;           // key -> ratchet envelopes that didn't open lately
    QHash<QString, QList<qint64>> m_roomTimes;          // address or link -> Nearby room messages lately
    QHash<QString, QStringList> m_caps;                 // key -> their caps tokens (known only)
    QHash<QString, qint64> m_probed;                    // key -> last caps probe
    QList<QVariantMap> m_folders;                       // {id, name, keys, collapsed}
    QStringList m_topOrder;                             // people in no folder, in the order shown
    QHash<QString, QVariantMap> m_trash;                // key -> their contact entry + {trashed (ms), contact, met}
    QHash<QString, Group> m_groups;
    QHash<QString, QString> m_gidx;                     // group message id -> group
    QHash<QString, QVariantMap> m_gpeers;               // members who aren't contacts: key -> {key, name, mailbox}
    QHash<QString, QVariantMap> m_ginvites;             // invites from contacts: group -> {g, from, fromName, name, count, inv}
    QHash<QString, QVariantMap> m_joining;              // groups being joined: group -> {admin, adminName, mailbox, token, name, since}
    QHash<QString, QVariantMap> m_gtokens;              // my invites and group codes: token -> {g, x, for}
    QHash<QString, GroupOut> m_gout;                    // envelope id -> waiting for that member's receipt
    QHash<QString, qint64> m_syncAsked;                 // group|key -> when
    bool m_groupsDirty = false;
    bool m_gchatsDirty = false;   // the group messages wait to be saved (saveGroupChatsSoon)
    bool m_chatsDirty = false;
    bool m_changedSoon = false;
    bool m_relaxed = false;
    bool m_saveFailed = false;
    qint64 m_saveSaid = 0;   // when "Couldn't save" was last said
    int m_startTries = 0;    // secrets.json couldn't be read: Chat starts again a few times
    QHash<QString, QList<qint64>> m_recent, m_pieceTimes, m_recentGroup, m_recentCall, m_recentM1;
    QHash<QString, QVariantMap> m_mxOut;                // reactions and edits on their way: envelope id -> {key, time}
    QHash<QString, qint64> m_typing, m_active, m_typingSent;
    QHash<QString, QStringList> m_pins;                 // where -> pinned message ids (only on this computer)
    QStringList m_pinnedChats;                          // chats pinned to the top, in order
    QSet<QString> m_archived, m_markedUnread;
    QHash<QString, qint64> m_mutedUntil;
    QSet<QString> m_hiddenNearby;                       // Hide: nearby people who aren't contacts, kept off the list
    QSet<QString> m_clearedChats;                       // Delete chat: off the list until a message comes or goes
    QHash<QString, QString> m_notifyMode;               // a chat's own "every", "one" or "mentions"
    QSet<QString> m_notified;                           // chats that notified since they were last opened
    bool m_reportArriving = false;                      // takeReport is announcing a report (toMe: it's for me)
    QHash<QString, bool> m_alerted;                     // whether the last message for a key notified
    bool toMe(const QString &key, const QString &name) const;   // a group message that mentions or answers me
    QSet<QString> m_rightAway;
    QHash<QString, QString> m_chatColors;
    QSet<QString> m_keptBack;
    bool m_moving = false;                           // contacts whose "keep" got my yes back (once each)
    bool m_front = false;
    qint64 m_activeSent = 0;
    struct Tries { int count = 0; qint64 next = 0; };
    QHash<QString, Tries> m_tries;
    struct Incoming { QString from; QList<QByteArray> parts; int have = 0; qint64 started = 0; QString file; };
    QVariantMap m_uploads;
    void uploaded(const QString &id, double fraction);
    QHash<QString, Incoming> m_incoming;
};
