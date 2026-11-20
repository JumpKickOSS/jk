// SPDX-License-Identifier: Apache-2.0
// The files pane's Preview half: markdown, mermaid, graphviz, asciidoc and images rendered
// into `previewHtml`. Every renderer's output goes through DOMPurify before it reaches the DOM,
// and every async step re-checks `_previewGen` so a superseded render cannot paint over a newer
// one. Spread into the code-view component's `methods`, so `this` is that component.

import { ensureCdn, markedParse } from './monaco.js';
import {
  extractMermaidFences,
  previewKind,
  isRemoteHttpUrl,
  resolveMarkdownImagePath,
  resolveMarkdownLinkPath,
  sanitizeDiagramSvg,
} from './paths.js';
import { buildProjectHash } from './route.js';

/** Tags a preview never renders: a repository document may not style or script the console around it. */
export const PREVIEW_FORBID_TAGS = ['style', 'link', 'meta', 'base', 'form'];

/**
 * Sanitized HTML parsed once into the body of an inert document (no browsing context: nothing it
 * names loads, nothing runs), in the same body context `v-html` parses it in. Every later step
 * works on these nodes, and only `body.innerHTML` leaves.
 */
function parseSanitized(html) {
  const doc = document.implementation.createHTMLDocument('');
  doc.body.innerHTML = html;
  return doc.body;
}

/**
 * A rendered diagram as a node of `doc`: `{ svg }` (already through `sanitizeDiagramSvg`) inside a
 * `.code-preview-diagram`, or `{ error }` as the text of a `.code-preview-diagram-err`.
 */
export function diagramNode(doc, diagram) {
  if (diagram && diagram.svg != null) {
    const box = doc.createElement('div');
    box.className = 'code-preview-diagram';
    box.innerHTML = String(diagram.svg);
    return box;
  }
  const err = doc.createElement('pre');
  err.className = 'code-preview-diagram-err';
  err.textContent = String((diagram && diagram.error) || 'Mermaid diagram failed');
  return err;
}

/**
 * Put each diagram where `extractMermaidFences` left its placeholder: a `<p>` whose one child is a
 * text node holding exactly `placeholders[i]` is replaced by `node(i)`, once. Attribute values and
 * a placeholder inside other text are never read, so the document cannot steer a diagram there.
 */
export function placeMermaidDiagrams(root, placeholders, node) {
  const pending = new Map((placeholders || []).map((p, i) => [p, i]));
  if (!pending.size) return;
  for (const p of Array.from(root.querySelectorAll('p'))) {
    const only = p.childNodes.length === 1 ? p.firstChild : null;
    if (!only || only.nodeType !== 3) continue;
    const key = String(only.nodeValue).trim();
    const i = pending.get(key);
    if (i === undefined) continue;
    pending.delete(key);
    p.replaceWith(node(i));
  }
}

/** One diagram preview (a `.mmd` or `.dot` file) as HTML, built as a node like the markdown ones. */
function diagramHtml(svg) {
  const root = parseSanitized('');
  root.append(diagramNode(root.ownerDocument, { svg }));
  return root.innerHTML;
}

