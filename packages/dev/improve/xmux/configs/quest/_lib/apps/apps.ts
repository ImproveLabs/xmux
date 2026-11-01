/** Installable Quest apps under apps/: Gradle module, Android package, launcher activity. */
export const APPS = {
  app: {
    activity: "dev.improve.xmux.app.MainActivity",
    directory: "xmux-quest-app",
    module: "xmux-quest-app",
    packageName: "dev.improve.xmux.quest",
  },
  stress: {
    activity: "dev.improve.xmux.stress.MainActivity",
    directory: "testing/xmux-quest-stress",
    module: "xmux-quest-stress",
    packageName: "dev.improve.xmux.stress",
  },
} as const;

export type AppName = keyof typeof APPS;

export function isAppName(value: string): value is AppName {
  return Object.hasOwn(APPS, value);
}

/** Reads an optional `app` positional; defaults to the store app. */
export function parseAppName(value: string | undefined): AppName {
  const name = value ?? "app";
  if (!isAppName(name))
    throw new Error(`Unknown app "${name}"; expected one of ${Object.keys(APPS).join(", ")}.`);
  return name;
}

export function releaseApk(app: AppName): string {
  const { directory, module } = APPS[app];
  return `${directory}/build/outputs/apk/release/${module}-release.apk`;
}

/** Positional for commands that act on one app. */
export const appArg = {
  app: {
    description: "app (standalone app) or stress (rendering stress test)",
    required: false,
    type: "positional",
  },
} as const;
