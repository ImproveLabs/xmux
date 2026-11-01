import { defineCommand } from "@-/com.npmjs.citty-universal";
import { appArg } from "@-/dev.improve.xmux.configs.quest._lib.apps";
import { serialArg } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { runQuestStats } from "#quest-stats";

/** `vpx _quest-stats [app|stress]`: runtime (VrApi), HWUI and process stats for an app. */
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
  run({ args }) {
    return runQuestStats(args);
  },
});
