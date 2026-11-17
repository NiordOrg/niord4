import { Component, input, computed } from '@angular/core';
import { Attachment } from '../../../../core/models/message.model';
import { ContentLang } from '../../../../shared/directives/content-lang';

/** A CSS length the ratio of two can be taken from: a number and a unit that is not relative. */
const LENGTH = /^\s*(\d*\.?\d+)\s*(px|em|rem|pt|pc|cm|mm|in)?\s*$/i;

/**
 * The width-to-height ratio of an authored box, as `aspect-ratio` writes it, or nothing when the
 * two lengths give none: a missing side, a percentage (which is a share of a different box for
 * each side) or two different units.
 */
function authoredRatio(width: string | undefined, height: string | undefined): string | undefined {
  const w = width ? LENGTH.exec(width) : null;
  const h = height ? LENGTH.exec(height) : null;
  if (!w || !h) return undefined;
  const unit = (w[2] ?? 'px').toLowerCase();
  if (unit !== (h[2] ?? 'px').toLowerCase()) return undefined;
  const [ww, hh] = [Number(w[1]), Number(h[1])];
  return ww > 0 && hh > 0 ? `${ww} / ${hh}` : undefined;
}

// The box is as wide as the card less its margins: a 100 % width on top of the margins would put
// it past the card's right edge and off its centre.
@Component({
  selector: 'app-message-attachment',
  imports: [ContentLang],
  template: `
    <div class="relative m-2.5 text-center">
      <div>
        @if (sourceType() === 'image') {
          <img
            class="inline-block"
            [src]="attachment().path"
            [style]="sourceStyle()"
            [alt]="altText()"
            [appContentLang]="captionLang()"
          />
        } @else {
          <video controls [style]="sourceStyle()">
            <source [src]="attachment().path" [type]="attachment().type" />
          </video>
        }
      </div>
      @if (caption(); as cap) {
        <div class="m-1">
          <span class="italic" [appContentLang]="captionLang()">{{ cap }}</span>
        </div>
      }
    </div>
  `,
})
export class MessageAttachmentComponent {
  attachment = input.required<Attachment>();

  sourceType = computed(() => {
    const att = this.attachment();
    return att.type?.startsWith('video') ? 'video' : 'image';
  });

  /**
   * The authored size, as far as the card has room for it. The width gives way to a narrower
   * card, so the height has to follow it: where the two lengths give a ratio, the height is left
   * to that ratio. Where they give none, the authored height stays and the picture is fitted
   * inside the box rather than stretched to fill it.
   */
  sourceStyle = computed(() => {
    const { width, height } = this.attachment();
    const style: Record<string, string> = { 'max-width': '100%' };
    if (width) style['width'] = width;
    const ratio = authoredRatio(width, height);
    if (ratio) {
      style['height'] = 'auto';
      style['aspect-ratio'] = ratio;
    } else if (height) {
      style['height'] = height;
      style['object-fit'] = 'contain';
    }
    return style;
  });

  caption = computed(() => {
    const att = this.attachment();
    return att.descs?.[0]?.caption || '';
  });

  /**
   * What the image is called to a reader who cannot see it: its caption, or else its file name.
   * An attachment is content — a chart excerpt, a photograph of a buoy — never decoration, so it
   * is never left without a name.
   */
  protected readonly altText = computed(() => this.caption() || this.attachment().fileName);

  /** The language the caption is written in, which is not always the page's. A file name has none. */
  protected readonly captionLang = computed(() =>
    this.caption() ? this.attachment().descs?.[0]?.lang : undefined,
  );
}
