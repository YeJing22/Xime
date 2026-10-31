package com.kingzcheung.xime.service

/**
 * 日语方案的公共判定与输入态处理。
 *
 * 日语的编码是罗马音，一个假名对应 2~3 个字母，因此**不能在编码按字符处理**：
 * 退格要按假名整体删、显示要把未拼完的尾部还原成原字母。
 * 边界规则见 [com.kingzcheung.xime.rime.romajiDeleteStart] / [com.kingzcheung.xime.rime.japanesePreedit]。
 */
internal object JapaneseInput {

    /** 三个日语方案（都吃罗马音）：九宫格、26 键罗马字、26 键日文。 */
    private val SCHEMAS = setOf("japanese_kana", "jaroomaji", "japanese")

    fun isJapanese(schemaId: String): Boolean = schemaId in SCHEMAS
}
