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

import jakarta.inject.Inject;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.gvt.renderer.ImageRenderer;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.XMLResourceDescriptor;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.svg.SVGDocument;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The symbols of the portrayal definition as raster images, for the backend's map images: each symbol rasterised
 * once per size it is drawn at, and kept.
 *
 * <p>A symbol is rasterised as a browser draws it into a map: the SVG is laid out at its own size (its
 * {@code width} and {@code height}, the viewBox fitted inside) and that picture is scaled to the size it is drawn
 * at, in each direction on its own. Batik rasterises it ({@link ImageTranscoder}, anti-aliased, pure strokes) into
 * an ARGB image of whole pixels, which the map image draws at a whole-pixel position without resampling.
 *
 * <p>The first rasterisation loads Batik, which takes a noticeable time; {@link #rasteriseAll} does it for every
 * symbol of a size set, which the map images do on a thread of their own while the application starts, so the
 * first map image does not wait for it ({@link #warmUp} starts such a thread). A request for a sprite that is being
 * rasterised waits for that rasterisation rather than doing it again.
 *
 * <p>This class lives beside the definition rather than with the map-image renderer: it turns the definition's
 * symbols into images and knows nothing of maps.
 */
@Component
public class PortrayalSymbols {

    private static final Logger log = LoggerFactory.getLogger(PortrayalSymbols.class);

    /**
     * Batik keeps parser and CSS state in static caches without promising that they are thread-safe, so one
     * symbol is rasterised at a time. It happens once per symbol and size.
     */
    private static final Object RASTERISING = new Object();

    @Inject
    PortrayalDefinition portrayal;

    private final Map<SpriteKey, CompletableFuture<BufferedImage>> sprites = new ConcurrentHashMap<>();

    /** A symbol at a size in whole pixels */
    private record SpriteKey(String symbolId, int width, int height) {
    }

    /**
     * Returns the symbols of a definition, for a caller outside the Spring context such as a test or a tool
     * @param portrayal the definition, which must be available
     * @return the symbols
     */
    public static PortrayalSymbols of(PortrayalDefinition portrayal) {
        PortrayalSymbols symbols = new PortrayalSymbols();
        symbols.portrayal = Objects.requireNonNull(portrayal);
        return symbols;
    }

    /**
     * Returns a symbol as a raster image of the given size, each length rounded to whole pixels. The image is
     * rasterised on the first request for the symbol at that size and the same instance is returned afterwards;
     * a caller must not change it.
     *
     * @param symbolId the symbol id
     * @param size the size it is drawn at
     * @return an ARGB image of the symbol, not premultiplied
     * @throws IllegalArgumentException when the definition has no such symbol
     * @throws IllegalStateException when the symbol cannot be rasterised
     */
    public BufferedImage sprite(String symbolId, SymbolSize size) {
        SpriteKey key = new SpriteKey(symbolId, pixels(size.width()), pixels(size.height()));
        CompletableFuture<BufferedImage> sprite = sprites.get(key);
        if (sprite == null) {
            CompletableFuture<BufferedImage> mine = new CompletableFuture<>();
            sprite = sprites.putIfAbsent(key, mine);
            if (sprite == null) {
                sprite = mine;
                try {
                    mine.complete(rasterise(key));
                } catch (RuntimeException e) {
                    // Not kept: a later request tries again
                    sprites.remove(key, mine);
                    mine.completeExceptionally(e);
                }
            }
        }
        try {
            return sprite.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /**
     * Rasterises every symbol of a size set at its size on a background thread, so that no request waits for the
     * first rasterisation. A request that arrives meanwhile waits for the sprite it needs if it is being
     * rasterised, and rasterises it itself if it is not yet.
     *
     * @param sizes the size set whose symbols are rasterised
     * @return completes when every symbol is rasterised, exceptionally when one cannot be
     */
    public CompletableFuture<Void> warmUp(SizeSet sizes) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // Start-up work has no calling request: the thread gets a job id of its own (MdcContext)
        Thread.ofPlatform().daemon().name("portrayal-symbols").start(MdcContext.withFreshJobId(() -> {
            try {
                rasteriseAll(sizes);
                done.complete(null);
            } catch (RuntimeException e) {
                log.error("Cannot rasterise the portrayal symbols for the map images: {}", e.getMessage(), e);
                done.completeExceptionally(e);
            }
        }));
        return done;
    }

    /**
     * Rasterises every symbol of a size set at its size on the calling thread, for a caller that prepares the map
     * images on a thread of its own; {@link #warmUp} starts one.
     *
     * @param sizes the size set whose symbols are rasterised
     * @throws IllegalStateException when a symbol cannot be rasterised
     */
    public void rasteriseAll(SizeSet sizes) {
        long start = System.nanoTime();
        sizes.symbols().forEach(this::sprite);
        log.info("Rasterised the {} portrayal symbols for the map images in {} ms", sizes.symbols().size(),
                (System.nanoTime() - start) / 1_000_000);
    }

    /** Returns how many sprites are rasterised or being rasterised: for tests */
    int spriteCount() {
        return sprites.size();
    }

    /** A length in whole pixels, at least one */
    private static int pixels(double length) {
        return (int) Math.max(1, Math.round(length));
    }

    /** Rasterises a symbol with Batik */
    private BufferedImage rasterise(SpriteKey key) {
        byte[] svg = portrayal.symbolSvg(key.symbolId());
        String file = portrayal.symbol(key.symbolId()).file();
        synchronized (RASTERISING) {
            try {
                SVGDocument document = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
                        .createSVGDocument("file:" + PortrayalDefinition.FOLDER + file, new ByteArrayInputStream(svg));
                fitAsABrowserDraws(document.getRootElement(), file);
                SpriteTranscoder transcoder = new SpriteTranscoder();
                transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, (float) key.width());
                transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, (float) key.height());
                transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);
                transcoder.transcode(new TranscoderInput(document), new TranscoderOutput());
                BufferedImage image = transcoder.image;
                if (image == null || image.getWidth() != key.width() || image.getHeight() != key.height()) {
                    throw new IllegalStateException("Batik drew no " + key.width() + " x " + key.height() + " image");
                }
                return image;
            } catch (IOException | TranscoderException | RuntimeException e) {
                throw new IllegalStateException("Cannot rasterise the portrayal symbol " + file + " at "
                        + key.width() + " x " + key.height() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Makes the document draw as a browser draws the file scaled to another size. A browser lays the viewBox out
     * inside the file's {@code width} x {@code height} (preserving its aspect, centred) and scales that picture
     * to the drawn size, in each direction on its own. The same mapping is the viewBox that picture shows,
     * stretched to the drawn size without preserving the aspect.
     */
    private static void fitAsABrowserDraws(Element svg, String file) {
        String[] box = svg.getAttributeNS(null, "viewBox").trim().split("[\\s,]+");
        double width = Double.parseDouble(svg.getAttributeNS(null, "width"));
        double height = Double.parseDouble(svg.getAttributeNS(null, "height"));
        if (box.length != 4 || !(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException(file + " needs a viewBox, a width and a height");
        }
        double minX = Double.parseDouble(box[0]);
        double minY = Double.parseDouble(box[1]);
        double boxWidth = Double.parseDouble(box[2]);
        double boxHeight = Double.parseDouble(box[3]);
        double scale = Math.min(width / boxWidth, height / boxHeight);
        double shownWidth = width / scale;
        double shownHeight = height / scale;
        svg.setAttributeNS(null, "viewBox", String.format(Locale.ROOT, "%s %s %s %s",
                minX - (shownWidth - boxWidth) / 2, minY - (shownHeight - boxHeight) / 2, shownWidth, shownHeight));
        svg.setAttributeNS(null, "preserveAspectRatio", "none");
    }

    /** Keeps the image Batik draws instead of encoding it */
    private static final class SpriteTranscoder extends ImageTranscoder {

        private BufferedImage image;

        @Override
        public BufferedImage createImage(int width, int height) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage image, TranscoderOutput output) {
            this.image = image;
        }

        /** Strokes as the browser draws them: at their exact position, not moved onto the pixel grid */
        @Override
        protected ImageRenderer createRenderer() {
            ImageRenderer renderer = super.createRenderer();
            RenderingHints hints = renderer.getRenderingHints();
            hints.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            hints.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            renderer.setRenderingHints(hints);
            return renderer;
        }
    }
}
