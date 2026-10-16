package cz.audioreplay

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.audioreplay.controls.ReplayWidget
import cz.audioreplay.core.WidgetLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetTest {
    @Test fun allWidgetSizesInflateWithExpectedControls() {
        val context=ApplicationProvider.getApplicationContext<ReplayApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WidgetLayout.entries.forEach { layout ->
                val view=ReplayWidget.createViews(context,layout).apply(context,FrameLayout(context))
                val hasStart=view.findViewById<android.view.View>(R.id.widget_start) != null
                assertEquals(layout in setOf(WidgetLayout.WIDE,WidgetLayout.FULL),hasStart)
                assertEquals(layout != WidgetLayout.COMPACT,view.findViewById<android.view.View>(R.id.widget_minute) != null)
                assertEquals(layout in setOf(WidgetLayout.COMPACT,WidgetLayout.FULL),view.findViewById<android.view.View>(R.id.widget_save) != null)
            }
        }
    }
    @Test fun adaptiveIconHasGreenBackgroundWithoutWhitePlate() {
        val context=ApplicationProvider.getApplicationContext<ReplayApplication>()
        val icon=context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val background=(icon as AdaptiveIconDrawable).background
        assertTrue(background is ColorDrawable)
        assertEquals(Color.rgb(38,94,82),(background as ColorDrawable).color)
    }
}
