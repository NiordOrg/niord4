import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, type EnvironmentProviders, NO_ERRORS_SCHEMA } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { LayoutComponent } from './layout.component';
import type { NiordInfoConfig } from '../../../niord-info.config';
import { provideTestNiordConfig, getTranslocoTestingModule } from '../../../testing/test-config';
import { provideSlot } from '../../slots/component-slots';

@Component({ selector: 'app-routed-view', template: `<p>routed</p>` })
class RoutedView {}

@Component({ selector: 'app-country-footer', template: `<footer>country footer</footer>` })
class CountryFooter {}

@Component({ selector: 'app-country-notice', template: `<p>country notice</p>` })
class CountryNotice {}

describe('LayoutComponent', () => {
  let fixture: ComponentFixture<LayoutComponent>;

  async function setUp(config?: Partial<NiordInfoConfig>, ...slots: EnvironmentProviders[]) {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [LayoutComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(config),
        provideRouter([{ path: '', component: RoutedView }]),
        ...slots,
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();
  }

  beforeEach(() => setUp());

  const el = () => fixture.nativeElement as HTMLElement;

  // A country may bring a footer of its own; a build that names none gets the site's plain one,
  // and a country's footer replaces it rather than joining it.
  it("renders the country's footer after the main landmark, in place of the default", async () => {
    await setUp({ footerComponent: CountryFooter });
    expect(el().querySelector('app-country-footer')!.textContent!.trim()).toBe('country footer');
    expect(el().querySelector('app-default-footer')).toBeNull();
    expect(
      el()
        .querySelector('main')!
        .compareDocumentPosition(el().querySelector('app-country-footer')!),
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('renders the default footer for a build that names none', () => {
    expect(el().querySelector('app-default-footer')!.textContent).toContain('Niord');
    expect(el().querySelector('app-country-footer')).toBeNull();
  });

  // A screen reader's "jump to main" needs a main landmark to jump to.
  it('renders the routed view inside the main landmark, between the menu and the footer', async () => {
    await TestBed.inject(Router).navigateByUrl('/');
    fixture.detectChanges();

    const main = el().querySelector('main')!;
    expect(el().querySelectorAll('main')).toHaveLength(1);
    expect(main.querySelector('app-routed-view')).not.toBeNull();
    expect(main.querySelector('app-main-menu')).toBeNull();
    expect(main.compareDocumentPosition(el().querySelector('app-main-menu')!)).toBe(
      Node.DOCUMENT_POSITION_PRECEDING,
    );
  });

  // Every title and reference in a list is a tab stop, so the footer is hundreds of presses
  // away without a way past them.
  it('starts with a link that moves focus to the main landmark', () => {
    const skip = el().firstElementChild as HTMLAnchorElement;
    expect(skip.tagName).toBe('A');
    expect(skip.getAttribute('href')).toBe('#main-content');
    expect(skip.textContent!.trim()).toBe('Skip to main content');

    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    skip.dispatchEvent(click);

    const main = el().querySelector('main')!;
    expect(main.id).toBe('main-content');
    expect(document.activeElement).toBe(main);
    // The fragment stays out of the address, which the router reads.
    expect(click.defaultPrevented).toBe(true);
  });

  // The one place in the site's page a country may add a component of its own.
  it("renders a country's slot component after the main landmark and before the footer", async () => {
    await setUp(
      undefined,
      provideSlot('layout.beforeFooter', () => Promise.resolve(CountryNotice)),
    );
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    const notice = el().querySelector('app-country-notice')!;
    expect(notice.textContent).toBe('country notice');
    expect(el().querySelector('main')!.compareDocumentPosition(notice)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(notice.compareDocumentPosition(el().querySelector('app-default-footer')!)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it('renders nothing between the main landmark and the footer when no country adds a component', () => {
    const between = el().querySelector('main')!.nextElementSibling!;
    expect(between.tagName).toBe('APP-SLOT');
    expect(between.children).toHaveLength(0);
    expect(between.textContent).toBe('');
    expect(between.nextElementSibling!.tagName).toBe('APP-DEFAULT-FOOTER');
  });
});
