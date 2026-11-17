import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';

import { test, type Frame, type Page } from '@playwright/test';

import { psql } from './backend';
import { buildDictionaryIndex, danishReason, forbiddenTopic, type DictionaryIndex } from './danish';
import { ALLOWED_MAIL_DOMAINS, APP_ORIGINS, MAIL_REPORT, STRICT_MAIL } from './env';
import { joinPath } from './paths';

/** What a page reported while a test ran, collected from the moment the page was created. */
interface Findings {
  readonly consoleErrors: string[];
  readonly pageErrors: string[];
  readonly serverErrors: string[];
  readonly missingKeys: Set<string>;
}

const findingsByPage = new WeakMap<Page, Findings>();

// Transloco logs every key it has no text for in a development build, which both dev servers are.
const MISSING_TRANSLATION = /Missing translation for '([^']+)'/;

// Console errors that say nothing about the page in the picture.
const ALWAYS_ALLOWED_CONSOLE = [/ResizeObserver loop/];

/*
 * A sandboxed frame (the administration app's `app-sandboxed-html`, an <iframe sandbox="">: a mail
 * in the mail queue, a report's result, a mailing list's test mail) may run no script and has an
 * opaque origin. Playwright runs every init script in every frame, its own and the rig's, so two
 * things are logged there that the application never raised:
 * - the browser's refusal to run a script in the frame, logged by Chromium for Playwright's own
 *   script even when the rig adds none (it names the frame: about:blank before the frame's
 *   document arrives, about:srcdoc after);
 * - the end-to-end suite's token seeding (ui-apps/admin/e2e/auth/inject-auth.ts, which the rig does
 *   not change) reading localStorage, which a sandboxed document without allow-same-origin
 *   refuses. A page error carries no frame, so it is known by its message, which only a sandboxed
 *   document raises, and by its stack, which names no file: an init script's, not the
 *   application's (the application's code is in its chunk files, and the frame runs none of it).
 * Exactly these two are left out; any other error in such a frame still counts.
 */
const SANDBOX_BLOCKED_SCRIPT =
  /^Blocked script execution in 'about:(?:blank|srcdoc)' because the document's frame is sandboxed and the 'allow-scripts' permission is not set\.$/;
const SANDBOX_STORAGE_MESSAGE =
  "Failed to read the 'localStorage' property from 'Window': The document is sandboxed and lacks the 'allow-same-origin' flag.";

/** Whether a page error is the e2e seeding's storage read failing in a sandboxed frame. */
export function isSandboxedInitScriptError(error: Error): boolean {
  if (error.message !== SANDBOX_STORAGE_MESSAGE) return false;
  const frames = (error.stack ?? '').split('\n').filter((line) => /^\s*at /.test(line));
  return (
    frames.length > 0 &&
    frames.every((line) => /^\s*at (?:.* \()?<anonymous>(?::\d+){0,2}\)?\s*$/.test(line))
  );
}

// Keys the public site asks for knowing that the base build has no text for them: the map's
// credit lines come from a country's branding file, which the base build does not ship
// (country-specific/default/language.config.ts names no branding language). The map component
// reads the key's echo as "no credit" and shows OpenStreetMap's alone (message-map.component.ts,
// `attributions`), so the log names no text missing from the picture.
const UNBRANDED_KEYS: ReadonlySet<string> = new Set(['MAP_ACCESSIBILITY', 'MAP_COPYRIGHT']);

// An e-mail address in a text.
const MAIL_ADDRESS = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;

/** A console message's origin is a third party's (a tile server, a CDN) unless it is the stack's. */
function isThirdParty(url: string): boolean {
  if (!url) return false;
  try {
    return !APP_ORIGINS.includes(new URL(url).origin);
  } catch {
    return false;
  }
}

/** Starts collecting what the page reports: console errors, uncaught errors, 5xx answers, keys. */
export function watchPage(page: Page): void {
  const findings: Findings = {
    consoleErrors: [],
    pageErrors: [],
    serverErrors: [],
    missingKeys: new Set(),
  };
  findingsByPage.set(page, findings);
  page.on('console', (message) => {
    const text = message.text();
    const missing = MISSING_TRANSLATION.exec(text);
    if (missing?.[1]) {
      if (!UNBRANDED_KEYS.has(missing[1])) findings.missingKeys.add(missing[1]);
      return;
    }
    if (message.type() !== 'error' || isThirdParty(message.location().url)) return;
    if (SANDBOX_BLOCKED_SCRIPT.test(text)) return;
    findings.consoleErrors.push(text);
  });
  page.on('pageerror', (error) => {
    if (isSandboxedInitScriptError(error)) return;
    findings.pageErrors.push(error.message);
  });
  page.on('response', (response) => {
    if (response.status() >= 500 && !isThirdParty(response.url())) {
      findings.serverErrors.push(`${response.status()} ${response.url()}`);
    }
  });
}

