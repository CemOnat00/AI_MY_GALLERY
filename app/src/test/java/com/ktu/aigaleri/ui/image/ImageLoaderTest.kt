package com.ktu.aigaleri.ui.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageLoaderTest {
    @Test fun smallImage_isNotSampled() = assertEquals(1, calculateInSampleSize(200, 100, 256))

    @Test fun exactlyMax_isNotSampled() = assertEquals(1, calculateInSampleSize(2048, 1000, 2048))

    @Test fun largeImage_isSampledByPowerOfTwo() = assertEquals(4, calculateInSampleSize(4000, 3000, 1024))

    @Test fun usesLongestSide() = assertEquals(2, calculateInSampleSize(1000, 3000, 2048))

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveMax_throws() { calculateInSampleSize(10, 10, 0) }
}
