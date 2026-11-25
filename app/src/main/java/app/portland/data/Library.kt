package app.portland.data

import org.json.JSONArray
import org.json.JSONObject

/** w/h are the display aspect (post-rotation), not necessarily the original pixel size. */
data class Pin(
    val id: String,
    val video: Boolean,
    val ext: String,
    val w: Int,
    val h: Int,
    val durationMs: Long = 0,
    val note: String = "",
    val boardId: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** AI description. null = not described yet, "" = the model gave nothing usable. */
    val caption: String? = null,
    /** Media + thumb are in R2. Local-only flag; never false for pins that came from R2. */
    val synced: Boolean = false,
    /** Named colours from [Colors.of], most dominant first. null = not analysed yet. Derived, so it never bumps updatedAt. */
    val colors: List<String>? = null,
) {
    val mediaKey get() = "media/$id.$ext"
    val thumbKey get() = "thumbs/$id.webp"
}

data class Board(val id: String, val name: String, val createdAt: Long, val updatedAt: Long = createdAt)

/** The whole app state. Small enough to live in memory and on disk as one JSON file. */
data class Library(
    val pins: List<Pin> = emptyList(),       // newest first
    val boards: List<Board> = emptyList(),   // newest first
    val deleted: Map<String, Long> = emptyMap(), // tombstones: id -> deletedAt
    /** R2 keys still to delete. Local-only, never uploaded. */
    val purge: List<String> = emptyList(),
) {
    /** [forRemote]: the shared index in R2, which holds only synced pins and no local bookkeeping. */
    fun toJson(forRemote: Boolean = false): String = JSONObject()
        .put("v", 1)
        .put("pins", JSONArray().apply {
            pins.filter { !forRemote || it.synced }.forEach { p ->
                put(JSONObject()
                    .put("id", p.id).put("video", p.video).put("ext", p.ext)
                    .put("w", p.w).put("h", p.h).put("dur", p.durationMs)
                    .put("note", p.note).put("board", p.boardId ?: JSONObject.NULL)
                    .put("created", p.createdAt).put("updated", p.updatedAt)
                    .put("synced", p.synced)
                    .put("caption", p.caption ?: JSONObject.NULL)
                    .put("colors", p.colors?.let(::JSONArray) ?: JSONObject.NULL))
            }
        })
        .put("boards", JSONArray().apply {
            boards.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.createdAt).put("updated", it.updatedAt)) }
        })
        .put("deleted", JSONObject(deleted as Map<*, *>))
        .apply { if (!forRemote) put("purge", JSONArray(purge)) }
        .toString()

    companion object {
        fun fromJson(s: String): Library {
            val o = JSONObject(s)
            val pins = o.getJSONArray("pins").let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Pin(
                        id = p.getString("id"), video = p.getBoolean("video"), ext = p.getString("ext"),
                        w = p.getInt("w"), h = p.getInt("h"), durationMs = p.optLong("dur"),
                        note = p.optString("note"), boardId = if (p.isNull("board")) null else p.getString("board"),
                        createdAt = p.getLong("created"), updatedAt = p.getLong("updated"),
                        synced = p.optBoolean("synced"),
                        caption = if (p.isNull("caption")) null else p.getString("caption"),
                        colors = p.optJSONArray("colors")?.let { c -> (0 until c.length()).map(c::getString) },
                    )
                }
            }
            val boards = o.getJSONArray("boards").let { a ->
                (0 until a.length()).map { i ->
                    val b = a.getJSONObject(i)
                    Board(b.getString("id"), b.getString("name"), b.getLong("created"), b.getLong("updated"))
                }
            }
            val d = o.getJSONObject("deleted")
            val purge = o.optJSONArray("purge")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            return Library(pins, boards, d.keys().asSequence().associateWith { d.getLong(it) }, purge)
        }

        /** Last-write-wins per id, deletions win over everything. Everything in [remote] is synced. */
        fun merge(local: Library, remote: Library): Library {
            val deleted = (local.deleted.keys + remote.deleted.keys)
                .associateWith { maxOf(local.deleted[it] ?: 0, remote.deleted[it] ?: 0) }
            val remoteIds = remote.pins.mapTo(HashSet()) { it.id }
            val pins = (local.pins + remote.pins)
                .groupBy { it.id }
                .mapNotNull { (id, v) ->
                    if (id in deleted) return@mapNotNull null
                    val win = v.maxBy { it.updatedAt } // maxBy keeps the first on ties: local wins
                    // Colours are derived and never bump updatedAt, so keep whichever side already worked them out.
                    if (win.colors == null) win.copy(colors = v.firstNotNullOfOrNull { it.colors }) else win
                }
                .map { if (it.id in remoteIds) it.copy(synced = true) else it }
                .sortedByDescending { it.createdAt }
            val boards = (local.boards + remote.boards)
                .groupBy { it.id }
                .mapNotNull { (id, v) -> if (id in deleted) null else v.maxBy { it.updatedAt } }
                .sortedByDescending { it.createdAt }
            val boardIds = boards.mapTo(HashSet()) { it.id }
            return Library(
                pins.map { if (it.boardId != null && it.boardId !in boardIds) it.copy(boardId = null) else it },
                boards,
                deleted,
                local.purge,
            )
        }
    }
}

/**
 * Pins matching [q], newest first: a note, AI description or board name containing it, or, when every word is
 * a colour ("blue", "red pink"), pins showing all those colours, most dominant first.
 */
fun Library.search(q: String): List<Pin> {
    val text = q.trim()
    if (text.isEmpty()) return pins
    val colors = Colors.query(text)
    val boardHits = boards.filter { it.name.contains(text, ignoreCase = true) }.mapTo(HashSet()) { it.id }
    val hits = pins.filter { p ->
        (colors != null && p.colors.orEmpty().containsAll(colors)) ||
            p.note.contains(text, ignoreCase = true) || p.caption.orEmpty().contains(text, ignoreCase = true) || p.boardId in boardHits
    }
    if (colors == null) return hits
    // Stable sort: pins where the colours dominate first, ties stay newest first; text-only hits go last.
    return hits.sortedBy { p -> colors.sumOf { c -> p.colors.orEmpty().indexOf(c).let { if (it < 0) 99 else it } } }
}

/** The board every pin in [ids] sits in: (true, id-or-null) if they agree, (false, null) if mixed or empty. */
fun Library.sharedBoard(ids: Set<String>): Pair<Boolean, String?> {
    val boards = pins.filter { it.id in ids }.mapTo(HashSet()) { it.boardId }
    return if (boards.size == 1) true to boards.first() else false to null
}
