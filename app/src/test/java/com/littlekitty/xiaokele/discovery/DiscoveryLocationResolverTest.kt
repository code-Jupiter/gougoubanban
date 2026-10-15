package com.littlekitty.xiaokele.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryLocationResolverTest {

    @Test
    fun normalizesChineseCityAndDistrictToSupportedSearchAreas() {
        assertEquals(
            "liandu",
            DiscoveryLocationResolver.normalizeSearchArea(
                adminArea = "浙江省",
                subAdminArea = "丽水市",
                locality = "丽水市",
                subLocality = "莲都区",
            ),
        )
        assertEquals(
            "hangzhou",
            DiscoveryLocationResolver.normalizeSearchArea(
                adminArea = "浙江省",
                subAdminArea = "杭州市",
                locality = "杭州市",
                subLocality = "西湖区",
            ),
        )
    }

    @Test
    fun keepsAValidBroaderAreaWhenDetailedFieldsAreMissing() {
        assertEquals(
            "浙江省",
            DiscoveryLocationResolver.normalizeSearchArea(
                adminArea = " 浙江省 ",
                subAdminArea = null,
                locality = null,
                subLocality = null,
            ),
        )
        assertNull(
            DiscoveryLocationResolver.normalizeSearchArea(
                adminArea = null,
                subAdminArea = " ",
                locality = null,
                subLocality = null,
            ),
        )
    }

    @Test
    fun coordinateFallbackRecognizesTheEmulatorLocation() {
        assertEquals(
            "hangzhou",
            DiscoveryLocationResolver.knownAreaForCoordinates(30.274148, 120.15515),
        )
    }
}
