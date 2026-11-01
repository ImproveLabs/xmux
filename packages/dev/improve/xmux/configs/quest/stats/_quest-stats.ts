#!/usr/bin/env bun
import { runMain } from "@-/com.npmjs.citty-universal";
import { statsCommand } from "#quest-stats-command";

await runMain(statsCommand);
