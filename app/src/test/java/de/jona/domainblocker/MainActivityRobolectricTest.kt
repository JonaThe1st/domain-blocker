package de.jona.domainblocker

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = BlockerApp::class
)
class MainActivityRobolectricTest {

    @Test
    fun activityReachesResumedWithAllViewsAndLogo() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertTrue(activity.window.decorView.isShown || !activity.isFinishing)
        assertNotNull(activity.findViewById(R.id.title))
        assertNotNull(activity.findViewById(R.id.switch_filter))
        assertNotNull(activity.findViewById(R.id.edit_domain))
        assertNotNull(activity.findViewById(R.id.btn_add))
        assertNotNull(activity.findViewById(R.id.list_domains))
        assertNotNull(activity.findViewById(R.id.edit_redirect))
        assertNotNull(activity.findViewById(R.id.btn_save))
        assertNotNull(activity.findViewById(R.id.crash_log))

        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val logo = BitmapFactory.decodeResource(
            app.resources,
            R.drawable.ic_launcher_foreground_gen
        )
        assertNotNull(logo)
        assertTrue(logo.width > 0 && logo.height > 0)
    }
}
