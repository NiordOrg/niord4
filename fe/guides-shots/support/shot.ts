import { existsSync, readFileSync, writeFileSync } from 'node:fs';

import { test, type Locator, type Page } from '@playwright/test';

import { guidesDir } from './env';
import { assertClean, type GuardOptions } from './guard';
import { joinPath } from './paths';
import { settle, type SettleOptions } from './stabilise';

/**
 * A picture's test is titled `<manual>/<file>`: the manual's folder in the guides and the file the
 * picture is written to under its images/ folder. The title is the one place the name is written,
 * so `--grep "<manual>/<file>"` takes one picture again and the manifest check reads the titles.
 */
const TITLE = /^([a-z0-9][a-z0-9._-]*)\/([^/\\]+\.(?:png|jpe?g))$/i;

/**
 * The width a picture is printed at, as a share of its width in CSS pixels. Every picture is
 * printed at the same ratio, so text is the same size in every figure of a manual, whether the
 * picture is a whole window or a small menu: a whole window (1280 CSS px) prints 800 px wide, the
 * widest figure a manual's column takes, and a menu 300 CSS px wide prints at 188 px.
 */
export const PRINT_SCALE = 0.625;

/** The record of the pictures' sizes, written beside the pictures (see recordSize). */
export const SIZE_RECORD = 'image-sizes.json';

export interface ShotOptions extends SettleOptions {
  /** The manual's folder; taken from the test's title, and checked against it when given. */
  readonly manual?: string;
  /** The picture's file name; taken from the test's title, and checked against it when given. */
  readonly file?: string;
  /** What to capture: one element, or several framed together (a menu with its trigger). */
  readonly target?: Locator | readonly Locator[];
  /** The margin around the target, in CSS pixels (default 8). */
  readonly pad?: number;
  /**
   * What stays visible inside a framed picture (default: the targets). The rest of the frame, the
   * margin and any gap between the targets, is painted over with a plain backdrop, so no part of a
   * neighbour or of the page behind a menu shows at the edges. A menu framed with its trigger keeps
   * the header the trigger sits in, for example.
   */
  readonly keep?: Locator | readonly Locator[];
  /**
   * The backdrop's colour, or false to show the page as it is around the targets. By default the
   * colour behind the first target: the page's background around a panel, white behind a menu.
   */
  readonly backdrop?: string | false;
  /**
   * For a picture of the whole window (no target): whether to end it above a line of text, a row or
   * a control that the window's bottom edge cuts through, and below the last content when the
   * window ends in empty space (default true).
   */
  readonly fit?: boolean;
  /**
   * For a picture without a target: take the whole page, from its top to the end of the document
   * (its footer), instead of the window (default false; `fit` then does not apply). For a page
   * taller than the window whose content sits in a bordered card: the window's bottom edge cuts
   * through the card, and no row above that edge ends it cleanly, so the picture shows the card to
   * its end instead.
   */
  readonly whole?: boolean;
  /**
   * Whether to leave the focus ring the steps left. By default a focused button or menu item keeps
   * its focus but loses its ring (an opened menu focuses its first item, and the highlight reads
   * as a state of its own); a focused text field is left alone, because moving its focus can show
   * a validation message.
   */
  readonly keepFocus?: boolean;
  /** Elements taken out of the layout for this picture only, e.g. a notice of the local run. */
  readonly hide?: readonly Locator[];
  /** Overrides `<guides>/<manual>/images/`, for a picture that is not a manual's (the pilot). */
  readonly outDir?: string;
  /** What the guard holds the picture to. */
  readonly guard?: GuardOptions;
}

