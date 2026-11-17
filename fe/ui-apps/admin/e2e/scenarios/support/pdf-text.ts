/*
 * The text of a PDF the backend renders, without a dependency: the objects are found, their
 * FlateDecode streams inflated with the web DecompressionStream, each font's glyphs mapped through
 * its ToUnicode CMap, and the text operators of each page read in order. It reads what the
 * backend's own report engine writes (Tj and TJ with ToUnicode fonts), not every PDF there is.
 */

interface PdfObject {
  readonly dict: string;
  readonly stream: Uint8Array | null;
}

interface CMap {
  readonly map: Map<number, string>;
  readonly width: number;
}

/** Bytes as a string of one character per byte. */
function latin1(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 8192) {
    out += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  return out;
}

function bytesOf(text: string): Uint8Array {
  return Uint8Array.from(text, (char) => char.charCodeAt(0) & 255);
}

async function inflate(bytes: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([bytes as BlobPart])
    .stream()
    .pipeThrough(new DecompressionStream('deflate'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function readObjects(text: string): Promise<Map<number, PdfObject>> {
  const objects = new Map<number, PdfObject>();
  const header = /(\d+)\s+(\d+)\s+obj\b/g;
  let match: RegExpExecArray | null;
  while ((match = header.exec(text))) {
    const start = match.index + match[0].length;
    const end = text.indexOf('endobj', start);
    if (end < 0) break;
    const body = text.slice(start, end);
    let dict = body;
    let stream: Uint8Array | null = null;
    const at = body.search(/stream\r?\n/);
    if (at >= 0) {
      dict = body.slice(0, at);
      let from = at + 'stream'.length;
      if (body[from] === '\r') from++;
      if (body[from] === '\n') from++;
      const length = /\/Length\s+(\d+)(?!\s+\d+\s+R)/.exec(dict);
      // A direct /Length gives the exact end; otherwise the end-of-line before endstream is dropped.
      let raw = length
        ? bytesOf(body.slice(from, from + Number(length[1])))
        : bytesOf(body.slice(from, body.lastIndexOf('endstream')).replace(/\r?\n$/, ''));
      if (/\/FlateDecode/.test(dict)) {
        raw = await inflate(raw).catch(() => new Uint8Array(0));
      }
      stream = raw;
    }
    objects.set(Number(match[1]), { dict, stream });
    header.lastIndex = end;
  }
  return objects;
}

function reference(dict: string, key: string): number | null {
  const match = new RegExp(`/${key}\\s+(\\d+)\\s+0\\s+R`).exec(dict);
  return match ? Number(match[1]) : null;
}

function parseCMap(text: string): CMap {
  const map = new Map<number, string>();
  const space = /begincodespacerange\s*<([0-9A-Fa-f]+)>/.exec(text);
  const width = space ? space[1].length / 2 : 1;
  const hexText = (hex: string): string => {
    let out = '';
    for (let i = 0; i + 4 <= hex.length; i += 4)
      out += String.fromCharCode(parseInt(hex.slice(i, i + 4), 16));
    return out;
  };
  for (const block of text.matchAll(/beginbfchar([\s\S]*?)endbfchar/g)) {
    for (const pair of block[1].matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
      map.set(parseInt(pair[1], 16), hexText(pair[2]));
    }
  }
  for (const block of text.matchAll(/beginbfrange([\s\S]*?)endbfrange/g)) {
    const ranges = block[1].matchAll(
      /<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(?:<([0-9A-Fa-f]+)>|\[([^\]]*)\])/g,
    );
    for (const range of ranges) {
      const first = parseInt(range[1], 16);
      const last = parseInt(range[2], 16);
      if (range[3]) {
        const base = parseInt(range[3], 16);
        for (let code = first; code <= last; code++) {
          map.set(code, String.fromCharCode(base + code - first));
        }
      } else {
        [...range[4].matchAll(/<([0-9A-Fa-f]+)>/g)].forEach((hex, i) =>
          map.set(first + i, hexText(hex[1])),
        );
      }
    }
  }
  return { map, width };
}

function decode(bytes: readonly number[], cmap: CMap | null | undefined): string {
  if (!cmap) return String.fromCharCode(...bytes);
  let out = '';
  for (let i = 0; i < bytes.length; i += cmap.width) {
    let code = 0;
    for (let k = 0; k < cmap.width; k++) code = (code << 8) | (bytes[i + k] ?? 0);
    out += cmap.map.get(code) ?? '';
  }
  return out;
}

const ESCAPES: Record<string, number> = {
  n: 10,
  r: 13,
  t: 9,
  b: 8,
  f: 12,
  '(': 40,
  ')': 41,
  '\\': 92,
};

/** A literal string starting at `text[i] === '('`: its bytes and the index after it. */
function readLiteral(text: string, start: number): [number[], number] {
  let depth = 1;
  let i = start + 1;
  const out: number[] = [];
  while (i < text.length && depth > 0) {
    const char = text[i];
    if (char === '\\') {
      const next = text[i + 1];
      if (next in ESCAPES) {
        out.push(ESCAPES[next]);
        i += 2;
        continue;
      }
      const octal = /^[0-7]{1,3}/.exec(text.slice(i + 1, i + 4));
      if (octal) {
        out.push(parseInt(octal[0], 8));
        i += 1 + octal[0].length;
        continue;
      }
      i += 2;
      continue;
    }
    if (char === '(') depth++;
    if (char === ')' && --depth === 0) {
      i++;
      break;
    }
    out.push(text.charCodeAt(i) & 255);
    i++;
  }
  return [out, i];
}

type Operand =
  | { readonly s: number[] }
  | { readonly n: string }
  | { readonly v: number }
  | { readonly arr: Operand[] }
  | { readonly arrStart: true };

function pageText(content: Uint8Array, cmaps: Map<string, CMap | null>): string {
  const text = latin1(content);
  let font: string | undefined;
  let out = '';
  let operands: Operand[] = [];
  let i = 0;
  const show = (bytes: number[]): void => {
    out += decode(bytes, font ? cmaps.get(font) : null);
  };
  while (i < text.length) {
    const char = text[i];
    if (char === '(') {
      const [bytes, next] = readLiteral(text, i);
      operands.push({ s: bytes });
      i = next;
      continue;
    }
    if (char === '<' && text[i + 1] !== '<') {
      const end = text.indexOf('>', i);
      const hex = text.slice(i + 1, end).replace(/\s/g, '');
      const bytes: number[] = [];
      for (let k = 0; k < hex.length; k += 2)
        bytes.push(parseInt(hex.slice(k, k + 2).padEnd(2, '0'), 16));
      operands.push({ s: bytes });
      i = end + 1;
      continue;
    }
    if (char === '[') {
      operands.push({ arrStart: true });
      i++;
      continue;
    }
    if (char === ']') {
      const items: Operand[] = [];
      while (operands.length && !('arrStart' in operands[operands.length - 1])) {
        items.unshift(operands.pop() as Operand);
      }
      operands.pop();
      operands.push({ arr: items });
      i++;
      continue;
    }
    if (/\s/.test(char)) {
      i++;
      continue;
    }
    const word = /^[^\s()<>[\]/]+|^\/[^\s()<>[\]/]*/.exec(text.slice(i, i + 200));
    if (!word) {
      i++;
      continue;
    }
    const token = word[0];
    i += token.length;
    if (token.startsWith('/')) {
      operands.push({ n: token.slice(1) });
      continue;
    }
    if (/^[-+.\d]+$/.test(token)) {
      operands.push({ v: Number(token) });
      continue;
    }
    const last = operands[operands.length - 1];
    if (token === 'Tf') {
      const name = operands[operands.length - 2];
      font = name && 'n' in name ? name.n : undefined;
    } else if (token === 'Tj' || token === "'" || token === '"') {
      if (token !== 'Tj') out += '\n';
      if (last && 's' in last) show(last.s);
    } else if (token === 'TJ') {
      for (const item of last && 'arr' in last ? last.arr : []) {
        if ('s' in item) show(item.s);
        else if ('v' in item && item.v < -200) out += ' ';
      }
    } else if (token === 'Td' || token === 'TD') {
      out += last && 'v' in last && Math.abs(last.v) > 0.5 ? '\n' : ' ';
    } else if (token === 'T*' || token === 'Tm') {
      out += '\n';
    } else if (token === 'ET') {
      out += ' ';
    }
    operands = [];
  }
  return out;
}

/** The text of every page of the PDF, in page order, one string per page. */
export async function pdfPages(pdf: Uint8Array): Promise<string[]> {
  const objects = await readObjects(latin1(pdf));

  const cmaps = new Map<string, CMap | null>();
  for (const object of objects.values()) {
    const fonts = /\/Font\s*<<([^>]*)>>/.exec(object.dict);
    if (!fonts) continue;
    for (const entry of fonts[1].matchAll(/\/(\S+?)\s+(\d+)\s+0\s+R/g)) {
      const font = objects.get(Number(entry[2]));
      const toUnicode = font ? reference(font.dict, 'ToUnicode') : null;
      const cmap = toUnicode ? objects.get(toUnicode)?.stream : null;
      cmaps.set(entry[1], cmap ? parseCMap(latin1(cmap)) : null);
    }
  }

  const catalog = [...objects.values()].find((object) => /\/Type\s*\/Catalog/.test(object.dict));
  const pages: number[] = [];
  const walk = (id: number | null): void => {
    const node = id === null ? undefined : objects.get(id);
    if (!node) return;
    if (/\/Type\s*\/Pages/.test(node.dict)) {
      const kids = /\/Kids\s*\[([^\]]*)\]/.exec(node.dict)?.[1] ?? '';
      for (const kid of kids.matchAll(/(\d+)\s+0\s+R/g)) walk(Number(kid[1]));
    } else if (id !== null) {
      pages.push(id);
    }
  };
  walk(catalog ? reference(catalog.dict, 'Pages') : null);

  return pages.map((id) => {
    const contents = /\/Contents\s*(\[[^\]]*\]|\d+\s+0\s+R)/.exec(objects.get(id)?.dict ?? '');
    const streams = contents
      ? [...contents[1].matchAll(/(\d+)\s+0\s+R/g)].map((m) => Number(m[1]))
      : [];
    return streams
      .map((stream) => {
        const content = objects.get(stream)?.stream;
        return content ? pageText(content, cmaps) : '';
      })
      .join('\n')
      .replace(/[ \t]+\n/g, '\n')
      .replace(/\n{2,}/g, '\n')
      .replace(/ {2,}/g, ' ')
      .trim();
  });
}

/** The text of the whole PDF. */
export async function pdfText(pdf: Uint8Array): Promise<string> {
  return (await pdfPages(pdf)).join('\n');
}
