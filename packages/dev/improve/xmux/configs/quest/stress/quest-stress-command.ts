import { defineCommand } from "@-/com.npmjs.citty-universal";
import { serialArg } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { runQuestStress } from "#quest-stress";

/** `vpx _quest-stress <count> [--active 4] [--fps 30]`: the stress-test app with N windows, then stats. */
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
      "Launch the stress-test app with N windows (M animating) and report stats after it settles.",
    name: "quest-stress",
  },
  run({ args }) {
    return runQuestStress(args);
  },
});
