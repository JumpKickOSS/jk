// SPDX-License-Identifier: Apache-2.0
// The new-project wizard page (#new) and the directory browser it and the Activity view open. The
// page renders the engine's form (GET /api/forms/new-project) through wizard-model.js: jk's step,
// then the chosen template's, then review, with a debounced preview of the tree and jk.toml beside
// it. Nothing here knows a template; every field, option and condition comes from the form. Spread
// into the root component.

import { get, post } from './api.js';
import {
  equivalentCommand,
  fieldsOf,
  linkFromHash,
  openingAnswers,
  optionId,
  optionLabel,
  postBody,
  settle,
  shownFields,
  sourceFor,
  treeRows,
  valueText,
  wizardSteps,
} from './wizard-model.js';

const PREVIEW_DEBOUNCE_MS = 350;

/** A fresh wizard: no form yet, nothing answered. */
export function emptyWizard() {
  return {
    form: null, // the served form ({id, title, rev, steps})
    template: '', // the id the form's template resolved to
    derived: {}, // this machine's derived defaults: {'git-email': …, 'parent-dir': …}
    answers: {}, // typed answers by form key; a field left out takes its default
    step: 0,
    loading: false,
    busy: false, // a create is in flight
    error: null,
    notice: null, // the form changed under the wizard (a 409), shown above the step
    problems: {}, // server-side problems by key, from a 400
    elicitation: null, // the MCP elicitation a link opened the wizard for
    options: {}, // a search field's rows by key: [{id, description}]
    query: {}, // a search field's typed filter by key
    openSearch: null, // the key whose search list is open
    tried: false, // Next was pressed past a problem: show every problem, answered or not
    advancedOpen: false,
    preview: null, // {path, files, manifest}
    previewError: null,
    previewBusy: false,
    copied: false,
  };
}

export const wizardComputed = {
  wizardSteps() {
    return wizardSteps(this.wizard.form);
  },
  wizardSettled() {
    if (!this.wizard.form) return { values: {}, hidden: new Set(), problems: [] };
    return settle(this.wizard.form, this.wizard.answers, this.wizard.derived);
  },
  /** The current step's shown fields, advanced ones apart. */
  wizardFields() {
    const step = this.wizardSteps[this.wizard.step];
    return shownFields(step ? step.fields : [], this.wizardSettled.values);
  },
  /** Problems by key: the server's from the last post, else the model's for fields that were touched. */
  wizardProblems() {
    const out = { ...this.wizard.problems };
    for (const p of this.wizardSettled.problems) if (!(p.key in out)) out[p.key] = p.message;
    return out;
  },
  wizardCommand() {
    if (!this.wizard.form || this.wizardSettled.problems.length) return '';
    return equivalentCommand(this.wizard.form, this.wizard.answers, this.wizard.derived);
  },
  wizardTree() {
    return treeRows(this.wizard.preview ? this.wizard.preview.files : []);
  },
  /** Review rows: every shown field with its settled value. */
  wizardSummary() {
    const s = this.wizardSettled;
    return fieldsOf(this.wizard.form)
      .filter((f) => !s.hidden.has(f.key) && s.values[f.key] != null)
      .map((f) => ({ key: f.key, label: f.label, value: this.wizardValueLabel(f, s.values[f.key]) }));
  },
};

