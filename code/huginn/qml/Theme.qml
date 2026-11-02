pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Settings › Look › Accessibility.
    readonly property bool hc: Settings.highContrast
    readonly property real textScale: Number(Settings.textSize) / 100
    // Labs › Performance check › Animations: Off holds everything still too (from the start it was tried at), and so
    // does Settings › Look › Battery saver while it's on.
    readonly property bool motion: !Settings.reduceMotion && !Perf.animationsOff && !System.batterySaving
    // The Seasonal leaves or snow only: Settings › Look › Animation "On, even on battery" keeps them moving under
    // Battery saver (everything else still slows). Less motion and a tried Animations: Off still hold them still.
    readonly property bool fallMotion: !Settings.reduceMotion && !Perf.animationsOff
                                       && (!System.batterySaving || Settings.seasonAnimation === "battery")

    // Seasonal: each season's look and the days it runs, as month * 100 + day, both ends included (a
    // range that wraps past the new year, like 1201 to 229, works too). The first that holds the day
    // wins; with none, the last one. Snow runs from December 1 to the end of February (229 covers a
    // leap year), Autumn the rest of the year. The order here is the order of the Seasonal choices, so a
    // new season joins them by being added here.
    function seasons() {
        return [
            { look: "autumn", name: "Autumn", from: 301, to: 1130 },
            { look: "snow", name: "Snow", from: 1201, to: 229 }
        ]
    }
    function seasonOn(d) {
        const list = seasons()
        const md = (d.getMonth() + 1) * 100 + d.getDate()
        for (let i = 0; i < list.length; ++i) {
            const s = list[i]
            if (s.from <= s.to ? (md >= s.from && md <= s.to) : (md >= s.from || md <= s.to)) return s
        }
        return list[list.length - 1]
    }
    // Checked every hour, so a new season starts without a restart.
    property date today: new Date()
    property Timer dayTimer: Timer { interval: 3600000; running: true; repeat: true; onTriggered: theme.today = new Date() }
    readonly property var season: seasonOn(today)
    // Settings › Look › Seasonal, on top of any theme: Off, Auto (by the date), then every season. A saved choice that
    // isn't one is Off.
    readonly property var seasonChoices: [{ value: "off", label: "Off" }, { value: "auto", label: "Auto by date" }]
                                         .concat(seasons().map(s => ({ value: s.look, label: s.name })))
    readonly property string seasonPick: seasonChoices.some(c => c.value === Settings.season) ? Settings.season : "off"
    readonly property string seasonName: (seasonChoices.find(c => c.value === seasonPick) || seasonChoices[0]).label

    // Settings › Look › Amount and Size of what falls, worked out the same way in Muninn. Both sliders
    // run 0 to 100 and are logarithmic, so the low end is fine-grained: Amount is round(400^(v/100)),
    // 1 to 400 on screen; Size is round(15 * 40^(v/100)), 15% to 600% of the usual.
    function fallCountAt(v) { return Math.max(1, Math.min(400, Math.round(Math.pow(400, Math.max(0, Math.min(100, v)) / 100)))) }
    function fallCountPos(n) { return 100 * Math.log(Math.max(1, Math.min(400, n))) / Math.log(400) }
    function fallSizeAt(v) { return Math.max(15, Math.min(600, Math.round(15 * Math.pow(40, Math.max(0, Math.min(100, v)) / 100)))) }
    function fallSizePos(p) { return 100 * Math.log(Math.max(15, Math.min(600, p)) / 15) / Math.log(40) }
    // How many fall in a w by h window: the Amount picked, or until it's moved, Settings.fallBase on
    // a 1600x900 window scaled by the window's area.
    // Handheld mode: half as many until the Amount slider is moved.
    function fallCount(w, h) {
        if (Settings.fallCount > 0) return Settings.fallCount
        const area = Math.max(0.2, (w * h) / (1600 * 900))
        return Math.max(1, Math.min(400, Math.round(Settings.fallBase * area * (System.handheld ? 0.5 : 1))))
    }

    // Settings › Look › Theme: "dark" (stone, iron and aged gold), "black" (true black, for OLED screens) or "light"
    // (parchment). High contrast wins over all. Seasonal goes on top of any of them: "autumn" or "snow" while on.
    readonly property string choice: ["black", "light"].indexOf(Settings.theme) >= 0 ? Settings.theme : "dark"
    readonly property string seasonLook: seasonPick === "off" ? "" : seasonPick !== "auto" ? seasonPick : season ? season.look : "autumn"
    // The look on screen, as the performance report names it.
    readonly property string mode: hc ? "contrast" : choice + (seasonLook !== "" ? "+" + seasonLook : "")
    readonly property bool light: choice === "light" && !hc
    readonly property bool black: choice === "black" && !hc
    // Seasonal is on, over any theme: rounded corners, see-through panes and something falling behind them.
    readonly property bool seasonal: seasonLook !== ""
    // What falls: snow, or leaves (Autumn, and any season without shapes of its own).
    readonly property bool snow: seasonLook === "snow"
    readonly property bool autumn: seasonal && !snow
    // On Dark the season brings its own shades too (warm bark, or night blue); on the other themes their own
    // shades stay and only what falls is added.
    readonly property bool tinted: seasonal && choice === "dark" && !hc
    readonly property bool snowTint: tinted && snow
    readonly property bool autumnTint: tinted && !snow
    // Settings › Look › Background (Seasonal on Dark): the season's own ground, or one of these dark grounds; the
    // panels and lines are shaded from it, so text stays readable.
    readonly property var groundSwatches: ["#0C1724", "#13201A", "#0E1C1E", "#1E1522", "#22140F", "#221B10", "#17191E", "#111111"]
    readonly property var groundNames: ["Night", "Pine", "Teal", "Plum", "Bark", "Umber", "Slate", "Charcoal"]
    readonly property string groundPick: tinted && groundSwatches.indexOf(Settings.seasonGround) >= 0 ? Settings.seasonGround : ""
    // A "#RRGGBB" lifted toward white by f (0 to 1), or darkened toward black by a negative f.
    function shade(hex, f) {
        const c = [1, 3, 5].map(i => parseInt(hex.substr(i, 2), 16) / 255)
        const m = c.map(v => f >= 0 ? v + (1 - v) * f : v * (1 + f))
        return Qt.rgba(m[0], m[1], m[2], 1)
    }
    // The shade for the look on screen. A shade with a lift (a ground, panel or line) follows a picked
    // Seasonal background instead of the season's own (see shade).
    function pick(dark, deep, parchment, contrast, leaf, frost, lift) {
        if (hc) return contrast
        if (light) return parchment
        if (black) return deep
        if (!tinted) return dark
        if (groundPick !== "" && lift !== undefined) return shade(groundPick, lift)
        return snow ? (frost || leaf || dark) : (leaf || dark)
    }

    readonly property color ground: pick("#101112", "#000000", "#F3EFE6", "#000000", "#221710", "#0E1726", 0)
    // The Seasonal ground behind the panes, a little lighter near the top (Leaves.qml draws it). On Light the
    // season tints the parchment faintly; on True black and High contrast it stays pure black.
    readonly property bool lightSeason: seasonal && light
    readonly property color groundDeep: lightSeason ? (snow ? "#DBE3EC" : "#E5D8C0") : pick("#0C0D0E", "#000000", "#E7E1D4", "#000000", "#1A110B", "#0A111D", -0.25)
    readonly property color groundTop: lightSeason ? (snow ? "#F4F7FA" : "#F7F0E2") : pick("#101112", "#000000", "#F3EFE6", "#000000", "#2E1F14", "#16223A", 0.06)
    readonly property color groundMid: lightSeason ? (snow ? "#EAEFF5" : "#F1E7D5") : pick("#101112", "#000000", "#F3EFE6", "#000000", "#241810", "#0E1726", 0)
    // Pure black under what falls: no gradient and no speckle.
    readonly property bool flatGround: seasonal && (black || hc)
    readonly property color panel: pick("#17191B", "#0B0C0D", "#FBF9F4", "#0A0A0A", "#2B1D13", "#152238", 0.05)
    readonly property color panelLow: pick("#141617", "#060607", "#ECE7DC", "#050505", "#241911", "#111C2F", 0.025)
    readonly property color raised: pick("#1D1E20", "#141516", "#E2DCCF", "#1A1A1A", "#4A3421", "#24375A", 0.14)
    readonly property color tile: pick("#24262A", "#1B1C1E", "#D8D1C2", "#1F1F1F", "#573E28", "#2C4268", 0.18)
    readonly property color line: pick("#2C2F33", "#232528", "#CEC6B6", "#9A9A9A", "#3E2B1B", "#2A3D5C", 0.1)
    readonly property color lineStrong: pick("#34373B", "#2B2E31", "#BDB4A2", "#B0B0B0", "#4A3421", "#33496B", 0.14)
    readonly property color lineMuted: pick("#45484D", "#3E4146", "#A69D8B", "#BDBDBD", "#6B4D33", "#3F5679", 0.22)
    readonly property color text: pick("#E4E1DA", "#E4E1DA", "#1F1C17", "#FFFFFF", "#ECDCC4", "#E6EEF8")
    readonly property color textSoft: pick("#C4C0B7", "#C4C0B7", "#3B362E", "#FFFFFF", "#D8C3A5", "#C9D6E6")
    // On a picked Seasonal background, Autumn's muted and faint text are a little brighter, so they still read.
    // No color: muted and faint text are stronger, so lightness alone keeps everything readable.
    readonly property color textMuted: mono && !hc ? (light ? "#3A3A3A" : "#C4C4C4")
                                     : groundPick !== "" && autumnTint ? "#B9A080" : pick("#9C978D", "#9C978D", "#615A4E", "#E0E0E0", "#A88C6C", "#93A7C0")
    readonly property color textFaint: mono && !hc ? (light ? "#555555" : "#A6A6A6")
                                     : groundPick !== "" ? (snowTint ? "#8EA5C0" : "#AD9474") : pick("#6E6A62", "#6E6A62", "#877F71", "#C8C8C8", "#7D6549", "#8299B5")
    readonly property color ornament: pick("#5E5A53", "#5E5A53", "#958B79", "#A0A0A0", "#8A6D4C", "#5F7697", 0.3)
    // The brightest text (a title, your own name) and section headings.
    readonly property color textBright: snowTint ? "#F4F8FD" : autumnTint ? "#F3E2C7" : text
    readonly property color section: snowTint ? "#8FB0D4" : autumnTint ? "#B08A5E" : textMuted
    // The three columns: the rail on the far left, the list beside it, and the open conversation.
    // Seasonal: the rail and the lists are panes the leaves or snow show through faintly (about 80%
    // solid), and the open conversation is frosted glass (about 38%), over the ground Leaves.qml draws.
    // Light and High contrast keep the panes more solid, so text over a leaf still reads.
    readonly property real sheetAlpha: hc ? 0.78 : light ? 0.55 : 0.38
    readonly property color rail: seasonal ? Qt.rgba(groundDeep.r, groundDeep.g, groundDeep.b, hc ? 0.92 : 0.85) : groundDeep
    readonly property color column: seasonal ? Qt.rgba(panelLow.r, panelLow.g, panelLow.b, hc ? 0.9 : 0.8) : panelLow
    readonly property color sheet: seasonal ? Qt.rgba(ground.r, ground.g, ground.b, sheetAlpha) : ground
    readonly property color seam: pick("#1F2125", "#16181A", "#D9D2C4", "#9A9A9A", "#35251A", "#1E2E48", 0.08)

    // ------------------------------------------------------------- color vision
    // Settings › Look › Color vision: "typical", "protan" (red-weak or red-blind), "deutan" (green-weak or
    // green-blind), "tritan" (blue-weak or blue-blind) or "mono" (no color). Every color that means something is
    // remapped for it from sets made for color-blind eyes (Okabe-Ito's blue, yellow and vermilion for red and green;
    // teal, pink and red without blue against yellow for blue; lightness alone for no color). The same sets as
    // Muninn's ColorVision.kt, where a test checks every pair of status colors stays apart under each.
    // Never color alone: status dots have shapes too (Avatar.qml), and failed things say so in words.
    readonly property string vision: ["protan", "deutan", "tritan", "mono"].indexOf(Settings.colorVision) >= 0 ? Settings.colorVision : "typical"
    readonly property bool cb: vision !== "typical"
    readonly property bool mono: vision === "mono"
    readonly property var visionChoices: [
        { value: "typical", label: "Typical" },
        { value: "protan", label: "Red-weak or red-blind" },
        { value: "deutan", label: "Green-weak or green-blind" },
        { value: "tritan", label: "Blue-weak or blue-blind" },
        { value: "mono", label: "No color" }
    ]
    // Which of a set: on Light, in High contrast, or on a dark ground (Dark, True black, Seasonal).
    readonly property int tone: hc ? 2 : light ? 1 : 0
    // Online, away, do not disturb and offline, for each kind and tone.
    readonly property var visionStatus: ({
        protan: [["#56B4E9", "#F0E442", "#E07020", "#6E6A62"], ["#0063A0", "#A88700", "#8F2D00", "#9A948A"], ["#7CCBFF", "#FFF05A", "#FF8A2E", "#8C8C8C"]],
        deutan: [["#56B4E9", "#F0E442", "#D55E00", "#6E6A62"], ["#0063A0", "#A88700", "#8F2D00", "#9A948A"], ["#7CCBFF", "#FFF05A", "#FF5A1E", "#8C8C8C"]],
        tritan: [["#2BC4C4", "#F4A6C0", "#E8443A", "#6E6A62"], ["#007C80", "#B4527A", "#C0281E", "#9A948A"], ["#4FF0F0", "#FFB0D0", "#FF5A4A", "#8C8C8C"]],
        mono: [["#FFFFFF", "#B0B0B0", "#707070", "#3C3C3C"], ["#000000", "#444444", "#777777", "#B0B0B0"], ["#FFFFFF", "#B0B0B0", "#707070", "#3C3C3C"]]
    })
    // Danger: its text, line and fill.
    readonly property var visionDanger: ({
        protan: [["#F08A4B", "#7A4220", "#24160E"], ["#A84300", "#D9A27E", "#F8E6DA"], ["#FFA060", "#7A4220", "#24160E"]],
        deutan: [["#F08A4B", "#7A4220", "#24160E"], ["#A84300", "#D9A27E", "#F8E6DA"], ["#FFA060", "#7A4220", "#24160E"]],
        tritan: [["#F2877C", "#6E3A34", "#24120F"], ["#B0281E", "#E0A49E", "#F7DEDB"], ["#FF9A90", "#6E3A34", "#24120F"]],
        mono: [["#9A9A9A", "#8A8A8A", "#262626"], ["#5A5A5A", "#6A6A6A", "#E2E2E2"], ["#B8B8B8", "#8A8A8A", "#262626"]]
    })
    readonly property var statusSet: cb ? visionStatus[vision][tone] : null
    readonly property var dangerSet: cb ? visionDanger[vision][tone] : null
    readonly property color danger: cb ? dangerSet[0] : hc ? "#FFB4A8" : light ? "#A23B2C" : snowTint ? "#F0A8A0" : autumnTint ? "#E08A7A" : "#D9A79E"
    readonly property color dangerLine: cb ? dangerSet[1] : light ? "#D9A79E" : snowTint ? "#6A3A40" : autumnTint ? "#6E3A30" : "#5A3A34"
    readonly property color dangerFill: cb ? dangerSet[2] : light ? "#F5E2DD" : snowTint ? "#2A1A22" : autumnTint ? "#2E1A14" : "#1E1716"
    // Someone's status: online, idle or away, do not disturb, and in a voice room (always said in words too, and
    // each dot has its own shape). High contrast: brighter and more saturated on its black.
    readonly property color online: cb ? statusSet[0] : hc ? "#5CFF8F" : light ? "#2F7D46" : snowTint ? "#4FC08D" : autumnTint ? "#3FB27F" : "#5FB872"
    readonly property color idle: cb ? statusSet[1] : hc ? "#FFD23F" : light ? "#9A6B00" : tinted ? "#E0B44C" : "#D9A440"
    readonly property color away: idle
    readonly property color dnd: cb ? statusSet[2] : hc ? "#FF6E66" : light ? "#B23A32" : snowTint ? "#E06A66" : "#D9534F"
    readonly property color offline: cb ? statusSet[3] : textFaint
    // Done or working (green), and a warning (amber), in the same sets.
    readonly property color success: online
    readonly property color warning: idle

    // "#RRGGBB" to CIE Lab, to find the nearest of a set (deltaE 1976).
    function lab(hex) {
        const c = [1, 3, 5].map(i => { const v = parseInt(String(hex).substr(i, 2), 16) / 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) })
        const x = (0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]) / 0.95047, y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        const z = (0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]) / 1.08883
        const f = t => t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116
        return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))]
    }
    function nearest(hex, list) {
        const a = lab(hex)
        let best = list[0], d = 1e9
        for (let i = 0; i < list.length; ++i) {
            const b = lab(list[i].hex || list[i])
            const e = Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2])
            if (e < d) { d = e; best = list[i] }
        }
        return best
    }

    // The screenshot tour's check of the sets above (Muninn's ColorVisionTest does the same): under each kind of
    // sight, simulated with Machado's matrices (full strength; no color as lightness alone), every two status colors
    // and the call buttons stay at least 20 apart (deltaE 1976). A pair too close is a warning, which fails the tour.
    readonly property var machado: ({
        protan: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
        deutan: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.01182, 0.04294, 0.968881]],
        tritan: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.3039]]
    })
    function seenAs(hex, kind) {
        const lin = [1, 3, 5].map(i => { const v = parseInt(String(hex).substr(i, 2), 16) / 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) })
        let o = lin
        if (kind === "mono") { const y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]; o = [y, y, y] }
        else if (machado[kind]) o = machado[kind].map(row => row[0] * lin[0] + row[1] * lin[1] + row[2] * lin[2])
        const back = v => { v = Math.max(0, Math.min(1, v)); return Math.round(255 * (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055)) }
        return "#" + o.map(v => ("0" + back(v).toString(16)).slice(-2)).join("")
    }
    function visionCheck() {
        const bad = []
        for (const kind in visionStatus) {
            visionStatus[kind].forEach((set, tone) => {
                const pairs = []
                for (let i = 0; i < set.length; ++i) for (let j = i + 1; j < set.length; ++j) pairs.push([set[i], set[j]])
                pairs.push([set[0], visionDanger[kind][tone][0]])
                pairs.forEach(p => {
                    const a = lab(seenAs(p[0], kind)), b = lab(seenAs(p[1], kind))
                    const d = Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2])
                    if (d < 20) bad.push(kind + " " + tone + " " + p[0] + "/" + p[1] + " " + d.toFixed(1))
                })
            })
        }
        return bad
    }
    Component.onCompleted: if (System.tourDir !== "") visionCheck().forEach(b => console.warn("Color vision: too close: " + b))

    // The accent: a named one or any #RRGGBB from the picker. On parchment it's darkened to stay readable.
    // Autumn's own gold and ember are warmer than the others'; Snow's gold is paler, to sit on night blue.
    readonly property var accentHex: ({ "gold": "#C2A25E", "iron": "#A9B4BE", "copper": "#B07A5A", "jade": "#6FAE8C", "fjord": "#6E9CC8", "heather": "#A58BC6", "ember": "#CF6F54" })
    readonly property var accents: ({ "gold": snowTint ? "#E3C27A" : autumnTint ? "#E9B56A" : "#C2A25E", "iron": mono ? (light ? "#5E5E5E" : "#D0D0D0") : "#A9B4BE",
                                      "copper": autumnTint ? "#D9853B" : "#B07A5A", "jade": "#6FAE8C", "fjord": "#6E9CC8", "heather": "#A58BC6", "ember": "#CF6F54" })
    readonly property var allAccents: ["gold", "iron", "copper", "jade", "fjord", "heather", "ember"]
    // Color vision: only the accents that stay apart for that kind of sight (the others map to the nearest of these).
    readonly property var visionAccents: ({ typical: allAccents, protan: ["gold", "iron", "jade", "fjord"], deutan: ["gold", "iron", "fjord"],
                                            tritan: ["gold", "iron", "jade", "ember"], mono: ["iron"] })
    readonly property var accentNames: visionAccents[vision]
    function validColor(v) { return /^#[0-9A-Fa-f]{6}$/.test(String(v || "")) }
    // The safe accent nearest to a name or "#RRGGBB" (itself when it's one of them, or with typical sight).
    function safeAccent(v) {
        if (!cb) return v
        if (accentNames.indexOf(v) >= 0) return v
        const hex = accentHex[v] || (validColor(v) ? v : accentHex.gold)
        return nearest(hex, accentNames.map(n => ({ name: n, hex: accentHex[n] }))).name
    }
    readonly property string accentKey: safeAccent(accentHex[Settings.accent] || validColor(Settings.accent) ? Settings.accent : "gold")
    readonly property color accentBase: accents[accentKey] || (validColor(accentKey) ? accentKey : accents.gold)
    readonly property color accent: mono ? (hc ? "#FFFFFF" : accents.iron) : light ? Qt.darker(accentBase, 1.7) : accentBase
    // A chat's own color (a contact's menu › Details › Chat color, one of the accent swatches as "#RRGGBB"), drawn
    // like the accent; the accent itself when the chat has none. Color vision: the nearest safe accent.
    readonly property var chatSwatches: ["", "#C2A25E", "#A9B4BE", "#B07A5A", "#6FAE8C", "#6E9CC8", "#A58BC6", "#CF6F54"]
    function chatAccent(c) {
        if (!validColor(c)) return accent
        if (mono) return accent
        const k = cb ? accentHex[safeAccent(c)] : c
        return light ? Qt.darker(k, 1.7) : k
    }
    // Text on a filled accent button.
    readonly property color onAccent: mono ? (light ? "#FFFFFF" : "#000000") : snowTint ? "#0A1424" : autumnTint ? "#2A1608" : ground
    readonly property color accentFill: light ? Qt.rgba(accent.r * 0.18 + 0.8, accent.g * 0.18 + 0.78, accent.b * 0.18 + 0.72, 1)
                                              : Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: light ? Qt.rgba(accent.r * 0.1 + 0.86, accent.g * 0.1 + 0.84, accent.b * 0.1 + 0.79, 1)
                                             : Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // What falls in Seasonal, as Leaves.qml draws it: a leaf's color for this theme and sight. On Light it's
    // darker, so it shows on parchment; red and green sight turn the reds to oranges and golds (lighter for red,
    // which looks dark to red-weak eyes); no color makes it gray by its lightness.
    function leafInk(hex) {
        const c = [1, 3, 5].map(i => parseInt(String(hex).substr(i, 2), 16) / 255)
        let r = c[0], g = c[1], b = c[2]
        if (mono) {
            const y = r * 0.299 + g * 0.587 + b * 0.114
            r = g = b = y
        } else if (vision === "protan" || vision === "deutan") {
            // Hue, saturation, lightness: reds (below 30 degrees) move to orange.
            const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, d = mx - mn
            let h = 0
            if (d > 0) h = mx === r ? 60 * (((g - b) / d) % 6) : mx === g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4)
            if (h < 0) h += 360
            const sat = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
            if (h < 30 || h > 330) {
                const nh = 30 + (h > 330 ? 0 : h) * 0.4
                const nl = Math.min(0.9, l + (vision === "protan" ? 0.1 : 0.04))
                const cc = (1 - Math.abs(2 * nl - 1)) * sat, x = cc * (1 - Math.abs((nh / 60) % 2 - 1)), m = nl - cc / 2
                r = cc + m; g = x + m; b = m
            }
        }
        if (light) { r *= 0.72; g *= 0.72; b *= 0.72 }
        return Qt.rgba(Math.max(0, Math.min(1, r)), Math.max(0, Math.min(1, g)), Math.max(0, Math.min(1, b)), 1)
    }
    // Snow: white, or a soft gray-blue on Light (gray with no color).
    readonly property color flakeInk: light ? (mono ? "#8A8A8A" : "#7F93AD") : "#FFFFFF"

    // Everything is drawn at its design size after the system's own scaling, made bigger on a small, dense
    // screen (a handheld) so it stays readable and easy to touch, times the Size setting (50% to 200%,
    // Ctrl+= and Ctrl+-). See System::fitFor.
    readonly property bool desktop: true
    readonly property real s: System.uiScale
    function px(v) { return Math.round(v * s) }
    // Text sizes: the overall Size, times the separate Text size.
    function fs(v) { return Math.round(v * s * textScale) }
    // Message density (Settings › Look): compact, default or spacious.
    readonly property bool compact: Settings.density === "compact"
    readonly property bool spacious: Settings.density === "spacious"
    function ms(v) { return Math.round(v * s * textScale) }

    // A window taller than it is wide gets the one-column layout, like Muninn on a phone.
    readonly property bool portrait: Ui.portrait
    // Panel widths at 100% (the list and the member list are dragged on a wide window). Portrait:
    // the list takes the whole width beside the rail.
    readonly property int railWidth: px(72)
    // On a narrow window or at a large Size they give up room, so the conversation always has some:
    // the list takes at most 40% of the space beside the rail, the member list at most 30%.
    readonly property int roomBesideRail: Math.max(0, Ui.windowWidth - railWidth)
    readonly property int listWidth: portrait ? roomBesideRail : Ui.windowWidth > 0 ? Math.min(px(Ui.listW), Math.round(roomBesideRail * 0.4)) : px(Ui.listW)
    readonly property int memberWidth: Ui.windowWidth > 0 ? Math.min(px(Ui.memberW), Math.round(roomBesideRail * 0.3)) : px(Ui.memberW)
    readonly property int headerHeight: px(48)
    // Hidden side panels (Ctrl+Shift+L and Ctrl+Shift+U). Portrait shows one column at a time.
    readonly property bool listShown: !portrait && Settings.showList
    readonly property bool membersShown: !portrait && Settings.showMembers

    readonly property string display: "Cinzel"
    readonly property string body: "Barlow"
    readonly property string runic: "Noto Sans Runic"

    // Corners: sharp is the house style. Seasonal (Autumn and Snow alike) rounds them: cards and
    // buttons at 14 px, a pill for the composer and the bigger buttons, a small one for knobs and badges.
    readonly property int radius: seasonal ? px(14) : 3
    readonly property int pill: seasonal ? px(22) : 3
    readonly property int radiusSmall: seasonal ? px(8) : 2
    // Controller focus: thick enough to spot from the couch.
    readonly property int focusWidth: Math.max(hc ? 3 : 2, px(hc ? 3 : 2))
    readonly property int rowHeight: px(touch ? 48 : 42)
    // Handheld mode: bigger touch targets (buttons, choices and switches at least 44 px at the design size).
    readonly property bool touch: System.handheld
    readonly property int tapMin: px(touch ? 44 : 36)

    // Avatars: a soft color per person, picked from their name so it never changes. Your own is gold.
    // Color vision: sets whose discs stay apart for that kind of sight (lightness alone for no color).
    readonly property var visionTones: ({
        protan: [[{ bg: "#302617", fg: "#E8D3A8" }, { bg: "#7E5925", fg: "#FFE8C0" }, { bg: "#792E9E", fg: "#F0D6FF" }, { bg: "#8A4272", fg: "#FFD6EE" }],
                 [{ bg: "#DBB3B3", fg: "#6A2A2A" }, { bg: "#E6DBA8", fg: "#5E4A0E" }, { bg: "#D7EAF4", fg: "#1F4A78" }, { bg: "#A8C2E6", fg: "#1F3A66" }]],
        tritan: [[{ bg: "#4F1717", fg: "#F2B0A8" }, { bg: "#9E2E2E", fg: "#FFE0DC" }, { bg: "#5A522B", fg: "#F0E6B8" }, { bg: "#1E6767", fg: "#CFF5F5" }],
                 [{ bg: "#DBB3B3", fg: "#6A2A2A" }, { bg: "#EEE3DD", fg: "#4A3A30" }, { bg: "#A8E6E6", fg: "#0F4A4A" }, { bg: "#B3C4DB", fg: "#24364F" }]],
        mono: [[{ bg: "#141414", fg: "#FFFFFF" }, { bg: "#383838", fg: "#FFFFFF" }, { bg: "#5E5E5E", fg: "#FFFFFF" }, { bg: "#D0D0D0", fg: "#000000" }],
               [{ bg: "#202020", fg: "#FFFFFF" }, { bg: "#5A5A5A", fg: "#FFFFFF" }, { bg: "#B0B0B0", fg: "#000000" }, { bg: "#E8E8E8", fg: "#000000" }]]
    })
    readonly property var avatarTones: hc ? [{ bg: "#1F1F1F", fg: "#FFFFFF" }]
        : cb ? visionTones[vision === "deutan" ? "protan" : vision][light ? 1 : 0]
        : snowTint ? [{ bg: "#3D5A80", fg: "#F4F8FD" }, { bg: "#6A4E6E", fg: "#F4F8FD" }, { bg: "#3E6560", fg: "#F4F8FD" }, { bg: "#5A5470", fg: "#F4F8FD" }, { bg: "#6E5A44", fg: "#F4F8FD" }]
        : autumnTint ? [{ bg: "#3E4A34", fg: "#CFE0B8" }, { bg: "#3A4458", fg: "#C4D2EC" }, { bg: "#5A3326", fg: "#F0B8A4" }, { bg: "#4A3650", fg: "#DEC6EC" }, { bg: "#2F4A4A", fg: "#B8E0E0" }]
        : light ? [{ bg: "#D5E4D3", fg: "#2E5A33" }, { bg: "#D6DDEB", fg: "#2F4A78" }, { bg: "#EED5CF", fg: "#86392A" }, { bg: "#E2D6EA", fg: "#5D3D78" }, { bg: "#D0E5E7", fg: "#235A60" }]
        : [{ bg: "#2C3A2C", fg: "#B6D6B6" }, { bg: "#2D3340", fg: "#B9C6E0" }, { bg: "#3A2724", fg: "#E6A596" }, { bg: "#33293A", fg: "#D2BCE0" }, { bg: "#24363A", fg: "#AED4D8" }]
    readonly property var avatarMine: hc ? ({ bg: "#1F1F1F", fg: "#FFFFFF" }) : mono ? (light ? ({ bg: "#2A2A2A", fg: "#FFFFFF" }) : ({ bg: "#E0E0E0", fg: "#000000" }))
        : light ? ({ bg: "#EADDBB", fg: "#6B5216" }) : snowTint ? ({ bg: "#3A3626", fg: "#F0D597" }) : autumnTint ? ({ bg: "#5A4020", fg: "#F0CF8A" }) : ({ bg: "#3A3220", fg: "#E0C27F" })
    function avatar(name, mine) {
        // Never undefined, even while a theme change is still settling.
        const plain = { bg: raised, fg: text }
        if (mine) return avatarMine || plain
        const tones = avatarTones || []
        let h = 0
        const n = String(name || "")
        for (let i = 0; i < n.length; ++i) h = (h * 31 + n.charCodeAt(i)) % 9973
        return tones[h % Math.max(1, tones.length)] || plain
    }
    // The initial-and-color choice someone sent with their profile: their color as the disc, and
    // light or dark text on it, whichever reads better. Color vision: the nearest disc of its set instead.
    readonly property var pictureColours: ["#C9A35E", "#3D6A9E", "#8A5A3E", "#4E8A4E", "#7A4E9E", "#8A8A8A"]
    function avatarFrom(hex) {
        const h = String(hex || "")
        if (!validColor(h)) return avatarMine || { bg: raised, fg: text }
        if (cb && !hc) {
            const set = (avatarTones || []).map(t => ({ hex: String(t.bg), t: t }))
            if (set.length) return nearest(h, set).t
        }
        const r = parseInt(h.substr(1, 2), 16) / 255, g = parseInt(h.substr(3, 2), 16) / 255, b = parseInt(h.substr(5, 2), 16) / 255
        const bright = r * 0.299 + g * 0.587 + b * 0.114 > 0.55
        return { bg: h, fg: bright ? "#1E1A14" : "#FFFFFF" }
    }

}
