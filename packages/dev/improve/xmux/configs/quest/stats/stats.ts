import type { ADB } from "@-/com.npmjs.appium-adb";
import { shellOrUndefined } from "@-/dev.improve.xmux.configs.quest.device";
import {
  type GfxInfo,
  type TopRow,
  type VrApiSample,
  parseBatteryLevel,
  parseGfxInfo,
  parseTopResumedActivity,
  parseTopRow,
  parseVrApiLine,
  parseXmuxMetrics,
} from "@-/dev.improve.xmux.configs.quest.parse";

export type QuestStats = {
  packageName: string;
  pid: number | undefined;
  focusedActivity: string | undefined;
  /** The most recent per-second runtime samples for this process, oldest first. */
  vrapi: VrApiSample[];
  gfx: GfxInfo | undefined;
  top: TopRow | undefined;
  xmux: Record<string, number> | undefined;
  batteryPercent: number | undefined;
};

/** Collects runtime (VrApi), HWUI and process stats for one package. */
export async function collectStats(
  adb: ADB,
  packageName: string,
  samples = 3,
): Promise<QuestStats> {
  const pidText = await shellOrUndefined(adb, ["pidof", packageName]);
  const pid =
    pidText === undefined || pidText.trim() === ""
      ? undefined
      : Number(pidText.trim().split(/\s+/)[0]);
  const vrapiLog =
    pid === undefined
      ? ""
      : await adb.adbExec(["logcat", "-d", "--pid", String(pid), "-s", "VrApi"]);
  const xmuxLog = await adb.adbExec(["logcat", "-d", "-s", "XMUX"]);
  const xmuxLines = xmuxLog
    .split("\n")
    .map((l) => parseXmuxMetrics(l))
    .filter((m) => m !== undefined);
  return {
    packageName,
    pid,
    focusedActivity: parseTopResumedActivity(
      (await shellOrUndefined(adb, ["dumpsys", "activity", "activities"])) ?? "",
    ),
    vrapi: vrapiLog
      .split("\n")
      .map((l) => parseVrApiLine(l))
      .filter((s) => s !== undefined)
      .slice(-samples),
    gfx: parseGfxInfo((await shellOrUndefined(adb, ["dumpsys", "gfxinfo", packageName])) ?? ""),
    top: parseTopRow((await shellOrUndefined(adb, ["top", "-b", "-n", "1"])) ?? "", packageName),
    xmux: xmuxLines.at(-1),
    batteryPercent: parseBatteryLevel((await shellOrUndefined(adb, ["dumpsys", "battery"])) ?? ""),
  };
}

export function formatStats(stats: QuestStats): string {
  const lines = [
    `${stats.packageName} pid=${stats.pid ?? "not running"} focused=${stats.focusedActivity ?? "?"}`,
  ];
  if (stats.xmux !== undefined) {
    lines.push(
      `  xmux: ${Object.entries(stats.xmux)
        .map(([k, v]) => `${k}=${v}`)
        .join(" ")}`,
    );
  }
  for (const s of stats.vrapi) {
    lines.push(
      `  runtime: fps=${s.fps}/${s.targetFps} stale=${s.stale} early=${s.early} app=${s.appMs}ms cpu+gpu=${s.cpuGpuMs}ms layers=${s.layers} gpu=${Math.round(s.gpu * 100)}% cpu=${Math.round(s.cpu * 100)}% temp=${s.temperatureC}C`,
    );
  }
  if (stats.gfx !== undefined) {
    const g = stats.gfx;
    lines.push(
      `  hwui: frames=${g.framesRendered} janky=${g.jankyFrames} p50=${g.p50Ms}ms p90=${g.p90Ms}ms p95=${g.p95Ms}ms p99=${g.p99Ms}ms`,
    );
  }
  if (stats.top !== undefined)
    lines.push(`  process: cpu=${stats.top.cpuPercent}% rss=${stats.top.rss}`);
  if (stats.batteryPercent !== undefined) lines.push(`  battery: ${stats.batteryPercent}%`);
  return lines.join("\n");
}
