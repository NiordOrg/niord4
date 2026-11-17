#!/usr/bin/env node
// seed-examples.mjs: the example rows of the user-guide pictures that must look fresh or are easiest made through the
// application, made once on the rig's copy (niord_guides) through the backend's REST interface, as the local realm's
// users editor, admin and user, the way the administration app makes them. Run it once after a fresh build, with the
// rig up and after the repository import (data/README.md, "After a backend start"):
//
//   node fe/guides-shots/data/seed-examples.mjs
//
// It makes, each only when the copy does not have it yet (found again by its "Guide example" title or name):
//   - two English drafts of niord-nw saved by editor ("Recently edited drafts" lists them), one with two event-date
//     intervals in one part (the backend reads one of them back while DateInterval's index is not written: the
//     script warns then);
//   - one verified English niord-nw warning that cancels a published niord-nw warning (a reference of type
//     CANCELLATION), so the publish dialog offers "Also cancel the referenced messages"; nothing is published;
//   - two comments on the first draft, by admin (acknowledged by editor) and by user (not acknowledged);
//   - a private tag of editor and a domain tag of niord-nw holding one published niord-nw warning;
//   - a saved filter of editor (drafts and verified messages in The Baltic Sea);
//   - one English NM draft with an image attachment (a plain sketch this script draws, no text) shown in its
//     description;
//   - the local user editor a recipient of two mailing lists with English names (navwarn-overview, navwarn-update),
//     joined as the Users tab's Mailing lists panel joins a user;
//   - in niord-nm, the retired series "Guide example retired weekly list" (activated, then retired with the reason
//     "Replaced by the weekly notices"), so the Publications page shows its Retired group;
//   - the active weekly series "Guide example weekly list" with no issue yet, whose first period opened at the
//     newest Wednesday 12:00 at least a week before the run: its page shows that period as Missing (with Create)
//     and the next as Upcoming; a run a week or more later moves the first period on;
//   - one amendment, reason "Corrected title of one notice.", of the weekly NtM issue the publication pictures show
//     as released in Niord (not imported; week 36 2026 in niord_test): the issue is rendered again from its
//     cut-off, no file uploaded, and its history shows the replaced document;
//   - one published NM notice added by hand (an INCLUDE override) to the open Accumulated NtM 2026;
// and, as its last step, has the backend generate the firing exercise messages from the firing schedule (what its
// schedule does in the background), printing how many are published.
// A second run finds every row and changes nothing. It reads the copy's database only to pick the published
// messages it refers to by the rig's own rule (guides.english_messages, which english.sql makes), through psql in the
// database container; everything it writes goes through the backend. Only a backend on this machine is accepted.
// No dependency beyond node's own modules.

import { execFileSync } from 'node:child_process';
import { deflateSync } from 'node:zlib';

const BACKEND_URL = (process.env.GUIDES_BACKEND_URL ?? 'http://localhost:8894').replace(/\/$/, '');
const KEYCLOAK_URL = (process.env.GUIDES_KEYCLOAK_URL ?? 'http://localhost:8080/auth').replace(
  /\/$/,
  '',
);
const DB = {
  container: process.env.GUIDES_DB_CONTAINER ?? 'niord-db',
  name: 'niord_guides',
  user: 'niord',
};
const MARK = 'Guide example';
const NW = 'niord-nw';
const NM = 'niord-nm';

