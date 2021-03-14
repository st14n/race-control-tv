package fr.groggy.racecontrol.tv.core

import org.threeten.bp.Instant
import org.threeten.bp.LocalDate

data class LocalDatePeriod(val start: LocalDate, val end: LocalDate)

data class InstantPeriod(val start: Instant, val end: Instant)
