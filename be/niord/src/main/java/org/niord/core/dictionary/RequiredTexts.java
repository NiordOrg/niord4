/*
 * Copyright 2026 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.dictionary;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The dictionary keys Java reads, and what each reader does without its key (docs/code-data-config-plan.md §4.6).
 * The texts' counterpart of the templates the code names ({@code RequiredTemplates}, the plan's C4).
 *
 * <p>The Dictionaries page's delete confirmation names these readers, beside the script resources that name the
 * key. {@code RequiredTextsTest} holds the list to the code: a dictionary key a Java class reads by literal must be
 * listed here, and every key listed here must be read somewhere and, unless a country supplies it, exist in the base
 * product's English texts.
 *
 * <p>Pure: no Spring, no database.
 */
public final class RequiredTexts {

    private RequiredTexts() {
    }

    /** The Java code that reads a dictionary key */
    public enum Reader {
        /**
         * {@code MessageService.cancellationTitle}: the title of a cancellation warning. Without the key in the
         * message's language it takes the English text, and without that the built-in "Cancellation of {0}".
         */
        MESSAGE_CANCELLATION,
        /**
         * {@code FiringExerciseService}: the time description and subject of a generated firing exercise message.
         * Without the keys it uses its built-in English formats and subject.
         */
        FIRING_EXERCISES,
        /**
         * {@code S124Service.responsibleAgency}: the producing organisation of an S-100 exchange set. Without the
         * key it names the organisation setting. It runs only where SECOM builds exchange sets, so the
         * administration app leaves it out of the delete confirmation when {@code /rest/settings/web} says
         * {@code s124Enabled} is false. ({@link #MESSAGE_CANCELLATION} stays: the cancellation warning is
         * minted whatever {@code niord.s124.enabled} says.)
         */
        S124_AGENCY,
        /**
         * {@code MessageCommentRestService}: the subject of a comment mail. Without the key the subject is
         * "null" followed by the user's name.
         */
        COMMENT_MAIL,
        /**
         * {@code MailingListExecutionService.expandSubject}: the dates of a mailing-list mail's subject. Without the
         * keys the date format is null and the mail cannot be built.
         */
        MAILING_LIST_MAIL,
        /**
         * {@code AudioPromulgationService}, through {@code PositionAssembler}: the positions of a message's audio
         * text. Without the keys the audio text cannot be generated.
         */
        AUDIO_TEXT,
        /**
         * The template directive {@code DateIntervalDirective}, through {@code NavWarnDateFormatter}: date
         * intervals in templates. Without the keys the template fails to render.
         */
        DATE_INTERVAL_DIRECTIVE,
        /**
         * The template directive {@code FromToDateDirective}: from-to dates in templates. Without the keys it
         * prints nothing.
         */
        FROM_TO_DATE_DIRECTIVE,
        /**
         * The template directive {@code LatLonDirective}, through {@code PositionUtils.getAudioFormat}: positions
         * formatted for audio in templates. Without the keys it prints nothing.
         */
        LAT_LON_DIRECTIVE,
        /**
         * {@code IndexIncompleteNotice}: the line at the top of a printed message list and a mailing-list report made
         * while the free-text index is being filled after a start. Without the key it prints a built-in English text.
         */
        INDEX_INCOMPLETE_NOTICE,
        /**
         * {@code MessageSearchCriteriaText}: the criteria line on the front page of a printed message list. Without
         * the key of a criterion's name it prints the base product's English word, without the key of a value the
         * value as it is stored, and without the date format the dates as day-month-year in digits.
         */
        SEARCH_CRITERIA,
        /**
         * {@code MessageExportService}: the label of each message part on the preview page of a message export.
         * Without the key in the export's language it takes the default language's, and without that the page's
         * own English word.
         */
        MESSAGE_EXPORT
    }

    /**
     * A dictionary key Java reads.
     *
     * @param dictionary      the dictionary
     * @param key             the key
     * @param countrySupplied true when the base product has no value and a country supplies it (§4.4)
     * @param readers         the code that reads it
     */
    public record JavaText(String dictionary, String key, boolean countrySupplied, Set<Reader> readers) {

        private static JavaText of(String dictionary, String key, Reader first, Reader... rest) {
            return new JavaText(dictionary, key, false, Set.copyOf(EnumSet.of(first, rest)));
        }
    }

