package com.liskovsoft.youtubeapi.videoinfo.V2

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.DOMImplementation
import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource

class RssAndroidSecurityTest {
    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
        <entry><yt:videoId>jNQXAC9IVRw</yt:videoId><yt:channelId>UCexample</yt:channelId>
        <title>Rock &amp; roll &lt;3</title><author><name>Public author</name></author></entry></feed>"""

    @Test fun unsupportedAndroidControlsStillParseNamespaceAwareAtomWithoutValidationOrExpansion() {
        val factory = UnsupportedOptionsFactory()
        val parsed = parseRssVideos("\uFEFF$atom", factory)
        assertTrue(parsed.ok)
        val video = parsed.value!!.single()
        assertEquals("jNQXAC9IVRw", video.videoId)
        assertEquals("UCexample", video.channelId)
        assertEquals("Rock & roll <3", video.title)
        assertEquals("Public author", video.author)
        assertEquals(3, factory.featureAttempts.size)
        assertEquals(2, factory.attributeAttempts.size)
        assertTrue(factory.xIncludeDisableAttempted)
        assertTrue(factory.isNamespaceAware)
        assertFalse(factory.isValidating)
        assertFalse(factory.isExpandEntityReferences)
        assertEquals(1, factory.buildersCreated)
        assertTrue(factory.resolverInstalled)
    }

    @Test fun declarationsAreRejectedBeforeCreatingAnyBuilderEvenWhenOptionsAreUnsupported() {
        val declarations = listOf(
            "<!DOCTYPE feed SYSTEM 'https://never-read.invalid/feed.dtd'>",
            "<!DOCTYPE feed [<!ENTITY internal 'expanded'>]>",
            "<!DOCTYPE feed [<!ENTITY external SYSTEM 'file:///never-read'>]>",
            "<!DOCTYPE feed [<!ENTITY % remote SYSTEM 'https://never-read.invalid/remote.dtd'>%remote;]>",
            "<!ENTITY standalone 'expanded'>",
            "<!-- <!DOCTYPE feed> -->")
        for (declaration in declarations) {
            val factory = UnsupportedOptionsFactory()
            val result = parseRssVideos(declaration + atom, factory)
            assertFalse(declaration, result.ok)
            assertEquals("invalid_rss", result.error!!.code)
            assertEquals(declaration, 0, factory.buildersCreated)
        }
    }

    @Test fun xIncludeCannotReadExternalResourcesEvenWithoutTheFactorySwitch() {
        for (include in listOf(
            "<xi:include xmlns:xi='http://www.w3.org/2001/XInclude' href='file:///never-read'/>",
            "<包含:include xmlns:包含='http://www.w3.org/2001/&#88;Include' href='https://never-read.invalid'/>")) {
            val factory = UnsupportedOptionsFactory()
            val result = parseRssVideos(atom.replace("</feed>", "$include</feed>"), factory)
            assertFalse(result.ok)
            assertEquals(0, factory.buildersCreated)
        }
    }

    @Test fun rejectingResolverFailsClosedIfTheParserAttemptsAnyExternalResolution() {
        val factory = UnsupportedOptionsFactory(probeExternalResolution = true)
        val result = parseRssVideos(atom, factory)
        assertFalse(result.ok)
        assertEquals("invalid_rss", result.error!!.code)
        assertTrue(factory.resolverInstalled)
        assertTrue(factory.externalResolverCalled)
        assertFalse(factory.externalAccessAllowed)
    }

    @Test fun supportedJdkParserAcceptsAtomButRejectsDtdEntitiesAndMalformedDocuments() {
        assertTrue(rssVideos(atom).ok)
        assertFalse(rssVideos("<!DOCTYPE feed [<!ENTITY x 'expanded'>]>" + atom).ok)
        assertFalse(rssVideos("<!ENTITY x SYSTEM 'https://never-read.invalid'>" + atom).ok)
        assertFalse(rssVideos("<feed xmlns='http://www.w3.org/2005/Atom'>").ok)
        assertEquals("invalid_rss_feed", rssVideos("<feed xmlns='https://wrong.invalid'/>").error!!.code)
    }

    /** Android XML parsers may reject optional features, attributes, and XInclude. */
    private class UnsupportedOptionsFactory(private val probeExternalResolution: Boolean = false) : DocumentBuilderFactory() {
        val featureAttempts = mutableListOf<String>()
        val attributeAttempts = mutableListOf<String>()
        var xIncludeDisableAttempted = false
        var buildersCreated = 0
        var resolverInstalled = false
        var externalResolverCalled = false
        var externalAccessAllowed = false
        override fun setFeature(name: String, value: Boolean) { featureAttempts += name; throw ParserConfigurationException("unsupported") }
        override fun getFeature(name: String): Boolean = throw ParserConfigurationException("unsupported")
        override fun setAttribute(name: String, value: Any?) { attributeAttempts += name; throw IllegalArgumentException("unsupported") }
        override fun getAttribute(name: String): Any = throw IllegalArgumentException("unsupported")
        override fun setXIncludeAware(state: Boolean) {
            assertFalse(state)
            xIncludeDisableAttempted = true
            throw UnsupportedOperationException("unsupported")
        }
        override fun isXIncludeAware(): Boolean = throw UnsupportedOperationException("unsupported")
        override fun newDocumentBuilder(): DocumentBuilder {
            buildersCreated++
            val configured = DocumentBuilderFactory.newInstance().also {
                it.isNamespaceAware = isNamespaceAware
                it.isValidating = isValidating
                it.isExpandEntityReferences = isExpandEntityReferences
                it.isXIncludeAware = false
            }.newDocumentBuilder()
            return object : DocumentBuilder() {
                private var resolver: EntityResolver? = null
                override fun setEntityResolver(value: EntityResolver?) {
                    resolverInstalled = value != null
                    resolver = value
                    configured.setEntityResolver(value)
                }
                override fun setErrorHandler(value: ErrorHandler?) = configured.setErrorHandler(value)
                override fun parse(input: InputSource): Document {
                    if (probeExternalResolution) {
                        externalResolverCalled = true
                        requireNotNull(resolver).resolveEntity(null, "https://never-read.invalid/external")
                        externalAccessAllowed = true
                    }
                    return configured.parse(input)
                }
                override fun isNamespaceAware(): Boolean = configured.isNamespaceAware
                override fun isValidating(): Boolean = configured.isValidating
                override fun newDocument(): Document = configured.newDocument()
                override fun getDOMImplementation(): DOMImplementation = configured.domImplementation
            }
        }
    }
}