export const wizardMethods = {
  // ---- the directory browser ----
  async openBrowser() {
    if (this.authModal) return;
    // Seed the picker with whatever was typed (~ / relative / absolute); server resolves vs $HOME.
    this.browserMode = 'workspace';
    const seed = this.buildDir.trim() || null;
    await this.browseTo(seed);
  },
  /** Browse for a path field of the wizard; Use writes the chosen directory into that answer. */
  async openPathBrowser(key) {
    if (this.authModal) return;
    this.browserMode = 'parent';
    this.browserKey = key;
    const typed = this.wizardSettled.values[key];
    await this.browseTo(typeof typed === 'string' && typed.trim() ? typed.trim() : null);
  },
  async browseTo(dir) {
    this.buildError = null;
    try {
      this.browser = await get('/api/fs' + (dir ? '?dir=' + encodeURIComponent(dir) : ''));
    } catch (e) {
      if (this.handleHttpError(e)) {
        this.browser = null;
        return;
      }
      if (!this.browser) {
        const msg = 'Could not list that directory';
        this.buildError = msg;
        if (this.browserMode === 'parent') this.wizard.error = msg;
      }
    }
  },
  chooseBrowsed() {
    if (this.browserMode === 'parent') {
      this.setWizardAnswer(this.browserKey || 'dir', this.browser.dir);
    } else {
      this.buildDir = this.browser.dir;
    }
    this.closeBrowser();
  },
  closeBrowser() {
    this.browser = null;
    this.browserMode = 'workspace';
    this.browserKey = null;
  },
  joinPath(dir, name) {
    return dir.endsWith('/') ? dir + name : dir + '/' + name;
  },

  // ---- the wizard ----
  openNewProject() {
    if (this.authModal) return;
    location.hash = '#new';
  },
  /** The #new route: load the form a link names, pre-filled with its answers. */
  async enterWizard(hash = location.hash) {
    const link = linkFromHash(hash);
    this.wizard = emptyWizard();
    this.wizard.elicitation = link.elicitation;
    this.wizard.loading = true;
    const loaded = await this.loadWizardForm(link.answers.template || '', link.answers.lang || '', link.answers);
    this.wizard.loading = false;
    if (!loaded) return;
    this.loadSearchOptions();
    this.schedulePreview();
    this.$nextTick(() => {
      // The name is the one answer no default fills, so typing starts there.
      const el = document.querySelector('#wz-name') || document.querySelector('.wizard-step input');
      if (el && typeof el.focus === 'function') el.focus();
    });
  },
  /**
   * Fetch the form for `template` (blank: the default one) and re-type `raw` answers against it,
   * keeping what still fits. False when the form could not be loaded.
   */
  async loadWizardForm(template, lang, raw = this.wizard.answers) {
    const q = [];
    if (template) q.push('template=' + encodeURIComponent(template));
    if (lang) q.push('lang=' + encodeURIComponent(lang));
    try {
      const res = await get('/api/forms/new-project' + (q.length ? '?' + q.join('&') : ''));
      this.applyWizardForm(res, raw);
      return true;
    } catch (e) {
      if (this.handleHttpError(e)) return false;
      this.wizard.error = e.error || 'Could not load the new-project form';
      return false;
    }
  },
  applyWizardForm(res, raw) {
    this.wizard.form = res.form;
    this.wizard.template = res.template;
    this.wizard.derived = res.derived || {};
    this.wizard.answers = openingAnswers(res, raw);
    this.wizard.problems = {};
  },
  wizardField(key) {
    return fieldsOf(this.wizard.form).find((f) => f.key === key) || null;
  },
  wizardValue(f) {
    return this.wizardSettled.values[f.key];
  },
  /** A text box's content: what was typed, else the settled value (a default fills an untouched box). */
  fieldText(f) {
    const typed = this.wizard.answers[f.key];
    if (typed !== undefined && typed !== null) return valueText(typed);
    const v = this.wizardValue(f);
    return v == null ? '' : valueText(v);
  },
  /** The problem to show beside `f`: the server's, or the model's once the field was answered or Next tried. */
  fieldProblem(f) {
    if (this.wizard.problems[f.key]) return this.wizard.problems[f.key];
    if (!this.wizard.tried && this.wizard.answers[f.key] === undefined) return null;
    return this.wizardProblems[f.key] || null;
  },
  wizardValueLabel(f, v) {
    if (typeof v === 'boolean') return v ? 'yes' : 'no';
    if (f.options && !Array.isArray(v)) {
      const o = f.options.find((x) => optionId(x) === v);
      if (o) return optionLabel(o);
    }
    return valueText(v);
  },
  optionId,
  optionLabel,
  isPicked(f, id) {
    const v = this.wizardValue(f);
    return Array.isArray(v) ? v.includes(id) : v === id;
  },
  toggleMulti(f, id) {
    const v = this.wizardValue(f);
    const cur = Array.isArray(v) ? v : [];
    this.setWizardAnswer(f.key, cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]);
  },
  /**
   * One answer changed. A new template or language brings a new form (the template's step is its
   * own); any change redraws the preview, never the page.
   */
  async setWizardAnswer(key, value) {
    this.wizard.answers = { ...this.wizard.answers, [key]: value };
    const problems = { ...this.wizard.problems };
    delete problems[key];
    this.wizard.problems = problems;
    this.wizard.notice = null;
    if (key === 'template' || key === 'lang') {
      const tpl = key === 'template' ? value : '';
      const lang = key === 'lang' ? value : this.wizard.answers.lang || '';
      const keep = { ...this.wizard.answers };
      if (key === 'lang') delete keep.template;
      if (key === 'template') delete keep.lang;
      await this.loadWizardForm(tpl, lang, keep);
      if (key === 'lang') this.loadSearchOptions();
    }
    this.schedulePreview();
  },
  /** Rows of every search field, from the endpoint its source names. */
  async loadSearchOptions() {
    for (const f of fieldsOf(this.wizard.form)) {
      if (f.kind !== 'search') continue;
      try {
        const rows = await get(sourceFor(f, this.wizardSettled.values));
        this.wizard.options = { ...this.wizard.options, [f.key]: Array.isArray(rows) ? rows : [] };
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.wizard.options = { ...this.wizard.options, [f.key]: [] };
      }
    }
  },
  searchRows(f) {
    const q = (this.wizard.query[f.key] || '').trim().toLowerCase();
    const rows = this.wizard.options[f.key] || [];
    if (!q) return rows;
    return rows.filter((r) => (r.id + ' ' + (r.description || '')).toLowerCase().includes(q));
  },
  searchLabel(f) {
    const v = this.wizardValue(f);
    const row = (this.wizard.options[f.key] || []).find((r) => r.id === v);
    return row ? row.id : v || '';
  },
  onSearchInput(f, text) {
    this.wizard.query = { ...this.wizard.query, [f.key]: text };
    this.wizard.openSearch = f.key;
  },
  pickSearch(f, id) {
    this.wizard.query = { ...this.wizard.query, [f.key]: '' };
    this.wizard.openSearch = null;
    if (id && id !== this.wizardValue(f)) this.setWizardAnswer(f.key, id);
  },
  onSearchKey(f, ev) {
    if (ev.key === 'Escape') {
      this.wizard.openSearch = null;
      ev.target.blur();
    }
    if (ev.key === 'Enter') {
      ev.preventDefault();
      const rows = this.searchRows(f);
      // A typed ref that lists nothing is still a template: a path, a URL, framework/name.
      this.pickSearch(f, rows.length ? rows[0].id : (this.wizard.query[f.key] || '').trim());
    }
  },
  onSearchBlur() {
    setTimeout(() => {
      this.wizard.openSearch = null;
    }, 120);
  },
  /** Fields of `step` that have a problem; the stepper refuses to go past them. */
  stepProblems(step) {
    const keys = new Set((this.wizardSteps[step]?.fields || []).map((f) => f.key));
    return Object.keys(this.wizardProblems).filter((k) => keys.has(k));
  },
  wizardGo(step) {
    for (let s = 0; s < step; s++) {
      if (this.stepProblems(s).length) {
        this.wizard.step = s;
        this.wizard.tried = true;
        return;
      }
    }
    this.wizard.step = Math.max(0, Math.min(step, this.wizardSteps.length - 1));
  },
  schedulePreview() {
    clearTimeout(this._previewTimer);
    this._previewTimer = setTimeout(() => this.runPreview(), PREVIEW_DEBOUNCE_MS);
  },
  async runPreview() {
    if (!this.wizard.form || this.wizardSettled.problems.length) {
      this.wizard.preview = null;
      this.wizard.previewError = null;
      return;
    }
    const seq = (this._previewSeq = (this._previewSeq || 0) + 1);
    this.wizard.previewBusy = true;
    try {
      const res = await post('/api/projects/preview', postBody(this.wizard.form, this.wizard.answers, this.wizard.derived));
      if (seq !== this._previewSeq) return;
      this.wizard.preview = res;
      this.wizard.previewError = null;
    } catch (e) {
      if (seq !== this._previewSeq || this.handleHttpError(e)) return;
      if (this.applyStaleForm(e)) return;
      this.wizard.previewError = e.error || 'Could not preview';
      this.applyProblems(e);
    } finally {
      if (seq === this._previewSeq) this.wizard.previewBusy = false;
    }
  },
  /** A 409 that carries a form: the form changed under the wizard, so take it and re-ask. */
  applyStaleForm(e) {
    if (e.status !== 409 || !e.body || !e.body.form) return false;
    this.applyWizardForm(e.body, this.wizard.answers);
    this.wizard.notice = 'The template changed while you were answering; check the fields again.';
    this.schedulePreview();
    return true;
  },
  applyProblems(e) {
    const problems = {};
    for (const p of (e.body && e.body.problems) || []) problems[p.key] = p.message;
    this.wizard.problems = problems;
  },
  async copyCommand() {
    try {
      await navigator.clipboard.writeText(this.wizardCommand);
      this.wizard.copied = true;
      setTimeout(() => (this.wizard.copied = false), 1500);
    } catch {
      // clipboard refused (insecure context): the command stays selectable on the page
    }
  },
  cancelWizard() {
    this.wizard = emptyWizard();
    this.setView('projects');
  },
  /** Create: scaffold, open the project's page and start its first build. */
  async createProject() {
    if (this.wizard.busy || !this.wizard.form) return;
    this.wizard.error = null;
    this.wizard.busy = true;
    const body = postBody(this.wizard.form, this.wizard.answers, this.wizard.derived);
    if (this.wizard.elicitation) body.elicitation = this.wizard.elicitation;
    try {
      const res = await post('/api/projects', body);
      const path = res.path || res.dir;
      this.wizard = emptyWizard();
      if (path) await this.triggerBuild(path);
      if (res.projectId) this.openProject(res.projectId, path);
      else this.setView('activity');
    } catch (e) {
      if (this.handleHttpError(e)) return;
      if (this.applyStaleForm(e)) return;
      this.wizard.error = e.error || 'Could not create the project';
      this.applyProblems(e);
    } finally {
      this.wizard.busy = false;
    }
  },
};
