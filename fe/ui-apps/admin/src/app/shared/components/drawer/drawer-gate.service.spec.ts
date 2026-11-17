import { describe, expect, it } from 'vitest';

import { DrawerGateService } from './drawer-gate.service';

describe('DrawerGateService', () => {
  it('starts with no dirty drawers', () => {
    const service = new DrawerGateService();
    expect(service.anyDirty()).toBe(false);
  });

  it('reports dirty while at least one drawer is registered dirty', () => {
    const service = new DrawerGateService();
    const a = {};
    const b = {};

    service.setDirty(a, true);
    expect(service.anyDirty()).toBe(true);

    service.setDirty(b, true);
    service.setDirty(a, false);
    expect(service.anyDirty()).toBe(true);

    service.setDirty(b, false);
    expect(service.anyDirty()).toBe(false);
  });

  it('setDirty is idempotent per drawer', () => {
    const service = new DrawerGateService();
    const drawer = {};

    service.setDirty(drawer, true);
    service.setDirty(drawer, true);
    service.setDirty(drawer, false);
    expect(service.anyDirty()).toBe(false);
  });

  it('remove clears a drawer regardless of state', () => {
    const service = new DrawerGateService();
    const drawer = {};

    service.setDirty(drawer, true);
    service.remove(drawer);
    expect(service.anyDirty()).toBe(false);

    // Removing an unregistered drawer is a no-op.
    service.remove({});
    expect(service.anyDirty()).toBe(false);
  });
});
