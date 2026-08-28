package eu.kanade.tachiyomi.source.model

/** Combined manga metadata and chapter update returned by TachiyomiX 1.6 sources. */
@Suppress("UNUSED")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
