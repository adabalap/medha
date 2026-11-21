package com.adabala.medha.notify

/**
 * PendingIntent request codes for the brief widget's tap targets.
 *
 * WHY THIS IS ITS OWN FILE
 *
 * Because it is arithmetic that decides where a tap goes, and arithmetic
 * that decides anything belongs where the test harness can execute it.
 * BriefWidgets imports half of Android, so nothing in it can be run without
 * a device; this can.
 *
 * WHY THE CODES HAVE TO BE DISTINCT
 *
 * The system keys a PendingIntent on its request code plus the intent's
 * "filter equality" — action, data URI, categories, component. Filter
 * equality compares a data URI by scheme, host and path PREFIX, and ignores
 * extras entirely. Two rows pointing at different pages of the same bank
 * are therefore equal as far as the system is concerned, so with one shared
 * request code they would be ONE PendingIntent, and FLAG_UPDATE_CURRENT
 * would quietly repoint both at whichever row was written last. Tapping the
 * EMI row would open the row below it.
 *
 * THE OFF-BY-ONE THIS ENCODES
 *
 * The first attempt was `widgetId * STRIDE + slotIndex`, with the widget
 * background passed its own widget id as a code. That put the root of
 * widget 1 and row 0 of widget 0 on the same number. Giving the root slot
 * index -1 moves it to the bottom of its own widget's range and leaves the
 * rows above it.
 */
object WidgetCodes {

    /**
     * Codes reserved per placed widget.
     *
     * Must exceed the largest row count by at least two — one for the root
     * and one for the +1 that lifts rows clear of it. The widest size draws
     * [BriefRender.ROWS_4X4] rows; the margin above that is deliberate
     * headroom for a bigger size later, and [fitsStride] is what fails if
     * somebody adds one without revisiting this.
     */
    const val STRIDE = 31

    /** Slot index standing for the widget background rather than a row. */
    const val ROOT_SLOT = -1

    /** The code for one tap target of one placed widget. */
    fun of(widgetId: Int, slotIndex: Int): Int =
        ((widgetId * STRIDE + slotIndex + 1) and 0x7FFFFFFF)

    /** The code for tapping the widget's background. */
    fun root(widgetId: Int): Int = of(widgetId, ROOT_SLOT)

    /**
     * True if [rows] rows still fit one widget's reserved range.
     *
     * The root takes index -1 and rows take 0..rows-1, so the span used is
     * rows + 1 and it has to stay inside STRIDE.
     */
    fun fitsStride(rows: Int): Boolean = rows + 1 <= STRIDE
}
