// SPDX-License-Identifier: Apache-2.0
// The new-project wizard's view model over a form shaped as GET /api/forms/new-project serves it:
// conditions, defaults, settling, the posted body, the equivalent command and wizard links.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const m = await spa('wizard-model.js');

const form = {
  id: 'new-project',
  title: 'New project',
  rev: '0123456789abcdef',
  steps: [
    {
      id: 'project',
      title: 'Project',
      fields: [
        { key: 'lang', label: 'Language', kind: 'choice', options: [{ id: 'java', label: 'Java' }, 'kotlin'], default: 'java', required: false },
        { key: 'template', label: 'Template', kind: 'search', source: '/api/templates?lang={lang}', default: 'app', required: false },
        { key: 'name', label: 'Name', kind: 'text', pattern: '[a-z][a-z0-9-]*', required: true },
        { key: 'group', label: 'Group', kind: 'text', from: 'git-email', required: false },
        { key: 'dir', label: 'Directory', kind: 'path', from: 'parent-dir', required: false },
        { key: 'layout', label: 'Layout', kind: 'choice', options: ['traditional', 'simple'], default: 'traditional', required: false },
      ],
    },
    {
      id: 'template',
      title: 'webmvc',
      fields: [
        { key: 'database', label: 'Database', kind: 'choice', options: ['none', 'postgres'], default: 'postgres', required: false },
        { key: 'migrations', label: 'Migrations', kind: 'choice', options: ['flyway', 'liquibase'], default: 'flyway', required: false, when: "database != 'none'" },
        { key: 'native', label: 'Native', kind: 'bool', default: false, required: false },
        { key: 'libs', label: 'Libraries', kind: 'multi', options: ['guava', 'jackson', 'slf4j'], required: true },
        { key: 'frontend', label: 'Front end', kind: 'choice', options: ['none', 'angular'], default: 'none', required: false, advanced: true },
      ],
    },
  ],
};
const derived = { 'git-email': 'org.example', 'parent-dir': '/home/u/src' };

test('conditions parse and evaluate as the engine reads them', () => {
  const t = (src, values) => m.testCondition(m.parseCondition(src), values);
  assert.equal(t("database != 'none'", { database: 'postgres' }), true);
  assert.equal(t("database != 'none'", { database: 'none' }), false);
  assert.equal(t("a == 'x' && (b || !c)", { a: 'x', b: false, c: false }), true);
  assert.equal(t("libs in ['jackson', 'gson']", { libs: ['guava', 'jackson'] }), true);
  assert.equal(t("db in ['h2']", {}), false);
  assert.equal(t('missing == true', {}), false);
  assert.throws(() => m.parseCondition("a == 'x"), /unclosed/);
  assert.throws(() => m.parseCondition('a ~ b'), /not part of the language/);
  assert.throws(() => m.parseCondition(''), /empty/);
});

test('settling fills defaults, hides by when, and names what is missing or misfit', () => {
  const s = m.settle(form, { name: 'Widget', database: 'none' }, derived);
  assert.deepEqual(s.problems, [
    { key: 'name', message: "name must match [a-z][a-z0-9-]*; got 'Widget'" },
    { key: 'libs', message: 'libs needs a value' },
  ]);
  assert.equal(s.values.group, 'org.example');
  assert.equal(s.values.dir, '/home/u/src');
  assert.ok(s.hidden.has('migrations'));
  // a hidden field takes its default whatever was answered
  const h = m.settle(form, { name: 'w', libs: [], database: 'none', migrations: 'liquibase' }, derived);
  assert.equal(h.values.migrations, 'flyway');
  // a multi settles in option order
  assert.deepEqual(m.settle(form, { name: 'w', libs: ['slf4j', 'guava'] }, derived).values.libs, ['guava', 'slf4j']);
});

test('the posted body carries the rev and every shown field, hidden ones left out', () => {
  const body = m.postBody(form, { name: 'widget', database: 'none', libs: ['jackson'] }, derived);
  assert.equal(body.rev, form.rev);
  assert.deepEqual(body.answers, {
    lang: 'java',
    template: 'app',
    name: 'widget',
    group: 'org.example',
    dir: '/home/u/src',
    layout: 'traditional',
    database: 'none',
    native: false,
    libs: ['jackson'],
    frontend: 'none',
  });
});