/** What the guard holds a picture to. */
export interface GuardOptions {
  /**
   * `app` (default) checks everything; `external`, for a page that is not Niord's (Keycloak's
   * console, MailHog, a rendered PDF), looks only for e-mail addresses outside the allowed domains.
   */
  readonly checks?: 'app' | 'external';
  /** Console errors this picture may carry, for a known message that changes nothing visible. */
  readonly allowConsole?: readonly RegExp[];
  /** Texts that look like a translation key but are data, e.g. an identifier shown on purpose. */
  readonly allowText?: readonly string[];
  /**
   * Whether to look for texts shaped like a translation key (default true). A page that lists
   * keys as its data (the dictionaries, the settings) turns it off; Transloco's log still counts.
   */
  readonly keyScan?: boolean;
  /** Mail domains this picture may show beyond the scrubbed data's (see ALLOWED_MAIL_DOMAINS). */
  readonly allowMailDomains?: readonly string[];
  /**
   * Danish texts this picture may show, each with the reason: the rare picture that must show a
   * second-language field. A string allows a text that equals it, a pattern one that matches it.
   */
  readonly allowDanish?: readonly AllowedDanish[];
}

/** A Danish text a picture may show, and why. */
export interface AllowedDanish {
  readonly text: string | RegExp;
  readonly reason: string;
}

/**
 * The part of the page a picture shows, in the top document's window coordinates (CSS pixels from
 * the window's top left corner; a picture taller than the window reaches below it). shot() passes
 * it; without it the guard reads the whole page.
 */
