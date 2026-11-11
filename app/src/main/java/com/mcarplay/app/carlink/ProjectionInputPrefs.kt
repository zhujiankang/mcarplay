package com.mcarplay.app.carlink

import android.content.Context

object ProjectionInputPrefs {
    enum class Input { CARLIFE, CARLINK }

    fun get(context: Context): Input =
        if (context.getSharedPreferences("projection_input", Context.MODE_PRIVATE).getString("source", "carlife") == "carlink") {
            Input.CARLINK
        } else {
            Input.CARLIFE
        }

    fun set(
        context: Context,
        value: Input,
    ) {
        context
            .getSharedPreferences("projection_input", Context.MODE_PRIVATE)
            .edit()
            .putString("source", if (value == Input.CARLINK) "carlink" else "carlife")
            .apply()
    }

    fun isCarlink(context: Context): Boolean = get(context) == Input.CARLINK
}
