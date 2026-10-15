# PPTX Reader for Android projectors

Shows PowerPoint (`.pptx`, `.ppsx`) and PDF files full screen on an Android projector or TV
box (built for the Magcubic HY320 Mini, Android 11), driven by the projector's remote.

- **Opens .pptx directly.** It has its own slide renderer: themes, masters and layouts, text with
  bullets, numbering and autofit, all 187 PowerPoint shapes, pictures, gradients, textures, groups,
  tables with table styles, charts (bar/column, line, area, scatter, pie, doughnut) and SmartArt.
  Hidden slides are skipped. PDFs use Android's built-in PDF renderer.
- **Gets files three ways:** a USB stick or the projector's storage (*Browse USB & storage*);
  a phone on the same Wi-Fi (scan the QR code on the home screen, pick a file, and it opens
  automatically); or *Open with* from the projector's own file manager.
- **Phone clicker:** the page the QR code opens also has big *Back / Next* buttons.

## Remote control

| Key | Action |
| --- | --- |
| ▶ / ▼ / OK | next slide |
| ◀ / ▲ | previous slide |
| hold OK (or MENU) | grid of all slides; pick one with the arrows + OK |
| number keys | jump to that slide |
| BACK | close the grid, then leave the presentation |

On the home screen, hold OK on a file to remove it from the list (files sent from a phone are deleted).

## Installing on the projector

1. Copy `PPTX-Reader.apk` to a USB stick and plug it into the projector.
2. Open the projector's file manager, find the APK and choose **Install**. If it is blocked,
   allow installs from unknown sources when asked (or under *Settings → Security*).
3. Start **PPTX Reader** from the app list. The first time you use *Browse*, choose **Allow** so it
   can read the USB stick.

## Limits

Animations and slide transitions are not played (each slide appears complete). Fonts are mapped to
the projector's own fonts, so line breaks can differ slightly from PowerPoint. Video, audio,
equations, and EMF/WMF pictures are not shown. Old binary `.ppt` files are not supported. For
pixel-perfect output, export the deck to PDF and open the PDF.

The phone upload page is only reachable on your local network while the app is open. Anyone on
that network can send a file or change slides.

## Building

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (signed with the debug key)
```

Debug builds include `RenderDumpActivity`, which renders every deck in
`Android/data/com.tvpptx.reader/files/decks` to PNGs in `.../files/dump`. It is handy for checking rendering.

The shape geometry in `app/src/main/assets/presets.txt` is converted from the ECMA-376
`presetShapeDefinitions.xml`.
