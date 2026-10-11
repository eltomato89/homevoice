package de.homevoice.assistant.text

/**
 * Turns spoken German numbers in subtitles into digits, the way a newspaper would write them:
 * "am vierundzwanzigsten Oktober um zehn Uhr dreißig" → "am 24. Oktober um 10:30 Uhr".
 * Small numbers (up to twelve) stay words unless a unit, time or date follows ("zwei Timer").
 */
object GermanNumbers {
    private val UNITS = mapOf(
        "null" to 0, "eins" to 1, "ein" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
        "sechs" to 6, "sieben" to 7, "sieb" to 7, "acht" to 8, "neun" to 9,
    )
    private val TEENS = mapOf(
        "zehn" to 10, "elf" to 11, "zwölf" to 12, "dreizehn" to 13, "vierzehn" to 14, "fünfzehn" to 15,
        "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
    )
    private val TENS = mapOf(
        "zwanzig" to 20, "dreißig" to 30, "vierzig" to 40, "fünfzig" to 50,
        "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
    )
    private val ORDINAL_IRREGULAR = mapOf("erst" to 1, "dritt" to 3, "siebt" to 7, "acht" to 8)
    private val ORDINAL_ENDINGS = setOf("e", "en", "er", "es", "em")
    private val MONTHS = setOf(
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august",
        "september", "oktober", "november", "dezember",
    )
    /** Words after which even small numbers become digits. */
    private val UNIT_WORDS = setOf(
        "uhr", "grad", "prozent", "euro", "cent", "minuten", "minute", "sekunden", "sekunde", "stunden",
        "stunde", "tage", "tagen", "wochen", "monate", "jahre", "jahren", "kilometer", "km", "meter",
        "kilowatt", "kilowattstunden", "kwh", "watt", "liter", "kilo", "kilogramm", "gramm", "mal",
    )

    private val WORD = Regex("""[A-Za-zÄÖÜäöüß]+|\d+(?:[.,]\d+)?|[^A-Za-zÄÖÜäöüß\d]+""")

    fun normalize(text: String): String {
        val tokens = WORD.findAll(text).map { it.value }.toMutableList()
        val words = tokens.indices.filter { tokens[it].first().isLetter() }
        fun next(i: Int, offset: Int = 1): String? = words.indexOf(i).let { p -> words.getOrNull(p + offset) }?.let { tokens[it] }
        fun nextIndex(i: Int, offset: Int = 1): Int? = words.indexOf(i).let { p -> words.getOrNull(p + offset) }

        val out = tokens.toMutableList()
        val consumed = BooleanArray(tokens.size)
        for (i in words) {
            if (consumed[i]) continue
            val word = tokens[i]
            val lower = word.lowercase()
            // "ein/eine/einen…" are mostly articles; only "eins" counts as a number on its own.
            if (lower in setOf("ein", "eine", "einen", "einem", "einer", "eines")) continue

            ordinal(lower)?.let { (value, _) ->
                val following = next(i)?.lowercase()
                if (value >= 13 || following in MONTHS) out[i] = "$value."
                return@let
            }
            if (out[i] != word) continue

            val value = cardinal(lower) ?: continue
            val following = next(i)?.lowercase()
            // "zehn Komma fünf" → "10,5"
            if (following == "komma") {
                val decimalIndex = nextIndex(i, 2)
                val decimals = decimalIndex?.let { decimalDigits(i, it, tokens, words) }
                if (decimals != null) {
                    out[i] = "$value,${decimals.first}"
                    clear(out, consumed, i + 1, decimals.second)
                    continue
                }
            }
            // "zehn Uhr dreißig" → "10:30 Uhr"
            if (following == "uhr" && value in 0..24) {
                val uhrIndex = nextIndex(i)!!
                val minuteIndex = nextIndex(i, 2)
                val minutes = minuteIndex?.let { cardinal(tokens[it].lowercase()) }
                if (minutes != null && minutes in 0..59) {
                    out[i] = "%d:%02d".format(value, minutes)
                    // keep "Uhr" after the time: "10:30 Uhr"
                    clear(out, consumed, i + 1, minuteIndex)
                    out[i] = out[i] + " " + tokens[uhrIndex]
                    continue
                }
                out[i] = value.toString()
                continue
            }
            if (value >= 13 || following in UNIT_WORDS || following in MONTHS) {
                out[i] = value.toString()
                if (following == "prozent") {
                    nextIndex(i)?.let { p -> out[p] = "%"; consumed[p] = true }
                }
            }
        }
        return out.joinToString("")
    }

