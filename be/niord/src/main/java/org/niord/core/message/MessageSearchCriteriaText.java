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
package org.niord.core.message;

import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.Envelope;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageSearchParams.CommentsType;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.message.MessageSearchParams.UserType;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.util.PositionFormatter;
import org.niord.model.ILocalizable;
import org.niord.model.ILocalizedDesc;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Function;

/**
 * The criteria line of a printed message list: what the search asked for, in the print's language, for the front
 * page of the message-list report (the template prints it as {@code searchCriteria}).
 * <p>
 * Each criterion is a name and its values. The names are the texts of the editors' message filter ({@code web}
 * dictionary, {@code menu.filter.*}) where the filter has one, else texts of the {@code pdf} dictionary
 * ({@code pdf.list.criteria.*}); a name whose text the dictionary lacks is the base product's English word. A value
 * of a closed set (a status, a type, a main type, a kind, a date type, a user type, a comments choice) is its text,
 * and a value without a text is printed as it is stored. An area, a category, a tag, a publication, a promulgation
 * type and the domain are printed by their names in the print's language, else in the default language, else the
 * first name they have, else their id; a message series, a chart and a reference by their ids, which are what an
 * editor types and reads. The values of a set are in the order of their closed set, or alphabetical.
 * <p>
 * The language of the search is not printed: the print is in the language it names.
 */
@Service
public class MessageSearchCriteriaText {

    /** Between two criteria */
    static final String CRITERIA_SEPARATOR = "; ";

    /** Between two values of a criterion */
    static final String VALUE_SEPARATOR = ", ";

    /** A text of a dictionary */
    record Text(String dictionary, String key) {
    }

    /** The name of a criterion: its text, and the base product's English value for a dictionary without the key */
    enum Name {
        QUERY("web", "menu.filter.text", "Text"),
        DOMAIN("web", "menu.filter.domain", "Domain"),
        REFERENCE("web", "menu.filter.reference", "Reference"),
        REFERENCE_LEVELS("pdf", "pdf.list.criteria.reference_levels", "Reference levels"),
        DATE("web", "menu.filter.date", "Date"),
        UPDATED("message", "msg.field.updated_date", "Last updated"),
        USER("web", "menu.filter.user", "User"),
        COMMENTS("web", "menu.filter.comments", "Comments"),
        STATUS("web", "menu.filter.status", "Status"),
        TYPE("web", "menu.filter.type", "Type"),
        MAIN_TYPE("pdf", "pdf.list.criteria.main_type", "Main type"),
        KIND("web", "menu.filter.kind", "Kind"),
        SERIES("web", "menu.filter.messageSeries", "Message Series"),
        AREA("web", "menu.filter.area", "Area"),
        CATEGORY("web", "menu.filter.category", "Category"),
        CHART("web", "menu.filter.chart", "Chart"),
        TAG("web", "menu.filter.tag", "Tag"),
        PUBLICATION("web", "menu.filter.publication", "Publication"),
        PROMULGATION_TYPE("web", "menu.filter.promulgationType", "Promulgation"),
        MAP_AREA("pdf", "pdf.list.criteria.map_area", "Map area");

        final Text text;
        final String builtIn;

        Name(String dictionary, String key, String builtIn) {
            this.text = new Text(dictionary, key);
            this.builtIn = builtIn;
        }
    }

    static final Map<Status, Text> STATUS_TEXTS = Map.of(
            Status.DRAFT, new Text("message", "msg.status.DRAFT"),
            Status.VERIFIED, new Text("message", "msg.status.VERIFIED"),
            Status.PUBLISHED, new Text("message", "msg.status.PUBLISHED"),
            Status.EXPIRED, new Text("message", "msg.status.EXPIRED"),
            Status.CANCELLED, new Text("message", "msg.status.CANCELLED"),
            Status.DELETED, new Text("message", "msg.status.DELETED"));

