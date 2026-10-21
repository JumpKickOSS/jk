// SPDX-License-Identifier: Apache-2.0
// The code graph's JavaScript/TypeScript reader, run by the jk-graph worker on the workspace's Node.js.
// argv: <dir holding node_modules/oxc-parser and oxc-resolver> <workspace root> <file list> <output>
// Each listed file (repo-relative) yields one JSON line of facts; the worker turns them into graph nodes.
'use strict';
const fs = require('fs');
const path = require('path');
const nodeModules = path.join(process.argv[2], 'node_modules');
const { parseSync } = require(path.join(nodeModules, 'oxc-parser'));
const { ResolverFactory } = require(path.join(nodeModules, 'oxc-resolver'));
const root = process.argv[3];
const files = fs.readFileSync(process.argv[4], 'utf8').split('\n').filter(Boolean);
const out = fs.openSync(process.argv[5], 'w');

const EXTENSIONS = ['.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs', '.json', '.vue'];
const resolvers = new Map();
function resolverFor(dir) {
  let d = dir;
  while (d.startsWith(root)) {
    const tsconfig = ['tsconfig.json', 'jsconfig.json'].map(n => path.join(d, n)).find(f => fs.existsSync(f));
    if (tsconfig) return cached(tsconfig);
    const up = path.dirname(d);
    if (up === d) break;
    d = up;
  }
  return cached(null);
}
function cached(tsconfig) {
  const key = tsconfig || '';
  if (!resolvers.has(key)) {
    const options = { extensions: EXTENSIONS, conditionNames: ['import', 'require', 'browser', 'default'], mainFields: ['module', 'main'] };
    if (tsconfig) options.tsconfig = { configFile: tsconfig, references: 'auto' };
    resolvers.set(key, new ResolverFactory(options));
  }
  return resolvers.get(key);
}
function resolve(fromDir, spec) {
  try {
    const r = resolverFor(fromDir).sync(fromDir, spec);
    if (!r.path) return null;
    const rel = path.relative(root, r.path).split(path.sep).join('/');
    if (rel.startsWith('..') || rel.split('/').includes('node_modules')) return null;
    return rel;
  } catch (e) {
    return null;
  }
}
function lang(file) {
  if (file.endsWith('.tsx')) return 'tsx';
  if (/\.[mc]?ts$/.test(file)) return 'ts';
  return 'jsx';
}

const NOTE = /^(NOTE|IMPORTANT|HACK|WHY|RATIONALE):/;

