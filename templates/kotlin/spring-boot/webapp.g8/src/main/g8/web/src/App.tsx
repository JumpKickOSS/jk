import { useEffect, useState } from "react";

type Greeting = { message: string };

// /api is the same origin in production (the app serves this bundle) and a Vite proxy under `npm run dev`.
export function App() {
  const [greeting, setGreeting] = useState("…");

  useEffect(() => {
    fetch("/api/hello")
      .then((res) => res.json() as Promise<Greeting>)
      .then((g) => setGreeting(g.message))
      .catch(() => setGreeting("the API is not answering"));
  }, []);

  return (
    <main>
      <h1>$name$</h1>
      <p>{greeting}</p>
      <p>
        This page is <code>index.html</code> from the <code>web</code> module's jar; the greeting is
        <code>GET /api/hello</code> from the <code>app</code> module. Reload on any path: the SPA
        fallback serves this shell again.
      </p>
    </main>
  );
}
