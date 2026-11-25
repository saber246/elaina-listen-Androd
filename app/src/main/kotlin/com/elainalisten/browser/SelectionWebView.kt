package com.elainalisten.browser

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import com.elainalisten.R

internal class SelectionWebView(
    context: Context,
    private val readSelection: (ActionMode) -> Unit,
    private val readFromHere: (ActionMode) -> Unit,
) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        if (type != ActionMode.TYPE_FLOATING) return super.startActionMode(callback, type)
        return super.startActionMode(object : ActionMode.Callback2() {
            private fun addReadAction(menu: Menu) {
                if (menu.findItem(R.id.action_read_selection) == null) {
                    menu.add(Menu.NONE, R.id.action_read_selection, Menu.NONE, R.string.browser_read_selection)
                }
                if (menu.findItem(R.id.action_read_from_here) == null) {
                    menu.add(Menu.NONE, R.id.action_read_from_here, Menu.NONE, R.string.browser_read_from_here)
                }
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val accepted = callback.onCreateActionMode(mode, menu)
                if (accepted) addReadAction(menu)
                return accepted
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val changed = callback.onPrepareActionMode(mode, menu)
                val missing = menu.findItem(R.id.action_read_selection) == null ||
                    menu.findItem(R.id.action_read_from_here) == null
                addReadAction(menu)
                return changed || missing
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.action_read_selection -> readSelection(mode)
                    R.id.action_read_from_here -> readFromHere(mode)
                    else -> return callback.onActionItemClicked(mode, item)
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }, type)
    }
}
