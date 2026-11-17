import { describe, expect, it } from 'vitest';

import { formatByteSize } from './format-byte-size';

const KIB = 1024;
const MIB = 1024 * KIB;
const GIB = 1024 * MIB;

describe('formatByteSize', () => {
  it('shows a size below 1024 bytes as whole bytes', () => {
    expect(formatByteSize(0)).toBe('0 B');
    expect(formatByteSize(1023)).toBe('1023 B');
  });

  it('shows 1024 bytes as 1.0 KB', () => {
    expect(formatByteSize(1024)).toBe('1.0 KB');
  });

  it('rounds before it picks the unit: a size that would print as 1024.0 KB is 1.0 MB', () => {
    expect(formatByteSize(1_048_525)).toBe('1.0 MB');
    expect(formatByteSize(1_048_575)).toBe('1.0 MB');
    // The last size that still rounds below 1024 KB.
    expect(formatByteSize(1_048_524)).toBe('1023.9 KB');
  });

  it('has GB: a size just under 1 GiB is 1.0 GB, not 1024.0 MB', () => {
    expect(formatByteSize(GIB - 1)).toBe('1.0 GB');
    expect(formatByteSize(5 * GIB)).toBe('5.0 GB');
  });

  it('keeps sizes beyond GB in GB', () => {
    expect(formatByteSize(2048 * GIB)).toBe('2048.0 GB');
  });

  it('shows one decimal in KB and MB', () => {
    expect(formatByteSize(1536)).toBe('1.5 KB');
    expect(formatByteSize(5 * MIB + 300 * KIB)).toBe('5.3 MB');
  });
});
