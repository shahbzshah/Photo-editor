package app.photopromptcodes.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.util.saveImageToGallery
import app.photopromptcodes.util.shareImage
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
            AsyncImage(
                model = vm.resultFile(),
                contentDescription = "Edited photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
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
