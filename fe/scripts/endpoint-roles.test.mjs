/**
 * The frontend half of the backend's endpoint table (`be/niord/src/test/resources/endpoints/`,
 * one file per controller group, the endpoint sweep's role table): every HTTP call the
 * administration app and the public site make is held against the entry of the handler it
 * reaches.
 *
 *   npm run test:guards      # from fe/
 *
 * - **Every call resolves** to one entry by verb and path, as Spring would route it (the entry's
 *   key or one of its `alsoAt` paths; a trailing slash is a path of its own). A call to a path no
 *   handler maps is a 404 waiting for a user (FILTER2 posted to `/rest/filters/filter/`).
 * - **Every query parameter** a call sends is one its handler reads (the entry's `params`, or `*`
 *   for a handler that reads the whole map), or is listed in `endpoint-roles/accepted-unread.json`
 *   with its backlog line.
 * - **Every ticket role** the frontend asks for a call (`openTicketed(url, role)`, the ticket
 *   service's `fetch(role)`) is one the entry's ticket cells accept (`ticket(<identity>:<role>)`
 *   answering `ok`).
 * - **Every public-site call** is allowed for anonymous without a domain header: the guard is
 *   `PermitAll`, and a reviewed entry's `anonymous` cell, where it has one, is `ok`.
 * - **Every item `role-offers.json` records as shown** to an identity is allowed, for that
 *   identity, by every endpoint the item calls. The spec beside the file
 *   (`ui-apps/admin/src/app/core/auth/endpoint-roles/endpoint-roles.spec.ts`) records which items
 *   each identity is shown, rendering the real `AuthFacade` over token claims built from the
 *   identities below; this guard holds those identities to the backend's test realm.
 * - **Every file that gates on a role** is rendered by that spec or listed in
 *   `role-offers.json`'s `notRendered` with its reason.
 *
 * The calls are extracted by `endpoint-roles/extract-calls.mjs`. A site whose address cannot be
 * followed is listed in `endpoint-roles/manual-calls.json` with the calls it makes (or none, and
 * why); an entry there or in `accepted-unread.json` that matches nothing fails, so the lists only
 * shrink. A call or an item that reaches an entry the backend has not decided (`reviewed: false`,
 * the generator's skeleton of a new handler) fails until the backend's table decides it.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

import { extractCalls, GATE_WORDS, sourceFiles } from './endpoint-roles/extract-calls.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TABLE_DIR = path.resolve(FE, '../be/niord/src/test/resources/endpoints');
const REALM = path.resolve(FE, '../be/niord/src/test/resources/keycloak/niord-realm.json');
const MANUAL = path.join(FE, 'scripts/endpoint-roles/manual-calls.json');
const ACCEPTED = path.join(FE, 'scripts/endpoint-roles/accepted-unread.json');
const OFFERS_DIR = 'ui-apps/admin/src/app/core/auth/endpoint-roles';
const OFFERS = path.join(FE, OFFERS_DIR, 'role-offers.json');

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));

// ---------------------------------------------------------------------------------------------
// The table

/** Every entry of every table file, with its file's group. */
export function loadTable(dir = TABLE_DIR) {
  const entries = [];
  for (const name of fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort()) {
    const file = readJson(path.join(dir, name));
    for (const e of file.entries) entries.push({ ...e, group: file.group });
  }
  return entries;
}

/** A Spring path pattern as a regular expression over a concrete path. */
export function patternRegex(pattern) {
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    const ch = pattern[i];
    if (ch === '{') {
      let depth = 1;
      let j = i + 1;
      while (j < pattern.length && depth > 0) {
        if (pattern[j] === '{') depth++;
        if (pattern[j] === '}') depth--;
        j++;
      }
      const inner = pattern.slice(i + 1, j - 1);
      if (inner.startsWith('*')) out += '.*';
      else if (inner.includes(':')) out += `(?:${inner.slice(inner.indexOf(':') + 1)})`;
      else out += '[^/]+';
      i = j;
    } else {
      out += ch.replace(/[.*+?^$()|[\]\\]/g, '\\$&');
      i++;
    }
  }
  return new RegExp(`^${out}$`);
}

/** The literal characters of a pattern: the more, the more specific (as Spring prefers). */
const specificity = (pattern) => pattern.replace(/\{[^}]*\}/g, '').length;

