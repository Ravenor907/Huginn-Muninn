import QtQuick
import Huginn

// The 9-dot pattern, like Android's, in Huginn's colors. Draw with a finger or the mouse, or with
// the controller or the arrow keys: move the ring to a dot, A (Enter) starts there, moving on picks
// up each dot the ring reaches, A again is done and B starts over.
// Passing straight over a dot in between picks it up too, as on Android.
// Settings › Privacy and lock › Hide the pattern as I draw: the line and the lit dots stay hidden;
// each dot gives a short pulse as it's picked up, so you know it registered.
Item {
    id: pad
    property bool error: false
    property bool enabled: true
    property var dots: []
    property int cursor: 4
    readonly property bool trail: Settings.patternTrail
    property int pulse: -1            // the dot pulsing now (the trail hidden)
    Timer { id: pulseOff; interval: 180; onTriggered: { pad.pulse = -1; canvas.requestPaint() } }
    property bool drawing: false      // a controller pattern is under way
    property bool keyed: false        // the D-pad or arrow keys have been used, so the ring shows
    property point finger: Qt.point(-1, -1)
    signal done(var dots)

    implicitWidth: Theme.px(300)
    implicitHeight: implicitWidth

    function center(i) { return Qt.point(width * ((i % 3) * 2 + 1) / 6, height * (Math.floor(i / 3) * 2 + 1) / 6) }
    function hit(x, y) {
        for (let i = 0; i < 9; i++) {
            const c = center(i)
            if (Math.hypot(c.x - x, c.y - y) < width / 9) return i
        }
        return -1
    }
    function add(i) {
        if (i < 0 || dots.indexOf(i) >= 0) return
        const d = dots.slice()
        if (d.length) {
            const last = d[d.length - 1]
            const r1 = Math.floor(last / 3), c1 = last % 3, r2 = Math.floor(i / 3), c2 = i % 3
            if ((r1 + r2) % 2 === 0 && (c1 + c2) % 2 === 0) {
                const mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2
                if (d.indexOf(mid) < 0) d.push(mid)
            }
        }
        d.push(i)
        dots = d
        if (!trail) { pulse = i; pulseOff.restart() }
        canvas.requestPaint()
    }
    function finish() {
        const d = dots
        dots = []
        drawing = false
        finger = Qt.point(-1, -1)
        canvas.requestPaint()
        if (d.length) done(d)
    }
    function clear() { dots = []; drawing = false; canvas.requestPaint() }
    // A wrong pattern: a short shake, as on the phone.
    function shake() { if (Theme.motion) shaking.restart() }

    function move(to) {
        cursor = to
        if (drawing) add(to)
    }
    // B is only used here while a pattern is under way; otherwise the lock gets it (Back).
    function handleButton(b) {
        if (!enabled) return false
        switch (b) {
        case "up": move(cursor >= 3 ? cursor - 3 : cursor); break
        case "down": move(cursor < 6 ? cursor + 3 : cursor); break
        case "left": move(cursor % 3 ? cursor - 1 : cursor); break
        case "right": move(cursor % 3 < 2 ? cursor + 1 : cursor); break
        case "a":
            if (drawing) finish()
            else { dots = []; drawing = true; add(cursor) }
            break
        case "b":
            if (!drawing) return false
            clear()
            break
        default: return false
        }
        keyed = true
        canvas.requestPaint()
        return true
    }
    onErrorChanged: canvas.requestPaint()
    onTrailChanged: canvas.requestPaint()
    onEnabledChanged: if (!enabled) clear()

    transform: Translate { id: nudge }
    SequentialAnimation {
        id: shaking
        NumberAnimation { target: nudge; property: "x"; to: -Theme.px(12); duration: 50 }
        NumberAnimation { target: nudge; property: "x"; to: Theme.px(12); duration: 80 }
        NumberAnimation { target: nudge; property: "x"; to: -Theme.px(8); duration: 70 }
        NumberAnimation { target: nudge; property: "x"; to: Theme.px(4); duration: 60 }
        NumberAnimation { target: nudge; property: "x"; to: 0; duration: 50 }
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            const col = pad.error ? Theme.danger : Theme.accent
            ctx.strokeStyle = col
            ctx.lineWidth = width / 40
            ctx.lineCap = "round"
            if (pad.dots.length && pad.trail) {
                ctx.beginPath()
                const s = pad.center(pad.dots[0])
                ctx.moveTo(s.x, s.y)
                for (let k = 1; k < pad.dots.length; k++) { const c = pad.center(pad.dots[k]); ctx.lineTo(c.x, c.y) }
                if (pad.finger.x >= 0) ctx.lineTo(pad.finger.x, pad.finger.y)
                ctx.stroke()
            }
            for (let i = 0; i < 9; i++) {
                const c = pad.center(i)
                const on = pad.trail && pad.dots.indexOf(i) >= 0
                const pulsing = !pad.trail && i === pad.pulse
                ctx.fillStyle = on ? col : Theme.textFaint
                ctx.beginPath()
                ctx.arc(c.x, c.y, width / (on ? 22 : 34), 0, Math.PI * 2)
                ctx.fill()
                if (pulsing) {
                    // A short pulse: a soft ring that goes again at once.
                    ctx.strokeStyle = Theme.accent
                    ctx.globalAlpha = 0.6
                    ctx.lineWidth = Math.max(2, width / 100)
                    ctx.beginPath()
                    ctx.arc(c.x, c.y, width / 20, 0, Math.PI * 2)
                    ctx.stroke()
                    ctx.globalAlpha = 1
                    ctx.strokeStyle = col
                    ctx.lineWidth = width / 40
                }
                if (i === pad.cursor && (Gamepad.connected || pad.keyed) && pad.enabled) {
                    // The controller's cursor: a ring around the dot it's on.
                    ctx.strokeStyle = Theme.accent
                    ctx.lineWidth = Math.max(2, width / 120)
                    ctx.beginPath()
                    ctx.arc(c.x, c.y, width / 14, 0, Math.PI * 2)
                    ctx.stroke()
                    ctx.strokeStyle = col
                    ctx.lineWidth = width / 40
                }
            }
        }
    }
    MouseArea {
        anchors.fill: parent
        enabled: pad.enabled
        // A scrolling page around the pad must not take the stroke away mid-draw.
        preventStealing: true
        onPressed: m => { pad.drawing = false; pad.dots = []; pad.finger = Qt.point(m.x, m.y); pad.add(pad.hit(m.x, m.y)); canvas.requestPaint() }
        onPositionChanged: m => { pad.finger = Qt.point(m.x, m.y); pad.add(pad.hit(m.x, m.y)); canvas.requestPaint() }
        onReleased: pad.finish()
        onCanceled: { pad.dots = []; pad.finger = Qt.point(-1, -1); canvas.requestPaint() }
    }
}
