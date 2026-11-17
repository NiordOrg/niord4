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
package org.niord.core.geojson;

import org.junit.jupiter.api.Test;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the geometry editor's text view ({@code POST /rest/messages/format-geometry} prints a geometry,
 * {@code /parse-geometry} reads the text back; the editor does both whenever the text view is opened and applied),
 * over whole feature collections generated from a fixed seed: several features of every type the text view knows,
 * positions anywhere on the globe, and names in one or two languages on the features and on single positions.
 * <p>
 * {@link PlainTextConverterGridTest} holds single points over a grid of minutes and the refused forms;
 * this class holds the geometry types, the names and the order of a whole text.
 */
public class PlainTextConverterPropertyTest {

    private static final long SEED = 20261115L;

    private static final PlainTextConverter EN = PlainTextConverter.newInstance(new String[] { "en" });
    private static final PlainTextConverter DA = PlainTextConverter.newInstance(new String[] { "da", "en" });

    /** Words of the names an editor gives positions: non-ASCII letters, abbreviations with dots, digits, commas */
    private static final String[] NAME_WORDS = {
            "Buoy", "Ærø", "S.", "Keldsnor", "SW.", "Vrag", "bøje", "U-9", "Lt.", "No.", "3", "Nordmole", "Øresund",
            "Fyr", "Wreck", "Grønland", "Nuuk", "Qeqertarsuaq", "Hals", "Barre", "(lit)", "Østerrenden,", "Skagen",
            "W", "N", "E", "S", "58°", "pos."
    };

    private static final double HALF_UNIT = 0.0005 / 60.0 + 1e-9;

