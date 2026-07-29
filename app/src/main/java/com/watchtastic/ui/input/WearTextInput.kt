package com.watchtastic.ui.input

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender

/**
 * Opens Wear OS's own text-entry surface and returns what the wearer produced.
 *
 * This is deliberately not a custom in-app keyboard. The system surface gives dictation,
 * the sliding QWERTY, handwriting and emoji in one place, honours the wearer's chosen
 * default input method, and is what every other watch app uses — reimplementing any of
 * that on a 1.2" screen would be strictly worse.
 */
@Composable
fun rememberTextInput(onResult: (String) -> Unit): (String) -> Unit {
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data: Intent = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(RESULT_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) callback.value(text)
    }

    return remember(launcher) {
        { prompt: String ->
            val remoteInput = RemoteInput.Builder(RESULT_KEY)
                .setLabel(prompt)
                .wearableExtender {
                    setEmojisAllowed(true)
                    // "Send" rather than "Done": the wearer's next action is to transmit.
                    setInputActionType(EditorInfo.IME_ACTION_SEND)
                }
                .build()

            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
            RemoteInputIntentHelper.putTitleExtra(intent, prompt)
            launcher.launch(intent)
        }
    }
}

private const val RESULT_KEY = "watchtastic_text"
