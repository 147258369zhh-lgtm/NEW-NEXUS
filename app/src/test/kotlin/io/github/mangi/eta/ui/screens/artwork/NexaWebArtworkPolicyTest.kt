package io.github.mangi.eta.ui.screens.artwork

import org.junit.Assert.*
import org.junit.Test

class NexaWebArtworkPolicyTest {
    @Test fun localDependenciesResolveFromTheirOwningDocument() {
        assertEquals("images/pelican ride.png", NexaWebArtworkStore.resourcePath("css/main.css", "../images/pelican%20ride.png?v=3#icon"))
        assertEquals("modules/wing.js", NexaWebArtworkStore.resourcePath("index.html", "./modules/wing.js"))
    }

    @Test fun privatePathsAndNetworkAddressesCannotBecomeBundledFiles() {
        listOf("../private.json", "a/../../private.json", "%2e%2e%2fprivate.json", "file:///data/private", "content://private", "https://example.com/main.js", "//example.com/main.js", "a\\b.js", "bad%00name.js", "/private.json").forEach {
            assertNull(it, NexaWebArtworkStore.resourcePath("index.html", it))
        }
    }

    @Test fun onlyWebDocumentsOfferAnExecutablePreview() {
        assertTrue(NexaWebArtworkStore.accepts("html", "<canvas id='pelican'></canvas>"))
        assertTrue(NexaWebArtworkStore.accepts(null, "  <!DOCTYPE html><html></html>"))
        assertTrue(NexaWebArtworkStore.accepts("svg", "<svg></svg>"))
        assertFalse(NexaWebArtworkStore.accepts("kotlin", "fun main() {}"))
        assertFalse(NexaWebArtworkStore.accepts("javascript", "alert('not a document')"))
    }
}
