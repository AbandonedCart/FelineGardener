package com.felinegardener.toxicplants

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val ASPCA_CATS_LIST_URL = "https://www.aspca.org/pet-care/animal-poison-control/cats-plant-list"
private const val ASPCA_PLANT_PATH_SEGMENT = "/pet-care/animal-poison-control/toxic-and-non-toxic-plants/"

data class ToxicPlant(
    val name: String,
    val detailsUrl: String,
    val imageUrl: String?
)

data class ToxicPlantsUiState(
    val query: String = "",
    val allPlants: List<ToxicPlant> = emptyList(),
    val filteredPlants: List<ToxicPlant> = emptyList(),
    val imageOverrides: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

object AspcaPlantService {
    suspend fun fetchPlantList(): List<ToxicPlant> = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(ASPCA_CATS_LIST_URL)
            .userAgent("Mozilla/5.0 (Android)")
            .get()
        parsePlantList(document)
    }

    fun parsePlantListFromHtml(html: String, baseUri: String = "https://www.aspca.org"): List<ToxicPlant> {
        return parsePlantList(Jsoup.parse(html, baseUri))
    }

    private fun parsePlantList(document: Document): List<ToxicPlant> {
        val plantLinks = document.select("a[href*=\"$ASPCA_PLANT_PATH_SEGMENT\"]")
        return plantLinks
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                val detailsUrl = anchor.absUrl("href").trim()
                if (name.isBlank() || detailsUrl.isBlank() || detailsUrl == ASPCA_CATS_LIST_URL) {
                    null
                } else {
                    val imageUrl = anchor.parents()
                        .flatMap { it.select("img").toList() }
                        .firstNotNullOfOrNull { imageElement ->
                            imageElement.absUrl("src").ifBlank {
                                imageElement.absUrl("data-src")
                            }.takeIf { it.isNotBlank() }
                        }
                    ToxicPlant(name = name, detailsUrl = detailsUrl, imageUrl = imageUrl)
                }
            }
            .distinctBy { plant -> plant.name.lowercase() }
            .sortedBy { plant -> plant.name.lowercase() }
    }

    suspend fun fetchPlantImage(detailsUrl: String): String? = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(detailsUrl)
            .userAgent("Mozilla/5.0 (Android)")
            .get()

        document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.select("img[src]").firstOrNull()?.absUrl("src")?.takeIf { it.isNotBlank() }
    }
}

fun filterPlants(plants: List<ToxicPlant>, query: String): List<ToxicPlant> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) {
        return plants
    }

    return plants.filter { plant ->
        plant.name.contains(trimmedQuery, ignoreCase = true)
    }
}

class ToxicPlantsViewModel : ViewModel() {
    var uiState by mutableStateOf(ToxicPlantsUiState())
        private set

    private val imageRequests = mutableSetOf<String>()

    init {
        loadPlants()
    }

    fun onQueryChange(query: String) {
        val filtered = filterPlants(uiState.allPlants, query)
        uiState = uiState.copy(query = query, filteredPlants = filtered)
    }

    fun ensurePlantImage(detailsUrl: String) {
        if (!imageRequests.add(detailsUrl)) {
            return
        }

        viewModelScope.launch {
            val imageUrl = runCatching { AspcaPlantService.fetchPlantImage(detailsUrl) }.getOrNull()
            if (!imageUrl.isNullOrBlank()) {
                uiState = uiState.copy(
                    imageOverrides = uiState.imageOverrides + (detailsUrl to imageUrl)
                )
            }
        }
    }

    private fun loadPlants() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            runCatching { AspcaPlantService.fetchPlantList() }
                .onSuccess { plants ->
                    uiState = uiState.copy(
                        isLoading = false,
                        allPlants = plants,
                        filteredPlants = filterPlants(plants, uiState.query),
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to load ASPCA plant list."
                    )
                }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                ToxicPlantsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToxicPlantsScreen(viewModel: ToxicPlantsViewModel = viewModel()) {
    val uiState = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Toxic Plants for Cats") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search plants") },
                singleLine = true
            )

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.errorMessage != null -> {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(uiState.filteredPlants, key = { it.detailsUrl }) { plant ->
                            val imageUrl = uiState.imageOverrides[plant.detailsUrl] ?: plant.imageUrl
                            PlantRow(
                                plant = plant,
                                imageUrl = imageUrl,
                                onNeedImage = { viewModel.ensurePlantImage(plant.detailsUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlantRow(
    plant: ToxicPlant,
    imageUrl: String?,
    onNeedImage: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(plant.detailsUrl, imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            onNeedImage()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plant.detailsUrl)))
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = plant.name,
            modifier = Modifier.size(88.dp),
            contentScale = ContentScale.Crop
        )

        Text(
            text = plant.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
