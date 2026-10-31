import { defineCommand } from "@-/com.npmjs.citty-universal";
import { consola } from "@-/com.npmjs.consola";
import { APPS } from "@-/dev.improve.xmux.configs.quest.apps";
import { connectQuest, serialArg } from "@-/dev.improve.xmux.configs.quest.device";
import { collectStats, formatStats } from "@-/dev.improve.xmux.configs.quest.stats";

/** `vpx _quest-stress <count> [--active 4] [--fps 30]`: the sample with N windows, then stats. */
export const stressCommand = defineCommand({
  args: {
    active: { default: "4", description: "windows that keep animating", type: "string" },
    count: { description: "number of windows (default 16)", required: false, type: "positional" },
    fps: { default: "30", description: "demo tick rate", type: "string" },
    json: { description: "print JSON instead of text", type: "boolean" },
    settle: { default: "35", description: "seconds to wait before sampling", type: "string" },
    ...serialArg,
  },
  meta: {
    description:
      "Launch the sample with N windows (M animating) and report stats after it settles.",
    name: "quest-stress",
  },
  async run({ args }) {
    const { activity, packageName } = APPS.sample;
    const { adb } = await connectQuest(args.serial);
    await adb.adbExec(["logcat", "-c"]);
    await adb.forceStop(packageName);
    await adb.shell(["dumpsys", "gfxinfo", packageName, "reset"]).catch(() => undefined);
    const count = args.count ?? "16";
    await adb.shell([
      "am",
      "start",
      "-n",
      `${packageName}/${activity}`,
      "--ei",
      "count",
      count,
      "--ei",
      "active",
      args.active,
      "--ei",
      "fps",
      args.fps,
    ]);
    consola.info(
      `started ${count} windows (${args.active} animating at ${args.fps} Hz); sampling in ${args.settle}s`,
    );
    await new Promise((resolve) => setTimeout(resolve, Number(args.settle) * 1000));
    const stats = await collectStats(adb, packageName);
    process.stdout.write(`${args.json ? JSON.stringify(stats, null, 2) : formatStats(stats)}\n`);
  },
});
