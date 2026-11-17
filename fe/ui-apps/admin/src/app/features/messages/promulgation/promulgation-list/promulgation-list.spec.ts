import { TestBed } from '@angular/core/testing';
import { Component, input } from '@angular/core';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

import type { BasePromulgation, Message } from '../../models/message.model';
import { provideCorePromulgationRenderers } from '../core-renderers.providers';
import { PROMULGATION_RENDERERS, type PromulgationRenderer } from '../promulgation-renderer.token';
import { PromulgationList } from './promulgation-list';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

@Component({
  selector: 'app-test-renderer',
  template: `<span class="test-renderer">{{ promulgation().type.serviceId }}</span>`,
})
class TestRenderer {
  readonly promulgation = input.required<BasePromulgation>();
}

function makeMessage(promulgations: BasePromulgation[]): Message {
  return { id: 'NM-1-26', promulgations } as Message;
}

function p(
  serviceId: string,
  promulgate = true,
  overrides: Partial<BasePromulgation> = {},
): BasePromulgation {
  return {
    type: { typeId: serviceId, serviceId, name: serviceId.toUpperCase() },
    promulgate,
    ...overrides,
  } as BasePromulgation;
}

function configure(extra: PromulgationRenderer[] = []) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideCorePromulgationRenderers(),
      ...extra.map((r) => ({
        provide: PROMULGATION_RENDERERS,
        useValue: r,
        multi: true as const,
      })),
    ],
  });
}

function createWith(msg: Message) {
  const fixture = TestBed.createComponent(PromulgationList);
  fixture.componentRef.setInput('msg', msg);
  fixture.detectChanges();
  return fixture;
}

describe('PromulgationList — filtering and visibility', () => {
  beforeEach(() => configure());

  it('hasVisible is false when there are no promulgations', () => {
    const fixture = createWith(makeMessage([]));
    expect(fixture.componentInstance.hasVisible()).toBe(false);
    expect(fixture.nativeElement.textContent.trim()).toBe('');
  });

  it('filters out entries with promulgate === false', () => {
    const fixture = createWith(makeMessage([p('navtex', false), p('twitter', true)]));
    expect(fixture.componentInstance.visible().length).toBe(1);
    expect(fixture.componentInstance.hasVisible()).toBe(true);
  });

  it('renders one block per visible entry, dispatching by serviceId', () => {
    const fixture = createWith(
      makeMessage([p('navtex'), p('twitter'), p('safetynet'), p('navtex', false)]),
    );
    expect(fixture.componentInstance.visible().length).toBe(3);
    // Heading text from each renderer's type.name surfaces in the dispatcher container
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('NAVTEX');
    expect(text).toContain('TWITTER');
    expect(text).toContain('SAFETYNET');
  });
});

describe('PromulgationList — showAll', () => {
  beforeEach(() => configure());

  function createWithShowAll(msg: Message) {
    const fixture = TestBed.createComponent(PromulgationList);
    fixture.componentRef.setInput('msg', msg);
    fixture.componentRef.setInput('showAll', true);
    fixture.detectChanges();
    return fixture;
  }

  it('renders ALL entries (incl. unpromulgated) when showAll is true', () => {
    const fixture = createWithShowAll(makeMessage([p('navtex', false), p('twitter', true)]));
    expect(fixture.componentInstance.visible().length).toBe(2);
    // The unpromulgated entry is greyed via opacity.
    expect(fixture.nativeElement.querySelector('.opacity-50')).not.toBeNull();
  });

  it('marks a MANDATORY unpromulgated entry with the warning glyph + aria label', () => {
    const mandatory = {
      type: {
        typeId: 'navtex',
        serviceId: 'navtex',
        name: 'NAVTEX',
        requirement: 'MANDATORY' as const,
      },
      promulgate: false,
    } as BasePromulgation;
    const fixture = createWithShowAll(makeMessage([mandatory]));
    const glyph = fixture.nativeElement.querySelector('[name="lucideTriangleAlert"]');
    expect(glyph).not.toBeNull();
    expect(glyph.getAttribute('aria-label')).toContain('editor.promulgation.mandatory');
  });

  it('does not mark a promulgated MANDATORY entry as unset', () => {
    const mandatoryOn = {
      type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX', requirement: 'MANDATORY' },
      promulgate: true,
    } as BasePromulgation;
    const fixture = createWithShowAll(makeMessage([mandatoryOn]));
    expect(fixture.nativeElement.querySelector('[name="lucideTriangleAlert"]')).toBeNull();
  });
});

describe('PromulgationList — unknown serviceId', () => {
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    configure();
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });
  afterEach(() => warnSpy.mockRestore());

  it('renders nothing for an unknown serviceId and warns once in dev mode', () => {
    const fixture = createWith(makeMessage([p('unknown-type'), p('unknown-type')]));
    // hasVisible is true (the entry passes the promulgate filter), but the inner block is omitted
    expect(fixture.componentInstance.hasVisible()).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('.promulgation-view-header').length).toBe(0);
    // isDevMode() is true under tests; warning fires once per unique unknown id
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toContain('unknown-type');
  });
});

describe('PromulgationList — registry composition', () => {
  it('core providers contribute exactly 4 entries', () => {
    TestBed.configureTestingModule({ providers: [provideCorePromulgationRenderers()] });
    const renderers = TestBed.inject(PROMULGATION_RENDERERS);
    expect(renderers.length).toBe(4);
    expect(renderers.map((r) => r.serviceId).sort()).toEqual([
      'audio',
      'navtex',
      'safetynet',
      'twitter',
    ]);
  });

  it('core + a synthetic third-party renderer compose to 5 entries', () => {
    TestBed.configureTestingModule({
      providers: [
        provideCorePromulgationRenderers(),
        {
          provide: PROMULGATION_RENDERERS,
          useValue: { serviceId: 'sms', component: TestRenderer },
          multi: true,
        },
      ],
    });
    const renderers = TestBed.inject(PROMULGATION_RENDERERS);
    expect(renderers.length).toBe(5);
    expect(renderers.map((r) => r.serviceId)).toContain('sms');
  });

  it("a later registration for a base product serviceId replaces the base product's renderer", () => {
    configure([{ serviceId: 'audio', component: TestRenderer }]);
    const fixture = createWith(makeMessage([p('audio')]));
    expect(fixture.nativeElement.querySelector('.test-renderer')?.textContent).toContain('audio');
  });
});
