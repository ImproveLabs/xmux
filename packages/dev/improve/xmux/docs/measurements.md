# Measurements and device recipes

Numbers from the 2026-10-30 prototype on a Quest 3, reproduced with `vpx _quest-stress <count> --active 4` and `vpx _quest-stats`.


HWUI frame stats for the panel's View hierarchy (record + issue time per redrawn frame):

```sh
adb -s "$QUEST_SERIAL" shell dumpsys gfxinfo dev.improve.xmux.quest reset
sleep 30
adb -s "$QUEST_SERIAL" shell dumpsys gfxinfo dev.improve.xmux.quest | sed -n '/Total frames/,/deadline missed (legacy)/p'
```

2026-10-30, demo redrawing every row at 30 Hz, release build: p50 6 ms, p90 9 ms, p99 11 ms,
0 janky frames over 1329 frames; process ~40% of one core (demo compute included), 252 MB RSS.
Before frame-wide glyph batching (one `drawGlyphs` per color change): p50 7 ms, p99 14 ms.

Screenshots: `adb exec-out screencap -p` returns an empty file on Horizon OS, and Android Studio's
mirroring agent (DisplayStreamer) aborts with SIGABRT. Use the headset's own capture (Meta button + trigger, or `am startservice -n com.oculus.metacam/.capture.CaptureService -a TAKE_SCREENSHOT`,
which only completes while the headset is worn) and pull `/sdcard/Oculus/Screenshots/`.


## Multi-window stress (2026-10-30, 90 Hz, 4 windows animating at 30 Hz, the rest idle)

| Windows | Runtime fps | Stale frames/s | Compositor layers | GPU | App CPU |
|---|---|---|---|---|---|
| 1 | 91 | 0 | 4 | 45% | 28% |
| 16 | 90 | 0 | 14 | 41% | 47% |
| 32 | 90 | 0 to 4 | 27 | 78% | 90% |
| 64 | rendered; not measured (headset slept) | | | | |

Findings: the runtime accepted 27 layers (the "16 layer" limit did not bite); GPU, not CPU, is the
ceiling, so tiering (smaller textures or mesh mode for far/idle windows) is the lever. All 16 windows
redrawing at 30 Hz saturates the main thread (HWUI p50 28 ms) and starves the Spatial SDK frame loop,
which shows as a black view: the app must cap redraw work per frame.

## Headset screenshots

`vpx _quest-capture` uses Horizon OS's own capture service and only works while the headset is
worn. Do not fake the proximity sensor (`com.oculus.vrpowermanager.prox_*` broadcasts): that leaves the
headset believing it is doffed, with the cameras off and the display dark, until it is taken off and
put back on.
