import type { Locator, Page } from '@playwright/test';

import { NOW } from './env';

/**
 * PDF.js, loaded by the browser from the CDN at a pinned version, so the workspace takes no
 * dependency for one picture. Chromium's own PDF viewer does not run headless, and nothing the
 * workspace installs turns a PDF page into an image.
 */
const PDFJS = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38';

// A page of its own, served by the test itself through page.route: the PDF is fetched by
// Playwright, outside the browser, so the page needs no access to the backend (the backend sends
// no CORS headers, and must not), and the renderer runs at an origin of its own.
const ORIGIN = 'http://guides-pdf.localhost';

/** A4's width at 96 dpi: the page is drawn at the size it prints at. */
const A4_WIDTH = 794;

/**
 * A time stamp as the backend's report templates print one (FreeMarker's `?datetime` in English:
 * "Nov 11, 2026, 11:28:28AM", the space before AM/PM optional), alone in its text item or after
 * the word "Generated" in the same item, as the public report prints it ("Generated Nov 11, 2026,
 * 10:09:29PM"). The word is the first group.
 */
const TIMESTAMP = String.raw`^(Generated\s+)?[A-Z][a-z]{2} \d{1,2}, \d{4},? \d{1,2}:\d{2}(?::\d{2})?\s?[AP]M$`;

/** An instant in the shape of TIMESTAMP, in the browser's (and the local backend's) time zone. */
function asTimestamp(at: string, timeZone = 'Europe/Copenhagen'): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  }).formatToParts(new Date(at));
  const part = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((entry) => entry.type === type)?.value ?? '';
  return (
    `${part('month')} ${part('day')}, ${part('year')}, ` +
    `${part('hour')}:${part('minute')}:${part('second')}${part('dayPeriod').toUpperCase()}`
  );
}

