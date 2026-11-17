import type { APIRequest, APIRequestContext, APIResponse, Page } from '@playwright/test';

import { TEST_AUTH_WINDOW_KEY } from '../../auth/inject-auth';
import { fetchTestTokenBundle, type TestRole } from '../../auth/keycloak-login';

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const Buffer: { from(data: string, encoding: 'utf8'): Uint8Array };

/*
 * The backend's two APIs as the scenarios read them, both through the admin dev server's /rest
 * proxy, so the suite needs no backend address of its own.
 *
 * The admin API is called with the token the browser page itself holds (the bundle the login
 * fixture seeded), so a call is made by the same user as the screen it is compared with, and with
 * the domain header the application sends. The public API is called without a token.
 */

/** The access token of the user signed in on the page. The page must have loaded the app once. */
async function accessTokenOf(page: Page): Promise<string> {
  if (page.url() === 'about:blank') await page.goto('/');
  const token = await page.evaluate(
    (key) =>
      (window as unknown as Record<string, { accessToken?: string } | undefined>)[key]?.accessToken,
    TEST_AUTH_WINDOW_KEY,
  );
  if (!token) throw new Error('the page holds no test login: use one of the role fixtures');
  return token;
}

/**
 * Runs a request and, when it throws (a refused connection, a timeout), rethrows its message with
 * the bearer token blanked: Playwright's call log prints every request header, and a report with
 * the token in it may be shared.
 */
async function sent<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (error) {
    const message = String(error instanceof Error ? error.message : error);
    // No cause on purpose: the caught error's message holds the token.
    // eslint-disable-next-line preserve-caught-error
    throw new Error(message.replace(/Bearer\s+[\w.~+/=-]+/g, 'Bearer <token>'));
  }
}

/**
 * Work as one of the test users without a page, as an afterAll hook must: a request context that
 * sends the user's token and the domain header. A failure's message has the token blanked.
 */
export async function asTestUser<T>(
  playwright: { readonly request: { newContext: APIRequest['newContext'] } },
  baseURL: string,
  role: TestRole,
  domain: string,
  work: (request: APIRequestContext) => Promise<T>,
): Promise<T> {
  const token = (await fetchTestTokenBundle(role)).accessToken;
  const request = await playwright.request.newContext({
    baseURL,
    extraHTTPHeaders: { Authorization: `Bearer ${token}`, NiordDomain: domain },
  });
  try {
    return await sent(() => work(request));
  } finally {
    await request.dispose();
  }
}

