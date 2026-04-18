package com.felinegardener.toxicplants

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

private const val ASPCA_CATS_LIST_URL = "https://www.aspca.org/pet-care/animal-poison-control/cats-plant-list"
private const val ASPCA_PLANT_PATH_SEGMENT = "/toxic-and-non-toxic-plants/"
private const val GITHUB_REPO_OWNER = "AbandonedCart"
private const val GITHUB_REPO_NAME = "FelineGardener"
private const val GITHUB_RELEASES_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
private const val GITHUB_LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

enum class PlantToxicityGroup {
    TOXIC,
    NON_TOXIC
}

data class ToxicPlant(
    val name: String,
    val detailsUrl: String,
    val imageUrl: String?,
    val alternateNames: List<String> = emptyList(),
    val toxicityGroup: PlantToxicityGroup = PlantToxicityGroup.TOXIC
)

data class PlantDetails(
    val imageUrl: String? = null,
    val alternateNames: List<String> = emptyList()
)

data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String
)

data class ToxicPlantsUiState(
    val query: String = "",
    val allPlants: List<ToxicPlant> = emptyList(),
    val filteredPlants: List<ToxicPlant> = emptyList(),
    val selectedToxicityGroup: PlantToxicityGroup = PlantToxicityGroup.TOXIC,
    val detailsOverrides: Map<String, PlantDetails> = emptyMap(),
    val availableUpdate: GitHubRelease? = null,
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
                    val (primaryName, alternateNames) = parsePlantNameAndAlternateNames(name)
                    val sectionHeaderText = anchor.parents()
                        .firstOrNull { parent ->
                            parent.classNames().contains("view-all-plants-list")
                        }
                        ?.selectFirst(".view-header h2")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                    val toxicityGroup = if (sectionHeaderText.contains("non-toxic", ignoreCase = true)) {
                        PlantToxicityGroup.NON_TOXIC
                    } else {
                        PlantToxicityGroup.TOXIC
                    }
                    ToxicPlant(
                        name = primaryName,
                        detailsUrl = detailsUrl,
                        imageUrl = null,
                        alternateNames = alternateNames,
                        toxicityGroup = toxicityGroup
                    )
                }
            }
            .distinctBy { plant -> "${plant.toxicityGroup}:${plant.name.lowercase()}" }
            .sortedWith(
                compareBy<ToxicPlant> { plant ->
                    when (plant.toxicityGroup) {
                        PlantToxicityGroup.TOXIC -> 0
                        PlantToxicityGroup.NON_TOXIC -> 1
                    }
                }.thenBy { plant -> plant.name.lowercase() }
            )
    }

    fun parsePlantDetailsFromHtml(html: String, baseUri: String = "https://www.aspca.org"): PlantDetails {
        return parsePlantDetails(Jsoup.parse(html, baseUri))
    }

    suspend fun fetchPlantDetails(detailsUrl: String): PlantDetails = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(detailsUrl)
            .userAgent("Mozilla/5.0 (Android)")
            .get()

        parsePlantDetails(document)
    }

    private fun parsePlantDetails(document: Document): PlantDetails {
        val imageUrl = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.select("img[src]").firstOrNull()?.absUrl("src")?.takeIf { it.isNotBlank() }

        val alternateNames = parseAlternateNamesFromDetailDocument(document)
        return PlantDetails(
            imageUrl = imageUrl,
            alternateNames = alternateNames
        )
    }

    private fun parsePlantNameAndAlternateNames(rawName: String): Pair<String, List<String>> {
        val trimmedName = rawName.trim()
        val openParen = trimmedName.indexOf('(')
        val closeParen = trimmedName.lastIndexOf(')')
        if (openParen <= 0 || closeParen <= openParen) {
            return trimmedName to emptyList()
        }

        val primaryName = trimmedName.substring(0, openParen).trim().ifBlank { trimmedName }
        val alternatesSection = trimmedName.substring(openParen + 1, closeParen)
        val alternates = splitAlternateNames(alternatesSection)
        return primaryName to alternates
    }

    private fun parseAlternateNamesFromDetailDocument(document: Document): List<String> {
        val candidateLabels = listOf(
            "Additional Common Names",
            "Common Names",
            "Common Name",
            "Alternate Names",
            "Also Known As"
        )

        val fromDefinitionLists = document.select("dt").mapNotNull { term ->
            val label = term.text().trim()
            val matchesLabel = candidateLabels.any { it.equals(label, ignoreCase = true) }
            if (matchesLabel) {
                term.nextElementSibling()?.text()?.trim()
            } else {
                null
            }
        }

        val fromLabelText = candidateLabels.flatMap { label ->
            document.select(":matchesOwn(^\\s*${Regex.escape(label)}\\s*:?)")
                .mapNotNull { element ->
                    val sameElementText = element.ownText()
                    val extracted = sameElementText
                        .substringAfter(':', missingDelimiterValue = "")
                        .trim()
                        .ifBlank { null }
                    extracted ?: element.nextElementSibling()?.text()?.trim()?.ifBlank { null }
                }
        }

        return (fromDefinitionLists + fromLabelText)
            .flatMap(::splitAlternateNames)
            .distinctBy { it.lowercase() }
    }

    private fun splitAlternateNames(raw: String): List<String> {
        return raw.split(',', ';', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }
}