test('the equivalent command spells only what differs from a literal default, derived ones always', () => {
  const answers = {
    template: 'java/spring-boot/webmvc',
    name: 'my-api',
    layout: 'simple',
    database: 'postgres',
    migrations: 'liquibase',
    native: true,
    libs: ['jackson', 'slf4j'],
    frontend: 'angular',
  };
  assert.equal(
    m.equivalentCommand(form, answers, derived),
    'jk new my-api -t java/spring-boot/webmvc --group org.example --dir /home/u/src -L simple'
      + ' -p migrations=liquibase -p native=true -p libs=jackson,slf4j -p frontend=angular',
  );
  const quoted = m.equivalentCommand(form, { name: 'a', dir: "/home/u/my src's", libs: ['guava'] }, derived);
  assert.match(quoted, / --dir '\/home\/u\/my src'\\''s' /);
});

test('the wizard has jk step, then the template step with advanced fields folded, then review', () => {
  const steps = m.wizardSteps(form);
  assert.deepEqual(steps.map((s) => s.id), ['project', 'template', 'review']);
  assert.equal(steps[1].title, 'webmvc');
  const shown = m.shownFields(steps[1].fields, { database: 'none' });
  assert.deepEqual(shown.plain.map((f) => f.key), ['database', 'native', 'libs']);
  assert.deepEqual(shown.advanced.map((f) => f.key), ['frontend']);
  // a template that asks nothing still has a second step, empty
  assert.deepEqual(m.wizardSteps({ steps: [form.steps[0]] })[1].fields, []);
});

test('a wizard link round-trips its answers and elicitation, never the token', () => {
  const hash = m.wizardHash({ name: 'widget', dir: '/home/u/my src', libs: ['guava', 'jackson'] }, 'abc');
  assert.equal(hash, '#new?name=widget&dir=%2Fhome%2Fu%2Fmy%20src&libs=guava%2Cjackson&elicitation=abc');
  const link = m.linkFromHash(hash + '&t=secret');
  assert.deepEqual(link, { answers: { name: 'widget', dir: '/home/u/my src', libs: 'guava,jackson' }, elicitation: 'abc' });
  // typed for the form: a multi splits, a bool reads yes/no, an unknown key or a misfit is dropped
  assert.deepEqual(m.typedAnswers(form, { libs: 'guava,jackson', native: 'yes', colour: 'red', database: 'oracle' }), {
    native: true,
    libs: ['guava', 'jackson'],
  });
  assert.equal(m.wizardHash({}), '#new');
});

test('the template search source fills its placeholders and the preview tree lists dirs first', () => {
  assert.equal(m.sourceFor(form.steps[0].fields[1], { lang: 'kotlin' }), '/api/templates?lang=kotlin');
  assert.deepEqual(
    m.treeRows(['jk.toml', 'src/main/java/App.java', 'README.md', 'src/test/java/AppTest.java']).map(
      (r) => '  '.repeat(r.depth) + r.name + (r.dir ? '/' : ''),
    ),
    ['src/', '  main/', '    java/', '      App.java', '  test/', '    java/', '      AppTest.java', 'jk.toml', 'README.md'],
  );
});

test("a create's agent wiring is kept for its project's page, and nothing when it wired nothing", () => {
  const agents = { files: ['AGENTS.md', '.mcp.json'], notes: ['Trust this folder'] };
  assert.deepEqual(m.agentNotes({ path: '/p', projectId: 'id-1', agents }), {
    projectId: 'id-1',
    files: ['AGENTS.md', '.mcp.json'],
    notes: ['Trust this folder'],
    callEnded: false,
  });
  assert.equal(m.agentNotes({ path: '/p', projectId: 'id-1', agents, handedOff: true }).callEnded, false);
  assert.equal(m.agentNotes({ path: '/p', projectId: 'id-1', agents: { files: [], notes: [] } }), null);
  assert.equal(m.agentNotes({ path: '/p', projectId: 'id-1' }), null);
  assert.equal(m.agentNotes({ path: '/p', agents }), null);
});

test('a create the agent had stopped waiting for says so, even with no agent files', () => {
  assert.deepEqual(m.agentNotes({ path: '/p', projectId: 'id-1', handedOff: false }), {
    projectId: 'id-1',
    files: [],
    notes: [],
    callEnded: true,
  });
});
