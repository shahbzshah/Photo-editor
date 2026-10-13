package app.photopromptcodes.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.photopromptcodes.data.CodeRepository
import app.photopromptcodes.data.PromptCode
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class CodeDetailViewModel(app: Application) : AndroidViewModel(app) {
    var code: PromptCode? by mutableStateOf(null)
        private set

    fun load(context: Context, codeName: String) {
        if (code != null) return
        viewModelScope.launch {
            code = CodeRepository.load(context).firstOrNull { it.code == codeName }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeDetailScreen(
    code: String,
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    vm: CodeDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(code) { vm.load(context, code) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickVisual = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) photoUri = uri }
    val pickLegacy = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) photoUri = uri }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) photoUri = cameraUri
    }

    fun choosePhoto() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            pickLegacy.launch("image/*")
        }
    }

    fun takePhoto() {
        val file = java.io.File(context.cacheDir, "shared/camera_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "app.photopromptcodes.fileprovider", file
        )
        cameraUri = uri
        takePicture.launch(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PaperDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack)
            )
        },
        containerColor = NearBlack
    ) { padding ->
        val c = vm.code
        if (c == null) {
            Text(
                "Code not found.",
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
            Text(
                text = c.code,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = Amber,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(text = c.category.uppercase(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Text(text = c.description, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(16.dp))
            Text(
                "PROMPT SENT TO CHATGPT",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = c.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Selected photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = ::choosePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (photoUri == null) "Gallery" else "Gallery")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = ::takePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Camera")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { photoUri?.let(onEdit) },
                enabled = photoUri != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = NearBlack,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = PaperDim
                )
            ) {
                Text("Edit photo", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
