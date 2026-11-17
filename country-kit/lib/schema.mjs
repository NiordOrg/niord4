/**
 * A JSON Schema validator for the few keywords the kit's schemas use, Node built-ins only: the
 * kit runs before anything is installed.
 *
 * It knows `type`, `properties`, `required`, `additionalProperties`, `patternProperties`,
 * `pattern`, `enum`, `const`, `items`, `uniqueItems`, `minItems`, `maxLength` and `oneOf`, with
 * draft 2020-12's meaning, and reads `$schema`, `title` and `description` as notes. A schema with
 * any other keyword is refused rather than half-applied: a keyword this validator skipped would
 * let through what the schema's reader thinks is checked.
 *
 * validate returns every problem, each as `{ path, message }` with `path` the value's place in
 * the document — `apps.admin.uiLanguages[1]`, `$` for the document itself.
 */
const KEYWORDS = new Set([
  '$schema',
  'title',
  'description',
  'type',
  'properties',
  'required',
  'additionalProperties',
  'patternProperties',
  'pattern',
  'enum',
  'const',
  'items',
  'uniqueItems',
  'minItems',
  'maxLength',
  'oneOf',
]);
const TYPES = new Set(['object', 'array', 'string', 'number', 'integer', 'boolean', 'null']);

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function typeOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  return typeof value;
}

const NAMES = {
  object: 'an object',
  array: 'a list',
  string: 'a text',
  number: 'a number',
  integer: 'a whole number',
  boolean: 'true or false',
  null: 'null',
};
const named = (type) => NAMES[type] ?? type;

const fits = (value, type) =>
  type === 'number' ? typeof value === 'number' : typeOf(value) === type;

/** Two JSON values are equal: the same type and, recursively, the same contents. */
function equal(a, b) {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b))
    return a.length === b.length && a.every((item, i) => equal(item, b[i]));
  if (isObject(a) && isObject(b)) {
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((key) => equal(a[key], b[key]));
  }
  return false;
}

/** Where a value sits: `a.b`, `a[0]`, `a["x-y"]` for a key that is not a plain name. */
const child = (path, key) =>
  typeof key === 'number'
    ? `${path}[${key}]`
    : /^[A-Za-z_$][\w$]*$/.test(key)
      ? path === '$'
        ? key
        : `${path}.${key}`
      : `${path === '$' ? '' : path}[${JSON.stringify(key)}]`;

/** Refuses a schema with a keyword this validator does not apply. */
export function checkSchema(schema, path = '#') {
  if (typeof schema === 'boolean') return;
  if (!isObject(schema)) throw new Error(`the schema at ${path} is not an object`);
  for (const keyword of Object.keys(schema))
    if (!KEYWORDS.has(keyword))
      throw new Error(
        `the schema uses "${keyword}" at ${path}, which this validator does not apply`,
      );
  if (schema.type !== undefined && !TYPES.has(schema.type))
    throw new Error(`the schema at ${path} names the type "${schema.type}"`);
  for (const [name, sub] of Object.entries(schema.properties ?? {}))
    checkSchema(sub, `${path}/properties/${name}`);
  for (const [pattern, sub] of Object.entries(schema.patternProperties ?? {})) {
    new RegExp(pattern, 'u');
    checkSchema(sub, `${path}/patternProperties/${pattern}`);
  }
  if (isObject(schema.additionalProperties))
    checkSchema(schema.additionalProperties, `${path}/additionalProperties`);
  if (schema.items !== undefined) checkSchema(schema.items, `${path}/items`);
  (schema.oneOf ?? []).forEach((sub, i) => checkSchema(sub, `${path}/oneOf/${i}`));
  if (schema.pattern !== undefined) new RegExp(schema.pattern, 'u');
}

function validateAt(value, schema, path, problems) {
  const fail = (message) => problems.push({ path, message });
  if (schema === true) return;
  if (schema === false) return fail('is not allowed');

  if (schema.type !== undefined && !fits(value, schema.type))
    return fail(`is ${named(typeOf(value))}, not ${named(schema.type)}`);
  if (schema.const !== undefined && !equal(value, schema.const))
    fail(`is not ${JSON.stringify(schema.const)}`);
  if (schema.enum !== undefined && !schema.enum.some((option) => equal(value, option)))
    fail(`is not one of ${schema.enum.map((option) => JSON.stringify(option)).join(', ')}`);

  if (typeof value === 'string') {
    if (schema.pattern !== undefined && !new RegExp(schema.pattern, 'u').test(value))
      fail(`${JSON.stringify(value)} does not match ${schema.pattern}`);
    if (schema.maxLength !== undefined && [...value].length > schema.maxLength)
      fail(`has ${[...value].length} characters, over ${schema.maxLength}`);
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems)
      fail(`has ${value.length} item(s), under ${schema.minItems}`);
    if (schema.uniqueItems)
      value.forEach((item, i) => {
        if (value.findIndex((other) => equal(other, item)) < i)
          problems.push({ path: child(path, i), message: `repeats ${JSON.stringify(item)}` });
      });
    if (schema.items !== undefined)
      value.forEach((item, i) => validateAt(item, schema.items, child(path, i), problems));
  }

  if (isObject(value)) {
    for (const name of schema.required ?? [])
      if (!Object.hasOwn(value, name)) fail(`has no ${name}`);
    const patterns = Object.entries(schema.patternProperties ?? {}).map(([pattern, sub]) => [
      new RegExp(pattern, 'u'),
      sub,
    ]);
    for (const [key, item] of Object.entries(value)) {
      const at = child(path, key);
      let known = false;
      if (schema.properties && Object.hasOwn(schema.properties, key)) {
        known = true;
        validateAt(item, schema.properties[key], at, problems);
      }
      for (const [re, sub] of patterns)
        if (re.test(key)) {
          known = true;
          validateAt(item, sub, at, problems);
        }
      if (known || schema.additionalProperties === undefined) continue;
      if (schema.additionalProperties === false)
        problems.push({ path: at, message: 'is an unknown field' });
      else validateAt(item, schema.additionalProperties, at, problems);
    }
  }

  if (schema.oneOf !== undefined) {
    const outcomes = schema.oneOf.map((sub) => {
      const found = [];
      validateAt(value, sub, path, found);
      return found;
    });
    const passing = outcomes.filter((found) => found.length === 0).length;
    if (passing === 0)
      fail(
        `matches none of its ${schema.oneOf.length} forms (${outcomes
          .map((found, i) => `${i + 1}: ${found.map((p) => `${p.path} ${p.message}`).join('; ')}`)
          .join(' | ')})`,
      );
    else if (passing > 1) fail(`matches ${passing} of its forms, not exactly one`);
  }
}

/** Every problem of `value` against `schema`; an empty list when it is valid. */
export function validate(value, schema) {
  checkSchema(schema);
  const problems = [];
  validateAt(value, schema, '$', problems);
  return problems;
}
