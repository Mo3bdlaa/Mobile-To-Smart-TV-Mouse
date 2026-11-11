package io.github.mo3bdlaa.tvmouse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.hid.Consumer
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.Key

@Composable
fun RemoteScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { HidManager.pressConsumer(Consumer.BACK) }
            RemoteButton(Icons.Filled.Home, "Home") { HidManager.pressConsumer(Consumer.HOME) }
            RemoteButton(Icons.Filled.Menu, "Menu") { HidManager.pressKey(Key.MENU) }
            RemoteButton(Icons.Filled.Search, "Search") { HidManager.pressConsumer(Consumer.SEARCH) }
        }

        // D-pad: arrow keys + Enter work for navigating most TV menus.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DpadButton(Icons.Filled.KeyboardArrowUp, "Up") { HidManager.pressKey(Key.UP) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DpadButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { HidManager.pressKey(Key.LEFT) }
                FilledIconButton(
                    onClick = { HidManager.pressKey(Key.ENTER) },
                    modifier = Modifier.padding(8.dp).size(88.dp),
                    shape = CircleShape,
                ) { Text("OK", style = MaterialTheme.typography.titleLarge) }
                DpadButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { HidManager.pressKey(Key.RIGHT) }
            }
            DpadButton(Icons.Filled.KeyboardArrowDown, "Down") { HidManager.pressKey(Key.DOWN) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") { HidManager.pressConsumer(Consumer.VOLUME_DOWN) }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { HidManager.pressConsumer(Consumer.MUTE) }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") { HidManager.pressConsumer(Consumer.VOLUME_UP) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RemoteButton(Icons.Filled.SkipPrevious, "Previous") { HidManager.pressConsumer(Consumer.PREVIOUS) }
            RemoteButton(Icons.Filled.PlayArrow, "Play / pause") { HidManager.pressConsumer(Consumer.PLAY_PAUSE) }
            RemoteButton(Icons.Filled.SkipNext, "Next") { HidManager.pressConsumer(Consumer.NEXT) }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "Not every TV reacts to every button. If Back doesn't work, try a 2-finger tap on the touchpad.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(60.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DpadButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.padding(4.dp).size(72.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(40.dp))
    }
}
