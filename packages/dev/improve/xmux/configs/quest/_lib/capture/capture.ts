import type { ADB } from "@-/com.npmjs.appium-adb";
import fs from "@-/com.npmjs.fs-extra";
import path from "@-/com.npmjs.pathe";
import { shellOrUndefined } from "@-/dev.improve.xmux.configs.quest._lib.device";
import {
  parseTopResumedActivity,
  systemScreenBlocking,
} from "@-/dev.improve.xmux.configs.quest._lib.parse";

const SCREENSHOTS = "/sdcard/Oculus/Screenshots";

async function newestScreenshot(adb: ADB): Promise<string | undefined> {
  const listing = await shellOrUndefined(adb, ["ls", "-t", SCREENSHOTS]);
  return listing
    ?.split("\n")
    .map((l) => l.trim())
    .find((l) => l.length > 0);
}

/**
 * Takes a headset screenshot through Horizon OS's own capture service and pulls it. Works only
 * while the headset is worn; never fakes the proximity sensor (that leaves the headset believing
 * it is doffed, with the cameras off, until it is taken off and put back on).
 */
export async function captureScreenshot(
  adb: ADB,
  outDir: string,
  deadlineMs = 30_000,
): Promise<string> {
  const blocking = systemScreenBlocking(
    parseTopResumedActivity(
      (await shellOrUndefined(adb, ["dumpsys", "activity", "activities"])) ?? "",
    ),
  );
  if (blocking !== undefined)
    throw new Error(`The headset is showing ${blocking}; a capture would only show that.`);

  const before = await newestScreenshot(adb);
  await adb.shell([
    "am",
    "startservice",
    "-n",
    "com.oculus.metacam/.capture.CaptureService",
    "-a",
    "TAKE_SCREENSHOT",
  ]);
  const started = Date.now();
  while (Date.now() - started < deadlineMs) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    const newest = await newestScreenshot(adb);
    if (newest !== undefined && newest !== before) {
      await fs.ensureDir(outDir);
      const local = path.join(outDir, newest);
      await adb.pull(`${SCREENSHOTS}/${newest}`, local);
      return local;
    }
  }
  throw new Error(
    `No new screenshot within ${deadlineMs / 1000}s. Horizon OS only captures while the headset is worn.`,
  );
}
