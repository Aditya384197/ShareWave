package com.sharewave.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val Orange = Color(0xFFF57C00)

@Composable
fun ShareWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Orange,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE0B2),
            onPrimaryContainer = Color(0xFF3E2100),
            secondaryContainer = Color(0xFFFFF3E0),
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Color(0xFFFFF3E0),
        ),
        content = content,
    )
}

@Composable
fun AppRoot(vm: AppVM) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.HOME) { vm.goHome() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.statusBarsPadding()) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.RECEIVE -> ReceiveScreen(vm)
                Screen.SEND -> SendScreen(vm)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: AppVM) {
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startReceive()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.logo), null,
            Modifier.size(128.dp).clip(RoundedCornerShape(30.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(16.dp))
        Text("ShareWave", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Fast offline file transfer", color = Color.Gray)
        Spacer(Modifier.height(36.dp))
        BigCard("⬆", "Send", "Pick files, then scan QR", MaterialTheme.colorScheme.primary, Color.White) { vm.openSend() }
        Spacer(Modifier.height(16.dp))
        BigCard("⬇", "Receive", "Show QR to the sender", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) {
            permLauncher.launch(perms)
        }
    }
}

@Composable
fun BigCard(icon: String, title: String, sub: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 40.sp)
            Spacer(Modifier.size(20.dp))
            Column {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ReceiveScreen(vm: AppVM) {
    val qr by vm.qr.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val transfers by vm.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Header("Receive", vm)
        Spacer(Modifier.height(12.dp))
        val bmp = qr
        if (bmp == null) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Image(bmp.asImageBitmap(), "QR", Modifier.padding(12.dp).size(280.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(status, textAlign = TextAlign.Center, color = Color.DarkGray)
        Spacer(Modifier.height(12.dp))
        TransferList(transfers, Modifier.weight(1f))
        Text("Saved in Downloads/ShareWave", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun SendScreen(vm: AppVM) {
    val files by vm.files.collectAsStateWithLifecycle()
    val transfers by vm.items.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    fun scanOptions() = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Scan the receiver's QR code")
        .setBeepEnabled(false)
        .setOrientationLocked(false)

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { vm.startSend(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addFiles(uris)
            scan.launch(scanOptions())
        } else if (vm.files.value.isEmpty()) vm.goHome()
    }
    LaunchedEffect(Unit) { pick.launch(arrayOf("*/*")) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Header("Send", vm)
        Spacer(Modifier.height(8.dp))
        Text(status, color = Color.DarkGray)
        if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(12.dp))
        if (transfers.isNotEmpty()) {
            TransferList(transfers, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(files) { f ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(f.name, maxLines = 1, fontWeight = FontWeight.Medium)
                            if (f.size >= 0) Text(fmtBytes(f.size), fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Add files") }
            Button(
                { scan.launch(scanOptions()) },
                enabled = !busy && files.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Scan QR & send") }
        }
    }
}

@Composable
fun Header(title: String, vm: AppVM) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ vm.goHome() }) { Text("← Back") }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(64.dp))
    }
}

@Composable
fun TransferList(list: List<TItem>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(list, key = { it.id }) { t ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(t.name, maxLines = 1, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    when (t.state) {
                        TState.ACTIVE -> {
                            val p = if (t.total > 0) t.done.toFloat() / t.total else 0f
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("${fmtBytes(t.done)} / ${fmtBytes(t.total)}", fontSize = 12.sp, color = Color.Gray)
                        }
                        TState.DONE -> Text("✔ ${fmtBytes(t.total)}", fontSize = 12.sp, color = Color(0xFF2E7D32))
                        TState.FAILED -> Text("✖ Failed", fontSize = 12.sp, color = Color(0xFFC62828))
                    }
                }
            }
        }
    }
}
