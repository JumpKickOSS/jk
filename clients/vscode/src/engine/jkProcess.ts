// SPDX-License-Identifier: Apache-2.0
import { spawn, type ChildProcess } from "node:child_process";

export interface JkRun {
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
}

/** jk processes this window started that have not exited: stopped when the extension deactivates. */
const running = new Set<ChildProcess>();

export function stopAll(): void {
  for (const child of running) {
    try {
      child.kill();
    } catch {
      // already gone
    }
  }
  running.clear();
}

/** Thrown when the configured jk cannot be started at all (not installed, not on PATH). */
export class JkNotFound extends Error {
  constructor(readonly bin: string, cause: string) {
    super(cause);
  }
}

/**
 * Run `bin args…` in `cwd` and collect its output. Windows only starts `.exe`/`.com` without a
 * shell, and cmd.exe resolves a bare `jk` or a `jk.cmd` through PATHEXT; with a shell the command
 * line is joined unquoted, so the binary path is quoted, and a path holding a quote is refused.
 */
export function runJk(bin: string, args: readonly string[], cwd: string, env: NodeJS.ProcessEnv = process.env): Promise<JkRun> {
  const viaShell = process.platform === "win32" && !/\.(exe|com)$/i.test(bin);
  if (viaShell && bin.includes('"')) {
    return Promise.reject(new JkNotFound(bin, `jk.path may not contain a quote: ${bin}`));
  }
  return new Promise((resolve, reject) => {
    const child = spawn(viaShell ? `"${bin}"` : bin, [...args], {
      cwd,
      // Plain text: nothing here is a terminal.
      env: { ...env, NO_COLOR: "1" },
      shell: viaShell,
      windowsHide: true,
    });
    running.add(child);
    const out: Buffer[] = [];
    const err: Buffer[] = [];
    child.stdout?.on("data", (b: Buffer) => out.push(b));
    child.stderr?.on("data", (b: Buffer) => err.push(b));
    child.on("error", (e: NodeJS.ErrnoException) => {
      running.delete(child);
      reject(
        e.code === "ENOENT"
          ? new JkNotFound(bin, `'${bin}' was not found. Install jk, or set jk.path in your user settings.`)
          : new JkNotFound(bin, `could not start ${bin}: ${e.message}`),
      );
    });
    child.on("close", (code) => {
      running.delete(child);
      resolve({
        code: code ?? 1,
        stdout: Buffer.concat(out).toString("utf8"),
        stderr: Buffer.concat(err).toString("utf8"),
      });
    });
  });
}