interface Box {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

interface Clip {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

function nameOf(options: ShotOptions): { manual: string; file: string } {
  const title = test.info().title;
  const match = TITLE.exec(title);
  const manual = match?.[1];
  const file = match?.[2];
  if (!manual || !file) {
    throw new Error(
      `A picture's test is titled <manual>/<file>.png after what it writes: "${title}".`,
    );
  }
  if ((options.manual && options.manual !== manual) || (options.file && options.file !== file)) {
    throw new Error(`shot() names ${options.manual}/${options.file}, but the test is "${title}".`);
  }
  return { manual, file };
}

function listOf(value: Locator | readonly Locator[] | undefined): readonly Locator[] {
  if (value === undefined) return [];
  return 'evaluate' in value ? [value] : value;
}

/**
 * The box of what an element paints, in the viewport's coordinates: its text (line by line),
 * its images, icons and form fields, the content of a same-origin frame, and every descendant
 * with a background or a border, each cut to the boxes of the ancestors that clip their overflow.
 * An element's own box can say little about that: a custom element laid out `inline` has none, a
 * Bootstrap row reaches past its column by its negative margins, and a frame's box is as tall as
 * the window while the mail in it is short. Runs in the page, so it is self-contained.
 */
const PAINTED_BOX = (root: Element): Box | null => {
  interface Rect {
    left: number;
    top: number;
    right: number;
    bottom: number;
  }
  const REPLACED = new Set(['img', 'svg', 'canvas', 'video', 'input', 'select', 'textarea']);
  const clear = (colour: string): boolean =>
    colour === 'transparent' || /^rgba\(.*,\s*0\)$/.test(colour);
  const paints = (style: CSSStyleDeclaration): boolean => {
    if (!clear(style.backgroundColor) || style.backgroundImage !== 'none') return true;
    return (['Top', 'Right', 'Bottom', 'Left'] as const).some(
      (side) =>
        parseFloat(style.getPropertyValue(`border-${side.toLowerCase()}-width`)) > 0 &&
        style.getPropertyValue(`border-${side.toLowerCase()}-style`) !== 'none' &&
        !clear(style.getPropertyValue(`border-${side.toLowerCase()}-color`)),
    );
  };
  const cut = (a: Rect, b: Rect): Rect | null => {
    const box = {
      left: Math.max(a.left, b.left),
      top: Math.max(a.top, b.top),
      right: Math.min(a.right, b.right),
      bottom: Math.min(a.bottom, b.bottom),
    };
    return box.right > box.left && box.bottom > box.top ? box : null;
  };
  const shift = (a: Rect, dx: number, dy: number): Rect => ({
    left: a.left + dx,
    top: a.top + dy,
    right: a.right + dx,
    bottom: a.bottom + dy,
  });
  let union: Rect | null = null;
  const add = (box: Rect | null): void => {
    if (!box) return;
    union = union
      ? {
          left: Math.min(union.left, box.left),
          top: Math.min(union.top, box.top),
          right: Math.max(union.right, box.right),
          bottom: Math.max(union.bottom, box.bottom),
        }
      : box;
  };
  // dx/dy carry a frame's offset in the top document; `clip` is in the top document's coordinates.
  const visit = (node: Element, clip: Rect, dx: number, dy: number): void => {
    const view = node.ownerDocument.defaultView;
    if (!view) return;
    const style = view.getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return;
    const own = shift(node.getBoundingClientRect(), dx, dy);
    const tag = node.tagName.toLowerCase();
    if (tag === 'iframe') {
      const inner = (node as HTMLIFrameElement).contentDocument?.body;
      const frame = cut(own, clip);
      if (!inner || !frame) return add(frame);
      const before = union;
      union = null;
      visit(inner, frame, own.left + node.clientLeft, own.top + node.clientTop);
      const content: Rect | null = union;
      union = before;
      return add(content ?? frame);
    }
    if (REPLACED.has(tag)) return add(cut(own, clip));
    if (own.right > own.left && own.bottom > own.top && paints(style)) add(cut(own, clip));
    const inner =
      style.overflowX !== 'visible' || style.overflowY !== 'visible' ? cut(clip, own) : clip;
    if (!inner) return;
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        if (!(child.textContent ?? '').trim()) continue;
        const range = node.ownerDocument.createRange();
        range.selectNodeContents(child);
        for (const line of Array.from(range.getClientRects())) add(cut(shift(line, dx, dy), inner));
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        visit(child as Element, inner, dx, dy);
      }
    }
  };
  const everywhere = { left: -1e6, top: -1e6, right: 1e6, bottom: 1e6 };
  visit(root, everywhere, 0, 0);
  return union;
};

