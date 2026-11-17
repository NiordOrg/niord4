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
package org.niord.web.map;

import org.junit.jupiter.api.Test;
import org.niord.core.util.GlobalMercator;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the part of the map a message's map image shows ({@link MapImagePlanner#frame}): the image the
 * public site, the mails and the PDF reports show beside a warning. Geometries generated from a fixed seed in the
 * waters of the base product's countries (Denmark, the Faroes, Greenland), one to three message parts each.
 */
public class MapImageFramePropertyTest {

    private static final long SEED = 20261115L;
    private static final int SIZE = 256;
    private static final int SINGLE_POINT_ZOOM = 10;

    private static double[] position(Random r, double lat0, double lon0, double spread) {
        return new double[] { lon0 + (r.nextDouble() - 0.5) * spread * 2, lat0 + (r.nextDouble() - 0.5) * spread };
    }

    private static FeatureCollectionVo part(Random r, double lat0, double lon0, double spread) {
        int features = 1 + r.nextInt(2);
        FeatureVo[] fs = new FeatureVo[features];
        for (int i = 0; i < features; i++) {
            GeometryVo g = switch (r.nextInt(3)) {
                case 0 -> new PointVo(position(r, lat0, lon0, spread));
                case 1 -> {
                    double[][] c = new double[2 + r.nextInt(3)][];
                    for (int j = 0; j < c.length; j++) {
                        c[j] = position(r, lat0, lon0, spread);
                    }
                    yield new MultiPointVo(c);
                }
                default -> {
                    double[][] c = new double[2 + r.nextInt(3)][];
                    for (int j = 0; j < c.length; j++) {
                        c[j] = position(r, lat0, lon0, spread);
                    }
                    yield new LineStringVo(c);
                }
            };
            FeatureVo f = new FeatureVo();
            f.setGeometry(g);
            fs[i] = f;
        }
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(fs);
        return fc;
    }

    /** The positions outside the image, as text, or an empty list */
    private static List<String> outside(FeatureCollectionVo[] fcs, TileWindow w) {
        List<String> out = new ArrayList<>();
        for (FeatureCollectionVo fc : fcs) {
            fc.visitCoordinates(c -> {
                double x = w.imageX(OpenLayersGeometry.x3857(c[0]));
                double y = w.imageY(OpenLayersGeometry.y3857(c[1]));
                if (x < 0 || x > w.width() || y < 0 || y > w.height()) {
                    out.add(String.format(Locale.ROOT, "%.4f,%.4f at pixel %.0f,%.0f", c[1], c[0], x, y));
                }
            });
        }
        return out;
    }

    @Test
    public void everyPositionOfAOnePartMessage_isInsideItsImage() {
        // One part: the image is centred on the geometry's extent and zoomed so that the extent fills at most 80 %,
        // so every position is inside it (unless the extent is too big even at the lowest zoom level, which a
        // geometry of these spreads never is).
        Random random = new Random(SEED);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            double lat0 = 54 + random.nextDouble() * 24;
            double lon0 = -60 + random.nextDouble() * 75;
            double spread = Math.pow(10, -3 + random.nextDouble() * 3.5);
            FeatureCollectionVo[] fcs = { part(random, lat0, lon0, spread) };
            TileWindow w = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
            List<String> out = outside(fcs, w);
            if (!out.isEmpty()) {
                failures.add("zoom " + w.zoom() + ": " + out);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " images miss positions: " + failures.stream().limit(5).toList());
    }

    @Test
    public void everyPositionOfAMessageOfSeveralParts_isInsideItsImage_documentsBug() {
        // PB-4. With two or three parts that have a geometry (Message.toGeoJson gives one feature collection per
        // part), the image is centred on the average of the parts' centres, while the zoom level is chosen for the
        // extent of all of them. When the parts differ in size or are spread unevenly the average is not the
        // middle of the extent, and the image cuts positions off: up to half the geometry is outside the picture.
        // Pinned as found; the smallest case is theSmallestMessageOfTwoParts below.
        Random random = new Random(SEED + 1);
        List<String> failures = new ArrayList<>();
        int framedAboveTheLowestZoom = 0;
        for (int i = 0; i < 2000; i++) {
            double lat0 = 54 + random.nextDouble() * 24;
            double lon0 = -60 + random.nextDouble() * 75;
            double spread = Math.pow(10, -2 + random.nextDouble() * 2);
            FeatureCollectionVo[] fcs = new FeatureCollectionVo[2 + random.nextInt(2)];
            for (int p = 0; p < fcs.length; p++) {
                // The parts near each other, as the parts of one warning are, but of different sizes
                fcs[p] = part(random, lat0 + (random.nextDouble() - 0.5) * spread,
                        lon0 + (random.nextDouble() - 0.5) * spread, spread * random.nextDouble());
            }
            TileWindow w = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
            if (w.zoom() > MapImagePlanner.MIN_ZOOM) {
                framedAboveTheLowestZoom++;
                List<String> out = outside(fcs, w);
                if (!out.isEmpty()) {
                    failures.add("zoom " + w.zoom() + ": " + out);
                }
            }
        }
        assertTrue(framedAboveTheLowestZoom > 1500, "" + framedAboveTheLowestZoom);
        // Found 2026-11-15: 40 of the 2,000. The fix makes the list empty and fails this line.
        assertTrue(failures.size() > 10, failures.size() + " images miss positions");
    }

