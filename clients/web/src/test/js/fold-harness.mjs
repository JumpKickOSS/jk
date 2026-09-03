// SPDX-License-Identifier: Apache-2.0
// Shared SPA bindings and event fixtures for the fold-layer node --test suites.
// One import set so a split cannot drift from fold.js's export list.
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));

export const {
  etaTotalMillis,
  foldEvent,
  historyCard,
  ioLines,
  MAX_CARDS,
  MAX_DIAGNOSTICS,
  MAX_OUTPUT_LINES,
  MAX_TEST_FAILURE_DIAGNOSTICS,
  normalizeDiagnostic,
  seedFromHistory,
  startAnchor,
  stepTimingLabel,
  weightDenominator,
  weightNumerator,
} = await spa('fold.js');
export const { moduleSummary, orderedModules, outcomeOf, phaseChainOf } = await spa('outcome.js');
export const { fmtBytes, fmtDuration } = await spa('format.js');
export const {
  detailForDisplay,
  detailSegments,
  liveStepDetail,
  looksLikeJavaMember,
  shortDisplayLabel,
  shortTestLabel,
  simpleTypeName,
  simplifyMethodParams,
} = await spa('label.js');
export const {
  compilerFailureReports,
  isCompilerDiag,
  isTestFailureDiag,
  parseAssertJMessage,
  parseCompilerBlock,
  snippetWindow,
  stackFrameLines,
  testFailureReport,
} = await spa('failure.js');

export const historyRecord = (id, dir, extra = {}) => ({
  id,
  kind: 'build',
  dir,
  coord: 'g:a',
  startedAt: 1000,
  finishedAt: 2000,
  millis: 1000,
  cancelled: false,
  success: true,
  modules: [],
  tasks: [],
  diagnostics: [],
  ...extra,
});

export const start = (id, dir, extra = {}) => ({
  type: 'request-start',
  data: { jid: id, kind: 'build', dir, ...extra },
});
export const finish = (id, data = {}) => ({ type: 'request-finish', data: { jid: id, ...data } });
