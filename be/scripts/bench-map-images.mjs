#!/usr/bin/env node
/*
 * Map-image benchmark: stand-in map servers, and the clients that time what a user of Niord waits for
 * around the message map images.
 *
 * WHY. The map image of a message (GET /rest/message-map-image/{uid}.png: the generated image itself,
 * served from the cache tree in one request; a 307 to the file in the repository for a custom thumbnail;
 * or the placeholder image itself when there is none) is drawn by the backend, and it sits
 * on paths that must never get slower: the PDF with map thumbnails (one image per message, fetched by the
 * PDF engine from the backend itself), the Details, Grid and Selected views of the administration app (a
 * page of up to 200 images requested at once), the tweet on publish, and - through the database
 * connection a generation holds - message GET, search and save.
 * This script measures those paths the same way before and after a change to the image renderer, and
 * against a deployed system over the real network. be/docs/map-image-benchmark.md says how to run it,
 * what passes, and holds the reference numbers of the test environment.
 *
 * SUBCOMMANDS
 *   stand-ins  Serves a static-map server (/staticmap.php, what the previous renderer asks for) and an
 *              OSM-like tile server (/osm/{z}/{x}/{y}.png) on loopback, with a latency drawn per request
 *              from one lognormal distribution, and counts every hit (GET /__stats on either port). With
 *              --static-map-model fetching the static-map server fetches the tiles under its window from
 *              the tile server and keeps them, as a real one does, instead of answering after one draw.
 *   local      Times a backend on this machine whose map source points at the stand-ins: cases 1, 2a, 2b,
 *              2c, 3, 3b, 4, 4b, 5, 6, 6b, 6c, 6d and 7 (see --help). It deletes generated map images
 *              under --cache (the cache tree, <niord.home>/cache: each message's images sit in its folder
 *              there, the message's repoPath) to make them cold, and case 5 saves a scratch message, so it
 *              is for a benchmark copy of the data only.
 *   remote     Times a deployed system with GET requests only (cases R1 to R8); it never writes there. The
 *              one other request it sends is the Keycloak token POST.
 *   compare    Applies the pass criteria to the result files of an old and a new run.
 *   selftest   Starts the stand-ins on free ports and checks their answers, the map-image client
 *              against a stand-in of the endpoint's answers, and the message reads against a stand-in of
 *              their endpoints; no backend needed.
 *
 * MESSAGE READS. Cases 6b, 6c and 6d (local) and R6, R7 and R8 (remote) time the message reads of
 * be/docs/message-read-benchmark.md: the administration app's list search with the owner's filter (NW and NM,
 * the default page of 100), its map view (1,000), and the public site's list and single message without a
 * login. They run only when --cases names them, read --searches and --message-gets as cases 6 and R5 do, and
 * need no image: a run of read cases alone (6 and R5 among them) fetches no map image and needs no stand-ins.
 *
 * USAGE (every flag: --help)
 *   node be/scripts/bench-map-images.mjs stand-ins --latency 250
 *   NIORD_USER=<user> NIORD_PASS=<password> node be/scripts/bench-map-images.mjs local --variant old \
 *        --domain niord-nm --filter '<message search query>' --cache <niord.home>/cache \
 *        --stand-ins http://127.0.0.1:9101 --out old-1.json
 *   NIORD_USER=<user> NIORD_PASS=<password> node be/scripts/bench-map-images.mjs remote \
 *        --base https://<host> --keycloak https://<keycloak host>/auth --domain niord-nm \
 *        --filter '<message search query>' --out remote.json
 *   node be/scripts/bench-map-images.mjs compare --old old-1.json,old-2.json --new new-1.json,new-2.json
 *
 * CREDENTIALS come from the environment only: NIORD_USER and NIORD_PASS, used for a password grant on a
 * public Keycloak client. They are never taken from a flag or a file, never printed, and never written to
 * the result file.
 *
 * Node built-ins only, one process: on the Windows workstations every process start costs a few hundred
 * milliseconds, so the stand-ins and the client never shell out per request.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';

const TOOL = 'bench-map-images';
const RESULT_FORMAT = 1;
const MAX_CONCURRENCY = 50;
// The placeholder: the endpoint answers it itself, a 200 with this header (value "placeholder"); a backend
// before that answer redirected to the static file this pattern names.
const PLACEHOLDER_HEADER = 'x-niord-map-image';
const PLACEHOLDER_FILE = /map_image_placeholder\.png$/;
// A generated map image: map_<size>.png, or map_<size>_<version>.png when the name carries a version.
const MAP_IMAGE_FILE = /^map_\d{1,4}(_[0-9a-f]{1,32})?\.png$/;
const VERSIONED_MAP_IMAGE_FILE = /^map_(\d{1,4})_[0-9a-f]{1,32}\.png$/;
const IMAGE_ACCEPT = 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8';
const LOCAL_CASES = ['1', '2a', '2b', '2c', '3', '3b', '4', '4b', '5', '6', '6b', '6c', '6d', '7'];
const LIST_STATES = ['cached', 'cold', 'coldTiles', 'stale'];
const REMOTE_CASES = ['R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7', 'R8'];
// What a run does without --cases: the map-image benchmark's cases; the new message reads only when named.
const DEFAULT_LOCAL_CASES = ['1', '2a', '2b', '3', '3b', '4', '4b', '5', '6', '7'];
const DEFAULT_REMOTE_CASES = ['R1', 'R2', 'R3', 'R4', 'R5'];
// The message reads: they fetch no map image. Those of SETLESS_CASES need no set of messages either.
const READ_CASES = ['6', '6b', '6c', '6d', 'R5', 'R6', 'R7', 'R8'];
const SETLESS_CASES = ['6b', '6c', '6d', 'R6', 'R7', 'R8'];

const USAGE = `Usage: node be/scripts/bench-map-images.mjs <subcommand> [flags]

stand-ins   --static-port 9101 --tile-port 9102 --host 127.0.0.1
            --latency 250       median latency per request, ms (0 = answer at once)
            --sigma 0.5         spread of the one lognormal latency distribution
            --seed 1            seed of the latency sequence
            --static-map-model static|fetching
                                static (default): a static map waits one latency draw, as if its
                                server held every tile; fetching: the static-map stand-in fetches the
                                tiles under its window it does not hold from the tile stand-in (each a
                                latency draw), keeps them, and answers after a LAN hop
            --lan-latency 2     the LAN hop of the fetching model, ms
            --quiet             do not log the first request of each User-Agent

local       --variant old|new   (required) which renderer the backend runs
            --base http://127.0.0.1:8888  --keycloak http://localhost:8080/auth
            --realm niord  --client niord-web  --domain <domainId>  --lang en
            --filter <query>    message search that defines the bench set
                                (default status=PUBLISHED, plus domain=<domain> when given)
            --count 50          messages with a geometry taken from it, in search order
            --set <file>        reuse the set of an earlier result file (required for 2c)
            --pool-filter <q>   the wider set of cases 4b and 7 (default --filter)
            --pool-size 200
            --list-filter <q>   the list search of cases 6 and 7 (default --filter)
            --stand-ins http://127.0.0.1:9101    where /__stats is read (hit-count checks)
            --cache <niord.home>/cache           generated images are deleted under it, in each
                                message's folder (its repoPath); --repo is refused
            --tile-cache <niord.home>/tile-cache deleted for a cold tile cache (new)
            --cases 1,2a,2b,3,3b,4,4b,5,6,7      case 2c runs on its own
                                6b, 6c, 6d only when named (the message reads, below)
            --warmup 1  --rounds 20  --cold-rounds 3  --pdf-runs 10  --pdf-cold-runs 5
            --concurrency 6,50  --list-rounds 3  --message-gets 200  --searches 30
            --states cached,cold,coldTiles,stale  the states of cases 4 and 4b (default all)
            --scratch-id <uid>  case 5 saves this message --saves times (default 30)
            --legacy-from <old niord.home>/cache case 3b copies map_<size>.png from here
                                (without it, 3b renames the new files to map_<size>.png)
            --settle 5000       ms left for background regeneration between batches
            --load cold|stale  --load-window 15000                   case 7
            --server-cmd "<java ... -jar x.jar>"  case 2c starts and stops the backend
            --restarts 5  --ready-log <regex>  --ready-timeout 300000  --server-log <file>
            --pause 500  --out <file>

remote      --base https://<host>  --keycloak https://<keycloak host>/auth   (both required)
            --realm niord  --client niord-web  --domain <domainId>  --lang en
            --filter <query>  --count 50  --set <file>  --list-filter <query>
            --cases R1,R2,R3,R4,R5  (R6, R7, R8 only when named)
            --rounds 5  --warmup 1  --concurrency 6,50
            --list-rounds 3  --pdf-runs 5  --message-gets 200  --searches 30
            --pause 1000  --out <file>

message reads (be/docs/message-read-benchmark.md), one request at a time; local / remote:
            6  / R5  GET /rest/messages/message/{id} --message-gets times, the list
                     search of --list-filter (maxSize 200) --searches times
            6b / R6  GET /rest/messages/search?mainType=NW&mainType=NM&lang=<lang>
                     (the default page of 100) --searches times
            6c / R7  the same as the map view sends it: maxSize=1000, viewMode=map,
                     includeNoPos=true; --searches times
            6d / R8  no login: GET /rest/public/v1/messages --searches times, then
                     GET /rest/public/v1/message/{id} --message-gets times over the
                     first --count ids of that list (both lang=<lang>&externalize=false)
            The first answers are not counted: local 10 message GETs and 3 lists,
            remote 5 and 2; --warmup and --rounds are not read. A run of these
            cases alone fetches no map image.

compare     --old a.json[,c.json]  --new b.json[,d.json]
            --tolerance 0.05  --floor-ms 2  --small-ms 40

selftest    starts the stand-ins on free ports and checks their answers, the
            map-image client against a stand-in of the endpoint's answers, and the
            message reads against a stand-in of their endpoints

Credentials: NIORD_USER and NIORD_PASS in the environment, nowhere else.`;

const OPTIONS = {
    help: { type: 'boolean' },
    quiet: { type: 'boolean' },
    'static-port': { type: 'string' },
    'tile-port': { type: 'string' },
    host: { type: 'string' },
    latency: { type: 'string' },
    sigma: { type: 'string' },
    seed: { type: 'string' },
    variant: { type: 'string' },
    base: { type: 'string' },
    keycloak: { type: 'string' },
    realm: { type: 'string' },
    client: { type: 'string' },
    domain: { type: 'string' },
    lang: { type: 'string' },
    filter: { type: 'string' },
    'list-filter': { type: 'string' },
    'pool-filter': { type: 'string' },
    'pool-size': { type: 'string' },
    count: { type: 'string' },
    set: { type: 'string' },
    'stand-ins': { type: 'string' },
    cache: { type: 'string' },
    // Refused (repoFlagRefusal): kept here so that an old command line gets that message, not "unknown option"
    repo: { type: 'string' },
    'tile-cache': { type: 'string' },
    'static-map-model': { type: 'string' },
    'lan-latency': { type: 'string' },
    states: { type: 'string' },
    cases: { type: 'string' },
    warmup: { type: 'string' },
    rounds: { type: 'string' },
    'cold-rounds': { type: 'string' },
    'pdf-runs': { type: 'string' },
    'pdf-cold-runs': { type: 'string' },
    concurrency: { type: 'string' },
    'list-rounds': { type: 'string' },
    'message-gets': { type: 'string' },
    searches: { type: 'string' },
    'scratch-id': { type: 'string' },
    saves: { type: 'string' },
    'legacy-from': { type: 'string' },
    settle: { type: 'string' },
    load: { type: 'string' },
    'load-window': { type: 'string' },
    'server-cmd': { type: 'string' },
    restarts: { type: 'string' },
    'ready-log': { type: 'string' },
    'ready-timeout': { type: 'string' },
    'server-log': { type: 'string' },
    pause: { type: 'string' },
    out: { type: 'string' },
    old: { type: 'string' },
    new: { type: 'string' },
    tolerance: { type: 'string' },
    'floor-ms': { type: 'string' },
    'small-ms': { type: 'string' },
};

// ------------------------------------------------------------------------------------ small helpers

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const round1 = (x) => (Number.isFinite(x) ? Math.round(x * 10) / 10 : x);

function fail(message) {
    console.error(`${TOOL}: ${message}`);
    process.exit(2);
}

function stamp(date = new Date()) {
    return date.toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
}

function csv(value) {
    return String(value ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

function num(flags, name, fallback, min = 0) {
    const raw = flags[name];
    if (raw === undefined) {
        return fallback;
    }
    const n = Number(raw);
    if (!Number.isFinite(n) || n < min) {
        fail(`--${name} must be a number >= ${min}`);
    }
    return n;
}

function concurrencies(flags) {
    return csv(flags.concurrency ?? '6,50').map((c) => {
        const n = Number(c);
        if (!Number.isInteger(n) || n < 1) {
            fail('--concurrency takes whole numbers >= 1');
        }
        if (n > MAX_CONCURRENCY) {
            console.warn(`concurrency ${n} capped at ${MAX_CONCURRENCY}`);
        }
        return Math.min(n, MAX_CONCURRENCY);
    });
}

// ----------------------------------------------------------------------------------------- statistics

function percentile(sorted, p) {
    if (sorted.length === 0) {
        return null;
    }
    const i = (sorted.length - 1) * p;
    const lo = Math.floor(i);
    const hi = Math.ceil(i);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
}

function summary(values) {
    const v = values.filter(Number.isFinite).sort((a, b) => a - b);
    if (v.length === 0) {
        return { n: 0 };
    }
    const mean = v.reduce((s, x) => s + x, 0) / v.length;
    return {
        n: v.length,
        min: round1(v[0]),
        median: round1(percentile(v, 0.5)),
        p95: round1(percentile(v, 0.95)),
        max: round1(v[v.length - 1]),
        mean: round1(mean),
    };
}

/** A measured quantity: its raw samples (kept, so runs can be pooled later) and their summary. */
function metric(values, unit = 'ms') {
    const samples = values.filter(Number.isFinite).map(round1);
    return { unit, samples, summary: summary(samples) };
}

/** Seeded PRNG (mulberry32), so a latency sequence can be repeated. */
function mulberry32(seed) {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

/**
 * One lognormal distribution with the given median: every stand-in request, static map and tile alike,
 * draws its latency from it. A fixed latency would hide what matters when one image needs up to four
 * tiles - the slowest of them.
 */
function lognormal(medianMs, sigma, seed) {
    const rnd = mulberry32(seed);
    return () => {
        if (medianMs <= 0) {
            return 0;
        }
        let u = 0;
        while (u === 0) {
            u = rnd();
        }
        const z = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rnd());
        return medianMs * Math.exp(sigma * z);
    };
}

// ------------------------------------------------------------------------------ PNG, in plain Node

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const CRC_TABLE = (() => {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c >>> 0;
    }
    return table;
})();

function crc32(buf) {
    let c = 0xffffffff;
    for (const b of buf) {
        c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const typed = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(typed));
    return Buffer.concat([length, typed, crc]);
}

/** The scanlines of 8-bit RGB (3 channels) or RGBA (4) pixels, every row unfiltered. */
function pngRows(width, height, channels, pixels) {
    const stride = width * channels;
    const raw = Buffer.alloc((stride + 1) * height);
    for (let y = 0; y < height; y++) {
        pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
    }
    return raw;
}

function pngFile(width, height, channels, idat) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;
    ihdr[9] = channels === 4 ? 6 : 2;
    return Buffer.concat([PNG_SIGNATURE, pngChunk('IHDR', ihdr), pngChunk('IDAT', idat), pngChunk('IEND', Buffer.alloc(0))]);
}

function encodePng(width, height, channels, pixels) {
    return pngFile(width, height, channels, zlib.deflateSync(pngRows(width, height, channels, pixels), { level: 6 }));
}

/** The same, compressed on the libuv thread pool, so a stand-in keeps answering while it encodes. */
function encodePngAsync(width, height, channels, pixels) {
    return new Promise((resolve, reject) => {
        zlib.deflate(pngRows(width, height, channels, pixels), { level: 6 }, (e, idat) => (e ? reject(e) : resolve(pngFile(width, height, channels, idat))));
    });
}

function etagOf(body) {
    return `"${crypto.createHash('sha1').update(body).digest('hex').slice(0, 20)}"`;
}

/** Decodes what encodePng writes (the self-test reads its own stand-ins' answers back). */
function decodePng(buf) {
    if (!buf.subarray(0, 8).equals(PNG_SIGNATURE)) {
        throw new Error('not a PNG');
    }
    let offset = 8;
    let width = 0;
    let height = 0;
    let channels = 0;
    const idat = [];
    while (offset < buf.length) {
        const length = buf.readUInt32BE(offset);
        const type = buf.toString('latin1', offset + 4, offset + 8);
        const data = buf.subarray(offset + 8, offset + 8 + length);
        if (crc32(buf.subarray(offset + 4, offset + 8 + length)) !== buf.readUInt32BE(offset + 8 + length)) {
            throw new Error(`bad CRC in ${type}`);
        }
        if (type === 'IHDR') {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            channels = data[9] === 6 ? 4 : 3;
        } else if (type === 'IDAT') {
            idat.push(data);
        }
        offset += 12 + length;
    }
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    if (raw.length !== (stride + 1) * height) {
        throw new Error('pixel data has the wrong length');
    }
    const pixels = Buffer.alloc(stride * height);
    for (let y = 0; y < height; y++) {
        if (raw[y * (stride + 1)] !== 0) {
            throw new Error('unexpected row filter');
        }
        raw.copy(pixels, y * stride, y * (stride + 1) + 1, (y + 1) * (stride + 1));
    }
    return { width, height, channels, pixels };
}

// ------------------------------------------------------------------------------- the synthetic map
//
// A fixed set of tile templates, drawn once when the stand-ins start, stands for the map: tile
// (z, x, y) is template hash(z, x, y), and a static map is cut from the templates of the tiles under
// its window. The old-renderer image and the new-renderer image of a message therefore show the same
// pixels, and no request pays for drawing: a stand-in that drew per request would spend its one thread
// on it and slow the concurrent cases by its own cost. The templates carry sea, coast, land, roads,
// parks and building blocks, so their PNG sizes are in the range of real OSM tiles.

function hash2(ix, iy, seed) {
    let h = Math.imul(ix, 374761393) ^ Math.imul(iy, 668265263) ^ Math.imul(seed, 1442695041);
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h ^= h >>> 16;
    return (h >>> 0) / 4294967296;
}

function valueNoise(x, y, seed) {
    const ix = Math.floor(x);
    const iy = Math.floor(y);
    const fx = x - ix;
    const fy = y - iy;
    const sx = fx * fx * (3 - 2 * fx);
    const sy = fy * fy * (3 - 2 * fy);
    const a = hash2(ix, iy, seed);
    const b = hash2(ix + 1, iy, seed);
    const c = hash2(ix, iy + 1, seed);
    const d = hash2(ix + 1, iy + 1, seed);
    return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
}