/**
 * The height a picture of the whole window ends at. A line of text, a row's control or an icon
 * the window's bottom edge cuts through ends the picture above it: above the row it belongs to
 * (a table row, a list item) or above the line itself. Below the last content, a window that ends
 * in empty space ends just below that content instead. Runs in the page, so it is self-contained.
 */
const FIT_HEIGHT = (): number => {
  const height = window.innerHeight;
  const width = window.innerWidth;
  interface Item {
    top: number;
    bottom: number;
    clipBottom: number;
    unitTop: number;
    unitBottom: number;
    // The space the picture keeps below it when it is the last content: a line of text or an
    // icon has none of its own, a painted box ends at its edge. It ends at the bottom of the
    // nearest ancestor that paints (a footer's bar, a card).
    margin: number;
    boxBottom: number;
    kind: 'text' | 'small' | 'box';
  }
  const items: Item[] = [];
  const REPLACED = new Set([
    'img',
    'svg',
    'canvas',
    'video',
    'input',
    'select',
    'textarea',
    'iframe',
  ]);
  const UNIT = 'tr, [role="row"], li, dt, dd';
  const clear = (colour: string): boolean =>
    colour === 'transparent' || /^rgba\(.*,\s*0\)$/.test(colour);
  const paints = (style: CSSStyleDeclaration): boolean =>
    !clear(style.backgroundColor) ||
    (parseFloat(style.borderTopWidth) > 0 && !clear(style.borderTopColor)) ||
    (parseFloat(style.borderBottomWidth) > 0 && !clear(style.borderBottomColor));
  const push = (
    rect: DOMRect,
    clipBottom: number,
    element: Element,
    kind: Item['kind'],
    margin: number,
  ): void => {
    if (rect.bottom <= 0 || rect.top >= height || rect.right <= 0 || rect.left >= width) return;
    if (rect.width <= 0 || rect.height <= 0 || clipBottom <= rect.top) return;
    const unit = kind === 'box' ? null : element.closest(UNIT)?.getBoundingClientRect();
    let boxBottom = Infinity;
    for (let node = element.parentElement; node && margin > 0; node = node.parentElement) {
      if (paints(getComputedStyle(node))) {
        boxBottom = node.getBoundingClientRect().bottom;
        break;
      }
    }
    items.push({
      top: rect.top,
      bottom: rect.bottom,
      clipBottom,
      unitTop: unit ? Math.min(unit.top, rect.top) : rect.top,
      unitBottom: unit ? Math.max(unit.bottom, rect.bottom) : rect.bottom,
      margin,
      boxBottom,
      kind,
    });
  };
  const visit = (node: Element, clipBottom: number): void => {
    const style = getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return;
    const rect = node.getBoundingClientRect();
    const tag = node.tagName.toLowerCase();
    if (REPLACED.has(tag)) {
      push(rect, clipBottom, node, rect.height <= 120 ? 'small' : 'box', 8);
      return;
    }
    if (paints(style)) push(rect, clipBottom, node, rect.height <= 64 ? 'small' : 'box', 0);
    const inner = style.overflowY !== 'visible' ? Math.min(clipBottom, rect.bottom) : clipBottom;
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        if (!(child.textContent ?? '').trim()) continue;
        const range = document.createRange();
        range.selectNodeContents(child);
        for (const line of Array.from(range.getClientRects())) {
          push(line, inner, node, 'text', 12);
        }
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        visit(child as Element, inner);
      }
    }
  };
  visit(document.body, height);

  // A cut moved up can cut through a taller neighbour (a two-line cell beside a one-line one).
  let edge = height;
  for (let round = 0; round < 20; round++) {
    const cutThrough = items.filter(
      (item) =>
        item.kind !== 'box' &&
        item.top < edge - 0.5 &&
        item.bottom > edge + 0.5 &&
        item.clipBottom >= edge - 0.5,
    );
    if (cutThrough.length === 0) break;
    edge = Math.floor(Math.min(...cutThrough.map((item) => item.unitTop)));
  }
  // A cut that would take away more than half the window says the page is one tall element: the
  // window is kept as it is.
  if (edge < height / 2) edge = height;

  // The last content above the edge: a row's bottom, a box that ends there, or a line of text
  // with a margin below it. A box that reaches the edge is a page's container and says nothing.
  let content = 0;
  for (const item of items) {
    if (item.top >= edge || item.bottom > edge + 0.5) continue;
    if (item.kind === 'box') {
      if (item.bottom < edge - 1) content = Math.max(content, item.bottom);
    } else if (item.unitBottom > item.bottom) {
      content = Math.max(content, item.unitBottom);
    } else {
      const below = Math.min(item.bottom + item.margin, item.boxBottom);
      content = Math.max(content, item.bottom, below);
    }
  }
  return content > 0 && content < edge - 4 ? Math.ceil(content) : edge;
};

