package my.nanihadesuka.compose.controller

import androidx.compose.runtime.State

// These solve issues related to transient illegal state exceptions.

internal inline val State<Float>.safeValue get() = try {
    value
} catch (e: IllegalStateException) {
    0f
}

internal inline val State<Int>.safeValue get() = try {
    value
} catch (e: IllegalStateException) {
    0
}

internal val State<Boolean>.safeValue get() = try {
    value
} catch (e: IllegalStateException) {
    false
}