function fbm(x, y, seed, octaves) {
    let sum = 0;
    let amplitude = 0.5;
    let frequency = 1;
    let norm = 0;
    for (let o = 0; o < octaves; o++) {
        sum += amplitude * valueNoise(x * frequency, y * frequency, seed + o * 101);
        norm += amplitude;
        amplitude *= 0.5;
        frequency *= 2.03;
    }
    return sum / norm;
}

const SEA = [170, 211, 223];
const COAST = [133, 172, 190];
const LAND = [242, 239, 233];
const PARK = [205, 235, 176];
const ROAD = [255, 255, 255];
const ROAD_EDGE = [206, 199, 187];
const BUILDING = [217, 208, 201];
const BUILDING_EDGE = [196, 182, 171];
const LABEL = [96, 96, 96];

function paint(pixels, i, gx, gy, zoom) {
    const scale = 1 / 2 ** zoom;
    const n = fbm((gx + 0.5) * scale * 0.5, (gy + 0.5) * scale * 0.5, 7, 9);
    let c = LAND;
    if (n < 0.47) {
        c = SEA;
    } else if (n < 0.476) {
        c = COAST;
    } else {
        const road = (valueNoise(gx / 97, gy / 97, 31) * 11) % 1;
        const lane = (valueNoise(gx / 37, gy / 37, 43) * 7) % 1;
        const bx = Math.floor(gx / 9);
        const by = Math.floor(gy / 9);
        const ox = gx - bx * 9;
        const oy = gy - by * 9;
        if (road < 0.035 || lane < 0.02) {
            c = ROAD;
        } else if (road < 0.06 || lane < 0.035) {
            c = ROAD_EDGE;
        } else if (valueNoise(gx / 61, gy / 61, 57) > 0.72) {
            c = PARK;
        } else if (hash2(bx, by, 3) < 0.45 && ox >= 1 && ox <= 7 && oy >= 1 && oy <= 6) {
            c = ox === 1 || ox === 7 || oy === 1 || oy === 6 ? BUILDING_EDGE : BUILDING;
        } else if (hash2(Math.floor(gx / 40), Math.floor(gy / 12), 5) < 0.12 && hash2(gx, gy, 9) < 0.45) {
            c = LABEL;
        }
    }
    pixels[i] = c[0];
    pixels[i + 1] = c[1];
    pixels[i + 2] = c[2];
}

function renderPixels(x0, y0, width, height, zoom) {
    const pixels = Buffer.alloc(width * height * 3);
    for (let j = 0; j < height; j++) {
        for (let i = 0; i < width; i++) {
            paint(pixels, (j * width + i) * 3, x0 + i, y0 + j, zoom);
        }
    }
    return pixels;
}

const TEMPLATE_COUNT = 48;
let tileTemplates = null;
let seamarkTemplates = null;

/** 48 tiles from across a synthetic coast at zoom 10, drawn once (about a second). */
function templates() {
    if (!tileTemplates) {
        tileTemplates = Array.from({ length: TEMPLATE_COUNT }, (_, k) => {
            const pixels = renderPixels((520 + ((k * 7) % 40)) * 256, (300 + ((k * 5) % 24)) * 256, 256, 256, 10);
            const png = encodePng(256, 256, 3, pixels);
            return { pixels, png, etag: etagOf(png) };
        });
    }
    return tileTemplates;
}

function templateFor(z, x, y) {
    return templates()[Math.floor(hash2(x, y, z + 1000) * TEMPLATE_COUNT)];
}

/** Mostly transparent overlay tiles with a few marks, like seamark tiles. */
function seamarks() {
    if (!seamarkTemplates) {
        seamarkTemplates = Array.from({ length: 8 }, (_, t) => {
            const pixels = Buffer.alloc(256 * 256 * 4);
            for (let k = 0; k < 4; k++) {
                const cx = 8 + Math.floor(hash2(t * 4 + k, 1, 2) * 240);
                const cy = 8 + Math.floor(hash2(t * 4 + k, 3, 4) * 240);
                for (let j = -3; j <= 3; j++) {
                    for (let i = -3; i <= 3; i++) {
                        const p = ((cy + j) * 256 + cx + i) * 4;
                        pixels[p] = k % 2 ? 0 : 220;
                        pixels[p + 1] = k % 2 ? 160 : 30;
                        pixels[p + 2] = 40;
                        pixels[p + 3] = 255;
                    }
                }
            }
            const png = encodePng(256, 256, 4, pixels);
            return { png, etag: etagOf(png) };
        });
    }
    return seamarkTemplates;
}

/** A window of the map at a zoom level, cut row by row from the templates of the tiles under it. */
function composeArea(x0, y0, width, height, zoom) {
    const out = Buffer.alloc(width * height * 3);
    for (let j = 0; j < height; j++) {
        const gy = y0 + j;
        const ty = Math.floor(gy / 256);
        const py = gy - ty * 256;
        for (let i = 0; i < width;) {
            const gx = x0 + i;
            const tx = Math.floor(gx / 256);
            const px = gx - tx * 256;
            const run = Math.min(256 - px, width - i);
            templateFor(zoom, tx, ty).pixels.copy(out, (j * width + i) * 3, (py * 256 + px) * 3, (py * 256 + px + run) * 3);
            i += run;
        }
    }
    return out;
}

function globalPixel(lat, lon, zoom) {
    const size = 256 * 2 ** zoom;
    const s = Math.sin((lat * Math.PI) / 180);
    return [((lon + 180) / 360) * size, (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * size];
}

/**
 * The centre parameter as the previous renderer writes it: "lat,lon" with '%f', which a JVM running
 * in a comma-decimal locale writes as "55,676000,12,568000". Both are accepted; the second is counted.
 */
function parseCentre(value) {
    const parts = String(value ?? '').split(',');
    if (parts.length === 2) {
        return { lat: Number(parts[0]), lon: Number(parts[1]), commaDecimals: false };
    }
    if (parts.length === 4) {
        return { lat: Number(`${parts[0]}.${parts[1]}`), lon: Number(`${parts[2]}.${parts[3]}`), commaDecimals: true };
    }
    return null;
}

// ---------------------------------------------------------------------------------------- stand-ins

const STATIC_MAP_MODELS = ['static', 'fetching'];
// The User-Agent of the static-map stand-in's own tile requests (fetching model), which are counted apart.
const STATIC_MAP_USER_AGENT = 'bench-static-map';

function newStandInStats(latency, sigma, seed, staticMapModel, lanLatency) {
    const route = () => ({ requests: 0, ok: 0, notModified: 0, bytes: 0 });
    return {
        startedAt: new Date().toISOString(),
        latency: { distribution: 'lognormal', medianMs: latency, sigma, seed },
        staticMap: { model: staticMapModel, lanLatencyMs: staticMapModel === 'fetching' ? lanLatency : null, tilesHeld: 0, tilesCleared: 0 },
        requests: 0,
        routes: { staticmap: route(), osm: route(), seamark: route(), staticmapTiles: route(), other: route() },
        commaDecimalCentres: 0,
        userAgents: {},
        paths: {},
        distinctPathsCapped: false,
    };
}

/**
 * Waits the given milliseconds more precisely than a timer: a timer fires on the operating system's tick
 * (about 15.6 ms on Windows), which would turn a 2 ms hop into 16. The last stretch is spent in
 * setImmediate turns, so the event loop keeps serving meanwhile.
 */
function waitPrecisely(ms) {
    const due = performance.now() + ms;
    return new Promise((resolve) => {
        const spin = () => (performance.now() >= due ? resolve() : setImmediate(spin));
        if (ms > 20) {
            setTimeout(spin, ms - 17);
        } else {
            spin();
        }
    });
}

/** The 256 px tiles (z/x/y, x wrapped around the antimeridian) a window of the map at a zoom level covers. */
function tilesUnder(x0, y0, width, height, zoom) {
    const n = 2 ** zoom;
    const keys = new Set();
    for (let ty = Math.floor(y0 / 256); ty <= Math.floor((y0 + height - 1) / 256); ty++) {
        if (ty < 0 || ty >= n) {
            continue;
        }
        for (let tx = Math.floor(x0 / 256); tx <= Math.floor((x0 + width - 1) / 256); tx++) {
            keys.add(`${zoom}/${((tx % n) + n) % n}/${ty}`);
        }
    }
    return [...keys];
}

/**
 * The stand-ins' one request handler. The static-map stand-in answers in one of two models:
 *
 * - 'static' (the default): every static map waits for one latency draw, as if the static-map server
 *   held every tile already. It charges the previous renderer one request per image and nothing for
 *   the tiles, which a real static-map server must itself fetch for a place it has not drawn.
 * - 'fetching': it behaves like a static-map server. It computes the 256 px tiles its window covers,
 *   fetches those it does not hold from the tile stand-in over HTTP (so each pays a draw of the same
 *   latency distribution the tile renderer pays, all of them at once, one fetch per tile for concurrent
 *   maps, at most 64 at a time as the tile renderer per host), keeps them in a tile cache of its own for
 *   as long as the stand-ins run, composes the map, and
 *   answers after a LAN hop (lanLatency, default 2 ms): the previous static-map server stood on the local
 *   network. The harness empties that cache (POST /__static-map/clear-tiles) in exactly the states in
 *   which it empties the tile renderer's tile cache. Its tile requests carry the User-Agent
 *   STATIC_MAP_USER_AGENT and are counted apart (route staticmapTiles).
 */
function createStandIns({ latency, sigma, seed, quiet, staticMapModel = 'static', lanLatency = 2 }) {
    const nextLatency = lognormal(latency, sigma, seed);
    const stats = newStandInStats(latency, sigma, seed, staticMapModel, lanLatency);
    const lastModified = new Date().toUTCString();
    const staticMaps = new Map();
    const fetching = staticMapModel === 'fetching';
    // The static-map stand-in's tile cache and its fetches in flight (fetching model)
    const staticTiles = new Set();
    const staticTileFetches = new Map();
    const tileAgents = { 'http:': new http.Agent({ keepAlive: true, maxSockets: 64 }) };
    let tileBase = null;

    function staticTile(key) {
        if (staticTiles.has(key)) {
            return null;
        }
        let pending = staticTileFetches.get(key);
        if (!pending) {
            pending = get(`${tileBase}/osm/${key}.png`, { agents: tileAgents, headers: { 'User-Agent': STATIC_MAP_USER_AGENT }, timeoutMs: 30_000 })
                .then((r) => {
                    if (r.status !== 200) {
                        throw new Error(`the tile ${key} answered ${r.status}`);
                    }
                    staticTiles.add(key);
                    stats.staticMap.tilesHeld = staticTiles.size;
                })
                .finally(() => staticTileFetches.delete(key));
            staticTileFetches.set(key, pending);
        }
        return pending;
    }

    function countPath(p) {
        if (p in stats.paths) {
            stats.paths[p]++;
        } else if (Object.keys(stats.paths).length < 5000) {
            stats.paths[p] = 1;
        } else {
            stats.distinctPathsCapped = true;
        }
    }

    const text = (status, message) => ({ status, headers: { 'Content-Type': 'text/plain' }, body: Buffer.from(`${message}\n`) });

    /**
     * The window the previous renderer asks for, centred on the given point: cut from the templates and
     * encoded off the event loop, once per window (the same message asks for the same window again).
     */
    function staticAnswer(url) {
        const centre = parseCentre(url.searchParams.get('center'));
        const zoom = Number(url.searchParams.get('zoom'));
        const size = /^(\d{1,4})x(\d{1,4})$/.exec(url.searchParams.get('size') ?? '');
        if (!centre || !Number.isFinite(centre.lat) || !Number.isFinite(centre.lon) || !Number.isInteger(zoom)
            || zoom < 0 || zoom > 19 || !size || Number(size[1]) > 2048 || Number(size[2]) > 2048) {
            return text(400, 'bad static-map query');
        }
        if (centre.commaDecimals) {
            stats.commaDecimalCentres++;
        }
        const width = Number(size[1]);
        const height = Number(size[2]);
        const [cx, cy] = globalPixel(centre.lat, centre.lon, zoom);
        const x0 = Math.round(cx - width / 2);
        const y0 = Math.round(cy - height / 2);
        const key = `${zoom}/${x0}/${y0}/${width}x${height}`;
        let png = staticMaps.get(key);
        if (!png) {
            png = encodePngAsync(width, height, 3, composeArea(x0, y0, width, height, zoom));
            staticMaps.set(key, png);
            if (staticMaps.size > 4000) {
                staticMaps.delete(staticMaps.keys().next().value);
            }
        }
        // The fetching model first gets the tiles under the window it does not hold
        const tiles = fetching
            ? Promise.all(tilesUnder(x0, y0, width, height, zoom).map(staticTile).filter(Boolean))
            : Promise.resolve();
        return tiles.then(() => png).then((body) => ({ status: 200, headers: { 'Content-Type': 'image/png', 'Content-Length': body.length }, body }));
    }

    function tileAnswer(req, kind, z, x, y, routeKind = kind) {
        const n = 2 ** z;
        if (z > 19 || x >= n || y >= n) {
            return text(404, 'no such tile');
        }
        const entry = kind === 'osm' ? templateFor(z, x, y) : seamarks()[Math.floor(hash2(x, y, z) * 8)];
        // What tile.openstreetmap.org sends: a cache lifetime, a strong ETag and a 304 on revalidation.
        const headers = {
            'Cache-Control': 'max-age=86400, stale-while-revalidate=604800, stale-if-error=604800',
            Expires: new Date(Date.now() + 86400e3).toUTCString(),
            'Last-Modified': lastModified,
            ETag: entry.etag,
            'Access-Control-Allow-Origin': '*',
        };
        const inm = req.headers['if-none-match'];
        if (inm && inm.split(',').map((s) => s.trim().replace(/^W\//, '')).includes(entry.etag)) {
            stats.routes[routeKind].notModified++;
            return { status: 304, headers };
        }
        return { status: 200, headers: { ...headers, 'Content-Type': 'image/png', 'Content-Length': entry.png.length }, body: entry.png };
    }

    // The latency actually delivered, arrival to answer: a timer fires on the operating system's tick
    // (about 15.6 ms on Windows), so the delivered distribution is the drawn one rounded up to it. Both
    // variants see the same; the result files record it.
    const delivered = [];
    // The LAN hop actually delivered (fetching model), from the map being ready to the answer
    const lanHops = [];

    function json(res, value) {
        const body = Buffer.from(JSON.stringify(value, null, 2));
        res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
        res.end(body);
    }

    function handle(req, res) {
        const arrived = performance.now();
        req.resume();
        const url = new URL(req.url, 'http://stand-in');
        if (url.pathname === '/__stats') {
            stats.deliveredLatencyMs = summary(delivered.slice());
            stats.staticMap.deliveredLanHopMs = fetching ? summary(lanHops.slice()) : null;
            json(res, stats);
            return;
        }
        if (url.pathname === '/__static-map/clear-tiles' && req.method === 'POST') {
            const cleared = staticTiles.size;
            staticTiles.clear();
            stats.staticMap.tilesHeld = 0;
            stats.staticMap.tilesCleared += cleared;
            json(res, { model: staticMapModel, cleared });
            return;
        }
        stats.requests++;
        let kind = 'other';
        let answer;
        // A static map of the fetching model pays for its tiles and a LAN hop, not a latency draw of its own
        const lanHop = fetching && url.pathname === '/staticmap.php';
        const due = lanHop ? null : arrived + nextLatency();
        try {
            const tile = /^\/(osm|seamark)\/(\d{1,2})\/(\d{1,7})\/(\d{1,7})\.png$/.exec(url.pathname);
            if (url.pathname === '/staticmap.php') {
                kind = 'staticmap';
                answer = staticAnswer(url);
            } else if (tile) {
                kind = tile[1] === 'osm' && req.headers['user-agent'] === STATIC_MAP_USER_AGENT ? 'staticmapTiles' : tile[1];
                answer = tileAnswer(req, tile[1], Number(tile[2]), Number(tile[3]), Number(tile[4]), kind);
            } else {
                answer = text(404, 'not a stand-in path');
            }
        } catch (e) {
            answer = text(500, e.message);
        }
        countPath(url.pathname);
        const ua = String(req.headers['user-agent'] ?? '(none)');
        const key = `${kind}: ${ua}`;
        if (!(key in stats.userAgents)) {
            stats.userAgents[key] = 0;
            if (!quiet) {
                console.log(`[stand-ins] first ${kind} request from User-Agent "${ua}"`);
            }
        }
        stats.userAgents[key]++;
        Promise.resolve(answer)
            .catch((e) => text(500, e.message))
            .then((a) => {
                const route = stats.routes[kind];
                route.requests++;
                if (a.status === 200) {
                    route.ok++;
                    route.bytes += a.body.length;
                }
                if (lanHop) {
                    const ready = performance.now();
                    waitPrecisely(lanLatency).then(() => {
                        res.writeHead(a.status, a.headers);
                        res.end(a.body);
                        lanHops.push(performance.now() - ready);
                        if (lanHops.length > 20_000) {
                            lanHops.splice(0, 10_000);
                        }
                    });
                    return;
                }
                setTimeout(() => {
                    res.writeHead(a.status, a.headers);
                    res.end(a.body);
                    delivered.push(performance.now() - arrived);
                    if (delivered.length > 20_000) {
                        delivered.splice(0, 10_000);
                    }
                }, Math.max(0, due - performance.now()));
            });
    }

    return {
        stats,
        handle,
        setTileBase: (base) => {
            tileBase = base;
        },
        close: () => Object.values(tileAgents).forEach((a) => a.destroy()),
    };
}

function listen(server, port, host) {
    return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => resolve(server.address().port));
    });
}

/**
 * Two ports, one handler: the static map and the tiles are told apart by path, so either port answers
 * both, and /__stats on either reports the shared counters. Node answers a request carrying
 * "Upgrade: h2c" (what an HTTP/2-preferring Java client sends over plain http) as plain HTTP/1.1,
 * because no 'upgrade' listener is registered. Requests are served concurrently and connections kept
 * alive.
 */
async function startStandIns({ host = '127.0.0.1', staticPort = 9101, tilePort = 9102, latency = 250, sigma = 0.5, seed = 1, quiet = false, staticMapModel = 'static', lanLatency = 2 }) {
    templates();
    seamarks();
    const standIns = createStandIns({ latency, sigma, seed, quiet, staticMapModel, lanLatency });
    const servers = [staticPort, tilePort].map(() => {
        const server = http.createServer(standIns.handle);
        server.keepAliveTimeout = 30_000;
        server.headersTimeout = 35_000;
        return server;
    });
    const boundStatic = await listen(servers[0], staticPort, host);
    const boundTiles = await listen(servers[1], tilePort, host);
    standIns.setTileBase(`http://${host.includes(':') ? `[${host}]` : host}:${boundTiles}`);
    return {
        stats: standIns.stats,
        staticPort: boundStatic,
        tilePort: boundTiles,
        close: () => {
            standIns.close();
            return Promise.all(servers.map((s) => new Promise((resolve) => {
                s.closeAllConnections();
                s.close(() => resolve());
            })));
        },
    };
}

