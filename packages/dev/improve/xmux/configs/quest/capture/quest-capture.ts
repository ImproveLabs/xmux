import { consola } from "@-/com.npmjs.consola";
import path from "@-/com.npmjs.pathe";
import { captureScreenshot } from "@-/dev.improve.xmux.configs.quest._lib.capture";
import { connectQuest } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";

export type QuestCaptureOptions = {
  out: string;
  serial: string | undefined;
};

export async function runQuestCapture(options: QuestCaptureOptions): Promise<void> {
  const { adb } = await connectQuest(options.serial);
  consola.success(await captureScreenshot(adb, path.resolve(PATHS.monorepoRoot, options.out)));
}
