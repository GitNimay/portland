package app.portland.ui

import androidx.compose.runtime.Composable
import app.portland.data.Library

// STUB: implemented separately.
@Composable
fun BoardsGrid(lib: Library, header: @Composable () -> Unit) { header() }

@Composable
fun BoardScreen(id: String) {}

/** Bottom sheet to choose a board for a pin; null = no board. */
@Composable
fun BoardPickerSheet(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {}
