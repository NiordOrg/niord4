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
 * Refuses a value that a save would give a setting when the code that reads the setting cannot use it. A saved setting
 * takes effect at once, so a mistyped value would act at once too: a number of days of 0 that deletes every file at the
 * next pass, a map image size that turns every new image into the placeholder. A bean that reads such a setting
 * implements this for its key, next to the declaration. {@link SettingsService#set} refuses the save with the reason
 * (the Settings page answers 400 with it), and the settings import skips the value with the reason in the job's log.
 * The checks run after the type check, so a value is null or of the type of the setting's row, which on a restored
 * database can differ from the catalogue's.
 */
public interface SettingValueCheck {

    /**
     * Returns why a save may not give the setting the value.
     *
     * @param key   the setting's key
     * @param value the value, null or of the type of the setting's row (null means the default)
     * @return why the value is refused, or null when it may be saved, and for a key this check does not know
     */
    String refusal(String key, Object value);

    /**
     * Returns the refusal of a whole number outside a range, for {@link #refusal}. A value that is no number is read
     * as the code reads it: a row whose type is not the catalogue's, as a database restored from an older system can
     * hold, takes text, and the code parses it, so text that is a whole number is held to the range and other text is
     * refused.
     *
     * @param key   the setting's key
     * @param value the value, null, a number or text; null is not refused
     * @param min   the smallest value the code can use
     * @param max   the largest value the code can use
     * @param takes what the setting takes, for the refusal, e.g. {@code a size in pixels from 1 to 2048}
     * @return the refusal, or null when the value is in the range
     */
    static String outside(String key, Object value, long min, long max, String takes) {
        if (value == null) {
            return null;
        }
        long number;
        if (value instanceof Number n) {
            number = n.longValue();
        } else {
            try {
                number = Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return key + " takes " + takes + ", not " + value;
            }
        }
        return number < min || number > max ? key + " takes " + takes + ", not " + value : null;
    }
}
