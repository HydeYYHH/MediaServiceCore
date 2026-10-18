package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AccountSignInTest {
    @Test fun keepsServerCodeAndInterval() {
        val c = readSignInCode("{\"device_code\":\"d\",\"user_code\":\"AB-CD\",\"verification_url\":\"https://youtube.com/activate\",\"expires_in\":60,\"interval\":3}", 100)!!
        assertEquals(60100L, c.expiresAtMs); assertEquals(3000L, c.intervalMs); assertEquals("AB-CD", c.userCode)
        assertNull(readSignInCode("{}", 0))
        assertNull(readSignInCode("{\"device_code\":\"d\",\"user_code\":\"u\",\"verification_url\":\"x\",\"expires_in\":0}", 0))
    }
    @Test fun escapesConfigurationAndRejectsMissingClient() {
        val q = signInQuery(SignInClient("id", "secret", "a\"b"))
        assertEquals("a\"b", jsonObject(q.body)!!.text("device_id"))
        assertEquals("", signInQuery(SignInClient("", "", "d")).url)
    }
}
