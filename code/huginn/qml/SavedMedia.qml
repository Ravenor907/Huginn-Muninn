import QtQuick
import Huginn

// Saved media (the Send menu's second row): the photos, videos and GIFs saved on this computer in one picker, newest
// first (MediaFiles: the Pictures and Videos folders, and Steam screenshots where there are some). Up to 10 together,
// numbered in the order they were picked. All, Photos, Videos and GIFs filter the grid; a video shows its length and
// a GIF a badge. The bar at the bottom counts them ("3 selected: 2 photos, 1 video") beside Send. Other folders opens
// the system's file window (or Huginn's own list on a controller) for anything elsewhere.
// Controller: the D-pad moves, A picks or unpicks, X sends, Y the next filter, Menu other folders, B closes.
// It lives in MoreLayer.qml, which sends what comes back (send): photos as an album, each video by the video rules.
Rectangle {
    id: sm
    property bool tour: false
    property bool shown: false
    property string filter: "all"
    property var items: []           // [{path, url, name, kind, time, size, ms}], newest first
    property var picked: []          // [{path, kind}], in the order they were picked
    property int sel: -1
    property bool hd: false          // High quality photos (best on the same Wi-Fi)
    readonly property int most: 10
    readonly property var filterOrder: ["all", "photo", "video", "gif"]
    readonly property var shownItems: filter === "all" ? items : items.filter(x => !!x && x.kind === filter)
    readonly property int photoCount: picked.filter(p => p.kind !== "video").length
    readonly property int videoCount: picked.filter(p => p.kind === "video").length
    readonly property bool videosOff: !tour && Chat.videoSendingOff()
    // The tour's grid: one of each kind (nothing here is real, and nothing is read from disk).
    readonly property var tourItems: [{ path: "t1", url: "", name: "Photo", kind: "photo", ms: 0 },
                                      { path: "t2", url: "", name: "Video", kind: "video", ms: 134000 },
                                      { path: "t3", url: "", name: "GIF", kind: "gif", ms: 0 },
                                      { path: "t4", url: "", name: "Video", kind: "video", ms: 18000 }]
    // Photos (GIFs among them) and videos, as paths, and High quality: MoreLayer sends them.
    signal send(var photos, var videos, bool hd)
    // Other folders: MoreLayer opens the file picker.
    signal browse()
    readonly property var prompts: [{ key: "dpad", label: "Choose" }, { key: "a", label: "Pick" }, { key: "x", label: "Send" },
                                    { key: "y", label: "Filter" }, { key: "b", label: "Close" }]
    anchors.fill: parent
    visible: shown
    color: Theme.ground

    function openPicker() {
        filter = "all"; picked = []; sel = -1; hd = false
        items = tour ? tourItems : []
        shown = true
        if (!tour) MediaFiles.load()
    }
    function close() { shown = false; picked = []; items = [] }
    function orderOf(path) {
        if (!path) return 0
        for (let i = 0; i < picked.length; i++) if (picked[i].path === path) return i + 1
        return 0
    }
    function toggle(i) {
        const it = shownItems[i]
        if (!it || !it.path) return
        sel = i
        const at = orderOf(it.path) - 1
        if (at >= 0) { const p = picked.slice(); p.splice(at, 1); picked = p; return }
        if (it.kind === "video" && videosOff) { Ui.say("Off while Ultra privacy is on.", true); return }
        if (picked.length >= most) { Ui.say("Up to 10 at once.", true); return }
        picked = picked.concat([{ path: it.path, kind: it.kind }])
    }
    function sendNow() {
        if (!picked.length) { Ui.say("Pick a photo, video or GIF first.", true); return }
        if (tour) { close(); return }
        const photos = picked.filter(p => p.kind !== "video").map(p => p.path)
        const videos = picked.filter(p => p.kind === "video").map(p => p.path)
        const best = hd
        close()
        send(photos, videos, best)
    }
    function nextFilter() { setFilter(filterOrder[(filterOrder.indexOf(filter) + 1) % filterOrder.length]) }
    function setFilter(f) { filter = f; sel = -1; grid.positionViewAtBeginning() }
    function countText() {
        const parts = []
        if (photoCount) parts.push(photoCount + (photoCount === 1 ? " photo" : " photos"))
        if (videoCount) parts.push(videoCount + (videoCount === 1 ? " video" : " videos"))
        return parts.join(", ")
    }

    function handleButton(b) {
        const n = shownItems.length, cols = grid.columns
        switch (b) {
        case "left": if (sel > 0) sel--; else if (sel < 0 && n) sel = 0; break
        case "right": if (sel < n - 1) sel++; break
        case "up": if (sel < 0 && n) sel = 0; else sel = Math.max(0, sel - cols); break
        case "down": if (n) sel = sel < 0 ? 0 : Math.min(n - 1, sel + cols); break
        case "a": if (sel < 0 && n) sel = 0; else if (sel >= 0) toggle(sel); break
        case "x": sendNow(); break
        case "y": nextFilter(); break
        case "start": if (!tour) browse(); break
        case "b": close(); break
        }
        if (sel >= 0) grid.positionViewAtIndex(sel, GridView.Contain)
        return true
    }

    Connections {
        target: MediaFiles
        function onReady(list) {
            if (!sm.shown || sm.tour) return
            let all = (list || []).slice()
            // Steam screenshots, where the system has them (the handheld): with the rest, newest first.
            const places = System.filePlaces() || []
            if (places.some(p => !!p && p.path === "steam:screenshots")) {
                const shots = (System.listFolder("steam:screenshots") || []).filter(e => !!e && !e.dir && MediaFiles.kindOf(e.name) !== "")
                const known = {}
                all.forEach(x => known[x.path] = true)
                shots.forEach(e => { if (!known[e.path]) all.push({ path: e.path, url: (String(e.path).charAt(0) === "/" ? "file://" : "file:///") + e.path, name: e.name, kind: MediaFiles.kindOf(e.name),
                                                                  time: e.time || 0, size: e.size || 0, ms: 0 }) })
                all.sort((a, b) => (b.time || 0) - (a.time || 0))
            }
            sm.items = all
        }
    }

    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
    PageHeader {
        id: head
        width: parent.width
        carved: false
        title: "Saved media"
        subtitle: MediaFiles.loading && !sm.tour ? "Looking in Pictures and Videos" : "Photos, videos and GIFs, up to 10"
        HButton { anchors.verticalCenter: parent.verticalCenter; text: "Other folders"; fontSize: 14; onClicked: if (!sm.tour) sm.browse() }
        HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; fontSize: 14; onClicked: sm.close() }
    }
    Segmented {
        id: seg
        anchors.top: head.bottom; anchors.topMargin: Theme.px(10)
        x: Theme.px(20)
        width: Math.min(implicitWidth, parent.width - Theme.px(40))
        options: [{ value: "all", label: "All" }, { value: "photo", label: "Photos" }, { value: "video", label: "Videos" }, { value: "gif", label: "GIFs" }]
        value: sm.filter
        onPicked: v => sm.setFilter(v)
    }
    GridView {
        id: grid
        readonly property int columns: Math.max(1, Math.floor(width / Theme.px(130)))
        anchors.top: seg.bottom; anchors.topMargin: Theme.px(12)
        anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: bar.top
        anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20); anchors.bottomMargin: Theme.px(8)
        clip: true
        cellWidth: Math.floor(width / columns); cellHeight: cellWidth
        model: sm.shownItems
        currentIndex: sm.sel
        delegate: Rectangle {
            id: tile
            required property var modelData
            required property int index
            readonly property string kind: modelData && modelData.kind ? modelData.kind : ""
            readonly property string path: modelData && modelData.path ? modelData.path : ""
            readonly property int order: sm.orderOf(path)
            readonly property bool still: kind === "photo" || kind === "gif"
            readonly property bool off: kind === "video" && sm.videosOff
            width: grid.cellWidth - Theme.px(6); height: width
            radius: Theme.radius; color: tileHover.hovered ? Theme.raised : Theme.groundDeep; clip: true
            opacity: off ? 0.45 : 1
            border.color: Theme.accent; border.width: index === sm.sel ? Theme.focusWidth : 0
            Image {
                anchors.fill: parent
                visible: tile.still && !sm.tour
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                autoTransform: true
                // Small copies, loaded only for the tiles in view; a GIF holds its first frame.
                sourceSize.width: Theme.px(220); sourceSize.height: Theme.px(220)
                source: visible && tile.modelData && tile.modelData.url ? tile.modelData.url : ""
            }
            // A video (or a tour tile): what it is, in the middle.
            Icon {
                visible: !tile.still || sm.tour
                anchors.centerIn: parent
                name: tile.kind === "video" ? "play" : "image"
                color: Theme.text; size: Theme.px(30)
            }
            // The pick order, or an empty ring to pick it.
            Rectangle {
                anchors.right: parent.right; anchors.top: parent.top; anchors.margins: Theme.px(6)
                width: Theme.px(24); height: width; radius: width / 2
                color: tile.order > 0 ? Theme.accent : Qt.rgba(0, 0, 0, 0.35)
                border.width: tile.order > 0 ? 0 : Theme.px(2); border.color: "white"
                Text {
                    textFormat: Text.PlainText
                    anchors.centerIn: parent
                    visible: tile.order > 0
                    text: tile.order > 0 ? String(tile.order) : ""
                    color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.Bold
                }
            }
            // A video's length.
            Rectangle {
                visible: tile.kind === "video" && videoLen.text !== ""
                anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: Theme.px(6)
                width: videoLen.implicitWidth + Theme.px(8); height: videoLen.implicitHeight + Theme.px(2); radius: Theme.px(4)
                color: Qt.rgba(0, 0, 0, 0.6)
                Text {
                    id: videoLen
                    textFormat: Text.PlainText; anchors.centerIn: parent
                    text: tile.kind === "video" && tile.modelData ? MediaFiles.lengthText(tile.modelData.ms || 0) : ""
                    color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(11)
                }
            }
            Rectangle {
                visible: tile.kind === "gif"
                anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.margins: Theme.px(6)
                width: gifTag.implicitWidth + Theme.px(8); height: gifTag.implicitHeight + Theme.px(2); radius: Theme.px(4)
                color: Qt.rgba(0, 0, 0, 0.6)
                Text { id: gifTag; textFormat: Text.PlainText; anchors.centerIn: parent; text: "GIF"; color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(11) }
            }
            Accessible.role: Accessible.CheckBox
            Accessible.checked: tile.order > 0
            Accessible.name: (tile.kind === "video" ? "Video" : tile.kind === "gif" ? "GIF" : "Photo")
                             + (tile.modelData && tile.modelData.name ? " " + tile.modelData.name : "")
                             + (tile.kind === "video" && videoLen.text !== "" ? ", " + videoLen.text : "")
                             + (tile.order > 0 ? ", picked " + tile.order : "")
            Accessible.onPressAction: sm.toggle(tile.index)
            HoverHandler { id: tileHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: sm.toggle(tile.index) }
        }
    }
    Text {
        textFormat: Text.PlainText
        anchors.centerIn: grid
        visible: sm.shownItems.length === 0
        width: grid.width - Theme.px(40)
        horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
        text: MediaFiles.loading && !sm.tour ? "Looking..."
            : sm.filter === "photo" ? "No photos in Pictures or Videos."
            : sm.filter === "video" ? "No videos in Pictures or Videos."
            : sm.filter === "gif" ? "No GIFs in Pictures or Videos."
            : "Nothing saved in Pictures or Videos. Try Other folders."
        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
    }

    // The bar at the bottom: how many, of what, and Send.
    Rectangle {
        id: bar
        anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
        height: Math.max(sendBtn.implicitHeight, barCol.implicitHeight) + Theme.px(24)
        color: Theme.panel
        Rectangle { width: parent.width; height: 1; color: Theme.line }
        Column {
            id: barCol
            anchors.left: parent.left; anchors.leftMargin: Theme.px(20)
            anchors.right: hdBtn.left; anchors.rightMargin: Theme.px(14)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(2)
            Text {
                textFormat: Text.PlainText
                width: parent.width; elide: Text.ElideRight
                text: sm.picked.length ? sm.picked.length + " selected: " + sm.countText() : "Pick up to 10"
                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width; elide: Text.ElideRight
                text: sm.videosOff ? "Videos are off while Ultra privacy is on." : "Full size close by. Others get up to 5 minutes."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            Text {
                textFormat: Text.PlainText
                visible: !!Ui.main && Ui.main.padInUse
                width: parent.width; elide: Text.ElideRight
                text: "A: pick · X: send · Y: filter · Menu: other folders · B: close"
                color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
        }
        // High quality photos: best on the same Wi-Fi (what Recent pictures offered before).
        HButton {
            id: hdBtn
            anchors.right: sendBtn.left; anchors.rightMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            visible: sm.photoCount > 0
            width: visible ? implicitWidth : 0
            text: sm.hd ? "High quality: on" : "High quality: off"
            kind: sm.hd ? "primary" : "normal"
            onClicked: sm.hd = !sm.hd
        }
        HButton {
            id: sendBtn
            anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
            anchors.verticalCenter: parent.verticalCenter
            text: "Send"; kind: "accent"
            enabled: sm.picked.length > 0
            onClicked: sm.sendNow()
        }
    }
}
