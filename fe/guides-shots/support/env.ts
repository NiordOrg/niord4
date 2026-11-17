import { test } from '@playwright/test';

import { joinPath } from './paths';

/*
 * Where the rig finds the stack it photographs and where the pictures go. Each value can be set in
 * the environment; the defaults are the rig's own stack, which start-rig.sh starts on slot 6: the
 * administration app on 4106, the public site on 4406 and the backend on 8894, beside the shared
 * Keycloak on 8080 and MailHog on 8025.
 */

// The slice of Node's `process` the rig reads: the folder's tsconfig sets `types: []`, and
// @types/node is not a dependency of the workspace.
declare const process: { readonly env: Record<string, string | undefined> };

function url(name: string, fallback: string): string {
  return (process.env[name]?.trim() || fallback).replace(/\/+$/, '');
}

function value(name: string, fallback: string): string {
  return process.env[name]?.trim() || fallback;
}

export const ADMIN_URL = url('GUIDES_ADMIN_URL', 'http://localhost:4106');
export const INFO_URL = url('GUIDES_INFO_URL', 'http://localhost:4406');
export const BACKEND_URL = url('GUIDES_BACKEND_URL', 'http://localhost:8894');
export const KEYCLOAK_URL = url('GUIDES_KEYCLOAK_URL', 'http://localhost:8080/auth');
export const MAILHOG_URL = url('GUIDES_MAILHOG_URL', 'http://localhost:8025');

/**
 * The copy's database, which the rig reads (never writes) for the rule its example messages are
 * picked by (english.sql's guides.english_messages): psql in the shared database container.
 */
export const DB = {
  container: value('GUIDES_DB_CONTAINER', 'niord-db'),
  name: value('GUIDES_DB', 'niord_guides'),
  user: value('GUIDES_DB_USER', 'niord'),
} as const;

/** The session domain every authenticated page opens in. */
export const DOMAIN = value('GUIDES_DOMAIN', 'niord-nw');

/** The instant a page that shows "now" is given (fixClock), so a picture taken again matches. */
export const NOW = value('GUIDES_NOW', '2026-11-11T10:00:00+01:00');

/** The local Keycloak's bootstrap administrator, from the compose file: a loopback-only console. */
export const KEYCLOAK_ADMIN = {
  username: value('GUIDES_KEYCLOAK_ADMIN', 'admin'),
  password: value('GUIDES_KEYCLOAK_ADMIN_PASSWORD', 'admin'),
} as const;

/** The origins of the stack itself; a console error from any other origin is a third party's. */
export const APP_ORIGINS: readonly string[] = [ADMIN_URL, INFO_URL, BACKEND_URL].map(
  (address) => new URL(address).origin,
);

/**
 * The mail domains a picture may show without a note. The scrubbed data's people have example.org
 * addresses only; another address on a page is recorded (or refused, see STRICT_MAIL). niord.local
 * is the local realm's own invented domain: a shared Keycloak that imported an older local realm
 * holds <login>@niord.local, and a sign-in copies it into the user's row.
 */
export const ALLOWED_MAIL_DOMAINS: readonly string[] = [
  'example.org',
  'example.com',
  'example.net',
  'niord.local',
  ...value('GUIDES_ALLOWED_MAIL_DOMAINS', '')
    .split(',')
    .map((domain) => domain.trim().toLowerCase())
    .filter(Boolean),
];

/**
 * Whether an e-mail address outside ALLOWED_MAIL_DOMAINS refuses a picture (GUIDES_STRICT_MAIL=1).
 * By default it is only recorded: the scrubbed data keeps organisations' mailboxes in published
 * notices and templates, which may show; a private person's address may not, and the report beside
 * the pictures lists every address seen, masked, for a reviewer to judge.
 */
export const STRICT_MAIL = value('GUIDES_STRICT_MAIL', '') === '1';

/** The report of the e-mail addresses a picture shows, written beside the pictures. */
export const MAIL_REPORT = 'mail-report.txt';

/**
 * The map tile cache's folder (GUIDES_TILE_CACHE; by default .tile-cache/ beside the specs, which
 * git ignores), or `off` to fetch every tile from its server.
 */
export const TILE_CACHE = value('GUIDES_TILE_CACHE', '');

/** The guides' folder: GUIDES_DIR, or the repository's guides/ beside fe/. */
export function guidesDir(): string {
  return value('GUIDES_DIR', '') || joinPath(test.info().project.testDir, '..', '..', 'guides');
}

/** Where the pilot's pictures go: GUIDES_PILOT_DIR, or .pilot/ beside the specs (gitignored). */
export function pilotDir(): string {
  return value('GUIDES_PILOT_DIR', '') || joinPath(test.info().project.testDir, '.pilot');
}
