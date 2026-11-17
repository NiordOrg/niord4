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
package org.niord.core.portrayal;

import org.niord.core.portrayal.PortrayalDefinition.Buffer;
import org.niord.core.portrayal.PortrayalDefinition.Content;
import org.niord.core.portrayal.PortrayalDefinition.LineStyle;
import org.niord.core.portrayal.PortrayalDefinition.SelectRow;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalDefinition.StrokeSize;
import org.niord.core.portrayal.PortrayalDefinition.Style;
import org.niord.core.portrayal.PortrayalDefinition.Symbol;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and checks the portrayal definition, and derives its image version. Everything a renderer relies on is
 * checked here, so that an available definition is a consistent one: every name a style, the buffer or a select
 * row uses exists, every symbol has its file and a size in both size sets, and the last select row matches every
 * message. Keys the backend does not read are ignored, except in a select row, where a key it does not know would
 * make the row match messages the web maps do not.
 */
final class PortrayalDefinitionReader {

    /** The length of the image version, in hexadecimal characters */
    static final int IMAGE_VERSION_LENGTH = 12;

    /** A symbol file: a plain file name in the portrayal folder */
    private static final Pattern SYMBOL_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.svg");

    private static final Pattern HEX_COLOUR = Pattern.compile("#([0-9a-fA-F]{6})");

    private static final Pattern RGBA_COLOUR = Pattern.compile(
            "rgba\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d*\\.?\\d+)\\s*\\)");

    private static final Set<String> CAPS = Set.of("butt", "round", "square");
    private static final Set<String> JOINS = Set.of("bevel", "round", "miter");
    private static final Set<String> SELECT_ROW_KEYS = Set.of("mainType", "messageType", "style");

    /** Parses the definition; a duplicated key is an error rather than a silent last-one-wins */
    private static final ObjectMapper READER = JsonMapper.builderWithJackson2Defaults()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Writes the canonical form the image version is taken over: compact, keys sorted at every level */
    private static final ObjectMapper CANONICAL = JsonMapper.builderWithJackson2Defaults()
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    private PortrayalDefinitionReader() {
    }

    /**
     * Reads the definition.
     *
     * @param definition the definition JSON
     * @param symbolFiles returns the bytes of a symbol file by its name, or null when there is no such file
     * @return the definition
     * @throws IllegalArgumentException when the definition is not valid; the message says why
     */
    static Content read(byte[] definition, Function<String, byte[]> symbolFiles) {
        JsonNode root;
        try {
            root = READER.readTree(definition);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The definition is not valid JSON: " + e.getOriginalMessage(), e);
        }
        object(root, "the definition");

        String catalogue = root.path("catalogue").asString("");

        Map<String, Color> colours = new HashMap<>();
        object(root.get("colours"), "colours").properties().forEach(entry ->
                colours.put(entry.getKey(), colour(entry.getValue(), "colours." + entry.getKey())));

        Map<String, Symbol> symbols = new LinkedHashMap<>();
        Map<String, byte[]> files = new HashMap<>();
        object(root.get("symbols"), "symbols").properties().forEach(entry -> {
            String id = entry.getKey();
            String path = "symbols." + id;
            JsonNode node = object(entry.getValue(), path);
            String file = string(node.get("file"), path + ".file");
            if (!SYMBOL_FILE.matcher(file).matches()) {
                throw invalid(path + ".file", "must name an .svg file in the portrayal folder, not '" + file + "'");
            }
            double[] anchor = pair(node.get("anchor"), path + ".anchor");
            if (!files.containsKey(file)) {
                byte[] bytes = symbolFiles.apply(file);
                if (bytes == null) {
                    throw invalid(path + ".file", "names " + file + ", which is not in the portrayal folder");
                }
                files.put(file, bytes);
            }
            symbols.put(id, new Symbol(id, file, anchor[0], anchor[1]));
        });
        if (symbols.isEmpty()) {
            throw invalid("symbols", "is empty");
        }

        LineStyle lineStyle = lineStyle(object(root.get("lineStyle"), "lineStyle"));

        JsonNode sizes = object(root.get("sizes"), "sizes");
        SizeSet screen = sizeSet(sizes.get("screen"), "sizes.screen", false);
        SizeSet print = sizeSet(sizes.get("print"), "sizes.print", true);

        Map<String, Style> styles = new LinkedHashMap<>();
        object(root.get("styles"), "styles").properties().forEach(entry -> {
            String id = entry.getKey();
            String path = "styles." + id;
            JsonNode node = object(entry.getValue(), path);
            Style style = new Style(id,
                    colourName(node.get("stroke"), path + ".stroke", colours),
                    symbolId(node.get("point"), path + ".point", symbols, screen, print),
                    symbolId(node.get("tag"), path + ".tag", symbols, screen, print),
                    symbolId(node.get("approx"), path + ".approx", symbols, screen, print));
            styles.put(id, style);
        });
        if (styles.isEmpty()) {
            throw invalid("styles", "is empty");
        }

        JsonNode bufferNode = object(root.get("buffer"), "buffer");
        Buffer buffer = new Buffer(
                colourName(bufferNode.get("fill"), "buffer.fill", colours),
                colourName(bufferNode.get("stroke"), "buffer.stroke", colours));

        List<SelectRow> select = select(root.get("select"), styles);

        return new Content(catalogue, colours, symbols, files, lineStyle, screen, print, styles, buffer, select,
                imageVersion(root, styles, symbols, files));
    }

