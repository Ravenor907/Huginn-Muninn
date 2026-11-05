import QtQuick
import Huginn

// The message a reply answers, above the reply: a bar, who wrote it and the start of it. Tap to go there.
Rectangle {
    id: quote
    property string who: ""
    property string what: ""
    // As wide as the words, up to this (the column it sits in; set it when loaded, where the parent is a Loader).
    property real limit: parent ? parent.width : Theme.px(400)
    signal jump()
    height: Theme.px(30)
    width: Math.min(limit, quoteText.implicitWidth + Theme.px(26))
    radius: Theme.radius
    color: qHover.hovered ? Theme.raised : Theme.panel
    Accessible.role: Accessible.Button
    Accessible.name: "Reply to " + who + ": " + what
    Rectangle { width: Theme.px(3); height: parent.height; radius: 1; color: Theme.accent }
    Text {
        id: quoteText
        x: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        width: Math.min(implicitWidth, quote.width - Theme.px(20))
        elide: Text.ElideRight
        textFormat: Text.PlainText
        text: quote.who + ": " + quote.what.replace(/\n/g, " ")
        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
    }
    HoverHandler { id: qHover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: quote.jump() }
}
