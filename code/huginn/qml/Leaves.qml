import QtQuick
import QtQuick.Window
import Huginn

// Seasonal, on top of any theme: the ground (on Dark, Autumn's warm bark, Snow's night blue, or the background
// picked on the Look page; on Light the parchment tinted faintly; on True black and High contrast pure black) and
// what falls behind the frosted panes, in colors for the theme and Color vision (Theme.leafInk and flakeInk).
// One engine for both seasons; kind only picks the shapes. Autumn: three leaf shapes (maple, oak and a serrated
// elm) in seven colors, and maple seeds. Snow: six-armed crystals in the leaves' and seeds' places, white (soft
// gray-blue on Light) and a little see-through.
// Fall (fall.cpp) draws each shape once into one picture and puts every leaf on screen in one batch, moving them
// from C++ at most 30 times a second (15 in the background), no item or script per leaf: they glide sideways as they
// fall, rock and tumble, flip edge-on and back, and lean into a gust now and then; the seeds spin and orbit as they
// drop. It stops while the window is hidden or minimized, after a minute in the background, and after 30 seconds
// without input on battery; Less motion and Battery saver lay everything still, scattered across the ground.
// Settings › Look: Animation (Off draws nothing and stops it; "On, even on battery" keeps it moving under Battery saver
// and idle on battery, at 15 frames a second while on battery), Amount (1 to 400 on screen; until it's moved,
// Theme.fallCount scales it by the window's area) and Size (15% to 600%). Very big ones are fewer, so all
// of them together hold at most twice the window's pixels.
Item {
    id: leaves
    visible: Theme.seasonal
    // What falls: "leaves" (Autumn) or "snow".
    readonly property string kind: Theme.snow ? "snow" : "leaves"
    // Settings › Look › Animation: Off, On, or On even on battery ("battery").
    readonly property bool falling: Settings.seasonAnimation !== "off"
    readonly property bool evenOnBattery: Settings.seasonAnimation === "battery"

    readonly property real sizeScale: (Number(Settings.fallSize) || 60) / 100 * Theme.s
    // At most as many as fit in twice the window's pixels at this size (only ever fewer for a few dozen huge ones).
    readonly property int fits: Math.max(1, Math.floor(Math.max(width * height, 1440000) * 2 / Math.pow(30 * sizeScale, 2)))
    readonly property int count: falling ? Math.min(Theme.fallCount(width, height), fits) : 0
    // About 5 seeds in every 23; the rest are leaves (all flakes in Snow).
    readonly property int nSeeds: Math.round(count * 5 / 23)
    readonly property int nLeaves: count - nSeeds
    readonly property bool winActive: Window.active
    readonly property bool onScreen: visible && Window.visibility !== Window.Hidden && Window.visibility !== Window.Minimized
    // In the background for a minute: everything holds still until Huginn is in front again.
    property bool resting: false
    Timer { interval: 60000; running: !leaves.winActive && leaves.onScreen && leaves.falling; onTriggered: leaves.resting = true }
    onWinActiveChanged: if (winActive) resting = false
    // On battery, 30 seconds without input holds it still too, until the next key, touch, mouse or button (not with
    // On, even on battery).
    readonly property bool idleHeld: System.onBattery && System.inputIdle && !evenOnBattery
    readonly property bool moving: onScreen && Theme.fallMotion && falling && !resting && !idleHeld

    // Where each leaf starts and how it moves. The same index always gets the same leaf, so the
    // sliders add or take away leaves without reshuffling the rest.
    readonly property var plan: {
        const out = []
        for (let i = 0; i < nLeaves; ++i) out.push(make(i, "leaf"))
        for (let i = 0; i < nSeeds; ++i) out.push(make(1000 + i, "seed"))
        return out
    }
    // A small, repeatable random source per leaf; Fall (fall.cpp) reads how it moves from these.
    function make(i, kind) {
        let s = (i * 2654435761 + 97) >>> 0
        const r = function() { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296 }
        const depth = r() < 0.2 ? 0 : r() < 0.55 ? 1 : 2
        if (kind === "seed")
            return { kind: "seed", sym: 15 + Math.floor(r() * 6), x0: r(), speed: 0.062 + r() * 0.016, sway: 12, swayT: 4 + r(),
                     w: 20 + r() * 5, rps: (1.8 + r() * 0.7) * (r() < 0.5 ? -1 : 1), phase: r() * 100, dir: r() < 0.5 ? -1 : 1,
                     orbit: 10 + r() * 7, orbitT: 3.2 + r() * 0.8, op: 0.9 }
        const w = depth === 0 ? 17 + r() * 5 : depth === 1 ? 22 + r() * 8 : 30 + r() * 10
        return { kind: "leaf", sym: Math.floor(r() * 15), x0: r(), depth: depth,
                 speed: depth === 0 ? 0.03 + r() * 0.012 : depth === 1 ? 0.048 + r() * 0.016 : 0.068 + r() * 0.024,
                 sway: w * (0.6 + r() * 0.6), swayT: 2.6 + r() * 2.2, w: w, spin: (4 + r() * 26) * (r() < 0.5 ? -1 : 1),
                 flipT: 1.9 + r() * 1.3, phase: r() * 100, dir: r() < 0.5 ? -1 : 1, glide: 0.5 + r() * 0.75,
                 tumbleT: 2.7 + r() * 2.8, zig: 0.65 + r() * 0.75, op: depth === 0 ? 0.6 : depth === 1 ? 0.85 : 1 }
    }

    // The ground: the season's (or the picked background), a little lighter near the top. Settings › Look › Gradient
    // sets how strong (flat to twice the usual); a faint speckle over it keeps the gradient smooth, without bands.
    readonly property real gradientK: Settings.seasonGradient / 100
    function towards(from, to, k) {
        const c = v => Math.max(0, Math.min(1, v))
        return Qt.rgba(c(from.r + (to.r - from.r) * k), c(from.g + (to.g - from.g) * k), c(from.b + (to.b - from.b) * k), 1)
    }
    // Drawn once per size and color as one still picture (main.cpp, GroundProvider), with the speckle in it; the
    // plain gradient shows only until it's ready. A resize waits a moment, so dragging the window makes one, not many.
    function hex(c) { return String(c).substring(1, 7) }
    readonly property string groundId: hex(towards(Theme.groundMid, Theme.groundTop, gradientK)) + "-" + hex(Theme.groundMid) + "-"
                                       + hex(towards(Theme.groundMid, Theme.groundDeep, gradientK))
    property size groundSize: Qt.size(0, 0)
    Component.onCompleted: sizeSoon.restart()
    Timer { id: sizeSoon; interval: 250; onTriggered: leaves.groundSize = Qt.size(Math.round(leaves.width), Math.round(leaves.height)) }
    Rectangle {
        anchors.fill: parent
        visible: !ground.shown && !Theme.flatGround
        gradient: Gradient {
            GradientStop { position: 0.0; color: leaves.towards(Theme.groundMid, Theme.groundTop, leaves.gradientK) }
            GradientStop { position: 0.45; color: Theme.groundMid }
            GradientStop { position: 1.0; color: leaves.towards(Theme.groundMid, Theme.groundDeep, leaves.gradientK) }
        }
    }
    // True black and High contrast: pure black, nothing lit behind what falls.
    Rectangle { anchors.fill: parent; visible: Theme.flatGround; color: "#000000" }
    Image {
        id: ground
        readonly property bool shown: status === Image.Ready && leaves.gradientK > 0
        anchors.fill: parent
        visible: shown
        source: leaves.visible && !Theme.flatGround && leaves.gradientK > 0 && leaves.groundSize.width > 0 ? "image://ground/" + leaves.groundId : ""
        sourceSize: leaves.groundSize
        asynchronous: true
        cache: false
        smooth: true
    }

    // What moves and draws them (fall.cpp): 30 frames a second in front, 15 behind other windows; all of them in one
    // batch over the ground.
    Fall {
        id: driver
        anchors.fill: parent
        running: leaves.moving
        // On battery with On, even on battery: 15, to save power.
        fps: leaves.winActive && !(leaves.evenOnBattery && (System.onBattery || System.batterySaving)) ? 30 : 15
        sizeScale: leaves.sizeScale
        snow: leaves.kind === "snow"
        plan: leaves.visible ? leaves.plan : []
        inks: leaves.inks
    }
    // Less motion, Animation off, or nothing drawn yet: everything in its place for one moment, no wind. Held
    // still for another reason (in the background, idle on battery), everything stays where it was. A new plan,
    // size or season while still is placed by Fall itself.
    function settle() { driver.settle() }
    onMovingChanged: if (!moving && (!Theme.fallMotion || !falling)) settle()
    onWidthChanged: sizeSoon.restart()
    onHeightChanged: sizeSoon.restart()

    // ------------------------------------------------------------- the colors
    // Seven leaf colors (light to dark of the gradient, which is also the vein color); fall.cpp has the shapes.
    readonly property var tones: [
        ["#f7d994", "#e8b45a", "#9a6a22"], ["#f6c384", "#e59a4c", "#8d4f1c"], ["#e88a5e", "#c45a2e", "#6e2c12"],
        ["#f0a26c", "#d6763a", "#7f3a16"], ["#dd7a56", "#b9452a", "#5e2010"], ["#cf9a55", "#a86a2a", "#5a3512"],
        ["#f2d57a", "#d9a93f", "#8c6a1d"]
    ]
    // The colors drawn for this theme and Color vision (see Theme.leafInk), as "#rrggbb": the leaves' and seeds'
    // tones, the seed's glint and the flakes. A change draws the shapes again in the new colors.
    function ink(c) { return String(Theme.leafInk(c)) }
    readonly property var inks: ({ leaves: tones.map(t => t.map(ink)), seeds: seedTones.map(t => t.map(ink)), glint: ink("#9a7a44"), flake: String(Theme.flakeInk) })
    readonly property var seedTones: [
        ["#6a4a22", "#b89a5c", "#e9d9a8"], ["#6a4a22", "#b89a5c", "#e9d9a8"], ["#7a5a2a", "#c9a86a", "#e9d9a8"],
        ["#7a5a2a", "#c9a86a", "#e9d9a8"], ["#8a6a33", "#d4b67a", "#e9d9a8"], ["#7a5a2a", "#c9a86a", "#e9d9a8"]
    ]
}
