import { Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

/**
 * Renders untrusted HTML documents (e.g. backend-generated mail bodies) inside
 * an <iframe srcdoc> with an EMPTY `sandbox` attribute: scripts are inert, the
 * content gets an opaque origin (no same-origin access, no cookies, no
 * top-navigation), yet the document keeps its own <style>/<head> so it renders
 * with full fidelity — unlike `[innerHTML]`, whose sanitizer strips document
 * structure and inline styles.
 *
 * The `bypassSecurityTrustHtml` below is safe BY CONSTRUCTION of the sandbox:
 * the trusted value is only ever bound to the sandboxed frame's srcdoc, never
 * to live DOM. Do not reuse this component's pattern outside an empty-sandbox
 * iframe.
 */
@Component({
  selector: 'app-sandboxed-html',
  template: `
    <iframe
      sandbox=""
      class="h-72 w-full rounded-niord-md border border-surface-200 bg-surface-0"
      [attr.srcdoc]="trustedHtml()"
      [title]="title()"
    ></iframe>
  `,
  host: { class: 'block' },
})
export class SandboxedHtml {
  private readonly sanitizer = inject(DomSanitizer);

  readonly html = input.required<string>();
  /** Accessible name for the frame. */
  readonly title = input.required<string>();
  /**
   * Optional CSS injected into the frame as a `<style>` block. Use it when the
   * source document links external stylesheets by absolute path that cannot
   * resolve inside the opaque-origin sandbox. When empty (default) the document
   * is rendered verbatim.
   */
  readonly injectedCss = input<string>('');

  protected readonly trustedHtml = computed<SafeHtml>(() =>
    this.sanitizer.bypassSecurityTrustHtml(this.composeDocument()),
  );

  private composeDocument(): string {
    const css = this.injectedCss();
    if (css === '') {
      return this.html();
    }
    // Drop the document's own <link rel="stylesheet"> tags: their absolute
    // hrefs 404 inside the sandbox and only add console noise. The injected
    // <style> below replaces them.
    const withoutLinks = this.html().replace(/<link\b[^>]*\bstylesheet\b[^>]*>/gi, '');
    const style = `<style>${css}</style>`;
    return withoutLinks.includes('</head>')
      ? withoutLinks.replace('</head>', `${style}</head>`)
      : `${style}${withoutLinks}`;
  }
}
