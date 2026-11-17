import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { MessagePublication } from './message-publication';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Message } from '../../models/message.model';

/**
 * A publication reference reads as the citation it is part of: the shared message-HTML
 * stylesheet draws every other link in a message as a link, and leaves the anchors under
 * `.message-publication` in the colour of the text around them. That class is what this
 * component contributes.
 */
describe('MessagePublication', () => {
  const message: Message = {
    id: 'uuid-1',
    mainType: 'NM',
    status: 'PUBLISHED',
    descs: [
      {
        lang: 'en',
        publication: '<a href="https://example.invalid/list.pdf">List of lights, 12.</a>',
        internalPublication: '<a href="https://example.invalid/case">Case 2026-17.</a>',
      },
    ],
  };

  function render(showInternal: boolean): HTMLElement {
    TestBed.configureTestingModule({
      imports: [MessagePublication],
      providers: [
        {
          provide: LanguageService,
          useValue: { selectedLanguage: () => 'en', defaultLanguage: 'en' },
        },
      ],
    });
    const fixture = TestBed.createComponent(MessagePublication);
    fixture.componentRef.setInput('message', message);
    fixture.componentRef.setInput('showInternal', showInternal);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('marks the publication as one, for the stylesheet to keep its links plain', () => {
    const boxes = [...render(false).querySelectorAll('.message-details-html')];
    expect(boxes).toHaveLength(1);
    expect(boxes[0].classList).toContain('message-publication');
    expect(boxes[0].querySelector('a')?.textContent).toBe('List of lights, 12.');
  });

  it('marks the internal publication the same way', () => {
    const boxes = [...render(true).querySelectorAll('.message-details-html')];
    expect(boxes).toHaveLength(2);
    for (const box of boxes) expect(box.classList).toContain('message-publication');
  });
});
