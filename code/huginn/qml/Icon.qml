import QtQuick
import Huginn

// Line icons on a 24-unit grid, in any size and color. Each name, size, color and line width is drawn once
// (IconProvider, icons.cpp, where the shapes are) and the picture is shared by every Icon that looks the same.
Item {
    id: icon
    property string name: "file"
    property color color: Theme.textMuted
    property real lineWidth: 1.6
    property int size: Theme.px(24)

    width: size
    height: size

    Image {
        anchors.fill: parent
        // The color without its "#" (a "#" would end the address): rrggbb, or aarrggbb when see-through.
        source: icon.size > 0 ? "image://icon/" + (icon.name || "file") + "/" + icon.size + "/" + String(icon.color).substring(1) + "/" + icon.lineWidth : ""
        sourceSize.width: icon.size
        sourceSize.height: icon.size
        smooth: true
    }
}
