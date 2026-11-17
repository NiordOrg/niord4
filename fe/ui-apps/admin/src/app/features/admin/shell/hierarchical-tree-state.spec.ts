import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { ModelLanguageSelection } from '../services/model-language-selection';
import { hierarchicalTreeState } from './hierarchical-tree-state';

describe('hierarchical admin model language', () => {
  it('requests and displays KL tree names independently of the UI language', () => {
    const ui = signal('en');
    TestBed.configureTestingModule({
      providers: [
        ModelLanguageSelection,
        {
          provide: LanguageService,
          useValue: {
            selectedLanguage: ui,
            defaultLanguage: 'da',
            modelLanguages: [{ value: 'en' }, { value: 'da' }, { value: 'kl' }],
          },
        },
      ],
    });
    const load = vi.fn((lang: string) =>
      of([
        { id: 1, descs: [{ lang, name: lang === 'kl' ? 'Anchorland (kl)' : 'Anchorland (en)' }] },
      ]),
    );
    const tree = TestBed.runInInjectionContext(() => hierarchicalTreeState(load));
    TestBed.tick();
    TestBed.inject(ModelLanguageSelection).select('kl');
    TestBed.tick();
    expect(load.mock.calls.map(([lang]) => lang)).toEqual(['en', 'kl']);
    expect(tree.entities()[0].descs[0].name).toBe('Anchorland (kl)');
    expect(ui()).toBe('en');
  });
});
