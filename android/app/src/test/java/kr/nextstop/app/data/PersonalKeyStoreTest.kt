package kr.nextstop.app.data

import android.content.Context
import kr.nextstop.app.domain.Provider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalKeyStoreTest {
    @Test fun `키를 평문으로 저장하지 않고 매번 다른 암호문을 사용한다`() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = PersonalKeyStore(context) { key }
        val preferences = context.getSharedPreferences("personal_api_keys", Context.MODE_PRIVATE)
        store.save(Provider.TAGO, "personal-test-key+123=")
        val first = preferences.getString("TAGO", null)!!
        assertFalse(first.contains("personal-test-key"))
        assertEquals("personal-test-key+123=", store.read(Provider.TAGO))
        store.save(Provider.TAGO, "personal-test-key+123=")
        assertNotEquals(first, preferences.getString("TAGO", null))
        store.delete(Provider.TAGO)
        assertFalse(store.has(Provider.TAGO))
        assertFalse(preferences.contains("TAGO"))
    }
    @Test fun `변조되거나 평문인 저장값은 복호화하지 않는다`() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = PersonalKeyStore(context) { key }
        val preferences = context.getSharedPreferences("personal_api_keys", Context.MODE_PRIVATE)
        store.save(Provider.TAGO, "personal-test-key")
        val original = preferences.getString("TAGO", null)!!
        preferences.edit().putString("TAGO", original.substringBefore(":") + ":AAAA").commit()
        assertNull(store.read(Provider.TAGO))
        preferences.edit().putString("TAGO", "personal-test-key").commit()
        assertNull(store.read(Provider.TAGO))
    }
}
