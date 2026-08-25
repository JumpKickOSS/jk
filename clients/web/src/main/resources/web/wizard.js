// SPDX-License-Identifier: Apache-2.0
// New Project, and the workspace directory browser it opens on top of. The lang -> framework ->
// template narrowing is three filtered lists over one /api/templates payload; the combo boxes are
// hand-rolled so the keyboard contract stays ours. Spread into the root component.

import { get, post } from './api.js';

export const wizardComputed = {
  templatesForLang() {
    const lang = (this.newProject?.lang || 'java').toLowerCase();
    return (this.templates || []).filter((t) => String(t.language || '').toLowerCase() === lang);
  },
  frameworkChoices() {
    const seen = new Set();
    const out = [{ value: 'none', label: 'none (unframed)' }];
    seen.add('none');
    for (const t of this.templatesForLang) {
      const fw = (t.framework || 'none').toLowerCase();
      if (seen.has(fw)) continue;
      seen.add(fw);
      out.push({ value: fw, label: fw });
    }
    return out;
  },
  filteredFrameworkChoices() {
    const q = (this.newProject.frameworkQuery || '').trim().toLowerCase();
    if (!q || q === 'all frameworks') return this.frameworkChoices;
    return this.frameworkChoices.filter((f) => f.label.toLowerCase().includes(q) || f.value.includes(q));
  },
  templatesForFilters() {
    const fw = (this.newProject.framework || '').toLowerCase();
    return this.templatesForLang.filter((t) => {
      if (!fw) return true;
      return String(t.framework || 'none').toLowerCase() === fw;
    });
  },
  templateChoices() {
    const allFw = !(this.newProject.framework || '');
    return this.templatesForFilters.map((t) => {
      const desc = (t.description || '').trim();
      const suffix = allFw && t.framework && t.framework !== 'none' ? ' · ' + t.framework : '';
      return {
        value: t.id,
        label: desc ? t.name + suffix + ' — ' + desc : t.name + suffix,
      };
    });
  },
  filteredTemplateChoices() {
    const q = (this.newProject.templateQuery || '').trim().toLowerCase();
    if (!q || q.startsWith('none')) return this.templateChoices;
    return this.templateChoices.filter((c) => c.label.toLowerCase().includes(q) || c.value.toLowerCase().includes(q));
  },
};

