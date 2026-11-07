import QtQuick
import QtQuick.Controls.Basic
import Huginn

// The message box docked at the bottom of a chat, typed into directly. Enter sends, Shift+Enter starts
// a new line, Ctrl+V pastes a picture, Up in an empty box edits your last message, Esc cancels a reply
// or an edit (or marks the chat read). With a controller, A on the conversation puts you in it and
// Steam's keyboard comes up; the page then routes the buttons here (see boxButton in the pages).
// The microphone on the right: press and hold to record a voice message, let go to send, drag off it
// or press Esc to cancel (a controller holds Y on the conversation instead).
// Dictation (Settings › Look › Accessibility, once it's downloaded): the microphone with lines beside the emoji. Tap it
// (or Ctrl+Shift+S, or Dictate in the box's options on a controller) and speak; the words come in at the cursor after
// each short pause. Tap again (or Esc) to stop. Never during a call.
Item {
    id: box
    property string placeholder: ""
    property string target: ""             // where pasted pictures go: a contact's key or "g:<group>:<channel>"
    property bool showAttach: true         // the paperclip (hidden where files can't go from here)
    property string pasteRefusal: ""       // with no target: said when a picture is pasted, instead of nothing
    property string bannerTitle: ""        // "Replying to Raven" or "Editing your message"; empty for none
    property string bannerText: ""
    property bool editing: false
    property color accent: Theme.accent    // a chat's own color on its page
    property var mentionNames: []          // group members, for @ suggestions
    // Voice messages: where they go (a contact's key or "g:<group>:<channel>"; "" records nothing), and
    // whether the microphone shows at all (the tour shows it without recording).
    property string voiceTo: ""
    property bool micShown: voiceTo !== ""
    // The microphone is held down with the mouse: recording here (the page hides its own banner).
    property bool micHeld: false
    readonly property bool micAway: micHeld && !micArea.containsMouse
    property alias text: field.text
    signal send(string text)
    signal typing(bool on)
    signal editLast()
    signal escaped()
    signal cancelBanner()
    signal attach()

    function focusField() { field.forceActiveFocus() }
    // Into the box to write. Where the on-screen keyboard would cover it (game mode), the message is written in the
    // prompt at the top instead, carrying on what's typed, and its Send sends from here.
    function writeHere() {
        field.forceActiveFocus()
        if (System.keyboardCovers) writeAbove()
    }
    // Round 17: Send sends what's in the prompt as it is (it has no @ suggestions, so a message ending in an @name is
    // sent, not left in the box with the name filled in). It sends only to the chat the prompt was opened for: a chat
    // or channel change under it (a tap on the message banner, a notification) closes it unsent (dropAbove).
    function writeAbove() {
        const to = box.target
        Ui.askMessage(box.placeholder !== "" ? box.placeholder : "Message", "", field.text, "Send", t => {
            if (box.target !== to) { Ui.say("The chat changed, so that message wasn't sent.", true); return }
            box.setText(t)
            box.submit()
        })
        if (Ui.prompt) Ui.prompt.owner = box
    }
    // The prompt at the top, if it's still open for this box: closed (what was typed is kept as its draft), unsent.
    function dropAbove() {
        const p = Ui.prompt
        if (!p || !p.visible || p.owner !== box) return
        p.cancel()
        Ui.say("The chat changed, so that message wasn't sent.", true)
    }
    onTargetChanged: { dropAbove(); dropDictation() }
    Component.onDestruction: { dropAbove(); dropDictation() }
    // Characters typed (shown from 1,800; more than 2,000 don't send).
    readonly property int typedCount: field.length < Ui.messageMax - 200 ? field.length : Ui.textLength(field.text.trim())
    readonly property bool hasFocus: field.activeFocus
    readonly property bool emojiShown: picker.visible
    // The controller or its pointer is in use: the focus shows as thick as everywhere else.
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    // Out of the box (the draft stays): the controller and the keyboard go back to the page.
    function leave() {
        picker.visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
    }
    function toggleEmoji() { picker.visible = !picker.visible }
    function emojiButton(b) { return picker.handleButton(b) }
    // What Enter does: take the first @ suggestion, or send.
    function enter() {
        if (box.suggestions.length > 0) box.pickMention(box.suggestions[0])
        else box.submit()
    }
    // Text put in by the app (Up to edit, a draft) is not typing, so it sends no typing signal.
    property bool settingText: false
    function setText(t) { settingText = true; field.text = t; field.cursorPosition = t.length; settingText = false; field.forceActiveFocus() }
    function insert(t) { field.insert(field.cursorPosition, t); field.forceActiveFocus() }

    // The @word being typed at the cursor, for suggestions ("" when there's none).
    readonly property string atWord: {
        const before = field.text.substring(0, field.cursorPosition)
        const m = before.match(/(^|\s)@([^\s@]{0,20})$/)
        return m ? m[2] : (before.endsWith("@") ? "" : "\u0000")
    }
    readonly property var suggestions: atWord === "\u0000" || !mentionNames.length ? []
        : mentionNames.filter(n => n.toLowerCase().startsWith(atWord.toLowerCase())).slice(0, 6)
    function pickMention(n) {
        const before = field.text.substring(0, field.cursorPosition)
        const after = field.text.substring(field.cursorPosition)
        const cut = before.lastIndexOf("@")
        const head = before.substring(0, cut) + "@" + n + " "
        field.text = head + after
        field.cursorPosition = head.length
        field.forceActiveFocus()
    }
    // ---- Dictation: speech to text at the cursor, all on this computer (src/dictation.h).
    readonly property bool dictateShown: Dictation.shown
    readonly property bool callLive: Calls.inCall || Rooms.inRoom
    // The sessions this box started: only their words come in here.
    property var dictSessions: []
    readonly property bool dictating: Dictation.tour || (Dictation.listening && dictSessions.indexOf(Dictation.session) >= 0)
    function toggleDictation() {
        if (Dictation.tour) return
        if (box.dictating) { Dictation.stop(); return }
        if (box.callLive) { Ui.say("Not during a call.", true); return }
        if (Voice.recording) { Ui.say("Finish the voice message first.", true); return }
        const why = Dictation.start()
        if (why !== "") { Ui.say(why, true); return }
        box.dictSessions = box.dictSessions.concat([Dictation.session]).slice(-8)
    }
    // The chat changed or the box went: listening stops, and nothing still on its way comes in.
    function dropDictation() {
        if (box.dictating && !Dictation.tour) Dictation.cancel()
        box.dictSessions = []
    }
    // Words heard: in at the cursor (a space first after a word), without taking the focus, so no keyboard pops up.
    function dictated(t) {
        const at = field.cursorPosition
        const add = Dictation.join(field.text.substring(0, at), t)
        if (add === "") return
        field.insert(at, add)
        field.cursorPosition = at + add.length
    }
    Connections {
        target: Dictation
        function onHeard(session, text) { if (box.dictSessions.indexOf(session) >= 0) box.dictated(text) }
        function onProblem(session, text) { if (box.dictSessions.indexOf(session) >= 0) Ui.say(text, true) }
    }
    // Press and hold on the microphone.
    function micDown() {
        if (box.voiceTo === "" || Voice.recording) return
        if (box.dictating) Dictation.stop()
        if (!Voice.start(box.voiceTo)) { Ui.say("The microphone isn't available right now.", true); return }
        box.micHeld = true
    }
    function micUp(send) {
        if (!box.micHeld) return
        box.micHeld = false
        if (!Voice.recording) return
        if (send) Voice.finish(box.voiceTo)
        else { Voice.cancel(); Ui.say("Recording cancelled.") }
    }
    // Recording stopped by itself (the 5-minute limit, a microphone error): nothing is held any more.
    Connections {
        target: Voice
        function onChanged() { if (!Voice.recording) box.micHeld = false }
    }
    Shortcut { sequence: "Escape"; enabled: box.micHeld; onActivated: box.micUp(false) }
    function submit() {
        const t = field.text.trim()
        if (t.length === 0) return
        // Too long to send: it stays in the box, never cut.
        const n = Ui.textLength(t)
        if (n > Ui.messageMax) { Ui.say(Ui.tooLongText(n), true); return }
        box.send(t)
        field.text = ""
        box.typing(false)
    }

    implicitHeight: col.implicitHeight

    Column {
        id: col
        width: parent.width
        spacing: Theme.px(6)

        // @ suggestions: names in the group that start with what's typed.
        Rectangle {
            visible: box.suggestions.length > 0
            width: Math.min(parent.width, Theme.px(320))
            height: sugCol.implicitHeight + Theme.px(8)
            radius: Theme.radius
            color: Theme.panel
            border.color: Theme.line
            Column {
                id: sugCol
                x: Theme.px(4); y: Theme.px(4)
                width: parent.width - Theme.px(8)
                Repeater {
                    model: box.suggestions
                    Rectangle {
                        width: sugCol.width
                        height: Theme.px(32)
                        radius: Theme.radius
                        color: sugHover.hovered ? Theme.raised : "transparent"
                        Text { textFormat: Text.PlainText; anchors.verticalCenter: parent.verticalCenter; x: Theme.px(10); text: "@" + modelData; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                        HoverHandler { id: sugHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: box.pickMention(modelData) }
                    }
                }
            }
        }

        // Replying to, or editing: what, with an X.
        Rectangle {
            visible: box.bannerTitle !== ""
            width: parent.width
            height: Theme.px(40)
            radius: Theme.radius
            color: Theme.panel
            Icon { id: bannerIcon; anchors.left: parent.left; anchors.leftMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter; name: box.editing ? "edit" : "reply"; size: Theme.px(16); color: box.accent }
            Text {
                anchors.left: bannerIcon.right; anchors.leftMargin: Theme.px(10)
                anchors.right: bannerClose.left; anchors.rightMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                textFormat: Text.PlainText
                text: box.bannerTitle + (box.bannerText !== "" ? ": " + box.bannerText.replace(/\n/g, " ") : "")
                color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
            Icon {
                id: bannerClose
                anchors.right: parent.right; anchors.rightMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                name: "close"; size: Theme.px(16); color: Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Cancel"
                TapHandler { onTapped: { box.cancelBanner(); field.forceActiveFocus() } }
            }
        }

        Rectangle {
            id: frame
            width: parent.width
            height: Math.min(Math.max(field.implicitHeight, Theme.px(44)), Theme.px(180))
            radius: Theme.radius
            color: Theme.raised
            border.width: field.activeFocus ? (box.pad ? Theme.focusWidth : 1) : 0
            border.color: box.pad ? box.accent : Theme.lineMuted
            Icon {
                id: attachIcon
                visible: box.showAttach
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - size) / 2
                name: "attach"; size: Theme.px(20); color: attachHover.hovered ? Theme.text : Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Send a picture"
                HoverHandler { id: attachHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: box.attach() }
            }
            Flickable {
                id: flick
                anchors.left: box.showAttach ? attachIcon.right : parent.left; anchors.leftMargin: Theme.px(box.showAttach ? 6 : 14)
                anchors.right: emojiIcon.left; anchors.rightMargin: Theme.px(6)
                anchors.top: parent.top; anchors.bottom: parent.bottom
                contentHeight: field.implicitHeight
                clip: true
                boundsBehavior: Flickable.StopAtBounds
                TextArea.flickable: TextArea {
                    id: field
                    FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                    placeholderText: box.placeholder
                    placeholderTextColor: Theme.textMuted
                    color: Theme.text
                    selectionColor: Theme.accentFill
                    selectedTextColor: Theme.text
                    wrapMode: TextEdit.Wrap
                    textFormat: TextEdit.PlainText
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(16)
                    topPadding: Theme.px(12); bottomPadding: Theme.px(12); leftPadding: Theme.px(8); rightPadding: Theme.px(8)
                    background: null
                    Accessible.name: box.placeholder
                    // Editing a sent message is not typing a new one: no typing signals then.
                    onTextChanged: if (!box.settingText && !box.editing) box.typing(text.trim().length > 0)
                    // Game Mode has no keyboard of its own: Steam's comes up (it does nothing elsewhere). Not when the focus
                    // only came back because Huginn's window did, or a menu closed: the person didn't ask to type then.
                    // Where that keyboard would cover the box, it never opens blind over it: a tap writes in the prompt
                    // at the top (writeAbove), and the pages do the same for the controller (writeHere).
                    onActiveFocusChanged: {
                        if (!activeFocus || focusReason === Qt.ActiveWindowFocusReason || focusReason === Qt.PopupFocusReason) return
                        if (!System.keyboardCovers) System.showSteamKeyboard()
                        else if (focusReason === Qt.MouseFocusReason) Qt.callLater(box.writeAbove)
                    }
                    Keys.onPressed: event => {
                        if (event.key === Qt.Key_Escape && box.micHeld) {
                            box.micUp(false)
                            event.accepted = true
                        } else if (event.key === Qt.Key_Escape && box.dictating) {
                            box.toggleDictation()
                            event.accepted = true
                        } else if (event.key === Qt.Key_S && (event.modifiers & Qt.ControlModifier) && (event.modifiers & Qt.ShiftModifier) && box.dictateShown) {
                            box.toggleDictation()
                            event.accepted = true
                        } else if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter) && !(event.modifiers & Qt.ShiftModifier)) {
                            box.enter()
                            event.accepted = true
                        } else if (event.key === Qt.Key_Up && field.text.length === 0) {
                            box.editLast()
                            event.accepted = true
                        } else if (event.key === Qt.Key_Escape) {
                            if (box.bannerTitle !== "") box.cancelBanner()
                            else box.escaped()
                            event.accepted = true
                        } else if (event.matches(StandardKey.Paste) && box.target !== "" && Chat.clipboardHasImage()) {
                            if (Chat.pasteImage(box.target)) Ui.say("Sending the picture.")
                            event.accepted = true
                        } else if (event.matches(StandardKey.Paste) && box.target === "" && box.pasteRefusal !== "" && Chat.clipboardHasImage()) {
                            Ui.say(box.pasteRefusal)
                            event.accepted = true
                        }
                    }
                }
            }
            // Recording: the time over the text, and what letting go does.
            Rectangle {
                visible: box.micHeld
                anchors.left: attachIcon.left; anchors.right: emojiIcon.right
                anchors.top: parent.top; anchors.bottom: parent.bottom
                anchors.topMargin: 1; anchors.bottomMargin: 1
                color: Theme.raised
                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width
                    spacing: Theme.px(10)
                    Rectangle { width: Theme.px(10); height: width; radius: width / 2; color: Theme.danger; anchors.verticalCenter: parent.verticalCenter }
                    Text {
                        textFormat: Text.PlainText
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(0, parent.width - Theme.px(20))
                        elide: Text.ElideRight
                        text: Voice.length(Voice.elapsed) + "   " + (box.micAway ? "Let go to cancel" : "Let go to send · drag away or Esc to cancel")
                        color: box.micAway ? Theme.danger : Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(15)
                    }
                }
            }
            Icon {
                id: emojiIcon
                anchors.right: dictIcon.visible ? dictIcon.left : micIcon.visible ? micIcon.left : parent.right; anchors.rightMargin: Theme.px(14)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - size) / 2
                name: "smile"; size: Theme.px(20); color: emojiHover.hovered || picker.visible ? Theme.text : Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Emoji"
                HoverHandler { id: emojiHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: picker.visible = !picker.visible }
            }
            // Dictation: tap to start, tap again to stop. Listening shows in the accent, with a ring that pulses.
            Rectangle {
                id: dictIcon
                readonly property bool off: box.callLive && !box.dictating
                visible: box.dictateShown
                anchors.right: micIcon.visible ? micIcon.left : parent.right; anchors.rightMargin: Theme.px(micIcon.visible ? 4 : 8)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - height) / 2
                width: Theme.px(32); height: width
                radius: Theme.radius
                color: box.dictating ? Theme.accentFill : dictArea.containsMouse && !off ? Theme.tile : "transparent"
                border.width: box.dictating ? 1 : 0
                border.color: box.accent
                opacity: off ? 0.4 : 1
                Accessible.role: Accessible.Button
                Accessible.name: box.dictating ? "Stop dictating" : "Dictate"
                Accessible.description: off ? "Not during a call" : ""
                Rectangle {
                    id: dictPulse
                    anchors.fill: parent
                    radius: parent.radius
                    color: "transparent"
                    border.width: Theme.px(2)
                    border.color: box.accent
                    visible: box.dictating
                    opacity: 0.6
                    SequentialAnimation on opacity {
                        running: dictPulse.visible && Theme.motion
                        loops: Animation.Infinite
                        NumberAnimation { from: 0.7; to: 0.1; duration: 700; easing.type: Easing.InOutQuad }
                        NumberAnimation { from: 0.1; to: 0.7; duration: 700; easing.type: Easing.InOutQuad }
                    }
                }
                Icon {
                    anchors.centerIn: parent
                    name: "dictate"; size: Theme.px(20)
                    color: box.dictating ? box.accent : dictArea.containsMouse && !dictIcon.off ? Theme.text : Theme.textMuted
                }
                MouseArea {
                    id: dictArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: dictIcon.off ? Qt.ArrowCursor : Qt.PointingHandCursor
                    onClicked: box.toggleDictation()
                }
                ToolTip.visible: dictArea.containsMouse
                ToolTip.delay: 400
                ToolTip.text: dictIcon.off ? "Not during a call" : box.dictating ? "Stop dictating (Ctrl+Shift+S)" : "Dictate (Ctrl+Shift+S)"
            }
            // Press and hold to record; let go on it to send, off it to cancel.
            Rectangle {
                id: micIcon
                visible: box.micShown
                anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - height) / 2
                width: Theme.px(32); height: width
                radius: Theme.radius
                color: box.micHeld ? (box.micAway ? Theme.raised : Theme.dangerFill) : micArea.containsMouse ? Theme.tile : "transparent"
                border.width: box.micHeld ? 1 : 0
                border.color: Theme.danger
                Accessible.role: Accessible.Button
                Accessible.name: "Hold to record a voice message"
                Icon {
                    anchors.centerIn: parent
                    name: "mic"; size: Theme.px(20)
                    color: box.micHeld ? Theme.danger : micArea.containsMouse ? Theme.text : Theme.textMuted
                }
                MouseArea {
                    id: micArea
                    anchors.fill: parent
                    hoverEnabled: true
                    preventStealing: true
                    cursorShape: Qt.PointingHandCursor
                    onPressed: box.micDown()
                    onReleased: box.micUp(containsMouse)
                    onCanceled: box.micUp(false)
                }
            }
        }
        // Near the limit: how many characters, so a long one is split before it's sent.
        Text {
            visible: box.typedCount >= Ui.messageMax - 200
            width: parent.width
            horizontalAlignment: Text.AlignRight
            textFormat: Text.PlainText
            text: box.typedCount > Ui.messageMax
                  ? String(box.typedCount).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + " of 2,000 characters: too long to send"
                  : String(box.typedCount).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + " of 2,000 characters"
            color: box.typedCount > Ui.messageMax ? Theme.danger : Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
    }

    EmojiPicker {
        id: picker
        anchors.right: parent.right
        anchors.bottom: parent.top
        anchors.bottomMargin: Theme.px(6)
        onPicked: e => { box.insert(e); picker.visible = false }
    }
}
