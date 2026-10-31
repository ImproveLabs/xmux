import { describe, expect, it } from "vitest";
import { pickQuestSerial } from "@-/dev.improve.xmux.configs.quest.device";

const quest = {
  udid: "1WMHH000000000",
  state: "device",
  model: "Quest_3",
  product: "eureka",
  device: "eureka",
};
const wifiQuest = { ...quest, udid: "192.168.1.50:5555" };
const emulator = {
  udid: "emulator-5554",
  state: "device",
  model: "sdk_gphone64_arm64",
  product: "sdk_gphone64_arm64",
  device: "emu64a",
};

describe("pickQuestSerial", () => {
  it("uses an explicit serial even if it is not listed", () => {
    expect(pickQuestSerial([], "10.0.0.2:5555")).toBe("10.0.0.2:5555");
  });

  it("picks the only online Quest and ignores emulators and offline entries", () => {
    expect(pickQuestSerial([emulator, quest], undefined)).toBe("1WMHH000000000");
    expect(pickQuestSerial([emulator, { ...quest, state: "offline" }, wifiQuest], undefined)).toBe(
      "192.168.1.50:5555",
    );
  });

  it("refuses to guess between several Quests or when none is online", () => {
    expect(() => pickQuestSerial([quest, wifiQuest], undefined)).toThrow(/Several Quests/);
    expect(() => pickQuestSerial([emulator], undefined)).toThrow(/No Quest is online/);
  });
});