    /**
     * Parses a colour as the definition writes it: {@code #RRGGBB}, or {@code rgba(r, g, b, a)} with r, g and b
     * from 0 to 255 and the alpha from 0 to 1.
     *
     * @param value the colour
     * @return the colour, its alpha rounded to the nearest of 0 to 255
     * @throws IllegalArgumentException when the value is neither
     */
    static Color parseColour(String value) {
        String colour = value.trim();
        Matcher hex = HEX_COLOUR.matcher(colour);
        if (hex.matches()) {
            return new Color(Integer.parseInt(hex.group(1), 16));
        }
        Matcher rgba = RGBA_COLOUR.matcher(colour);
        if (rgba.matches()) {
            int r = Integer.parseInt(rgba.group(1));
            int g = Integer.parseInt(rgba.group(2));
            int b = Integer.parseInt(rgba.group(3));
            double alpha = Double.parseDouble(rgba.group(4));
            if (r <= 255 && g <= 255 && b <= 255 && alpha <= 1) {
                return new Color(r, g, b, (int) Math.round(alpha * 255));
            }
        }
        throw new IllegalArgumentException("'" + value + "' is neither #RRGGBB nor rgba(r, g, b, a)");
    }

    /**
     * Returns the image version: see {@link PortrayalDefinition#imageVersion()}. The digest takes, each framed by
     * a label and its length, the canonical JSON of the image-relevant part of the definition, then every symbol
     * file a style uses in the order of the file names, then {@link PortrayalDefinition#RENDERER_REVISION}.
     */
    private static String imageVersion(JsonNode root, Map<String, Style> styles, Map<String, Symbol> symbols,
                                       Map<String, byte[]> files) {
        SortedSet<String> usedSymbols = new TreeSet<>();
        styles.values().forEach(style -> {
            usedSymbols.add(style.point());
            usedSymbols.add(style.tag());
            usedSymbols.add(style.approx());
        });

        ObjectNode relevant = CANONICAL.createObjectNode();
        relevant.set("colours", root.get("colours"));
        relevant.set("lineStyle", root.get("lineStyle"));
        relevant.putObject("sizes").set("print", root.get("sizes").get("print"));
        relevant.set("styles", root.get("styles"));
        relevant.set("buffer", root.get("buffer"));
        relevant.set("select", root.get("select"));
        ObjectNode usedSymbolNodes = relevant.putObject("symbols");
        SortedSet<String> usedFiles = new TreeSet<>();
        for (String id : usedSymbols) {
            JsonNode symbol = root.get("symbols").get(id);
            usedSymbolNodes.putObject(id)
                    .set("file", symbol.get("file"))
                    .set("anchor", symbol.get("anchor"));
            usedFiles.add(symbols.get(id).file());
        }

        MessageDigest digest = sha256();
        frame(digest, "definition", CANONICAL.writeValueAsBytes(relevant));
        for (String file : usedFiles) {
            frame(digest, "symbol " + file, withoutCarriageReturns(files.get(file)));
        }
        frame(digest, "renderer revision",
                Integer.toString(PortrayalDefinition.RENDERER_REVISION).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest()).substring(0, IMAGE_VERSION_LENGTH);
    }

