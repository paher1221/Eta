package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal data class DeviceCapabilitiesUi(
    val tools: AgentToolCapabilities,
) {
}


@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    var tools by remember { mutableStateOf(AgentToolCapabilities.capture(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                tools = AgentToolCapabilities.capture(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }
    return DeviceCapabilitiesUi(tools)
}
