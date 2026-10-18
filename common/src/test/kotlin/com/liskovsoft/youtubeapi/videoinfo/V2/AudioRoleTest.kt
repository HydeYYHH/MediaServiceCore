package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AudioRoleTest {
    @Test fun preservesDescriptiveOriginalAndDubbedRoles() {
        fun role(id: String?, auto: Boolean = false, default: Boolean = false) = audioRole(PlayerAudio(id, null, null, default, auto))
        assertEquals(AudioRole.DESCRIPTIVE, role("en.2"))
        assertEquals(AudioRole.ORIGINAL, role("pt-BR.4"))
        assertEquals(AudioRole.DUBBED, role("de-DE.10"))
        assertEquals(AudioRole.AUTO_DUBBED, role("en.2", true))
        assertEquals(AudioRole.ORIGINAL, role(null, default = true))
        assertEquals(AudioRole.UNKNOWN, role(null))
    }
}
