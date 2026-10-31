/** Installable apps in packages/dev/improve/xmux/android/: Gradle module, Android package, launcher activity. */
export const APPS = {
  app: { activity: ".MainActivity", module: "app", packageName: "dev.xmux.quest" },
  sample: { activity: ".MainActivity", module: "sample-embed", packageName: "dev.xmux.sample" },
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
  const { module } = APPS[app];
  return `${module}/build/outputs/apk/release/${module}-release.apk`;
}

/** Positional for commands that act on one app. */
export const appArg = {
  app: {
    description: "app (store app) or sample (embedding sample)",
    required: false,
    type: "positional",
  },
} as const;