/**
 * The clip of the targets with their margin. Within the viewport it is measured from the viewport
 * and the picture is an ordinary one; a target taller or wider than the viewport is measured from
 * the document and taken as a full-page picture, the only kind that reaches past the viewport.
 */
async function clipOf(
  page: Page,
  targets: readonly Locator[],
  pad: number,
): Promise<{ clip: Clip; fullPage: boolean; scroll: { x: number; y: number } }> {
  await targets[0]?.scrollIntoViewIfNeeded();
  const boxes: Box[] = [];
  for (const target of targets) {
    const box = await target.evaluate(PAINTED_BOX);
    if (!box) throw new Error(`The target ${target} has no visible box.`);
    boxes.push(box);
  }
  const left = Math.min(...boxes.map((box) => box.left)) - pad;
  const top = Math.min(...boxes.map((box) => box.top)) - pad;
  const right = Math.max(...boxes.map((box) => box.right)) + pad;
  const bottom = Math.max(...boxes.map((box) => box.bottom)) + pad;
  const view = await page.evaluate(() => ({
    scrollX: window.scrollX,
    scrollY: window.scrollY,
    width: window.innerWidth,
    height: window.innerHeight,
    documentWidth: document.documentElement.scrollWidth,
    documentHeight: document.documentElement.scrollHeight,
  }));
  const inside =
    left >= -pad && top >= -pad && right <= view.width + pad && bottom <= view.height + pad;
  const [offsetX, offsetY, maxX, maxY] = inside
    ? [0, 0, view.width, view.height]
    : [view.scrollX, view.scrollY, view.documentWidth, view.documentHeight];
  // Rounded to the nearest pixel on every side, so the margins differ by less than a pixel.
  const x = Math.max(0, Math.round(left + offsetX));
  const y = Math.max(0, Math.round(top + offsetY));
  const width = Math.min(maxX, Math.round(right + offsetX)) - x;
  const height = Math.min(maxY, Math.round(bottom + offsetY)) - y;
  return {
    clip: { x, y, width, height },
    fullPage: !inside,
    scroll: { x: view.scrollX, y: view.scrollY },
  };
}

const BACKDROP_ID = 'guides-shots-backdrop';

/**
 * Paints the frame of a picture over with a plain colour, except for the boxes that stay visible.
 * The layer is absolutely placed in the document (on the root element, so no positioned ancestor
 * moves it), above everything, and lets every pointer event through.
 */