export const previewMethods = {
  revokeImageUrl() {
    if (this.previewImageUrl) {
      URL.revokeObjectURL(this.previewImageUrl);
      this.previewImageUrl = null;
    }
    if (this._mdImgBlobs) {
      for (const u of this._mdImgBlobs) {
        try {
          URL.revokeObjectURL(u);
        } catch {
          // ignore
        }
      }
      this._mdImgBlobs = [];
    }
  },
  /**
   * Markdown {@code <img>} handling (GitHub README style):
   * Remote http(s): leave the absolute URL on the tag. Do <b>not</b> set {@code crossorigin}
   *  (that forces a CORS fetch and breaks github.com/user-attachments / many badge CDNs).
   *  {@code referrerpolicy=no-referrer} helps hosts that soft-block hotlinks by Referer.
   *  We intentionally skip the engine image proxy for remotes — GH user-attachments often 404
   *  server-side, and a failed proxy + {@code crossorigin} fallback was the CORS error loop.
   * Relative: auth-fetch workspace raw bytes → blob: URL.
   * Works on the parsed preview in place; a superseded render keeps none of its blob: URLs.
   */
  async hydrateMarkdownImages(root, gen) {
    const imgs = root.querySelectorAll('img[src]');
    if (!imgs.length) return;
    const { getBlob } = await import('./api.js');
    const blobs = [];
    const jobs = [];
    for (const img of imgs) {
      const src = img.getAttribute('src');
      if (!src) continue;

      img.removeAttribute('crossorigin');
      img.setAttribute('loading', 'lazy');

      if (isRemoteHttpUrl(src)) {
        let remote = src.trim();
        if (remote.startsWith('//')) remote = 'https:' + remote;
        img.setAttribute('src', remote);
        img.setAttribute('referrerpolicy', 'no-referrer');
        continue;
      }

      const rel = resolveMarkdownImagePath(this.path, src);
      if (!rel) continue;
      jobs.push(
        (async () => {
          try {
            const blob = await getBlob(
              '/api/project/file/raw?' + this.projectQuery() + '&path=' + encodeURIComponent(rel),
            );
            if (gen !== this._previewGen) return;
            const url = URL.createObjectURL(blob);
            blobs.push(url);
            img.setAttribute('src', url);
            img.removeAttribute('data-jk-img-miss');
            img.classList.remove('code-preview-img-miss');
          } catch {
            img.setAttribute('data-jk-img-miss', rel);
            img.classList.add('code-preview-img-miss');
          }
        })(),
      );
    }
    await Promise.all(jobs);
    if (gen !== this._previewGen) {
      for (const u of blobs) {
        try {
          URL.revokeObjectURL(u);
        } catch {
          // ignore
        }
      }
      return;
    }
    this._mdImgBlobs = blobs;
  },
  /**
   * Point relative markdown links at the files-pane hash so clicks open the target in
   * code-view (e.g. badge → {@code docs/architecture.md}). External http(s) stay as-is
   * (new tab). Works on the parsed preview in place.
   */
  rewriteMarkdownLinks(root) {
    if (!this.projectId) return;
    for (const a of root.querySelectorAll('a[href]')) {
      const href = a.getAttribute('href');
      if (!href) continue;
      if (isRemoteHttpUrl(href) || /^[a-z][a-z0-9+.-]*:/i.test(href.trim())) {
        // External: open outside the SPA.
        a.setAttribute('target', '_blank');
        a.setAttribute('rel', 'noopener noreferrer');
        continue;
      }
      if (href.trim().startsWith('#')) continue; // in-page anchor
      const rel = resolveMarkdownLinkPath(this.path, href);
      if (!rel) continue;
      a.setAttribute(
        'href',
        buildProjectHash({ projectId: this.projectId, files: true, path: rel }),
      );
      a.classList.add('code-preview-inlink');
      a.removeAttribute('target');
    }
  },
  async togglePreview() {
    if (!this.previewable) return;
    if (this.previewOpen) {
      if (this._previewTimer) {
        clearTimeout(this._previewTimer);
        this._previewTimer = null;
      }
      this.previewOpen = false;
      this.previewError = null;
      this.previewHtml = '';
      this.revokeImageUrl();
      await this.$nextTick();
      if (this._editor) this._editor.layout();
      if (this.file && !this.isImage && !this.editorVisible) await this.paint();
      return;
    }
    this.previewOpen = true;
    await this.$nextTick();
    if (this._editor) this._editor.layout();
    await this.renderPreview();
  },
  async renderPreview() {
    const gen = ++this._previewGen;
    this.previewLoading = true;
    this.previewError = null;
    // Keep prior HTML visible while re-rendering (live edit); only clear image URL for image kind.
    const kind = previewKind(this.path);
    try {
      if (kind === 'image') {
        this.revokeImageUrl();
        this.previewHtml = '';
        const { getBlob } = await import('./api.js');
        const blob = await getBlob(
          '/api/project/file/raw?' + this.projectQuery() + '&path=' + encodeURIComponent(this.path),
        );
        if (gen !== this._previewGen) return;
        this.previewImageUrl = URL.createObjectURL(blob);
      } else if (kind === 'markdown') {
        this.revokeImageUrl(); // drop prior blob: image srcs before re-render
        // ESM for marked/purify — no AMD park (avoids racing Monaco's markdown grammar load).
        const [marked, purify] = await Promise.all([ensureCdn('marked'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        const { markdown, fences, placeholders } = extractMermaidFences(this.currentContent());
        const raw = markedParse(marked, markdown);
        // Keep raw HTML <img> (GitHub READMEs) and markdown images; hydrate srcs to blob: after.
        const html = purify.sanitize(raw, {
          ADD_ATTR: ['loading', 'referrerpolicy', 'decoding', 'width', 'height'],
          // A repository's README is not the page's to style: a <style> it carries would restyle
          // the whole console (an overlay on Save or Build), with the CSP's inline styles allowed.
          FORBID_TAGS: PREVIEW_FORBID_TAGS,
          ALLOW_UNKNOWN_PROTOCOLS: false,
          ALLOWED_URI_REGEXP:
            /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|blob|data):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
        });
        // Mermaid UMD only when fenced blocks exist (noAmd park stays off the default path).
        const diagrams = [];
        if (fences.length) {
          const mermaid = await ensureCdn('mermaid');
          if (gen !== this._previewGen) return;
          mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
          for (let i = 0; i < fences.length; i++) {
            try {
              const id = 'jk-md-mmd-' + gen + '-' + i;
              const { svg } = await mermaid.render(id, fences[i]);
              diagrams.push({ svg: sanitizeDiagramSvg(purify, svg) });
            } catch (err) {
              diagrams.push({ error: (err && err.message) || 'Mermaid diagram failed' });
            }
            if (gen !== this._previewGen) return;
          }
        }
        // Parsed once; diagrams, images and links are placed as nodes, never spliced into the string.
        const root = parseSanitized(html);
        placeMermaidDiagrams(root, placeholders, (i) => diagramNode(root.ownerDocument, diagrams[i]));
        await this.hydrateMarkdownImages(root, gen);
        if (gen !== this._previewGen) return;
        this.rewriteMarkdownLinks(root);
        this.previewHtml = root.innerHTML;
      } else if (kind === 'mermaid') {
        const [mermaid, purify] = await Promise.all([ensureCdn('mermaid'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
        const id = 'jk-mmd-' + gen;
        const { svg } = await mermaid.render(id, this.currentContent());
        if (gen !== this._previewGen) return;
        this.previewHtml = diagramHtml(sanitizeDiagramSvg(purify, svg));
      } else if (kind === 'graphviz') {
        // Sanitize like markdown/asciidoc: .dot files from a cloned repo control
        // the SVG (URL= attrs, arbitrary markup) — one DOMPurify chokepoint for all renderers.
        const [Viz, purify] = await Promise.all([ensureCdn('viz'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        const viz = await Viz.instance();
        if (gen !== this._previewGen) return;
        const svg = viz.renderSVGElement(this.currentContent()).outerHTML;
        this.previewHtml = diagramHtml(sanitizeDiagramSvg(purify, svg));
      } else if (kind === 'asciidoc') {
        const Asciidoctor = await ensureCdn('asciidoctor');
        const purify = await ensureCdn('purify');
        if (gen !== this._previewGen) return;
        const adoc = typeof Asciidoctor === 'function' ? Asciidoctor() : Asciidoctor;
        const html = adoc.convert(this.currentContent(), { safe: 'secure', attributes: { showtitle: true } });
        this.previewHtml = purify.sanitize(html, { FORBID_TAGS: PREVIEW_FORBID_TAGS });
      } else {
        this.previewError = 'No preview for this file type';
      }
    } catch (e) {
      if (gen !== this._previewGen) return;
      this.previewError = (e && e.message) || (e && e.error) || 'Preview failed';
      this.previewHtml = '';
    } finally {
      if (gen === this._previewGen) this.previewLoading = false;
    }
  },
};
