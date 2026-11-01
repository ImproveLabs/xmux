import { defineCommand } from "@-/com.npmjs.citty-universal";
import { serialArg } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { runQuestCapture } from "#quest-capture";

/** `vpx _quest-capture [--out results/quest]`: a headset screenshot (the headset must be worn). */
export const captureCommand = defineCommand({
  args: {
    out: {
      default: "results/quest",
      description: "directory to save into, relative to the repo root",
      type: "string",
    },
    ...serialArg,
  },
  meta: {
    description: "Take a headset screenshot (headset must be worn) and pull it.",
    name: "quest-capture",
  },
  run({ args }) {
    return runQuestCapture(args);
  },
});
