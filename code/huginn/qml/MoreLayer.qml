import QtQuick
import QtMultimedia
import Huginn

// Cap m2 over a chat or a group channel (shared/docs/protocol.md section 12), the same as Muninn's ChatMore.kt and
// CameraScreen.kt: the attach menu (pictures, several at once as an album; a video message; a video file; a
// poll), an album full size with arrows, a video message playing, the camera recording one (it waits for
// Send or Discard, never sent by itself), a new poll, and the Shared page (photos, videos, links and voice).
// where: the contact's key or "g:<group>:<channel>"; title: who or what it goes to.
Item {
    id: layer
    property string where: ""
    property string title: ""
    property bool tour: false
    property var photos: []          // an album open full size
    property int photoAt: 0
    property string videoId: ""      // a video message playing (its stored photo id)
    property bool camera: false      // recording a video message
    property bool sharedOpen: false
    property string sharedTab: "media"
    property int sharedSel: -1
    // The Shared page loads a page at a time: more as you scroll near the end.
    readonly property int pageSize: 60
    property int sharedShown: pageSize
    readonly property var sharedData: sharedOpen ? (Chat.chats, Chat.groups, Chat.shared(where)) : ({ media: [], links: [], voices: [] })
    readonly property var sharedVoices: sharedData.voices || []
    // The tabs in order, for X on a controller.
    readonly property var sharedTabs: ["media", "links", "voice"]
    function sharedCount() { return sharedTab === "media" ? sharedData.media.length : sharedTab === "links" ? sharedData.links.length : sharedVoices.length }
    readonly property bool busy: photos.length > 0 || videoId !== "" || camera || pollDlg.visible || sharedOpen
    anchors.fill: parent
    z: 40

    // ------------------------------------------------------------- what the page calls
    function attach() {
        if (tour) return
        if (where === "") { Ui.say("Open a chat first.", true); return }
        Ui.pickFrom("Send to " + title, [{ header: "", items: [
            { name: "Pictures from this device", detail: "Up to 10 as an album", url: "files", icon: "image" },
            { name: "Recent pictures", url: "recent", icon: "image" },
            { name: "Video message", detail: "Up to a minute", url: "record", icon: "play" },
            { name: "Video file", detail: "MP4, up to a minute", url: "videofile", icon: "play" },
            { name: "Poll", url: "poll", icon: "check" }]
            .concat(where.indexOf("g:") === 0 || !Chat.isContact(where) ? [] : [{ name: "A file", detail: "Any file", url: "file", icon: "file" }]) }], a => {
                if (a === "files") pickPictures()
                else if (a === "recent") recentPicture()
                else if (a === "record") recordVideo()
                else if (a === "videofile") sendVideoFile()
                else if (a === "poll") newPoll()
                else if (a === "file") PeopleActions.sendFile(where)
            })
    }
    function pickPictures() {
        const paths = Chat.pickPictures()
        if (!paths.length) return
        sendPictures(paths, false)
    }
    function sendPictures(paths, hd) {
        const r = Chat.sendPhotos(where, paths, hd)
        if (r !== "") Ui.say(r, true)
        else Ui.say(paths.length === 1 ? "Sending the picture." : "Sending " + paths.length + " pictures" + (paths.length > 1 ? " as an album." : "."))
    }
    // The newest pictures load off screen; the list opens when they're in.
    property bool wantPictures: false
    function recentPicture() {
        wantPictures = true
        Chat.loadRecentPictures()
    }
    Connections {
        target: Chat
        function onRecentPicturesReady(pics) {
            if (!layer.wantPictures) return
            layer.wantPictures = false
            if (!pics.length) { Ui.say("No pictures in your Pictures folder.", true); return }
            Ui.pickFrom("Send a picture", [{ header: "Newest first", items: pics.map(p => Object.assign({ icon: "image" }, p)) }], path =>
                Ui.pickFrom("Send this picture", [{ header: "", items: [
                    { name: "Send", url: "normal" },
                    { name: "High quality", detail: "Best on the same Wi-Fi", url: "hd" }] }],
                    q => layer.sendPictures([path], q === "hd")))
        }
    }
    function sendVideoFile() {
        const path = Videos.pickFile()
        if (path === "") return
        const r = Videos.sendFile(where, path)
        Ui.say(r !== "" ? r : "Sending the video message.", r !== "" && r.indexOf("need") < 0)
    }
    // Dropped files: pictures go in the chat (several as an album), MP4 videos as video messages, anything
    // else to Files, ready to send over the Wi-Fi.
    function dropFiles(urls) {
        const pics = [], others = []
        let videos = 0
        for (let i = 0; i < urls.length; i++) {
            const path = Chat.localPath(urls[i])
            if (path === "") continue
            if (Chat.isPicture(path)) pics.push(path)
            else if (/\.(mp4|m4v|mov)$/i.test(path)) {
                const r = Videos.sendFile(where, path)
                if (r !== "") Ui.say(r, true); else videos++
            } else others.push(urls[i])
        }
        if (pics.length) sendPictures(pics, false)
        if (videos) Ui.say(videos === 1 ? "Sending the video message." : "Sending " + videos + " video messages.")
        // Cap f1: other files go straight to a contact over the internet when they can.
        if (others.length && where.indexOf("g:") !== 0 && PeopleActions.dropFiles(where, others)) return
        if (others.length) {
            Transfer.share(others)
            Ui.say((others.length === 1 ? "That file is" : "Those files are") + " ready in Files: pick a device on the same Wi-Fi to send.")
            Ui.openPage("files", {})
        }
    }
    function newPoll() { pollDlg.open("Poll for " + title) }
    function viewPhotos(list, i) { photos = list; photoAt = i }
    function playVideo(id) {
        if (tour) return
        videoId = id
        Videos.play(id, playOut)
    }
    function closeVideo() { Videos.stop(); videoId = "" }
    function recordVideo() {
        if (!Videos.hasCamera()) { Ui.say("No camera found on this device.", true); return }
        camera = true
        Qt.callLater(() => { if (!Videos.openCamera(camOut)) { Ui.say(Videos.error, true); camera = false } })
    }
    function closeCamera() {
        Videos.closeCamera()
        Videos.discard()
        camera = false
    }
    function sendRecorded() {
        const r = Videos.send(where)
        Ui.say(r !== "" ? r : "Sending the video message.", r !== "")
        if (!Videos.reviewing) camera = false
    }
    function askOpen(url) {
        if (url === "") return
        Ui.sure("Open " + Chat.linkHost(url) + "?", url, "Open", () => { if (!Chat.openLink(url)) Ui.say("That link can't be opened here.", true) })
    }
    function openShared() { if (!tour && where !== "") { sharedTab = "media"; sharedSel = -1; sharedShown = pageSize; sharedOpen = true } }
    function openSharedItem(i) {
        const media = sharedData.media, links = sharedData.links
        if (sharedTab === "voice") {
            const v = sharedVoices[i]
            if (!v) return
            if (Voice.playing === v.photo) Voice.stop(); else Voice.play(v.photo)
            return
        }
        if (sharedTab === "media") {
            const it = media[i]
            if (!it) return
            if (it.video > 0) { playVideo(it.photo); return }
            const pics = media.filter(x => !(x.video > 0))
            viewPhotos(pics.map(x => x.photo), Math.max(0, pics.findIndex(x => x.id === it.id)))
        } else if (links[i]) askOpen(links[i].url)
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
            if (Videos.reviewing) {
                if (b === "a" || b === "start") sendRecorded()
                else if (b === "x") Videos.play("review", reviewOut)
                else if (b === "b") closeCamera()
            } else if (b === "a") { if (Videos.recording) Videos.stopRecording(); else Videos.record() }
            else if (b === "b") closeCamera()
            return true
        }
        if (pollDlg.visible) return pollDlg.handleButton(b)
        if (sharedOpen) {
            const n = sharedCount()
            const cols = sharedTab === "media" ? mediaGrid.columns : 1
            switch (b) {
            case "left": if (sharedTab === "media" && sharedSel > 0) sharedSel--; break
            case "right": if (sharedTab === "media" && sharedSel < n - 1) sharedSel++; break
            case "up": sharedSel = Math.max(0, sharedSel - cols); break
            case "down": sharedSel = n ? Math.min(n - 1, sharedSel < 0 ? 0 : sharedSel + cols) : -1; if (sharedSel >= sharedShown - cols) sharedShown += pageSize; break
            case "x": sharedTab = sharedTabs[(sharedTabs.indexOf(sharedTab) + 1) % sharedTabs.length]; sharedSel = -1; sharedShown = pageSize; break
            case "a": if (sharedSel >= 0) openSharedItem(sharedSel); break
            case "b": sharedOpen = false; break
            }
            return true
        }
        return false
    }

    // A recorded video plays as soon as it's ready, to be watched before Send or Discard.
    property bool reviewingNow: Videos.reviewing
    onReviewingNowChanged: if (reviewingNow && camera) Qt.callLater(() => Videos.play("review", reviewOut))

    // ------------------------------------------------------------- the Shared page
    Rectangle {
        anchors.fill: parent
        visible: layer.sharedOpen
        color: Theme.ground
        MouseArea { anchors.fill: parent }
        PageHeader {
            id: sharedHead
            width: parent.width
            carved: false
            title: "Shared with " + layer.title
            subtitle: layer.sharedData.media.length + " photos and videos · " + layer.sharedData.links.length + " links · " + layer.sharedVoices.length + " voice messages"
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; fontSize: 14; onClicked: layer.sharedOpen = false }
        }
        Segmented {
            id: sharedTabs
            anchors.top: sharedHead.bottom; anchors.topMargin: Theme.px(10)
            x: Theme.px(20)
            options: [{ value: "media", label: "Photos and videos" }, { value: "links", label: "Links" }, { value: "voice", label: "Voice" }]
            value: layer.sharedTab
            onPicked: v => { layer.sharedTab = v; layer.sharedSel = -1; layer.sharedShown = layer.pageSize }
        }
        GridView {
            id: mediaGrid
            readonly property int columns: Math.max(1, Math.floor(width / cellWidth))
            visible: layer.sharedTab === "media"
            anchors.top: sharedTabs.bottom; anchors.topMargin: Theme.px(12)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20)
            clip: true
            cellWidth: Theme.px(150); cellHeight: Theme.px(150)
            model: layer.sharedData.media.slice(0, layer.sharedShown)
            currentIndex: layer.sharedSel
            onAtYEndChanged: if (atYEnd && layer.sharedShown < layer.sharedData.media.length) layer.sharedShown += layer.pageSize
            delegate: Rectangle {
                width: mediaGrid.cellWidth - Theme.px(6); height: width
                radius: Theme.radius; color: Theme.groundDeep; clip: true
                border.color: Theme.accent; border.width: index === layer.sharedSel ? Theme.focusWidth : 0
                Image {
                    anchors.fill: parent
                    visible: !(modelData.video > 0)
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    // A small cached copy, loaded only for the tiles in view.
                    source: visible && !layer.tour ? "image://thumb/" + modelData.photo : ""
                }
                Column {
                    visible: modelData.video > 0
                    anchors.centerIn: parent
                    spacing: Theme.px(4)
                    Icon { anchors.horizontalCenter: parent.horizontalCenter; name: "play"; color: "white"; size: Theme.px(30) }
                    Text { textFormat: Text.PlainText; text: Voice.length(modelData.video || 0); color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                // Cap a1: a GIF holds its first frame here; it moves once opened.
                Rectangle {
                    visible: !!modelData.gif
                    anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.margins: Theme.px(6)
                    width: mediaGifTag.implicitWidth + Theme.px(8); height: mediaGifTag.implicitHeight + Theme.px(2); radius: Theme.px(4)
                    color: Qt.rgba(0, 0, 0, 0.6)
                    Text { id: mediaGifTag; textFormat: Text.PlainText; anchors.centerIn: parent; text: "GIF"; color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(11) }
                }
                Accessible.role: Accessible.Button
                Accessible.name: (modelData.video > 0 ? "Video from " : modelData.gif ? "GIF from " : "Photo from ") + modelData.name
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: { layer.sharedSel = index; layer.openSharedItem(index) } }
            }
        }
        ListView {
            id: linkList
            visible: layer.sharedTab === "links"
            anchors.top: sharedTabs.bottom; anchors.topMargin: Theme.px(12)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20)
            clip: true
            spacing: Theme.px(8)
            model: layer.sharedData.links.slice(0, layer.sharedShown)
            currentIndex: layer.sharedSel
            onAtYEndChanged: if (atYEnd && layer.sharedShown < layer.sharedData.links.length) layer.sharedShown += layer.pageSize
            delegate: Rectangle {
                width: linkList.width
                height: Math.max(Theme.px(64), linkCol.implicitHeight) + Theme.px(16)
                radius: Theme.radius; color: Theme.panel
                border.color: index === layer.sharedSel ? Theme.accent : Theme.line; border.width: index === layer.sharedSel ? Theme.focusWidth : 1
                Image {
                    id: linkThumb
                    visible: modelData.thumb !== ""
                    x: Theme.px(8); y: Theme.px(8); width: Theme.px(64); height: width
                    fillMode: Image.PreserveAspectCrop
                    source: modelData.thumb
                }
                Column {
                    id: linkCol
                    x: linkThumb.visible ? Theme.px(84) : Theme.px(12); y: Theme.px(8)
                    width: parent.width - x - Theme.px(12)
                    spacing: Theme.px(2)
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                    Text { visible: text !== ""; width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: modelData.description; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: modelData.host + " · " + modelData.name; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: { layer.sharedSel = index; layer.openSharedItem(index) } }
            }
        }
        // Voice messages, newest first: a click (or A) plays one, again stops it.
        ListView {
            id: voiceList
            visible: layer.sharedTab === "voice"
            anchors.top: sharedTabs.bottom; anchors.topMargin: Theme.px(12)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20)
            clip: true
            spacing: Theme.px(8)
            model: layer.sharedTab === "voice" ? layer.sharedVoices.slice(0, layer.sharedShown) : []
            currentIndex: layer.sharedSel
            onAtYEndChanged: if (atYEnd && layer.sharedShown < layer.sharedVoices.length) layer.sharedShown += layer.pageSize
            delegate: Rectangle {
                id: voiceRow
                readonly property bool playingThis: Voice.playing !== "" && Voice.playing === modelData.photo
                width: voiceList.width
                height: Math.max(Theme.px(56), voiceCol.implicitHeight + Theme.px(16))
                radius: Theme.radius
                color: voiceHover.hovered ? Theme.raised : Theme.panel
                border.color: index === layer.sharedSel ? Theme.accent : Theme.line; border.width: index === layer.sharedSel ? Theme.focusWidth : 1
                Accessible.role: Accessible.Button
                Accessible.name: "Voice message from " + modelData.name + ", " + Voice.length(modelData.voice || 0)
                Accessible.onPressAction: { layer.sharedSel = index; layer.openSharedItem(index) }
                Icon {
                    id: voiceIcon
                    x: Theme.px(14); anchors.verticalCenter: parent.verticalCenter
                    name: voiceRow.playingThis ? "stop" : "play"; size: Theme.px(22); color: Theme.accent
                }
                Column {
                    id: voiceCol
                    anchors.left: voiceIcon.right; anchors.leftMargin: Theme.px(14)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(4)
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: modelData.name + " · " + Voice.length(modelData.voice || 0); color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold }
                    Text { width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText; text: Qt.formatDateTime(new Date(modelData.time || 0), "MMM d, h:mm AP"); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    Rectangle {
                        width: parent.width; height: Theme.px(3); radius: Theme.radiusSmall; color: Theme.lineMuted
                        Rectangle { width: parent.width * (voiceRow.playingThis ? Voice.progress : 0); height: parent.height; radius: parent.radius; color: Theme.accent }
                    }
                }
                HoverHandler { id: voiceHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: { layer.sharedSel = index; layer.openSharedItem(index) } }
            }
        }
        Text {
            textFormat: Text.PlainText
            anchors.centerIn: parent
            visible: layer.sharedCount() === 0
            text: layer.sharedTab === "media" ? "No photos or videos yet." : layer.sharedTab === "links" ? "No links yet." : "No voice messages yet."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
        }
    }

    // ------------------------------------------------------------- an album full size
    PhotoViewer {
        id: viewer
        anchors.fill: parent
        tour: layer.tour
        photos: layer.photos
        at: layer.photoAt
        onAtChanged: layer.photoAt = at
        onClosed: layer.photos = []
    }

    // ------------------------------------------------------------- a video message playing
    Rectangle {
        anchors.fill: parent
        visible: layer.videoId !== ""
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
            HButton { text: "Back"; onClicked: layer.closeVideo() }
            HButton { text: Videos.paused ? "Play" : "Pause"; kind: "accent"; onClicked: Videos.togglePause() }
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(240); height: Theme.px(4); color: Qt.rgba(1, 1, 1, 0.25)
                Rectangle { width: parent.width * Videos.progress; height: parent.height; color: Theme.accent }
            }
        }
    }

    // ------------------------------------------------------------- recording a video message
    Rectangle {
        anchors.fill: parent
        visible: layer.camera
        color: "black"
        MouseArea { anchors.fill: parent }
        VideoOutput {
            id: camOut
            visible: !Videos.reviewing
            anchors.fill: parent; anchors.margins: Theme.px(10); anchors.bottomMargin: Theme.px(90)
            fillMode: VideoOutput.PreserveAspectFit
        }
        VideoOutput {
            id: reviewOut
            visible: Videos.reviewing
            anchors.fill: camOut
            fillMode: VideoOutput.PreserveAspectFit
        }
        Text {
            textFormat: Text.PlainText
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top; anchors.topMargin: Theme.px(16)
            text: Videos.reviewing ? "Your video message · " + Voice.length(Videos.reviewMs) + " · it's only sent if you choose Send"
                : Videos.recording ? "Recording " + Voice.length(Videos.elapsed) + " of 1:00"
                : "Video message for " + layer.title + " · up to a minute"
            color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            style: Text.Outline; styleColor: "black"
        }
        Rectangle {
            visible: Videos.recording
            anchors.left: parent.left; anchors.top: parent.top; anchors.margins: Theme.px(20)
            width: Theme.px(14); height: width; radius: width / 2; color: Theme.danger
        }
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(18)
            spacing: Theme.px(14)
            HButton { visible: !Videos.reviewing; text: "Cancel"; onClicked: layer.closeCamera() }
            HButton { visible: !Videos.reviewing; text: Videos.recording ? "Stop" : "Record"; kind: Videos.recording ? "danger" : "accent"; onClicked: Videos.recording ? Videos.stopRecording() : Videos.record() }
            HButton { visible: Videos.reviewing; text: "Discard"; kind: "danger"; onClicked: layer.closeCamera() }
            HButton { visible: Videos.reviewing; text: Videos.playing === "review" && !Videos.paused ? "Pause" : "Watch again"; onClicked: Videos.playing === "review" && !Videos.paused ? Videos.togglePause() : Videos.play("review", reviewOut) }
            HButton { visible: Videos.reviewing; text: "Send"; kind: "accent"; onClicked: layer.sendRecorded() }
        }
        Text {
            textFormat: Text.PlainText
            anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: Theme.px(22)
            visible: !!Ui.main && Ui.main.padInUse
            text: Videos.reviewing ? "A: Send · X: watch again · B: Discard" : Videos.recording ? "A: Stop" : "A: Record · B: Cancel"
            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }

    PollDialog {
        id: pollDlg
        onSend: (q, answers, multi) => {
            const r = Chat.sendPoll(layer.where, q, answers, multi)
            Ui.say(r !== "" ? r : "Poll sent.", r !== "")
        }
    }

    Connections {
        target: Videos
        function onChanged() { if (Videos.error !== "" && (layer.camera || layer.videoId !== "")) { Ui.say(Videos.error, true) } }
    }
    // A voice message from the Shared page stops when it closes.
    onSharedOpenChanged: if (!sharedOpen && Voice.playing !== "") Voice.stop()
    Component.onDestruction: { Videos.stop(); if (camera) Videos.closeCamera() }
}
