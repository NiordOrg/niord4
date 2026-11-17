import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { provideRouter, Router, TitleStrategy } from '@angular/router';
import { TRANSLOCO_MISSING_HANDLER, TranslocoService } from '@jsverse/transloco';

import { InfoTitleStrategy } from './info-title.strategy';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  TEST_TRANSLATIONS,
} from '../../testing/test-config';

@Component({ template: '' })
class BlankComponent {}

describe('InfoTitleStrategy', () => {
  let title: Title;
  let router: Router;

  function setUp() {
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [
        provideRouter([
          { path: 'plain', component: BlankComponent },
          { path: 'map', component: BlankComponent, title: 'MENU_MAP' },
        ]),
        { provide: TitleStrategy, useClass: InfoTitleStrategy },
      ],
    });
    title = TestBed.inject(Title);
    router = TestBed.inject(Router);
  }

  it('names the site alone on a route that names no view', async () => {
    setUp();
    await router.navigateByUrl('/plain');
    expect(title.getTitle()).toBe(TEST_TRANSLATIONS['en']['SITE_TITLE']);
  });

  it('names the view the reader is on after the site', async () => {
    setUp();
    await router.navigateByUrl('/map');
    expect(title.getTitle()).toBe(
      `${TEST_TRANSLATIONS['en']['SITE_TITLE']} - ${TEST_TRANSLATIONS['en']['MENU_MAP']}`,
    );
  });

  it('follows the language switcher without a second navigation', async () => {
    setUp();
    await router.navigateByUrl('/map');
    TestBed.inject(TranslocoService).setActiveLang('zz');
    expect(title.getTitle()).toBe(
      `${TEST_TRANSLATIONS['zz']['SITE_TITLE']} - ${TEST_TRANSLATIONS['zz']['MENU_MAP']}`,
    );
    expect(title.getTitle()).not.toContain('MENU_MAP');
  });

  // On a slow link the first navigation lands long before the words do. The tab keeps the title
  // the page was served with meanwhile, rather than reading "SITE_TITLE - MENU_MAP".
  it('keeps the served title until the bundle has landed, then names the view', async () => {
    TestBed.configureTestingModule({
      imports: [getEmptyTranslocoTestingModule()],
      providers: [
        provideRouter([{ path: 'map', component: BlankComponent, title: 'MENU_MAP' }]),
        { provide: TitleStrategy, useClass: InfoTitleStrategy },
      ],
    });
    title = TestBed.inject(Title);
    title.setTitle('Served title');
    await TestBed.inject(Router).navigateByUrl('/map');
    expect(title.getTitle()).toBe('Served title');

    TestBed.inject(TranslocoService).setTranslation(TEST_TRANSLATIONS['en'], 'en');
    expect(title.getTitle()).toBe(
      `${TEST_TRANSLATIONS['en']['SITE_TITLE']} - ${TEST_TRANSLATIONS['en']['MENU_MAP']}`,
    );
  });

  // Waiting for the bundle is not a missing text: outside production Transloco warns about every
  // key it cannot translate, and a probe by translation read as a fault on every cold load.
  it('waits for the bundle without a missing-translation warning', async () => {
    const missing: string[] = [];
    TestBed.configureTestingModule({
      imports: [getEmptyTranslocoTestingModule()],
      providers: [
        provideRouter([{ path: 'map', component: BlankComponent, title: 'MENU_MAP' }]),
        { provide: TitleStrategy, useClass: InfoTitleStrategy },
        {
          provide: TRANSLOCO_MISSING_HANDLER,
          useValue: {
            handle: (key: string) => {
              missing.push(key);
              return key;
            },
          },
        },
      ],
    });
    await TestBed.inject(Router).navigateByUrl('/map');
    expect(missing).toEqual([]);
  });

  // On a cold load the first navigation can beat the bundle, and a one-shot translate would
  // bake the raw key into the tab with nothing ever fixing it.
  it('corrects a title applied before the bundle arrived', async () => {
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [
        provideRouter([{ path: 'map', component: BlankComponent, title: 'MENU_MAP' }]),
        { provide: TitleStrategy, useClass: InfoTitleStrategy },
      ],
    });
    title = TestBed.inject(Title);
    const transloco = TestBed.inject(TranslocoService);
    transloco.setActiveLang('zz');
    await TestBed.inject(Router).navigateByUrl('/map');

    transloco.setActiveLang('en');
    expect(title.getTitle()).toBe(
      `${TEST_TRANSLATIONS['en']['SITE_TITLE']} - ${TEST_TRANSLATIONS['en']['MENU_MAP']}`,
    );
  });
});
