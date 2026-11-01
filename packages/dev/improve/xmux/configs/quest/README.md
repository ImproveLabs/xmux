# quest

Helpers for running xmux on a Meta Quest over adb (USB or Wi-Fi). One package per command:

| Package | Bin | Does |
|---|---|---|
| `install` | `_quest-install [app\|stress] [--no-build] [-- am start extras]` | builds the release APK with Gradle, installs, force-stops, launches |
| `stress` | `_quest-stress [count] --active 4 --fps 30 --settle 35` | launches the stress-test app with N windows, waits, prints stats |
| `stats` | `_quest-stats [app\|stress] [--json]` | runtime (VrApi), HWUI (`gfxinfo`) and process stats |
| `capture` | `_quest-capture [--out results/quest]` | headset screenshot through Horizon OS's capture service |

Helper packages live under `_lib/`: `device` (picks and connects to the headset with appium-adb),
`parse` (pure parsers for Horizon OS output, tested against real Quest 3 output), `apps` (installable
apps), `stats` (collects and formats runtime stats), and `capture` (captures and pulls screenshots).

Device selection: `--serial`, then `QUEST_SERIAL`, then the only online device whose model is a Quest.
`XMUX_ADB_LOG=debug` shows every adb call.

Screenshots only work while the headset is worn. Never fake the proximity sensor
(`com.oculus.vrpowermanager.prox_*`): it leaves the headset believing it is doffed, cameras off, until
it is taken off and put back on.
