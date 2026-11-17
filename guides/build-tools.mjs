#!/usr/bin/env node
/**
 * The build helpers of the guides; build.sh runs them. Plain Node, no dependencies.
 *
 *   node build-tools.mjs subsites
 *       The sub-sites: one line "<folder>/<master>" per master document. A sub-site is a folder
 *       of guides/ (not one starting with _ or ., and not images, stylesheets or manifest) and a
 *       master is an .adoc file at the folder's top level with a document title, a line "= Title".
 *       The chapters a master includes start at "==" and are not masters.
 *
 *   node build-tools.mjs kit-prepare
 *       Stage 1 of the country kit's guides: picks the kit's Markdown guides that may be published,
 *       rewrites their links for the site, turns each Mermaid diagram into a Graphviz file, writes
 *       the set's index page, and writes _kit/convert.sh, which build.sh runs in the pinned image
 *       (kramdoc, then dot). Prints what it left out and why.
 *
 *   node build-tools.mjs kit-finish <site folder>
 *       Stage 2: turns kramdoc's AsciiDoc into the pages Asciidoctor renders, under
 *       _kit/country-kit/: GitHub's heading ids, so the guides' own #links keep working, the
 *       diagrams (a picture, or the Mermaid source when it could not be drawn), the page header.
 *       Copies the diagrams' SVG files into the site.
 *
 *   node build-tools.mjs landing <site folder>
 *       The built landing page: an element with data-subsite="<folder>" stays only when the build
 *       produced that sub-site (a master, or an index.html); "@master" in its links becomes the
 *       folder's master document. So the landing page lists a guide from the build that first
 *       holds it, and never links a page that is not there.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const STAGE = path.join(HERE, '_kit');
const KIT_OUT = 'country-kit'; // the set's folder in the site and in the stage

const NOT_SUBSITES = new Set(['images', 'stylesheets', 'manifest', 'node_modules']);

/**
 * The kit's guides that may go on the site, as the kit package lays them out (kit-include.json:
 * the README, API-CHANGES.md, docs/**, templates/**, and docs/country-api.md, which the kit's
 * build copies from be/docs/). templates/AGENTS.md is the one template the guides' reading order
 * names. The order is the order of the set's own pages.
 */
function kitCandidates() {
  const docs = fs
    .readdirSync(path.join(REPO, 'country-kit', 'docs'))
    .filter((name) => name.endsWith('.md'))
    .sort((a, b) => (a === 'README.md' ? -1 : b === 'README.md' ? 1 : a.localeCompare(b)));
  return [
    ...docs.map((name) => ({ kit: `docs/${name}`, from: `country-kit/docs/${name}` })),
    { kit: 'docs/country-api.md', from: 'be/docs/country-api.md' },
    { kit: 'README.md', from: 'country-kit/README.md' },
    { kit: 'API-CHANGES.md', from: 'country-kit/API-CHANGES.md' },
    { kit: 'templates/AGENTS.md', from: 'country-kit/templates/AGENTS.md' },
  ].filter((c) => fs.existsSync(path.join(REPO, c.from)));
}

/** The guides leave SECOM and S-124 out: a kit guide that names either stays off the site. */
const RULED_OUT = /secom|\bs-?124(?!\d)/i;

const SITE_ROOT_URL = /^https?:\/\/docs\.niord\.org(\/[^\s#?]*)?(#\S*)?$/i;

// ---------------------------------------------------------------------------------------------
// Sub-sites
// ---------------------------------------------------------------------------------------------

export function subsites(dir = HERE) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory() || /^[_.]/.test(entry.name) || NOT_SUBSITES.has(entry.name)) continue;
    const folder = path.join(dir, entry.name);
    const masters = fs
      .readdirSync(folder)
      .filter((name) => name.endsWith('.adoc'))
      .filter((name) => /^= \S/m.test(fs.readFileSync(path.join(folder, name), 'utf8')))
      .sort();
    for (const master of masters) out.push(`${entry.name}/${master.replace(/\.adoc$/, '')}`);
  }
  return out.sort();
}

// ---------------------------------------------------------------------------------------------
// Markdown helpers
// ---------------------------------------------------------------------------------------------

