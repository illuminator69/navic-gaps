package paige.navic.util

import java.text.Normalizer

actual fun toNfd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
