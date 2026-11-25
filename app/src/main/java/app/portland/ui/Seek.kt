package app.portland.ui

/** Where a relative seek lands: clamped to the clip, and 0 while the duration is unknown. */
fun seekTarget(position: Long, deltaMs: Long, duration: Long): Long {
    if (duration <= 0L) return 0L
    return (position + deltaMs).coerceIn(0L, duration)
}

/** Maps a 0..1 point on the timeline to a position; out-of-range or NaN input is clamped. */
fun fractionToPosition(fraction: Float, duration: Long): Long {
    if (duration <= 0L || fraction.isNaN()) return 0L
    // Double keeps long clips exact; a Float has only 24 bits of mantissa.
    return (fraction.coerceIn(0f, 1f).toDouble() * duration).toLong().coerceIn(0L, duration)
}

/** The inverse of [fractionToPosition], for drawing the timeline. */
fun positionToFraction(position: Long, duration: Long): Float {
    if (duration <= 0L) return 0f
    return (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
}