    static final Map<Type, Text> TYPE_TEXTS = Map.of(
            Type.PERMANENT_NOTICE, new Text("message", "msg.type.PERMANENT_NOTICE"),
            Type.TEMPORARY_NOTICE, new Text("message", "msg.type.TEMPORARY_NOTICE"),
            Type.PRELIMINARY_NOTICE, new Text("message", "msg.type.PRELIMINARY_NOTICE"),
            Type.MISCELLANEOUS_NOTICE, new Text("message", "msg.type.MISCELLANEOUS_NOTICE"),
            Type.COASTAL_WARNING, new Text("message", "msg.type.COASTAL_WARNING"),
            Type.SUBAREA_WARNING, new Text("message", "msg.type.SUBAREA_WARNING"),
            Type.NAVAREA_WARNING, new Text("message", "msg.type.NAVAREA_WARNING"),
            Type.LOCAL_WARNING, new Text("message", "msg.type.LOCAL_WARNING"));

    static final Map<MainType, Text> MAIN_TYPE_TEXTS = Map.of(
            MainType.NW, new Text("pdf", "pdf.nw"),
            MainType.NM, new Text("pdf", "pdf.nm"));

    /** A bulletin has no text: no filter offers it, and it is printed as it is stored */
    static final Map<MessageKind, Text> KIND_TEXTS = Map.of(
            MessageKind.WARNING, new Text("message", "msg.kind.WARNING"),
            MessageKind.CANCELLATION, new Text("message", "msg.kind.CANCELLATION"));

    static final Map<DateType, Text> DATE_TYPE_TEXTS = Map.of(
            DateType.PUBLISH_DATE, new Text("message", "msg.field.publish_date"),
            DateType.PUBLISH_FROM_DATE, new Text("message", "msg.field.publish_from_date"),
            DateType.PUBLISH_TO_DATE, new Text("pdf", "pdf.list.criteria.publish_to_date"),
            DateType.EVENT_DATE, new Text("message", "msg.field.event_dates"),
            DateType.CREATED_DATE, new Text("message", "msg.field.created_date"),
            DateType.UPDATED_DATE, new Text("message", "msg.field.updated_date"));

    static final Map<UserType, Text> USER_TYPE_TEXTS = Map.of(
            UserType.CREATED_BY, new Text("pdf", "pdf.list.criteria.user.CREATED_BY"),
            UserType.UPDATED_BY, new Text("pdf", "pdf.list.criteria.user.UPDATED_BY"),
            UserType.LAST_UPDATED_BY, new Text("pdf", "pdf.list.criteria.user.LAST_UPDATED_BY"));

    static final Map<CommentsType, Text> COMMENTS_TEXTS = Map.of(
            CommentsType.ANY, new Text("pdf", "pdf.list.criteria.comments.ANY"),
            CommentsType.OWN, new Text("pdf", "pdf.list.criteria.comments.OWN"),
            CommentsType.ANY_UNACK, new Text("pdf", "pdf.list.criteria.comments.ANY_UNACK"),
            CommentsType.OWN_UNACK, new Text("pdf", "pdf.list.criteria.comments.OWN_UNACK"));

    /** The value of a temporary tag, the one a print of the selected messages makes */
    static final Text SELECTION_TEXT = new Text("pdf", "pdf.list.criteria.selection");

    /** The format of a date, by the language's date text */
    static final Text DATE_FORMAT_TEXT = new Text("message", "msg.time.date_format");

    @Inject
    NiordApp app;

    @Inject
    DictionaryService dictionaryService;

    @Inject
    DomainService domainService;

    @Inject
    AreaService areaService;

    @Inject
    CategoryService categoryService;

    @Inject
    MessageTagService messageTagService;

    @Inject
    PublicationResolver publicationResolver;

    @Inject
    PromulgationTypeService promulgationTypeService;

