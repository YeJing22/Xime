package com.kingzcheung.xime.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 界面语言始终跟随系统语言。
 *
 * - 不提供 App 内语言开关，也不使用系统级 per-app locale（避免残留设置锁死语言）。
 * - 译文只维护 `values-zh-rHK`；zh-MO 与 zh-HK 用词一致，统一归一化为 zh-HK。
 * - Activity 与 IME Service 通过 [wrap] 覆盖 Context，保证设置页与键盘同语言。
 */
object AppLocale {

    /**
     * 当前生效的 LocaleList：始终取系统语言（忽略 per-app locale）。
     * App 内已不提供语言开关，避免残留的系统级应用语言把界面锁在旧语言。
     */
    fun currentLocales(): LocaleList =
        normalizeLocales(android.content.res.Resources.getSystem().configuration.locales)

    /** 是否香港繁体（zh-HK / zh-MO）。 */
    fun isHongKong(): Boolean {
        val locales = currentLocales()
        if (locales.size() == 0) return false
        val first = locales[0]
        return first.language == "zh" && first.country.equals("HK", ignoreCase = true)
    }

    /** zh-MO 与 zh-HK 文案一致，统一走香港资源。 */
    fun normalize(locale: Locale): Locale =
        if (locale.language == "zh" && locale.country.equals("MO", ignoreCase = true)) {
            Locale.forLanguageTag("zh-HK")
        } else {
            locale
        }

    /** 包装 Context，使其 resources/configuration 使用系统语言；[config] 用于配置变更时传入新配置。 */
    fun wrap(base: Context, config: Configuration? = null): Context {
        val cfg = Configuration(config ?: base.resources.configuration)
        cfg.setLocales(currentLocales())
        return ContextWrapper(base.createConfigurationContext(cfg))
    }

    private fun normalizeLocales(list: LocaleList): LocaleList {
        val normalized = (0 until list.size()).map { normalize(list[it]) }
        val allSame = normalized.indices.all { normalized[it] == list[it] }
        return if (allSame) list else LocaleList(*normalized.toTypedArray())
    }
}