const FENCE = /^(\s*)(`{3,}|~{3,})\s*([\w+-]*)/;

/** Splits Markdown into parts: { code: true, lang, lines } for fenced blocks, else { lines }. */
function splitFences(text) {
  const parts = [];
  let current = { code: false, lines: [] };
  let fence = null;
  for (const line of text.split('\n')) {
    if (fence === null) {
      const m = FENCE.exec(line);
      if (m) {
        if (current.lines.length) parts.push(current);
        fence = { char: m[2][0], length: m[2].length };
        current = { code: true, lang: m[3].toLowerCase(), indent: m[1], open: line, lines: [] };
        continue;
      }
      current.lines.push(line);
    } else {
      const m = /^\s*(`{3,}|~{3,})\s*$/.exec(line);
      if (m && m[1][0] === fence.char && m[1].length >= fence.length) {
        current.close = line;
        parts.push(current);
        current = { code: false, lines: [] };
        fence = null;
        continue;
      }
      current.lines.push(line);
    }
  }
  if (current.lines.length || current.code) parts.push(current);
  return parts;
}

function joinParts(parts) {
  return parts
    .map((p) => (p.code ? [p.open, ...p.lines, p.close ?? ''].join('\n') : p.lines.join('\n')))
    .join('\n');
}

/** GitHub's heading id: the heading's text, lower case, punctuation dropped, spaces as hyphens. */
function githubSlug(heading) {
  const text = heading
    .replace(/!?\[((?:[^\[\]]|\[[^\]]*\])*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/`/g, '')
    .replace(/\*\*|__|\*/g, '')
    .trim()
    .toLowerCase();
  return text.replace(/[^\p{L}\p{M}\p{N}\p{Pc}\- ]/gu, '').replace(/ /g, '-');
}

/** The ATX headings of a Markdown text, outside fenced code, with their GitHub ids in order. */
function headings(parts) {
  const seen = new Map();
  const out = [];
  for (const part of parts) {
    if (part.code) continue;
    for (const line of part.lines) {
      const m = /^ {0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$/.exec(line);
      if (!m) continue;
      const base = githubSlug(m[2]);
      const n = seen.get(base) ?? 0;
      seen.set(base, n + 1);
      out.push({ level: m[1].length, text: m[2], id: n === 0 ? base : `${base}-${n}` });
    }
  }
  return out;
}

const posix = path.posix;

/** The site path of a kit page: docs/x.md -> docs/x.html. */
const htmlOf = (kitPath) => kitPath.replace(/\.md$/i, '.html');

/**
 * A link target of a kit page, made right for the site: a link to another published kit page
 * points at its HTML; a link to docs.niord.org becomes relative, so the link check covers it; a
 * link to anything else of the kit (a script, a schema, a guide left out) is no link, and its
 * text stays. Returns the new target, null for "no link", or { kitOnly: <kit path> } for a link to
 * one of the kit's guides that the site leaves out, so the text can name the guide's file in the
 * kit package.
 */
function siteTarget(target, fromKit, outPage, published, leftOut = new Set()) {
  const root = SITE_ROOT_URL.exec(target);
  const up = posix.relative(posix.dirname(posix.join(KIT_OUT, outPage)), '.') || '.';
  if (root) {
    const p = root[1] && root[1] !== '/' ? root[1].replace(/^\//, '') : 'index.html';
    return `${up}/${p}${root[2] ?? ''}`;
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('//')) return target;
  if (target.startsWith('#')) return target;
  const hash = target.indexOf('#');
  const rawPath = hash < 0 ? target : target.slice(0, hash);
  const fragment = hash < 0 ? '' : target.slice(hash);
  let kitPath = posix.normalize(posix.join(posix.dirname(fromKit), decodeURI(rawPath)));
  if (kitPath.endsWith('/') || rawPath.endsWith('/')) kitPath = posix.join(kitPath, 'README.md');
  if (leftOut.has(kitPath)) return { kitOnly: kitPath };
  if (!published.has(kitPath)) return null;
  const rel = posix.relative(posix.dirname(outPage), htmlOf(kitPath));
  return `${rel}${fragment}`;
}

/**
 * Rewrites the inline links of a Markdown line (code spans left alone). rewrite(target) returns
 * the new target, null to keep only the text, or { kitOnly } to keep the text and name the guide's
 * file in the kit package ("the kit package's `docs/x.md`").
 */
function rewriteLinks(line, rewrite) {
  const spans = [];
  const guarded = line.replace(/(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)/g, (m) => {
    spans.push(m);
    return `\u0000${spans.length - 1}\u0000`;
  });
  const done = guarded.replace(
    /(!?)\[((?:[^\[\]]|\[[^\]]*\])*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g,
    (whole, bang, text, target, offset, all) => {
      if (bang) return whole;
      const restored = text.replace(/\u0000(\d+)\u0000/g, (_, i) => spans[Number(i)]);
      const next = rewrite(target);
      if (next === null || typeof next === 'object') {
        const plain = restored.trim();
        const isName = !plain.includes('`') && /^[\w./<>@-]+(\.[a-z]{1,5}|\/)$/i.test(plain);
        if (next === null) return isName ? `\`${plain}\`` : text;
        const sentenceStart = /[.!?]\s+$/.test(all.slice(0, offset));
        return isName
          ? `${sentenceStart ? 'The' : 'the'} kit package's \`${next.kitOnly}\``
          : `${text} in the kit package's \`${next.kitOnly}\``;
      }
      return `[${text}](${next})`;
    },
  );
  return done.replace(/\u0000(\d+)\u0000/g, (_, i) => spans[Number(i)]);
}