/** An index of the table by verb: each path of each entry with its regular expression. */
export function routes(entries) {
  const out = [];
  for (const e of entries) {
    const [verb, key] = e.key.split(' ');
    for (const p of [key, ...(e.alsoAt ?? [])]) {
      out.push({ verb, pattern: p, re: patternRegex(p), entry: e });
    }
  }
  return out;
}

/**
 * The entry a call reaches: the extracted path with each unknown part (`{}`) as a sample
 * segment, matched against every path of the table; the most specific match of the call's verb.
 */
export function resolve(index, verb, callPath) {
  const sample = callPath.replace(/\{\}/g, 'x0');
  const matches = index.filter((r) => r.re.test(sample));
  const same = matches
    .filter((r) => r.verb === verb)
    .sort((a, b) => specificity(b.pattern) - specificity(a.pattern));
  if (same.length) return { entries: [same[0].entry] };
  // validate-${kind}: an unknown part inside a segment reaches each entry whose literal path fits.
  if (/[^/]\{\}|\{\}[^/]/.test(callPath)) {
    const escaped = callPath.split('{}').map((p) => p.replace(/[.*+?^$()|[\]\\]/g, '\\$&'));
    const partial = new RegExp(`^${escaped.join('[^/]+')}$`);
    const fits = index.filter(
      (r) => r.verb === verb && partial.test(r.pattern.replace(/\{[^}]*\}/g, 'x0')),
    );
    if (fits.length) return { entries: [...new Set(fits.map((r) => r.entry))] };
  }
  return { entries: [], otherVerbs: [...new Set(matches.map((r) => r.verb))] };
}

// ---------------------------------------------------------------------------------------------
// Identities and answers

/** The roles of each identity of the role table, in the domain of its header (SweepIdentity). */
export const IDENTITY_ROLES = {
  anonymous: [],
  'anonymous+header': [],
  user: ['user'],
  userOtherDomain: [],
  editor: ['user', 'editor'],
  admin: ['user', 'editor', 'admin'],
  sysadmin: ['user', 'editor', 'admin', 'sysadmin'],
  domainEditor: ['user', 'editor'],
  adminElsewhere: ['user'],
};

const ROLE_CONSTANTS = {
  USER: 'user',
  EDITOR: 'editor',
  ADMIN: 'admin',
  SYSADMIN: 'sysadmin',
};

/** What the annotation alone answers an identity: ok, 401 (no token) or 403. */
export function derived(guard, identity) {
  if (guard === 'PermitAll') return 'ok';
  const anonymous = identity === 'anonymous' || identity === 'anonymous+header';
  if (guard === 'DenyAll') return anonymous ? 401 : 403;
  const m = /^RolesAllowed\((.*)\)$/.exec(guard);
  assert.ok(m, `unknown guard ${guard}`);
  if (anonymous) return 401;
  const roles = IDENTITY_ROLES[identity];
  assert.ok(roles, `unknown identity ${identity}`);
  return m[1].split(',').some((c) => roles.includes(ROLE_CONSTANTS[c.trim()])) ? 'ok' : 403;
}

/**
 * The table's answer to an identity: `{ ok, from }` where `from` is `cell` (a written cell),
 * `guard` (the annotation decides: no code check, or the annotation refuses), `row cell` (an ok
 * cell of that identity on a named row) or `unasserted` (a code-checked entry with no cell for
 * that identity, where the annotation allows).
 */
export function answer(entry, identity) {
  const cell = entry.answers?.[identity];
  if (cell !== undefined) return { ok: cell === 'ok', from: 'cell', cell };
  const byGuard = derived(entry.guard, identity);
  if (byGuard !== 'ok') return { ok: false, from: 'guard', cell: { status: byGuard } };
  // A cell on a named row (`user@sweep-messages-draft`) answers for that row: one that is ok
  // shows the identity allowed; a refused one is the row's rule (another owner's, another
  // domain's row), not the identity's, so it decides nothing here.
  if (rowCells(entry, identity).some((c) => c.ok)) {
    return { ok: true, from: 'row cell' };
  }
  return { ok: true, from: entry.codeCheck === 'found' ? 'unasserted' : 'guard' };
}

