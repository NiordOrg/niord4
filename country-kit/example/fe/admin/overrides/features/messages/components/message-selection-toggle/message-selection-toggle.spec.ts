import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideTestTransloco } from '@niord/admin/api/testing';
import { MessageSelectionService } from '@niord/admin/internal/features/messages/services/message-selection.service';
import { Icon } from '@niord/admin/internal/shared/icons/icon';
import { describe, expect, it } from 'vitest';

import { MessageSelectionToggle } from './message-selection-toggle';

function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideTestTransloco()],
  });
  const fixture = TestBed.createComponent(MessageSelectionToggle);
  fixture.componentRef.setInput('message', { id: 'm-1' });
  fixture.detectChanges();
  const icon = () => fixture.debugElement.query(By.directive(Icon)).componentInstance as Icon;
  return { fixture, icon, selection: TestBed.inject(MessageSelectionService) };
}

// The example's one guarded override: the selection toggle shows a bookmark where the base
// product shows a star. The base product's own spec of the toggle runs against this copy too.
describe('MessageSelectionToggle (example country override)', () => {
  it('shows a bookmark, outlined while the message is not selected', () => {
    const { icon } = setup();
    expect(icon().name()).toBe('lucideBookmark');
    expect(icon().filled()).toBe(false);
  });

  it('fills the bookmark when the message is selected', () => {
    const { fixture, icon, selection } = setup();
    selection.select({ id: 'm-1' });
    fixture.detectChanges();
    expect(icon().name()).toBe('lucideBookmark');
    expect(icon().filled()).toBe(true);
  });
});