// ------------------------------------------------------------------------------------------ HTTP out

function agentsFor(maxSockets) {
    return {
        'http:': new http.Agent({ keepAlive: true, maxSockets }),
        'https:': new https.Agent({ keepAlive: true, maxSockets }),
    };
}

function destroyAgents(agents) {
    Object.values(agents).forEach((a) => a.destroy());
}

function request(method, url, { headers = {}, body, agents, timeoutMs = 120_000 } = {}) {
    const target = new URL(url);
    const client = target.protocol === 'https:' ? https : http;
    return new Promise((resolve, reject) => {
        const t0 = performance.now();
        const req = client.request(target, {
            method,
            agent: agents?.[target.protocol],
            headers: {
                'Accept-Encoding': 'gzip',
                ...headers,
                ...(body !== undefined ? { 'Content-Length': Buffer.byteLength(body) } : {}),
            },
        }, (res) => {
            const ttfbMs = performance.now() - t0;
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('error', reject);
            res.on('end', () => {
                const buf = Buffer.concat(chunks);
                resolve({ status: res.statusCode, headers: res.headers, body: buf, bytes: buf.length, ttfbMs, totalMs: performance.now() - t0 });
            });
        });
        req.setTimeout(timeoutMs, () => req.destroy(new Error(`no answer within ${timeoutMs} ms: ${method} ${target.pathname}`)));
        req.on('error', reject);
        if (body !== undefined) {
            req.write(body);
        }
        req.end();
    });
}

// Only local mode may write (case 5 saves a scratch message); remote mode never turns this on.
let writesAllowed = false;

const get = (url, options) => request('GET', url, options);

function write(method, url, options) {
    if (!writesAllowed) {
        throw new Error(`${method} refused: this mode sends GET requests only`);
    }
    return request(method, url, options);
}

function decoded(response) {
    return /gzip/i.test(response.headers['content-encoding'] ?? '') ? zlib.gunzipSync(response.body) : response.body;
}

function jsonBody(response) {
    return JSON.parse(decoded(response).toString('utf8'));
}

// -------------------------------------------------------------------------------------------- tokens

/** A password grant on a public client; renewed shortly before it expires. */
function tokenSource(keycloak, realm, client) {
    const endpoint = `${keycloak.replace(/\/+$/, '')}/realms/${encodeURIComponent(realm)}/protocol/openid-connect/token`;
    let token = null;
    let expiresAt = 0;
    return {
        endpoint,
        async bearer() {
            if (token && Date.now() < expiresAt - 30_000) {
                return token;
            }
            const user = process.env.NIORD_USER;
            const password = process.env.NIORD_PASS;
            if (!user || !password) {
                fail('set NIORD_USER and NIORD_PASS in the environment; credentials are read from nowhere else');
            }
            const form = new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password }).toString();
            const r = await request('POST', endpoint, {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
                body: form,
                timeoutMs: 30_000,
            });
            if (r.status !== 200) {
                let why = '';
                try {
                    why = jsonBody(r).error ?? '';
                } catch {
                    // the reason is optional
                }
                throw new Error(`token request to ${endpoint} answered ${r.status} ${why}`.trim());
            }
            const answer = jsonBody(r);
            if (!answer.access_token) {
                throw new Error('the token answer carried no access_token');
            }
            token = answer.access_token;
            expiresAt = Date.now() + (answer.expires_in ?? 60) * 1000;
            return token;
        },
    };
}

// ------------------------------------------------------------------------------------ Niord requests

function makeContext(flags, mode) {
    const base = (flags.base ?? (mode === 'local' ? 'http://127.0.0.1:8888' : '')).replace(/\/+$/, '');
    const keycloak = flags.keycloak ?? (mode === 'local' ? 'http://localhost:8080/auth' : '');
    if (!base) {
        fail('--base is required in remote mode');
    }
    if (!keycloak) {
        fail('--keycloak is required in remote mode');
    }
    const ctx = {
        mode,
        base,
        keycloak,
        realm: flags.realm ?? 'niord',
        client: flags.client ?? 'niord-web',
        lang: flags.lang ?? 'en',
        domain: flags.domain ?? null,
        variant: flags.variant ?? null,
        standIns: flags['stand-ins']?.replace(/\/+$/, '') ?? null,
        cache: flags.cache ?? null,
        tileCache: flags['tile-cache'] ?? null,
        mapImageSize: 256,
        imageTimeoutMs: 30_000,
        pdfTimeoutMs: 300_000,
        imageHops: { oneHop: 0, twoHop: 0 },
    };
    ctx.tokens = tokenSource(keycloak, ctx.realm, ctx.client);
    return ctx;
}

/** What the administration app sends on an API call: the bearer token and, with a desk, NiordDomain. */
async function apiHeaders(ctx, accept = 'application/json') {
    return {
        Authorization: `Bearer ${await ctx.tokens.bearer()}`,
        Accept: accept,
        ...(ctx.domain ? { NiordDomain: ctx.domain } : {}),
    };
}

function hasGeometry(message) {
    return (message.parts ?? []).some((p) => (p.geometry?.features ?? []).length > 0);
}

function defaultFilter(ctx) {
    return ctx.domain ? `domain=${encodeURIComponent(ctx.domain)}&status=PUBLISHED` : 'status=PUBLISHED';
}

const UID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A message's folder, relative to the repository root; its generated map images sit in the same folder
 * under the cache tree (MessageMapImageRestService.imageFolder: RepoCache.pathFor(repoPath)). The
 * message's repoPath when the answer carries one; otherwise the folder the backend gives every message
 * from its UID (UidUtils.uidToHashedFolderPath: messages/<uid[0]>/<uid[1..2]>/<uid>), since the search
 * answer, a MessageVo, holds no repoPath. A folder that is empty, absolute, or holds a '.' or '..' step
 * is no folder of the tree: null, so nothing outside the tree is ever deleted.
 */
function messageFolder(message) {
    const raw = typeof message?.repoPath === 'string' && message.repoPath.trim()
        ? message.repoPath.trim()
        : (UID.test(message?.id ?? '') ? `messages/${message.id.slice(0, 1)}/${message.id.slice(1, 3)}/${message.id}` : null);
    if (raw === null || /^([/\\]|[a-z]:)/i.test(raw)) {
        return null;
    }
    const steps = raw.replace(/\\/g, '/').replace(/\/+$/, '').split('/');
    return steps.every((s) => s && s !== '.' && s !== '..') ? steps.join('/') : null;
}

/** The folder of each message of a set that has one, by UID. */
function messageFolders(messages) {
    const out = {};
    for (const m of messages) {
        const folder = messageFolder(m);
        if (folder) {
            out[m.id] = folder;
        }
    }
    return out;
}

/** The messages a filter matches, in search order, which of them carry a geometry, and their folders. */
async function searchSet(ctx, filter, count) {
    const url = `${ctx.base}/rest/messages/search?${filter}&maxSize=1000&page=0&lang=${ctx.lang}`;
    const r = await get(url, { headers: await apiHeaders(ctx) });
    if (r.status !== 200) {
        throw new Error(`message search answered ${r.status}: ${url}`);
    }
    const page = jsonBody(r);
    const withGeometry = page.data.filter(hasGeometry);
    const chosen = withGeometry.slice(0, count);
    return {
        filter,
        description: page.description ?? null,
        searchTotal: page.total,
        withGeometry: withGeometry.length,
        withoutGeometry: page.data.length - withGeometry.length,
        uids: chosen.map((m) => m.id),
        shortIds: chosen.map((m) => m.shortId ?? null),
        folders: messageFolders(chosen),
        files: {},
    };
}

async function resolveSet(ctx, flags, count) {
    if (flags.set) {
        const previous = JSON.parse(fs.readFileSync(flags.set, 'utf8'));
        if (!previous.set?.uids?.length) {
            fail(`${flags.set} holds no set.uids`);
        }
        // Each folder from the UID, as the backend makes it. A set written by an earlier version holds the
        // folders it learnt from the 307s, which a custom thumbnail's 307 may have pointed elsewhere; one
        // of those is kept only for a UID that gives no folder.
        const folders = {};
        for (const id of previous.set.uids) {
            const folder = messageFolder({ id }) ?? messageFolder({ repoPath: previous.set.folders?.[id] });
            if (folder) {
                folders[id] = folder;
            }
        }
        return { ...previous.set, folders, files: previous.set.files ?? {}, reusedFrom: path.basename(flags.set) };
    }
    const set = await searchSet(ctx, flags.filter ?? defaultFilter(ctx), count);
    if (set.uids.length === 0) {
        fail(`the filter "${set.filter}" matches no message with a geometry`);
    }
    return set;
}

/**
 * Counts the images a run got in one request and in two, for the result file's imageHops: compare reads
 * it to say when an old run and a new one fetched their images in a different number of requests.
 */
function countHops(ctx, hops) {
    if (ctx.imageHops) {
        const key = hops === 1 ? 'oneHop' : 'twoHop';
        ctx.imageHops[key] = (ctx.imageHops[key] ?? 0) + 1;
    }
}

/**
 * One map image as a browser loads it. A generated image is a one-hop sample: the address the page names
 * answers 200 with the image itself, from the cache tree (hop1Ms is the whole fetch, hop2Ms stays null).
 * A two-hop sample is a 307 to a file in the repository, then that file: the path of a custom thumbnail
 * an editor set, and of every image from a backend before the generated images were served at once.
 * The placeholder is counted, not timed as an image: a 200 the endpoint marks with X-Niord-Map-Image, or,
 * from a backend before that answer, a 307 to map_image_placeholder.png.
 * No token and no NiordDomain header: an <img> carries neither. A Location on the page's own host with
 * http:// on an https page is taken as https://, as a browser upgrades a mixed-content image; that is
 * counted. Any further redirect is followed and counted too.
 */
async function fetchImage(ctx, uid, agents) {
    const url = `${ctx.base}/rest/message-map-image/${encodeURIComponent(uid)}.png`;
    const out = { uid, ok: false, placeholder: false, custom: false, hops: 0, hop1Ms: null, hop2Ms: null, totalMs: null, bytes: 0, folder: null, file: null, upgraded: false, extraHops: 0, error: null, failedStatus: null };
    const t0 = performance.now();
    try {
        const first = await get(url, { agents, timeoutMs: ctx.imageTimeoutMs, headers: { Accept: IMAGE_ACCEPT } });
        out.hop1Ms = first.totalMs;
        if (first.status === 200 && first.headers[PLACEHOLDER_HEADER] === 'placeholder') {
            out.placeholder = true;
            out.bytes = first.bytes;
            return out;
        }
        if (first.status === 200) {
            if (!/^image\//i.test(first.headers['content-type'] ?? '') || first.bytes === 0) {
                out.error = `expected an image or a redirect, got 200 ${first.headers['content-type'] ?? 'without a Content-Type'}${first.bytes === 0 ? ' and no body' : ''}`;
                out.failedStatus = first.status;
                return out;
            }
            out.hops = 1;
            out.bytes = first.bytes;
            out.ok = true;
            countHops(ctx, 1);
            return out;
        }
        if (first.status < 300 || first.status > 399 || !first.headers.location) {
            out.error = `expected an image or a redirect, got ${first.status}`;
            out.failedStatus = first.status;
            return out;
        }
        const page = new URL(url);
        let next = new URL(first.headers.location, url);
        if (next.protocol === 'http:' && page.protocol === 'https:' && next.hostname === page.hostname) {
            next.protocol = 'https:';
            out.upgraded = true;
        }
        out.placeholder = PLACEHOLDER_FILE.test(next.pathname);
        const inRepo = /^\/rest\/repo\/file\/(.+)\/([^/]+)$/.exec(next.pathname);
        if (inRepo) {
            out.folder = decodeURIComponent(inRepo[1]);
            out.file = decodeURIComponent(inRepo[2]);
            out.custom = !MAP_IMAGE_FILE.test(out.file);
        }
        const t1 = performance.now();
        let second;
        for (let hops = 0; ; hops++) {
            second = await get(next.href, { agents, timeoutMs: ctx.imageTimeoutMs, headers: { Accept: IMAGE_ACCEPT } });
            if (second.status >= 300 && second.status < 400 && second.headers.location && hops < 3) {
                next = new URL(second.headers.location, next);
                out.extraHops++;
                continue;
            }
            break;
        }
        out.hop2Ms = performance.now() - t1;
        out.hops = 2;
        out.bytes = second.bytes;
        out.ok = second.status === 200 && !out.placeholder;
        if (out.ok) {
            countHops(ctx, 2);
        }
        if (second.status !== 200) {
            out.error = `the image file answered ${second.status}`;
            out.failedStatus = second.status;
        }
    } catch (e) {
        out.error = e.message;
        out.failedStatus = 'network';
    } finally {
        out.totalMs = performance.now() - t0;
    }
    return out;
}

async function runPool(items, concurrency, task) {
    const results = new Array(items.length);
    let next = 0;
    const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
        while (next < items.length) {
            const k = next++;
            results[k] = await task(items[k], k);
        }
    });
    await Promise.all(workers);
    return results;
}

async function sequentialImages(ctx, uids, rounds, agents) {
    const results = [];
    for (let r = 0; r < rounds; r++) {
        for (const uid of uids) {
            results.push(await fetchImage(ctx, uid, agents));
        }
    }
    return results;
}

function imageCounts(results) {
    const failedStatuses = {};
    results.filter((r) => r.failedStatus !== null).forEach((r) => {
        failedStatuses[r.failedStatus] = (failedStatuses[r.failedStatus] ?? 0) + 1;
    });
    return {
        requests: results.length,
        ok: results.filter((r) => r.ok).length,
        oneHop: results.filter((r) => r.ok && r.hops === 1).length,
        twoHop: results.filter((r) => r.ok && r.hops === 2).length,
        placeholders: results.filter((r) => r.placeholder).length,
        customThumbnails: results.filter((r) => r.custom).length,
        errors: results.filter((r) => r.error).length,
        failedStatuses,
        upgradedLocations: results.filter((r) => r.upgraded).length,
        extraHops: results.reduce((s, r) => s + r.extraHops, 0),
        firstErrors: [...new Set(results.filter((r) => r.error).map((r) => r.error))].slice(0, 5),
    };
}

function addCounts(total, counts) {
    for (const [k, v] of Object.entries(counts)) {
        if (typeof v === 'number') {
            total[k] = (total[k] ?? 0) + v;
        } else if (Array.isArray(v)) {
            total[k] = [...new Set([...(total[k] ?? []), ...v])].slice(0, 5);
        } else if (v && typeof v === 'object') {
            total[k] = addCounts(total[k] ?? {}, v);
        }
    }
    return total;
}

/** Timings count the requests that got their image; a failed one is counted, not timed. */
const served = (results) => results.filter((r) => r.ok);

/**
 * Fetches each image of the set once, so that every image exists, and learns which file name the
 * renderer uses: from the 307 of a backend that redirects to its generated image, otherwise from the
 * message's folder under --cache (currentMapImageName: the latest stamped map image there, a versioned name
 * before an unversioned one of the same time). The folders themselves come from the set
 * (searchSet), not from the answer: a generated image served at once names no file.
 */
async function discover(ctx, set) {
    const agents = agentsFor(6);
    const results = await runPool(set.uids, 6, (uid) => fetchImage(ctx, uid, agents));
    destroyAgents(agents);
    for (const r of results) {
        const file = r.file && !r.custom && !r.placeholder
            ? r.file
            : (ctx.cache && set.folders[r.uid] ? currentMapImageName(ctx.cache, set.folders[r.uid]) : null);
        if (file) {
            set.files[r.uid] = file;
        }
    }
    return imageCounts(results);
}

/**
 * How the backend names its images, from the names discover learnt: 'versioned', 'unversioned', 'mixed',
 * or 'unknown' when it learnt none (no --cache, and no image came by a redirect).
 */
function imageNaming(names) {
    if (names.length === 0) {
        return 'unknown';
    }
    if (names.every((n) => VERSIONED_MAP_IMAGE_FILE.test(n))) {
        return 'versioned';
    }
    return names.every((n) => /^map_\d+\.png$/.test(n)) ? 'unversioned' : 'mixed';
}

/**
 * The list PDF exactly as the administration app's print dialog asks for it
 * (message-print.service.ts): report, paper, thumbnails, language, then the filter; the bearer token
 * stands in for the one-time ticket the browser puts in the address.
 */
function listPdfUrl(ctx, filter, thumbnails) {
    return `${ctx.base}/rest/message-reports/report.pdf?report=standard&pageSize=A4&pageOrientation=portrait`
        + `&mapThumbnails=${thumbnails}&lang=${ctx.lang}&${filter}`;
}

function messagePdfUrl(ctx, uid) {
    return `${ctx.base}/rest/message-reports/message/${encodeURIComponent(uid)}.pdf?pageSize=A4&pageOrientation=portrait`
        + `&mapThumbnails=true&lang=${ctx.lang}`;
}

/**
 * Image XObjects in a PDF. A stream object can never sit inside an object stream, so every image
 * dictionary is readable in the file as written. `mapSized` counts the images of the map-image size:
 * one per message whose thumbnail made it into the document.
 */
function pdfImages(buf, mapImageSize) {
    const text = buf.toString('latin1');
    let images = 0;
    let mapSized = 0;
    const re = /\/Subtype\s*\/Image\b/g;
    let m;
    while ((m = re.exec(text)) !== null) {
        images++;
        const start = text.lastIndexOf(' obj', m.index);
        const end = text.indexOf('stream', m.index);
        const dict = text.slice(start < 0 ? m.index : start, end < 0 ? m.index + 400 : end);
        const w = /\/Width\s+(\d+)/.exec(dict);
        const h = /\/Height\s+(\d+)/.exec(dict);
        if (w && h && Number(w[1]) === mapImageSize && Number(h[1]) === mapImageSize) {
            mapSized++;
        }
    }
    const smasks = (text.match(/\/SMask\s+\d+\s+\d+\s+R/g) ?? []).length;
    return { images, smasks, mapSized };
}

async function fetchPdf(ctx, url) {
    const r = await get(url, { headers: await apiHeaders(ctx, 'application/pdf'), timeoutMs: ctx.pdfTimeoutMs });
    const body = decoded(r);
    const isPdf = body.subarray(0, 5).toString('latin1') === '%PDF-';
    return { status: r.status, ok: r.status === 200 && isPdf, ttfbMs: r.ttfbMs, totalMs: r.totalMs, bytes: body.length, ...(isPdf ? pdfImages(body, ctx.mapImageSize) : { images: 0, smasks: 0, mapSized: 0 }) };
}

