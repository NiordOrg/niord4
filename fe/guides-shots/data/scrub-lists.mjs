// scrub-lists.mjs: what the rig's checks share with scrub.sql, read from scrub.sql itself so that each list is written
// once: the functional logins and mailboxes (g_functional_login), the notices with a private mailbox
// (g_private_mail), the notice with a private contact (g_private_contact) and the form of an address that is a
// person's by its shape (guides.scrub_shape). It also reads the persons' addresses of scrub.sql's rule "A person's
// address in any text", the addresses of that form and the other addresses default-deny replaces from niord_test
// (only read), into memory: they are never written or printed. It holds default-deny's list of role words
// (ROLE_WORDS) and the address pattern the rig's scripts share (ADDRESS_PATTERN).
//
// Used by english-history.mjs and repo-files.mjs. make-guides-db.sh passes the lists to scrub.sql, check.sql and
// check-names.sh (which runs inside the database container and cannot read this folder):
//   node scrub-lists.mjs functional|private-mail|private-contact|role-words     prints the list, '|'-separated
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

function valuesOf(sql, statement) {
  const match = new RegExp(`${statement.replace(/[.()]/g, '\\$&')} VALUES([^;]+);`).exec(sql);
  const values = match ? [...match[1].matchAll(/'([^']*)'/g)].map((m) => m[1]) : [];
  if (values.length === 0 || values.some((v) => !/^[A-Za-z0-9-]+$/.test(v)))
    throw new Error(
      `scrub-lists: "${statement}" not found in scrub.sql, or a value outside [A-Za-z0-9-].`,
    );
  return values;
}

/** The lists of scrub.sql. */
export function scrubLists() {
  const sql = readFileSync(join(HERE, 'scrub.sql'), 'utf8');
  const shape = /INSERT INTO guides\.scrub_shape \(form\) VALUES \('([^']+)'\);/.exec(sql)?.[1];
  if (!shape)
    throw new Error('scrub-lists: the form of guides.scrub_shape was not found in scrub.sql.');
  const address = /INSERT INTO guides\.scrub_address \(pattern\) VALUES \('([^']+)'\);/.exec(
    sql,
  )?.[1];
  if (!address)
    throw new Error('scrub-lists: the pattern of guides.scrub_address was not found in scrub.sql.');
  return {
    functional: valuesOf(sql, 'INSERT INTO g_functional_login (login)'),
    privateMail: valuesOf(sql, 'INSERT INTO g_private_mail (short_id)'),
    privateContact: valuesOf(sql, 'INSERT INTO g_private_contact (short_id)'),
    shape,
    address,
  };
}

/** A list as an SQL list of literals: ('a', 'b'). The values are [A-Za-z0-9-] only (checked above). */
export const sqlList = (values) => `(${values.map((v) => `'${v}'`).join(', ')})`;

/**
 * Default-deny (the orchestrator's decision of 2026-11-14 under the owner's "remove GDPR information"): an address
 * outside example.org is kept only when it is known to be a role: its local part is on scrub.sql's functional list
 * (g_functional_login), or every word of its local part (split at dots, hyphens, underscores and digits) is on this
 * list of generic role, department and unit words. Every other address counts as a person's and is replaced (texts,
 * search words, history snapshots) or keeps its file out (repository files). Built from the data of niord_test
 * (2026-11-14): the words of the addresses its texts, search words, snapshots and repository files keep, judged one by
 * one; a word that might be a first name or initials is NOT on the list, so its address is replaced. It holds no
 * person's name and no whole address. Lower case, letters only.
 */
