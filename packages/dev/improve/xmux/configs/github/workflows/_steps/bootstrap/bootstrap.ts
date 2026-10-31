import type { Step } from "@github-actions-workflow-ts/lib";
import { checkoutStep } from "@-/dev.improve.xmux.configs.github.workflows._steps.checkout";
import { installDependenciesStep } from "@-/dev.improve.xmux.configs.github.workflows._steps.install-dependencies";
import { setupToolchainStep } from "@-/dev.improve.xmux.configs.github.workflows._steps.setup-toolchain";
import { setupVitePlusStep } from "@-/dev.improve.xmux.configs.github.workflows._steps.setup-vite-plus";

/** Every job starts the same way, so local and CI use identical tool versions. */
export const bootstrapSteps: Step[] = [
  checkoutStep,
  setupToolchainStep,
  setupVitePlusStep,
  installDependenciesStep,
];
