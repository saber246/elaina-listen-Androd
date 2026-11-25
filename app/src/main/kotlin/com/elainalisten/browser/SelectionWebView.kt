package com.elainalisten.browser

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import com.elainalisten.R

internal class SelectionWebView(
    context: Context,
    private val readSelection: (ActionMode) -> Unit,
    private val readFromHere: (ActionMode) -> Unit,
    private val onParagraphPress: (Float, Float) -> Unit,
    private val onPageTap: () -> Unit,
) : WebView(context) {
    private val readingGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onLongPress(e: MotionEvent) = onParagraphPress(e.x, e.y)

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onPageTap()
            return false
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        readingGesture.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        return super.startActionMode(object : ActionMode.Callback2() {
            private fun addReadAction(menu: Menu) {
                if (menu.findItem(R.id.action_read_from_here) == null) {
                    menu.add(Menu.NONE, R.id.action_read_from_here, 0, R.string.browser_read_from_here)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                if (menu.findItem(R.id.action_read_selection) == null) {
                    menu.add(Menu.NONE, R.id.action_read_selection, Menu.NONE, R.string.browser_read_selection)
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
