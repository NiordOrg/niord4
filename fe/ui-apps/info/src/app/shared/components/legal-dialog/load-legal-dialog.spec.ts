import { describe, expect, it } from 'vitest';

import { LegalDialogComponent } from './legal-dialog.component';
import { loadLegalDialog } from './load-legal-dialog';

describe('loadLegalDialog', () => {
  it('resolves to the legal dialog', async () => {
    await expect(loadLegalDialog()).resolves.toBe(LegalDialogComponent);
  });
});
