package com.dreamglass.cos17;
import com.coloros16.liquidglass.hook.LockWallpaperGeometry;
import org.junit.Test;
import static org.junit.Assert.*;
public class LockWallpaperGeometryTest {
    @Test public void exactScreenMapsWithoutOffset() {
        assertArrayEquals(new float[]{0,0,1440,3168},LockWallpaperGeometry.cover(720,1584,1440,3168),.001f);
    }
    @Test public void wideWallpaperCropsHorizontallyWithoutStretch() {
        float[] r=LockWallpaperGeometry.cover(2000,2000,1000,2000);
        assertArrayEquals(new float[]{-500,0,1500,2000},r,.001f);
        // The screen center samples the wallpaper center, not its top-left corner.
        assertEquals(1000f,(500-r[0])*2000f/(r[2]-r[0]),.001f);
    }
    @Test public void landscapeRotationCropsVertically() {
        assertArrayEquals(new float[]{0,-750,2000,1250},LockWallpaperGeometry.cover(1000,1000,2000,500),.001f);
    }
    @Test public void invalidSourceCannotBecomeBlackOrStaleScreenFallback() {
        assertNull(LockWallpaperGeometry.cover(0,1000,1000,2000));
        assertNull(LockWallpaperGeometry.cover(1000,1000,1000,0));
    }
}
