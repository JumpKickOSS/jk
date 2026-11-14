// SPDX-License-Identifier: Apache-2.0
import * as fs from "node:fs";
import * as path from "node:path";
import Mocha from "mocha";

/** The extension host's test entry point: every compiled `*.test.js` beside this file, under mocha. */
export function run(): Promise<void> {
  const mocha = new Mocha({ ui: "bdd", color: true, timeout: 30_000 });
  for (const f of fs.readdirSync(__dirname)) if (f.endsWith(".test.js")) mocha.addFile(path.join(__dirname, f));
  return new Promise((resolve, reject) => {
    mocha.run((failures) => (failures > 0 ? reject(new Error(`${failures} integration tests failed`)) : resolve()));
  });
}