export const ROLE_WORDS = [
  // seeds: generic mailboxes and services
  'info',
  'post',
  'mail',
  'kontakt',
  'contact',
  'vagt',
  'vagts',
  'vrag',
  'office',
  'vts',
  'mrcc',
  'jrcc',
  'navtex',
  'havn',
  'havnen',
  'harbour',
  // departments and services of the data
  'distribution',
  'forebyg',
  'greenpos',
  'iskort',
  'kundeservice',
  'nautical',
  'operations',
  'operativsektion',
  'shipping',
  'niord',
  'account',
  'commcen',
  'havnekontor',
  'havnevagten',
  'lodstilsyn',
  'maritim',
  'ntm',
  'ufs',
  'pilot',
  'soekort',
  'vagtcentral',
  'ship',
  'pos',
  'shippos',
  'requests',
  'cps',
  // publications, organisations and units written as codes
  'efs',
  'brs',
  'sfs',
  'sifa',
  'danpilot',
  'skawpilot',
  'kingfisher',
  'dmi',
  'frv',
  'gst',
  'kms',
  'fic',
  'grl',
  'isc',
  'oyr',
  'vd',
  'ako',
  'fko',
  'ktp',
  'vfk',
];
if (ROLE_WORDS.some((w) => !/^[a-z]+$/.test(w)))
  throw new Error('scrub-lists: a role word outside [a-z].');

/**
 * An e-mail address anywhere in a text (the whole local part: nothing of an address before it), case-insensitively;
 * group 1 is the address. Written once, in scrub.sql (guides.scrub_address), which check.sql reads from the copy;
 * english-history.mjs and repo-files.mjs read it here.
 */
export const ADDRESS_PATTERN = scrubLists().address;

/** The words of an address's local part: split at dots, hyphens, underscores and digits. */
export const localWords = (address) =>
  address
    .toLowerCase()
    .split('@')[0]
    .split(/[._0-9-]+/)
    .filter(Boolean);

/**
 * Whether default-deny keeps an address as it is: example.org, a functional login or mailbox of scrub.sql, or a local
 * part made of role words only.
 */
export function keptAddress(address, functional = scrubLists().functional) {
  const lower = address.toLowerCase();
  const [local, domain] = lower.split('@');
  if (domain === 'example.org') return true;
  if (functional.includes(local)) return true;
  const words = localWords(lower);
  return words.length > 0 && words.every((w) => ROLE_WORDS.includes(w));
}

/** scrub.sql's and check.sql's test of the same rule, for SQL: the address a (lower case) is kept. */
export const keptSql = (a, functional) =>
  `(split_part(${a}, '@', 2) = 'example.org' OR split_part(${a}, '@', 1) IN ${sqlList(functional)} ` +
  `OR coalesce(array_remove(regexp_split_to_array(split_part(${a}, '@', 1), '[._0-9-]+'), '') ` +
  `<@ ARRAY${JSON.stringify(ROLE_WORDS).replaceAll('"', "'")}::text[] ` +
  `AND cardinality(array_remove(regexp_split_to_array(split_part(${a}, '@', 1), '[._0-9-]+'), '')) > 0, false))`;

/**
 * An address of the person's form (scrub.sql's guides.scrub_shape, the local part) at any domain, as the pattern
 * scrub.sql and check.sql use: the whole local part (nothing of an address before it), case-insensitively.
 */
export const shapePattern = (shape) =>
  `(?<![a-z0-9._%+-])((?:${shape})@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})(?![a-z0-9-])`;

export function psqlOn(container, db, sql, user = 'niord') {
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      container,
      'psql',
      '-X',
      '-q',
      '-At',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      user,
      '-d',
      db,
    ],
    { input: sql, encoding: 'utf8', maxBuffer: 1 << 28, windowsHide: true },
  );
}

/**
 * The persons' addresses of scrub.sql's rule (g_person_mail: the users' addresses, the comment recipients', not the
 * functional mailboxes, and the private mailbox), the addresses of the person's form that belong to none of them
 * (src 'shape', ranked as scrub.sql ranks them) and every other address of a text column or the search words that
 * default-deny does not keep (src 'deny', ranked likewise: scrub.sql's contact<N>@example.org), read from the source
 * database (niord_test, only read), lower case: [address, src, key, priority], the source row naming each without
 * the address.
 */
