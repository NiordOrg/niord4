// The accessibility scan's own parts, on a crafted axe result: what a tag names, how results are
// grouped, and that no page content reaches a report.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  anyViolation,
  criteriaOf,
  groupScans,
  kindOf,
  reduceResult,
  renderMarkdown,
  safeSelector,
} from './info-a11y-scan.mjs';

/** An axe result as axe-core returns it, with the page's markup and text in its nodes. */
function axeResult(id, tags, targets, impact = 'serious') {
  return {
    id,
    impact,
    tags,
    help: `Help for ${id}`,
    helpUrl: `https://dequeuniversity.com/rules/axe/4.14/${id}`,
    nodes: targets.map((target) => ({
      target,
      impact,
      html: '<p class="secret">SECRET MESSAGE TEXT</p>',
      failureSummary: 'Fix any of the following: SECRET MESSAGE TEXT',
      any: [{ message: 'SECRET MESSAGE TEXT', data: { fgColor: '#667589' } }],
    })),
  };
}

test('a criterion tag names its success criterion, one- and two-digit numbers alike', () => {
  assert.deepEqual(criteriaOf(['cat.color', 'wcag2aa', 'wcag143']), ['1.4.3']);
  assert.deepEqual(criteriaOf(['wcag21aa', 'wcag1410']), ['1.4.10']);
  assert.deepEqual(criteriaOf(['wcag111', 'wcag412']), ['1.1.1', '4.1.2']);
});

test('a level tag names no criterion', () => {
  assert.deepEqual(
    criteriaOf(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag2aaa', 'wcag22aa']),
    [],
  );
  assert.deepEqual(criteriaOf(undefined), []);
});

test('a rule tagged only best practice is kept apart from the WCAG rules', () => {
  assert.equal(kindOf(['cat.semantics', 'best-practice']), 'best-practice');
  assert.equal(kindOf(['cat.color', 'wcag2aa', 'wcag143']), 'wcag');
  // A rule of a later WCAG or of level AAA alone is no 2.1 A or AA failure.
  assert.equal(kindOf(['wcag22aa', 'wcag258']), 'best-practice');
});

test('a reduced result keeps the selectors and drops the markup, the summary and the checks', () => {
  const reduced = reduceResult(axeResult('color-contrast', ['wcag2aa', 'wcag143'], [['p.x']]));
  assert.deepEqual(reduced.nodes, [{ target: 'p.x', impact: 'serious' }]);
  assert.doesNotMatch(JSON.stringify(reduced), /SECRET|667589/);
});

test('a quoted value in a selector is cut out', () => {
  assert.equal(
    safeSelector(['a[href="/rest/repo/file/secret.pdf"]', "img[alt='Secret']"]),
    'a[href="…"] >>> img[alt="…"]',
  );
  assert.equal(safeSelector('#main-content'), '#main-content');
});

test('scans group by state and rule, summing nodes over languages and widths', () => {
  const contrast = reduceResult(
    axeResult('color-contrast', ['wcag2aa', 'wcag143'], [['.a'], ['.b']]),
  );
  const heading = reduceResult(
    axeResult('page-has-heading-one', ['cat.semantics', 'best-practice'], [['html']], 'moderate'),
  );
  const states = groupScans([
    { state: '/a', lang: 'en', width: 1280, violations: [contrast, heading], incomplete: [] },
    { state: '/a', lang: 'en', width: 320, violations: [contrast], incomplete: [] },
    { state: '/b', lang: 'en', width: 1280, violations: [], incomplete: [contrast] },
  ]);
  assert.deepEqual([...states.keys()], ['/a', '/b']);
  const a = states.get('/a');
  const rule = a.get('violation|wcag').get('color-contrast');
  assert.equal(rule.nodes, 4);
  assert.deepEqual(rule.criteria, ['1.4.3']);
  assert.deepEqual(rule.seen, ['en@1280', 'en@320']);
  assert.equal(rule.firstTarget, '.a');
  assert.ok(!a.get('violation|wcag').has('page-has-heading-one'));
  assert.ok(a.get('violation|best-practice').has('page-has-heading-one'));
  assert.ok(states.get('/b').get('incomplete|wcag').has('color-contrast'));
  assert.ok(!states.get('/b').has('violation|wcag'));
});

test('the report has one row per rule and carries no page content', () => {
  const results = [
    axeResult('color-contrast', ['wcag2aa', 'wcag143'], [['.a'], ['.b']]),
    axeResult('region', ['cat.keyboard', 'best-practice'], [['footer']], 'moderate'),
  ];
  const reduced = results.map(reduceResult);
  const scans = [
    { state: '/a', lang: 'en', width: 1280, violations: reduced, incomplete: [] },
    { state: '/a', lang: 'en', width: 320, violations: reduced, incomplete: [] },
    { state: '/c', lang: 'en', width: 1280, violations: [], incomplete: [] },
  ];
  const markdown = renderMarkdown(groupScans(scans), { 'axe-core': '4.14.0' });
  assert.equal(markdown.match(/^\| color-contrast \|/gm)?.length, 1);
  assert.equal(markdown.match(/^\| region \|/gm)?.length, 1);
  assert.match(
    markdown,
    /\| color-contrast \| 1\.4\.3 \| serious \| 4 \| en@1280, en@320 \| `\.a` \|/,
  );
  // The best-practice rule is in its own table, after the WCAG one.
  assert.ok(
    markdown.indexOf('Best practice: violations') > markdown.indexOf('| color-contrast |') &&
      markdown.indexOf('| region |') > markdown.indexOf('Best practice: violations'),
  );
  assert.match(markdown, /## \/c\n\nNo rule failed and none was left undecided\./);
  assert.doesNotMatch(markdown, /SECRET|<p|667589/);
});

test('the run fails while any scan has a violation', () => {
  assert.equal(anyViolation([{ violations: [], incomplete: [{}] }]), false);
  assert.equal(anyViolation([{ violations: [{}], incomplete: [] }]), true);
});
