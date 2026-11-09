// SPDX-License-Identifier: Apache-2.0
// Decisions: questions an operation puts to a person before it proceeds (a destructive clean, an
// uninstall). The engine holds them in one queue (GET /api/decisions, the SSE `decision` event).
// The header bell lists the open ones; a bell entry, a Needs-you row and the link a desktop
// notification opens are all `#decision/<id>`, which opens <decision-dialog> over whatever page is
// showing. Any surface may answer, the first answer wins, and the SSE frame of a settled decision
// closes the dialog everywhere else. Closing the dialog is not an answer.

import { adoptFragmentToken, get, post } from './api.js';

/** How long a dialog shows "settled by …" before it closes itself. */
export const SETTLED_CLOSE_MS = 2500;

/** The route that opens decision `id`. */
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

const SURFACE = Object.freeze({
  'host-form': "the agent's host",
  'host-url': "the agent's host",
  dashboard: 'the dashboard',
  cli: 'jk decide',
});

/** The answer that only moves a host's question to the dashboard; the dashboard never offers it. */
const SHOW = 'show';
/** The confirmation's field and its declining choice, as the engine's DecisionForms names them. */
const ANSWER = 'answer';
const NO = 'no';

/** An option as the form carries it (a bare id or `{id, label, help}`), as `{id, label, help}`. */
function option(o) {
  return typeof o === 'string' ? { id: o, label: o, help: '' } : { id: o.id, label: o.label || o.id, help: o.help || '' };
}

/** Every field of the decision's form, in step order. */
function formFields(decision) {
  const steps = (decision && decision.form && decision.form.steps) || [];
  return steps.flatMap((s) => s.fields || []);
}

/**
 * The choices a decision is answered with, when its form is one choice and nothing else: one
 * `{id, label, declines}` per option, in the form's order (the order the agent's host shows them),
 * `show` left out. Null for a form with real inputs. `declines` mirrors the engine: the
 * confirmation's `no` settles declined, every other choice accepted.
 */
export function decisionChoices(decision) {
  const fields = formFields(decision);
  if (fields.length !== 1 || fields[0].kind !== 'choice') return null;
  const field = fields[0];
  const choices = (field.options || [])
    .map(option)
    .filter((o) => o.id !== SHOW)
    .map((o) => ({ ...o, key: field.key, declines: field.key === ANSWER && o.id === NO }));
  return choices.length ? choices : null;
}

/** A field as the dialog renders it. */
function inputField(f) {
  return {
    key: f.key,
    label: f.label || f.key,
    help: f.help || '',
    kind: f.kind,
    required: !!f.required,
    options: (f.options || []).map(option),
  };
}

/** The confirmation's answer field when the form has other fields too (a batch), else null. */
function batchAnswer(decision) {
  const fields = formFields(decision);
  if (fields.length < 2) return null;
  const answer = fields.find((f) => f.key === ANSWER && f.kind === 'choice');
  return answer || null;
}

/** A form with real inputs: its fields as the dialog renders them. */
function inputFields(decision) {
  return formFields(decision).map((f) => ({
    key: f.key,
    label: f.label || f.key,
    help: f.help || '',
    kind: f.kind,
    required: !!f.required,
    options: (f.options || []).map(option),
  }));
}

/** The starting values of a form with real inputs: each field's default, else empty. */
export function initialValues(decision) {
  const out = {};
  for (const f of formFields(decision)) {
    if (f.default !== undefined) out[f.key] = f.default;
    else if (f.kind === 'bool') out[f.key] = false;
    else if (f.kind === 'multi') out[f.key] = [];
    else if (f.kind === 'choice' && f.options && f.options.length) out[f.key] = option(f.options[0]).id;
    else out[f.key] = '';
  }
  return out;
}

/** The line a settled decision shows: how it ended, and who settled it. */
export function settledLine(decision) {
  if (!decision || decision.state === 'open') return '';
  if (decision.state === 'expired') return 'Expired: nobody answered in time.';
  const by = decision.answeredBy ? SURFACE[decision.answeredBy] || decision.answeredBy : '';
  return (by ? 'Settled by ' + by : 'Settled') + ': ' + decision.state + '.';
}

