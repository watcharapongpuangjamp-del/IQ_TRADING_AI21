package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppNavigation
import com.example.ui.theme.IQTradingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("IQ Trading AI", appName)
  }

  @Test
  fun `app navigation boots directly into local sqlite terminal`() {
    composeTestRule.setContent {
      IQTradingTheme {
        AppNavigation()
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("nav_terminal").assertExists()
  }
}
