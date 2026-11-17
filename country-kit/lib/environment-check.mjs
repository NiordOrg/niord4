/**
 * The production environment's `production` must be the name `true`: the e2e test's Keycloak
 * token stub and the dev routes are left out of a bundle by it alone (app.config.ts,
 * app.routes.ts), so a file that omits it, or was copied from the development file with `false`,
 * is refused.
 *
 * Its three addresses must be origin-relative: `apiBaseUrl` (`/rest`),
 * `keycloak.url` (`/auth`) and, when set, `map.wmsUrl` (the backend's same-origin WMS proxy,
 * `/wms/`). A deployed bundle then talks to its own origin only, and the administration app's
 * Content-Security-Policy needs no other origin for them.
 *
 * Set-up runs before `npm ci`, so there is no TypeScript compiler to ask: the file is read
 * lexically. The object exported as `environment` is found, and each of the three values must be
 * a string literal starting with exactly one `/`. A value that is not a literal — a name, a
 * concatenation, a template with a substitution, a spread that might carry it — fails, because
 * it cannot be read without running the file.
 */
import fs from 'node:fs';

/** The tokens of a TypeScript text: strings, names, numbers and single punctuation characters. */
function tokens(text) {
  const out = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (/\s/.test(c)) i++;
    else if (text.startsWith('//', i))
      i = text.indexOf('\n', i) < 0 ? text.length : text.indexOf('\n', i);
    else if (text.startsWith('/*', i)) {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 2;
    } else if (c === "'" || c === '"' || c === '`') {
      let j = i + 1;
      let value = '';
      let substitution = false;
      while (j < text.length && text[j] !== c) {
        if (text[j] === '\\') {
          value += text[j + 1] ?? '';
          j += 2;
          continue;
        }
        if (c === '`' && text.startsWith('${', j)) substitution = true;
        value += text[j++];
      }
      out.push({ kind: substitution ? 'template' : 'string', value });
      i = j + 1;
    } else if (/[A-Za-z_$]/.test(c)) {
      const name = text.slice(i).match(/^[A-Za-z_$][\w$]*/)[0];
      out.push({ kind: 'name', value: name });
      i += name.length;
    } else if (/[0-9]/.test(c)) {
      const number = text.slice(i).match(/^[0-9][\w.]*/)[0];
      out.push({ kind: 'number', value: number });
      i += number.length;
    } else if (text.startsWith('...', i)) {
      out.push({ kind: 'spread', value: '...' });
      i += 3;
    } else {
      out.push({ kind: 'punct', value: c });
      i++;
    }
  }
  return out;
}

const SPREAD = Symbol('spread');

/**
 * Reads the object literal starting at `tokens[start]` (`{`). Returns its properties, each a
 * nested object or `{ tokens }` for any other value, and the index after its `}`. A spread is
 * recorded under SPREAD.
 */
function objectAt(list, start) {
  const properties = {};
  let i = start + 1;
  while (i < list.length && !(list[i].kind === 'punct' && list[i].value === '}')) {
    if (list[i].kind === 'spread') {
      properties[SPREAD] = true;
      i++;
    } else if (list[i].kind === 'name' || list[i].kind === 'string') {
      const key = list[i].value;
      if (list[i + 1]?.value !== ':') {
        // A shorthand property: its value is a name, not a literal.
        properties[key] = { tokens: [list[i]] };
        i++;
      } else if (list[i + 2]?.value === '{') {
        const nested = objectAt(list, i + 2);
        properties[key] = nested.properties;
        i = nested.next;
        continue;
      } else {
        const value = [];
        let depth = 0;
        let j = i + 2;
        for (; j < list.length; j++) {
          const token = list[j];
          if (token.kind === 'punct' && '([{'.includes(token.value)) depth++;
          if (token.kind === 'punct' && ')]}'.includes(token.value)) {
            if (depth === 0) break;
            depth--;
          }
          if (depth === 0 && token.kind === 'punct' && token.value === ',') break;
          value.push(token);
        }
        properties[key] = { tokens: value };
        i = j;
        continue;
      }
    }
    if (list[i]?.kind === 'punct' && list[i].value === ',') i++;
    else if (list[i] && !(list[i].kind === 'punct' && list[i].value === '}')) i++;
  }
  return { properties, next: i + 1 };
}

