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

package org.niord.web;

import org.niord.core.domain.DomainService;
import org.niord.core.message.*;
import org.niord.core.schedule.FiringExerciseService;
import org.niord.core.schedule.FiringSchedule;
import org.niord.core.schedule.FiringScheduleService;
import org.niord.core.schedule.vo.FiringAreaPeriodsVo;
import org.niord.core.schedule.vo.FiringScheduleVo;
import org.niord.core.user.Roles;
import org.niord.model.IJsonSerializable;
import org.niord.model.message.MessageVo;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST interface for accessing and updating firing schedules.
 */
@RestController
@RequestMapping("/rest/firing-schedules")
@Transactional
@PermitAll
public class FiringScheduleRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FiringScheduleRestService.class);

    private final FiringScheduleService firingScheduleService;

    private final FiringExerciseService firingExerciseService;

    private final DomainService domainService;

    private final MessageSeriesService messageSeriesService;

    private final MessageTagService messageTagService;

    FiringScheduleRestService(FiringScheduleService firingScheduleService, FiringExerciseService firingExerciseService, DomainService domainService, MessageSeriesService messageSeriesService, MessageTagService messageTagService) {
        this.firingScheduleService = firingScheduleService;
        this.firingExerciseService = firingExerciseService;
        this.domainService = domainService;
        this.messageSeriesService = messageSeriesService;
        this.messageTagService = messageTagService;
    }

    /***************************************/
    /** Firing Schedules                  **/
    /***************************************/

    /**
     * Returns all firing schedules
     * @return all firing schedules
     */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<FiringScheduleVo> getFiringSchedules() {
        return firingScheduleService.getFiringSchedules().stream()
                .map(FiringSchedule::toVo)
                .collect(Collectors.toList());
    }

    /**
     * Creates a new firing schedule based on the schedule template
     * @param schedule the firing schedule to create
     * @return the created firing schedule
     */
    @PostMapping(value = {"/firing-schedule", "/firing-schedule/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public FiringScheduleVo createFiringSchedule(@RequestBody FiringScheduleVo schedule) {

        // The service names an unknown domain or series (400) and a domain that already has a schedule (409)
        if (schedule.getDomain() == null) {
            throw new NiordWebException("A firing schedule must name its domain", 400);
        }
        if (schedule.getTargetDomain() == null) {
            throw new NiordWebException("A firing schedule must name its target domain", 400);
        }
        checkScheduleDays(schedule);

        log.info("Create new firing schedule " + schedule);
        return firingScheduleService.createFiringSchedule(new FiringSchedule(schedule)).toVo();
    }

    /**
     * Updates the firing schedule data from the schedule template
     * @param schedule the firing schedule to update
     * @return the updated firing schedule
     */
    @PutMapping(value = "/firing-schedule/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public FiringScheduleVo updateFiringSchedule(@PathVariable("id") Integer id, @RequestBody FiringScheduleVo schedule) {
        if (!Objects.equals(id, schedule.getId())) {
            throw new NiordWebException(400);
        }
        checkScheduleDays(schedule);

        log.info("Updating firing schedule " + schedule);
        return firingScheduleService.updateFiringSchedule(new FiringSchedule(schedule)).toVo();
    }

    /**
     * Refuses schedule days below 1. Empty is allowed: the schedule then uses the firingExerciseScheduleDays setting.
     * A value below 1 would end the run's window before today, so the next run would generate nothing and cancel
     * every current firing exercise of the schedule. A fraction reaches this point already cut to a whole number by
     * the JSON reader; the editor's form refuses it.
     * @param schedule the firing schedule to check
     */
    private static void checkScheduleDays(FiringScheduleVo schedule) {
        if (schedule.getScheduleDays() != null && schedule.getScheduleDays() < 1) {
            throw new NiordWebException("The schedule days must be 1 or more", 400);
        }
    }

    /**
     * Deletes the firing schedule
     * @param id the ID of the firing schedule to delete
     */
    @DeleteMapping("/firing-schedule/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void deleteFiringSchedule(@PathVariable("id") Integer id) {

        log.info("Updating firing schedule " + id);
        firingScheduleService.deleteFiringSchedule(id);
    }

    /**
     * Updates the firing exercises based on the currently active firing schedules: the hourly run, run now. The
     * call answers when the run has finished; a run of the hourly job or of another press on the same schedule is
     * waited for.
     *
     * @return what the run left undone: the failed schedules, areas and cancellations, all 0 when it did everything
     */
    @PutMapping(value = "/update-firing-exercises", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public FiringExerciseService.FiringRunResult updateFiringExercises() {
        log.info("Updating firing exercises based on active schedules");
        // null: every active schedule
        return firingExerciseService.updateFiringExercises(null);
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
    @GetMapping(value = "/search-firing-area-periods", produces = "application/json;charset=UTF-8")
    public List<FiringAreaPeriodsVo> searchFiringAreaPeriods(
            @RequestParam(name = "date", required = false) Long date,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "area", required = false) Set<Integer> areaIds,
            @RequestParam(name = "inactive", defaultValue = "false") Boolean inactive,
            @RequestParam(name = "lang", required = false) String lang) {

        Date searchDate = date != null ? new Date(date) : new Date();
        return firingScheduleService.searchFiringAreaPeriods(searchDate, query, areaIds, inactive, lang);
    }

    /**
     * Updates the firing area periods, i.e. the list of firing periods for an area at the given date
     * @param firingAreaPeriods the firing area periods to update
     * @param date the date to update the firing periods for
     * @param lang the language to filter areas by
     * @return the updated firing area periods
     */
    @PutMapping(value = "/firing-area-periods",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public FiringAreaPeriodsVo updateFiringAreaPeriodsForDate(
            @RequestParam(name = "date", required = false) Long date,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestBody FiringAreaPeriodsVo firingAreaPeriods) {

        if (date == null) {
            throw new NiordWebException("The date must be specified", 400);
        }
        if (firingAreaPeriods.getArea() == null || firingAreaPeriods.getArea().getId() == null) {
            throw new NiordWebException("The firing area must be specified", 400);
        }
        if (firingAreaPeriods.getFiringPeriods() == null) {
            // Missing or null: an update replaces the day's periods, so only an explicit list, even an
            // empty one ("no periods this day"), may delete them
            throw new NiordWebException("The firing periods must be specified (an empty list for no periods this day)", 400);
        }

        log.info("Updating firing area periods for area " + firingAreaPeriods.getArea().getId());
        return firingScheduleService.updateFiringAreaPeriodsForDate(firingAreaPeriods, new Date(date), lang);
    }

    /***************************************/
    /** Firing Area Template Messages     **/
    /***************************************/

    /**
     * Generates a firing area message template for all active firing areas
     * @param params the parameters
     * @return the generated firing area messages
     */
    @PostMapping(value = "/generate-firing-area-messages",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public List<MessageVo> generateFiringAreaMessages(@RequestBody FiringAreaMessageParams params) {

        // Validate the the message series belong to the current domain
        if (!domainService.currentDomain().containsMessageSeries(params.getSeriesId())) {
            throw new NiordWebException(403);
        }

        MessageSeries messageSeries = messageSeriesService.findBySeriesId(params.getSeriesId());
        MessageTag tag = messageTagService.findTag(domainService.currentDomain(), params.getTagId());

        log.info("Generating firing area message templates. Message Series: "
                + params.getSeriesId() + ". Message Tag: " + params.getTagId());
        return firingScheduleService.generateFiringAreaMessages(messageSeries, tag).stream()
                .map(m -> m.toVo(MessageVo.class, Message.MESSAGE_DETAILS_FILTER))
                .collect(Collectors.toList());
    }

    /**
     * Helper class used when generating firing area template messages
     */
    @SuppressWarnings("unused")
    public static class FiringAreaMessageParams implements IJsonSerializable {
        String seriesId;
        String tagId;

        public String getSeriesId() {
            return seriesId;
        }

        public void setSeriesId(String seriesId) {
            this.seriesId = seriesId;
        }

        public String getTagId() {
            return tagId;
        }

        public void setTagId(String tagId) {
            this.tagId = tagId;
        }
    }
}
