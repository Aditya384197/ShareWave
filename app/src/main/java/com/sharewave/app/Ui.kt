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
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    BackHandler(enabled = screen != Screen.HOME) {
        if (!(screen == Screen.SEND && vm.fileUp())) vm.goHome()
    }
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

private fun needAllFiles(): Boolean =
    Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()

private fun allFilesIntent(pkg: String) =
    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))

@Composable
fun HomeScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val recvPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val sendPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val recvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startReceive()
    }
    val manageLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { vm.openSend() }
    val sendLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (needAllFiles()) manageLauncher.launch(allFilesIntent(ctx.packageName)) else vm.openSend()
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
        BigCard("⬆", "Send", "Choose files, then scan QR", MaterialTheme.colorScheme.primary, Color.White) {
            sendLauncher.launch(sendPerms)
        }
        Spacer(Modifier.height(16.dp))
        BigCard("⬇", "Receive", "Show QR to the sender", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) {
            recvLauncher.launch(recvPerms)
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
    val ctx = LocalContext.current
    val transfers by vm.items.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cat by vm.cat.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val dir by vm.dir.collectAsStateWithLifecycle()

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { vm.startSend(it) } }
    val manage = rememberLauncherForActivityResult(StartActivityForResult()) { vm.load() }

    fun scanOptions() = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Scan the receiver's QR code")
        .setBeepEnabled(false)
        .setOrientationLocked(false)

    val inTransfer = busy || transfers.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Send", vm)
        if (inTransfer) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color.DarkGray)
            if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(12.dp))
            TransferList(transfers, Modifier.weight(1f))
            Button({ vm.backToPick() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("Send more files")
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cat.values().forEach { c ->
                    FilterChip(selected = c == cat, onClick = { vm.setCat(c) }, label = { Text(c.label) })
                }
            }
            if (status.isNotEmpty()) Text(status, color = Color(0xFFC62828), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cat == Cat.FILES && needAllFiles()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Allow access to all files to browse folders", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button({ manage.launch(allFilesIntent(ctx.packageName)) }) { Text("Allow access") }
                    }
                } else if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (entries.isEmpty()) {
                    Text("Nothing here", Modifier.align(Alignment.Center), color = Color.Gray)
                } else if (cat == Cat.PHOTOS || cat == Cat.VIDEOS) {
                    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        gridItems(entries, key = { it.key }) { e ->
                            val on = selected.containsKey(e.key)
                            Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.toggle(e) }) {
                                Thumb(e, cat, Modifier.fillMaxSize())
                                if (on) {
                                    Box(Modifier.fillMaxSize().background(Orange.copy(alpha = 0.35f)))
                                    Text("✔", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (cat == Cat.FILES) {
                            item {
                                Text(
                                    dir.absolutePath, fontSize = 12.sp, color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                        items(entries, key = { it.key }) { e ->
                            val on = selected.containsKey(e.key)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { if (e.isDir) vm.openDir(e) else vm.toggle(e) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Thumb(e, cat, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, maxLines = 1, fontWeight = FontWeight.Medium)
                                    if (e.size >= 0) Text(fmtBytes(e.size), fontSize = 12.sp, color = Color.Gray)
                                }
                                if (e.isDir) TextButton({ vm.addFolder(e) }) { Text("Add all") }
                                else if (on) Text("✔", color = Orange, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} selected", fontWeight = FontWeight.Bold)
                    Text(fmtBytes(selected.values.sumOf { maxOf(it.size, 0L) }), fontSize = 12.sp, color = Color.Gray)
                }
                if (selected.isNotEmpty()) TextButton({ vm.clearSelection() }) { Text("Clear") }
                Button({ scan.launch(scanOptions()) }, enabled = selected.isNotEmpty()) { Text("Scan QR & Send") }
            }
        }
    }
}

@Composable
fun Thumb(e: Entry, cat: Cat, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, e.key) {
        value = withContext(Dispatchers.IO) {
            try {
                when {
                    e.isDir -> null
                    cat == Cat.APPS -> ctx.packageManager.getApplicationIcon(e.sub).toBitmap(96, 96)
                    cat == Cat.PHOTOS || cat == Cat.VIDEOS || cat == Cat.MUSIC ->
                        ctx.contentResolver.loadThumbnail(e.uri, Size(240, 240), null)
                    else -> null
                }
            } catch (ex: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(
                when {
                    e.isDir -> "📁"
                    cat == Cat.MUSIC -> "🎵"
                    cat == Cat.VIDEOS -> "🎬"
                    cat == Cat.PHOTOS -> "🖼"
                    cat == Cat.APPS -> "📦"
                    else -> "📄"
                },
                fontSize = 22.sp,
            )
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
