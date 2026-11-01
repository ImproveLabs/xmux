import { defineCommand } from "@-/com.npmjs.citty-universal";
import { runGradleScript } from "#gradle";

export const gradleCommand = defineCommand({
  // Claim these flags so citty forwards them to Gradle instead of handling them itself.
  args: {
    help: { alias: "h", description: "Show Gradle help", type: "boolean" },
    version: { alias: "v", description: "Show the Gradle version", type: "boolean" },
  },
  meta: {
    description: "Run Gradle with the Android SDK and pinned JDK configured.",
    name: "gradle",
  },
  run({ rawArgs }) {
    return runGradleScript(rawArgs);
  },
});
