package ua.numeric

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ua.numeric.Case.*
import ua.numeric.Gender.*

class UaNumericEngineTest {
    private val engine = UaNumericEngine

    @Test
    fun everyCalendarDayInALeapYear() {
        val months = listOf(
            "січня" to 31, "лютого" to 29, "березня" to 31, "квітня" to 30, "травня" to 31, "червня" to 30,
            "липня" to 31, "серпня" to 31, "вересня" to 30, "жовтня" to 31, "листопада" to 30, "грудня" to 31,
        )
        val days = listOf(
            "першого", "другого", "третього", "четвертого", "п’ятого", "шостого", "сьомого",
            "восьмого", "дев’ятого", "десятого", "одинадцятого", "дванадцятого", "тринадцятого",
            "чотирнадцятого", "п’ятнадцятого", "шістнадцятого", "сімнадцятого", "вісімнадцятого",
            "дев’ятнадцятого", "двадцятого", "двадцять першого", "двадцять другого", "двадцять третього",
            "двадцять четвертого", "двадцять п’ятого", "двадцять шостого", "двадцять сьомого",
            "двадцять восьмого", "двадцять дев’ятого", "тридцятого", "тридцять першого",
        )
        months.forEachIndexed { monthIndex, (name, length) ->
            for (day in 1..length) {
                val month = monthIndex + 1
                val expected = "${days[day - 1]} $name дві тисячі двадцять четвертого року"
                assertEquals("$day $name 2024 (date)", expected, engine.date(day, month, 2024))
                assertEquals("$day $name 2024 (numeric)", expected, engine.dateOrNull("$day.$month.2024"))
                assertEquals("$day $name 2024 (named)", expected, engine.dateOrNull("$day $name 2024 року"))
            }
        }
    }

    @Test
    fun calendarAndYearPhrases() = checks(
        Triple("9 червня 2077 року", "дев’ятого червня дві тисячі сімдесят сьомого року",
            { engine.dateOrNull("9 червня 2077 року") }),
        Triple("24 серпня 2024", "двадцять четвертого серпня дві тисячі двадцять четвертого року",
            { engine.dateOrNull("24 серпня 2024") }),
        Triple("1 січня 1991", "першого січня тисяча дев’ятсот дев’яносто першого року",
            { engine.date(1, 1, 1991) }),
        Triple("31 грудня 2000", "тридцять першого грудня двохтисячного року", { engine.date(31, 12, 2000) }),
        Triple("1 січня 2000", "першого січня двохтисячного року", { engine.date(1, 1, 2000) }),
        Triple("24 лютого 2022", "двадцять четвертого лютого дві тисячі двадцять другого року",
            { engine.date(24, 2, 2022) }),
        Triple("31 грудня 1999", "тридцять першого грудня тисяча дев’ятсот дев’яносто дев’ятого року",
            { engine.date(31, 12, 1999) }),
        Triple("11 листопада 1111", "одинадцятого листопада тисяча сто одинадцятого року",
            { engine.date(11, 11, 1111) }),
        Triple("22 лютого 2222", "двадцять другого лютого дві тисячі двісті двадцять другого року",
            { engine.date(22, 2, 2222) }),
        Triple("1.1.1000", "першого січня тисячного року", { engine.dateOrNull("1.1.1000") }),
        Triple("1.1.0001", "першого січня першого року", { engine.dateOrNull("1.1.0001") }),
        Triple("2100-06-09", "дев’ятого червня дві тисячі сотого року", { engine.dateOrNull("2100-06-09") }),
        Triple("month casing and no-break spaces", "дев’ятого червня дві тисячі сімдесят сьомого року",
            { engine.dateOrNull(" 09\u00a0ЧЕРВНЯ\u202f2077 р. ") }),
        Triple("localized slash format", "дев’ятого червня дві тисячі сімдесят сьомого року",
            { engine.dateOrNull("09/06/2077") }),
        Triple("29 лютого 2400", "двадцять дев’ятого лютого дві тисячі чотирьохсотого року",
            { engine.date(29, 2, 2400) }),
        Triple("у 2077 році", "у дві тисячі сімдесят сьомому році", { engine.yearOrNull("у 2077 році") }),
        Triple("з 1999 року", "з тисяча дев’ятсот дев’яносто дев’ятого року", { engine.yearOrNull("з 1999 року") }),
        Triple("до 2000 року", "до двохтисячного року", { engine.yearOrNull("до 2000 року") }),
        Triple("2001 року", "дві тисячі першого року", { engine.year(2001) }),
        Triple("У 2001 році", "У дві тисячі першому році", { engine.yearOrNull("У 2001 році") }),
        Triple("2077-й рік", "дві тисячі сімдесят сьомий рік", { engine.yearOrNull("2077-й рік") }),
        Triple("3000", "трьохтисячний рік", { engine.year(3000, YearForm.NOMINATIVE) }),
        Triple("5000", "п’ятитисячного року", { engine.year(5000) }),
        Triple("9999", "дев’ять тисяч дев’ятсот дев’яносто дев’ятому році",
            { engine.year(9999, YearForm.LOCATIVE) }),
        Triple("date with stress", "дев’я́того че́рвня дві́ ти́сячі сімдеся́т сьо́мого ро́ку",
            { engine.date(9, 6, 2077, stress = true) }),
    )