object GitHubReleaseService {
    suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(GITHUB_LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FelineGardener")
                connectTimeout = 10000
                readTimeout = 10000
            }

            try {
                if (connection.responseCode !in 200..299) {
                    null
                } else {
                    val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name").trim()
                    val htmlUrl = json.optString("html_url").trim().ifBlank { GITHUB_RELEASES_URL }
                    if (tagName.isBlank()) {
                        null
                    } else {
                        GitHubRelease(tagName = tagName, htmlUrl = htmlUrl)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

fun filterPlants(plants: List<ToxicPlant>, query: String): List<ToxicPlant> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) {
        return plants
    }

    return plants.filter { plant ->
        plant.name.contains(trimmedQuery, ignoreCase = true) ||
            plant.alternateNames.any { it.contains(trimmedQuery, ignoreCase = true) }
    }
}

fun splitPlantsByToxicity(plants: List<ToxicPlant>): Pair<List<ToxicPlant>, List<ToxicPlant>> {
    return plants.partition { it.toxicityGroup == PlantToxicityGroup.TOXIC }
}

fun selectToxicityGroupForFilteredPlants(
    filteredPlants: List<ToxicPlant>,
    currentSelection: PlantToxicityGroup
): PlantToxicityGroup {
    val (toxicPlants, nonToxicPlants) = splitPlantsByToxicity(filteredPlants)
    val currentHasMatches = when (currentSelection) {
        PlantToxicityGroup.TOXIC -> toxicPlants.isNotEmpty()
        PlantToxicityGroup.NON_TOXIC -> nonToxicPlants.isNotEmpty()
    }
    if (currentHasMatches) {
        return currentSelection
    }

    return when {
        toxicPlants.isNotEmpty() -> PlantToxicityGroup.TOXIC
        nonToxicPlants.isNotEmpty() -> PlantToxicityGroup.NON_TOXIC
        else -> currentSelection
    }
}

class ToxicPlantsViewModel : ViewModel() {
    var uiState by mutableStateOf(ToxicPlantsUiState())
        private set

    private val imageRequests = mutableSetOf<String>()

    init {
        loadPlants()
        checkForUpdates()
    }

    fun onQueryChange(query: String) {
        val filtered = filterPlants(uiState.allPlants, query)
        uiState = uiState.copy(
            query = query,
            filteredPlants = filtered,
            selectedToxicityGroup = selectToxicityGroupForFilteredPlants(
                filteredPlants = filtered,
                currentSelection = uiState.selectedToxicityGroup
            )
        )
    }

    fun onToxicityGroupSelected(group: PlantToxicityGroup) {
        uiState = uiState.copy(selectedToxicityGroup = group)
    }

    fun ensurePlantDetails(detailsUrl: String) {
        if (!imageRequests.add(detailsUrl)) {
            return
        }

        viewModelScope.launch {
            val details = runCatching { AspcaPlantService.fetchPlantDetails(detailsUrl) }
                .getOrDefault(PlantDetails())
            if (!details.imageUrl.isNullOrBlank() || details.alternateNames.isNotEmpty()) {
                uiState = uiState.copy(
                    detailsOverrides = uiState.detailsOverrides + (detailsUrl to details)
                )
            }
        }
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            val latestRelease = runCatching { GitHubReleaseService.fetchLatestRelease() }.getOrNull() ?: return@launch
            val currentHash = BuildConfig.GIT_SHORT_HASH.trim()
            val isUpdateAvailable = normalizeReleaseTag(latestRelease.tagName) != normalizeReleaseTag(currentHash)
            uiState = uiState.copy(
                availableUpdate = if (isUpdateAvailable) latestRelease else null
            )
        }
    }

    private fun normalizeReleaseTag(tag: String): String {
        return tag.trim()
            .removePrefix("v")
            .removePrefix("sha-")
            .lowercase()
    }

    private fun loadPlants() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            runCatching { AspcaPlantService.fetchPlantList() }
                .onSuccess { plants ->
                    val filtered = filterPlants(plants, uiState.query)
                    uiState = uiState.copy(
                        isLoading = false,
                        allPlants = plants,
                        filteredPlants = filtered,
                        selectedToxicityGroup = selectToxicityGroupForFilteredPlants(
                            filteredPlants = filtered,
                            currentSelection = uiState.selectedToxicityGroup
                        ),
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
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Feline Gardener") })
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
            uiState.availableUpdate?.let { release ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Update available (${release.tagName})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "Tap to download the latest APK from GitHub Releases.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            if (!uiState.isLoading && uiState.errorMessage == null) {
                Text(
                    text = "Showing ${uiState.filteredPlants.size} plants",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                    val (toxicPlants, nonToxicPlants) = splitPlantsByToxicity(uiState.filteredPlants)
                    val selectedTabIndex = when (uiState.selectedToxicityGroup) {
                        PlantToxicityGroup.TOXIC -> 0
                        PlantToxicityGroup.NON_TOXIC -> 1
                    }
                    val activePlants = when (uiState.selectedToxicityGroup) {
                        PlantToxicityGroup.TOXIC -> toxicPlants
                        PlantToxicityGroup.NON_TOXIC -> nonToxicPlants
                    }

                    TabRow(selectedTabIndex = selectedTabIndex) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { viewModel.onToxicityGroupSelected(PlantToxicityGroup.TOXIC) },
                            text = { Text("Toxic (${toxicPlants.size})") }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { viewModel.onToxicityGroupSelected(PlantToxicityGroup.NON_TOXIC) },
                            text = { Text("Non-Toxic (${nonToxicPlants.size})") }
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(activePlants, key = { it.detailsUrl }) { plant ->
                            val detailsOverride = uiState.detailsOverrides[plant.detailsUrl]
                            val imageUrl = detailsOverride?.imageUrl ?: plant.imageUrl
                            val alternateNames = (plant.alternateNames + (detailsOverride?.alternateNames ?: emptyList()))
                                .distinctBy { it.lowercase() }
                            PlantRow(
                                plant = plant,
                                imageUrl = imageUrl,
                                alternateNames = alternateNames,
                                onNeedDetails = { viewModel.ensurePlantDetails(plant.detailsUrl) }
                            )
                        }

                        if (activePlants.isEmpty()) {
                            item(key = "empty-state") {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = "No plants found in this section for your search.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
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
    alternateNames: List<String>,
    onNeedDetails: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(plant.detailsUrl) {
        onNeedDetails()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plant.detailsUrl)))
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = plant.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = plant.name,
                    modifier = Modifier.size(88.dp),
                    contentScale = ContentScale.Crop
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = plant.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (alternateNames.isNotEmpty()) {
                    Text(
                        text = alternateNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