function facts(rel) {
  const abs = path.join(root, rel);
  const src = fs.readFileSync(abs, 'utf8');
  const starts = [0];
  for (let i = 0; i < src.length; i++) if (src.charCodeAt(i) === 10) starts.push(i + 1);
  const line = n => {
    let lo = 0, hi = starts.length - 1;
    const p = n.start;
    while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (starts[mid] <= p) lo = mid; else hi = mid - 1; }
    return lo + 1;
  };
  const r = parseSync(rel, src, { lang: lang(rel), sourceType: 'unambiguous' });
  const dir = path.dirname(abs);
  const f = { file: rel, errors: r.errors.length, decls: [], imports: [], calls: [], uses: [], notes: [] };
  const declare = (name, kind, n, parent) => { f.decls.push({ name, kind, line: line(n), parent }); return f.decls.length - 1; };
  const imported = (spec, n, kind, names) => {
    const resolved = resolve(dir, spec);
    f.imports.push({ spec, resolved, line: line(n), kind, names });
  };
  const nameOf = k => k && (k.type === 'Identifier' || k.type === 'PrivateIdentifier' ? k.name : k.type === 'Literal' ? String(k.value) : null);

  function decorators(node, owner) {
    for (const d of node.decorators || []) {
      const call = d.expression;
      if (!call || call.type !== 'CallExpression' || nameOf(call.callee) !== 'Component') continue;
      const arg = call.arguments[0];
      if (!arg || arg.type !== 'ObjectExpression') continue;
      for (const p of arg.properties) {
        const key = nameOf(p.key);
        const strings = v => v.type === 'Literal' && typeof v.value === 'string' ? [v.value] : v.type === 'ArrayExpression' ? v.elements.flatMap(e => e ? strings(e) : []) : [];
        if (key === 'templateUrl' || key === 'styleUrl' || key === 'styleUrls') {
          for (const s of strings(p.value)) f.uses.push({ from: owner, kind: 'file', name: s, line: line(p) });
        } else if (key === 'selector') {
          for (const s of strings(p.value)) f.decls[owner].selector = s;
        }
      }
    }
  }

  function walk(node, owner) {
    if (!node || typeof node.type !== 'string') return;
    switch (node.type) {
      case 'ImportDeclaration':
        imported(node.source.value, node, 'static', (node.specifiers || []).map(s => ({
          local: s.local.name,
          imported: s.type === 'ImportSpecifier' ? nameOf(s.imported) : s.type === 'ImportDefaultSpecifier' ? 'default' : '*',
        })));
        return;
      case 'ExportAllDeclaration':
        imported(node.source.value, node, 'reexport', []);
        return;
      case 'ExportNamedDeclaration':
        if (node.source) imported(node.source.value, node, 'reexport', []);
        break;
      case 'ImportExpression':
        if (node.source && node.source.type === 'Literal') imported(String(node.source.value), node, 'dynamic', []);
        break;
      case 'ClassDeclaration':
      case 'ClassExpression':
        if (node.id && owner < 0) {
          const idx = declare(node.id.name, 'class', node, -1);
          decorators(node, idx);
          for (const m of node.body.body) {
            const mname = nameOf(m.key);
            if ((m.type === 'MethodDefinition' || m.type === 'TSAbstractMethodDefinition') && mname && mname !== 'constructor') {
              const midx = declare(mname, 'method', m, idx);
              walk(m.value, midx);
            } else {
              walk(m.value, idx);
            }
          }
          return;
        }
        break;
      case 'FunctionDeclaration':
        if (node.id && owner < 0) {
          const idx = declare(node.id.name, 'function', node, -1);
          walk(node.body, idx);
          return;
        }
        break;
      case 'TSInterfaceDeclaration':
        if (owner < 0) declare(node.id.name, 'interface', node, -1);
        return;
      case 'TSTypeAliasDeclaration':
        if (owner < 0) declare(node.id.name, 'type', node, -1);
        return;
      case 'TSEnumDeclaration':
        if (owner < 0) declare(node.id.name, 'enum', node, -1);
        return;
      case 'VariableDeclarator':
        if (owner < 0 && node.id && node.id.type === 'Identifier' && node.init
            && (node.init.type === 'ArrowFunctionExpression' || node.init.type === 'FunctionExpression')) {
          const idx = declare(node.id.name, 'function', node, -1);
          walk(node.init.body, idx);
          return;
        }
        break;
      case 'CallExpression': {
        const c = node.callee;
        if (c.type === 'Identifier' && c.name === 'require' && node.arguments[0] && node.arguments[0].type === 'Literal') {
          imported(String(node.arguments[0].value), node, 'require', []);
        } else if (c.type === 'Identifier') {
          f.calls.push({ from: owner, callee: c.name, object: null, line: line(node) });
        } else if (c.type === 'MemberExpression' && !c.computed && c.object.type === 'Identifier') {
          f.calls.push({ from: owner, callee: nameOf(c.property), object: c.object.name, line: line(node) });
        }
        break;
      }
      case 'JSXOpeningElement': {
        const n = node.name;
        if (n.type === 'JSXIdentifier' && /^[A-Z]/.test(n.name)) f.uses.push({ from: owner, kind: 'component', name: n.name, line: line(node) });
        break;
      }
    }
    for (const key in node) {
      if (key === 'parent' || key === 'start' || key === 'end') continue;
      const v = node[key];
      if (Array.isArray(v)) { for (const c of v) if (c && typeof c === 'object') walk(c, owner); }
      else if (v && typeof v === 'object' && typeof v.type === 'string') walk(v, owner);
    }
  }

  walk(r.program, -1);
  for (const c of r.comments || []) {
    const text = c.value.replace(/^[\s*]+/, '').trim();
    if (NOTE.test(text)) f.notes.push({ text: text.split('\n')[0], line: line(c) });
  }
  return f;
}

for (const rel of files) {
  let line;
  try {
    line = JSON.stringify(facts(rel));
  } catch (e) {
    line = JSON.stringify({ file: rel, failed: String(e && e.message || e) });
  }
  fs.writeSync(out, line + '\n');
}
fs.closeSync(out);