/** What a check says of an entry the backend has not decided (the generator's skeleton). */
const undecided = (e) =>
  `${e.key}, which the endpoint table has not decided (reviewed: false): decide its cells in ` +
  `be/niord/src/test/resources/endpoints/${e.group}.json (be/docs/test-infrastructure.md, "The endpoint sweep").`;

/** The cells of an identity on a named row: `<identity>@<row>`. */
export function rowCells(entry, identity) {
  return Object.entries(entry.answers ?? {})
    .filter(([name]) => name.startsWith(`${identity}@`))
    .map(([name, value]) => ({ name, ok: value === 'ok' }));
}

/**
 * The ticket cells of an entry: `ticket(<identity>:<role>)`, on the case's own row or on a named
 * one (`ticket(user:user)@sweep-messages-draft`) → role (null without one) and answer.
 */
export function ticketCells(entry) {
  const out = [];
  for (const [name, value] of Object.entries(entry.answers ?? {})) {
    const m = /^ticket\(([A-Za-z+]+)(?::([a-z-]+))?\)(?:@(.+))?$/.exec(name);
    if (m)
      out.push({ name, minter: m[1], role: m[2] ?? null, row: m[3] ?? null, ok: value === 'ok' });
  }
  return out;
}

/** A call the browser itself opens (a download, a new tab): it carries no bearer token. */
export const opensInBrowser = (c) => /^(openPublic|window)\b/.test(c.via);

// ---------------------------------------------------------------------------------------------
// The checks, as functions of their inputs (the negative tests below feed them crafted ones)

/**
 * Every call, with the entry it reaches; the unresolvable sites resolved through the manual
 * list. Returns the problems found and the resolved calls.
 */
