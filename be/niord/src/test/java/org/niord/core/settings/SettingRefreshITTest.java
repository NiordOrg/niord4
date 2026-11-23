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

import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.MailService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved setting takes effect at once, and everyday use reads a field as before: the services keep their
 * {@code @Setting} fields, and a save refreshes them ({@link SettingsRefresher}).
 * <ul>
 *     <li>A save on the Settings page reaches the field of a singleton created at start ({@link MailService}) and of
 *     the sea-chart servlet, which is created only at the first {@code /wms/} request after start-up.</li>
 *     <li>A key an environment variable or system property overrides stays overridden.</li>
 *     <li>Another node's save is followed at the next poll of the change count, which also empties the settings
 *     cache. Here the test makes the change as another node does (the row written behind this node's cache, the
 *     count raised) and calls this node's poll, which the suite's schedule switches off; a second node started on
 *     the same database, with its poll on, is {@link SettingsSecondNodeITTest}.</li>
 *     <li>A field changes only when its setting changed: a poll that finds nothing new leaves a field a test set
 *     directly.</li>
 * </ul>
 * Every write commits, so each test puts back the row and, through the same path, the field.
 */
@RecordApplicationEvents
class SettingRefreshITTest extends ITTest {

    private static final String SENDER = "mailSender";

    private static final String PROVIDER = "wmsProvider";

    @Autowired
    SettingsService settingsService;

    @Autowired
    SettingsRefresher settingsRefresher;

    @Autowired
    MailService mailService;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @PersistenceContext
    EntityManager em;

    @Test
    void aSaveOnTheSettingsPageReachesTheFieldOfARunningSingleton() {
        Object original = rowValue(SENDER);
        String saved = "it-refresh-" + System.nanoTime() + "@example.org";
        try {
            put(SENDER, saved);

            assertEquals(saved, senderField(), "the field holds the saved value, without a restart");
        } finally {
            put(SENDER, original);
        }
        assertEquals(original, senderField(), "and the restored row is the field's value again");
    }

    /** The events are recorded on the test's thread, so this save is made on it, as the settings import makes one */
    @Test
    void aSaveThatChangesAFieldIsPublishedAsSavedHere() {
        Object original = rowValue(SENDER);
        try {
            settingsService.set(SENDER, "it-published-" + System.nanoTime() + "@example.org");

            assertEquals(List.of(new SettingChangedEvent(SENDER, true)),
                    events.stream(SettingChangedEvent.class).toList(), "published once, as saved on this node");

            events.clear();
            settingsService.set(SENDER, rowValue(SENDER));
            assertEquals(List.of(), events.stream(SettingChangedEvent.class).toList(),
                    "a save of the same value changes no field and publishes nothing");
        } finally {
            settingsService.set(SENDER, original);
        }
    }

    @Test
    void aSaveOnTheSettingsPageReachesTheFieldOfTheServletCreatedAfterStartUp() {
        Object original = rowValue(PROVIDER);
        // The servlet is created at its first request; this one makes sure it exists before the save
        given().when().get("/wms/x?x=1").then().statusCode(403);
        Object servlet = wmsServlet();
        String saved = "http://127.0.0.1:9/it-refresh-" + System.nanoTime();
        try {
            put(PROVIDER, saved);

            assertEquals(saved, ReflectionTestUtils.getField(servlet, PROVIDER),
                    "the servlet's field holds the saved provider");
        } finally {
            put(PROVIDER, original);
        }
        assertEquals(original, ReflectionTestUtils.getField(servlet, PROVIDER));
    }

    @Test
    void aKeyAnEnvironmentVariableOrSystemPropertyOverridesStaysOverridden() {
        Object original = rowValue(SENDER);
        String override = "it-override-" + System.nanoTime() + "@example.org";
        System.setProperty(SENDER, override);
        try {
            put(SENDER, "it-saved-" + System.nanoTime() + "@example.org");

            assertEquals(override, senderField(), "the override wins over the saved row, as at start");
        } finally {
            System.clearProperty(SENDER);
            put(SENDER, original);
        }
        assertEquals(original, senderField(), "without the override, the row's value");
    }

    @Test
    void anotherNodesSaveIsFollowedAtThePoll() {
        settingsRefresher.pollForChanges();
        Object original = rowValue(SENDER);
        String elsewhere = "it-elsewhere-" + System.nanoTime() + "@example.org";
        assertEquals(original, settingsService.get(SENDER), "precondition: the row's value is in the cache");
        events.clear();
        try {
            saveAsAnotherNode(SENDER, elsewhere);
            assertEquals(original, senderField(), "precondition: this node has not seen the save yet");
            assertEquals(original, settingsService.get(SENDER), "nor has its cache");

            settingsRefresher.pollForChanges();

            assertEquals(elsewhere, senderField(), "the poll refreshed the field");
            assertEquals(elsewhere, settingsService.get(SENDER), "and emptied the cache: a read by name follows");
            assertEquals(List.of(new SettingChangedEvent(SENDER, false)),
                    events.stream(SettingChangedEvent.class).toList(),
                    "the change is published, as found by the poll; nothing else changed");
        } finally {
            saveAsAnotherNode(SENDER, original);
            settingsRefresher.pollForChanges();
        }
        assertEquals(original, senderField());
    }

    @Test
    void aPollThatFindsNoChangedSettingLeavesAFieldSetDirectly() {
        settingsRefresher.pollForChanges();
        Object target = AopTestUtils.getUltimateTargetObject(mailService);
        Object injected = ReflectionTestUtils.getField(target, SENDER);
        try {
            ReflectionTestUtils.setField(target, SENDER, "it-direct@example.org");
            raiseTheChangeCount();

            settingsRefresher.pollForChanges();

            assertEquals("it-direct@example.org", senderField(),
                    "the setting did not change, so the field is left as the test set it");
        } finally {
            ReflectionTestUtils.setField(target, SENDER, injected);
        }
    }

    // ------------------------------------------------------------------------------------------- //

    /** Saves the value through the Settings page's endpoint, as a sysadmin */
    private void put(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/settings/setting/" + key)
                .then().statusCode(200);
    }

    /** Writes the row behind this node's cache and raises the change count, as a save on another node does */
    private void saveAsAnotherNode(String key, Object value) {
        Tx.requiringNew().run(() -> em.find(Setting.class, key).setValue(value));
        raiseTheChangeCount();
    }

    private void raiseTheChangeCount() {
        Tx.requiringNew().run(() -> {
            Setting count = em.find(Setting.class, SettingsRefresher.CHANGE_COUNT.getKey());
            Long before = count != null ? SettingsRefresher.toLong(count.getValue()) : null;
            settingsService.writeState(SettingsRefresher.CHANGE_COUNT, (before != null ? before : 0L) + 1);
        });
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }

    /** The mail sender as {@link MailService} holds it */
    private Object senderField() {
        Object target = AopTestUtils.getUltimateTargetObject(mailService);
        return ReflectionTestUtils.getField(target, SENDER);
    }

    /** The sea-chart servlet the delegating servlet created at its first request */
    private Object wmsServlet() {
        Object delegating = context.getBean("wmsProxyServletRegistration", ServletRegistrationBean.class).getServlet();
        Object servlet = ReflectionTestUtils.getField(delegating, "delegate");
        assertNotNull(servlet, "the first /wms/ request created the servlet");
        return servlet;
    }
}
