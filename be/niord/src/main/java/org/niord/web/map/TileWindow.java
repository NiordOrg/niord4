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

import org.niord.core.util.GlobalMercator;

import java.util.ArrayList;
import java.util.List;

/**
 * The rectangle of the Web Mercator pixel plane (EPSG:3857, 256 px tiles) that one map image shows, and the
 * mapping from EPSG:3857 metres to the image's pixels.
 *
 * <p>Pixel coordinates count from the north-west corner of the world, as XYZ tile numbers do (OpenStreetMap,
 * OpenLayers). {@link GlobalMercator} counts {@code y} from the south (TMS); only its
 * {@link GlobalMercator#Resolution(int) resolution} is used here.
 *
 * <p>The window's corner is a whole pixel, so every tile is drawn at a whole-pixel offset, never resampled. The
 * requested centre therefore lies within half a pixel of the image centre; a renderer that places marks with
 * {@link #imageX(double)} and {@link #imageY(double)} uses the same corner as the tiles, so marks and background
 * never drift apart.
 *
 * @param zoom    the zoom level, 0 to {@value #MAX_ZOOM}
 * @param originX the world pixel column of the image's left edge (negative or beyond the world's width only near
 *                the antimeridian, where the tile columns wrap)
 * @param originY the world pixel row of the image's top edge (outside the world only near the poles, where no
 *                tiles exist)
 * @param width   the image width in pixels, 1 to {@value #MAX_SIZE}
 * @param height  the image height in pixels, 1 to {@value #MAX_SIZE}
 */
public record TileWindow(int zoom, long originX, long originY, int width, int height) {

    /** The edge length of a tile in pixels. */
    public static final int TILE_SIZE = GlobalMercator.TILE_SIZE;

    /** The highest zoom level the pixel arithmetic supports; no tile server goes this far. */
    public static final int MAX_ZOOM = 30;

    /** The largest image edge; a map thumbnail is a fraction of this. */
    public static final int MAX_SIZE = 2048;

    /** Half the world's width in EPSG:3857 metres, as in {@link GlobalMercator}. */
    private static final double ORIGIN_SHIFT = 2 * Math.PI * 6378137 / 2.0;

    private static final GlobalMercator MERCATOR = new GlobalMercator();

    public TileWindow {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be 0 to " + MAX_ZOOM + ", was " + zoom);
        }
        if (width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "image size must be 1 to " + MAX_SIZE + " px, was " + width + " x " + height);
        }
    }

    /**
     * The window of a {@code width} x {@code height} image whose centre is the given EPSG:3857 point.
     *
     * @param centreX the centre's easting in EPSG:3857 metres
     * @param centreY the centre's northing in EPSG:3857 metres
     */
    public static TileWindow centredOn(double centreX, double centreY, int zoom, int width, int height) {
        if (!Double.isFinite(centreX) || !Double.isFinite(centreY)) {
            throw new IllegalArgumentException("the centre must be a finite EPSG:3857 point");
        }
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be 0 to " + MAX_ZOOM + ", was " + zoom);
        }
        double resolution = MERCATOR.Resolution(zoom);
        double px = (centreX + ORIGIN_SHIFT) / resolution;
        double py = (ORIGIN_SHIFT - centreY) / resolution;
        return new TileWindow(zoom, Math.round(px - width / 2.0), Math.round(py - height / 2.0), width, height);
    }

    /** Metres per pixel at this zoom level, measured at the equator. */
    public double resolution() {
        return MERCATOR.Resolution(zoom);
    }

    /** The image column of an EPSG:3857 easting (fractional; 0 is the left edge of the first pixel). */
    public double imageX(double x3857) {
        return (x3857 + ORIGIN_SHIFT) / resolution() - originX;
    }

    /** The image row of an EPSG:3857 northing (fractional; 0 is the top edge of the first pixel). */
    public double imageY(double y3857) {
        return (ORIGIN_SHIFT - y3857) / resolution() - originY;
    }

    /**
     * The tiles that intersect the window and where each goes in the image: exactly the tiles the image shows,
     * at this zoom level only. Tile columns wrap at the antimeridian, so the same tile may appear twice when the
     * window is wider than the world; rows beyond the poles have no tiles and are left out.
     */
    List<Placement> placements() {
        int tilesPerAxis = 1 << zoom;
        long firstColumn = Math.floorDiv(originX, TILE_SIZE);
        long lastColumn = Math.floorDiv(originX + width - 1, TILE_SIZE);
        long firstRow = Math.floorDiv(originY, TILE_SIZE);
        long lastRow = Math.floorDiv(originY + height - 1, TILE_SIZE);

        List<Placement> placements = new ArrayList<>();
        for (long row = firstRow; row <= lastRow; row++) {
            if (row < 0 || row >= tilesPerAxis) {
                continue;
            }
            for (long column = firstColumn; column <= lastColumn; column++) {
                placements.add(new Placement(
                        (int) Math.floorMod(column, tilesPerAxis),
                        (int) row,
                        (int) (column * TILE_SIZE - originX),
                        (int) (row * TILE_SIZE - originY)));
            }
        }
        return placements;
    }

    /**
     * One tile of the window.
     *
     * @param x  the XYZ tile column, wrapped into the world
     * @param y  the XYZ tile row
     * @param dx the image column of the tile's left edge
     * @param dy the image row of the tile's top edge
     */
    record Placement(int x, int y, int dx, int dy) {
    }
}
