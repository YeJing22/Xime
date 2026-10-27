package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLocaleTest {

    @Test
    fun `zh-MO 归一化为 zh-HK`() {
        assertEquals("zh-HK", AppLocale.normalize(Locale.forLanguageTag("zh-MO")).toLanguageTag())
    }

    @Test
    fun `其他语言保持不变`() {
        listOf("zh-Hans", "zh-TW", "en-US", "ja-JP").forEach { tag ->
            assertEquals(tag, AppLocale.normalize(Locale.forLanguageTag(tag)).toLanguageTag())
        }
    }
}
