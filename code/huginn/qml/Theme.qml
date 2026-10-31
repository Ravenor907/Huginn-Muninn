pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Settings › Look › Accessibility.
    readonly property bool hc: Settings.highContrast
    readonly property real textScale: Number(Settings.textSize) / 100
    readonly property bool motion: !Settings.reduceMotion

    // Seasonal: each season's look and the days it runs, as month * 100 + day, both ends included (a
    // range that wraps past the new year, like 1201 to 229, works too). The first that holds the day
    // wins; with none, the last one. Snow runs from December 1 to the end of February (229 covers a
    // leap year), Autumn the rest of the year. The order here is the order of the Season choices, and
    // of the cycle when Seasonal is pressed again, so a new season joins both by being added here.
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
    // Settings › Look › Season: Auto (by the date), then every season. A saved choice that isn't one is Auto.
    readonly property var seasonChoices: [{ value: "auto", label: "Auto" }].concat(seasons().map(s => ({ value: s.look, label: s.name })))
    readonly property string seasonPick: seasonChoices.some(c => c.value === Settings.season) ? Settings.season : "auto"
    readonly property string seasonName: (seasonChoices.find(c => c.value === seasonPick) || seasonChoices[0]).label
    // The choice after the current one: pressing Seasonal again steps Auto, Autumn, Snow and back to Auto.
    function nextSeason() {
        const i = seasonChoices.findIndex(c => c.value === seasonPick)
        return seasonChoices[(i + 1) % seasonChoices.length].value
    }

    // Settings › Look: "dark" (stone, iron and aged gold), "black" (true black, for OLED screens),
    // "light" (parchment) or "seasonal" (a season's look, picked by the date or by hand, see seasons).
    // choice is what was picked; mode is the look on screen ("autumn" or "snow" while Seasonal shows
    // that season). High contrast wins over all.
    readonly property bool savedSeason: seasons().some(s => s.look === Settings.theme)
    readonly property string choice: savedSeason ? "seasonal"
                                   : ["black", "light", "seasonal"].indexOf(Settings.theme) >= 0 ? Settings.theme : "dark"
    readonly property string mode: choice === "seasonal" ? (seasonPick !== "auto" ? seasonPick : season ? season.look : "autumn") : choice
    readonly property bool light: mode === "light" && !hc
    readonly property bool black: mode === "black" && !hc
    readonly property bool snow: mode === "snow" && !hc
    // Autumn, and any season without shades of its own.
    readonly property bool autumn: choice === "seasonal" && !snow && !hc
    // Either season: rounded corners, see-through panes and something falling behind them.
    readonly property bool seasonal: autumn || snow
    // Settings › Look › Background: the season's own ground, or one of these dark grounds; the panels
    // and lines are shaded from it, so text stays readable.
    readonly property var groundSwatches: ["#0C1724", "#13201A", "#0E1C1E", "#1E1522", "#22140F", "#221B10", "#17191E", "#111111"]
    readonly property var groundNames: ["Night", "Pine", "Teal", "Plum", "Bark", "Umber", "Slate", "Charcoal"]
    readonly property string groundPick: seasonal && groundSwatches.indexOf(Settings.seasonGround) >= 0 ? Settings.seasonGround : ""
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
        if (!seasonal) return dark
        if (groundPick !== "" && lift !== undefined) return shade(groundPick, lift)
        return snow ? (frost || leaf || dark) : (leaf || dark)
    }
    // A saved season's name is Seasonal; a saved look that doesn't exist falls back to Dark.
    Component.onCompleted: {
        if (savedSeason) Settings.theme = "seasonal"
        else if (["dark", "black", "light", "seasonal"].indexOf(Settings.theme) < 0) Settings.theme = "dark"
    }

    readonly property color ground: pick("#101112", "#000000", "#F3EFE6", "#000000", "#221710", "#0E1726", 0)
    readonly property color groundDeep: pick("#0C0D0E", "#000000", "#E7E1D4", "#000000", "#1A110B", "#0A111D", -0.25)
    // The Seasonal ground behind the panes, a little lighter near the top (Leaves.qml draws it).
    readonly property color groundTop: pick("#101112", "#000000", "#F3EFE6", "#000000", "#2E1F14", "#16223A", 0.06)
    readonly property color groundMid: pick("#101112", "#000000", "#F3EFE6", "#000000", "#241810", "#0E1726", 0)
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
    readonly property color textMuted: groundPick !== "" && autumn ? "#B9A080" : pick("#9C978D", "#9C978D", "#615A4E", "#E0E0E0", "#A88C6C", "#93A7C0")
    readonly property color textFaint: groundPick !== "" ? (snow ? "#8EA5C0" : "#AD9474") : pick("#6E6A62", "#6E6A62", "#877F71", "#C8C8C8", "#7D6549", "#8299B5")
    readonly property color ornament: pick("#5E5A53", "#5E5A53", "#958B79", "#A0A0A0", "#8A6D4C", "#5F7697", 0.3)
    // The brightest text (a title, your own name) and section headings.
    readonly property color textBright: snow ? "#F4F8FD" : autumn ? "#F3E2C7" : text
    readonly property color section: snow ? "#8FB0D4" : autumn ? "#B08A5E" : textMuted
    // The three columns: the rail on the far left, the list beside it, and the open conversation.
    // Seasonal: the rail and the lists are panes the leaves or snow show through faintly (about 80%
    // solid), and the open conversation is frosted glass (about 38%), over the ground Leaves.qml draws.
    readonly property color rail: seasonal ? Qt.rgba(groundDeep.r, groundDeep.g, groundDeep.b, 0.85) : groundDeep
    readonly property color column: seasonal ? Qt.rgba(panelLow.r, panelLow.g, panelLow.b, 0.8) : panelLow
    readonly property color sheet: seasonal ? Qt.rgba(ground.r, ground.g, ground.b, 0.38) : ground
    readonly property color seam: pick("#1F2125", "#16181A", "#D9D2C4", "#9A9A9A", "#35251A", "#1E2E48", 0.08)
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    readonly property color danger: Settings.colorBlind ? (light ? "#8A5A00" : "#F2B33D") : hc ? "#FFB4A8" : light ? "#A23B2C" : snow ? "#F0A8A0" : autumn ? "#E08A7A" : "#D9A79E"
    readonly property color dangerLine: Settings.colorBlind ? (light ? "#C79A45" : "#7A5A1E") : light ? "#D9A79E" : snow ? "#6A3A40" : autumn ? "#6E3A30" : "#5A3A34"
    readonly property color dangerFill: Settings.colorBlind ? (light ? "#F6EBD4" : "#221B0E") : light ? "#F5E2DD" : snow ? "#2A1A22" : autumn ? "#2E1A14" : "#1E1716"
    // Someone's status: online, idle or away, do not disturb, and in a voice room (always said in words too).
    // Color-blind friendly: blue, yellow and vermilion (a set made for color-blind eyes), told apart by lightness too,
    // never red against green. High contrast: brighter and more saturated on its black.
    readonly property bool cb: Settings.colorBlind
    readonly property color online: cb ? (hc ? "#7CCBFF" : light ? "#0063A0" : "#56B4E9")
                                       : hc ? "#5CFF8F" : light ? "#2F7D46" : snow ? "#4FC08D" : autumn ? "#3FB27F" : "#5FB872"
    readonly property color idle: cb ? (hc ? "#FFF05A" : light ? "#A88700" : "#F0E442")
                                     : hc ? "#FFD23F" : light ? "#9A6B00" : seasonal ? "#E0B44C" : "#D9A440"
    readonly property color away: idle
    readonly property color dnd: cb ? (hc ? "#FF7A2E" : light ? "#8F2D00" : "#D55E00")
                                    : hc ? "#FF6E66" : light ? "#B23A32" : snow ? "#E06A66" : "#D9534F"
    readonly property color offline: textFaint

    // The accent: a named one or any #RRGGBB from the picker. On parchment it's darkened to stay readable.
    // Autumn's own gold and ember are warmer than the others'; Snow's gold is paler, to sit on night blue.
    readonly property var accents: ({ "gold": snow ? "#E3C27A" : autumn ? "#E9B56A" : "#C2A25E", "iron": "#A9B4BE", "copper": autumn ? "#D9853B" : "#B07A5A", "jade": "#6FAE8C",
                                      "fjord": "#6E9CC8", "heather": "#A58BC6", "ember": "#CF6F54" })
    readonly property var accentNames: ["gold", "iron", "copper", "jade", "fjord", "heather", "ember"]
    function validColor(v) { return /^#[0-9A-Fa-f]{6}$/.test(String(v || "")) }
    readonly property color accentBase: accents[Settings.accent] || (validColor(Settings.accent) ? Settings.accent : accents.gold)
    readonly property color accent: light ? Qt.darker(accentBase, 1.7) : accentBase
    // Text on a filled accent button.
    readonly property color onAccent: snow ? "#0A1424" : autumn ? "#2A1608" : ground
    readonly property color accentFill: light ? Qt.rgba(accent.r * 0.18 + 0.8, accent.g * 0.18 + 0.78, accent.b * 0.18 + 0.72, 1)
                                              : Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: light ? Qt.rgba(accent.r * 0.1 + 0.86, accent.g * 0.1 + 0.84, accent.b * 0.1 + 0.79, 1)
                                             : Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // Everything is drawn at its real size (Qt and the system already handle high-DPI screens), times
    // the Size setting (50% to 200%, Ctrl+= and Ctrl+-). See System::uiScale.
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
    readonly property int rowHeight: px(42)

    // Avatars: a soft color per person, picked from their name so it never changes. Your own is gold.
    readonly property var avatarTones: hc ? [{ bg: "#1F1F1F", fg: "#FFFFFF" }]
        : snow ? [{ bg: "#3D5A80", fg: "#F4F8FD" }, { bg: "#6A4E6E", fg: "#F4F8FD" }, { bg: "#3E6560", fg: "#F4F8FD" }, { bg: "#5A5470", fg: "#F4F8FD" }, { bg: "#6E5A44", fg: "#F4F8FD" }]
        : autumn ? [{ bg: "#3E4A34", fg: "#CFE0B8" }, { bg: "#3A4458", fg: "#C4D2EC" }, { bg: "#5A3326", fg: "#F0B8A4" }, { bg: "#4A3650", fg: "#DEC6EC" }, { bg: "#2F4A4A", fg: "#B8E0E0" }]
        : light ? [{ bg: "#D5E4D3", fg: "#2E5A33" }, { bg: "#D6DDEB", fg: "#2F4A78" }, { bg: "#EED5CF", fg: "#86392A" }, { bg: "#E2D6EA", fg: "#5D3D78" }, { bg: "#D0E5E7", fg: "#235A60" }]
        : [{ bg: "#2C3A2C", fg: "#B6D6B6" }, { bg: "#2D3340", fg: "#B9C6E0" }, { bg: "#3A2724", fg: "#E6A596" }, { bg: "#33293A", fg: "#D2BCE0" }, { bg: "#24363A", fg: "#AED4D8" }]
    readonly property var avatarMine: hc ? ({ bg: "#1F1F1F", fg: "#FFFFFF" }) : light ? ({ bg: "#EADDBB", fg: "#6B5216" }) : snow ? ({ bg: "#3A3626", fg: "#F0D597" }) : autumn ? ({ bg: "#5A4020", fg: "#F0CF8A" }) : ({ bg: "#3A3220", fg: "#E0C27F" })
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
    // light or dark text on it, whichever reads better.
    readonly property var pictureColours: ["#C9A35E", "#3D6A9E", "#8A5A3E", "#4E8A4E", "#7A4E9E", "#8A8A8A"]
    function avatarFrom(hex) {
        const h = String(hex || "")
        if (!validColor(h)) return avatarMine || { bg: raised, fg: text }
        const r = parseInt(h.substr(1, 2), 16) / 255, g = parseInt(h.substr(3, 2), 16) / 255, b = parseInt(h.substr(5, 2), 16) / 255
        const bright = r * 0.299 + g * 0.587 + b * 0.114 > 0.55
        return { bg: h, fg: bright ? "#1E1A14" : "#FFFFFF" }
    }

}
