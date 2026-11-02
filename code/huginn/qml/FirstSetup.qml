import QtQuick
import Huginn

// First setup on a fresh install, like Muninn's (muninn/.../screens/SetupFlow.kt and FirstSetup.kt): Welcome, the lock
// (on unless skipped), your name and picture, Link your phone, Add people, a quick tour of the real screen, then Done
// with Start a chat. Every page can be skipped, and what is already set is never asked again or saved over. It picks
// up again at the next start until it is done. Labs › Replay first setup (practice) shows every page and saves nothing,
// with a Practice ribbon across the top. The D-pad moves between the buttons, A presses one, B goes back.
Item {
    id: setup
    visible: false
    anchors.fill: parent

    property bool practice: false     // nothing is saved: Labs practice, or the screenshot tour
    property bool ribbon: false       // Labs practice: the ribbon with Leave
    property var steps: []
    property int index: 0
    readonly property string step: steps[index] || ""
    readonly property bool touring: step === "tour"
    property int stop: 0
    property int focusIndex: 0
    property bool codeShown: false
    property int tick: 0
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    readonly property string sample: "PRACTICE-SAMPLE-CODE"

    // The stops of the tour, one sentence each. spot: what Main.tourSpot() finds on the real screen.
    readonly property var stops: [
        { spot: "groups", text: setup.pad ? "The rail: Chats, then your groups. LB steps through them." : "The rail: Chats, then your groups." },
        { spot: "chats", text: "Your chats are listed here." },
        { spot: "add", text: "The + adds someone or shows your code." },
        { spot: "chats", text: setup.pad ? "LT opens the options for what's picked." : "Right-click a chat or a message for its options." },
        { spot: "attach", text: "In a chat, the clip beside the message box sends a file." },
        { spot: "card", text: setup.pad ? "Your card: mute, deafen and your profile." : "Your card: mute, deafen and your profile. Ctrl+Shift+M mutes." },
        { spot: "settings", text: setup.pad ? "The valknut opens Settings. Menu does too." : "The valknut opens Settings." }
    ]
    readonly property var spot: touring && Ui.main ? (tick, width, height, Ui.main.tourSpot(stops[Math.min(stop, stops.length - 1)].spot)) : null
    Timer { interval: 300; repeat: true; running: setup.visible && setup.touring; onTriggered: setup.tick++ }

    // The buttons on show, in the order the controller steps through them: [{ id, label }].
    readonly property var order: {
        const lockSet = step === "lock" && Vault.exists && !practice
        switch (step) {
        case "welcome": return [{ id: "restore", label: "Restore from a backup" }, { id: "skipAll", label: "Skip setup" }, { id: "next", label: "Start" }]
        case "lock": return lockSet ? [{ id: "back", label: "Back" }, { id: "next", label: "Next" }] : [{ id: "back", label: "Back" }, { id: "skipLock", label: "Skip" }]
        case "name": return [{ id: "nameField", label: "Type" }, { id: "picture", label: "Add a picture" }, { id: "back", label: "Back" }, { id: "next", label: "Skip" }, { id: "saveName", label: "Next" }]
        case "link": return [{ id: "linkNow", label: "Show the code" }, { id: "back", label: "Back" }, { id: "next", label: "Skip" }]
        case "people": return (codeShown ? [{ id: "copyLink", label: "Copy link" }] : [{ id: "showCode", label: "Show my code" }])
                               .concat([{ id: "pasteField", label: "Type" }, { id: "addCode", label: "Add" }, { id: "back", label: "Back" }, { id: "next", label: "Later" }, { id: "next2", label: "Next" }])
        case "tour": return [{ id: "skipTour", label: "Skip tour" }, { id: "nextStop", label: stop < stops.length - 1 ? "Next" : "Got it" }]
        case "done": return [{ id: "back", label: "Back" }, { id: "finish", label: ribbon ? "Back to Labs" : "Start a chat" }]
        }
        return []
    }
    readonly property int focusAt: Math.max(0, Math.min(focusIndex, order.length - 1))
    readonly property string focusId: order.length ? order[focusAt].id : ""
    function lit(id) { return pad && focusId === id }
    // Where the focus starts on each page: its main button.
    function primaryIndex() {
        const ids = order.map(o => o.id)
        for (const p of ["next", "saveName", "linkNow", "showCode", "nextStop", "finish", "skipLock"])
            if (ids.indexOf(p) >= 0 && (p !== "next" || step === "welcome" || step === "lock")) return ids.indexOf(p)
        return 0
    }

    readonly property var prompts: {
        if (step === "lock" && !(Vault.exists && !practice))
            return [{ key: "x", label: "Skip" }, { key: "b", label: "Back" }]
        const a = order.length ? order[focusAt].label : ""
        const b = index > 0 ? (touring && stop > 0 ? "Previous" : "Back") : ribbon ? "Leave" : ""
        return [{ key: "dpad", label: "Choose" }, { key: "a", label: a }].concat(b !== "" ? [{ key: "b", label: b }] : [])
    }

    // isPractice: Labs practice. shotStep and shotStop: the screenshot tour's page and tour stop (practice, no ribbon).
    function open(isPractice, shotStep, shotStop) {
        practice = !!isPractice || !!shotStep
        ribbon = !!isPractice && !shotStep
        const s = ["welcome"]
        if (practice || !Vault.exists) s.push("lock")
        s.push("name")
        if (practice || (!Linked.phone.key && Linked.companions.length === 0)) s.push("link")
        s.push("people", "tour", "done")
        steps = s
        index = shotStep ? Math.max(0, s.indexOf(shotStep)) : 0
        stop = shotStop || 0
        codeShown = false
        nameInput.text = practice ? (Ui.myName || "Raven") : Chat.username
        pasteInput.text = ""
        visible = true
        if (!practice && Settings.setupState !== "started") Settings.setupState = "started"
        enter()
    }
    function enter() {
        focusIndex = primaryIndex()
        // The tour is about the Chats screen.
        if (touring && Ui.main && Ui.main.pageName !== "dms") Ui.main.openPage("dms", {})
        if (Ui.main) Ui.main.restoreFocus()
    }
    function next() {
        if (index < steps.length - 1) { index++; stop = 0; enter() }
    }
    function back() {
        if (touring && stop > 0) { stop--; return }
        if (index > 0) { index--; if (touring) stop = stops.length - 1; enter() }
        else if (ribbon) finish(false)
    }
    // Done (or Skip setup, or Leave): a real run is marked done; practice goes back to Labs.
    function finish(startChat) {
        const real = !practice, labs = ribbon
        if (picker.visible) picker.close()
        visible = false
        practice = false; ribbon = false; steps = []; index = 0
        if (real) Settings.setupState = "done"
        if (!Ui.main) return
        if (labs) Ui.main.openPage("settings", { section: "about" })
        else if (real) {
            Ui.main.openPage("dms", {})
            if (startChat) Qt.callLater(() => { const p = Ui.main.page(); if (p && p.addMenu) p.addMenu() })
        }
        Ui.main.restoreFocus()
    }
    function skipLock() {
        if (practice) { next(); return }
        Ui.sure("Skip the lock?", "Without a lock, anyone who can use this computer can open Huginn and read your chats. Set one any time in Settings › Privacy and lock.",
                "Skip", () => {
            if (Vault.exists || Vault.startWithoutLock()) setup.next()
            else Ui.say("That didn't save. Try again.", true)
        }, false, true)
    }
    function saveName() {
        const t = nameInput.text.trim()
        if (t === "") { Ui.say("Type a name, or Skip.", true); return }
        if (!practice && t !== Chat.username) Chat.username = t
        next()
    }
    function linkNow() {
        if (!Ui.main) return
        if (practice) Ui.main.showLinkPhone(true, true)
        else Ui.main.showLinkPhone(true, false)
    }
    function showCode() {
        if (!practice) Chat.newLink()
        codeShown = true
        focusIndex = 0
    }
    function copyLink() {
        if (practice) { Ui.say("Practice: this copies your link."); return }
        Chat.copyLink()
        Ui.say("Link copied.")
    }
    function addCode() {
        const t = pasteInput.text.trim()
        if (t === "") { pasteInput.forceActiveFocus(); return }
        if (practice) { Ui.say("Practice: their code would add them here."); pasteInput.text = ""; return }
        if (t.indexOf("MUNINNG1:") >= 0 || (Chat.parseGroupCode(t) || {}).name) { Ui.say("That's a group invite. Open it from + in Chats.", true); return }
        const c = Chat.parseCode(t)
        if (!c.key) { Ui.say("That code didn't work.", true); return }
        if (c.mine) { Ui.say("That's your own code.", true); return }
        if (c.expired) { Ui.say("That code expired. Ask for a new one.", true); return }
        if (Chat.replaces(t).key) { Ui.say("You know someone by that name or inbox. Add them from + in Chats.", true); return }
        Chat.addContact(t)
        PeopleActions.added(t, c.key)
        Ui.say(c.name + " added.")
        pasteInput.text = ""
    }
    function run(id) {
        switch (id) {
        case "next": case "next2": next(); break
        case "back": back(); break
        case "skipAll": finish(false); break
        // A backup from Huginn or Muninn instead of starting fresh (practice: nothing is opened).
        case "restore": if (Ui.main) Ui.main.openBackup("restore", true, practice); break
        case "skipLock": skipLock(); break
        case "nameField": nameInput.forceActiveFocus(); System.showSteamKeyboard(); break
        case "picture": if (practice) Ui.say("Practice: your picture stays as it is."); else picker.open(); break
        case "saveName": saveName(); break
        case "linkNow": linkNow(); break
        case "showCode": showCode(); break
        case "copyLink": copyLink(); break
        case "pasteField": pasteInput.forceActiveFocus(); System.showSteamKeyboard(); break
        case "addCode": addCode(); break
        case "skipTour": next(); break
        case "nextStop": if (stop < stops.length - 1) stop++; else next(); break
        case "finish": finish(!ribbon); break
        }
    }
    function handleButton(b) {
        if (picker.visible) return picker.handleButton(b)
        if (step === "lock" && !(Vault.exists && !practice)) {
            if (b === "x") { skipLock(); return true }
            if (lockPanel.handleButton(b)) return true
            return true
        }
        if (b === "left" || b === "up") focusIndex = Math.max(0, focusAt - 1)
        else if (b === "right" || b === "down") focusIndex = Math.min(order.length - 1, focusAt + 1)
        else if (b === "a") run(focusId)
        else if (b === "b") back()
        return true
    }

    // ------------------------------------------------------------- the pages
    Rectangle {
        anchors.fill: parent
        visible: !setup.touring
        color: Qt.rgba(Theme.ground.r, Theme.ground.g, Theme.ground.b, 0.97)
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
    }

    Rectangle {
        id: card
        visible: !setup.touring
        anchors.horizontalCenter: parent.horizontalCenter
        y: ribbonBar.visible ? ribbonBar.height + (parent.height - ribbonBar.height - height) / 2 : (parent.height - height) / 2
        width: Math.min(parent.width - Theme.px(32), Theme.px(560))
        height: Math.min(parent.height - Theme.px(32) - (ribbonBar.visible ? ribbonBar.height : 0), setup.pageHeight + foot.height + Theme.px(48))
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        Accessible.role: Accessible.Dialog
        Accessible.name: "First setup"

        Flickable {
            id: body
            x: Theme.px(24); y: Theme.px(24)
            width: parent.width - Theme.px(48)
            height: Math.max(0, foot.y - y - Theme.px(8))
            contentWidth: width
            contentHeight: setup.step === "lock" ? height : setup.pageHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds

            // Welcome: the raven and one line about Huginn.
            Column {
                id: welcomePage
                visible: setup.step === "welcome"
                width: body.width
                spacing: Theme.px(12)
                topPadding: Theme.px(12)
                Image {
                    anchors.horizontalCenter: parent.horizontalCenter
                    source: "assets/raven-gold.png"
                    width: Theme.px(96); height: width
                    sourceSize.width: 256; sourceSize.height: 256
                }
                Title { text: "WELCOME TO HUGINN" }
                Line { text: "Private messages, calls and files with family. No phone number, no account." }
                Line { text: "Have a backup from Huginn or Muninn?"; topPadding: Theme.px(8) }
                HButton { anchors.horizontalCenter: parent.horizontalCenter; text: "Restore from a backup"; focused: setup.lit("restore"); onClicked: setup.run("restore") }
            }

            // The lock: the usual lock screen. Skip asks first.
            Item {
                id: lockPage
                visible: setup.step === "lock"
                width: body.width
                height: body.height
                LockPanel {
                    id: lockPanel
                    visible: !(Vault.exists && !setup.practice)
                    anchors.fill: parent
                    mode: "setup"
                    practice: setup.practice
                    onFinished: setup.next()
                    onCancelled: setup.back()
                }
                Column {
                    visible: !lockPanel.visible
                    width: parent.width
                    spacing: Theme.px(10)
                    topPadding: Theme.px(12)
                    Title { text: "PROTECT HUGINN" }
                    Line { text: Vault.hasLock ? "Your lock is set." : "No lock. Set one any time in Settings › Privacy and lock." }
                }
            }

            // Your name and picture.
            Column {
                id: namePage
                visible: setup.step === "name"
                width: body.width
                spacing: Theme.px(12)
                topPadding: Theme.px(12)
                Title { text: "WHAT SHOULD PEOPLE CALL YOU?" }
                Line { text: "Not your real one. Change it any time in Settings › You." }
                Field { id: nameInput; focusId: "nameField"; placeholder: "A name"; onEnter: setup.saveName() }
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: Theme.px(14)
                    Avatar { anchors.verticalCenter: parent.verticalCenter; key: setup.practice ? "" : "me"; name: nameInput.text || "?"; size: Theme.px(48) }
                    HButton { anchors.verticalCenter: parent.verticalCenter; text: "Add a picture"; focused: setup.lit("picture"); onClicked: setup.run("picture") }
                }
            }

            // Link your phone: the usual Link your phone screen opens over this one.
            Column {
                id: linkPage
                visible: setup.step === "link"
                width: body.width
                spacing: Theme.px(12)
                topPadding: Theme.px(12)
                Title { text: "LINK YOUR PHONE" }
                Line { text: Linked.phone.key && !setup.practice ? "Your phone is linked." : "Your phone's Muninn chats, here too. Optional." }
                Line { text: "In Muninn: Settings › Linked devices › Scan to link." }
                HButton { anchors.horizontalCenter: parent.horizontalCenter; text: "Show the code"; kind: "primary"; focused: setup.lit("linkNow"); onClicked: setup.run("linkNow") }
            }

            // Add people: my code to share, or paste theirs.
            Column {
                id: peoplePage
                visible: setup.step === "people"
                width: body.width
                spacing: Theme.px(12)
                topPadding: Theme.px(12)
                Title { text: "ADD PEOPLE" }
                Line { text: "Both of you need Huginn or Muninn. Share your code, or paste theirs." }
                Rectangle {
                    visible: setup.codeShown
                    anchors.horizontalCenter: parent.horizontalCenter
                    // The same light paper the code is drawn on in every look, so it always scans.
                    width: Math.min(parent.width, Theme.px(200)); height: width
                    color: "#E4E1DA"
                    Image {
                        anchors.fill: parent; anchors.margins: Theme.px(10)
                        smooth: false
                        opacity: setup.practice ? 0.35 : 1
                        source: setup.codeShown ? "image://qr/" + encodeURIComponent(setup.practice ? setup.sample : Chat.myLink) : ""
                    }
                    Rectangle {
                        visible: setup.practice
                        anchors.centerIn: parent
                        width: practiceMark.implicitWidth + Theme.px(24); height: practiceMark.implicitHeight + Theme.px(10)
                        color: Theme.accent
                        Text {
                            id: practiceMark
                            textFormat: Text.PlainText
                            anchors.centerIn: parent
                            text: "PRACTICE"
                            color: Theme.onAccent
                            font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
                        }
                    }
                }
                Line {
                    visible: setup.codeShown
                    text: setup.practice ? "A sample code. It adds no one." : "Scan to add me. Works for 1 hour."
                }
                HButton {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: setup.codeShown ? "Copy link" : "Show my code"
                    kind: "accent"
                    focused: setup.lit(setup.codeShown ? "copyLink" : "showCode")
                    onClicked: setup.run(setup.codeShown ? "copyLink" : "showCode")
                }
                Field { id: pasteInput; focusId: "pasteField"; placeholder: "Paste their code or link"; onEnter: setup.addCode() }
                HButton { anchors.horizontalCenter: parent.horizontalCenter; text: "Add"; focused: setup.lit("addCode"); onClicked: setup.run("addCode") }
            }

            // Done.
            Column {
                id: donePage
                visible: setup.step === "done"
                width: body.width
                spacing: Theme.px(12)
                topPadding: Theme.px(12)
                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Theme.px(64); height: width
                    color: Theme.accentFill
                    border.width: 2; border.color: Theme.accent
                    Icon { anchors.centerIn: parent; name: "check"; size: Theme.px(32); lineWidth: 2.2; color: Theme.accent }
                }
                Title { text: "YOU'RE SET" }
                Line { text: "Messages disappear after 7 days, on every device." }
                Line { visible: setup.ribbon; text: "Practice ends here and goes back to Labs." }
            }
        }

        // Progress (one mark a page; the tour has none of its own), then the page's buttons.
        Column {
            id: foot
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            x: Theme.px(24)
            width: parent.width - Theme.px(48)
            spacing: Theme.px(12)
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.px(6)
                Accessible.role: Accessible.StaticText
                Accessible.name: "Step " + (setup.dot + 1) + " of " + setup.dots
                Repeater {
                    model: setup.dots
                    Rectangle {
                        required property int index
                        width: index === setup.dot ? Theme.px(18) : Theme.px(8); height: Theme.px(8)
                        color: index === setup.dot ? Theme.accent : Theme.line
                    }
                }
            }
            Row {
                anchors.right: parent.right
                spacing: Theme.px(8)
                Repeater {
                    model: setup.order.filter(o => setup.footIds.indexOf(o.id) >= 0)
                    HButton {
                        required property var modelData
                        text: modelData.label
                        kind: modelData.id === "next" && setup.step !== "welcome" && setup.step !== "lock" ? "normal"
                            : ["next", "next2", "saveName", "finish"].indexOf(modelData.id) >= 0 ? "primary" : "plain"
                        focused: setup.lit(modelData.id)
                        onClicked: setup.run(modelData.id)
                    }
                }
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                visible: text !== ""
                text: setup.step === "lock" && lockPanel.visible ? "" : setup.pad ? "D-pad moves. A picks. B goes back." : ""
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
    }
    readonly property var footIds: ["skipAll", "back", "skipLock", "next", "next2", "saveName", "finish"]
    readonly property real pageHeight: step === "welcome" ? welcomePage.implicitHeight : step === "lock" ? Theme.px(540)
                                     : step === "name" ? namePage.implicitHeight : step === "link" ? linkPage.implicitHeight
                                     : step === "people" ? peoplePage.implicitHeight : step === "done" ? donePage.implicitHeight : 0
    // The progress marks: every page but the tour.
    readonly property int dots: steps.filter(s => s !== "tour").length
    readonly property int dot: steps.slice(0, index + 1).filter(s => s !== "tour").length - 1

    // ------------------------------------------------------------- the tour, over the real screen
    Item {
        id: tourLayer
        visible: setup.touring
        anchors.fill: parent
        readonly property real margin: Theme.px(6)
        readonly property var r: setup.spot ? { "x": setup.spot.x - margin, "y": setup.spot.y - margin, "w": setup.spot.width + margin * 2, "h": setup.spot.height + margin * 2 }
                                            : { "x": 0, "y": 0, "w": 0, "h": 0 }
        readonly property color dim: Qt.rgba(0, 0, 0, 0.62)
        // Nothing under the tour can be clicked.
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        // Everything dimmed but the spot.
        Rectangle { x: 0; y: 0; width: parent.width; height: setup.spot ? Math.max(0, tourLayer.r.y) : parent.height; color: tourLayer.dim }
        Rectangle {
            visible: !!setup.spot
            x: 0; y: Math.max(0, tourLayer.r.y + tourLayer.r.h)
            width: parent.width; height: Math.max(0, parent.height - y)
            color: tourLayer.dim
        }
        Rectangle {
            visible: !!setup.spot
            x: 0; y: Math.max(0, tourLayer.r.y)
            width: Math.max(0, tourLayer.r.x); height: Math.max(0, tourLayer.r.h)
            color: tourLayer.dim
        }
        Rectangle {
            visible: !!setup.spot
            x: Math.max(0, tourLayer.r.x + tourLayer.r.w); y: Math.max(0, tourLayer.r.y)
            width: Math.max(0, parent.width - x); height: Math.max(0, tourLayer.r.h)
            color: tourLayer.dim
        }
        Rectangle {
            visible: !!setup.spot
            x: tourLayer.r.x; y: tourLayer.r.y; width: tourLayer.r.w; height: tourLayer.r.h
            color: "transparent"
            border.width: Math.max(2, Theme.px(2)); border.color: Theme.accent
        }
        // The stop's sentence beside the spot (to its right when there's room), with Skip tour and Next.
        Rectangle {
            id: stopCard
            readonly property real gap: Theme.px(16)
            readonly property real topRoom: ribbonBar.visible ? ribbonBar.height + gap : gap
            width: Math.min(parent.width - Theme.px(32), Theme.px(420))
            height: stopCol.implicitHeight + Theme.px(32)
            x: !setup.spot ? (parent.width - width) / 2
             : tourLayer.r.x + tourLayer.r.w + gap + width <= parent.width - gap ? tourLayer.r.x + tourLayer.r.w + gap
             : Math.max(gap, tourLayer.r.x - gap - width)
            y: !setup.spot ? (parent.height - height) / 2
             : Math.max(topRoom, Math.min(parent.height - height - gap, tourLayer.r.y + tourLayer.r.h / 2 - height / 2))
            radius: Theme.radius
            color: Theme.panel
            border.width: 1; border.color: Theme.accent
            Accessible.role: Accessible.Dialog
            Accessible.name: "Tour"
            Column {
                id: stopCol
                x: Theme.px(16); y: Theme.px(16)
                width: parent.width - Theme.px(32)
                spacing: Theme.px(10)
                Text {
                    textFormat: Text.PlainText
                    text: (setup.stop + 1) + " of " + setup.stops.length
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: setup.touring ? setup.stops[Math.min(setup.stop, setup.stops.length - 1)].text : ""
                    color: Theme.text
                    font.family: Theme.body; font.pixelSize: Theme.fs(16)
                }
                Row {
                    anchors.right: parent.right
                    spacing: Theme.px(8)
                    HButton { text: "Skip tour"; kind: "plain"; focused: setup.lit("skipTour"); onClicked: setup.run("skipTour") }
                    HButton { text: setup.stop < setup.stops.length - 1 ? "Next" : "Got it"; kind: "primary"; focused: setup.lit("nextStop"); onClicked: setup.run("nextStop") }
                }
            }
        }
    }

    // ------------------------------------------------------------- practice
    Rectangle {
        id: ribbonBar
        visible: setup.ribbon
        anchors.top: parent.top
        width: parent.width
        height: Math.max(Theme.px(32), ribbonText.implicitHeight + Theme.px(12))
        color: Theme.accent
        Accessible.role: Accessible.StaticText
        Accessible.name: "Practice. Nothing is saved."
        Text {
            id: ribbonText
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
            anchors.verticalCenter: parent.verticalCenter
            text: "PRACTICE · NOTHING IS SAVED"
            color: Theme.onAccent
            font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
        }
        Text {
            textFormat: Text.PlainText
            anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
            anchors.verticalCenter: parent.verticalCenter
            text: "Leave"
            color: Theme.onAccent
            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
            Accessible.role: Accessible.Button
            Accessible.name: "Leave practice"
            Accessible.onPressAction: setup.finish(false)
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: setup.finish(false) }
        }
    }

    // Your picture (the name page's Add a picture).
    PicturePicker { id: picker; z: 5 }

    component Title: Text {
        textFormat: Text.PlainText
        width: parent ? parent.width : 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        color: Theme.text
        font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
    }
    component Line: Text {
        textFormat: Text.PlainText
        width: parent ? parent.width : 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        color: Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(15)
    }
    // A text box: Enter does the page's job, Esc leaves it.
    component Field: Rectangle {
        id: field
        property alias text: input.text
        property string placeholder
        property string focusId
        signal enter()
        function forceActiveFocus() { input.forceActiveFocus() }
        width: parent ? Math.min(parent.width, Theme.px(360)) : 0
        anchors.horizontalCenter: parent ? parent.horizontalCenter : undefined
        height: Math.max(Theme.px(40), input.implicitHeight + Theme.px(16))
        radius: Theme.radius
        color: Theme.groundDeep
        border.width: setup.lit(field.focusId) || input.activeFocus ? Theme.focusWidth : 1
        border.color: input.activeFocus ? Theme.accent : setup.lit(field.focusId) ? Theme.text : Theme.line
        TextInput {
            id: input
            FieldMenu {}   // right click: Cut, Copy, Paste, Select all
            anchors.fill: parent; anchors.leftMargin: Theme.px(10); anchors.rightMargin: Theme.px(10)
            verticalAlignment: TextInput.AlignVCenter
            color: Theme.text
            selectionColor: Theme.accentFill
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
            maximumLength: 400
            clip: true
            Keys.onReturnPressed: field.enter()
            Keys.onEnterPressed: field.enter()
            Keys.onEscapePressed: { if (Ui.main) Ui.main.restoreFocus() }
        }
        Text {
            textFormat: Text.PlainText
            visible: input.text.length === 0
            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            text: field.placeholder
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        TapHandler { onTapped: input.forceActiveFocus() }
    }
}
