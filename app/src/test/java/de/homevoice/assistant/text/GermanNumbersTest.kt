package de.homevoice.assistant.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GermanNumbersTest {
    private fun n(s: String) = GermanNumbers.normalize(s)

    @Test fun parsesCompoundCardinals() {
        assertEquals(24, GermanNumbers.cardinal("vierundzwanzig"))
        assertEquals(2026, GermanNumbers.cardinal("zweitausendsechsundzwanzig"))
        assertEquals(1975, GermanNumbers.cardinal("neunzehnhundertfünfundsiebzig"))
        assertEquals(312, GermanNumbers.cardinal("dreihundertzwölf"))
        assertEquals(101, GermanNumbers.cardinal("hundertundeins"))
        assertEquals(17, GermanNumbers.cardinal("siebzehn"))
        assertEquals(null, GermanNumbers.cardinal("zwanzigfach"))
        assertEquals(null, GermanNumbers.cardinal("hunderte"))
    }

    @Test fun datesAndOrdinals() {
        assertEquals("Am 24. Oktober hast du einen Termin.", n("Am vierundzwanzigsten Oktober hast du einen Termin."))
        assertEquals("Vom 3. März bis zum 1. April", n("Vom dritten März bis zum ersten April"))
        assertEquals("Der erste Platz geht an Thilo.", n("Der erste Platz geht an Thilo."))
        assertEquals("Das ist dein 25. Geburtstag.", n("Das ist dein fünfundzwanzigster Geburtstag."))
    }

    @Test fun timesUnitsAndDecimals() {
        assertEquals("Um 10:30 Uhr ist der Zahnarzt.", n("Um zehn Uhr dreißig ist der Zahnarzt."))
        assertEquals("Ab 7 Uhr regnet es.", n("Ab sieben Uhr regnet es."))
        assertEquals("Es sind 9,5 Grad.", n("Es sind neun Komma fünf Grad."))
        assertEquals("Die Batterie hat 45 %.", n("Die Batterie hat fünfundvierzig Prozent."))
        assertEquals("Ich stelle einen Timer auf 5 Minuten.", n("Ich stelle einen Timer auf fünf Minuten."))
        assertEquals("Die PV-Anlage liefert 2,4 Kilowatt.", n("Die PV-Anlage liefert zwei Komma vier Kilowatt."))
    }

    @Test fun keepsSmallNumbersAndArticles() {
        assertEquals("Du hast zwei Timer und ein Licht an.", n("Du hast zwei Timer und ein Licht an."))
        assertEquals("Das Auto hat 46 Prozent? Nein: 46 %.", n("Das Auto hat 46 Prozent? Nein: sechsundvierzig Prozent."))
        assertEquals("Im Jahr 2026 waren es 13 Tage.", n("Im Jahr zweitausendsechsundzwanzig waren es dreizehn Tage."))
    }

    @Test fun incompleteStreamingWordsStayUntouched() {
        assertEquals("Am vierund", n("Am vierund"))
    }
}