async function bodyOf<T>(response: APIResponse, call: string): Promise<T> {
  if (!response.ok()) {
    throw new Error(
      `${call} answered ${response.status()}: ${(await response.text()).slice(0, 500)}`,
    );
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

type Params = Record<string, string | number | boolean>;

/** The admin API, as the user signed in on `page`, in `domain`. */
export class AdminApi {
  constructor(
    private readonly page: Page,
    private readonly domain: string,
  ) {}

  /** The same user in another domain. */
  inDomain(domain: string): AdminApi {
    return new AdminApi(this.page, domain);
  }

  private async headers(): Promise<Record<string, string>> {
    return {
      Authorization: `Bearer ${await accessTokenOf(this.page)}`,
      NiordDomain: this.domain,
      Accept: 'application/json',
    };
  }

  async get<T>(path: string, params?: Params): Promise<T> {
    const headers = await this.headers();
    const response = await sent(() => this.page.request.get(path, { params, headers }));
    return bodyOf<T>(response, `GET ${path}`);
  }

  async post<T>(path: string, data: unknown, params?: Params): Promise<T> {
    const headers = await this.headers();
    const response = await sent(() => this.page.request.post(path, { data, params, headers }));
    return bodyOf<T>(response, `POST ${path}`);
  }

  async put<T>(path: string, data: unknown, params?: Params): Promise<T> {
    const headers = await this.headers();
    const response = await sent(() => this.page.request.put(path, { data, params, headers }));
    return bodyOf<T>(response, `PUT ${path}`);
  }

  /**
   * A PUT whose body is one bare word, such as a message's new status, unquoted and labelled
   * JSON, as the app sends it. Given as bytes: Playwright would quote a string sent as JSON.
   */
  async putWord<T>(path: string, word: string, params?: Params): Promise<T> {
    const headers = { ...(await this.headers()), 'Content-Type': 'application/json' };
    const response = await sent(() =>
      this.page.request.put(path, { data: Buffer.from(word, 'utf8'), params, headers }),
    );
    return bodyOf<T>(response, `PUT ${path}`);
  }

  /** A PUT whose body is a JSON string, such as a series' new status, as the app sends it. */
  async putString<T>(path: string, value: string, params?: Params): Promise<T> {
    const headers = { ...(await this.headers()), 'Content-Type': 'application/json' };
    const response = await sent(() =>
      this.page.request.put(path, { data: value, params, headers }),
    );
    return bodyOf<T>(response, `PUT ${path}`);
  }

  /** The raw answer, for a file such as a PDF: any type is accepted, not only JSON. */
  async fetch(path: string, params?: Params): Promise<APIResponse> {
    const headers = { ...(await this.headers()), Accept: '*/*' };
    return sent(() => this.page.request.get(path, { params, headers }));
  }
}

/** The public API, anonymous. */
export class PublicApi {
  constructor(private readonly request: APIRequestContext) {}

  async get<T>(path: string, params?: Params): Promise<T> {
    const response = await this.request.get(path, {
      params,
      headers: { Accept: 'application/json' },
    });
    return bodyOf<T>(response, `GET ${path}`);
  }

  /** The answer's status, for a read that must fail (a message that is not public). */
  async status(path: string, params?: Params): Promise<number> {
    return (await this.request.get(path, { params })).status();
  }

  /** The raw answer, for a file such as a PDF. */
  async fetch(path: string, params?: Params): Promise<APIResponse> {
    return this.request.get(path, { params });
  }
}

/** The parts of a message the scenarios read, as both APIs give it. */
export interface MessageVo {
  readonly id: string;
  readonly shortId?: string;
  readonly number?: number;
  readonly status: string;
  readonly mainType: string;
  readonly type?: string;
  readonly kind?: string;
  readonly messageSeries?: { readonly seriesId: string };
  readonly publishDateFrom?: string | number;
  readonly publishDateTo?: string | number;
  readonly areas?: readonly { readonly id: number; readonly descs?: readonly Desc[] }[];
  readonly categories?: readonly { readonly id: number; readonly descs?: readonly Desc[] }[];
  readonly descs?: readonly Desc[];
  readonly parts?: readonly MessagePartVo[];
  readonly references?: readonly { readonly messageId: string; readonly type: string }[];
  readonly promulgations?: readonly PromulgationVo[];
}

export interface MessagePartVo {
  readonly indexNo?: number;
  readonly type: string;
  readonly descs?: readonly Desc[];
  readonly geometry?: {
    readonly features: readonly {
      readonly geometry: { readonly type: string; readonly coordinates: unknown };
      readonly properties?: Record<string, unknown>;
    }[];
  };
}

export interface PromulgationVo {
  readonly type: { readonly typeId: string };
  readonly promulgate: boolean;
  readonly text?: string;
  readonly preamble?: string;
}

export interface Desc {
  readonly lang: string;
  readonly name?: string;
  readonly title?: string;
  readonly vicinity?: string;
  readonly subject?: string;
  readonly details?: string;
}

/** The English text of a desc list, or '' when there is none. */
export function english<D extends { readonly lang: string }>(
  descs: readonly D[] | undefined,
  field: keyof D,
): string {
  return String(descs?.find((desc) => desc.lang === 'en')?.[field] ?? '');
}
