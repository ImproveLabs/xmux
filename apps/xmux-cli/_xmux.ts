#!/usr/bin/env bun
import { execa } from "@-/com.npmjs.execa";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";
import { fileURLToPath } from "node:url";

// Keep the caller's working directory so relative trace and frame paths work as usual.
const { exitCode } = await execa(
  "cargo",
  [
    "run",
    "--quiet",
    "--manifest-path",
    fileURLToPath(new URL("./Cargo.toml", import.meta.url)),
    "--bin",
    "xmux",
    "--",
    ...process.argv.slice(2),
  ],
  {
    env: { XMUX_REPO_ROOT: PATHS.monorepoRoot },
    reject: false,
    stdio: "inherit",
  },
);

process.exitCode = exitCode ?? 1;
