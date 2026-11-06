import QtQuick
import QtMultimedia
import Huginn

// Cap m2 over a chat or a group channel (shared/docs/protocol.md section 12), the same as Muninn's ChatMore.kt and
// CameraScreen.kt: the Send menu (Camera, Saved media, Encrypted file sharing, Poll), Saved media (SavedMedia.qml:
// photos, videos and GIFs, up to 10 together), an album full size with arrows, a video message playing, the camera
// (a click or A for a photo, held for a video message; either waits for Send or Discard, never sent by itself), a new
// poll, and the Shared page (photos, videos, links and voice).
// where: the contact's key or "g:<group>:<channel>"; title: who or what it goes to.
// On the Shared page every photo, video and voice message is a square tile (one not on this device says so). A click
// (or A) opens it; a right click, a long press, X, LT or Menu opens its menu: Open, Show in chat (the chat scrolls to
// it and lights it for a moment), Save, Forward (sent again as a new message) and Delete (on this computer only,
// held 5 seconds).
// Y goes to the next tab.
Item {
    id: more
    property string where: ""
    property string title: ""
    property bool tour: false
    property var photos: []          // an album open full size
    property int photoAt: 0
    property string videoId: ""      // a video message playing (its stored photo id)
    property bool camera: false      // the camera is open (a photo or a video message)
    property string shot: ""         // a photo just taken, waiting for Send or Discard (its scratch file)
    property bool shutterDown: false // the shutter (a click or A) is held
    property bool heldVideo: false   // this hold started a video message: letting go stops it
    property bool sharedOpen: false
    property string sharedTab: "media"
    property int sharedSel: -1
    // The Shared page loads a page at a time: more as you scroll near the end.
    readonly property int pageSize: 60
    property int sharedShown: pageSize
    readonly property var sharedData: !sharedOpen ? ({ media: [], links: [], voices: [] }) : tour ? tourShared : (Chat.chats, Chat.groups, Chat.shared(where))
    // The tour's Shared page: one tile of each kind (nothing here is real).
    readonly property var tourShared: ({
        media: [{ id: "t1", photo: "t1", video: 0, name: "Sam", here: true },
                { id: "t2", photo: "t2", video: 42000, name: "You", mine: true, here: true },
                { id: "t3", photo: "t3", video: 0, gif: true, name: "Jake", here: true },
                { id: "t4", photo: "t4", video: 0, name: "Mira", here: false }],
        links: [{ id: "t5", url: "https://example.com", title: "example.com", description: "", host: "example.com", thumb: "", name: "Sam" }],
        voices: [{ id: "t6", photo: "t6", voice: 14000, name: "Sam", time: Date.now() - 3600000, here: true },
                 { id: "t7", photo: "t7", voice: 6000, name: "Mira", time: Date.now() - 86400000, here: false }] })
    readonly property var sharedVoices: sharedData.voices || []
    // The tabs in order, for Y on a controller.
    readonly property var sharedTabs: ["media", "links", "voice"]
    function sharedCount() { return sharedTab === "media" ? sharedData.media.length : sharedTab === "links" ? sharedData.links.length : sharedVoices.length }
    readonly property bool busy: photos.length > 0 || videoId !== "" || camera || pollDlg.visible || sharedOpen || savedMedia.shown
    // Media and files › Show in chat: the page scrolls to the message and lights it for a moment.
    signal showInChat(string msgId)
    // The button legend while the Shared page, Saved media or the camera is in front (the page shows it instead of its own).
    readonly property var prompts: camera ? (shot !== "" || Videos.reviewing
                                             ? [{ key: "a", label: "Send" }, { key: "b", label: "Discard" }]
                                             : Videos.recording ? [{ key: "a", label: "Let go to stop" }]
                                             : [{ key: "a", label: "Photo, hold for video" }, { key: "b", label: "Cancel" }])
        : savedMedia.shown ? savedMedia.prompts
        : sharedOpen && photos.length === 0 && videoId === ""
        ? [{ key: "dpad", label: "Choose" }, { key: "a", label: sharedTab === "voice" ? "Play" : "Open" }, { key: "lt", label: "Options" },
           { key: "y", label: "Next tab" }, { key: "b", label: "Close" }] : []
    anchors.fill: parent
    z: 40

    // ------------------------------------------------------------- what the page calls
    // The Send menu, the same four rows as Muninn's: Camera, Saved media, Encrypted file sharing, Poll.
    function attach() {
        if (tour) return
        if (where === "") { Ui.say("Open a chat first.", true); return }
        const cam = Videos.hasCamera()
        // FileLink.reachNote: "" when Nearby, Trusted direct or Tor works right now, else "Ask them to update" (an
        // older app) or "Can't reach them right now". A group keeps the row on: each member gets their own path.
        const inGroup = String(where).indexOf("g:") === 0
        const note = inGroup || typeof FileLink.reachNote !== "function" ? "" : String(FileLink.reachNote(where) || "")
        const reach = note === ""
        const pad = !!Ui.main && Ui.main.padInUse
        Ui.pickFrom("Send to " + title, [{ header: "", items: [
            { name: "Camera", detail: !cam ? "No camera found" : pad ? "A for a photo, hold A for video" : "Click for a photo, hold for video",
              url: "camera", icon: "video", disabled: !cam },
            { name: "Saved media", detail: "Photos, videos and GIFs, up to 10", url: "media", icon: "image" },
            { name: "Encrypted file sharing", detail: reach ? "Nearby, trusted or Tor" : note, url: "file", icon: "attach", disabled: !reach },
            { name: "Poll", detail: "Ask a question", url: "poll", icon: "check" }] }], a => {
                if (a === "camera") { if (cam) openCamera(); else Ui.say("No camera found on this device.", true) }
                else if (a === "media") savedMedia.openPicker()
                else if (a === "file") { if (reach) PeopleActions.sendFile(where); else Ui.say(note, true) }
                else if (a === "poll") newPoll()
            })
    }
    // Saved media › Other folders: the system's file window, or Huginn's own list on a controller (game mode).
    // Photos and GIFs go as an album, videos by the video rules; anything else belongs to Encrypted file sharing.
    function browseMedia() {
        Ui.pickFiles(paths => more.sendMedia(paths))
    }
    function sendMedia(paths) {
        const pics = [], clips = []
        let skipped = 0
        for (let i = 0; i < paths.length; i++) {
            const p = String(paths[i]).indexOf("file:") === 0 ? Chat.localPath(paths[i]) : String(paths[i])
            const k = MediaFiles.kindOf(p)
            if (k === "video") clips.push(p)
            else if (k !== "") pics.push(p)
            else skipped++
        }
        if (pics.length + clips.length > 10) { Ui.say("Up to 10 at once.", true); return }
        if (skipped) Ui.say("Only photos, videos and GIFs here. Other files go with Encrypted file sharing.", !pics.length && !clips.length)
        if (pics.length) sendPictures(pics, false)
        if (clips.length) sendVideos(clips)
    }
    function sendPictures(paths, hd) {
        const r = Chat.sendPhotos(where, paths, hd)
        if (r !== "") Ui.say(r, true)
        else Ui.say(paths.length === 1 ? "Sending the picture." : "Sending " + paths.length + " pictures" + (paths.length > 1 ? " as an album." : "."))
    }
    // Videos from Saved media, one after another. Each follows the video rules (agent V's planVideo and
    // sendPickedVideo): full size to anyone close by or direct, the 720p copy (up to 5 minutes) for the rest. Over the
    // limit: "Video too long", with Send as a file (Encrypted file sharing) or Cancel. Never trimmed.
    function sendVideos(paths) {
        if (!paths || !paths.length) return
        const path = paths[0], rest = paths.slice(1)
        const w = where
        const next = () => Qt.callLater(() => { if (more.where === w) more.sendVideos(rest) })
        if (Chat.videoSendingOff()) { Ui.say("Off while Ultra privacy is on.", true); return }
        const plan = Chat.planVideo(w, path, false) || ({})
        const asFile = { name: "Send as a file", detail: "Encrypted file sharing, full quality", url: "file", icon: "attach" }
        const offerFile = (title, why) => Ui.pickFrom(title, [{ header: why || "", items: [asFile, { name: "Cancel", url: "cancel", icon: "close" }] }],
                                                      a => { if (a === "file") PeopleActions.sendFiles(w, [path]); next() }, next)
        if (plan.tooLong || plan.length === "too long") { offerFile("Video too long", plan.why || "Videos can be up to 5 minutes."); return }
        if (!plan.ok) {
            if (plan.asFile) offerFile("Send this video", plan.why || "")
            else { Ui.say(plan.why || "That video can't be sent.", true); next() }
            return
        }
        if ((plan.full || 0) + (plan.relay || 0) === 0) { offerFile("Send this video", plan.summary || ""); return }
        const r = Chat.sendPickedVideo(w, path, !!plan.inFull) || ({})
        if (r.said) Ui.say(r.said, !r.ok)
        next()
    }
    function sendClips(paths) {
        let sent = 0
        paths.forEach(p => { const r = Videos.sendFile(where, p); if (r !== "") Ui.say(r, true); else sent++ })
        if (sent) Ui.say(sent === 1 ? "Sending the video message." : "Sending " + sent + " video messages.")
    }
    // Dropped files: pictures go in the chat (several as an album), anything else as a file, by the best route (the
    // same as Send file). A video goes as a file too; a short MP4 that fits a video message asks which.
    function dropFiles(urls) {
        const pics = [], others = [], clips = [], clipUrls = []
        for (let i = 0; i < urls.length; i++) {
            const path = Chat.localPath(urls[i])
            if (path === "") continue
            if (Chat.isPicture(path)) pics.push(path)
            else if (/\.(mp4|m4v|mov)$/i.test(path) && Videos.fitsMessage(path)) { clips.push(path); clipUrls.push(urls[i]) }
            else others.push(urls[i])
        }
        if (pics.length) sendPictures(pics, false)
        // Caps f1 and f2: anything else as a file, to the person or to everyone in the group.
        if (others.length) PeopleActions.sendFiles(where, others)
        if (!clips.length) return
        Ui.pickFrom(clips.length === 1 ? "Send this video" : "Send " + clips.length + " videos", [{ header: "", items: [
            { name: "As a file", detail: "Saved on their device, full quality", url: "file", icon: "attach" },
            { name: "As a video message", detail: "Plays in the chat", url: "message", icon: "play" }] }],
            a => { if (a === "message") more.sendClips(clips); else if (a === "file") PeopleActions.sendFiles(more.where, clipUrls) })
    }
    function newPoll() { pollDlg.open("Poll for " + title) }
    function viewPhotos(list, i) { photos = list; photoAt = i }
    function playVideo(id) {
        if (tour) return
        videoId = id
        Videos.play(id, playOut)
    }
    function closeVideo() { Videos.stop(); videoId = "" }
    // Camera: a click (or A) takes a photo; held past holdTimer it records a video message (up to a
    // minute) until let go. Either waits for Send or Discard, never sent by itself.
    function openCamera() {
        if (!Videos.hasCamera()) { Ui.say("No camera found on this device.", true); return }
        shot = ""; shutterDown = false; heldVideo = false
        camera = true
        Qt.callLater(() => { if (!Videos.openCamera(camOut)) { Ui.say(Videos.error, true); camera = false } })
    }
    function closeCamera() {
        holdTimer.stop()
        discardShot()
        Videos.closeCamera()
        Videos.discard()
        shutterDown = false; heldVideo = false
        camera = false
    }
    function discardShot() { if (shot !== "") { const p = shot; shot = ""; MediaFiles.shred(p) } }
    function shutterPress() {
        if (shot !== "" || Videos.reviewing) return
        // A press while one records (a hold whose let-go never came) stops it.
        if (Videos.recording) { Videos.stopRecording(); heldVideo = false; return }
        shutterDown = true; heldVideo = false
        holdTimer.restart()
    }
    function shutterRelease() {
        if (!shutterDown) return
        shutterDown = false
        if (holdTimer.running) { holdTimer.stop(); takePhoto(); return }
        if (heldVideo && Videos.recording) Videos.stopRecording()
        heldVideo = false
    }
    // The shutter's press taken away (the window lost the mouse): no photo; a video message being held stops.
    function shutterCancel() {
        holdTimer.stop()
        if (shutterDown && heldVideo && Videos.recording) Videos.stopRecording()
        shutterDown = false; heldVideo = false
    }
    function takePhoto() {
        const p = MediaFiles.keepFrame(camOut)
        if (p === "") { Ui.say("The camera isn't ready yet.", true); return }
        shot = p
    }
    function sendRecorded() {
        if (shot !== "") {
            const p = shot
            shot = ""
            sendPictures([p], false)
            MediaFiles.shred(p)
            closeCamera()
            return
        }
        const r = Videos.send(where)
        Ui.say(r !== "" ? r : "Sending the video message.", r !== "")
        if (!Videos.reviewing) camera = false
    }
    // Retake: back to the camera from a photo or a video message waiting for Send.
    function retake() {
        if (shot !== "") { discardShot(); return }
        if (Videos.reviewing) { Videos.stop(); Videos.discard() }
    }
    Timer {
        id: holdTimer
        interval: 400
        onTriggered: {
            if (!more.shutterDown || !more.camera) return
            // Ultra privacy with video sending off: a hold does nothing but say why.
            if (Chat.videoSendingOff()) { Ui.say("Videos are off while Ultra privacy is on.", true); return }
            more.heldVideo = true
            Videos.record()
        }
    }
    function askOpen(url) {
        if (url === "") return
        // Ultra privacy: the browser isn't behind Tor, so say so and offer Copy link instead.
        const ultra = UltraPrivacy.active
        Ui.sure("Open " + Chat.linkHost(url) + "?", ultra ? url + "\n\nIt opens in your browser, outside Tor. The site sees your internet address." : url,
                "Open", () => { if (!Chat.openLink(url)) Ui.say("That link can't be opened here.", true) })
        if (ultra && Ui.confirm) { Ui.confirm.otherText = "Copy link"; Ui.confirm.otherCallback = () => { Chat.copyText(url); Ui.say("Link copied.") } }
    }
    function openShared() { if (tour || where !== "") { sharedTab = "media"; sharedSel = -1; sharedShown = pageSize; sharedOpen = true } }
    function sharedList() { return sharedTab === "links" ? sharedData.links : sharedTab === "voice" ? sharedVoices : sharedData.media }
    function kindOf(it) { return sharedTab === "voice" ? "voice message" : it.video > 0 ? "video" : it.gif ? "GIF" : "photo" }
    function openSharedItem(i) {
        const it = sharedList()[i]
        if (!it || tour) return
        if (sharedTab === "links") { askOpen(it.url); return }
        if (it.here === false) { Ui.say("This " + kindOf(it) + " isn't on this device.", true); return }
        if (sharedTab === "voice") {
            if (Voice.playing === it.photo) Voice.stop(); else Voice.play(it.photo)
            return
        }
        if (it.video > 0) { playVideo(it.photo); return }
        const pics = sharedData.media.filter(x => !(x.video > 0) && x.here !== false)
        viewPhotos(pics.map(x => x.photo), Math.max(0, pics.findIndex(x => x.id === it.id)))
    }
    // The Menu key and Shift+F10 (the page's contextMenu and menuAnchor): the picked item's menu, at its tile.
    function anchorItem() { return sharedOpen && sharedSel >= 0 ? (sharedTab === "links" ? linkList : tileGrid).itemAtIndex(sharedSel) : null }
    function contextMenu() {
        if (sharedSel < 0 && sharedCount() > 0) sharedSel = 0
        if (sharedSel >= 0) itemMenu(sharedSel)
    }
    // An item's menu (a right click, a long press, X, LT or Menu): at its tile or row, or as the list when it isn't in view.
    function itemMenuAt(i) {
        const view = sharedTab === "links" ? linkList : tileGrid
        const item = view.itemAtIndex(i)
        sharedSel = i
        if (item) Ui.menuAt(item, Math.min(item.width / 2, Theme.px(140)), item.height / 2, () => more.itemMenu(i))
        else itemMenu(i)
    }
    function itemMenu(i) {
        const it = sharedList()[i]
        if (!it) return
        sharedSel = i
        if (sharedTab === "links") {
            Ui.pickFrom(it.host || "Link", [{ header: "", items: [
                { name: "Open", icon: "attach", url: "open" },
                { name: "Show in chat", icon: "thread", url: "show" },
                { name: "Copy link", icon: "attach", url: "copy" },
                { name: "Forward", icon: "forward", url: "forward", sub: () => { if (!more.tour) PeopleActions.forwardMenu(it.url) } }] }],
                a => more.runItem(it, a))
            return
        }
        const here = it.here !== false, kind = kindOf(it)
        const items = []
        if (here) items.push({ name: sharedTab === "voice" ? (Voice.playing !== "" && Voice.playing === it.photo ? "Stop" : "Play") : it.video > 0 ? "Play" : "Open",
                               icon: sharedTab === "voice" || it.video > 0 ? "play" : "image", url: "open" })
        items.push({ name: "Show in chat", icon: "thread", url: "show" })
        if (here) items.push({ name: "Save", icon: "save", detail: "To " + saveWhere(it), url: "save" },
                             { name: "Forward", icon: "forward", url: "forward", sub: () => { if (!more.tour) PeopleActions.forwardMedia(it, kind) } })
        Ui.pickFrom(kind.charAt(0).toUpperCase() + kind.slice(1) + " from " + (it.name || "someone"),
                    [{ header: here ? "" : "Not on this device", items: items }, { header: "", items: [{ name: "Delete", url: "delete", danger: true }] }],
                    a => more.runItem(it, a))
    }
    // Where Save puts it: pictures beside Save to Pictures', videos and voice beside received files.
    function saveWhere(it) { return sharedTab === "voice" || it.video > 0 ? (Transfer.saveName || "Downloads") + " › Huginn" : "Pictures › Huginn" }
    function runItem(it, a) {
        if (tour || !it) return
        const i = sharedList().findIndex(x => x.id === it.id)
        if (a === "open") { if (i >= 0) openSharedItem(i) }
        else if (a === "show") { sharedOpen = false; showInChat(it.id) }
        else if (a === "copy") { Chat.copyText(it.url); Ui.say("Link copied.") }
        else if (a === "forward") { if (sharedTab === "links") PeopleActions.forwardMenu(it.url); else PeopleActions.forwardMedia(it, kindOf(it)) }
        else if (a === "save") {
            const path = Chat.saveMedia(it.photo)
            Ui.say(path !== "" ? "Saved in " + saveWhere(it) + "." : "Couldn't save it.", path === "")
        } else if (a === "delete") {
            const w = where, kind = kindOf(it)
            Ui.sure("Delete this " + kind + "?", "It's deleted on this computer only.", "Delete", () => {
                if (Voice.playing !== "" && Voice.playing === it.photo) Voice.stop()
                if (w.indexOf("g:") === 0) Chat.deleteGroupMessage(w.split(":")[1], it.id)
                else Chat.deleteMessage(w, it.id)
                Ui.say("Deleted.")
            }, true, true)
        }
    }

    function handleButton(b) {
        if (photos.length > 0) {
            viewer.handleButton(b)
            return true
        }
        if (videoId !== "") {
            if (b === "a" || b === "x") Videos.togglePause()
            else if (b === "b") closeVideo()
            return true
        }
        if (camera) {
            if (shot !== "") {
                if (b === "a" || b === "start") sendRecorded()
                else if (b === "x") retake()
                else if (b === "b") closeCamera()
            } else if (Videos.reviewing) {
                if (b === "a" || b === "start") sendRecorded()
                else if (b === "x") Videos.play("review", reviewOut)
                else if (b === "b") closeCamera()
            } else if (b === "a") shutterPress()
            else if (b === "b") closeCamera()
            return true
        }
        if (pollDlg.visible) return pollDlg.handleButton(b)
        if (savedMedia.shown) return savedMedia.handleButton(b)
        if (sharedOpen) {
            const n = sharedCount()
            const cols = sharedTab === "links" ? 1 : tileGrid.columns
            switch (b) {
            case "left": if (cols > 1 && sharedSel > 0) sharedSel--; break
            case "right": if (cols > 1 && sharedSel < n - 1) sharedSel++; break
            case "up": sharedSel = Math.max(0, sharedSel - cols); break
            case "down": sharedSel = n ? Math.min(n - 1, sharedSel < 0 ? 0 : sharedSel + cols) : -1; if (sharedSel >= sharedShown - cols) sharedShown += pageSize; break
            case "y": sharedTab = sharedTabs[(sharedTabs.indexOf(sharedTab) + 1) % sharedTabs.length]; sharedSel = -1; sharedShown = pageSize; break
            case "x": case "start": if (sharedSel < 0 && n) sharedSel = 0; if (sharedSel >= 0) itemMenuAt(sharedSel); break
            case "a": if (sharedSel >= 0) openSharedItem(sharedSel); break
            case "b": sharedOpen = false; break
            }
            return true
        }
        return false
    }
    // A button let go (the page passes it on while busy): A held on the camera.
    function handleRelease(b) {
        if (camera && b === "a") { shutterRelease(); return true }
        return busy
    }

    // A recorded video plays as soon as it's ready, to be watched before Send or Discard.
    property bool reviewingNow: Videos.reviewing
    onReviewingNowChanged: if (reviewingNow && camera) Qt.callLater(() => Videos.play("review", reviewOut))

    // ------------------------------------------------------------- the Shared page
    Rectangle {
        anchors.fill: parent
        visible: more.sharedOpen
        color: Theme.ground
        MouseArea { anchors.fill: parent }
        PageHeader {
            id: sharedHead
            width: parent.width
            carved: false
            title: more.title !== "" ? "Shared with " + more.title : "Shared"
            subtitle: more.sharedData.media.length + " photos and videos · " + more.sharedData.links.length + " links · " + more.sharedVoices.length + " voice messages"
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; fontSize: 14; onClicked: more.sharedOpen = false }
        }
        Segmented {
            id: sharedSeg
            anchors.top: sharedHead.bottom; anchors.topMargin: Theme.px(10)
            x: Theme.px(20)
            width: Math.min(implicitWidth, parent.width - Theme.px(40))
            options: [{ value: "media", label: "Photos and videos" }, { value: "links", label: "Links" }, { value: "voice", label: "Voice" }]
            value: more.sharedTab
            onPicked: v => { more.sharedTab = v; more.sharedSel = -1; more.sharedShown = more.pageSize }
        }
        // Photos and videos, or voice messages: square tiles, as many across as fit.
        GridView {
            id: tileGrid
            readonly property int columns: Math.max(1, Math.floor(width / Theme.px(150)))
            visible: more.sharedTab !== "links"
            anchors.top: sharedSeg.bottom; anchors.topMargin: Theme.px(12)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20)
            clip: true
            cellWidth: Math.floor(width / columns); cellHeight: cellWidth
            model: more.sharedTab === "voice" ? more.sharedVoices.slice(0, more.sharedShown)
                 : more.sharedTab === "media" ? more.sharedData.media.slice(0, more.sharedShown) : []
            currentIndex: more.sharedSel
            onAtYEndChanged: if (atYEnd && more.sharedShown < more.sharedCount()) more.sharedShown += more.pageSize
            delegate: Rectangle {
                id: tile
                required property var modelData
                required property int index
                readonly property bool voice: more.sharedTab === "voice"
                readonly property bool here: modelData.here !== false
                readonly property bool video: !voice && modelData.video > 0
                readonly property bool photo: !voice && !video && here
                readonly property bool playingThis: voice && Voice.playing !== "" && Voice.playing === modelData.photo
                width: tileGrid.cellWidth - Theme.px(6); height: width
                radius: Theme.radius; color: tileHover.hovered ? Theme.raised : Theme.groundDeep; clip: true
                border.color: Theme.accent; border.width: index === more.sharedSel ? Theme.focusWidth : 0
                Image {
                    anchors.fill: parent
                    visible: tile.photo && !more.tour
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    // A small cached copy, loaded only for the tiles in view.
                    source: visible ? "image://thumb/" + tile.modelData.photo : ""
                }
                // A video, a voice message, one not on this device (or a tour photo): what it is, in the middle.
                Column {
                    visible: !tile.photo || more.tour
                    anchors.centerIn: parent
                    width: parent.width - Theme.px(12)
                    spacing: Theme.px(4)
                    Icon {
                        anchors.horizontalCenter: parent.horizontalCenter
                        name: !tile.here ? "down" : tile.playingThis ? "stop" : tile.voice ? "mic" : tile.video ? "play" : "image"
                        color: tile.here ? Theme.text : Theme.textMuted; size: Theme.px(30)
                    }
                    Text {
                        textFormat: Text.PlainText
                        width: parent.width; horizontalAlignment: Text.AlignHCenter; elide: Text.ElideRight
                        text: !tile.here ? "Not on this device" : tile.voice ? Voice.length(tile.modelData.voice || 0)
                            : tile.video ? Voice.length(tile.modelData.video || 0) : "Photo"
                        color: tile.here ? Theme.text : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                    Text {
                        textFormat: Text.PlainText
                        visible: tile.voice || tile.video || !tile.here
                        width: parent.width; horizontalAlignment: Text.AlignHCenter; elide: Text.ElideRight
                        text: tile.modelData.name || ""
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
                    }
                }
                // A voice message playing: how far it is.
                Rectangle {
                    visible: tile.playingThis
                    anchors.left: parent.left; anchors.bottom: parent.bottom
                    width: parent.width * Voice.progress; height: Theme.px(3); color: Theme.accent
                }
                // Cap a1: a GIF holds its first frame here; it moves once opened.
                Rectangle {
                    visible: !!tile.modelData.gif
                    anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.margins: Theme.px(6)
                    width: mediaGifTag.implicitWidth + Theme.px(8); height: mediaGifTag.implicitHeight + Theme.px(2); radius: Theme.px(4)
                    color: Qt.rgba(0, 0, 0, 0.6)
                    Text { id: mediaGifTag; textFormat: Text.PlainText; anchors.centerIn: parent; text: "GIF"; color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(11) }
                }
                Accessible.role: Accessible.Button
                Accessible.name: (tile.voice ? "Voice message from " : tile.video ? "Video from " : tile.modelData.gif ? "GIF from " : "Photo from ")
                                 + (tile.modelData.name || "someone") + (tile.here ? "" : ", not on this device")
                Accessible.onPressAction: { more.sharedSel = tile.index; more.openSharedItem(tile.index) }
                HoverHandler { id: tileHover; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => {
                        more.sharedSel = tile.index
                        if (button === Qt.RightButton) Ui.menuAt(tile, point.position.x, point.position.y, () => more.itemMenu(tile.index))
                        else more.openSharedItem(tile.index)
                    }
                    onLongPressed: { more.sharedSel = tile.index; Ui.menuAt(tile, point.position.x, point.position.y, () => more.itemMenu(tile.index)) }
                }
            }
        }
        ListView {
            id: linkList
            visible: more.sharedTab === "links"
            anchors.top: sharedSeg.bottom; anchors.topMargin: Theme.px(12)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20)
            clip: true
            spacing: Theme.px(8)
            model: more.sharedData.links.slice(0, more.sharedShown)
            currentIndex: more.sharedSel
            onAtYEndChanged: if (atYEnd && more.sharedShown < more.sharedData.links.length) more.sharedShown += more.pageSize
            delegate: Rectangle {
                id: linkRow
                required property var modelData
                required property int index
                width: linkList.width
                height: Math.max(Theme.px(64), linkCol.implicitHeight) + Theme.px(16)
                radius: Theme.radius; color: Theme.panel
                border.color: linkRow.index === more.sharedSel ? Theme.accent : Theme.line; border.width: linkRow.index === more.sharedSel ? Theme.focusWidth : 1
                Image {
                    id: linkThumb
                    visible: linkRow.modelData.thumb !== ""
                    x: Theme.px(8); y: Theme.px(8); width: Theme.px(64); height: width
                    fillMode: Image.PreserveAspectCrop
                    source: linkRow.modelData.thumb
                }
                Column {
                    id: linkCol
                    x: linkThumb.visible ? Theme.px(84) : Theme.px(12); y: Theme.px(8)
                    width: parent.width - x - Theme.px(12)
                    spacing: Theme.px(2)
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: linkRow.modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                    Text { visible: text !== ""; width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: linkRow.modelData.description; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: linkRow.modelData.host + " · " + linkRow.modelData.name; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => {
                        more.sharedSel = linkRow.index
                        if (button === Qt.RightButton) Ui.menuAt(linkRow, point.position.x, point.position.y, () => more.itemMenu(linkRow.index))
                        else more.openSharedItem(linkRow.index)
                    }
                    onLongPressed: { more.sharedSel = linkRow.index; Ui.menuAt(linkRow, point.position.x, point.position.y, () => more.itemMenu(linkRow.index)) }
                }
            }
        }
        Text {
            textFormat: Text.PlainText
            anchors.centerIn: parent
            visible: more.sharedCount() === 0
            text: more.sharedTab === "media" ? "No photos or videos yet." : more.sharedTab === "links" ? "No links yet." : "No voice messages yet."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
        }
    }

    // ------------------------------------------------------------- an album full size
    PhotoViewer {
        id: viewer
        anchors.fill: parent
        tour: more.tour
        photos: more.photos
        at: more.photoAt
        onAtChanged: more.photoAt = at
        onClosed: more.photos = []
    }

    // ------------------------------------------------------------- a video message playing
    Rectangle {
        anchors.fill: parent
        visible: more.videoId !== ""
        color: "black"
        MouseArea { anchors.fill: parent; onClicked: Videos.togglePause() }
        VideoOutput {
            id: playOut
            anchors.fill: parent; anchors.margins: Theme.px(10); anchors.bottomMargin: Theme.px(80)
            fillMode: VideoOutput.PreserveAspectFit
        }
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(18)
            spacing: Theme.px(14)
            HButton { text: "Back"; onClicked: more.closeVideo() }
            HButton { text: Videos.paused ? "Play" : "Pause"; kind: "accent"; onClicked: Videos.togglePause() }
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(240); height: Theme.px(4); color: Qt.rgba(1, 1, 1, 0.25)
                Rectangle { width: parent.width * Videos.progress; height: parent.height; color: Theme.accent }
            }
        }
    }

    // ------------------------------------------------------------- the camera: a photo, or a video message
    Rectangle {
        anchors.fill: parent
        visible: more.camera
        color: "black"
        MouseArea { anchors.fill: parent }
        VideoOutput {
            id: camOut
            visible: !Videos.reviewing && more.shot === ""
            anchors.fill: parent; anchors.margins: Theme.px(10); anchors.bottomMargin: Theme.px(110)
            fillMode: VideoOutput.PreserveAspectFit
        }
        VideoOutput {
            id: reviewOut
            visible: Videos.reviewing && more.shot === ""
            anchors.fill: camOut
            fillMode: VideoOutput.PreserveAspectFit
        }
        // A photo just taken, waiting for Send (read from its scratch file, never cached).
        Image {
            anchors.fill: camOut
            visible: more.shot !== ""
            fillMode: Image.PreserveAspectFit
            cache: false
            asynchronous: true
            source: more.shot === "" ? "" : (more.shot.charAt(0) === "/" ? "file://" : "file:///") + more.shot
        }
        Text {
            textFormat: Text.PlainText
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top; anchors.topMargin: Theme.px(16)
            width: Math.min(implicitWidth, parent.width - Theme.px(80)); elide: Text.ElideRight
            text: more.shot !== "" ? "Your photo for " + more.title + " · it's only sent if you choose Send"
                : Videos.reviewing ? "Your video message · " + Voice.length(Videos.reviewMs) + " · it's only sent if you choose Send"
                : Videos.recording ? "Recording " + Voice.length(Videos.elapsed) + " of 1:00"
                : "Camera for " + more.title
            color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            style: Text.Outline; styleColor: "black"
        }
        Rectangle {
            visible: Videos.recording
            anchors.left: parent.left; anchors.top: parent.top; anchors.margins: Theme.px(20)
            width: Theme.px(14); height: width; radius: width / 2; color: Theme.danger
        }
        // Taking: Cancel, the shutter (click for a photo, hold for video) and its hint.
        Row {
            visible: !Videos.reviewing && more.shot === ""
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            spacing: Theme.px(18)
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Cancel"; onClicked: more.closeCamera() }
            Rectangle {
                id: shutter
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(68); height: width; radius: width / 2
                color: "transparent"
                border.width: Theme.px(4); border.color: "white"
                Rectangle {
                    anchors.centerIn: parent
                    width: Videos.recording ? parent.width * 0.42 : parent.width - Theme.px(14); height: width
                    radius: Videos.recording ? Theme.px(6) : width / 2
                    color: Videos.recording ? Theme.danger : shutterArea.pressed ? Theme.textSoft : "white"
                }
                Accessible.role: Accessible.Button
                Accessible.name: Videos.recording ? "Stop recording" : "Shutter: click for a photo, hold for video"
                Accessible.onPressAction: { if (Videos.recording) Videos.stopRecording(); else more.takePhoto() }
                MouseArea {
                    id: shutterArea
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onPressed: more.shutterPress()
                    onReleased: more.shutterRelease()
                    onCanceled: more.shutterCancel()
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                textFormat: Text.PlainText
                text: Videos.recording ? "Let go to stop"
                    : !!Ui.main && Ui.main.padInUse ? "A for a photo, hold A for video" : "Click for a photo, hold for video"
                color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
        }
        // Waiting: Discard, Retake (a photo) or Watch again (a video message), Send.
        Row {
            visible: Videos.reviewing || more.shot !== ""
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(18)
            spacing: Theme.px(14)
            HButton { text: "Discard"; kind: "danger"; onClicked: more.closeCamera() }
            HButton { visible: more.shot !== ""; text: "Retake"; onClicked: more.retake() }
            HButton { visible: more.shot === ""; text: Videos.playing === "review" && !Videos.paused ? "Pause" : "Watch again"; onClicked: Videos.playing === "review" && !Videos.paused ? Videos.togglePause() : Videos.play("review", reviewOut) }
            HButton { text: "Send"; kind: "accent"; onClicked: more.sendRecorded() }
        }
        Text {
            textFormat: Text.PlainText
            anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: Theme.px(22)
            visible: !!Ui.main && Ui.main.padInUse
            text: more.shot !== "" ? "A: Send · X: retake · B: Discard"
                : Videos.reviewing ? "A: Send · X: watch again · B: Discard"
                : Videos.recording ? "Let go of A to stop" : "B: Cancel"
            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }

    // ------------------------------------------------------------- Saved media
    SavedMedia {
        id: savedMedia
        z: 1
        tour: more.tour
        onSend: (photos, videos, hd) => {
            if (photos.length) more.sendPictures(photos, hd)
            if (videos.length) more.sendVideos(videos)
        }
        onBrowse: { savedMedia.close(); more.browseMedia() }
    }

    PollDialog {
        id: pollDlg
        onSend: (q, answers, multi) => {
            const r = Chat.sendPoll(more.where, q, answers, multi)
            Ui.say(r !== "" ? r : "Poll sent.", r !== "")
        }
    }

    // A let go of on the camera, straight from the controller (the page's handleRelease passes keys on too).
    Connections {
        target: Gamepad
        enabled: more.camera
        function onReleased(button) { if (button === "a") more.shutterRelease() }
        function onLost(button) { if (button === "a") more.shutterCancel() }
    }
    Connections {
        target: Videos
        function onChanged() { if (Videos.error !== "" && (more.camera || more.videoId !== "")) { Ui.say(Videos.error, true) } }
    }
    // A voice message from the Shared page stops when it closes.
    onSharedOpenChanged: if (!sharedOpen && Voice.playing !== "") Voice.stop()
    Component.onDestruction: { Videos.stop(); if (camera) Videos.closeCamera(); if (shot !== "") MediaFiles.shred(shot) }
}
