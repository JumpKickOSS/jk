// SPDX-License-Identifier: Apache-2.0
import * as fs from "node:fs";

/**
 * One spelling of a path, for comparing two: links resolved, and on Windows the case folded. VS Code
 * hands a folder's fsPath with a lower-case drive letter (d:\\a\\...) where the environment spells
 * it D:\\a\\..., and realpath keeps whichever case it was given.
 */
export function canonical(p: string): string {
  const resolved = fs.realpathSync.native(p);
  return process.platform === "win32" ? resolved.toLowerCase() : resolved;
}
