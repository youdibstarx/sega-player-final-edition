package com.segaiptv.player

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class BackendTrustConfigTest {
    @Test fun bundledCaIsTheOfficialSelfSignedIsrgRootX1() {
        val cert = File("src/main/res/raw/isrg_root_x1.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertTrue(cert.basicConstraints >= 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6", digest)
    }

    @Test fun additionalTrustIsRestrictedToTheExactHttpsPanelHost() {
        val factory = DocumentBuilderFactory.newInstance()
        val xml = factory.newDocumentBuilder().parse(File("src/main/res/xml/network_security_config.xml"))
        val domains = xml.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        val domain = domains.item(0) as org.w3c.dom.Element
        assertEquals("sega.faster4k.xyz", domain.textContent.trim())
        assertEquals("false", domain.getAttribute("includeSubdomains"))
        val panel = domain.parentNode as org.w3c.dom.Element
        assertEquals("false", panel.getAttribute("cleartextTrafficPermitted"))
        val anchors = xml.getElementsByTagName("certificates")
        var customCount = 0
        for (i in 0 until anchors.length) {
            val anchor = anchors.item(i) as org.w3c.dom.Element
            when (anchor.getAttribute("src")) {
                "@raw/isrg_root_x1" -> {
                    customCount++
                    assertSame(panel, anchor.parentNode.parentNode)
                }
                else -> assertEquals("system", anchor.getAttribute("src"))
            }
        }
        assertEquals(1, customCount)
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("@xml/network_security_config", app.getAttribute("android:networkSecurityConfig"))
    }
}
