import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { SafetynetView } from './safetynet-view';
import type { SafetynetPromulgation } from './safetynet-promulgation.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(promulgation: SafetynetPromulgation) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(SafetynetView);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.detectChanges();
  return fixture;
}

const base = {
  type: { typeId: 'safetynet', serviceId: 'safetynet', name: 'SafetyNET' },
  promulgate: true,
} as const;

describe('SafetynetView', () => {
  it('renders priority + areaName', () => {
    const fixture = setup({ ...base, priority: 'URGENCY', areaName: 'METAREA III' });
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('URGENCY');
    expect(text).toContain('METAREA III');
  });

  it('renders the text body via plainTextToHtml in a monospace container', () => {
    const fixture = setup({ ...base, text: 'broadcast' });
    const mono: HTMLElement | null = fixture.nativeElement.querySelector('.font-mono');
    expect(mono).not.toBeNull();
    expect(mono!.textContent).toContain('broadcast');
  });

  it('omits the body container when text is absent', () => {
    const fixture = setup({ ...base, areaName: 'METAREA III' });
    expect(fixture.nativeElement.querySelector('.font-mono')).toBeNull();
  });
});
