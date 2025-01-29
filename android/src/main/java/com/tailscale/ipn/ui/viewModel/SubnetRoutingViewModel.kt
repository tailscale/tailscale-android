// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * SubnetRoutingViewModel is responsible for managing the content of the subnet router management view.
 * This class watches the backend preferences and updates the UI accordingly whenever the advertised routes
 * change. It also handles the state of the editing dialog, and updates the preferences stored in
 * the backend when the routes are edited in the UI.
 */
class SubnetRoutingViewModel : ViewModel() {
    private val TAG = "SubnetRoutingViewModel"

    /**
     * Matches the value of the "RouteAll" backend preference.
     */
    val routeAll: StateFlow<Boolean> = MutableStateFlow(true)

    /**
     * The advertised routes displayed at any point in time in the UI. The class observes
     * this value for changes, and updates the backend preferences accordingly.
     */
    val advertisedRoutes: StateFlow<List<String>> = MutableStateFlow(listOf())

    /**
     * Whether we are presenting the add/edit dialog to set/change the value of a route.
     */
    val isPresentingDialog: StateFlow<Boolean> = MutableStateFlow(false)

    /**
     * When editing a route, this stores the initial value. It is used to determine which
     * of the previously existing routes needs to be updated. This starts as empty, and dismissing
     * the edit dialog should reset it to empty as well.
     * If the user is adding a new route, this will be empty despite isPresentingDialog being true.
     */
    private val editingRoute: StateFlow<String> = MutableStateFlow("")

    /**
     * The value currently entered in the add/edit dialog text field.
     */
    val dialogTextFieldValue: MutableStateFlow<String> = MutableStateFlow("")

    /**
     * True if the value currently entered in the dialog text field is valid, false otherwise.
     * If the text field is empty, this returns true as we don't want to display an error state
     * when the user hasn't entered anything.
     */
    val isTextFieldValueValid: StateFlow<Boolean> = MutableStateFlow(true)

    /**
     * If an error occurred while saving the ipn.Prefs to the backend this value is
     * non-null. Subsequent successful attempts to save will clear it.
     */
    val currentError: MutableStateFlow<String?> = MutableStateFlow(null)

    init {
        viewModelScope.launch {
            // Any time the value entered by the user in the add/edit dialog changes, we determine
            // whether it is valid or invalid, and set isTextFieldValueValid accordingly.
            dialogTextFieldValue
                .collect { newValue ->
                    if (newValue.isEmpty()) {
                        isTextFieldValueValid.set(true)
                        return@collect
                    }
                    val isValid = isValidCIDR(newValue)
                    Log.v(TAG, "isValidCIDR($newValue): $isValid")
                    isTextFieldValueValid.set(isValid)
                    return@collect
                }
        }

        viewModelScope.launch {
            // Similarly, if the routes change in the backend at any time, we should also reflect
            // that change in the UI.
            Notifier.prefs
                // Ignore any prefs updates without AdvertiseRoutes
                .mapNotNull { it?.AdvertiseRoutes }
                // Ignore duplicate values to prevent an unnecessary UI update
                .distinctUntilChanged()
                // Ignore any value that matches the current value in UI,
                // to prevent an unnecessary UI update
                .filter { it != advertisedRoutes }.collect { newRoutesFromBackend ->
                    Log.d(
                        TAG, "AdvertiseRoutes changed in the backend: $newRoutesFromBackend"
                    )
                    advertisedRoutes.set(newRoutesFromBackend)
                }
        }

        viewModelScope.launch {
            Notifier.prefs.map { it?.RouteAll }.distinctUntilChanged().collect {
                Log.d(TAG, "RouteAll changed in the backend: $it")
                routeAll.set(it)
            }
        }

        viewModelScope.launch {
            routeAll.collect {
                val prefsOut = Ipn.MaskedPrefs()
                prefsOut.RouteAll = it
                Log.d(TAG, "Will save RouteAll in the backend: $it")
                Client(viewModelScope).editPrefs(prefsOut, responseHandler = { result ->
                    if (result.isFailure) {
                        Log.e(TAG, "Error saving RouteAll: ${result.exceptionOrNull()}")
                        currentError.set(result.exceptionOrNull()?.localizedMessage)
                        return@editPrefs
                    } else {
                        Log.d(
                            TAG,
                            "RouteAll set in backend. New value: ${result.getOrNull()?.RouteAll}"
                        )
                        currentError.set(null)
                    }
                })
            }
        }
    }

    // Public functions

    fun toggleUseSubnets(onDone: () -> Unit) {
        routeAll.set(!routeAll.value)
        onDone()
    }

