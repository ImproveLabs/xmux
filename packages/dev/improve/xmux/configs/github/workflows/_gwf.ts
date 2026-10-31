#!/usr/bin/env bun
/**
 * `vpx _gwf`: regenerates .github/workflows/*.yml from the `*.wac.ts` sources in this package.
 * Runs on every `vp install` (root postinstall); never edit the generated YAML by hand.
 */
import {
  getConfigAsync,
  getWorkflowFilePaths,
  importWorkflowFile,
  relativePath,
  resolveRootDir,
  writeWorkflowJSONToYamlFiles,
} from "@github-actions-workflow-ts/cli";
import { defineCommand, runMain } from "@-/com.npmjs.citty-universal";
import { PATHS } from "@-/dev.improve.xmux.constants.paths";

const mainCommand = defineCommand({
  meta: {
    description: "Generate GitHub Actions workflows from *.wac.ts sources.",
    name: "gwf",
  },
  async run() {
    process.chdir(PATHS.monorepoRoot);
    const config = (await getConfigAsync()) ?? {};
    const files = getWorkflowFilePaths() ?? [];
    const rootDir = resolveRootDir(config);
    const createdDirectories = new Set<string>();
    let count = 0;
    for (const filePath of files) {
      const workflows = await importWorkflowFile(filePath);
      count += writeWorkflowJSONToYamlFiles(
        workflows,
        relativePath(filePath),
        config,
        rootDir,
        createdDirectories,
      );
    }
    process.stdout.write(
      `[_gwf] generated ${count} workflow file(s) from ${files.length} source(s)\n`,
    );
  },
});

await runMain(mainCommand);
