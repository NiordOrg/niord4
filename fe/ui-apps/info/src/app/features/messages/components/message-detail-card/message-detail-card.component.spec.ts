import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { MessageDetailCardComponent } from './message-detail-card.component';
import {
  provideTestNiordConfig,
  getTranslocoTestingModule,
  TEST_TRANSLATIONS,
} from '../../../../testing/test-config';
import { Message } from '../../../../core/models/message.model';
import { FakeResizeObserver } from '../../../../testing/fake-resize-observer';

describe('MessageDetailCardComponent', () => {
  let fixture: ComponentFixture<MessageDetailCardComponent>;
  let component: MessageDetailCardComponent;

  const baseMsg: Message = {
    id: 'msg-1',
    mainType: 'NW',
    shortId: 'NW-001',
    descs: [{ lang: 'en', title: 'Test Message' }],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageDetailCardComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig()],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    fixture = TestBed.createComponent(MessageDetailCardComponent);
    component = fixture.componentInstance;
  });

  it('should display title from desc', () => {
    fixture.componentRef.setInput('msg', baseMsg);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Test Message');
  });

  // A Danish compound in a wide system font is wider than a phone; a title that cannot break it
  // takes the page sideways. jsdom lays nothing out; the width itself is checked in a browser by
  // npm run layout:info.
  it.each(['list', 'details'] as const)(
    'breaks a title word longer than the card (%s)',
    (format) => {
      fixture.componentRef.setInput('msg', {
        ...baseMsg,
        descs: [{ lang: 'da', title: 'Farvandsafmærkningsændringer' }],
      });
      fixture.componentRef.setInput('format', format);
      fixture.detectChanges();

      const title = (fixture.nativeElement as HTMLElement).querySelector('.message-title')!;
      expect(title.textContent!.trim()).toBe('Farvandsafmærkningsændringer');
      expect(title.classList).toContain('break-words');
    },
  );

  it('should render references section', () => {
    const msg: Message = {
      ...baseMsg,
      references: [{ messageId: 'ref-1', type: 'REPETITION', descs: [] }],
    };
    fixture.componentRef.setInput('msg', msg);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('ref-1');
  });

  it('should format charts text correctly', () => {
    const msg: Message = {
      ...baseMsg,
      charts: [{ chartNumber: '101', internationalNumber: 42 }, { chartNumber: '202' }],
    };
    fixture.componentRef.setInput('msg', msg);
    fixture.detectChanges();
    expect(component.chartsText()).toBe('101 (INT 42), 202.');
  });

  /** The buttons of the card whose text is exactly this. */
  function buttonsLabelled(label: string): HTMLButtonElement[] {
    return [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ].filter((button) => button.textContent!.trim() === label);
  }

  it('lists the attachments behind a toggle that opens and closes the list', () => {
    const msg: Message = {
      ...baseMsg,
      attachments: [{ path: '/img/a.png', fileName: 'a.png' }],
    };
    fixture.componentRef.setInput('msg', msg);
    fixture.detectChanges();
    const links = () =>
      (fixture.nativeElement as HTMLElement).querySelectorAll('a[href="/img/a.png"]');
    expect(links()).toHaveLength(0);
    expect(buttonsLabelled('Hide attachments')).toHaveLength(0);

    buttonsLabelled('Show attachments')[0].click();
    fixture.detectChanges();
    expect(links()).toHaveLength(1);
    expect(links()[0].textContent!.trim()).toBe('a.png');

    buttonsLabelled('Hide attachments')[0].click();
    fixture.detectChanges();
    expect(links()).toHaveLength(0);
    expect(buttonsLabelled('Show attachments')).toHaveLength(1);
  });

  // A keyboard user who presses the toggle stays on it: one button changes its words and says
  // whether the list is open, rather than being swapped for another one, which would drop focus
  // to the page (WCAG 2.4.3).
  it('keeps focus on the attachments toggle, one button that says whether the list is open', () => {
    fixture.componentRef.setInput('msg', {
      ...baseMsg,
      attachments: [{ path: '/img/a.png', fileName: 'a.png' }],
    });
    fixture.detectChanges();

    const toggle = buttonsLabelled('Show attachments')[0];
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.focus();

    toggle.click();
    fixture.detectChanges();
    expect(buttonsLabelled('Hide attachments')[0]).toBe(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(toggle);

    toggle.click();
    fixture.detectChanges();
    expect(buttonsLabelled('Show attachments')[0]).toBe(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
  });

  // A reference id stands in a line of text; a colour alone would not tell it from the text
  // around it (WCAG 1.4.1), so it is drawn as a plain link, underlined, as an attachment is.
  it('draws a reference id as an underlined link, not as a subtle one', () => {
    fixture.componentRef.setInput('msg', {
      ...baseMsg,
      references: [{ messageId: 'NW-099-26', type: 'REPETITION', descs: [] }],
    });
    fixture.detectChanges();

    const reference = buttonsLabelled('NW-099-26')[0];
    expect(reference.classList).toContain('link-button');
    expect(reference.classList).not.toContain('is-subtle');
  });

  // The title is what a screen reader moves from message to message by (WCAG 1.3.1): a heading
  // one level below the heading the card stands under, the area's h4 in the list and on the
  // print page. The dialog sets its own level, below its title.
  it.each([
    ['list', undefined, '5'],
    ['details', undefined, '5'],
    ['details', 3, '3'],
  ] as const)('puts the title in a heading (%s, level %s given)', (format, level, expected) => {
    fixture.componentRef.setInput('msg', baseMsg);
    fixture.componentRef.setInput('format', format);
    if (level !== undefined) fixture.componentRef.setInput('headingLevel', level);
    fixture.detectChanges();

    const headings = (fixture.nativeElement as HTMLElement).querySelectorAll('[role="heading"]');
    expect(headings).toHaveLength(1);
    expect(headings[0].getAttribute('aria-level')).toBe(expected);
    expect(headings[0].querySelector('.message-title')!.textContent!.trim()).toBe('Test Message');
    // In the list the title is still the button that opens the message, inside the heading.
    expect(headings[0].querySelector('button.row-link') !== null).toBe(format === 'list');
  });

  // A run of parts of one kind is named once, above its first part; a kind that comes back after
  // another is named again.
  it('names each run of parts of one kind once', () => {
    fixture.componentRef.setInput('msg', {
      ...baseMsg,
      parts: [
        { type: 'DETAILS', descs: [{ lang: 'en', details: '<p>One</p>' }] },
        { type: 'DETAILS', descs: [{ lang: 'en', details: '<p>Two</p>' }] },
        { type: 'TIME', descs: [{ lang: 'en', details: '<p>Monday</p>' }] },
        { type: 'DETAILS', descs: [{ lang: 'en', details: '<p>Three</p>' }] },
      ],
    });
    fixture.detectChanges();

    const headings = [...(fixture.nativeElement as HTMLElement).querySelectorAll('dt')].map((dt) =>
      dt.textContent!.trim(),
    );
    expect(headings).toEqual(['Details', '', 'Time', 'Details']);
  });

  // A position table is wider than a phone and scrolls inside a box of its own. That box sits
  // inside the dd, which keeps its place in the definition list, and while it scrolls it is a
  // tab stop named after the part, so a keyboard can scroll it. When it does scroll is layout,
  // which the directive's own spec and the layout check (npm run layout:info) cover.
  it('scrolls a wide part inside a region named after the part, within the dd', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    try {
      const msg: Message = {
        ...baseMsg,
        parts: [
          {
            type: 'POSITIONS',
            descs: [
              { lang: 'en', details: '<table class="positions"><tr><td>1)</td></tr></table>' },
            ],
          },
        ],
      };
      fixture.componentRef.setInput('msg', msg);
      fixture.detectChanges();
      TestBed.tick();

      const dd = (fixture.nativeElement as HTMLElement).querySelector('dd')!;
      const box = dd.querySelector(':scope > .message-details-html') as HTMLElement;
      expect(box.classList).toContain('overflow-x-auto');
      expect(dd.classList).not.toContain('overflow-x-auto');

      Object.defineProperty(box, 'scrollWidth', { configurable: true, value: 480 });
      Object.defineProperty(box, 'clientWidth', { configurable: true, value: 160 });
      FakeResizeObserver.last!.report();
      TestBed.tick();

      expect(box.getAttribute('tabindex')).toBe('0');
      expect(box.getAttribute('role')).toBe('region');
      expect(box.getAttribute('aria-label')).toBe('Positions');
      expect(dd.hasAttribute('role')).toBe(false);
    } finally {
      vi.unstubAllGlobals();
      FakeResizeObserver.last = undefined;
    }
  });

  it('asks for the message when its title is pressed in the list, and for a reference when that is', () => {
    fixture.componentRef.setInput('msg', {
      ...baseMsg,
      references: [{ messageId: 'NW-099-26', type: 'REPETITION', descs: [] }],
    });
    fixture.detectChanges();
    const spy = vi.fn();
    component.showDetails.subscribe(spy);
    const el = fixture.nativeElement as HTMLElement;

    (el.querySelector('button.row-link') as HTMLButtonElement).click();
    expect(spy).toHaveBeenLastCalledWith('msg-1');

    buttonsLabelled('NW-099-26')[0].click();
    expect(spy).toHaveBeenLastCalledWith('NW-099-26');
  });

  // The dialog shows one message; its title is a heading, not a way to open itself again.
  it('draws the title as text, not as a button, in the details format', () => {
    fixture.componentRef.setInput('msg', baseMsg);
    fixture.componentRef.setInput('format', 'details');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('button.row-link')).toBeNull();
    expect(el.querySelector('.message-title')!.textContent!.trim()).toBe('Test Message');
  });
});

