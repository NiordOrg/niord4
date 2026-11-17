import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { MessageAreaHeading } from './message-area-heading';
import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { MessageArea } from '../../models/message.model';
import { MessageAreaSortLauncher } from '../../services/message-area-sort.launcher';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function createComponent(area: MessageArea) {
  const fixture = TestBed.createComponent(MessageAreaHeading);
  fixture.componentRef.setInput('areaHeading', area);
  fixture.detectChanges();
  return fixture;
}

describe('MessageAreaHeading', () => {
  function configure(options?: { isEditor?: boolean; domainUserRole?: boolean }) {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        {
          provide: AuthFacade,
          useValue: {
            hasRole: () => () => options?.isEditor ?? false,
            hasDomainUserRole: () => options?.domainUserRole ?? true,
          },
        },
        { provide: MessageAreaSortLauncher, useValue: { sortAreaHeading: () => undefined } },
      ],
    });
  }

  it('renders the area path via app-message-areas when given a real area', () => {
    configure();
    const fixture = createComponent({
      id: 42,
      descs: [{ lang: 'en', name: 'Northern Sea' }],
    });

    const html = fixture.nativeElement.innerHTML;
    expect(html).toContain('app-message-areas');
    expect(html).not.toContain('msg.area.general');
  });

  it('renders the "no area" label when given the sentinel area', () => {
    configure();
    const fixture = createComponent({ id: -999999 });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('msg.area.general');
    expect(fixture.nativeElement.innerHTML).not.toContain('<app-message-areas');
  });

  it('does not render structural table/row elements itself', () => {
    configure();
    const fixture = createComponent({ id: 1, descs: [{ lang: 'en', name: 'A' }] });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('tr')).toBeNull();
    expect(root.querySelector('td')).toBeNull();
    expect(root.querySelector('th')).toBeNull();
  });

  it('shows the sort link for an editor on a real area', () => {
    configure({ isEditor: true });
    const fixture = createComponent({ id: 7, descs: [{ lang: 'en', name: 'A' }] });
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
  });

  it('hides the sort link for a non-editor', () => {
    configure({ isEditor: false });
    const fixture = createComponent({ id: 7, descs: [{ lang: 'en', name: 'A' }] });
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('editor without the user role in the domain: no sort button, whose temporary tag would be refused', () => {
    configure({ isEditor: true, domainUserRole: false });
    const fixture = createComponent({ id: 7, descs: [{ lang: 'en', name: 'A' }] });
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('hides the sort link on the no-area sentinel even for an editor', () => {
    configure({ isEditor: true });
    const fixture = createComponent({ id: -999999 });
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
