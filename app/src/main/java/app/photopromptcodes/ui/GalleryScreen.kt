package app.photopromptcodes.ui

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    var codes by mutableStateOf<List<PromptCode>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var category by mutableStateOf<String?>(null)
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
        viewModelScope.launch { codes = CodeRepository.load(getApplication()) }
    }

    fun setQuery(q: String) { query = q }
    fun setCategory(c: String?) { category = c }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenCode: (String) -> Unit,
    onOpenHistory: () -> Unit,
    vm: GalleryViewModel = viewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Prompt Codes", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Edit history", tint = PaperDim)
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
                onValueChange = vm::setQuery,
                placeholder = { Text("Search effects, moods, lenses...", color = PaperDim) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = PaperDim) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Paper,
                    unfocusedTextColor = Paper,
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = vm.category == null,
                        onClick = { vm.setCategory(null) },
                        label = { Text("All") },
                        colors = chipColors(vm.category == null)
                    )
                }
                items(vm.categories) { cat ->
                    FilterChip(
                        selected = vm.category == cat,
                        onClick = { vm.setCategory(if (vm.category == cat) null else cat) },
                        label = { Text(cat) },
                        colors = chipColors(vm.category == cat)
                    )
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(vm.filtered, key = { it.code }) { code ->
                    CodeCard(code = code, onClick = { onOpenCode(code.code) })
                }
                item { Spacer(Modifier.height(24.dp)) }
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = code.code,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = code.description,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = code.category.uppercase(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PaperDim
            )
        }
    }
}
