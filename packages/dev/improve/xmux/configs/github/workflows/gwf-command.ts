import { defineCommand } from "@-/com.npmjs.citty-universal";
import { generateWorkflows } from "#gwf";

export const gwfCommand = defineCommand({
  meta: {
    description: "Generate GitHub Actions workflows from *.wac.ts sources.",
    name: "gwf",
  },
  run: generateWorkflows,
});