export interface GuardRegion {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** A text the reader of a picture sees, and where it stands. */
interface SeenText {
  readonly text: string;
  readonly where: string;
  /** The sentence a piece of text stands in, when it is one piece of a longer sentence. */
  readonly within?: string;
}

/**
 * Every text a frame shows, in its document and in every open shadow root:
 * - each visible text node's sentence (the text of its nearest block, so that a sentence split by
 *   inline tags is read whole) and the text node's own text, since a block of inline controls
 *   ("Edit Verificér Publish") reads as one text no word list knows;
 * - the text of each visible button, link, label, option, menu item and tab on its own;
 * - the value of each visible field, the selected option of each visible list box, the
 *   placeholder and title of each visible element, the <title> of each visible SVG, the
 *   alternative text of an image that did not load (the browser shows it), and the generated
 *   content of ::before and ::after.
 * Each with where it stands: the nearest component's tag and the element's own. Visible means
 * rendered, not under `visibility: hidden` and not under `opacity: 0` (checkVisibility).
 */
function visibleTexts(frame: Frame): Promise<SeenText[]> {
  return frame
    .evaluate(() => {
      const seen: { text: string; where: string; within?: string }[] = [];
      const visible = (element: Element): boolean =>
        element.checkVisibility({ opacityProperty: true, visibilityProperty: true });
      const tidy = (text: string | null | undefined): string =>
        (text ?? '').replace(/\s+/g, ' ').trim();
      const add = (text: string | null | undefined, where: string, within?: string): void => {
        const trimmed = tidy(text);
        if (trimmed) seen.push({ text: trimmed, where, ...(within ? { within } : {}) });
      };
      const describe = (element: Element): string => {
        let host: Element | null = element;
        while (host && !host.tagName.includes('-')) {
          host = host.parentElement ?? ((host.getRootNode() as ShadowRoot).host || null);
        }
        const own = element.tagName.toLowerCase();
        const cls = element.classList[0] ? `.${element.classList[0]}` : '';
        return `${host ? `${host.tagName.toLowerCase()} ` : ''}${own}${cls}`;
      };
      // The document and every open shadow root in it (an array's iterator reaches what is pushed
      // while it runs, so a root inside a root is found too).
      const roots: (Document | ShadowRoot)[] = [document];
      for (const root of roots) {
        for (const element of root.querySelectorAll('*')) {
          if (element.shadowRoot) roots.push(element.shadowRoot);
        }
      }
      const CONTROLS =
        'button, a, label, [role="button"], [role="link"], [role="menuitem"], ' +
        '[role="menuitemradio"], [role="menuitemcheckbox"], [role="option"], [role="tab"]';
      for (const root of roots) {
        const top = root instanceof Document ? root.body : root;
        if (!top) continue;
        const blocks = new Set<Element>();
        const pieces: { text: string; element: Element; block: Element }[] = [];
        const walker = (root instanceof Document ? root : root.ownerDocument).createTreeWalker(
          top,
          NodeFilter.SHOW_TEXT,
        );
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (!(node.textContent ?? '').trim()) continue;
          const parent = node.parentElement;
          if (!parent || parent.closest('script, style, noscript')) continue;
          if (!visible(parent)) continue;
          let element = parent;
          while (
            element.parentElement &&
            element.parentElement !== document.body &&
            getComputedStyle(element).display.startsWith('inline')
          ) {
            element = element.parentElement;
          }
          blocks.add(element);
          pieces.push({ text: node.textContent ?? '', element: parent, block: element });
        }
        // The sentences first; then each piece of one on its own, named with its sentence (a
        // sentence the picture may show allows its pieces, and a refused one reports them once).
        const blockText = new Map<Element, string>();
        for (const block of blocks) {
          const text = tidy((block as HTMLElement).innerText ?? block.textContent);
          blockText.set(block, text);
          add(text, describe(block));
        }
        for (const piece of pieces) {
          add(piece.text, `${describe(piece.element)} (text)`, blockText.get(piece.block));
        }
        for (const control of root.querySelectorAll(CONTROLS)) {
          if (!visible(control)) continue;
          add((control as HTMLElement).innerText ?? control.textContent, describe(control));
        }
        const fields = root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
          'input:not([type="hidden"]):not([type="password"]), textarea',
        );
        for (const field of fields) {
          if (visible(field)) add(field.value, `${describe(field)} (value)`);
        }
        for (const select of root.querySelectorAll('select')) {
          if (!visible(select)) continue;
          for (const option of select.selectedOptions) {
            add(option.label || option.text, `${describe(select)} (selected option)`);
          }
        }
        for (const element of root.querySelectorAll('[placeholder], [title]')) {
          if (!visible(element)) continue;
          for (const name of ['placeholder', 'title']) {
            add(element.getAttribute(name), `${describe(element)} (${name})`);
          }
        }
        // An SVG's <title> (an icon's or a drawing's tooltip) has no box of its own, so the text
        // walk above skips it: it is read when its SVG is visible.
        for (const title of root.querySelectorAll('svg title')) {
          const svg = title.closest('svg');
          if (svg && visible(svg)) add(title.textContent, `${describe(svg)} (svg title)`);
        }
        for (const image of root.querySelectorAll('img[alt]')) {
          const img = image as HTMLImageElement;
          if (visible(img) && (!img.complete || img.naturalWidth === 0)) {
            add(img.alt, `${describe(img)} (alt of an image that did not load)`);
          }
        }
        for (const element of root.querySelectorAll('*')) {
          for (const pseudo of ['::before', '::after']) {
            const content = getComputedStyle(element, pseudo).content;
            if (!content || content === 'none' || content === 'normal') continue;
            const text = (content.match(/"(?:[^"\\]|\\.)*"/g) ?? [])
              .map((part) => part.slice(1, -1).replace(/\\(.)/g, '$1'))
              .join('');
            if (text.trim() && visible(element)) {
              add(text, `${describe(element)} (${pseudo})`);
            }
          }
        }
      }
      return seen;
    })
    .catch(() => []);
}

/**
 * The texts of a frame an e-mail address is looked for in: the text of each block (its own text
 * nodes, in order, so an address split by inline tags is read whole) of which a visible text node
 * shows within `region`, and the value of each visible field that shows within it; `region` in
 * the frame's own window coordinates, or null for the whole frame. Visible as in visibleTexts. A
 * block cut by the region's edge is read whole.
 *
 * Shows: some point of the text within the region is not covered by a layer that hides it, one
 * painted above it (a dialog over the list, a sticky bar over the page scrolled under it) with a
 * solid background colour, fully opaque. A translucent layer (a dialog's scrim, which leaves the
 * page legible) hides nothing, nor does a picture, a gradient or a filtered layer; a point the
 * browser cannot tell (outside the window, a text that takes no pointer) counts as shown. Runs in
 * the frame, so it is self-contained.
 */
