package de.jona.domainblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val blocked = setOf("instagram.com", "doubleclick.net")

    @Test
    fun exactMatch() {
        assertTrue(DomainMatcher.isBlocked("instagram.com", blocked))
    }

    @Test
    fun subdomainMatch() {
        assertTrue(DomainMatcher.isBlocked("www.instagram.com", blocked))
        assertTrue(DomainMatcher.isBlocked("cdn.www.instagram.com", blocked))
    }

    @Test
    fun notAfalsePositive() {
        assertFalse(DomainMatcher.isBlocked("notinstagram.com", blocked))
        assertFalse(DomainMatcher.isBlocked("instagram.com.evil.com", blocked))
        assertFalse(DomainMatcher.isBlocked("instagramcom", blocked))
        assertFalse(DomainMatcher.isBlocked("example.org", blocked))
    }

    @Test
    fun caseAndDotInsensitive() {
        assertTrue(DomainMatcher.isBlocked("INSTAGRAM.COM", blocked))
        assertTrue(DomainMatcher.isBlocked("instagram.com.", blocked))
        assertTrue(DomainMatcher.isBlocked("  WWW.INSTAGRAM.COM.  ", setOf(" Instagram.COM. ")))
    }

    @Test
    fun emptyBlockedEntriesAreIgnored() {
        assertFalse(DomainMatcher.isBlocked("example.com", setOf("", "   ", ".")))
    }

    @Test
    fun emptyNeverMatches() {
        assertFalse(DomainMatcher.isBlocked("", blocked))
        assertFalse(DomainMatcher.isBlocked("   ", blocked))
    }

    @Test
    fun normalizeCleansUserInput() {
        assertEquals("instagram.com", DomainMatcher.normalize("https://www.Instagram.com/foo"))
        assertEquals("instagram.com", DomainMatcher.normalize("http://instagram.com"))
        assertEquals("doubleclick.net", DomainMatcher.normalize(" doubleclick.net "))
        assertEquals("x.com", DomainMatcher.normalize("X.com:8080/path"))
        assertNull(DomainMatcher.normalize(""))
        assertNull(DomainMatcher.normalize("not a host"))
        assertNull(DomainMatcher.normalize("foo\tbar.com"))
        assertNull(DomainMatcher.normalize("foo\nbar.com"))
        assertNull(DomainMatcher.normalize("localhost"))
    }

    @Test
    fun normalizeSupportsIdnLikeUnicodeAndTrailingDot() {
        assertEquals("例え.テスト", DomainMatcher.normalize("https://例え.テスト./path"))
        assertEquals("example.com", DomainMatcher.normalize("WWW.EXAMPLE.COM."))
    }

    @Test
    fun normalizeRejectsEmptyOrDotOnlyHosts() {
        assertNull(DomainMatcher.normalize("..."))
        assertNull(DomainMatcher.normalize("  "))
    }
}