async function paintBackdrop(
  page: Page,
  frame: Clip,
  keep: readonly Box[],
  colour: string,
): Promise<void> {
  await page.evaluate(
    ({ id, frame, keep, colour }) => {
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      svg.id = id;
      svg.setAttribute('width', String(frame.width));
      svg.setAttribute('height', String(frame.height));
      svg.setAttribute(
        'style',
        `position:absolute;left:${frame.x}px;top:${frame.y}px;z-index:2147483647;` +
          'pointer-events:none;display:block;margin:0;',
      );
      const mask = document.createElementNS(ns, 'mask');
      mask.id = `${id}-mask`;
      const rect = (x: number, y: number, w: number, h: number, fill: string): SVGRectElement => {
        const element = document.createElementNS(ns, 'rect');
        element.setAttribute('x', String(x));
        element.setAttribute('y', String(y));
        element.setAttribute('width', String(Math.max(0, w)));
        element.setAttribute('height', String(Math.max(0, h)));
        element.setAttribute('fill', fill);
        return element;
      };
      mask.appendChild(rect(0, 0, frame.width, frame.height, 'white'));
      // Each hole is rounded inwards to whole pixels: a hole's edge between two pixels would let a
      // sliver of the page show through, where the kept element's own edge is only cut finer.
      for (const box of keep) {
        const left = Math.ceil(box.left - frame.x);
        const top = Math.ceil(box.top - frame.y);
        const right = Math.floor(box.right - frame.x);
        const bottom = Math.floor(box.bottom - frame.y);
        mask.appendChild(rect(left, top, right - left, bottom - top, 'black'));
      }
      svg.appendChild(mask);
      const cover = rect(0, 0, frame.width, frame.height, colour);
      cover.setAttribute('mask', `url(#${mask.id})`);
      svg.appendChild(cover);
      document.documentElement.appendChild(svg);
    },
    { id: BACKDROP_ID, frame, keep: [...keep], colour },
  );
}

async function removeBackdrop(page: Page): Promise<void> {
  await page.evaluate((id) => document.getElementById(id)?.remove(), BACKDROP_ID);
}

/**
 * The colour behind the largest target: its nearest ancestor's background that is not
 * transparent, or white. The largest is the subject (the menu, not its trigger in the header).
 */
async function colourBehind(targets: readonly Locator[]): Promise<string> {
  let best = { area: -1, colour: '#ffffff' };
  for (const target of targets) {
    const found = await target.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      for (let node = element.parentElement; node; node = node.parentElement) {
        const colour = getComputedStyle(node).backgroundColor;
        if (colour !== 'transparent' && !/^rgba\(.*,\s*0\)$/.test(colour)) {
          return { area: rect.width * rect.height, colour };
        }
      }
      return { area: rect.width * rect.height, colour: '#ffffff' };
    });
    if (found.area > best.area) best = found;
  }
  return best.colour;
}

/**
 * Takes the focus ring off a focused button or menu item (see keepFocus); a text field is left as
 * it is. The element is not left without focus: a menu closes when focus leaves it (CDK's menus
 * do). It is blurred and focused again in the same task, without the ring (`focusVisible: false`),
 * so a listener that waits for the focus to settle sees it where it was.
 */
async function clearFocus(page: Page): Promise<void> {
  await page.evaluate(() => {
    const active = document.activeElement as HTMLElement | null;
    if (!active || active === document.body) return;
    if (active.closest('input, textarea, select, [contenteditable="true"]')) return;
    active.blur();
    active.focus({ preventScroll: true, focusVisible: false } as FocusOptions);
  });
}

/**
 * Records a picture's size beside the pictures, in `image-sizes.json`: its width and height in CSS
 * pixels and the width to print it at (PRINT_SCALE), keyed by file name, for the manual's build.
 */