/**
 * A message with no text in the reader's language arrives in one it has: on a site that offers
 * Kalaallisut, many a warning is Danish on a Kalaallisut page. Each piece of that text says which
 * language it is in, so a screen reader reads it in its own language, and the page stays in the
 * reader's. Here the text is English and the page is in `zz`.
 */
describe('MessageDetailCardComponent, for a message in another language than the page', () => {
  const englishOnly: Message = {
    id: 'msg-en',
    mainType: 'NW',
    shortId: 'NW-002-26',
    descs: [{ lang: 'en', title: 'Firing exercises', publication: '<a href="#">NtM 12/2026</a>' }],
    references: [{ messageId: 'NW-001-26', descs: [{ lang: 'en', description: 'repeated' }] }],
    parts: [
      {
        type: 'DETAILS',
        descs: [{ lang: 'en', subject: 'Subject', details: '<p>Keep clear.</p>' }],
      },
    ],
  };

  async function render(msg: Message, page: string, format: 'list' | 'details') {
    TestBed.configureTestingModule({
      imports: [MessageDetailCardComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig({ defaultLanguage: page })],
      schemas: [NO_ERRORS_SCHEMA],
    });
    const fixture = TestBed.createComponent(MessageDetailCardComponent);
    fixture.componentRef.setInput('msg', msg);
    fixture.componentRef.setInput('format', format);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      marked: [...el.querySelectorAll('[lang]')].map((e) => [
        e.getAttribute('lang'),
        e.textContent!.trim(),
      ]),
      flags: [...el.querySelectorAll('img[src*="flags"]')].map((img) => img.getAttribute('alt')),
      /** The spans that stand for a flag the build does not ship: the language's code. */
      codes: [...el.querySelectorAll('span')]
        .filter((span) => span.textContent!.trim() === msg.descs?.[0]?.lang?.toUpperCase())
        .map((span) => [span.textContent!.trim(), span.getAttribute('aria-hidden')]),
    };
  }

  it.each(['list', 'details'] as const)(
    'marks every piece of English text on a page in another language as English (%s)',
    async (format) => {
      const { marked, flags } = await render(englishOnly, 'zz', format);

      expect(document.documentElement.lang).toBe('zz');
      expect(marked).toEqual([
        ['en', 'Firing exercises'],
        ['en', 'repeated'],
        ['en', 'Subject'],
        ['en', 'Keep clear.'],
        ['en', 'NtM 12/2026'],
      ]);
      // The flag beside the title and the subject names the language to a screen reader too,
      // by the name the language configuration gives it, in the words of the page.
      expect(flags).toEqual(['[English flág]', '[English flág]']);
    },
  );

  // The list Show attachments opens names each file by its caption, which the backend may only
  // hold in one language. An attachment drawn above or below the card is its own component's case.
  it.each([
    ['zz', 'en'],
    ['en', null],
  ] as const)(
    'marks an attachment caption that is not in the language of the page (%s page)',
    async (page, lang) => {
      const withAttachment: Message = {
        ...englishOnly,
        attachments: [
          {
            path: '/rest/repo/file/photo.jpg',
            fileName: 'photo.jpg',
            descs: [{ lang: 'en', caption: 'A photo' }],
          },
        ],
      };
      const { fixture } = await render(withAttachment, page, 'details');
      fixture.componentInstance.showAttachments.set(true);
      fixture.detectChanges();

      const caption = [...(fixture.nativeElement as HTMLElement).querySelectorAll('dd span')].find(
        (span) => span.textContent!.includes('A photo'),
      );
      expect(caption).toBeDefined();
      expect(caption!.getAttribute('lang')).toBe(lang);
    },
  );

  // A flag path is never made up from a language code: it would be a broken image.
  it('shows a language the build ships no flag for by its code, with no image', async () => {
    const german: Message = {
      id: 'msg-de',
      mainType: 'NW',
      descs: [{ lang: 'de', title: 'Titel' }],
    };
    const { marked, flags, codes } = await render(german, 'en', 'details');

    expect(marked).toEqual([['de', 'Titel']]);
    expect(flags).toEqual([]);
    // To the eye only: a screen reader has the lang of the title beside it, and would read the
    // bare code as a word.
    expect(codes).toEqual([['DE', 'true']]);
  });

  it('marks nothing and draws no flag where the text is in the language of the page', async () => {
    const { marked, flags } = await render(englishOnly, 'en', 'details');

    expect(document.documentElement.lang).toBe('en');
    expect(marked).toEqual([]);
    expect(flags).toEqual([]);
  });
});

