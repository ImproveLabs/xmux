import { Step } from "@github-actions-workflow-ts/lib";

export const setupVitePlusStep = new Step({
  name: "Set up Vite+",
  uses: "voidzero-dev/setup-vp@v1",
  with: { "run-install": false },
});
