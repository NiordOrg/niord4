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

/**
 * Published by {@link SettingsRefresher} when a refresh gave at least one {@code @Setting} field of the key a new
 * value: after a save on this node ({@code savedHere}), or after another node's save that this node's poll found.
 * The fields already hold the new value when it is published. A bean that keeps something built from a setting (a
 * pause, a cache, an index) listens with {@code @EventListener} and acts on its own key. The listener runs on the
 * saving request's thread or on the poll's, inside the refresh's transaction, so a long piece of work goes to a thread
 * of its own; and work that every node shares (the database's) is done where {@code savedHere} is true, work of the
 * node's own (its folder, its memory) on every node.
 *
 * @param key       the setting key
 * @param savedHere whether the setting was saved on this node, rather than found changed by the poll
 */
public record SettingChangedEvent(String key, boolean savedHere) {
}
