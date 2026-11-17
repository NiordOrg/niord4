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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One table of positions, printed by the backend in the form the administration app prints them
 * ({@code DD° MM.mmm'N - DDD° MM.mmm'E}, three decimals, English and Danish decimal separators). The same rows
 * are pinned on the frontend by {@code fe/ui-apps/admin/src/app/shared/utils/coordinate-format-grid.spec.ts}
 * against {@code formatLatLon}: an editor sees a position in the geometry editor as the frontend prints it and
 * reads it in the mails, PDFs and the public site as the backend prints it, so a difference between the two
 * shows on both sides. Change both tables together.
 */
public class PositionFormatterSharedTableTest {

    /** {@code lat | lon | English | Danish}. */
    static final Object[][] SHARED_POSITIONS = {
            { 56.99940309853659, 9.294097396125087, "56° 59.964'N - 009° 17.646'E", "56° 59,964'N - 009° 17,646'E" },
            { 55.0, 12.0, "55° 00.000'N - 012° 00.000'E", "55° 00,000'N - 012° 00,000'E" },
            { 1.9999999, 179.9999999, "02° 00.000'N - 180° 00.000'E", "02° 00,000'N - 180° 00,000'E" },
            { -0.0004, -0.0004, "00° 00.024'S - 000° 00.024'W", "00° 00,024'S - 000° 00,024'W" },
            { -12.5, -45.25, "12° 30.000'S - 045° 15.000'W", "12° 30,000'S - 045° 15,000'W" },
            { 89.99999999, -179.99999999, "90° 00.000'N - 180° 00.000'W", "90° 00,000'N - 180° 00,000'W" },
            { 53.119167, 9.555333, "53° 07.150'N - 009° 33.320'E", "53° 07,150'N - 009° 33,320'E" },
            { 64.17491666, -51.73888, "64° 10.495'N - 051° 44.333'W", "64° 10,495'N - 051° 44,333'W" },
            { 54.99999, 10.99999, "54° 59.999'N - 010° 59.999'E", "54° 59,999'N - 010° 59,999'E" },
            { 54.999999, 10.999999, "55° 00.000'N - 011° 00.000'E", "55° 00,000'N - 011° 00,000'E" },
    };

    @Test
    public void theSharedTable_printsTheSameAsTheAdministrationApp() {
        for (Object[] row : SHARED_POSITIONS) {
            double lat = (Double) row[0];
            double lon = (Double) row[1];
            assertEquals(row[2], PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_3,
                    "LAT - LON", lat, lon), "en " + lat + "," + lon);
            assertEquals(row[3], PositionFormatter.format(Locale.forLanguageTag("da"), PositionFormatter.LATLON_DEC_3,
                    "LAT - LON", lat, lon), "da " + lat + "," + lon);
        }
    }
}
