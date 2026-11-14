// SPDX-License-Identifier: Apache-2.0
// Runs every compiled unit test with node's own runner, listing the files itself so the command is
// the same on every shell and Node version.
import { spawnSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { join } from "node:path";

const dir = join("out", "test", "unit");
const files = readdirSync(dir).filter((f) => f.endsWith(".test.js")).map((f) => join(dir, f));
const r = spawnSync(process.execPath, ["--test", ...files], { stdio: "inherit" });
process.exit(r.status ?? 1);
