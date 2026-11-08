// Records the jk web project page (it follows the newest run) for the agent demo's right pane.
//
//   node record-dashboard.cjs <dashboard-url-with-token> <project-id> <out-dir> <stop-file>
//
// Headless Chrome (playwright-core, channel "chrome"), an 880×1080 video of the page zoomed to
// 125% (a screencast records CSS pixels, so device scale would only pad the frame). Writes <out-dir>/dashboard-start (epoch seconds when recording began) so the
// compositor can line this video up with the terminal's, then records until <stop-file> exists.
const { chromium } = require("playwright-core");
const fs = require("fs");
const path = require("path");

(async () => {
  const [url, project, out, stop] = process.argv.slice(2);
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const context = await browser.newContext({
    viewport: { width: 880, height: 1080 },
    colorScheme: "dark",
    recordVideo: { dir: out, size: { width: 880, height: 1080 } },
  });
  const started = Date.now() / 1000;
  const page = await context.newPage();
  // The token in #t=… signs this page in once; then the route changes in place, never reloading.
  await page.addInitScript(() => {
    document.addEventListener("DOMContentLoaded", () => { document.documentElement.style.zoom = "1.25"; });
  });
  await page.goto(url);
  await page.waitForFunction(() => !/Access Denied/.test(document.body.innerText) && document.querySelector("header"), null, { timeout: 15000 });
  await page.waitForTimeout(500);
  await page.evaluate((id) => { location.hash = `#project/${id}`; }, project);
  await page.waitForTimeout(500);
  fs.writeFileSync(path.join(out, "dashboard-start"), String(started));
  while (!fs.existsSync(stop)) await new Promise((r) => setTimeout(r, 250));
  const video = page.video();
  await context.close();
  fs.renameSync(await video.path(), path.join(out, "dashboard.webm"));
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
