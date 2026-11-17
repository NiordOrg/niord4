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
package org.niord.web.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.util.GlobalMercator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tile range {@link AtonTileRestService} serves: zoom 0 to {@link AtonTileRestService#MAX_ZOOM},
 * and at zoom z the columns and rows 0 to 2^z - 1. The handler refuses everything else before it
 * searches, caches or writes anything; {@code AtonTileRestServiceITTest} covers that on the wire.
 */
class AtonTileRestServiceTest {

    @Test
    void theSingleTileOfZoomZeroIsServed() {
        assertTrue(AtonTileRestService.isServedTile(0, 0, 0));
        assertFalse(AtonTileRestService.isServedTile(0, 1, 0));
        assertFalse(AtonTileRestService.isServedTile(0, 0, 1));
    }

    @Test
    void everyZoomLevelServesItsOwnColumnsAndRowsOnly() {
        for (int z = 0; z <= AtonTileRestService.MAX_ZOOM; z++) {
            int last = (1 << z) - 1;
            assertTrue(AtonTileRestService.isServedTile(z, 0, 0), "z=" + z);
            assertTrue(AtonTileRestService.isServedTile(z, last, last), "z=" + z);
            assertFalse(AtonTileRestService.isServedTile(z, last + 1, 0), "z=" + z);
            assertFalse(AtonTileRestService.isServedTile(z, 0, last + 1), "z=" + z);
            assertFalse(AtonTileRestService.isServedTile(z, -1, 0), "z=" + z);
            assertFalse(AtonTileRestService.isServedTile(z, 0, -1), "z=" + z);
        }
    }

    @Test
    void zoomLevelsOutsideTheServedRangeAreRefused() {
        assertFalse(AtonTileRestService.isServedTile(-1, 0, 0));
        assertFalse(AtonTileRestService.isServedTile(Integer.MIN_VALUE, 0, 0));
        assertFalse(AtonTileRestService.isServedTile(AtonTileRestService.MAX_ZOOM + 1, 0, 0));
        // 1 << 32 == 1 in Java, so an unbounded zoom would have wrapped back to a one-tile level
        assertFalse(AtonTileRestService.isServedTile(32, 0, 0));
        assertFalse(AtonTileRestService.isServedTile(Integer.MAX_VALUE, 0, 0));
    }

    @Test
    void theDeepestServedTileKeepsTheMercatorPixelArithmeticInsideTheIntRange() {
        // The tile bounds multiply the column and row by the tile size; at MAX_ZOOM the far edge of
        // the last tile must still be a positive int, or the bounds would wrap to another tile.
        int last = (1 << AtonTileRestService.MAX_ZOOM) - 1;
        long farEdge = (long) (last + 1) * GlobalMercator.TILE_SIZE;
        assertTrue(farEdge <= Integer.MAX_VALUE);

        double[] bounds = new GlobalMercator().TileLatLonBounds(last, last, AtonTileRestService.MAX_ZOOM);
        assertTrue(bounds[0] < bounds[2], "south edge below the north edge");
        assertTrue(bounds[1] < bounds[3], "west edge left of the east edge");
    }
}
