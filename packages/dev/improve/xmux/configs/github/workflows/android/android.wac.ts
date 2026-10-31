import { NormalJob, Step, Workflow } from "@github-actions-workflow-ts/lib";
import {
  bootstrapSteps,
  onChanges,
  type Paths,
  toolingPaths,
  vpRun,
} from "@-/dev.improve.xmux.configs.github.workflows._steps";

const paths: Paths = [
  "packages/dev/improve/xmux/android/**",
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle/**",
  "gradlew",
  "packages/dev/improve/xmux/schema/**",
  "packages/dev/improve/xmux/assets/**",
  "packages/dev/improve/xmux/testing/fixtures/**",
  ...toolingPaths,
  ".github/workflows/android.yml",
];

const build = new NormalJob("build", { "runs-on": "ubuntu-latest" }).addSteps([
  ...bootstrapSteps,
  new Step({ name: "Cache Gradle", uses: "gradle/actions/setup-gradle@v4" }),
  vpRun("android:build"),
  vpRun("android:test"),
  vpRun("android:lint"),
  new Step({
    if: "always()",
    name: "Upload test reports",
    uses: "actions/upload-artifact@v4",
    with: {
      name: "android-test-reports",
      path: "packages/dev/improve/xmux/android/**/build/reports/tests/",
    },
  }),
]);

export const androidWorkflow = new Workflow("android", {
  name: "android",
  on: onChanges(paths),
}).addJob(build);
