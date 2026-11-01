import {
  getConfigAsync,
  getWorkflowFilePaths,
  importWorkflowFile,
  relativePath,
  resolveRootDir,
  writeWorkflowJSONToYamlFiles,
} from "@github-actions-workflow-ts/cli";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";

export async function generateWorkflows(): Promise<void> {
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
}
