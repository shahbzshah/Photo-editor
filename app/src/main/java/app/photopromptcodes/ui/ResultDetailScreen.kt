package app.photopromptcodes.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.data.HistoryRecord
import app.photopromptcodes.data.HistoryRepository
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.util.saveImageToGallery
import app.photopromptcodes.util.shareImage
import app.photopromptcodes.util.shareImageToApp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResultDetailViewModel(app: Application) : AndroidViewModel(app) {
    var record: HistoryRecord? by mutableStateOf(null)
        private set
    private val repo = HistoryRepository(app)

    fun load(id: String) {
        if (record != null) return
        viewModelScope.launch { record = repo.get(id) }
    }

    fun originalFile(): File? = record?.let { repo.originalFile(it) }
    fun resultFile(): File? = record?.let { repo.resultFile(it) }

    fun delete(onDone: () -> Unit) {
        val r = record ?: return
        viewModelScope.launch {
            repo.delete(r)
            onDone()
        }
    }

    fun toggleFavorite() {
        val r = record ?: return
        viewModelScope.launch {
            val fav = repo.toggleFavorite(r)
            record = r.copy(favorite = fav)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    vm: ResultDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(recordId) { vm.load(recordId) }
    val record = vm.record

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        record?.code.orEmpty(),
                        fontFamily = Mono,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PaperDim)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = if (record?.favorite == true) Amber else PaperDim
                        )
                    }
                    IconButton(onClick = { vm.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = PaperDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack)
            )
        },
        containerColor = NearBlack
    ) { padding ->
        if (record == null) {
            Text(
                "Edit not found.",
                modifier = Modifier.padding(padding).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Before/after comparison slider
            var sliderPos by remember { mutableStateOf(0.5f) }
            var showBefore by remember { mutableStateOf(true) }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val sliderOffset = maxWidth * sliderPos
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                sliderPos = (sliderPos + dragAmount / widthPx).coerceIn(0.02f, 0.98f)
                            }
                        }
                ) {
                    // After image (full, bottom layer)
                    AsyncImage(
                        model = vm.resultFile(),
                        contentDescription = "Edited photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Before image (clipped to left of slider, top layer) — only when visible
                    if (showBefore) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(sliderPos)
                                .clipToBounds()
                        ) {
                            AsyncImage(
                                model = vm.originalFile(),
                                contentDescription = "Original photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // Divider line
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(2.dp)
                                .background(Amber)
                                .align(Alignment.CenterStart)
                                .offset(x = sliderOffset - 1.dp)
                        )
                    }
                    // Labels
                    if (showBefore) {
                        Text(
                            "BEFORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(NearBlack.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        "AFTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(NearBlack.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    // Minimizer toggle — hide/show the before image
                    TextButton(
                        onClick = { showBefore = !showBefore },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                            .background(NearBlack.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    ) {
                        Text(
                            if (showBefore) "Hide before" else "Show before",
                            color = Amber,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Text(
                if (showBefore) "Drag anywhere on the image to compare" else "Before image hidden — tap Show before to compare",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val file = vm.resultFile() ?: return@withContext false
                                saveImageToGallery(
                                    context,
                                    file.readBytes(),
                                    "ppc_${record.code.trimStart('/')}_${record.timestamp}.png"
                                ) != null
                            }
                            saved = ok
                            Toast.makeText(
                                context,
                                if (ok) "Saved to gallery" else "Couldn't save to gallery",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = NearBlack
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saved) "Saved" else "Save to gallery", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        vm.resultFile()?.let { shareImage(context, it) }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "SHARE TO",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        vm.resultFile()?.let {
                            shareImageToApp(context, it, "com.whatsapp", "WhatsApp")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("WhatsApp") }
                OutlinedButton(
                    onClick = {
                        vm.resultFile()?.let {
                            shareImageToApp(context, it, "com.zhiliaoapp.musically", "TikTok")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("TikTok") }
                OutlinedButton(
                    onClick = {
                        vm.resultFile()?.let {
                            shareImageToApp(context, it, "com.instagram.android", "Instagram")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Instagram") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatDate(record.timestamp),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