const MAIL_TEXTS = (region: GuardRegion | null): string[] => {
  const visible = (element: Element): boolean =>
    element.checkVisibility({ opacityProperty: true, visibilityProperty: true });
  const alpha = (colour: string): number => {
    if (colour === 'transparent') return 0;
    const slash = /\/\s*([\d.]+)(%?)\s*\)$/.exec(colour);
    if (slash) return parseFloat(slash[1] ?? '0') / (slash[2] ? 100 : 1);
    const rgba = /^rgba\([^,]+,[^,]+,[^,]+,\s*([\d.]+)\s*\)$/.exec(colour);
    if (rgba) return parseFloat(rgba[1] ?? '0');
    return /^(?:rgb|hsl|hwb|lab|lch|oklab|oklch|color)\(/.test(colour) ? 1 : 0;
  };
  const up = (node: Element): Element | null =>
    node.parentElement ?? ((node.getRootNode() as ShadowRoot).host || null);
  const hides = (layer: Element): boolean => {
    const style = getComputedStyle(layer);
    if (alpha(style.backgroundColor) < 1) return false;
    if (style.filter !== 'none' || style.mixBlendMode !== 'normal') return false;
    for (let node: Element | null = layer; node; node = up(node)) {
      if (parseFloat(getComputedStyle(node).opacity) < 1) return false;
    }
    return true;
  };
  const isAncestor = (candidate: Element, of: Element): boolean => {
    for (let node: Element | null = of; node; node = up(node)) if (node === candidate) return true;
    return false;
  };
  const shownAt = (element: Element, x: number, y: number): boolean => {
    const root = element.getRootNode() as Document | ShadowRoot;
    const stack = root.elementsFromPoint(x, y);
    const at = stack.indexOf(element);
    if (at < 0) return true;
    return !stack.slice(0, at).some((layer) => !isAncestor(layer, element) && hides(layer));
  };
  const shows = (element: Element, rect: DOMRectReadOnly): boolean => {
    if (rect.width <= 0 && rect.height <= 0) return false;
    const left = Math.max(rect.left, region ? region.x : -Infinity);
    const top = Math.max(rect.top, region ? region.y : -Infinity);
    const right = Math.min(rect.right, region ? region.x + region.width : Infinity);
    const bottom = Math.min(rect.bottom, region ? region.y + region.height : Infinity);
    if (right <= left || bottom <= top) return false;
    const [inLeft, inRight] = right - left > 2 ? [left + 1, right - 1] : [left, right];
    const [inTop, inBottom] = bottom - top > 2 ? [top + 1, bottom - 1] : [top, bottom];
    return [
      [(left + right) / 2, (top + bottom) / 2],
      [inLeft, inTop],
      [inRight, inTop],
      [inLeft, inBottom],
      [inRight, inBottom],
    ].some(([x, y]) => shownAt(element, x ?? 0, y ?? 0));
  };
  const roots: (Document | ShadowRoot)[] = [document];
  for (const root of roots) {
    for (const element of root.querySelectorAll('*')) {
      if (element.shadowRoot) roots.push(element.shadowRoot);
    }
  }
  const texts: string[] = [];
  for (const root of roots) {
    const top = root instanceof Document ? root.body : root;
    if (!top) continue;
    const blocks = new Map<Element, { parts: string[]; shown: boolean }>();
    const walker = (root instanceof Document ? root : root.ownerDocument).createTreeWalker(
      top,
      NodeFilter.SHOW_TEXT,
    );
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const parent = node.parentElement;
      if (!parent || parent.closest('script, style, noscript')) continue;
      if (!visible(parent)) continue;
      let block = parent;
      while (
        block.parentElement &&
        block.parentElement !== document.body &&
        getComputedStyle(block).display.startsWith('inline')
      ) {
        block = block.parentElement;
      }
      let entry = blocks.get(block);
      if (!entry) {
        entry = { parts: [], shown: false };
        blocks.set(block, entry);
      }
      entry.parts.push(node.textContent ?? '');
      if (!entry.shown && (node.textContent ?? '').trim()) {
        const range = document.createRange();
        range.selectNodeContents(node);
        entry.shown = Array.from(range.getClientRects()).some((rect) => shows(parent, rect));
      }
    }
    for (const entry of blocks.values()) {
      if (entry.shown) texts.push(entry.parts.join(''));
    }
    for (const field of root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
      'input, textarea',
    )) {
      if (visible(field) && shows(field, field.getBoundingClientRect())) texts.push(field.value);
    }
  }
  return texts;
};

