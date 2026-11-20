import { ApplicationRef, Component, Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DialogRef } from '@angular/cdk/dialog';
import { By } from '@angular/platform-browser';

import { DialogFrame } from './dialog-frame';
import { openInfoDialog } from './open-dialog';
import { provideInfoIcons } from '../../icons/info-icons';
import { getTranslocoTestingModule } from '../../../testing/test-config';
import { FakeResizeObserver } from '../../../testing/fake-resize-observer';

@Component({
  imports: [DialogFrame],
  template: `
    <app-dialog-frame title="A title">
      <p class="body-text">Body</p>
      <footer class="dialog-foot"><button type="button" class="btn-primary">Done</button></footer>
    </app-dialog-frame>
  `,
})
class FrameHost {}

describe('DialogFrame', () => {
  describe('the panel', () => {
    function setup(providers: unknown[] = []) {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [getTranslocoTestingModule()],
        providers: [provideInfoIcons(), ...providers] as never[],
      });
      const fixture = TestBed.createComponent(FrameHost);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('renders the header, the body and the projected footer', () => {
      const el = setup();
      expect(el.querySelector('.dialog-title')!.textContent!.trim()).toBe('A title');
      expect(el.querySelector('.dialog-body .body-text')).not.toBeNull();
      // The footer is projected as a sibling of the body, so a dialog without one gets no bar.
      expect(el.querySelector('.dialog-panel > .dialog-foot')).not.toBeNull();
    });

    it('closes the dialog from the header control', () => {
      const ref = { close: vi.fn() };
      const el = setup([{ provide: DialogRef, useValue: ref }]);
      (el.querySelector('.dialog-header .icon-button') as HTMLButtonElement).click();
      expect(ref.close).toHaveBeenCalled();
    });

    // A control that leaves the page with focus on it drops focus to the document behind the
    // dialog; the title is where a dialog puts it instead. It is never a Tab stop of its own.
    it('takes focus on its title when asked, and leaves the title out of the tab order', () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [getTranslocoTestingModule()],
        providers: [provideInfoIcons()],
      });
      const fixture = TestBed.createComponent(FrameHost);
      fixture.detectChanges();
      const heading = (fixture.nativeElement as HTMLElement).querySelector('.dialog-title')!;
      expect(heading.getAttribute('tabindex')).toBe('-1');

      fixture.debugElement.query(By.directive(DialogFrame)).componentInstance.focusTitle();

      expect(document.activeElement).toBe(heading);
    });

    // WCAG 2.1.1: a document with nothing focusable in it — a disclaimer on a phone — can be
    // scrolled to its end from the keyboard only when the body itself takes focus. A body that
    // fits takes no tab stop.
    describe('its body', () => {
      beforeEach(() => vi.stubGlobal('ResizeObserver', FakeResizeObserver));
      afterEach(() => {
        vi.unstubAllGlobals();
        FakeResizeObserver.last = undefined;
      });

      function bodyLaidOut(contentHeight: number, boxHeight: number): HTMLElement {
        const body = setup().querySelector('.dialog-body') as HTMLElement;
        TestBed.tick();
        for (const [name, value] of [
          ['scrollWidth', 300],
          ['clientWidth', 300],
          ['scrollHeight', contentHeight],
          ['clientHeight', boxHeight],
        ] as const) {
          Object.defineProperty(body, name, { configurable: true, get: () => value });
        }
        FakeResizeObserver.last!.report();
        TestBed.tick();
        return body;
      }

      it('is a tab stop named by the title while its content is taller than it', () => {
        const body = bodyLaidOut(1200, 400);
        expect(body.getAttribute('tabindex')).toBe('0');
        expect(body.getAttribute('role')).toBe('region');
        expect(body.getAttribute('aria-label')).toBe('A title');
        // Drawn inside the body: the panel clips whatever lies outside it.
        expect(body.className).toContain('focus-visible:-outline-offset-2');
      });

      it('takes no tab stop while its content fits', () => {
        const body = bodyLaidOut(400, 400);
        expect(body.hasAttribute('tabindex')).toBe(false);
        expect(body.hasAttribute('role')).toBe(false);
      });
    });

    it('names the dialog by registering its heading with the container', () => {
      const container = { _addAriaLabelledBy: vi.fn(), _removeAriaLabelledBy: vi.fn() };
      const el = setup([
        { provide: DialogRef, useValue: { close: vi.fn(), containerInstance: container } },
      ]);
      const headingId = el.querySelector('.dialog-title')!.id;
      expect(headingId).not.toBe('');
      expect(container._addAriaLabelledBy).toHaveBeenCalledWith(headingId);
    });
  });

  describe('inside a real CDK dialog', () => {
    // The panel portals into a body-level overlay container that outlives the fixture, so the
    // Escape at the end of the test is the only thing that takes it down — and it never runs if
    // an assertion before it fails. A panel left standing leaks into every later spec in the
    // shared fork, which then fails for a reason that has nothing to do with it.
    afterEach(() => {
      document
        .querySelectorAll('.cdk-overlay-container')
        .forEach((container) => container.replaceChildren());
    });

    it('carries the dialog role, the modal flag and the heading as its name, and closes on Escape', async () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [getTranslocoTestingModule()],
        providers: [provideInfoIcons()],
      });
      const appRef = TestBed.inject(ApplicationRef);
      await openInfoDialog(TestBed.inject(Injector), async () => FrameHost, null);
      appRef.tick();

      const container = document.querySelector('cdk-dialog-container')!;
      expect(container.getAttribute('role')).toBe('dialog');
      expect(container.getAttribute('aria-modal')).toBe('true');
      expect(container.getAttribute('aria-labelledby')).toBe(
        container.querySelector('.dialog-title')!.id,
      );

      // The overlay listens on <body>, so the event has to bubble up from inside the dialog,
      // and the CDK reads the legacy `keyCode` rather than `key`.
      container.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true } as never),
      );
      appRef.tick();
      expect(document.querySelector('cdk-dialog-container')).toBeNull();
    });
  });
});
