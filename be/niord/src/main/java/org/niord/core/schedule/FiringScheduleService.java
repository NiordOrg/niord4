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

package org.niord.core.schedule;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.*;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.message.*;
import org.niord.core.model.BaseEntity;
import org.niord.core.schedule.vo.FiringAreaPeriodsVo;
import org.niord.core.service.BaseService;
import org.niord.core.util.TimeUtils;
import org.niord.model.DataFilter;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.slf4j.Logger;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.niord.core.area.AreaSearchParams.TREE_SORT_ORDER;
import static org.niord.model.message.MainType.NM;
import static org.niord.model.search.PagedSearchParamsVo.SortOrder.ASC;

/**
 * Business interface for accessing Niord firing areas
 */
@Service
@SuppressWarnings("unused")
public class FiringScheduleService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FiringScheduleService.class);

    private final MessageService messageService;

    private final AreaService areaService;

    private final ChartService chartService;

    private final CategoryService categoryService;

    private final DomainService domainService;

    private final DomainLookupService domainLookupService;

    private final MessageSeriesService messageSeriesService;

    FiringScheduleService(MessageService messageService, AreaService areaService, ChartService chartService, CategoryService categoryService, DomainService domainService, DomainLookupService domainLookupService, MessageSeriesService messageSeriesService) {
        this.messageService = messageService;
        this.areaService = areaService;
        this.chartService = chartService;
        this.categoryService = categoryService;
        this.domainService = domainService;
        this.domainLookupService = domainLookupService;
        this.messageSeriesService = messageSeriesService;
    }

    /***************************************/
    /** Firing Schedules                  **/
    /***************************************/

    /**
     * Returns all firing schedules
     *
     * @return all firing schedules
     */
    public List<FiringSchedule> getFiringSchedules() {
        return getAll(FiringSchedule.class);
    }

    /**
     * Returns the firing schedule with the given ID, or null if not found
     *
     * @param id the ID
     * @return the firing schedule with the given ID, or null if not found
     */
    public FiringSchedule findById(Integer id) {
        return getByPrimaryKey(FiringSchedule.class, id);
    }

    /**
     * Creates a new firing schedule based on the schedule template
     *
     * @param schedule the firing schedule to create
     * @return the created firing schedule
     */
    @Transactional
    public FiringSchedule createFiringSchedule(FiringSchedule schedule) {
        if (schedule.isPersisted()) {
            throw new IllegalArgumentException("Cannot create schedule with existing ID " + schedule.getId());
        }

        // Substitute the message series with the persisted ones
        String seriesId = schedule.getTargetMessageSeries() == null ? null : schedule.getTargetMessageSeries().getSeriesId();
        MessageSeries targetSeries = StringUtils.isBlank(seriesId) ? null : messageSeriesService.findBySeriesId(seriesId);
        if (targetSeries == null) {
            throw new NiordWebException(StringUtils.isBlank(seriesId)
                    ? "A firing schedule must name its target message series"
                    : "No message series " + seriesId, 400);
        }
        schedule.setTargetMessageSeries(targetSeries);

        // Substitute the target domain with the persisted ones
        schedule.setTargetDomain(persistedDomain(schedule.getTargetDomain(), "target domain"));

        // Substitute the domain with the persisted ones
        Domain domain = persistedDomain(schedule.getDomain(), "domain");
        if (domain.getFiringSchedule() != null) {
            throw new NiordWebException("The domain " + domain.getDomainId() + " already has a firing schedule", 409);
        }
        schedule.setDomain(domain);
        domain.setFiringSchedule(schedule);

        schedule = saveEntity(schedule);

        return schedule;
    }

    /**
     * Returns the persisted domain that the given template names
     *
     * @param template the domain template, which names the domain by its domain id
     * @param role     what the domain is to the schedule, for the message
     * @return the persisted domain
     * @throws NiordWebException (400) when the template names no domain, or one that does not exist
     */
    private Domain persistedDomain(Domain template, String role) {
        String domainId = template == null ? null : template.getDomainId();
        Domain domain = StringUtils.isBlank(domainId) ? null : domainLookupService.findByDomainId(domainId);
        if (domain == null) {
            throw new NiordWebException(StringUtils.isBlank(domainId)
                    ? "A firing schedule must name its " + role
                    : "No domain " + domainId, 400);
        }
        return domain;
    }

    /**
     * Updates the firing schedule data from the schedule template
     *
     * @param schedule the firing schedule to update
     * @return the updated firing schedule
     */
    @Transactional
    public FiringSchedule updateFiringSchedule(FiringSchedule schedule) {
        FiringSchedule original = findById(schedule.getId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing schedule " + schedule.getId());
        }

        // Substitute the domain with the persisted ones
        Domain domain = domainLookupService.findByDomainId(schedule.getDomain().getDomainId());
        if (!Objects.equals(original.getDomain().getDomainId(), domain.getDomainId())) {
            original.getDomain().setFiringSchedule(null);
        }
        original.setDomain(domain);
        domain.setFiringSchedule(original);

        // Substitute the target domain with the persisted ones
        original.setTargetDomain(domainLookupService.findByDomainId(schedule.getTargetDomain().getDomainId()));

        // Substitute the message series with the persisted ones
        original.setTargetMessageSeries(messageSeriesService.findBySeriesId(schedule.getTargetMessageSeries().getSeriesId()));

        original.setScheduleDays(schedule.getScheduleDays());
        original.setActive(schedule.isActive());

        return saveEntity(original);
    }

    /**
     * Deletes the firing schedule
     *
     * @param id the ID of the firing schedule to delete
     * @noinspection all
     */
    @Transactional
    public boolean deleteFiringSchedule(Integer id) {

        FiringSchedule schedule = findById(id);
        if (schedule != null) {
            schedule.getDomain().setFiringSchedule(null);
            remove(schedule);
            return true;
        }
        return false;
    }

    /***************************************/
    /** Firing Area Periods               **/
    /***************************************/

    /**
     * Fetches the firing area periods, i.e. all firing areas and their firing periods that matches the parameters
     *
     * @param date the date
     * @param query a search query
     * @param areaIds area subtrees to search
     * @param inactive whether to include inactive areas or not
     * @param lang the language to filter areas by
     * @return the firing area periods that matches the parameters
     */
    public List<FiringAreaPeriodsVo> searchFiringAreaPeriods(Date date, String query, Set<Integer> areaIds, boolean inactive, String lang) {

        Set<Boolean> activeSet = inactive
            ? new HashSet<>(Arrays.asList(TRUE, FALSE))
            : Collections.singleton(TRUE);

        // Adjust the date for the time-zone of the current domain
        Domain domain = domainService.currentDomain();
        TimeZone timeZone = domain != null ? domain.timeZone() : null;
        Date fromDate = TimeUtils.resetTime(date, timeZone);
        Date toDate = TimeUtils.endOfDay(date, timeZone);

        // The start of the day before, one calendar day back in the same zone (not 24 h), so a period stored with
        // its end before its start is listed on the next day when its end, moved one day on, falls on that day
        ZoneId zone = timeZone != null ? timeZone.toZoneId() : ZoneId.systemDefault();
        Date prevFromDate = Date.from(fromDate.toInstant().atZone(zone).toLocalDate().minusDays(1)
                .atStartOfDay(zone).toInstant());

        // Find the firing periods
        List<FiringPeriod> fps = em.createNamedQuery("FiringPeriod.findByDateInterval", FiringPeriod.class)
                .setParameter("active", activeSet)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .setParameter("prevFromDate", prevFromDate)
                .getResultList();

        // Look up all firing areas for the current domain
        DataFilter filter = DataFilter.get()
                .fields(DataFilter.PARENT, DataFilter.DETAILS, DataFilter.GEOMETRY)
                .lang(lang);

        AreaSearchParams param = new AreaSearchParams()
                .name(query)
                .domain(domain == null ? null : domain.getDomainId())
                .areaIds(areaIds)
                .inactive(inactive)
                .type(AreaType.FIRING_AREA);
        param.sortBy(TREE_SORT_ORDER).sortOrder(ASC);

        List<FiringAreaPeriodsVo> result = areaService.searchAreas(param).stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .map(FiringAreaPeriodsVo::new)
                .collect(Collectors.toList());

        Map<Integer, FiringAreaPeriodsVo> areaLookup = result.stream()
                .collect(Collectors.toMap(s -> s.getArea().getId(), Function.identity()));

        // Group the firing periods by area
        for (FiringPeriod fp : fps) {
            FiringAreaPeriodsVo firingAreaPeriods = areaLookup.get(fp.getArea().getId());
            if (firingAreaPeriods != null) {
                firingAreaPeriods.getFiringPeriods().add(fp.toVo());
            }
        }

        return result;
    }

    /**
     * Fetches the firing area periods, i.e. the area and its firing periods on the specified date, and null if not found
     * @param areaId the id of the area
     * @param date the date
     * @param lang the language to filter areas by
     * @return the firing area periods, and null if not found
     */
    public FiringAreaPeriodsVo getFiringAreaPeriods(Integer areaId, Date date, String lang) {

        // TODO: Not very efficient
        return searchFiringAreaPeriods(date, null, null, true, lang).stream()
                .filter(fap -> Objects.equals(fap.getArea().getId(), areaId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Updates the firing area periods, i.e. the list of firing periods for an area at the given date. A period of the
     * day that the body leaves out is deleted only when the body's loaded period ids name it, or when the body names
     * none (then the body replaces the day). A period of an earlier day that the body sends back unchanged is left as
     * stored
     * @param firingAreaPeriods the area to update
     * @param date the date to update the firing periods for
     * @param lang the language to filter areas by
     * @return the updated firing area periods
     */
    public FiringAreaPeriodsVo updateFiringAreaPeriodsForDate(FiringAreaPeriodsVo firingAreaPeriods, Date date, String lang) {

        // Fetch the current firing periods for the area
        FiringAreaPeriodsVo currentFiringPeriods = getFiringAreaPeriods(firingAreaPeriods.getArea().getId(), date, lang);
        if (currentFiringPeriods == null) {
            throw new NiordWebException("No firing area " + firingAreaPeriods.getArea().getId(), 400);
        }

        Area area = areaService.getAreaDetails(firingAreaPeriods.getArea().getId());
        List<FiringPeriod> updatedFps = firingAreaPeriods.getFiringPeriods().stream()
                .map(fp -> new FiringPeriod(area, fp))
                .collect(Collectors.toList());

        // Handle new firing periods
        updatedFps.stream()
                .filter(BaseEntity::isNew)
                .forEach(this::addFiringPeriod);

        // Handle updated firing periods. A period that starts before the day being saved is listed on it for its
        // hours on this day; the editor shows it locked and sends it back as stored. Sent back unchanged it is
        // left as stored, so a period stored with its end before its start, which is listed on the next day too
        // (L-103), does not make that day's save fail on a row that can only be corrected on its start day
        Domain domain = domainService.currentDomain();
        Date dayStart = TimeUtils.resetTime(date, domain != null ? domain.timeZone() : null);
        updatedFps.stream()
                .filter(BaseEntity::isPersisted)
                .filter(fp -> !isUnchangedPeriodOfAnEarlierDay(fp, dayStart))
                .forEach(this::updateFiringPeriod);

        // Create a look-up of all updated (i.e. existing firing periods)
        Set<Integer> updatedFpIds = updatedFps.stream()
                .filter(BaseEntity::isPersisted)
                .map(BaseEntity::getId)
                .collect(Collectors.toSet());

        // Handle deleted firing periods. A body that names the periods it loaded deletes only those of them it left
        // out, never a period it did not load, so a period another tab added after this one loaded is kept
        // (FIRING8); a body without them replaces the day's periods
        Set<Integer> loadedFpIds = firingAreaPeriods.getLoadedPeriodIds() == null
                ? null
                : new HashSet<>(firingAreaPeriods.getLoadedPeriodIds());
        currentFiringPeriods.getFiringPeriods().stream()
                .filter(fp -> !updatedFpIds.contains(fp.getId()))
                .filter(fp -> loadedFpIds == null || loadedFpIds.contains(fp.getId()))
                .forEach(fp -> deleteFiringPeriod(fp.getId()));

        // Return the updated firing area
        return getFiringAreaPeriods(area.getId(), date, lang);
    }

    /**
     * Returns whether the period sent back by a day's save is a stored period that starts before that day, with its
     * stored from and to unchanged
     * @param period the period sent back, with its id
     * @param dayStart the start of the day being saved
     * @return whether the period is a stored period of an earlier day, sent back unchanged
     */
    private boolean isUnchangedPeriodOfAnEarlierDay(FiringPeriod period, Date dayStart) {
        if (period.getFromDate() == null || period.getToDate() == null) {
            return false;
        }
        FiringPeriod stored = getByPrimaryKey(FiringPeriod.class, period.getId());
        return stored != null && stored.getFromDate().before(dayStart) && !stored.hasChanged(period);
    }

    /***************************************/
    /** Firing Periods                    **/
    /***************************************/

    /**
     * Returns the list of legacy firing periods
     * @return the list of legacy firing periods
     */
    public List<FiringPeriod> getAllLegacyFiringPeriods() {
        return em.createNamedQuery("FiringPeriod.findLegacyFiringPeriods", FiringPeriod.class)
                .getResultList();
    }

    /**
     * Creates a new firing period based on the given template
     * @param templatePeriod the template firing period
     * @return the persisted firing period
     */
    @Transactional
    public FiringPeriod addFiringPeriod(FiringPeriod templatePeriod) {
        // Validation
        if (templatePeriod.isPersisted()) {
            throw new IllegalArgumentException("Cannot create existing firing period");
        }
        if (!templatePeriod.firingPeriodDefined()) {
            throw new IllegalArgumentException("Firing period must defined area, from- and to-dates");
        }
        checkEndsAfterItStarts(templatePeriod);

        return saveEntity(templatePeriod);
    }

    /**
     * Updates firing period based on the given template
     * @param templatePeriod the template firing period
     * @return the persisted firing period
     */
    @Transactional
    public FiringPeriod updateFiringPeriod(FiringPeriod templatePeriod) {
        // Validation
        if (templatePeriod.isNew()) {
            throw new IllegalArgumentException("Cannot update non-existing firing period");
        }
        if (!templatePeriod.firingPeriodDefined()) {
            throw new IllegalArgumentException("Firing period must defined area, from- and to-dates");
        }
        checkEndsAfterItStarts(templatePeriod);

        FiringPeriod originalFp = getByPrimaryKey(FiringPeriod.class, templatePeriod.getId());
        originalFp.updateFiringPeriod(templatePeriod);

        return saveEntity(originalFp);
    }

    /**
     * Refuses a firing period that does not end after it starts. A period
     * "until midnight" ends at 00:00 of the next day; stored as 00:00 of its own day, its firing warning would expire
     * before the firing. The refusal is answered 400, and the request's transaction rolls back, so a day saved with
     * one such period changes nothing.
     *
     * @param period the period to check, whose area and dates are defined
     */
    private void checkEndsAfterItStarts(FiringPeriod period) {
        if (!period.endsAfterItStarts()) {
            throw new IllegalArgumentException("A firing period must end after it starts (area "
                    + period.getArea().getId() + ": " + period.getFromDate().toInstant() + " to "
                    + period.getToDate().toInstant() + "); to fire until midnight, end it at 00:00 of the next day");
        }
    }

    /**
     * Deletes the firing period with the given ID
     * @param id the firing period ID
     */
    @Transactional
    public boolean deleteFiringPeriod(Integer id) {

        FiringPeriod firingPeriod = getByPrimaryKey(FiringPeriod.class, id);
        if (firingPeriod != null) {
            remove(firingPeriod);
            return true;
        }
        return false;
    }

    /***************************************/
    /** Firing Area Template Messages     **/
    /***************************************/

    /**
     * Generates a firing area message template for all active firing areas.
     * @param messageSeries the message series to use
     * @param messageTag the message tag to assign the messages to
     * @return the list of newly created firing area message templates
     */
    @Transactional
    public List<Message> generateFiringAreaMessages(MessageSeries messageSeries, MessageTag messageTag) {

        // Search for active firing areas for the current domain
        Domain domain = domainService.currentDomain();
        AreaSearchParams param = new AreaSearchParams()
                .inactive(false)
                .domain(domain != null ? domain.getDomainId() : null)
                .type(AreaType.FIRING_AREA);
        param.sortBy(TREE_SORT_ORDER).sortOrder(ASC);

        List<Area> firingAreas = areaService.searchAreas(param);
        if (firingAreas.isEmpty()) {
            log.info("No active firing areas found - skip generating messages");
            return Collections.emptyList();
        }

        // Found by its exact English name (the bootstrap carries it, docs/code-data-config-plan.md §7.1); without
        // it the messages get no category, which an operator should hear about once, not once a message
        Category firingExercise = categoryService.getFiringExercisesCategory();
        if (firingExercise == null) {
            log.warn("No category named \"" + CategoryService.FIRING_EXERCISES_CATEGORY_NAME
                    + "\" (English) - the firing area messages are generated without a category");
        }

        List<Message> faMessages = new ArrayList<>();
        for (Area area : firingAreas) {

            Message message = new Message();
            message.setMessageSeries(messageSeries);
            message.setMainType(messageSeries.getMainType());
            message.setType(messageSeries.getMainType() == NM ? Type.MISCELLANEOUS_NOTICE : Type.LOCAL_WARNING);
            message.setStatus(Status.DRAFT);
            message.setShortId("FA-" + area.getDescs().get(0).getName().replace(" ", "-"));
            message.setAutoTitle(true);
            message.getAreas().add(area);

            MessagePart part = message.addPart(new MessagePart(MessagePartType.DETAILS));
            if (area.getGeometry() != null) {

                // Compute the charts for the message
                message.getCharts().addAll(chartService.getIntersectingCharts(area.getGeometry()));

                // Copy the area geometry to the message
                FeatureCollection featureCollection = new FeatureCollection();
                Feature feature = new Feature();
                featureCollection.addFeature(feature);
                feature.setGeometry(area.getGeometry());
                area.getDescs().stream()
                        .filter(desc -> StringUtils.isNotBlank(desc.getName()))
                        .forEach(desc -> feature.getProperties().put("name:" + desc.getLang(), desc.getName()));
                part.setGeometry(featureCollection);
            }

            // Update the title line
            for (AreaDesc desc : area.getDescs()) {
                message.checkCreateDesc(desc.getLang());
            }
            message.updateMessageTitle();

            // Categories
            if (firingExercise != null) {
                message.getCategories().add(firingExercise);
            }

            // Save the message
            message = messageService.saveMessage(message);

            // Add to message tag
            if (messageTag != null) {
                messageTag.getMessages().add(message);
                message.getTags().add(messageTag);
            }

            faMessages.add(message);
        }

        if (messageTag != null) {
            messageTag.updateMessageCount();
            saveEntity(messageTag);
        }

        return faMessages;
    }
}
