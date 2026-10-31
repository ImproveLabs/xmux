import { Step } from "@github-actions-workflow-ts/lib";

export const installDependenciesStep = new Step({
  name: "Install dependencies",
  run: "vp install",
});