// ---------------------------------------------------------------------------------------------
// Mermaid flowcharts as Graphviz
// ---------------------------------------------------------------------------------------------

const decodeMermaid = (s) =>
  s
    .replace(/#lt;/g, '<')
    .replace(/#gt;/g, '>')
    .replace(/#quot;/g, '"')
    .replace(/#amp;/g, '&')
    .replace(/#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/<br\s*\/?>/gi, '\n');

const SHAPES = { '[': [']', 'box'], '{': ['}', 'diamond'], '(': [')', 'round'] };

function parseNode(line, start) {
  let i = start;
  while (line[i] === ' ') i++;
  const id = /^[A-Za-z0-9_]+/.exec(line.slice(i));
  if (!id) return null;
  i += id[0].length;
  const shape = SHAPES[line[i]];
  if (!shape) return { id: id[0], end: i };
  const [close, kind] = shape;
  i++;
  if ('[({/\\>'.includes(line[i])) return null; // stadiums, subroutines and the like: not drawn
  let label;
  if (line[i] === '"') {
    const q = line.indexOf('"', i + 1);
    if (q < 0) return null;
    label = line.slice(i + 1, q);
    i = q + 1;
    while (line[i] === ' ') i++;
    if (line[i] !== close) return null;
  } else {
    const c = line.indexOf(close, i);
    if (c < 0) return null;
    label = line.slice(i, c).trim();
    i = c;
  }
  return { id: id[0], label: decodeMermaid(label), shape: kind, end: i + 1 };
}

const EDGES = [
  [/-->\|"?([^|"]*)"?\|/y, 'solid', true],
  [/-\.->\|"?([^|"]*)"?\|/y, 'dashed', true],
  [/==>\|"?([^|"]*)"?\|/y, 'bold', true],
  [/--\s+"?([^"]+?)"?\s+-->/y, 'solid', true],
  [/-\.\s+"?([^"]+?)"?\s+\.->/y, 'dashed', true],
  [/==\s+"?([^"]+?)"?\s+==>/y, 'bold', true],
  [/-->/y, 'solid', true],
  [/-\.->/y, 'dashed', true],
  [/==>/y, 'bold', true],
  [/---/y, 'solid', false],
  [/-\.-/y, 'dashed', false],
  [/~~~/y, 'invis', false],
];

function parseEdge(line, start) {
  let i = start;
  while (line[i] === ' ') i++;
  for (const [re, style, arrow] of EDGES) {
    re.lastIndex = i;
    const m = re.exec(line);
    if (m) return { style, arrow, label: m[1] ? decodeMermaid(m[1].trim()) : '', end: re.lastIndex };
  }
  return null;
}

