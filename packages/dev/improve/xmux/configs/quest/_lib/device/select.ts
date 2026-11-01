import type { VerboseDevice } from "@-/com.npmjs.appium-adb";

/**
 * Picks the headset to talk to: an explicit serial wins, then QUEST_SERIAL, then the only online
 * device whose model is a Quest (USB serial or `ip:5555` after `adb tcpip`). Pure for testing.
 */
export function pickQuestSerial(
  devices: readonly VerboseDevice[],
  requested: string | undefined,
): string {
  if (requested !== undefined && requested.length > 0) return requested;
  const quests = devices.filter((d) => d.state === "device" && d.model.startsWith("Quest"));
  if (quests.length === 1 && quests[0] !== undefined) return quests[0].udid;
  const seen = devices.map((d) => `${d.udid} (${d.model || "?"}, ${d.state})`).join(", ") || "none";
  if (quests.length === 0) {
    throw new Error(
      `No Quest is online over adb (devices: ${seen}). Plug it in or run \`adb connect <ip>:5555\`.`,
    );
  }
  throw new Error(`Several Quests are online (${seen}); pass --serial or set QUEST_SERIAL.`);
}
