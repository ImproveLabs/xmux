import { consola } from "@-/com.npmjs.consola";
import path from "@-/com.npmjs.pathe";
import { runGradle } from "@-/dev.improve.xmux.lib.gradle";
import { APPS, parseAppName, releaseApk } from "@-/dev.improve.xmux.configs.quest._lib.apps";
import { connectQuest } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";

export type QuestInstallOptions = {
  app: string | undefined;
  build: boolean;
  serial: string | undefined;
  launchArgs: readonly string[];
};

export async function runQuestInstall(options: QuestInstallOptions): Promise<void> {
  const app = parseAppName(options.app);
  const { activity, module, packageName } = APPS[app];
  if (options.build) await runGradle([`:${module}:assembleRelease`, "--quiet"]);
  const { adb, serial } = await connectQuest(options.serial);
  await adb.install(path.join(PATHS.apps, releaseApk(app)), {
    replace: true,
    timeout: 180_000,
  });
  await adb.forceStop(packageName);
  await adb.shell(["am", "start", "-n", `${packageName}/${activity}`, ...options.launchArgs]);
  consola.success(`installed and launched ${packageName} on ${serial}`);
}