function recordSize(folder: string, file: string, width: number, height: number): void {
  const path = joinPath(folder, SIZE_RECORD);
  let record: Record<string, unknown> = {};
  if (existsSync(path)) {
    try {
      record = JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>;
    } catch {
      record = {};
    }
  }
  record[file] = {
    cssWidth: width,
    cssHeight: height,
    printWidth: Math.round(width * PRINT_SCALE),
  };
  const sorted = Object.fromEntries(Object.entries(record).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(path, `${JSON.stringify(sorted, null, 2)}\n`, 'utf8');
}

/**
 * Takes one picture for the guides: waits until the page is still, takes out what `hide` names,
 * frames the picture, refuses a page that is not fit (the guard, which reads the e-mail addresses
 * within the frame only), and writes `<guides>/<manual>/images/<file>` at the device scale the
 * configuration sets. Without a target the picture is the window, fitted (see `fit`); with one,
 * the targets and their margin, the margin painted over (see `keep` and `backdrop`). Returns the
 * path written.
 */
export async function shot(page: Page, options: ShotOptions = {}): Promise<string> {
  const { manual, file } = nameOf(options);
  const folder = options.outDir ?? joinPath(guidesDir(), manual, 'images');
  await settle(page, options);
  // Before the guard: an element the picture leaves out is not in it, and its text does not count.
  for (const element of options.hide ?? []) {
    await element.evaluateAll((elements) =>
      elements.forEach((hidden) =>
        (hidden as HTMLElement).style.setProperty('display', 'none', 'important'),
      ),
    );
  }
  if (!options.keepFocus) await clearFocus(page);
  const targets = listOf(options.target);
  const path = joinPath(folder, file);
  let clip: Clip;
  let fullPage = false;
  if (targets.length > 0) {
    const pad = options.pad ?? 8;
    const framing = await clipOf(page, targets, pad);
    clip = framing.clip;
    fullPage = framing.fullPage;
    // The guard reads the picture's part of the page in the window's coordinates; a full-page
    // clip is in the document's.
    const offset = fullPage ? framing.scroll : { x: 0, y: 0 };
    await assertClean(page, options.guard, folder, {
      ...clip,
      x: clip.x - offset.x,
      y: clip.y - offset.y,
    });
    if (pad > 0 && options.backdrop !== false) {
      const kept: Box[] = [];
      for (const element of listOf(options.keep).length > 0 ? listOf(options.keep) : targets) {
        const box = await element.evaluate(PAINTED_BOX);
        if (box) kept.push(box);
      }
      const colour = options.backdrop || (await colourBehind(targets));
      // The layer lives in document coordinates; the boxes and an in-window clip in the window's.
      const offset = fullPage ? { x: 0, y: 0 } : framing.scroll;
      await paintBackdrop(
        page,
        { ...clip, x: clip.x + offset.x, y: clip.y + offset.y },
        kept.map((box) => ({
          left: box.left + framing.scroll.x,
          top: box.top + framing.scroll.y,
          right: box.right + framing.scroll.x,
          bottom: box.bottom + framing.scroll.y,
        })),
        colour,
      );
    }
  } else if (options.whole) {
    // In the document's coordinates, from its top: the picture reaches past the window.
    const size = await page.evaluate(() => {
      window.scrollTo(0, 0);
      return {
        width: document.documentElement.clientWidth,
        height: document.documentElement.scrollHeight,
      };
    });
    clip = { x: 0, y: 0, width: size.width, height: size.height };
    fullPage = true;
    // Scrolled to the top, the document's coordinates are the window's.
    await assertClean(page, options.guard, folder, clip);
  } else {
    const view = page.viewportSize();
    if (!view) throw new Error('The page has no viewport size.');
    const height = options.fit === false ? view.height : await page.evaluate(FIT_HEIGHT);
    clip = { x: 0, y: 0, width: view.width, height };
    await assertClean(page, options.guard, folder, clip);
  }
  try {
    await page.screenshot({
      path,
      animations: 'disabled',
      caret: 'hide',
      scale: 'device',
      clip,
      fullPage,
    });
  } finally {
    await removeBackdrop(page);
  }
  recordSize(folder, file, clip.width, clip.height);
  console.log(`  wrote ${path} (${clip.width}x${clip.height} CSS px)`);
  return path;
}
