import QtQuick
import Huginn

// Labs › Performance check: Run check measures about 25 seconds (standing still, scrolling a long list, the
// Seasonal animation or a test animation, then 5 seconds idle) and gives a short verdict with the main suspect, the
// idle processor use and what kept Huginn busiest. Try settings keeps a
// display mode, drawing, render loop or animations choice for the next start (Restart uses it, Undo all goes back
// to Automatic); Size changes at once. The report shows here before Copy report or Send with Report a bug. Up and
// Down move, Left and Right change a choice, A uses it, B closes (or cancels a check under way).
Item {
    id: panel
    visible: false
    anchors.fill: parent
    property bool tour: false
    property int at: 0
    property string reportText: ""
    readonly property bool padFocus: !!Ui.main && Ui.main.padInUse
    // The Seasonal animation is moving: the check's third part measures it instead of the test animation.
    readonly property bool seasonalMoving: Theme.seasonal && Settings.seasonAnimation !== "off" && Theme.motion

    // The tour shows sample results: nothing is measured.
    readonly property var samplePhases: [
        { name: "Still", fps: 60, median: "16.7", worst: "18.9", spikes: "0.0", draw: "1.2", cpu: 9 },
        { name: "Scrolling", fps: 52, median: "16.8", worst: "41.6", spikes: "6.4", draw: "2.8", cpu: 61 },
        { name: "Animation", fps: 60, median: "16.7", worst: "20.1", spikes: "0.5", draw: "1.9", cpu: 24 }
    ]
    readonly property string sampleReport: "Huginn performance check\nHuginn: 1.1\nSystem: Linux\nGame mode: yes\nDisplay mode: xcb (X11)\n"
        + "Drawing: OpenGL, sample graphics chip\nSoftware drawing: no\nRender loop: basic\n"
        + "Screen: 1920 x 1080 px, 155 x 87 mm reported, 120 Hz\nHuginn fit: 2.10x, Size 100%, Text size 100%\n\n"
        + "Verdict: Spikes while scrolling\nMain suspect: Laying out the list keeps the processor busy"
    readonly property var phases: tour ? samplePhases : Perf.phases
    readonly property bool hasResult: tour || Perf.hasResult
    readonly property string verdict: tour ? "Spikes while scrolling" : Perf.verdict
    readonly property string suspect: tour ? "Laying out the list keeps the processor busy" : Perf.suspect
    readonly property bool running: !tour && Perf.running
    readonly property var over: tour ? ({ display: "x11", drawing: "auto", loop: "basic", animations: "on" }) : Perf.overrides
    readonly property bool overOn: tour || Perf.overridesOn
    readonly property bool restartNeeded: !tour && Perf.restartNeeded
    readonly property string inUse: tour ? "Display mode X11, Render loop Basic" : Perf.inUse
    readonly property string fellBack: tour ? "" : Perf.fellBack

    readonly property var displayOptions: [{ value: "auto", label: "Automatic" }, { value: "x11", label: "X11" }, { value: "wayland", label: "Wayland" }]
    readonly property var drawingOptions: [{ value: "auto", label: "Automatic" }, { value: "opengl", label: "OpenGL" }, { value: "vulkan", label: "Vulkan" },
                                           { value: "software", label: "Software" }]
    readonly property var loopOptions: [{ value: "auto", label: "Automatic" }, { value: "threaded", label: "Threaded" }, { value: "basic", label: "Basic" }]
    readonly property var animationOptions: [{ value: "on", label: "On" }, { value: "off", label: "Off" }]
    function optionsFor(k) { return k === "display" ? displayOptions : k === "drawing" ? drawingOptions : k === "loop" ? loopOptions : animationOptions }

    // What the controller can stop on, top to bottom.
    readonly property var items: {
        const out = []
        out.push("copyTop", "sendTop")
        if (fellBack !== "") out.push("fellBack")
        if (overOn) out.push("undo")
        if (restartNeeded) out.push("restart")
        out.push("run")
        if (Perf.onLinux) out.push("display")
        out.push("drawing", "loop", "animations", "size", "report", "copy", "send")
        return out
    }
    readonly property string cur: items[Math.max(0, Math.min(at, items.length - 1))]
    function lit(k) { return padFocus && !running && cur === k }
    onItemsChanged: if (at >= items.length) at = items.length - 1

    function open(asTour) {
        tour = !!asTour
        at = items.indexOf("run")
        view.contentY = 0
        refreshReport()
        visible = true
    }
    function close() {
        if (!tour) Perf.cancel()
        visible = false
        if (Ui.main) Ui.main.restoreFocus()
    }
    function refreshReport() { reportText = tour ? sampleReport : Perf.report({ look: Theme.mode }) }
    function run() { if (!tour) Perf.start(seasonalMoving) }
    function setOpt(k, v) { if (!tour) Perf.setOverride(k, v) }
    // A click or tap on a choice: the focus goes there too.
    function pick(k, v) { at = Math.max(0, items.indexOf(k)); setOpt(k, v) }
    // One choice left (-1) or right (1); A (0) goes round to the next.
    function cycle(k, dir) {
        const opts = optionsFor(k)
        let i = Math.max(0, opts.findIndex(o => o.value === over[k]))
        i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
        setOpt(k, opts[i].value)
    }
    function stepSize(dir) { if (!tour && Ui.main) Ui.main.stepSize(dir) }
    function undoAll() {
        if (tour) return
        Perf.undoAll()
        Ui.say("Back to Automatic from the next start.")
    }
    function restart() {
        if (tour) return
        if (!Perf.restart()) Ui.say("Couldn't restart. Close Huginn and open it again.", true)
    }
    function copy() {
        if (tour) return
        refreshReport()
        Perf.copy(reportText)
        Ui.say("Report copied.")
    }
    function send() {
        if (tour || !Ui.main) return
        Ui.main.openReportWith(Perf.shortReport({ look: Theme.mode }))
    }
    function activate() {
        switch (cur) {
        case "fellBack": if (!tour) Perf.dismissFellBack(); break
        case "undo": undoAll(); break
        case "restart": restart(); break
        case "run": run(); break
        case "display": case "drawing": case "loop": case "animations": cycle(cur, 0); break
        case "size": stepSize(0); break
        case "copy": case "copyTop": copy(); break
        case "send": case "sendTop": send(); break
        }
    }
    function itemFor(k) {
        return ({ fellBack: fellRow, undo: triedRow, restart: triedRow, run: runRow, display: rowDisplay, drawing: rowDrawing,
                  loop: rowLoop, animations: rowAnimations, size: rowSize, report: reportBox, copy: reportButtons, send: reportButtons, copyTop: topButtons, sendTop: topButtons })[k]
    }
    function reveal() {
        const it = itemFor(cur)
        if (!it || !it.visible) return
        const y = it.mapToItem(col, 0, 0).y
        if (y < view.contentY) view.contentY = Math.max(0, y)
        else if (y + it.height > view.contentY + view.height) view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), y + it.height - view.height)
    }
    onCurChanged: Qt.callLater(reveal)
    // On the report, Up and Down scroll it first, then move on.
    function scrollReport(dir) {
        const maxY = Math.max(0, reportView.contentHeight - reportView.height)
        if ((dir < 0 && reportView.contentY <= 0) || (dir > 0 && reportView.contentY >= maxY)) return false
        reportView.contentY = Math.max(0, Math.min(maxY, reportView.contentY + dir * Theme.px(60)))
        return true
    }
    function handleButton(b) {
        if (running) {
            if (b === "a" || b === "b") Perf.cancel()
            return true
        }
        const sideways = cur === "display" || cur === "drawing" || cur === "loop" || cur === "animations" || cur === "size"
        switch (b) {
        case "up": if (!(cur === "report" && scrollReport(-1))) at = Math.max(0, at - 1); break
        case "down": if (!(cur === "report" && scrollReport(1))) at = Math.min(items.length - 1, at + 1); break
        case "left":
            if (sideways) { if (cur === "size") stepSize(-1); else cycle(cur, -1) }
            else if (cur === "send") at = items.indexOf("copy")
            else if (cur === "restart" && overOn) at = items.indexOf("undo")
            break
        case "right":
            if (sideways) { if (cur === "size") stepSize(1); else cycle(cur, 1) }
            else if (cur === "copy") at = items.indexOf("send")
            else if (cur === "undo" && restartNeeded) at = items.indexOf("restart")
            break
        case "a": activate(); break
        case "b": close(); break
        }
        return true
    }
    Connections {
        target: Perf
        enabled: panel.visible && !panel.tour
        function onChanged() { if (!Perf.running) panel.refreshReport() }
        function onOverridesChanged() { panel.refreshReport() }
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: if (!panel.running) panel.close() }

    // A choice that needs a restart: its name, a few words and the choices.
    component OptRow: Rectangle {
        id: orow
        property string title
        property string note: ""
        property bool lit: false
        property var options: []
        property string value: ""
        signal picked(string value)
        width: parent ? parent.width : 0
        height: Math.max(otext.implicitHeight, oseg.implicitHeight) + Theme.px(16)
        radius: Theme.radius
        color: lit ? Theme.focusFill : "transparent"
        border.width: lit ? Theme.focusWidth : 0
        border.color: Theme.accent
        Column {
            id: otext
            x: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - oseg.width - Theme.px(30))
            spacing: Theme.px(2)
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                text: orow.title
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
            }
            Text {
                textFormat: Text.PlainText
                visible: orow.note !== ""
                width: parent.width
                wrapMode: Text.Wrap
                text: orow.note
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        Segmented {
            id: oseg
            anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(implicitWidth, Math.round(orow.width * 0.62))
            options: orow.options
            value: orow.value
            onPicked: v => orow.picked(v)
        }
    }
    component Heading: Text {
        textFormat: Text.PlainText
        color: Theme.section
        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.Bold; font.letterSpacing: Theme.px(1)
    }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(820))
        height: parent.height - Theme.px(64)
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Item {
            id: head
            x: Theme.px(24); y: Theme.px(20)
            // As tall as the title at any text size.
            width: parent.width - Theme.px(48); height: Math.max(Theme.px(28), title.implicitHeight)
            Text {
                textFormat: Text.PlainText
                id: title
                anchors.verticalCenter: parent.verticalCenter
                text: "PERFORMANCE CHECK"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Text {
                textFormat: Text.PlainText
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                width: Math.max(0, parent.width - title.implicitWidth - Theme.px(16))
                horizontalAlignment: Text.AlignRight
                elide: Text.ElideLeft
                text: panel.running ? "A or B cancel" : "A use, B close"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }

        // ------------------------------------------------ while the check runs
        Item {
            id: runArea
            visible: panel.running
            anchors.top: head.bottom; anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            Text {
                textFormat: Text.PlainText
                id: phaseText
                width: parent.width - cancel.width - Theme.px(12)
                elide: Text.ElideRight
                text: (Perf.phase === 0 ? "Standing still" : Perf.phase === 1 ? "Scrolling a long list"
                       : Perf.phase === 2 ? (panel.seasonalMoving ? "Seasonal animation" : "Test animation")
                       : Perf.phase === 3 ? "Idle" : "Measuring")
                      + "  ·  " + Math.round(Perf.progress * 100) + "%"
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
            }
            HButton {
                id: cancel
                anchors.right: parent.right
                anchors.verticalCenter: phaseText.verticalCenter
                text: "Cancel"
                focused: panel.padFocus
                onClicked: Perf.cancel()
            }
            Rectangle {
                id: bar
                anchors.top: cancel.bottom; anchors.topMargin: Theme.px(12)
                width: parent.width; height: Theme.px(8)
                radius: Theme.radiusSmall
                color: Theme.raised
                border.color: Theme.line
                Rectangle {
                    width: Math.round(parent.width * Math.max(0, Math.min(1, Perf.progress)))
                    height: parent.height
                    radius: Theme.radiusSmall
                    color: Theme.accent
                }
            }
            Text {
                textFormat: Text.PlainText
                id: keepStill
                anchors.top: bar.bottom; anchors.topMargin: Theme.px(8)
                width: parent.width
                wrapMode: Text.Wrap
                text: "Leave Huginn in front until it's done."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            // What is measured: a long list that scrolls by itself, then the test animation.
            Item {
                id: stage
                anchors.top: keepStill.bottom; anchors.topMargin: Theme.px(12)
                width: parent.width
                anchors.bottom: parent.bottom
                clip: true
                ListView {
                    id: list
                    anchors.fill: parent
                    visible: Perf.phase === 1
                    interactive: false
                    model: visible ? 400 : 0
                    delegate: Rectangle {
                        id: sampleRow
                        required property int index
                        width: ListView.view.width
                        height: Theme.px(56)
                        color: index % 2 ? Theme.raised : "transparent"
                        Rectangle {
                            x: Theme.px(10); anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(36); height: width
                            radius: Theme.radiusSmall
                            color: Theme.chatSwatches[1 + index % 7]
                        }
                        Column {
                            x: Theme.px(56); anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - Theme.px(66)
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; elide: Text.ElideRight
                                text: "Sample row " + (sampleRow.index + 1)
                                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; elide: Text.ElideRight
                                text: "A line of sample text, drawn while the list scrolls."
                                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                    }
                    SequentialAnimation {
                        running: panel.running && Perf.phase === 1
                        loops: Animation.Infinite
                        NumberAnimation { target: list; property: "contentY"; from: 0; to: Math.max(0, list.contentHeight - list.height); duration: 2800 }
                        NumberAnimation { target: list; property: "contentY"; to: 0; duration: 2800 }
                    }
                }
                Item {
                    id: shapes
                    anchors.fill: parent
                    visible: Perf.phase === 2 && !panel.seasonalMoving
                    Repeater {
                        model: shapes.visible ? 36 : 0
                        Rectangle {
                            id: shape
                            required property int index
                            width: Theme.px(18 + (index % 4) * 8); height: width
                            radius: Theme.radiusSmall
                            color: Theme.chatSwatches[1 + index % 7]
                            opacity: 0.85
                            y: (index * 37) % Math.max(1, shapes.height - height)
                            NumberAnimation on x {
                                running: shapes.visible
                                loops: Animation.Infinite
                                from: -shape.width; to: shapes.width
                                duration: 1800 + (shape.index % 6) * 350
                            }
                            RotationAnimation on rotation {
                                running: shapes.visible
                                loops: Animation.Infinite
                                from: 0; to: 360
                                duration: 1400 + (shape.index % 5) * 300
                            }
                        }
                    }
                }
                Text {
                    textFormat: Text.PlainText
                    anchors.centerIn: parent
                    width: parent.width
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    visible: Perf.phase === 0 || Perf.phase === 3 || (Perf.phase === 2 && panel.seasonalMoving)
                    text: Perf.phase === 0 ? "Measuring the screen as it is." : Perf.phase === 3 ? "Measuring Huginn at rest. Hands off."
                                                                                 : "Measuring the Seasonal animation."
                    color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }
        }

        // ------------------------------------------------ results, settings to try and the report
        Flickable {
            id: view
            visible: !panel.running
            anchors.top: head.bottom; anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: col
                width: view.width
                spacing: Theme.px(10)

                // Tried settings that kept the window from starting were dropped.
                Rectangle {
                    id: fellRow
                    visible: panel.fellBack !== ""
                    width: parent.width
                    height: Math.max(fellText.implicitHeight, fellOk.height) + Theme.px(20)
                    radius: Theme.radius
                    color: Theme.dangerFill
                    border.width: panel.lit("fellBack") ? Theme.focusWidth : 1
                    border.color: panel.lit("fellBack") ? Theme.accent : Theme.dangerLine
                    Text {
                        textFormat: Text.PlainText
                        id: fellText
                        x: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - fellOk.width - Theme.px(36)
                        wrapMode: Text.Wrap
                        text: "Huginn didn't start with " + panel.fellBack + ", so it's back to Automatic."
                        color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                    HButton {
                        id: fellOk
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        text: "OK"
                        focused: panel.lit("fellBack")
                        onClicked: if (!panel.tour) Perf.dismissFellBack()
                    }
                }
                // What this run started with, and Undo all and Restart.
                Rectangle {
                    id: triedRow
                    visible: panel.overOn || panel.restartNeeded
                    width: parent.width
                    height: Math.max(triedCol.implicitHeight, triedButtons.height) + Theme.px(20)
                    radius: Theme.radius
                    color: Theme.accentFill
                    border.color: Theme.accent
                    Column {
                        id: triedCol
                        x: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - triedButtons.width - Theme.px(36)
                        spacing: Theme.px(2)
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: "Tried settings in use: " + panel.inUse
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                        }
                        Text {
                            textFormat: Text.PlainText
                            visible: panel.restartNeeded
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: "Restart to use your new choices."
                            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                    }
                    Row {
                        id: triedButtons
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(8)
                        HButton { visible: panel.overOn; text: "Undo all"; focused: panel.lit("undo"); onClicked: panel.undoAll() }
                        HButton { visible: panel.restartNeeded; text: "Restart"; kind: "primary"; focused: panel.lit("restart"); onClicked: panel.restart() }
                    }
                }

                // The report, first thing: copy it to paste anywhere, or send it as a bug report.
                Row {
                    id: topButtons
                    spacing: Theme.px(8)
                    HButton { text: "Copy report"; focused: panel.lit("copyTop"); onClicked: { panel.at = panel.items.indexOf("copyTop"); panel.copy() } }
                    HButton { text: "Send report"; kind: "primary"; focused: panel.lit("sendTop"); onClicked: { panel.at = panel.items.indexOf("sendTop"); panel.send() } }
                }

                Heading { text: "CHECK" }
                Rectangle {
                    id: runRow
                    width: parent.width
                    height: Math.max(verdictCol.implicitHeight, runButton.height) + Theme.px(20)
                    radius: Theme.radius
                    color: Theme.raised
                    border.width: panel.lit("run") ? Theme.focusWidth : 1
                    border.color: panel.lit("run") ? Theme.accent : Theme.line
                    Column {
                        id: verdictCol
                        x: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - runButton.width - Theme.px(36)
                        spacing: Theme.px(4)
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: panel.hasResult ? panel.verdict : "Not checked yet"
                            color: panel.hasResult ? Theme.textBright : Theme.textSoft
                            font.family: Theme.body; font.pixelSize: Theme.fs(18); font.weight: Font.Bold
                        }
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: panel.hasResult ? "Main suspect: " + panel.suspect : "About 25 seconds. Measures smoothness and load."
                            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                    }
                    HButton {
                        id: runButton
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        text: panel.hasResult ? "Run again" : "Run check"
                        kind: "accent"
                        focused: panel.lit("run")
                        onClicked: { panel.at = panel.items.indexOf("run"); panel.run() }
                    }
                }
                // Each part's numbers.
                Column {
                    id: table
                    visible: panel.hasResult
                    width: parent.width
                    readonly property real nameW: Math.round(width * 0.28)
                    readonly property real cellW: (width - nameW) / 4
                    Repeater {
                        model: [{ name: "", fps: "fps", worst: "Worst ms", spikes: "Spikes %", cpu: "CPU %" }].concat(panel.phases)
                        Row {
                            id: trow
                            required property var modelData
                            required property int index
                            height: Theme.px(28)
                            Text {
                                textFormat: Text.PlainText
                                width: table.nameW; elide: Text.ElideRight
                                anchors.verticalCenter: parent.verticalCenter
                                text: trow.modelData.name
                                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                            }
                            Repeater {
                                model: ["fps", "worst", "spikes", "cpu"]
                                Text {
                                    id: cell
                                    required property string modelData
                                    textFormat: Text.PlainText
                                    width: table.cellW; elide: Text.ElideRight
                                    horizontalAlignment: Text.AlignRight
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: String(trow.modelData[cell.modelData])
                                    color: trow.index === 0 ? Theme.textMuted : Theme.text
                                    font.family: Theme.body; font.pixelSize: Theme.fs(trow.index === 0 ? 12 : 14)
                                }
                            }
                        }
                    }
                }
                // At rest, and what kept Huginn busiest.
                Text {
                    textFormat: Text.PlainText
                    visible: panel.hasResult && text !== ""
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: panel.tour ? "Idle CPU: 1% (0 frames drawn)\nBusiest: nothing measurable"
                                     : [Perf.idleLine, Perf.busiest, Perf.threadsLine].filter(t => t !== "").join("\n")
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }

                Heading { text: "TRY SETTINGS" }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: "Used from the next start. If Huginn can't start with one, it goes back to Automatic."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
                OptRow {
                    id: rowDisplay
                    visible: Perf.onLinux
                    title: "Display mode"
                    lit: panel.lit("display"); options: panel.displayOptions; value: panel.over.display
                    onPicked: v => panel.pick("display", v)
                }
                OptRow {
                    id: rowDrawing
                    title: "Drawing"; note: "OpenGL and Vulkan use the graphics chip"
                    lit: panel.lit("drawing"); options: panel.drawingOptions; value: panel.over.drawing
                    onPicked: v => panel.pick("drawing", v)
                }
                OptRow {
                    id: rowLoop
                    title: "Render loop"
                    lit: panel.lit("loop"); options: panel.loopOptions; value: panel.over.loop
                    onPicked: v => panel.pick("loop", v)
                }
                OptRow {
                    id: rowAnimations
                    title: "Animations"
                    lit: panel.lit("animations"); options: panel.animationOptions; value: panel.over.animations
                    onPicked: v => panel.pick("animations", v)
                }
                // Size: the same as Settings › Look › Size, and the panel shows it at once.
                Rectangle {
                    id: rowSize
                    readonly property bool lit: panel.lit("size")
                    width: parent.width
                    height: Math.max(sizeCol.implicitHeight, sizeButtons.height) + Theme.px(16)
                    radius: Theme.radius
                    color: lit ? Theme.focusFill : "transparent"
                    border.width: lit ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Column {
                        id: sizeCol
                        x: Theme.px(10); anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(0, parent.width - sizeButtons.width - Theme.px(30))
                        spacing: Theme.px(2)
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width; elide: Text.ElideRight
                            text: "Size: " + Settings.uiSize + "%"
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
                        }
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width; wrapMode: Text.Wrap
                            text: "Changes now. Fit for this screen: " + System.screenScale.toFixed(2) + "x"
                            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
                        }
                    }
                    Row {
                        id: sizeButtons
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(8)
                        HButton { text: "Smaller"; onClicked: { panel.at = panel.items.indexOf("size"); panel.stepSize(-1) } }
                        HButton { text: "Bigger"; onClicked: { panel.at = panel.items.indexOf("size"); panel.stepSize(1) } }
                    }
                }

                Heading { text: "REPORT" }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: "No names, messages, files or addresses."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
                Rectangle {
                    id: reportBox
                    width: parent.width
                    height: Theme.px(220)
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.width: panel.lit("report") ? Theme.focusWidth : 1
                    border.color: panel.lit("report") ? Theme.accent : Theme.lineMuted
                    Flickable {
                        id: reportView
                        anchors.fill: parent
                        anchors.margins: Theme.px(10)
                        contentHeight: reportBody.implicitHeight
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds
                        Text {
                            textFormat: Text.PlainText
                            id: reportBody
                            width: reportView.width
                            wrapMode: Text.WrapAnywhere
                            text: panel.reportText
                            color: Theme.textSoft
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                    }
                    TapHandler { onTapped: panel.at = panel.items.indexOf("report") }
                }
                Row {
                    id: reportButtons
                    anchors.right: parent.right
                    spacing: Theme.px(10)
                    HButton { text: "Copy report"; focused: panel.lit("copy"); onClicked: { panel.at = panel.items.indexOf("copy"); panel.copy() } }
                    HButton { text: "Send with Report a bug"; kind: "primary"; focused: panel.lit("send"); onClicked: { panel.at = panel.items.indexOf("send"); panel.send() } }
                }
            }
        }
    }
}
