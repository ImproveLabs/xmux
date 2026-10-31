import { Step } from "@github-actions-workflow-ts/lib";

/** Installs proto and every tool pinned in .prototools (bun, node, rust, jdk). */
export const setupToolchainStep = new Step({
  name: "Set up toolchain (proto)",
  uses: "moonrepo/setup-toolchain@v0",
  with: { "auto-install": true },
});
