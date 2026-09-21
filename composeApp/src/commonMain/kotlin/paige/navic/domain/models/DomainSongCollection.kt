package paige.navic.domain.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Immutable
@Serializable
sealed interface DomainSongCollection {
	val id: String
	val name: String?
	val coverArtId: String?
	val duration: Duration?
	val songCount: Int
	val songs: List<DomainSong>
}

/**
 * Non-null text for a collection's title, and for a song's artist credit.
 *
 * Upstream made [DomainSongCollection.name] and [DomainSong.artistName] nullable at alpha58 —
 * the latter because a song's credit now lives in `artists`, which can be empty. The fork puts
 * both straight into Text() in a couple of dozen places, so they share one fallback here rather
 * than each site inventing its own.
 */
val DomainSongCollection.displayName: String
	get() = name ?: "[unknown album]"

/** The song's credit line, falling back to its `artists` list and then to a placeholder. */
val DomainSong.creditText: String
	get() = artistName?.takeIf { it.isNotBlank() }
		?: artists.joinToString { it.name }.takeIf { it.isNotBlank() }
		?: "[unknown artist]"