function rendererHtml(cssWidth: number, stamp: string | null): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <style>
      html, body { margin: 0; background: #fff; }
      body { padding: 16px; }
      #pdf-sheet { position: relative; display: inline-block; }
      canvas { display: block; border: 1px solid #d0d0d0; }
      #pdf-text { position: absolute; left: 1px; top: 1px; overflow: hidden; pointer-events: none; }
      #pdf-text span { position: absolute; white-space: pre; line-height: 1; color: transparent; }
      #pdf-all-text { position: absolute; left: -100000px; top: 0; width: 600px; white-space: pre; }
    </style>
  </head>
  <body>
    <div id="pdf-sheet"><canvas id="pdf-page"></canvas><div id="pdf-text"></div></div>
    <div id="pdf-all-text"></div>
    <script type="module">
      import * as pdfjs from '${PDFJS}/build/pdf.min.mjs';
      pdfjs.GlobalWorkerOptions.workerSrc = '${PDFJS}/build/pdf.worker.min.mjs';
      const body = document.body;
      const stamp = ${JSON.stringify(stamp)};
      const timestamp = new RegExp(${JSON.stringify(TIMESTAMP)});
      try {
        const pdf = await pdfjs.getDocument({
          url: '/document.pdf',
          disableRange: true,
          disableStream: true,
          cMapUrl: '${PDFJS}/cmaps/',
          cMapPacked: true,
          standardFontDataUrl: '${PDFJS}/standard_fonts/',
        }).promise;
        const page = await pdf.getPage(1);
        const viewport = page.getViewport({ scale: ${cssWidth} / page.getViewport({ scale: 1 }).width });
        const ratio = window.devicePixelRatio || 1;
        const full = document.createElement('canvas');
        full.width = Math.round(viewport.width * ratio);
        full.height = Math.round(viewport.height * ratio);
        const context = full.getContext('2d', { willReadFrequently: true });
        await page.render({ canvasContext: context, viewport, transform: [ratio, 0, 0, ratio, 0, 0] }).promise;

        // The page's text, for the guard, laid where the page shows it (transparent, so the guard
        // reads an e-mail address only where the picture shows the page), and the time stamps the
        // server printed, which are written again with the rig's fixed instant.
        const text = await page.getTextContent();
        const items = [];
        for (const item of text.items) {
          if (typeof item.str !== 'string' || !item.str.trim()) continue;
          const [, , c, d, x, y] = pdfjs.Util.transform(viewport.transform, item.transform);
          const size = Math.hypot(c, d);
          const found = stamp ? timestamp.exec(item.str.trim()) : null;
          const shown = found ? (found[1] ? 'Generated ' : '') + stamp : item.str;
          items.push({ text: shown, x, y, size });
          if (!found) continue;
          const width = item.width * viewport.scale;
          context.fillStyle = '#ffffff';
          context.fillRect((x - 1) * ratio, (y - size) * ratio, (width + 2) * ratio, size * 1.3 * ratio);
          context.fillStyle = '#000000';
          context.font = (size * ratio) + 'px Arial, Helvetica, sans-serif';
          context.textBaseline = 'alphabetic';
          context.fillText(shown, x * ratio, y * ratio);
        }

        // The page cut to its content: as far above and below it as the page's left margin, so
        // the empty lower half of a short first page does not fill the picture.
        const pixels = context.getImageData(0, 0, full.width, full.height).data;
        let top = full.height, bottom = 0, left = full.width;
        for (let row = 0; row < full.height; row++) {
          for (let column = 0; column < full.width; column++) {
            const at = (row * full.width + column) * 4;
            if (pixels[at] < 245 || pixels[at + 1] < 245 || pixels[at + 2] < 245) {
              if (row < top) top = row;
              bottom = row;
              if (column < left) left = column;
              break;
            }
          }
        }
        if (bottom < top) { top = 0; bottom = full.height - 1; left = 0; }
        const from = Math.max(0, top - left);
        const to = Math.min(full.height, bottom + 1 + left);
        const canvas = document.getElementById('pdf-page');
        canvas.width = full.width;
        canvas.height = to - from;
        canvas.style.width = viewport.width + 'px';
        canvas.style.height = (to - from) / ratio + 'px';
        canvas.getContext('2d').drawImage(full, 0, from, full.width, to - from, 0, 0, full.width, to - from);
        const layer = document.getElementById('pdf-text');
        layer.style.width = viewport.width + 'px';
        layer.style.height = (to - from) / ratio + 'px';
        for (const item of items) {
          const span = document.createElement('span');
          span.textContent = item.text;
          span.style.left = item.x + 'px';
          span.style.top = (item.y - item.size - from / ratio) + 'px';
          span.style.fontSize = item.size + 'px';
          layer.appendChild(span);
        }
        // The page's whole text once more, as one text, for the Danish check, which reads a
        // sentence the page breaks over lines whole: left of the window, never inside a picture's
        // clip, so the e-mail check, which reads only the clip, does not count it.
        document.getElementById('pdf-all-text').textContent = items.map((item) => item.text).join('\\n');
        body.dataset.state = 'rendered';
      } catch (error) {
        body.dataset.state = 'failed';
        body.dataset.error = String(error && error.message ? error.message : error);
      }
    </script>
  </body>
</html>`;
}

/** Where to fetch the PDF: an address of the backend, with the headers of the caller to be. */
export interface PdfSource {
  readonly url: string;
  readonly headers?: Record<string, string>;
}

/** How the first page is drawn. */
export interface PdfOptions {
  /** The page's width in CSS pixels (default A4's at 96 dpi). */
  readonly cssWidth?: number;
  /**
   * The instant the page's time stamps are written again with (default the rig's NOW), or null to
   * show what the server printed. The backend prints the second it made the report (a report's
   * "Generated" line), and the rig cannot set the backend's clock, so without this a picture of a
   * report differs on every run. Only a text shaped like the templates' time stamps is redrawn.
   */
  readonly at?: string | null;
}

/**
 * Fetches a PDF and draws its first page in the page, at its printed width and the configured
 * device scale, cut to its content, and returns the drawn page for shot() to take as its target.
 */
export async function openPdfFirstPage(
  page: Page,
  source: PdfSource,
  options: PdfOptions = {},
): Promise<Locator> {
  const response = await page.request.get(source.url, { headers: source.headers });
  if (!response.ok()) {
    throw new Error(`${source.url} answered ${response.status()} ${response.statusText()}`);
  }
  const type = response.headers()['content-type'] ?? '';
  if (!type.includes('pdf')) throw new Error(`${source.url} answered ${type}, not a PDF.`);
  const at = options.at === undefined ? NOW : options.at;
  const html = rendererHtml(options.cssWidth ?? A4_WIDTH, at === null ? null : asTimestamp(at));
  await page.route(`${ORIGIN}/**`, (route) =>
    new URL(route.request().url()).pathname === '/document.pdf'
      ? route.fulfill({ response })
      : route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }),
  );
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(() => document.body.dataset['state'] !== undefined, undefined, {
    timeout: 60_000,
  });
  const [state, error] = await page.evaluate(() => [
    document.body.dataset['state'],
    document.body.dataset['error'],
  ]);
  if (state !== 'rendered') throw new Error(`PDF.js could not draw ${source.url}: ${error}`);
  return page.locator('#pdf-page');
}