/** Sequential PDF runs, the first `warmup` discarded. */
async function pdfRuns(ctx, url, warmup, runs, before) {
    const measured = [];
    for (let i = 0; i < warmup + runs; i++) {
        if (before) {
            await before(i);
        }
        const r = await fetchPdf(ctx, url);
        if (!r.ok) {
            throw new Error(`PDF answered ${r.status}${r.bytes ? '' : ' with no body'}: ${url}`);
        }
        if (i >= warmup) {
            measured.push(r);
        }
    }
    return measured;
}

function pdfMetrics(caseResult, prefix, runs) {
    const p = prefix ? `${prefix}.` : '';
    caseResult.metrics[`${p}total`] = metric(runs.map((r) => r.totalMs));
    caseResult.metrics[`${p}firstByte`] = metric(runs.map((r) => r.ttfbMs));
    caseResult.metrics[`${p}bytes`] = metric(runs.map((r) => r.bytes), 'bytes');
    caseResult.counts[`${p}mapImagesPerPdf`] = runs.map((r) => r.mapSized);
    caseResult.counts[`${p}imageXObjectsPerPdf`] = runs.map((r) => r.images);
}

/** Message GETs and list searches, one at a time, as the administration app sends them. */
async function apiReads(ctx, uids, listFilter, gets, searches, warmGets, warmSearches) {
    const agents = agentsFor(1);
    const out = { get: [], search: [], searchBytes: [], errors: [] };
    for (let i = 0; i < warmGets + gets; i++) {
        const uid = uids[i % uids.length];
        const r = await get(`${ctx.base}/rest/messages/message/${encodeURIComponent(uid)}`, { headers: await apiHeaders(ctx), agents });
        if (r.status !== 200 || r.bytes === 0) {
            out.errors.push(`message ${uid} answered ${r.status}`);
        } else if (i >= warmGets) {
            out.get.push(r.totalMs);
        }
    }
    const url = `${ctx.base}/rest/messages/search?${listFilter}&lang=${ctx.lang}&page=0&maxSize=200`;
    for (let i = 0; i < warmSearches + searches; i++) {
        const r = await get(url, { headers: await apiHeaders(ctx), agents });
        if (r.status !== 200) {
            out.errors.push(`list search answered ${r.status}`);
        } else if (i >= warmSearches) {
            out.search.push(r.totalMs);
            out.searchBytes.push(r.bytes);
        }
    }
    destroyAgents(agents);
    return out;
}

// --------------------------------------------------------------------------------------- message reads
// The reads of be/docs/message-read-benchmark.md beside cases 6 and R5, each request as the application
// sends it (messages-api.service.ts and messages-shell.facade.ts of the administration app; messages.store.ts
// and message-details-dialog.component.ts of the public site). One exception: the public list leaves out the
// mainType=NW&mainType=NM the site sends by default, which selects the same messages.

/** The filter the owner timed: NW and NM; the backend lists the published ones when no status is given (it ignores areaId and language). */
const READ_FILTER = 'mainType=NW&mainType=NM';

/** The administration app's list search: the default page (100), or the map view's 1,000. */
function adminListUrl(ctx, mapView) {
    const query = `${READ_FILTER}&lang=${encodeURIComponent(ctx.lang)}`;
    return `${ctx.base}/rest/messages/search?${query}${mapView ? '&page=0&maxSize=1000&viewMode=map&includeNoPos=true' : ''}`;
}

/** The public site's list and single message: no login, and no repository links rewritten to absolute ones. */
function publicListUrl(ctx) {
    return `${ctx.base}/rest/public/v1/messages?lang=${encodeURIComponent(ctx.lang)}&externalize=false`;
}

function publicMessageUrl(ctx, id) {
    return `${ctx.base}/rest/public/v1/message/${encodeURIComponent(id)}?lang=${encodeURIComponent(ctx.lang)}&externalize=false`;
}

/** What the public site sends: no token and no NiordDomain. */
const anonymousHeaders = async () => ({ Accept: 'application/json' });

/**
 * GETs one after another, the first `warm` not counted: the times and sizes of the answers that came back
 * 200 with a body, the others as errors, and the first good answer (its body tells what was listed).
 */
async function timedGets(label, urlAt, count, warm, headers) {
    const agents = agentsFor(1);
    const out = { ms: [], bytes: [], errors: [], first: null };
    try {
        for (let i = 0; i < warm + count; i++) {
            const r = await get(urlAt(i), { headers: await headers(), agents });
            if (r.status !== 200 || r.bytes === 0) {
                out.errors.push(`${label} answered ${r.status}`);
                continue;
            }
            out.first ??= r;
            if (i >= warm) {
                out.ms.push(r.totalMs);
                out.bytes.push(r.bytes);
            }
        }
    } finally {
        destroyAgents(agents);
    }
    return out;
}

function readCheck(errors) {
    return { name: 'no failed read', pass: errors.length === 0, detail: errors.slice(0, 5).join('; ') || '0' };
}

/** 6b / R6 (the default page of 100) and 6c / R7 (the map view): --searches list searches, one at a time. */
async function adminListCase(ctx, id, mapView, o, warm) {
    const c = newCase(id, mapView
        ? 'Map view: NW and NM list search, maxSize 1000 (viewMode=map), one at a time'
        : 'List search as the owner timed it: NW and NM, the default page of 100, one at a time');
    const r = await timedGets('list search', () => adminListUrl(ctx, mapView), o.searches, warm, () => apiHeaders(ctx));
    c.metrics.listSearch = metric(r.ms);
    c.metrics.listSearchBytes = metric(r.bytes, 'bytes');
    if (r.first) {
        const page = jsonBody(r.first);
        c.counts.listed = page.data?.length ?? 0;
        c.counts.total = page.total ?? null;
    }
    c.checks.push(readCheck(r.errors));
    return endCase(c);
}

/**
 * 6d / R8: the public site's list, then its single message for the first --count ids of that list, cycled
 * --message-gets times; no login.
 */
async function publicReadsCase(ctx, id, o, warmGets, warmLists) {
    const c = newCase(id, 'Public list and public message GET, no login, one at a time');
    const list = await timedGets('public list', () => publicListUrl(ctx), o.searches, warmLists, anonymousHeaders);
    c.metrics.publicList = metric(list.ms);
    c.metrics.publicListBytes = metric(list.bytes, 'bytes');
    const listed = list.first ? jsonBody(list.first) : [];
    const ids = (Array.isArray(listed) ? listed : []).map((m) => m?.id).filter(Boolean).slice(0, o.count);
    c.counts.listed = Array.isArray(listed) ? listed.length : 0;
    c.counts.messages = ids.length;
    let errors = list.errors;
    if (ids.length === 0) {
        c.checks.push({ name: 'public messages to read', pass: false, detail: 'the public list named no message, so no single message was read' });
    } else {
        const one = await timedGets('public message', (i) => publicMessageUrl(ctx, ids[i % ids.length]), o.messageGets, warmGets, anonymousHeaders);
        c.metrics.publicMessageGet = metric(one.ms);
        c.metrics.publicMessageBytes = metric(one.bytes, 'bytes');
        errors = errors.concat(one.errors);
    }
    c.checks.push(readCheck(errors));
    return endCase(c);
}

// ------------------------------------------------------------------------------- files in the cache tree
// root is --cache (<niord.home>/cache), or --legacy-from; a folder is a message's repoPath (messageFolder).

/**
 * Refuses the flag of the version that deleted under the repository folder: a current backend keeps its
 * generated images under the cache tree, so an old command line would delete nothing and time cached
 * images as cold ones. Returns the message, or null when --repo is not given.
 */
function repoFlagRefusal(flags) {
    return flags.repo === undefined ? null
        : '--repo is no longer taken: a current backend keeps its generated map images under the cache tree, '
          + 'not in the repository; name that tree with --cache <niord.home>/cache';
}

function folderPath(root, folder) {
    return path.join(root, ...folder.split('/'));
}

/**
 * The name the renderer gives a message's map image, read from its folder under root, or null when the
 * folder holds none. The backend stamps an image with the message's change time, not with its drawing
 * time, and this script's rename and copy keep that time; so a superseded map_<size>.png left beside the
 * current map_<size>_<version>.png carries the same time, and the readdir order would decide. The name
 * with the latest time is taken, and on equal times a versioned name before an unversioned one: an
 * unversioned name is current only on a build older than the versioned names, and such a build redirects,
 * so discover takes its name from the 307 and never reads it here.
 */
function currentMapImageName(root, folder) {
    try {
        const dir = folderPath(root, folder);
        const names = fs.readdirSync(dir).filter((n) => MAP_IMAGE_FILE.test(n));
        if (names.length === 0) {
            return null;
        }
        const versioned = (n) => (VERSIONED_MAP_IMAGE_FILE.test(n) ? 1 : 0);
        return names
            .map((n) => ({ n, t: fs.statSync(path.join(dir, n)).mtimeMs }))
            .sort((a, b) => b.t - a.t || versioned(b.n) - versioned(a.n))[0].n;
    } catch {
        return null;
    }
}

/** Map images only - map_<size>.png and map_<size>_<version>.png; a custom thumbnail is never touched. */
function deleteMapImages(root, folders) {
    let deleted = 0;
    let failed = 0;
    for (const folder of folders) {
        let names = [];
        try {
            names = fs.readdirSync(folderPath(root, folder));
        } catch {
            continue;
        }
        for (const name of names.filter((n) => MAP_IMAGE_FILE.test(n))) {
            try {
                fs.rmSync(path.join(folderPath(root, folder), name));
                deleted++;
            } catch {
                failed++;
            }
        }
    }
    return { deleted, failed };
}

/**
 * The state right after a deployment that changes the image version: the newest versioned file of each
 * folder renamed to map_<size>.png, the name the previous renderer used. A rename keeps the modification
 * time, so the file still counts as fresh for the message.
 */
function renameToUnversioned(root, folders) {
    let renamed = 0;
    for (const folder of folders) {
        const dir = folderPath(root, folder);
        let names = [];
        try {
            names = fs.readdirSync(dir).filter((n) => VERSIONED_MAP_IMAGE_FILE.test(n));
        } catch {
            continue;
        }
        if (names.length === 0) {
            continue;
        }
        const newest = names
            .map((n) => ({ n, t: fs.statSync(path.join(dir, n)).mtimeMs }))
            .sort((a, b) => b.t - a.t)[0].n;
        const size = VERSIONED_MAP_IMAGE_FILE.exec(newest)[1];
        fs.renameSync(path.join(dir, newest), path.join(dir, `map_${size}.png`));
        renamed++;
    }
    return renamed;
}

/** Copies the previous renderer's map_<size>.png into the new home, with its modification time. */
function copyPreviousImages(fromRoot, root, folders, size) {
    let copied = 0;
    let missing = 0;
    for (const folder of folders) {
        const source = path.join(folderPath(fromRoot, folder), `map_${size}.png`);
        if (!fs.existsSync(source)) {
            missing++;
            continue;
        }
        deleteMapImages(root, [folder]);
        const target = path.join(folderPath(root, folder), `map_${size}.png`);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(source, target);
        const st = fs.statSync(source);
        fs.utimesSync(target, st.atime, st.mtime);
        copied++;
    }
    return { copied, missing };
}

function mapImageBytes(root, folders) {
    const sizes = [];
    for (const folder of folders) {
        try {
            for (const name of fs.readdirSync(folderPath(root, folder)).filter((n) => MAP_IMAGE_FILE.test(n))) {
                sizes.push(fs.statSync(path.join(folderPath(root, folder), name)).size);
            }
        } catch {
            // an unknown folder has no image to measure
        }
    }
    return sizes;
}

/**
 * Deletes a folder. The backend may still be writing into it (the tile cache is written after an image
 * has its tiles), so a file that appears meanwhile is retried rather than failing the run.
 */
