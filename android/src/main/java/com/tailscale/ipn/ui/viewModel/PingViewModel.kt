// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.dataSource.PingDataSource
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.ConnectionMode
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PingViewModelFactory(
    private val dataSource: PingDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(PingViewModel::class.java)) {
      return PingViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class PingViewModel(
    private val dataSource: PingDataSource,
) : ViewModel() {
  private val TAG = PingViewModel::class.simpleName

  // The timer ticks every second, for a maximum of 10 seconds, hence triggering 10 ping
  // requests.
  private val timer =
      object : CountDownTimer(1000 * 10, 1000) {
        override fun onTick(millisUntilFinished: Long) {
          sendPing()
          fetchStatusAndUpdateConnectionMode()
        }

        override fun onFinish() {
          TSLog.d(TAG, "Ping timer terminated")
        }
      }

  override fun onCleared() {
    timer.cancel()
  }

  // The peer to ping.
  private val _peer = MutableStateFlow<Tailcfg.Node?>(null)
  val peer: StateFlow<Tailcfg.Node?> = _peer.asStateFlow()
  // Whether we are using a relayed or direct connection. Will be NotConnected until the first
  // PeerStatus value has been fetched. NotConnected is not surfaced to the user.
  private val _connectionMode = MutableStateFlow<ConnectionMode>(ConnectionMode.NotConnected())
  val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()
  // An error message to display if any request fails. Non-null if an error message must be surfaced
  // to the user. If a subsequent request succeeds, this property should be set to null again.
  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
  // The last latency value in a human-readable format (e.g. "14.5 ms").
  private val _lastLatencyValue = MutableStateFlow<String>("")
  val lastLatencyValue: StateFlow<String> = _lastLatencyValue.asStateFlow()
  // A list of latency values over time in milliseconds. These are used to plot the latency
  // values in the chart.
  private val _latencyValues = MutableStateFlow<List<Double>>(emptyList())
  val latencyValues: StateFlow<List<Double>> = _latencyValues.asStateFlow()

  fun startPing(peer: Tailcfg.Node) {
    if (_peer.value?.StableID == peer.StableID) return

    _peer.value = peer
    timer.start()
  }

  // sendPing asks the backend to send one ping to the peer and handles the response.
  // It checks for any errors in the response Err field. If an error is present, it sets the
  // errorMessage property to a non-null value and returns. If there is no error, it updates the
  // lastLatencyValue property with the formatted latency, and adds the latency value to the
  // latencyValues list.
  private fun sendPing() {
    peer.value?.let { peer ->
      dataSource.ping(viewModelScope, peer) { response ->
        response.onSuccess { pingResult ->
          val error = pingResult.Err
          if (error.isNotEmpty()) {
            _errorMessage.value = error.replaceFirstChar { it.uppercase() }
            return@onSuccess
          } else {
            _errorMessage.value = null
            val latency: Double = pingResult.LatencySeconds * 1000
            _lastLatencyValue.value = "${latency.roundedString(1)} ms"
            _latencyValues.value = this.latencyValues.value + latency
          }
        }
        response.onFailure { error ->
          val context: Context = App.get().applicationContext
          val stringError = error.toString()
          TSLog.d(TAG, "Ping request failed: $stringError")
          if (stringError.contains("timeout")) {
            _errorMessage.value =
                context.getString(
                    R.string.request_timed_out_make_sure_that_is_online,
                    peer.ComputedName,
                )
          } else {
            _errorMessage.value =
                context.getString(R.string.an_unknown_error_occurred_please_try_again)
          }
        }
      }
    }
  }

  // fetchStatusAndUpdateConnectionMode fetches the PeerStatus for the peer and updates the
  // connectionMode property as soon as a direct connection is finally established.
  private fun fetchStatusAndUpdateConnectionMode() {
    dataSource.status(viewModelScope) { statusResult ->
      statusResult.onSuccess { result ->
        result.Peer?.let { map ->
          map[peer.value?.Key]?.let { peerStatus ->
            val curAddr = peerStatus.CurAddr.orEmpty()
            val peerRelay = peerStatus.PeerRelay.orEmpty()
            val relay = peerStatus.Relay.orEmpty()
            if (curAddr.isNotEmpty()) {
              _connectionMode.value = ConnectionMode.Direct()
            } else if (peerRelay.isNotEmpty()) {
              _connectionMode.value = ConnectionMode.PeerRelay()
            } else if (relay.isNotEmpty()) {
              _connectionMode.value = ConnectionMode.Derp(relayName = relay.uppercase())
            }
          }
        }
      }
      statusResult.onFailure { TSLog.d(TAG, "Failed to fetch status: $it") }
    }
  }
}

private fun Double.roundedString(decimals: Int): String = "%.${decimals}f".format(this)
