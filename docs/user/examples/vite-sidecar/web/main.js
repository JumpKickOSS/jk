// Same origin as the page: Vite's dev server proxies /api to the JVM, so this is a plain fetch.
const out = document.querySelector("#api");
fetch("/api/hello")
  .then((r) => r.json())
  .then((json) => (out.textContent = `${json.message} at ${json.at}`))
  .catch((e) => (out.textContent = `the JVM did not answer: ${e}`));
