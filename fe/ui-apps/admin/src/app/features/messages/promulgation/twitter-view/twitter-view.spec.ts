import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { TwitterView } from './twitter-view';
import type { TwitterPromulgation } from './twitter-promulgation.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(promulgation: TwitterPromulgation) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(TwitterView);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.detectChanges();
  return fixture;
}

const base = {
  type: { typeId: 'twitter', serviceId: 'twitter', name: 'Twitter' },
  promulgate: true,
} as const;

describe('TwitterView', () => {
  it('renders the tweet body', () => {
    const fixture = setup({ ...base, tweet: 'Hello fjord' });
    expect(fixture.nativeElement.textContent).toContain('Hello');
    expect(fixture.nativeElement.textContent).toContain('fjord');
  });

  it('renders nothing visible when tweet is absent', () => {
    const fixture = setup({ ...base });
    expect(fixture.nativeElement.querySelector('.font-mono')).toBeNull();
  });
});
