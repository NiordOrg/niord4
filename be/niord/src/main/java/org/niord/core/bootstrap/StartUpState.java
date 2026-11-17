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
package org.niord.core.bootstrap;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The start-up flag the bootstrap command waits for (docs/code-data-config-plan.md §7.2): set once every start-up
 * listener has run, the settings' and the dictionaries' default rows included. Until then {@code GET} and
 * {@code POST /rest/bootstrap} answer 503, so the bootstrap's imports never interleave with the start-up's own
 * writes, and the base product's bootstrap gives the same ids on every fresh database.
 *
 * <p>The web server answers before the start-up listeners have run. They listen to {@code ApplicationReadyEvent},
 * most of them unordered, and an unordered listener has the lowest precedence too, so no listener on that event can
 * be sure to run last. Spring Boot publishes the readiness state {@code ACCEPTING_TRAFFIC} right after every
 * {@code ApplicationReadyEvent} listener has returned (EventPublishingRunListener.ready), and the flag is set
 * then. It stays set.
 */
@Component
public class StartUpState {

    private volatile boolean done;

    /**
     * Sets the flag when the application accepts traffic
     *
     * @param event the readiness change
     */
    @EventListener
    public void onReadinessChange(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
            done = true;
        }
    }

    /**
     * Returns whether every start-up listener has run
     *
     * @return true once the application accepts traffic
     */
    public boolean isDone() {
        return done;
    }
}
