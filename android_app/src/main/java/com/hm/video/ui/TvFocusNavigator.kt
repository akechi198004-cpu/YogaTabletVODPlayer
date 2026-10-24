package com.hm.video.ui

import android.view.View

/**
 * Explicit D-pad boundaries for the dynamically built LinearLayout lists/grids.
 *
 * Android's default focus search can leave a list, skip records or jump to the last card when
 * a row is incomplete. Wiring neighbours explicitly keeps movement local and predictable.
 */
object TvFocusNavigator {

    fun wireList(items: List<View>, upTarget: View?, downTarget: View? = null) {
        if (items.isEmpty()) return
        items.forEach(::ensureId)
        upTarget?.let(::ensureId)
        downTarget?.let(::ensureId)
        items.forEachIndexed { index, item ->
            val id = item.id
            item.nextFocusUpId = if (index == 0) upTarget?.id ?: id else items[index - 1].id
            item.nextFocusDownId = if (index == items.lastIndex) downTarget?.id ?: id else items[index + 1].id
            item.nextFocusLeftId = id
            item.nextFocusRightId = id
        }
        upTarget?.let { it.nextFocusDownId = items.first().id }
        downTarget?.let { it.nextFocusUpId = items.last().id }
    }

    fun wireGrid(items: List<View>, columns: Int, upTarget: View?, downTarget: View? = null) {
        if (items.isEmpty() || columns < 1) return
        items.forEach(::ensureId)
        upTarget?.let(::ensureId)
        downTarget?.let(::ensureId)
        val rows = (items.size + columns - 1) / columns
        items.forEachIndexed { index, item ->
            val id = item.id
            val row = index / columns
            val column = index % columns
            item.nextFocusLeftId = if (column > 0) items[index - 1].id else id
            item.nextFocusRightId =
                if (column < columns - 1 && index + 1 < items.size) items[index + 1].id else id
            item.nextFocusUpId = if (row > 0) items[index - columns].id else upTarget?.id ?: id
            item.nextFocusDownId = when {
                index + columns < items.size -> items[index + columns].id
                row < rows - 1 -> items.last().id // incomplete last row: nearest valid item
                downTarget != null -> downTarget.id
                else -> id
            }
        }
        upTarget?.let { it.nextFocusDownId = items.first().id }
        downTarget?.let { it.nextFocusUpId = items.last().id }
    }

    private fun ensureId(view: View): Int {
        if (view.id == View.NO_ID) view.id = View.generateViewId()
        return view.id
    }
}
