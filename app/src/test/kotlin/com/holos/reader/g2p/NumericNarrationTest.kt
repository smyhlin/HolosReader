package com.holos.reader.g2p

import com.holos.reader.tts.SupertonicLanguageSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class NumericNarrationTest {
    @Test
    fun distinguishesDecimalCommasAndGroupedAmounts() {
        check("Значення 1,234.", "Значення одна ціла двісті тридцять чотири тисячні.")
        check("0,001", "нуль цілих одна тисячна")
        check("10,000", "десять тисяч")
        check("1,234,567", "один мільйон двісті тридцять чотири тисячі п'ятсот шістдесят сім")
        check("1,234.56", "одна тисяча двісті тридцять чотири цілі п'ятдесят шість сотих")
        check("1 000", "одна тисяча")
        check("1\u202F234,5%", "одна тисяча двісті тридцять чотири цілі п'ять десятих відсотків")
        check("1,234%", "одна ціла двісті тридцять чотири тисячні відсотків")
        check("1\u00A0000\u2009000₴", "один мільйон гривень")
    }

    @Test
    fun retainsCurrencySignsAndDeclinesDecimalAmounts() {
        check("−100₴", "мінус сто гривень")
        check("-\$100", "мінус сто доларів")
        check("+€50", "плюс п'ятдесят євро")
        check("-12,50₴", "мінус дванадцять цілих п'ятдесят сотих гривні")
        check("1,5$", "одна ціла п'ять десятих долара")
        check("2₴", "дві гривні")
        check("5-10₴", "п'ять-десять гривень")
        check("2,2", "дві цілі дві десяті")
        check("4,04", "чотири цілі чотири соті")
        check("12,2", "дванадцять цілих дві десяті")
    }

    @Test
    fun readsClockComponentsTogether() {
        check("1:00", "одна година")
        check("21:00", "двадцять одна година")
        check("9:05", "дев'ять годин нуль п'ять хвилин")
        check("12:30:45", "дванадцять годин тридцять хвилин сорок п'ять секунд")
        check("1:00:02", "одна година нуль хвилин нуль дві секунди")
        check("16:9", "шістнадцять:дев'ять")
        check("99:12:01", "дев'яносто дев'ять:дванадцять:один")
    }

    @Test
    fun declinesMeasurementsAndUnitAbbreviations() {
        check("1%", "один відсоток")
        check("+2%", "плюс два відсотки")
        check("−1 °C", "мінус один градус Цельсія")
        check("4°F", "чотири градуси Фаренгейта")
        check("11%", "одинадцять відсотків")
        check("−5%", "мінус п'ять відсотків")
        check("+2‰", "плюс два проміле")
        check("1 тис.", "одна тисяча.")
        check("2 млн.", "два мільйони.")
        check("1 км", "один кілометр")
        check("2 кг", "два кілограми")
        check("1 хв", "одна хвилина")
        check("2 год.", "дві години.")
        check("1 сек", "одна секунда")
        check("21 ст.", "двадцять одне століття.")
        check("100 грн.", "сто гривень.")
        check("2 годинники", "два годинники")
    }

    @Test
    fun distinguishesDatesFromGameStats() {
        val date = "двадцять другого серпня дві тисячі двадцять п'ятого року"
        check("22/08/2025", date)
        check("22.08.2025", date)
        check("29/02/2024", "двадцять дев'ятого лютого дві тисячі двадцять четвертого року")
        check("29/02/2025", "двадцять дев'ять/два/дві тисячі двадцять п'ять")
        check("120/150", "сто двадцять на сто п'ятдесят")
        check("1/2", "один на два")
    }

    @Test
    fun spellsNamedDatesIsoDatesYearPhrasesAndOrdinalsViaUaNumericEngine() {
        check("9 червня 2077 року", "дев'ятого червня дві тисячі сімдесят сьомого року")
        check("9 червня 2077", "дев'ятого червня дві тисячі сімдесят сьомого року")
        check("2077-06-09", "дев'ятого червня дві тисячі сімдесят сьомого року")
        check("у 2077 році", "у дві тисячі сімдесят сьомому році")
        check("2077-й рік", "дві тисячі сімдесят сьомий рік")
        check("2001 року", "дві тисячі першого року")
        check("з 1999 року", "з тисяча дев'ятсот дев'яносто дев'ятого року")
        check("1-й, 2-га, 3-тє, 2000-го", "перший, друга, третє, двохтисячного")
        check("1 000 000 000 000", "трильйон")
    }

    @Test
    fun readsDateSuffixesAndPreservesSentencePunctuation() {
        val date = "дев'ятого червня дві тисячі сімдесят сьомого року"
        check("Запис від 09.06.2077 року.", "Запис від $date.")
        check("Запис від 2077-06-09 р.", "Запис від $date.")
        check("9 червня 2077 р. Він повернувся.", "$date. Він повернувся.")
        check("9 червня 2077 р. він повернувся.", "$date він повернувся.")
        check("9 червня 2077 р., потім виїхав.", "$date, потім виїхав.")
        check("9 че́рвня 2077 ро́ку", date)
        check("Це сталося 1.01.0999 року.", "Це сталося першого січня дев'ятсот дев'яносто дев'ятого року.")
    }

    @Test
    fun expandsExplicitYearEndingsAndAbbreviationsInContext() {
        val whenYear = "У дві тисячі сімдесят сьомому році"
        check("У 2077 р. він повернувся.", "$whenYear він повернувся.")
        check("У 2077‑му році.", "$whenYear.")
        check("У 2077 ро́ці", whenYear)
        check("Від 2077-го року.", "Від дві тисячі сімдесят сьомого року.")
    }

    @Test
    fun speaksSignedLongLimitsAndDigitsBeyondThem() {
        check(
            "−9223372036854775808",
            "мінус дев'ять квінтильйонів двісті двадцять три квадрильйони триста сімдесят два трильйони " +
                "тридцять шість мільярдів вісімсот п'ятдесят чотири мільйони сімсот сімдесят п'ять тисяч вісімсот вісім",
        )
        check("+99999999999999999999", "плюс " + List(20) { "дев'ять" }.joinToString(" "))
        check("1 001", "одна тисяча один")
        check("1 001 001", "один мільйон одна тисяча один")
    }

    @Test
    fun inflectsLargerStarRatingsInRunningText() {
        check("Ранг ★★★☆☆☆☆☆☆☆☆", "Ранг три з одинадцяти зірок")
    }

    @Test
    fun recognizesUkrainianCalendarPhrasesBeforeRoutingMixedLanguage() {
        val text = SupertonicLanguageSplitter.prepareParagraph("Вийшло оновлення Shadow Step 09.06.2077 року.")
        val spans = SupertonicLanguageSplitter.split(text, true)

        assertEquals(SupertonicLanguageSplitter.LanguageSpan("Shadow Step", "en"), spans[1])
        assertEquals(
            SupertonicLanguageSplitter.LanguageSpan("дев'ятого червня дві тисячі сімдесят сьомого року.", "uk"),
            spans[2],
        )
        val english = "Shadow Step launched on 2077-06-09."
        assertEquals(english, SupertonicLanguageSplitter.prepareParagraph(english))
    }

    private fun check(input: String, expected: String) {
        assertEquals(input, expected, UkrainianTextNormalizer.normalize(input))
    }
}