/** A Mermaid flowchart as { nodes, edges, clusters, direction }, or null when it uses more. */
export function parseFlowchart(source) {
  const lines = source
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('%%'));
  const head = /^(?:flowchart|graph)\s+(TD|TB|BT|LR|RL)\s*;?$/.exec(lines.shift() ?? '');
  if (!head) return null;
  const nodes = new Map();
  const edges = [];
  const clusters = [];
  let cluster = null;
  const register = (n) => {
    const known = nodes.get(n.id) ?? { id: n.id, label: n.id, shape: 'box', cluster: null };
    if (n.label !== undefined) Object.assign(known, { label: n.label, shape: n.shape });
    if (!nodes.has(n.id)) {
      known.cluster = cluster;
      if (cluster) cluster.nodes.push(n.id);
      nodes.set(n.id, known);
    }
  };
  for (const line of lines) {
    const sub = /^subgraph\s+([A-Za-z0-9_]+)\s*(?:\["([^"]*)"\]|\[([^\]]*)\])?$/.exec(line);
    if (sub) {
      if (cluster) return null;
      cluster = { id: sub[1], label: decodeMermaid(sub[2] ?? sub[3] ?? sub[1]), nodes: [] };
      clusters.push(cluster);
      continue;
    }
    if (line === 'end') {
      if (!cluster) return null;
      cluster = null;
      continue;
    }
    if (/^direction\s/.test(line)) continue;
    const first = parseNode(line, 0);
    if (!first) return null;
    register(first);
    let prev = first.id;
    let i = first.end;
    for (;;) {
      while (line[i] === ' ' || line[i] === ';') i++;
      if (i >= line.length) break;
      const edge = parseEdge(line, i);
      if (!edge) return null;
      const node = parseNode(line, edge.end);
      if (!node) return null;
      register(node);
      edges.push({ from: prev, to: node.id, ...edge });
      prev = node.id;
      i = node.end;
    }
  }
  if (cluster) return null;
  return { direction: head[1] === 'TD' ? 'TB' : head[1], nodes, edges, clusters };
}

function wrap(text, width = 30) {
  return text
    .split('\n')
    .map((para) => {
      const out = [];
      let line = '';
      for (const word of para.split(/\s+/).filter(Boolean)) {
        if (line && line.length + 1 + word.length > width) {
          out.push(line);
          line = word;
        } else line = line ? `${line} ${word}` : word;
      }
      if (line) out.push(line);
      return out.join('\n');
    })
    .join('\n');
}

