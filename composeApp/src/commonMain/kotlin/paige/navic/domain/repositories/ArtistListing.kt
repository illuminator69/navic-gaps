package paige.navic.domain.repositories

/**
 * Whether an artist list the server answered may replace the one Room holds (B-069).
 *
 * `getArtists` is parsed leniently (`SessionManager.fetchAlbumArtists`), and a Subsonic error
 * envelope — an HTTP 200 with no `artists` in it — comes out of that parse as an empty list.
 * Replacing Room's list with it drops every artist, and the artist tab stays empty until the
 * next pull that works. So an answer that lists nobody is believed only where Room holds nobody
 * either: a new server, a first sync. Against a library that has [held] artists it is a read
 * that failed, and the caller says so instead of pruning.
 *
 * Shared by the full pull and the artist tab's refresh (`DbRepository.syncArtists`) and by the
 * re-read after an artist merge (`resyncArtist`). A library that was really emptied on the
 * server reads the same way and keeps its artists here until the account is logged out; that
 * is the cheaper of the two mistakes.
 */
fun artistListMayReplace(listed: Int, held: Int): Boolean = listed > 0 || held == 0
