// SPDX-License-Identifier: Apache-2.0
// The "What's new" dialog: after JumpKick updates itself, the bell's "JumpKick was updated" notice
// links to #whats-new, which opens this over whatever page is showing. It lists the highlights of
// every release the update moved across (GET /api/whats-new), newest first, and links to the full
// notes on GitHub. Closing it puts the page's own route back.
import { get } from './api.js';

/** HTML-escape `text`. */
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * One highlight's Markdown as HTML: `code`, **bold** and [links](https://…), nothing else. The text
 * is escaped first, so only these three shapes ever become markup, and a link must be http(s).
 */
export function inlineMarkdown(markdown) {
  // Code spans first, parked behind placeholders, so bold and links can span them and nothing
  // inside a code span is read as Markdown. The placeholder uses characters escaping never makes.
  const codes = [];
  const parked = String(markdown || '').replace(/`([^`]*)`/g, (_, code) => {
    codes.push(`<code>${escapeHtml(code)}</code>`);
    return `\u0000${codes.length - 1}\u0000`;
  });
  return escapeHtml(parked)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    .replace(/\u0000(\d+)\u0000/g, (_, i) => codes[Number(i)]);
}

export function whatsNewData() {
  return {
    // null while closed; { loading } while fetching; the API's answer, or { error }, once open.
    whatsNew: null,
    _whatsNewUnder: null,
  };
}

export const whatsNewMethods = {
  /** #whats-new opens the dialog over the page under it; any other route closes it. */
  routeWhatsNew(route, change) {
    if (route.whatsNew) {
      const from = change && change.oldURL ? new URL(change.oldURL).hash : null;
      if (from != null && from !== '#whats-new') this._whatsNewUnder = from;
      this.bellOpen = false;
      this.openWhatsNew();
      return true;
    }
    this.whatsNew = null;
    return false;
  },
  async openWhatsNew() {
    this.whatsNew = { loading: true };
    try {
      this.whatsNew = await get('/api/whats-new');
    } catch {
      this.whatsNew = { error: true };
    }
  },
  closeWhatsNew() {
    this.whatsNew = null;
    history.replaceState(null, '', this._whatsNewUnder || location.pathname + location.search);
    this._whatsNewUnder = null;
  },
  whatsNewHtml(markdown) {
    return inlineMarkdown(markdown);
  },
};
