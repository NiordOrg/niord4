/*
 * Copyright 2016 Danish Maritime Authority.
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

package org.niord.core.publication;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.domain.Domain;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.util.TimeUtils;

import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.niord.core.message.vo.MessageTagVo.MessageTagType.PUBLIC;
import static org.niord.core.publication.Publication.DEFAULT_EDITION;

/**
 * Helper class used when updating a publication from its template.
 * An instance of this class is only valid within a single transaction.
 * <p>
 * Primarily, the class has utility methods for expanding the parameters
 * of a value with the format ${week}, ${year}, etc.
 * <p>
 * The class also sports a function for looking up or creating a message tag to associate with the publication.
 */
public class PublicationTemplateUpdateCtx {

    final Publication publication;
    final MessageTagService messageTagService;
    final Map<String, String> replacementValues = new HashMap<>();

    /** The values of a text that names a week: ${year} and ${year-2-digits} are the ISO week-based year */
    final Map<String, String> weekYearReplacementValues = new HashMap<>();

    /**
     * Constructor. The date values are read in the zone of the publication's domain (the template's domain when the
     * template has one, as {@link Publication#updateFromTemplate} takes it over), else in the JVM's zone.
     **/
    public PublicationTemplateUpdateCtx(Publication publication, MessageTagService messageTagService) {
        this.publication = publication;
        this.messageTagService = messageTagService;

        String edition = publication.getEdition() != null
                ? String.valueOf(publication.getEdition())
                : String.valueOf(DEFAULT_EDITION);
        replacementValues.put("${edition}", edition);

        Date date = publication.getPublishDateFrom();
        Integer weekYear = null;
        if (date != null) {
            ZoneId zone = zone(publication);
            int year = TimeUtils.year(date, zone);
            int week = TimeUtils.isoWeek(date, zone);
            int month = date.toInstant().atZone(zone).getMonthValue();
            weekYear = TimeUtils.isoWeekBasedYear(date, zone);

            replacementValues.put("${year-2-digits}", String.valueOf(year).substring(2));
            replacementValues.put("${year}", String.valueOf(year));
            replacementValues.put("${week}", String.valueOf(week));
            replacementValues.put("${week-2-digits}", String.format("%02d", week));
            replacementValues.put("${month}", String.valueOf(month));
            replacementValues.put("${month-2-digits}", String.format("%02d", month));
        }

        weekYearReplacementValues.putAll(replacementValues);
        if (weekYear != null) {
            weekYearReplacementValues.put("${year-2-digits}", String.valueOf(weekYear).substring(2));
            weekYearReplacementValues.put("${year}", String.valueOf(weekYear));
        }
    }

    /** Returns the zone the date values are read in: the domain's the publication takes, else the JVM's */
    private static ZoneId zone(Publication publication) {
        Publication template = publication.getTemplate();
        Domain domain = template != null && template.getDomain() != null
                ? template.getDomain()
                : publication.getDomain();
        return TimeUtils.zoneOrDefault(domain != null ? domain.getTimeZone() : null);
    }

    /** Returns if the text names a week, ${week} or ${week-2-digits} */
    private static boolean namesAWeek(String text) {
        return text != null && text.contains("${week");
    }

    /**
     * Finds or creates a public tag with the given name
     * @param name the tag name
     * @return the tag
     */
    @Transactional
    public MessageTag findOrCreatePublicMessageTag(String name) {
        List<MessageTag> tags = messageTagService.findTagsByTypeAndName(PUBLIC, name);
        if (tags.isEmpty()) {
            MessageTag tag = new MessageTag();
            tag.checkAssignTagId();
            tag.setName(name);
            tag.setType(PUBLIC);
            tag.setLocked(false);
            return messageTagService.saveEntity(tag);
        } else {
            return tags.getFirst();
        }
    }

    /** Returns the value, or the default value if the value is null **/
    public <D> D val(D value, D defaultValue) {
        return value != null  ? value : defaultValue;
    }

    /**
     * Expand the parameters of a value with the format ${week}, ${year}, etc.
     * A value that names a week pairs it with the ISO week-based year: "${week}/${year}" on 1 January 2027 is
     * "53/2026". A value that names no week keeps the calendar year.
     * @param value the value to expand parameters for
     * @return the updated value
     */
    public String str(String value, String defaultValue) {
        return expand(value, defaultValue, namesAWeek(value));
    }

    /**
     * Expands a set of values that belong together, such as a template's report parameters, where the week and
     * the year are separate values printed side by side: when any of them names a week, ${year} is the ISO
     * week-based year in all of them. A value is expanded as {@link #str(String, String)} does, with "" as the
     * default.
     * @param values the values to expand
     * @return the expanded values, by the same keys
     */
    public Map<String, Object> strAll(Map<String, Object> values) {
        boolean weekYear = values.values().stream().anyMatch(v -> v != null && namesAWeek(v.toString()));
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, expand(value.toString(), "", weekYear)));
        return result;
    }

    /** Expands the value with the week-based year or the calendar year, or answers the default for a blank value */
    private String expand(String value, String defaultValue, boolean weekYear) {
        Map<String, String> values = weekYear ? weekYearReplacementValues : replacementValues;
        if (!values.isEmpty() && StringUtils.isNotBlank(value)) {
            for (Map.Entry<String, String> kv : values.entrySet()) {
                value = value.replace(kv.getKey(), kv.getValue());
            }
        } else if (StringUtils.isNotBlank(defaultValue)) {
            value = defaultValue;
        }
        return value;
    }

}
