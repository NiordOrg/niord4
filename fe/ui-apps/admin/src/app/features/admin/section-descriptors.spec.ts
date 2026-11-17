import { describe, expect, it } from 'vitest';

import { ADMIN_AREA, SYSADMIN_AREA } from './section-descriptors';

describe('admin section descriptors', () => {
  it('the admin area lists its seven sections in rail order', () => {
    expect(ADMIN_AREA.sections.map((s) => s.path)).toEqual([
      'charts',
      'areas',
      'publications',
      'publication-categories',
      'sources',
      'users',
      'mailing-lists',
    ]);
  });

  /**
   * The categories sit directly under the publications they group.
   *
   * They are read as a pair — a series is filed under a category, and the
   * category decides whether that series reaches the public site — so anything
   * between them in the rail makes the reader hunt for the second half.
   */
  it('places the publication categories immediately after publications', () => {
    const paths = ADMIN_AREA.sections.map((s) => s.path);
    expect(paths.indexOf('publication-categories')).toBe(paths.indexOf('publications') + 1);
  });

  it('the sysadmin area lists its fourteen sections in rail order (no standalone integration section)', () => {
    expect(SYSADMIN_AREA.sections.map((s) => s.path)).toEqual([
      'series',
      'domains',
      'promulgation',
      'schedules',
      'dictionaries',
      'param-types',
      'script-resources',
      'categories',
      'reports',
      'mails',
      'niord-integration',
      'secom',
      'settings',
      'batch',
    ]);
    expect(SYSADMIN_AREA.sections.some((s) => s.path === 'integration')).toBe(false);
  });

  it('section paths are unique within each area', () => {
    for (const area of [ADMIN_AREA, SYSADMIN_AREA]) {
      const paths = area.sections.map((s) => s.path);
      expect(new Set(paths).size).toBe(paths.length);
    }
  });

  it('label and description keys derive from the section path', () => {
    for (const area of [ADMIN_AREA, SYSADMIN_AREA]) {
      for (const section of area.sections) {
        expect(section.labelKey).toBe(`admin.sections.${section.path}.label`);
        expect(section.descriptionKey).toBe(`admin.sections.${section.path}.description`);
      }
    }
  });

  it('every section but SECOM carries a help anchor (including the niord-integration manual section)', () => {
    // SECOM and S-124 are not in the user guides yet, so the section shows no help link.
    const withoutHelp = [...ADMIN_AREA.sections, ...SYSADMIN_AREA.sections]
      .filter((s) => !s.helpSection)
      .map((s) => s.path);
    expect(withoutHelp).toEqual(['secom']);
    const niordIntegration = SYSADMIN_AREA.sections.find((s) => s.path === 'niord-integration');
    expect(niordIntegration?.helpSection).toBe('integration');
  });

  it('overview help keys are non-empty (an empty string would render no help link)', () => {
    expect(ADMIN_AREA.overviewHelpSection).toBeTruthy();
    expect(SYSADMIN_AREA.overviewHelpSection).toBeTruthy();
  });

  it('the two optional-subsystem sections carry their runtime visibility gate', () => {
    const niordIntegration = SYSADMIN_AREA.sections.find((s) => s.path === 'niord-integration');
    expect(niordIntegration?.settingGate).toBe('adminIntegrationPageEnabled');
    const secom = SYSADMIN_AREA.sections.find((s) => s.path === 'secom');
    expect(secom?.settingGate).toBe('secomEnabled');
  });

  it('niord-integration and secom are the only gated sections; all others have no settingGate', () => {
    const gated = [...ADMIN_AREA.sections, ...SYSADMIN_AREA.sections]
      .filter((s) => s.settingGate)
      .map((s) => s.path);
    expect(gated).toEqual(['niord-integration', 'secom']);
  });
});