    @Test
    public void theSmallestMessageOfTwoParts() {
        // PB-4, the smallest input: part 1 is a line 0.274° long (55.5° N, 11.0° E to 11.274° E), part 2 one point
        // at its east end. The extent is the line, about 200 of the image's 256 pixels at zoom 10; the image is
        // centred at 11.2055° E, the average of the parts' centres 11.137 and 11.274, so the west end of the line
        // is about 22 pixels left of the image.
        FeatureCollectionVo line = new FeatureCollectionVo();
        FeatureVo l = new FeatureVo();
        l.setGeometry(new LineStringVo(new double[][] { { 11.0, 55.5 }, { 11.274, 55.5 } }));
        line.setFeatures(new FeatureVo[] { l });
        FeatureCollectionVo point = new FeatureCollectionVo();
        FeatureVo p = new FeatureVo();
        p.setGeometry(new PointVo(new double[] { 11.274, 55.5 }));
        point.setFeatures(new FeatureVo[] { p });
        FeatureCollectionVo[] fcs = { line, point };

        TileWindow w = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        assertEquals(10, w.zoom());
        double westEnd = w.imageX(OpenLayersGeometry.x3857(11.0));
        double eastEnd = w.imageX(OpenLayersGeometry.x3857(11.274));
        assertTrue(eastEnd <= SIZE, "the east end is shown: " + eastEnd);
        // Expected: the west end inside the image (x >= 0); seen: left of it
        assertTrue(westEnd < 0, "the west end at pixel " + westEnd + " of " + SIZE + ", zoom " + w.zoom());

        // The same geometry as one part is framed whole
        FeatureCollectionVo merged = new FeatureCollectionVo();
        merged.setFeatures(new FeatureVo[] { l, p });
        TileWindow whole = MapImagePlanner.frame(new FeatureCollectionVo[] { merged }, SIZE, SINGLE_POINT_ZOOM);
        assertEquals(List.of(), outside(new FeatureCollectionVo[] { merged }, whole));
    }

    @Test
    public void aGeometryAcrossTheAntimeridian_isFramedOnTheOtherSideOfTheWorld_documentsBehavior() {
        // The extent is computed without regard to the antimeridian (GeoJsonVo.computeBBox, "naive"), so two points
        // 0.2° apart across 180° span 359.8° of longitude: the image is the lowest zoom level centred on 0° and shows
        // neither. No base country's waters cross the antimeridian; pinned so a change shows.
        FeatureCollectionVo fc = new FeatureCollectionVo();
        FeatureVo f = new FeatureVo();
        f.setGeometry(new MultiPointVo(new double[][] { { 179.9, 60 }, { -179.9, 60 } }));
        fc.setFeatures(new FeatureVo[] { f });
        FeatureCollectionVo[] fcs = { fc };
        TileWindow w = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        assertEquals(MapImagePlanner.MIN_ZOOM, w.zoom());
        assertEquals(2, outside(fcs, w).size());
    }

    @Test
    public void webMercator_roundTrip_andTileBounds() {
        // GlobalMercator (the map images' resolutions, the AtoN tiles' bounds): metres and back give the position,
        // and a tile's bounds hold every position whose pixel falls in it.
        GlobalMercator m = new GlobalMercator();
        Random random = new Random(SEED + 2);
        for (int i = 0; i < 5000; i++) {
            double lat = -85 + random.nextDouble() * 170;
            double lon = -180 + random.nextDouble() * 360;
            double[] metres = m.LatLonToMeters(lat, lon);
            double[] back = m.MetersToLatLon(metres[0], metres[1]);
            assertEquals(lat, back[0], 1e-9, lat + "," + lon);
            assertEquals(lon, back[1], 1e-9, lat + "," + lon);
        }
        for (int z = 0; z <= 18; z++) {
            assertEquals(2 * Math.PI * 6378137 / 256 / Math.pow(2, z), m.Resolution(z), 1e-9);
            int tiles = 1 << z;
            for (int i = 0; i < 50; i++) {
                int tx = random.nextInt(tiles);
                int ty = random.nextInt(tiles);
                double[] b = m.TileLatLonBounds(tx, ty, z);
                assertTrue(b[0] < b[2] && b[1] < b[3], "z" + z + " " + tx + "," + ty);
                assertTrue(b[0] >= -85.06 && b[2] <= 85.06 && b[1] >= -180 && b[3] <= 180, "z" + z + " " + tx + "," + ty);
            }
        }
    }
}
