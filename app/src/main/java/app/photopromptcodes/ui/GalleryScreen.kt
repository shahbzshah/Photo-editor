package app.photopromptcodes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.data.CodeRepository
import app.photopromptcodes.data.PromptCode
import coil.compose.AsyncImage
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import app.photopromptcodes.ui.theme.SurfaceRaised
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    var codes by mutableStateOf<List<PromptCode>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var category by mutableStateOf<String?>(null)
        private set
    var recentCodes by mutableStateOf<List<PromptCode>>(emptyList())
        private set
    var promptOfTheDay by mutableStateOf<PromptCode?>(null)
        private set

    val categories: List<String> get() = CodeRepository.categories(codes)

    val filtered: List<PromptCode>
        get() {
            val q = query.trim().lowercase()
            return codes.filter { c ->
                (category == null || c.category == category) &&
                    (q.isEmpty() ||
                        c.code.lowercase().contains(q) ||
                        c.description.lowercase().contains(q) ||
                        c.category.lowercase().contains(q))
            }
        }

    init {
        viewModelScope.launch {
            codes = CodeRepository.load(getApplication())
            refreshRecent()
            // Deterministic daily pick: day-of-year rotates through all codes
            if (codes.isNotEmpty()) {
                val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                promptOfTheDay = codes[dayOfYear % codes.size]
            }
        }
    }

    fun refreshRecent() {
        val recent = app.photopromptcodes.data.RecentCodes.get(getApplication())
        val byCode = codes.associateBy { it.code }
        recentCodes = recent.mapNotNull { byCode[it] }
    }

    var viewMode by mutableStateOf(
        getApplication<Application>().getSharedPreferences("ppc_prefs", android.content.Context.MODE_PRIVATE)
            .getString("gallery_view_mode", "list") ?: "list"
    )
        private set

    fun toggleViewMode() {
        viewMode = if (viewMode == "list") "grid" else "list"
        getApplication<Application>().getSharedPreferences("ppc_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("gallery_view_mode", viewMode).apply()
    }

    fun updateQuery(q: String) { query = q }
    fun updateCategory(c: String?) { category = c }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenCode: (String) -> Unit,
    onCustomPrompt: (String, Uri) -> Unit,
    vm: GalleryViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Refresh recent codes whenever the screen resumes (e.g. after a run)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshRecent()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var showCustomDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var customText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pendingCustomText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val pickVisual = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val text = pendingCustomText
        if (uri != null && text != null) onCustomPrompt(text, uri)
        pendingCustomText = null
    }
    val takePictureUri = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Uri?>(null) }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        val text = pendingCustomText
        val uri = takePictureUri.value
        if (success && uri != null && text != null) onCustomPrompt(text, uri)
        pendingCustomText = null
    }

    fun launchCustomPhoto(text: String, useCamera: Boolean) {
        pendingCustomText = text
        showCustomDialog = false
        if (useCamera) {
            val file = java.io.File(context.cacheDir, "shared/camera_${System.currentTimeMillis()}.jpg")
            file.parentFile?.mkdirs()
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "app.photopromptcodes.fileprovider", file
            )
            takePictureUri.value = uri
            takePicture.launch(uri)
        } else {
            pickVisual.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    if (showCustomDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom edit") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = { Text("Describe the edit — e.g. make the sky dramatic and add film grain", color = PaperDim) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Paper,
                            unfocusedTextColor = Paper,
                            focusedBorderColor = Amber
                        )
                    )
                    if (customText.isBlank()) {
                        Text(
                            "Type your edit first — then pick Gallery or Camera.",
                            color = PaperDim,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                val hasText = customText.isNotBlank()
                Row {
                    androidx.compose.material3.TextButton(
                        onClick = { launchCustomPhoto(customText, useCamera = false) },
                        enabled = hasText
                    ) { Text("Gallery", color = if (hasText) Amber else PaperDim) }
                    androidx.compose.material3.TextButton(
                        onClick = { launchCustomPhoto(customText, useCamera = true) },
                        enabled = hasText
                    ) { Text("Camera", color = if (hasText) Amber else PaperDim) }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel", color = PaperDim)
                }
            },
            containerColor = SurfaceDark
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Photo Prompt Codes",
                        fontFamily = Mono,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    TextButton(onClick = { vm.toggleViewMode() }) {
                        Text(
                            if (vm.viewMode == "list") "Grid" else "List",
                            color = Amber,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack)
            )
        },
        containerColor = NearBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::updateQuery,
                placeholder = { Text("Search effects, moods, lenses...", color = PaperDim) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = PaperDim) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Paper,
                    unfocusedTextColor = Paper,
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                item {
                    FilterChip(
                        selected = vm.category == null,
                        onClick = { vm.updateCategory(null) },
                        label = { Text("All", style = MaterialTheme.typography.labelMedium) },
                        colors = chipColors(vm.category == null)
                    )
                }
                items(vm.categories) { cat ->
                    FilterChip(
                        selected = vm.category == cat,
                        onClick = { vm.updateCategory(if (vm.category == cat) null else cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                        colors = chipColors(vm.category == cat)
                    )
                }
            }
            if (vm.recentCodes.isNotEmpty() && vm.query.isBlank() && vm.category == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RECENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = PaperDim,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(vm.recentCodes.take(5), key = { it.code }) { code ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(SurfaceRaised)
                                    .clickable { onOpenCode(code.code) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Amber)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = code.code,
                                    fontFamily = Mono,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Amber,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            if (vm.viewMode == "grid") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.filtered, key = { it.code }) { code ->
                        GridCodeCard(code = code, onClick = { onOpenCode(code.code) })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                vm.promptOfTheDay?.let { potd ->
                    if (vm.query.isBlank() && vm.category == null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCode(potd.code) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                border = BorderStroke(1.dp, Amber)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = potd.sampleAsset,
                                        contentDescription = "Prompt of the day",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "PROMPT OF THE DAY",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Amber
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = potd.code,
                                            fontFamily = Mono,
                                            fontWeight = FontWeight.Bold,
                                            color = Paper,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = potd.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PaperDim,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                tint = Amber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom edit",
                                    fontWeight = FontWeight.SemiBold,
                                    color = Amber,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Describe any edit in your own words",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PaperDim
                                )
                            }
                        }
                    }
                }
                items(vm.filtered, key = { it.code }) { code ->
                    CodeCard(code = code, onClick = { onOpenCode(code.code) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            } // end list mode
        }
    }
}

@Composable
private fun GridCodeCard(code: PromptCode, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        // Uniform 4:5 thumb with the code overlaid on a bottom fade: no
        // text block below, so every row is exactly even — no dead space.
        Box {
            AsyncImage(
                model = code.sampleAsset,
                contentDescription = "Sample: ${code.code}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.78f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = code.category.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper,
                    maxLines = 1
                )
                Text(
                    text = code.code,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun chipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Amber,
    selectedLabelColor = NearBlack,
    containerColor = SurfaceDark,
    labelColor = PaperDim
)

@Composable
private fun CodeCard(code: PromptCode, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sample thumbnail
            AsyncImage(
                model = code.sampleAsset,
                contentDescription = "Sample: ${code.code}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = code.code,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = code.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = PaperDim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = code.category.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PaperDim
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = PaperDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
