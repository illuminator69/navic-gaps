package paige.navic.domain.manager

import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.settings.StationCharacter

/**
 * A mix's songs as its page shows them (P5 round 2), built by [RadioManager.buildMix] and played
 * by [RadioManager.playMixBuild]. [station] is the station they came from, when the mix plays as
 * one: playing then continues that station rather than starting another, so what plays is what
 * the page showed.
 */
class MixBuild internal constructor(
	val mix: Mix,
	val songs: List<DomainSong>,
	internal val station: BuiltStation?
) {
	/** Whether more follows these songs when they are played. */
	val continues: Boolean get() = station != null && !station.answer.exhausted
}

/** A station started but not yet loaded: its answer, and the queue its seeds and first batch make. */
internal class BuiltStation(
	val answer: StationAnswer,
	val seed: StationSeed,
	val character: StationCharacter,
	val name: String,
	val queue: List<DomainSong>,
	/** The station's own songs in [queue] (the seeds left out), for its served set. */
	val served: List<String>
)
