// SPDX-License-Identifier: Apache-2.0
// Decisions: questions an operation puts to a person before it proceeds (a destructive clean, an
// uninstall). The engine holds them in one queue (GET /api/decisions, the SSE `decision` event);
// this file is the `#decision/<id>` page and the view model the console's Needs-you banner reads.
// Your agent asks you; this page is where the details live. Any surface may answer, the first
// answer wins, and the SSE frame of a settled decision clears it everywhere else.

import { get, post } from './api.js';

/** The route that shows decision `id`. */
export function decisionHash(id) {
  return '#decision/' + encodeURIComponent(id);
}

/** Minutes and seconds left before `deadline`, `''` once it passed. */
export function leftLabel(deadline, now) {
  const ms = Number(deadline) - Number(now);
  if (!Number.isFinite(ms) || ms <= 0) return '';
  const min = Math.floor(ms / 60000);
  return min >= 1 ? min + ' min left' : Math.ceil(ms / 1000) + ' s left';
}

/**
 * The Needs-you banner's rows: every open decision, newest first, with what a row shows and where
 * it links. `list` is GET /api/decisions' `decisions`.
 */
export function openDecisions(list, now = Date.now()) {
  if (!Array.isArray(list)) return [];
  return list
    .filter((d) => d && d.state === 'open')
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
    .map((d) => ({
      id: d.id,
      kind: d.kind,
      question: d.question,
      who: d.label || 'an agent',
      project: d.project || null,
      href: decisionHash(d.id),
      left: leftLabel(d.deadline, now),
    }));
}

/** Folds one SSE `decision` payload into `list`: replaces the decision with its id, or adds it. */
export function foldDecision(list, decision) {
  const out = Array.isArray(list) ? list.slice() : [];
  if (!decision || !decision.id) return out;
  const at = out.findIndex((d) => d.id === decision.id);
  if (at >= 0) out[at] = decision;
  else out.unshift(decision);
  return out;
}

/** The confirmation's choices, from the decision's form: `[{id, label}]`, `show` left out. */
export function answerOptions(decision) {
  const steps = (decision && decision.form && decision.form.steps) || [];
  for (const step of steps) {
    for (const field of step.fields || []) {
      if (field.key === 'answer') {
        return (field.options || [])
          .filter((o) => o.id !== 'show')
          .map((o) => ({ id: o.id, label: o.label || o.id }));
      }
    }
  }
  return [];
}

const SURFACE = Object.freeze({
  'host-form': "the agent's host",
  'host-url': "the agent's host",
  dashboard: 'the dashboard',
  cli: 'jk decide',
});

/** What the page shows for `decision`: an open one takes either answer here. */
export function decisionView(decision) {
  if (!decision) return null;
  const open = decision.state === 'open';
  return {
    id: decision.id,
    question: decision.question,
    detail: Array.isArray(decision.detail) ? decision.detail : [],
    who: decision.label || 'an agent',
    project: decision.project || null,
    state: decision.state,
    open,
    command: decision.command,
    answeredBy: decision.answeredBy ? SURFACE[decision.answeredBy] || decision.answeredBy : '',
    options: answerOptions(decision),
  };
}

/** The POST /api/decisions/<id> body. */
export function answerBody(action, answer) {
  const body = { action };
  if (action === 'accept') body.answers = { answer };
  return body;
}

/** `#decision/<id>`: the question, its detail, and its answers. */
export const DecisionPage = {
  props: { id: { type: String, default: null }, event: { type: Object, default: null } },
  data: () => ({ decision: null, error: '', busy: false, choice: 'yes' }),
  computed: {
    view() {
      return decisionView(this.decision);
    },
  },
  watch: {
    id() {
      this.load();
    },
    event(e) {
      if (e && e.id === this.id) this.decision = e;
    },
  },
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      if (!this.id) return;
      if (location.hash.indexOf('?') >= 0) history.replaceState(null, '', decisionHash(this.id));
      this.error = '';
      try {
        this.decision = await get('/api/decisions/' + encodeURIComponent(this.id));
      } catch (e) {
        this.error = e && e.status === 404 ? 'No such decision: it expired or the engine restarted.' : String((e && e.error) || e);
      }
    },
    async send(action) {
      this.busy = true;
      this.error = '';
      try {
        this.decision = await post('/api/decisions/' + encodeURIComponent(this.id), answerBody(action, this.choice));
      } catch (e) {
        this.error = String((e && e.error) || e);
        // A 409 means another surface answered first: show how it was settled.
        this.decision = await get('/api/decisions/' + encodeURIComponent(this.id)).catch(() => this.decision);
      } finally {
        this.busy = false;
      }
    },
  },
  template: `
    <main class="decision-page">
      <div class="feed-head"><h2>Needs you</h2><span v-if="view" class="retention">{{ view.state }}</span></div>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="!view && !error" class="empty">Loading the decision…</p>
      <section v-if="view" class="decision" :data-decision="view.id">
        <h3>{{ view.question }}</h3>
        <p class="dim small">Asked by {{ view.who }}<template v-if="view.project"> in {{ view.project }}</template></p>
        <ul v-if="view.detail.length" class="decision-detail"><li v-for="line in view.detail" :key="line"><code>{{ line }}</code></li></ul>
        <div v-if="view.open" class="decision-answer">
          <label v-for="o in view.options" :key="o.id"><input type="radio" name="answer" :value="o.id" v-model="choice"> {{ o.label }}</label>
        </div>
        <div v-if="view.open" class="detail-actions">
          <button type="button" class="action-cyan" :disabled="busy" @click="send('accept')"><jk-icon name="check"></jk-icon>Answer</button>
          <button type="button" :disabled="busy" @click="send('decline')"><jk-icon name="x"></jk-icon>Decline</button>
        </div>
        <p v-if="!view.open" class="dim">Settled: {{ view.state }}<template v-if="view.answeredBy"> by {{ view.answeredBy }}</template>.</p>
      </section>
    </main>`,
};
