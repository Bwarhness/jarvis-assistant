package dk.foss.jarvis.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationContextBuilderTest {

    @Test
    fun `coarse with place name sends no coordinates`() {
        val msg = LocationContextBuilder.build(
            placeName = "Dresden, Germany",
            latitude = 51.0504,
            longitude = 13.7373,
            precise = false,
            fixAgeSeconds = 12,
        )
        assertEquals(
            "USER_CONTEXT location = Dresden, Germany | accuracy = coarse | fix_age = 12s",
            msg!!.content,
        )
        assertEquals("system", msg.role)
    }

    @Test
    fun `precise with place name includes coordinates at 4 decimals`() {
        val msg = LocationContextBuilder.build(
            placeName = "Dresden, Germany",
            latitude = 51.05041,
            longitude = 13.73726,
            precise = true,
            fixAgeSeconds = 5,
        )
        assertEquals(
            "USER_CONTEXT location = Dresden, Germany (51.0504, 13.7373) | accuracy = precise | fix_age = 5s",
            msg!!.content,
        )
    }

    @Test
    fun `coarse without place name sends nothing`() {
        assertNull(
            LocationContextBuilder.build(
                placeName = null,
                latitude = 51.05,
                longitude = 13.74,
                precise = false,
                fixAgeSeconds = 0,
            ),
        )
    }

    @Test
    fun `precise without place name sends coordinates only`() {
        val msg = LocationContextBuilder.build(
            placeName = null,
            latitude = 51.0504,
            longitude = 13.7373,
            precise = true,
            fixAgeSeconds = 30,
        )
        assertEquals(
            "USER_CONTEXT location = unknown (51.0504, 13.7373) | accuracy = precise | fix_age = 30s",
            msg!!.content,
        )
    }

    @Test
    fun `stale fix age is reflected in the grammar`() {
        val msg = LocationContextBuilder.build(
            placeName = "Prague, Czechia",
            latitude = 0.0,
            longitude = 0.0,
            precise = false,
            fixAgeSeconds = 612,
        )
        assertEquals(
            "USER_CONTEXT location = Prague, Czechia | accuracy = coarse | fix_age = 612s",
            msg!!.content,
        )
    }

    @Test
    fun `negative coordinates format correctly in precise mode`() {
        val msg = LocationContextBuilder.build(
            placeName = null,
            latitude = -33.8688,
            longitude = 151.2093,
            precise = true,
            fixAgeSeconds = 1,
        )
        assertEquals(
            "USER_CONTEXT location = unknown (-33.8688, 151.2093) | accuracy = precise | fix_age = 1s",
            msg!!.content,
        )
    }
}
