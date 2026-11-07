// SPDX-License-Identifier: Apache-2.0
// The console's Needs-you banner: the decisions that block an agent, oldest first, from
// `GET /api/decisions` and the SSE `decision` frame. An engine without the queue answers 404, and
// the banner is then simply absent. Answering lives on the decision's own page (`#decision/<id>`);
// the banner links there and names the command a terminal answers it with.

/** A decision's opening time, whichever name the row carries it under. */
function openedAt(d) {
  return d.openedAt ?? d.createdAt ?? d.at ?? 0;
}

/** True while a decision still waits: no resolution and no settled state. */
export function isOpen(d) {
  if (!d || !d.id) return false;
  if (d.resolution) return false;
  return !d.state || d.state === 'open';
}

/** The rows of a `GET /api/decisions` body (`{ decisions }` or a bare array), open ones only, oldest first. */
export function openDecisions(body) {
  const rows = Array.isArray(body) ? body : (body && body.decisions) || [];
  return rows.filter(isOpen).sort((a, b) => openedAt(a) - openedAt(b));
}

/**
 * Fold one SSE `decision` frame (`{ change, decision }`, `opened` or `resolved`) into the open
 * list, returning a new array: an opened decision is added or replaced, a resolved one dropped.
 */
export function foldDecision(list, frame) {
  const d = frame && (frame.decision || frame);
  if (!d || !d.id) return list;
  const rest = (list || []).filter((x) => x.id !== d.id);
  if (frame.change === 'resolved' || !isOpen(d)) return rest;
  return [...rest, d].sort((a, b) => openedAt(a) - openedAt(b));
}

/**
 * Load the open decisions with `get` (api.js's), tolerating an engine that has no queue: a 404
 * is `{ available: false, decisions: [] }`. Any other error rethrows for the caller's 401 gate.
 */
export async function fetchDecisions(get) {
  try {
    return { available: true, decisions: openDecisions(await get('/api/decisions')) };
  } catch (e) {
    if (e && (e.status === 404 || e.status === 405)) return { available: false, decisions: [] };
    throw e;
  }
}

/** The host label of the session a decision came from, else its raw session id. */
function askedBy(d, sessions) {
  const id = d.session && typeof d.session === 'object' ? d.session.id : d.session;
  const s = (sessions || []).find((x) => x.id === id || (x.label && x.label === id));
  if (s) return s.label || s.client || (s.origin === 'cli' ? 'shell' : s.origin);
  return d.label || id || 'an agent';
}

/**
 * The banner's rows: `{ id, question, askedBy, sessionId, openedAt, deadline, href, command }`,
 * oldest first. `command` is the terminal's answer (`jk decide <id>`), shown beside the link so
 * a decision a dashboard button may not answer still says how to answer it.
 */
export function bannerRows(decisions, sessions) {
  return (decisions || []).filter(isOpen).map((d) => ({
    id: d.id,
    question: d.question || d.title || 'A decision is waiting',
    askedBy: askedBy(d, sessions),
    sessionId: d.session && typeof d.session === 'object' ? d.session.id : d.session || null,
    openedAt: openedAt(d) || null,
    deadline: d.deadline ?? null,
    href: '#decision/' + encodeURIComponent(d.id),
    command: 'jk decide ' + d.id,
  }));
}

/** Session ids (and labels) a decision is waiting on: their rail cards read "waiting on you". */
export function waitingSessions(decisions) {
  const out = new Set();
  for (const d of decisions || []) {
    if (!isOpen(d)) continue;
    const id = d.session && typeof d.session === 'object' ? d.session.id : d.session;
    if (id) out.add(id);
  }
  return out;
}
