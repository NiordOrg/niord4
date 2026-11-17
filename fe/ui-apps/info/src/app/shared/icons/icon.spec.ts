import { TestBed } from '@angular/core/testing';

import { Icon } from './icon';
import { infoLucideIcons, provideInfoIcons } from './info-icons';

describe('Icon', () => {
  function render(name: string) {
    TestBed.configureTestingModule({ providers: [provideInfoIcons()] });
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', name);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the named Lucide glyph as an svg', () => {
    expect(render('lucidePrinter').nativeElement.querySelector('svg')).toBeTruthy();
  });

  it('marks the glyph decorative, so the accessible name stays on the control around it', () => {
    const icon = render('lucidePrinter').nativeElement.querySelector('ng-icon');
    expect(icon?.getAttribute('aria-hidden')).toBe('true');
  });

  it('registers every glyph the site renders', () => {
    // A name that is not in the set renders an empty <ng-icon>, silently — so the set is the
    // thing worth asserting, not one call site.
    for (const [name, svg] of Object.entries(infoLucideIcons)) {
      expect(name.startsWith('lucide')).toBe(true);
      expect(svg).toContain('<svg');
    }
  });
});
