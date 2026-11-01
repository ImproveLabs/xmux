/** Glob paths for a trigger; GitHub requires at least one. */
export type Paths = [string, ...string[]];

/** Paths shared by every workflow's trigger. */
export const toolingPaths: Paths = [
  ".prototools",
  "package.json",
  "bun.lock",
  "vite.config.ts",
  "packages/com/**",
  "packages/dev/improve/xmux/configs/**",
  "packages/dev/improve/xmux/lib/**",
];

/** Run on pushes to main and on pull requests that touch `paths`. */
export function onChanges(paths: Paths) {
  return {
    pull_request: { paths },
    push: { branches: ["main"] as [string], paths },
  };
}
