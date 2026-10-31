import { describe, expect, it } from "vitest";
import { parseProtoBinOutput } from "@-/dev.improve.xmux.configs.proto.bin";

const JAVA = "/Users/me/.proto/tools/jdk/temurin-17.0.20+8/Contents/Home/bin/java";

describe("parseProtoBinOutput", () => {
  it("reads the plain path proto prints in a terminal", () => {
    expect(parseProtoBinOutput(`${JAVA}\n`)).toBe(JAVA);
  });

  it("reads the NDJSON proto prints for agents, skipping notices", () => {
    const ndjson = [
      '{"type":"message","message":"Detected an AI agent environment, printing as NDJSON."}',
      `{"type":"message","message":"${JAVA}"}`,
    ].join("\n");
    expect(parseProtoBinOutput(ndjson)).toBe(JAVA);
  });

  it("returns undefined when nothing looks like a path", () => {
    expect(parseProtoBinOutput('{"type":"error","message":"missing executable"}')).toBeUndefined();
    expect(parseProtoBinOutput("")).toBeUndefined();
  });
});
