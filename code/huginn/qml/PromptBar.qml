import QtQuick
import Huginn

// The bottom bar: status on the left, controller prompts on the right.
// Shows sending and receiving progress (LocalSend) wherever you are.
Rectangle {
    id: bar
    property string status
    property color statusColor: Theme.textMuted
    property var prompts: []          // [{ key, label }]
    property bool showKeys: true      // the buttons (Main.qml shows them only after Select)
    signal prompt(string key)

    implicitHeight: Math.max(Theme.px(44), (keys.visible ? keys.height : 0) + Theme.px(4))
    color: Theme.ground
    Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.seam }

    Text {
        textFormat: Text.PlainText
        visible: width > Theme.px(24)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(24)
        anchors.verticalCenter: parent.verticalCenter
        anchors.right: bar.showKeys ? keys.left : bar.right
        anchors.rightMargin: Theme.px(20)
        elide: Text.ElideRight
        text: LocalSend.busy ? LocalSend.status + (LocalSend.progress > 0 ? "  " + Math.round(LocalSend.progress * 100) + "%" : "")
            : bar.status
        color: LocalSend.busy ? Theme.accent : bar.statusColor
        font.family: Theme.body
        font.pixelSize: Theme.fs(15)
        font.weight: Font.DemiBold
    }

    // Progress for sending and receiving: a full-width bar along the top of the prompt bar.
    Rectangle {
        visible: LocalSend.busy
        anchors.top: parent.top
        width: parent.width
        height: Theme.px(5)
        color: Theme.line
        Rectangle {
            width: parent.width * LocalSend.progress
            height: parent.height
            color: Theme.accent
        }
    }

    // Fitting the buttons in a narrow window (a handheld is about 914 wide): first Settings and Hide buttons
    // go, then the labels get short, then the rest goes on two lines. Nothing is ever cut off.
    FontMetrics { id: labelMetrics; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
    FontMetrics { id: glyphMetrics; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
    // "Group menu" isn't shortened to "Menu": that reads as the Menu button, which opens Settings.
    readonly property var briefs: ({ "Next section": "Next", "Hold to record": "Record", "Group menu": "Options",
                                     "Mute mic": "Mute", "Unmute mic": "Unmute", "Search, Transfers": "Search",
                                     "Reply in the thread": "Reply", "Answer with video": "Video", "Let go: send": "Send",
                                     "Older or newer": "Scroll", "Pattern or password": "Lock",
                                     "Talk (back paddle)": "Talk" })
    function need(p, brief) {
        const glyph = p.key.split("+").map(k => k === "up" ? "▲" : k === "down" ? "▼" : /^p[1-4]$/.test(k) ? k.toUpperCase() : Gamepad.glyph(k)).join(" ")
        const label = brief && briefs[p.label] !== undefined ? briefs[p.label] : p.label
        return Math.max(Theme.px(27), Math.ceil(glyphMetrics.advanceWidth(glyph)) + Theme.px(14)) + Theme.px(8)
             + Math.ceil(labelMetrics.advanceWidth(label)) + 1
    }
    function total(list, brief) {
        let w = 0
        for (let i = 0; i < list.length; i++) w += need(list[i], brief) + (i ? keys.gap : 0)
        return w
    }
    // Leave the status some room when there is one.
    readonly property real room: Math.max(Theme.px(120), width - Theme.px(48)
                                 - (bar.status !== "" || LocalSend.busy ? Theme.px(140) : 0))
    readonly property var essentials: prompts.filter(p => p.key !== "start" && p.key !== "back")
    readonly property bool brief: total(prompts, false) > room && total(essentials, false) > room
    readonly property var shown: total(prompts, false) <= room ? prompts : essentials
    // The shown buttons in lines that each fit, in order.
    readonly property var lines: {
        const out = [[]]
        let w = 0
        for (let i = 0; i < shown.length; i++) {
            const n = need(shown[i], brief)
            const cur = out[out.length - 1]
            if (cur.length && w + keys.gap + n > room) { out.push([shown[i]]); w = n }
            else { cur.push(shown[i]); w += (cur.length > 1 ? keys.gap : 0) + n }
        }
        return out
    }

    Column {
        id: keys
        visible: bar.showKeys
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(24)
        anchors.verticalCenter: parent.verticalCenter
        readonly property real gap: Theme.px(22)
        Repeater {
            model: bar.lines
            Row {
                required property var modelData
                anchors.right: parent.right
                spacing: keys.gap
                // Two lines: each still a full touch target in Handheld mode (the chips are tappable).
                height: bar.lines.length > 1 ? Math.max(Theme.px(34), Theme.touch ? Theme.tapMin : 0) : implicitHeight
                Repeater {
                    model: parent.modelData
                    PromptKey {
                        required property var modelData
                        anchors.verticalCenter: parent.verticalCenter
                        key: modelData.key
                        label: bar.brief && bar.briefs[modelData.label] !== undefined ? bar.briefs[modelData.label] : modelData.label
                        onActivated: bar.prompt(modelData.key)
                    }
                }
            }
        }
    }
}
