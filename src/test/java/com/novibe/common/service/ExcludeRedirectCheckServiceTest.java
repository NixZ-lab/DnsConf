package com.novibe.common.service;

import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcludeRedirectCheckServiceTest {

    private static ExcludeRedirectCheckService serviceFor(String... domains) {
        return new ExcludeRedirectCheckService(new ExcludeRedirectSettingsLoader() {
            @Override
            public List<String> loadIgnoredDomains() {
                return List.of(domains);
            }
        });
    }

    @Test
    void excludesDomainItself() {
        assertTrue(serviceFor("instagram.com").shouldExclude("instagram.com"));
    }

    @Test
    void excludesSubdomains() {
        ExcludeRedirectCheckService service = serviceFor("instagram.com");
        assertTrue(service.shouldExclude("api.instagram.com"));
        assertTrue(service.shouldExclude("a.b.instagram.com"));
    }

    @Test
    void doesNotExcludeOtherDomainsWithSameEnding() {
        ExcludeRedirectCheckService service = serviceFor("x.com", "ai.com");
        assertFalse(service.shouldExclude("netflix.com"));
        assertFalse(service.shouldExclude("dropbox.com"));
        assertFalse(service.shouldExclude("openai.com"));
        assertFalse(service.shouldExclude("chat.openai.com"));
    }

    @Test void exactExclusionDoesNotExcludeParentOrDescendants() {
        var s=serviceFor("exact:www.openai.com");
        assertTrue(s.shouldExclude("www.openai.com"));
        assertFalse(s.shouldExclude("openai.com"));
        assertFalse(s.shouldExclude("ios.chat.openai.com"));
        assertFalse(s.shouldExclude("nested.www.openai.com"));
    }
    @Test void explicitTreePreservesLiteralWwwAndDomainBoundaries() {
        var s=serviceFor("tree:www.openai.com");
        assertTrue(s.shouldExclude("nested.www.openai.com"));
        assertFalse(s.shouldExclude("openai.com"));
        assertFalse(s.shouldExclude("notwww.openai.com"));
    }
    @Test void legacyWwwHackAndLegacyParentStillMeanExactlyWhatTheyMeant() {
        var old=serviceFor("www.www.openai.com");
        assertTrue(old.shouldExclude("www.openai.com"));
        assertFalse(old.shouldExclude("openai.com"));
        assertTrue(serviceFor("www.openai.com").shouldExclude("ios.chat.openai.com"));
    }
    @Test void appleSpotifyTreesAndExplicitOpenaiExactKeepOtherDomains() {
        var s=serviceFor("tree:apple.com","spotify.com","exact:ios.chat.openai.com");
        assertTrue(s.shouldExclude("api.apple.com"));
        assertTrue(s.shouldExclude("www.spotify.com"));
        assertTrue(s.shouldExclude("ios.chat.openai.com"));
        assertFalse(s.shouldExclude("api.openai.com"));
        assertFalse(s.shouldExclude("spotify.fls.jetbrains.com"));
    }
    @Test void explicitModesNormalizeCaseAndTrailingDotAndRejectTypos() {
        assertTrue(serviceFor(" exact:WWW.OpenAI.Com. ").shouldExclude("WWW.OPENAI.COM."));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->serviceFor("excat:openai.com"));
    }
}
