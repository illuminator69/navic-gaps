package paige.navic.util

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun toNfd(value: String): String =
	(value as NSString).decomposedStringWithCanonicalMapping