    /**
     * Deletes the given subnet route from the list of advertised routes.
     * Calling this function will cause the backend preferences to be updated in the background.
     *
     * @param route The route string to be deleted from the list of advertised routes.
     *              If the route does not exist in the list, no changes are made.
     */
    fun deleteRoute(route: String) {
        val currentRoutes = advertisedRoutes.value.toMutableList()
        if (!currentRoutes.contains(route)) {
            Log.e(TAG, "Attempted to delete route, but it does not exist: $route")
            return
        }
        currentRoutes.remove(route)
        advertisedRoutes.set(currentRoutes)
        saveRoutesToPrefs()
    }

    /**
     * Starts editing the given subnet route. Called when the user taps the 'pencil' button
     * on a route in the list.
     */
    fun startEditingRoute(route: String) {
        Log.d(TAG, "startEditingRoute: $route")
        editingRoute.set(route)
        dialogTextFieldValue.set(route)
        isPresentingDialog.set(true)
    }

    /**
     * Commits the changes made so far in the editing dialog.
     */
    fun doneEditingRoute(newValue: String) {
        Log.d(TAG, "doneEditingRoute: $newValue")
        editRoute(editingRoute.value, newValue)
        stopEditingRoute()
    }

    /**
     * Cancels any current editing session and closes the dialog.
     */
    fun stopEditingRoute() {
        Log.d(TAG, "stopEditingRoute")
        isPresentingDialog.set(false)
        dialogTextFieldValue.set("")
        editingRoute.set("")
    }

    /**
     * This makes the actual changes whenever adding or editing a route.
     * If adding a new route, oldRoute will be empty.
     * This function validates the input before making any changes. If newRoute
     * is not a valid CIDR IPv4/IPv6 range, this function does nothing.
     */
    private fun editRoute(oldRoute: String, newRoute: String) {
        val currentRoutes = advertisedRoutes.value.toMutableList()
        if (oldRoute == newRoute) {
            Log.v(TAG, "Attempted to call editRoute with the same route: $newRoute")
            return
        }
        if (currentRoutes.contains(newRoute)) {
            Log.e(TAG, "Attempted to call editRoute with a duplicate route: $newRoute")
            return
        }
        // Verify the newRoute is a valid IPv4 or IPv6 CIDR range.
        val isValid = isValidCIDR(newRoute)
        if (!isValid) {
            Log.e(TAG, "Attempted to call editRoute with an invalid route: $newRoute")
            return
        }
        val index = currentRoutes.indexOf(oldRoute)
        if (index == -1) {
            Log.v(TAG, "Adding new route: $newRoute")
            currentRoutes.add(newRoute)
        } else {
            Log.v(TAG, "Updating route at index $index: $newRoute")
            currentRoutes[index] = newRoute
        }
        advertisedRoutes.set(currentRoutes)
        saveRoutesToPrefs()
    }

    private fun saveRoutesToPrefs() {
        val prefsOut = Ipn.MaskedPrefs()
        prefsOut.AdvertiseRoutes = advertisedRoutes.value
        Log.d(TAG, "Will save AdvertiseRoutes in the backend: $(advertisedRoutes.value)")
        Client(viewModelScope).editPrefs(prefsOut, responseHandler = { result ->
            if (result.isFailure) {
                Log.e(TAG, "Error saving AdvertiseRoutes: ${result.exceptionOrNull()}")
                currentError.set(result.exceptionOrNull()?.localizedMessage)
                return@editPrefs
            } else {
                Log.d(
                    TAG,
                    "AdvertiseRoutes set in backend. New value: ${result.getOrNull()?.AdvertiseRoutes}"
                )
                currentError.set(null)
            }
        })
    }

    /**
     * Clears the current error message and reloads the routes currently saved in the backend
     * to the UI. We call this when dismissing an error upon saving the routes.
     */
    fun onErrorDismissed() {
        currentError.set(null)
        Client(viewModelScope).prefs { response ->
            Log.d(TAG, "Reloading routes from backend due to failed save: $response")
            this.advertisedRoutes.set(response.getOrNull()?.AdvertiseRoutes ?: emptyList())
        }
    }

    companion object RouteValidation {
        /**
         * Returns true if the given String is a valid IPv4 or IPv6 CIDR range, false otherwise.
         */
        fun isValidCIDR(newRoute: String): Boolean {
            val cidrPattern =
                Regex("(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])/(\\d+)") // IPv4 CIDR
            val ipv6CidrPattern =
                Regex("(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))/(\\d+)") // IPv6 CIDR
            return cidrPattern.matches(newRoute) || ipv6CidrPattern.matches(newRoute)
        }
    }
}

