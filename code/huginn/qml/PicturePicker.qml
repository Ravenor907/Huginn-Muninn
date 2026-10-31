import QtQuick
import Huginn

// Your picture: choose one from a file, use your initial on a color instead, or remove it. A chosen
// file goes through Move and scale: the picture under a round window, dragged to move, the wheel, a
// pinch or the slider to zoom, and Save keeps a 256 by 256 crop. It lives in ProfileCard.qml and is
// opened from Settings › You. D-pad moves along the choices, A picks, B goes back.
Item {
    id: pp
    visible: false
    anchors.fill: parent
    property string step: "choose"     // "choose" or "crop"
    property int focusIndex: 0
    signal closed()

    function open() { step = "choose"; focusIndex = 0; visible = true }
    function close() { visible = false; closed() }
    // The computer's own file picker (System.pickPicture), so no extra Qt module is needed.
    function choose() { const f = System.pickPicture(); if (f !== "") pp.startCrop(f) }
    function startCrop(url) {
        crop.source = ""
        crop.zoom = 1
        crop.source = url
        step = "crop"; focusIndex = 0
    }
    function useInitial(hex) {
        Profile.setMyColour(hex)
        if (Profile.hasMyPicture) Profile.clearMyPicture()
    }
    function remove() { Profile.clearMyPicture(); Ui.say("Your picture is removed.") }
    function save() {
        if (crop.status !== Image.Ready) { Ui.say("The picture isn't ready yet.", true); return }
        // The window's exact contents, scaled to 256 by 256: what everyone sees.
        cropWindow.grabToImage(function(result) {
            if (!Profile.setMyPicture(result.image)) { Ui.say("That picture couldn't be made small enough to send. Try another.", true); return }
            Ui.say("Your picture is saved and sent to your contacts.")
            pp.step = "choose"; pp.focusIndex = 0
        }, Qt.size(256, 256))
    }


    // The choices on the first step, in the order the controller steps through them.
    readonly property var choices: {
        const a = [{ id: "file", label: "Choose a picture", icon: "image" }]
        a.push({ id: "initial", label: "Use my initial and a color", icon: "person" })
        if (Profile.hasMyPicture) a.push({ id: "remove", label: "Remove picture", icon: "close", danger: true })
        a.push({ id: "close", label: "Close" })
        return a
    }
    property int swatch: -1           // the focused swatch on the Use my initial row, or none
    readonly property var cropChoices: [{ id: "save", label: "Save" }, { id: "back", label: "Back" }]
    function run(id) {
        if (id === "file") choose()
        else if (id === "initial") { if (swatch < 0) swatch = Math.max(0, Theme.pictureColours.indexOf(String(Profile.myColour).toUpperCase())); else useInitial(Theme.pictureColours[swatch]) }
        else if (id === "remove") remove()
        else if (id === "close") close()
        else if (id === "save") save()
        else if (id === "back") { step = "choose"; focusIndex = 0 }
    }
    function handleButton(b) {
        if (step === "crop") {
            const n = cropChoices.length
            if (b === "b") run("back")
            else if (b === "a") run(cropChoices[focusIndex].id)
            else if (b === "left" || b === "up") focusIndex = Math.max(0, focusIndex - 1)
            else if (b === "right" || b === "down") focusIndex = Math.min(n - 1, focusIndex + 1)
            else if (b === "lb") crop.zoomBy(-0.1)
            else if (b === "rb") crop.zoomBy(0.1)
            return true
        }
        const n = choices.length
        if (focusIndex >= n) focusIndex = n - 1
        const onInitial = choices[focusIndex] && choices[focusIndex].id === "initial"
        if (b === "b") { if (swatch >= 0) swatch = -1; else close() }
        else if (b === "a") run(choices[focusIndex].id)
        else if (onInitial && swatch >= 0 && b === "left") swatch = Math.max(0, swatch - 1)
        else if (onInitial && swatch >= 0 && b === "right") swatch = Math.min(Theme.pictureColours.length - 1, swatch + 1)
        else if (b === "up") { swatch = -1; focusIndex = Math.max(0, focusIndex - 1) }
        else if (b === "down") { swatch = -1; focusIndex = Math.min(n - 1, focusIndex + 1) }
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.6) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: pp.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(60), Theme.px(440))
        height: Math.min(parent.height - Theme.px(40), (pp.step === "crop" ? cropCol.implicitHeight : chooseCol.implicitHeight) + Theme.px(48))
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: pp.step === "crop" ? "Move and scale" : "Your picture"
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        // ------------------------------------------------------------- step one: choose
        // Scrolls when the window is short or the Size is large.
        Flickable {
            id: chooseView
            visible: pp.step === "choose"
            x: Theme.px(24); y: Theme.px(24)
            width: parent.width - Theme.px(48)
            height: Math.max(0, parent.height - Theme.px(48))
            contentWidth: width
            contentHeight: chooseCol.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: chooseCol
                width: chooseView.width
                spacing: Theme.px(14)
                Text {
                    text: "YOUR PICTURE"
                    color: Theme.accent
                    font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
                }
                Column {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: Theme.px(8)
                    Avatar { anchors.horizontalCenter: parent.horizontalCenter; key: "me"; name: Chat.username; size: Theme.px(96); backdrop: Theme.panel }
                    Text { anchors.horizontalCenter: parent.horizontalCenter; text: Chat.username; color: Theme.textBright; font.family: Theme.body; font.pixelSize: Theme.fs(17); font.weight: Font.DemiBold }
                }
                Column {
                    width: parent.width
                    spacing: Theme.px(4)
                    Repeater {
                        model: pp.choices
                        Column {
                            width: parent.width
                            spacing: Theme.px(8)
                            ChoiceRow { choice: modelData; at: index; width: parent.width }
                            // The six colors, under Use my initial and a color.
                            Row {
                                visible: modelData.id === "initial"
                                x: Theme.px(40)
                                spacing: Theme.px(10)
                                Repeater {
                                    model: Theme.pictureColours
                                    Rectangle {
                                        readonly property bool current: String(Profile.myColour).toUpperCase() === modelData && !Profile.hasMyPicture
                                        readonly property bool focused: pp.swatch === index
                                        width: Theme.px(34); height: width; radius: width / 2
                                        color: modelData
                                        border.width: current || focused ? Theme.px(3) : 1
                                        border.color: focused ? Theme.text : current ? Theme.accent : Theme.lineMuted
                                        Accessible.role: Accessible.RadioButton
                                        Accessible.name: ["Gold", "Blue", "Brown", "Green", "Purple", "Grey"][index] + " for your initial"
                                        Accessible.checked: current
                                        Accessible.onPressAction: pp.useInitial(modelData)
                                        Icon { visible: parent.current; anchors.centerIn: parent; name: "check"; size: Theme.px(16); color: Theme.avatarFrom(modelData).fg }
                                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                                        TapHandler { onTapped: pp.useInitial(modelData) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: "Sent only to your contacts and the groups you're in, encrypted like a message."
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
            }
        }

        // ------------------------------------------------------------- step two: move and scale
        Column {
            id: cropCol
            visible: pp.step === "crop"
            x: Theme.px(24); y: Theme.px(24)
            width: parent.width - Theme.px(48)
            spacing: Theme.px(14)
            Text {
                text: "MOVE AND SCALE"
                color: Theme.accent
                font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
            }
            // The square that's saved, with the round window everyone sees drawn over it.
            Item {
                id: frame
                anchors.horizontalCenter: parent.horizontalCenter
                readonly property int side: Math.min(Theme.px(280), cropCol.width, Math.max(Theme.px(120), Math.round(pp.height * 0.4)))
                width: side; height: side
                Accessible.role: Accessible.Graphic
                Accessible.name: "Your picture under a round window. Drag to move, scroll or pinch to zoom"
                Item {
                    id: cropWindow
                    anchors.fill: parent
                    clip: true
                    Rectangle { anchors.fill: parent; color: "#000000" }
                    Image {
                        id: crop
                        property real zoom: 1
                        // Just big enough to crop from; the saved copy is 256 by 256.
                        sourceSize.width: 1024; sourceSize.height: 1024
                        autoTransform: true
                        asynchronous: true
                        fillMode: Image.Stretch
                        smooth: true; mipmap: true
                        // The smallest zoom fills the window; the picture can grow to four times that.
                        readonly property real fit: implicitWidth > 0 && implicitHeight > 0 ? Math.max(frame.side / implicitWidth, frame.side / implicitHeight) : 1
                        width: implicitWidth * fit * zoom
                        height: implicitHeight * fit * zoom
                        onStatusChanged: if (status === Image.Ready) { zoom = 1; centre() }
                        function centre() { x = (frame.side - width) / 2; y = (frame.side - height) / 2 }
                        // Keeps the window covered: no edge of the picture ever shows inside it.
                        function settle() {
                            x = Math.min(0, Math.max(frame.side - width, x))
                            y = Math.min(0, Math.max(frame.side - height, y))
                        }
                        // Zoom about the window's middle.
                        function zoomTo(z) {
                            z = Math.max(1, Math.min(4, z))
                            if (z === zoom) return
                            const cx = (frame.side / 2 - x) / width, cy = (frame.side / 2 - y) / height
                            zoom = z
                            x = frame.side / 2 - cx * width
                            y = frame.side / 2 - cy * height
                            settle()
                        }
                        function zoomBy(d) { zoomTo(zoom + d) }
                        DragHandler {
                            target: crop
                            onActiveChanged: if (!active) crop.settle()
                        }
                        PinchHandler {
                            target: null
                            onScaleChanged: delta => crop.zoomTo(crop.zoom * delta)
                        }
                    }
                    WheelHandler {
                        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                        onWheel: event => crop.zoomBy(event.angleDelta.y > 0 ? 0.1 : -0.1)
                    }
                    Text {
                        visible: crop.status === Image.Error
                        anchors.centerIn: parent
                        width: parent.width - Theme.px(24)
                        wrapMode: Text.WordWrap; horizontalAlignment: Text.AlignHCenter
                        text: "That file couldn't be opened as a picture."
                        color: Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                }
                // Outside the circle, dimmed: a ring whose thick edge covers the corners.
                Item {
                    anchors.fill: parent
                    clip: true
                    Rectangle {
                        anchors.centerIn: parent
                        width: frame.side * 3; height: width; radius: width / 2
                        color: "transparent"
                        border.width: frame.side
                        border.color: Qt.rgba(0, 0, 0, 0.55)
                    }
                    Rectangle {
                        anchors.fill: parent; anchors.margins: 1
                        radius: width / 2
                        color: "transparent"
                        border.width: Theme.px(2); border.color: Theme.accent
                    }
                }
                HoverHandler { cursorShape: Qt.SizeAllCursor }
            }
            // Zoom: the slider, and how big the picture is.
            Row {
                width: parent.width
                spacing: Theme.px(10)
                Text { id: zoomCaption; anchors.verticalCenter: parent.verticalCenter; text: "Zoom"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                HSlider {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - zoomCaption.width - zoomText.width - Theme.px(20)
                    from: 1; to: 4; stepSize: 0.05
                    value: crop.zoom
                    label: "Zoom"
                    onMoved: v => crop.zoomTo(v)
                }
                Text { id: zoomText; anchors.verticalCenter: parent.verticalCenter; width: Theme.px(44); horizontalAlignment: Text.AlignRight; text: Math.round(crop.zoom * 100) + "%"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                text: "Drag to move, scroll or pinch to zoom. Saved small (256 by 256). Sent only to your contacts and the groups you're in, encrypted like a message."
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            Row {
                spacing: Theme.px(8)
                HButton { text: "Save"; kind: "primary"; focused: pp.focusIndex === 0; enabled: crop.status === Image.Ready; onClicked: pp.run("save") }
                HButton { text: "Back"; focused: pp.focusIndex === 1; onClicked: pp.run("back") }
            }
        }
    }

    // Scrolls the first step so the row the controller is on is in view.
    function reveal(item) {
        if (!item || !item.visible) return
        const y = item.mapToItem(chooseCol, 0, 0).y
        if (y < chooseView.contentY) chooseView.contentY = y
        else if (y + item.height > chooseView.contentY + chooseView.height) chooseView.contentY = Math.min(chooseView.contentHeight - chooseView.height, y + item.height - chooseView.height)
    }

    // One choice on the first step: an icon and its words, lit while focused.
    component ChoiceRow: Rectangle {
        id: cr
        property var choice: ({})
        property int at: 0
        readonly property bool focused: pp.focusIndex === at && pp.swatch < 0
        height: Theme.px(44)
        onFocusedChanged: if (focused) Qt.callLater(pp.reveal, cr)
        radius: Theme.radius
        color: focused ? Theme.focusFill : crHover.hovered ? Theme.raised : "transparent"
        border.width: focused ? Theme.focusWidth : 0
        border.color: Theme.accent
        Accessible.role: Accessible.Button
        Accessible.name: choice.label || ""
        Accessible.onPressAction: pp.run(choice.id)
        Icon {
            id: crIcon
            visible: !!cr.choice.icon
            anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
            anchors.verticalCenter: parent.verticalCenter
            name: cr.choice.icon || "file"; size: Theme.px(20)
            color: cr.choice.danger ? Theme.danger : Theme.textSoft
        }
        Text {
            anchors.left: parent.left; anchors.leftMargin: cr.choice.icon ? Theme.px(42) : Theme.px(12)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: cr.choice.label || ""
            color: cr.choice.danger ? Theme.danger : Theme.text
            font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
        }
        HoverHandler { id: crHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: { pp.focusIndex = cr.at; pp.swatch = -1; pp.run(cr.choice.id) } }
    }

    // A plain slider in house style: a line, a knob, Left and Right or a drag to move it.
    component HSlider: Item {
        id: sl
        property real from: 0
        property real to: 100
        property real stepSize: 1
        property real value: 0
        property string label: ""
        property bool focused: false
        signal moved(real value)
        implicitHeight: Theme.px(28)
        readonly property real frac: to > from ? Math.max(0, Math.min(1, (value - from) / (to - from))) : 0
        Accessible.role: Accessible.Slider
        Accessible.name: label
        Accessible.onIncreaseAction: sl.moved(Math.min(sl.to, sl.value + sl.stepSize))
        Accessible.onDecreaseAction: sl.moved(Math.max(sl.from, sl.value - sl.stepSize))
        function at(px) {
            const f = Math.max(0, Math.min(1, (px - knob.width / 2) / (width - knob.width)))
            let v = from + f * (to - from)
            v = Math.round(v / stepSize) * stepSize
            moved(Math.max(from, Math.min(to, v)))
        }
        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: parent.width - knob.width; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.line }
        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: (parent.width - knob.width) * sl.frac; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.accent }
        Rectangle {
            id: knob
            anchors.verticalCenter: parent.verticalCenter
            x: (sl.width - width) * sl.frac
            width: Theme.px(18); height: width; radius: Theme.seasonal ? width / 2 : Theme.radiusSmall
            color: slHover.hovered || sl.focused ? Theme.accent : Theme.textSoft
            border.width: sl.focused ? Theme.focusWidth : 0
            border.color: Theme.text
        }
        HoverHandler { id: slHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: point => sl.at(point.position.x) }
        DragHandler {
            target: null
            onCentroidChanged: if (active) sl.at(centroid.position.x)
        }
    }
}