/**
 * Where a frame's window stands in the top document's window, as far as it is visible through the
 * frames around it; null when it is not visible, undefined when it cannot be told.
 */
async function frameBox(frame: Frame): Promise<GuardRegion | null | undefined> {
  const parent = frame.parentFrame();
  if (!parent) return undefined;
  const outer = parent.parentFrame() ? await frameBox(parent) : undefined;
  if (outer === null) return null;
  const local = await frame
    .frameElement()
    .then((element) =>
      element.evaluate((node) => {
        const iframe = node as HTMLIFrameElement;
        const rect = iframe.getBoundingClientRect();
        const style = getComputedStyle(iframe);
        const left = parseFloat(style.paddingLeft) || 0;
        const top = parseFloat(style.paddingTop) || 0;
        return {
          x: rect.left + iframe.clientLeft + left,
          y: rect.top + iframe.clientTop + top,
          width: iframe.clientWidth - left - (parseFloat(style.paddingRight) || 0),
          height: iframe.clientHeight - top - (parseFloat(style.paddingBottom) || 0),
        };
      }),
    )
    .catch(() => undefined);
  if (!local) return undefined;
  const box = outer ? { ...local, x: local.x + outer.x, y: local.y + outer.y } : local;
  return outer ? intersect(box, outer) : box;
}

function intersect(a: GuardRegion, b: GuardRegion): GuardRegion | null {
  const x = Math.max(a.x, b.x);
  const y = Math.max(a.y, b.y);
  const right = Math.min(a.x + a.width, b.x + b.width);
  const bottom = Math.min(a.y + a.height, b.y + b.height);
  return right > x && bottom > y ? { x, y, width: right - x, height: bottom - y } : null;
}

/**
 * The e-mail addresses outside `domains` that the picture shows, in the page and in its frames
 * (MailHog shows the mail itself in one): read from the visible text within `region` (see
 * MAIL_TEXTS), or from all the visible text when no region is given. A frame whose place cannot be
 * told is read whole.
 */
async function foreignAddresses(
  page: Page,
  domains: readonly string[],
  region: GuardRegion | undefined,
): Promise<Set<string>> {
  const foreign = new Set<string>();
  for (const frame of page.frames()) {
    let local: GuardRegion | null = null;
    if (region && frame === page.mainFrame()) local = region;
    else if (region) {
      const box = await frameBox(frame);
      if (box === null) continue;
      if (box) {
        const seen = intersect(region, box);
        if (!seen) continue;
        local = { ...seen, x: seen.x - box.x, y: seen.y - box.y };
      }
    }
    // A frame that went away while it was read shows nothing; any other failure is the guard's.
    const texts = await frame.evaluate(MAIL_TEXTS, local).catch((error: unknown) => {
      if (frame.isDetached()) return [] as string[];
      throw error;
    });
    for (const address of texts.join('\n').match(MAIL_ADDRESS) ?? []) {
      const host = address.slice(address.lastIndexOf('@') + 1).toLowerCase();
      if (!domains.some((domain) => host === domain || host.endsWith(`.${domain}`))) {
        foreign.add(address);
      }
    }
  }
  return foreign;
}

/**
 * The Danish texts of the copy's data: every Danish dictionary value (web, message, pdf, mail,
 * template, aton, ...) and the other Danish values english.sql kept before deleting them, beside
 * the Danish rows the copy holds (guides.danish_text), read once per worker through psql in the
 * database container, and the base product's English bundle, whose texts never count as Danish.
 */
let dictionaryIndex: Promise<DictionaryIndex> | undefined;

// The sources whose values are interface words: refused as a whole text too.
const INTERFACE_SOURCES =
  /^(?:dictionary:|TemplateParamDesc|MailingListDesc\.name|MailingListTriggerDesc|CategoryDesc|ScheduledMail)/;

