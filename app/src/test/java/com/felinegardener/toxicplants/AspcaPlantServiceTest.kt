package com.felinegardener.toxicplants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspcaPlantServiceTest {
    @Test
    fun parsePlantListFromHtml_extractsPlantEntriesAndSplitsToxicityGroups() {
        val html = """
            <html><body>
                <div class="view-all-plants-list">
                    <div class="view-header"><h2>Plants Toxic to Cats</h2></div>
                    <div class="plant-entry">
                        <a href="/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/lily">Lily</a>
                        <img src="https://images.example/lily.jpg" />
                    </div>
                </div>
                <div class="view-all-plants-list">
                    <div class="view-header"><h2>Plants Non-Toxic to Cats</h2></div>
                    <div class="plant-entry">
                        <a href="/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/aloe">Aloe Vera</a>
                    </div>
                </div>
                <div class="plant-entry">
                    <a href="/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/lily">lily</a>
                </div>
            </body></html>
        """.trimIndent()

        val plants = AspcaPlantService.parsePlantListFromHtml(html)

        assertEquals(2, plants.size)
        assertEquals("Lily", plants[0].name)
        assertEquals(PlantToxicityGroup.TOXIC, plants[0].toxicityGroup)
        assertEquals(null, plants[0].imageUrl)
        assertEquals("Aloe Vera", plants[1].name)
        assertEquals(PlantToxicityGroup.NON_TOXIC, plants[1].toxicityGroup)
    }

    @Test
    fun parsePlantListFromHtml_extractsAlternateNamesFromPlantTitle() {
        val html = """
            <html><body>
                <div class="view-all-plants-list">
                    <div class="view-header"><h2>Plants Toxic to Cats</h2></div>
                    <div class="plant-entry">
                        <a href="/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/ivy">Ivy (English Ivy, Needlepoint Ivy)</a>
                    </div>
                </div>
            </body></html>
        """.trimIndent()

        val plants = AspcaPlantService.parsePlantListFromHtml(html)

        assertEquals(1, plants.size)
        assertEquals("Ivy", plants[0].name)
        assertEquals(listOf("English Ivy", "Needlepoint Ivy"), plants[0].alternateNames)
    }

    @Test
    fun parsePlantDetailsFromHtml_extractsImageAndAlternateNames() {
        val html = """
            <html><head>
                <meta property="og:image" content="https://images.example/rose.jpg" />
            </head><body>
                <dl>
                    <dt>Additional Common Names</dt>
                    <dd>Wild Rose, Garden Rose; Prairie Rose</dd>
                </dl>
            </body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://images.example/rose.jpg", details.imageUrl)
        assertEquals(listOf("Wild Rose", "Garden Rose", "Prairie Rose"), details.alternateNames)
    }

    @Test
    fun parsePlantDetailsFromHtml_prefersAspcaPlantImageFieldOverOgLogo() {
        val html = """
            <html><head>
                <meta property="og:image" content="http://www.aspca.org/sites/default/files/aspca-logo-square.png" />
            </head><body>
                <div class="field-name-field-image">
                    <img data-echo="https://www.aspca.org/sites/default/files/styles/medium_image_300x200/public/field/image/plants/aloe_vera_aloe-300x200.jpg?itok=zSlTYKxN" />
                </div>
            </body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals(
            "https://www.aspca.org/sites/default/files/styles/medium_image_300x200/public/field/image/plants/aloe_vera_aloe-300x200.jpg?itok=zSlTYKxN",
            details.imageUrl
        )
    }

    @Test
    fun parsePlantDetailsFromHtml_upgradesAspcaHttpImageUrlToHttps() {
        val html = """
            <html><head>
                <meta property="og:image" content="http://www.aspca.org/sites/default/files/aspca-logo-square.png" />
            </head><body></body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/aspca-logo-square.png", details.imageUrl)
    }

    @Test
    fun parsePlantDetailsFromHtml_rejectsNonAspcaHttpImageUrl() {
        val html = """
            <html><head>
                <meta property="og:image" content="http://images.example/rose.jpg" />
            </head><body></body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/aspca-logo-square.png", details.imageUrl)
    }

    @Test
    fun parsePlantDetailsFromHtml_acceptsNonAspcaHttpsImageUrl() {
        val html = """
            <html><head>
                <meta property="og:image" content="https://images.example/rose.jpg" />
            </head><body></body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://images.example/rose.jpg", details.imageUrl)
    }

    @Test
    fun parsePlantListFromHtml_upgradesAspcaHttpDetailsUrlToHttps() {
        val html = """
            <html><body>
                <div class="view-all-plants-list">
                    <div class="view-header"><h2>Plants Toxic to Cats</h2></div>
                    <div class="plant-entry">
                        <a href="http://www.aspca.org/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/lily">Lily</a>
                    </div>
                </div>
            </body></html>
        """.trimIndent()

        val plants = AspcaPlantService.parsePlantListFromHtml(html)

        assertEquals(1, plants.size)
        assertEquals(
            "https://www.aspca.org/pet-care/aspca-poison-control/toxic-and-non-toxic-plants/lily",
            plants.first().detailsUrl
        )
    }

    @Test
    fun parsePlantDetailsFromHtml_resolvesRelativeImagePathUsingBaseUri() {
        val html = """
            <html><body>
                <img src="/sites/default/files/example.png" />
            </body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/example.png", details.imageUrl)
    }

    @Test
    fun parsePlantDetailsFromHtml_preservesAspcaHttpsImageWithQueryParams() {
        val html = """
            <html><head>
                <meta property="og:image" content="https://www.aspca.org/sites/default/files/styles/medium_image_300x200/public/field/image/plants/arum-r.jpg?itok=206UUxCJ" />
            </head><body></body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals(
            "https://www.aspca.org/sites/default/files/styles/medium_image_300x200/public/field/image/plants/arum-r.jpg?itok=206UUxCJ",
            details.imageUrl
        )
    }

    @Test
    fun parsePlantDetailsFromHtml_fallsBackToAspcaLogoWhenImageMissing() {
        val html = """
            <html><body>
                <h1>No image here</h1>
            </body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/aspca-logo-square.png", details.imageUrl)
    }

    @Test
    fun parsePlantDetailsFromHtml_fallsBackToAspcaLogoWhenImageUrlInvalid() {
        val html = """
            <html><head>
                <meta property="og:image" content="javascript:alert('xss')" />
            </head><body></body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/aspca-logo-square.png", details.imageUrl)
    }

    @Test
    fun parsePlantDetailsFromHtml_ignoresLazyloaderPlaceholderImage() {
        val html = """
            <html><body>
                <div class="field-name-field-image">
                    <img src="https://www.aspca.org/sites/all/modules/contrib/lazyloader/image_placeholder.gif" />
                </div>
            </body></html>
        """.trimIndent()

        val details = AspcaPlantService.parsePlantDetailsFromHtml(html)

        assertEquals("https://www.aspca.org/sites/default/files/aspca-logo-square.png", details.imageUrl)
    }

    @Test
    fun filterPlants_matchesCaseInsensitiveSubstringsInPrimaryAndAlternateNames() {
        val plants = listOf(
            ToxicPlant(
                name = "Lily",
                detailsUrl = "https://example/lily",
                imageUrl = null,
                toxicityGroup = PlantToxicityGroup.TOXIC
            ),
            ToxicPlant(
                name = "Aloe Vera",
                detailsUrl = "https://example/aloe",
                imageUrl = null,
                alternateNames = listOf("Medicinal Aloe"),
                toxicityGroup = PlantToxicityGroup.NON_TOXIC
            ),
            ToxicPlant(
                name = "Azalea",
                detailsUrl = "https://example/azalea",
                imageUrl = null,
                toxicityGroup = PlantToxicityGroup.TOXIC
            )
        )

        val filtered = filterPlants(plants, "medicinal")

        assertEquals(1, filtered.size)
        assertEquals("Aloe Vera", filtered.first().name)
    }

    @Test
    fun filterPlants_returnsAllWhenQueryIsBlank() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null, toxicityGroup = PlantToxicityGroup.TOXIC),
            ToxicPlant("Aloe Vera", "https://example/aloe", null, toxicityGroup = PlantToxicityGroup.NON_TOXIC)
        )

        val filtered = filterPlants(plants, "   ")

        assertEquals(2, filtered.size)
        assertTrue(filtered.containsAll(plants))
    }

    @Test
    fun splitPlantsByToxicity_returnsToxicAndNonToxicBuckets() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null, toxicityGroup = PlantToxicityGroup.TOXIC),
            ToxicPlant("Aloe Vera", "https://example/aloe", null, toxicityGroup = PlantToxicityGroup.NON_TOXIC),
            ToxicPlant("Azalea", "https://example/azalea", null, toxicityGroup = PlantToxicityGroup.TOXIC)
        )

        val (toxic, nonToxic) = splitPlantsByToxicity(plants)

        assertEquals(listOf("Lily", "Azalea"), toxic.map { it.name })
        assertEquals(listOf("Aloe Vera"), nonToxic.map { it.name })
    }

    @Test
    fun selectToxicityGroupForFilteredPlants_prefersGroupWithMatchesWhenCurrentHasNone() {
        val filtered = listOf(
            ToxicPlant("Spider Plant", "https://example/spider-plant", null, toxicityGroup = PlantToxicityGroup.NON_TOXIC)
        )

        val selected = selectToxicityGroupForFilteredPlants(filtered, PlantToxicityGroup.TOXIC)

        assertEquals(PlantToxicityGroup.NON_TOXIC, selected)
    }

    @Test
    fun parseLatestReleaseFromJson_extractsApkAssetUrl() {
        val json = """
            {
              "tag_name": "abc1234",
              "html_url": "https://github.com/AbandonedCart/FelineGardener/releases/tag/abc1234",
              "assets": [
                { "name": "notes.txt", "browser_download_url": "https://example/notes.txt" },
                { "name": "FelineGardener-abc1234.apk", "browser_download_url": "https://example/FelineGardener-abc1234.apk" }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseService.parseLatestReleaseFromJson(json)

        assertEquals("abc1234", release?.tagName)
        assertEquals("https://example/FelineGardener-abc1234.apk", release?.apkDownloadUrl)
    }

    @Test
    fun parseLatestReleaseFromJson_returnsNullWhenTagMissing() {
        val json = """
            {
              "html_url": "https://github.com/AbandonedCart/FelineGardener/releases/latest",
              "assets": []
            }
        """.trimIndent()

        val release = GitHubReleaseService.parseLatestReleaseFromJson(json)

        assertEquals(null, release)
    }
}
