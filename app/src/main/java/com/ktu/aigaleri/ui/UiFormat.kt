package com.ktu.aigaleri.ui

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Arayüz dili Türkçedir; sayı ve tarih biçimi cihaz dilinden bağımsız Türkçe olur (T-022). */
internal val TURKISH: Locale = Locale.forLanguageTag("tr-TR")

/** Binlik ayraçlı sayı ("1200" -> "1.200"). Banner ve indeks ekranı aynı biçimi kullanır. */
internal fun formatCount(value: Int, locale: Locale = TURKISH): String = NumberFormat.getIntegerInstance(locale).format(value)

/** "6 Eki 2026 14:32" biçimi (tarih orta, saat kısa). */
internal fun formatDateTime(epochMillis: Long, locale: Locale = TURKISH): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(epochMillis))