/**
 * What the dialog shows for `decision`. `mode` is `choices` (the answers are the buttons, a row of
 * two or a column of more), `batch` (a checklist of changes, each with what it does and why, then
 * the confirmation's answers as the buttons) or `form` (fields, a submit and a decline).
 */
export function decisionView(decision) {
  if (!decision) return null;
  const open = decision.state === 'open';
  const answer = batchAnswer(decision);
  if (answer) {
    const choices = (answer.options || [])
      .map(option)
      .filter((o) => o.id !== SHOW)
      .map((o) => ({ ...o, key: ANSWER, declines: o.id === NO }));
    return {
      ...baseView(decision, open),
      mode: 'batch',
      layout: 'row',
      choices,
      fields: formFields(decision).filter((f) => f.key !== ANSWER).map(inputField),
    };
  }
  const choices = decisionChoices(decision);
  return {
    ...baseView(decision, open),
    mode: choices ? 'choices' : 'form',
    layout: choices && choices.length === 2 ? 'row' : 'column',
    choices: choices || [],
    fields: choices ? [] : inputFields(decision),
  };
}

/** What every view carries: the question, who asked, the deadline and how it stands. */
function baseView(decision, open) {
  return {
    id: decision.id,
    question: decision.question,
    detail: Array.isArray(decision.detail) ? decision.detail : [],
    who: decision.label || (decision.kind === 'setup' ? 'JumpKick' : 'an agent'),
    project: decision.project || null,
    deadline: decision.deadline || null,
    state: decision.state,
    open,
    settled: settledLine(decision),
  };
}

/**
 * The body a batch's answer button sends: its yes accepts with the ticked changes, its no declines.
 * The ticked changes keep the form's order.
 */
export function batchBody(choice, values, view) {
  if (choice.declines) return DECLINE_BODY;
  const answers = { [ANSWER]: choice.id };
  for (const f of view.fields) {
    const v = values[f.key];
    answers[f.key] = f.kind === 'multi' ? f.options.map((o) => o.id).filter((id) => (v || []).includes(id)) : v;
  }
  return { action: 'accept', answers };
}

/** The POST /api/decisions/<id> body that answers with `choice`: the engine settles it by the choice. */
export function choiceBody(choice) {
  return { action: 'accept', answers: { [choice.key]: choice.id } };
}

/** The POST body that submits a form with real inputs. */
export function formBody(values) {
  return { action: 'accept', answers: { ...values } };
}

/** The POST body that declines. */
export const DECLINE_BODY = Object.freeze({ action: 'decline' });

/** The decision an SSE `decision` frame carries, whichever shape it arrives in. */
export function frameDecision(frame) {
  const d = frame && (frame.decision || frame);
  return d && d.id ? d : null;
}

/**
 * The root component's share: which decision the dialog shows and the bell's menu. `#decision/<id>`
 * opens the dialog over the page under it, and closing puts that page's route back.
 */
export function decisionData(route) {
  return {
    decisionId: route.decisionId || null, // the decision the dialog shows, from #decision/<id>
    decisionEvent: null, // the newest SSE decision, which the open dialog follows
    bellOpen: false,
    bellSeenFrom: 0, // the marker as it stood when the bell opened: what it highlights as new
    _underHash: '', // the route under the dialog, put back when it closes
  };
}

