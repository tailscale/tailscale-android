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
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.ConnectionMode
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.roundedString
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PingViewModelFactory(private val peer: Tailcfg.Node) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PingViewModel() as T
  }
}

class PingViewModel : ViewModel() {
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

  // The peer to ping.
  var peer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)
  // Whether we are using a relayed or direct connection. Will be NotConnected until the first
  // PeerStatus value has been fetched. NotConnected is not surfaced to the user.
  val connectionMode: StateFlow<ConnectionMode> = MutableStateFlow(ConnectionMode.NotConnected())
  // An error message to display if any request fails. Non-null if an error message must be surfaced
  // to the user. If a subsequent request succeeds, this property should be set to null again.
  val errorMessage: StateFlow<String?> = MutableStateFlow(null)
  // The last latency value in a human-readable format (e.g. "14.5 ms").
  val lastLatencyValue: StateFlow<String> = MutableStateFlow("")
  // A list of latency values over time in milliseconds. These are used to plot the latency
  // values in the chart.
  var latencyValues: StateFlow<List<Double>> = MutableStateFlow(emptyList())

  fun startPing(peer: Tailcfg.Node) {
    this.peer.set(peer)
    timer.start()
  }

  fun handleDismissal() {
    timer.cancel()
    this.peer.set(null)
    this.connectionMode.set(ConnectionMode.NotConnected())
    this.lastLatencyValue.set("")
    this.latencyValues.set(emptyList())
    this.errorMessage.set(null)
  }

  // sendPing asks the backend to send one ping to the peer and handles the response.
  // It checks for any errors in the response Err field. If an error is present, it sets the
  // errorMessage property to a non-null value and returns. If there is no error, it updates the
  // lastLatencyValue property with the formatted latency, and adds the latency value to the
  // latencyValues list.
  private fun sendPing() {
    peer.value?.let { peer ->
      Client(viewModelScope).ping(peer) { response ->
        response.onSuccess { pingResult ->
          val error = pingResult.Err
          if (error.isNotEmpty()) {
            this.errorMessage.set(error.replaceFirstChar { it.uppercase() })
            return@onSuccess
          } else {
            this.errorMessage.set(null)
            val latency: Double = pingResult.LatencySeconds * 1000
            this.lastLatencyValue.set("${latency.roundedString(1)} ms")
            this.latencyValues.set(this.latencyValues.value + latency)
          }
        }
        response.onFailure { error ->
          val context: Context = App.get().applicationContext
          val stringError = error.toString()
          TSLog.d(TAG, "Ping request failed: $stringError")
          if (stringError.contains("timeout")) {
            this.errorMessage.set(
                context.getString(
                    R.string.request_timed_out_make_sure_that_is_online, peer.ComputedName))
          } else {
            this.errorMessage.set(
                context.getString(R.string.an_unknown_error_occurred_please_try_again))
          }
        }
      }
    }
  }

  // fetchStatusAndUpdateConnectionMode fetches the PeerStatus for the peer and updates the
  // connectionMode property as soon as a direct connection is finally established.
  private fun fetchStatusAndUpdateConnectionMode() {
    Client(viewModelScope).status { statusResult ->
      statusResult.onSuccess { result ->
        result.Peer?.let { map ->
          map[peer.value?.Key]?.let { peerStatus ->
            val curAddr = peerStatus.CurAddr.orEmpty()
            val peerRelay = peerStatus.PeerRelay.orEmpty()
            val relay = peerStatus.Relay.orEmpty()
            if (curAddr.isNotEmpty()) {
              this.connectionMode.set(ConnectionMode.Direct())
            } else if (peerRelay.isNotEmpty()) {
              this.connectionMode.set(ConnectionMode.PeerRelay())
            } else if (relay.isNotEmpty()) {
              this.connectionMode.set(ConnectionMode.Derp(relayName = relay.uppercase()))
            }
          }
        }
      }
      statusResult.onFailure { TSLog.d(TAG, "Failed to fetch status: $it") }
    }
  }
}
