import { describe, expect, it } from 'vitest';

import { keycloakConsoleUrl } from './keycloak-console-url';

describe('keycloakConsoleUrl', () => {
  it('joins a base URL that already ends with a slash without doubling it', () => {
    expect(keycloakConsoleUrl({ url: 'https://kc.example.com/auth/', realm: 'dma-test' })).toBe(
      'https://kc.example.com/auth/admin/dma-test/console/#/dma-test/clients',
    );
  });

  it('appends the missing slash before the console path', () => {
    expect(keycloakConsoleUrl({ url: 'https://kc.example.com/auth', realm: 'dma-test' })).toBe(
      'https://kc.example.com/auth/admin/dma-test/console/#/dma-test/clients',
    );
  });

  it('uses the configured realm — never a hardcoded one', () => {
    const url = keycloakConsoleUrl({ url: 'http://localhost:8080/', realm: 'brs-prod' });
    expect(url).toBe('http://localhost:8080/admin/brs-prod/console/#/brs-prod/clients');
    expect(url).not.toContain('niord');
  });
});
