#!/usr/bin/env bun
import { runMain } from "@-/com.npmjs.citty-universal";
import { captureCommand } from "#quest-capture-command";

await runMain(captureCommand);