export function checkCalls({ calls, entries, manual, accepted }) {
  const index = routes(entries);
  const problems = [];
  const resolved = [];
  const usedManual = new Set();
  const usedAccepted = new Set();
  const usedTicketRoles = new Set();
  const usedNotCalls = new Set();
  const all = [];
  for (const c of calls) {
    if (c.path !== null) {
      all.push(c);
      continue;
    }
    const m = manual.unresolved.findIndex(
      (u) => u.file === c.file && u.expression === c.expression,
    );
    if (m < 0) {
      problems.push(
        `${c.file}:${c.line} sends ${c.verb} to \`${c.expression}\`, an address the extractor cannot follow: ` +
          'list it in scripts/endpoint-roles/manual-calls.json ("unresolved") with the calls it makes and why.',
      );
      continue;
    }
    usedManual.add(m);
    for (const extra of manual.unresolved[m].calls ?? []) {
      all.push({ app: c.app, file: c.file, line: c.line, via: 'manual', params: [], ...extra });
    }
  }
  manual.unresolved.forEach((u, i) => {
    if (!usedManual.has(i)) {
      problems.push(
        `manual-calls.json lists ${u.file} \`${u.expression}\`, which no site has any more: remove it.`,
      );
    }
  });
  for (const c of all) {
    const where = `${c.file}:${c.line}`;
    if (c.via.startsWith('member') && notCall(c)) continue;
    const hit = resolve(index, c.verb, c.path);
    if (hit.entries.length === 0) {
      problems.push(
        `${c.verb} ${c.path} (${where}) reaches no handler of the endpoint table` +
          (hit.otherVerbs.length ? `; that path takes ${hit.otherVerbs.join(', ')}` : '') +
          '. If the backend has it, run its EndpointTableGenerator; if not, the frontend calls a path no handler maps.',
      );
      continue;
    }
    for (const e of hit.entries) checkOne(c, e, where);
  }

  function notCall(c) {
    const i = (manual.notCalls ?? []).findIndex(
      (n) => n.file === c.file && c.via === `member ${n.member}`,
    );
    if (i >= 0) usedNotCalls.add(i);
    return i >= 0;
  }

  function checkOne(c, e, where) {
    const call = { ...c, entry: e };
    resolved.push(call);
    if (!e.reviewed) {
      problems.push(`${c.verb} ${c.path} (${where}) reaches ${undecided(e)}`);
      return;
    }
    if (e.params !== '*') {
      const reads = new Set(e.params ?? []);
      for (const p of c.params) {
        if (reads.has(p)) continue;
        const a = accepted.findIndex((x) => x.endpoint === e.key && x.param === p);
        if (a >= 0) {
          usedAccepted.add(a);
          continue;
        }
        problems.push(
          `${c.verb} ${c.path} (${where}) sends \`${p}\`, which ${e.handler} (${e.key}) does not read ` +
            `(it reads ${[...reads].join(', ') || 'none'}): stop sending it, or list it in accepted-unread.json with its backlog line.`,
        );
      }
    }
    if (c.ticketRoles) checkTicket(call, e, where);
    // The public site has no sign-in, and a page the browser opens without a ticket sends no
    // token: both reach the handler as anonymous.
    const anonymously =
      c.app === 'info'
        ? `The public site calls ${c.verb} ${c.path} (${where})`
        : opensInBrowser(c) && !c.ticketRoles
          ? `The browser opens ${c.verb} ${c.path} (${where}) without a ticket, so as anonymous`
          : null;
    if (anonymously) {
      if (e.guard !== 'PermitAll') {
        problems.push(`${anonymously}, but ${e.key} is ${e.guard}: anonymous gets 401.`);
      } else if (e.answers?.anonymous !== undefined && e.answers.anonymous !== 'ok') {
        problems.push(
          `${anonymously}, but ${e.key} answers anonymous ${JSON.stringify(e.answers.anonymous)}.`,
        );
      }
    }
  }

  function checkTicket(call, e, where) {
    const c = call;
    let roles = c.ticketRoles;
    if (roles.includes('?')) {
      const t = manual.ticketRoles.findIndex((x) => x.file === c.file && x.endpoint === e.key);
      if (t < 0) {
        problems.push(
          `${c.verb} ${c.path} (${where}) carries a ticket whose role is asked elsewhere: name the roles in manual-calls.json ("ticketRoles").`,
        );
        return;
      }
      usedTicketRoles.add(t);
      roles = manual.ticketRoles[t].roles;
    }
    call.ticketRoles = roles;
    const cells = ticketCells(e);
    for (const role of roles) {
      if (cells.some((t) => t.role === role && t.ok)) continue;
      const seen = cells.map((t) => `${t.name}: ${t.ok ? 'ok' : 'refused'}`).join(', ');
      problems.push(
        `${c.verb} ${c.path} (${where}) asks a ticket ${role ? `with role ${role}` : 'with no role'}, ` +
          `but ${e.key} has no ticket cell that accepts one (${seen || 'no ticket cell'}).`,
      );
    }
  }

  (manual.notCalls ?? []).forEach((n, i) => {
    if (!usedNotCalls.has(i)) {
      problems.push(
        `manual-calls.json lists ${n.file} ${n.member} as no call, but the extractor no longer reports it: remove it.`,
      );
    }
  });
  accepted.forEach((a, i) => {
    if (!usedAccepted.has(i)) {
      problems.push(
        `accepted-unread.json lists ${a.param} on ${a.endpoint}, which no call sends any more: remove it.`,
      );
    }
  });
  manual.ticketRoles.forEach((t, i) => {
    if (!usedTicketRoles.has(i)) {
      problems.push(
        `manual-calls.json names ticket roles for ${t.file} ${t.endpoint}, which no call needs: remove it.`,
      );
    }
  });
  return { problems, resolved };
}

/**
 * Every identity an item is shown to is allowed by every endpoint the item calls, unless the
 * item lists that identity under `knownFaults` with where the fault is recorded (an offer the
 * backend refuses that is already in the backlog); such an entry that no longer shows fails, as
 * the backend's known faults do. With `reached` (the entry keys the extracted calls reach), an
 * endpoint an item names that no call of the frontend reaches fails too: the item's mapping is
 * stale.
 */
