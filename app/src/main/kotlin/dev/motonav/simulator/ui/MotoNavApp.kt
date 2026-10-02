package dev.motonav.simulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MotoNavApp(state: AppState, actions: AppActions) {
    MotoTheme {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.messageSerial) {
            state.message?.let {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(it)
            }
        }
        Box(Modifier.fillMaxSize()) {
            HomeScreen(state, actions)
            // Keeps scrolled content from running under the clock and status icons.
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Moto.colors.background.copy(alpha = 0.92f)),
            )
            AppSheets(state, actions)
            AppDialogs(state)
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { data ->
                Snackbar(
                    containerColor = Moto.colors.surfaceRaised,
                    contentColor = Moto.colors.text,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                ) { Text(data.visuals.message) }
            }
        }
    }
}