    /** "fünf" (+ "drei" …) after "Komma": decimal digits and the index of the last token used. */
    private fun decimalDigits(start: Int, first: Int, tokens: List<String>, words: List<Int>): Pair<String, Int>? {
        val digits = StringBuilder()
        var last = -1
        var p = words.indexOf(first)
        while (p in words.indices) {
            val v = cardinal(tokens[words[p]].lowercase()) ?: break
            if (v > 9 && digits.isNotEmpty()) break
            digits.append(v)
            last = words[p]
            if (v > 9) break
            p++
        }
        return if (digits.isEmpty()) null else digits.toString() to last
    }

    private fun clear(out: MutableList<String>, consumed: BooleanArray, from: Int, to: Int) {
        for (k in from..to) {
            out[k] = ""
            consumed[k] = true
        }
    }

    /** Cardinal number words up to 999,999, e.g. "zweitausendsechsundzwanzig" → 2026. */
    fun cardinal(word: String): Int? {
        if (word.isEmpty() || word == "ein" || word == "sieb") return null
        return parseThousands(word)
    }

    private fun parseThousands(w: String): Int? {
        val idx = w.indexOf("tausend")
        if (idx < 0) return parseHundreds(w)
        val left = w.substring(0, idx)
        val right = w.substring(idx + "tausend".length)
        val multiplier = if (left.isEmpty()) 1 else parseHundreds(left) ?: return null
        val rest = if (right.isEmpty()) 0 else parseHundreds(right.removePrefix("und")) ?: return null
        return multiplier * 1000 + rest
    }

    private fun parseHundreds(w: String): Int? {
        val idx = w.indexOf("hundert")
        if (idx < 0) return parseBelow100(w)
        val left = w.substring(0, idx)
        val right = w.substring(idx + "hundert".length)
        // "neunzehnhundert…" (years) allows teens as multiplier.
        val multiplier = if (left.isEmpty()) 1 else parseBelow100(left)?.takeIf { it in 1..99 } ?: return null
        val rest = if (right.isEmpty()) 0 else parseBelow100(right.removePrefix("und")) ?: return null
        return multiplier * 100 + rest
    }

    private fun parseBelow100(w: String): Int? {
        UNITS[w]?.let { return if (w == "sieb") null else it }
        TEENS[w]?.let { return it }
        TENS[w]?.let { return it }
        val und = w.indexOf("und")
        if (und > 0) {
            val unit = UNITS[w.substring(0, und)] ?: return null
            val tens = TENS[w.substring(und + 3)] ?: return null
            if (unit == 0) return null
            return tens + unit
        }
        return null
    }

    /** Ordinal words ("vierundzwanzigsten", "dritte", "ersten") → value and ending. */
    fun ordinal(word: String): Pair<Int, String>? {
        // "erste", "dritten", "siebter", "achtes" …
        for ((stem, value) in ORDINAL_IRREGULAR) {
            if (word.startsWith(stem) && word.removePrefix(stem) in ORDINAL_ENDINGS) return value to word
        }
        val m = Regex("^(.+?)(ste|te)(n|r|s|m)?$").find(word) ?: return null
        val stem = m.groupValues[1]
        val suffix = m.groupValues[2]
        val value = cardinal(stem) ?: return null
        // 2–19 take "-te", from 20 on "-ste" ("zweite", "neunzehnte", "zwanzigste").
        val expected = if (value < 20) "te" else "ste"
        if (suffix != expected) return null
        return value to word
    }
}
