import QtQuick
import QtQuick.Shapes
import Huginn

// Line icons drawn on a 24-unit grid, so they stay sharp at any size and take any color.
Item {
    id: icon
    property string name: "file"
    property color color: Theme.textMuted
    property real lineWidth: 1.6
    property int size: Theme.px(24)

    width: size
    height: size

    readonly property var paths: ({
        "folder": "M3 6h7l2 2h9v11H3z",
        "file": "M6 3h8l4 4v14H6z M14 3v4h4",
        "image": "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6",
        "network": "M3 5h18v10H3z M8 19h8 M12 15v4",
        "phone": "M8 3h8v18H8z M11 18h2",
        // An upright handset (earpiece top left, mouthpiece bottom right), not the hang-up shape.
        "call": "M6.5 3h3l1.5 4.5-2.2 1.6a11 11 0 0 0 6.1 6.1l1.6-2.2 4.5 1.5v3a2 2 0 0 1-2.2 2C11.6 18.9 5.1 12.4 4.5 5.2A2 2 0 0 1 6.5 3z",
        "hangup": "M3 15c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-2.5a10 10 0 0 0-6 0V16l-3.5 1.5z",
        "hash": "M10 4l-2 16 M16 4l-2 16 M4.5 9h15 M3.5 15h15",
        "speaker": "M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6",
        // Notifications: a bell, and struck through for a muted chat.
        "bell": "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 20a2 2 0 0 0 4 0",
        "belloff": "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 20a2 2 0 0 0 4 0 M3 3l18 18",
        "crown": "M4 17l-1-9 5 4 4-7 4 7 5-4-1 9z M4 20h16",
        "tag": "M3 12V4h8l10 10-8 8z M7.5 8a0.5 0.5 0 1 0 0.01 0",
        "gear": "M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1",
        "power": "M12 3v8 M7 6.5a7.5 7.5 0 1 0 10 0",
        "send": "M4 12l16-8-6 16-2-6z M12 14l8-10",
        "shield": "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
        "attach": "M20 12l-8 8a5 5 0 0 1-7-7l9-9a3.5 3.5 0 0 1 5 5l-9 9a2 2 0 0 1-3-3l8-8",
        "people": "M9 11a3.5 3.5 0 1 0 0.01 0 M3 20a6 6 0 0 1 12 0 M16 11a3 3 0 1 0 0.01 0 M16 15a5 5 0 0 1 5 5",
        // One person: Contacts on the rail.
        "person": "M12 4.5a4 4 0 1 0 0.01 0 M4.5 20.5a7.5 7.5 0 0 1 15 0z",
        "check": "M5 12l5 5 9-10",
        "back": "M15 5l-7 7 7 7",
        "forward": "M9 5l7 7-7 7",
        "lock": "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3",
        "info": "M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1",
        "plus": "M12 5v14 M5 12h14",
        "qr": "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2",
        "play": "M7 4l13 8-13 8z",
        "stop": "M7 7h10v10H7z",
        "mic": "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3",
        "reply": "M10 6l-6 6 6 6 M4 12h10a6 6 0 0 1 6 6v1",
        "smile": "M12 3a9 9 0 1 0 0.01 0 M8 14a5 5 0 0 0 8 0 M9 9.5h0.01 M15 9.5h0.01",
        "edit": "M4 20h4L19 9l-4-4L4 16z M14 6l4 4",
        "pin": "M9 3h6l-1 6 4 4H6l4-4z M12 13v8",
        "thread": "M4 5h16v10H9l-5 4z M8 9h8 M8 12h5",
        "search": "M11 4a7 7 0 1 0 0.01 0 M20 20l-3.5-3.5",
        "close": "M5 5l14 14 M19 5L5 19",
        "down": "M5 9l7 7 7-7",
        // The desktop panels.
        "sidebar": "M3 5h18v14H3z M9 5v14",
        "headphones": "M4 16v-4a8 8 0 0 1 16 0v4 M4 14h3v6H4z M17 14h3v6h-3z",
        "deafened": "M4 16v-4a8 8 0 0 1 16 0v4 M4 14h3v6H4z M17 14h3v6h-3z M3 3l18 18",
        "micoff": "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M3 3l18 18",
        "video": "M3 7h12v10H3z M15 11l6-3v8l-6-3z",
        "keyboard": "M3 7h18v10H3z M6.5 10.5h1 M10 10.5h1 M13.5 10.5h1 M17 10.5h1 M7 14h10",
        "moon": "M19 14.5A8 8 0 1 1 9.5 5a6.5 6.5 0 0 0 9.5 9.5z",
        "screen": "M3 5h18v11H3z M8 20h8 M12 16v4",
        // Three dots in a column: an item's one menu.
        "more": "M12 4.5a1 1 0 1 0 0.01 0 M12 11a1 1 0 1 0 0.01 0 M12 17.5a1 1 0 1 0 0.01 0"
    })

    Shape {
        anchors.fill: parent
        preferredRendererType: Shape.CurveRenderer
        ShapePath {
            strokeColor: icon.color
            strokeWidth: icon.lineWidth * Math.max(1, icon.size / 28)
            fillColor: "transparent"
            capStyle: ShapePath.SquareCap
            joinStyle: ShapePath.MiterJoin
            scale: Qt.size(icon.size / 24, icon.size / 24)
            PathSvg { path: icon.paths[icon.name] || icon.paths["file"] }
        }
    }
}