const dotString = (s) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`;

export function flowchartToDot(chart) {
  const out = [
    'digraph diagram {',
    `  graph [rankdir=${chart.direction}, bgcolor="transparent", nodesep=0.3, ranksep=0.45, pad=0.15, fontname="Helvetica", fontsize=11, fontcolor="#1d2b3a"];`,
    '  node [shape=box, style="rounded,filled", fillcolor="#eef3f8", color="#3d5a78", fontcolor="#1d2b3a", fontname="Helvetica", fontsize=11, margin="0.14,0.07"];',
    '  edge [color="#3d5a78", fontcolor="#33475b", fontname="Helvetica", fontsize=10, arrowsize=0.7];',
  ];
  const nodeLine = (n) => {
    const attrs = [`label=${dotString(wrap(n.label, n.shape === 'diamond' ? 22 : 30))}`];
    if (n.shape === 'diamond') attrs.push('shape=diamond', 'style=filled', 'fillcolor="#fff6dd"');
    return `n_${n.id} [${attrs.join(', ')}];`;
  };
  for (const n of chart.nodes.values()) if (!n.cluster) out.push(`  ${nodeLine(n)}`);
  for (const c of chart.clusters) {
    out.push(`  subgraph cluster_${c.id} {`);
    out.push(`    label=${dotString(wrap(c.label, 50))}; style="dashed,rounded"; color="#8a9bad";`);
    for (const id of c.nodes) out.push(`    ${nodeLine(chart.nodes.get(id))}`);
    out.push('  }');
  }
  for (const e of chart.edges) {
    const attrs = [];
    if (e.label) attrs.push(`label=${dotString(wrap(e.label, 24))}`);
    if (e.style !== 'solid') attrs.push(`style=${e.style}`);
    if (!e.arrow) attrs.push('arrowhead=none');
    out.push(`  n_${e.from} -> n_${e.to}${attrs.length ? ` [${attrs.join(', ')}]` : ''};`);
  }
  out.push('}');
  return `${out.join('\n')}\n`;
}

/** The diagram in words, for its alternative text. */
export function flowchartAlt(chart) {
  const name = (id) => chart.nodes.get(id).label.replace(/\s+/g, ' ');
  const parts = [];
  const linked = new Set();
  for (const e of chart.edges) {
    if (e.style === 'invis') continue;
    linked.add(e.from).add(e.to);
    parts.push(`${name(e.from)} → ${name(e.to)}${e.label ? ` (${e.label.replace(/\s+/g, ' ')})` : ''}`);
  }
  for (const c of chart.clusters) {
    parts.push(`${c.label}: ${c.nodes.map(name).join('; ')}`);
    c.nodes.forEach((id) => linked.add(id));
  }
  for (const n of chart.nodes.values()) if (!linked.has(n.id)) parts.push(name(n.id));
  return `Diagram. ${parts.join('. ')}.`;
}

// ---------------------------------------------------------------------------------------------
// kit-prepare
// ---------------------------------------------------------------------------------------------

function kitPrepare() {
  fs.rmSync(STAGE, { recursive: true, force: true });
  const candidates = kitCandidates().map((c) => ({
    ...c,
    text: fs.readFileSync(path.join(REPO, c.from), 'utf8').replace(/^﻿/, '').replace(/\r\n/g, '\n'),
  }));
  for (const c of candidates) {
    c.title = /^#\s+(.+?)\s*#*\s*$/m.exec(c.text)?.[1] ?? posix.basename(c.kit, '.md');
    const hit = RULED_OUT.exec(c.text);
    c.published = !hit;
    if (hit) {
      const line = c.text.slice(0, hit.index).split('\n').length;
      console.log(`kit: left out ${c.kit} (${c.from}:${line} names "${hit[0]}", which the guides leave out)`);
    }
  }
  const published = new Set(candidates.filter((c) => c.published).map((c) => c.kit));
  const leftOut = new Set(candidates.filter((c) => !c.published).map((c) => c.kit));
  const pages = [];
  for (const c of candidates.filter((x) => x.published)) {
    pages.push(preparePage(c.kit, c.text, c.kit, published, leftOut, { kind: 'guide', from: c.from }));
  }
  // The index names each left-out guide itself (indexMarkdown), so its links get no second note.
  pages.push(preparePage('index.md', indexMarkdown(candidates, published), 'index.md', published, new Set(), { kind: 'index' }));

  const script = ['set -e', 'cd "$(dirname "$0")"'];
  for (const p of pages) {
    script.push(`mkdir -p 'kramdoc/${posix.dirname(p.page)}'`);
    script.push(`kramdoc --wrap=preserve -o 'kramdoc/${p.page.replace(/\.md$/, '.adoc')}' '${KIT_OUT}/${p.page}'`);
    for (const d of p.diagrams.filter((x) => x.dot)) {
      script.push(`dot -Tsvg '${KIT_OUT}/${d.dot}' -o '${KIT_OUT}/${d.svg}' || echo 'dot failed on ${d.dot}' >&2`);
    }
  }
  fs.writeFileSync(path.join(STAGE, 'convert.sh'), `${script.join('\n')}\n`);
  fs.writeFileSync(path.join(STAGE, 'pages.json'), `${JSON.stringify(pages, null, 2)}\n`);
  console.log(`kit: ${pages.length - 1} of ${candidates.length} guides go on the site, with the set's index`);
}

