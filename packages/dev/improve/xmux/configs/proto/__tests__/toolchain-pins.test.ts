import { describe, expect, it } from "vitest";
import { parse } from "@-/com.npmjs.smol-toml";
import { PATHS } from "@-/dev.improve.xmux.constants.paths";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const read = (relative: string) => readFileSync(join(PATHS.monorepoRoot, relative), "utf8");
const prototools = parse(read(".prototools")) as Record<string, unknown>;

describe(".prototools", () => {
  it("pins every language the repo builds with", () => {
    for (const tool of ["bun", "node", "rust", "jdk"])
      expect(prototools[tool], tool).toEqual(expect.any(String));
  });

  it("agrees with rust-toolchain.toml", () => {
    const toolchain = parse(read("rust-toolchain.toml")) as {
      toolchain: { channel: string };
    };
    expect(toolchain.toolchain.channel).toBe(prototools.rust);
  });

  it("agrees with package.json packageManager", () => {
    const pkg = JSON.parse(read("package.json")) as { packageManager: string };
    expect(pkg.packageManager).toBe(`bun@${String(prototools.bun)}`);
  });

  it("builds Android with JDK 17", () => {
    expect(String(prototools.jdk)).toMatch(/-17\./);
  });
});