for (const url of [BACKEND_URL, KEYCLOAK_URL]) {
  if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(url).hostname)) {
    throw new Error(`seed-examples.mjs writes to the local rig only; refusing ${url}.`);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// The backend, as a user of the local realm (password grant; the local users' password is their name)
// ---------------------------------------------------------------------------------------------------------------

const tokens = new Map();

async function token(user) {
  if (!tokens.has(user)) {
    const response = await fetch(`${KEYCLOAK_URL}/realms/niord/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'password',
        client_id: 'niord-web',
        username: user,
        password: user,
      }),
    });
    if (!response.ok) {
      throw new Error(
        `Keycloak refused the local user ${user}: ${response.status} ${await response.text()}`,
      );
    }
    tokens.set(user, (await response.json()).access_token);
  }
  return tokens.get(user);
}

/** One call to the backend as a user in a domain; the answer's JSON (or null when it has no body). */
async function api(user, method, path, { domain = NW, json, form, params } = {}) {
  const url = new URL(`${BACKEND_URL}${path}`);
  for (const [key, value] of Object.entries(params ?? {})) {
    for (const one of [value].flat()) url.searchParams.append(key, String(one));
  }
  const headers = { Authorization: `Bearer ${await token(user)}`, NiordDomain: domain };
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json;charset=UTF-8';
    body = typeof json === 'string' ? json : JSON.stringify(json);
  } else if (form) {
    body = form;
  }
  const response = await fetch(url, { method, headers, body });
  const text = await response.text();
  if (!response.ok) {
    const error = new Error(
      `${method} ${path} as ${user} answered ${response.status}: ${text.slice(0, 500)}`,
    );
    error.status = response.status;
    error.body = text;
    throw error;
  }
  return text ? JSON.parse(text) : null;
}

/** One query's rows from the copy (read only), through psql in the database container. */
function psqlRows(query) {
  const output = execFileSync(
    'docker',
    [
      'exec',
      DB.container,
      'psql',
      '-X',
      '-At',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      DB.user,
      '-d',
      DB.name,
      '-c',
      query,
    ],
    { encoding: 'utf8', timeout: 120_000, windowsHide: true, maxBuffer: 1 << 26 },
  );
  return output.split(/\r?\n/).filter(Boolean);
}

const made = [];
const found = [];
function note(list, what) {
  list.push(what);
  console.log(`${list === made ? 'made ' : 'found'}  ${what}`);
}

// ---------------------------------------------------------------------------------------------------------------
// What the examples refer to
// ---------------------------------------------------------------------------------------------------------------

/** The id of the area with this English name. */
async function areaId(name) {
  const areas = await api('editor', 'GET', '/rest/areas/search', {
    params: { name, lang: 'en', limit: 50 },
  });
  const area = areas.find((a) => (a.descs ?? []).some((d) => d.lang === 'en' && d.name === name));
  if (!area) throw new Error(`The copy has no area named ${name}.`);
  return area.id;
}

/** The published niord-nw warnings that are English throughout by the rig's rule, by uid. */
function englishPublishedNw() {
  return new Set(
    psqlRows(
      `SELECT uid FROM guides.english_messages('${NW}', 'PUBLISHED') WHERE english_ok AND NOT promulgation_danish`,
    ),
  );
}

/** A message search's results (no text query, so the database answers, not the index). */
async function search(user, domain, params) {
  const result = await api(user, 'GET', '/rest/messages/search', {
    domain,
    params: { domain, lang: 'en', maxSize: 1000, ...params },
  });
  return result.data ?? [];
}

function englishTitle(message) {
  return (message.descs ?? []).find((d) => d.lang === 'en')?.title ?? '';
}

// ---------------------------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------------------------

const point = (lat, lon) => ({
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [lon, lat] },
  properties: {},
});
const polygon = (corners) => ({
  type: 'Feature',
  geometry: {
    type: 'Polygon',
    coordinates: [[...corners, corners[0]].map(([lat, lon]) => [lon, lat])],
  },
  properties: {},
});
const interval = (from, to) => ({ allDay: false, fromDate: from, toDate: to });

/**
 * A new message from the backend's own blank one (what Editor > New message starts from), filled in and saved as the
 * user; English only, with an explicit title that carries the mark. The Audio promulgation is mandatory in niord-nw:
 * it gets the English text given, as the backend writes it.
 */
async function createMessage(user, domain, mainType, fill, audioText) {
  const message = await api(user, 'GET', '/rest/messages/new-message-template', {
    domain,
    params: { mainType, populate: true },
  });
  fill(message);
  for (const promulgation of message.promulgations ?? []) {
    if (promulgation.type?.typeId === 'audio') {
      promulgation.promulgate = Boolean(audioText);
      promulgation.text = audioText ?? null;
    }
  }
  return api(user, 'POST', '/rest/messages/message', { domain, json: message });
}

const DRAFT_ADRIFT = `${MARK}: buoy adrift south of Gedser`;
const DRAFT_EXERCISE = `${MARK}: naval exercise east of Gedser`;
const VERIFIED_CANCEL = `${MARK}: warning cancelled`;
const NM_SKETCH = `${MARK}: buoyage changed, see the sketch`;

async function nwDrafts(baltic) {
  const existing = await search('editor', NW, {
    status: ['DRAFT', 'VERIFIED'],
    messageSeries: 'dma-nw',
  });
  const byTitle = new Map(existing.map((m) => [englishTitle(m), m]));

  let adrift = byTitle.get(DRAFT_ADRIFT);
  if (adrift) {
    note(found, `draft "${DRAFT_ADRIFT}" (${adrift.id})`);
  } else {
    adrift = await createMessage(
      'editor',
      NW,
      'NW',
      (m) => {
        m.messageSeries = { seriesId: 'dma-nw' };
        m.type = 'COASTAL_WARNING';
        m.autoTitle = false;
        m.areas = [{ id: baltic }];
        m.descs = [{ lang: 'en', title: DRAFT_ADRIFT, vicinity: 'Gedser' }];
        m.parts = [
          {
            indexNo: 0,
            type: 'DETAILS',
            hideSubject: true,
            geometry: { type: 'FeatureCollection', features: [point(54.5, 12.0833)] },
            descs: [
              {
                lang: 'en',
                subject: 'Buoy adrift',
                details:
                  "<p>A yellow special mark buoy has been observed adrift in pos. 54° 30.0'N - 012° 05.0'E.</p>" +
                  '<p>Mariners are advised to keep well clear.</p>',
              },
            ],
          },
        ];
      },
      'A yellow special mark buoy has been observed adrift in position 54 degrees 30.0 min. North - 012 degrees ' +
        '05.0 min. East.\nMariners are advised to keep well clear.',
    );
    note(made, `draft "${DRAFT_ADRIFT}" (${adrift.id})`);
  }

  let exercise = byTitle.get(DRAFT_EXERCISE);
  if (exercise) {
    note(found, `draft "${DRAFT_EXERCISE}" (${exercise.id})`);
  } else {
    exercise = await createMessage(
      'editor',
      NW,
      'NW',
      (m) => {
        m.messageSeries = { seriesId: 'dma-nw' };
        m.type = 'COASTAL_WARNING';
        m.autoTitle = false;
        m.areas = [{ id: baltic }];
        m.descs = [{ lang: 'en', title: DRAFT_EXERCISE, vicinity: 'East of Gedser' }];
        m.parts = [
          {
            indexNo: 0,
            type: 'DETAILS',
            hideSubject: true,
            // Two event-date intervals in one part: two days of the same exercise
            eventDates: [
              interval('2026-11-24T08:00:00.000+00:00', '2026-11-24T16:00:00.000+00:00'),
              interval('2026-11-26T08:00:00.000+00:00', '2026-11-26T16:00:00.000+00:00'),
            ],
            geometry: {
              type: 'FeatureCollection',
              features: [
                polygon([
                  [54.8333, 12.8333],
                  [54.8333, 13.3333],
                  [54.5833, 13.3333],
                  [54.5833, 12.8333],
                ]),
              ],
            },
            descs: [
              {
                lang: 'en',
                subject: 'Naval exercise',
                details:
                  '<p>On 24 November 2026 and on 26 November 2026, from 0800 to 1600 UTC each day, a naval exercise ' +
                  "will take place in the area between pos. 54° 50.0'N - 012° 50.0'E, 54° 50.0'N - 013° 20.0'E, " +
                  "54° 35.0'N - 013° 20.0'E and 54° 35.0'N - 012° 50.0'E.</p>" +
                  '<p>Military vessels will be in the area listening on VHF channel 16. Mariners are requested to ' +
                  'pass with caution.</p>',
              },
            ],
          },
        ];
      },
      'On 24 November 2026 and on 26 November 2026, from 0800 to 1600 UTC each day, a naval exercise will take ' +
        'place in the area between positions 54 degrees 50.0 min. North - 012 degrees 50.0 min. East, 54 degrees ' +
        '50.0 min. North - 013 degrees 20.0 min. East, 54 degrees 35.0 min. North - 013 degrees 20.0 min. East and ' +
        '54 degrees 35.0 min. North - 012 degrees 50.0 min. East.\nMilitary vessels will be in the area listening ' +
        'on VHF channel 16. Mariners are requested to pass with caution.',
    );
    note(made, `draft "${DRAFT_EXERCISE}" (${exercise.id})`);
  }
  // On 2026-11-11 the backend stored both intervals with the same index (DateInterval maps its own writable indexNo
  // onto the part's @OrderColumn, which Hibernate 7 lets shadow the list order, as Feature's did), so it reads one
  // back: said here, so the picture that needs two is not taken on a copy that shows one.
  const read = await api(
    'editor',
    'GET',
    `/rest/messages/editable-message/${encodeURIComponent(exercise.id)}`,
    {
      params: { lang: 'en' },
    },
  );
  const intervals = read.parts?.[0]?.eventDates?.length ?? 0;
  if (intervals < 2) {
    console.warn(
      `warning: "${DRAFT_EXERCISE}" was saved with 2 event-date intervals in one part, and the backend reads back ` +
        `${intervals} (two DateInterval rows with the same indexNo).`,
    );
  }

  let cancelling = byTitle.get(VERIFIED_CANCEL);
  if (cancelling) {
    note(
      found,
      `${cancelling.status.toLowerCase()} warning "${VERIFIED_CANCEL}" (${cancelling.id})`,
    );
  } else {
    const target = await cancelTarget();
    cancelling = await createMessage(
      'editor',
      NW,
      'NW',
      (m) => {
        m.messageSeries = { seriesId: 'dma-nw' };
        m.type = 'COASTAL_WARNING';
        m.autoTitle = false;
        m.areas = (target.areas ?? []).slice(0, 1).map((a) => ({ id: a.id }));
        if (m.areas.length === 0) m.areas = [{ id: baltic }];
        m.descs = [{ lang: 'en', title: VERIFIED_CANCEL }];
        m.references = [{ messageId: target.shortId, type: 'CANCELLATION' }];
        m.parts = [
          {
            indexNo: 0,
            type: 'DETAILS',
            hideSubject: true,
            descs: [
              {
                lang: 'en',
                subject: 'Warning cancelled',
                details: `<p>Navigational warning ${target.shortId} is cancelled.</p>`,
              },
            ],
          },
        ];
      },
      `Navigational warning ${target.shortId} is cancelled.`,
    );
    note(made, `draft "${VERIFIED_CANCEL}" (${cancelling.id}), cancelling ${target.shortId}`);
  }
  if (cancelling.status === 'DRAFT') {
    cancelling = await api(
      'editor',
      'PUT',
      `/rest/messages/message/${encodeURIComponent(cancelling.id)}/status`,
      {
        json: 'VERIFIED',
      },
    );
    note(made, `status VERIFIED of "${VERIFIED_CANCEL}"`);
  }
  return { adrift, exercise, cancelling };
}

/**
 * The published warning the verified example cancels: a niord-nw warning of the series dma-nw, English throughout,
 * that no other message refers to yet; the last one of the search's order, so it is not the one the specs pick first.
 */
async function cancelTarget() {
  const english = englishPublishedNw();
  const referenced = new Set(psqlRows('SELECT DISTINCT "messageId" FROM public."Reference"'));
  const published = (
    await search('editor', NW, { status: 'PUBLISHED', messageSeries: 'dma-nw' })
  ).filter(
    (m) => english.has(m.id) && m.shortId && !referenced.has(m.shortId) && !referenced.has(m.id),
  );
  const target = published.at(-1);
  if (!target)
    throw new Error('The copy has no published dma-nw warning in English that nothing refers to.');
  return target;
}

/** The published warning the domain tag holds: the one the specs show first (support/backend.ts, publishedMessageId). */
async function taggedPublishedMessage() {
  const english = englishPublishedNw();
  const response = await fetch(`${BACKEND_URL}/rest/public/v1/messages?lang=en&domain=${NW}`);
  if (!response.ok) throw new Error(`The public API answered ${response.status}`);
  const messages = (await response.json()).filter((m) => english.has(m.id));
  const drawn = messages.find((m) =>
    (m.parts ?? []).some((p) => (p.geometry?.features?.length ?? 0) > 0),
  );
  const message = drawn ?? messages[0];
  if (!message)
    throw new Error(`The domain ${NW} has no published warning that is English throughout.`);
  return message;
}

// ---------------------------------------------------------------------------------------------------------------
// Comments, tags, saved filter
// ---------------------------------------------------------------------------------------------------------------

const COMMENT_ACKED = `${MARK}: please check the position against the latest chart before this is verified.`;
const COMMENT_OPEN = `${MARK}: the buoy's owner has been told; a cancellation may follow tomorrow.`;

async function comments(draft) {
  const path = `/rest/messages/message/${encodeURIComponent(draft.id)}`;
  const existing = await api('editor', 'GET', `${path}/comments`);
  const byText = new Map(existing.map((c) => [c.comment, c]));

  let acked = byText.get(COMMENT_ACKED);
  if (acked) {
    note(found, 'comment by admin');
  } else {
    acked = await api('admin', 'POST', `${path}/comment`, {
      json: { comment: COMMENT_ACKED, emailAddresses: [] },
    });
    note(made, 'comment by admin');
  }
  if (!acked.acknowledgedBy) {
    await api('editor', 'PUT', `${path}/comment/${acked.id}/ack`);
    note(made, 'acknowledgement of the comment by editor');
  }

  if (byText.has(COMMENT_OPEN)) {
    note(found, 'comment by user (not acknowledged)');
  } else {
    await api('user', 'POST', `${path}/comment`, {
      json: { comment: COMMENT_OPEN, emailAddresses: [] },
    });
    note(made, 'comment by user (not acknowledged)');
  }
}

const PRIVATE_TAG = `${MARK}: to read through`;
const DOMAIN_TAG = `${MARK}: buoys`;

async function tags() {
  const existing = await api('editor', 'GET', '/rest/tags/search', {
    params: { name: MARK, limit: 100 },
  });
  const tag = async (name, type) => {
    let found_ = existing.find((t) => t.name === name && t.type === type);
    if (found_) {
      note(found, `${type.toLowerCase()} tag "${name}"`);
    } else {
      found_ = await api('editor', 'POST', '/rest/tags/tag', {
        json: { name, type, locked: false },
      });
      note(made, `${type.toLowerCase()} tag "${name}"`);
    }
    return found_;
  };
  await tag(PRIVATE_TAG, 'PRIVATE');
  const domainTag = await tag(DOMAIN_TAG, 'DOMAIN');

  const message = await taggedPublishedMessage();
  const tagsOfMessage = await api(
    'editor',
    'GET',
    `/rest/tags/message/${encodeURIComponent(message.id)}`,
  );
  if (tagsOfMessage.some((t) => t.tagId === domainTag.tagId)) {
    note(found, `published ${message.shortId ?? message.id} in "${DOMAIN_TAG}"`);
  } else {
    await api(
      'editor',
      'PUT',
      `/rest/tags/tag/${encodeURIComponent(domainTag.tagId)}/add-messages`,
      {
        json: [message.id],
      },
    );
    note(made, `published ${message.shortId ?? message.id} in "${DOMAIN_TAG}"`);
  }
}

const FILTER = `${MARK}: Baltic drafts`;

async function savedFilter(baltic) {
  const filters = await api('editor', 'GET', '/rest/filters/all');
  if (filters.some((f) => f.name === FILTER)) {
    note(found, `saved filter "${FILTER}"`);
    return;
  }
  // The parameters as the Messages page writes them (messages-filter-params.serializer.ts)
  await api('editor', 'POST', '/rest/filters/filter', {
    json: { name: FILTER, parameters: `status=DRAFT&status=VERIFIED&area=${baltic}` },
  });
  note(made, `saved filter "${FILTER}"`);
}

// ---------------------------------------------------------------------------------------------------------------
// The NM draft with an image
// ---------------------------------------------------------------------------------------------------------------

/** CRC-32 of a PNG chunk's type and data. */
function crc32(bytes) {
  let crc = ~0;
  for (const byte of bytes) {
    crc ^= byte;
    for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return ~crc >>> 0;
}

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/**
 * A plain sketch of a harbour approach, no text: the sea, a coast with a pier, a dashed leading line and the pair of
 * lateral buoys that mark it (red to port, green to starboard), 360 x 240 pixels.
 */
function sketchPng() {
  const width = 360;
  const height = 240;
  const sea = [214, 234, 248];
  const land = [236, 226, 196];
  const coastLine = [120, 110, 90];
  const red = [200, 40, 40];
  const green = [30, 140, 60];
  const line = [70, 90, 120];
  const raw = Buffer.alloc((width * 3 + 1) * height);
  const set = (x, y, [r, g, b]) => {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    const at = y * (width * 3 + 1) + 1 + x * 3;
    raw[at] = r;
    raw[at + 1] = g;
    raw[at + 2] = b;
  };
  const coastX = (y) => 70 + 18 * Math.sin(y / 28);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) set(x, y, x < coastX(y) ? land : sea);
    for (let w = -1; w <= 1; w++) set(Math.round(coastX(y)) + w, y, coastLine);
  }
  // The pier, out from the coast
  for (let y = 112; y < 122; y++)
    for (let x = Math.round(coastX(y)); x < 150; x++) set(x, y, coastLine);
  // The leading line into the harbour, dashed
  for (let x = 150; x < width - 10; x++)
    if (Math.floor(x / 8) % 2 === 0) for (let w = 0; w < 2; w++) set(x, 132 + w, line);
  // The buoys either side of the line
  const disc = (cx, cy, r, colour) => {
    for (let y = cy - r; y <= cy + r; y++)
      for (let x = cx - r; x <= cx + r; x++)
        if ((x - cx) ** 2 + (y - cy) ** 2 <= r * r) set(x, y, colour);
  };
  disc(230, 112, 9, red);
  disc(230, 154, 9, green);
  disc(300, 108, 9, red);
  disc(300, 158, 9, green);

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.writeUInt8(8, 8); // bit depth
  header.writeUInt8(2, 9); // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

async function nmDraftWithImage(baltic) {
  const existing = await search('editor', NM, {
    status: ['DRAFT', 'VERIFIED'],
    messageSeries: 'dma-nm',
  });
  const found_ = existing.find((m) => englishTitle(m) === NM_SKETCH);
  if (found_) {
    note(found, `NM draft "${NM_SKETCH}" (${found_.id})`);
    return;
  }
  const message = await api('editor', 'GET', '/rest/messages/new-message-template', {
    domain: NM,
    params: { mainType: 'NM', populate: true },
  });
  // The attachment goes into the message's temporary folder, as the editor's upload does
  const form = new FormData();
  form.append('file', new Blob([sketchPng()], { type: 'image/png' }), 'guide-example-sketch.png');
  const [attachment] = await api(
    'editor',
    'POST',
    `/rest/messages/attachments/${message.editRepoPath}/${message.revision}`,
    { domain: NM, form },
  );
  if (!attachment?.path) throw new Error('The upload of the sketch came back without a path.');
  // The image picker's URL (the path's file name is already encoded by the backend; this one needs none)
  const src = `/rest/repo/file/${attachment.path}`;

  message.messageSeries = { seriesId: 'dma-nm' };
  message.type = 'TEMPORARY_NOTICE';
  message.autoTitle = false;
  message.areas = [{ id: baltic }];
  message.descs = [{ lang: 'en', title: NM_SKETCH, vicinity: 'Gedser' }];
  message.attachments = [
    { ...attachment, descs: [{ lang: 'en', caption: 'Sketch of the changed buoyage' }] },
  ];
  message.parts = [
    {
      indexNo: 0,
      type: 'DETAILS',
      geometry: { type: 'FeatureCollection', features: [point(54.5333, 11.95)] },
      descs: [
        {
          lang: 'en',
          subject: 'Buoyage changed',
          details:
            '<p>The buoyage of the approach has been changed: a new pair of lateral buoys marks the leading line ' +
            'into the harbour, as the sketch shows.</p>' +
            `<p><img src="${src}" alt="Sketch of the changed buoyage" style="width: 360px;"></p>`,
        },
      ],
    },
  ];
  const saved = await api('editor', 'POST', '/rest/messages/message', {
    domain: NM,
    json: message,
  });
  note(made, `NM draft "${NM_SKETCH}" (${saved.id}) with the attachment ${attachment.fileName}`);
}

// ---------------------------------------------------------------------------------------------------------------
// The local user editor on two mailing lists (the Users tab's Mailing lists panel shows Leave beside them)
// ---------------------------------------------------------------------------------------------------------------

/** Lists with English names; the panel joins a user by its username (PUT update-status, as user-editor.ts does). */
const EDITOR_LISTS = ['navwarn-overview', 'navwarn-update'];

async function editorMailingLists() {
  const all = await api('admin', 'GET', '/rest/mailing-lists/search', { params: { lang: 'en' } });
  const joined = new Set(
    (
      await api('admin', 'GET', '/rest/mailing-lists/search', { params: { username: 'editor' } })
    ).map((l) => l.mailingListId),
  );
  for (const id of EDITOR_LISTS) {
    const list = all.find((l) => l.mailingListId === id);
    if (!list) throw new Error(`The copy has no mailing list ${id}.`);
    const name = (list.descs ?? []).find((d) => d.lang === 'en')?.name ?? id;
    if (joined.has(id)) {
      note(found, `editor on the mailing list "${name}"`);
      continue;
    }
    await api(
      'admin',
      'PUT',
      `/rest/mailing-lists/mailing-list/${encodeURIComponent(id)}/update-status`,
      {
        json: { user: { username: 'editor' }, recipient: true },
      },
    );
    note(made, `editor on the mailing list "${name}"`);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Publications (niord-nm): a retired series, a weekly series with a missing period, an amended issue, and a message
// added by hand to the open compilation
// ---------------------------------------------------------------------------------------------------------------

const ZONE = 'Europe/Copenhagen';
const RETIRED_SERIES = 'guide-example-retired-weekly';
const RETIRED_NAME = `${MARK} retired weekly list`;
const RETIRED_REASON = 'Replaced by the weekly notices';
const WEEKLY_SERIES = 'guide-example-weekly';
const WEEKLY_NAME = `${MARK} weekly list`;
const AMEND_REASON = 'Corrected title of one notice.';
const OVERRIDE_REASON = `${MARK}: in force all year; published before the first weekly issue of the year.`;

/** The instant a wall-clock time in a zone stands for (the offset read from Intl, so summer time is right). */
function zonedInstant(year, month, day, hour, minute, zone) {
  const guess = Date.UTC(year, month, day, hour, minute);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
      .formatToParts(new Date(guess))
      .map((p) => [p.type, Number(p.value)]),
  );
  const shown = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute);
  return new Date(guess - (shown - guess));
}

/**
 * The weekly series' first period opens at the newest Wednesday 12:00 (the weekly NtM's nominal cut-off, in the
 * domain's zone) that lies at least a week back: that period has closed with no issue (a Missing row) and the next
 * one has not (the Upcoming row). Measured from the moment the script runs, as the backend measures it.
 */
function weeklyAnchor(now = new Date()) {
  const latest = now.getTime() - 7 * 24 * 3600_000;
  for (let back = 0; back < 9; back++) {
    const day = new Date(latest - back * 24 * 3600_000);
    const local = new Intl.DateTimeFormat('en-GB', {
      timeZone: ZONE,
      weekday: 'short',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
    }).formatToParts(day);
    const get = (type) => local.find((p) => p.type === type).value;
    if (get('weekday') !== 'Wed') continue;
    const anchor = zonedInstant(
      Number(get('year')),
      Number(get('month')) - 1,
      Number(get('day')),
      12,
      0,
      ZONE,
    );
    if (anchor.getTime() <= latest) return anchor;
  }
  throw new Error('No Wednesday found in the nine days before a week ago.');
}

async function seriesById() {
  const all = await api('admin', 'GET', '/rest/publication-series/search-details', { domain: NM });
  return new Map(all.map((s) => [s.seriesId, s]));
}

/** A new weekly series of niord-nm in English only, as Copy series makes one from the weekly NtM. */
async function createWeeklySeries(seriesId, name, firstIssueStartsAt) {
  const template = await api(
    'admin',
    'GET',
    '/rest/publication-series/copy-series-template/weekly-ntm',
    { domain: NM },
  );
  delete template.version;
  return api('admin', 'POST', '/rest/publication-series/series', {
    domain: NM,
    json: {
      ...template,
      seriesId,
      domainId: NM,
      languages: ['en'],
      descs: [
        {
          lang: 'en',
          name,
          nameSuggestionPattern: `${name}, week \${week} - \${year}`,
          fileNamePattern: `${seriesId}-\${week}-\${year}.pdf`,
        },
      ],
      firstIssueStartsAt: firstIssueStartsAt.toISOString(),
    },
  });
}

async function setSeriesStatus(seriesId, status, reason) {
  return api(
    'admin',
    'PUT',
    `/rest/publication-series/series/${encodeURIComponent(seriesId)}/status`,
    {
      domain: NM,
      json: JSON.stringify(status),
      params: reason ? { reason } : {},
    },
  );
}

async function retiredSeries(existing) {
  let series = existing.get(RETIRED_SERIES);
  if (series?.status === 'RETIRED') {
    note(found, `retired series "${RETIRED_NAME}"`);
    return;
  }
  if (!series) {
    series = await createWeeklySeries(
      RETIRED_SERIES,
      RETIRED_NAME,
      new Date('2025-01-01T11:00:00.000Z'),
    );
  }
  // Activated first, then retired with its reason, as a desk would have run it
  if (series.status === 'DRAFT') await setSeriesStatus(RETIRED_SERIES, 'ACTIVE');
  await setSeriesStatus(RETIRED_SERIES, 'RETIRED', RETIRED_REASON);
  note(made, `retired series "${RETIRED_NAME}" ("${RETIRED_REASON}")`);
}

async function weeklySeriesWithMissingPeriod(existing) {
  const anchor = weeklyAnchor();
  let series = existing.get(WEEKLY_SERIES);
  if (!series) {
    series = await createWeeklySeries(WEEKLY_SERIES, WEEKLY_NAME, anchor);
    note(made, `weekly series "${WEEKLY_NAME}", first period from ${anchor.toISOString()}`);
  } else if (
    series.status !== 'DRAFT' &&
    new Date(series.firstIssueStartsAt).getTime() === anchor.getTime()
  ) {
    note(found, `weekly series "${WEEKLY_NAME}", first period from ${anchor.toISOString()}`);
  } else {
    // Run again a week or more later: the first period moves on, so the series page still shows one Missing row
    const editable = await api(
      'admin',
      'GET',
      `/rest/publication-series/editable-series/${encodeURIComponent(WEEKLY_SERIES)}`,
      { domain: NM },
    );
    series = await api(
      'admin',
      'PUT',
      `/rest/publication-series/series/${encodeURIComponent(WEEKLY_SERIES)}`,
      {
        domain: NM,
        json: { ...editable, firstIssueStartsAt: anchor.toISOString() },
      },
    );
    note(made, `first period of "${WEEKLY_NAME}" moved to ${anchor.toISOString()}`);
  }
  if (series.status === 'DRAFT') {
    await setSeriesStatus(WEEKLY_SERIES, 'ACTIVE');
    note(made, `"${WEEKLY_NAME}" activated`);
  }
  const rows = (
    await api(
      'admin',
      'GET',
      `/rest/publication-issues/series/${encodeURIComponent(WEEKLY_SERIES)}`,
      {
        domain: NM,
      },
    )
  ).data.map((r) => r.computedStatus ?? r.status);
  console.log(`       "${WEEKLY_NAME}" lists: ${rows.join(', ') || 'nothing'}`);
}

async function issuesOf(seriesId) {
  return (
    await api('admin', 'GET', `/rest/publication-issues/series/${encodeURIComponent(seriesId)}`, {
      domain: NM,
    })
  ).data;
}

/**
 * One published issue of the weekly NtM amended once: the issue the publication pictures show as released in Niord
 * (support/admin-publications.ts, RELEASED_HERE: not imported, five or more members, the latest; week 36 2026 in
 * niord_test, 9 members, which the amendment keeps), found again by the amendment's reason.
 */
async function amendedIssue() {
  const [done] = psqlRows(
    `SELECT i."publicId" FROM public."IssueAuditEntry" a JOIN public."PublicationIssue" i ON i.id = a.issue_id ` +
      `JOIN public."PublicationSeries" s ON s.id = i.series_id WHERE s."seriesId" = 'weekly-ntm' ` +
      `AND a.action = 'AMENDED' AND a.reason = '${AMEND_REASON}' ORDER BY a.id LIMIT 1`,
  );
  const [publicId] = done
    ? [done]
    : psqlRows(
        `SELECT i."publicId" FROM public."PublicationIssue" i JOIN public."PublicationSeries" s ` +
          `ON s.id = i.series_id WHERE s."seriesId" = 'weekly-ntm' AND i.status = 'PUBLISHED' ` +
          `AND i."legacyPublicationId" IS NULL AND i."memberCount" >= 5 ` +
          `ORDER BY i."intervalTo" DESC NULLS LAST, i.id DESC LIMIT 1`,
      );
  if (!publicId) throw new Error('The weekly NtM has no published issue released in Niord.');
  const issue = (await issuesOf('weekly-ntm')).find((i) => i.publicId === publicId);
  const label = `"${(issue?.descs ?? []).find((d) => d.lang === 'en')?.name ?? publicId}"`;
  if (done) {
    note(found, `amendment of ${label}`);
    return;
  }
  // A query-backed issue is rendered again from its frozen cut-off: no file is uploaded. The resolution's warnings
  // are acknowledged as the Amend dialog has the admin acknowledge them.
  const path = `/rest/publication-issues/issue/${encodeURIComponent(publicId)}/amend`;
  const amend = (acknowledgedWarnings) =>
    api('admin', 'PUT', path, {
      domain: NM,
      json: { reason: AMEND_REASON, acknowledgedWarnings, version: issue.version },
    });
  let result;
  try {
    result = await amend([]);
  } catch (error) {
    const warnings = error.status === 409 ? JSON.parse(error.body).unacknowledgedWarnings : null;
    if (!warnings?.length) throw error;
    result = await amend(warnings);
  }
  note(
    made,
    `amendment of ${label} ("${AMEND_REASON}", ${result.archivePaths?.length ?? 0} document(s) replaced, ` +
      `${result.memberCount} members)`,
  );
}

/**
 * One published NM notice added by hand to the open Accumulated NtM (an INCLUDE override): an English notice of
 * dma-nm published before 2026 and still in force, so the year's weekly issues do not hold it.
 */
async function addedByHand() {
  const open = (await issuesOf('accumulated-yearly-ntm')).filter((i) => i.status === 'OPEN');
  const issue = open.at(0);
  if (!issue) throw new Error('Accumulated Yearly NtM has no open issue.');
  const label = `"${(issue.descs ?? []).find((d) => d.lang === 'en')?.name ?? issue.publicId}"`;
  const path = `/rest/publication-issues/issue/${encodeURIComponent(issue.publicId)}`;
  const decisions = await api('admin', 'GET', `${path}/overrides`, {
    domain: NM,
    params: { lang: 'en' },
  });
  const standing = decisions.find((d) => (d.reason ?? '').startsWith(MARK));
  if (standing) {
    note(found, `${standing.messageId ?? standing.messageUid} added by hand to ${label}`);
    return;
  }
  const candidates = psqlRows(
    `SELECT m.uid || '|' || coalesce(m."shortId", '') FROM guides.english_messages('${NM}', 'PUBLISHED') e ` +
      `JOIN public."Message" m ON m.uid = e.uid ` +
      `JOIN public."MessageSeries" s ON s.id = m."messageSeries_id" ` +
      `WHERE e.english_ok AND NOT e.promulgation_danish AND s."seriesId" = 'dma-nm' ` +
      `AND m."publishDateFrom" < timestamp '2025-12-31 23:00' ORDER BY m."publishDateFrom" DESC LIMIT 10`,
  ).map((row) => row.split('|'));
  for (const [uid, shortId] of candidates) {
    try {
      await api('admin', 'PUT', `${path}/overrides/include`, {
        domain: NM,
        json: { messageUid: uid, reason: OVERRIDE_REASON, version: issue.version },
      });
    } catch (error) {
      if (String(error.message).includes('OVERRIDE_ALREADY_A_MEMBER')) continue;
      throw error;
    }
    note(made, `${shortId || uid} added by hand to ${label}`);
    return;
  }
  throw new Error(`No published NM notice could be added by hand to ${label}.`);
}

// ---------------------------------------------------------------------------------------------------------------
// The firing exercises (last): generated now, whatever the hour, as the backend's schedule would
// ---------------------------------------------------------------------------------------------------------------

async function firingExercises() {
  await api('editor', 'PUT', '/rest/firing-schedules/update-firing-exercises', {
    domain: 'niord-fa',
  });
  const response = await fetch(`${BACKEND_URL}/rest/public/v1/messages?lang=en&domain=niord-fe`);
  if (!response.ok) throw new Error(`The public API answered ${response.status}`);
  const published = await response.json();
  console.log(
    `firing exercises updated: ${published.length} firing exercise messages published (niord-fe).`,
  );
}

// ---------------------------------------------------------------------------------------------------------------

const baltic = await areaId('The Baltic Sea');
const { adrift } = await nwDrafts(baltic);
await comments(adrift);
await tags();
await savedFilter(baltic);
await nmDraftWithImage(baltic);
await editorMailingLists();
const existingSeries = await seriesById();
await retiredSeries(existingSeries);
await weeklySeriesWithMissingPeriod(existingSeries);
await amendedIssue();
await addedByHand();
await firingExercises();
console.log(`seed-examples: ${made.length} made, ${found.length} found.`);
