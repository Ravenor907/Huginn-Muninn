import QtQuick
import Huginn

// The Read aloud page (consolidation item 8; Settings › Look › Accessibility › Read aloud), the same as Muninn's: the
// on/off switch, the voice (Female, Alba; Male, northern English; or Device, this computer's own), two smooth sliders
// under it (1.43: Speed, how fast words are spoken, 70% to 130%; Cadence, the pauses between phrases and sentences,
// 50% to 200%; both 100% until moved), Hear a sample, Download the voice now, Allow on mobile data and Delete voices.
// Until the natural voices are published only Device can be picked and one line says "Natural voices aren't ready yet".
//
// The host (SettingsPage) shows it as a page and passes the controller on: handleButton(b) takes "up", "down", "left",
// "right" and "a", and returns false for what it doesn't use ("b", and Left on a row with nothing to its left), so the
// host goes back. [active]: the controller is on this page (draws the focus).
Item {
    id: aloud
    property bool active: true
    // The focused row (an index into rows).
    property int row: 0

    readonly property bool hasRa: typeof ReadAloud !== "undefined" && !!ReadAloud
    readonly property bool isOn: hasRa ? !!ReadAloud.on : false
    readonly property bool natural: hasRa ? !!ReadAloud.natural : false
    readonly property bool ready: hasRa ? !!ReadAloud.ready : false
    readonly property bool hasFiles: hasRa ? !!ReadAloud.hasFiles : false
    readonly property string voice: hasRa ? String(ReadAloud.voice || "f") : "f"
    readonly property bool device: voice === "d"
    readonly property string status: hasRa ? String(ReadAloud.status || "") : ""
    readonly property bool downloading: status.indexOf("Downloading") === 0
    readonly property bool padOn: active && !!Ui.main && !!Ui.main.padInUse
    // The two sliders: the saved values, or where a slider is while it moves (heard at once, saved when it stops).
    readonly property real savedSpeed: hasRa && ReadAloud.speed !== undefined ? Number(ReadAloud.speed) : 100
    readonly property real savedCadence: hasRa && ReadAloud.cadence !== undefined ? Number(ReadAloud.cadence) : 100
    property real movingSpeed: -1
    property real movingCadence: -1
    // When a held key or D-pad press started (it moves faster after half a second).
    property double holdStart: 0
    function isSlider(key) { return key === "speed" || key === "cadence" }
    function low(key) { return key === "cadence" ? 50 : 70 }
    function high(key) { return key === "cadence" ? 200 : 130 }
    function held(key, v) { return Math.max(low(key), Math.min(high(key), Number(v) || 100)) }
    function valueOf(key) {
        return key === "cadence" ? (movingCadence >= 0 ? movingCadence : savedCadence) : (movingSpeed >= 0 ? movingSpeed : savedSpeed)
    }
    function valueText(key) { return Math.round(held(key, valueOf(key))) + "%" }

    readonly property var rows: {
        const r = ["on"]
        if (isOn) {
            r.push("voice", "speed", "cadence", "sample")
            if (natural && !device && !ready && !downloading) r.push("get")
            if (natural && !device) r.push("mobile")
        }
        if (hasFiles) r.push("delete")
        return r
    }
    onRowsChanged: row = Math.max(0, Math.min(rows.length - 1, row))

    implicitHeight: col.implicitHeight
    implicitWidth: Theme.px(420)

    function voiceOptions() {
        return natural ? [{ value: "f", label: "Female" }, { value: "d", label: "Device" }]
                       : [{ value: "d", label: "Device" }]
    }
    function pickVoice(v) {
        if (!hasRa || v === voice) return
        // A natural voice is already kept and this one isn't: switching downloads it (about 65 MB) and deletes the
        // one kept, so ask.
        if (v !== "d" && hasFiles && !(device && ready && v === "f")) {
            Ui.sure("Switch to the natural voice?",
                    "Huginn downloads it (about 65 MB), then deletes the voice it has now.", "Switch", () => { ReadAloud.voice = v })
            return
        }
        ReadAloud.voice = v
    }
    function flip(key) {
        if (!hasRa) return
        if (key === "on") ReadAloud.on = !ReadAloud.on
        else if (key === "mobile") ReadAloud.mobileData = !ReadAloud.mobileData
    }
    function switchOn(key) { return key === "on" ? isOn : key === "mobile" ? (hasRa && !!ReadAloud.mobileData) : false }
    function press(key) {
        if (!hasRa) return
        if (key === "sample") ReadAloud.sample()
        else if (key === "get") { ReadAloud.prepare(); Ui.say(ReadAloud.status || "Downloading the voice.") }
        else if (key === "delete")
            Ui.sure("Delete voices?", "The computer's own voice reads until the natural voice downloads again.", "Delete",
                    () => { ReadAloud.deleteVoices(); Ui.say("Voices deleted.") }, false, true)
    }
    // A slider while it moves: heard at once (the next word or pause), saved when it stops.
    function moveTo(key, v) {
        if (!hasRa) return
        const x = held(key, v)
        if (key === "cadence") {
            movingCadence = x
            if (typeof ReadAloud.previewCadence === "function") ReadAloud.previewCadence(x)
        } else {
            movingSpeed = x
            ReadAloud.previewSpeed(x)
        }
    }
    function saveSliders() {
        saveTimer.stop()
        if (!hasRa) return
        if (movingSpeed >= 0) { ReadAloud.speed = movingSpeed; movingSpeed = -1 }
        if (movingCadence >= 0) { ReadAloud.cadence = movingCadence; movingCadence = -1 }
    }
    // One key press: 1%, and 5% a repeat once the key is held half a second (the same as Muninn's SliderStep).
    function nudge(key, dir, repeat) {
        const now = Date.now()
        if (!repeat || holdStart <= 0) holdStart = now
        const step = now - holdStart >= 500 ? 5 : 1
        moveTo(key, valueOf(key) + dir * step)
        // A controller has no key-up here: saved once the presses stop.
        saveTimer.restart()
    }
    Timer { id: saveTimer; interval: 400; onTriggered: aloud.saveSliders() }

    function cycleVoice(dir) {
        const opts = voiceOptions()
        let i = opts.findIndex(o => o.value === (natural ? voice : "d"))
        i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
        if (opts[i]) pickVoice(opts[i].value)
    }
    function handleButton(b) {
        const key = rows[row] || ""
        const toggle = key === "on" || key === "mobile"
        const repeat = typeof Gamepad !== "undefined" && !!Gamepad && Gamepad.repeating()
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(rows.length - 1, row + 1); return true
        case "a":
            if (toggle) flip(key)
            else if (key === "voice") cycleVoice(0)
            else if (isSlider(key)) { moveTo(key, 100); saveSliders() }
            else press(key)
            return true
        case "left":
            // On a switch Left turns it off once a press (Left on one that's off goes back).
            if (toggle) { if (repeat) return true; if (switchOn(key)) { flip(key); return true } return false }
            if (key === "voice") { cycleVoice(-1); return true }
            if (isSlider(key)) { nudge(key, -1, repeat); return true }
            return false
        case "right":
            if (toggle) { if (!repeat && !switchOn(key)) flip(key); return true }
            if (key === "voice") { cycleVoice(1); return true }
            if (isSlider(key)) { nudge(key, 1, repeat); return true }
            return true
        }
        return false
    }

    Column {
        id: col
        width: parent.width
        Repeater {
            model: aloud.rows
            Rectangle {
                id: rowItem
                required property int index
                required property var modelData
                readonly property string key: String(modelData || "")
                readonly property bool focused: aloud.padOn && aloud.row === index
                readonly property bool slider: aloud.isSlider(key)
                readonly property string title: key === "on" ? "Read aloud" : key === "voice" ? "Voice" : key === "speed" ? "Speed" : key === "cadence" ? "Cadence"
                    : key === "sample" ? "Try the voice" : key === "get" ? "Download the voice now"
                    : key === "mobile" ? "Allow on mobile data" : key === "delete" ? "Delete voices" : ""
                readonly property string note: key === "on" ? "Reads messages out loud"
                    : key === "voice" ? (!aloud.natural ? "Natural voices aren't ready yet" : aloud.device ? "" : aloud.status)
                    : key === "get" ? "About 65 MB" : key === "mobile" ? "Voice download on mobile data"
                    : key === "delete" ? "Frees about 65 MB" : ""
                width: col.width
                height: slider ? sliderCol.implicitHeight + Theme.px(20)
                      : Math.max(Theme.px(52), texts.implicitHeight + Theme.px(20), voiceCtl.visible ? voiceCtl.implicitHeight + Theme.px(16) : 0)
                color: focused ? Theme.focusFill : "transparent"
                border.width: focused ? Theme.focusWidth : 0
                border.color: Theme.accent
                Rectangle { visible: rowItem.index > 0; width: parent.width; height: 1; color: Theme.seam }

                Column {
                    id: texts
                    visible: !rowItem.slider
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
                    anchors.right: control.left; anchors.rightMargin: Theme.px(16)
                    y: (parent.height - implicitHeight) / 2
                    spacing: Theme.px(2)
                    Text { textFormat: Text.PlainText; width: parent.width; wrapMode: Text.Wrap; text: rowItem.title; color: rowItem.key === "delete" ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                    Text { textFormat: Text.PlainText; visible: rowItem.note !== ""; width: parent.width; wrapMode: Text.WordWrap; text: rowItem.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                Item {
                    id: control
                    visible: !rowItem.slider
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
                    anchors.verticalCenter: parent.verticalCenter
                    width: voiceCtl.visible ? Math.min(voiceCtl.implicitWidth, rowItem.width * 0.6)
                         : toggleCtl.visible ? toggleCtl.implicitWidth : buttonCtl.visible ? buttonCtl.implicitWidth : 0
                    height: Math.max(Theme.px(36), voiceCtl.visible ? voiceCtl.implicitHeight : 0, buttonCtl.visible ? buttonCtl.implicitHeight : 0)
                    ToggleSwitch {
                        id: toggleCtl
                        visible: rowItem.key === "on" || rowItem.key === "mobile"
                        anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                        checked: rowItem.key === "on" ? aloud.isOn : rowItem.key === "mobile" ? aloud.switchOn("mobile") : false
                        Accessible.name: rowItem.title
                        onToggled: aloud.flip(rowItem.key)
                    }
                    Segmented {
                        id: voiceCtl
                        visible: rowItem.key === "voice"
                        anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                        width: parent.width
                        options: rowItem.key === "voice" ? aloud.voiceOptions() : []
                        value: aloud.natural ? aloud.voice : "d"
                        onPicked: v => aloud.pickVoice(v)
                    }
                    HButton {
                        id: buttonCtl
                        visible: rowItem.key === "sample" || rowItem.key === "get" || rowItem.key === "delete"
                        anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                        text: rowItem.key === "sample" ? "Hear a sample" : rowItem.key === "get" ? "Download" : "Delete"
                        kind: rowItem.key === "delete" ? "danger" : "normal"
                        focused: rowItem.focused
                        onClicked: aloud.press(rowItem.key)
                    }
                }

                // Speed or Cadence: its value, a smooth slider (no notches) and the two ends named under it.
                Column {
                    id: sliderCol
                    visible: rowItem.slider
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
                    y: Theme.px(10)
                    spacing: Theme.px(6)
                    Item {
                        width: parent.width
                        height: sliderTitle.implicitHeight
                        Text { id: sliderTitle; textFormat: Text.PlainText; text: rowItem.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                        Text { anchors.right: parent.right; textFormat: Text.PlainText; text: rowItem.slider ? aloud.valueText(rowItem.key) : ""; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    }
                    Item {
                        id: track
                        width: parent.width
                        height: Theme.px(28)
                        activeFocusOnTab: rowItem.slider
                        readonly property real lo: aloud.low(rowItem.key)
                        readonly property real hi: aloud.high(rowItem.key)
                        readonly property real frac: rowItem.slider ? Math.max(0, Math.min(1, (aloud.valueOf(rowItem.key) - lo) / (hi - lo))) : 0
                        Accessible.role: Accessible.Slider
                        Accessible.name: "Read aloud " + rowItem.title.toLowerCase() + ", " + (rowItem.slider ? aloud.valueText(rowItem.key) : "")
                        Accessible.onIncreaseAction: aloud.nudge(rowItem.key, 1, false)
                        Accessible.onDecreaseAction: aloud.nudge(rowItem.key, -1, false)
                        function at(px) {
                            const f = Math.max(0, Math.min(1, (px - knob.width / 2) / Math.max(1, width - knob.width)))
                            aloud.moveTo(rowItem.key, lo + f * (hi - lo))
                        }
                        Keys.onPressed: event => {
                            if (event.key === Qt.Key_Left || event.key === Qt.Key_Minus) { aloud.nudge(rowItem.key, -1, event.isAutoRepeat); event.accepted = true }
                            else if (event.key === Qt.Key_Right || event.key === Qt.Key_Plus || event.key === Qt.Key_Equal) { aloud.nudge(rowItem.key, 1, event.isAutoRepeat); event.accepted = true }
                        }
                        Keys.onReleased: event => {
                            if (!event.isAutoRepeat && (event.key === Qt.Key_Left || event.key === Qt.Key_Right || event.key === Qt.Key_Minus
                                                         || event.key === Qt.Key_Plus || event.key === Qt.Key_Equal)) { aloud.saveSliders(); event.accepted = true }
                        }
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: parent.width - knob.width; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.lineMuted }
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: (parent.width - knob.width) * track.frac; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.accent }
                        Rectangle {
                            id: knob
                            anchors.verticalCenter: parent.verticalCenter
                            x: (track.width - width) * track.frac
                            width: Theme.px(18); height: width; radius: Theme.seasonal ? width / 2 : Theme.radiusSmall
                            color: trackHover.hovered || rowItem.focused || track.activeFocus ? Theme.accent : Theme.textSoft
                            border.width: rowItem.focused || track.activeFocus ? Theme.focusWidth : 0
                            border.color: Theme.text
                        }
                        HoverHandler { id: trackHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: point => { track.forceActiveFocus(); track.at(point.position.x); aloud.saveSliders() }
                        }
                        DragHandler {
                            target: null
                            xAxis.enabled: true
                            yAxis.enabled: false
                            onCentroidChanged: if (active) track.at(centroid.position.x)
                            // Saved when the drag ends, never on every pixel.
                            onActiveChanged: if (!active) aloud.saveSliders()
                        }
                    }
                    Item {
                        width: parent.width
                        height: slowText.implicitHeight
                        Text { id: slowText; textFormat: Text.PlainText; text: rowItem.key === "cadence" ? "Shorter pauses" : "Slower"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                        Text { anchors.right: parent.right; textFormat: Text.PlainText; text: rowItem.key === "cadence" ? "Longer pauses" : "Faster"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                    }
                }

                // A tap puts the controller's place here too (the switch and buttons take their own taps).
                TapHandler { onTapped: aloud.row = rowItem.index }
            }
        }
    }
}
