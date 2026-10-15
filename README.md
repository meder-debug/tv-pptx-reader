# PPTX Reader for Android projectors

PPTX Reader is an Android app for presenting PowerPoint (`.pptx`, `.ppsx`) and PDF files full
screen on projectors, TVs and TV boxes, controlled with the device's remote. It runs on Android 7
and newer.

**[Download the latest APK](https://github.com/meder-debug/tv-pptx-reader/releases/latest)**, then
follow [Installing on the projector](#installing-on-the-projector).

- **Opens .pptx directly.** It has its own slide renderer: themes, masters and layouts, text with
  bullets, numbering and autofit, all 187 PowerPoint shapes, pictures, gradients, textures, groups,
  tables with table styles, charts (bar/column, line, area, scatter, pie, doughnut) and SmartArt.
  Hidden slides are skipped. PDFs use Android's built-in PDF renderer.
- **Click animations.** Lines, pictures and shapes set to appear (or disappear) *On Click* in
  PowerPoint come in one press of OK at a time, so a slide can reveal a question first and its
  answer next. Effects a click on a shape would trigger come as extra presses at the end.
- **Zoom.** ▲ magnifies the slide; the arrows move around it. Zoomed views are redrawn sharp,
  for PDFs too.
- **Video and sound** embedded in a .pptx play when their click comes (or by themselves, if set to
  start automatically). The volume keys set their volume.
- **Gets files three ways:** a USB stick or the projector's storage (*Browse USB & storage*);
  a phone on the same Wi-Fi (scan the QR code on the home screen, pick a file, and it opens
  automatically); or *Open with* from the projector's own file manager.
- **Phone clicker:** the page the QR code opens also has big *Back / Next* buttons, and a
  *Pause / play* button while a video plays.

## Remote control

| Key | Action |
| --- | --- |
| ▶ / ▼ / OK | next click of the slide's animations, then the next slide |
| ◀ | take back the last click, then the previous slide |
| ▲ | zoom in (2×) |
| hold OK (or MENU) | grid of all slides; pick one with the arrows + OK |
| number keys | jump to that slide |
| BACK | close the grid, then leave the presentation |

While zoomed in, the arrows move around the slide, OK zooms in further (up to 4×), and BACK zooms
out a step; at normal size the keys work as above again.

On the home screen, hold OK on a file to remove it from the list (files sent from a phone are deleted).

## Installing on the projector

1. Copy `PPTX-Reader.apk` to a USB stick and plug it into the projector.
2. Open the projector's file manager, find the APK and choose **Install**. If it is blocked,
   allow installs from unknown sources when asked (or under *Settings → Security*).
3. Start **PPTX Reader** from the app list. The first time you use *Browse*, choose **Allow** so it
   can read the USB stick.

## Limits

Animations decide what is visible at each click, but their motion is not played: entrances and
exits fade in and out, emphasis effects and motion paths are skipped, and charts and SmartArt
appear whole instead of piece by piece. Slide transitions are not played. Video and sound linked
from outside the .pptx can't play, nor can formats the device has no decoder for (such as WMV).
Fonts are mapped to the projector's own fonts, so line breaks can differ slightly from PowerPoint.
Equations and EMF/WMF pictures are not shown. Old binary `.ppt` files are not supported. For
pixel-perfect output, export the deck to PDF and open the PDF (PDFs have no animations).

The phone upload page is only reachable on your local network while the app is open. Anyone on
that network can send a file or change slides.

## Building

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (signed with the debug key)
```

Debug builds include `RenderDumpActivity`, which renders every deck in
`Android/data/com.tvpptx.reader/files/decks` to PNGs in `.../files/dump`. It is handy for checking rendering.
Extras: `--ez steps true` also renders every click of each slide's animations, and `--ef zoom 2`
renders the middle of each slide magnified.

## License

MIT, see [LICENSE](LICENSE). Third-party parts:
- [ZXing](https://github.com/zxing/zxing) (QR codes), Apache License 2.0.
- `app/src/main/assets/presets.txt`, the shape geometry, converted from the ECMA-376 / ISO/IEC 29500
  `presetShapeDefinitions.xml`.

PowerPoint is a trademark of Microsoft. This project is not affiliated with Microsoft.