    /**
     * Returns the criteria line of the search, in the given language
     *
     * @param params   the search, as it was run
     * @param language the language of the print
     * @param timeZone the zone the print is dated in, which its dates are printed in
     * @return the criteria line, empty when the search names no criterion
     */
    @Transactional(readOnly = true)
    public String describe(MessageSearchParams params, String language, String timeZone) {
        Locale locale = StringUtils.isBlank(language) ? Locale.ROOT : Locale.of(language);
        List<String> criteria = new ArrayList<>();

        if (StringUtils.isNotBlank(params.getQuery())) {
            add(criteria, Name.QUERY, language, List.of("'" + params.getQuery() + "'"));
        }
        if (StringUtils.isNotBlank(params.getDomain())) {
            add(criteria, Name.DOMAIN, language, List.of(domainName(params.getDomain())));
        }
        if (StringUtils.isNotBlank(params.getMessageId())) {
            add(criteria, Name.REFERENCE, language, List.of(params.getMessageId()));
        }
        if (params.getReferenceLevels() != null) {
            add(criteria, Name.REFERENCE_LEVELS, language, List.of(String.valueOf(params.getReferenceLevels())));
        }
        if (params.getFrom() != null || params.getTo() != null) {
            List<String> date = new ArrayList<>();
            if (params.getDateType() != null) {
                date.add(value(DATE_TYPE_TEXTS, params.getDateType(), language));
            }
            date.add(interval(params.getFrom(), params.getTo(), language, locale, timeZone));
            add(criteria, Name.DATE, language, date);
        }
        if (params.getUpdatedFrom() != null || params.getUpdatedTo() != null) {
            add(criteria, Name.UPDATED, language,
                    List.of(interval(params.getUpdatedFrom(), params.getUpdatedTo(), language, locale, timeZone)));
        }
        if (StringUtils.isNotBlank(params.getUsername()) || params.getUserType() != null) {
            String user = params.getUserType() == null ? params.getUsername()
                    : StringUtils.isBlank(params.getUsername()) ? value(USER_TYPE_TEXTS, params.getUserType(), language)
                    : value(USER_TYPE_TEXTS, params.getUserType(), language) + " " + params.getUsername();
            add(criteria, Name.USER, language, List.of(user));
        }
        if (params.getCommentsType() != null) {
            add(criteria, Name.COMMENTS, language, List.of(value(COMMENTS_TEXTS, params.getCommentsType(), language)));
        }
        add(criteria, Name.STATUS, language, values(STATUS_TEXTS, params.getStatuses(), language));
        add(criteria, Name.TYPE, language, values(TYPE_TEXTS, params.getTypes(), language));
        add(criteria, Name.MAIN_TYPE, language, values(MAIN_TYPE_TEXTS, params.getMainTypes(), language));
        add(criteria, Name.KIND, language, values(KIND_TEXTS, params.getKinds(), language));
        add(criteria, Name.SERIES, language, sorted(params.getSeriesIds(), Function.identity(), locale));
        add(criteria, Name.AREA, language, sorted(params.getAreaIds(), id -> areaName(id, language), locale));
        add(criteria, Name.CATEGORY, language,
                sorted(params.getCategoryIds(), id -> categoryName(id, language), locale));
        add(criteria, Name.CHART, language, sorted(params.getChartNumbers(), Function.identity(), locale));
        add(criteria, Name.TAG, language, sorted(params.getTags(), id -> tagName(id, language), locale));
        add(criteria, Name.PUBLICATION, language,
                sorted(params.getPublications(), id -> publicationName(id, language), locale));
        add(criteria, Name.PROMULGATION_TYPE, language,
                sorted(params.getPromulgationTypes(), this::promulgationTypeName, locale));
        if (params.getExtent() != null && !params.getExtent().isEmpty()) {
            add(criteria, Name.MAP_AREA, language, List.of(extent(params.getExtent().getEnvelopeInternal(), locale)));
        }

        return String.join(CRITERIA_SEPARATOR, criteria);
    }

    /** Adds the criterion with the given values, unless it has none */
    private void add(List<String> criteria, Name name, String language, List<String> values) {
        if (!values.isEmpty()) {
            criteria.add(text(name.text, language, name.builtIn) + ": " + String.join(VALUE_SEPARATOR, values));
        }
    }

    /** Returns the text in the language, else the given value */
    private String text(Text text, String language, String otherwise) {
        String value = dictionaryService.value(text.dictionary(), language, text.key());
        return StringUtils.isBlank(value) ? otherwise : value;
    }

    /** Returns the text of a value of a closed set, else the value as it is stored */
    private <E extends Enum<E>> String value(Map<E, Text> texts, E value, String language) {
        Text text = texts.get(value);
        return text == null ? value.name() : text(text, language, value.name());
    }

