import { NormalJob, Step, Workflow } from "@github-actions-workflow-ts/lib";
import {
  bootstrapSteps,
  onChanges,
  type Paths,
  toolingPaths,
  vpRun,
} from "@-/dev.improve.xmux.configs.github.workflows._steps";

const paths: Paths = [...toolingPaths, "tsconfig.json", "wac.config.ts", ".github/workflows/**"];

const check = new NormalJob("check", { "runs-on": "ubuntu-latest" }).addSteps([
  ...bootstrapSteps,
  // `vp install` regenerated the YAML through the root postinstall; it must match what is committed.
  new Step({
    name: "Generated workflows are up to date",
    run: "git diff --exit-code -- .github/workflows",
  }),
  vpRun("ts:check"),
  vpRun("ts:test"),
]);

export const typescriptWorkflow = new Workflow("typescript", {
  name: "typescript",
  on: onChanges(paths),
}).addJob(check);
