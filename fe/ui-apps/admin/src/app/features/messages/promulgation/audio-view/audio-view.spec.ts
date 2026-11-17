import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { AudioView } from './audio-view';
import type { AudioPromulgation } from './audio-promulgation.model';

function setup(promulgation: AudioPromulgation) {
  const fixture = TestBed.createComponent(AudioView);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.detectChanges();
  return fixture;
}

const base = {
  type: { typeId: 'audio', serviceId: 'audio', name: 'Audio' },
  promulgate: true,
} as const;

describe('AudioView', () => {
  it('renders the text body via plainTextToHtml', () => {
    const fixture = setup({ ...base, text: 'Broadcast content' });
    const mono: HTMLElement | null = fixture.nativeElement.querySelector('.font-mono');
    expect(mono).not.toBeNull();
    expect(mono!.textContent).toContain('Broadcast');
  });

  it('omits the body container when text is absent', () => {
    const fixture = setup({ ...base });
    expect(fixture.nativeElement.querySelector('.font-mono')).toBeNull();
  });
});
