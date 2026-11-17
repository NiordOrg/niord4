import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { BrandingService } from '../../i18n/branding.service';
import { LanguageService } from '../services/language.service';
import { LEGAL_DOCUMENTS, LegalDocument, LegalDocumentDialog } from './legal-document-dialog';

/**
 * The country's footer: the authority's copyright link, its logo and its legal documents. Every
 * item is the country's branding and shows only when the branding of the active language sets
 * it; a build without branding (the base product's own) renders no footer row at all.
 *
 * The branding decides what shows, the merged translation the wording: what to show is asked of
 * the branding layer, never of the merged translation, because the backend's web dictionary
 * carries keys of the same names, which reach every build; the text shown is the merged
 * translation's, so an operator's dictionary row for one of these keys replaces the branding's
 * wording (fe-country-split-plan §4.10, dictionary layer 5 above branding layer 4).
 */
@Component({
  selector: 'app-main-footer',
  imports: [TranslocoPipe, LegalDocumentDialog],
  templateUrl: './footer.html',
  styleUrl: './footer.css',
})
export class Footer {
  private readonly branding = inject(BrandingService);
  private readonly language = inject(LanguageService);

  protected readonly copyright = computed(() => this.isBranded('footer.copyright'));
  protected readonly copyrightLink = computed(() => this.isBranded('footer.link'));
  protected readonly logo = computed(() => this.isBranded('main.footer.links.center.imageSrc'));
  protected readonly logoLink = computed(() => this.isBranded('main.footer.links.center.url'));
  protected readonly documents = computed(() =>
    LEGAL_DOCUMENTS.filter((id) => this.isBranded(`footer.${id}`)),
  );
  protected readonly shown = computed(
    () => this.copyright() || this.logo() || this.documents().length > 0,
  );

  /** The legal document whose dialog is open or was last open. */
  protected readonly document = signal<LegalDocument>('disclaimer');
  protected readonly documentOpen = signal(false);

  protected openDocument(id: LegalDocument): void {
    this.document.set(id);
    this.documentOpen.set(true);
  }

  private isBranded(key: string): boolean {
    return this.branding.isBranded(this.language.selectedLanguage(), key);
  }
}