/**
 * The name of a field is shown whole, in every language the site ships. Kalaallisut is
 * polysynthetic — its words are long and a translator cannot shorten them — and the Danish names
 * of two fields are wider than the column they used to be cut to. jsdom lays nothing out, so this
 * pins what makes a whole label possible: no label is cut to an ellipsis, and each may wrap. The
 * widths themselves are measured in a browser by npm run layout:info.
 */
describe('MessageDetailCardComponent, the name of each field', () => {
  const PART_TYPES = ['DETAILS', 'TIME', 'POSITIONS', 'NOTE', 'PROHIBITION', 'SIGNALS'] as const;

  /** Every field the card can name, in the order it names them. */
  const LABEL_KEYS = [
    'FIELD_REFERENCES',
    ...PART_TYPES.map((type) => `PART_TYPE_${type}`),
    'FIELD_ATTACHMENTS',
    'FIELD_CHARTS',
    'FIELD_PUBLICATION',
  ];

  const everyField: Message = {
    id: 'msg-every-field',
    mainType: 'NW',
    shortId: 'NW-003-26',
    descs: [{ lang: 'da', title: 'Skydeøvelser', publication: '<a href="#">EfS 12/2026</a>' }],
    references: [{ messageId: 'NW-001-26' }],
    parts: PART_TYPES.map((type) => ({ type, descs: [{ lang: 'da', details: '<p>Tekst.</p>' }] })),
    attachments: [{ path: '/rest/repo/file/bilag.pdf', fileName: 'bilag.pdf' }],
    charts: [{ chartNumber: '101' }],
  };

  /** Classes that would cut a label short instead of letting it wrap. */
  const CLIPPING = ['truncate', 'overflow-hidden', 'text-ellipsis', 'whitespace-nowrap'];

  it.each(['en', 'zz'])(
    'names every field in full, the longest name in the bundle included (%s)',
    async (lang) => {
      TestBed.configureTestingModule({
        imports: [MessageDetailCardComponent, getTranslocoTestingModule()],
        providers: [provideTestNiordConfig({ defaultLanguage: lang })],
        schemas: [NO_ERRORS_SCHEMA],
      });
      const fixture = TestBed.createComponent(MessageDetailCardComponent);
      fixture.componentRef.setInput('msg', everyField);
      fixture.componentRef.setInput('format', 'details');
      await fixture.whenStable();
      fixture.detectChanges();

      const words = TEST_TRANSLATIONS[lang];
      const expected = LABEL_KEYS.map((key) => words[key]);
      const longest = expected.reduce((a, b) => (b.length > a.length ? b : a));
      const labels = [...(fixture.nativeElement as HTMLElement).querySelectorAll('dt')];

      expect(labels.map((dt) => dt.textContent!.trim())).toEqual(expected);
      expect(labels.map((dt) => dt.textContent!.trim())).toContain(longest);
      for (const dt of labels) {
        for (const clipping of CLIPPING) expect(dt.classList).not.toContain(clipping);
        expect(dt.classList).toContain('break-words');
      }
    },
  );
});

