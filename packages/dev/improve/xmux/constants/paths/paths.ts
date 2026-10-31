import path from "@-/com.npmjs.pathe";
import { fileURLToPath } from "node:url";

/** Filesystem locations shared by xmux tooling. Resolved statically from this file's position. */
export const PATHS = {
  get monorepoRoot(): string {
    return path.resolve(fileURLToPath(new URL("../../../../../../", import.meta.url)));
  },
  get android(): string {
    return path.join(this.monorepoRoot, "packages/dev/improve/xmux/android");
  },
  get bridge(): string {
    return path.join(this.monorepoRoot, "packages/dev/improve/xmux/bridge");
  },
  get fixtures(): string {
    return path.join(this.monorepoRoot, "packages/dev/improve/xmux/testing/fixtures");
  },
  get results(): string {
    return path.join(this.monorepoRoot, "results");
  },
} as const;