    private static void frame(MessageDigest digest, String label, byte[] content) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
        digest.update(content);
    }

    /** Returns the bytes without their carriage returns, so a file checked out with CRLF hashes as with LF */
    static byte[] withoutCarriageReturns(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (byte b : bytes) {
            if (b != '\r') {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static LineStyle lineStyle(JsonNode node) {
        String cap = string(node.get("cap"), "lineStyle.cap");
        if (!CAPS.contains(cap)) {
            throw invalid("lineStyle.cap", "must be one of " + new TreeSet<>(CAPS) + ", not '" + cap + "'");
        }
        String join = string(node.get("join"), "lineStyle.join");
        if (!JOINS.contains(join)) {
            throw invalid("lineStyle.join", "must be one of " + new TreeSet<>(JOINS) + ", not '" + join + "'");
        }
        double miterLimit = number(node.get("miterLimit"), "lineStyle.miterLimit");
        if (miterLimit < 1) {
            throw invalid("lineStyle.miterLimit", "must be at least 1");
        }
        return new LineStyle(cap, join, miterLimit);
    }

    private static SizeSet sizeSet(JsonNode node, String path, boolean print) {
        object(node, path);
        String unit = string(node.get("unit"), path + ".unit");
        OptionalInt referenceImageSize = OptionalInt.empty();
        JsonNode reference = node.get("referenceImageSize");
        if (print || reference != null) {
            double value = positive(reference, path + ".referenceImageSize");
            if (value != Math.rint(value)) {
                throw invalid(path + ".referenceImageSize", "must be a whole number of pixels");
            }
            referenceImageSize = OptionalInt.of((int) value);
        }
        Map<String, SymbolSize> symbols = new HashMap<>();
        object(node.get("symbols"), path + ".symbols").properties().forEach(entry -> {
            String sizePath = path + ".symbols." + entry.getKey();
            double[] size = pair(entry.getValue(), sizePath);
            if (size[0] <= 0 || size[1] <= 0) {
                throw invalid(sizePath, "must be a positive width and height");
            }
            symbols.put(entry.getKey(), new SymbolSize(size[0], size[1]));
        });
        return new SizeSet(unit, referenceImageSize, symbols,
                stroke(node.get("line"), path + ".line"),
                positive(node.get("tagSpacing"), path + ".tagSpacing"),
                stroke(node.get("buffer"), path + ".buffer"));
    }

    private static StrokeSize stroke(JsonNode node, String path) {
        object(node, path);
        double width = positive(node.get("width"), path + ".width");
        JsonNode dashNode = node.get("dash");
        if (dashNode == null || !dashNode.isArray()) {
            throw invalid(path + ".dash", "must be an array of lengths");
        }
        List<Double> dash = new ArrayList<>();
        boolean anyPositive = false;
        for (JsonNode length : dashNode) {
            double value = number(length, path + ".dash");
            if (value < 0) {
                throw invalid(path + ".dash", "must hold no negative length");
            }
            anyPositive |= value > 0;
            dash.add(value);
        }
        if (!dash.isEmpty() && !anyPositive) {
            throw invalid(path + ".dash", "must hold a positive length, or be empty for a solid stroke");
        }
        return new StrokeSize(width, dash);
    }

    private static List<SelectRow> select(JsonNode node, Map<String, Style> styles) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("select", "must be a non-empty array of rows");
        }
        List<SelectRow> rows = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "select[" + i + "]";
            JsonNode row = object(node.get(i), path);
            for (String key : row.propertyNames()) {
                if (!SELECT_ROW_KEYS.contains(key)) {
                    throw invalid(path, "has the key '" + key + "'; a row has only " + new TreeSet<>(SELECT_ROW_KEYS));
                }
            }
            String style = string(row.get("style"), path + ".style");
            if (!styles.containsKey(style)) {
                throw invalid(path + ".style", "names " + style + ", which is not a style");
            }
            SelectRow selectRow = new SelectRow(
                    optionalString(row.get("mainType"), path + ".mainType"),
                    optionalString(row.get("messageType"), path + ".messageType"),
                    style);
            boolean last = i == node.size() - 1;
            if (last && !selectRow.keyless()) {
                throw invalid(path, "is the last row and must have no keys, so that it matches every message");
            }
            if (!last && selectRow.keyless()) {
                throw invalid(path, "has no keys and matches every message, so the rows after it are never used");
            }
            rows.add(selectRow);
        }
        return rows;
    }

    private static String colourName(JsonNode node, String path, Map<String, Color> colours) {
        String name = string(node, path);
        if (!colours.containsKey(name)) {
            throw invalid(path, "names " + name + ", which is not a colour");
        }
        return name;
    }

    private static String symbolId(JsonNode node, String path, Map<String, Symbol> symbols, SizeSet screen,
                                   SizeSet print) {
        String id = string(node, path);
        if (!symbols.containsKey(id)) {
            throw invalid(path, "names " + id + ", which is not a symbol");
        }
        if (!screen.symbols().containsKey(id)) {
            throw invalid(path, "names " + id + ", which has no size in sizes.screen");
        }
        if (!print.symbols().containsKey(id)) {
            throw invalid(path, "names " + id + ", which has no size in sizes.print");
        }
        return id;
    }

    private static Color colour(JsonNode node, String path) {
        try {
            return parseColour(string(node, path));
        } catch (IllegalArgumentException e) {
            throw invalid(path, e.getMessage());
        }
    }

    private static JsonNode object(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "must be an object");
        }
        return node;
    }

    private static String string(JsonNode node, String path) {
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw invalid(path, "must be a non-empty string");
        }
        return node.stringValue();
    }

    private static String optionalString(JsonNode node, String path) {
        return node == null ? null : string(node, path);
    }

    private static double number(JsonNode node, String path) {
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw invalid(path, "must be a number");
        }
        return node.doubleValue();
    }

    private static double positive(JsonNode node, String path) {
        double value = number(node, path);
        if (value <= 0) {
            throw invalid(path, "must be greater than 0");
        }
        return value;
    }

    private static double[] pair(JsonNode node, String path) {
        if (node == null || !node.isArray() || node.size() != 2) {
            throw invalid(path, "must be an array of two numbers");
        }
        return new double[] {number(node.get(0), path), number(node.get(1), path)};
    }

    private static IllegalArgumentException invalid(String path, String problem) {
        return new IllegalArgumentException(path + " " + problem);
    }
}
