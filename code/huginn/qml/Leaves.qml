import QtQuick
import QtQuick.Window
import Huginn

// The Seasonal look's ground (Autumn's warm bark, Snow's night blue, or the background picked on the
// Look page) and what falls behind the frosted panes. One engine for both seasons; kind only picks
// the shapes. Autumn: three leaf shapes (maple, oak and a serrated elm) in seven colors, and maple
// seeds. Snow: six-armed crystals and soft round flakes, white and a little see-through, in the
// leaves' and seeds' places. Each is drawn once on a small Canvas, then moved by a 30 fps timer: they
// glide sideways as they fall, rock and tumble, flip edge-on and back, and lean into a gust now and
// then; the seeds (and the flakes in their place) spin and orbit as they drop. Nothing is allocated
// per frame. The timer stops while the window is hidden or minimized, and with Less motion on
// everything lies still, scattered across the ground.
// Settings › Look: Animation (Off draws nothing, Light about half as many), How many (25% to 200% of
// about 18 leaves and 5 seeds on a 1600x900 window, scaled by the window's area) and size (50% to 200%).
Item {
    id: leaves
    visible: Theme.seasonal
    // What falls: "leaves" (Autumn) or "snow".
    readonly property string kind: Theme.snow ? "snow" : "leaves"
    // Settings › Look › Animation: Off 0, Light about half, Full all.
    readonly property real amount: Settings.seasonAnimation === "off" ? 0 : Settings.seasonAnimation === "light" ? 0.5 : 1

    readonly property real sizeScale: (Number(Settings.leafSize) || 100) / 100 * Theme.s
    readonly property real density: (Number(Settings.leafCount) || 100) / 100
    readonly property real area: Math.max(0.2, (width * height) / (1600 * 900))
    readonly property int nLeaves: amount > 0 ? Math.min(140, Math.max(4, Math.round(18 * area * density * amount))) : 0
    readonly property int nSeeds: amount > 0 ? Math.min(40, Math.max(1, Math.round(5 * area * density * amount))) : 0
    readonly property bool winActive: Window.active
    readonly property bool onScreen: visible && Window.visibility !== Window.Hidden && Window.visibility !== Window.Minimized
    readonly property bool moving: onScreen && Theme.motion && amount > 0

    // Where each leaf starts and how it moves. The same index always gets the same leaf, so the
    // sliders add or take away leaves without reshuffling the rest.
    readonly property var plan: {
        const out = []
        for (let i = 0; i < nLeaves; ++i) out.push(make(i, "leaf"))
        for (let i = 0; i < nSeeds; ++i) out.push(make(1000 + i, "seed"))
        return out
    }
    // A small, repeatable random source per leaf.
    function make(i, kind) {
        let s = (i * 2654435761 + 97) >>> 0
        const r = function() { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296 }
        const depth = r() < 0.2 ? 0 : r() < 0.55 ? 1 : 2
        if (kind === "seed")
            return { kind: "seed", sym: 15 + Math.floor(r() * 6), x0: r(), speed: 0.062 + r() * 0.016, sway: 12, swayT: 4 + r(),
                     w: 20 + r() * 5, rps: (1.8 + r() * 0.7) * (r() < 0.5 ? -1 : 1), phase: r() * 100, dir: r() < 0.5 ? -1 : 1,
                     orbit: 10 + r() * 7, orbitT: 3.2 + r() * 0.8, op: 0.9, wx: 0, lastFall: -1 }
        const w = depth === 0 ? 17 + r() * 5 : depth === 1 ? 22 + r() * 8 : 30 + r() * 10
        return { kind: "leaf", sym: Math.floor(r() * 15), x0: r(), depth: depth,
                 speed: depth === 0 ? 0.03 + r() * 0.012 : depth === 1 ? 0.048 + r() * 0.016 : 0.068 + r() * 0.024,
                 sway: w * (0.6 + r() * 0.6), swayT: 2.6 + r() * 2.2, w: w, spin: (4 + r() * 26) * (r() < 0.5 ? -1 : 1),
                 flipT: 1.9 + r() * 1.3, phase: r() * 100, dir: r() < 0.5 ? -1 : 1, glide: 0.5 + r() * 0.75,
                 tumbleT: 2.7 + r() * 2.8, zig: 0.65 + r() * 0.75, op: depth === 0 ? 0.6 : depth === 1 ? 0.85 : 1, wx: 0, lastFall: -1 }
    }

    // The ground: the season's (or the picked background), a little lighter near the top.
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: Theme.groundTop }
            GradientStop { position: 0.45; color: Theme.groundMid }
            GradientStop { position: 1.0; color: Theme.groundDeep }
        }
    }

    // The clock. Date.now() costs nothing to read and never allocates.
    property real t0: Date.now()
    readonly property real tau: Math.PI * 2
    // A gust everyone feels: 0 most of the time, rising to 1 for a few seconds now and then.
    function gustAt(t) {
        const g = Math.sin(t * 0.21) * Math.sin(t * 0.07 + 1.3)
        return g > 0 ? g * g : 0
    }
    Timer {
        interval: leaves.winActive ? 33 : 66
        repeat: true
        running: leaves.moving
        onTriggered: leaves.step((Date.now() - leaves.t0) / 1000, interval / 1000)
    }
    function step(t, dt) {
        const g = gustAt(t)
        const n = rep.count
        for (let i = 0; i < n; ++i) { const it = rep.itemAt(i); if (it) it.place(t, dt, g) }
    }
    // Less motion, or nothing drawn yet: everything in its place for one moment, no wind.
    function settle() { if (!moving) step(37.3, 0) }
    onMovingChanged: settle()
    onPlanChanged: Qt.callLater(settle)
    onKindChanged: Qt.callLater(settle)
    onWidthChanged: Qt.callLater(settle)
    onHeightChanged: Qt.callLater(settle)

    Repeater {
        id: rep
        model: leaves.visible ? leaves.plan.length : 0
        delegate: Item {
            id: leaf
            required property int index
            readonly property var p: leaves.plan[index] || ({})
            // The drawn size: the leaf's own width times the slider. The canvas is drawn at that size,
            // so it stays crisp; the slider repaints it once, not every frame.
            readonly property int side: Math.max(8, Math.ceil((p.w || 24) * leaves.sizeScale))
            width: side; height: side
            opacity: p.op || 1
            // The stem's base, where the leaf turns; a snowflake turns about its middle.
            readonly property real ox: side * (leaves.kind === "snow" ? 0.5 : 0.25)
            readonly property real oy: side * 0.5
            transform: [
                Scale { id: flipT; origin.x: leaf.ox; origin.y: leaf.oy; xScale: 1 },
                Rotation { id: rotT; origin.x: leaf.ox; origin.y: leaf.oy; angle: 0 }
            ]
            Component.onCompleted: place(37.3, 0, 0)

            function place(t, dt, gust) {
                const q = p
                if (!q.kind) return
                const W = leaves.width, H = leaves.height, ss = leaves.sizeScale, tau = leaves.tau
                const tt = t + q.phase
                const fall = ((tt * q.speed) % 1.25) - 0.12
                // Back at the top: the wind's push starts over.
                if (fall < q.lastFall) q.wx = 0
                q.lastFall = fall
                let x = q.x0 * W + Math.sin(tt / q.swayT * tau) * q.sway * ss + Math.sin(tt / (q.swayT * 2.7) * tau) * q.sway * 0.4 * ss
                let y = fall * H
                let rot, flip
                if (q.kind === "seed") {
                    rot = (tt * q.rps * 360) % 360
                    flip = 1
                    x += Math.cos(tt / q.orbitT * tau) * q.orbit * ss
                    y += Math.sin(tt / q.orbitT * tau) * q.orbit * 0.5 * ss
                    q.wx += gust * 70 * ss * dt
                } else {
                    // Rock with the sway, a slow tumble each way, and a lean into the gust.
                    rot = q.spin * Math.sin(tt / q.swayT * tau) + Math.sin(tt * 0.6) * 10
                        + q.dir * 18 * Math.sin(tt / q.tumbleT * tau) + gust * 28 * q.dir
                    flip = 0.55 + 0.45 * Math.cos(tt / q.flipT * tau)
                    // Gliding sideways as it falls, and pushed along by the gust.
                    x += q.dir * q.glide * q.zig * (fall + 0.12) * H * 0.16
                    q.wx += gust * q.zig * 110 * ss * dt
                }
                x += q.wx
                // Off one side comes back in on the other.
                const span = W + side * 2
                x = ((x + side) % span + span) % span - side
                leaf.x = x - ox
                leaf.y = y - oy
                rotT.angle = rot
                flipT.xScale = flip
            }

            Canvas {
                id: cv
                anchors.fill: parent
                renderTarget: Canvas.Image
                renderStrategy: Canvas.Immediate
                onWidthChanged: requestPaint()
                readonly property int sym: leaf.p.sym === undefined ? 0 : leaf.p.sym
                onSymChanged: requestPaint()
                readonly property string kind: leaves.kind
                onKindChanged: requestPaint()
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()
                    ctx.clearRect(0, 0, width, height)
                    // The shapes are drawn in a 48 by 48 box whose origin, the stem's base, sits at (12, 24).
                    const k = width / 48
                    ctx.translate(12 * k, 24 * k)
                    ctx.scale(k, k)
                    ctx.lineJoin = "round"; ctx.lineCap = "round"
                    if (kind === "snow") leaves.drawFlake(ctx, sym)
                    else if (sym >= 15) leaves.drawSeed(ctx, sym - 15)
                    else leaves.drawLeaf(ctx, sym)
                }
            }
        }
    }

    // ------------------------------------------------------------- the shapes
    // Fifteen leaves: shape (maple, oak, elm) and color (light to dark of the gradient, which is also the vein color).
    readonly property var leafShape: [0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2]
    readonly property var leafTone: [0, 0, 1, 2, 3, 1, 0, 1, 0, 4, 5, 2, 6, 2, 6]
    readonly property var tones: [
        ["#f7d994", "#e8b45a", "#9a6a22"], ["#f6c384", "#e59a4c", "#8d4f1c"], ["#e88a5e", "#c45a2e", "#6e2c12"],
        ["#f0a26c", "#d6763a", "#7f3a16"], ["#dd7a56", "#b9452a", "#5e2010"], ["#cf9a55", "#a86a2a", "#5a3512"],
        ["#f2d57a", "#d9a93f", "#8c6a1d"]
    ]
    readonly property var seedTones: [
        ["#6a4a22", "#b89a5c", "#e9d9a8"], ["#6a4a22", "#b89a5c", "#e9d9a8"], ["#7a5a2a", "#c9a86a", "#e9d9a8"],
        ["#7a5a2a", "#c9a86a", "#e9d9a8"], ["#8a6a33", "#d4b67a", "#e9d9a8"], ["#7a5a2a", "#c9a86a", "#e9d9a8"]
    ]
    // The oak and elm outlines as points around the edge.
    readonly property var oak: [29.0, 0.0, 28.0, 1.8, 24.0, 4.3, 23.1, 5.4, 23.1, 6.7, 24.5, 10.6, 24.2, 12.4, 22.0, 13.1, 15.8, 12.5, 15.1, 13.3, 14.1, 17.0, 12.9, 17.9, 10.5, 16.6, 6.0, 11.2, 2.2, 14.7, 1.3, 15.0, 0.6, 14.6, 0.2, 12.0, 0.9, 5.7, 0.0, 0.0, 0.9, -5.7, 0.2, -12.0, 0.6, -14.6, 1.3, -15.0, 2.2, -14.7, 6.0, -11.2, 10.5, -16.6, 12.9, -17.9, 14.1, -17.0, 15.1, -13.3, 15.8, -12.5, 22.0, -13.1, 24.2, -12.4, 24.5, -10.6, 23.1, -6.7, 23.1, -5.4, 24.0, -4.3, 28.0, -1.8]
    readonly property var elm: [36.0, 0.0, 36.2, 1.5, 33.6, 3.1, 32.3, 4.9, 28.4, 5.6, 27.2, 6.8, 24.3, 6.7, 23.7, 7.9, 21.4, 7.5, 21.0, 8.7, 19.2, 8.0, 18.7, 9.2, 17.2, 8.2, 16.5, 9.5, 15.3, 8.4, 14.5, 9.6, 13.6, 8.4, 12.5, 9.6, 11.8, 8.3, 10.4, 9.4, 9.9, 8.1, 8.2, 9.0, 7.8, 7.7, 5.8, 8.3, 5.3, 7.0, 3.0, 7.1, 2.7, 5.8, 0.3, 5.3, 0.2, 3.8, -2.0, 2.6, -1.3, 0.9, -2.0, 0.0, -2.5, -0.9, -0.8, -2.4, -1.1, -4.0, 1.4, -4.9, 1.6, -6.3, 4.0, -6.5, 4.5, -7.8, 6.6, -7.4, 7.0, -8.7, 8.9, -8.0, 9.3, -9.2, 10.8, -8.2, 11.5, -9.5, 12.7, -8.4, 13.5, -9.6, 14.4, -8.4, 15.5, -9.6, 16.2, -8.3, 17.6, -9.4, 18.1, -8.1, 19.8, -9.0, 20.3, -7.7, 22.3, -8.3, 22.8, -7.1, 25.2, -7.4, 26.2, -6.2, 29.8, -6.0, 31.1, -4.7, 34.8, -3.3, 35.2, -1.1]
    // Veins from the stem's base, as quadratic curves (control point, then end), and the spots.
    readonly property var veins: [
        [[19.5, 0, 35, 0], [13.5, -10.5, 23, -21], [13.5, 10.5, 23, 21], [5.5, -11.5, 7, -23], [5.5, 11.5, 7, 23]],
        [[15, 0, 26, 0], [10, -5.5, 16, -11], [10, 5.5, 16, 11], [6, -6, 8, -12], [6, 6, 8, 12]],
        [[18, 0, 32, 0], [9, -4, 14, -8], [9, 4, 14, 8], [13, -3, 22, -6], [13, 3, 22, 6]]
    ]
    readonly property var spots: [
        [[7.8, -1.9, 1.1], [8.2, -7, 0.8], [18.1, -6.3, 1.2], [16.6, -6.5, 1], [13.2, -1.6, 1.4]],
        [[6.3, 7.9, 0.9], [9.2, -3.1, 0.9]],
        [[17.6, -1.5, 1.1], [7, -8.1, 0.7], [8.2, -6.3, 1.2]]
    ]
    function outline(ctx, shape) {
        if (shape === 0) {
            // The maple: five pointed lobes.
            ctx.moveTo(1, -2); ctx.quadraticCurveTo(3.1, -5.5, 4, -9); ctx.quadraticCurveTo(2.4, -12, 2, -15)
            ctx.lineTo(7, -23); ctx.lineTo(10, -18); ctx.quadraticCurveTo(12.6, -19, 14, -20); ctx.quadraticCurveTo(12.9, -16.5, 13, -13)
            ctx.quadraticCurveTo(15.6, -14.5, 17, -16); ctx.lineTo(23, -21); ctx.lineTo(26, -15); ctx.quadraticCurveTo(24.4, -12.5, 24, -10)
            ctx.quadraticCurveTo(21.9, -8.5, 21, -7); ctx.quadraticCurveTo(23.6, -7, 25, -7); ctx.quadraticCurveTo(27.6, -8, 29, -9)
            ctx.quadraticCurveTo(32.1, -6, 34, -3); ctx.lineTo(35, 0); ctx.lineTo(34, 3); ctx.quadraticCurveTo(30.9, 6, 29, 9)
            ctx.quadraticCurveTo(26.4, 8, 25, 7); ctx.quadraticCurveTo(22.4, 7, 21, 7); ctx.quadraticCurveTo(23.1, 8.5, 24, 10)
            ctx.quadraticCurveTo(25.6, 12.5, 26, 15); ctx.lineTo(23, 21); ctx.lineTo(17, 16); ctx.quadraticCurveTo(14.4, 14.5, 13, 13)
            ctx.quadraticCurveTo(14.1, 16.5, 14, 20); ctx.quadraticCurveTo(11.4, 19, 10, 18); ctx.lineTo(7, 23); ctx.lineTo(2, 15)
            ctx.quadraticCurveTo(3.6, 12, 4, 9); ctx.quadraticCurveTo(1.9, 5.5, 1, 2); ctx.closePath()
            return
        }
        const pts = shape === 1 ? oak : elm
        ctx.moveTo(pts[0], pts[1])
        for (let i = 2; i < pts.length; i += 2) ctx.lineTo(pts[i], pts[i + 1])
        ctx.closePath()
    }
    function drawLeaf(ctx, sym) {
        const shape = leafShape[sym] || 0
        const tone = tones[leafTone[sym] || 0]
        const dark = tone[2]
        // The stem.
        ctx.strokeStyle = dark; ctx.lineWidth = 1.2
        ctx.beginPath(); ctx.moveTo(-7, 1.2); ctx.quadraticCurveTo(-3.5, 0.4, 0, 0); ctx.stroke()
        // The blade, lit from near the stem.
        const g = ctx.createRadialGradient(7.2, -2.4, 0, 7.2, -2.4, 31.2)
        g.addColorStop(0, tone[0]); g.addColorStop(0.55, tone[1]); g.addColorStop(1, tone[2])
        ctx.fillStyle = g
        ctx.lineWidth = 0.35
        ctx.beginPath(); outline(ctx, shape); ctx.fill(); ctx.stroke()
        // Veins and a few spots.
        ctx.globalAlpha = 0.8; ctx.lineWidth = 0.7
        const vs = veins[shape]
        ctx.beginPath()
        for (let i = 0; i < vs.length; ++i) { const v = vs[i]; ctx.moveTo(0, 0); ctx.quadraticCurveTo(v[0], v[1], v[2], v[3]) }
        ctx.stroke()
        ctx.globalAlpha = 0.35; ctx.fillStyle = dark
        const sp = spots[shape]
        for (let i = 0; i < sp.length; ++i) { const c = sp[i]; ctx.beginPath(); ctx.arc(c[0], c[1], c[2], 0, tau); ctx.fill() }
        ctx.globalAlpha = 1
    }
    // A snowflake in a leaf's or seed's place, centered in the box (12 right of the stem's base): a
    // big six-armed crystal, a soft round flake or a small crystal for a leaf (by its shape), a small
    // soft flake for a seed.
    function drawFlake(ctx, sym) {
        const type = sym >= 15 ? 1 : (leafShape[sym] || 0)
        ctx.translate(12, 0)
        if (type === 1) {
            const r = sym >= 15 ? 5 : 7
            const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r)
            g.addColorStop(0, "rgba(255, 255, 255, 0.85)")
            g.addColorStop(0.55, "rgba(255, 255, 255, 0.6)")
            g.addColorStop(1, "rgba(255, 255, 255, 0)")
            ctx.fillStyle = g
            ctx.beginPath(); ctx.arc(0, 0, r, 0, tau); ctx.fill()
            return
        }
        const r = type === 0 ? 14 : 9
        const twigs = type === 0 ? [0.5, 0.75] : [0.55]
        const third = Math.PI / 3
        ctx.globalAlpha = 0.85
        ctx.strokeStyle = "#ffffff"; ctx.lineWidth = 1.8
        ctx.beginPath()
        for (let i = 0; i < 6; ++i) {
            const a = i * third, c = Math.cos(a), n = Math.sin(a)
            ctx.moveTo(0, 0); ctx.lineTo(c * r, n * r)
            for (let j = 0; j < twigs.length; ++j) {
                const at = twigs[j], len = r * (at > 0.6 ? 0.2 : 0.3)
                for (let side = -1; side <= 1; side += 2) {
                    const b = a + side * third
                    ctx.moveTo(c * r * at, n * r * at); ctx.lineTo(c * r * at + Math.cos(b) * len, n * r * at + Math.sin(b) * len)
                }
            }
        }
        ctx.stroke()
        ctx.globalAlpha = 1
    }
    function drawSeed(ctx, n) {
        const tone = seedTones[n] || seedTones[0]
        const g = ctx.createLinearGradient(2, 0, 38, 0)
        g.addColorStop(0, tone[0]); g.addColorStop(0.25, tone[1]); g.addColorStop(1, tone[2])
        // The wing.
        ctx.globalAlpha = 0.95
        ctx.fillStyle = g; ctx.strokeStyle = tone[0]; ctx.lineWidth = 0.5
        ctx.beginPath(); ctx.moveTo(2, 0); ctx.bezierCurveTo(8, -9, 22, -11, 34, -7); ctx.bezierCurveTo(38, -5, 38, 1, 34, 3)
        ctx.bezierCurveTo(24, 7, 10, 7, 2, 2); ctx.closePath(); ctx.fill(); ctx.stroke()
        // Its ribs.
        ctx.globalAlpha = 0.55; ctx.lineWidth = 0.35
        ctx.beginPath()
        ctx.moveTo(4, -1); ctx.quadraticCurveTo(18, -6, 33, -6)
        ctx.moveTo(4, 0); ctx.quadraticCurveTo(18, -3, 33, -3)
        ctx.moveTo(4, 0.5); ctx.quadraticCurveTo(18, 0, 33, -1)
        ctx.moveTo(4, 1); ctx.quadraticCurveTo(18, 2, 33, 1)
        ctx.stroke()
        // The seed itself, with a glint.
        ctx.globalAlpha = 1; ctx.fillStyle = tone[0]
        ctx.beginPath(); ctx.ellipse(1 - 4.2, -3, 8.4, 6); ctx.fill()
        ctx.globalAlpha = 0.7; ctx.fillStyle = "#9a7a44"
        ctx.beginPath(); ctx.ellipse(-2.2, -2, 4.4, 2.4); ctx.fill()
        ctx.globalAlpha = 1
    }
}
