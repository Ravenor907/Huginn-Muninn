import QtQuick
import Huginn

// A person's round picture, or a round disc in their color with their initial. Pictures are round
// in every look. key is the chat key, or "me". showDot adds their status dot in the corner: green
// online, amber away, red do not disturb, grey offline, nothing when it isn't known.
Item {
    id: av
    property string key: ""
    property string name: ""
    property int size: Theme.px(32)
    property bool showDot: false
    // What the dot is cut out of: the surface behind the avatar.
    property color backdrop: Theme.panel
    // Dimmed: an offline member, a blocked contact.
    property bool dim: false
    width: size; height: size

    readonly property bool mine: key === "me"
    // Bumps when the picture or status behind this key changes, so the bindings below read again.
    property int rev: 0
    Connections {
        target: Profile
        function onPictureChanged(k) { if (k === av.key) av.rev++ }
        function onStatusChanged(k) { if (k === av.key) av.rev++ }
    }
    readonly property url pictureUrl: (rev, key !== "" ? Profile.pictureUrl(key) : "")
    readonly property bool hasPicture: String(pictureUrl) !== ""
    readonly property string status: (rev, showDot && key !== "" ? Profile.statusOf(key) : "")
    // Their color: the one sent with their profile, else one picked from their name.
    readonly property var tone: {
        rev
        let hex = key === "" ? "" : mine ? Profile.myColour : Profile.colourOf(key)
        if (mine && !Theme.validColor(hex)) hex = Profile.colourOf("me")
        if (mine && !Theme.validColor(hex)) hex = String(Theme.accentBase)
        if (Theme.validColor(hex) && !Theme.hc) return Theme.avatarFrom(hex)
        return Theme.avatar(name, mine)
    }
    readonly property string initial: { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    Accessible.role: Accessible.Graphic
    Accessible.name: name + (status === "online" ? ", online" : status === "away" ? ", away" : status === "dnd" ? ", do not disturb" : status === "offline" ? ", offline" : "")

    // The initial on their color (also what shows until a picture loads).
    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: av.tone && av.tone.bg ? av.tone.bg : Theme.raised
        opacity: av.dim ? 0.5 : 1
        visible: !av.hasPicture || pic.status !== Image.Ready
        Text {
            textFormat: Text.PlainText
            anchors.centerIn: parent
            text: av.initial
            color: av.tone && av.tone.fg ? av.tone.fg : Theme.text
            font.family: Theme.body; font.pixelSize: Math.round(av.size * 0.42); font.weight: Font.DemiBold
        }
    }
    // The picture: Profile's image provider already makes it round.
    Image {
        id: pic
        anchors.fill: parent
        visible: av.hasPicture && status === Image.Ready
        asynchronous: true
        // Kept once made: the version in its URL brings a new one after a change.
        cache: true
        smooth: true; mipmap: true
        fillMode: Image.PreserveAspectFit
        sourceSize.width: 256; sourceSize.height: 256
        source: av.hasPicture ? av.pictureUrl : ""
        opacity: av.dim ? 0.5 : 1
    }
    // The status dot, cut out of the corner.
    Rectangle {
        visible: av.showDot && av.status !== ""
        anchors.right: parent.right; anchors.bottom: parent.bottom
        anchors.rightMargin: -Math.round(av.size * 0.06); anchors.bottomMargin: -Math.round(av.size * 0.06)
        width: Math.max(Theme.px(10), Math.round(av.size * 0.36)); height: width; radius: width / 2
        color: av.status === "online" ? Theme.online : av.status === "away" ? Theme.away : av.status === "dnd" ? Theme.dnd : Theme.offline
        border.width: Math.max(2, Math.round(width * 0.2))
        border.color: av.backdrop
    }
}
