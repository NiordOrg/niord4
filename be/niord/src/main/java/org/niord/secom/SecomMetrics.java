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
package org.niord.secom;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.SecomCertificateAlertService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes SECOM operational gauges to the Micrometer registry (actuator): dataset,
 * subscription, node and outbox counts — the same counts the {@code /rest/secom/status}
 * endpoint serves. The values are refreshed by a scheduled sweep rather than computed at
 * scrape time, so a metrics scrape never touches the database (gauges read 0 until the
 * first sweep, within a minute of startup). The certificate days-to-expiry gauge is the
 * exception: it is computed at scrape time from the in-memory service certificate (still
 * no database), so it tracks hot reloads immediately.
 * <p>
 * Only registered when the SECOM integration is enabled — disabled deployments expose no
 * {@code niord.secom.*} meters. Whether the actuator {@code metrics} endpoint is exposed
 * over HTTP remains a deployment decision ({@code management.endpoints.web.exposure}).
 */
@Component
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomMetrics {

    private final EntityManager em;
    // Strong reference - Micrometer only holds the gauge's state object weakly
    private final McpSecurityService pki;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

    public SecomMetrics(MeterRegistry registry, EntityManager em, McpSecurityService pki) {
        this.em = em;
        this.pki = pki;
        for (S124Dataset.Status status : S124Dataset.Status.values()) {
            register(registry, "niord.secom.datasets", status.name());
        }
        for (SecomOutboxEntry.Status status : SecomOutboxEntry.Status.values()) {
            register(registry, "niord.secom.outbox", status.name());
        }
        register(registry, "niord.secom.subscriptions", null);
        register(registry, "niord.secom.nodes", null);
        Gauge.builder("niord.secom.certificate.days-to-expiry", pki,
                        p -> SecomCertificateAlertService.daysToExpiry(p.serviceCertificate().getNotAfter(), new Date()))
                .register(registry);
    }

    private void register(MeterRegistry registry, String name, String status) {
        AtomicLong value = values.computeIfAbsent(key(name, status), k -> new AtomicLong());
        Gauge.Builder<AtomicLong> gauge = Gauge.builder(name, value, AtomicLong::get);
        if (status != null) {
            gauge = gauge.tag("status", status.toLowerCase());
        }
        gauge.register(registry);
    }

    private static String key(String name, String status) {
        return status == null ? name : name + ":" + status;
    }

    private void set(String name, String status, long count) {
        AtomicLong value = values.get(key(name, status));
        if (value != null) {
            value.set(count);
        }
    }

    /** Refreshes the gauge values from the database */
    @Scheduled(cron = "${niord.secom.metrics-cron:29 * * * * ?}")
    @Transactional(readOnly = true)
    public void refresh() {
        for (S124Dataset.Status status : S124Dataset.Status.values()) {
            set("niord.secom.datasets", status.name(), em.createQuery(
                            "select count(d) from S124Dataset d where d.status = :status", Long.class)
                    .setParameter("status", status).getSingleResult());
        }
        Map<String, Long> outboxByStatus = new ConcurrentHashMap<>();
        for (Object[] row : em.createQuery(
                "select e.status, count(e) from SecomOutboxEntry e group by e.status", Object[].class)
                .getResultList()) {
            outboxByStatus.put(((SecomOutboxEntry.Status) row[0]).name(), (Long) row[1]);
        }
        for (SecomOutboxEntry.Status status : SecomOutboxEntry.Status.values()) {
            set("niord.secom.outbox", status.name(), outboxByStatus.getOrDefault(status.name(), 0L));
        }
        set("niord.secom.subscriptions", null, em.createQuery(
                "select count(s) from SecomSubscription s", Long.class).getSingleResult());
        set("niord.secom.nodes", null, em.createQuery(
                "select count(n) from SecomNode n", Long.class).getSingleResult());
    }
}
