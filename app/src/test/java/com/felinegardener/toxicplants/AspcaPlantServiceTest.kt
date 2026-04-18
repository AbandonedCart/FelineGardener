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
        assertEquals("https://images.example/lily.jpg", plants[0].imageUrl)
        assertEquals("Aloe Vera", plants[1].name)
        assertEquals(PlantToxicityGroup.NON_TOXIC, plants[1].toxicityGroup)
    }

    @Test
    fun filterPlants_matchesCaseInsensitiveSubstrings() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null, PlantToxicityGroup.TOXIC),
            ToxicPlant("Aloe Vera", "https://example/aloe", null, PlantToxicityGroup.NON_TOXIC),
            ToxicPlant("Azalea", "https://example/azalea", null, PlantToxicityGroup.TOXIC)
        )

        val filtered = filterPlants(plants, "ale")

        assertEquals(1, filtered.size)
        assertEquals("Azalea", filtered.first().name)
    }

    @Test
    fun filterPlants_returnsAllWhenQueryIsBlank() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null, PlantToxicityGroup.TOXIC),
            ToxicPlant("Aloe Vera", "https://example/aloe", null, PlantToxicityGroup.NON_TOXIC)
        )

        val filtered = filterPlants(plants, "   ")

        assertEquals(2, filtered.size)
        assertTrue(filtered.containsAll(plants))
    }

    @Test
    fun splitPlantsByToxicity_returnsToxicAndNonToxicBuckets() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null, PlantToxicityGroup.TOXIC),
            ToxicPlant("Aloe Vera", "https://example/aloe", null, PlantToxicityGroup.NON_TOXIC),
            ToxicPlant("Azalea", "https://example/azalea", null, PlantToxicityGroup.TOXIC)
        )

        val (toxic, nonToxic) = splitPlantsByToxicity(plants)

        assertEquals(listOf("Lily", "Azalea"), toxic.map { it.name })
        assertEquals(listOf("Aloe Vera"), nonToxic.map { it.name })
    }
}
