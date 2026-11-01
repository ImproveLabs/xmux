import { Step } from "@github-actions-workflow-ts/lib";

export const vpRun = (task: string, name = `vp run ${task}`): Step =>
  new Step({ name, run: `vp run ${task}` });
