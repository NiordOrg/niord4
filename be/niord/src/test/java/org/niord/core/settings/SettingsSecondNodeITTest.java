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
package org.niord.core.settings;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.MailService;
import org.niord.core.util.CdiUtils;
import org.niord.test.ITTest;
import org.niord.test.NiordTestApplication;
import org.niord.test.NiordTestContainers;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A setting saved on one node takes effect on a second node of the same database within that node's poll of the
 * change count ({@link SettingsRefresher#pollForChanges}). The test's own application context is the first node; the
 * second is the whole application started again on the suite's database, as a second pod of a deployment starts, with
 * the poll switched on (every two seconds here; a deployment's default is every 30 seconds, pinned by
 * {@code ScheduledCronExpressionTest}). The suite's contexts have the poll switched off ({@link ITTest}), so only the
 * second node polls.
 *
 * <p>The second node shares the suite's Niord home, as the suite's other contexts do, and starts without deleting the
 * message index ({@code messageIndexDeleteOnStartup}, by a system property for the length of its start), so that its
 * start does not empty the index the first node searches.
 */
class SettingsSecondNodeITTest extends ITTest {

    private static final String SENDER = "mailSender";

    /** How long the second node may take to follow a save: a few of its polls */
    private static final Duration FOLLOWS_WITHIN = Duration.ofSeconds(30);

    private static final String DELETE_INDEX_ON_START = "messageIndexDeleteOnStartup";

    @Autowired
    SettingsService settingsService;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    @Test
    void aSaveOnOneNodeReachesTheFieldOfASecondNodeWithinItsPoll() {
        Object original = Tx.requiringNew().call(() -> em.find(Setting.class, SENDER).getValue());
        String saved = "it-second-node-" + System.nanoTime() + "@example.org";

        try (ConfigurableApplicationContext secondNode = startSecondNode()) {
            MailService secondMail = secondNode.getBean(MailService.class);
            assertEquals(original, sender(secondMail), "precondition: the second node started with the row's value");

            try {
                settingsService.set(SENDER, saved);

                await().atMost(FOLLOWS_WITHIN).untilAsserted(() -> assertEquals(saved, sender(secondMail),
                        "the second node's field holds the value saved on the first, without a restart"));
                assertEquals(saved, secondNode.getBean(SettingsService.class).get(SENDER),
                        "and its settings cache was emptied: a read by name follows too");
            } finally {
                settingsService.set(SENDER, original);
            }
            await().atMost(FOLLOWS_WITHIN).untilAsserted(() -> assertEquals(original, sender(secondMail),
                    "the second node follows the restored value too"));
        } finally {
            // The second node's start pointed the static application context at itself
            context.getBeanProvider(CdiUtils.class).ifAvailable(cdiUtils -> cdiUtils.setApplicationContext(context));
        }
    }

    /**
     * Starts the application again on the suite's database, with the suite's properties and the poll of the change
     * count switched on
     */
    private static ConfigurableApplicationContext startSecondNode() {
        String deleteIndex = System.getProperty(DELETE_INDEX_ON_START);
        System.setProperty(DELETE_INDEX_ON_START, "false");
        try {
            SpringApplication application = new SpringApplication(NiordTestApplication.class);
            application.setBannerMode(Banner.Mode.OFF);
            application.setRegisterShutdownHook(false);
            return application.run(
                    "--server.port=0",
                    "--spring.datasource.url=" + NiordTestContainers.dbJdbcUrl(),
                    "--spring.datasource.username=" + NiordTestContainers.dbUsername(),
                    "--spring.datasource.password=" + NiordTestContainers.dbPassword(),
                    "--spring.liquibase.change-log=classpath:db/changelog/changelog-with-seed.yaml",
                    "--spring.jpa.properties.jakarta.persistence.validation.mode=none",
                    "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + NiordTestContainers.keycloakRealmUrl(),
                    "--spring.mail.host=" + NiordTestContainers.smtpHost(),
                    "--spring.mail.port=" + NiordTestContainers.smtpPort(),
                    "--niord.smtp.host=" + NiordTestContainers.smtpHost(),
                    "--niord.smtp.port=" + NiordTestContainers.smtpPort(),
                    // The suite's schedules that change shared rows stay off, as on every context of the suite
                    "--niord.schedule.auto-publish-cron=-",
                    "--niord.schedule.auto-expire-cron=-",
                    "--niord.schedule.firing-exercise-cron=-",
                    "--niord.mailinglist.status-change-sweep-cron=-",
                    // The one this test is about
                    "--niord.settings.change-poll-cron=*/2 * * * * ?");
        } finally {
            if (deleteIndex == null) {
                System.clearProperty(DELETE_INDEX_ON_START);
            } else {
                System.setProperty(DELETE_INDEX_ON_START, deleteIndex);
            }
        }
    }

    /** The mail sender as the given node's {@link MailService} holds it */
    private static Object sender(MailService mailService) {
        Object target = AopTestUtils.getUltimateTargetObject(mailService);
        return ReflectionTestUtils.getField(target, SENDER);
    }
}
