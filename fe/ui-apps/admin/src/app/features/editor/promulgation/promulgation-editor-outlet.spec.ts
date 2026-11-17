import { TestBed } from '@angular/core/testing';
import { Component, input, signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { BasePromulgation, MessageStatus } from '../../messages/models/message.model';
import { provideCorePromulgationEditors } from './core-editors.providers';
import { PromulgationEditorOutlet } from './promulgation-editor-outlet';
import {
  PROMULGATION_EDITORS,
  type PromulgationEditor,
  type PromulgationEditorContext,
} from './promulgation-editor.token';

@Component({
  selector: 'app-test-editor',
  template: `<span class="test-editor">{{ promulgation().type.serviceId }}</span>`,
})
class TestEditor {
  readonly promulgation = input.required<BasePromulgation>();
  readonly context = input.required<PromulgationEditorContext>();
}

function context(): PromulgationEditorContext {
  return {
    patch: vi.fn(),
    generate: vi.fn(),
    reformat: vi.fn(),
    busy: () => false,
    messageStatus: signal<MessageStatus | undefined>('DRAFT'),
  };
}

function promulgation(serviceId: string): BasePromulgation {
  return {
    type: { typeId: serviceId, serviceId, name: serviceId.toUpperCase() },
    promulgate: true,
  } as BasePromulgation;
}

function configure(extra: PromulgationEditor[] = []) {
  TestBed.configureTestingModule({
    providers: [
      ...extra.map((e) => ({ provide: PROMULGATION_EDITORS, useValue: e, multi: true as const })),
    ],
  });
}

function createWith(p: BasePromulgation) {
  const fixture = TestBed.createComponent(PromulgationEditorOutlet);
  fixture.componentRef.setInput('promulgation', p);
  fixture.componentRef.setInput('context', context());
  fixture.detectChanges();
  return fixture;
}

describe('PromulgationEditorOutlet', () => {
  it('dispatches to the editor registered for the entry serviceId', () => {
    configure([{ serviceId: 'navtex', component: TestEditor }]);
    const fixture = createWith(promulgation('navtex'));
    expect(fixture.nativeElement.querySelector('.test-editor')?.textContent).toContain('navtex');
  });

  describe('unknown serviceId', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;
    beforeEach(() => {
      configure([{ serviceId: 'navtex', component: TestEditor }]);
      warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });
    afterEach(() => warnSpy.mockRestore());

    it('renders nothing and warns once in dev mode', () => {
      const fixture = createWith(promulgation('unknown'));
      expect(fixture.nativeElement.querySelector('.test-editor')).toBeNull();
      expect(warnSpy).toHaveBeenCalledTimes(1);
      expect(warnSpy.mock.calls[0][0]).toContain('unknown');
    });
  });

  it('core providers register navtex/safetynet/twitter/audio editors', () => {
    TestBed.configureTestingModule({ providers: [provideCorePromulgationEditors()] });
    const editors = TestBed.inject(PROMULGATION_EDITORS);
    expect(editors.map((e) => e.serviceId).sort()).toEqual([
      'audio',
      'navtex',
      'safetynet',
      'twitter',
    ]);
  });
});