export const decisionMethods = {
  /**
   * A route change: `#decision/<id>` opens the dialog and leaves the page alone; true when it did.
   * `change` is the `hashchange` event, whose old URL is the page's route to put back.
   */
  routeDecision(route, change) {
    this.bellOpen = false;
    if (route.decisionId) {
      const from = change && change.oldURL ? new URL(change.oldURL).hash : null;
      if (from != null && !from.startsWith('#decision/')) this._underHash = from;
      // A notification's link carries the token (`?t=`): keep it, and keep it out of the address bar.
      const scrubbed = adoptFragmentToken(location.hash);
      if (scrubbed != null) history.replaceState(null, '', location.pathname + location.search + scrubbed);
      this.decisionId = route.decisionId;
      this.markSeen(); // a dialog shown, from a click or a notification, is seen
      return true;
    }
    this.decisionId = null;
    return false;
  },
  /** Dismiss the dialog: the decision stays open, in the bell, and the page's route comes back. */
  closeDecision() {
    this.decisionId = null;
    history.replaceState(null, '', this._underHash || location.pathname + location.search);
  },
  /** Opening the bell clears its count, in every tab. */
  toggleBell() {
    this.bellOpen = !this.bellOpen;
    if (this.bellOpen) {
      this.bellSeenFrom = this.seenUpTo;
      this.markSeen();
      if (this.dismissPreview) this.dismissPreview();
    }
  },
  /** A click outside the bell, or Esc, folds its menu. */
  foldBell(event) {
    if (!this.bellOpen) return;
    if (event && event.type === 'keydown' && event.key !== 'Escape') return;
    if (event && event.type !== 'keydown' && event.target && event.target.closest && event.target.closest('.bell')) return;
    this.bellOpen = false;
  },
};

/**
 * `<decision-dialog>`: one decision over the page, a fixed size, its detail scrolling inside.
 * Emits `close` when dismissed (Esc, the close button, the backdrop), once answered here, and a
 * moment after another surface settles it.
 */
