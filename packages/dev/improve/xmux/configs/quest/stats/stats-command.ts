import { defineCommand } from "@-/com.npmjs.citty-universal";
import { APPS, appArg, parseAppName } from "@-/dev.improve.xmux.configs.quest.apps";
import { connectQuest, serialArg } from "@-/dev.improve.xmux.configs.quest.device";
import { collectStats, formatStats } from "#stats";

/** `vpx _quest-stats [app|sample]`: runtime (VrApi), HWUI and process stats for an app. */
export const statsCommand = defineCommand({
  args: {
    ...appArg,
    json: { description: "print JSON instead of text", type: "boolean" },
    ...serialArg,
  },
  meta: {
    description: "Print runtime (VrApi), HWUI and process stats for an app on the Quest.",
    name: "quest-stats",
  },
  async run({ args }) {
    const { adb } = await connectQuest(args.serial);
    const stats = await collectStats(adb, APPS[parseAppName(args.app)].packageName);
    process.stdout.write(`${args.json ? JSON.stringify(stats, null, 2) : formatStats(stats)}\n`);
  },
});
