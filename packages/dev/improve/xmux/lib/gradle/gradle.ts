import { execa } from "@-/com.npmjs.execa";
import path from "@-/com.npmjs.pathe";
import { resolveAndroidSdk } from "@-/dev.improve.xmux.lib.android-sdk";
import { protoJavaHome } from "@-/dev.improve.xmux.lib.proto-bin";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";

/**
 * Runs the Gradle wrapper at the repo root, streaming its output, with the Android SDK resolved and
 * JAVA_HOME pointed at the JDK pinned in .prototools (falling back to the caller's JAVA_HOME when
 * proto is not installed).
 */
export async function runGradle(args: readonly string[]): Promise<void> {
  const env: Record<string, string> = { ANDROID_HOME: resolveAndroidSdk() };
  const javaHome = await protoJavaHome();
  if (javaHome !== undefined) env.JAVA_HOME = javaHome;
  await execa(path.join(PATHS.monorepoRoot, "gradlew"), [...args], {
    cwd: PATHS.monorepoRoot,
    env,
    stdio: "inherit",
  });
}
