import { TestBed } from '@angular/core/testing';
import { PREFERENCE_KEYS, PREFERENCE_PREFIX, PreferencesService } from './preferences.service';

describe('PreferencesService', () => {
  let preferences: PreferencesService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    preferences = TestBed.inject(PreferencesService);
  });

  it('is empty when nothing has been remembered', () => {
    expect(preferences.language()).toBeUndefined();
    expect(preferences.rootAreaId()).toBeUndefined();
    expect(preferences.mainTypes()).toEqual({ NW: true, NM: true });
  });

  it('remembers what it is told, under the prefix', () => {
    preferences.setLanguage('da');
    preferences.setRootAreaId('453');
    preferences.setMainTypes({ NW: false, NM: true });

    expect(localStorage.getItem('niord-info.language')).toBe('da');
    expect(localStorage.getItem('niord-info.rootAreaId')).toBe('453');
    expect(localStorage.getItem('niord-info.NW')).toBe('false');
    expect(preferences.language()).toBe('da');
    expect(preferences.mainTypes()).toEqual({ NW: false, NM: true });
  });

  // A returning reader set these on the previous deployment of this site.
  describe('the names the previous deployment wrote', () => {
    it('carries a value over and takes the old entry away', () => {
      localStorage.setItem('language', 'kl');
      expect(preferences.language()).toBe('kl');
      expect(localStorage.getItem('niord-info.language')).toBe('kl');
      expect(localStorage.getItem('language')).toBeNull();
    });

    it('keeps the filter a reader turned off', () => {
      localStorage.setItem('NW', 'false');
      localStorage.setItem('NM', 'true');
      expect(preferences.mainTypes()).toEqual({ NW: false, NM: true });
      expect(localStorage.getItem('NW')).toBeNull();
      expect(localStorage.getItem('niord-info.NW')).toBe('false');
    });

    it('keeps the root area a reader chose', () => {
      localStorage.setItem('rootAreaId', '454');
      expect(preferences.rootAreaId()).toBe('454');
      expect(localStorage.getItem('niord-info.rootAreaId')).toBe('454');
    });

    it('carries over once, and a later write is the one that counts', () => {
      localStorage.setItem('language', 'kl');
      expect(preferences.language()).toBe('kl');
      localStorage.setItem('language', 'da');
      expect(preferences.language()).toBe('kl');
    });

    it('prefers what this deployment wrote over what the old one left', () => {
      localStorage.setItem('language', 'kl');
      localStorage.setItem('niord-info.language', 'da');
      expect(preferences.language()).toBe('da');
      expect(localStorage.getItem('language')).toBe('kl');
    });
  });

  // A browser with storage switched off throws on every access.
  describe('storage that refuses', () => {
    let getItem: typeof localStorage.getItem;
    let setItem: typeof localStorage.setItem;

    beforeEach(() => {
      getItem = localStorage.getItem;
      setItem = localStorage.setItem;
      localStorage.getItem = () => {
        throw new Error('denied');
      };
      localStorage.setItem = () => {
        throw new Error('denied');
      };
    });

    afterEach(() => {
      localStorage.getItem = getItem;
      localStorage.setItem = setItem;
    });

    it('reads as no preference at all', () => {
      expect(preferences.language()).toBeUndefined();
      expect(preferences.rootAreaId()).toBeUndefined();
      expect(preferences.mainTypes()).toEqual({ NW: true, NM: true });
    });

    it('writes without failing the caller', () => {
      expect(() => preferences.setLanguage('da')).not.toThrow();
      expect(() => preferences.setRootAreaId('453')).not.toThrow();
      expect(() => preferences.setMainTypes({ NW: false, NM: false })).not.toThrow();
    });
  });

  // The kit holds every country's cookie statement to preference-keys.json, so what the setters
  // write and the list may never drift apart.
  it('stores exactly the names preference-keys.json lists, each prefixed', () => {
    localStorage.clear();
    preferences.setLanguage('da');
    preferences.setRootAreaId('453');
    preferences.setMainTypes({ NW: true, NM: false });

    const stored = Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i));
    expect(stored.sort()).toEqual(PREFERENCE_KEYS.map((key) => PREFERENCE_PREFIX + key).sort());
  });
});
