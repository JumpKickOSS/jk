// SPDX-License-Identifier: Apache-2.0
import { isRecord } from "./http";
import { JkNotFound, runJk, type JkRun } from "./jkProcess";

/** Where the engine serves HTTP and the bearer token it accepts. */
export interface EngineEndpoint {
  /** The engine's HTTP base, always ending in `/` (`http://127.0.0.1:8910/`). */
  readonly url: string;
  readonly token: string;
  /** The engine's identity id (`GET /api/identity`); `""` when jk did not say. */
  readonly engineId: string;
}

/** cmd.exe's exit status for a command it cannot find. */
const WINDOWS_NOT_FOUND = 9009;

/**
 * Ask the user's own jk for its engine: `jk web -O jsonl` starts the engine when none is running and
 * prints `{url, token, engineId}`. The token comes from the engine's owner-only file through jk, so
 * the extension never reads the engine's state directory and always lands on the engine this
 * machine's jk uses: a WSL distro's or a container's own when the window is remote.
 */
export async function discover(bin: string, cwd: string, run: typeof runJk = runJk): Promise<EngineEndpoint> {
  const result = await run(bin, ["web", "-O", "jsonl"], cwd);
  if (process.platform === "win32" && result.code === WINDOWS_NOT_FOUND) {
    throw new JkNotFound(bin, `'${bin}' was not found. Install jk, or set jk.path in your user settings.`);
  }
  return parseEndpoint(result);
}

/** The endpoint from `jk web -O jsonl`'s output; throws with jk's own words when it has none. */
export function parseEndpoint(result: JkRun): EngineEndpoint {
  const line = lastJsonLine(result.stdout);
  if (line && typeof line["url"] === "string" && line["url"] !== "") {
    const url = line["url"].endsWith("/") ? line["url"] : line["url"] + "/";
    const token = typeof line["token"] === "string" ? line["token"] : "";
    if (token === "") throw new Error("the jk engine's token could not be read");
    return { url, token, engineId: typeof line["engineId"] === "string" ? line["engineId"] : "" };
  }
  const why =
    (line && typeof line["error"] === "string" && line["error"]) ||
    result.stderr.trim().split(/\r?\n/).pop() ||
    `jk web exited ${result.code}`;
  throw new Error(why);
}

function lastJsonLine(stdout: string): Record<string, unknown> | undefined {
  const lines = stdout.split(/\r?\n/).filter((l) => l.trim().startsWith("{"));
  for (let i = lines.length - 1; i >= 0; i--) {
    try {
      const v: unknown = JSON.parse(lines[i] ?? "");
      if (isRecord(v)) return v;
    } catch {
      // not the object we want
    }
  }
  return undefined;
}
