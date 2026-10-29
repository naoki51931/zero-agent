package com.zeroagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeOutcomeVerifierTest {
    private fun evidence(
        beforeHost: String = "x.com",
        afterHost: String = "x.com",
        beforeUrl: String = "https://x.com/",
        afterUrl: String = "https://x.com/search",
        beforeDom: String = "home",
        afterDom: String = "search results",
        text: String = "search results",
        confirmed: Boolean = false
    ) = EpisodeOutcomeVerifier.Evidence(beforeHost, afterHost, beforeUrl, afterUrl,
        beforeDom, afterDom, text, confirmed)

    @Test fun changedPageDoesNotImplySuccess() {
        assertEquals("unknown", EpisodeOutcomeVerifier.classify(evidence()).outcome)
    }

    @Test fun confirmedGoalIsSuccess() {
        assertEquals("success", EpisodeOutcomeVerifier.classify(evidence(confirmed = true)).outcome)
    }

    @Test fun emptyTextCannotBeSuccess() {
        assertEquals("unknown", EpisodeOutcomeVerifier.classify(evidence(text = "", confirmed = true)).outcome)
    }

    @Test fun unchangedPageIsFailure() {
        assertEquals("failed", EpisodeOutcomeVerifier.classify(evidence(
            afterUrl = "https://x.com/", afterDom = "home", confirmed = true)).outcome)
    }

    @Test fun crossHostNavigationIsFailure() {
        assertEquals("failed", EpisodeOutcomeVerifier.classify(evidence(afterHost = "example.com", confirmed = true)).outcome)
    }

    @Test fun domChangeWithoutUrlChangeIsObservable() {
        assertEquals("unknown", EpisodeOutcomeVerifier.classify(evidence(afterUrl = "https://x.com/")).outcome)
    }
}
