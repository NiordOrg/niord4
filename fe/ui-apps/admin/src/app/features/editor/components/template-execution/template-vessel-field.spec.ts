import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { TemplatesApiService } from '../../services/api/templates-api.service';
import { TemplateVesselField, type VesselData } from './template-vessel-field';

interface Handle {
  patch(patch: Partial<VesselData>): void;
}

function setup(value?: VesselData) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  // Render nothing — this suite drives the value-merge logic, not the (child-heavy) template.
  TestBed.overrideComponent(TemplateVesselField, { set: { template: '' } });
  const fixture = TestBed.createComponent(TemplateVesselField);
  fixture.componentRef.setInput('value', value);
  return { fixture, handle: fixture.componentInstance as unknown as Handle };
}

describe('TemplateVesselField distance unit', () => {
  it("labels the unit buttons with the language's unit texts, keeping the stored unit codes", () => {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        provideNiordIcons(),
        { provide: TemplatesApiService, useValue: { validate: vi.fn() } },
      ],
    });
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslation(
      { 'common.distanceUnit.nm': 'sm', 'common.distanceUnit.m': 'm' },
      'da',
    );
    transloco.setActiveLang('da');
    const fixture = TestBed.createComponent(TemplateVesselField);
    const emitted = vi.fn();
    fixture.componentInstance.valueChange.subscribe(emitted);
    fixture.detectChanges();

    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')];
    expect(buttons.map((button) => button.textContent?.trim())).toEqual(['sm', 'm']);
    buttons[0].click();
    expect(emitted).toHaveBeenCalledWith(expect.objectContaining({ minDistType: 'nm' }));
  });
});

describe('TemplateVesselField', () => {
  it('emits the merged vessel on a field change, seeding the defaults', () => {
    const { fixture, handle } = setup();
    const emitted = vi.fn();
    fixture.componentInstance.valueChange.subscribe(emitted);

    handle.patch({ name: 'Tug' });

    expect(emitted).toHaveBeenCalledWith({ contact: true, minDistType: 'm', name: 'Tug' });
  });

  it('preserves the existing value when patching', () => {
    const { fixture, handle } = setup({ name: 'Tug', contact: false, minDistType: 'nm' });
    const emitted = vi.fn();
    fixture.componentInstance.valueChange.subscribe(emitted);

    handle.patch({ minDist: 5 });

    expect(emitted).toHaveBeenCalledWith({
      name: 'Tug',
      contact: false,
      minDistType: 'nm',
      minDist: 5,
    });
  });
});