    private static String name(Random r) {
        int words = 1 + r.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(NAME_WORDS[r.nextInt(NAME_WORDS.length)]);
        }
        String s = sb.toString();
        return s.endsWith(",") ? s.substring(0, s.length() - 1) : s;
    }

    /** A position on the grid the text view prints (thousandths of a minute), anywhere on the globe */
    private static double[] position(Random r) {
        double lat = (r.nextInt(90) + r.nextInt(60000) / 60000.0) * (r.nextBoolean() ? 1 : -1);
        double lon = (r.nextInt(180) + r.nextInt(60000) / 60000.0) * (r.nextBoolean() ? 1 : -1);
        return new double[] { lon, lat };
    }

    private static double[][] positions(Random r, int n) {
        double[][] c = new double[n][];
        for (int i = 0; i < n; i++) {
            c[i] = position(r);
        }
        return c;
    }

    private static FeatureVo feature(Random r, String[] languages) {
        GeometryVo g;
        int count;
        switch (r.nextInt(4)) {
            case 0 -> {
                g = new PointVo(position(r));
                count = 1;
            }
            case 1 -> {
                count = 1 + r.nextInt(4);
                g = new MultiPointVo(positions(r, count));
            }
            case 2 -> {
                count = 2 + r.nextInt(4);
                g = new LineStringVo(positions(r, count));
            }
            default -> {
                count = 3 + r.nextInt(3);
                double[][] ring = new double[count + 1][];
                double[][] open = positions(r, count);
                System.arraycopy(open, 0, ring, 0, count);
                ring[count] = open[0].clone();
                g = new PolygonVo(new double[][][] { ring });
            }
        }
        FeatureVo f = new FeatureVo();
        f.setGeometry(g);
        for (String lang : languages) {
            if (r.nextInt(2) == 0) {
                f.getProperties().put("name:" + lang, name(r));
            }
            for (int i = 0; i < count; i++) {
                if (r.nextInt(3) == 0) {
                    f.getProperties().put("name:" + i + ":" + lang, name(r));
                }
            }
        }
        return f;
    }

    private static FeatureCollectionVo collection(Random r, String[] languages) {
        FeatureVo[] features = new FeatureVo[1 + r.nextInt(3)];
        for (int i = 0; i < features.length; i++) {
            features[i] = feature(r, languages);
        }
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(features);
        return fc;
    }

    /** The positions of a geometry as the text view lists them (a polygon's closing position left out) */
    private static List<double[]> listed(GeometryVo g) {
        List<double[]> list = new ArrayList<>();
        if (g instanceof PolygonVo p) {
            double[][] ring = p.getCoordinates()[0];
            for (int i = 0; i < ring.length - 1; i++) {
                list.add(ring[i]);
            }
        } else {
            g.visitCoordinates(list::add);
        }
        return list;
    }

    private static Map<String, Object> names(FeatureVo f) {
        Map<String, Object> names = new TreeMap<>();
        f.getProperties().forEach((k, v) -> {
            if (k.startsWith("name:")) {
                names.put(k, v);
            }
        });
        return names;
    }

    /** What differs between the collection and the one read back, or null when nothing does */
    private static String difference(FeatureCollectionVo expected, FeatureCollectionVo actual) {
        if (actual == null || actual.getFeatures().length != expected.getFeatures().length) {
            return "features: " + expected.getFeatures().length + " -> "
                    + (actual == null ? "none" : actual.getFeatures().length);
        }
        for (int i = 0; i < expected.getFeatures().length; i++) {
            FeatureVo e = expected.getFeatures()[i];
            FeatureVo a = actual.getFeatures()[i];
            if (!e.getGeometry().getType().equals(a.getGeometry().getType())) {
                return "feature " + i + ": " + e.getGeometry().getType() + " -> " + a.getGeometry().getType();
            }
            if (e.getGeometry() instanceof PolygonVo) {
                double[][] ring = ((PolygonVo) a.getGeometry()).getCoordinates()[0];
                if (ring[0][0] != ring[ring.length - 1][0] || ring[0][1] != ring[ring.length - 1][1]) {
                    return "feature " + i + ": the polygon's ring is not closed";
                }
            }
            List<double[]> ep = listed(e.getGeometry());
            List<double[]> ap = listed(a.getGeometry());
            if (ep.size() != ap.size()) {
                return "feature " + i + ": " + ep.size() + " positions -> " + ap.size();
            }
            for (int j = 0; j < ep.size(); j++) {
                if (Math.abs(ep.get(j)[0] - ap.get(j)[0]) > HALF_UNIT || Math.abs(ep.get(j)[1] - ap.get(j)[1]) > HALF_UNIT) {
                    return "feature " + i + " position " + j + ": " + ep.get(j)[1] + "," + ep.get(j)[0] + " -> "
                            + ap.get(j)[1] + "," + ap.get(j)[0];
                }
            }
            if (!names(e).equals(names(a))) {
                return "feature " + i + " names: " + names(e) + " -> " + names(a);
            }
        }
        return null;
    }

    @Test
    public void everyPrintedCollection_readsBackAsTheSameCollection() throws Exception {
        // Format, then parse: the same features in the same order, the same geometry types, every position within
        // half a thousandth of a minute, and the same names on the features and on the positions, for an English
        // deployment (one language) and a Danish one (da, en: decimal comma).
        Random random = new Random(SEED);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < 600; i++) {
            boolean danish = i % 2 == 1;
            PlainTextConverter converter = danish ? DA : EN;
            FeatureCollectionVo fc = collection(random, danish ? new String[] { "da", "en" } : new String[] { "en" });
            String text = converter.toPlainText(fc);
            FeatureCollectionVo back;
            try {
                back = converter.fromPlainText(text);
            } catch (Exception e) {
                failures.add("refused (" + e.getMessage() + "): " + text);
                continue;
            }
            String diff = difference(fc, back);
            if (diff != null) {
                failures.add(diff + " in:\n" + text);
            }
            checked++;
        }
        assertTrue(failures.isEmpty(), failures.size() + " of 600 differ; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
        assertEquals(600, checked);
    }

    @Test
    public void printingWhatWasRead_printsTheSameText() throws Exception {
        // Idempotence of the text view: applying the text unchanged and opening the view again shows the same text.
        Random random = new Random(SEED + 1);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            boolean danish = i % 2 == 1;
            PlainTextConverter converter = danish ? DA : EN;
            FeatureCollectionVo fc = collection(random, danish ? new String[] { "da", "en" } : new String[] { "en" });
            String once = converter.toPlainText(fc);
            String twice = converter.toPlainText(converter.fromPlainText(once));
            if (!once.equals(twice)) {
                failures.add(once + "\n-> \n" + twice);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of 300 change; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
    }

    @Test
    public void anyTextTheParserAccepts_isOnTheGlobe() {
        // Fuzz: lines built from the characters and pieces positions are typed with. Whatever is accepted is on the
        // globe. (An unreadable text is refused; the endpoint answers it with no geometry.)
        String[] pieces = { "0", "5", "9", "55", "89", "90", "91", "179", "180", "181", "°", "'", "\"", " ", " ", ",",
                ".", "-", "+", "N", "S", "E", "W", "n", "e", "1e2", "0x1p3", "Infinity", "NaN", "60", "59.9999",
                "1)", "\n", "Point", "Polygon", "da:", "en:", "I", "J" };
        Random random = new Random(SEED + 2);
        List<String> failures = new ArrayList<>();
        int accepted = 0;
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 4 + random.nextInt(14);
            for (int j = 0; j < n; j++) {
                sb.append(pieces[random.nextInt(pieces.length)]);
            }
            String text = sb.toString();
            FeatureCollectionVo fc;
            try {
                fc = EN.fromPlainText(text);
            } catch (Exception refused) {
                continue;
            }
            if (fc == null) {
                continue;
            }
            accepted++;
            fc.visitCoordinates(c -> {
                if (!(Math.abs(c[1]) <= 90 && Math.abs(c[0]) <= 180)) {
                    failures.add("\"" + text.replace("\n", "\\n") + "\" -> " + c[1] + "," + c[0]);
                }
            });
        }
        assertTrue(accepted > 50, "the fuzz accepted too little to mean anything: " + accepted);
        assertTrue(failures.isEmpty(), failures.size() + " off the globe: " + failures);
    }

    @Test
    public void aNameWithAWordEndingInALanguageCodeAndAColon_readsBackWhole() throws Exception {
        // PB-3 (CG9). A name that holds a word ending in a language code of the deployment followed by a colon
        // ("Havnen:" ends in "en:", "Renden:" too) reads back whole: a language marker is a word of its own, at the
        // start of the text or after a comma or a space, never the end of another word. The editor applies the text
        // view whenever it is used, so a cut here would change the stored name without a word.
        FeatureVo f = new FeatureVo();
        f.setGeometry(new PointVo(new double[] { 10.5, 57.5 }));
        f.getProperties().put("name:da", "Havnen: nordmole");
        f.getProperties().put("name:en", "Harbour: north mole");
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[] { f });

        String text = DA.toPlainText(fc);
        FeatureVo back = DA.fromPlainText(text).getFeatures()[0];
        assertEquals("Havnen: nordmole", back.getProperties().get("name:da"), text);
        assertEquals("Harbour: north mole", back.getProperties().get("name:en"), text);

        // The same with one language: "Golden: wreck" in an English deployment, and in upper case
        for (String name : new String[] { "Golden: wreck", "GOLDEN: WRECK", "Renden:Skagen" }) {
            f.getProperties().clear();
            f.getProperties().put("name:en", name);
            String enText = EN.toPlainText(fc);
            Object enName = EN.fromPlainText(enText).getFeatures()[0].getProperties().get("name:en");
            assertEquals(name, enName, enText);
        }
    }

    @Test
    public void everyNameWithWordsEndingInALanguageCodeAndAColon_readsBackWhole() throws Exception {
        // The round trip of names whose words end in "da:" or "en:" in any case, on the features and on single
        // positions, for both deployments.
        String[] words = { "Havnen:", "Renden:", "Golden:", "Kanalen:", "Rønda:", "BROEN:", "Hellerupda:", "Buoy",
                "Ærø", "S.", "3", "nord" };
        Random random = new Random(SEED + 3);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            boolean danish = i % 2 == 1;
            PlainTextConverter converter = danish ? DA : EN;
            String[] languages = danish ? new String[] { "da", "en" } : new String[] { "en" };
            FeatureVo f = new FeatureVo();
            f.setGeometry(new MultiPointVo(positions(random, 2)));
            for (String lang : languages) {
                f.getProperties().put("name:" + lang, wordsName(random, words));
                f.getProperties().put("name:1:" + lang, wordsName(random, words));
            }
            FeatureCollectionVo fc = new FeatureCollectionVo();
            fc.setFeatures(new FeatureVo[] { f });
            String text = converter.toPlainText(fc);
            String diff = difference(fc, converter.fromPlainText(text));
            if (diff != null) {
                failures.add(diff + " in:\n" + text);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of 300 differ; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
    }

    private static String wordsName(Random r, String[] words) {
        int n = 1 + r.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[r.nextInt(words.length)]);
        }
        return sb.toString();
    }
}
