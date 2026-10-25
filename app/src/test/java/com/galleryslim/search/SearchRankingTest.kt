package com.galleryslim.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {
    private fun e(id: Long, labels: String, text: String = "") = PhotoIndexEntry(id, 1, labels, text, 0)

    @Test
    fun `labels outrank text and every word must match`() {
        val entries = listOf(
            e(1, "beach sky sunset"),
            e(2, "sky", "sunset boulevard menu"),
            e(3, "dog grass"),
        )
        assertEquals(listOf(1L, 2L), SearchRanking.rank(entries, "sunset"))
        assertEquals(listOf(1L), SearchRanking.rank(entries, "beach sunset"))
        assertEquals(emptyList<Long>(), SearchRanking.rank(entries, "beach dog"))
    }

    @Test
    fun `everyday words map to model labels and filler words are ignored`() {
        val entries = listOf(e(1, "beach"), e(2, "dog"))
        assertEquals(listOf(1L), SearchRanking.rank(entries, "photos of the sea"))
        assertEquals(listOf(2L), SearchRanking.rank(entries, "my puppy"))
        assertEquals(emptyList<Long>(), SearchRanking.rank(entries, "the"))
    }

    @Test
    fun `text inside photos is searchable`() {
        val entries = listOf(e(1, "paper", "big bazaar total rs. 540"))
        assertEquals(listOf(1L), SearchRanking.rank(entries, "Big Bazaar receipt".replace(" receipt", "")))
    }
}
