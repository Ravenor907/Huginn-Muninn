import QtQuick
import QtQuick.Controls.Basic
import Huginn

// Asks for a line of text: the title on its own line, the help text wrapping under it, the field,
// and Cancel and the answer at the foot. Sized to its content (about 440 px), never full width.
// Enter confirms, Escape or B cancels. A list of choices (like a group's kind) can sit under the field.
Item {
    id: prompt
    property string title
    property string context
    property string label: ""
    property string confirmText: "Done"
    property bool password: false
    property var acceptCallback: null
    // Set after opening, for a prompt whose way out is a choice too (like Skip): its label and what it does.
    property string cancelText: "Cancel"
    property var cancelCallback: null
    // Optional: [{ value, label }] shown as a row of buttons under the field; the callback gets the pick too.
    property var choices: []
    property string choice: ""
    property string choicesLabel: ""
    // Optional, set after opening: a small link under the choices that shows more words in place (like the rules of
    // each group type). Choices with a "detail" show as cards with that line under the name.
    property string moreLabel: ""
    property string moreText: ""
    property bool moreOpen: false
    readonly property bool cards: choices.some(c => !!c.detail)
    // Set after opening (Ui.askAppend): the most characters the answer may hold, 0 for no limit.
    property int limit: 0
    signal accepted(string text)
    signal cancelled()

    visible: false
    anchors.fill: parent

    property bool raw: false   // keep spaces as typed (config file lines)
    // Several lines (a group's about text and rules): Enter starts a new line, Ctrl+Enter saves.
    property bool multiLine: false
    readonly property string typed: multiLine ? area.text : field.text
    function openLong(t, ctx, initial, confirm, callback) {
        openWith(t, ctx, initial, confirm, callback, false, true, [], "", "")
        multiLine = true
        area.text = field.text
        area.cursorPosition = area.text.length
        area.forceActiveFocus()
    }
    function open(t, ctx, initial, confirm, callback, isPassword, keepSpaces) {
        openWith(t, ctx, initial, confirm, callback, isPassword, keepSpaces, [], "", "")
    }
    function openWith(t, ctx, initial, confirm, callback, isPassword, keepSpaces, opts, pick, optsLabel) {
        raw = !!keepSpaces
        multiLine = false
        label = ""
        title = t
        context = ctx || ""
        confirmText = confirm || "Done"
        password = !!isPassword
        acceptCallback = callback || null
        cancelText = "Cancel"
        cancelCallback = null
        choices = opts || []
        choice = pick || (choices.length ? choices[0].value : "")
        choicesLabel = optsLabel || ""
        moreLabel = ""
        moreText = ""
        moreOpen = false
        limit = 0
        draftKey = t + "|" + (ctx || "") + "|" + (initial || "")
        const draft = isPassword ? "" : (drafts[draftKey] || "")
        field.text = draft || initial || ""
        visible = true
        field.forceActiveFocus()
        // Select the name but not the extension, like a desktop file manager.
        const dot = field.text.lastIndexOf(".")
        field.select(0, dot > 0 && !isPassword ? dot : field.text.length)
        System.showSteamKeyboard()
    }
    // A draft carried on (Ui.askAppend): the cursor after it, nothing selected.
    function cursorToEnd() {
        const f = multiLine ? area : field
        f.deselect()
        f.cursorPosition = f.text.length
    }
    function accept() {
        const t = raw && !multiLine ? typed : typed.trim()
        if (!raw && t.length === 0) return
        // Too long to send: say so and keep it here, never cut.
        if (limit > 0 && Ui.textLength(t) > limit) { Ui.say(Ui.tooLongText(Ui.textLength(t)), true); return }
        if (drafts[draftKey] !== undefined) { const d = Object.assign({}, drafts); delete d[draftKey]; drafts = d }
        visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
        const pick = choice
        cancelCallback = null
        if (acceptCallback) acceptCallback(t, pick)
        accepted(t)
    }
    // Cancel by mistake shouldn't lose what was typed: it comes back the next time this prompt opens.
    property var drafts: ({})
    property string draftKey: ""
    function cancel() {
        if (!password && typed.length > 0) { const d = Object.assign({}, drafts); d[draftKey] = typed; drafts = d }
        visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
        const after = cancelCallback
        cancelCallback = null
        cancelled()
        if (after) after()
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; onClicked: prompt.cancel() }

    // Steam's on-screen keyboard covers the lower half of the window: the box sits at the top, above it.
    readonly property bool atTop: visible && System.keyboardCovers()
    Rectangle {
        id: box
        anchors.centerIn: prompt.atTop ? undefined : parent
        anchors.top: prompt.atTop ? parent.top : undefined
        anchors.topMargin: prompt.atTop ? Theme.px(8) : 0
        anchors.horizontalCenter: prompt.atTop ? parent.horizontalCenter : undefined
        width: Math.min(parent.width - Theme.px(32), Theme.px(440))
        // Never taller than the window (at the top, than the half above the keyboard): long help text scrolls above
        // the field.
        height: Math.min(prompt.atTop ? Math.max(Theme.px(160), parent.height * 0.5) : parent.height - Theme.px(32),
                         Theme.px(36) + head.implicitHeight + body.implicitHeight + foot.height)
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent }   // a click inside doesn't close it

        Flickable {
            id: headView
            x: Theme.px(20); y: Theme.px(20)
            width: parent.width - Theme.px(40)
            height: Math.max(0, Math.min(head.implicitHeight, box.height - Theme.px(36) - body.implicitHeight - foot.height))
            contentWidth: width
            contentHeight: head.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: head
                width: headView.width
                spacing: Theme.px(8)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: prompt.title.toUpperCase()
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: Theme.fs(18)
                    font.weight: Font.Bold
                    font.letterSpacing: Theme.px(2)
                }
                // Wraps at spaces, and inside a long code that has none.
                Text {
                    textFormat: Text.PlainText
                    visible: prompt.context.length > 0
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: prompt.context
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(14)
                    lineHeight: 1.15
                }
            }
        }
        Column {
            id: body
            anchors.top: headView.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            spacing: Theme.px(6)
            Text {
                textFormat: Text.PlainText
                visible: prompt.label.length > 0
                text: prompt.label.toUpperCase()
                color: Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
            }
            TextField {
                id: field
                visible: !prompt.multiLine
                FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                width: parent.width
                height: Math.max(Theme.px(40), implicitHeight)
                leftPadding: Theme.px(10)
                color: Theme.text
                selectionColor: Theme.accentFill
                selectedTextColor: Theme.text
                echoMode: prompt.password ? TextInput.Password : TextInput.Normal
                font.family: Theme.body
                font.pixelSize: Theme.fs(15)
                inputMethodHints: Qt.ImhNoPredictiveText
                background: Rectangle {
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.color: field.activeFocus ? Theme.accent : Theme.line
                }
                Keys.onReturnPressed: prompt.accept()
                Keys.onEnterPressed: prompt.accept()
                Keys.onEscapePressed: prompt.cancel()
            }
            // Several lines: wraps, scrolls past about eight lines.
            ScrollView {
                id: areaView
                visible: prompt.multiLine
                width: parent.width
                // Shorter above the on-screen keyboard, so all of it stays in sight.
                height: visible ? Theme.px(prompt.atTop ? 100 : 180) : 0
                background: Rectangle {
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.color: area.activeFocus ? Theme.accent : Theme.line
                }
                TextArea {
                    id: area
                    FieldMenu {}
                    wrapMode: TextEdit.Wrap
                    leftPadding: Theme.px(10); rightPadding: Theme.px(10); topPadding: Theme.px(8); bottomPadding: Theme.px(8)
                    color: Theme.text
                    selectionColor: Theme.accentFill
                    selectedTextColor: Theme.text
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(15)
                    background: null
                    Keys.onEscapePressed: prompt.cancel()
                    Keys.onReturnPressed: event => { if (event.modifiers & Qt.ControlModifier) prompt.accept(); else event.accepted = false }
                    Keys.onEnterPressed: event => { if (event.modifiers & Qt.ControlModifier) prompt.accept(); else event.accepted = false }
                }
            }
            Text {
                textFormat: Text.PlainText
                visible: prompt.multiLine
                width: parent.width
                wrapMode: Text.WordWrap
                text: Ui.main && Ui.main.padInUse ? "Enter starts a new line. A saves." : "Enter starts a new line. Ctrl+Enter saves."
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            Text {
                textFormat: Text.PlainText
                visible: prompt.choices.length > 0 && prompt.choicesLabel.length > 0
                width: parent.width
                wrapMode: Text.WordWrap
                topPadding: Theme.px(8)
                text: prompt.choicesLabel
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            Row {
                visible: prompt.choices.length > 0
                width: parent.width
                spacing: Theme.px(6)
                Repeater {
                    model: prompt.choices
                    Rectangle {
                        readonly property bool on: prompt.choice === modelData.value
                        width: (parent.width - Theme.px(6) * (prompt.choices.length - 1)) / Math.max(1, prompt.choices.length)
                        height: prompt.cards ? Math.max(Theme.px(84), cardCol.implicitHeight + Theme.px(16)) : Theme.px(36)
                        radius: Theme.radius
                        color: on ? Theme.accentFill : chHover.hovered ? Theme.raised : "transparent"
                        border.color: on ? Theme.accent : Theme.line
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: modelData.label
                        Accessible.description: modelData.detail || ""
                        Accessible.checked: on
                        Column {
                            id: cardCol
                            anchors.centerIn: parent
                            width: parent.width - Theme.px(prompt.cards ? 16 : 8)
                            spacing: Theme.px(4)
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                horizontalAlignment: prompt.cards ? Text.AlignLeft : Text.AlignHCenter
                                elide: Text.ElideRight
                                text: modelData.label
                                color: cardCol.parent.on ? Theme.accent : prompt.cards ? Theme.text : Theme.textSoft
                                font.family: Theme.body; font.pixelSize: Theme.fs(prompt.cards ? 15 : 13)
                                font.bold: prompt.cards
                            }
                            Text {
                                textFormat: Text.PlainText
                                visible: prompt.cards
                                width: parent.width
                                wrapMode: Text.WordWrap
                                text: modelData.detail || ""
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(12)
                            }
                        }
                        HoverHandler { id: chHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: prompt.choice = modelData.value }
                    }
                }
            }
            Text {
                textFormat: Text.PlainText
                visible: prompt.moreLabel.length > 0
                topPadding: Theme.px(8)
                text: prompt.moreLabel
                color: moreHover.hovered ? Theme.accent : Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
                font.underline: true
                Accessible.role: Accessible.Button
                Accessible.name: text
                HoverHandler { id: moreHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: prompt.moreOpen = !prompt.moreOpen }
            }
            Text {
                textFormat: Text.PlainText
                visible: prompt.moreOpen && prompt.moreText.length > 0
                width: parent.width
                wrapMode: Text.WordWrap
                topPadding: Theme.px(6)
                text: prompt.moreText
                color: Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
                lineHeight: 1.2
            }
            Item { width: 1; height: Theme.px(14) }
        }
        Rectangle {
            id: foot
            anchors.bottom: parent.bottom
            width: parent.width
            height: Theme.px(64)
            color: Theme.panelLow
            Rectangle { width: parent.width; height: 1; color: Theme.line }
            Row {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(8)
                HButton { text: prompt.cancelText; kind: "plain"; onClicked: prompt.cancel() }
                HButton { text: prompt.confirmText; kind: "primary"; onClicked: prompt.accept() }
            }
        }
    }

    // The controller's buttons here. X pastes while the clipboard has text; LT then shows the more words, or Down when
    // LT is the talk button in a voice room (it only talks there).
    readonly property bool canPaste: visible && (multiLine ? area.canPaste : field.canPaste)
    readonly property bool ltTalks: TalkKey.padButton === "lt" && Settings.pushToTalk && Rooms.inRoom
    readonly property string moreKey: !canPaste ? "x" : ltTalks ? "down" : "lt"
    readonly property var prompts: [{ key: "a", label: confirmText }, { key: "b", label: cancelText }].concat(System.keyboardAvailable() ? [{ key: "y", label: "Keyboard" }] : [],
        canPaste ? [{ key: "x", label: "Paste" }] : [], moreLabel.length ? [{ key: moreKey, label: moreLabel }] : [])
    function handleButton(b) {
        switch (b) {
        case "a": case "start": accept(); return true
        case "b": cancel(); return true
        case "y": (multiLine ? area : field).forceActiveFocus(); System.showSteamKeyboard(); return true
        case "x":
            if (canPaste) { const f = multiLine ? area : field; f.forceActiveFocus(); f.paste() }
            else if (moreLabel.length) moreOpen = !moreOpen
            return true
        case "lt": if (moreLabel.length && moreKey !== "down") moreOpen = !moreOpen; return true
        case "down": if (moreLabel.length && moreKey === "down") moreOpen = !moreOpen; return true
        case "left": case "right":
            if (choices.length) {
                const i = Math.max(0, choices.findIndex(c => c.value === choice))
                choice = choices[Math.max(0, Math.min(choices.length - 1, i + (b === "left" ? -1 : 1)))].value
            }
            return true
        }
        return true
    }
}