export function checkOffers({ offers, entries, reached }) {
  const byKey = new Map(entries.map((e) => [e.key, e]));
  const problems = [];
  const unasserted = [];
  for (const [component, def] of Object.entries(offers.components)) {
    for (const [item, spec] of Object.entries(def.items)) {
      const known = new Map(
        (spec.knownFaults ?? []).map((k) => [k.identity, { ...k, seen: false }]),
      );
      for (const key of spec.endpoints) {
        const e = byKey.get(key);
        if (!e) {
          problems.push(
            `role-offers.json: ${component} "${item}" calls ${key}, which is no entry of the endpoint table.`,
          );
          continue;
        }
        if (!e.reviewed) {
          problems.push(`role-offers.json: ${component} "${item}" calls ${undecided(e)}`);
          continue;
        }
        if (reached && !reached.has(key)) {
          problems.push(
            `role-offers.json: ${component} "${item}" calls ${key}, but no call of the frontend reaches ${key}: ` +
              'the item now calls something else; map it to the endpoints its action calls.',
          );
        }
        for (const identity of spec.shownTo) {
          const a = answer(e, identity);
          if (!a.ok) {
            const k = known.get(identity);
            if (k) {
              k.seen = true;
              continue;
            }
            problems.push(
              `${component} shows "${item}" to ${identity}, but ${key} answers ${identity} ${JSON.stringify(a.cell)} ` +
                `(${a.from === 'cell' ? 'its cell' : `its guard ${e.guard}`}).`,
            );
          } else if (a.from === 'unasserted') {
            unasserted.push(`${component} "${item}" → ${key} as ${identity} (${a.from})`);
          }
        }
      }
      for (const k of known.values()) {
        if (!k.seen) {
          problems.push(
            `role-offers.json lists ${component} "${item}" for ${k.identity} as a known fault (${k.ref}), ` +
              `but no endpoint it calls refuses ${k.identity} any more: remove the entry.`,
          );
        }
      }
    }
  }
  return { problems, unasserted };
}

/** A reference to where a fault or a decision is recorded, as the lists here accept it. */
export function validRef(ref, backlog, { design = true } = {}) {
  return (
    (ref.startsWith('backlog: ') && backlog.includes(ref.slice('backlog: '.length))) ||
    (design && /^design: \S/.test(ref))
  );
}

/** The client roles a realm user holds, composites and groups expanded, per client. */
export function realmClaims(realm, username) {
  const user = realm.users.find((u) => u.username === username);
  assert.ok(user, `no user ${username} in the test realm`);
  const clientRoles = realm.roles?.client ?? {};
  const out = {};
  const add = (client, role) => {
    out[client] ??= new Set();
    if (out[client].has(role)) return;
    out[client].add(role);
    const def = clientRoles[client]?.find((r) => r.name === role);
    for (const [c, roles] of Object.entries(def?.composites?.client ?? {})) {
      for (const r of roles) add(c, r);
    }
  };
  for (const [client, roles] of Object.entries(user.clientRoles ?? {}))
    for (const r of roles) add(client, r);
  const groups = [];
  const walk = (gs) => gs?.forEach((g) => (groups.push(g), walk(g.subGroups)));
  walk(realm.groups);
  for (const p of user.groups ?? []) {
    const g = groups.find((x) => x.path === p);
    for (const [client, roles] of Object.entries(g?.clientRoles ?? {}))
      for (const r of roles) add(client, r);
  }
  return Object.fromEntries(Object.entries(out).map(([c, s]) => [c, [...s].sort()]));
}

/** The source files that gate on a role and are neither rendered by the spec nor listed. */
export function unlistedGates({ offers, files }) {
  const covered = new Set([
    ...Object.values(offers.components).map((c) => c.file),
    ...Object.keys(offers.notRendered),
  ]);
  return files.filter((f) => !covered.has(f));
}

// ---------------------------------------------------------------------------------------------
// The real inputs

const entries = loadTable();
const calls = extractCalls(FE);
const manual = readJson(MANUAL);
const accepted = readJson(ACCEPTED).entries;
const offers = readJson(OFFERS);
const result = checkCalls({ calls, entries, manual, accepted });

const fmt = (list) => `\n  ${list.join('\n  ')}`;

