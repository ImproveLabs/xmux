import { defineCommand } from "@-/com.npmjs.citty-universal";
import { consola } from "@-/com.npmjs.consola";
import path from "@-/com.npmjs.pathe";
import { connectQuest, serialArg } from "@-/dev.improve.xmux.configs.quest.device";
import { PATHS } from "@-/dev.improve.xmux.constants.paths";
import { captureScreenshot } from "#capture";

/** `vpx _quest-capture [--out results/quest]`: a headset screenshot (the headset must be worn). */
export const captureCommand = defineCommand({
  args: {
    out: {
      default: path.join("results", "quest"),
      description: "directory to save into, relative to the repo root",
      type: "string",
    },
    ...serialArg,
  },
  meta: {
    description: "Take a headset screenshot (headset must be worn) and pull it.",
    name: "quest-capture",
  },
  async run({ args }) {
    const { adb } = await connectQuest(args.serial);
    consola.success(await captureScreenshot(adb, path.resolve(PATHS.monorepoRoot, args.out)));
  },
});