function readDictionaryIndex(): Promise<DictionaryIndex> {
  dictionaryIndex ??= (async () => {
    const data = JSON.parse(
      psql(
        `SELECT coalesce(json_agg(json_build_array(source, coalesce(en, ''), da)), '[]') ` +
          `FROM guides.danish_text`,
        'the Danish texts of the data (guides.danish_text, made by data/english.sql)',
      ),
    ) as [string, string, string][];
    const rows = data.map(([source, en, da]) => ({ en, da, ui: INTERFACE_SOURCES.test(source) }));
    const bundlePath = joinPath(
      test.info().project.testDir,
      '..',
      'languages',
      'en',
      'i18n',
      'admin',
      'en.json',
    );
    const english = new Set<string>();
    const collect = (value: unknown): void => {
      if (typeof value === 'string') english.add(value);
      else if (value && typeof value === 'object') Object.values(value).forEach(collect);
    };
    collect(JSON.parse(readFileSync(bundlePath, 'utf8')));
    return buildDictionaryIndex(rows, english);
  })();
  return dictionaryIndex;
}

/**
 * The Danish texts a picture shows that it may not, each named with why it is Danish and where it
 * stands, in the page and in every frame (MailHog's mail, a PDF's text layer).
 */
async function danishTexts(
  page: Page,
  allowed: readonly AllowedDanish[],
): Promise<{ readonly danish: readonly string[]; readonly topics: readonly string[] }> {
  const index = await readDictionaryIndex();
  const danish = new Map<string, string>();
  const topics = new Map<string, string>();
  const checked = new Set<string>();
  const isAllowed = (text: string): boolean =>
    allowed.some((allow) =>
      typeof allow.text === 'string' ? allow.text === text : allow.text.test(text),
    );
  for (const frame of page.frames()) {
    for (const { text, where, within } of await visibleTexts(frame)) {
      if (checked.has(text)) continue;
      checked.add(text);
      if (within && within !== text && (danish.has(within) || topics.has(within))) continue;
      const topic = forbiddenTopic(text);
      if (topic) topics.set(text, `"${text.slice(0, 160)}" (${topic}) in ${where}`);
      if (isAllowed(text) || (within && isAllowed(within))) continue;
      const reason = danishReason(text, index);
      if (reason) danish.set(text, `"${text.slice(0, 160)}" (${reason}) in ${where}`);
    }
  }
  return { danish: [...danish.values()], topics: [...topics.values()] };
}

/** A personal value as a report may print it: its first two characters. */
function mask(text: string): string {
  return `${text.slice(0, 2)}***`;
}

/**
 * Records the masked addresses a picture shows in the report in `folder`, one line per picture:
 * its test title and the addresses. A picture taken again replaces its own line, and a picture
 * that shows no address any more takes its line out, so the report says what the pictures on disk
 * show. The rig runs on one worker, so no two pictures rewrite the file at the same time.
 */
function recordMail(folder: string, addresses: readonly string[]): void {
  const path = joinPath(folder, MAIL_REPORT);
  if (addresses.length === 0 && !existsSync(path)) return;
  mkdirSync(folder, { recursive: true });
  const title = test.info().title;
  const kept = existsSync(path)
    ? readFileSync(path, 'utf8')
        .split('\n')
        .filter((line) => line !== '' && !line.startsWith(`${title}\t`))
    : [];
  const mine = addresses.length > 0 ? [`${title}\t${addresses.map(mask).join(', ')}`] : [];
  const lines = [...kept, ...mine].sort();
  writeFileSync(path, lines.length > 0 ? `${lines.join('\n')}\n` : '', 'utf8');
}

/**
 * Refuses a picture of a page that is not fit for the guides: an error toast, a console error or
 * an uncaught error of the stack, a 5xx answer, a missing translation (logged by Transloco, or a
 * text on the page shaped like a key), or a Danish text (danish.ts; on every page, Niord's or not,
 * unless `allowDanish` names it). An e-mail address outside the scrubbed data's domains that the
 * picture shows (visible text within `region`, in the page or in any of its frames; the whole
 * page's visible text without a region) is recorded, masked, in the report in `reportFolder` (the
 * pictures' folder), and refuses the picture only when GUIDES_STRICT_MAIL=1: an organisation's
 * mailbox in a published notice may show, a private person's may not, and the report is where a
 * reviewer tells them apart. The other checks read the whole page, inside the region or not.
 */
