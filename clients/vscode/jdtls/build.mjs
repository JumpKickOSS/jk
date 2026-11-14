// SPDX-License-Identifier: Apache-2.0
// Builds server/jk-jdtls.jar, the JumpKick importer bundle the Java language server loads through
// package.json's javaExtensions. It compiles against the Eclipse bundles inside the redhat.java
// VSIX that deps.json pins (Open VSX, sha256 checked), so neither jk's build nor this one needs
// Eclipse tooling. `--test` also runs the bundle's model tests. Needs a JDK 21+ (JAVA_HOME or PATH)
// and unzip (tar on Windows).
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import * as fs from "node:fs";
import * as https from "node:https";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const pin = JSON.parse(fs.readFileSync(path.join(here, "deps.json"), "utf8"))["redhat.java"];
const runTests = process.argv.includes("--test");

/** The pinned redhat.java VSIX, downloaded once and checked against its sha256. */
export async function redhatJava() {
  const dir = path.join(root, "node_modules", ".cache", "redhat-java");
  const vsix = path.join(dir, `redhat.java-${pin.version}.vsix`);
  if (!fs.existsSync(vsix) || sha256(vsix) !== pin.sha256) {
    fs.mkdirSync(dir, { recursive: true });
    await download(pin.url, `${vsix}.part`);
    const got = sha256(`${vsix}.part`);
    if (got !== pin.sha256) throw new Error(`redhat.java ${pin.version}: sha256 ${got}, expected ${pin.sha256}`);
    fs.renameSync(`${vsix}.part`, vsix);
  }
  const unpacked = path.join(dir, `redhat.java-${pin.version}`);
  if (!fs.existsSync(path.join(unpacked, "extension", "package.json"))) {
    fs.rmSync(unpacked, { recursive: true, force: true });
    fs.mkdirSync(unpacked, { recursive: true });
    if (process.platform === "win32") run("tar", ["-xf", vsix, "-C", unpacked]);
    else run("unzip", ["-q", vsix, "-d", unpacked]);
  }
  return { vsix, extension: path.join(unpacked, "extension"), version: pin.version };
}

function sha256(file) {
  return createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function download(url, to, redirects = 5) {
  return new Promise((resolve, reject) => {
    https
      .get(url, (res) => {
        if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
          res.resume();
          resolve(download(new URL(res.headers.location, url).toString(), to, redirects - 1));
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error(`GET ${url}: HTTP ${res.statusCode}`));
          return;
        }
        const out = fs.createWriteStream(to);
        res.pipe(out);
        out.on("finish", () => out.close(() => resolve()));
        out.on("error", reject);
      })
      .on("error", reject);
  });
}

function tool(name) {
  const home = process.env["JAVA_HOME"];
  const exe = process.platform === "win32" ? `${name}.exe` : name;
  return home ? path.join(home, "bin", exe) : exe;
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: "inherit" });
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error(`${path.basename(cmd)} exited ${r.status}`);
}

function javaFiles(dir) {
  return fs.readdirSync(dir, { recursive: true }).filter((f) => String(f).endsWith(".java")).map((f) => path.join(dir, String(f)));
}

async function main() {
  const java = await redhatJava();
  const plugins = path.join(java.extension, "server", "plugins");
  const classpath = fs.readdirSync(plugins).filter((f) => f.endsWith(".jar")).map((f) => path.join(plugins, f)).join(path.delimiter);
  const out = path.join(root, "out", "jdtls");
  const classes = path.join(out, "classes");
  fs.rmSync(out, { recursive: true, force: true });
  run(tool("javac"), ["--release", "21", "-encoding", "UTF-8", "-Xlint:all,-processing", "-Werror", "-cp", classpath, "-d", classes, ...javaFiles(path.join(here, "src"))]);

  if (runTests) {
    const gson = fs.readdirSync(plugins).find((f) => f.startsWith("com.google.gson_"));
    const testClasses = path.join(out, "test-classes");
    const testCp = [classes, path.join(plugins, String(gson))].join(path.delimiter);
    run(tool("javac"), ["--release", "21", "-encoding", "UTF-8", "-cp", testCp, "-d", testClasses, ...javaFiles(path.join(here, "test"))]);
    run(tool("java"), ["-cp", [testClasses, testCp].join(path.delimiter), "cc.jumpkick.jdtls.ProjectModelTest", path.join(here, "test")]);
  }

  const jar = path.join(root, "server", "jk-jdtls.jar");
  fs.mkdirSync(path.dirname(jar), { recursive: true });
  fs.rmSync(jar, { force: true });
  // A fixed entry date keeps the jar byte-for-byte reproducible.
  run(tool("jar"), [
    "--create",
    "--file", jar,
    "--date", "2026-01-01T00:00:00Z",
    "--manifest", path.join(here, "META-INF", "MANIFEST.MF"),
    "-C", classes, ".",
    "-C", here, "plugin.xml",
  ]);
  console.log(`built ${path.relative(root, jar)} against redhat.java ${java.version}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(e instanceof Error ? e.message : e);
    process.exit(1);
  });
}
