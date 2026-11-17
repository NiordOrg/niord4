import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TranslocoService } from '@jsverse/transloco';

import { MessageSource } from './message-source';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageDateFormatterService } from '../../services/message-date-formatter.service';
import { Message } from '../../models/message.model';

const publishedMessage: Message = {
  id: 'uuid-1',
  shortId: 'NW-001-25',
  mainType: 'NW',
  status: 'PUBLISHED',
  publishDateFrom: '1723196245000',
  descs: [{ lang: 'en', title: 'Test' }],
};

function setup(opts: {
  readonly source?: string;
  readonly date?: string;
  readonly translate: (key: string, params?: Record<string, unknown>) => string;
  readonly message?: Message;
}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: MessageDescriptionService, useValue: { getSource: () => opts.source ?? '' } },
      {
        provide: MessageDateFormatterService,
        useValue: { formatDateInterval: () => opts.date ?? '' },
      },
      { provide: TranslocoService, useValue: { translate: opts.translate } },
    ],
  });
  const fixture = TestBed.createComponent(MessageSource);
  fixture.componentRef.setInput('message', opts.message ?? publishedMessage);
  return fixture.componentInstance;
}

describe('MessageSource', () => {
  it('appends the publish date when the label override omits the {{date}} token', () => {
    // The backend `message` Dictionary overrides msg.field.published with a plain
    // label ("Published"), so translate ignores the date param — the component must
    // append the date itself, rendering "<label> <date>".
    const c = setup({ source: 'ACME', date: '9 Aug 2024', translate: () => 'Published' });
    expect(c.content()).toBe('ACME. Published 9 Aug 2024');
  });

  it('does not duplicate the date when the label already interpolates {{date}}', () => {
    // The compiled bundle value "Published {{date}}" substitutes the date itself.
    const c = setup({
      source: 'ACME',
      date: '9 Aug 2024',
      translate: (_key, params) => `Published ${params?.['date']}`,
    });
    expect(c.content()).toBe('ACME. Published 9 Aug 2024');
  });

  it('omits the published label when the message has no publish date', () => {
    const c = setup({ source: 'ACME', date: '', translate: () => 'Published' });
    expect(c.content()).toBe('ACME.');
  });

  it('omits the published label for a non-published message', () => {
    const c = setup({
      source: 'ACME',
      date: '9 Aug 2024',
      translate: () => 'Published',
      message: { ...publishedMessage, status: 'DRAFT' },
    });
    expect(c.content()).toBe('ACME.');
  });
});
