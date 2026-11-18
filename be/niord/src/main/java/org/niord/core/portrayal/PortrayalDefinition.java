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

import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * The portrayal definition of the message maps: the colours, symbols, sizes and styles with which a message's
 * geometry is drawn, and the rows that select a style for a message.
 *
 * <p>One definition serves the two web applications and the backend's map images: the file
 * {@code fe/shared-map/portrayal/portrayal.json} and the SVG symbols beside it, which the Maven build copies into
 * {@value #FOLDER} on the class path (see the module's {@code pom.xml}). The web maps draw with the
 * {@linkplain #screen() screen} size set, the map images with the {@linkplain #print() print} one. This class
 * holds the data only; how a geometry is drawn with it is the renderer's.
 *
 * <p>The definition is read once, on a thread of its own that starts when the bean is created, so that reading and
 * hashing it do not lengthen the application's start; every accessor waits until it is read. A definition that is
 * missing or invalid does not stop the application: an error is logged once, {@link #available()} answers false
 * and every other accessor throws {@link IllegalStateException}, so the renderer answers with its placeholder image
 * instead.
 *
 * <p>{@link #imageVersion()} names the version of the map images drawn with this definition. It is derived from
 * what the images depend on, so nothing is bumped by hand except {@link #RENDERER_REVISION}.
 */
@Component
public class PortrayalDefinition {

    /**
     * The revision of the Java drawing of the map images, part of {@link #imageVersion()}. Bump it whenever a change
     * to the backend's drawing code changes the images it makes from an unchanged definition, so that the images
     * made before are made again.
     */
    public static final int RENDERER_REVISION = 2;

    /** The class path folder of the definition and its symbols */
    static final String FOLDER = "portrayal/";

    /** The class path resource of the definition */
    static final String DEFINITION = FOLDER + "portrayal.json";

    private static final String UNUSABLE =
            "The portrayal definition {} cannot be used, so the message map images show their placeholder: {}";

    private static final Logger log = LoggerFactory.getLogger(PortrayalDefinition.class);

    /** The definition, or null when it could not be read */
    private final CompletableFuture<Content> content;

    /** Reads the definition from the class path on a thread of its own; see the class comment */
    PortrayalDefinition() {
        this(PortrayalDefinition::classPathResource,
                task -> Thread.ofPlatform().daemon().name("portrayal-definition").start(task));
    }

    /**
     * Reads the definition from resources on the given executor, as the bean reads it from the class path: a
     * definition that cannot be read is logged and comes back unavailable. Start-up work with no calling request:
     * the task gets a job id of its own (MdcContext).
     *
     * @param resources returns the bytes of a resource by its class path name, or null when there is none
     * @param reader    runs the reading
     */
    PortrayalDefinition(Function<String, byte[]> resources, Executor reader) {
        this.content = new CompletableFuture<>();
        reader.execute(MdcContext.withFreshJobId(() -> {
            Content read = null;
            try {
                read = readOrLog(resources);
            } finally {
                // Whatever happened, no accessor waits for ever: a definition not read is not available
                content.complete(read);
            }
        }));
    }

    private PortrayalDefinition(Content content) {
        this.content = CompletableFuture.completedFuture(content);
    }

    /**
     * Returns the definition read from the given inputs.
     *
     * @param definition the definition JSON
     * @param symbolFiles returns the bytes of a symbol file of the definition by its name, or null when it has none
     * @return the definition
     * @throws IllegalArgumentException when the definition is not valid; the message says why
     */
    static PortrayalDefinition of(byte[] definition, Function<String, byte[]> symbolFiles) {
        return new PortrayalDefinition(PortrayalDefinitionReader.read(definition, symbolFiles));
    }

    /**
     * Returns the definition read from resources as the bean reads them from the class path: a definition that
     * cannot be read is logged and comes back unavailable.
     *
     * @param resources returns the bytes of a resource by its class path name, such as {@value #DEFINITION}, or
     *                  null when there is none
     * @return the definition, available or not
     */
    static PortrayalDefinition fromResources(Function<String, byte[]> resources) {
        return new PortrayalDefinition(resources, Runnable::run);
    }

    /** Returns the definition read from the resources, or null after logging why it cannot be read */
    private static Content readOrLog(Function<String, byte[]> resources) {
        try {
            byte[] definition = resources.apply(DEFINITION);
            if (definition == null) {
                throw new IllegalArgumentException(DEFINITION + " is not on the class path");
            }
            Content content = PortrayalDefinitionReader.read(definition, file -> resources.apply(FOLDER + file));
            log.info("Portrayal definition '{}' read from the class path: map image version {}",
                    content.catalogue(), content.imageVersion());
            return content;
        } catch (IllegalArgumentException e) {
            // The message says what is wrong with the definition; a stack trace would add nothing
            log.error(UNUSABLE, DEFINITION, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.error(UNUSABLE, DEFINITION, e.getMessage(), e);
            return null;
        }
    }

    /** Returns the bytes of a class path resource, or null when there is none */
    static byte[] classPathResource(String name) {
        try (InputStream in = PortrayalDefinition.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name, e);
        }
    }

    /**
     * Returns whether the definition could be read. When it could not, every accessor but this one throws
     * {@link IllegalStateException}.
     * @return whether the definition is available
     */
    public boolean available() {
        return content.join() != null;
    }

    /**
     * Returns the version of the map images drawn with this definition: the first 12 hexadecimal characters of a
     * SHA-256 over what the images depend on - the definition without its screen size set and its catalogue name
     * (keys sorted, so neither the order of the keys nor the formatting of the file counts), the bytes of every
     * symbol a style uses (carriage returns removed) and {@link #RENDERER_REVISION}. A change to the screen sizes
     * alone leaves it unchanged.
     *
     * @return the image version, 12 lower-case hexadecimal characters
     */
    public String imageVersion() {
        return content().imageVersion();
    }

    /**
     * Returns the name of the portrayal catalogue the definition follows, for logs and documentation
     * @return the catalogue name, empty when the definition names none
     */
    public String catalogue() {
        return content().catalogue();
    }

    /**
     * Returns the style of a message: the style of the first select row that matches the message's main type and
     * type. The last row matches every message, so a message of an unknown or missing type gets that row's style.
     *
     * @param mainType the message's main type ({@code NW}, {@code NM}), or null
     * @param messageType the message's type (e.g. {@code TEMPORARY_NOTICE}), or null
     * @return the style
     */
    public Style styleFor(String mainType, String messageType) {
        Content c = content();
        for (SelectRow row : c.select()) {
            if (row.matches(mainType, messageType)) {
                return c.styles().get(row.style());
            }
        }
        // The reader requires a last row that matches every message
        throw new IllegalStateException("No select row matches " + mainType + "/" + messageType);
    }

    /**
     * Returns the styles by their id
     * @return the styles, an unmodifiable map
     */
    public Map<String, Style> styles() {
        return content().styles();
    }

    /**
     * Returns the select rows in their order; the first row that matches a message gives its style
     * @return the rows, an unmodifiable list
     */
    public List<SelectRow> select() {
        return content().select();
    }

    /**
     * Returns the symbols by their id
     * @return the symbols, an unmodifiable map
     */
    public Map<String, Symbol> symbols() {
        return content().symbols();
    }

    /**
     * Returns a symbol, whose anchor places it on a position
     * @param symbolId the symbol id, as a style names it
     * @return the symbol
     * @throws IllegalArgumentException when the definition has no such symbol
     */
    public Symbol symbol(String symbolId) {
        Symbol symbol = content().symbols().get(symbolId);
        if (symbol == null) {
            throw new IllegalArgumentException("The portrayal definition has no symbol " + symbolId);
        }
        return symbol;
    }

    /**
     * Returns the SVG document of a symbol, as its file holds it
     * @param symbolId the symbol id, as a style names it
     * @return a copy of the file's bytes
     * @throws IllegalArgumentException when the definition has no such symbol
     */
    public byte[] symbolSvg(String symbolId) {
        return content().symbolFiles().get(symbol(symbolId).file()).clone();
    }

    /**
     * Returns the size set of the web maps, in CSS pixels
     * @return the screen size set
     */
    public SizeSet screen() {
        return content().screen();
    }

    /**
     * Returns the size set of the map images, in image pixels of an image {@link SizeSet#referenceImageSize()}
     * pixels wide
     * @return the print size set
     */
    public SizeSet print() {
        return content().print();
    }

    /**
     * Returns the print size set for a map image of another size: {@link #print()} with every length scaled by
     * {@code imageSize / referenceImageSize}
     *
     * @param imageSize the width of the map image in pixels, greater than 0
     * @return the print size set for that image size
     */
    public SizeSet print(int imageSize) {
        if (imageSize <= 0) {
            throw new IllegalArgumentException("Invalid map image size " + imageSize);
        }
        SizeSet print = print();
        int reference = print.referenceImageSize().orElseThrow();
        return imageSize == reference ? print : print.scaledTo(imageSize);
    }

    /**
     * Returns a named colour of the definition
     * @param name the colour name, as a style or the buffer names it
     * @return the colour, with its alpha
     * @throws IllegalArgumentException when the definition has no such colour
     */
    public Color colour(String name) {
        Color colour = content().colours().get(name);
        if (colour == null) {
            throw new IllegalArgumentException("The portrayal definition has no colour " + name);
        }
        return colour;
    }

    /**
     * Returns the colours with which a buffer area around a message's geometry is drawn
     * @return the buffer's colour names
     */
    public Buffer buffer() {
        return content().buffer();
    }

    /**
     * Returns how the ends and corners of the strokes are drawn
     * @return the line style
     */
    public LineStyle lineStyle() {
        return content().lineStyle();
    }

    private Content content() {
        Content read = content.join();
        if (read == null) {
            throw new IllegalStateException("The portrayal definition " + DEFINITION + " is not available");
        }
        return read;
    }

    /**
     * A symbol of the definition.
     *
     * @param id the symbol id
     * @param file the name of its SVG file in the portrayal folder
     * @param anchorX the horizontal position of the point placed on the position, as a fraction of the symbol's
     *                width from its left edge
     * @param anchorY the vertical position of that point, as a fraction of the symbol's height from its top edge
     */
    public record Symbol(String id, String file, double anchorX, double anchorY) {
        public Symbol {
            Objects.requireNonNull(id);
            Objects.requireNonNull(file);
        }
    }

    /**
     * The size a symbol is drawn at.
     *
     * @param width the width, in the unit of its size set
     * @param height the height, in the unit of its size set
     */
    public record SymbolSize(double width, double height) {

        SymbolSize scaled(double factor) {
            return new SymbolSize(width * factor, height * factor);
        }
    }

    /**
     * The width and dash pattern of a stroke.
     *
     * @param width the stroke width, in the unit of its size set
     * @param dash the dash pattern: the lengths of the dashes and the gaps between them, alternately, starting
     *             with a dash; empty for a solid stroke
     */
    public record StrokeSize(double width, List<Double> dash) {
        public StrokeSize {
            dash = List.copyOf(dash);
        }

        /**
         * Returns the dash pattern as {@link BasicStroke} takes it
         * @return a new array of the dash lengths, or null for a solid stroke
         */
        public float[] dashPattern() {
            if (dash.isEmpty()) {
                return null;
            }
            float[] pattern = new float[dash.size()];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = dash.get(i).floatValue();
            }
            return pattern;
        }

        StrokeSize scaled(double factor) {
            return new StrokeSize(width * factor, dash.stream().map(length -> length * factor).toList());
        }
    }

    /**
     * A size set: the sizes with which a geometry is drawn on one medium.
     *
     * @param unit the unit of every length of the set: {@code css-px} for the screen, {@code image-px} for the map
     *             images
     * @param referenceImageSize the width of the map image the lengths are for; empty in the screen set
     * @param symbols the size of each symbol, by symbol id
     * @param line the stroke of a line or an area's outline
     * @param tagSpacing the distance between the tags along a line
     * @param buffer the stroke of a buffer area's outline
     */
    public record SizeSet(String unit, OptionalInt referenceImageSize, Map<String, SymbolSize> symbols,
                          StrokeSize line, double tagSpacing, StrokeSize buffer) {
        public SizeSet {
            Objects.requireNonNull(unit);
            Objects.requireNonNull(referenceImageSize);
            symbols = Map.copyOf(symbols);
            Objects.requireNonNull(line);
            Objects.requireNonNull(buffer);
        }

        /**
         * Returns the size of a symbol in this set
         * @param symbolId the symbol id
         * @return the size
         * @throws IllegalArgumentException when the set has no size for the symbol
         */
        public SymbolSize symbol(String symbolId) {
            SymbolSize size = symbols.get(symbolId);
            if (size == null) {
                throw new IllegalArgumentException("The " + unit + " size set has no size for symbol " + symbolId);
            }
            return size;
        }

        /** Returns this set for another image size, every length scaled by {@code imageSize / referenceImageSize} */
        SizeSet scaledTo(int imageSize) {
            double factor = (double) imageSize / referenceImageSize.orElseThrow();
            Map<String, SymbolSize> scaled = new HashMap<>();
            symbols.forEach((id, size) -> scaled.put(id, size.scaled(factor)));
            return new SizeSet(unit, OptionalInt.of(imageSize), scaled, line.scaled(factor), tagSpacing * factor,
                    buffer.scaled(factor));
        }
    }

    /**
     * A style: how the geometry of a message is drawn.
     *
     * @param id the style id
     * @param stroke the name of the colour of its lines and outlines
     * @param point the symbol id of a point
     * @param tag the symbol id of the tags along a line and in an area
     * @param approx the symbol id of the adornment of an approximate position
     */
    public record Style(String id, String stroke, String point, String tag, String approx) {
        public Style {
            Objects.requireNonNull(id);
            Objects.requireNonNull(stroke);
            Objects.requireNonNull(point);
            Objects.requireNonNull(tag);
            Objects.requireNonNull(approx);
        }
    }

    /**
     * The colours of a buffer area around a message's geometry.
     *
     * @param fill the name of the fill colour
     * @param stroke the name of the outline colour
     */
    public record Buffer(String fill, String stroke) {
        public Buffer {
            Objects.requireNonNull(fill);
            Objects.requireNonNull(stroke);
        }
    }

    /**
     * How the ends and corners of the strokes are drawn, named as the canvas of the web maps names them.
     *
     * @param cap {@code butt}, {@code round} or {@code square}
     * @param join {@code bevel}, {@code round} or {@code miter}
     * @param miterLimit the miter limit, at least 1
     */
    public record LineStyle(String cap, String join, double miterLimit) {
        public LineStyle {
            Objects.requireNonNull(cap);
            Objects.requireNonNull(join);
        }

        /**
         * Returns the cap as {@link BasicStroke} takes it
         * @return one of the {@code BasicStroke.CAP_*} constants
         */
        public int awtCap() {
            return switch (cap) {
                case "butt" -> BasicStroke.CAP_BUTT;
                case "square" -> BasicStroke.CAP_SQUARE;
                default -> BasicStroke.CAP_ROUND;
            };
        }

        /**
         * Returns the join as {@link BasicStroke} takes it
         * @return one of the {@code BasicStroke.JOIN_*} constants
         */
        public int awtJoin() {
            return switch (join) {
                case "bevel" -> BasicStroke.JOIN_BEVEL;
                case "miter" -> BasicStroke.JOIN_MITER;
                default -> BasicStroke.JOIN_ROUND;
            };
        }
    }

    /**
     * A select row: the style of the messages it matches. A key that the row leaves out matches every value.
     *
     * @param mainType the main type the row matches, or null for any
     * @param messageType the message type the row matches, or null for any
     * @param style the style id
     */
    public record SelectRow(String mainType, String messageType, String style) {
        public SelectRow {
            Objects.requireNonNull(style);
        }

        /**
         * Returns whether the row matches a message
         * @param mainType the message's main type, or null
         * @param messageType the message's type, or null
         * @return whether every key of the row equals the message's value
         */
        public boolean matches(String mainType, String messageType) {
            return (this.mainType == null || this.mainType.equals(mainType))
                    && (this.messageType == null || this.messageType.equals(messageType));
        }

        /**
         * Returns whether the row has no keys, and so matches every message
         * @return whether the row is keyless
         */
        public boolean keyless() {
            return mainType == null && messageType == null;
        }
    }

    /** The definition as read */
    record Content(
            String catalogue,
            Map<String, Color> colours,
            Map<String, Symbol> symbols,
            Map<String, byte[]> symbolFiles,
            LineStyle lineStyle,
            SizeSet screen,
            SizeSet print,
            Map<String, Style> styles,
            Buffer buffer,
            List<SelectRow> select,
            String imageVersion) {
        Content {
            colours = Map.copyOf(colours);
            symbols = Map.copyOf(symbols);
            symbolFiles = Map.copyOf(symbolFiles);
            styles = Map.copyOf(styles);
            select = List.copyOf(select);
        }
    }
}
