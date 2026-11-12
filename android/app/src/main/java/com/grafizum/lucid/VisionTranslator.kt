package com.grafizum.lucid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Outcome(
    val source: String = "",
    val translated: String? = null,
    val detected: String? = null,
    val provider: String = "",
    /** How the source is pronounced, as the engine spells it (romaji for Japanese). */
    val reading: String? = null,
    val empty: Boolean = false,
    val sameLanguage: Boolean = false,
    val error: String? = null,
    /** Where the chosen text sat in the captured frame (only for look mode). */
    val box: Rect? = null,
    val blocks: Int = 0,
)

/**
 * Everything runs on the device: ML Kit language identification and
 * translation, plus ML Kit text recognition for the look-mode fallback.
 * A network fallback only kicks in if an on-device model cannot be downloaded.
 */
class VisionTranslator(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val languageId = LanguageIdentification.getClient()

    /**
     * Translate a string we already have (text access mode).
     *
     * [sourceHint] pins the source language (a language code) instead of
     * auto-detecting — used when the card's language chips are changed live.
     * Pass null to detect.
     */
    suspend fun translate(
        source: String,
        target: String,
        onStatus: (String) -> Unit = {},
        sourceHint: String? = null,
        /** The rest of the screen: sent with the line, never shown. */
        screenContext: String = "",
    ): Outcome {
        val text = tidy(source.trim())
        if (text.length < 2) return Outcome(source = text, empty = true)

        onStatus("Detecting")

        // Bounded: a detector that never answers must not leave the card
        // spinning forever — an unknown language stays unknown.
        val tag = sourceHint ?: detect(text)
        // Never call an unnamed language English. Doing that labelled Spanish
        // lines "English" and, when English was also the target, handed the
        // original back as its own translation.
        val from = tag?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "und" }

        if (from != null && from == target) {
            return Outcome(
                source = text,
                translated = text,
                detected = from,
                provider = "on-device",
                sameLanguage = true,
            )
        }

        onStatus("Translating")

        // The offline models are fast but weak on languages like Japanese, so
        // the online engine leads when the user allows it.
        if (Prefs.onlineEngine(context)) {
            // With neighbours in the query the engine would detect on the whole
            // screen instead of the line, so the line's own detected language
            // pins the source whenever context is travelling with it.
            val sl = sourceHint ?: if (screenContext.isNotBlank() && from != null) from else "auto"
            google(text, target, sl, screenContext)?.let { result ->
                return Outcome(
                    source = text,
                    translated = result.text,
                    detected = sourceHint ?: result.detected ?: from,
                    provider = "online",
                    reading = result.reading,
                )
            }
        }

        // Without a source language the offline models have nothing to work
        // from; only the online engine can still have a go.
        val onDevice = if (from == null) {
            null
        } else {
            try {
                translateOnDevice(text, from, target)
            } catch (err: Exception) {
                null
            }
        }

        if (onDevice != null) {
            return Outcome(source = text, translated = onDevice, detected = from, provider = "on-device")
        }

        // No engine left. Say so instead of showing a rough machine guess.
        return Outcome(
            source = text,
            detected = from,
            error = when {
                from == null ->
                    "Couldn't tell which language that is. Tap the left chip and pick it, or switch ENGINE to Online."
                Prefs.onlineEngine(context) ->
                    "Couldn't reach the translation service. Check the connection, or switch ENGINE to On-device in Lucid."
                else ->
                    "No offline model for this language pair yet. Switch ENGINE to Online in Lucid."
            },
        )
    }

    /**
     * What this line is written in, or null when the detector will not commit.
     * A few words often come back undecided, so the ranked answer is taken
     * only when it is confident; an unknown line is never assumed English.
     */
    private suspend fun detect(text: String): String? {
        val primary = runCatching {
            withTimeoutOrNull(2500) { languageId.identifyLanguage(text).await() }
        }.getOrNull()
        if (!primary.isNullOrBlank() && primary != "und") return primary

        val ranked = runCatching {
            withTimeoutOrNull(1200) { languageId.identifyPossibleLanguages(text).await() }
        }.getOrNull().orEmpty()
        val best = ranked.firstOrNull() ?: return null
        return best.languageTag.takeIf {
            best.confidence >= 0.5f && it.isNotBlank() && it != "und"
        }
    }

    /**
     * Look mode: read the frame, then keep the one line of text the lens point
     * is resting on — a line, not the paragraph around it, and never the
     * nearest one somewhere else in the frame.
     *
     * [slack] widens each line by that many pixels before the test, because the
     * lens may be aimed at the gap between two lines and a line's own box is
     * cut tight to its glyphs.
     */
    suspend fun read(
        bitmap: Bitmap,
        point: Point,
        target: String,
        slack: Int = 0,
        onStatus: (String) -> Unit = {},
    ): Outcome {
        onStatus("Reading")

        val result = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (err: Exception) {
            return Outcome(error = "Couldn't read that area.")
        }

        // Lines rather than blocks: a paragraph is one block, and the lens is
        // over one of its lines. Reading the block would answer for the whole
        // paragraph whatever the lens touched.
        val lines = result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = tidy(line.text)
                if (text.length < 2) null else Block(text, box)
            }

        if (lines.isEmpty()) return Outcome(empty = true)

        val chosen = pick(lines, point, slack) ?: return Outcome(empty = true)

        val outcome = translate(chosen.text, target, onStatus)
        return outcome.copy(box = chosen.box, blocks = lines.size)
    }

    private data class Block(val text: String, val box: Rect)

    /** The line the point is on — or nothing at all. */
    private fun pick(lines: List<Block>, point: Point, slack: Int): Block? {
        val hit = UnderLens.innermost(lines.map { box(it.box) }, point.x, point.y, slack)
            ?: return null
        // The innermost box decides, so a caption nested inside a bigger text
        // frame still answers for itself.
        return lines.firstOrNull { box(it.box) == hit }
    }

    private fun box(rect: Rect) = UnderLens.Box(rect.left, rect.top, rect.right, rect.bottom)

    /**
     * Chat screens carry timestamps, delivery ticks and edge artifacts that read
     * as noise once translated. Drop the trailing ones from each line; fall back
     * to the raw text if that leaves nothing usable.
     */
    fun tidy(raw: String): String {
        val ticks = Regex("""[\s\u00a0]*[\u2713\u2714]{1,3}[\s\u00a0]*$""")
        val clock = Regex("""[\s\u00a0]*\b\d{1,2}[:.]\d{2}[\s\u00a0]*(?:[AaPp]\.?[Mm]\.?)?[\s\u00a0]*$""")
        val leadingClock = Regex("""^[\s\u00a0]*\b\d{1,2}[:.]\d{2}[\s\u00a0]*(?:[AaPp]\.?[Mm]\.?)?[\s\u00a0]*""")
        // Chat bubbles often sit next to a retry row that is not part of the message.
        val retry = Regex("""[\s\u00a0]*(?:Tap to retry|Retry)[\s\u00a0]*$""", RegexOption.IGNORE_CASE)
        val stray = Regex("""[\s\u00a0]*[/\\|\u00b7]+[\s\u00a0]*$""")
        // Web pages carry citation markers such as [1] or [12] inline.
        val citations = Regex("""\[\d{1,3}]""")

        val lines = raw.lines()
            .map { line ->
                line.replace(citations, "")
                    .replace(ticks, "")
                    .replace(clock, "")
                    .replace(ticks, "")
                    .replace(retry, "")
                    .replace(leadingClock, "")
                    .replace(stray, "")
                    .replace(Regex("""[\s\u00a0]{2,}"""), " ")
                    .trim()
            }
            .filter { it.isNotEmpty() }

        val cleaned = lines.joinToString("\n").trim()
        return if (cleaned.length >= 2) cleaned else raw.trim()
    }

    private suspend fun translateOnDevice(text: String, source: String, target: String): String =
        withContext(Dispatchers.Default) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()

            val translator = Translation.getClient(options)
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                translator.translate(text).await()
            } finally {
                translator.close()
            }
        }

    /** What the online engine answered: the translation, and what it noticed. */
    private data class Online(val text: String, val detected: String?, val reading: String?)

    /** One round trip: the answer, the language the engine saw, and its readings. */
    private data class Fetched(val text: String, val detected: String?, val readings: List<String>)

    /**
     * Google's public translate endpoint: no key, no signup, and far better on
     * Japanese than the on-device models. Returns translated text, the detected
     * code, and — asked for with dt=rm — how the source is pronounced.
     *
     * With [context] the neighbours travel too: the line under the lens first,
     * the rest of the screen behind it, one per line. The answer is used only
     * when the engine kept those lines apart — otherwise the line is asked for
     * on its own — so the card can never be shown a neighbour's sentence.
     */
    private suspend fun google(
        text: String,
        target: String,
        sl: String,
        context: String = "",
    ): Online? =
        withContext(Dispatchers.IO) {
            val clipped = if (text.length > 1500) text.substring(0, 1500) else text
            val lines = Contexts.query(context, clipped)
            val joined = lines.joinToString("\n")

            if (lines.size <= 1) {
                // Nothing else to send: one request asks for the translation
                // and the reading together, exactly as it always has.
                val answer = request(joined, target, sl, reading = true)
                    ?: return@withContext null
                if (answer.text.isBlank()) return@withContext null
                return@withContext Online(
                    answer.text,
                    answer.detected,
                    answer.readings.joinToString(" ").ifBlank { null },
                )
            }

            val answer = request(joined, target, sl, reading = true) ?: return@withContext null
            val picked = Contexts.resolve(lines, answer.text)
            if (picked != null) {
                // A reading row describes one line. They are trusted over the
                // kanji only when there is exactly one row per line sent; if the
                // engine mixed them, the line's reading is asked for again on
                // its own rather than a neighbour's being drawn over this text.
                val reading = answer.readings.takeIf { it.size == lines.size }?.firstOrNull()
                    ?: request(clipped, target, sl, reading = true)?.readings?.firstOrNull()
                return@withContext Online(picked, answer.detected, reading?.trim()?.ifBlank { null })
            }

            // The engine did not keep the lines apart. Ask again for the line on
            // its own: a wrong sentence shown is worse than a second request.
            val plain = request(clipped, target, sl, reading = true) ?: return@withContext null
            if (plain.text.isBlank()) return@withContext null
            return@withContext Online(
                plain.text,
                plain.detected,
                plain.readings.joinToString(" ").ifBlank { null },
            )
        }

    /** One request for a block of lines, whatever it holds. */
    private fun request(
        query: String,
        target: String,
        sl: String,
        reading: Boolean,
    ): Fetched? =
        runCatching {
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=" + URLEncoder.encode(sl, "UTF-8") +
                "&dt=t" + (if (reading) "&dt=rm" else "") +
                "&tl=" + URLEncoder.encode(target, "UTF-8") +
                "&q=" + URLEncoder.encode(query, "UTF-8")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Lucid")
                connectTimeout = TimeUnit.SECONDS.toMillis(4).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(6).toInt()
            }

            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(body)
                val segments = root.optJSONArray(0) ?: return@runCatching null

                val out = StringBuilder()
                val readings = ArrayList<String>()
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONArray(index) ?: continue
                    // optString answers "null" for a JSON null, so the null
                    // has to be checked for — otherwise it lands in the
                    // translation as the word "null".
                    if (!segment.isNull(0)) {
                        val piece = segment.optString(0)
                        if (piece.isNotEmpty()) {
                            out.append(piece)
                            continue
                        }
                    }
                    // A reading row translates nothing: index 0 is empty and
                    // index 3 holds the source spelled out in Latin letters.
                    val spoken = if (segment.length() > 3 && !segment.isNull(3)) segment.optString(3) else ""
                    if (spoken.isNotBlank()) readings += spoken
                }

                val detected = if (root.length() > 2 && !root.isNull(2)) {
                    root.getString(2).substringBefore('-').ifBlank { null }
                } else {
                    null
                }

                Fetched(out.toString().trim(), detected, readings)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /**
     * Word by word: the meaning of each word of a language written without
     * spaces. All the words travel in one request; anything that does not come
     * back lined up is dropped rather than paired with the wrong word.
     */
    suspend fun gloss(
        source: String,
        lang: String?,
        target: String,
    ): List<Words.Pair> = withContext(Dispatchers.IO) {
        val terms = Words.terms(source, lang)
        if (terms.size < 2) return@withContext emptyList()

        runCatching {
            val connection = (URL(Words.request(terms, target, lang)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Lucid")
                connectTimeout = TimeUnit.SECONDS.toMillis(4).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(6).toInt()
            }

            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val meanings = Words.parse(body, terms.size) ?: return@runCatching emptyList()
                Words.pairs(terms, meanings)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    fun close() {
        runCatching { recognizer.close() }
        runCatching { languageId.close() }
    }
}