describe('endpoint roles: the frontend against the endpoint table', () => {
  it('every call reaches an entry, sends only parameters it reads, asks tickets it accepts', () => {
    assert.deepEqual(result.problems, [], fmt(result.problems));
  });

  it('every accepted unread parameter and known offer fault names where it is recorded', () => {
    const backlog = fs.readFileSync(path.join(FE, 'docs/backlog.md'), 'utf8');
    const bad = accepted.filter((a) => !validRef(a.ref, backlog));
    assert.deepEqual(
      bad,
      [],
      'a ref is "backlog: <a phrase of fe/docs/backlog.md>" or "design: <why>"',
    );
    const badFaults = Object.entries(offers.components).flatMap(([component, def]) =>
      Object.entries(def.items).flatMap(([item, spec]) =>
        (spec.knownFaults ?? [])
          .filter((k) => !validRef(k.ref ?? '', backlog, { design: false }))
          .map((k) => `${component} "${item}" ${k.identity}: ${k.ref}`),
      ),
    );
    assert.deepEqual(
      badFaults,
      [],
      'a known offer fault is "backlog: <a phrase of fe/docs/backlog.md>"',
    );
  });

  it('the counts: calls checked, entries of the table', () => {
    console.log(
      `endpoint roles: ${result.resolved.length} calls checked; table: ${entries.length} entries`,
    );
    assert.ok(result.resolved.length > 300, `only ${result.resolved.length} calls extracted`);
  });

  it('every item shown to an identity is allowed for it by each endpoint the item calls', () => {
    const reached = new Set(result.resolved.map((c) => c.entry.key));
    const { problems, unasserted } = checkOffers({ offers, entries, reached });
    console.log(
      `endpoint roles: ${unasserted.length} offer cells rest on a code check the table writes no ` +
        'cell for that identity on (the annotation allows it)',
    );
    assert.deepEqual(problems, [], fmt(problems));
  });

  it('the identities of role-offers.json hold the client roles the test realm gives their users', () => {
    const realm = readJson(REALM);
    const problems = [];
    for (const [name, id] of Object.entries(offers.identities)) {
      if (!id.username) continue;
      const expected = realmClaims(realm, id.username);
      const claimed = Object.fromEntries(
        Object.entries(id.resourceAccess).map(([c, r]) => [c, [...r].sort()]),
      );
      for (const client of new Set([...Object.keys(expected), ...Object.keys(claimed)])) {
        if (client === 'account') continue;
        if (JSON.stringify(expected[client] ?? []) !== JSON.stringify(claimed[client] ?? [])) {
          problems.push(
            `${name} (${id.username}) on ${client}: the realm gives ${JSON.stringify(expected[client] ?? [])}, ` +
              `role-offers.json claims ${JSON.stringify(claimed[client] ?? [])}`,
          );
        }
      }
      if (!IDENTITY_ROLES[name]) problems.push(`${name} is no identity of the endpoint table`);
    }
    assert.deepEqual(problems, [], fmt(problems));
  });

  it('every file that gates on a role is rendered by the spec or listed with its reason', () => {
    const gating = sourceFiles(FE)
      .filter(({ app }) => app === 'admin')
      .map(({ file }) => file)
      .filter(
        (f) =>
          !f.startsWith(OFFERS_DIR) && GATE_WORDS.test(fs.readFileSync(path.join(FE, f), 'utf8')),
      );
    const missing = unlistedGates({ offers, files: gating });
    assert.deepEqual(
      missing,
      [],
      `gated files the spec does not render: list each in role-offers.json${fmt(missing)}`,
    );
    const stale = Object.keys(offers.notRendered).filter((f) => !gating.includes(f));
    assert.deepEqual(stale, [], `role-offers.json lists files that gate on no role${fmt(stale)}`);
  });
});

