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
package org.niord.core.aton;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import org.junit.jupiter.api.Test;
import org.niord.core.script.NiordAppObjectWrapper;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words the base product's {@code templates/aton/light-character.ftl} prints for a light character (the
 * "verbose" light character of the AtoN templates, {@code LightCharacterService.formatLightCharacter}),
 * rendered from the bootstrap file exactly as the service renders it, then trimmed by
 * {@link LightCharacterService#trimResult}. The template is data a deployment receives by a release import, so
 * a fix ships as one.
 */
public class LightCharacterTextTest {

    private static String words(String lightCharacter) throws Exception {
        String template = Files.readString(Path.of("bootstrap/templates/aton/light-character.ftl"), StandardCharsets.UTF_8);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("light-character.ftl", template);
        cfg.setTemplateLoader(loader);
        cfg.setObjectWrapper(new NiordAppObjectWrapper(cfg.getIncompatibleImprovements()));
        cfg.setLocale(Locale.ENGLISH);
        StringWriter out = new StringWriter();
        cfg.getTemplate("light-character.ftl")
                .process(Map.of("lightModel", LightCharacterParser.getInstance().parse(lightCharacter)), out);
        return LightCharacterService.trimResult(out.toString());
    }

    @Test
    public void everyLightOfTheGrid_isDescribed_withItsNumbers() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String lc : List.of("F.R", "Fl.G.3s", "Fl(2)R.6s", "Fl(2+1)W.10s", "LFl.W.10s", "Q.G", "VQ(3).W.5s",
                "IQ.R.9s", "Iso.WRG.4s", "Oc(2)W.8s", "Al.WR.4s", "Mo(U)15s", "Q(6)+LFl.W.15s", "Fl.W.10s 12m 8M")) {
            String text = words(lc);
            if (text.isBlank() || text.contains("${")) {
                failures.add(lc + " -> \"" + text + "\"");
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals("2 red flashes every 6 seconds", words("Fl(2)R.6s"));
        assertEquals("white flash every 10 seconds, 12 meters, 8 nautical miles", words("Fl.W.10s 12m 8M"));
    }

    @Test
    public void oneSecondOneMeterOneMile_areSingular_andThePeriodHasNoFullStop() throws Exception {
        // The template wrote "${period}. seconds", "${elevation} meters" and "${range} nautical miles" whatever the
        // number: "1 nautical miles", and the Danish ordinal full stop in an English text (ATON15).
        assertEquals("green flash every second, 1 meter, 1 nautical mile", words("Fl.G.1s 1m 1M"));
    }

    @Test
    public void aDecimalPeriod_isPrintedWithItsPoint() throws Exception {
        // ATON14: "Fl.G.2.5s" was read and printed as 25 seconds
        assertEquals("green flash every 2.5 seconds", words("Fl.G.2.5s"));
        assertEquals("red flash every 3 seconds, 4.5 nautical miles", words("Fl.R.3s 4.5M"));
        assertEquals("2 white flashes every 7.5 seconds, 12.5 meters, 8 nautical miles",
                words("Fl(2)W.7.5s 12.5m 8M"));
    }
}
