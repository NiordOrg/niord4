import { describe, expect, it } from 'vitest';
import { projectDesc } from './project-desc';

describe('projectDesc', () => {
  it('uses preferred, default, then first available text without mutating or reusing a stored row', () => {
    const descs = [
      { lang: 'en', name: 'English', description: 'First available', id: 1 },
      { lang: 'da', name: 'Dansk', description: '', id: 2 },
      { lang: 'kl', name: '   ', description: '', id: 3 },
    ];
    const before = structuredClone(descs);
    const result = projectDesc(descs, 'kl', 'da');
    expect(result).toEqual({ lang: 'kl', name: 'Dansk', description: 'First available', id: 3 });
    expect(descs).not.toContain(result);
    expect(descs).toEqual(before);
    expect(projectDesc(descs, 'en', 'da')?.name).toBe('English');
    expect(projectDesc(descs, 'fr', 'da')?.name).toBe('Dansk');
  });

  it('returns undefined when no description exists', () => {
    expect(projectDesc([], 'kl', 'da')).toBeUndefined();
    expect(projectDesc(undefined, 'kl', 'da')).toBeUndefined();
  });
});