    /** The dictionary keys Java reads */
    public static final List<JavaText> JAVA_TEXTS = List.of(
            JavaText.of("message", "msg.cancellation.title", Reader.MESSAGE_CANCELLATION),
            JavaText.of("message", "msg.time.date_time_format", Reader.FIRING_EXERCISES, Reader.FROM_TO_DATE_DIRECTIVE),
            JavaText.of("message", "msg.time.time_format", Reader.FIRING_EXERCISES),
            JavaText.of("message", "msg.firing_exercises.subject", Reader.FIRING_EXERCISES),
            JavaText.of("message", "msg.time.until_further_notice", Reader.FROM_TO_DATE_DIRECTIVE),
            new JavaText("message", "msg.responsible.agency", true, Set.of(Reader.S124_AGENCY)),
            JavaText.of("mail", "mail.comment.subject", Reader.COMMENT_MAIL),
            JavaText.of("mail", "mail.date_format.short", Reader.MAILING_LIST_MAIL),
            JavaText.of("mail", "mail.date_format.long", Reader.MAILING_LIST_MAIL),
            JavaText.of("template", "position.deg", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "position.min", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "position.north", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "position.south", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "position.east", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "position.west", Reader.AUDIO_TEXT, Reader.LAT_LON_DIRECTIVE),
            JavaText.of("template", "navwarn.date.day_suffix", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.html_day_suffix", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.until_further_notice", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.from", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.to", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.until", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.day_prefix", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.time_prefix", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "navwarn.date.time_interval_prefix", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("template", "term.and", Reader.DATE_INTERVAL_DIRECTIVE),
            JavaText.of("pdf", "pdf.list.index_incomplete", Reader.INDEX_INCOMPLETE_NOTICE),
            JavaText.of("web", "menu.filter.text", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.domain", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.reference", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.date", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.user", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.comments", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.status", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.type", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.kind", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.messageSeries", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.area", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.category", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.chart", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.tag", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.publication", Reader.SEARCH_CRITERIA),
            JavaText.of("web", "menu.filter.promulgationType", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.DRAFT", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.VERIFIED", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.PUBLISHED", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.EXPIRED", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.CANCELLED", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.status.DELETED", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.PERMANENT_NOTICE", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.TEMPORARY_NOTICE", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.PRELIMINARY_NOTICE", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.MISCELLANEOUS_NOTICE", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.COASTAL_WARNING", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.SUBAREA_WARNING", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.NAVAREA_WARNING", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.type.LOCAL_WARNING", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.kind.WARNING", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.kind.CANCELLATION", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.publish_date", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.publish_from_date", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.event_dates", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.created_date", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.updated_date", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.time.date_format", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.nw", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.nm", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.main_type", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.reference_levels", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.publish_to_date", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.user.CREATED_BY", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.user.UPDATED_BY", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.user.LAST_UPDATED_BY", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.comments.ANY", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.comments.OWN", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.comments.ANY_UNACK", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.comments.OWN_UNACK", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.selection", Reader.SEARCH_CRITERIA),
            JavaText.of("pdf", "pdf.list.criteria.map_area", Reader.SEARCH_CRITERIA),
            JavaText.of("message", "msg.field.details", Reader.MESSAGE_EXPORT),
            JavaText.of("message", "msg.field.time", Reader.MESSAGE_EXPORT),
            JavaText.of("message", "msg.field.positions", Reader.MESSAGE_EXPORT),
            JavaText.of("message", "msg.field.note", Reader.MESSAGE_EXPORT),
            JavaText.of("message", "msg.field.prohibition", Reader.MESSAGE_EXPORT),
            JavaText.of("message", "msg.field.signals", Reader.MESSAGE_EXPORT));

    /**
     * The key prefixes templates complete at runtime, such as {@code text("msg.status." + msg.status)}
     * ({@code message-support.ftl}): no template names such a key in full.
     */
    public static final List<String> RUNTIME_PREFIXES = List.of("lang.", "msg.status.", "msg.type.", "msg.field.");

    /**
     * Returns the Java code that reads the key of the dictionary, in the order of {@link Reader}
     *
     * @param dictionary the dictionary
     * @param key        the key
     * @return the readers; empty when no Java code reads the key from that dictionary
     */
    public static Set<Reader> readers(String dictionary, String key) {
        Set<Reader> readers = EnumSet.noneOf(Reader.class);
        JAVA_TEXTS.stream()
                .filter(t -> t.dictionary().equals(dictionary) && t.key().equals(key))
                .forEach(t -> readers.addAll(t.readers()));
        return readers;
    }

    /**
     * Returns the runtime prefix the key starts with
     *
     * @param key the key
     * @return the prefix, or null
     */
    public static String runtimePrefix(String key) {
        return RUNTIME_PREFIXES.stream().filter(key::startsWith).findFirst().orElse(null);
    }
}
