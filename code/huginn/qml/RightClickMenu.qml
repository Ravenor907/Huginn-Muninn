import QtQuick
import Huginn

// The one right-click menu, like Discord's, for everything: a right click, a long press on a touch screen, the Menu key
// or Shift+F10 open it where the pointer (or the focus) is, inside the window and flipped at its edges. It shows what a
// menu function builds with Ui.pickFrom, so each menu is written once: here as a small menu, as the big list for a
// controller. A row with sub opens a side menu; a menu that opens another as its answer takes its place (Back returns).
// Up, Down, Right (into a side menu), Left, Enter and Esc work it, and so do the D-pad, A and B. Ui.menuAt opens it.
FocusScope {
    id: menu
    anchors.fill: parent
    z: 45
    visible: depth > 0

    // Up to three menus at once: the first, a side menu and one more. Each has its rows, its place and its current row.
    property int depth: 0
    property var e0: []
    property var e1: []
    property var e2: []
    property string t0: ""
    property int c0: -1
    property int c1: -1
    property int c2: -1
    property var a0: null      // where a menu goes: { x, y, alt, altY } (alt and altY: where it goes flipped)
    property var a1: null
    property var a2: null
    property int p1: -1        // the row a side menu came from
    property int p2: -1
    property var cbs: [null, null, null]
    property var backs: [null, null, null]
    // Keys or a controller are moving through it: the current row has a ring, and a new menu starts on its first row.
    property bool keyNav: false

    // While a menu function runs, Ui.pickFrom comes here (wants) instead of opening the big list.
    property bool capturing: false
    property int target: 0
    property bool got: false
    property var pendingAnchor: null
    // A menu opened a moment after a choice (a menu that comes back with the change made) shows in the same place.
    property real followUntil: 0
    property var followAnchor: null
    property Item lastFocus: null
    readonly property int margin: Theme.px(6)

    // ------------------------------------------------------------- what the rest of the app calls
    // build: a function that opens a menu with Ui.pickFrom. (x, y) in item (null: the window). Nothing to show: false.
    function openAt(item, x, y, build, viaKey) {
        if (typeof build !== "function") return false
        const p = item ? item.mapToItem(menu, x, y) : menu.mapFromItem(null, x, y)
        close()
        followAnchor = null
        keyNav = !!viaKey || (!!Ui.main && Ui.main.padInUse)
        pendingAnchor = { x: p.x, y: p.y, alt: p.x, altY: p.y }
        if (!run(0, build)) return false
        grab()
        return true
    }
    function wants() { return capturing || (followAnchor !== null && Date.now() < followUntil) }
    function take(title, sections, cb, back) {
        const list = flatten(sections, back)
        if (!list.length) return
        if (!capturing) {
            const a = followAnchor
            followAnchor = null; followUntil = 0
            depth = 0
            setLevel(0, title, list, cb, back, a)
            depth = 1
            grab()
            return
        }
        got = true
        setLevel(target, title, list, cb, back, pendingAnchor)
        depth = target + 1
    }
    function close() {
        followUntil = 0
        hide()
        e0 = []; e1 = []; e2 = []
        cbs = [null, null, null]; backs = [null, null, null]
    }

    // A text box: Cut, Copy, Paste and Select all. (x, y) in the box; x < 0: at its text cursor.
    function isField(f) { return !!f && typeof f.selectAll === "function" && typeof f.paste === "function" && f.selectedText !== undefined }
    function forField(f, x, y, viaKey) {
        if (!isField(f)) return false
        if (!f.activeFocus) f.forceActiveFocus(Qt.PopupFocusReason)
        const s = f.selectionStart, e = f.selectionEnd, has = s !== e
        const ro = !!f.readOnly
        // A password shows dots: it can't be copied or cut.
        const hidden = f.echoMode !== undefined && f.echoMode !== TextInput.Normal
        const empty = String(f.text || "").length === 0
        if (x < 0) { const r = f.cursorRectangle; x = r.x; y = r.y + r.height }
        const items = [{ name: "Cut", detail: "Ctrl+X", url: "cut", disabled: !has || ro || hidden },
                       { name: "Copy", detail: "Ctrl+C", url: "copy", disabled: !has || hidden },
                       { name: "Paste", detail: "Ctrl+V", url: "paste", disabled: ro || !f.canPaste },
                       { name: "Select all", detail: "Ctrl+A", url: "all", disabled: empty }]
        return openAt(f, x, y, () => Ui.pickFrom("", [{ header: "", items: items }], a => {
            // The box got its focus back as the menu closed; its selection comes back too.
            f.forceActiveFocus(Qt.PopupFocusReason)
            if (has) f.select(s, e)
            if (a === "cut") f.cut()
            else if (a === "copy") f.copy()
            else if (a === "paste") f.paste()
            else if (a === "all") f.selectAll()
        }), viaKey)
    }

    // The screenshot tour: a row's side menu, open, by the row's name.
    function openSubNamed(name) {
        const i = e0.findIndex(e => !!e && e.name === name)
        if (i < 0 || depth < 1) return
        setCur(0, i)
        openSub(0, i, false)
    }
    // A controller's buttons (Main.press sends them here while the menu is open).
    function handleButton(b) {
        const l = Math.max(0, depth - 1), i = curAt(l), e = entriesAt(l)[i]
        keyNav = true
        switch (b) {
        case "up": step(l, -1); break
        case "down": step(l, 1); break
        case "right": if (e && e.slider) nudge(l, i, 1); else if (e && e.sub) openSub(l, i, true); break
        case "left": if (e && e.slider) nudge(l, i, -1); else goOut(false); break
        case "a": if (selectable(e)) activate(l, i); else step(l, 1); break
        case "b": goOut(true); break
        }
        return true
    }

    // ------------------------------------------------------------- inside
    function entriesAt(l) { return l === 0 ? e0 : l === 1 ? e1 : e2 }
    function curAt(l) { return l === 0 ? c0 : l === 1 ? c1 : c2 }
    function setCur(l, i) { if (l === 0) c0 = i; else if (l === 1) c1 = i; else c2 = i }
    function anchorAt(l) { return l === 0 ? a0 : l === 1 ? a1 : a2 }
    function parentAt(l) { return l === 1 ? p1 : l === 2 ? p2 : -1 }
    function selectable(e) { return !!e && !e.header && !e.sep && !e.disabled }
    function firstRow(l) { return entriesAt(l).findIndex(e => selectable(e)) }
    // Sections become rows: a line between sections, a header when it has one, and Back first on a sub-page.
    function flatten(sections, back) {
        const out = []
        if (back) out.push({ name: "Back", icon: "back", isBack: true })
        const secs = sections || []
        for (let s = 0; s < secs.length; s++) {
            const items = (secs[s] && secs[s].items) || []
            if (!items.length) continue
            if (out.length) out.push({ sep: true })
            if (secs[s].header) out.push({ header: String(secs[s].header) })
            items.forEach(e => { if (e) out.push(e) })
        }
        return out
    }
    function setLevel(l, title, list, cb, back, anchor) {
        cbs[l] = cb || null
        backs[l] = back || null
        if (anchor) { if (l === 0) a0 = anchor; else if (l === 1) a1 = anchor; else a2 = anchor }
        if (l === 0) { t0 = title || ""; e0 = list } else if (l === 1) e1 = list; else e2 = list
        setCur(l, keyNav ? firstRow(l) : -1)
    }
    // Runs f with Ui.pickFrom caught for menu l: true when f opened a menu there.
    function run(l, f) {
        target = l; got = false; capturing = true
        try { f() } finally { capturing = false }
        return got
    }
    function grab() {
        if (menu.activeFocus) return
        lastFocus = Ui.main ? Ui.main.activeFocusItem : null
        menu.forceActiveFocus(Qt.PopupFocusReason)
    }
    // Out of sight, the focus back where it was (the rows stay, for a menu that takes this one's place).
    function hide() {
        subTimer.stop()
        // Read before it hides: a hidden item has lost its focus already.
        const f = lastFocus, had = menu.activeFocus
        lastFocus = null
        depth = 0
        // A popup's reason: a message box doesn't bring up Steam's keyboard for it (Composer).
        if (!had) return
        if (f && f.visible) f.forceActiveFocus(Qt.PopupFocusReason)
        else if (Ui.main) Ui.main.restoreFocus()
    }
    // A choice: f runs with the menu out of the way (a dialog it opens keeps the focus). A menu f opens at once takes
    // this one's place; one it opens a moment later shows here too.
    function runInto(l, f) {
        const a = anchorAt(l)
        hide()
        pendingAnchor = a
        if (run(l, f)) { grab(); return }
        close()
        followAnchor = a
        followUntil = Date.now() + 400
    }
    function activate(l, i) {
        const e = entriesAt(l)[i]
        if (!selectable(e) || e.slider) return
        if (e.isBack) { const b = backs[l]; if (b) runInto(l, b); return }
        if (e.sub) { openSub(l, i, true); return }
        const cb = cbs[l], url = e.url
        runInto(l, () => { if (cb) cb(url) })
    }
    function openSub(l, i, viaKey) {
        const e = entriesAt(l)[i]
        if (!e || !e.sub || l >= 2) return
        if (depth > l + 1 && parentAt(l + 1) === i) {
            if (viaKey) { keyNav = true; setCur(l + 1, firstRow(l + 1)) }
            return
        }
        depth = l + 1
        const panel = panels.itemAt(l), row = panel ? panel.rowAt(i) : null
        if (!panel || !row) return
        const r = row.mapToItem(menu, 0, 0), pad = Theme.px(5)
        pendingAnchor = { x: panel.x + panel.width - Theme.px(3), y: r.y - pad, alt: panel.x + Theme.px(3), altY: r.y + row.height + pad }
        if (l === 0) p1 = i; else p2 = i
        const was = keyNav
        keyNav = !!viaKey
        if (!run(l + 1, e.sub)) keyNav = was
    }
    // Back one menu (a side menu closes; a sub-page goes back with useBack), or closed.
    function goOut(useBack) {
        if (depth > 1) { depth = depth - 1; return }
        if (backs[0]) { runInto(0, backs[0]); return }
        if (useBack) close()
    }
    function step(l, dir) {
        const list = entriesAt(l), n = list.length
        if (!n) return
        let i = curAt(l)
        for (let k = 0; k < n; k++) {
            i = i < 0 ? (dir > 0 ? 0 : n - 1) : (i + dir + n) % n
            if (selectable(list[i])) { setCur(l, i); showRow(l, i); return }
        }
    }
    // A letter: the next row whose name starts with it.
    function jump(l, ch) {
        const list = entriesAt(l), n = list.length
        let i = curAt(l)
        for (let k = 0; k < n; k++) {
            i = (i + 1 + n) % n
            if (selectable(list[i]) && String(list[i].name || "").toLowerCase().indexOf(ch) === 0) { setCur(l, i); showRow(l, i); return }
        }
    }
    function showRow(l, i) { const p = panels.itemAt(l); if (p) p.show(i) }
    function nudge(l, i, dir) { const p = panels.itemAt(l), r = p ? p.rowAt(i) : null; if (r) r.nudge(dir) }
    // The pointer on a row: it's the current one, and after a moment its side menu opens (or another one closes).
    function pointAt(l, i) {
        keyNav = false
        setCur(l, selectable(entriesAt(l)[i]) ? i : -1)
        if (l > 0) setCur(l - 1, parentAt(l))
        if (l > 1) setCur(0, p1)
        subTimer.level = l
        subTimer.row = i
        subTimer.restart()
    }
    // Wide enough for its longest row, within limits (a longer one ends in "...").
    function widthFor(list, title) {
        let w = title ? smallFont.advanceWidth(String(title).toUpperCase()) * 1.15 + Theme.px(28) : 0
        for (let i = 0; i < list.length; i++) {
            const e = list[i]
            if (!e || e.sep) continue
            if (e.header) { w = Math.max(w, smallFont.advanceWidth(String(e.header).toUpperCase()) * 1.15 + Theme.px(28)); continue }
            let r = bigFont.advanceWidth(String(e.name || "") + (e.slider ? " 100%" : "")) + Theme.px(32)
            if (e.icon) r += Theme.px(26)
            if (e.detail && !e.slider) r += smallFont.advanceWidth(String(e.detail)) + Theme.px(18)
            if (e.sub || e.check) r += Theme.px(24)
            w = Math.max(w, r)
        }
        return Math.max(Theme.px(190), Math.min(Theme.px(340), menu.width - 2 * margin, Math.ceil(w)))
    }

    onWidthChanged: if (depth > 0) close()
    onHeightChanged: if (depth > 0) close()

    FontMetrics { id: bigFont; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.Medium }
    FontMetrics { id: smallFont; font.family: Theme.body; font.pixelSize: Theme.fs(12) }

    Timer {
        id: subTimer
        property int level: 0
        property int row: -1
        interval: 220
        onTriggered: {
            if (level >= menu.depth || menu.curAt(level) !== row) return
            const e = menu.entriesAt(level)[row]
            if (e && e.sub) menu.openSub(level, row, false)
            else if (menu.depth > level + 1) menu.depth = level + 1
        }
    }

    // Keys: Enter, Space and Return choose when let go, so the release doesn't reach what's under the menu.
    property int armed: 0
    Keys.onPressed: event => {
        event.accepted = true
        const l = Math.max(0, depth - 1), i = curAt(l), e = entriesAt(l)[i]
        switch (event.key) {
        case Qt.Key_Up: keyNav = true; step(l, -1); break
        case Qt.Key_Down: keyNav = true; step(l, 1); break
        case Qt.Key_Home: keyNav = true; setCur(l, -1); step(l, 1); break
        case Qt.Key_End: keyNav = true; setCur(l, -1); step(l, -1); break
        case Qt.Key_Right: keyNav = true; if (e && e.slider) nudge(l, i, 1); else if (e && e.sub) openSub(l, i, true); break
        case Qt.Key_Left: keyNav = true; if (e && e.slider) nudge(l, i, -1); else goOut(false); break
        case Qt.Key_Return: case Qt.Key_Enter: case Qt.Key_Space: if (!event.isAutoRepeat) armed = event.key; break
        case Qt.Key_Escape: goOut(true); break
        case Qt.Key_Tab: case Qt.Key_Backtab: close(); break
        default: {
            const ch = String(event.text || "").toLowerCase()
            if (ch.length === 1 && ch > " ") { keyNav = true; jump(l, ch) }
        }
        }
    }
    Keys.onReleased: event => {
        event.accepted = true
        if (event.isAutoRepeat || event.key !== armed) return
        armed = 0
        const l = Math.max(0, depth - 1), i = curAt(l)
        if (i >= 0) activate(l, i)
    }

    // A press outside closes it. A right click there goes on to what's under it (its own menu opens there).
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.AllButtons
        onPressed: mouse => {
            const right = mouse.button === Qt.RightButton
            menu.close()
            if (right) mouse.accepted = false
        }
        onWheel: wheel => { menu.close(); wheel.accepted = false }
    }

    Repeater {
        id: panels
        model: 3
        Rectangle {
            id: panel
            readonly property int level: index
            readonly property var list: level === 0 ? menu.e0 : level === 1 ? menu.e1 : menu.e2
            readonly property var place: level === 0 ? menu.a0 : level === 1 ? menu.a1 : menu.a2
            readonly property string title: level === 0 ? menu.t0 : ""
            readonly property int cur: level === 0 ? menu.c0 : level === 1 ? menu.c1 : menu.c2
            readonly property int pad: Theme.px(5)
            function rowAt(i) { return rows.itemAt(i) }
            function show(i) {
                const r = rows.itemAt(i)
                if (!r) return
                if (r.y < flick.contentY) flick.contentY = r.y
                else if (r.y + r.height > flick.contentY + flick.height) flick.contentY = r.y + r.height - flick.height
            }
            visible: level < menu.depth && list.length > 0 && !!place
            width: menu.widthFor(list, title)
            height: Math.min(menu.height - 2 * menu.margin, body.implicitHeight + 2 * pad)
            x: {
                const a = panel.place
                if (!a) return 0
                let v = a.x
                if (v + width > menu.width - menu.margin) v = a.alt - width
                return Math.max(menu.margin, Math.min(v, menu.width - width - menu.margin))
            }
            y: {
                const a = panel.place
                if (!a) return 0
                let v = a.y
                if (v + height > menu.height - menu.margin) v = a.altY - height
                return Math.max(menu.margin, Math.min(v, menu.height - height - menu.margin))
            }
            radius: Theme.radius
            color: Theme.panel
            border.color: Theme.line
            border.width: 1
            Accessible.role: Accessible.PopupMenu
            Accessible.name: title !== "" ? title : "Menu"
            // A click between rows stays here.
            MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

            Flickable {
                id: flick
                anchors.fill: parent
                anchors.margins: panel.pad
                contentWidth: width
                contentHeight: body.implicitHeight
                interactive: contentHeight > height + 1
                clip: interactive
                boundsBehavior: Flickable.StopAtBounds
                Column {
                    id: body
                    width: flick.width
                    Text {
                        textFormat: Text.PlainText
                        visible: panel.title !== ""
                        width: parent.width
                        leftPadding: Theme.px(8); rightPadding: Theme.px(8)
                        topPadding: Theme.px(4); bottomPadding: Theme.px(4)
                        elide: Text.ElideRight
                        text: panel.title.toUpperCase()
                        color: Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
                    }
                    Repeater {
                        id: rows
                        model: panel.list
                        Item {
                            id: row
                            // The menu's own row (a model's copy of it may not keep its functions, like sub).
                            readonly property var e: panel.list[index] || ({})
                            readonly property bool isHead: !!e.header
                            readonly property bool isSep: !!e.sep
                            readonly property bool off: !!e.disabled
                            readonly property bool on: !isHead && !isSep && !off && panel.cur === index
                            // A side menu open from this row keeps it lit.
                            readonly property bool open: !!e.sub && menu.depth > panel.level + 1 && menu.parentAt(panel.level + 1) === index
                            readonly property var prev: index > 0 ? panel.list[index - 1] : null
                            // The first red row gets a line above it, unless a section line is already there.
                            readonly property bool firstDanger: !!e.danger && !!prev && !prev.danger && !prev.sep && !prev.header
                            readonly property int lineH: Math.max(Theme.px(32), label.implicitHeight + Theme.px(10))
                            property real value: e.value !== undefined ? Number(e.value) : (e.from !== undefined ? Number(e.from) : 0)
                            readonly property real lo: e.from !== undefined ? Number(e.from) : 0
                            readonly property real hi: e.to !== undefined ? Number(e.to) : 100
                            readonly property real stepBy: e.step ? Number(e.step) : 1
                            function set(v) {
                                const n = Math.max(lo, Math.min(hi, Math.round(v / stepBy) * stepBy))
                                if (n === value) return
                                value = n
                                // From the menu's own rows: a model's copy may not keep functions.
                                const src = menu.entriesAt(panel.level)[index]
                                if (src && typeof src.moved === "function") src.moved(n)
                            }
                            function nudge(dir) { set(value + dir * stepBy) }
                            width: body.width
                            height: isSep ? Theme.px(9) : isHead ? headText.implicitHeight + Theme.px(8)
                                  : (firstDanger ? Theme.px(9) : 0) + lineH + (e.slider ? Theme.px(26) : 0)

                            Rectangle {
                                visible: row.isSep || row.firstDanger
                                x: Theme.px(6); y: Theme.px(4)
                                width: parent.width - Theme.px(12); height: 1
                                color: Theme.line
                            }
                            Text {
                                id: headText
                                textFormat: Text.PlainText
                                visible: row.isHead
                                anchors.bottom: parent.bottom
                                width: parent.width
                                leftPadding: Theme.px(8); rightPadding: Theme.px(8); bottomPadding: Theme.px(3)
                                wrapMode: Text.WordWrap
                                text: row.isHead ? String(row.e.header).toUpperCase() : ""
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
                            }
                            Rectangle {
                                id: tile
                                visible: !row.isHead && !row.isSep
                                anchors.fill: parent
                                anchors.topMargin: row.firstDanger ? Theme.px(9) : 0
                                radius: Theme.radiusSmall
                                color: row.on || row.open ? (row.e.danger ? Qt.rgba(Theme.danger.r, Theme.danger.g, Theme.danger.b, 0.16) : Theme.tile) : "transparent"
                                border.width: row.on && menu.keyNav ? Theme.focusWidth : 0
                                border.color: Theme.accent
                                opacity: row.off ? 0.45 : 1
                                Accessible.role: Accessible.MenuItem
                                Accessible.name: String(row.e.name || "") + (row.e.detail && !row.e.slider ? ", " + row.e.detail : "")
                                                 + (row.e.check ? ", on" : "") + (row.e.sub ? ", more" : "") + (row.off ? ", not available" : "")
                                Accessible.onPressAction: menu.activate(panel.level, index)
                                Item {
                                    id: line
                                    width: parent.width
                                    height: row.lineH
                                    Icon {
                                        id: rowIcon
                                        visible: !!row.e.icon
                                        x: Theme.px(8)
                                        anchors.verticalCenter: parent.verticalCenter
                                        name: row.e.icon || "file"
                                        size: Theme.px(16)
                                        color: row.e.danger ? Theme.danger : row.on ? Theme.text : Theme.textMuted
                                    }
                                    Icon {
                                        id: endIcon
                                        visible: !!row.e.sub || !!row.e.check
                                        anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
                                        anchors.verticalCenter: parent.verticalCenter
                                        name: row.e.sub ? "down" : "check"
                                        rotation: row.e.sub ? -90 : 0
                                        size: Theme.px(row.e.sub ? 14 : 16)
                                        lineWidth: 2
                                        color: row.e.sub ? Theme.textMuted : Theme.accent
                                    }
                                    Text {
                                        id: detailText
                                        textFormat: Text.PlainText
                                        visible: !!row.e.detail && !row.e.slider
                                        anchors.right: endIcon.visible ? endIcon.left : parent.right; anchors.rightMargin: Theme.px(8)
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: Math.min(implicitWidth, line.width * 0.45)
                                        elide: Text.ElideRight
                                        text: row.e.detail && !row.e.slider ? String(row.e.detail) : ""
                                        color: Theme.textMuted
                                        font.family: Theme.body; font.pixelSize: Theme.fs(12)
                                    }
                                    Text {
                                        id: label
                                        textFormat: Text.PlainText
                                        anchors.left: rowIcon.visible ? rowIcon.right : parent.left
                                        anchors.leftMargin: Theme.px(rowIcon.visible ? 10 : 8)
                                        anchors.right: detailText.visible ? detailText.left : endIcon.visible ? endIcon.left : parent.right
                                        anchors.rightMargin: Theme.px(8)
                                        anchors.verticalCenter: parent.verticalCenter
                                        elide: Text.ElideRight
                                        text: String(row.e.name || "") + (row.e.slider ? " " + row.value + String(row.e.unit || "") : "")
                                        color: row.e.danger ? Theme.danger : Theme.text
                                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.Medium
                                    }
                                }
                                // A slider row (a volume): drag or click along it, or Left and Right.
                                Item {
                                    id: slide
                                    visible: !!row.e.slider
                                    anchors.top: line.bottom
                                    x: Theme.px(10)
                                    width: parent.width - Theme.px(20)
                                    height: visible ? Theme.px(22) : 0
                                    Accessible.role: Accessible.Slider
                                    Accessible.name: String(row.e.name || "")
                                    Rectangle {
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: parent.width; height: Theme.px(4); radius: height / 2
                                        color: Theme.line
                                        Rectangle {
                                            width: parent.width * (row.value - row.lo) / Math.max(1, row.hi - row.lo)
                                            height: parent.height; radius: parent.radius
                                            color: Theme.accent
                                        }
                                    }
                                    Rectangle {
                                        width: Theme.px(14); height: width; radius: width / 2
                                        anchors.verticalCenter: parent.verticalCenter
                                        x: (parent.width - width) * (row.value - row.lo) / Math.max(1, row.hi - row.lo)
                                        color: Theme.accent
                                    }
                                    MouseArea {
                                        anchors.fill: parent
                                        enabled: slide.visible
                                        preventStealing: true
                                        onPressed: mouse => { menu.setCur(panel.level, index); row.set(row.lo + (row.hi - row.lo) * Math.max(0, Math.min(1, mouse.x / width))) }
                                        onPositionChanged: mouse => { if (pressed) row.set(row.lo + (row.hi - row.lo) * Math.max(0, Math.min(1, mouse.x / width))) }
                                    }
                                }
                                HoverHandler {
                                    cursorShape: row.off ? Qt.ArrowCursor : Qt.PointingHandCursor
                                    onHoveredChanged: if (hovered) menu.pointAt(panel.level, index)
                                }
                                TapHandler { onTapped: menu.activate(panel.level, index) }
                            }
                        }
                    }
                }
            }
        }
    }
}
