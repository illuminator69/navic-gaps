package paige.navic.util

/**
 * Worbus P4 N2: how every piece shows its version, `0.4.0 (abc1234)`. A build that is not a release
 * build (debug) says `0.4.0-dev (abc1234)`. [commit] is `BuildInfo.GIT_COMMIT`; a blank one reads
 * "unknown".
 */
fun versionLine(version: String, commit: String, release: Boolean): String {
	val number = version.trim().removePrefix("v")
	val shown = if (release) number else "$number-dev"
	return "$shown (${commit.trim().ifEmpty { "unknown" }})"
}
