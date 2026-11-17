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

import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.user.Roles;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for managing message series.
 */
@RestController
@RequestMapping("/rest/message-series")
@Transactional
@PermitAll
public class MessageSeriesRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageSeriesRestService.class);

    private final MessageSeriesService messageSeriesService;

    MessageSeriesRestService(MessageSeriesService messageSeriesService) {
        this.messageSeriesService = messageSeriesService;
    }

    /** Returns all message series */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<SystemMessageSeriesVo> getAllMessageSeries(
            @RequestParam(name = "messageNumbers", defaultValue = "false") boolean messageNumbers) {
        List<SystemMessageSeriesVo> messageSeries = new ArrayList<>();
        for (MessageSeries series : messageSeriesService.getAllMessageSeries()) {
            SystemMessageSeriesVo vo = series.toVo(SystemMessageSeriesVo.class);
            if (messageNumbers) {
                // The next number in the series' current year, read in the zone of its domain as publishing
                // numbers it, not in the JVM's
                int year = messageSeriesService.currentYear(series);
                vo.setNextMessageNumber(messageSeriesService.getNextMessageNumber(series.getSeriesId(), year));
            }
            messageSeries.add(vo);
        }
        return messageSeries;
    }

    /** Returns the message series with the given comma-separated ID's */
    @GetMapping(value = "/search/{seriesIds}", produces = "application/json;charset=UTF-8")
    public List<SystemMessageSeriesVo> getMessageSeries(
            @PathVariable("seriesIds") String seriesIds,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {

        return messageSeriesService.findByIds(seriesIds.split(",")).stream()
                .limit(limit)
                .map(ms -> ms.toVo(SystemMessageSeriesVo.class))
                .collect(Collectors.toList());
    }

    /** Searches message series based on the given search parameters */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<SystemMessageSeriesVo> searchMessageSeries(
            @RequestParam(name = "name", defaultValue = "") String name,
            @RequestParam(name = "domain", defaultValue = "false") String domainId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return messageSeriesService.searchMessageSeries(name, domainId, limit).stream()
                .map(ms -> ms.toVo(SystemMessageSeriesVo.class))
                .collect(Collectors.toList());
    }

    /** Creates a new message series */
    @PostMapping(value = {"/series", "/series/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public SystemMessageSeriesVo createMessageSeries(@RequestBody SystemMessageSeriesVo seriesVo) throws Exception {
        log.info("Creating message series " + seriesVo);
        return messageSeriesService.createMessageSeries(new MessageSeries(seriesVo)).toVo(SystemMessageSeriesVo.class);
    }

    /** Updates an existing message series */
    @PutMapping(value = "/series/{seriesId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public SystemMessageSeriesVo updateMessageSeries(@PathVariable("seriesId") String seriesId, @RequestBody SystemMessageSeriesVo seriesVo) throws Exception {
        if (!Objects.equals(seriesId, seriesVo.getSeriesId())) {
            throw new NiordWebException(400);
        }

        log.info("Updating message series " + seriesVo);
        return messageSeriesService.updateMessageSeries(new MessageSeries(seriesVo)).toVo(SystemMessageSeriesVo.class);
    }

    /** Deletes an existing message series */
    @DeleteMapping("/series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void deleteMessageSeries(@PathVariable("seriesId") String seriesId) throws Exception {
        log.info("Deleting message series " + seriesId);
        messageSeriesService.deleteMessageSeries(seriesId);
    }

    /** Returns the next message series number for the given year */
    @GetMapping(value = "/series/{seriesIds}/number/{year}", produces = "application/json;charset=UTF-8")
    public Integer getNextMessageSeriesNumber(
            @PathVariable("seriesIds") String seriesIds,
            @PathVariable("year") int year) {
        return messageSeriesService.getNextMessageNumber(seriesIds, year);
    }

    /** Sets the next message series number for the given year */
    @PutMapping(value = "/series/{seriesIds}/number/{year}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public Integer setNextMessageSeriesNumber(
            @PathVariable("seriesIds") String seriesIds,
            @PathVariable("year") int year,
            @RequestBody(required = false) Integer nextNumber) {
        if (nextNumber == null) {
            throw new NiordWebException("The next number is missing", 400);
        }
        log.info("Updating next number for message series " + seriesIds + " to " + nextNumber);
        messageSeriesService.setNextMessageNumber(seriesIds, year, nextNumber);
        return nextNumber;
    }

    /** Computes the next message series number for the given year */
    @GetMapping(value = "/series/{seriesIds}/compute-number/{year}", produces = "application/json;charset=UTF-8")
    public Integer computeNextMessageSeriesNumber(
            @PathVariable("seriesIds") String seriesIds,
            @PathVariable("year") int year) {
        return messageSeriesService.computeNextMessageNumber(seriesIds, year);
    }
}
