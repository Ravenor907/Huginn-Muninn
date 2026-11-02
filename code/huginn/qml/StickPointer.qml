import QtQuick
import Huginn

// The mouse pointer on the right stick. RB or a right-stick click (R3) is a left click and RT a
// right click: real mouse events at its tip, so buttons, rows and hover tools answer it just as
// they answer a mouse. It hides after a few seconds still, and as soon as a real mouse moves.
Item {
    id: pointer
    property var host: null              // the window it moves and clicks in
    property bool shown: false
    property bool injecting: false       // one of its own events is on its way (Main's HoverHandler skips those)
    property real lastX: -1              // where its last event went
    property real lastY: -1
    property real boost: 1
    property bool placed: false          // it has been somewhere: it comes back there
    property bool l3Down: false
    property bool r3Click: false         // R3 is down on its own; with L3 it's the panic lock, not a click
    signal clicked(real x, real y)

    // No size of its own: the arrow is drawn outside the box, so it never gets in the way of a
    // click or of finding the list under it.
    width: 0
    height: 0
    visible: shown

    readonly property real dead: 0.15

    function send(button, down) {
        if (!host) return
        injecting = true
        Gamepad.pointerButton(host, x, y, button, down)
        injecting = false
        lastX = x; lastY = y
        if (down) clicked(x, y)
    }
    function moveTo(px, py) {
        if (!host) return
        x = Math.max(0, Math.min(host.width - 1, px))
        y = Math.max(0, Math.min(host.height - 1, py))
        injecting = true
        Gamepad.pointerMove(host, x, y)
        injecting = false
        lastX = x; lastY = y
    }
    function show() {
        if (!host) return
        if (!placed) { x = host.width / 2; y = host.height / 2; placed = true }
        shown = true
        moveTo(x, y)
        idle.restart()
    }
    // still: hide it after a while untouched, and take the hover off whatever was under it.
    // A real mouse moving hides it too, but that mouse now owns the hover.
    function hide(still) {
        if (!shown) return
        shown = false
        idle.stop()
        boost = 1
        if (still && host) {
            injecting = true
            Gamepad.pointerMove(host, -10, -10)
            injecting = false
            lastX = -10; lastY = -10
        }
    }
    // A real mouse event at the point its own last event went is only Qt repeating that one.
    function isOwn(px, py) { return injecting || (Math.abs(px - lastX) < 2 && Math.abs(py - lastY) < 2) }

    function press(b) {
        if (b === "ls") { l3Down = true; r3Click = false; return }
        if (b === "rs") { r3Click = !l3Down; return }
        // Hidden: the first press only shows where it is.
        if (!shown) { show(); return }
        idle.stop()
        send(b === "rt" ? 2 : 1, true)
    }
    function release(b) {
        if (b === "ls") { l3Down = false; return }
        if (b === "rs") {
            // R3 clicks on release, so holding it with L3 (panic lock) never clicks.
            if (r3Click) { if (shown) { send(1, true); send(1, false) } else show() }
            r3Click = false
            idle.restart()
            return
        }
        send(b === "rt" ? 2 : 1, false)
        if (shown) idle.restart()
    }

    Timer {
        id: idle
        interval: 5000
        onTriggered: pointer.hide(true)
    }
    // The right stick: slow near the middle for aiming, quick at the edge, and quicker still
    // while it's held all the way over.
    Timer {
        interval: System.batterySaving ? 33 : 16   // Battery saver: 30 a second
        repeat: true
        // Only while Huginn is in front (a game's stick moves never reach the pointer).
        running: pointer.host !== null && Qt.application.state === Qt.ApplicationActive && Math.hypot(Gamepad.rightX, Gamepad.rightY) > pointer.dead
        onTriggered: {
            const rx = Gamepad.rightX, ry = Gamepad.rightY
            const m = Math.hypot(rx, ry)
            if (m <= pointer.dead) return
            const t = Math.min(1, (m - pointer.dead) / (1 - pointer.dead))
            pointer.boost = t > 0.9 ? Math.min(2.4, pointer.boost + 0.03) : 1
            const step = Theme.px(1000) * t * t * pointer.boost * interval / 1000
            if (!pointer.shown) pointer.show()
            pointer.moveTo(pointer.x + rx / m * step, pointer.y + ry / m * step)
            idle.restart()
        }
        onRunningChanged: if (!running) pointer.boost = 1
    }

    // The arrow, its tip on the point.
    Canvas {
        id: arrow
        width: Theme.px(26)
        height: width * 1.3
        onWidthChanged: requestPaint()
        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            const k = width / 14
            ctx.beginPath()
            ctx.moveTo(1 * k, 1 * k)
            ctx.lineTo(1 * k, 16 * k)
            ctx.lineTo(5 * k, 12.5 * k)
            ctx.lineTo(8 * k, 18 * k)
            ctx.lineTo(10.5 * k, 17 * k)
            ctx.lineTo(7.8 * k, 11.5 * k)
            ctx.lineTo(12.5 * k, 11.5 * k)
            ctx.closePath()
            ctx.fillStyle = "#F4F1EA"
            ctx.strokeStyle = "#101112"
            ctx.lineWidth = Math.max(1.5, k * 1.1)
            ctx.lineJoin = "round"
            ctx.fill()
            ctx.stroke()
        }
    }
}
