package com.eyes.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemSettingsTest {
    @Test
    fun samsungHasIntent() {
        assertTrue(oemComponents("samsung").isNotEmpty())
    }

    @Test
    fun xiaomiHasIntent() {
        assertTrue(oemComponents("Xiaomi").any { it.pkg == "com.miui.securitycenter" })
    }

    @Test
    fun huaweiAndHonorHaveIntents() {
        assertTrue(oemComponents("HUAWEI").any { it.pkg == "com.huawei.systemmanager" })
        assertEquals(oemComponents("HUAWEI"), oemComponents("honor"))
    }

    @Test
    fun oppoVivoOneplusHaveIntents() {
        for (brand in listOf("OPPO", "vivo", "OnePlus")) assertTrue(brand, oemComponents(brand).isNotEmpty())
    }

    @Test
    fun unknownBrandEmpty() {
        assertTrue(oemComponents("Google").isEmpty())
    }

    @Test
    fun manufacturerMatchIsCaseInsensitive() {
        assertEquals(oemComponents("xiaomi"), oemComponents("XIAOMI"))
    }
}