export function readPersons({ container = 'niord-db', db = 'niord_test', user = 'niord' } = {}) {
  const lists = scrubLists();
  const columns = JSON.parse(
    psqlOn(
      container,
      db,
      `SELECT json_agg(json_build_array(c.table_name, c.column_name) ORDER BY 1)
       FROM information_schema.columns c
       JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
       WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
         AND c.data_type IN ('text', 'character varying')
         AND c.table_name NOT IN ('databasechangelog', 'databasechangeloglock');`,
      user,
    ),
  );
  const pattern = shapePattern(lists.shape).replaceAll("'", "''");
  // scrub.sql's g_shape_seen: every match of the form in a text column, before any step rewrites one
  const seen = columns
    .map(
      ([t, c]) =>
        `SELECT lower(m[1]) AS a FROM public."${t}" x, regexp_matches(x."${c}", '${pattern}', 'gi') m ` +
        `WHERE x."${c}" LIKE '%@%'`,
    )
    .join('\n    UNION\n    ');
  // scrub.sql's g_mail_seen: every address in a text column or among the search words, before any step rewrites one
  const address = ADDRESS_PATTERN.replaceAll("'", "''");
  const seenAll = [
    ...columns.map(
      ([t, c]) =>
        `SELECT lower(m[1]) AS a FROM public."${t}" x, regexp_matches(x."${c}", '${address}', 'gi') m ` +
        `WHERE x."${c}" LIKE '%@%'`,
    ),
    `SELECT lower(m[1]) AS a FROM public.message_search_text x, ` +
      `regexp_matches(array_to_string(tsvector_to_array(x.search_tsv), ' '), '${address}', 'gi') m ` +
      `WHERE x.search_tsv::text LIKE '%@%'`,
  ].join('\n    UNION\n    ');
  const rows = JSON.parse(
    psqlOn(
      container,
      db,
      `WITH persons AS (
         SELECT a, src, key, pri FROM (
           SELECT DISTINCT ON (lower(btrim(email))) lower(btrim(email)) AS a, 'user' AS src, id::bigint AS key, 1 AS pri
           FROM public."User" WHERE email LIKE '%@%' ORDER BY lower(btrim(email)), id) u
         UNION ALL
         SELECT a, 'comment', min(k), 2 FROM (
           SELECT lower(btrim(coalesce(substring(addr FROM '<([^>]*)>'), addr))) AS a,
                  (dense_rank() OVER (ORDER BY addr) - 1)::bigint AS k
           FROM (SELECT DISTINCT "emailAddresses" AS addr FROM public."Comment_emailAddresses"
                 WHERE "emailAddresses" IS NOT NULL) d) c
         GROUP BY a
         UNION ALL
         SELECT a, 'private', (dense_rank() OVER (ORDER BY a) - 1)::bigint, 3 FROM (
           SELECT DISTINCT lower(x[1]) AS a FROM public."MessagePartDesc" pd
           JOIN public."MessagePart" p ON p.id = pd.entity_id JOIN public."Message" m ON m.id = p.message_id,
                regexp_matches(pd.details, '([A-Za-z0-9._%+-]+@brk\\.dk)', 'g') x
           WHERE m."shortId" IN ${sqlList(lists.privateMail)}) pm),
       listed AS (
         SELECT * FROM persons WHERE a LIKE '%@%' AND split_part(a, '@', 1) NOT IN ${sqlList(lists.functional)}),
       seen AS (
         ${seen}),
       shape AS (
         SELECT a, 'shape' AS src, dense_rank() OVER (ORDER BY a)::bigint AS key, 4 AS pri FROM seen
         WHERE a NOT IN (SELECT a FROM listed) AND split_part(a, '@', 2) <> 'example.org'),
       seen_all AS (
         ${seenAll})
       SELECT coalesce(json_agg(json_build_array(a, src, key, pri)), '[]') FROM (
         SELECT a, src, key, pri FROM listed
         UNION ALL
         SELECT a, src, key, pri FROM shape
         UNION ALL
         SELECT a, 'deny', dense_rank() OVER (ORDER BY a)::bigint, 5 FROM seen_all
         WHERE a NOT IN (SELECT a FROM listed) AND a NOT IN (SELECT a FROM shape)
           AND NOT ${keptSql('a', lists.functional)}) x;`,
      user,
    ),
  );
  return rows;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const lists = scrubLists();
  const which = {
    functional: lists.functional,
    'private-mail': lists.privateMail,
    'private-contact': lists.privateContact,
    'role-words': ROLE_WORDS,
  }[process.argv[2]];
  if (!which) {
    console.error('usage: node scrub-lists.mjs functional|private-mail|private-contact|role-words');
    process.exit(2);
  }
  console.log(which.join('|'));
}