    /** Returns the texts of the values, in the order of their closed set */
    private <E extends Enum<E>> List<String> values(Map<E, Text> texts, Collection<E> values, String language) {
        return values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .map(v -> value(texts, v, language))
                .toList();
    }

    /** Returns the names of the ids, in alphabetical order */
    private static List<String> sorted(Collection<String> ids, Function<String, String> name, Locale locale) {
        Collator collator = Collator.getInstance(locale);
        return ids.stream()
                .filter(StringUtils::isNotBlank)
                .map(name)
                .sorted(collator)
                .toList();
    }

    /** Returns the from-to dates by the language's date text, in the print's zone; an open end is left empty */
    private String interval(Date from, Date to, String language, Locale locale, String timeZone) {
        DateFormat format;
        try {
            format = new SimpleDateFormat(text(DATE_FORMAT_TEXT, language, MessageSearchParams.DATE_FORMAT), locale);
        } catch (IllegalArgumentException e) {
            format = new SimpleDateFormat(MessageSearchParams.DATE_FORMAT, locale);
        }
        if (StringUtils.isNotBlank(timeZone)) {
            format.setTimeZone(TimeZone.getTimeZone(timeZone));
        }
        return (from == null ? "" : format.format(from) + " ") + "-" + (to == null ? "" : " " + format.format(to));
    }

    /** Returns the south-west and the north-east corner of the extent */
    private static String extent(Envelope envelope, Locale locale) {
        return PositionFormatter.format(locale, PositionFormatter.LATLON_DEC_1, envelope.getMinY(), envelope.getMinX())
                + " - "
                + PositionFormatter.format(locale, PositionFormatter.LATLON_DEC_1, envelope.getMaxY(), envelope.getMaxX());
    }

    private String domainName(String domainId) {
        return domainService.getDomains(true, false).stream()
                .filter(d -> domainId.equals(d.getDomainId()))
                .map(Domain::getName)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(domainId);
    }

    private String areaName(String areaId, String language) {
        Area area = areaService.findByAreaId(areaId);
        return area == null ? areaId : name(area, language, d -> d.getName(), areaId);
    }

    private String categoryName(String categoryId, String language) {
        Category category = categoryService.findByCategoryId(categoryId);
        return category == null ? categoryId : name(category, language, d -> d.getName(), categoryId);
    }

    private String tagName(String tagId, String language) {
        MessageTag tag = messageTagService.findTag(tagId);
        if (tag == null) {
            return tagId;
        }
        if (tag.getType() == MessageTagType.TEMP) {
            return text(SELECTION_TEXT, language, tagId);
        }
        return StringUtils.isBlank(tag.getName()) ? tagId : tag.getName();
    }

    private String publicationName(String publicationId, String language) {
        PublicationIssue issue = publicationResolver.findIssue(publicationId);
        if (issue != null) {
            String name = name(issue, language, d -> d.getName(), null);
            if (name == null && issue.getSeries() != null) {
                name = name(issue.getSeries(), language, d -> d.getName(), null);
            }
            return name == null ? publicationId : name;
        }
        Publication publication = publicationResolver.findLegacy(publicationId);
        return publication == null ? publicationId : name(publication, language, d -> d.getTitle(), publicationId);
    }

    private String promulgationTypeName(String typeId) {
        PromulgationType type = promulgationTypeService.getPromulgationType(typeId);
        return type == null || StringUtils.isBlank(type.getName()) ? typeId : type.getName();
    }

    /**
     * Returns the entity's name in the language, else in the default language, else its first name, else the given
     * value
     */
    private <D extends ILocalizedDesc> String name(ILocalizable<D> entity, String language, Function<D, String> name,
                                                   String otherwise) {
        if (entity.getDescs() == null) {
            return otherwise;
        }
        List<D> descs = new ArrayList<>();
        for (String lang : new String[] { language, app.getDefaultLanguage() }) {
            D desc = entity.getDesc(lang);
            if (desc != null) {
                descs.add(desc);
            }
        }
        descs.addAll(entity.getDescs());
        return descs.stream()
                .map(name)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(otherwise);
    }
}