export async function assertClean(
  page: Page,
  options: GuardOptions = {},
  reportFolder?: string,
  region?: GuardRegion,
): Promise<void> {
  const app = (options.checks ?? 'app') === 'app';
  const allowedConsole = [...ALWAYS_ALLOWED_CONSOLE, ...(options.allowConsole ?? [])];
  const allowDomains = [...ALLOWED_MAIL_DOMAINS, ...(options.allowMailDomains ?? [])];
  const onPage = await page.evaluate(
    ({ checkKeys, allowText }) => {
      // A whole text node shaped like a key: `messages.filter.status.label`, `view_mode.table`,
      // or the public site's `MENU_DETAILS`. A file name or a host name has one dot and no capital
      // or underscore, and is left alone.
      const looksLikeKey = (text: string): boolean => {
        if (/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(text)) return true;
        if (!/^[a-z][a-zA-Z0-9]*(?:[._][a-zA-Z0-9]+)+$/.test(text) || !text.includes('.')) {
          return false;
        }
        if (/\.(?:org|com|net|int|eu|io|info|gov|edu)$/i.test(text)) return false;
        return text.split('.').length > 2 || /[A-Z_]/.test(text);
      };
      const keys: string[] = [];
      if (checkKeys) {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          const text = (node.textContent ?? '').trim();
          if (!text || text.length > 120 || /\s/.test(text) || allowText.includes(text)) continue;
          const parent = node.parentElement;
          if (!parent || parent.closest('script, style, noscript, code, pre, textarea')) continue;
          if (!parent.checkVisibility({ opacityProperty: true, visibilityProperty: true })) {
            continue;
          }
          if (looksLikeKey(text)) keys.push(text);
        }
      }
      const errorToasts = Array.from(document.querySelectorAll('.toast-error')).map((toast) =>
        (toast.textContent ?? '').trim(),
      );
      return { keys, errorToasts };
    },
    {
      checkKeys: app && options.keyScan !== false,
      allowText: [...(options.allowText ?? [])],
    },
  );

  const foreign = await foreignAddresses(page, allowDomains, region);
  const problems: string[] = [];
  if (foreign.size > 0) {
    const seen = `e-mail addresses outside ${allowDomains.join(', ')}: ${[...foreign].map(mask).join(', ')}`;
    if (STRICT_MAIL || !reportFolder) problems.push(seen);
    else {
      recordMail(reportFolder, [...foreign]);
      console.log(`  recorded in ${MAIL_REPORT}: ${seen}`);
    }
  } else if (reportFolder) {
    recordMail(reportFolder, []);
  }
  if (app) {
    const findings = findingsByPage.get(page);
    const consoleErrors = (findings?.consoleErrors ?? []).filter(
      (text) => !allowedConsole.some((allowed) => allowed.test(text)),
    );
    if (onPage.errorToasts.length > 0) {
      problems.push(`error toasts: ${onPage.errorToasts.join(' | ')}`);
    }
    if (consoleErrors.length > 0) problems.push(`console errors: ${consoleErrors.join(' | ')}`);
    if (findings?.pageErrors.length) {
      problems.push(`page errors: ${findings.pageErrors.join(' | ')}`);
    }
    if (findings?.serverErrors.length) {
      problems.push(`server errors: ${findings.serverErrors.join(' | ')}`);
    }
    if (findings?.missingKeys.size) {
      problems.push(`missing translations: ${[...findings.missingKeys].join(', ')}`);
    }
    if (onPage.keys.length > 0) problems.push(`texts shaped like keys: ${onPage.keys.join(', ')}`);
  }
  // Danish text, on Niord's pages and on the others alike (a mail, a PDF, Keycloak's console), and
  // the two topics the owner's standing ruling keeps out of the guides (danish.ts, forbiddenTopic).
  const { danish, topics } = await danishTexts(page, options.allowDanish ?? []);
  if (danish.length > 0) problems.push(`Danish text: ${danish.join(' | ')}`);
  if (topics.length > 0) problems.push(`S-124 or SECOM: ${topics.join(' | ')}`);
  if (problems.length > 0) {
    throw new Error(`The picture is refused:\n- ${problems.join('\n- ')}`);
  }
}
