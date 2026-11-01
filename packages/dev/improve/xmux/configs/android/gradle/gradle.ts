import { runGradle } from "@-/dev.improve.xmux.lib.gradle";

/** Runs the configured Gradle wrapper with every argument supplied by the caller. */
export async function runGradleScript(args: readonly string[]): Promise<void> {
  await runGradle(args);
}
