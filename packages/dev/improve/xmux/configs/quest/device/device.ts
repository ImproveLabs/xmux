import { ADB } from "@-/com.npmjs.appium-adb";
import appiumLogger from "@-/com.npmjs.appium__logger";
import { resolveAndroidSdk } from "@-/dev.improve.xmux.configs.android.sdk";
import { pickQuestSerial } from "#select";

export type Quest = { adb: ADB; serial: string };

function sdkRoot(): string | undefined {
  try {
    return resolveAndroidSdk();
  } catch {
    return undefined; // appium-adb then looks for adb on PATH.
  }
}

/** Connects to the headset chosen by {@link pickQuestSerial}. */
export async function connectQuest(serial: string | undefined): Promise<Quest> {
  // appium-adb logs every adb invocation at debug level; XMUX_ADB_LOG=debug brings that back.
  appiumLogger.level = process.env.XMUX_ADB_LOG ?? "warn";
  const root = sdkRoot();
  const adb = await ADB.createADB(root === undefined ? {} : { sdkRoot: root });
  const devices = await adb.getConnectedDevices({ verbose: true });
  const chosen = pickQuestSerial(devices, serial ?? process.env.QUEST_SERIAL);
  adb.setDeviceId(chosen);
  return { adb, serial: chosen };
}

/** `adb shell` that returns undefined instead of throwing when the command exits non-zero. */
export async function shellOrUndefined(adb: ADB, cmd: string[]): Promise<string | undefined> {
  try {
    return await adb.shell(cmd);
  } catch {
    return undefined;
  }
}

/** `--serial` flag shared by every Quest command. */
export const serialArg = {
  serial: {
    description:
      "adb serial (USB serial or ip:5555); defaults to QUEST_SERIAL or the only Quest online",
    type: "string",
  },
} as const;