    @Test
    fun invalidDateAndYearTokens() {
        val invalidDates = listOf(
            "29.02.1900", "29.02.2100", "29 лютого 2023", "31 квітня 2024", "0 червня 2077",
            "31.13.2024", "1.0.2024", "1/1.2024", "2077-6-09", "2024-04-31", "1 січня 0000",
            "1/09/06/2077", "v09.06.2077", "09.06.2077.1",
            "9 червень 2077 року", "11 листопада", "24.08.20242", "9 червня 2077 року.",
        )
        val invalidYears = listOf(
            "0999 року", "10000 рік", "з 1999 році", "у 2077 року", "2077-й році", "2077 років",
            "у 2077", "2077 гривень", "цей 2077 рік",
        )
        for (d in invalidDates) assertNull("Expected null date for $d", engine.dateOrNull(d))
        for (y in invalidYears) assertNull("Expected null year for $y", engine.yearOrNull(y))
    }

    @Test
    fun cardinalAndOrdinalCases() = checks(
        Triple("cardinal 0", "нуль", { engine.cardinal(0) }),
        Triple("cardinal 1 feminine", "одна", { engine.cardinal(1, gender = FEMININE) }),
        Triple("cardinal 1 neuter", "одне", { engine.cardinal(1, gender = NEUTER) }),
        Triple("cardinal 1 alternative neuter", "одно", { engine.cardinal(1, gender = NEUTER, variant = Variant.ALTERNATIVE) }),
        Triple("cardinal 2 masculine", "два", { engine.cardinal(2) }),
        Triple("cardinal 2 feminine", "дві", { engine.cardinal(2, gender = FEMININE) }),
        Triple("cardinal 11", "одинадцять", { engine.cardinal(11) }),
        Triple("cardinal 12", "дванадцять", { engine.cardinal(12) }),
        Triple("cardinal 16", "шістнадцять", { engine.cardinal(16) }),
        Triple("cardinal 21 feminine accusative", "двадцять одну", { engine.cardinal(21, ACCUSATIVE, FEMININE) }),
        Triple("cardinal 22 feminine", "двадцять дві", { engine.cardinal(22, gender = FEMININE) }),
        Triple("cardinal 50 genitive", "п’ятдесяти", { engine.cardinal(50, GENITIVE) }),
        Triple("cardinal 100", "сто", { engine.cardinal(100) }),
        Triple("cardinal 200", "двісті", { engine.cardinal(200) }),
        Triple("cardinal 200 dative", "двомстам", { engine.cardinal(200, DATIVE) }),
        Triple("cardinal 200 instrumental", "двомастами", { engine.cardinal(200, INSTRUMENTAL) }),
        Triple("cardinal 400 locative", "чотирьохстах", { engine.cardinal(400, LOCATIVE) }),
        Triple("cardinal 1000", "тисяча", { engine.cardinal(1000) }),
        Triple("cardinal 2000", "дві тисячі", { engine.cardinal(2000) }),
        Triple("cardinal 1000000", "мільйон", { engine.cardinal(1_000_000) }),
        Triple("cardinal 2000000000", "два мільярди", { engine.cardinal(2_000_000_000) }),
        Triple("cardinal 102000 genitive", "ста двох тисяч", { engine.cardinal(102_000, GENITIVE) }),
        Triple("cardinal 2077 genitive", "двох тисяч сімдесяти семи", { engine.cardinal(2077, GENITIVE) }),
        Triple("cardinal 478659 genitive", "чотирьохсот сімдесяти восьми тисяч шестисот п’ятдесяти дев’яти",
            { engine.cardinal(478659, GENITIVE) }),
        Triple("cardinal 5 alternative genitive", "п’ятьох", { engine.cardinal(5, GENITIVE, variant = Variant.ALTERNATIVE) }),
        Triple("cardinal 5 primary instrumental", "п’ятьма", { engine.cardinal(5, INSTRUMENTAL) }),
        Triple("cardinal 5 alternative instrumental", "п’ятьома",
            { engine.cardinal(5, INSTRUMENTAL, variant = Variant.ALTERNATIVE) }),
        Triple("cardinal negative", "мінус дванадцять", { engine.cardinal(-12) }),
        Triple("cardinal signed minimum",
            "мінус дев’ять квінтильйонів двісті двадцять три квадрильйони триста сімдесят два трильйони " +
                "тридцять шість мільярдів вісімсот п’ятдесят чотири мільйони сімсот сімдесят п’ять тисяч вісімсот вісім",
            { engine.cardinal(Long.MIN_VALUE) }),
        Triple("ordinal 0 inanimate accusative alternative", "нульовий",
            { engine.ordinal(0, ACCUSATIVE, variant = Variant.ALTERNATIVE) }),
        Triple("ordinal 3 feminine accusative", "третю", { engine.ordinal(3, ACCUSATIVE, FEMININE) }),
        Triple("ordinal 23 feminine genitive", "двадцять третьої", { engine.ordinal(23, GENITIVE, FEMININE) }),
        Triple("ordinal plural inanimate accusative", "п’яті", { engine.ordinal(5, ACCUSATIVE, number = NumberForm.PLURAL) }),
        Triple("ordinal plural animate accusative", "п’ятих",
            { engine.ordinal(5, ACCUSATIVE, number = NumberForm.PLURAL, animacy = Animacy.ANIMATE) }),
        Triple("ordinal 1999 genitive", "тисяча дев’ятсот дев’яносто дев’ятого", { engine.ordinal(1999, GENITIVE) }),
        Triple("ordinal 2000 locative", "двохтисячному", { engine.ordinal(2000, LOCATIVE) }),
        Triple("ordinal 530000", "п’ятсоттридцятитисячний", { engine.ordinal(530000) }),
        Triple("ordinal 100000", "стотисячний", { engine.ordinal(100_000) }),
        Triple("ordinal 90000", "дев’яностотисячний", { engine.ordinal(90_000) }),
        Triple("ordinal 250000000", "двохсотп’ятдесятимільйонний", { engine.ordinal(250_000_000) }),
        Triple("ordinal 65000000", "шістдесятип’ятимільйонний", { engine.ordinal(65_000_000) }),
        Triple("ordinal 16000", "шістнадцятитисячний", { engine.ordinal(16_000) }),
        Triple("ordinal 1003000", "мільйон трьохтисячний", { engine.ordinal(1_003_000) }),
        Triple("ordinal 1000000", "мільйонний", { engine.ordinal(1_000_000) }),
        Triple("ordinal signed maximum genitive",
            "дев’ять квінтильйонів двісті двадцять три квадрильйони триста сімдесят два трильйони " +
                "тридцять шість мільярдів вісімсот п’ятдесят чотири мільйони сімсот сімдесят п’ять тисяч вісімсот сьомого",
            { engine.ordinal(Long.MAX_VALUE, GENITIVE) }),
    )

