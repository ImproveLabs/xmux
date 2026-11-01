import { execa } from "@-/com.npmjs.execa";
import path from "@-/com.npmjs.pathe";
import { PATHS } from "@-/dev.improve.xmux.lib.paths";

/**
 * `proto bin <tool>` prints the executable path; when proto detects an AI agent it prints NDJSON
 * records instead (`{"type":"message","message":"<path>"}`). Accept both.
 */
export function parseProtoBinOutput(stdout: string): string | undefined {
  for (const line of stdout.split("\n").map((l) => l.trim())) {
    if (line.length === 0) continue;
    if (!line.startsWith("{")) return line;
    try {
      const record = JSON.parse(line) as { type?: string; message?: unknown };
      if (
        record.type === "message" &&
        typeof record.message === "string" &&
        path.isAbsolute(record.message)
      ) {
        return record.message;
      }
    } catch {
      // Not a record; keep looking.
    }
  }
  return undefined;
}

/** Path of the executable proto resolves for `tool` in this repo, or undefined without proto. */
export async function protoBin(tool: string): Promise<string | undefined> {
  try {
    const { stdout } = await execa("proto", ["bin", tool], { cwd: PATHS.monorepoRoot });
    return parseProtoBinOutput(stdout);
  } catch {
    return undefined;
  }
}

/** JAVA_HOME for the JDK pinned in .prototools (`<home>/bin/java` -> `<home>`). */
export async function protoJavaHome(): Promise<string | undefined> {
  const java = await protoBin("jdk");
  return java === undefined ? undefined : path.dirname(path.dirname(java));
}
