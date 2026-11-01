/**
 * Pure parsers for the text Horizon OS tools print. Kept free of I/O so the shapes are pinned by
 * tests against real output captured from a Quest 3 (see __tests__/parse.test.ts).
 */

/** One per-second line from the VrApi runtime (`adb logcat -s VrApi`). */
export type VrApiSample = {
  pid: number;
  fps: number;
  targetFps: number;
  early: number;
  stale: number;
  appMs: number;
  cpuGpuMs: number;
  layers: number;
  gpu: number;
  cpu: number;
  temperatureC: number;
  freeMb: number;
};

const LOGCAT_PREFIX = /^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+VrApi\s*:\s*(.*)$/;

function field(body: string, name: string): string | undefined {
  // Fields are comma-separated `Name=value`; names contain characters like `&` and `%`.
  for (const part of body.split(",")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq) === name) return part.slice(eq + 1);
  }
  return undefined;
}

function leadingNumber(value: string | undefined): number {
  if (value === undefined) return Number.NaN;
  const m = /^-?\d+(?:\.\d+)?/.exec(value);
  return m === null ? Number.NaN : Number(m[0]);
}

export function parseVrApiLine(line: string): VrApiSample | undefined {
  const m = LOGCAT_PREFIX.exec(line.trim());
  if (m === null) return undefined;
  const [, pid, body] = m;
  if (pid === undefined || body === undefined || !body.startsWith("FPS=")) return undefined;
  const [fps, targetFps] = (field(body, "FPS") ?? "").split("/").map(Number);
  return {
    pid: Number(pid),
    fps: fps ?? Number.NaN,
    targetFps: targetFps ?? Number.NaN,
    early: leadingNumber(field(body, "Early")),
    stale: leadingNumber(field(body, "Stale")),
    appMs: leadingNumber(field(body, "App")),
    cpuGpuMs: leadingNumber(field(body, "CPU&GPU")),
    layers: leadingNumber(field(body, "LCnt")),
    gpu: leadingNumber(field(body, "GPU%")),
    cpu: leadingNumber(field(body, "CPU%")),
    temperatureC: leadingNumber(field(body, "Temp")),
    freeMb: leadingNumber(field(body, "Free")),
  };
}

/** Frame statistics from `dumpsys gfxinfo <package>`. */
export type GfxInfo = {
  framesRendered: number;
  jankyFrames: number;
  p50Ms: number;
  p90Ms: number;
  p95Ms: number;
  p99Ms: number;
};

export function parseGfxInfo(text: string): GfxInfo | undefined {
  const num = (re: RegExp) => {
    const m = re.exec(text);
    return m?.[1] === undefined ? Number.NaN : Number(m[1]);
  };
  const framesRendered = num(/Total frames rendered:\s*(\d+)/);
  if (Number.isNaN(framesRendered)) return undefined;
  return {
    framesRendered,
    jankyFrames: num(/^Janky frames:\s*(\d+)/m),
    p50Ms: num(/50th percentile:\s*(\d+)ms/),
    p90Ms: num(/90th percentile:\s*(\d+)ms/),
    p95Ms: num(/95th percentile:\s*(\d+)ms/),
    p99Ms: num(/99th percentile:\s*(\d+)ms/),
  };
}

/** One process row from `top -b -n 1`. */
export type TopRow = { pid: number; cpuPercent: number; rss: string; name: string };

export function parseTopRow(text: string, name: string): TopRow | undefined {
  for (const line of text.split("\n")) {
    const cols = line.trim().split(/\s+/);
    if (cols.length < 12 || cols[cols.length - 1] !== name) continue;
    return { pid: Number(cols[0]), rss: cols[5] ?? "", cpuPercent: Number(cols[8]), name };
  }
  return undefined;
}

/** The focused activity from `dumpsys activity activities`, e.g. `dev.improve.xmux.quest/.MainActivity`. */
export function parseTopResumedActivity(text: string): string | undefined {
  const m = /topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)/.exec(text);
  return m?.[1];
}

/**
 * System screens that mean the headset is not showing the app: the Guardian dialog ("Finding
 * position in room", shown when the cameras see nothing) and the doffed ClearActivity.
 */
export function systemScreenBlocking(activity: string | undefined): string | undefined {
  if (activity === undefined) return undefined;
  if (activity.includes("GuardianDialogActivity"))
    return "the Guardian tracking dialog (cameras cannot see; turn on lights or put the headset on)";
  if (activity.includes("ClearActivity"))
    return "the doffed screen (the headset thinks it is not being worn)";
  return undefined;
}

export function parseBatteryLevel(text: string): number | undefined {
  const m = /^\s*level:\s*(\d+)/m.exec(text);
  return m?.[1] === undefined ? undefined : Number(m[1]);
}

/** `XMUX` log lines from the stress-test app: `windows=16 ticks=713 framesDrawnTotal=2884 ...`. */
export function parseXmuxMetrics(line: string): Record<string, number> | undefined {
  const pairs = [...line.matchAll(/(\w+)=(-?\d+(?:\.\d+)?)/g)];
  if (pairs.length === 0 || !line.includes("windows=")) return undefined;
  return Object.fromEntries(pairs.map(([, k, v]) => [k, Number(v)]));
}
