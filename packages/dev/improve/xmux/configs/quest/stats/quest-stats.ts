import { APPS, parseAppName } from "@-/dev.improve.xmux.configs.quest._lib.apps";
import { connectQuest } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { collectStats, formatStats } from "@-/dev.improve.xmux.configs.quest._lib.stats";

export type QuestStatsOptions = {
  app: string | undefined;
  json: boolean | undefined;
  serial: string | undefined;
};

export async function runQuestStats(options: QuestStatsOptions): Promise<void> {
  const { adb } = await connectQuest(options.serial);
  const stats = await collectStats(adb, APPS[parseAppName(options.app)].packageName);
  process.stdout.write(`${options.json ? JSON.stringify(stats, null, 2) : formatStats(stats)}\n`);
}