/** The exported `environment` object's properties, or undefined when the file has none. */
function environmentObject(text) {
  const list = tokens(text);
  for (let i = 0; i < list.length; i++) {
    if (list[i].value !== 'environment' || list[i - 1]?.value !== 'const') continue;
    let j = i + 1;
    while (j < list.length && list[j].value !== '=') j++;
    if (list[j + 1]?.value === '{') return objectAt(list, j + 1).properties;
    return null;
  }
  return undefined;
}

const FIELDS = [
  { path: ['production'], required: true, literal: 'true' },
  { path: ['apiBaseUrl'], required: true },
  { path: ['keycloak', 'url'], required: true },
  { path: ['map', 'wmsUrl'], required: false },
];

/** What a production bundle gets when `production` is not `true` (app.config.ts, app.routes.ts). */
const NOT_PRODUCTION =
  "a production bundle without it lets the e2e test's Keycloak token stub (window.__NIORD_TEST_AUTH__) take Keycloak's place and compiles in the dev routes";

/**
 * The problems of a production environment file's text: each an error line naming the field.
 * `label` names the file in the messages.
 */
export function checkEnvironmentText(text, label = 'environment.production.ts') {
  const errors = [];
  const environment = environmentObject(text);
  if (environment === undefined) return [`${label} exports no \`environment\``];
  if (environment === null) return [`${label}: \`environment\` is not an object literal`];
  for (const { path, required, literal } of FIELDS) {
    const name = path.join('.');
    let holder = environment;
    let value;
    let spread = false;
    for (const [index, key] of path.entries()) {
      if (holder[SPREAD]) spread = true;
      const next = Object.hasOwn(holder, key) ? holder[key] : undefined;
      if (index === path.length - 1) value = next;
      else if (next && !next.tokens) holder = next;
      else {
        if (next?.tokens) value = { tokens: next.tokens, notObject: true };
        break;
      }
    }
    if (value === undefined) {
      if (spread)
        errors.push(
          `${label}: ${name} may come from a spread, which set-up cannot read; write it out`,
        );
      else if (literal)
        errors.push(`${label} does not set ${name}: it is \`${literal}\`, as ${NOT_PRODUCTION}`);
      else if (required) errors.push(`${label} does not set ${name}`);
      continue;
    }
    if (value.notObject) {
      errors.push(`${label}: ${path[0]} is not an object literal, so ${name} cannot be read`);
      continue;
    }
    const [token, ...rest] = value.tokens;
    if (literal) {
      if (!token || rest.length || token.kind !== 'name' || token.value !== literal)
        errors.push(`${label}: ${name} is not \`${literal}\`: ${NOT_PRODUCTION}`);
      continue;
    }
    if (!token || rest.length || token.kind !== 'string') {
      errors.push(
        `${label}: ${name} is not a string literal; it is an origin-relative address such as '/rest'`,
      );
      continue;
    }
    if (!/^\/(?!\/)/.test(token.value) || token.value.includes('\\'))
      errors.push(
        `${label}: ${name} is '${token.value}', not origin-relative: a deployed bundle reaches its backend, Keycloak and the WMS proxy on its own origin (/rest, /auth, /wms/)`,
      );
  }
  return errors;
}

/** The problems of the country's production environment file; none when it has no such file. */
export function checkEnvironment(file, label) {
  if (!fs.existsSync(file)) return [];
  return checkEnvironmentText(fs.readFileSync(file, 'utf8'), label);
}
