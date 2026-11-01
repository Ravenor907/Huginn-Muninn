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
    signal accepted(string text)
    signal cancelled()

    visible: false
    anchors.fill: parent

    property bool raw: false   // keep spaces as typed (config file lines)
    function open(t, ctx, initial, confirm, callback, isPassword, keepSpaces) {
        openWith(t, ctx, initial, confirm, callback, isPassword, keepSpaces, [], "", "")
    }
    function openWith(t, ctx, initial, confirm, callback, isPassword, keepSpaces, opts, pick, optsLabel) {
        raw = !!keepSpaces
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
    function accept() {
        const t = raw ? field.text : field.text.trim()
        if (!raw && t.length === 0) return
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
        if (!password && field.text.length > 0) { const d = Object.assign({}, drafts); d[draftKey] = field.text; drafts = d }
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

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(440))
        // Never taller than the window: long help text scrolls above the field.
        height: Math.min(parent.height - Theme.px(32), Theme.px(36) + head.implicitHeight + body.implicitHeight + foot.height)
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
                        height: Theme.px(36)
                        radius: Theme.radius
                        color: on ? Theme.accentFill : chHover.hovered ? Theme.raised : "transparent"
                        border.color: on ? Theme.accent : Theme.line
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: modelData.label
                        Accessible.checked: on
                        Text {
                            textFormat: Text.PlainText
                            anchors.centerIn: parent
                            width: parent.width - Theme.px(8)
                            horizontalAlignment: Text.AlignHCenter
                            elide: Text.ElideRight
                            text: modelData.label
                            color: parent.on ? Theme.accent : Theme.textSoft
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                        HoverHandler { id: chHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: prompt.choice = modelData.value }
                    }
                }
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

    function handleButton(b) {
        switch (b) {
        case "a": case "start": accept(); return true
        case "b": cancel(); return true
        case "y": field.forceActiveFocus(); System.showSteamKeyboard(); return true
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