/** Writes one page's Markdown into the stage and returns what kit-finish needs. */
function preparePage(page, text, fromKit, published, leftOut, extra) {
  const parts = splitFences(text);
  const diagrams = [];
  const base = posix.basename(page, '.md');
  for (const part of parts) {
    if (part.code) {
      if (part.lang !== 'mermaid') continue;
      const n = diagrams.length + 1;
      const source = part.lines.join('\n');
      const chart = parseFlowchart(source);
      const d = { n, marker: `KITDIAGRAM${n}X`, source, indent: part.indent };
      if (chart) {
        d.dot = posix.join(posix.dirname(page), `${base}-diagram-${n}.dot`);
        d.svg = d.dot.replace(/\.dot$/, '.svg');
        d.alt = flowchartAlt(chart);
        fs.mkdirSync(path.join(STAGE, KIT_OUT, posix.dirname(d.dot)), { recursive: true });
        fs.writeFileSync(path.join(STAGE, KIT_OUT, d.dot), flowchartToDot(chart));
      } else {
        console.log(`kit: ${fromKit}: diagram ${n} uses Mermaid this build cannot draw; shown as its source`);
      }
      diagrams.push(d);
      Object.assign(part, { code: false, lines: ['', `${part.indent}${d.marker}`, ''] });
      continue;
    }
    part.lines = part.lines.map((line) =>
      rewriteLinks(line, (target) => siteTarget(target, fromKit, page, published, leftOut)),
    );
  }
  const ids = headings(parts).map((h) => ({ level: h.level, id: h.id }));
  const out = path.join(STAGE, KIT_OUT, page);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, joinParts(parts));
  return { page, ids, diagrams, ...extra };
}

/**
 * The set's index page, in Markdown: what the kit is, then the reading order of the kit's own
 * docs/README.md ("Who reads what"), each guide linked when it is on the site and named as a file
 * of the kit package when it is not; then the references.
 */
function indexMarkdown(candidates, published) {
  const byKit = new Map(candidates.map((c) => [c.kit, c]));
  const readme = byKit.get('docs/README.md');
  // The index's links name kit paths (docs/x.md), which preparePage turns into the site's.
  const entry = (kit) => {
    const c = byKit.get(kit);
    const title = c?.title ?? posix.basename(kit, '.md');
    if (published.has(kit)) return `[${title}](${kit})`;
    return `*${title}* (\`${kit}\`, in the kit package only)`;
  };
  const fromDocs = (target) => {
    if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('//')) return target;
    if (target.startsWith('#')) return `docs/README.md${target}`;
    const hash = target.indexOf('#');
    const p = hash < 0 ? target : target.slice(0, hash);
    return posix.normalize(posix.join('docs', p)) + (hash < 0 ? '' : target.slice(hash));
  };
  const rows = [];
  if (readme) {
    const section = /^## Who reads what\s*\n([\s\S]*?)(?=^## |(?![\s\S]))/m.exec(readme.text)?.[1] ?? '';
    for (const line of section.split('\n')) {
      const cells = /^\|(.*)\|\s*$/.exec(line.trim())?.[1].split(/(?<!\\)\|/).map((s) => s.trim());
      if (!cells || cells.length < 2 || /^-+$/.test(cells[0].replace(/[:\s]/g, '')) || cells[0] === 'Who') continue;
      const link = /^\[[^\]]*\]\(([^)#\s]+)[^)]*\):?\s*(.*)$/.exec(cells[1]);
      if (!link) {
        rows.push(`1. **${cells[0]}:** ${cells[1]}`);
        continue;
      }
      const kit = posix.normalize(posix.join('docs', link[1]));
      const rest = rewriteLinks(link[2], fromDocs);
      rows.push(`1. **${cells[0]}:** ${entry(kit)}: ${rest}`);
    }
  }
  const refs = ['README.md', 'API-CHANGES.md', 'docs/country-api.md']
    .filter((k) => byKit.has(k))
    .map((k) => `- ${entry(k)}`);
  const overview = published.has('docs/README.md')
    ? '[The country guides](docs/README.md)'
    : '*The country guides* (`docs/README.md`, in the kit package only)';
  return `# Country kit guides

Niord is one base product that every country runs, with a thin layer of its own on top. The
Danish and the Greenland deployments, for example, are each a country built on the base product:
a repository of their own that holds only what makes the deployment theirs, such as its
languages, look, waters and starting data.

The country kit is the set of tools and guides a country builds its deployment with. It is
published with every version of the base product as the package \`org.niord:niord-country-kit\`,
and after set-up a country reads the guides that match the version it builds on in
\`.niord/kit/docs/\` of its own repository.

The pages here are those guides, rendered from the kit as it stands in the source this site was
built from, which may be newer than the version a country builds on. Some of the kit's guides are
read in the kit package only; the list below names them, and a page that refers to one names it
as a file of the kit package.

Start with ${overview}: how the base product and a country fit together, what is mandatory and
what is optional, and who reads which guide.

## Reading order

${rows.join('\n')}

## Reference

${refs.join('\n')}
`;
}

