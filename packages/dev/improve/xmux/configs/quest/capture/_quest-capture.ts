#!/usr/bin/env bun
import { runMain } from "@-/com.npmjs.citty-universal";
import { captureCommand } from "#capture-command";

await runMain(captureCommand);
