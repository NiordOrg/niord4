import { TestBed } from '@angular/core/testing';
import { COMPONENT_SLOTS, PROMULGATION_EDITORS, PROMULGATION_RENDERERS } from '@niord/admin/api';
import { describe, expect, it } from 'vitest';

import { BulletinEdit } from './bulletin/bulletin-edit';
import { BulletinView } from './bulletin/bulletin-view';
import { provideCountry } from './country.providers';
import { WelcomeNote } from './welcome-note/welcome-note';

describe('provideCountry (example country)', () => {
  it('renders the harbour bulletin with its own component', () => {
    TestBed.configureTestingModule({ providers: [provideCountry()] });

    const renderers = TestBed.inject(PROMULGATION_RENDERERS);
    expect(renderers).toContainEqual({ serviceId: 'xx-bulletin', component: BulletinView });
  });

  it('edits the harbour bulletin with its own component', () => {
    TestBed.configureTestingModule({ providers: [provideCountry()] });

    const editors = TestBed.inject(PROMULGATION_EDITORS);
    expect(editors).toContainEqual({ serviceId: 'xx-bulletin', component: BulletinEdit });
  });

  it('adds the welcome note to the home.welcome slot, loaded on demand', async () => {
    TestBed.configureTestingModule({ providers: [provideCountry()] });

    const slots = TestBed.inject(COMPONENT_SLOTS);
    expect(slots.map((registration) => registration.slot)).toEqual(['home.welcome']);
    expect(await slots[0].load()).toBe(WelcomeNote);
  });
});
