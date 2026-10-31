package paige.navic.ui.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * A dry run of [readCover] over a whole library, against the prototype the rules were fitted with
 * (I-057). Off unless NAVIC_COVER_PARITY_DIR names a directory holding `pixels.bin.gz` (per cover: a
 * big-endian u16 id length, u16 width, u16 height, the id, then width * height RGB bytes) and
 * `expected.jsonl` (the prototype's decision per cover: id, dark, kind, fieldHue, bg, tone, accent).
 * It writes `kotlin-disagreements.txt` beside them and fails when the two disagree on a page's side or
 * kind on more than 1 in 200 covers. JVM-only (it reads files), so androidHostTest.
 */
class CoverReadingParityTest {

	@Test
	fun theKotlinRulesDecideAsThePrototypeDid() {
		val dir = System.getenv("NAVIC_COVER_PARITY_DIR")?.let(::File) ?: return
		val expected = File(dir, "expected.jsonl").readLines().filter { it.isNotBlank() }.associate { line ->
			val o = Json.parseToJsonElement(line).jsonObject
			o.getValue("id").jsonPrimitive.content to o
		}
		var total = 0
		var sideOrKind = 0
		var background = 0
		var tone = 0
		var accent = 0
		val notes = StringBuilder()
		DataInputStream(GZIPInputStream(File(dir, "pixels.bin.gz").inputStream()).buffered()).use { input ->
			while (true) {
				val idLength = try { input.readUnsignedShort() } catch (_: EOFException) { break }
				val width = input.readUnsignedShort()
				val height = input.readUnsignedShort()
				val id = ByteArray(idLength).also { input.readFully(it) }.decodeToString()
				val rgb = ByteArray(width * height * 3).also { input.readFully(it) }
				val argb = IntArray(width * height) { i ->
					(0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
				}
				val want = expected[id] ?: continue
				val got = readCover(argb, width, height) ?: continue
				total++
				val wantDark = want.getValue("dark").jsonPrimitive.booleanOrNull
				val wantKind = want.getValue("kind").jsonPrimitive.content
				val wantTone = want.getValue("tone").jsonPrimitive.floatOrNull ?: 0f
				if (wantDark != got.dark || wantKind != got.fieldKind.name) {
					sideOrKind++
					notes.append("$id side/kind: want dark=$wantDark $wantKind tone $wantTone, got dark=${got.dark} ${got.fieldKind} tone ${got.tone}\n")
				}
				if (want.getValue("bg").jsonPrimitive.booleanOrNull != got.background) background++
				if (kotlin.math.abs(wantTone - got.tone) > 0.5f) tone++
				val wantAccent = want["accent"]?.jsonPrimitive?.takeIf { it.isString }?.content
				if ((wantAccent == null) != (got.accent == null)) accent++
				else if (wantAccent != null && got.accent != null) {
					val wantHue = oklabHueChroma(wantAccent.removePrefix("#").toInt(16)).first
					if (hueGap(wantHue, oklabHueChroma(got.accent).first) > 15f) {
						accent++
						notes.append("$id accent: want $wantAccent, got #${(got.accent and 0xFFFFFF).toString(16).padStart(6, '0')}\n")
					}
				}
			}
		}
		val summary = "covers $total; side or kind differ $sideOrKind; background differs $background; tone off by > 0.5: $tone; accent hue off by > 15 deg: $accent\n"
		File(dir, "kotlin-disagreements.txt").writeText(summary + notes)
		println(summary)
		assertTrue(total > 0, "no covers read")
		assertTrue(sideOrKind * 200 <= total, summary)
	}
}
