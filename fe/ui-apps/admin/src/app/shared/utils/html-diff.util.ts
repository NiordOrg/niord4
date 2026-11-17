/**
 * HTML word-diff for the message-comparison "Changes" tab. It tokenises two HTML strings
 * (respecting tag boundaries), finds the longest matching blocks recursively, and renders the
 * result as HTML where inserted runs are wrapped in `<ins>` and deleted runs in `<del>` — so two
 * message renderings can be shown as a single marked-up diff.
 *
 * Pure string/array work — no DOM access and no dependencies.
 */

interface Operation {
  action: 'equal' | 'insert' | 'delete' | 'replace' | 'none';
  startInBefore: number;
  endInBefore?: number;
  startInAfter: number;
  endInAfter?: number;
}

class Match {
  readonly endInBefore: number;
  readonly endInAfter: number;
  constructor(
    readonly startInBefore: number,
    readonly startInAfter: number,
    readonly length: number,
  ) {
    this.endInBefore = startInBefore + length - 1;
    this.endInAfter = startInAfter + length - 1;
  }
}

const isEndOfTag = (char: string): boolean => char === '>';
const isStartOfTag = (char: string): boolean => char === '<';
const isWhitespace = (char: string): boolean => /^\s+$/.test(char);
const isTag = (token: string): boolean => /^\s*<[^>]+>\s*$/.test(token);
const isntTag = (token: string): boolean => !isTag(token);