function clearDir(dir) {
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

// ----------------------------------------------------------------------------------- stand-in checks

async function standInSnapshot(ctx) {
    if (!ctx.standIns) {
        return null;
    }
    const r = await get(`${ctx.standIns}/__stats`, { timeoutMs: 5_000 });
    const s = jsonBody(r);
    return {
        staticmap: s.routes.staticmap.ok,
        osm: s.routes.osm.ok,
        osmNotModified: s.routes.osm.notModified,
        seamark: s.routes.seamark.requests,
        staticmapTiles: s.routes.staticmapTiles?.ok ?? 0,
        commaDecimalCentres: s.commaDecimalCentres,
    };
}

/** The static-map model the stand-ins run ('static' when they do not say, as before the fetching model). */
async function standInModel(ctx) {
    if (!ctx.standIns) {
        return 'static';
    }
    const r = await get(`${ctx.standIns}/__stats`, { timeoutMs: 5_000 }).catch((e) => fail(`no stand-ins at ${ctx.standIns}: ${e.message}`));
    return jsonBody(r).staticMap?.model ?? 'static';
}

/**
 * Empties the tile cache of the renderer being measured, for a state with cold tiles: the tile renderer's
 * cache folder, or, in the fetching model, the static-map stand-in's own tile cache (the previous
 * renderer's static-map server). The tile renderer's memory of its last 64 tiles is not touched; the
 * coldTiles states flush it by drawing other images first.
 */
async function clearTiles(ctx) {
    if (ctx.variant === 'new' && ctx.tileCache) {
        clearDir(ctx.tileCache);
    } else if (ctx.clearsStaticMapTiles) {
        const r = await request('POST', `${ctx.standIns}/__static-map/clear-tiles`, { timeoutMs: 5_000 });
        if (r.status !== 200) {
            throw new Error(`the static-map stand-in answered ${r.status} to clearing its tiles`);
        }
    }
}

/** Whether the run can empty the tile cache of the renderer being measured. */
const clearsTiles = (ctx) => (ctx.variant === 'new' ? Boolean(ctx.tileCache) : ctx.clearsStaticMapTiles);

/**
 * What the stand-ins must have seen. The previous renderer fetches exactly one static map per image it
 * draws; in the fetching model its static-map stand-in fetches the one to nine tiles under each 300 px
 * window it does not hold ('cold-tiles') and none when it holds them ('cold', 'warm-tiles'). The tile
 * renderer fetches one to four OSM tiles per image with a cold tile cache (fewer when neighbouring
 * images share tiles) and none with a warm one. Nobody may ask for a seamark tile: server images are
 * drawn on OSM alone. 'background' checks only that last rule, since the regeneration behind an image
 * served while superseded happens after the request has been answered.
 */
function hitCheck(ctx, caseResult, label, s0, s1, kind, coldImages) {
    if (!s0 || !s1) {
        return;
    }
    const delta = Object.fromEntries(Object.keys(s1).map((k) => [k, s1[k] - s0[k]]));
    const rules = [['seamark', 0]];
    if (ctx.variant === 'old') {
        rules.push(['osm', 0]);
        if (kind === 'none') {
            rules.push(['staticmap', 0], ['staticmapTiles', 0]);
        } else if (kind !== 'background') {
            rules.push(['staticmap', coldImages]);
            if (ctx.staticMapModel !== 'fetching') {
                rules.push(['staticmapTiles', 0]);
            } else if (kind === 'cold-tiles') {
                rules.push(['staticmapTiles', [1, 9 * coldImages]]);
            } else {
                rules.push(['staticmapTiles', 0]);
            }
        }
    } else {
        rules.push(['staticmap', 0], ['staticmapTiles', 0]);
        if (kind === 'none' || kind === 'warm-tiles') {
            rules.push(['osm', 0]);
        } else if (kind === 'cold-tiles') {
            rules.push(['osm', [1, 4 * coldImages]]);
        }
    }
    const pass = rules.every(([k, want]) => (Array.isArray(want) ? delta[k] >= want[0] && delta[k] <= want[1] : delta[k] === want));
    const expectation = rules.map(([k, want]) => `${k} ${Array.isArray(want) ? `${want[0]}..${want[1]}` : want}`).join(', ');
    caseResult.standIns.push({ label, delta, expectation, pass });
    if (!pass) {
        caseResult.checks.push({ name: `stand-in hits (${label})`, pass: false, detail: `expected ${expectation}, saw ${JSON.stringify(delta)}` });
    }
    if (delta.commaDecimalCentres > 0) {
        caseResult.notes.push(`${delta.commaDecimalCentres} static-map request(s) wrote the centre with decimal commas (a comma-decimal JVM locale)`);
    }
}

// --------------------------------------------------------------------------------------- case results

function newCase(id, title) {
    return { id, title, startedAt: new Date().toISOString(), durationMs: null, metrics: {}, counts: {}, standIns: [], checks: [], notes: [], t0: performance.now() };
}

function endCase(c) {
    c.durationMs = Math.round(performance.now() - c.t0);
    delete c.t0;
    return c;
}

function skipped(id, title, why) {
    return { id, title, skipped: why, metrics: {}, counts: {}, standIns: [], checks: [], notes: [] };
}

function imageChecks(c, counts, label = 'measured') {
    c.checks.push({ name: `no placeholder (${label})`, pass: counts.placeholders === 0, detail: `${counts.placeholders} of ${counts.requests}` });
    c.checks.push({ name: `no failed request (${label})`, pass: counts.errors === 0, detail: counts.firstErrors.join('; ') || '0' });
}

/**
 * The image timings of a case: 'total' is the whole fetch of every served image, one request or two.
 * 'redirect' (the 307 alone) and 'file' (the file it names) exist only for the images served in two
 * requests, a custom thumbnail or any image of a backend that redirected; a generated image of a current
 * backend is one request, which 'total' already holds.
 */
function imageMetrics(c, prefix, results) {
    const p = prefix ? `${prefix}.` : '';
    const ok = served(results);
    const twoHop = ok.filter((r) => r.hops === 2);
    c.metrics[`${p}total`] = metric(ok.map((r) => r.totalMs));
    if (twoHop.length > 0) {
        c.metrics[`${p}redirect`] = metric(twoHop.map((r) => r.hop1Ms));
        c.metrics[`${p}file`] = metric(twoHop.map((r) => r.hop2Ms));
    }
    c.metrics[`${p}imageBytes`] = metric(ok.map((r) => r.bytes), 'bytes');
}

function folders(set, uids = set.uids) {
    return uids.map((u) => set.folders[u]).filter(Boolean);
}

// ---------------------------------------------------------------------------------------- local cases

async function localCachedImage(ctx, set, o) {
    const c = newCase('1', 'Cached image, one image at a time: the whole fetch (one request; the 307 alone and the file only for an image served by a redirect)');
    const agents = agentsFor(6);
    await sequentialImages(ctx, set.uids, o.warmup, agents);
    const s0 = await standInSnapshot(ctx);
    const results = await sequentialImages(ctx, set.uids, o.rounds, agents);
    const s1 = await standInSnapshot(ctx);
    destroyAgents(agents);
    imageMetrics(c, '', results);
    c.counts = imageCounts(results);
    imageChecks(c, c.counts);
    hitCheck(ctx, c, `${o.rounds} rounds`, s0, s1, 'none', 0);
    return endCase(c);
}

async function localColdImage(ctx, set, o) {
    const title = 'Cold image and cold tile cache, one at a time (the tweet on publish)';
    if (!ctx.cache) {
        return skipped('2a', title, 'needs --cache');
    }
    const c = newCase('2a', title);
    const agents = agentsFor(6);
    const measured = [];
    for (let r = 0; r < o.warmup + o.coldRounds; r++) {
        const removed = deleteMapImages(ctx.cache, folders(set));
        await clearTiles(ctx);
        const s0 = await standInSnapshot(ctx);
        const results = await sequentialImages(ctx, set.uids, 1, agents);
        const s1 = await standInSnapshot(ctx);
        if (r < o.warmup) {
            continue;
        }
        measured.push(...results);
        c.checks.push({ name: `every image deleted (round ${r - o.warmup + 1})`, pass: removed.deleted >= set.uids.length && removed.failed === 0, detail: `${removed.deleted} deleted, ${removed.failed} failed, ${set.uids.length} images` });
        hitCheck(ctx, c, `round ${r - o.warmup + 1}`, s0, s1, 'cold-tiles', set.uids.length);
    }
    destroyAgents(agents);
    if (ctx.variant === 'new' && !ctx.tileCache) {
        c.notes.push('no --tile-cache given: the tile cache was not cleared, so this is not a cold-tile run');
    }
    imageMetrics(c, '', measured);
    c.counts = imageCounts(measured);
    imageChecks(c, c.counts);
    return endCase(c);
}

async function localColdImageWarmTiles(ctx, set, o, tilesWarm) {
    const title = 'Cold image, warm tile cache, one at a time (new renderer; the previous one in the fetching model)';
    if (ctx.variant !== 'new' && !ctx.clearsStaticMapTiles) {
        return skipped('2b', title, 'the previous renderer has no tile cache of its own; its cold case is 2a');
    }
    if (!ctx.cache) {
        return skipped('2b', title, 'needs --cache');
    }
    const c = newCase('2b', title);
    const agents = agentsFor(6);
    if (!tilesWarm) {
        deleteMapImages(ctx.cache, folders(set));
        await sequentialImages(ctx, set.uids, 1, agents);
    }
    const measured = [];
    for (let r = 0; r < o.warmup + o.coldRounds; r++) {
        deleteMapImages(ctx.cache, folders(set));
        const s0 = await standInSnapshot(ctx);
        const results = await sequentialImages(ctx, set.uids, 1, agents);
        const s1 = await standInSnapshot(ctx);
        if (r >= o.warmup) {
            measured.push(...results);
            hitCheck(ctx, c, `round ${r - o.warmup + 1}`, s0, s1, 'warm-tiles', set.uids.length);
        }
    }
    destroyAgents(agents);
    imageMetrics(c, '', measured);
    c.counts = imageCounts(measured);
    imageChecks(c, c.counts);
    return endCase(c);
}

async function ensureImages(ctx, uids) {
    const agents = agentsFor(6);
    const results = await runPool(uids, 6, (uid) => fetchImage(ctx, uid, agents));
    destroyAgents(agents);
    return imageCounts(results);
}

function pdfImageChecks(c, prefix, runs, expected) {
    const short = runs.filter((r) => r.mapSized < expected).length;
    c.checks.push({ name: `every thumbnail in the PDF (${prefix})`, pass: short === 0, detail: `${short} of ${runs.length} PDFs held fewer than ${expected} map images` });
}

async function localPdf(ctx, set, o) {
    const c = newCase('3', `PDF of the bench set (${set.withGeometry} messages with a geometry) with map thumbnails`);
    const expected = set.withGeometry;
    if (set.searchTotal > set.uids.length) {
        c.notes.push(`the filter matches ${set.searchTotal} messages but the bench set holds ${set.uids.length}: the cold runs leave the rest cached`);
    }
    await ensureImages(ctx, set.uids);
    const cached = await pdfRuns(ctx, listPdfUrl(ctx, set.filter, true), o.warmup, o.pdfRuns);
    pdfMetrics(c, 'cached', cached);
    pdfImageChecks(c, 'cached', cached, expected);
    if (ctx.cache) {
        c.metrics.pngBytes = metric(mapImageBytes(ctx.cache, folders(set)), 'bytes');
        const cold = [];
        for (let i = 0; i < o.warmup + o.pdfColdRuns; i++) {
            deleteMapImages(ctx.cache, folders(set));
            const s0 = await standInSnapshot(ctx);
            const run = await pdfRuns(ctx, listPdfUrl(ctx, set.filter, true), 0, 1);
            const s1 = await standInSnapshot(ctx);
            if (i >= o.warmup) {
                cold.push(...run);
                hitCheck(ctx, c, `cold run ${i - o.warmup + 1}`, s0, s1, 'warm-tiles', set.uids.length);
            }
        }
        pdfMetrics(c, 'cold', cold);
        pdfImageChecks(c, 'cold', cold, expected);
    } else {
        c.notes.push('no --cache: the cold runs were skipped');
    }
    const plain = await pdfRuns(ctx, listPdfUrl(ctx, set.filter, false), o.warmup, o.pdfRuns);
    pdfMetrics(c, 'noThumbnails', plain);
    const single = await pdfRuns(ctx, messagePdfUrl(ctx, set.uids[0]), o.warmup, o.pdfRuns);
    pdfMetrics(c, 'oneMessage', single);
    return endCase(c);
}

async function localPdfAfterDeployment(ctx, set, o) {
    const title = 'PDF right after the deployment: every image still under its previous name (new renderer only)';
    if (ctx.variant !== 'new') {
        return skipped('3b', title, 'the post-deployment state exists for the new renderer only');
    }
    if (!ctx.cache) {
        return skipped('3b', title, 'needs --cache');
    }
    const c = newCase('3b', title);
    const runs = [];
    for (let i = 0; i < o.warmup + o.pdfColdRuns; i++) {
        await ensureImages(ctx, set.uids);
        await sleep(o.settle);
        if (o.legacyFrom) {
            const copy = copyPreviousImages(o.legacyFrom, ctx.cache, folders(set), ctx.mapImageSize);
            if (copy.missing > 0) {
                c.notes.push(`${copy.missing} previous image(s) missing under --legacy-from`);
            }
        } else {
            renameToUnversioned(ctx.cache, folders(set));
        }
        const s0 = await standInSnapshot(ctx);
        const run = await pdfRuns(ctx, listPdfUrl(ctx, set.filter, true), 0, 1);
        await sleep(o.settle);
        const s1 = await standInSnapshot(ctx);
        if (i >= o.warmup) {
            runs.push(...run);
            hitCheck(ctx, c, `run ${i - o.warmup + 1}`, s0, s1, 'background', set.uids.length);
        }
    }
    c.notes.push(o.legacyFrom ? 'previous images copied from --legacy-from' : 'previous state made by renaming the current files to map_<size>.png');
    pdfMetrics(c, '', runs);
    pdfImageChecks(c, 'after deployment', runs, set.withGeometry);
    return endCase(c);
}

/**
 * The Details, Grid and Selected views all request the same address, eagerly, for every row of a page:
 * one fetch pattern stands for the three. Concurrency 6 is a browser's HTTP/1.1 limit per host (the
 * development proxy); 50 stands for HTTP/2 through the production proxy. The states: every image cached;
 * every image deleted ('cold': the tile renderer finds its tiles in the tile cache); for the tile renderer
 * also every image deleted and the tile cache emptied ('coldTiles': a page of images of places no image
 * was drawn of for a week, every tile fetched at once), and every image under a superseded name ('stale').
 * Before each coldTiles batch the images of other messages ('others', from the pool) are drawn first, so
 * that the renderer's memory of the tiles it used last holds theirs, not the page's; without others the
 * memory may still hold some of the page's tiles, and a note says so.
 */
async function localList(ctx, id, uids, set, o, others = null) {
    const c = newCase(id, `List view page of ${uids.length} map images, fetched at once`);
    let states = ['cached'];
    if (ctx.cache) {
        states.push('cold');
        if (clearsTiles(ctx)) {
            states.push('coldTiles');
            if (!others?.uids.length) {
                c.notes.push("no other messages to draw before a coldTiles batch: the renderer may still hold some of the page's tiles in memory");
            }
        } else if (ctx.variant === 'new') {
            c.notes.push('no --tile-cache: the coldTiles state was not measured');
        }
        if (ctx.variant === 'new') {
            states.push('stale');
        }
    } else {
        c.notes.push('no --cache: only the cached state was measured');
    }
    if (o.states.length) {
        c.notes.push(`--states: only ${o.states.join(', ')} of ${states.join(', ')}`);
        states = states.filter((s) => o.states.includes(s));
    }
    for (const state of states) {
        for (const conc of o.concurrency) {
            const agents = agentsFor(conc);
            const makespans = [];
            const requests = [];
            const counts = {};
            if (state === 'cached') {
                await ensureImages(ctx, uids);
            }
            for (let b = 0; b < o.warmup + o.listRounds; b++) {
                if (state === 'cold') {
                    deleteMapImages(ctx.cache, folders(set, uids));
                } else if (state === 'coldTiles') {
                    if (others?.uids.length) {
                        deleteMapImages(ctx.cache, folders(others, others.uids));
                        await ensureImages(ctx, others.uids);
                    }
                    deleteMapImages(ctx.cache, folders(set, uids));
                    await clearTiles(ctx);
                } else if (state === 'stale') {
                    renameToUnversioned(ctx.cache, folders(set, uids));
                }
                const s0 = await standInSnapshot(ctx);
                const t0 = performance.now();
                const results = await runPool(uids, conc, (uid) => fetchImage(ctx, uid, agents));
                const makespan = performance.now() - t0;
                if (state === 'stale') {
                    await sleep(o.settle);
                }
                const s1 = await standInSnapshot(ctx);
                if (b < o.warmup) {
                    continue;
                }
                makespans.push(makespan);
                requests.push(...served(results).map((r) => r.totalMs));
                addCounts(counts, imageCounts(results));
                const kind = state === 'cached' ? 'none'
                    : state === 'stale' ? 'background'
                        : state === 'coldTiles' ? 'cold-tiles' : 'warm-tiles';
                hitCheck(ctx, c, `${state}, concurrency ${conc}, batch ${b - o.warmup + 1}`, s0, s1, kind, uids.length);
            }
            destroyAgents(agents);
            c.metrics[`${state}.c${conc}.makespan`] = metric(makespans);
            c.metrics[`${state}.c${conc}.request`] = metric(requests);
            c.counts[`${state}.c${conc}`] = counts;
            imageChecks(c, { ...counts, firstErrors: counts.firstErrors ?? [] }, `${state}, concurrency ${conc}`);
        }
    }
    return endCase(c);
}

async function localSave(ctx, o) {
    const title = 'Save round trip: GET editable-message + PUT message, on a scratch message';
    if (!o.scratchId) {
        return skipped('5', title, 'needs --scratch-id');
    }
    const c = newCase('5', title);
    const id = encodeURIComponent(o.scratchId);
    const reads = [];
    const saves = [];
    const trips = [];
    for (let i = 0; i < 2 + o.saves; i++) {
        const t0 = performance.now();
        const g = await get(`${ctx.base}/rest/messages/editable-message/${id}`, { headers: await apiHeaders(ctx) });
        if (g.status !== 200 || g.bytes === 0) {
            c.checks.push({ name: 'editable message read', pass: false, detail: `answered ${g.status}${g.bytes === 0 ? ' with an empty body: no message has that id' : ''}` });
            break;
        }
        const p = await write('PUT', `${ctx.base}/rest/messages/message/${id}`, {
            headers: { ...(await apiHeaders(ctx)), 'Content-Type': 'application/json;charset=UTF-8' },
            body: decoded(g).toString('utf8'),
        });
        if (p.status !== 200) {
            c.checks.push({ name: 'message saved', pass: false, detail: `answered ${p.status}: ${decoded(p).toString('utf8').slice(0, 200)}` });
            break;
        }
        if (i >= 2) {
            reads.push(g.totalMs);
            saves.push(p.totalMs);
            trips.push(performance.now() - t0);
        }
    }
    c.metrics.editableGet = metric(reads);
    c.metrics.put = metric(saves);
    c.metrics.roundTrip = metric(trips);
    return endCase(c);
}

async function localReads(ctx, set, o) {
    const c = newCase('6', 'Message GET and list search (maxSize 200), one at a time');
    const r = await apiReads(ctx, set.uids, o.listFilter, o.messageGets, o.searches, 10, 3);
    c.metrics.messageGet = metric(r.get);
    c.metrics.listSearch = metric(r.search);
    c.metrics.listSearchBytes = metric(r.searchBytes, 'bytes');
    c.checks.push({ name: 'no failed read', pass: r.errors.length === 0, detail: r.errors.slice(0, 5).join('; ') || '0' });
    return endCase(c);
}

async function localReadsUnderLoad(ctx, pool, o) {
    const title = `Message GET and list search while ${pool.uids.length} map images are requested at concurrency 50`;
    if (!ctx.cache) {
        return skipped('7', title, 'needs --cache');
    }
    const c = newCase('7', title);
    let load = o.load;
    if (load === 'stale' && ctx.variant !== 'new') {
        c.notes.push('a stale load exists for the new renderer only; the old one ran a cold load');
        load = 'cold';
    }
    if (load === 'stale') {
        await ensureImages(ctx, pool.uids);
        renameToUnversioned(ctx.cache, folders(pool));
    } else {
        deleteMapImages(ctx.cache, folders(pool));
    }
    const loadAgents = agentsFor(MAX_CONCURRENCY);
    const readAgents = agentsFor(1);
    let loadDone = false;
    let windowEnds = Infinity;
    const loadStart = performance.now();
    const loadRun = runPool(pool.uids, MAX_CONCURRENCY, (uid) => fetchImage(ctx, uid, loadAgents)).then((results) => {
        loadDone = true;
        windowEnds = performance.now() + (load === 'stale' ? o.loadWindow : 0);
        return { results, makespan: performance.now() - loadStart };
    });
    const gets = [];
    const searches = [];
    const errors = [];
    const searchUrl = `${ctx.base}/rest/messages/search?${o.listFilter}&lang=${ctx.lang}&page=0&maxSize=200`;
    for (let i = 0; !loadDone || performance.now() < windowEnds; i++) {
        if (gets.length >= o.messageGets && searches.length >= o.searches) {
            break;
        }
        const isSearch = i % 7 === 6;
        const url = isSearch ? searchUrl : `${ctx.base}/rest/messages/message/${encodeURIComponent(set0(pool, i))}`;
        const r = await get(url, { headers: await apiHeaders(ctx), agents: readAgents });
        if (r.status !== 200) {
            errors.push(`${isSearch ? 'search' : 'message'} answered ${r.status}`);
        } else {
            (isSearch ? searches : gets).push(r.totalMs);
        }
    }
    const loaded = await loadRun;
    destroyAgents(loadAgents);
    destroyAgents(readAgents);
    c.metrics.messageGet = metric(gets);
    c.metrics.listSearch = metric(searches);
    c.metrics.loadMakespan = metric([loaded.makespan]);
    c.counts.load = { kind: load, ...imageCounts(loaded.results) };
    c.checks.push({ name: 'no failed read under load', pass: errors.length === 0, detail: errors.slice(0, 5).join('; ') || '0' });
    if (gets.length < 20) {
        c.notes.push(`only ${gets.length} message GETs fell inside the load; raise --pool-size or use --load stale with a longer --load-window`);
    }
    return endCase(c);
}

/**
 * Up to 30 messages of a set that are not on the page, with a known folder: drawn before a coldTiles batch,
 * about 90 tiles, more than the renderer keeps in memory (64).
 */
function othersThan(source, pageUids) {
    if (!source) {
        return null;
    }
    const uids = source.uids.filter((u) => !pageUids.includes(u) && source.folders?.[u]).slice(0, 30);
    return { uids, folders: source.folders };
}

function set0(set, i) {
    return set.uids[i % set.uids.length];
}

async function answers(ctx) {
    try {
        const r = await get(`${ctx.base}/rest/settings/web`, { timeoutMs: 2_000 });
        return r.status === 200;
    } catch {
        return false;
    }
}

function stopServer(child) {
    return new Promise((resolve) => {
        if (child.exitCode !== null) {
            resolve();
            return;
        }
        child.once('exit', () => resolve());
        if (process.platform === 'win32') {
            spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
        } else {
            try {
                process.kill(-child.pid, 'SIGTERM');
            } catch {
                child.kill('SIGTERM');
            }
            setTimeout(() => {
                try {
                    process.kill(-child.pid, 'SIGKILL');
                } catch {
                    // already gone
                }
            }, 15_000).unref();
        }
    });
}

/**
 * The first image after a JVM start: the start command runs once per sample, the harness waits until
 * /rest/settings/web answers (and, with --ready-log, until the log shows that line), fetches one cold
 * image, and stops the process tree again.
 */
async function localFirstImageAfterStart(ctx, set, o, outFile) {
    const title = 'First image after a JVM start (cold image)';
    if (!o.serverCmd) {
        return skipped('2c', title, 'needs --server-cmd');
    }
    if (!ctx.cache) {
        return skipped('2c', title, 'needs --cache');
    }
    if (await answers(ctx)) {
        fail(`a backend already answers at ${ctx.base}; case 2c starts and stops the backend itself, so stop it first`);
    }
    const c = newCase('2c', title);
    const logFile = o.serverLog ?? `${outFile}.server.log`;
    const log = fs.createWriteStream(logFile, { flags: 'a' });
    const ready = [];
    const firsts = [];
    for (let i = 0; i < o.restarts; i++) {
        const uid = set.uids[i % set.uids.length];
        const folder = set.folders[uid];
        if (!folder) {
            fail(`the set file gives no folder for ${uid}; use a result file of an earlier local run of the same variant as --set`);
        }
        deleteMapImages(ctx.cache, [folder]);
        await clearTiles(ctx);
        log.write(`\n===== ${TOOL} start ${i + 1} of ${o.restarts}, ${new Date().toISOString()} =====\n`);
        const t0 = performance.now();
        const child = spawn(o.serverCmd, { shell: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
        let logSeen = !o.readyLog;
        const watch = (chunk) => {
            log.write(chunk);
            if (!logSeen && o.readyLog.test(chunk.toString('utf8'))) {
                logSeen = true;
            }
        };
        child.stdout.on('data', watch);
        child.stderr.on('data', watch);
        try {
            while (!(logSeen && (await answers(ctx)))) {
                if (child.exitCode !== null) {
                    throw new Error(`the backend exited with ${child.exitCode} before it was ready; see ${logFile}`);
                }
                if (performance.now() - t0 > o.readyTimeout) {
                    throw new Error(`the backend was not ready within ${o.readyTimeout} ms; see ${logFile}`);
                }
                await sleep(500);
            }
            ready.push(performance.now() - t0);
            const s0 = await standInSnapshot(ctx);
            const agents = agentsFor(1);
            const result = await fetchImage(ctx, uid, agents);
            destroyAgents(agents);
            const s1 = await standInSnapshot(ctx);
            firsts.push(result);
            hitCheck(ctx, c, `start ${i + 1}`, s0, s1, 'cold-tiles', 1);
        } finally {
            await stopServer(child);
            for (let k = 0; k < 60 && (await answers(ctx)); k++) {
                await sleep(500);
            }
        }
    }
    log.end();
    c.metrics.firstImage = metric(served(firsts).map((r) => r.totalMs));
    c.metrics.startToReady = metric(ready);
    c.counts = imageCounts(firsts);
    imageChecks(c, c.counts);
    c.notes.push(`backend output appended to ${logFile}`);
    return endCase(c);
}

function localOptions(flags) {
    const load = flags.load ?? 'cold';
    if (!['cold', 'stale'].includes(load)) {
        fail('--load takes cold or stale');
    }
    const states = csv(flags.states ?? '');
    const unknownStates = states.filter((s) => !LIST_STATES.includes(s));
    if (unknownStates.length) {
        fail(`unknown list state(s): ${unknownStates.join(', ')}; --states takes ${LIST_STATES.join(', ')}`);
    }
    let readyLog = null;
    if (flags['ready-log']) {
        try {
            readyLog = new RegExp(flags['ready-log']);
        } catch (e) {
            fail(`--ready-log is not a regular expression: ${e.message}`);
        }
    }
    return {
        count: num(flags, 'count', 50, 1),
        warmup: num(flags, 'warmup', 1),
        rounds: num(flags, 'rounds', 20, 1),
        coldRounds: num(flags, 'cold-rounds', 3, 1),
        pdfRuns: num(flags, 'pdf-runs', 10, 1),
        pdfColdRuns: num(flags, 'pdf-cold-runs', 5, 1),
        concurrency: concurrencies(flags),
        states,
        listRounds: num(flags, 'list-rounds', 3, 1),
        poolSize: num(flags, 'pool-size', 200, 1),
        messageGets: num(flags, 'message-gets', 200, 1),
        searches: num(flags, 'searches', 30, 1),
        saves: num(flags, 'saves', 30, 1),
        scratchId: flags['scratch-id'] ?? null,
        legacyFrom: flags['legacy-from'] ?? null,
        settle: num(flags, 'settle', 5000),
        load,
        loadWindow: num(flags, 'load-window', 15000),
        serverCmd: flags['server-cmd'] ?? null,
        restarts: num(flags, 'restarts', 5, 1),
        readyLog,
        readyTimeout: num(flags, 'ready-timeout', 300000, 1000),
        serverLog: flags['server-log'] ?? null,
        pause: num(flags, 'pause', 500),
    };
}

function newResult(ctx, flags) {
    const cpus = os.cpus();
    return {
        tool: TOOL,
        format: RESULT_FORMAT,
        mode: ctx.mode,
        variant: ctx.variant,
        startedAt: new Date().toISOString(),
        finishedAt: null,
        durationMs: null,
        target: { base: ctx.base, keycloak: ctx.keycloak, realm: ctx.realm, client: ctx.client, domain: ctx.domain, lang: ctx.lang },
        client: { node: process.version, platform: `${os.platform()} ${os.release()}`, cpus: cpus.length, cpuModel: cpus[0]?.model ?? null, memoryGb: Math.round(os.totalmem() / 2 ** 30) },
        flags: { ...flags },
        set: null,
        pool: null,
        probe: {},
        // Images served in one request (the image at once) and in two (a 307, then the file); the object
        // is the context's own, so it holds the run's totals when the file is written.
        imageHops: ctx.imageHops,
        cases: {},
        findings: [],
        failedChecks: [],
    };
}

function finish(result, outFile, t0) {
    result.finishedAt = new Date().toISOString();
    result.durationMs = Math.round(performance.now() - t0);
    result.failedChecks = Object.values(result.cases).flatMap((c) => c.checks.filter((k) => k.pass === false).map((k) => ({ case: c.id, ...k })));
    fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
    fs.writeFileSync(outFile, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
    printResult(result);
    console.log(`\nresults written to ${outFile} (${(result.durationMs / 1000).toFixed(1)} s)`);
}

async function runLocal(flags) {
    const t0 = performance.now();
    writesAllowed = true;
    const ctx = makeContext(flags, 'local');
    if (!['old', 'new'].includes(ctx.variant)) {
        fail('local mode needs --variant old or --variant new');
    }
    const o = localOptions(flags);
    const cases = csv(flags.cases ?? DEFAULT_LOCAL_CASES.join(','));
    const unknown = cases.filter((k) => !LOCAL_CASES.includes(k));
    if (unknown.length) {
        fail(`unknown local case(s): ${unknown.join(', ')}`);
    }
    const outFile = flags.out ?? `bench-local-${ctx.variant}-${stamp()}.json`;
    const result = newResult(ctx, flags);
    ctx.staticMapModel = await standInModel(ctx);
    ctx.clearsStaticMapTiles = ctx.variant === 'old' && ctx.staticMapModel === 'fetching';
    result.staticMapModel = ctx.staticMapModel;

    if (cases.includes('2c')) {
        if (cases.length > 1) {
            fail('case 2c starts and stops the backend itself; run it on its own (--cases 2c)');
        }
        if (!flags.set) {
            fail('case 2c needs --set: the result file of an earlier local run of the same variant (it knows the image folders)');
        }
        result.set = await resolveSet(ctx, flags, o.count);
        result.cases['2c'] = await localFirstImageAfterStart(ctx, result.set, o, outFile);
        finish(result, outFile, t0);
        return result.failedChecks.length === 0 ? 0 : 1;
    }

    const settings = await get(`${ctx.base}/rest/settings/web`, { timeoutMs: 10_000 }).catch((e) => fail(`no backend at ${ctx.base}: ${e.message}`));
    if (settings.status !== 200) {
        fail(`${ctx.base}/rest/settings/web answered ${settings.status}`);
    }
    ctx.mapImageSize = jsonBody(settings).mapImageSize ?? 256;
    result.probe.mapImageSize = ctx.mapImageSize;
    // The message reads fetch no image, and 6b to 6d no set: a run of them alone skips what they do not use.
    const set = cases.some((k) => !SETLESS_CASES.includes(k)) ? await resolveSet(ctx, flags, o.count) : null;
    result.set = set;
    o.listFilter = flags['list-filter'] ?? set?.filter;
    if (set && cases.some((k) => !READ_CASES.includes(k))) {
        const discovery = await discover(ctx, set);
        result.probe.discovery = discovery;
        const naming = imageNaming(Object.values(set.files));
        result.probe.imageNaming = naming;
        if ((ctx.variant === 'old' && naming === 'versioned') || (ctx.variant === 'new' && naming === 'unversioned')) {
            result.findings.push(`--variant ${ctx.variant} but the backend names its images ${naming}: check which jar is running`);
        }
        if (discovery.placeholders > 0 || discovery.errors > 0) {
            result.findings.push(`the first fetch of the set got ${discovery.placeholders} placeholder(s) and ${discovery.errors} error(s): check the backend's map source and baseUri`);
        }
    }

    let pool = null;
    const flushesMemory = clearsTiles(ctx) && cases.includes('4');
    if (cases.includes('4b') || cases.includes('7') || flushesMemory) {
        pool = flags['pool-filter'] || o.poolSize > set.uids.length
            ? await searchSet(ctx, flags['pool-filter'] ?? set.filter, o.poolSize)
            : { ...set };
        await discover(ctx, pool);
        result.pool = { filter: pool.filter, uids: pool.uids, folders: pool.folders };
        if (pool.uids.length < o.poolSize) {
            result.findings.push(`the pool holds ${pool.uids.length} messages with a geometry, not ${o.poolSize}: widen --pool-filter`);
        }
    }

    const order = LOCAL_CASES.filter((k) => cases.includes(k));
    for (const id of order) {
        console.log(`case ${id} ...`);
        let c;
        if (id === '1') c = await localCachedImage(ctx, set, o);
        if (id === '2a') c = await localColdImage(ctx, set, o);
        if (id === '2b') c = await localColdImageWarmTiles(ctx, set, o, cases.includes('2a'));
        if (id === '3') c = await localPdf(ctx, set, o);
        if (id === '3b') c = await localPdfAfterDeployment(ctx, set, o);
        if (id === '4') c = await localList(ctx, '4', set.uids, set, o, othersThan(pool, set.uids));
        if (id === '4b') c = await localList(ctx, '4b', pool.uids, pool, o, othersThan(set, pool.uids));
        if (id === '5') c = await localSave(ctx, o);
        if (id === '6') c = await localReads(ctx, set, o);
        if (id === '6b') c = await adminListCase(ctx, '6b', false, o, 3);
        if (id === '6c') c = await adminListCase(ctx, '6c', true, o, 3);
        if (id === '6d') c = await publicReadsCase(ctx, '6d', o, 10, 3);
        if (id === '7') c = await localReadsUnderLoad(ctx, pool, o);
        result.cases[id] = c;
        await sleep(o.pause);
    }
    finish(result, outFile, t0);
    return result.failedChecks.length === 0 ? 0 : 1;
}

// --------------------------------------------------------------------------------------- remote cases

async function runRemote(flags) {
    const t0 = performance.now();
    const ctx = makeContext(flags, 'remote');
    const cases = csv(flags.cases ?? DEFAULT_REMOTE_CASES.join(','));
    const unknown = cases.filter((k) => !REMOTE_CASES.includes(k));
    if (unknown.length) {
        fail(`unknown remote case(s): ${unknown.join(', ')}`);
    }
    const o = {
        count: num(flags, 'count', 50, 1),
        rounds: num(flags, 'rounds', 5, 2),
        warmup: num(flags, 'warmup', 1),
        concurrency: concurrencies(flags),
        listRounds: num(flags, 'list-rounds', 3, 1),
        pdfRuns: num(flags, 'pdf-runs', 5, 1),
        messageGets: num(flags, 'message-gets', 200, 1),
        searches: num(flags, 'searches', 30, 1),
        pause: num(flags, 'pause', 1000),
    };
    const host = new URL(ctx.base).hostname;
    const outFile = flags.out ?? `bench-remote-${host}-${stamp()}.json`;
    const result = newResult(ctx, flags);

    // Every path once before any loop: a wrong address or a refused login stops the run here.
    const settings = await get(`${ctx.base}/rest/settings/web`, { headers: await apiHeaders(ctx) });
    if (settings.status !== 200) {
        fail(`${ctx.base}/rest/settings/web answered ${settings.status}`);
    }
    ctx.mapImageSize = jsonBody(settings).mapImageSize ?? 256;
    result.probe.mapImageSize = ctx.mapImageSize;
    // R6 to R8 need no set, and the message reads no image: a run of them alone skips what they do not use.
    const set = cases.some((k) => !SETLESS_CASES.includes(k)) ? await resolveSet(ctx, flags, o.count) : null;
    result.set = set;
    if (set && set.uids.length < o.count) {
        result.findings.push(`the filter gave ${set.uids.length} messages with a geometry, not ${o.count}`);
    }
    const listFilter = flags['list-filter'] ?? set?.filter;
    let probeImage = null;
    if (set && cases.some((k) => !READ_CASES.includes(k))) {
        probeImage = await fetchImage(ctx, set.uids[0], agentsFor(1));
        result.probe.image = { status: probeImage.error ?? 'ok', totalMs: round1(probeImage.totalMs), file: probeImage.file, upgraded: probeImage.upgraded };
        if (probeImage.error) {
            fail(`the image probe failed: ${probeImage.error}`);
        }
        result.findings.push(`the probe fetched the image of ${set.uids[0]} once before R1`);
    }
    if (set) {
        const probeMessage = await get(`${ctx.base}/rest/messages/message/${encodeURIComponent(set.uids[0])}`, { headers: await apiHeaders(ctx) });
        const probeSearch = await get(`${ctx.base}/rest/messages/search?${listFilter}&lang=${ctx.lang}&page=0&maxSize=200`, { headers: await apiHeaders(ctx) });
        result.probe.message = probeMessage.status;
        result.probe.listSearch = probeSearch.status;
        if (probeMessage.status !== 200 || probeSearch.status !== 200) {
            fail(`message GET answered ${probeMessage.status}, list search ${probeSearch.status}`);
        }
        result.probe.listSearchTotal = jsonBody(probeSearch).total;
    }
    if (cases.includes('R6') || cases.includes('R7')) {
        const probeReadList = await get(adminListUrl(ctx, false), { headers: await apiHeaders(ctx) });
        result.probe.readListSearch = probeReadList.status;
        if (probeReadList.status !== 200) {
            fail(`the NW and NM list search answered ${probeReadList.status}`);
        }
    }
    if (cases.includes('R8')) {
        const probePublicList = await get(publicListUrl(ctx), { headers: await anonymousHeaders() });
        result.probe.publicList = probePublicList.status;
        if (probePublicList.status !== 200) {
            fail(`the public list answered ${probePublicList.status}`);
        }
    }

    for (const id of REMOTE_CASES.filter((k) => cases.includes(k))) {
        console.log(`case ${id} ...`);
        if (id === 'R1') {
            const c = newCase('R1', `Image of ${set.uids.length} messages, one at a time, ${o.rounds} rounds`);
            const agents = agentsFor(1);
            const first = await sequentialImages(ctx, set.uids, 1, agents);
            const cached = await sequentialImages(ctx, set.uids, o.rounds - 1, agents);
            destroyAgents(agents);
            imageMetrics(c, 'first', first);
            imageMetrics(c, 'cached', cached);
            c.counts.first = imageCounts(first);
            c.counts.cached = imageCounts(cached);
            c.notes.push('"first" is the first fetch of each image in this run, the state on the server unknown; "cached" are the later rounds');
            imageChecks(c, c.counts.first, 'first');
            imageChecks(c, c.counts.cached, 'cached');
            result.cases.R1 = endCase(c);
        }
        if (id === 'R2') {
            const c = newCase('R2', `List view page of ${set.uids.length} images fetched at once (Details, Grid, Selected)`);
            for (const conc of o.concurrency) {
                const agents = agentsFor(conc);
                const makespans = [];
                const requests = [];
                const counts = {};
                for (let b = 0; b < o.warmup + o.listRounds; b++) {
                    const tb = performance.now();
                    const results = await runPool(set.uids, conc, (uid) => fetchImage(ctx, uid, agents));
                    if (b >= o.warmup) {
                        makespans.push(performance.now() - tb);
                        requests.push(...served(results).map((r) => r.totalMs));
                        addCounts(counts, imageCounts(results));
                    }
                }
                destroyAgents(agents);
                c.metrics[`c${conc}.makespan`] = metric(makespans);
                c.metrics[`c${conc}.request`] = metric(requests);
                c.counts[`c${conc}`] = counts;
                imageChecks(c, { ...counts, firstErrors: counts.firstErrors ?? [] }, `concurrency ${conc}`);
                await sleep(o.pause);
            }
            result.cases.R2 = endCase(c);
        }
        if (id === 'R3' || id === 'R4') {
            const thumbnails = id === 'R3';
            const c = newCase(id, `List PDF of the set ${thumbnails ? 'with' : 'without'} map thumbnails`);
            const runs = await pdfRuns(ctx, listPdfUrl(ctx, set.filter, thumbnails), o.warmup, o.pdfRuns);
            pdfMetrics(c, 'pdf', runs);
            if (thumbnails) {
                const expected = set.withGeometry;
                const held = Math.min(...runs.map((r) => r.mapSized));
                c.counts.expectedMapImages = expected;
                if (held < expected) {
                    result.findings.push(`R3: the PDFs held ${held} map images of the ${expected} their messages call for; the time measured is that of a PDF without them`);
                }
            }
            result.cases[id] = endCase(c);
        }
        if (id === 'R5') {
            const c = newCase('R5', 'Message GET and list search (maxSize 200), one at a time');
            const r = await apiReads(ctx, set.uids, listFilter, o.messageGets, o.searches, 5, 2);
            c.metrics.messageGet = metric(r.get);
            c.metrics.listSearch = metric(r.search);
            c.metrics.listSearchBytes = metric(r.searchBytes, 'bytes');
            c.checks.push({ name: 'no failed read', pass: r.errors.length === 0, detail: r.errors.slice(0, 5).join('; ') || '0' });
            result.cases.R5 = endCase(c);
        }
        if (id === 'R6' || id === 'R7') {
            result.cases[id] = await adminListCase(ctx, id, id === 'R7', o, 2);
        }
        if (id === 'R8') {
            result.cases.R8 = await publicReadsCase(ctx, 'R8', o, 5, 2);
        }
        await sleep(o.pause);
    }
    const upgraded = Object.values(result.cases).reduce((s, c) => s + Object.values(c.counts ?? {}).reduce((t, v) => t + (v?.upgradedLocations ?? 0), 0), 0);
    if (upgraded > 0 || probeImage?.upgraded) {
        result.findings.push(`the 307 Location names http:// on an https deployment (${upgraded} times); the harness went to https:// directly, as a browser upgrades a mixed-content image`);
    }
    const limited = Object.values(result.cases).flatMap((c) => Object.entries(c.counts ?? {})
        .filter(([, v]) => v?.failedStatuses?.['429'])
        .map(([k, v]) => `${c.id} ${k}: ${v.failedStatuses['429']} of ${v.requests}`));
    if (limited.length) {
        result.findings.push(`image requests answered 429 Too Many Requests (${limited.join('; ')}): the deployment limits how many requests one client may have open; failed requests are counted, not timed`);
    }
    finish(result, outFile, t0);
    return 0;
}

// ---------------------------------------------------------------------------------------------- output

function pad(value, width, right = false) {
    const s = String(value ?? '');
    return right ? s.padStart(width) : s.padEnd(width);
}

const CASE_ORDER = [...LOCAL_CASES, ...REMOTE_CASES];
const byCaseOrder = (a, b) => CASE_ORDER.indexOf(a) - CASE_ORDER.indexOf(b);

function printResult(result) {
    const rows = [];
    for (const c of Object.values(result.cases).sort((a, b) => byCaseOrder(a.id, b.id))) {
        if (c.skipped) {
            rows.push([c.id, '(skipped)', '', '', '', '', c.skipped]);
            continue;
        }
        for (const [name, m] of Object.entries(c.metrics)) {
            const s = m.summary;
            const unit = m.unit === 'ms' ? '' : ` ${m.unit}`;
            rows.push([c.id, `${name}${unit}`, s.n, s.median, s.p95, s.max, '']);
        }
    }
    const header = ['case', 'metric', 'n', 'median', 'p95', 'max'];
    const widths = header.map((h, i) => Math.max(h.length, ...rows.map((r) => String(r[i] ?? '').length)));
    console.log(`\n${header.map((h, i) => pad(h, widths[i], i >= 2)).join('  ')}   (ms unless named)`);
    console.log(widths.map((w) => '-'.repeat(w)).join('  '));
    for (const r of rows) {
        console.log(`${r.slice(0, 6).map((v, i) => pad(v, widths[i], i >= 2)).join('  ')}${r[6] ? `   ${r[6]}` : ''}`);
    }
    for (const f of result.findings) {
        console.log(`finding: ${f}`);
    }
    for (const k of result.failedChecks) {
        console.log(`FAILED  ${k.case}  ${k.name}: ${k.detail}`);
    }
}

// --------------------------------------------------------------------------------------------- compare

function bootstrapRatio(a, b, iterations = 1000) {
    const rnd = mulberry32(42);
    const median = (arr) => percentile(arr.slice().sort((x, y) => x - y), 0.5);
    const draw = (arr) => Array.from(arr, () => arr[Math.floor(rnd() * arr.length)]);
    const ratios = [];
    for (let i = 0; i < iterations; i++) {
        ratios.push(median(draw(b)) / median(draw(a)));
    }
    ratios.sort((x, y) => x - y);
    return [round2(percentile(ratios, 0.025)), round2(percentile(ratios, 0.975))];
}

function round2(x) {
    return Number.isFinite(x) ? Math.round(x * 100) / 100 : x;
}

function pooledMetrics(runs) {
    const pooled = new Map();
    for (const run of runs) {
        for (const [id, c] of Object.entries(run.cases ?? {})) {
            for (const [name, m] of Object.entries(c.metrics ?? {})) {
                if (m.unit !== 'ms' || m.samples.length === 0) {
                    continue;
                }
                const key = `${id}|${name}`;
                pooled.set(key, [...(pooled.get(key) ?? []), ...m.samples]);
            }
        }
    }
    return pooled;
}

/**
 * How a run's images were fetched: 'one' (the image at once), 'two' (a 307, then the file), 'mixed', or
 * 'none' when no image was served. A result file written before imageHops existed came from a version of
 * this script that took only a redirect as an image, so any image it served is two-hop: one it timed as a
 * 307 (cases 1, 2a, 2b and R1), or one a case counted as served (counts.ok, or ok in a nested count such
 * as counts.c6, counts['cold.c6'], counts.first or counts.load: cases 2c, 4, 4b, 7 and R2 time no 307).
 */
function hopShape(run) {
    if (run.imageHops) {
        const one = run.imageHops.oneHop ?? 0;
        const two = run.imageHops.twoHop ?? 0;
        return one > 0 && two > 0 ? 'mixed' : one > 0 ? 'one' : two > 0 ? 'two' : 'none';
    }
    const timedImages = Object.values(run.cases ?? {}).some((c) => Object.entries(c.metrics ?? {})
        .some(([name, m]) => /(^|\.)redirect$/.test(name) && (m.samples?.length ?? 0) > 0));
    const served = (counts) => (counts?.ok ?? 0) > 0;
    const countedImages = Object.values(run.cases ?? {}).some((c) => served(c.counts)
        || Object.values(c.counts ?? {}).some((v) => v !== null && typeof v === 'object' && !Array.isArray(v) && served(v)));
    return timedImages || countedImages ? 'two' : 'none';
}

const HOP_WORDS = { one: 'one request each (the image at once)', two: 'two requests each (a 307, then the file)', mixed: 'one request or two' };

/**
 * The line compare prints when the old runs and the new ones fetched their images in a different number
 * of requests, or null when they did not (or one side served no image). The pass criteria stay the same:
 * the note says what the image times weigh against each other.
 */
function hopNote(olds, news) {
    const side = (runs) => {
        const shapes = [...new Set(runs.map(hopShape).filter((s) => s !== 'none'))];
        return shapes.length === 0 ? 'none' : shapes.length === 1 ? shapes[0] : 'mixed';
    };
    const o = side(olds);
    const n = side(news);
    if (o === 'none' || n === 'none' || (o === n && o !== 'mixed')) {
        return null;
    }
    return `image fetches: the old runs took ${HOP_WORDS[o]}, the new runs ${HOP_WORDS[n]}; `
        + 'every image time below weighs one against the other, not like for like';
}

function runCompare(flags) {
    const read = (files) => csv(files).map((f) => JSON.parse(fs.readFileSync(f, 'utf8')));
    if (!flags.old || !flags.new) {
        fail('compare needs --old and --new');
    }
    const olds = read(flags.old);
    const news = read(flags.new);
    const tolerance = num(flags, 'tolerance', 0.05);
    const floorMs = num(flags, 'floor-ms', 2);
    const smallMs = num(flags, 'small-ms', 40);
    const O = pooledMetrics(olds);
    const N = pooledMetrics(news);
    const models = [...new Set(olds.map((r) => r.staticMapModel ?? 'static'))];
    console.log(`old runs: static-map model ${models.join(' and ')}${models.length > 1 ? ' (MIXED: the old runs are not comparable)' : ''}`);
    const hops = hopNote(olds, news);
    if (hops) {
        console.log(`NOTE  ${hops}`);
    }
    const pairs = [...N.keys()].filter((k) => O.has(k)).map((k) => [k, k]);
    // States only the new side has are held against the old side's nearest state: the post-deployment PDF
    // against the cached PDF, a cold image with warm tiles against the old cold image, a page of cold
    // images with cold tiles against the old page of cold images, and a page of superseded images (served
    // at once, redrawn in the background) against the old page of cached images. In the fetching model the
    // old side has the tile states too (2b, coldTiles), and they are held against each other.
    const counterpart = (k) => {
        const [id, name] = k.split('|');
        if (id === '3b') {
            return `3|cached.${name}`;
        }
        if (id === '2b') {
            return `2a|${name}`;
        }
        if ((id === '4' || id === '4b') && name.startsWith('coldTiles.')) {
            return `${id}|cold.${name.slice('coldTiles.'.length)}`;
        }
        if ((id === '4' || id === '4b') && name.startsWith('stale.')) {
            return `${id}|cached.${name.slice('stale.'.length)}`;
        }
        return null;
    };
    for (const k of N.keys()) {
        const oldKey = counterpart(k);
        if (oldKey && O.has(oldKey) && !O.has(k)) {
            pairs.push([oldKey, k]);
        }
    }
    pairs.sort((a, b) => byCaseOrder(a[1].split('|')[0], b[1].split('|')[0]));
    const limit = (x) => (x < smallMs ? Math.max(x * (1 + tolerance), x + floorMs) : x * (1 + tolerance));
    const rows = pairs.map(([ok, nk]) => {
        const so = summary(O.get(ok));
        const sn = summary(N.get(nk));
        const pass = sn.median <= limit(so.median) && sn.p95 <= limit(so.p95);
        return { case: nk.split('|')[0], metric: nk.split('|')[1], against: ok === nk ? '' : ok.replace('|', ' '), old: so, new: sn, ratio: round2(sn.median / so.median), ci: bootstrapRatio(O.get(ok), N.get(nk)), pass };
    });
    console.log('case  metric                              old med  new med  ratio  95% CI        old p95  new p95  verdict');
    for (const r of rows) {
        console.log(`${pad(r.case, 4)}  ${pad(r.metric + (r.against ? ` (vs ${r.against})` : ''), 34)}  ${pad(r.old.median, 7, true)}  ${pad(r.new.median, 7, true)}  ${pad(r.ratio, 5, true)}  ${pad(`${r.ci[0]}-${r.ci[1]}`, 12)}  ${pad(r.old.p95, 7, true)}  ${pad(r.new.p95, 7, true)}  ${r.pass ? 'pass' : 'SLOWER'}`);
    }
    const failedChecks = [...olds, ...news].flatMap((run) => (run.failedChecks ?? []).map((k) => `${run.mode} ${run.variant ?? ''} case ${k.case}: ${k.name}: ${k.detail}`));
    failedChecks.forEach((f) => console.log(`FAILED  ${f}`));
    const slower = rows.filter((r) => !r.pass).length;
    console.log(`\n${rows.length} comparisons, ${slower} slower than old x ${1 + tolerance} (floor ${floorMs} ms under ${smallMs} ms), ${failedChecks.length} failed checks`);
    return slower === 0 && failedChecks.length === 0 ? 0 : 1;
}

// -------------------------------------------------------------------------------------------- selftest

/**
 * The map-image client against a stand-in of the endpoint's answers: a generated image served at once
 * (200), the placeholder (200 with X-Niord-Map-Image), a custom thumbnail (307 to the repository), an
 * older backend's redirect to a generated image and to the placeholder file, and a 200 that is no image.
 * Then the metrics a case keeps of them, and the note compare prints when old and new runs differ.
 */
async function selftestMapImageAnswers(expect) {
    const png = templates()[0].png;
    const server = http.createServer((req, res) => {
        const at = `http://127.0.0.1:${server.address().port}`;
        const image = (extra = {}) => {
            res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, ...extra });
            res.end(png);
        };
        const redirect = (to) => {
            res.writeHead(307, { Location: `${at}${to}`, 'Content-Length': 0 });
            res.end();
        };
        switch (req.url) {
            case '/rest/message-map-image/generated.png': return image();
            case '/rest/message-map-image/placeholder.png': return image({ 'X-Niord-Map-Image': 'placeholder' });
            case '/rest/message-map-image/custom.png': return redirect('/rest/repo/file/messages/a/b/custom_thumb.png');
            case '/rest/message-map-image/redirected.png': return redirect('/rest/repo/file/messages/a/c/map_256.png');
            case '/rest/message-map-image/old-placeholder.png': return redirect('/img/map_image_placeholder.png');
            case '/rest/repo/file/messages/a/b/custom_thumb.png':
            case '/rest/repo/file/messages/a/c/map_256.png':
            case '/img/map_image_placeholder.png': return image();
            case '/rest/message-map-image/html.png':
                res.writeHead(200, { 'Content-Type': 'text/html', 'Content-Length': 6 });
                return res.end('<html>');
            default:
                res.writeHead(404, { 'Content-Length': 0 });
                return res.end();
        }
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const agents = agentsFor(4);
    try {
        const ctx = { base: `http://127.0.0.1:${server.address().port}`, imageTimeoutMs: 5000, imageHops: { oneHop: 0, twoHop: 0 } };
        const fetch = (uid) => fetchImage(ctx, uid, agents);
        const generated = await fetch('generated');
        expect(generated.ok && !generated.error && generated.hops === 1 && generated.bytes === png.length,
            `a generated image answered 200 at once is a served one-hop sample (${generated.error ?? 'no error'})`);
        expect(Number.isFinite(generated.hop1Ms) && generated.hop2Ms === null && generated.folder === null,
            'a one-hop sample times the whole fetch as its first hop and has no second hop');
        const placeholder = await fetch('placeholder');
        expect(placeholder.placeholder && !placeholder.ok && !placeholder.error, 'the placeholder answered 200 is counted, not timed');
        const custom = await fetch('custom');
        expect(custom.ok && custom.custom && custom.hops === 2 && Number.isFinite(custom.hop2Ms) && custom.folder === 'messages/a/b',
            `a custom thumbnail is still a two-hop sample through the 307 (${custom.error ?? 'no error'})`);
        const redirected = await fetch('redirected');
        expect(redirected.ok && !redirected.custom && redirected.hops === 2 && redirected.file === 'map_256.png',
            'a generated image an older backend redirects to is a two-hop sample');
        const oldPlaceholder = await fetch('old-placeholder');
        expect(oldPlaceholder.placeholder && !oldPlaceholder.ok, 'an older backend\'s redirect to the placeholder file is counted, not timed');
        const html = await fetch('html');
        expect(!html.ok && /expected an image or a redirect, got 200 text\/html/.test(html.error ?? ''), `a 200 that is no image fails (${html.error})`);
        expect(ctx.imageHops.oneHop === 1 && ctx.imageHops.twoHop === 2, `the run counts its images by hops (${JSON.stringify(ctx.imageHops)})`);

        const counts = imageCounts([generated, custom, redirected, placeholder, html]);
        expect(counts.ok === 3 && counts.oneHop === 1 && counts.twoHop === 2 && counts.placeholders === 1 && counts.errors === 1,
            `the case counts tell one-hop from two-hop images (${JSON.stringify({ ok: counts.ok, oneHop: counts.oneHop, twoHop: counts.twoHop })})`);
        const oneHopCase = newCase('t', 'one hop');
        imageMetrics(oneHopCase, '', [generated, generated]);
        expect(oneHopCase.metrics.total.samples.length === 2 && !('redirect' in oneHopCase.metrics) && !('file' in oneHopCase.metrics),
            'a case of one-hop images keeps the whole fetch, and no 307 or file time');
        const mixedCase = newCase('t', 'mixed');
        imageMetrics(mixedCase, 'cached', [generated, custom, redirected]);
        expect(mixedCase.metrics['cached.total'].samples.length === 3 && mixedCase.metrics['cached.redirect'].samples.length === 2
            && mixedCase.metrics['cached.file'].samples.length === 2, 'the 307 and the file are timed for the two-hop images only');

        const legacyOld = { cases: { 1: { metrics: { redirect: { unit: 'ms', samples: [3, 4] }, total: { unit: 'ms', samples: [9, 10] } } } } };
        const oneHopNew = { imageHops: { oneHop: 50, twoHop: 0 }, cases: {} };
        const twoHopNew = { imageHops: { oneHop: 0, twoHop: 50 }, cases: {} };
        const noImages = { cases: { 5: { metrics: { save: { unit: 'ms', samples: [1] } } } } };
        expect(hopShape(legacyOld) === 'two' && hopShape(oneHopNew) === 'one' && hopShape(noImages) === 'none',
            'a result file without imageHops that timed a 307 is two-hop; one that timed no image is neither');
        const note = hopNote([legacyOld], [oneHopNew]) ?? '';
        expect(/old runs took two requests each/.test(note) && /new runs one request each/.test(note),
            `compare says when the old runs are two-hop and the new one-hop (${note})`);
        expect(hopNote([legacyOld], [twoHopNew]) === null && hopNote([noImages], [oneHopNew]) === null,
            'compare says nothing when both sides took the same hops or one side served no image');
        // A legacy file of cases that time no 307 (2c, 4, 4b, 7, R2) still served two-hop images: its counts say so.
        const legacyR2 = { cases: { R2: { metrics: { 'c6.total': { unit: 'ms', samples: [20, 22] } }, counts: { c6: { requests: 100, ok: 100, placeholders: 0, errors: 0 }, expectedMapImages: 100 } } } };
        const legacyCold = { cases: { 4: { metrics: {}, counts: { 'cold.c6': { requests: 10, ok: 10 } } } } };
        const legacyTop = { cases: { '2c': { metrics: {}, counts: { requests: 5, ok: 5, firstErrors: [] } } } };
        const legacyNothingServed = { cases: { R2: { metrics: {}, counts: { c6: { requests: 10, ok: 0, errors: 10 }, mapImagesPerPdf: [3] } } } };
        expect(hopShape(legacyR2) === 'two' && hopShape(legacyCold) === 'two' && hopShape(legacyTop) === 'two'
            && hopShape(legacyNothingServed) === 'none',
            'a result file without imageHops that counted a served image is two-hop, also with no 307 timed; one that served none is neither');
        const r2Note = hopNote([legacyR2], [oneHopNew]) ?? '';
        expect(/old runs took two requests each/.test(r2Note) && /new runs one request each/.test(r2Note),
            `compare says when an old R2-only file is two-hop and the new one one-hop (${r2Note})`);
    } finally {
        destroyAgents(agents);
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    }
}

/**
 * The local mode's files against a stand-in of the search and the image endpoint and a cache tree in a
 * temporary folder: each message's folder comes from the search answer (its repoPath, else its UID), not
 * from the image's answer; discover learns the file name from the folder under --cache; the deletion
 * removes the map images there and nothing else; a set of an earlier version gets its folders from the
 * UIDs; and --repo is refused, also by the script itself before it asks a backend anything.
 */
async function selftestCacheTree(expect) {
    const png = templates()[0].png;
    const u1 = '1a2b3c4d-0000-4000-8000-000000000001';
    const u2 = '5e6f7a8b-0000-4000-8000-000000000002';
    const u3 = '9c0d1e2f-0000-4000-8000-000000000003';
    const geometry = [{ geometry: { features: [{ type: 'Feature' }] } }];
    const page = {
        total: 5,
        data: [
            { id: u1, shortId: 'NW-001-26', parts: geometry },
            { id: u2, shortId: 'NW-002-26', repoPath: 'messages/own/place', parts: geometry },
            { id: 'not-a-uid', parts: geometry },
            { id: u3, repoPath: 'messages/../../outside', parts: geometry },
            { id: '7f7f7f7f-0000-4000-8000-000000000004', parts: [] },
        ],
    };
    const server = http.createServer((req, res) => {
        const at = `http://127.0.0.1:${server.address().port}`;
        const url = new URL(req.url, at);
        if (url.pathname === '/rest/messages/search') {
            const body = Buffer.from(JSON.stringify(page));
            res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': body.length });
            return res.end(body);
        }
        if (url.pathname === `/rest/message-map-image/${u2}.png`) {
            // A custom thumbnail in another folder: its 307 names neither the message's folder nor a map image
            res.writeHead(307, { Location: `${at}/rest/repo/file/thumbs/x/custom_thumb.png`, 'Content-Length': 0 });
            return res.end();
        }
        if (url.pathname.startsWith('/rest/message-map-image/') || url.pathname === '/rest/repo/file/thumbs/x/custom_thumb.png') {
            res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length });
            return res.end(png);
        }
        res.writeHead(404, { 'Content-Length': 0 });
        return res.end();
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const cache = fs.mkdtempSync(path.join(os.tmpdir(), `${TOOL}-cache-`));
    try {
        const ctx = {
            base: `http://127.0.0.1:${server.address().port}`, lang: 'en', domain: null, cache,
            tokens: { bearer: async () => 'selftest' }, imageTimeoutMs: 5000, imageHops: { oneHop: 0, twoHop: 0 },
        };
        const u1Folder = `messages/1/a2/${u1}`;
        const set = await searchSet(ctx, 'status=PUBLISHED', 50);
        expect(set.folders[u1] === u1Folder,
            `a message the search names without a repoPath has the folder the backend gives its UID (${set.folders[u1]})`);
        expect(set.folders[u2] === 'messages/own/place', `a repoPath the search answer carries is the message's folder (${set.folders[u2]})`);
        expect(!('not-a-uid' in set.folders) && !(u3 in set.folders),
            `a message with no UID and no repoPath, or with a repoPath that climbs, has no folder: nothing is deleted for it (${JSON.stringify(Object.keys(set.folders))})`);

        // The backend has drawn u1's image twice (an older and a current portrayal) beside a file of another kind
        const dir = folderPath(cache, u1Folder);
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'map_256.png'), png);
        fs.utimesSync(path.join(dir, 'map_256.png'), new Date(Date.now() - 60_000), new Date(Date.now() - 60_000));
        fs.writeFileSync(path.join(dir, 'map_256_0a1b2c.png'), png);
        fs.writeFileSync(path.join(dir, 'notes.pdf'), 'kept');
        const discovery = await discover(ctx, set);
        expect(discovery.oneHop >= 3 && set.files[u1] === 'map_256_0a1b2c.png',
            `discover learns a one-hop image's file name from its folder under --cache, the later stamped map image (${set.files[u1]})`);
        expect(!(u2 in set.files) && set.folders[u2] === 'messages/own/place',
            "a custom thumbnail's 307 changes neither the message's folder nor its file name");
        expect(imageNaming(Object.values(set.files)) === 'versioned' && imageNaming([]) === 'unknown'
            && imageNaming(['map_256.png']) === 'unversioned',
            'the naming probe reads the learnt names, and says unknown when it learnt none');

        // The backend stamps an image with the message's change time, so a superseded map_256.png left by an
        // earlier run (a stale state, case 3b) carries the same time as the current versioned image
        const stamp = new Date(Date.now() - 120_000);
        fs.utimesSync(path.join(dir, 'map_256.png'), stamp, stamp);
        fs.utimesSync(path.join(dir, 'map_256_0a1b2c.png'), stamp, stamp);
        delete set.files[u1];
        await discover(ctx, set);
        expect(set.files[u1] === 'map_256_0a1b2c.png' && imageNaming(Object.values(set.files)) === 'versioned',
            `with map_256.png and map_256_<version>.png of the same time, discover learns the versioned name (${set.files[u1]})`);

        const removed = deleteMapImages(cache, folders(set));
        expect(removed.deleted === 2 && removed.failed === 0 && fs.readdirSync(dir).join(',') === 'notes.pdf',
            `the deletion removes the map images in the message's folder under --cache and nothing else (${removed.deleted} deleted; left ${fs.readdirSync(dir).join(',')})`);

        // A set file of the version that learnt its folders from the 307s
        const oldSetFile = path.join(cache, 'old-set.json');
        fs.writeFileSync(oldSetFile, JSON.stringify({ set: { uids: [u1, 'legacy-id'], folders: { [u1]: 'thumbs/x', 'legacy-id': 'messages/l/eg/legacy-id' } } }));
        const reused = await resolveSet(ctx, { set: oldSetFile }, 50);
        expect(reused.folders[u1] === u1Folder && reused.folders['legacy-id'] === 'messages/l/eg/legacy-id',
            `a reused set takes each folder from the UID, and keeps an earlier folder only for a UID that gives none (${JSON.stringify(reused.folders)})`);

        const refusal = repoFlagRefusal({ repo: '/home/repo' }) ?? '';
        expect(/--repo/.test(refusal) && /--cache <niord\.home>\/cache/.test(refusal) && repoFlagRefusal({ cache: cache }) === null,
            '--repo is refused with a message that names --cache');
        const run = spawnSync(process.execPath, [fileURLToPath(import.meta.url), 'local', '--variant', 'new', '--base', 'http://127.0.0.1:9', '--repo', cache],
            { encoding: 'utf8', timeout: 30_000 });
        expect(run.status === 2 && /--cache <niord\.home>\/cache/.test(run.stderr) && !/no backend/.test(run.stderr),
            `the script refuses an old command line with --repo before it asks a backend anything (exit ${run.status})`);
    } finally {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
        fs.rmSync(cache, { recursive: true, force: true });
    }
}

/**
 * The message reads against a stand-in of the three endpoints, which answers only the request each
 * application sends: the list search with a token, NW and NM and either no page size (the default 100) or
 * the map view's; the public list and message without a token, with lang and externalize=false.
 */
async function selftestMessageReads(expect) {
    const ids = ['1a2b3c4d-0000-4000-8000-000000000001', '5e6f7a8b-0000-4000-8000-000000000002'];
    let publicList = ids.map((id) => ({ id }));
    const hits = { search: 0, map: 0, list: 0, message: {} };
    const server = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://127.0.0.1');
        const q = url.searchParams;
        const answer = (status, body) => {
            const buf = Buffer.from(JSON.stringify(body));
            res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': buf.length });
            res.end(buf);
        };
        const anonymous = !req.headers.authorization && !req.headers.niorddomain;
        const publicQuery = q.get('lang') === 'en' && q.get('externalize') === 'false';
        if (url.pathname === '/rest/messages/search') {
            const filter = q.getAll('mainType').join(',') === 'NW,NM' && q.get('lang') === 'en' && Boolean(req.headers.authorization);
            const page = !q.has('maxSize') && !q.has('viewMode');
            const map = q.get('maxSize') === '1000' && q.get('viewMode') === 'map' && q.get('includeNoPos') === 'true' && q.get('page') === '0';
            if (filter && (page || map)) {
                hits[map ? 'map' : 'search']++;
                return answer(200, { total: 3, data: [{ id: ids[0] }, { id: ids[1] }, { id: 'x' }] });
            }
            return answer(400, { error: 'not the request the administration app sends' });
        }
        if (url.pathname === '/rest/public/v1/messages' && anonymous && publicQuery) {
            hits.list++;
            return answer(200, publicList);
        }
        const one = url.pathname.match(/^\/rest\/public\/v1\/message\/([^/]+)$/);
        if (one && anonymous && publicQuery && ids.includes(decodeURIComponent(one[1]))) {
            hits.message[one[1]] = (hits.message[one[1]] ?? 0) + 1;
            return answer(200, { id: one[1] });
        }
        return answer(404, {});
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
        const ctx = { base: `http://127.0.0.1:${server.address().port}`, lang: 'en', domain: null, tokens: { bearer: async () => 'selftest' } };
        const o = { searches: 3, messageGets: 4, count: 50 };
        const page = await adminListCase(ctx, '6b', false, o, 1);
        expect(page.metrics.listSearch.summary.n === 3 && hits.search === 4 && page.counts.listed === 3 && page.counts.total === 3
            && page.checks.every((k) => k.pass), `6b sends the NW and NM list search with no page size, one uncounted (${hits.search} requests)`);
        const map = await adminListCase(ctx, 'R7', true, o, 1);
        expect(map.metrics.listSearch.summary.n === 3 && hits.map === 4 && map.checks.every((k) => k.pass),
            `R7 sends the map view's search: maxSize=1000, viewMode=map, includeNoPos=true (${hits.map} requests)`);
        const reads = await publicReadsCase(ctx, '6d', o, 2, 1);
        expect(reads.metrics.publicList.summary.n === 3 && reads.metrics.publicMessageGet.summary.n === 4
            && hits.message[ids[0]] === 3 && hits.message[ids[1]] === 3 && reads.counts.messages === 2 && reads.checks.every((k) => k.pass),
            `6d reads the public list, then each listed message in turn, without a token (${JSON.stringify(hits.message)})`);
        publicList = [];
        const empty = await publicReadsCase(ctx, 'R8', o, 0, 0);
        expect(empty.checks.some((k) => !k.pass && /named no message/.test(k.detail)) && !('publicMessageGet' in empty.metrics),
            'an empty public list fails the case rather than timing nothing');
        const refused = await adminListCase({ ...ctx, lang: 'da' }, '6b', false, o, 0);
        expect(refused.checks.some((k) => !k.pass && /answered 400/.test(k.detail)) && refused.metrics.listSearch.summary.n === 0,
            'a refused list search is a failed read, not a fast sample');
    } finally {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    }
}

async function runSelftest() {
    const problems = [];
    const expect = (ok, what) => {
        console.log(`${ok ? 'ok  ' : 'FAIL'}  ${what}`);
        if (!ok) {
            problems.push(what);
        }
    };
    const si = await startStandIns({ staticPort: 0, tilePort: 0, latency: 20, sigma: 0.3, seed: 7, quiet: true });
    const tiles = `http://127.0.0.1:${si.tilePort}`;
    const statics = `http://127.0.0.1:${si.staticPort}`;
    const agents = agentsFor(20);
    try {
        const t = await get(`${tiles}/osm/10/547/320.png`, { agents });
        const tile = decodePng(t.body);
        expect(t.status === 200 && tile.width === 256 && tile.height === 256 && tile.channels === 3, `tile answers a 256x256 RGB PNG (${t.bytes} bytes)`);
        expect(Boolean(t.headers.etag) && /max-age=\d+/.test(t.headers['cache-control'] ?? ''), 'tile carries an ETag and a Cache-Control max-age');
        expect(t.headers.connection !== 'close', 'the connection is kept alive');
        const again = await get(`${tiles}/osm/10/547/320.png`, { agents, headers: { 'If-None-Match': t.headers.etag } });
        expect(again.status === 304 && again.bytes === 0, 'If-None-Match with the ETag answers 304');
        const h2c = await get(`${tiles}/osm/10/547/321.png`, { headers: { Connection: 'Upgrade, HTTP2-Settings', Upgrade: 'h2c', 'HTTP2-Settings': 'AAMAAABkAARAAAAAAAIAAAAA' } });
        expect(h2c.status === 200 && h2c.bytes > 0, 'a request offering "Upgrade: h2c" is answered as plain HTTP/1.1');

        // A static map centred on the middle of tile 547/320 at zoom 10 must show exactly that tile.
        const size = 256 * 2 ** 10;
        const lon = ((547 * 256 + 128) / size) * 360 - 180;
        const lat = (Math.atan(Math.sinh(Math.PI * (1 - (2 * (320 * 256 + 128)) / size))) * 180) / Math.PI;
        const dot = await get(`${statics}/staticmap.php?center=${lat.toFixed(6)},${lon.toFixed(6)}&zoom=10&size=256x256`, { agents });
        const map = decodePng(dot.body);
        let same = 0;
        for (let i = 0; i < map.pixels.length; i += 3) {
            same += map.pixels[i] === tile.pixels[i] && map.pixels[i + 1] === tile.pixels[i + 1] && map.pixels[i + 2] === tile.pixels[i + 2] ? 1 : 0;
        }
        expect(dot.status === 200 && same / (map.pixels.length / 3) > 0.99, `the static map is drawn from the same synthetic map as the tiles (${((100 * same) / (map.pixels.length / 3)).toFixed(1)} % identical pixels)`);
        const comma = await get(`${statics}/staticmap.php?center=${lat.toFixed(6).replace('.', ',')},${lon.toFixed(6).replace('.', ',')}&zoom=10&size=300x300`, { agents });
        const wide = decodePng(comma.body);
        expect(comma.status === 200 && wide.width === 300 && wide.height === 300, 'a centre written with decimal commas is read too (300x300, what the previous renderer asks for)');
        const sea = await get(`${tiles}/seamark/10/547/320.png`, { agents });
        expect(sea.status === 200 && decodePng(sea.body).channels === 4, 'a seamark tile is a transparent RGBA PNG');
        const bad = await get(`${tiles}/osm/3/99/0.png`, { agents });
        expect(bad.status === 404, 'a tile outside the zoom level answers 404');

        const sizes = templates().map((t) => t.png.length).sort((a, b) => a - b);
        console.log(`      tile template sizes: ${sizes[0]}..${sizes[sizes.length - 1]} bytes, median ${percentile(sizes, 0.5)}; static map ${comma.bytes} bytes`);

        const lat20 = [];
        for (let i = 0; i < 30; i++) {
            lat20.push((await get(`${tiles}/osm/12/${2190 + i}/1280.png`, { agents })).totalMs);
        }
        const med = percentile(lat20.sort((a, b) => a - b), 0.5);
        const tick = process.platform === 'win32' ? 16 : 2;
        expect(med > 14 && med < 20 + tick + 8, `latency is drawn around the median (median ${med.toFixed(1)} ms for 20 ms; the timer tick here is about ${tick} ms)`);
        const tc = performance.now();
        await runPool(Array.from({ length: 20 }, (_, i) => i), 20, (i) => get(`${tiles}/osm/11/${1090 + i}/640.png`, { agents }));
        const makespan = performance.now() - tc;
        expect(makespan < 20 * 20 * 0.6, `20 tiles at once are served concurrently (makespan ${makespan.toFixed(0)} ms)`);
        const ts = performance.now();
        await runPool(Array.from({ length: 20 }, (_, i) => i), 20, (i) => get(`${statics}/staticmap.php?center=55.${100 + i},11.${300 + i}&zoom=9&size=300x300`, { agents }));
        const staticSpan = performance.now() - ts;
        expect(staticSpan < 20 * 20 * 0.6, `20 new static maps at once are served concurrently (makespan ${staticSpan.toFixed(0)} ms)`);

        const stats = jsonBody(await get(`${statics}/__stats`));
        expect(stats.routes.osm.notModified === 1, 'the stats count the 304');
        expect(stats.routes.staticmap.ok === 22 && stats.commaDecimalCentres === 1, 'the stats count every static map and the comma-decimal centre');
        expect(stats.routes.seamark.requests === 1, 'the stats count the seamark request');
        expect(Object.keys(stats.userAgents).length > 0, 'the stats record the User-Agent');
        expect(stats.paths['/osm/10/547/320.png'] === 2, 'the stats count hits per path');
        expect(stats.deliveredLatencyMs.n > 50, `the stats report the delivered latency (median ${stats.deliveredLatencyMs.median} ms, p95 ${stats.deliveredLatencyMs.p95} ms)`);
        expect(stats.staticMap.model === 'static' && stats.routes.staticmapTiles.requests === 0, 'the static model fetches no tile for a static map');

        // The fetching model: a 300 px window centred on the middle of tile 10/547/320 covers tiles 546-548 x 319-321
        const fsi = await startStandIns({ staticPort: 0, tilePort: 0, latency: 20, sigma: 0.3, seed: 7, quiet: true, staticMapModel: 'fetching', lanLatency: 2 });
        try {
            const fetchingStatics = `http://127.0.0.1:${fsi.staticPort}`;
            const query = `/staticmap.php?center=${lat.toFixed(6)},${lon.toFixed(6)}&zoom=10&size=300x300`;
            const fstats = async () => jsonBody(await get(`${fetchingStatics}/__stats`));
            const first = await get(`${fetchingStatics}${query}`, { agents });
            let fs1 = await fstats();
            expect(first.status === 200 && decodePng(first.body).width === 300 && fs1.routes.staticmapTiles.ok === 9 && fs1.routes.osm.ok === 0,
                `fetching model: a static map fetches the 9 tiles under its window, counted apart from the tile renderer's (${fs1.routes.staticmapTiles.ok})`);
            const again = await get(`${fetchingStatics}${query}`, { agents });
            fs1 = await fstats();
            expect(again.status === 200 && fs1.routes.staticmapTiles.ok === 9, 'fetching model: the same map again fetches no tile');
            expect(again.totalMs < first.totalMs && again.totalMs < 12, `fetching model: a map of held tiles answers after the LAN hop (${again.totalMs.toFixed(1)} ms, the first ${first.totalMs.toFixed(1)} ms)`);
            const cleared = jsonBody(await request('POST', `${fetchingStatics}/__static-map/clear-tiles`));
            expect(cleared.cleared === 9, `fetching model: clearing empties its tile cache (${cleared.cleared} tiles)`);
            await runPool(Array.from({ length: 5 }, (_, i) => i), 5, () => get(`${fetchingStatics}${query}`, { agents }));
            fs1 = await fstats();
            expect(fs1.routes.staticmapTiles.ok === 18, `fetching model: concurrent maps share one fetch per tile (${fs1.routes.staticmapTiles.ok - 9} for 5 maps)`);
            const hop = fs1.staticMap.deliveredLanHopMs;
            expect(hop.n === 7 && hop.median >= 2 && hop.median < 6, `fetching model: the LAN hop is delivered (median ${hop.median} ms for 2 ms)`);
        } finally {
            await fsi.close();
        }

        await selftestMapImageAnswers(expect);
        await selftestCacheTree(expect);
        await selftestMessageReads(expect);
    } catch (e) {
        expect(false, `selftest aborted: ${e.stack}`);
    } finally {
        destroyAgents(agents);
        await si.close();
    }
    console.log(problems.length === 0 ? '\nselftest passed' : `\nselftest FAILED (${problems.length})`);
    return problems.length === 0 ? 0 : 1;
}

// ------------------------------------------------------------------------------------------------ main

async function runStandIns(flags) {
    const host = flags.host ?? '127.0.0.1';
    const latency = num(flags, 'latency', 250);
    const sigma = num(flags, 'sigma', 0.5);
    const seed = num(flags, 'seed', 1);
    const staticMapModel = flags['static-map-model'] ?? 'static';
    if (!STATIC_MAP_MODELS.includes(staticMapModel)) {
        fail(`--static-map-model takes ${STATIC_MAP_MODELS.join(' or ')}`);
    }
    const lanLatency = num(flags, 'lan-latency', 2);
    const si = await startStandIns({
        host,
        staticPort: num(flags, 'static-port', 9101),
        tilePort: num(flags, 'tile-port', 9102),
        latency,
        sigma,
        seed,
        quiet: Boolean(flags.quiet),
        staticMapModel,
        lanLatency,
    });
    console.log(`static map  http://${host}:${si.staticPort}/staticmap.php?center=<lat>,<lon>&zoom=<z>&size=<w>x<h>`);
    console.log(`tiles       http://${host}:${si.tilePort}/osm/{z}/{x}/{y}.png   (/seamark/{z}/{x}/{y}.png is counted: nothing may ask for it)`);
    console.log(`stats       http://${host}:${si.staticPort}/__stats   (either port)`);
    console.log(`latency     lognormal, median ${latency} ms, sigma ${sigma}, seed ${seed}; Ctrl+C stops`);
    console.log(staticMapModel === 'fetching'
        ? `static-map  fetching model: tiles from the tile stand-in, kept until POST /__static-map/clear-tiles; LAN hop ${lanLatency} ms`
        : 'static-map  static model: one latency draw per static map');
    const stop = async () => {
        await si.close();
        process.exit(0);
    };
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    return new Promise(() => {});
}

async function main() {
    const [first, ...rest] = process.argv.slice(2);
    const sub = first?.startsWith('-') ? 'help' : first;
    const args = first?.startsWith('-') ? [] : rest;
    let flags;
    try {
        flags = parseArgs({ args, options: OPTIONS, strict: true, allowPositionals: false }).values;
    } catch (e) {
        fail(`${e.message}\n\n${USAGE}`);
    }
    if (!sub || sub === 'help' || flags.help) {
        console.log(USAGE);
        return 0;
    }
    const refusal = repoFlagRefusal(flags);
    if (refusal) {
        fail(refusal);
    }
    switch (sub) {
        case 'stand-ins':
            return runStandIns(flags);
        case 'local':
            return runLocal(flags);
        case 'remote':
            return runRemote(flags);
        case 'compare':
            return runCompare(flags);
        case 'selftest':
            return runSelftest();
        default:
            fail(`unknown subcommand "${sub}"\n\n${USAGE}`);
    }
    return 2;
}

main().then(
    (code) => {
        process.exitCode = code ?? 0;
    },
    (e) => {
        console.error(`${TOOL} FAILED: ${e.stack ?? e.message}`);
        process.exitCode = 1;
    },
);
