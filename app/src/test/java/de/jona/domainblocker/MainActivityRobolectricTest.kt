package de.jona.domainblocker

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MainActivityRobolectricTest {

    @Test
    fun activityReachesResumedAfterInflatingMainLayout() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .get()

        assertEquals(false, activity.isFinishing)
        assertNotNull(activity.findViewById<android.view.View>(R.id.switch_filter))
        assertNotNull(activity.findViewById<android.view.View>(R.id.edit_domain))
        assertNotNull(activity.findViewById<android.view.View>(R.id.edit_redirect))
    }

    @Test
    fun applicationUsesExpectedThemeAndBackground() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .get()

        assertEquals("de.jona.domainblocker", context.packageName)
        assertEquals(
            context.getColor(R.color.background),
            activity.window.decorView.background?.let {
                val drawable = it as android.graphics.drawable.ColorDrawable
                drawable.color
            }
        )
        assertNotNull(
            android.graphics.BitmapFactory.decodeResource(
                context.resources,
                R.drawable.ic_launcher_foreground_gen
            )
        )
    }
}
