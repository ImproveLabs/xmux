import { defineCommand } from "@-/com.npmjs.citty-universal";
import { appArg } from "@-/dev.improve.xmux.configs.quest._lib.apps";
import { serialArg } from "@-/dev.improve.xmux.configs.quest._lib.device";
import { runQuestInstall } from "#quest-install";

/** Everything after `--` is passed to `am start`, e.g. `-- --ei count 16`. */
function afterDoubleDash(rawArgs: readonly string[]): string[] {
  const i = rawArgs.indexOf("--");
  return i === -1 ? [] : rawArgs.slice(i + 1);
}

/** `vpx _quest-install [app|stress] [--no-build] [-- am start extras]`. */
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
  run({ args, rawArgs }) {
    return runQuestInstall({ ...args, launchArgs: afterDoubleDash(rawArgs) });
  },
});
