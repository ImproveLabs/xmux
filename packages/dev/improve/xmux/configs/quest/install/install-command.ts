import { defineCommand } from "@-/com.npmjs.citty-universal";
import { consola } from "@-/com.npmjs.consola";
import path from "@-/com.npmjs.pathe";
import { runGradle } from "@-/dev.improve.xmux.configs.android.gradle";
import { APPS, appArg, parseAppName, releaseApk } from "@-/dev.improve.xmux.configs.quest.apps";
import { connectQuest, serialArg } from "@-/dev.improve.xmux.configs.quest.device";
import { PATHS } from "@-/dev.improve.xmux.constants.paths";

/** Everything after `--` is passed to `am start`, e.g. `-- --ei count 16`. */
function afterDoubleDash(rawArgs: readonly string[]): string[] {
  const i = rawArgs.indexOf("--");
  return i === -1 ? [] : rawArgs.slice(i + 1);
}

/** `vpx _quest-install [app|sample] [--no-build] [-- am start extras]`. */
export const installCommand = defineCommand({
  args: {
    ...appArg,
    build: {
      default: true,
      description: "build the release APK first (--no-build to skip)",
      type: "boolean",
    },
    ...serialArg,
  },
  meta: {
    description: "Build an app, install it on the Quest and launch it.",
    name: "quest-install",
  },
  async run({ args, rawArgs }) {
    const app = parseAppName(args.app);
    const { activity, module, packageName } = APPS[app];
    if (args.build) await runGradle([`:${module}:assembleRelease`, "--quiet"]);
    const { adb, serial } = await connectQuest(args.serial);
    await adb.install(path.join(PATHS.android, releaseApk(app)), {
      replace: true,
      timeout: 180_000,
    });
    await adb.forceStop(packageName);
    await adb.shell([
      "am",
      "start",
      "-n",
      `${packageName}/${activity}`,
      ...afterDoubleDash(rawArgs),
    ]);
    consola.success(`installed and launched ${packageName} on ${serial}`);
  },
});
