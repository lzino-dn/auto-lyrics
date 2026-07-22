package com.autolyrics.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus

/**
 * Reads lyrics from a user-chosen folder on the device (picked via the
 * system folder picker, persisted as a SAF tree URI). Files are matched
 * by name against the current track:
 *
 *   "Artist - Title.lrc"  /  "Title - Artist.lrc"  /  "Title.lrc"
 *
 * (also .txt). Matching is case-insensitive and tolerant of extra
 * whitespace and dash variants. Files with LRC timestamps are shown
 * synced; anything else is shown as plain lyrics.
 */
object LocalLrcStore {

    const val PREF_KEY_FOLDER_URI = "local_lyrics_folder_uri"

    private val EXTENSIONS = listOf(".lrc", ".txt")

    data class LocalLyrics(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String
    )

    fun getLyrics(context: Context, title: String, artist: String): LocalLyrics? {
        if (title.isBlank()) return null
        val treeUri = folderUri(context) ?: return null
        val fileUri = try {
            findMatch(context, treeUri, title, artist)
        } catch (_: Exception) {
            null
        } ?: return null

        val content = try {
            context.contentResolver.openInputStream(fileUri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (content.isBlank()) return null

        val synced = LrcParser.parse(content)
        if (synced.any { it.text != "♪" && it.text.isNotBlank() }) {
            return LocalLyrics(synced, LyricsStatus.FOUND, "Local · Synced")
        }

        val plain = content.lines()
            .filter { it.isNotBlank() }
            .map { line -> LyricLine(0L, line.trim()) }
        if (plain.isNotEmpty()) {
            return LocalLyrics(plain, LyricsStatus.PLAIN_ONLY, "Local · Plain")
        }
        return null
    }

    private fun folderUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY_FOLDER_URI, null) ?: return null
        val uri = Uri.parse(stored)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (stillGranted) uri else null
    }

    private fun findMatch(context: Context, treeUri: Uri, title: String, artist: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )

        val t = normalize(title)
        val a = normalize(artist)
        val exactNames = mutableListOf<String>()
        if (a.isNotBlank()) {
            exactNames.add("$a - $t")
            exactNames.add("$t - $a")
        }

        var titleOnlyMatch: Uri? = null
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val ext = EXTENSIONS.firstOrNull { name.lowercase().endsWith(it) } ?: continue
                val base = normalize(name.dropLast(ext.length))
                if (base in exactNames) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
                if (base == t && titleOnlyMatch == null) {
                    titleOnlyMatch = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
        }
        return titleOnlyMatch
    }

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
