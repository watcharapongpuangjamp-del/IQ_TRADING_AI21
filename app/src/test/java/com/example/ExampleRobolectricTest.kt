package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppNavigation
import com.example.ui.theme.IQTradingTheme
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
  fun `firebase bootstrap handles initialization safely without throwing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val hasGoogleAppId = context.resources.getIdentifier(
      "google_app_id",
      "string",
      context.packageName
    ) != 0
    val initialized = FirebaseBootstrap.ensureInitialized(context)
    assertEquals(hasGoogleAppId, initialized)
    if (hasGoogleAppId) {
      assertTrue(FirebaseApp.getApps(context).isNotEmpty())
      assertNotNull(FirebaseBootstrap.getAuthOrNull(context))
    }
  }

  @Test
  fun `app navigation renders without IllegalStateException`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val initialized = FirebaseBootstrap.ensureInitialized(context)
    composeTestRule.setContent {
      IQTradingTheme {
        AppNavigation()
      }
    }
    composeTestRule.waitForIdle()
    val expectedTag = if (initialized) {
      "google_sign_in_button"
    } else {
      "firebase_config_missing_screen"
    }
    composeTestRule.onNodeWithTag(expectedTag).assertExists()
  }
}
