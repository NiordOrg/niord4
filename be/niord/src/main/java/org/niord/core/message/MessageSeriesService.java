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
package org.niord.core.message;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.sequence.DefaultSequence;
import org.niord.core.sequence.Sequence;
import org.niord.core.sequence.SequenceService;
import org.niord.core.service.BaseService;
import org.niord.core.util.TimeUtils;
import org.niord.core.web.NiordWebException;
import org.niord.model.message.MainType;
import org.niord.model.message.Type;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import jakarta.persistence.FlushModeType;
import org.springframework.transaction.annotation.Transactional;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business interface for managing message series
 */
@Service
public class MessageSeriesService extends BaseService {

    public static final String NUMBER_SEQUENCE_TYPE_YEARLY = "MESSAGE_SERIES_NUMBER_%s_%d";
    public static final String NUMBER_SEQUENCE_TYPE_CONTINUOUS = "MESSAGE_SERIES_NUMBER_%s";

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageSeriesService.class);

    @Inject
    SequenceService sequenceService;

    @Inject
    DomainLookupService domainLookupService;

    @Inject
    NiordApp app;

    /**
     * Returns the message series with the given series identifier
     * @param seriesId the series identifier
     * @return the message series with the given series identifier or null if not found
     */
    public MessageSeries findBySeriesId(String seriesId) {
        try {
            return em.createNamedQuery("MessageSeries.findBySeriesId", MessageSeries.class)
                    .setParameter("seriesId", seriesId)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns all message series with the given series IDs
     * @param seriesIds the series IDs of the message series to look up
     * @return the list of all message series with the given series IDs
     */
    public List<MessageSeries> findByIds(String... seriesIds) {
        Set<String> serIds = new HashSet<>(Arrays.asList(seriesIds));
        return em.createNamedQuery("MessageSeries.findBySeriesIds", MessageSeries.class)
                .setParameter("seriesIds", serIds)
                .getResultList();
    }

    /**
     * Returns a list of persisted message series based on a list of template message series
     * @param series the list of message series to look up persisted series for
     * @return the list of corresponding persisted series
     */
    public List<MessageSeries> persistedMessageSeries(List<MessageSeries> series) {
        return series.stream()
                .map(ms -> findBySeriesId(ms.getSeriesId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns all message series
     * @return the list of all message series
     */
    public List<MessageSeries> getAllMessageSeries() {
        return getAll(MessageSeries.class);
    }

    /**
     * Returns the persisted message series matching the given template series id.
     * If the message series does not exist, create it.
     * @param templateSeries the message series
     * @return the persisted message series matching the given template series id or a newly created series
     */
    public MessageSeries findOrCreateMessageSeries(MessageSeries templateSeries) {
        // Sanity checks
        if (templateSeries == null || StringUtils.isBlank(templateSeries.getSeriesId())) {
            return null;
        }

        MessageSeries series = findBySeriesId(templateSeries.getSeriesId());
        if (series == null) {
            series = createMessageSeries(templateSeries);
        }

        return series;
    }

    /**
     * Searches for message series matching the given term
     *
     * @param term the search term
     * @param domainId  if defined restrict the search to the given domain
     * @param limit the maximum number of results
     * @return the search result
     */
    public List<MessageSeries> searchMessageSeries(String term, String domainId, int limit) {

        // Optionally, filter by the domains associated with the current domain
        Domain d = (domainId != null) ? domainLookupService.findByDomainId(domainId) : null;
        Set<String> domainSeriesIds = d == null
                ? null
                : d.getMessageSeries().stream()
                    .map(MessageSeries::getSeriesId)
                    .collect(Collectors.toSet());

        if (StringUtils.isNotBlank(term)) {
            return em
                    .createNamedQuery("MessageSeries.searchMessageSeries", MessageSeries.class)
                    .setParameter("term", "%" + term + "%")
                    .setMaxResults(limit)
                    .getResultList()
                    .stream()
                    .filter(s -> domainSeriesIds == null || domainSeriesIds.contains(s.getSeriesId()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Creates a new message series from the given template
     * @param series the new message series
     * @return the persisted message series
     */
    @Transactional
    public MessageSeries createMessageSeries(MessageSeries series) {
        // The required fields are refused with a sentence (400), not left to the database's NOT NULL (500)
        if (StringUtils.isBlank(series.getSeriesId())) {
            throw new IllegalArgumentException("A message series needs a series id");
        }
        if (series.getMainType() == null) {
            throw new IllegalArgumentException("Message series " + series.getSeriesId()
                    + " needs a main type (NW or NM)");
        }
        if (series.getNumberSequenceType() == null) {
            throw new IllegalArgumentException("Message series " + series.getSeriesId()
                    + " needs a number sequence type");
        }
        MessageSeries original = findBySeriesId(series.getSeriesId());
        if (original != null) {
            throw new IllegalArgumentException("Cannot create message series with duplicate message series IDs"
                    + series.getSeriesId());
        }

        log.info("Creating new message series " + series.getSeriesId());
        return saveEntity(series);
    }

    /**
     * Updates an existing message series from the given template
     * @param series the message series to update
     * @return the persisted message series
     */
    @Transactional
    public MessageSeries updateMessageSeries(MessageSeries series) {
        MessageSeries original = findBySeriesId(series.getSeriesId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update message series non-existing message series IDs"
                    + series.getSeriesId());
        }

        original.setMainType(series.getMainType());
        original.getTypes().clear();
        original.getTypes().addAll(series.getTypes());
        original.setShortFormat(series.getShortFormat());
        original.setNavtexFormat(series.getNavtexFormat());
        original.setNumberSequenceType(series.getNumberSequenceType());
        original.getEditorFields().clear();
        original.getEditorFields().addAll(series.getEditorFields());
        original.setExcludeFromMessageIndex(series.getExcludeFromMessageIndex());

        log.info("Updating message series " + series.getSeriesId());
        return saveEntity(original);
    }

    /**
     * Deletes the message series with the given Series ID.
     * <p>
     * A series in use is refused with a 409: one that has messages, that a domain lists, that a firing schedule
     * targets, or that has an S-124 configuration. The detail names the uses, so the administrator knows what to take
     * out of use first. The foreign keys to a series are NO ACTION, so without this check the delete fails at the
     * flush and answers 500 with no reason.
     *
     * @param seriesId the message series to delete
     * @return if the message series was deleted
     * @noinspection all
     */
    @Transactional
    public boolean deleteMessageSeries(String seriesId) {

        MessageSeries original = findBySeriesId(seriesId);
        if (original != null) {
            refuseDeleteOfSeriesInUse(original);
            log.info("Removing message series " + seriesId);
            remove(original);
            return true;
        }
        return false;
    }

    /**
     * Refuses the delete of a message series that is in use
     *
     * @param series the message series to delete
     */
    private void refuseDeleteOfSeriesInUse(MessageSeries series) {
        long messages = em.createQuery("SELECT count(m) FROM Message m WHERE m.messageSeries = :series", Long.class)
                .setParameter("series", series)
                .getSingleResult();
        List<String> domains = em.createQuery("SELECT DISTINCT d.domainId FROM Domain d JOIN d.messageSeries s "
                        + "WHERE s = :series ORDER BY d.domainId", String.class)
                .setParameter("series", series)
                .getResultList();
        long schedules = em.createQuery("SELECT count(fs) FROM FiringSchedule fs WHERE fs.targetMessageSeries = :series",
                        Long.class)
                .setParameter("series", series)
                .getSingleResult();
        long s124Configs = em.createQuery("SELECT count(c) FROM S124SeriesConfig c WHERE c.messageSeries = :series",
                        Long.class)
                .setParameter("series", series)
                .getSingleResult();

        List<String> uses = new ArrayList<>();
        if (messages > 0) {
            uses.add(messages + (messages == 1 ? " message" : " messages"));
        }
        if (!domains.isEmpty()) {
            uses.add((domains.size() == 1 ? "the domain " : "the domains ") + String.join(", ", domains));
        }
        if (schedules > 0) {
            uses.add(schedules + (schedules == 1 ? " firing schedule" : " firing schedules"));
        }
        if (s124Configs > 0) {
            uses.add("its S-124 configuration");
        }
        if (!uses.isEmpty()) {
            throw new NiordWebException("The message series is in use: " + String.join("; ", uses)
                    + ". Take it out of use first.", 409);
        }
    }

    /****************************/
    /** Message ID Generation  **/
    /****************************/

    /**
     * Returns the message series with the given ID, after checking that it exists and that the year is "sensible"
     * (1700-2100). Every year endpoint of a series (the next number, its reset and the computed next number) makes
     * this check, so an unknown series or a year out of range is a 400 on all three alike.
     *
     * @param seriesId the message series ID
     * @param year the year
     * @return the message series
     */
    private MessageSeries requireSeriesAndYear(String seriesId, int year) {

        // Validate that seriesId designates a valid messaage series
        MessageSeries messageSeries = findBySeriesId(seriesId);
        if (messageSeries == null) {
            throw new IllegalArgumentException("Invalid message series " + seriesId);
        }

        // Check that the year is "sensible"
        if (year < 1700 || year > 2100) {
            throw new IllegalArgumentException("Invalid year " + year);
        }
        return messageSeries;
    }

    /**
     * Returns the sequence associated with the given message series and year
     * @param seriesId the message series ID
     * @param year the year
     * @return the sequence associated with the given message series and year
     */
    private Sequence sequenceForSeries(String seriesId, int year, int initialValue) {

        MessageSeries messageSeries = requireSeriesAndYear(seriesId, year);

        String sequenceKey
                = messageSeries.getNumberSequenceType() == NumberSequenceType.CONTINUOUS
                ? String.format(NUMBER_SEQUENCE_TYPE_CONTINUOUS, seriesId)
                : String.format(NUMBER_SEQUENCE_TYPE_YEARLY, seriesId, year);

        return new DefaultSequence(sequenceKey, initialValue);
    }

    /**
     * Returns (but does not bump) the next message number for the given message series and year
     *
     * @param seriesId the message series ID
     * @param year      the year
     * @return the next series identifier number
     */
    public Integer getNextMessageNumber(String seriesId, int year) {
        Sequence sequence = sequenceForSeries(seriesId, year, 1);
        return (int) sequenceService.peekNextValue(sequence);
    }

    /**
     * Resets the next message number for the given message series and year, in the caller's transaction.
     * <p>
     * For a series that numbers its warnings itself (YEARLY or CONTINUOUS) a number below the one
     * {@link #computeNextMessageNumber(String, int)} gives is refused: a message of the series (in the year, or in any
     * year for a CONTINUOUS series) already has it, and the next publication would take it again (SERIES-RESET). The
     * series' number row is locked first, as a publication and a cancellation lock it before anything else, so the
     * check waits for a publication that holds the number and then reads the number it took; no publication can
     * take a number between the check and the reset. A series and year with no numbered message accept any number of
     * 0 or more, as before.
     *
     * @param seriesId the message series ID
     * @param year      the year
     * @param nextNumber the next number for the message series
     * @throws IllegalArgumentException for a number below 0, or below one already used (400 at the REST endpoint)
     */
    public void setNextMessageNumber(String seriesId, int year, int nextNumber) {
        // The next-number popover's own lower bound: a negative sequence would number published warnings from it
        if (nextNumber < 0) {
            throw new IllegalArgumentException("The next number must be 0 or more, was " + nextNumber);
        }
        MessageSeries series = requireSeriesAndYear(seriesId, year);
        if (series.isAutoNumbered()) {
            // The number row first: the same lock order as publish, cancel and the auto-publish sweep
            sequenceService.lockNextValue(sequenceForSeries(seriesId, year, 1));
            int firstFree = computeNextMessageNumber(seriesId, year);
            // 1 is what Compute gives a series and year with no numbered message: then 0 stays possible
            if (firstFree > 1 && nextNumber < firstFree) {
                throw new IllegalArgumentException("The next number " + nextNumber + " of message series " + seriesId
                        + " is below a number already used: the next free number is " + firstFree);
            }
        }
        sequenceService.resetNextValue(sequenceForSeries(seriesId, year, nextNumber));
    }

    /**
     * Computes (but does not update) the next message number for the given message series and year
     * by looking at the numbers already assigned.
     * <p>
     * A continuous message series numbers on across the years, so for such a series the year is ignored
     * and all the numbers of the series are looked at. Every other series counts the messages published in the
     * year, read in the zone of the series' domain.
     *
     * @param seriesId the message series ID
     * @param year      the year: checked as every year endpoint checks it, then ignored for a continuous
     *                  message series
     * @return the computed next series identifier number
     */
    public Integer computeNextMessageNumber(String seriesId, int year) {
        MessageSeries series = requireSeriesAndYear(seriesId, year);
        if (series.getNumberSequenceType() == NumberSequenceType.CONTINUOUS) {
            return em.createNamedQuery("Message.maxNumberInSeries", Integer.class)
                    .setParameter("series", series)
                    .getSingleResult() + 1;
        }
        // The year in the zone of the series' domain, as publishing numbers it, not the JVM's
        ZoneId zone = seriesZone(series);
        Date fromDate = Date.from(LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant());
        Date toDate = Date.from(LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().minusMillis(1));
        return em.createNamedQuery("Message.maxNumberInPeriod", Integer.class)
                .setParameter("series", series)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getSingleResult() + 1;
    }

    /**
     * Creates a new message number specific for the given message series and year, inside the caller's transaction
     * (the publication's). Two publications in one series and year take turns: the second waits until the first
     * commits or rolls back. A publication that rolls back gives its number back.
     *
     * @param seriesId the message series ID
     * @param year      the year
     * @return the new series identifier number
     */
    public Integer newMessageNumber(String seriesId, int year) {
        Sequence sequence = sequenceForSeries(seriesId, year, 1);
        return (int) sequenceService.takeNextValue(sequence);
    }

    /**
     * Locks, until the caller's transaction ends, the number sequence a message of the given series published at the
     * given date takes its number from, without taking a number. Does nothing for a series that is not
     * auto-numbered.
     * <p>
     * A status change that will take a number only after it has changed other rows (a cancellation, which changes the
     * cancelled message and the recording publications' tags before it numbers its cancellation warning) calls this
     * first, so the series' number is the first row it locks, as it is for a publication: the two then take turns
     * instead of each waiting for a row the other holds.
     *
     * @param series      the message series
     * @param publishDate the publish date of the message that will be numbered
     */
    public void lockMessageNumber(MessageSeries series, Date publishDate) {
        if (series == null || !series.isAutoNumbered() || publishDate == null) {
            return;
        }
        int year = TimeUtils.year(publishDate, seriesZone(series));
        sequenceService.lockNextValue(sequenceForSeries(series.getSeriesId(), year, 1));
    }

    /**
     * Updates the message with (optionally) a new message number and a short ID
     * specified by the associated message series
     *
     * @param message the message to (optionally) update with a new message number and short ID
     * @param assignMessageNumber whether to assign a new message number or not
     */
    public void updateMessageIdsFromMessageSeries(Message message, boolean assignMessageNumber) {

        MessageSeries messageSeries = message.getMessageSeries();
        if (messageSeries == null) {
            throw new IllegalArgumentException("Message series must be specified");
        }

        // The year, the number sequence and the ${year} and ${week} tokens follow the publish date in the zone of
        // the series' domain, not the JVM's
        ZoneId zone = seriesZone(messageSeries);

        // First, update the message series number and short ID
        NumberSequenceType type = messageSeries.getNumberSequenceType();
        if (type == NumberSequenceType.YEARLY || type == NumberSequenceType.CONTINUOUS) {

            Date publishDate = message.getPublishDateFrom();

            if (publishDate == null) {
                throw new IllegalArgumentException("Publish date must be specified");
            }
            int year = message.checkUpdateYear(zone);

            // If requested assign a new message number
            if (assignMessageNumber && message.getNumber() == null) {
                message.setNumber(newMessageNumber(messageSeries.getSeriesId(), year));
            }

            // Assign the short ID by resolving the short ID format of the message series
            FormatResolver formatResolver = new FormatResolver(message, app, zone);
            message.setShortId(formatResolver.resolveFormat(messageSeries.getShortFormat()));

        } else {

            // A series without automatic numbers: the year follows the publish date in the same zone
            if (message.getPublishDateFrom() != null) {
                message.checkUpdateYear(zone);
            }

            if (type == NumberSequenceType.MANUAL) {

                // If the message number is defined and a shortFormat is defined, generate a shortId
                if (message.getNumber() != null && StringUtils.isNotBlank(messageSeries.getShortFormat())) {
                    FormatResolver formatResolver = new FormatResolver(message, app, zone);
                    message.setShortId(formatResolver.resolveFormat(messageSeries.getShortFormat()));
                }

                // If the short ID is assigned manually, check validity
                if (StringUtils.isBlank(message.getShortId())) {
                    throw new IllegalArgumentException("Message must be assigned a short ID");
                }
            }
        }

    }

    /**
     * Returns the zone a message of the series is numbered in: the time zone of the domain that holds the series.
     * A series in no domain, in a domain whose zone cannot be read, or in several domains whose zones differ
     * falls back to the JVM's zone. A domain without a zone counts as the JVM's zone, as {@link Domain#timeZone()}
     * does.
     *
     * @param series the message series
     * @return the zone of the series
     */
    public ZoneId seriesZone(MessageSeries series) {
        ZoneId jvmZone = TimeZone.getDefault().toZoneId();
        if (series == null || StringUtils.isBlank(series.getSeriesId())) {
            return jvmZone;
        }
        List<String> zoneIds = em.createQuery(
                        "select distinct d.timeZone from Domain d join d.messageSeries s where s.seriesId = :seriesId",
                        String.class)
                .setParameter("seriesId", series.getSeriesId())
                .setFlushMode(FlushModeType.COMMIT)
                .getResultList();

        Set<ZoneId> zones = new HashSet<>();
        for (String zoneId : zoneIds) {
            if (StringUtils.isBlank(zoneId)) {
                zones.add(jvmZone);
                continue;
            }
            try {
                zones.add(ZoneId.of(zoneId.trim()));
            } catch (DateTimeException e) {
                log.debug("Message series {}: a domain's time zone '{}' cannot be read; the JVM's zone is used",
                        series.getSeriesId(), zoneId);
                return jvmZone;
            }
        }
        if (zones.size() == 1) {
            return zones.iterator().next();
        }
        log.debug("Message series {} is in {} domain zones {}; the JVM's zone is used",
                series.getSeriesId(), zones.size(), zones);
        return jvmZone;
    }

    /**
     * Returns the year it is now in the zone the series is numbered in ({@link #seriesZone(MessageSeries)}), the
     * year a message published now would take its number in. Not the JVM's year: in the first hours of 1 January
     * in a zone east of UTC, the two differ.
     *
     * @param series the message series
     * @return the current year of the series
     */
    public int currentYear(MessageSeries series) {
        return currentYear(series, Instant.now());
    }

    /**
     * Returns the year of the given instant in the zone the series is numbered in. {@link #currentYear(MessageSeries)}
     * calls it with the current instant; a test passes a fixed one.
     *
     * @param series the message series
     * @param now    the instant to read the year of
     * @return the year of the instant in the series' zone
     */
    int currentYear(MessageSeries series, Instant now) {
        return yearAt(now, seriesZone(series));
    }

    /**
     * Returns the year of the given instant in the given zone
     *
     * @param instant the instant
     * @param zone    the zone
     * @return the year of the instant in the zone
     */
    public static int yearAt(Instant instant, ZoneId zone) {
        return instant.atZone(zone).getYear();
    }

    /**
     * Utility class that will resolve a format string containing tokens such as
     * "${year}", "${number-3-digits}", etc.
     * <p>
     * ${week} is the ISO-8601 week, so in a format that names a week (${week} or ${week-2-digits}) ${year} and
     * ${year-2-digits} are the ISO week-based year: 1 January 2027 is "53/2026", not "53/2027". A format
     * that names no week keeps the calendar year.
     */
    public static class FormatResolver {

        private final Map<String, String> params = new HashMap<>();

        /** The tokens of a format that names a week: the year tokens hold the ISO week-based year */
        private final Map<String, String> weekYearParams = new HashMap<>();

        /** Constructor: reads ${year} and ${week} in the JVM's zone **/
        public FormatResolver(Message message, NiordApp app) {
            this(message, app, null);
        }

        /**
         * Constructor
         * @param message the message
         * @param app the application
         * @param zone the zone ${year} and ${week} are read in (the series' domain zone), or null for the JVM's
         */
        public FormatResolver(Message message, NiordApp app, ZoneId zone) {

            Date publishDate = message.getPublishDateFrom();
            if (publishDate == null) {
                throw new IllegalArgumentException("Publish date must be specified");
            }
            ZoneId z = zone != null ? zone : TimeZone.getDefault().toZoneId();
            int year = TimeUtils.year(publishDate, z);
            int week = TimeUtils.isoWeek(publishDate, z);
            MainType mainType = message.getMainType() != null ? message.getMainType() : message.getType().getMainType();

            params.put("${year-2-digits}", String.valueOf(year).substring(2));
            params.put("${year}", String.valueOf(year));
            params.put("${week}", String.format("%02d", week));
            params.put("${week-2-digits}", String.format("%02d", week));
            params.put("${number}", message.getNumber() == null ? "" : String.valueOf(message.getNumber()));
            params.put("${number-3-digits}", message.getNumber() == null ? "" : String.format("%03d", message.getNumber()));
            params.put("${main-type}", mainType.toString());
            params.put("${main-type-lower}", mainType.toString().toLowerCase());
            params.put("${country}", app.getCountry());
            params.put("${id}", message.getId() == null ? "" : String.valueOf(message.getId()));
            params.put("${legacy-id}", message.getLegacyId() == null ? "" : String.valueOf(message.getLegacyId()));
            params.put("${t-or-p}", getNmTOrPToken(message));

            int weekYear = TimeUtils.isoWeekBasedYear(publishDate, z);
            weekYearParams.putAll(params);
            weekYearParams.put("${year-2-digits}", String.valueOf(weekYear).substring(2));
            weekYearParams.put("${year}", String.valueOf(weekYear));
        }

        /**
         * Resolves the format by expanding tokens such as "${year}", "${number-3-digits}", etc.
         * In a format that names a week, the year tokens are the ISO week-based year.
         * @param format the format to expand
         * @return the result
         */
        public String resolveFormat(String format) {

            String result = format;
            if (StringUtils.isNotBlank(format)) {
                Map<String, String> tokens = format.contains("${week") ? weekYearParams : params;
                for (Map.Entry<String, String> e : tokens.entrySet()) {
                    result = result.replace(e.getKey(), e.getValue()).trim();
                }
            }
            return result;
        }

        /** Returns the NM T or P token to be used in the short ID for the message */
        private String getNmTOrPToken(Message message) {
            if (message.getType() == Type.TEMPORARY_NOTICE) {
                return "(T)";
            } else if (message.getType() == Type.PRELIMINARY_NOTICE) {
                return "(P)";
            }
            return "";
        }
    }
}
