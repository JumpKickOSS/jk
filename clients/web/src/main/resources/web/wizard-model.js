// SPDX-License-Identifier: Apache-2.0
// The new-project wizard's view model: the engine's form (GET /api/forms/new-project) read the way
// the engine reads it. Conditions, defaults, fit and settling follow cc.jumpkick.form.Form; the
// equivalent command follows FormCommand; links into the wizard are the shape NewProjectWizard
// writes. Pure functions of (form, answers), so the page, `node --test` and the conformance test all
// drive the same code. Nothing here is per template: every field comes from the form.

/** jk's step keys; every other key of a new-project form is a template property. */
export const PROJECT_KEYS = ['lang', 'template', 'name', 'group', 'dir', 'layout'];

/** How `jk new` spells each of jk's fields; the rest are `-p key=value`. */
const FLAGS = {
  lang: '--lang',
  template: '-t',
  name: null, // positional
  group: '--group',
  dir: '--dir',
  layout: '-L',
};

/** Query keys of a wizard link that are not answers. */
const LINK_KEYS = new Set(['t', 'elicitation']);

// ---- conditions: the grammar on cc.jumpkick.form.Condition ----

function tokenize(src) {
  const out = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
    } else if (c === "'" || c === '"') {
      const close = src.indexOf(c, i + 1);
      if (close < 0) throw new Error(`condition "${src}" has an unclosed string`);
      out.push({ kind: 'str', text: src.slice(i + 1, close) });
      i = close + 1;
    } else if (/\p{L}/u.test(c)) {
      let end = i + 1;
      while (end < src.length && /[\p{L}\p{N}_]/u.test(src[end])) end++;
      const word = src.slice(i, end);
      const kind = word === 'true' || word === 'false' ? 'bool' : word === 'in' ? 'in' : 'key';
      out.push({ kind, text: word });
      i = end;
    } else {
      const two = src.slice(i, i + 2);
      if (['==', '!=', '&&', '||'].includes(two)) {
        out.push({ kind: two, text: two });
        i += 2;
      } else if ('!()[],'.includes(c)) {
        out.push({ kind: c, text: c });
        i++;
      } else {
        throw new Error(`condition "${src}" has '${c}', which is not part of the language`);
      }
    }
  }
  out.push({ kind: 'end', text: '' });
  return out;
}

/** Parses a `when` into a tree of {op, …}; throws on a syntax error. */
export function parseCondition(src) {
  const toks = tokenize(String(src));
  let at = 0;
  const peek = () => toks[at];
  const accept = (kind) => (toks[at].kind === kind ? (at++, true) : false);
  const expect = (kind) => {
    if (!accept(kind)) throw new Error(`condition "${src}" expects '${kind}', got '${peek().text}'`);
  };
  const literal = () => {
    const t = toks[at++];
    if (t.kind === 'str') return t.text;
    if (t.kind === 'bool') return t.text === 'true';
    throw new Error(`condition "${src}" expects a quoted string, true or false, got '${t.text}'`);
  };
  const unary = () => {
    if (accept('!')) return { op: 'not', operand: unary() };
    if (accept('(')) {
      const inner = or();
      expect(')');
      return inner;
    }
    const key = toks[at++];
    if (key.kind !== 'key') throw new Error(`condition "${src}" expects a field key, got '${key.text}'`);
    if (accept('==')) return { op: 'eq', key: key.text, literal: literal(), negated: false };
    if (accept('!=')) return { op: 'eq', key: key.text, literal: literal(), negated: true };
    if (accept('in')) {
      expect('[');
      const literals = [literal()];
      while (accept(',')) literals.push(literal());
      expect(']');
      return { op: 'in', key: key.text, literals };
    }
    return { op: 'flag', key: key.text };
  };
  const and = () => {
    let left = unary();
    while (accept('&&')) left = { op: 'and', left, right: unary() };
    return left;
  };
  const or = () => {
    let left = and();
    while (accept('||')) left = { op: 'or', left, right: and() };
    return left;
  };
  if (peek().kind === 'end') throw new Error(`condition "${src}" is empty`);
  const expr = or();
  if (peek().kind !== 'end') throw new Error(`condition "${src}" has '${peek().text}' where it should end`);
  return expr;
}

/** Evaluates a parsed condition against the values settled so far; an absent key has no value. */
export function testCondition(expr, values) {
  switch (expr.op) {
    case 'eq': {
      const equal = values[expr.key] === expr.literal;
      return expr.negated !== equal;
    }
    case 'in': {
      const v = values[expr.key];
      if (Array.isArray(v)) return v.some((x) => expr.literals.includes(x));
      return v != null && expr.literals.includes(v);
    }
    case 'flag':
      return values[expr.key] === true;
    case 'not':
      return !testCondition(expr.operand, values);
    case 'and':
      return testCondition(expr.left, values) && testCondition(expr.right, values);
    case 'or':
      return testCondition(expr.left, values) || testCondition(expr.right, values);
    default:
      throw new Error('unknown condition op ' + expr.op);
  }
}

const parsedWhen = new Map();

