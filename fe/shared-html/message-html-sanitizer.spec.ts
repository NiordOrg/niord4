import { SecurityContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { type MockInstance, vi } from 'vitest';

import { MESSAGE_HTML_CLASSES } from './message-html-classes';
import { MessageHtmlSanitizer } from './message-html-sanitizer';

/**
 * The text a fragment renders. The sanitiser's own serialiser writes non-ASCII as a character
 * reference and the DOM's writes the character, so a spec about the text reads the text.
 */
function textOf(html: string): string {
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? '';
}

/** The elements of a fragment that match a selector. */
function select(html: string, selector: string): Element[] {
  return [...new DOMParser().parseFromString(html, 'text/html').body.querySelectorAll(selector)];
}

describe('MessageHtmlSanitizer', () => {
  let service: MessageHtmlSanitizer;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MessageHtmlSanitizer);
  });

  it('answers with an empty string for nothing to render', () => {
    expect(service.sanitizeToString(null)).toBe('');
    expect(service.sanitizeToString(undefined)).toBe('');
    expect(service.sanitizeToString('')).toBe('');
  });

  it('keeps ordinary text marks', () => {
    const result = service.sanitizeToString('<p>Hello <strong>world</strong></p>');
    expect(result).toContain('<p>');
    expect(result).toContain('<strong>');
    expect(result).toContain('world');
  });

  it('drops script elements and their content', () => {
    const result = service.sanitizeToString('<p>Safe</p><script>alert("xss")</script>');
    expect(result).not.toContain('<script');
    expect(result).not.toContain('alert');
    expect(result).toContain('Safe');
  });

  it('drops event attributes', () => {
    expect(service.sanitizeToString('<img src="x" onerror="alert(1)">')).not.toContain('onerror');
  });

  it('neutralises a javascript: URL and keeps the link text', () => {
    const result = service.sanitizeToString('<a href="javascript:alert(1)">click</a>');
    expect(result).toContain('unsafe:');
    expect(result).toContain('click');
  });

  it('neutralises a javascript: URL on an element that also carries a kept declaration', () => {
    const result = service.sanitizeToString(
      '<a href="javascript:alert(1)" style="text-align: center">click</a>',
    );
    expect(result).toContain('unsafe:');
    expect(result).not.toContain('href="javascript:');
    expect(result).toContain('text-align: center');
  });

  // The message-HTML stylesheet styles the position tables by class, so the classes an editor
  // writes are the contract; these are the ones the shipped notices carry.
  describe('the class vocabulary the shared stylesheet renders', () => {
    it('keeps a position table with its row and cell classes', () => {
      const result = service.sanitizeToString(
        '<table class="position-table"><tr class="underline">' +
          '<td class="pos-index">1</td><td class="pos-col">55&deg; N</td></tr></table>',
      );
      expect(result).toContain('class="position-table"');
      expect(result).toContain('class="underline"');
      expect(result).toContain('class="pos-index"');
      expect(result).toContain('class="pos-col"');
      expect(textOf(result)).toContain('55° N');
    });

    it('keeps a multi-class table', () => {
      const result = service.sanitizeToString(
        '<table class="no-border condensed"><tbody><tr><td class="underline">x</td></tr></tbody></table>',
      );
      expect(result).toContain('class="no-border condensed"');
      expect(result).toContain('class="underline"');
    });

    it('keeps a no-wrap span', () => {
      const result = service.sanitizeToString('<span class="no-wrap">55° 43.2\' N</span>');
      expect(result).toContain('class="no-wrap"');
      expect(textOf(result)).toContain("55° 43.2' N");
    });

    it('keeps the lists and the repository images a message carries', () => {
      const result = service.sanitizeToString(
        '<ul class="positions"><li><p>one</p><p>two</p></li></ul>' +
          '<img src="/rest/repo/file/messages/a.png">',
      );
      expect(result).toContain('class="positions"');
      expect(result).toContain('<li>');
      expect(result).toContain('src="/rest/repo/file/messages/a.png"');
    });

    it('keeps a class the message already carried alongside a kept declaration', () => {
      const result = service.sanitizeToString(
        '<p class="no-wrap" style="text-align: justify">Position 1</p>',
      );
      expect(result).toContain('class="no-wrap"');
      expect(result).toContain('text-align: justify');
      expect(result).toContain('Position 1');
    });
  });

  // The seven properties the shipped messages actually carry in a `style` attribute. Two of them
  // are an editor's decision about the text and are kept; the other five are residue of a paste
  // out of a word processor and would override the site's own type scale.
  describe('the seven properties the messages carry', () => {
    it('keeps a justified paragraph', () => {
      const result = service.sanitizeToString(
        '<p style="text-align: justify;">Indtil videre er der etableret et restriktionsområde.</p>',
      );
      expect(result).toContain('style="text-align: justify"');
      expect(result).toContain('Indtil videre');
    });

    it('keeps an indented paragraph', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: 30px;"><strong>19. - 20. september 2026</strong></p>',
      );
      expect(result).toContain('style="padding-left: 30px"');
      expect(result).toContain('<strong>');
    });

    it('keeps both when one element carries both', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: 30px; text-align: justify;">both</p>',
      );
      expect(result).toContain('style="padding-left: 30px; text-align: justify"');
    });

    it('drops the five a word processor left behind, and keeps the text', () => {
      const result = service.sanitizeToString(
        '<p style="margin: 0cm 0cm 2.25pt 0cm;">' +
          '<span style="font-size: 8.5pt; font-family: \'Verdana\',sans-serif; color: black;">' +
          'Der kan forekomme opankrede skydemål i området.</span></p>' +
          '<span style="background-color: #ffffff;">,</span>' +
          '<span style="color: #000000;">Oplysning om skydning</span>',
      );
      expect(result).not.toContain('style=');
      expect(result).not.toContain('margin');
      expect(result).not.toContain('font-size');
      expect(result).not.toContain('font-family');
      expect(result).not.toContain('Verdana');
      expect(result).not.toContain('color');
      expect(result).not.toContain('#ffffff');
      expect(result).toContain('Der kan forekomme opankrede skydem');
      expect(result).toContain('Oplysning om skydning');
    });

    it('drops an empty style attribute without leaving a class behind', () => {
      const result = service.sanitizeToString('<p style="">text</p>');
      expect(result).toBe('<p>text</p>');
    });

    it('drops a style attribute whose declarations are all malformed', () => {
      const result = service.sanitizeToString('<p style="padding-left;text-align">text</p>');
      expect(result).toBe('<p>text</p>');
    });
  });

  describe('the values the two allowed properties accept', () => {
    it('accepts the four alignment keywords, whatever their case', () => {
      for (const alignment of ['left', 'right', 'center', 'justify']) {
        expect(service.sanitizeToString(`<p style="text-align: ${alignment}">x</p>`)).toContain(
          `text-align: ${alignment}`,
        );
      }
      expect(service.sanitizeToString('<p style="TEXT-ALIGN: JUSTIFY">x</p>')).toContain(
        'text-align: justify',
      );
    });

    it('drops an alignment keyword that is not one of the four', () => {
      expect(service.sanitizeToString('<p style="text-align: end">x</p>')).not.toContain('style=');
      expect(service.sanitizeToString('<p style="text-align: inherit">x</p>')).not.toContain(
        'style=',
      );
    });

    it('accepts a length in each unit a word processor emits', () => {
      for (const length of ['30px', '2rem', '1.5em', '18pt', '1cm']) {
        expect(service.sanitizeToString(`<p style="padding-left: ${length}">x</p>`)).toContain(
          `padding-left: ${length}`,
        );
      }
    });

    it('drops an indent wider than the cap, in whatever unit it is written', () => {
      for (const length of ['161px', '11rem', '11em', '121pt', '5cm']) {
        expect(service.sanitizeToString(`<p style="padding-left: ${length}">x</p>`)).not.toContain(
          'style=',
        );
      }
      expect(service.sanitizeToString('<p style="padding-left: 160px">x</p>')).toContain(
        'padding-left: 160px',
      );
    });

    it('drops a negative, a unitless and a percentage indent', () => {
      for (const length of ['-30px', '30', '50%', '0']) {
        expect(service.sanitizeToString(`<p style="padding-left: ${length}">x</p>`)).not.toContain(
          'style=',
        );
      }
    });

    it('drops an expression()', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: expression(alert(1)); text-align: expression(alert(2))">x</p>',
      );
      expect(result).toBe('<p>x</p>');
      expect(result).not.toContain('expression');
    });

    it('drops a url()', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: url(javascript:alert(1)); background: url(//evil/x.png)">x</p>',
      );
      expect(result).toBe('<p>x</p>');
      expect(result).not.toContain('url(');
    });

    it('drops a calc() and a var()', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: calc(100% - 2px); text-align: var(--x)">x</p>',
      );
      expect(result).toBe('<p>x</p>');
    });

    it('drops a declaration that tries to close the attribute', () => {
      const result = service.sanitizeToString(
        '<p style=\'padding-left: 30px" onmouseover="alert(1)\'>x</p>',
      );
      expect(result).not.toContain('onmouseover');
      expect(result).not.toContain('alert');
    });
  });

  describe('an element the sanitiser removes', () => {
    // The sanitiser drops an element it does not know but hoists its children, so every element
    // after it moves up: a mark that travels WITH the element is the only one that still fits.
    // `<o:p>`, which a paste out of a word processor leaves behind, is taken out before the
    // sanitiser sees it; `<blink>` is left for the sanitiser to drop.
    it('does not shift the marks of the elements around it', () => {
      const result = service.sanitizeToString(
        '<p style="text-align: justify">first</p>' +
          '<o:p>hoisted</o:p>' +
          '<p style="padding-left: 30px">second</p>' +
          '<o:p><span style="text-align: center">nested</span></o:p>' +
          '<blink style="padding-left: 30px"><span style="text-align: left">blinked</span></blink>' +
          '<p style="text-align: right">third</p>',
      );
      expect(result).toContain('<p style="text-align: justify">first</p>');
      expect(result).toContain('<p style="padding-left: 30px">second</p>');
      expect(result).toContain('<span style="text-align: center">nested</span>');
      expect(result).toContain('<span style="text-align: left">blinked</span>');
      expect(result).toContain('<p style="text-align: right">third</p>');
      expect(result).not.toContain('o:p');
      expect(result).not.toContain('blink>');
      expect(result).toContain('hoisted');
    });

    it('takes its own mark with it', () => {
      const result = service.sanitizeToString(
        '<blink style="padding-left: 30px">gone</blink><p>kept</p>',
      );
      expect(result).not.toContain('style=');
      expect(result).not.toContain('class=');
      expect(result).toContain('gone');
      expect(result).toContain('<p>kept</p>');
    });

    it('does not leave a mark on a message that forged one', () => {
      const result = service.sanitizeToString(
        '<p class="niord-inline-style-0">forged</p><p style="padding-left: 30px">real</p>',
      );
      expect(result).toContain('<p>forged</p>');
      expect(result).toContain('<p style="padding-left: 30px">real</p>');
      expect(result).not.toContain('niord-inline-style');
    });
  });

  // The stored messages carry what earlier editors left in them: the comments and data attributes
  // of the position lists, a word processor's `<o:p>`, TinyMCE's and AngularJS's attributes. The
  // sanitiser drops all of it and warns each time, which buries a warning about markup nobody
  // expected under hundreds of these; they are taken out before it, with the same result.
  describe('what earlier editors left in the markup', () => {
    let dom: DomSanitizer;
    let warn: MockInstance<typeof console.warn>;

    beforeEach(() => {
      dom = TestBed.inject(DomSanitizer);
      warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });

    afterEach(() => warn.mockRestore());

    /** Angular's own result for the markup, and the warnings it gave producing it. */
    function sanitizedByAngular(html: string): { result: string; warnings: number } {
      warn.mockClear();
      const result = dom.sanitize(SecurityContext.HTML, html) ?? '';
      const warnings = warn.mock.calls.length;
      warn.mockClear();
      return { result, warnings };
    }

    const leftovers: [string, string][] = [
      ['a comment', "<p>1</p><!-- from position --><p>55° 30,0' N</p><!-- to position -->"],
      ['what a word processor writes as a comment', '<?xml:namespace prefix="o" ?><p>x</p>'],
      [
        'the data attributes of a position',
        '<span data-latitude="55.5" data-longitude="10.25">55° 30,0\' N</span>',
      ],
      ['an id', '<p id="section-1">x</p>'],
      ['a TinyMCE attribute', '<span mce-data-marked="1">x</span>'],
      ['AngularJS attributes', '<div ng-if="part.descs" ng-bind-html="part.descs">x</div>'],
      [
        'a caption attribute',
        '<table caption="Positions"><tbody><tr><td>1</td></tr></tbody></table>',
      ],
      [
        'a publication reference',
        '<a publication="800d2c57-672a-494a-81dd-90d1c6480b32" href="https://example.org/p">[J. no. 1].</a>',
      ],
      [
        'the attributes of a case-system link',
        '<a href="https://example.org/case?recno=1" VerID="7" recno="575780">case</a>',
      ],
      [
        'the elements a word processor writes',
        '<p>Skydning<o:p>&nbsp;</o:p></p><o:p><strong>nested</strong> <o:p>twice</o:p></o:p>',
      ],
      [
        'the AngularJS directive that was saved with its message part',
        '<render-message-part part="part" subject="true"><div>x</div></render-message-part>',
      ],
    ];

    for (const [what, html] of leftovers) {
      it(`removes ${what} as the sanitiser does, without its warning`, () => {
        const angular = sanitizedByAngular(html);
        expect(angular.warnings).toBeGreaterThan(0);

        expect(service.sanitizeToString(html)).toBe(angular.result);
        expect(warn).not.toHaveBeenCalled();
      });
    }

    it('removes them in the same parse that records a kept declaration', () => {
      const result = service.sanitizeToString(
        '<p style="padding-left: 30px" data-latitude="55.5" id="p1">x<o:p></o:p></p><!-- c -->',
      );
      expect(result).toBe('<p style="padding-left: 30px">x</p>');
      expect(warn).not.toHaveBeenCalled();
    });

    it('reads the markup as the sanitiser does, keeping a leading line break', () => {
      const html = '\n<p data-latitude="55.5">x</p>';
      expect(service.sanitizeToString(html)).toBe(sanitizedByAngular(html).result);
    });

    it('still removes a script, an event attribute and a javascript: URL beside them, and warns', () => {
      const html =
        '<p data-latitude="55.5">a</p><o:p><script>alert("xss")</script></o:p>' +
        '<img src="x" onerror="alert(1)" id="i"><a href="javascript:alert(2)" id="l">click</a>';
      const angular = sanitizedByAngular(html);

      const result = service.sanitizeToString(html);
      expect(result).toBe(angular.result);
      expect(result).not.toContain('<script');
      expect(result).not.toContain('alert("xss")');
      expect(result).not.toContain('onerror');
      expect(result).toContain('href="unsafe:javascript:alert(2)"');
      const warnings = warn.mock.calls.map(([message]) => String(message));
      expect(warnings.some((w) => w.includes('sanitizing HTML stripped some content'))).toBe(true);
      expect(warnings.some((w) => w.includes('sanitizing unsafe URL value'))).toBe(true);
    });

    it('still warns about an element nobody expected', () => {
      service.sanitizeToString(
        '<p data-latitude="55.5">a</p><iframe src="https://example.com"></iframe>',
      );
      expect(warn).toHaveBeenCalledTimes(1);
    });

    // Taking `<o:p>` out of `<h1><o:p><h2>` leaves `<h1><h2>`, which the parser reads as two
    // headings side by side: the sanitiser would be handed a different tree.
    it('leaves a leftover whose removal would change how the markup parses', () => {
      const html = '<h1><o:p><h2 data-end="1">x</h2></o:p></h1>';
      const angular = sanitizedByAngular(html);

      expect(service.sanitizeToString(html)).toBe(angular.result);
      expect(warn).toHaveBeenCalled();
    });

    // In a browser a field inside a form stands in for the form's own property of the same name, so
    // `form.attributes` or `form.classList` may be an input that a pass reading them trips over.
    // The DOM these specs run on does not do that, so here the result is only compared.
    it('reads nothing off a form, whose fields can stand in for its properties', () => {
      const html =
        '<form style="text-align: center" data-x="1"><input name="attributes">' +
        '<input name="classList"><input name="nodeName"><p data-latitude="55.5">x</p></form>';
      expect(service.sanitizeToString(html)).toBe(sanitizedByAngular(html).result);
    });

    describe('markup with none of them', () => {
      let parse: MockInstance<DOMParser['parseFromString']>;

      beforeEach(() => {
        // The sanitiser builds its parser on first use; build it before counting.
        dom.sanitize(SecurityContext.HTML, '<p>x</p>');
        parse = vi.spyOn(DOMParser.prototype, 'parseFromString');
      });

      afterEach(() => parse.mockRestore());

      /** How often the markup is parsed on its way through a sanitiser. */
      function parsesOf(sanitize: () => unknown): number {
        parse.mockClear();
        sanitize();
        return parse.mock.calls.length;
      }

      it('is parsed no more often than the sanitiser alone parses it', () => {
        const html = "<p>Hello <strong>world</strong></p><ul><li>55° 30,0' N</li></ul>";
        const alone = parsesOf(() => dom.sanitize(SecurityContext.HTML, html));

        expect(parsesOf(() => service.sanitizeToString(html))).toBe(alone);
        // The count sees the preparing pass where there is one.
        const leftover = '<p data-latitude="55.5">x</p>';
        expect(parsesOf(() => service.sanitizeToString(leftover))).toBeGreaterThan(
          parsesOf(() => dom.sanitize(SecurityContext.HTML, leftover)),
        );
      });
    });
  });

  // Both applications' stylesheets carry global utilities, and a class out of a message reaches
  // them as surely as one out of a template.
  describe('a class outside the vocabulary', () => {
    it('is dropped, so a message cannot lay itself over the page', () => {
      const result = service.sanitizeToString(
        '<div class="fixed inset-0 z-50 bg-surface-0"><p>Log in again</p></div>',
      );
      expect(result).not.toContain('class=');
      expect(result).not.toContain('fixed');
      expect(textOf(result)).toContain('Log in again');
    });

    it('is dropped beside a class the vocabulary keeps', () => {
      const result = service.sanitizeToString(
        '<table class="position-table sr-only"><tr><td class="pos-col hidden">1</td></tr></table>',
      );
      expect(select(result, 'table')[0].getAttribute('class')).toBe('position-table');
      expect(select(result, 'td')[0].getAttribute('class')).toBe('pos-col');
    });

    it('is dropped on an element that also carries a kept declaration', () => {
      const result = service.sanitizeToString(
        '<p class="absolute top-0" style="padding-left: 30px">indented</p>',
      );
      expect(result).toBe('<p style="padding-left: 30px">indented</p>');
    });

    it('keeps every class of the vocabulary', () => {
      for (const name of MESSAGE_HTML_CLASSES) {
        expect(select(service.sanitizeToString(`<p class="${name}">x</p>`), 'p')[0].className).toBe(
          name,
        );
      }
    });
  });

  // A link that opens a new tab hands the opened page `window.opener` unless it says otherwise,
  // and `rel="opener"` says it outright.
  describe('a link that names a target', () => {
    it('is given noopener and noreferrer', () => {
      const [link] = select(
        service.sanitizeToString('<a href="https://example.com" target="_blank">x</a>'),
        'a',
      );
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('href')).toBe('https://example.com');
    });

    it('loses a rel that would hand the opened page this one', () => {
      const [link] = select(
        service.sanitizeToString(
          '<a href="https://example.com" target="_blank" rel="opener">x</a>',
        ),
        'a',
      );
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('is given it whatever the target is called', () => {
      const [link] = select(
        service.sanitizeToString('<a href="https://example.com" target="chart">x</a>'),
        'a',
      );
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('is given it on an element that also carries a kept declaration', () => {
      const [link] = select(
        service.sanitizeToString(
          '<a href="https://example.com" target="_blank" rel="opener" style="text-align: center">x</a>',
        ),
        'a',
      );
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.getAttribute('style')).toBe('text-align: center');
    });

    it('leaves a link without a target as it was', () => {
      const html = '<a href="/rest/repo/file/a.pdf">x</a>';
      expect(service.sanitizeToString(html)).toBe(html);
    });
  });

  describe('the result a template binds', () => {
    it('is marked safe, so the two declarations survive the binding', () => {
      const html = '<p style="padding-left: 30px">indented</p>';
      const dom = TestBed.inject(DomSanitizer);

      expect(dom.sanitize(SecurityContext.HTML, service.sanitize(html))).toBe(
        service.sanitizeToString(html),
      );
      // The same string bound as a plain string would lose the declaration again.
      expect(dom.sanitize(SecurityContext.HTML, service.sanitizeToString(html))).not.toContain(
        'style=',
      );
    });
  });
});
