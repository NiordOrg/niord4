/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.repo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the {@link IconSize} enum.
 *
 * <p>{@code getSize()} exposes the pixel size backing each constant, and the
 * {@code getIconSize(int)} factory maps an integer pixel size onto a constant,
 * defaulting to {@link IconSize#SIZE_64} for any value other than 32 or 128.
 * Note that the value 64 only resolves to {@code SIZE_64} via the default
 * branch, not via an explicit case.
 */
public class IconSizeTest {

    // --- getSize() ---------------------------------------------------------

    @Test
    public void getSizeReturnsBackingPixelSize() {
        assertEquals(32, IconSize.SIZE_32.getSize());
        assertEquals(64, IconSize.SIZE_64.getSize());
        assertEquals(128, IconSize.SIZE_128.getSize());
    }

    // --- getIconSize(int): explicit mappings -------------------------------

    @Test
    public void getIconSize32MapsToSize32() {
        assertSame(IconSize.SIZE_32, IconSize.getIconSize(32));
    }

    @Test
    public void getIconSize128MapsToSize128() {
        assertSame(IconSize.SIZE_128, IconSize.getIconSize(128));
    }

    @Test
    public void getIconSize64MapsToSize64() {
        // 64 is not an explicit switch case; it resolves to SIZE_64 via the default branch.
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(64));
    }

    // --- getIconSize(int): default branch ----------------------------------

    @Test
    public void getIconSizeZeroDefaultsToSize64() {
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(0));
    }

    @Test
    public void getIconSize16DefaultsToSize64() {
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(16));
    }

    @Test
    public void getIconSize100DefaultsToSize64() {
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(100));
    }

    @Test
    public void getIconSize256DefaultsToSize64() {
        // 256 is a plausible-but-unsupported icon size; it still falls through to the default.
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(256));
    }

    @Test
    public void getIconSizeNegativeDefaultsToSize64() {
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(-1));
    }

    // --- round trip --------------------------------------------------------

    @Test
    public void getIconSizeRoundTripsSupportedSizes() {
        // For the values backed by an explicit case (32, 128), getIconSize(getSize())
        // returns the same constant. SIZE_64 round-trips only via the default branch.
        assertSame(IconSize.SIZE_32, IconSize.getIconSize(IconSize.SIZE_32.getSize()));
        assertSame(IconSize.SIZE_64, IconSize.getIconSize(IconSize.SIZE_64.getSize()));
        assertSame(IconSize.SIZE_128, IconSize.getIconSize(IconSize.SIZE_128.getSize()));
    }
}