/** Whether `field` is shown, given the values settled before it. */
export function visible(field, values) {
  if (!field.when) return true;
  let expr = parsedWhen.get(field.when);
  if (!expr) {
    expr = parseCondition(field.when);
    parsedWhen.set(field.when, expr);
  }
  return testCondition(expr, values);
}

// ---- fields ----

/** Every field of `form`, in form order. */
export function fieldsOf(form) {
  return (form?.steps || []).flatMap((s) => s.fields || []);
}

export function optionId(o) {
  return typeof o === 'string' ? o : o.id;
}

export function optionLabel(o) {
  return typeof o === 'string' ? o : o.label || o.id;
}

/**
 * The field's default: the literal, or `derived[from]` for an engine-derived one (`git-email`,
 * `parent-dir`). A derived value that does not fit counts as none.
 */
export function defaultOf(field, derived = {}) {
  if (field.default !== undefined) return field.default;
  if (field.from) {
    const v = derived[field.from];
    return v == null || problem(field, v) != null ? null : v;
  }
  return null;
}

/** Why `value` is not an answer to `field`, or null when it is (type, options, pattern). */
export function problem(field, value) {
  const ids = (field.options || []).map(optionId);
  const notAnOption = (v) => `${field.key} must be one of ${ids.join(', ')}; got '${v}'`;
  switch (field.kind) {
    case 'bool':
      return typeof value === 'boolean' ? null : `${field.key} must be true or false`;
    case 'text':
      if (typeof value !== 'string') return `${field.key} must be a string`;
      if (field.pattern && !new RegExp('^(?:' + field.pattern + ')$').test(value)) {
        return `${field.key} must match ${field.pattern}; got '${value}'`;
      }
      return null;
    case 'choice':
      return typeof value === 'string' && ids.includes(value) ? null : notAnOption(value);
    case 'multi':
      if (!Array.isArray(value)) return `${field.key} must be a list of ${ids.join(', ')}`;
      for (const v of value) if (!ids.includes(v)) return notAnOption(v);
      return new Set(value).size === value.length ? null : `${field.key} lists an option twice`;
    case 'search':
    case 'path':
      return typeof value === 'string' && value !== '' ? null : `${field.key} must be a non-empty string`;
    default:
      return `${field.key} has an unknown kind ${field.kind}`;
  }
}

/**
 * A text answer (a link's query value) as the field's type: a bool reads true/yes/y or
 * false/no/n, a multi splits on commas; a value that already has a type is kept.
 */
export function parseValue(field, raw) {
  if (typeof raw !== 'string') return Array.isArray(raw) ? raw.map(String) : raw;
  const s = raw.trim();
  if (field.kind === 'bool') {
    const t = s.toLowerCase();
    if (['true', 'yes', 'y'].includes(t)) return true;
    if (['false', 'no', 'n'].includes(t)) return false;
    return null;
  }
  if (field.kind === 'multi') return s.split(',').map((x) => x.trim()).filter(Boolean);
  return raw;
}

/** An answer as text: a multi joined on commas. */
export function valueText(value) {
  return Array.isArray(value) ? value.join(',') : String(value);
}

function inOptionOrder(field, value) {
  if (!Array.isArray(value)) return value;
  return (field.options || []).map(optionId).filter((id) => value.includes(id));
}

/**
 * Settles `answers` against the form, field by field in order, as Form.check does: a hidden field
 * takes its default whatever was answered; a shown one its answer when it fits, else its default; a
 * required field left with neither is a problem. An empty string is no answer to a required field.
 */
export function settle(form, answers, derived = {}) {
  const values = {};
  const hidden = new Set();
  const problems = [];
  for (const f of fieldsOf(form)) {
    const fallback = defaultOf(f, derived);
    if (!visible(f, values)) {
      hidden.add(f.key);
      if (fallback != null) values[f.key] = fallback;
      continue;
    }
    let answer = answers[f.key];
    if (answer === '' && f.required) answer = null;
    if (answer != null) {
      const p = problem(f, answer);
      if (p) problems.push({ key: f.key, message: p });
      else values[f.key] = inOptionOrder(f, answer);
    } else if (fallback != null) {
      values[f.key] = fallback;
    } else if (f.required) {
      problems.push({ key: f.key, message: f.key + ' needs a value' });
    }
  }
  return { values, hidden, problems };
}

/** The wizard's steps: jk's, then the template's (every later form step folds into it), then review. */
export function wizardSteps(form) {
  const steps = form?.steps || [];
  const first = steps[0] || { id: 'project', title: 'Project', fields: [] };
  const rest = steps.slice(1).flatMap((s) => s.fields || []);
  return [
    { id: first.id, title: first.title, fields: first.fields || [] },
    { id: 'template', title: steps[1]?.title || 'Template', fields: rest },
    { id: 'review', title: 'Review', fields: [] },
  ];
}

/** `fields` split for rendering: the shown ones, the advanced ones folded apart. */
export function shownFields(fields, values) {
  const plain = [];
  const advanced = [];
  for (const f of fields) {
    if (!visible(f, values)) continue;
    (f.advanced ? advanced : plain).push(f);
  }
  return { plain, advanced };
}

