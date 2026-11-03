// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)
  private var lastPrefs: Ipn.Prefs? = null

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap
  private val _nodeState = MutableStateFlow(NodeState.NONE)
  val nodeState: StateFlow<NodeState> = _nodeState
  val managedByOrganization = MDMSettings.managedByOrganizationName.flow

  enum class NodeState {
    NONE,
    ACTIVE_AND_RUNNING,

    // Last selected exit node is active but is not being used.
    ACTIVE_NOT_RUNNING,

    // Last selected exit node is currently offline.
    OFFLINE_ENABLED,

    // Last selected exit node has been de-selected and is currently offline.
    OFFLINE_DISABLED,

    // Exit node selection is managed by an administrator, and last selected exit node is currently
    // offline
    OFFLINE_MDM,
    RUNNING_AS_EXIT_NODE,
  }

  init {
    viewModelScope.launch {
      Notifier.state.collect {
        // Reload the user profiles on all state transitions to ensure loggedInUser is
        // correct
        viewModelScope.launch { loadUserProfiles() }
      }
    }

    // This will observe the userId of the current node and reload our user profiles if
    // we discover it has changed (e.g. due to a login or user switch)
    viewModelScope.launch {
      Notifier.netmap.collect { netmap ->
        netmap?.SelfNode?.User.let {
          if (it != selfNodeUserId) {
            selfNodeUserId = it
            viewModelScope.launch { loadUserProfiles() }
          }
        }
      }
    }

    viewModelScope.launch {
      Notifier.prefs.collect {
        it?.let {
          lastPrefs = it
          isRunningExitNode.set(AdvertisedRoutesHelper.exitNodeOnFromPrefs(it))
        }
      }
    }

    viewModelScope.launch {
      combine(prefs, netmap, isRunningExitNode) { prefs, netmap, isRunningExitNode ->
            // Handle nullability for prefs and netmap
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val chosenExitNodeId = validPrefs.activeExitNodeID ?: validPrefs.selectedExitNodeID
            val exitNodePeer = chosenExitNodeId?.let { id ->
              validNetmap.Peers?.find { it.StableID == id }
            }

            when {
              exitNodePeer?.Online == false -> {
                if (MDMSettings.exitNodeID.flow.value.value != null) {
                  NodeState.OFFLINE_MDM
                } else if (validPrefs.activeExitNodeID != null) {
                  NodeState.OFFLINE_ENABLED
                } else {
                  NodeState.OFFLINE_DISABLED
                }
              }
              exitNodePeer != null -> {
                if (!validPrefs.activeExitNodeID.isNullOrEmpty()) {
                  NodeState.ACTIVE_AND_RUNNING
                } else {
                  NodeState.ACTIVE_NOT_RUNNING
                }
              }
              isRunningExitNode -> {
                NodeState.RUNNING_AS_EXIT_NODE
              }
              else -> {
                NodeState.NONE
              }
            }
          }
          .collect { nodeState -> _nodeState.value = nodeState }
    }
    TSLog.d(TAG, "Created")
  }

  // VPN Control
  fun startVPN() {
    UninitializedApp.get().startVPN()
  }

  fun stopVPN() {
    UninitializedApp.get().stopVPN()
  }

  // Login/Logout

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true. to avoid a race: if
   *    editPrefs() set WantRunning=true, the backend would fire a cc.Login(LoginDefault) on the
   *    existing control client immediately. start() then tears down this client and creates a new
   *    one
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   *
   * start() always tears down the current control client (and with it any in-flight register
   * request and cached auth URL). To avoid repeated taps on "Log in" / "Reauthenticate" cancelling
   * each other, a plain interactive login (no maskedPrefs, no authKey) that arrives while another
   * login is in progress does not call editPrefs()/start() again: if start() already completed it
   * only re-requests the auth URL via startLoginInteractive(), otherwise it is ignored. Interactive
   * login failures, and not receiving an auth URL within [LOGIN_URL_TIMEOUT_MS], are shown to the
   * user instead of failing silently. See tailscale/tailscale#18304.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    val interactiveOnly = maskedPrefs == null && authKey == null
    if (interactiveOnly) {
      when (loginPhase.get()) {
        LoginPhase.STARTING -> {
          TSLog.d(TAG, "login(): already starting, ignoring repeated request")
          completionHandler(Result.success(Unit))
          return
        }
        LoginPhase.AWAITING_URL -> {
          TSLog.d(TAG, "login(): login in progress, re-requesting auth URL without restarting")
          // Clear the last URL so that the same URL being sent again is delivered to the
          // browseToURL collector.
          Notifier.browseToURL.set(null)
          armLoginWatchdog()
          Client(viewModelScope).startLoginInteractive { loginResult ->
            loginResult.onFailure {
              TSLog.e(TAG, "startLoginInteractive() failed: ${it.message}")
              onLoginFailed(showError = true)
            }
            completionHandler(loginResult)
          }
          return
        }
        LoginPhase.IDLE -> {}
      }
    }
    loginPhase.set(LoginPhase.STARTING)
    Notifier.browseToURL.set(null)
    armLoginWatchdog()

    // Start the IPNService foreground notification so that Android
    // does not freeze the process or cut network access while the user is in the browser
    // completing auth. The foreground service transitions to a full VPN service later when
    // startVPN() is called after the backend reaches Running state.
    UninitializedApp.get().startForegroundForLogin()
    val client = Client(viewModelScope)

    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()
    // Don't set WantRunning=true here. Setting it in editPrefs() triggers cc.Login(LoginDefault)
    // in the Go backend on the existing control client; when the user taps "Log in," login() calls
    // start(), which triggers resetControlClientLocked(), cancelling the existing control client
    // and creating a new one. On a slow connection, the first control client's register is still
    // in flight when it is canceled. Instead, set WantRunning=true on the Prefs returned by
    // editPrefs() and pass
    // it via start()'s UpdatePrefs, which resets the control client first.
    if (authKey != null) {
      finalMaskedPrefs.LoggedOut = false
    }

    client.editPrefs(finalMaskedPrefs) { editResult ->
      editResult
          .onFailure {
            TSLog.e(TAG, "editPrefs() failed: ${it.message}")
            onLoginFailed(showError = interactiveOnly)
            completionHandler(Result.failure(it))
          }
          .onSuccess { success ->
            success.WantRunning = true
            val opts = Ipn.Options(UpdatePrefs = success, AuthKey = authKey)
            client.start(opts) { startResult ->
              startResult
                  .onFailure {
                    TSLog.e(TAG, "start() failed: ${it.message}")
                    onLoginFailed(showError = interactiveOnly)
                    completionHandler(Result.failure(it))
                  }
                  .onSuccess {
                    // start() has replaced the control client; from here on a repeated login
                    // request only needs startLoginInteractive().
                    loginPhase.compareAndSet(LoginPhase.STARTING, LoginPhase.AWAITING_URL)
                    client.startLoginInteractive { loginResult ->
                      loginResult
                          .onFailure {
                            TSLog.e(
                                TAG,
                                "startLoginInteractive() failed: ${it.message}",
                            )
                            onLoginFailed(showError = interactiveOnly)
                            completionHandler(Result.failure(it))
                          }
                          .onSuccess { completionHandler(Result.success(Unit)) }
                    }
                  }
            }
          }
    }
  }

  private fun onLoginFailed(showError: Boolean) {
    loginWatchdog?.cancel()
    loginPhase.set(LoginPhase.IDLE)
    if (showError) {
      showLoginError(R.string.login_failed)
    }
  }

  fun loginWithAuthKey(authKey: String, completionHandler: (Result<Unit>) -> Unit = {}) {
    val prefs = Ipn.MaskedPrefs()
    prefs.WantRunning = true
    login(prefs, authKey = authKey, completionHandler)
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    val prefs = Ipn.MaskedPrefs()
    prefs.ControlURL = controlURL
    login(prefs, completionHandler = completionHandler)
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    Client(viewModelScope).logout { result ->
      result
          .onSuccess { TSLog.d(TAG, "Logout started: $it") }
          .onFailure { TSLog.e(TAG, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles() {
    Client(viewModelScope).profiles { result ->
      result.onSuccess(loginProfiles::set).onFailure {
        TSLog.e(TAG, "Error loading profiles: ${it.message}")
      }
    }

    Client(viewModelScope).currentProfile { result ->
      result
          .onSuccess { loggedInUser.set(if (it.isEmpty()) null else it) }
          .onFailure { TSLog.e(TAG, "Error loading current profile: ${it.message}") }
    }
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    val switchProfile = {
      Client(viewModelScope).switchProfile(profile) {
        startVPN()
        completionHandler(it)
      }
    }
    Client(viewModelScope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) { result ->
      result
          .onSuccess { switchProfile() }
          .onFailure { TSLog.e(TAG, "Error setting wantRunning to false: ${it.message}") }
    }
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).addProfile {
      if (it.isSuccess) {
        login()
      }
      startVPN()
      completionHandler(it)
    }
  }

  fun deleteProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).deleteProfile(profile) {
      viewModelScope.launch { loadUserProfiles() }
      completionHandler(it)
    }
  }

  // Exit Node Manipulation

  fun toggleExitNode() {
    val prefs = prefs.value ?: return

    LoadingIndicator.start()
    if (prefs.activeExitNodeID != null) {
      // We have an active exit node so we should keep it, but disable it
      Client(viewModelScope).setUseExitNode(false) { LoadingIndicator.stop() }
    } else if (prefs.selectedExitNodeID != null) {
      // We have a prior exit node to enable
      Client(viewModelScope).setUseExitNode(true) { LoadingIndicator.stop() }
    } else {
      // This should not be possible.  In this state the button is hidden
      TSLog.e(TAG, "No exit node to disable and no prior exit node to enable")
    }
  }

  fun setRunningExitNode(isOn: Boolean) {
    LoadingIndicator.start()
    lastPrefs?.let { currentPrefs ->
      val newPrefs: Ipn.MaskedPrefs =
          if (isOn) {
            setZeroRoutes(currentPrefs)
          } else {
            removeAllZeroRoutes(currentPrefs)
          }
      Client(viewModelScope).editPrefs(newPrefs) { result ->
        LoadingIndicator.stop()
        TSLog.d("RunExitNodeViewModel", "Edited prefs: $result")
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = removeAllZeroRoutes(prefs).AdvertiseRoutes.orEmpty().toMutableList()
    newRoutes.add("0.0.0.0/0")
    newRoutes.add("::/0")
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = emptyList<String>().toMutableList()
    prefs.AdvertiseRoutes.orEmpty().forEach {
      if (it != "0.0.0.0/0" && it != "::/0") {
        newRoutes.add(it)
      }
    }
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  companion object {
    /** How long to wait for an auth URL (or login completion) before telling the user. */
    const val LOGIN_URL_TIMEOUT_MS = 30_000L

    /** How long a login whose auth URL was delivered is still considered in progress. */
    private const val LOGIN_IN_PROGRESS_MAX_MS = 10 * 60_000L

    private enum class LoginPhase {
      IDLE,
      // editPrefs()/start() in flight.
      STARTING,
      // start() done; waiting for the auth URL or for the user to finish in the browser.
      AWAITING_URL,
    }

    // Shared by all IpnViewModel instances, since login can be triggered from several screens.
    private val loginPhase = AtomicReference(LoginPhase.IDLE)
    @Volatile private var loginWatchdog: Job? = null

    /**
     * Waits for an auth URL or for login to complete. If neither happens within
     * [LOGIN_URL_TIMEOUT_MS] the login is considered stuck: the phase is reset so the next tap
     * restarts the flow, and the user is told. Runs in the application scope so it outlives the
     * screen that started the login.
     */
    private fun armLoginWatchdog() {
      val tag = "IpnViewModel"
      // Compare against the values at the time of the request (rather than skipping the first
      // emission) so that a change which happens before the coroutine subscribes is not missed.
      val startCount = Notifier.browseToURLCount.value
      val startState = Notifier.state.value
      loginWatchdog?.cancel()
      loginWatchdog =
          App.get().applicationScope.launch {
            val loginDone =
                Notifier.state.filter {
                  it != startState && (it == Ipn.State.Running || it == Ipn.State.NeedsMachineAuth)
                }
            val finished =
                withTimeoutOrNull(LOGIN_URL_TIMEOUT_MS) {
                  merge(
                          Notifier.browseToURLCount.filter { it != startCount }.map { false },
                          loginDone.map { true },
                      )
                      .first()
                }
            when (finished) {
              null -> {
                TSLog.w(tag, "login: no auth URL received after ${LOGIN_URL_TIMEOUT_MS}ms")
                loginPhase.set(LoginPhase.IDLE)
                showLoginError(R.string.login_timeout)
              }
              true -> loginPhase.set(LoginPhase.IDLE)
              false -> {
                // Auth URL delivered. Keep treating repeated taps as "show the URL again" until
                // the user finishes (or abandons) the login.
                val urlState = Notifier.state.value
                withTimeoutOrNull(LOGIN_IN_PROGRESS_MAX_MS) {
                  Notifier.state.first { it != urlState && it != Ipn.State.NeedsLogin }
                }
                loginPhase.set(LoginPhase.IDLE)
              }
            }
          }
    }

    private fun showLoginError(@StringRes message: Int) {
      App.get().applicationScope.launch {
        withContext(Dispatchers.Main) {
          Toast.makeText(App.get(), message, Toast.LENGTH_LONG).show()
        }
      }
    }
  }
}
