// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.notifier.TaildropNotifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.BrowserOpener
import com.tailscale.ipn.util.PendingTdPayload
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TdPayload
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModelFactory(private val appViewModel: AppViewModel) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(appViewModel) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(private val appViewModel: AppViewModel) : IpnViewModel() {
  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))
  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState
  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  var isToggleInProgress = MutableStateFlow(false)
  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission
  // Select Taildrop directory
  private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers
  // The list of peers
  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers
  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state
  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm
  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)
  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  val isVpnPrepared: StateFlow<Boolean> = appViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = appViewModel.vpnActive

  var searchJob: Job? = null

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

  sealed class PendingTaildropItem {
    abstract val id: String

    data class File(val partial: Ipn.PartialFile) : PendingTaildropItem() {
      override val id: String =
          partial.FinalPath ?: partial.PartialPath ?: "${partial.Name}-${partial.Started}"
    }

    data class Payload(val payload: PendingTdPayload) : PendingTaildropItem() {
      override val id: String = payload.id
    }
  }

  private val consumedFileIds = MutableStateFlow<Set<String>>(emptySet())

  // Go drops files from incomingFiles soon after they finish, so we accumulate
  // Done=true entries here once and let the user act on them.
  private val _pendingFiles = MutableStateFlow<List<Ipn.PartialFile>>(emptyList())

  val pendingFiles: StateFlow<List<Ipn.PartialFile>> =
      _pendingFiles
          .combine(consumedFileIds) { files, consumed ->
            files.filter { PendingTaildropItem.File(it).id !in consumed }
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val pendingItems: StateFlow<List<PendingTaildropItem>> =
      Notifier.tdPayloadInbox
          .combine(pendingFiles) { payloads, files ->
            files.map { PendingTaildropItem.File(it) } +
                payloads.map { PendingTaildropItem.Payload(it) }
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val isPresentingPendingItemsList = MutableStateFlow(false)

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIpAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  // Returns true if we should skip all of the user-interactive permissions prompts
  // (with the exception of the VPN permission prompt)
  fun skipPromptsForAuthKeyLogin(): Boolean {
    val v = MDMSettings.authKey.flow.value.value
    return v != null && v != ""
  }

  private val peerCategorizer = PeerCategorizer()

  init {
    viewModelScope.launch {
      var previousState: State? = null
      combine(Notifier.state, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            stateRes.set(userStringRes(currentState, previousState, active))
            // Determine if the VPN toggle should be on
            val isOn =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true
                  previousState == State.NoState && currentState == State.Starting -> true
                  else -> false
                }
            // Update the VPN toggle state
            _vpnToggleState.value = isOn
            // Update the previous state
            previousState = currentState
          }
    }
    viewModelScope.launch {
      _searchTerm.debounce(250L).collect { term ->
        // run the search as a background task
        searchJob?.cancel()
        searchJob =
            launch(Dispatchers.Default) {
              val filteredPeers = peerCategorizer.groupedAndFilteredPeers(term)
              _searchViewPeers.value = filteredPeers
            }
      }
    }
    viewModelScope.launch {
      Notifier.netmap.collect { it ->
        it?.let { netmap ->
          searchJob?.cancel()
          launch(Dispatchers.Default) {
            peerCategorizer.regenerateGroupedPeers(netmap)
            val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
            _peers.value = peerCategorizer.peerSets
            _searchViewPeers.value = filteredPeers
          }
          if (netmap.SelfNode.keyDoesNotExpire) {
            showExpiry.set(false)
            return@let
          } else {
            val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
            val window =
                expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
            val expiresSoon =
                TimeUtil.isWithinExpiryNotificationWindow(window, it.SelfNode.KeyExpiry ?: "")
            showExpiry.set(expiresSoon)
          }
        }
      }
    }
    viewModelScope.launch {
      App.get().healthNotifier?.currentIcon?.collect { icon -> healthIcon.set(icon) }
    }
    viewModelScope.launch {
      Notifier.incomingFiles.collect { list ->
        val arrivals =
            list.orEmpty().filter { f ->
              f.Done == true &&
                  !f.Name.endsWith(".${TdPayload.fileExtension}") &&
                  !f.Name.endsWith(".partial")
            }
        if (arrivals.isEmpty()) return@collect
        _pendingFiles.update { current ->
          val knownIds = current.map { PendingTaildropItem.File(it).id }.toSet()
          current + arrivals.filter { PendingTaildropItem.File(it).id !in knownIds }
        }
      }
    }
  }

  fun maybeRequestVpnPermission() {
    _requestVpnPermission.value = true
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    TSLog.d("VpnPermissions", "vpnIntent=$vpnIntent")
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    } else {
      appViewModel.setVpnPrepared(true)
      startVPN()
    }
    _requestVpnPermission.value = false // reset
  }

  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      isToggleInProgress.value = true
      try {
        val currentState = Notifier.state.value

        if (desiredState) {
          // User wants to turn ON the VPN
          when {
            currentState != Ipn.State.Running -> showVPNPermissionLauncherIfUnauthorized()
          }
        } else {
          // User wants to turn OFF the VPN
          if (currentState == Ipn.State.Running) {
            stopVPN()
          }
        }
      } finally {
        isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }

  fun enableSearchAutoFocus() {
    autoFocusSearch = true
  }

  fun disableSearchAutoFocus() {
    autoFocusSearch = false
  }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }

  fun handlePendingItemsBannerTap(context: Context) {
    val items = pendingItems.value
    if (items.size >= 2) {
      isPresentingPendingItemsList.value = true
      return
    }
    items.firstOrNull()?.let { consume(context, it) }
  }

  fun consume(context: Context, item: PendingTaildropItem) {
    when (item) {
      is PendingTaildropItem.File -> {
        openFile(context, item.partial)
        consumedFileIds.update { it + item.id }
      }
      is PendingTaildropItem.Payload -> {
        when (item.payload.kind) {
          TdPayload.Kind.URL -> openUrl(context, item.payload.content)
          TdPayload.Kind.TEXT -> copyToClipboard(context, item.payload.content)
        }
        Notifier.removeTdPayload(item.payload.id)
        TaildropNotifier.cancel(context, item.payload.id)
      }
    }
    if (pendingItems.value.isEmpty()) isPresentingPendingItemsList.value = false
  }

  fun dismiss(context: Context, item: PendingTaildropItem) {
    when (item) {
      is PendingTaildropItem.File -> consumedFileIds.update { it + item.id }
      is PendingTaildropItem.Payload -> {
        Notifier.removeTdPayload(item.payload.id)
        TaildropNotifier.cancel(context, item.payload.id)
      }
    }
    if (pendingItems.value.isEmpty()) isPresentingPendingItemsList.value = false
  }

  private fun openUrl(context: Context, content: String) {
    val uri =
        runCatching { Uri.parse(content) }
            .getOrNull()
            ?.takeIf { !it.scheme.isNullOrEmpty() }
    if (uri == null) {
      copyToClipboard(context, content)
      return
    }
    if (!BrowserOpener.openInDefaultBrowser(context, uri)) {
      TSLog.w("MainViewModel", "openUrl failed for $content")
      copyToClipboard(context, content)
    }
  }

  private fun copyToClipboard(context: Context, content: String) {
    val cm =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? AndroidClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Tailscale", content))
    Toast.makeText(context, R.string.taildrop_copied_to_clipboard, Toast.LENGTH_SHORT).show()
  }

  private fun openFile(context: Context, partial: Ipn.PartialFile) {
    val uri =
        partial.FinalPath?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return openTaildropFolder(context)
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, mime)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      TSLog.w("MainViewModel", "openFile fallback to folder: $e")
      openTaildropFolder(context)
    }
  }

  fun openTaildropFolder(context: Context) {
    val treeUri =
        runCatching { TaildropDirectoryStore.loadSavedDir() }.getOrNull()
            ?: run {
              TSLog.w("MainViewModel", "openTaildropFolder: no saved Taildrop dir")
              return
            }
    val docUri =
        runCatching {
              DocumentsContract.buildDocumentUriUsingTree(
                  treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            }
            .getOrNull()
            ?: treeUri
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(docUri, "vnd.android.document/directory")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      TSLog.w("MainViewModel", "openTaildropFolder failed: $e")
    }
  }
}

private fun userStringRes(currentState: State?, previousState: State?, vpnActive: Boolean): Int {
  return when {
    previousState == State.NoState && currentState == State.Starting -> R.string.starting
    currentState == State.NoState -> R.string.placeholder
    currentState == State.InUseOtherUser -> R.string.placeholder
    currentState == State.NeedsLogin ->
        if (vpnActive) R.string.please_login else R.string.connect_to_vpn
    currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
    currentState == State.Stopped -> R.string.stopped
    currentState == State.Starting -> R.string.starting
    currentState == State.Running -> if (vpnActive) R.string.connected else R.string.placeholder
    else -> R.string.placeholder
  }
}
