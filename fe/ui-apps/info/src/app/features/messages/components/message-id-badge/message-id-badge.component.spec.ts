import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { MessageIdBadgeComponent } from './message-id-badge.component';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../../../testing/test-config';

describe('MessageIdBadgeComponent', () => {
  let fixture: ComponentFixture<MessageIdBadgeComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageIdBadgeComponent],
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
      schemas: [NO_ERRORS_SCHEMA],
    });
    fixture = TestBed.createComponent(MessageIdBadgeComponent);
  });

  function chips(): { className: string; text: string }[] {
    return [...fixture.nativeElement.querySelectorAll('span.status-badge')].map(
      (el: HTMLElement) => ({ className: el.className, text: el.textContent ?? '' }),
    );
  }

  it('renders the identifier as one chip', () => {
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NW', shortId: 'NW-001' });
    fixture.detectChanges();
    expect(chips()).toEqual([{ className: 'status-badge mono-id', text: 'NW-001' }]);
  });

  it('gives an NM identifier the same chip as an NW one', () => {
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NM', shortId: 'NM-001' });
    fixture.detectChanges();
    expect(chips()).toEqual([{ className: 'status-badge mono-id', text: 'NM-001' }]);
  });

  it('adds a second chip for a cancellation when the status is asked for', () => {
    fixture.componentRef.setInput('msg', {
      id: '1',
      mainType: 'NW',
      shortId: 'NW-001',
      status: 'CANCELLED',
    });
    fixture.componentRef.setInput('showStatus', true);
    fixture.detectChanges();
    const [id, status] = chips();
    expect(id.text).toBe('NW-001');
    expect(status.className).toContain('is-cancelled');
    expect(status.className).not.toContain('is-expired');
    expect(status.text).toBe('STATUS_CANCELLED');
  });

  it('marks a temporary notice after the chips', () => {
    fixture.componentRef.setInput('msg', {
      id: '1',
      mainType: 'NW',
      shortId: 'NW-001',
      type: 'TEMPORARY_NOTICE',
    });
    fixture.detectChanges();
    expect(chips()).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('(T)');
  });

  // The badge is markup the template writes; nothing is trusted into the DOM here.
  it('binds no HTML', () => {
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NW', shortId: '<b>x</b>' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('b')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('<b>x</b>');
  });
});