export const DecisionDialog = {
  props: {
    id: { type: String, required: true },
    event: { type: Object, default: null },
    now: { type: Number, default: 0 },
  },
  emits: ['close'],
  data: () => ({ decision: null, error: '', busy: false, values: {}, closing: null }),
  computed: {
    view() {
      return decisionView(this.decision);
    },
    left() {
      return this.view && this.view.open ? leftLabel(this.view.deadline, this.now || Date.now()) : '';
    },
  },
  watch: {
    id() {
      this.load();
    },
    event(frame) {
      const d = frameDecision(frame);
      // An answer sent from here settles through its own reply.
      if (!d || d.id !== this.id || this.busy) return;
      this.show(d);
    },
  },
  mounted() {
    this._onKey = (e) => {
      if (e.key === 'Escape') this.dismiss();
    };
    window.addEventListener('keydown', this._onKey);
    this.load();
    this.$nextTick(() => this.$refs.dialog && this.$refs.dialog.focus());
  },
  unmounted() {
    window.removeEventListener('keydown', this._onKey);
    clearTimeout(this.closing);
  },
  methods: {
    async load() {
      clearTimeout(this.closing);
      this.closing = null;
      this.decision = null;
      this.error = '';
      try {
        this.show(await get('/api/decisions/' + encodeURIComponent(this.id)));
      } catch (e) {
        this.error = e && e.status === 404 ? 'No such decision: it expired or the engine restarted.' : String((e && e.error) || e);
      }
    },
    /** Shows `d`; a settled one says how and closes itself shortly after. */
    show(d) {
      this.decision = d;
      if (d.state === 'open') {
        this.values = initialValues(d);
        return;
      }
      if (this.closing == null) this.closing = setTimeout(() => this.$emit('close'), SETTLED_CLOSE_MS);
    },
    dismiss() {
      this.$emit('close');
    },
    async send(body) {
      this.busy = true;
      this.error = '';
      try {
        await post('/api/decisions/' + encodeURIComponent(this.id), body);
        this.$emit('close');
      } catch (e) {
        if (e && e.status === 409) {
          // Another surface answered first: say how it was settled.
          const d = await get('/api/decisions/' + encodeURIComponent(this.id)).catch(() => null);
          if (d) this.show(d);
          else {
            this.error = 'Another surface answered it first.';
            this.closing = setTimeout(() => this.$emit('close'), SETTLED_CLOSE_MS);
          }
        } else {
          this.error = String((e && e.error) || e);
        }
      } finally {
        this.busy = false;
      }
    },
    answer(choice) {
      return this.send(this.view.mode === 'batch' ? batchBody(choice, this.values, this.view) : choiceBody(choice));
    },
    ticked(key, id) {
      return Array.isArray(this.values[key]) && this.values[key].includes(id);
    },
    submit() {
      return this.send(formBody(this.values));
    },
    decline() {
      return this.send(DECLINE_BODY);
    },
    toggleMulti(key, id) {
      const now = Array.isArray(this.values[key]) ? this.values[key] : [];
      this.values[key] = now.includes(id) ? now.filter((x) => x !== id) : [...now, id];
    },
  },
  template: `
    <div class="modal-bg decision-modal-bg" role="presentation" @click.self="dismiss()">
      <div class="modal decision-modal" role="dialog" aria-modal="true" aria-labelledby="decision-q"
           tabindex="-1" ref="dialog" :data-decision="id">
        <div class="modal-head">
          <span class="dot-warn" aria-hidden="true"></span>
          <span class="lbl">needs you</span>
          <span v-if="left" class="dim small">{{ left }}</span>
          <span class="spacer"></span>
          <button type="button" class="icon-btn" @click="dismiss()" aria-label="Close" data-tip="Close; the decision stays open">
            <jk-icon name="x"></jk-icon></button>
        </div>
        <div class="decision-body modal-scroll">
          <p v-if="!view && !error" class="empty">Loading the decision…</p>
          <template v-if="view">
            <h3 id="decision-q" class="decision-q">{{ view.question }}</h3>
            <p class="decision-who">Asked by {{ view.who }}<template v-if="view.project"> in <code>{{ view.project }}</code></template></p>
            <ul v-if="view.open && view.mode === 'batch'" class="decision-changes">
              <template v-for="f in view.fields" :key="f.key">
                <li v-for="o in f.options" :key="o.id" :data-change="o.id">
                  <label>
                    <input type="checkbox" :checked="ticked(f.key, o.id)" :disabled="busy" @change="toggleMulti(f.key, o.id)">
                    <span class="decision-change"><span class="decision-change-what">{{ o.label }}</span>
                      <span v-if="o.help" class="decision-change-why">{{ o.help }}</span></span>
                  </label>
                </li>
              </template>
            </ul>
            <ul v-if="view.detail.length" class="decision-detail"><li v-for="(line, i) in view.detail" :key="i"><code>{{ line }}</code></li></ul>
            <div v-if="view.open && view.mode === 'form'" class="decision-fields">
              <label v-for="f in view.fields" :key="f.key" class="decision-field" :data-field="f.key">
                <span>{{ f.label }}<span v-if="f.help" class="dim"> {{ f.help }}</span></span>
                <input v-if="f.kind === 'bool'" type="checkbox" v-model="values[f.key]">
                <select v-else-if="f.kind === 'choice'" v-model="values[f.key]">
                  <option v-for="o in f.options" :key="o.id" :value="o.id">{{ o.label }}</option></select>
                <span v-else-if="f.kind === 'multi'" class="decision-multi">
                  <button v-for="o in f.options" :key="o.id" type="button" :aria-pressed="(values[f.key] || []).includes(o.id)"
                          @click="toggleMulti(f.key, o.id)">{{ o.label }}</button></span>
                <input v-else v-model="values[f.key]" spellcheck="false" autocomplete="off">
              </label>
            </div>
          </template>
        </div>
        <div class="decision-foot">
          <p v-if="error" class="error">{{ error }}</p>
          <p v-if="view && !view.open" class="decision-settled">{{ view.settled }}</p>
          <div v-if="view && view.open && (view.mode === 'choices' || view.mode === 'batch')" class="decision-choices" :class="view.layout">
            <button v-for="c in view.choices" :key="c.id" type="button" :class="c.declines ? 'declines' : 'accepts'"
                    :disabled="busy" :data-choice="c.id" @click="answer(c)">{{ c.label }}</button>
          </div>
          <div v-if="view && view.open && view.mode === 'form'" class="decision-choices row">
            <button type="button" class="declines" :disabled="busy" @click="decline()">Decline</button>
            <button type="button" class="accepts" :disabled="busy" @click="submit()">Submit</button>
          </div>
        </div>
      </div>
    </div>`,
};