    @Test
    fun quantityContexts() {
        val student = CountedNoun(
            MASCULINE,
            NounForms("студент", "студента", "студентові", "студента", "студентом", "студенті"),
            NounForms("студенти", "студентів", "студентам", "студентів", "студентами", "студентах"),
            Animacy.ANIMATE,
        )
        val year = CountedNoun(
            MASCULINE,
            NounForms("рік", "року", "року", "рік", "роком", "році"),
            NounForms("роки", "років", "рокам", "роки", "роками", "роках"),
        )
        checks(
            Triple("21 студент", "двадцять один студент", { engine.count(21, student) }),
            Triple("22 студенти", "двадцять два студенти", { engine.count(22, student) }),
            Triple("25 студентів", "двадцять п’ять студентів", { engine.count(25, student) }),
            Triple("2 students animate accusative", "двох студентів", { engine.count(2, student, ACCUSATIVE) }),
            Triple("22 students animate accusative", "двадцять два студенти", { engine.count(22, student, ACCUSATIVE) }),
            Triple("21 students animate accusative", "двадцять одного студента", { engine.count(21, student, ACCUSATIVE) }),
            Triple("11 students", "одинадцять студентів", { engine.count(11, student) }),
            Triple("21 students dative", "двадцяти одному студентові", { engine.count(21, student, DATIVE) }),
            Triple("2000 students dative", "двом тисячам студентів", { engine.count(2000, student, DATIVE) }),
            Triple("2077 years genitive", "двох тисяч сімдесяти семи років", { engine.count(2077, year, GENITIVE) }),
            Triple("money 2077", "дві тисячі сімдесят сім гривень", { engine.money(BigDecimal("2077")) }),
            Triple("money 22.02", "двадцять дві гривні дві копійки", { engine.money(BigDecimal("22.02")) }),
            Triple("money 1.01", "одна гривня одна копійка", { engine.money(BigDecimal("1.01")) }),
            Triple("money with stress", "п’я́ть гри́вень п’я́ть копі́йок",
                { engine.money(BigDecimal("5.05"), stress = true) }),
            Triple("money paucal stress", "дві́ гри́вні дві́ копі́йки",
                { engine.money(BigDecimal("2.02"), stress = true) }),
            Triple("money -0.01", "мінус нуль гривень одна копійка", { engine.money(BigDecimal("-0.01")) }),
            Triple("money three trailing zeros", "одна гривня нуль копійок", { engine.money(BigDecimal("1.000"), true) }),
            Triple("midnight", "нульова година", { engine.time(0, 0) }),
            Triple("07:05", "сьома година п’ять хвилин", { engine.time(7, 5) }),
            Triple("23:22:01", "двадцять третя година двадцять дві хвилини одна секунда", { engine.time(23, 22, 1) }),
            Triple("01:00:02", "перша година дві секунди", { engine.time(1, 0, 2) }),
            Triple("1/2", "одна друга", { engine.fraction(1, 2) }),
            Triple("2/3", "дві третіх", { engine.fraction(2, 3) }),
            Triple("3/5 instrumental", "трьома п’ятими", { engine.fraction(3, 5, INSTRUMENTAL) }),
            Triple("2/3 dative", "двом третім", { engine.fraction(2, 3, DATIVE) }),
            Triple("1/20 accusative", "одну двадцяту", { engine.fraction(1, 20, ACCUSATIVE) }),
            Triple("-21/100", "мінус двадцять одна сота", { engine.fraction(-21, 100) }),
            Triple("2.50", "дві цілих п’ятдесят сотих", { engine.decimal(BigDecimal("2.50")) }),
            Triple("1.01", "одна ціла одна сота", { engine.decimal(BigDecimal("1.01")) }),
            Triple("-0.001", "мінус нуль цілих одна тисячна", { engine.decimal(BigDecimal("-0.001")) }),
            Triple("1E+3", "тисяча", { engine.decimal(BigDecimal("1E+3")) }),
        )
    }

    @Test
    fun typedInputsRejectValuesOutsideTheirContracts() {
        assertThrows(IllegalArgumentException::class.java) { engine.date(31, 4, 2024) }
        assertThrows(IllegalArgumentException::class.java) { engine.year(999) }
        assertThrows(IllegalArgumentException::class.java) { engine.year(10_000) }
        assertThrows(IllegalArgumentException::class.java) { engine.money(BigDecimal("1.001")) }
        assertThrows(IllegalArgumentException::class.java) { engine.money(BigDecimal("9223372036854775808")) }
        assertThrows(IllegalArgumentException::class.java) { engine.time(24, 0) }
        assertThrows(IllegalArgumentException::class.java) { engine.time(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { engine.time(1, 2, 60) }
        assertThrows(IllegalArgumentException::class.java) { engine.fraction(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { engine.decimal(BigDecimal("1E-19")) }
    }

    private fun checks(vararg cases: Triple<String, String, () -> String?>) {
        for ((label, expected, render) in cases) {
            assertEquals(label, expected, render())
        }
    }
}
