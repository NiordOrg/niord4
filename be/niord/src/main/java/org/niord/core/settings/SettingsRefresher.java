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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/**
 * Makes a saved setting take effect at once, without slowing everyday use: the services keep reading their
 * {@code @Setting} fields, and this bean sets those fields again when a setting changes
 * ({@link SettingInjectionBeanPostProcessor#refresh}). Nothing is added to a request; the work is done once per save,
 * and once per poll.
 * <ul>
 *     <li>On the node that saves: {@link SettingsService#set} calls {@link #refreshSaved} once the save has committed,
 *     which refreshes the fields of the saved key.</li>
 *     <li>On every other node: {@code set} also adds one to the {@code state} setting {@link #CHANGE_COUNT} in the
 *     save's transaction. {@link #pollForChanges} reads that one row about every 30 seconds, off every request; when
 *     it has changed, the node empties its settings cache (so a key read by name follows too, not only after the
 *     cache's three minutes) and refreshes every field.</li>
 * </ul>
 * A field is set only when its setting's value changed; an environment variable or system property named like the key
 * still wins. After each refresh that gave a field of a key a new value, a {@link SettingChangedEvent} is published.
 */
@Component
public class SettingsRefresher {

    /** How many times a setting was saved; each node polls it to follow the saves of another node */
    public static final Setting CHANGE_COUNT = new Setting("settingsChangeCount", null, false)
            .type(Setting.Type.Long)
            .scope(Setting.Scope.state)
            .description("How many times a setting was saved; each node polls it to follow another node's saves");

    private static final Logger log = LoggerFactory.getLogger(SettingsRefresher.class);

    private final SettingsService settingsService;

    private final SettingInjectionBeanPostProcessor processor;

    private final SettingsCache settingsCache;

    private final ApplicationEventPublisher events;

    /** Whether the poll has read the change count once; guarded by this */
    private boolean following;

    /** The change count whose saves this node's fields hold; guarded by this */
    private Long seen;

    SettingsRefresher(SettingsService settingsService, SettingInjectionBeanPostProcessor processor,
                      SettingsCache settingsCache, ApplicationEventPublisher events) {
        this.settingsService = settingsService;
        this.processor = processor;
        this.settingsCache = settingsCache;
        this.events = events;
    }

    /**
     * Refreshes the fields of a key saved on this node, after the save has committed, in a transaction of its own (the
     * save's has committed). When this node had followed every save before this one, it counts this save as followed,
     * so its next poll refreshes nothing; otherwise the poll still catches the other node's save.
     *
     * @param key         the saved key
     * @param countBefore the change count the save found, null when there was none
     * @param countAfter  the change count the save wrote
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshSaved(String key, Long countBefore, long countAfter) {
        synchronized (this) {
            if (following && Objects.equals(seen, countBefore)) {
                seen = countAfter;
            }
        }
        publish(processor.refresh(settingsService, key::equals), true);
    }

    /**
     * Reads the change count and, when another node saved a setting since the last poll, empties this node's settings
     * cache and refreshes every field. The first poll after a start refreshes every field once, so a save made by
     * another node while this one started is followed too. {@code niord.settings.change-poll-cron} sets the schedule,
     * and {@code -} switches the poll off (the integration suite switches it off and calls it directly).
     */
    @Scheduled(cron = "${niord.settings.change-poll-cron:14/30 * * * * ?}")
    @Transactional
    public void pollForChanges() {
        Long count;
        try {
            count = toLong(settingsService.peek(CHANGE_COUNT.getKey()));
        } catch (RuntimeException e) {
            log.warn("Could not read " + CHANGE_COUNT.getKey() + ": settings saved on another node are followed at "
                    + "the next poll that can: " + e);
            return;
        }
        synchronized (this) {
            if (following && Objects.equals(seen, count)) {
                return;
            }
            following = true;
            seen = count;
        }
        settingsCache.getCache().clear();
        Set<String> changed = processor.refresh(settingsService, key -> true);
        if (!changed.isEmpty()) {
            log.info("Settings saved elsewhere followed: " + changed);
        }
        publish(changed, false);
    }

    /** Publishes a {@link SettingChangedEvent} for each key */
    private void publish(Set<String> keys, boolean savedHere) {
        keys.forEach(key -> events.publishEvent(new SettingChangedEvent(key, savedHere)));
    }

    /** The change count as a number, null when there is none */
    static Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString().trim());
    }
}
