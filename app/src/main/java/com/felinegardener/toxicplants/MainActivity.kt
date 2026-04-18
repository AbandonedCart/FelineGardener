package com.felinegardener.toxicplants

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

private const val ASPCA_CATS_LIST_URL = "https://www.aspca.org/pet-care/animal-poison-control/cats-plant-list"
private const val ASPCA_PLANT_PATH_SEGMENT = "/toxic-and-non-toxic-plants/"
private const val GITHUB_REPO_OWNER = "AbandonedCart"
private const val GITHUB_REPO_NAME = "FelineGardener"
private const val GITHUB_RELEASES_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
private const val GITHUB_LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
private const val UPDATE_CACHE_DIR = "updates"
private const val MAX_UPDATE_APK_FILENAME_LENGTH = 120
private val INVALID_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
private val CONSECUTIVE_UNDERSCORES = Regex("_+")

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
    val htmlUrl: String,
    val apkDownloadUrl: String?
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
        val imageUrl = resolveImageUrl(
            document = document,
            rawValue = document.selectFirst("meta[property=og:image]")?.attr("content")
        )
            ?: resolveImageUrl(
                document = document,
                rawValue = document.selectFirst("meta[name=twitter:image]")?.attr("content")
            )
            ?: resolveImageUrl(
                document = document,
                rawValue = document.select("img[src]").firstOrNull()?.attr("src")
            )

        val alternateNames = parseAlternateNamesFromDetailDocument(document)
        return PlantDetails(
            imageUrl = imageUrl,
            alternateNames = alternateNames
        )
    }

    private fun resolveImageUrl(document: Document, rawValue: String?): String? {
        val trimmed = rawValue?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }

        val baseUrl = document.baseUri().ifBlank { ASPCA_CATS_LIST_URL }
        val resolved = runCatching { URL(URL(baseUrl), trimmed).toString() }
            .getOrElse { trimmed }
            .trim()
            .ifBlank { return null }

        return resolved
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
                    parseLatestReleaseFromJson(body)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun parseLatestReleaseFromJson(body: String): GitHubRelease? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tagName = json.optString("tag_name").trim()
        if (tagName.isBlank()) {
            return null
        }
        val htmlUrl = json.optString("html_url").trim().ifBlank { GITHUB_RELEASES_URL }
        val assets = json.optJSONArray("assets")
        val apkUrls = assets?.let { array ->
            (0 until array.length())
                .asSequence()
                .mapNotNull { index -> array.optJSONObject(index) }
                .mapNotNull { asset ->
                    asset.optString("browser_download_url").trim().ifBlank { null }
                }
                .filter { url ->
                    url.substringBefore('?').endsWith(".apk", ignoreCase = true)
                }
                .toList()
        }.orEmpty()
        val apkDownloadUrl = apkUrls.firstOrNull { url ->
            url.substringAfterLast('/').contains("felinegardener", ignoreCase = true)
        } ?: apkUrls.firstOrNull()
        return GitHubRelease(
            tagName = tagName,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl
        )
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
    val coroutineScope = rememberCoroutineScope()
    var isUpdateDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {}
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
                            isUpdateDialogVisible = true
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

                if (isUpdateDialogVisible) {
                    AlertDialog(
                        onDismissRequest = { isUpdateDialogVisible = false },
                        title = { Text("Install update ${release.tagName}?") },
                        text = {
                            Text("This will download the latest APK and start installation.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    isUpdateDialogVisible = false
                                    coroutineScope.launch {
                                        val didStartInstall = downloadAndInstallReleaseApk(
                                            context = context,
                                            release = release
                                        )
                                        if (!didStartInstall) {
                                            Toast.makeText(
                                                context,
                                                "Unable to auto-install the update. Opening the Releases page for manual download.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text("Download & Install")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { isUpdateDialogVisible = false }) {
                                Text("Cancel")
                            }
                        }
                    )
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

private suspend fun downloadAndInstallReleaseApk(context: Context, release: GitHubRelease): Boolean {
    val apkDownloadUrl = release.apkDownloadUrl?.trim().orEmpty()
    if (apkDownloadUrl.isBlank()) {
        return false
    }

    val downloadUri = runCatching { URI(apkDownloadUrl) }.getOrNull() ?: return false
    val host = downloadUri.host?.lowercase(Locale.US).orEmpty()
    val allowedHosts = setOf("github.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    if (!downloadUri.scheme.equals("https", ignoreCase = true) || host !in allowedHosts) {
        return false
    }

    val apkFile = withContext(Dispatchers.IO) {
        runCatching {
            val rawFileName = apkDownloadUrl
                .substringAfterLast('/')
                .substringBefore('?')
                .ifBlank { "FelineGardener-${release.tagName}.apk" }
            val fileName = sanitizeApkFileName(rawFileName)
            val updatesDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
            val destination = File(updatesDir, fileName)
            URL(apkDownloadUrl).openStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destination.takeIf(::isValidDownloadedApk)
        }.getOrNull()
    } ?: return false

    return startApkInstall(context, apkFile)
}

private fun sanitizeApkFileName(rawName: String): String {
    val cleaned = rawName
        .replace("/", "_")
        .replace("\\", "_")
        .replace("..", "_")
        .replace(INVALID_FILENAME_CHARS, "_")
        .replace(CONSECUTIVE_UNDERSCORES, "_")
        .trim('_', '.')
        .ifBlank { "FelineGardener-update.apk" }
    val normalized = if (cleaned.endsWith(".apk", ignoreCase = true)) cleaned else "$cleaned.apk"
    return normalized.take(MAX_UPDATE_APK_FILENAME_LENGTH)
}

private fun isValidDownloadedApk(file: File): Boolean {
    return file.exists() && file.length() > 0L && file.extension.equals("apk", ignoreCase = true)
}

private fun startApkInstall(context: Context, apkFile: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        Toast.makeText(
            context,
            "Please allow installs from this app in Settings, then try updating again.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    val apkUri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    }.getOrNull() ?: return false

    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return runCatching {
        context.startActivity(installIntent)
        true
    }.getOrElse { false }
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
