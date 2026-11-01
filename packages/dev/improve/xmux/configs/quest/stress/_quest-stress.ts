#!/usr/bin/env bun
import { runMain } from "@-/com.npmjs.citty-universal";
import { stressCommand } from "#quest-stress-command";

await runMain(stressCommand);
