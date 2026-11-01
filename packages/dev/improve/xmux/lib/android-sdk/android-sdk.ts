import fs from "@-/com.npmjs.fs-extra";
import path from "@-/com.npmjs.pathe";
import { homedir } from "node:os";

export type AndroidSdkEnvironment = {
  env: Record<string, string | undefined>;
  home: string;
  platform: NodeJS.Platform;
  exists: (path: string) => boolean;
};

const defaultEnvironment = (): AndroidSdkEnvironment => ({
  env: process.env,
  home: homedir(),
  platform: process.platform,
  exists: fs.existsSync,
});

/**
 * The Android SDK is the one toolchain piece proto does not manage. Honour ANDROID_HOME, then the
 * default location Android Studio installs to.
 */
export function resolveAndroidSdk(
  environment: AndroidSdkEnvironment = defaultEnvironment(),
): string {
  const { env, home, platform, exists } = environment;
  if (env.ANDROID_HOME !== undefined && env.ANDROID_HOME.length > 0) return env.ANDROID_HOME;
  const fallback =
    platform === "darwin"
      ? path.join(home, "Library", "Android", "sdk")
      : platform === "win32"
        ? path.join(home, "AppData", "Local", "Android", "Sdk")
        : path.join(home, "Android", "Sdk");
  if (exists(fallback)) return fallback;
  throw new Error(
    `Android SDK not found. Set ANDROID_HOME (looked at ANDROID_HOME and ${fallback}).`,
  );
}
