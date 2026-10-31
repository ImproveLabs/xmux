import path from "@-/com.npmjs.pathe";
import { existsSync } from "node:fs";
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
  exists: existsSync,
});

/**
 * The Android SDK is the one toolchain piece proto does not manage. Honour ANDROID_HOME, then the
 * legacy ANDROID_SDK_ROOT, then the default location Android Studio installs to.
 */
export function resolveAndroidSdk(
  environment: AndroidSdkEnvironment = defaultEnvironment(),
): string {
  const { env, home, platform, exists } = environment;
  for (const candidate of [env.ANDROID_HOME, env.ANDROID_SDK_ROOT]) {
    if (candidate !== undefined && candidate.length > 0) return candidate;
  }
  const fallback =
    platform === "darwin"
      ? path.join(home, "Library", "Android", "sdk")
      : platform === "win32"
        ? path.join(home, "AppData", "Local", "Android", "Sdk")
        : path.join(home, "Android", "Sdk");
  if (exists(fallback)) return fallback;
  throw new Error(
    `Android SDK not found. Set ANDROID_HOME (looked at ANDROID_HOME, ANDROID_SDK_ROOT and ${fallback}).`,
  );
}