/** What POST /api/projects and /preview take: the form's rev and every shown field's settled value. */
export function postBody(form, answers, derived = {}) {
  const { values, hidden } = settle(form, answers, derived);
  const out = {};
  for (const f of fieldsOf(form)) {
    if (!hidden.has(f.key) && values[f.key] != null) out[f.key] = values[f.key];
  }
  return { rev: form.rev, answers: out };
}

/** A POSIX shell word: bare when safe, else single-quoted. */
export function shellQuote(word) {
  const w = String(word);
  if (/^[A-Za-z0-9_./:@%+=,-]+$/.test(w)) return w;
  return "'" + w.replace(/'/g, "'\\''") + "'";
}

function sameValue(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * The `jk new` command that scaffolds what the answers settle to, spelled as FormCommand spells it:
 * the name positional, jk's fields by flag, the template's as `-p key=value`; a hidden field, or
 * one whose value is its literal default, is left out. A derived default (the group, the directory)
 * depends on where the command runs, so it is always spelled.
 */
export function equivalentCommand(form, answers, derived = {}) {
  const { values, hidden } = settle(form, answers, derived);
  const positional = [];
  const named = [];
  for (const f of fieldsOf(form)) {
    const v = values[f.key];
    if (v == null || hidden.has(f.key)) continue;
    if (f.default !== undefined && sameValue(v, f.default)) continue;
    if (!(f.key in FLAGS)) {
      named.push('-p', shellQuote(f.key + '=' + valueText(v)));
    } else if (FLAGS[f.key] === null) {
      positional.push(shellQuote(valueText(v)));
    } else if (typeof v === 'boolean') {
      named.push(v ? FLAGS[f.key] : FLAGS[f.key] + '=false');
    } else {
      named.push(FLAGS[f.key], shellQuote(valueText(v)));
    }
  }
  return ['jk new', ...positional, ...named].join(' ');
}

/** The endpoint a search field lists its options from, its `{key}` placeholders filled in. */
export function sourceFor(field, values) {
  return String(field.source || '').replace(/\{([^}]*)\}/g, (_, k) =>
    values[k] == null ? '' : encodeURIComponent(valueText(values[k])),
  );
}

// ---- links into the wizard: #new?<key>=<value>&…[&elicitation=<id>] ----

/** The answers and elicitation id a `#new?…` hash carries, as text; the token is not an answer. */
export function linkFromHash(hash) {
  const h = String(hash || '');
  const q = h.indexOf('?');
  const answers = {};
  let elicitation = null;
  if (q >= 0) {
    for (const pair of h.slice(q + 1).split('&')) {
      const eq = pair.indexOf('=');
      if (eq <= 0) continue;
      let k;
      let v;
      try {
        k = decodeURIComponent(pair.slice(0, eq));
        v = decodeURIComponent(pair.slice(eq + 1).replace(/\+/g, ' '));
      } catch {
        continue;
      }
      if (k === 'elicitation') elicitation = v;
      else if (!LINK_KEYS.has(k)) answers[k] = v;
    }
  }
  return { answers, elicitation };
}

/** The `#new?…` hash that opens the wizard pre-filled with `answers`. */
export function wizardHash(answers, elicitation = null) {
  const pairs = [];
  for (const [k, v] of Object.entries(answers || {})) {
    if (v == null || v === '') continue;
    pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(valueText(v)));
  }
  if (elicitation) pairs.push('elicitation=' + encodeURIComponent(elicitation));
  return pairs.length ? '#new?' + pairs.join('&') : '#new';
}

/** Link answers typed for `form`; a key the form does not have is dropped, a misfit too. */
export function typedAnswers(form, raw) {
  const out = {};
  for (const f of fieldsOf(form)) {
    if (raw[f.key] == null) continue;
    const v = parseValue(f, raw[f.key]);
    if (v != null && problem(f, v) == null) out[f.key] = v;
  }
  return out;
}

/**
 * The answers the wizard opens with, from a served form ({form, template}) and a link's raw
 * answers: typed for the form, the template the form's own id.
 */
export function openingAnswers(served, raw) {
  const answers = typedAnswers(served.form, raw || {});
  answers.template = served.template;
  return answers;
}

/** Flat file paths as indented rows for the preview: directories first, each level name-sorted. */
export function treeRows(paths) {
  const root = { dirs: new Map(), files: [] };
  for (const p of paths || []) {
    const segs = String(p).split('/').filter(Boolean);
    let node = root;
    for (const s of segs.slice(0, -1)) {
      if (!node.dirs.has(s)) node.dirs.set(s, { dirs: new Map(), files: [] });
      node = node.dirs.get(s);
    }
    if (segs.length) node.files.push(segs[segs.length - 1]);
  }
  const rows = [];
  const cmp = (a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
  const walk = (node, depth) => {
    for (const name of [...node.dirs.keys()].sort(cmp)) {
      rows.push({ depth, name, dir: true });
      walk(node.dirs.get(name), depth + 1);
    }
    for (const name of node.files.sort(cmp)) rows.push({ depth, name, dir: false });
  };
  walk(root, 0);
  return rows;
}
