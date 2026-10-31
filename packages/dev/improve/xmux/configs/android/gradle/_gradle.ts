#!/usr/bin/env bun
/**
 * `vpx _gradle <args...>`: the Gradle wrapper at the repo root with ANDROID_HOME and JAVA_HOME set.
 * Arguments pass through untouched (no CLI parser, so Gradle's own --help and --version work).
 */
import { runGradle } from "#gradle";

await runGradle(process.argv.slice(2));
