#!/usr/bin/env bun
/**
 * `vpx _gwf`: regenerates .github/workflows/*.yml from the `*.wac.ts` sources in this package.
 * Runs on every `vp install` (root postinstall); never edit the generated YAML by hand.
 */
import { runMain } from "@-/com.npmjs.citty-universal";
import { gwfCommand } from "#gwf-command";

await runMain(gwfCommand);
