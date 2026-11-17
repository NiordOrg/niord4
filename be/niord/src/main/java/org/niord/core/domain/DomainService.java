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
package org.niord.core.domain;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.Area;
import org.niord.core.category.Category;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.service.BaseService;
import org.niord.core.user.TicketService;
import org.niord.core.user.CurrentUserContext;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

/**
 * Provides an interface for managing application domains
 */
@Service
@SuppressWarnings("unused")
public class DomainService extends BaseService {

    private final static ThreadLocal<String> THREAD_LOCAL_DOMAIN = new ThreadLocal<>();

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DomainService.class);

    @Inject
    KeycloakIntegrationService keycloakService;

    @Inject
    MessageSeriesService messageSeriesService;

    @Inject
    DomainLookupService domainLookupService;

    @Inject
    CurrentUserContext currentUserContext;

    @Inject
    TicketService ticketService;

    /**
     * Returns the current domain or null if none are set.
     * <p>
     * For a bearer-token caller the domain is the one the {@code NiordDomain} header names, falling back
     * to the domain of a resolved ticket. The header and the caller's roles on it arrive together: the
     * {@code NiordDomainFilter} adds only the roles the token holds on the domain the header names.
     * <p>
     * A caller whose only credential is a resolved ticket is different. Its roles come from the ticket,
     * and {@code TicketRestService} checked them against the domain the ticket was issued for, so that
     * domain is the only one they are valid in. For such a caller the ticket's domain is authoritative
     * and the header is ignored; otherwise a header naming another domain would pair that domain with
     * roles that were never checked there.
     */
    public Domain currentDomain() {

        TicketService.TicketData ticketData = ticketService.getTicketDataForCurrentThread();
        if (ticketData != null && currentUserContext.isAnonymous()) {
            return StringUtils.isNotBlank(ticketData.getDomain())
                    ? domainLookupService.findByDomainId(ticketData.getDomain())
                    : null;
        }

        Domain currentDomain = null;

        String domainId = THREAD_LOCAL_DOMAIN.get();
        if (StringUtils.isNotBlank(domainId)) {
            currentDomain = domainLookupService.findByDomainId(domainId);
        }

        if (currentDomain == null && ticketData != null) {
            // Fall back to the domain of a ticket resolved for the current thread
            currentDomain = domainLookupService.findByDomainId(ticketData.getDomain());
        }

        return currentDomain;
    }

    /**
     * Returns whether the given domain ID names a stored, active domain.
     * <p>
     * Used by the {@code NiordDomainFilter} before it adds the roles a token holds on the Keycloak client the
     * {@code NiordDomain} header names: roles on a client that is not a Niord domain, or on a domain that has
     * been deactivated or deleted, must not grant anything.
     *
     * @param domainId the domain ID
     * @return whether the domain exists and is active
     */
    public boolean isActiveDomain(String domainId) {
        if (StringUtils.isBlank(domainId)) {
            return false;
        }
        Domain domain = domainLookupService.findByDomainId(domainId);
        return domain != null && domain.isActive();
    }

    /**
     * May be called, e.g. from a servlet filter to set the
     * ticket for the current thread based on a request header or DNS name.
     * <p>
     * Must be followed up with a call to call to removeDomainForCurrentThread()
     * @param domainId the domain ID to set
     */
    public void setDomainForCurrentThread(String domainId) {
        if (StringUtils.isNotBlank(domainId)) {
            THREAD_LOCAL_DOMAIN.set(domainId);
        }
    }

    /**
     * May be called, e.g. from a servlet filter to remove the
     * current domain from the current thread
     * <p>
     * Must be preceded by a call to call to setDomainForCurrentThread()
     */
    public void removeDomainForCurrentThread() {
        THREAD_LOCAL_DOMAIN.remove();
    }

    /**
     * Returns all domains
     * @return all domains
     */
    public List<Domain> getDomains() {
        return getDomains(false, false);
    }

    /**
     * Returns all domains, optionally with the current Keycloak state
     * @param includeInactive whether to include inactive domains or not
     * @param keycloakState whether to load the state of the domains in Keycloak
     * @return all domains
     */
    public List<Domain> getDomains(boolean includeInactive, boolean keycloakState) {

        String query = includeInactive ? "Domain.findAll" : "Domain.findActive";
        List<Domain> domains = em.createNamedQuery(query, Domain.class)
                .getResultList();

        if (keycloakState) {
            try {
                Set<String> keycloakClients = keycloakService.getKeycloakDomainIds();
                domains.forEach(d -> d.setInKeycloak(keycloakClients.contains(d.getDomainId())));
            } catch (Exception e) {
                log.error("Failed loading Keycloak states for domains", e);
            }

        }
        return domains;
    }

    /**
     * Returns the domains where the "publish" flag has been set. These are the domains
     * that would by default be returned by default in public API's for fetching messages
     * @return the published domains
     */
    public List<Domain> getPublishedDomains() {
        return em.createNamedQuery("Domain.getPublishedDomains", Domain.class)
                .getResultList();
    }

    /**
     * Returns all domains for which the current user has the given role
     * @param role the role that the current user must have
     * @return all domains for which the current user has the given role
     */
    public List<Domain> domainsWithUserRole(@SuppressWarnings("all") String role) {
        Set<String> domainIds = currentUserContext.getKeycloakDomainIdsForRole(role);
        return getDomains().stream()
                .filter(d -> domainIds.contains(d.getDomainId()))
                .collect(Collectors.toList());
    }

    /**
     * Returns a list of persisted domains based on a list of template domains
     * @param domains the list of charts to look up persisted charts for
     * @return the list of corresponding persisted charts
     */
    public List<Domain> persistedDomains(List<Domain> domains) {
        return domains.stream()
                .map(c -> domainLookupService.findByDomainId(c.getDomainId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns the domain ids of a list of template domains that no row carries: those {@link #persistedDomains}
     * leaves out, in the list's order, each once
     *
     * @param domains the template domains, or null
     * @return the domain ids no row carries
     */
    public List<String> missingDomainIds(List<Domain> domains) {
        if (domains == null) {
            return List.of();
        }
        return domains.stream()
                .filter(Objects::nonNull)
                .map(Domain::getDomainId)
                .filter(id -> domainLookupService.findByDomainId(id) == null)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Returns whether a domain may carry the given time zone: none (blank, the server's zone is used), or one that
     * both {@code ZoneId.of} and {@code TimeZone.getTimeZone} read as it is written. The domain's zone is read with
     * {@code TimeZone.getTimeZone} ({@link Domain#timeZone()}, the templates' printed times), which turns any name it
     * does not know into GMT without a word, so every printed time of the domain would be shifted. {@code ZoneId}
     * alone is not enough: it reads offset ids such as {@code +01:00} or {@code UTC+01:00} that
     * {@code TimeZone.getTimeZone} turns into GMT. A zone {@code TimeZone} reads as GMT is accepted only when it is
     * GMT (offset zero).
     *
     * @param zone the time zone, or null
     * @return whether the zone is blank or read the same by both
     */
    public static boolean isAcceptableTimeZone(String zone) {
        if (StringUtils.isBlank(zone)) {
            return true;
        }
        if (!zone.equals(zone.trim())) {
            return false;
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            return false;
        }
        // TimeZone.getTimeZone answers "GMT" for every id it cannot read
        return !"GMT".equals(TimeZone.getTimeZone(zone).getID())
                || ZoneOffset.UTC.equals(zoneId.normalized());
    }

    /** Refuses a blank domain name, which the database refuses only at the commit (a 500 with no reason) */
    private static void checkName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("A domain needs a name");
        }
    }

    /** Refuses a time zone {@link #isAcceptableTimeZone} does not accept */
    private static void checkTimeZone(String zone) {
        if (!isAcceptableTimeZone(zone)) {
            throw new IllegalArgumentException("Unknown time zone '" + zone + "'");
        }
    }

    /**
     * Updates the domain data from the domain template
     * @param domain the domain to update
     * @return the updated domain
     */
    @Transactional
    public Domain updateDomain(Domain domain) {
        Domain original = domainLookupService.findByDomainId(domain.getDomainId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing domain "
                    + domain.getDomainId());
        }

        checkTimeZone(domain.getTimeZone());
        checkName(domain.getName());

        // Copy the domain data
        original.setActive(domain.isActive());
        original.setSortOrder(domain.getSortOrder());
        original.setName(domain.getName());
        original.setTimeZone(domain.getTimeZone());
        original.setLatitude(domain.getLatitude());
        original.setLongitude(domain.getLongitude());
        original.setZoomLevel(domain.getZoomLevel());
        original.setMessageSortOrder(domain.getMessageSortOrder());
        original.setColor(domain.getColor());
        original.setPublish(domain.getPublish());
        original.setAtons(domain.getAtons());
        original.setTemplates(domain.getTemplates());

        // Substitute the areas with the persisted ones
        original.setAreas(persistedList(Area.class, domain.getAreas()));

        // Substitute the categories with the persisted ones
        original.setCategories(persistedList(Category.class, domain.getCategories()));

        // Substitute the message series with the persisted ones
        original.setMessageSeries(messageSeriesService.persistedMessageSeries(domain.getMessageSeries()));

        return saveEntity(original);
    }

    /**
     * Creates a new domain based on the domain template
     * @param domain the domain to create
     * @return the created domain
     */
    @Transactional
    public Domain createDomain(Domain domain, @SuppressWarnings("all") boolean createInKeycloak) {
        // Before anything else: a domain without an id or a name is refused with a sentence (DOMAIN4); any other
        // refusal of the database comes at the flush below, still before Keycloak is asked
        if (StringUtils.isBlank(domain.getDomainId())) {
            throw new IllegalArgumentException("A domain needs a domain id");
        }
        checkName(domain.getName());
        Domain original = domainLookupService.findByDomainId(domain.getDomainId());
        if (original != null) {
            throw new IllegalArgumentException("Cannot create domain with duplicate domain ID "
                    + domain.getDomainId());
        }
        checkTimeZone(domain.getTimeZone());

        // Substitute the areas with the persisted ones
        domain.setAreas(persistedList(Area.class, domain.getAreas()));

        // Substitute the categories with the persisted ones
        domain.setCategories(persistedList(Category.class, domain.getCategories()));

        // Substitute the message series with the persisted ones
        domain.setMessageSeries(messageSeriesService.persistedMessageSeries(domain.getMessageSeries()));

        domain = saveEntity(domain);
        // The insert reaches the database now, not at the commit: a row it refuses (a value longer than its column)
        // fails here, before Keycloak is asked to make the domain's client (DOMAIN4)
        em.flush();

        // If request, create the domain in Keycloak - but do not throw an error in case of an error
        if (createInKeycloak) {
            try {
                keycloakService.createKeycloakDomain(domain);
            } catch (Exception e) {
                log.error("Error creating new domain in Keycloak", e);
            }
        }

        return domain;
    }

    /**
     * Deletes the domain
     * @param domainId the ID of the domain to delete
     * @noinspection all
     */
    @Transactional
    public boolean deleteDomain(String domainId) {

        Domain domain = domainLookupService.findByDomainId(domainId);
        if (domain != null) {
            remove(domain);
            return true;
        }
        return false;
    }

    /**
     * Creates the domain as a client in Keycloak
     * @param domain the domain to create a Keycloak client for
     */
    public void createDomainInKeycloak(Domain domain) throws Exception {

        long t0 = System.currentTimeMillis();

        boolean result = keycloakService.createKeycloakDomain(domain);

        log.info(String.format("Created domain %s in Keycloak in %d ms. Result: %s",
                domain.getDomainId(), System.currentTimeMillis() - t0, result));

    }
}
