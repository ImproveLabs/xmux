import { describe, expect, it } from "vitest";
import { resolveAndroidSdk } from "@-/dev.improve.xmux.configs.android.sdk";

const base = { env: {}, home: "/Users/me", platform: "darwin" as const, exists: () => false };

describe("resolveAndroidSdk", () => {
  it("prefers ANDROID_HOME, then ANDROID_SDK_ROOT", () => {
    expect(
      resolveAndroidSdk({ ...base, env: { ANDROID_HOME: "/a", ANDROID_SDK_ROOT: "/b" } }),
    ).toBe("/a");
    expect(resolveAndroidSdk({ ...base, env: { ANDROID_SDK_ROOT: "/b" } })).toBe("/b");
  });

  it("ignores empty variables and falls back to the Android Studio default", () => {
    const exists = (p: string) => p === "/Users/me/Library/Android/sdk";
    expect(resolveAndroidSdk({ ...base, env: { ANDROID_HOME: "" }, exists })).toBe(
      "/Users/me/Library/Android/sdk",
    );
    expect(
      resolveAndroidSdk({
        ...base,
        platform: "linux",
        home: "/home/me",
        exists: (p) => p === "/home/me/Android/Sdk",
      }),
    ).toBe("/home/me/Android/Sdk");
  });

  it("explains what it looked for when nothing is found", () => {
    expect(() => resolveAndroidSdk(base)).toThrow(/Set ANDROID_HOME/);
  });
});