// ---------------------------------------------------------------------------------------------
// kit-finish
// ---------------------------------------------------------------------------------------------

const DELIMITERS = /^(-{4,}|\.{4,}|={4,}|\*{4,}|_{4,}|\+{4,}|\/{4,}|\|={3,}|--)$/;

function kitFinish(site) {
  const pages = JSON.parse(fs.readFileSync(path.join(STAGE, 'pages.json'), 'utf8'));
  const written = [];
  for (const p of pages) {
    const adocName = p.page.replace(/\.md$/, '.adoc');
    let lines = fs
      .readFileSync(path.join(STAGE, 'kramdoc', adocName), 'utf8')
      .replace(/\r\n/g, '\n')
      .split('\n');

    // Headings: GitHub's ids, in order.
    const ids = [...p.ids];
    let open = null;
    const out = [];
    let titleSeen = false;
    for (const line of lines) {
      if (open !== null) {
        if (line === open) open = null;
        out.push(line);
        continue;
      }
      if (DELIMITERS.test(line)) {
        open = line;
        out.push(line);
        continue;
      }
      const h = /^(=+) \S/.exec(line);
      if (h) {
        const next = ids.shift();
        if (!next || next.level !== h[1].length) {
          throw new Error(`${p.page}: heading "${line}" does not match the Markdown's headings`);
        }
        if (h[1].length === 1 && !titleSeen) titleSeen = true;
        else {
          if (out.length && /^\[#[^\]]+\]$/.test(out[out.length - 1])) out.pop();
          out.push(`[#${next.id}]`);
        }
      }
      out.push(line);
    }
    if (ids.length) throw new Error(`${p.page}: ${ids.length} Markdown heading(s) not found in kramdoc's output`);
    lines = out;

    // Diagrams: a picture, or the Mermaid source.
    for (const d of p.diagrams) {
      const at = lines.findIndex((l) => l.trim() === d.marker);
      if (at < 0) throw new Error(`${p.page}: diagram ${d.n} lost in the conversion`);
      const svg = d.svg && path.join(STAGE, KIT_OUT, d.svg);
      let block;
      if (svg && fs.existsSync(svg) && fs.statSync(svg).size > 0) {
        const alt = d.alt.replace(/"/g, '\\"');
        block = ['[.kit-diagram]', `image::${posix.basename(d.svg)}[alt="${alt}"]`];
        const target = path.join(site, KIT_OUT, d.svg);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(svg, target);
      } else {
        block = ['.Diagram, as its Mermaid source', '[source,text]', '----', ...d.source.split('\n'), '----'];
      }
      lines.splice(at, 1, ...block);
    }

    // The header: the title, the page's attributes, then where it comes from.
    let t = lines.findIndex((l) => l.trim() !== '');
    if (t < 0 || !/^= \S/.test(lines[t])) {
      lines.splice(0, 0, `= ${posix.basename(p.page, '.md')}`, '');
      t = 0;
    }
    const attrs = [
      ':toc: left',
      ':toclevels: 3',
      ':sectanchors:',
      ':sectlinks:',
      ':idprefix:',
      ':idseparator: -',
      ':icons: font',
      ':source-highlighter: coderay',
      ':table-caption!:',
      ':figure-caption!:',
      ':example-caption!:',
    ];
    if (p.kind === 'index') attrs.splice(0, 2, ':toc!:');
    const home = posix.relative(posix.dirname(posix.join(KIT_OUT, p.page)), '.') || '.';
    const kitIndex = posix.relative(posix.dirname(p.page), 'index.html');
    const banner =
      p.kind === 'index'
        ? [`[.kit-source]`, `link:${home}/index.html[Niord documentation]`]
        : [
            '[.kit-source]',
            `link:${home}/index.html[Niord documentation] › link:${kitIndex}[Country kit guides] +`,
            `This guide ships in the country kit package as \`${p.page}\`. It is rendered here from the kit as it`,
            'stands in the source this site was built from; a country reads the copy of the version it builds on in',
            '`.niord/kit/`. A guide this page names only by its file in the kit package is not on this site: it is',
            'read in the kit package only.',
          ];
    lines.splice(t + 1, 0, ...attrs, '', ...banner, '');
    const target = path.join(STAGE, KIT_OUT, adocName);
    fs.writeFileSync(target, lines.join('\n'));
    written.push(posix.join('_kit', KIT_OUT, adocName));
  }
  console.log(written.join('\n'));
}

// ---------------------------------------------------------------------------------------------
// landing
// ---------------------------------------------------------------------------------------------

function landing(site) {
  const file = path.join(site, 'index.html');
  let html = fs.readFileSync(file, 'utf8');
  const masters = new Map();
  for (const doc of subsites()) {
    const [folder, master] = doc.split('/');
    if (!fs.existsSync(path.join(site, folder, `${master}.html`))) continue;
    if (!masters.has(folder)) masters.set(folder, master);
  }
  const opens = [...html.matchAll(/<([a-zA-Z][a-zA-Z0-9]*)\b[^>]*\bdata-subsite="([^"]+)"[^>]*>/g)];
  const kept = [];
  const dropped = [];
  for (const m of opens.reverse()) {
    const [tag, folder] = [m[1].toLowerCase(), m[2]];
    const start = m.index;
    const re = new RegExp(`<(/?)${tag}\\b[^>]*>`, 'gi');
    re.lastIndex = start;
    let depth = 0;
    let end = -1;
    for (let t = re.exec(html); t; t = re.exec(html)) {
      depth += t[1] ? -1 : 1;
      if (depth === 0) {
        end = t.index + t[0].length;
        break;
      }
    }
    if (end < 0) throw new Error(`landing: <${tag} data-subsite="${folder}"> is not closed`);
    const master = masters.get(folder) ?? (fs.existsSync(path.join(site, folder, 'index.html')) ? 'index' : null);
    if (master === null) {
      html = html.slice(0, start) + html.slice(end).replace(/^[ \t]*\r?\n/, '');
      dropped.push(folder);
    } else {
      html = html.slice(0, start) + html.slice(start, end).replaceAll('@master', master) + html.slice(end);
      kept.push(folder);
    }
  }
  fs.writeFileSync(file, html);
  console.log(
    `landing: ${kept.length} entries listed` +
      (dropped.length ? `; not built yet, left out: ${[...new Set(dropped)].sort().join(', ')}` : ''),
  );
}

// ---------------------------------------------------------------------------------------------
// preview
// ---------------------------------------------------------------------------------------------

// A site built with --preview is published in a folder of the documentation site (docs.niord.org/new)
// before it replaces the pages at the root: every page asks search engines not to list it or follow
// its links, and the folder carries no CNAME (the domain belongs to the site's root). Every link
// between the pages is relative, so the site works in any folder.
function preview(site) {
  const robots = '<meta name="robots" content="noindex, nofollow">';
  let pages = 0;
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(file);
      else if (entry.name.endsWith('.html')) {
        const html = fs.readFileSync(file, 'utf8');
        if (html.includes('name="robots"')) continue;
        const head = /<head[^>]*>/i.exec(html);
        if (!head) throw new Error(`preview: ${path.relative(site, file)} has no <head>`);
        const at = head.index + head[0].length;
        fs.writeFileSync(file, `${html.slice(0, at)}\n${robots}${html.slice(at)}`);
        pages += 1;
      }
    }
  };
  walk(site);
  fs.rmSync(path.join(site, 'CNAME'), { force: true });
  console.log(`preview: ${pages} pages marked noindex, CNAME left out`);
}

// ---------------------------------------------------------------------------------------------

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) === fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const [command, arg] = process.argv.slice(2);
  try {
    if (command === 'subsites') console.log(subsites().join('\n'));
    else if (command === 'kit-prepare') kitPrepare();
    else if (command === 'kit-finish' && arg) kitFinish(path.resolve(arg));
    else if (command === 'landing' && arg) landing(path.resolve(arg));
    else if (command === 'preview' && arg) preview(path.resolve(arg));
    else {
      console.error(
        'usage: node build-tools.mjs subsites | kit-prepare | kit-finish <site> | landing <site> | preview <site>',
      );
      process.exit(2);
    }
  } catch (error) {
    console.error(`build-tools ${command}: ${error.message}`);
    process.exit(1);
  }
}
