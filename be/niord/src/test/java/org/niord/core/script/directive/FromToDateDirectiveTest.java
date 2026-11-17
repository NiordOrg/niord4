/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.script.directive;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;
import static org.niord.core.script.directive.DirectiveTestSupport.utc;

/**
 * Framework-neutral Tier-1 unit tests for {@link FromToDateDirective}.
 * <p>
 * The directive formats a from/to date interval using the {@code text} bundle keys
 * {@code msg.time.date_time_format} ("d MMMM yyyy HH:mm") and
 * {@code msg.time.until_further_notice} ("until further notice"), and the env
 * {@code timeZone} ("UTC"). The tests below exercise every branch of the
 * from/to formatting logic plus the "neither date" fallback, and document that
 * the declared {@code tz} parameter is never read.
 * <p>
 * Only Freemarker is involved (via {@link DirectiveTestSupport}); these tests use
 * no Quarkus, database, or container and survive a later Spring migration unchanged.
 */
public class FromToDateDirectiveTest {

    @Test
    public void fromToDate_bothDates_rendersIntervalSeparatedByDash() throws Exception {
        // Both fromDate and toDate present -> "<from> - <to>" using the bundle's date_time_format in UTC.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2017-04-12 13:00"));
        data.put("to", utc("2017-04-16 13:55"));
        Assertions.assertEquals(
                "12 April 2017 13:00 - 16 April 2017 13:55",
                render("<@d fromDate=from toDate=to/>", data));
    }

    @Test
    public void fromToDate_fromOnly_rendersFromDateWithoutDash() throws Exception {
        // Only fromDate present -> just the formatted from date, no separator and no to date.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2017-04-12 13:00"));
        Assertions.assertEquals(
                "12 April 2017 13:00",
                render("<@d fromDate=from/>", data));
    }

    @Test
    public void fromToDate_toOnly_rendersToDateWithoutDash() throws Exception {
        // Only toDate present -> just the formatted to date, no separator and no from date.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("to", utc("2017-04-16 13:55"));
        Assertions.assertEquals(
                "16 April 2017 13:55",
                render("<@d toDate=to/>", data));
    }

    @Test
    public void fromToDate_neitherDate_rendersUntilFurtherNotice() throws Exception {
        // Neither fromDate nor toDate -> the bundle's msg.time.until_further_notice fallback string.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        Assertions.assertEquals(
                "until further notice",
                render("<@d/>", data));
    }

    @Test
    public void fromToDate_tzParameterOverridesEnvTimeZone() throws Exception {
        // SCRIPT3 (fixed): the "tz" parameter now overrides the zone (mirrors DateIntervalDirective).
        // UTC 13:00 shown in GMT+1 is 14:00.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2017-04-12 13:00"));
        Assertions.assertEquals(
                "12 April 2017 14:00",
                render("<@d fromDate=from tz=\"GMT+1\"/>", data));
    }

    @Test
    public void fromToDate_blankTzParameterFallsBackToEnvTimeZone() throws Exception {
        // A blank tz falls back to the env "timeZone" data (UTC here), so the output stays 13:00.
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2017-04-12 13:00"));
        Assertions.assertEquals(
                "12 April 2017 13:00",
                render("<@d fromDate=from tz=\"\"/>", data));
    }

    @Test
    public void fromToDate_sameFromAndToDate_rendersBothSidesWithDash() throws Exception {
        // Equal from/to are still both rendered with the " - " separator (no de-duplication branch exists).
        Map<String, Object> data = data();
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2017-04-12 13:00"));
        data.put("to", utc("2017-04-12 13:00"));
        Assertions.assertEquals(
                "12 April 2017 13:00 - 12 April 2017 13:00",
                render("<@d fromDate=from toDate=to/>", data));
    }
}