/**
 * A link a message carries looks like a link without a pointer over it — on a phone nothing
 * hovers. What the stylesheet does with each is checked in a browser by npm run layout:info; here,
 * that each link wears what the stylesheet reads.
 */
describe('MessageDetailCardComponent, links', () => {
  it('draws the attachment list as links, and marks the publication reference as one', async () => {
    TestBed.configureTestingModule({
      imports: [MessageDetailCardComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig()],
      schemas: [NO_ERRORS_SCHEMA],
    });
    const fixture = TestBed.createComponent(MessageDetailCardComponent);
    fixture.componentRef.setInput('msg', {
      id: 'msg-links',
      mainType: 'NW',
      descs: [{ lang: 'en', title: 'Links', publication: '<a href="#">NtM 12/2026</a>' }],
      parts: [
        { type: 'DETAILS', descs: [{ lang: 'en', details: '<p><a href="#">the chart</a></p>' }] },
      ],
      attachments: [{ path: '/rest/repo/file/annex.pdf', fileName: 'annex.pdf' }],
    } satisfies Message);
    fixture.componentInstance.showAttachments.set(true);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const attachment = el.querySelector('a[href="/rest/repo/file/annex.pdf"]')!;
    expect(attachment.classList).toContain('link-button');
    expect(attachment.classList).not.toContain('is-subtle');

    const publication = [...el.querySelectorAll('dd')].find((dd) =>
      dd.textContent!.includes('NtM 12/2026'),
    )!;
    expect([...publication.classList]).toEqual(
      expect.arrayContaining(['message-details-html', 'message-publication']),
    );

    const part = [...el.querySelectorAll('.message-details-html')].find((box) =>
      box.textContent!.includes('the chart'),
    )!;
    expect(part.classList).not.toContain('message-publication');
  });
});
