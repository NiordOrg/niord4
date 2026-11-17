import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * The ONE way a message is named anywhere in this section.
 *
 * Three facts arrive with every row that refers to a message — the key that
 * addresses it, the short id it is written under, and what it is called — and
 * this is where they are turned into something a reader can act on.
 *
 * **A uid is never printed.** It is a uuid: it names nothing to the person
 * looking at the row, it cannot be quoted in a mail about the message, and a
 * column of them tells a curator nothing about which notice they are about to
 * take out of an issue. So the short id is the name where there is one, and the
 * TITLE is the name where there is not — with a mark saying the message has not
 * been numbered yet, which is the fact behind the missing id and the thing an
 * editor reading the row would want to know.
 *
 * **It opens the message in the editor.** Somebody reading an issue who clicks a
 * message wants to do something about it — correct it, cancel it, look at why it
 * expired — and the editor is where that happens; the message list would leave
 * them to search for the same row again. The uid travels as the link TARGET
 * only, the same address the message list and the edit shortcut hand the editor:
 * the editor resolves it by uid first and by short id second, so a message
 * nobody has numbered is still one click away from the row that mentions it. A
 * row carrying neither an id nor a uid addresses nothing and is rendered as
 * plain text.
 *
 * Anything projected into it — a `(P)`/`(T)` suffix, the drift marks — follows
 * the name and precedes the note, so every surface that names a message reads
 * the same way round.
 */
@Component({
  selector: 'app-message-ref',
  imports: [RouterLink, TranslocoPipe],
  template: `
    @let text = name() ?? ('admin.publications.messageRef.untitled' | transloco);
    @if (target(); as id) {
      <a [class]="nameClass()" [attr.title]="hoverText()" [routerLink]="['/editor/edit', id]">{{
        text
      }}</a>
    } @else {
      <span [class]="nameClass()" [attr.title]="hoverText()">{{ text }}</span>
    }
    <ng-content />
    <!--
      Said rather than left as an absence. A row without a number is not a row
      whose id failed to load; it is a message nobody has numbered yet, and that
      is the fact an editor reading the list is being told.
    -->
    @if (unnumbered()) {
      <span class="ml-1 text-xs whitespace-nowrap text-fg-subtle">
        {{ 'admin.publications.messageRef.notNumbered' | transloco }}
      </span>
    }
  `,
  host: { class: 'contents' },
})
export class MessageRef {
  /** The message's own identifier. The link target, and never shown. */
  readonly messageUid = input<string | null | undefined>(undefined);

  /** The short id — `NM-815-26` — where the message has one. */
  readonly messageId = input<string | null | undefined>(undefined);

  /** What the message is called, in the language the row was read in. */
  readonly title = input<string | null | undefined>(undefined);

  /**
   * Whether this reference is to a message taken OUT of what the list is about,
   * so the name is struck through as well as said.
   */
  readonly struck = input(false);

  /** True while the message carries no short id — the note says so. */
  protected readonly unnumbered = computed(() => !this.messageId());

  /** What the row says: the short id where there is one, the title otherwise. */
  protected readonly name = computed(() => this.messageId() ?? this.title() ?? undefined);

  /**
   * Where the link goes: by uid where there is one, by short id otherwise.
   *
   * The uid first, because it is what the editor is addressed by everywhere
   * else: an editor already holding the message recognises its own uid and keeps
   * unsaved work, and a short id can name more than one message across domains.
   */
  protected readonly target = computed(() => this.messageUid() ?? this.messageId() ?? undefined);

  /**
   * The whole name in the hover text, because a title is cut to its column.
   *
   * Only where the title IS the name — a short id is short enough to be shown
   * whole, and a tooltip repeating it says nothing.
   */
  protected readonly hoverText = computed(() =>
    this.unnumbered() ? (this.title() ?? null) : null,
  );

  protected readonly nameClass = computed(() => {
    // A short id offers nowhere sensible to break; a title is prose and wraps.
    const link = this.unnumbered() ? 'reference-link' : 'reference-link whitespace-nowrap';
    const base = this.target() ? link : '';
    return this.struck() ? `${base} line-through`.trim() : base;
  });
}
