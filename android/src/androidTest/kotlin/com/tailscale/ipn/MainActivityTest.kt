// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.os.Build
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.apache.commons.codec.binary.Base32
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityTest {
  companion object {
    const val TAG = "MainActivityTest"
  }

  @get:Rule val activityRule = activityScenarioRule<MainActivity>()

  @Before fun setUp() {}

  @After fun tearDown() {}

  /**
   * This test starts with a clean install, logs the user in to a tailnet using credentials provided
   * through a build config, and then makes sure we can hit https://hello.ts.net.
   */
  @Test
  fun loginAndVisitHello() {
    val githubUsername = BuildConfig.GITHUB_USERNAME
    val githubPassword = BuildConfig.GITHUB_PASSWORD
    val github2FASecret = Base32().decode(BuildConfig.GITHUB_2FA_SECRET)
    val config =
        TimeBasedOneTimePasswordConfig(
            codeDigits = 6,
            hmacAlgorithm = HmacAlgorithm.SHA1,
            timeStep = 30,
            timeStepUnit = TimeUnit.SECONDS)
    val githubTOTP = TimeBasedOneTimePasswordGenerator(github2FASecret, config)
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    Log.d(TAG, "Click through Get Started screen")
    device.find(By.text("Get Started"))
    device.find(By.text("Get Started")).click()

    Log.d(TAG, "Wait for VPN permission prompt and accept")
    device.find(By.text("Connection request"))
    device.find(By.text("OK")).click()

    asNecessary(
        2.minutes,
        {
          Log.d(TAG, "Log in")
          device.find(By.text("Log in")).click()
        },
        {
          Log.d(TAG, "Accept Chrome terms and conditions (if necessary)")
          device.find(By.text("Welcome to Chrome"))
          val dismissIndex =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 1 else 0
          device.find(UiSelector().instance(dismissIndex).className(Button::class.java)).click()
        },
        {
          Log.d(TAG, "Don't turn on sync")
          device.find(By.text("Turn on sync?"))
          device.find(By.text("No thanks")).click()
        },
        {
          Log.d(TAG, "Log in with GitHub")
          device.find(By.text("Sign in with GitHub")).click()
        },
        {
          Log.d(TAG, "Make sure GitHub page has loaded")
          device.find(By.text("Username or email address"))
          device.find(By.text("Sign in"))
        },
        {
          Log.d(TAG, "Enter credentials")
          device
              .find(UiSelector().instance(0).className(EditText::class.java))
              .setText(githubUsername)
          device
              .find(UiSelector().instance(1).className(EditText::class.java))
              .setText(githubPassword)
          device.find(By.text("Sign in")).click()
        },
        {
          Log.d(TAG, "Enter 2FA")
          device.find(By.text("Two-factor authentication"))
          device
              .find(UiSelector().instance(0).className(EditText::class.java))
              .setText(githubTOTP.generate())
          device.find(UiSelector().instance(0).className(Button::class.java)).click()
        },
        {
          Log.d(TAG, "Authorizing Tailscale")
          device.find(By.text("Authorize tailscale")).click()
        },
        {
          Log.d(TAG, "Accept Tailscale app")
          device.find(By.text("Learn more about OAuth"))
          // Sleep a little to give button time to activate

          Thread.sleep(5.seconds.inWholeMilliseconds)
          device.find(UiSelector().instance(1).className(Button::class.java)).click()
        },
        {
          Log.d(TAG, "Connect device")
          device.find(By.text("Connect device"))
          device.find(UiSelector().instance(0).className(Button::class.java)).click()
        })

    try {
      Log.d(TAG, "Accept Permission (Either Storage or Notifications)")
      device.find(By.text("Continue")).click()
      device.find(By.text("Allow")).click()
    } catch (t: Throwable) {
      // we're not always prompted for permissions, that's okay
    }

    Log.d(TAG, "Wait for VPN to connect")
    device.find(By.text("Connected"))

    val helloResponse = helloTSNet
    Assert.assertTrue(
        "Response from hello.ts.net should show success",
        helloResponse.contains("You're connected over Tailscale!"))
  }
}

private val helloTSNet: String
  get() {
    return URL("https://hello.ts.net").run {
      openConnection().run {
        this as HttpURLConnection
        connectTimeout = 30000
        readTimeout = 5000
        inputStream.bufferedReader().readText()
      }
    }
  }