export const wizardMethods = {
  // ---- the workspace picker (Browse…) ----
  async openBrowser() {
    if (this.authModal) return;
    // Seed the picker with whatever was typed (~ / relative / absolute); server resolves vs $HOME.
    this.browserMode = 'workspace';
    const seed = this.buildDir.trim() || null;
    await this.browseTo(seed);
  },
  async openParentBrowser() {
    if (this.authModal) return;
    this.browserMode = 'parent';
    const seed = this.newProject.parentDir && this.newProject.parentDir.trim()
      ? this.newProject.parentDir.trim()
      : null;
    await this.browseTo(seed);
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
      if (this.browser) {
        // an unreadable subdir: stay where we are
      } else {
        const msg = 'Could not list that directory';
        this.buildError = msg;
        if (this.browserMode === 'parent') this.newProjectError = msg;
      }
    }
  },
  chooseBrowsed() {
    if (this.browserMode === 'parent') {
      this.newProject.parentDir = this.browser.dir;
    } else {
      this.buildDir = this.browser.dir;
    }
    this.browser = null;
    this.browserMode = 'workspace';
  },
  closeBrowser() {
    this.browser = null;
    this.browserMode = 'workspace';
  },
  // ---- New project (POST /api/projects) ----
  async openNewProject() {
    if (this.authModal) return;
    this.newProjectOpen = true;
    this.newProjectError = null;
    this.newProjectBusy = false;
    // Defaults (group from git email like `jk new`, parent from history / well-known roots)
    // and the short-name catalog — load in parallel so the modal fills quickly.
    let defaults = null;
    let templates = null;
    try {
      [defaults, templates] = await Promise.all([
        get('/api/projects/defaults').catch((e) => {
          if (e.status === 401) throw e;
          return null;
        }),
        get('/api/templates').catch((e) => {
          if (e.status === 401) throw e;
          return null;
        }),
      ]);
    } catch (e) {
      if (this.handleHttpError(e)) {
        this.newProjectOpen = false;
        return;
      }
    }
    if (defaults) {
      if (defaults.group && !this.newProject.group) this.newProject.group = defaults.group;
      if (defaults.parentDir && !this.newProject.parentDir) this.newProject.parentDir = defaults.parentDir;
    } else if (!this.newProject.parentDir) {
      // Last-resort parent: $HOME from a bare fs listing (same as before defaults existed).
      try {
        const fs = await get('/api/fs');
        this.newProject.parentDir = fs.dir || '';
      } catch (e) {
        if (this.handleHttpError(e)) {
          this.newProjectOpen = false;
          return;
        }
      }
    }
    if (!this.newProject.group) this.newProject.group = 'com.example';
    // No hand-maintained fallback copy of the catalog: stale data is worse than an honest
    // "catalog unavailable" state (the input still accepts any template ref typed directly).
    const live = Array.isArray(templates) && templates.length ? templates : null;
    this.templates = live || [];
    this.templatesUnavailable = !live;
    this.onNewProjectLangChange();
    // Focus Name so the user can type the app name immediately; @focus selects any existing value.
    this.$nextTick(() => {
      const el = this.$refs.nameInput;
      if (el && typeof el.focus === 'function') el.focus();
    });
  },
  onNewProjectLangChange() {
    const fws = this.frameworkChoices.map((f) => f.value);
    if (this.newProject.framework && !fws.includes(this.newProject.framework)) {
      this.pickFramework('', 'All frameworks');
    }
    if (this.newProject.template && !this.templateChoices.some((c) => c.value === this.newProject.template)) {
      this.pickTemplate('', 'None — blank project');
    }
  },
  onFrameworkQueryInput() {
    this.frameworkOpen = true;
  },
  onFrameworkBlur() {
    setTimeout(() => { this.frameworkOpen = false; }, 120);
  },
  onFrameworkKey(ev) {
    if (ev.key === 'Escape') { this.frameworkOpen = false; ev.target.blur(); }
    if (ev.key === 'Enter' && this.filteredFrameworkChoices.length) {
      const f = this.filteredFrameworkChoices[0];
      this.pickFramework(f.value, f.label);
      ev.preventDefault();
    }
  },
  pickFramework(value, label) {
    this.newProject.framework = value || '';
    this.newProject.frameworkQuery = value ? label : '';
    this.frameworkOpen = false;
    if (this.newProject.template && !this.templateChoices.some((c) => c.value === this.newProject.template)) {
      this.pickTemplate('', 'None — blank project');
    }
  },
  onTemplateQueryInput() {
    this.templateOpen = true;
  },
  onTemplateBlur() {
    setTimeout(() => { this.templateOpen = false; }, 120);
  },
  onTemplateKey(ev) {
    if (ev.key === 'Escape') { this.templateOpen = false; ev.target.blur(); }
    if (ev.key === 'Enter' && this.filteredTemplateChoices.length) {
      const c = this.filteredTemplateChoices[0];
      this.pickTemplate(c.value, c.label);
      ev.preventDefault();
    }
  },
  pickTemplate(value, label) {
    this.newProject.template = value || '';
    this.newProject.templateQuery = value ? label : '';
    this.templateOpen = false;
  },
  selectedTemplate() {
    const id = this.newProject.template;
    if (!id) return null;
    return (this.templates || []).find((x) => x.id === id) || null;
  },
  showLayoutPicker() {
    const t = this.selectedTemplate();
    if (!t) return true;
    const lays = t.layouts || [];
    return lays.includes('traditional') && lays.includes('simple');
  },
  closeNewProject() {
    this.newProjectOpen = false;
    this.newProjectError = null;
    this.newProjectBusy = false;
  },
  async submitNewProject() {
    this.newProjectError = null;
    this.newProjectBusy = true;
    const hasTemplate = !!this.newProject.template;
    const body = {
      name: this.newProject.name.trim(),
      group: this.newProject.group.trim() || 'com.example',
      lang: this.newProject.lang,
      layout: this.newProject.layout,
      parentDir: this.newProject.parentDir.trim(),
      executable: hasTemplate ? false : !!this.newProject.executable,
    };
    if (hasTemplate) body.template = this.newProject.template;
    try {
      const res = await post('/api/projects', body);
      const path = res.path || res.dir;
      this.closeNewProject();
      this.newProject.name = '';
      this.newProject.template = '';
      this.newProject.templateQuery = '';
      this.newProject.framework = '';
      this.newProject.frameworkQuery = '';
      // Keep group + parentDir so the next create is one field away from a sibling project.
      if (path) {
        // Route with the durable projectId from the create response — never the
        // filesystem path: #project/<abs-path> lands a broken page in history (isValidId
        // rejects '/'). Without an id, skip the hash push instead of pushing a dead route.
        if (res.projectId) this.openProject(res.projectId, path);
        await this.triggerBuild(path);
        this.setView('activity');
      }
    } catch (e) {
      if (this.handleHttpError(e)) return;
      this.newProjectError = e.error || 'Could not create project';
    } finally {
      this.newProjectBusy = false;
    }
  },
  joinPath(dir, name) {
    return dir.endsWith('/') ? dir + name : dir + '/' + name;
  },
};
