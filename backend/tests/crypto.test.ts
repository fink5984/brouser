import { describe, it, expect } from 'vitest';
import { timingSafeEqual } from '../src/lib/crypto.js';

describe('timingSafeEqual', () => {
  it('returns true for identical strings', () => {
    expect(timingSafeEqual('super-secret', 'super-secret')).toBe(true);
  });

  it('returns false for different strings of the same length', () => {
    expect(timingSafeEqual('super-secret', 'super-secreT')).toBe(false);
  });

  it('returns false for strings of different length', () => {
    expect(timingSafeEqual('short', 'a-lot-longer-value')).toBe(false);
  });

  it('returns false when compared against an empty string', () => {
    expect(timingSafeEqual('non-empty', '')).toBe(false);
  });
});
