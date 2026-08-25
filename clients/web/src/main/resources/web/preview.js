// SPDX-License-Identifier: Apache-2.0
// The files pane's Preview half: markdown, mermaid, graphviz, asciidoc, d2 and images rendered
// into `previewHtml`. Every renderer's output goes through DOMPurify before it reaches the DOM,
// and every async step re-checks `_previewGen` so a superseded render cannot paint over a newer
// one. Spread into the code-view component's `methods`, so `this` is that component.

import { ensureCdn, markedParse } from './monaco.js';
import {
  extractMermaidFences,
  injectMermaidSvgs,
  previewKind,
  isRemoteHttpUrl,
  resolveMarkdownImagePath,
  resolveMarkdownLinkPath,
  sanitizeDiagramSvg,
} from './paths.js';
import { buildProjectHash, escapeHtml } from './route.js';

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
   */
  async hydrateMarkdownImages(html, gen) {
    if (!html || typeof DOMParser === 'undefined') return html;
    const parser = new DOMParser();
    const doc = parser.parseFromString('<div id="jk-md-root">' + html + '</div>', 'text/html');
    const root = doc.getElementById('jk-md-root');
    if (!root) return html;
    const imgs = root.querySelectorAll('img[src]');
    if (!imgs.length) return root.innerHTML;
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
              '/api/project/file/raw?project=' +
                encodeURIComponent(this.projectId) +
                '&path=' +
                encodeURIComponent(rel),
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
      return html;
    }
    this._mdImgBlobs = blobs;
    return root.innerHTML;
  },
  /**
   * Point relative markdown links at the files-pane hash so clicks open the target in
   * code-view (e.g. badge → {@code docs/architecture.md}). External http(s) stay as-is
   * (new tab).
   */
  rewriteMarkdownLinks(html) {
    if (!html || typeof DOMParser === 'undefined' || !this.projectId) return html;
    const parser = new DOMParser();
    const doc = parser.parseFromString('<div id="jk-md-root">' + html + '</div>', 'text/html');
    const root = doc.getElementById('jk-md-root');
    if (!root) return html;
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
    return root.innerHTML;
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
          '/api/project/file/raw?project=' +
            encodeURIComponent(this.projectId) +
            '&path=' +
            encodeURIComponent(this.path),
        );
        if (gen !== this._previewGen) return;
        this.previewImageUrl = URL.createObjectURL(blob);
      } else if (kind === 'markdown') {
        this.revokeImageUrl(); // drop prior blob: image srcs before re-render
        // ESM for marked/purify — no AMD park (avoids racing Monaco's markdown grammar load).
        const [marked, purify] = await Promise.all([ensureCdn('marked'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        const { markdown, fences } = extractMermaidFences(this.currentContent());
        const raw = markedParse(marked, markdown);
        // Keep raw HTML <img> (GitHub READMEs) and markdown images; hydrate srcs to blob: after.
        let html = purify.sanitize(raw, {
          ADD_ATTR: ['loading', 'referrerpolicy', 'decoding', 'width', 'height'],
          ALLOW_UNKNOWN_PROTOCOLS: false,
          ALLOWED_URI_REGEXP:
            /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|blob|data):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
        });
        // Mermaid UMD only when fenced blocks exist (noAmd park stays off the default path).
        if (fences.length) {
          const mermaid = await ensureCdn('mermaid');
          if (gen !== this._previewGen) return;
          mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
          const svgs = [];
          for (let i = 0; i < fences.length; i++) {
            try {
              const id = 'jk-md-mmd-' + gen + '-' + i;
              const { svg } = await mermaid.render(id, fences[i]);
              svgs.push('<div class="code-preview-diagram">' + sanitizeDiagramSvg(purify, svg) + '</div>');
            } catch (err) {
              const msg = (err && err.message) || 'Mermaid diagram failed';
              svgs.push('<pre class="code-preview-diagram-err">' + escapeHtml(msg) + '</pre>');
            }
            if (gen !== this._previewGen) return;
          }
          html = injectMermaidSvgs(html, svgs);
        }
        if (gen !== this._previewGen) return;
        html = await this.hydrateMarkdownImages(html, gen);
        if (gen !== this._previewGen) return;
        html = this.rewriteMarkdownLinks(html);
        if (gen !== this._previewGen) return;
        this.previewHtml = html;
      } else if (kind === 'mermaid') {
        const [mermaid, purify] = await Promise.all([ensureCdn('mermaid'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
        const id = 'jk-mmd-' + gen;
        const { svg } = await mermaid.render(id, this.currentContent());
        if (gen !== this._previewGen) return;
        this.previewHtml = '<div class="code-preview-diagram">' + sanitizeDiagramSvg(purify, svg) + '</div>';
      } else if (kind === 'graphviz') {
        // Sanitize like markdown/asciidoc: .dot files from a cloned repo control
        // the SVG (URL= attrs, arbitrary markup) — one DOMPurify chokepoint for all renderers.
        const [Viz, purify] = await Promise.all([ensureCdn('viz'), ensureCdn('purify')]);
        if (gen !== this._previewGen) return;
        const viz = await Viz.instance();
        if (gen !== this._previewGen) return;
        this.previewHtml =
          '<div class="code-preview-diagram">' +
          sanitizeDiagramSvg(purify, viz.renderSVGElement(this.currentContent()).outerHTML) +
          '</div>';
      } else if (kind === 'asciidoc') {
        const Asciidoctor = await ensureCdn('asciidoctor');
        const purify = await ensureCdn('purify');
        if (gen !== this._previewGen) return;
        const adoc = typeof Asciidoctor === 'function' ? Asciidoctor() : Asciidoctor;
        const html = adoc.convert(this.currentContent(), { safe: 'secure', attributes: { showtitle: true } });
        this.previewHtml = purify.sanitize(html);
      } else if (kind === 'd2') {
        // WASM-heavy; dynamic ESM from unpkg. CSP/wasm may reject — surface the error.
        const mod = await import('https://unpkg.com/@terrastruct/d2@0.1.33/dist/index.js');
        if (gen !== this._previewGen) return;
        const D2 = mod.D2 || mod.default;
        const d2 = new D2();
        const result = await d2.compile(this.currentContent());
        const rendered = await d2.render(result.diagram || result);
        const purify = await ensureCdn('purify');
        if (gen !== this._previewGen) return;
        this.previewHtml =
          '<div class="code-preview-diagram">' +
          sanitizeDiagramSvg(purify, rendered) +
          '</div>';
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
