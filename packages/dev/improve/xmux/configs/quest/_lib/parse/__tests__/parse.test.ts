import { describe, expect, it } from "vitest";
import {
  parseBatteryLevel,
  parseGfxInfo,
  parseTopResumedActivity,
  parseTopRow,
  parseVrApiLine,
  parseXmuxMetrics,
  systemScreenBlocking,
} from "@-/dev.improve.xmux.configs.quest._lib.parse";

// Real output captured from a Quest 3 on 2026-10-30 (32 windows, 4 animating).
const VRAPI =
  "10-30 11:18:27.004 21936 22054 I VrApi   : FPS=91/90,Prd=17ms,Tear=0,Early=0,Stale=0,Stale2/5/10/max=0/0/0/0,VSnc=1,Lat=-4,Fov=0,CPU4/GPU=3/2,1651/456MHz,OC=3F,TA=0/0/0,SP=N/N/N,Mem=3196MHz,Free=2062MB,PLS=0,Temp=52.0C/0.0C,TW=2.30ms,App=1.83ms,GD=0.00ms,CPU&GPU=2.67ms,LCnt=16(DR90,LM4),GPU%=0.42,CPU%=0.42(W0.46),CFL=12.02/16.15,ICFLp95=15.32,LD=0,SF=1.00,DpuScale=1.00,LP=0,DVFS=0,Preempt=422,PoseAgeP95=0.00";

const GFXINFO = `Stats since: 1234ns
Total frames rendered: 3891
Janky frames: 1148 (29.50%)
Janky frames (legacy): 1170 (30.07%)
50th percentile: 7ms
90th percentile: 38ms
95th percentile: 44ms
99th percentile: 61ms
Number Missed Vsync: 0`;

const TOP = `  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
31699 u0_a136      20   0 8.0G 252M 182M S 40.7   3.2   0:41.79 dev.improve.xmux.quest
 5403 u0_a135      20   0 6.0G  83M  55M S  0.0   1.0   0:00.13 dev.improve.xmux.iwsdk.pwa`;

describe("parseVrApiLine", () => {
  it("extracts the fields the stress table reports", () => {
    expect(parseVrApiLine(VRAPI)).toEqual({
      pid: 21936,
      fps: 91,
      targetFps: 90,
      early: 0,
      stale: 0,
      appMs: 1.83,
      cpuGpuMs: 2.67,
      layers: 16,
      gpu: 0.42,
      cpu: 0.42,
      temperatureC: 52,
      freeMb: 2062,
    });
  });

  it("ignores other tags and non-stats VrApi lines", () => {
    expect(parseVrApiLine("10-30 11:18:27.004 21936 22054 I XMUX    : windows=32")).toBeUndefined();
    expect(
      parseVrApiLine("10-30 11:18:27.004 21936 22054 I VrApi   : vrapi_Initialize"),
    ).toBeUndefined();
  });
});

describe("parseGfxInfo", () => {
  it("reads totals and percentiles", () => {
    expect(parseGfxInfo(GFXINFO)).toEqual({
      framesRendered: 3891,
      jankyFrames: 1148,
      p50Ms: 7,
      p90Ms: 38,
      p95Ms: 44,
      p99Ms: 61,
    });
  });

  it("returns undefined when the process has no stats", () => {
    expect(parseGfxInfo("No process found for: dev.improve.xmux.quest")).toBeUndefined();
  });
});

describe("parseTopRow", () => {
  it("finds the row for a package by its last column", () => {
    expect(parseTopRow(TOP, "dev.improve.xmux.quest")).toEqual({
      pid: 31699,
      cpuPercent: 40.7,
      rss: "252M",
      name: "dev.improve.xmux.quest",
    });
    expect(parseTopRow(TOP, "dev.improve.xmux.stress")).toBeUndefined();
  });
});

describe("activity and system screens", () => {
  it("reads the resumed activity", () => {
    const text =
      "      topResumedActivity=ActivityRecord{429bf55 u0 dev.improve.xmux.quest/.MainActivity t1604}";
    expect(parseTopResumedActivity(text)).toBe("dev.improve.xmux.quest/.MainActivity");
    expect(parseTopResumedActivity("nothing here")).toBeUndefined();
  });

  it("flags the Guardian dialog and the doffed screen, nothing else", () => {
    expect(
      systemScreenBlocking(
        "com.oculus.guardian/com.oculus.vrguardianservice.guardiandialog.GuardianDialogActivity",
      ),
    ).toMatch(/Guardian/);
    expect(systemScreenBlocking("com.oculus.os.clearactivity/.ClearActivity")).toMatch(/doffed/);
    expect(systemScreenBlocking("dev.improve.xmux.quest/.MainActivity")).toBeUndefined();
    expect(systemScreenBlocking(undefined)).toBeUndefined();
  });
});

describe("misc", () => {
  it("reads the battery level", () => {
    expect(parseBatteryLevel("  AC powered: true\n  level: 6\n  scale: 100")).toBe(6);
  });

  it("reads the stress-test app's metrics line", () => {
    expect(
      parseXmuxMetrics("windows=16 ticks=713 framesDrawnTotal=2884 drawCallsLastFrameTotal=2142"),
    ).toEqual({
      windows: 16,
      ticks: 713,
      framesDrawnTotal: 2884,
      drawCallsLastFrameTotal: 2142,
    });
    expect(parseXmuxMetrics("placed 16 windows")).toBeUndefined();
  });
});
