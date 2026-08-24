package com.nativOS.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * Location bridge — provides GPS coordinates from Android's LocationManager to Linux.
 *
 * Actions: start_updates, stop_updates, get_last_known
 * Events: location_update
 */
class LocationBridge(private val service: BridgeService) : LocationListener {

    companion object {
        private const val TAG = "NativOS.Location"
        private const val DEFAULT_MIN_TIME_MS = 1000L
        private const val DEFAULT_MIN_DISTANCE_M = 1f
    }

    private val locationManager =
        service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isListening = false

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_START_LOCATION -> handleStartUpdates(request)
            BridgeProtocol.ACTION_STOP_LOCATION -> handleStopUpdates(request)
            BridgeProtocol.ACTION_GET_LAST_KNOWN -> handleGetLastKnown(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown location action: ${request.action}")
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleStartUpdates(request: BridgeRequest): String {
        val minTimeMs = request.params.optLong("min_time_ms", DEFAULT_MIN_TIME_MS)
        val minDistanceM = request.params.optDouble("min_distance_m", DEFAULT_MIN_DISTANCE_M.toDouble()).toFloat()

        return try {
            if (isListening) {
                locationManager.removeUpdates(this)
            }

            // Try GPS first, then network provider
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> LocationManager.PASSIVE_PROVIDER
            }

            locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, this)
            isListening = true
            Log.i(TAG, "Location updates started (provider=$provider)")

            BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("provider", provider)
            )
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Location permission not granted")
            )
        }
    }

    private fun handleStopUpdates(request: BridgeRequest): String {
        locationManager.removeUpdates(this)
        isListening = false
        Log.i(TAG, "Location updates stopped")
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    @SuppressLint("MissingPermission")
    private fun handleGetLastKnown(request: BridgeRequest): String {
        return try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                BridgeProtocol.response(
                    request.id, BridgeProtocol.STATUS_OK,
                    locationToJson(location)
                )
            } else {
                BridgeProtocol.response(
                    request.id, BridgeProtocol.STATUS_ERROR,
                    JSONObject().put("message", "No last known location available")
                )
            }
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Location permission not granted")
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        service.broadcastEvent(
            BridgeProtocol.TYPE_LOCATION,
            BridgeProtocol.EVENT_LOCATION_UPDATE,
            locationToJson(location)
        )
    }

    @Deprecated("Deprecated in API level 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun locationToJson(location: Location): JSONObject {
        return JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", location.altitude)
            put("accuracy", location.accuracy.toDouble())
            put("speed", location.speed.toDouble())
            put("bearing", location.bearing.toDouble())
            put("timestamp", location.time)
            put("provider", location.provider ?: "unknown")
        }
    }

    fun cleanup() {
        locationManager.removeUpdates(this)
        isListening = false
    }
}
