package com.felinegardener.toxicplants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspcaPlantServiceTest {
    @Test
    fun parsePlantListFromHtml_extractsPlantEntriesAndNormalizesDuplicates() {
        val html = """
            <html><body>
                <div class="plant-entry">
                    <a href="/pet-care/animal-poison-control/toxic-and-non-toxic-plants/lily">Lily</a>
                    <img src="https://images.example/lily.jpg" />
                </div>
                <div class="plant-entry">
                    <a href="/pet-care/animal-poison-control/toxic-and-non-toxic-plants/aloe">Aloe Vera</a>
                </div>
                <div class="plant-entry">
                    <a href="/pet-care/animal-poison-control/toxic-and-non-toxic-plants/lily">lily</a>
                </div>
            </body></html>
        """.trimIndent()

        val plants = AspcaPlantService.parsePlantListFromHtml(html)

        assertEquals(2, plants.size)
        assertEquals("Aloe Vera", plants[0].name)
        assertEquals("Lily", plants[1].name)
        assertEquals("https://images.example/lily.jpg", plants[1].imageUrl)
    }

    @Test
    fun filterPlants_matchesCaseInsensitiveSubstrings() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null),
            ToxicPlant("Aloe Vera", "https://example/aloe", null),
            ToxicPlant("Azalea", "https://example/azalea", null)
        )

        val filtered = filterPlants(plants, "ale")

        assertEquals(1, filtered.size)
        assertEquals("Azalea", filtered.first().name)
    }

    @Test
    fun filterPlants_returnsAllWhenQueryIsBlank() {
        val plants = listOf(
            ToxicPlant("Lily", "https://example/lily", null),
            ToxicPlant("Aloe Vera", "https://example/aloe", null)
        )

        val filtered = filterPlants(plants, "   ")

        assertEquals(2, filtered.size)
        assertTrue(filtered.containsAll(plants))
    }
}
