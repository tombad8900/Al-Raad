package com.alraad.app.navigation

/** Outcome of handing the visible diagnostics log to Android's share chooser. */
internal enum class ExportResult { SHARED, EMPTY, COPIED_INSTEAD, FAILED }
