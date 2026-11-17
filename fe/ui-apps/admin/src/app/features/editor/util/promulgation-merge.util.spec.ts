import { describe, expect, it } from 'vitest';

import type { BasePromulgation } from '../../messages/models/message.model';
import { mergeGeneratedPromulgation } from './promulgation-merge.util';

// Trimmed real dev-backend payloads (2026-07-05):
//  - `existing` = the NAVTEX entry off a real editable message (promulgate:false, has `preamble`,
//    no `text`); its transmitters are all off.
//  - `generated` = POST /rest/promulgations/generate/navtex response (promulgate:true, has `text`,
//    Baltico transmitter flipped on, NO `preamble`).
function navtexExisting(): BasePromulgation {
  return {
    '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
    type: {
      typeId: 'navtex',
      serviceId: 'navtex',
      name: 'NAVTEX/S-53',
      priority: 11,
      requirement: 'OPTIONAL',
    },
    promulgate: false,
    priority: 'ROUTINE',
    transmitters: { Rogaland: false, Baltico: false, Aasiaat: false },
    preamble: '${publish-date}\nDANISH NAV WARN ${number-year-id}',
  } as unknown as BasePromulgation;
}

function navtexGenerated(): BasePromulgation {
  return {
    '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
    type: {
      typeId: 'navtex',
      serviceId: 'navtex',
      name: 'NAVTEX/S-53',
      priority: 11,
      requirement: 'OPTIONAL',
    },
    promulgate: true,
    priority: 'ROUTINE',
    transmitters: { Rogaland: false, Baltico: true, Aasiaat: false },
    text: 'ANCHORLAND. NORTH SOUND.\nA TORPEDO HAS BEEN REPORTED 55-32.8N',
  } as unknown as BasePromulgation;
}

/** Index-access view of a merged entry (fields beyond the BasePromulgation contract). */
function rec(promulgation: BasePromulgation): Record<string, unknown> {
  return promulgation as unknown as Record<string, unknown>;
}

describe('mergeGeneratedPromulgation', () => {
  it('overwrites present response fields and adds fields absent on the existing entry', () => {
    const merged = rec(mergeGeneratedPromulgation(navtexExisting(), navtexGenerated()));
    expect(merged['promulgate']).toBe(true);
    expect(merged['text']).toBe('ANCHORLAND. NORTH SOUND.\nA TORPEDO HAS BEEN REPORTED 55-32.8N');
  });

  it('keeps fields ABSENT from the response (preamble survives)', () => {
    const merged = rec(mergeGeneratedPromulgation(navtexExisting(), navtexGenerated()));
    expect(merged['preamble']).toBe('${publish-date}\nDANISH NAV WARN ${number-year-id}');
  });

  it('never lets a generate response turn OFF a channel the user enabled (empty-body case)', () => {
    // Real flow: the user checks the channel (promulgate:true) then clicks Generate; a brand-new
    // message has no body, so the backend returns promulgate:false. That must NOT switch the
    // channel back off — which would grey out the editor and make Generate-all skip it.
    const existing = rec(navtexExisting());
    existing['promulgate'] = true;
    const generated = rec(navtexGenerated());
    generated['promulgate'] = false;
    const merged = rec(
      mergeGeneratedPromulgation(
        existing as unknown as BasePromulgation,
        generated as unknown as BasePromulgation,
      ),
    );
    expect(merged['promulgate']).toBe(true);
    // The generated text still lands, so once the body is filled the regenerate populates it.
    expect(merged['text']).toBe('ANCHORLAND. NORTH SOUND.\nA TORPEDO HAS BEEN REPORTED 55-32.8N');
  });

  it('preserves the @class discriminator from the existing entry', () => {
    const merged = mergeGeneratedPromulgation(navtexExisting(), navtexGenerated());
    expect(merged['@class']).toBe('org.niord.core.promulgation.vo.NavtexMessagePromulgationVo');
  });

  it('preserves @class even when the response omits it', () => {
    const generated = rec(navtexGenerated());
    delete generated['@class'];
    const merged = mergeGeneratedPromulgation(
      navtexExisting(),
      generated as unknown as BasePromulgation,
    );
    expect(merged['@class']).toBe('org.niord.core.promulgation.vo.NavtexMessagePromulgationVo');
  });

  it('forces type.typeId to survive from the existing entry even if the response re-keys it', () => {
    const generated = navtexGenerated();
    (generated.type as { typeId: string }).typeId = 'WRONG';
    const merged = mergeGeneratedPromulgation(navtexExisting(), generated);
    expect(merged.type.typeId).toBe('navtex');
  });

  it('deep-merges nested plain objects (transmitters map) rather than dropping keys', () => {
    const merged = rec(mergeGeneratedPromulgation(navtexExisting(), navtexGenerated()));
    expect(merged['transmitters']).toEqual({ Rogaland: false, Baltico: true, Aasiaat: false });
  });

  it('overwrites arrays WHOLESALE (never element-merged)', () => {
    const existing = {
      '@class': 'org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo',
      type: {
        typeId: 'safetynet',
        serviceId: 'safetynet',
        name: 'SafetyNET',
        requirement: 'OPTIONAL',
      },
      promulgate: true,
      areas: [{ name: 'AREA-A' }, { name: 'AREA-B' }],
    } as unknown as BasePromulgation;
    const generated = {
      '@class': 'org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo',
      type: {
        typeId: 'safetynet',
        serviceId: 'safetynet',
        name: 'SafetyNET',
        requirement: 'OPTIONAL',
      },
      promulgate: true,
      areas: [{ name: 'AREA-C' }],
    } as unknown as BasePromulgation;
    const merged = rec(mergeGeneratedPromulgation(existing, generated));
    expect(merged['areas']).toEqual([{ name: 'AREA-C' }]);
  });

  it('keeps a field the response omits when the response carries none of it (audio minimal)', () => {
    // POST /generate/audio returns only { @class, type, promulgate } — an existing `text` survives.
    const existing = {
      '@class': 'org.niord.core.promulgation.vo.AudioMessagePromulgationVo',
      type: { typeId: 'audio', serviceId: 'audio', name: 'Audio', requirement: 'MANDATORY' },
      promulgate: false,
      text: 'Existing audio script',
    } as unknown as BasePromulgation;
    const generated = {
      '@class': 'org.niord.core.promulgation.vo.AudioMessagePromulgationVo',
      type: { typeId: 'audio', serviceId: 'audio', name: 'Audio', requirement: 'MANDATORY' },
      promulgate: true,
    } as unknown as BasePromulgation;
    const merged = rec(mergeGeneratedPromulgation(existing, generated));
    expect(merged['text']).toBe('Existing audio script');
    expect(merged['promulgate']).toBe(true);
  });

  it('does not mutate the existing entry (immutable)', () => {
    const existing = navtexExisting();
    mergeGeneratedPromulgation(existing, navtexGenerated());
    const view = rec(existing);
    expect(view['text']).toBeUndefined();
    expect((view['transmitters'] as Record<string, boolean>)['Baltico']).toBe(false);
  });
});
