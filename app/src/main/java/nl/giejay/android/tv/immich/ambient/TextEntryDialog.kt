package nl.giejay.android.tv.immich.ambient

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Typing a value for a free-text setting: a plain platform dialog with one text field (the frame's
 * remote can't type well, but an on-screen keyboard or `adb shell input text` can). The dialog's
 * window gets the keys itself, so the ambient screen's key routing stays out of its way.
 */
object TextEntryDialog {

    /**
     * [secret]: the field starts empty and hides what is typed; saving it empty keeps the current
     * value. [clearLabel], when not null, adds a button that saves "" (e.g. "Use built-in").
     */
    @Suppress("DEPRECATION") // THEME_DEVICE_DEFAULT_DARK: the API 19 way to get the dark dialog
    fun show(
        context: Context,
        title: String,
        message: String,
        initial: String,
        secret: Boolean,
        clearLabel: String?,
        onSave: (String) -> Unit
    ): AlertDialog {
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (secret) hint = "Type a new one, or leave empty to keep it" else setText(initial)
            setSelection(text.length)
        }
        val box = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val save = {
            val typed = field.text.toString().trim()
            if (!(secret && typed.isEmpty())) onSave(typed)
        }
        val builder = AlertDialog.Builder(context, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Cancel", null)
        if (clearLabel != null) builder.setNeutralButton(clearLabel) { _, _ -> onSave("") }
        val dialog = builder.create()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                dialog.dismiss()
                true
            } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        return dialog
    }
}