describe('endpoint roles: the checks refuse what they exist to refuse', () => {
  const entry = (key, extra = {}) => ({
    key,
    handler: 'X.y',
    alsoAt: [],
    guard: 'PermitAll',
    codeCheck: 'none-found',
    params: [],
    answers: {},
    reviewed: true,
    group: 'g',
    ...extra,
  });
  const none = { unresolved: [], ticketRoles: [] };
  const call = (verb, p, extra = {}) => ({
    app: 'admin',
    file: 'f.ts',
    line: 1,
    via: 'http',
    verb,
    path: p,
    params: [],
    ...extra,
  });

  it('a call to a path no entry lists, a trailing slash included (FILTER2)', () => {
    const table = [entry('POST /rest/filters/filter')];
    const r = checkCalls({
      calls: [call('POST', '/rest/filters/filter/')],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.match(r.problems.join('\n'), /reaches no handler/);
    const ok = checkCalls({
      calls: [call('POST', '/rest/filters/filter/')],
      entries: [entry('POST /rest/filters/filter', { alsoAt: ['/rest/filters/filter/'] })],
      manual: none,
      accepted: [],
    });
    assert.deepEqual(ok.problems, []);
  });

  it('a call with the wrong verb', () => {
    const r = checkCalls({
      calls: [call('PUT', '/rest/a/{}')],
      entries: [entry('GET /rest/a/{id}')],
      manual: none,
      accepted: [],
    });
    assert.match(r.problems.join('\n'), /takes GET/);
  });

  it('a parameter the handler does not read', () => {
    const table = [entry('GET /rest/charts/search/{ids}', { params: ['ids', 'lang'] })];
    const r = checkCalls({
      calls: [call('GET', '/rest/charts/search/{}', { params: ['geometry'] })],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.match(r.problems.join('\n'), /sends `geometry`/);
  });

  it('a ticket role the entry refuses (N8: contacts-tab asks user where admin is checked)', () => {
    const table = [
      entry('GET /rest/contacts/export', {
        codeCheck: 'found',
        answers: {
          'ticket(admin:admin)': 'ok',
          'ticket(user:user)': { status: 403, detail: null },
        },
      }),
    ];
    const r = checkCalls({
      calls: [call('GET', '/rest/contacts/export', { ticketRoles: ['user'] })],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.match(r.problems.join('\n'), /with role user/);
  });

  it('a public-site call to an endpoint anonymous may not call', () => {
    const table = [entry('GET /rest/x', { guard: 'RolesAllowed(USER)' })];
    const r = checkCalls({
      calls: [call('GET', '/rest/x', { app: 'info' })],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.match(r.problems.join('\n'), /anonymous gets 401/);
  });

  it('an offer shown to an identity the endpoint refuses (FE-437: Mail shown to user)', () => {
    const table = [entry('GET /rest/message-mail/send', { guard: 'RolesAllowed(EDITOR)' })];
    const o = {
      components: {
        Menu: {
          file: 'm.ts',
          items: {
            mail: { endpoints: ['GET /rest/message-mail/send'], shownTo: ['user', 'editor'] },
          },
        },
      },
    };
    const r = checkOffers({ offers: o, entries: table });
    assert.match(r.problems.join('\n'), /shows "mail" to user/);
  });

  it('a stale manual or accepted entry', () => {
    const r = checkCalls({
      calls: [],
      entries: [],
      manual: { unresolved: [{ file: 'f.ts', expression: 'x' }], ticketRoles: [] },
      accepted: [{ endpoint: 'GET /rest/x', param: 'y' }],
    });
    assert.equal(r.problems.length, 2);
  });

  it('a call or an item that reaches an entry the backend has not decided', () => {
    const table = [entry('GET /rest/contacts/export', { reviewed: false, codeCheck: 'found' })];
    const r = checkCalls({
      calls: [call('GET', '/rest/contacts/export', { ticketRoles: ['user'] })],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.equal(r.problems.length, 1, r.problems.join('\n'));
    assert.match(
      r.problems[0],
      /reaches GET \/rest\/contacts\/export, which the endpoint table has not decided/,
    );
    const o = {
      components: {
        Menu: {
          file: 'm.ts',
          items: { export: { endpoints: ['GET /rest/contacts/export'], shownTo: ['admin'] } },
        },
      },
    };
    assert.match(
      checkOffers({ offers: o, entries: table }).problems.join('\n'),
      /"export" calls GET \/rest\/contacts\/export, which the endpoint table has not decided/,
    );
  });

  it('a ref names a backlog phrase or a design decision, nothing else', () => {
    const backlog = '- **The picker offers an action the backend refuses.** More.';
    assert.ok(validRef('backlog: The picker offers an action the backend refuses', backlog));
    assert.ok(validRef('design: sent on purpose', backlog));
    assert.ok(!validRef('design: sent on purpose', backlog, { design: false }));
    assert.ok(!validRef('backlog: A line the backlog does not hold', backlog));
    assert.ok(!validRef('intake:messages-1', backlog));
  });

  it('a ticket cell on a named row counts, for its role only', () => {
    const table = [
      entry('GET /rest/r/{id}.pdf', {
        codeCheck: 'found',
        answers: {
          'ticket(user:user)@sweep-x-draft': 'ok',
          'ticket(user)@sweep-x-draft': { status: 403, detail: null },
        },
      }),
    ];
    const r = checkCalls({
      calls: [call('GET', '/rest/r/{}.pdf', { ticketRoles: ['user', null] })],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.equal(r.problems.length, 1, r.problems.join('\n'));
    assert.match(r.problems[0], /with no role, but GET \/rest\/r\/\{id\}\.pdf has no ticket cell/);
  });

  it('a download the browser opens without a ticket reaches the endpoint as anonymous', () => {
    const table = [
      entry('GET /rest/settings/editable-settings', {
        codeCheck: 'found',
        answers: { anonymous: { status: 403, detail: null }, 'ticket(sysadmin:sysadmin)': 'ok' },
      }),
      entry('GET /rest/x/export', { guard: 'RolesAllowed(ADMIN)' }),
    ];
    const r = checkCalls({
      calls: [
        call('GET', '/rest/settings/editable-settings', { via: 'openPublic' }),
        call('GET', '/rest/x/export', { via: 'window' }),
        call('GET', '/rest/settings/editable-settings', {
          via: 'openTicketed',
          ticketRoles: ['sysadmin'],
        }),
      ],
      entries: table,
      manual: none,
      accepted: [],
    });
    assert.equal(r.problems.length, 2, r.problems.join('\n'));
    assert.match(
      r.problems[0],
      /opens GET \/rest\/settings\/editable-settings .* without a ticket.*answers anonymous/,
    );
    assert.match(r.problems[1], /opens GET \/rest\/x\/export .* anonymous gets 401/);
  });

  it('an item mapped to an endpoint no call reaches any more', () => {
    const table = [entry('GET /rest/message-mail/send', { guard: 'RolesAllowed(EDITOR)' })];
    const o = {
      components: {
        Menu: {
          file: 'm.ts',
          items: { mail: { endpoints: ['GET /rest/message-mail/send'], shownTo: ['editor'] } },
        },
      },
    };
    const r = checkOffers({ offers: o, entries: table, reached: new Set() });
    assert.match(r.problems.join('\n'), /"mail" calls GET \/rest\/message-mail\/send, but no call/);
    assert.deepEqual(
      checkOffers({ offers: o, entries: table, reached: new Set(['GET /rest/message-mail/send']) })
        .problems,
      [],
    );
  });

  it('a known offer fault passes while it shows, and fails once it no longer does', () => {
    const table = [entry('POST /rest/tags/tag', { guard: 'RolesAllowed(USER)' })];
    const offer = (shownTo) => ({
      components: {
        Picker: {
          file: 'p.ts',
          items: {
            manage: {
              endpoints: ['POST /rest/tags/tag'],
              shownTo,
              knownFaults: [{ identity: 'userOtherDomain', ref: 'backlog: x' }],
            },
          },
        },
      },
    });
    assert.deepEqual(
      checkOffers({ offers: offer(['user', 'userOtherDomain']), entries: table }).problems,
      [],
    );
    assert.match(
      checkOffers({ offers: offer(['user']), entries: table }).problems.join('\n'),
      /known fault .* no endpoint it calls refuses userOtherDomain any more/,
    );
    const unknown = offer(['user', 'userOtherDomain', 'anonymous']);
    assert.match(
      checkOffers({ offers: unknown, entries: table }).problems.join('\n'),
      /shows "manage" to anonymous, but POST \/rest\/tags\/tag answers anonymous/,
    );
  });

  it('a row cell that is ok shows the identity allowed; a refused one decides nothing', () => {
    const e = entry('GET /rest/m/{id}', {
      codeCheck: 'found',
      answers: {
        'user@sweep-x-draft': 'ok',
        'editor@sweep-x-draft-nm': { status: 403, detail: null },
      },
    });
    assert.equal(answer(e, 'user').from, 'row cell');
    assert.deepEqual(answer(e, 'editor'), { ok: true, from: 'unasserted' });
  });

  it('a gated file neither rendered nor listed', () => {
    const o = { components: { A: { file: 'a.ts', items: {} } }, notRendered: { 'b.ts': 'why' } };
    assert.deepEqual(unlistedGates({ offers: o, files: ['a.ts', 'b.ts', 'c.ts'] }), ['c.ts']);
  });
});
