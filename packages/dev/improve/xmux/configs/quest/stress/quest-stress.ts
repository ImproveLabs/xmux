import { consola } from "@-/com.npmjs.consola";
import { APPS } from "@-/dev.improve.xmux.configs.quest._lib.apps";
import { connectQuest } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { collectStats, formatStats } from "@-/dev.improve.xmux.configs.quest._lib.stats";

export type QuestStressOptions = {
  active: string;
  count: string | undefined;
  fps: string;
  json: boolean | undefined;
  settle: string;
  serial: string | undefined;
};

export async function runQuestStress(options: QuestStressOptions): Promise<void> {
  const { activity, packageName } = APPS.stress;
  const { adb } = await connectQuest(options.serial);
  await adb.adbExec(["logcat", "-c"]);
  await adb.forceStop(packageName);
  await adb.shell(["dumpsys", "gfxinfo", packageName, "reset"]).catch(() => undefined);
  const count = options.count ?? "16";
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
    options.active,
    "--ei",
    "fps",
    options.fps,
  ]);
  consola.info(
    `started ${count} windows (${options.active} animating at ${options.fps} Hz); sampling in ${options.settle}s`,
  );
  await new Promise((resolve) => setTimeout(resolve, Number(options.settle) * 1000));
  const stats = await collectStats(adb, packageName);
  process.stdout.write(`${options.json ? JSON.stringify(stats, null, 2) : formatStats(stats)}\n`);
}
