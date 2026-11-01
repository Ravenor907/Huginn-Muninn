import QtQuick
import Huginn

// Cap m2 inside a message (shared/docs/protocol.md section 12), the same as Muninn's ChatMore.kt: an album's
// photos side by side, a video message to play, a poll to vote in (counts, and who voted for what), and a
// link preview made by the sender (nothing is fetched here; clicking asks before the browser opens it).
// host: the page (its MoreLayer is host.more); where: the contact's key or "g:<group>:<channel>".
Column {
    id: parts
    property var host: null
    property var m: ({})
    property string where: ""
    readonly property bool live: !m.unsent && !m.system
    readonly property bool tour: !!host && !!host.isTour
    spacing: Theme.px(6)
    visible: live && (!!m.album || m.video > 0 || !!m.pollView || !!m.lp)

    // An album: 2 across for up to 4, else 3. Click one to see them all full size.
    Column {
        visible: parts.live && !!parts.m.album
        spacing: Theme.px(4)
        Grid {
            id: grid
            readonly property var photos: parts.m.album || []
            columns: photos.length <= 4 ? 2 : 3
            spacing: Theme.px(4)
            Repeater {
                model: grid.photos
                delegate: Rectangle {
                    // Shrinks to fit a narrow chat.
                    width: Math.min(Theme.px(grid.columns === 2 ? 140 : 96), Math.floor((parts.width - grid.spacing * (grid.columns - 1)) / grid.columns)); height: width
                    radius: Theme.radius; color: Theme.groundDeep; clip: true
                    Image {
                        anchors.fill: parent
                        fillMode: Image.PreserveAspectCrop
                        asynchronous: true
                        source: parts.tour ? "" : "image://thumb/" + modelData.photo
                    }
                    Accessible.role: Accessible.Button
                    Accessible.name: "Photo " + (index + 1) + " of " + grid.photos.length
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: parts.host.more.viewPhotos(grid.photos.map(p => p.photo), index) }
                }
            }
        }
        Text { textFormat: Text.PlainText; text: (parts.m.album || []).length + " photos"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
    }

    // A video message: a dark tile with a play mark and its length. Click to play it.
    Rectangle {
        visible: parts.live && parts.m.video > 0 && !!parts.m.photo
        width: Math.min(Theme.px(200), parts.width); height: Math.round(width * 0.75)
        radius: Theme.radius; color: "black"
        border.color: Theme.line; border.width: 1
        Rectangle {
            anchors.centerIn: parent
            width: Theme.px(52); height: width; radius: width / 2
            color: Qt.rgba(1, 1, 1, 0.14)
            Icon { anchors.centerIn: parent; name: "play"; color: "white"; size: Theme.px(26) }
        }
        Text {
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.margins: Theme.px(8)
            text: "Video message · " + Voice.length(parts.m.video || 0)
            color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        Accessible.role: Accessible.Button
        Accessible.name: "Play the video message, " + Voice.length(parts.m.video || 0)
        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: parts.host.more.playVideo(parts.m.photo) }
    }

    // A poll: click an answer to vote (again to take it back), with counts and bars.
    Rectangle {
        id: poll
        readonly property var p: parts.m.pollView || null
        visible: parts.live && !!p
        width: Math.min(parts.width, Theme.px(420))
        height: pollCol.implicitHeight + Theme.px(20)
        radius: Theme.radius; color: Theme.panel
        border.color: Theme.line; border.width: 1
        Column {
            id: pollCol
            x: Theme.px(12); y: Theme.px(10); width: parent.width - Theme.px(24)
            spacing: Theme.px(6)
            Text {
                width: parent.width; wrapMode: Text.Wrap; textFormat: Text.PlainText
                text: poll.p ? poll.p.q : ""
                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17); font.weight: Font.DemiBold
            }
            Repeater {
                model: poll.p ? poll.p.options : []
                delegate: Rectangle {
                    width: pollCol.width
                    height: optRow.implicitHeight + Theme.px(18)
                    radius: Theme.radius
                    color: modelData.mine ? Theme.accentFill : optHover.hovered ? Theme.raised : Theme.groundDeep
                    Row {
                        id: optRow
                        x: Theme.px(10); y: Theme.px(7); width: parent.width - Theme.px(20)
                        spacing: Theme.px(10)
                        Rectangle {
                            anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(18); height: width
                            radius: poll.p && poll.p.multi ? Theme.px(4) : width / 2
                            color: modelData.mine ? Theme.accent : "transparent"
                            border.color: modelData.mine ? Theme.accent : Theme.textMuted; border.width: 1.5
                            Icon { anchors.centerIn: parent; visible: modelData.mine; name: "check"; size: Theme.px(13); color: Theme.ground; lineWidth: 2.4 }
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - Theme.px(28) - countText.width - Theme.px(20)
                            wrapMode: Text.Wrap; textFormat: Text.PlainText
                            text: modelData.text
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                        }
                        Text {
                            textFormat: Text.PlainText
                            id: countText
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.n
                            color: modelData.n > 0 ? Theme.accent : Theme.textMuted
                            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                        }
                    }
                    Rectangle {
                        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(3)
                        x: Theme.px(10); width: parent.width - Theme.px(20); height: Theme.px(3); radius: height / 2
                        color: Theme.line
                        Rectangle { width: poll.p && poll.p.total > 0 ? parent.width * modelData.n / poll.p.total : 0; height: parent.height; radius: height / 2; color: Theme.accent }
                    }
                    HoverHandler { id: optHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: parts.choose(index) }
                    Accessible.role: poll.p && poll.p.multi ? Accessible.CheckBox : Accessible.RadioButton
                    Accessible.checked: modelData.mine
                    Accessible.name: modelData.text + ", " + modelData.n + (modelData.n === 1 ? " vote" : " votes")
                }
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width; wrapMode: Text.Wrap
                text: (poll.p ? poll.p.total : 0) + ((poll.p && poll.p.total === 1) ? " vote" : " votes") + (poll.p && poll.p.multi ? " · pick any" : "") + " · click to vote"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            Text {
                textFormat: Text.PlainText
                visible: !!poll.p && poll.p.total > 0
                text: "Who voted ›"
                color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                Accessible.role: Accessible.Button
                Accessible.name: "Who voted"
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: parts.whoVoted() }
            }
        }
    }

    // A link preview, made by the sender. Clicking asks before the browser opens it.
    Rectangle {
        id: card
        readonly property var lp: parts.m.lp || null
        visible: parts.live && !!lp
        width: Math.min(parts.width, Theme.px(420))
        height: Math.max(thumb.visible ? thumb.height : 0, cardCol.implicitHeight) + Theme.px(16)
        radius: Theme.radius; color: cardHover.hovered ? Theme.raised : Theme.panel
        border.color: Theme.line; border.width: 1
        Image {
            id: thumb
            visible: !!parts.m.lpThumb
            x: Theme.px(8); y: Theme.px(8)
            width: Theme.px(64); height: width
            fillMode: Image.PreserveAspectCrop
            source: parts.m.lpThumb || ""
        }
        Column {
            id: cardCol
            x: thumb.visible ? thumb.width + Theme.px(18) : Theme.px(12); y: Theme.px(8)
            width: parent.width - x - Theme.px(12)
            spacing: Theme.px(2)
            Text {
                width: parent.width; maximumLineCount: 2; elide: Text.ElideRight; wrapMode: Text.Wrap; textFormat: Text.PlainText
                text: card.lp ? card.lp.t || "" : ""
                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
            }
            Text {
                visible: text !== ""
                width: parent.width; maximumLineCount: 2; elide: Text.ElideRight; wrapMode: Text.Wrap; textFormat: Text.PlainText
                text: card.lp ? card.lp.d || "" : ""
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            Text {
                width: parent.width; elide: Text.ElideRight; textFormat: Text.PlainText
                text: parts.m.lpHost || ""
                color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
        }
        Accessible.role: Accessible.Link
        Accessible.name: (card.lp ? card.lp.t : "") + ", " + (parts.m.lpHost || "")
        HoverHandler { id: cardHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: if (card.lp) parts.host.more.askOpen(card.lp.u) }
    }

    // A click on an answer: single choice picks it (or takes it back); several toggles it.
    function choose(i) {
        const p = m.pollView
        if (!p || tour || where === "") return
        const mine = []
        for (let k = 0; k < p.options.length; k++) if (p.options[k].mine) mine.push(k)
        let next
        if (p.multi) next = mine.indexOf(i) >= 0 ? mine.filter(x => x !== i) : mine.concat([i]).sort((a, b) => a - b)
        else next = mine.indexOf(i) >= 0 ? [] : [i]
        const r = Chat.vote(where, m.id, next)
        if (r !== "") Ui.say(r, true)
    }
    function whoVoted() {
        const p = m.pollView
        if (!p) return
        Ui.pickFrom(p.q, [{ header: p.total + (p.total === 1 ? " vote" : " votes"), items: p.options.map(o => ({ name: o.text + " · " + o.n, detail: o.who || "Nobody yet", url: "" })) }], () => {})
    }
}
