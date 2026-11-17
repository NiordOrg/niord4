import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MessageAreaNameComponent } from './message-area-name.component';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../../../testing/test-config';
import { Area } from '../../../../core/models/message.model';

describe('MessageAreaNameComponent', () => {
  let fixture: ComponentFixture<MessageAreaNameComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageAreaNameComponent],
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
    });
    fixture = TestBed.createComponent(MessageAreaNameComponent);
  });

  function rendered() {
    const el = fixture.nativeElement as HTMLElement;
    return {
      text: el.textContent,
      langs: [...el.querySelectorAll('span')].map((span) => span.getAttribute('lang')),
    };
  }

  it('should render area name', () => {
    const area: Area = { id: 1, descs: [{ lang: 'en', name: 'Anchorland' }] };
    fixture.componentRef.setInput('area', area);
    fixture.detectChanges();
    expect(rendered().text).toBe('Anchorland');
  });

  it('should render lineage with divider', () => {
    const parent: Area = { id: 1, descs: [{ lang: 'en', name: 'Europe' }] };
    const child: Area = { id: 2, parent, descs: [{ lang: 'en', name: 'Anchorland' }] };
    fixture.componentRef.setInput('area', child);
    fixture.detectChanges();
    expect(rendered().text).toBe('Europe - Anchorland');
  });

  it('should show leaf only when lineage=false', () => {
    const parent: Area = { id: 1, descs: [{ lang: 'en', name: 'Europe' }] };
    const child: Area = { id: 2, parent, descs: [{ lang: 'en', name: 'Anchorland' }] };
    fixture.componentRef.setInput('area', child);
    fixture.componentRef.setInput('lineage', false);
    fixture.detectChanges();
    expect(rendered().text).toBe('Anchorland');
  });

  it('leaves out an area with no name, and the divider with it', () => {
    const parent: Area = { id: 1, descs: [] };
    const child: Area = { id: 2, parent, descs: [{ lang: 'en', name: 'Anchorland' }] };
    fixture.componentRef.setInput('area', child);
    fixture.detectChanges();
    expect(rendered().text).toBe('Anchorland');
  });

  // An area the backend has no Kalaallisut name for is named in Danish on a Kalaallisut page,
  // and a screen reader has to be told, name by name, since a lineage can mix the two.
  it('marks each name that is not in the language of the page with its own', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MessageAreaNameComponent],
      providers: [
        provideTestNiordConfig({ languages: ['kl', 'da', 'en'], defaultLanguage: 'kl' }),
        provideTranslocoSpy(),
      ],
    });
    fixture = TestBed.createComponent(MessageAreaNameComponent);
    const parent: Area = { id: 1, descs: [{ lang: 'kl', name: 'Westmark' }] };
    const child: Area = { id: 2, parent, descs: [{ lang: 'da', name: 'Westmark Havn' }] };
    fixture.componentRef.setInput('area', child);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(document.documentElement.lang).toBe('kl');
    expect(rendered()).toEqual({
      text: 'Westmark - Westmark Havn',
      langs: [null, 'da'],
    });
  });
});
