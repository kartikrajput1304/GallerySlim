package com.galleryslim.blackbox.cleanup

import com.galleryslim.cleanup.CleanupLanes.Lane
import com.galleryslim.cleanup.StandingRules
import com.galleryslim.cleanup.StandingRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 7.7 "Standing rules: the five questions, and the weekly nudge", page 55. */
class BbStandingRulesTest {

    @Test fun `SR-01 the five questions and detail lines read exactly as in the PDF, in order`() {
        val expected = listOf(
            "Clear screenshots after a month?" to "Old screenshots are pre-selected in Quick review.",
            "Let good-morning cards and forwards go after a week?" to "Greeting and quote images from chats are pre-selected.",
            "Clear blurry and too-dark shots?" to "Low-quality photos are pre-selected.",
            "Always keep 10 GB free?" to "Overnight shrinking works toward this goal.",
            "Lighter copies of photos older than 2 years?" to "Favourites and albums you protect are never touched.",
        )
        assertEquals(expected, Rule.entries.map { it.question to it.detail })
    }

    @Test fun `SR-02 rules 1-3 drive Old screenshots, Greetings and Low-quality, rules 4-5 drive no list`() {
        assertEquals(listOf(Lane.OLD_SCREENSHOTS, Lane.GREETINGS, Lane.LOW_QUALITY, null, null), Rule.entries.map { it.lane })
    }

    @Test fun `SR-03 all rules are off and setup is not done by default`() {
        val ctx = FakeContext()
        for (r in Rule.entries) assertFalse("$r", StandingRules.isOn(ctx, r))
        assertFalse(StandingRules.setupDone(ctx))
        assertEquals(emptySet<Rule>(), StandingRules.enabled(ctx))
    }

    @Test fun `SR-04 each answer is saved immediately and can be changed`() {
        val ctx = FakeContext()
        StandingRules.set(ctx, Rule.SCREENSHOTS, true)
        StandingRules.set(ctx, Rule.COOL, true)
        assertEquals(setOf(Rule.SCREENSHOTS, Rule.COOL), StandingRules.enabled(ctx))
        StandingRules.set(ctx, Rule.SCREENSHOTS, false)
        assertEquals(setOf(Rule.COOL), StandingRules.enabled(ctx))
        assertFalse("answering is not finishing setup", StandingRules.setupDone(ctx))
        StandingRules.markSetupDone(ctx)
        assertTrue(StandingRules.setupDone(ctx))
    }

    @Test fun `SR-06b weekly nudge and rules texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Your weekly tidy-up is ready",
            "Your rules picked what to clear. Take a look, then confirm once.",
            "Weekly tidy-up",
            "Your rules · ",
            "You can change this any time in Settings. Rules only suggest; you always confirm.",
            ": answer once, and GallerySlim handles the rest.",
            "Rules saved. Change them any time in Settings.",
            "Let GallerySlim tidy for you",
            "Answer five quick questions. Nothing is ever deleted without you.",
            "Keep it tidy by itself",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }
}
