#!/usr/bin/env bun
/**
 * `vpx _gradle <args...>`: the Gradle wrapper at the repo root with ANDROID_HOME and JAVA_HOME set.
 * Arguments pass through untouched, including Gradle's own --help and --version.
 */
import { runMain } from "@-/com.npmjs.citty-universal";
import { gradleCommand } from "#gradle-command";

await runMain(gradleCommand);