/** Split HTML into a flat token list: whole tags, words, whitespace runs, and single punctuation. */
function htmlToTokens(html: string): string[] {
  let mode: 'char' | 'tag' | 'whitespace' = 'char';
  let currentWord = '';
  const words: string[] = [];
  for (const char of html) {
    switch (mode) {
      case 'tag':
        if (isEndOfTag(char)) {
          currentWord += '>';
          words.push(currentWord);
          currentWord = '';
          mode = isWhitespace(char) ? 'whitespace' : 'char';
        } else {
          currentWord += char;
        }
        break;
      case 'char':
        if (isStartOfTag(char)) {
          if (currentWord) words.push(currentWord);
          currentWord = '<';
          mode = 'tag';
        } else if (/\s/.test(char)) {
          if (currentWord) words.push(currentWord);
          currentWord = char;
          mode = 'whitespace';
        } else if (/[\w#@]+/i.test(char)) {
          currentWord += char;
        } else {
          if (currentWord) words.push(currentWord);
          currentWord = char;
        }
        break;
      case 'whitespace':
        if (isStartOfTag(char)) {
          if (currentWord) words.push(currentWord);
          currentWord = '<';
          mode = 'tag';
        } else if (isWhitespace(char)) {
          currentWord += char;
        } else {
          if (currentWord) words.push(currentWord);
          currentWord = char;
          mode = 'char';
        }
        break;
    }
  }
  if (currentWord) words.push(currentWord);
  return words;
}

/** For each distinct before-token, the ascending list of positions it occurs at in the after-tokens. */
function createIndex(findThese: string[], inThese: string[]): Record<string, number[]> {
  const index: Record<string, number[]> = {};
  for (const token of findThese) {
    index[token] = [];
    let idx = inThese.indexOf(token);
    while (idx !== -1) {
      index[token].push(idx);
      idx = inThese.indexOf(token, idx + 1);
    }
  }
  return index;
}

/** The single longest contiguous run of equal tokens within the given before/after windows. */
function findMatch(
  beforeTokens: string[],
  afterTokens: string[],
  indexOfBeforeLocationsInAfterTokens: Record<string, number[]>,
  startInBefore: number,
  endInBefore: number,
  startInAfter: number,
  endInAfter: number,
): Match | undefined {
  let bestMatchInBefore = startInBefore;
  let bestMatchInAfter = startInAfter;
  let bestMatchLength = 0;
  let matchLengthAt: Record<number, number> = {};

  for (let indexInBefore = startInBefore; indexInBefore < endInBefore; indexInBefore++) {
    const newMatchLengthAt: Record<number, number> = {};
    const lookingFor = beforeTokens[indexInBefore];
    const locationsInAfter = indexOfBeforeLocationsInAfterTokens[lookingFor] ?? [];
    for (const indexInAfter of locationsInAfter) {
      if (indexInAfter < startInAfter) continue;
      if (indexInAfter >= endInAfter) break;
      const newMatchLength = (matchLengthAt[indexInAfter - 1] ?? 0) + 1;
      newMatchLengthAt[indexInAfter] = newMatchLength;
      if (newMatchLength > bestMatchLength) {
        bestMatchInBefore = indexInBefore - newMatchLength + 1;
        bestMatchInAfter = indexInAfter - newMatchLength + 1;
        bestMatchLength = newMatchLength;
      }
    }
    matchLengthAt = newMatchLengthAt;
  }

  return bestMatchLength !== 0
    ? new Match(bestMatchInBefore, bestMatchInAfter, bestMatchLength)
    : undefined;
}

/** Recurse before/after the best match to collect all matching blocks in document order. */
function recursivelyFindMatchingBlocks(
  beforeTokens: string[],
  afterTokens: string[],
  index: Record<string, number[]>,
  startInBefore: number,
  endInBefore: number,
  startInAfter: number,
  endInAfter: number,
  matchingBlocks: Match[],
): Match[] {
  const match = findMatch(
    beforeTokens,
    afterTokens,
    index,
    startInBefore,
    endInBefore,
    startInAfter,
    endInAfter,
  );
  if (match) {
    if (startInBefore < match.startInBefore && startInAfter < match.startInAfter) {
      recursivelyFindMatchingBlocks(
        beforeTokens,
        afterTokens,
        index,
        startInBefore,
        match.startInBefore,
        startInAfter,
        match.startInAfter,
        matchingBlocks,
      );
    }
    matchingBlocks.push(match);
    if (match.endInBefore <= endInBefore && match.endInAfter <= endInAfter) {
      recursivelyFindMatchingBlocks(
        beforeTokens,
        afterTokens,
        index,
        match.endInBefore + 1,
        endInBefore,
        match.endInAfter + 1,
        endInAfter,
        matchingBlocks,
      );
    }
  }
  return matchingBlocks;
}

function findMatchingBlocks(beforeTokens: string[], afterTokens: string[]): Match[] {
  const index = createIndex(beforeTokens, afterTokens);
  return recursivelyFindMatchingBlocks(
    beforeTokens,
    afterTokens,
    index,
    0,
    beforeTokens.length,
    0,
    afterTokens.length,
    [],
  );
}

const ACTION_MAP: Record<string, Operation['action']> = {
  'false,false': 'replace',
  'true,false': 'insert',
  'false,true': 'delete',
  'true,true': 'none',
};

/** Turn the matching blocks into an ordered list of equal / insert / delete / replace operations. */
function calculateOperations(beforeTokens: string[], afterTokens: string[]): Operation[] {
  let positionInBefore = 0;
  let positionInAfter = 0;
  const operations: Operation[] = [];

  const matches = findMatchingBlocks(beforeTokens, afterTokens);
  matches.push(new Match(beforeTokens.length, afterTokens.length, 0));

  for (const match of matches) {
    const matchStartsAtCurrentBefore = positionInBefore === match.startInBefore;
    const matchStartsAtCurrentAfter = positionInAfter === match.startInAfter;
    const action = ACTION_MAP[`${matchStartsAtCurrentBefore},${matchStartsAtCurrentAfter}`];
    if (action !== 'none') {
      operations.push({
        action,
        startInBefore: positionInBefore,
        endInBefore: action !== 'insert' ? match.startInBefore - 1 : undefined,
        startInAfter: positionInAfter,
        endInAfter: action !== 'delete' ? match.startInAfter - 1 : undefined,
      });
    }
    if (match.length !== 0) {
      operations.push({
        action: 'equal',
        startInBefore: match.startInBefore,
        endInBefore: match.endInBefore,
        startInAfter: match.startInAfter,
        endInAfter: match.endInAfter,
      });
    }
    positionInBefore = match.endInBefore + 1;
    positionInAfter = match.endInAfter + 1;
  }

  // Coalesce consecutive replaces (and a lone whitespace token wedged between them) into one.
  const postProcessed: Operation[] = [];
  let lastOp: Operation = { action: 'none', startInBefore: 0, startInAfter: 0 };
  const isSingleWhitespace = (op: Operation): boolean => {
    if (op.action !== 'equal' || (op.endInBefore ?? 0) - op.startInBefore !== 0) {
      return false;
    }
    return /^\s$/.test(
      beforeTokens.slice(op.startInBefore, (op.endInBefore ?? op.startInBefore) + 1).join(''),
    );
  };
  for (const op of operations) {
    if (
      (isSingleWhitespace(op) && lastOp.action === 'replace') ||
      (op.action === 'replace' && lastOp.action === 'replace')
    ) {
      lastOp.endInBefore = op.endInBefore;
      lastOp.endInAfter = op.endInAfter;
    } else {
      postProcessed.push(op);
      lastOp = op;
    }
  }
  return postProcessed;
}

/** The leading run of tokens from `start` for which `predicate` holds. */
function consecutiveWhere(
  start: number,
  content: string[],
  predicate: (token: string) => boolean,
): string[] {
  const sliced = content.slice(start);
  let lastMatchingIndex: number | undefined;
  for (let index = 0; index < sliced.length; index++) {
    const answer = predicate(sliced[index]);
    if (answer) lastMatchingIndex = index;
    else break;
  }
  return lastMatchingIndex !== undefined ? sliced.slice(0, lastMatchingIndex + 1) : [];
}

/** Wrap the non-tag runs of `content` in `<tag>`, leaving the tags themselves outside the wrapper. */
function wrap(tag: 'ins' | 'del', content: string[]): string {
  let rendering = '';
  let position = 0;
  const { length } = content;
  for (;;) {
    if (position >= length) break;
    const nonTags = consecutiveWhere(position, content, isntTag);
    position += nonTags.length;
    if (nonTags.length !== 0) {
      rendering += `<${tag}>${nonTags.join('')}</${tag}>`;
    }
    if (position >= length) break;
    const tags = consecutiveWhere(position, content, isTag);
    position += tags.length;
    rendering += tags.join('');
  }
  return rendering;
}

function inclusiveSlice(tokens: string[], start: number, end: number | undefined): string[] {
  return tokens.slice(start, (end ?? start - 1) + 1);
}

function renderOperation(op: Operation, beforeTokens: string[], afterTokens: string[]): string {
  switch (op.action) {
    case 'equal':
      return inclusiveSlice(beforeTokens, op.startInBefore, op.endInBefore).join('');
    case 'insert':
      return wrap('ins', inclusiveSlice(afterTokens, op.startInAfter, op.endInAfter));
    case 'delete':
      return wrap('del', inclusiveSlice(beforeTokens, op.startInBefore, op.endInBefore));
    case 'replace':
      return (
        wrap('del', inclusiveSlice(beforeTokens, op.startInBefore, op.endInBefore)) +
        wrap('ins', inclusiveSlice(afterTokens, op.startInAfter, op.endInAfter))
      );
    default:
      return '';
  }
}

/**
 * Diff two HTML strings. Returns HTML with additions wrapped in `<ins>` and deletions in `<del>`
 * (tags are diffed but never wrapped). Identical inputs are returned unchanged.
 */
export function htmlDiff(before: string, after: string): string {
  if (before === after) {
    return before;
  }
  const beforeTokens = htmlToTokens(before);
  const afterTokens = htmlToTokens(after);
  const operations = calculateOperations(beforeTokens, afterTokens);
  return operations.map((op) => renderOperation(op, beforeTokens, afterTokens)).join('');
}
