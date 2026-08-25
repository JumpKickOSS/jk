// SPDX-License-Identifier: Apache-2.0
// The dashboard's icon set: `<jk-icon name="refresh">`. Inline SVG rather than a font or a sprite
// sheet, because `default-src 'self'` gates a data: URI but not SVG in the DOM, and because a path
// stroked with currentColor inherits whatever state class its container carries.

// A tiny inline-SVG icon set: <jk-icon name="refresh">. Each glyph is a 24x24 path stroked (or, for
// a couple, filled) with currentColor, so `color` / state classes drive it — no icon font, no network
// fetch, and inline SVG is allowed under the dashboard's CSP (default-src 'self' doesn't gate SVG DOM,
// unlike a data: URI). Sized by the .ico class (1em by default; see style.css). Registered globally
// before mount so every component (including phase-chain) can use it.
const ICON_PATHS = {
  'chevron-left': 'M15 18l-6-6 6-6',
  'chevron-right': 'M9 18l6-6-6-6',
  'arrow-up': 'M12 19V5M5 12l7-7 7 7',
  'arrow-down': 'M12 5v14M19 12l-7 7-7-7',
  check: 'M20 6L9 17l-5-5',
  x: 'M18 6L6 18M6 6l12 12',
  ban: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM5.6 5.6l12.8 12.8',
  alert: 'M10.3 3.9 2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01',
  refresh: 'M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15',
  trash: 'M4 7h16M9 7V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v3M6 7l1 13a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-13M10 11v6M14 11v6',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z',
  help: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.6 9.5a2.5 2.5 0 0 1 4.85.83c0 1.67-2.45 2.17-2.45 3.67M12 17h.01',
  settings: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.05.05a2 2 0 1 1-2.83 2.83l-.05-.05a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.05.05a2 2 0 1 1-2.83-2.83l.05-.05a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.05-.05a2 2 0 1 1 2.83-2.83l.05.05a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.05-.05a2 2 0 1 1 2.83 2.83l-.05.05a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  bolt: 'M13 2 3 14 12 14 11 22 21 10 12 10Z',
  activity: 'M22 12h-4l-3 9L9 3l-3 9H2',
  database: 'M12 3c-4.4 0-8 1.3-8 3s3.6 3 8 3 8-1.3 8-3-3.6-3-8-3zM4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
  cpu: 'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3',
  'folder-open': 'M6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.55 6a2 2 0 0 1-1.94 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2',
  'chevron-down': 'M6 9l6 6 6-6',
  // Funnel — sits inside the file-tree filter box (code.js).
  filter: 'M22 3H2l8 9.46V19l4 2v-8.54L22 3z',
  // Dog-eared sheet — the generic file glyph in the #project/<id>/files tree (two subpaths).
  file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zM14 2v6h6',
  plus: 'M12 5v14M5 12h14',
  // Two overlapping rectangles — clipboard / copy affordance (lucide-style).
  copy: 'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2M8 2h8a1 1 0 0 1 1 1v2H7V3a1 1 0 0 1 1-1z',
  // Floppy-disk save affordance (Files toolbar).
  save: 'M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2zM17 21v-8H7v8M7 3v5h8',
  // Eye — Preview affordance (Files toolbar).
  eye: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  // "</>" — code (View/edit this codebase on the project page).
  code: 'M16 18l6-6-6-6M8 6l-6 6 6 6M14.5 4l-5 16',
};
// Icons that read better as a solid shape than an outline at small sizes.
const ICON_SOLID = {
  play: 'M8 5v14l11-7z',
  dot: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
};
export const JkIcon = {
  props: { name: { type: String, required: true } },
  computed: {
    solid() {
      return this.name in ICON_SOLID;
    },
    d() {
      return this.solid ? ICON_SOLID[this.name] : ICON_PATHS[this.name] || '';
    },
  },
  template: `<svg class="ico" viewBox="0 0 24 24" :fill="solid ? 'currentColor' : 'none'"
       :stroke="solid ? 'none' : 'currentColor'" stroke-width="2" stroke-linecap="round"
       stroke-linejoin="round" aria-hidden="true"><path :d="d"></path></svg>`,
};
