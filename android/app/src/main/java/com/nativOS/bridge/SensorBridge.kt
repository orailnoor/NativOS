package com.nativOS.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sensor bridge — streams accelerometer, gyroscope, proximity, light,
 * and magnetometer data from Android's SensorManager to Linux.
 *
 * Actions: list_sensors, subscribe, unsubscribe
 * Events: accelerometer, gyroscope, proximity, light, magnetometer
 */
class SensorBridge(private val service: BridgeService) : SensorEventListener {

    companion object {
        private const val TAG = "NativOS.Sensor"
    }

    private val sensorManager = service.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activeSensors = mutableSetOf<Int>()

    private val sensorTypeMap = mapOf(
        "accelerometer" to Sensor.TYPE_ACCELEROMETER,
        "gyroscope" to Sensor.TYPE_GYROSCOPE,
        "proximity" to Sensor.TYPE_PROXIMITY,
        "light" to Sensor.TYPE_LIGHT,
        "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
        "gravity" to Sensor.TYPE_GRAVITY,
        "rotation" to Sensor.TYPE_ROTATION_VECTOR,
        "pressure" to Sensor.TYPE_PRESSURE,
    )

    private val eventNameMap = mapOf(
        Sensor.TYPE_ACCELEROMETER to BridgeProtocol.EVENT_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE to BridgeProtocol.EVENT_GYROSCOPE,
        Sensor.TYPE_PROXIMITY to BridgeProtocol.EVENT_PROXIMITY,
        Sensor.TYPE_LIGHT to BridgeProtocol.EVENT_LIGHT,
        Sensor.TYPE_MAGNETIC_FIELD to BridgeProtocol.EVENT_MAGNETOMETER,
        Sensor.TYPE_GRAVITY to "gravity",
        Sensor.TYPE_ROTATION_VECTOR to "rotation",
        Sensor.TYPE_PRESSURE to "pressure",
    )

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_LIST_SENSORS -> handleListSensors(request)
            BridgeProtocol.ACTION_SUBSCRIBE -> handleSubscribe(request)
            BridgeProtocol.ACTION_UNSUBSCRIBE -> handleUnsubscribe(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown sensor action: ${request.action}")
            )
        }
    }

    private fun handleListSensors(request: BridgeRequest): String {
        val sensors = JSONArray()
        for ((name, type) in sensorTypeMap) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                sensors.put(JSONObject().apply {
                    put("name", name)
                    put("type", type)
                    put("vendor", sensor.vendor)
                    put("resolution", sensor.resolution.toDouble())
                    put("max_range", sensor.maximumRange.toDouble())
                    put("available", true)
                })
            }
        }
        return BridgeProtocol.response(
            request.id, BridgeProtocol.STATUS_OK,
            JSONObject().put("sensors", sensors)
        )
    }

    private fun handleSubscribe(request: BridgeRequest): String {
        val sensorName = request.params.optString("sensor", "")
        val rate = request.params.optInt("rate_us", SensorManager.SENSOR_DELAY_UI)

        val sensorType = sensorTypeMap[sensorName]
            ?: return BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown sensor: $sensorName")
            )

        val sensor = sensorManager.getDefaultSensor(sensorType)
            ?: return BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Sensor not available: $sensorName")
            )

        sensorManager.registerListener(this, sensor, rate)
        activeSensors.add(sensorType)
        Log.i(TAG, "Subscribed to sensor: $sensorName")

        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    private fun handleUnsubscribe(request: BridgeRequest): String {
        val sensorName = request.params.optString("sensor", "")

        if (sensorName == "all") {
            sensorManager.unregisterListener(this)
            activeSensors.clear()
            Log.i(TAG, "Unsubscribed from all sensors")
        } else {
            val sensorType = sensorTypeMap[sensorName]
                ?: return BridgeProtocol.response(
                    request.id, BridgeProtocol.STATUS_ERROR,
                    JSONObject().put("message", "Unknown sensor: $sensorName")
                )

            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor != null) {
                sensorManager.unregisterListener(this, sensor)
                activeSensors.remove(sensorType)
                Log.i(TAG, "Unsubscribed from sensor: $sensorName")
            }
        }

        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val eventName = eventNameMap[event.sensor.type] ?: return

        val data = JSONObject().apply {
            put("timestamp", event.timestamp)
            put("accuracy", event.accuracy)
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_GRAVITY -> {
                    put("x", event.values[0].toDouble())
                    put("y", event.values[1].toDouble())
                    put("z", event.values[2].toDouble())
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    put("x", event.values[0].toDouble())
                    put("y", event.values[1].toDouble())
                    put("z", event.values[2].toDouble())
                    if (event.values.size > 3) put("w", event.values[3].toDouble())
                }
                Sensor.TYPE_PROXIMITY -> {
                    put("distance", event.values[0].toDouble())
                    put("near", event.values[0] < event.sensor.maximumRange)
                }
                Sensor.TYPE_LIGHT -> {
                    put("lux", event.values[0].toDouble())
                }
                Sensor.TYPE_PRESSURE -> {
                    put("hpa", event.values[0].toDouble())
                }
            }
        }

        service.broadcastEvent(BridgeProtocol.TYPE_SENSOR, eventName, data)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun cleanup() {
        sensorManager.unregisterListener(this)
        activeSensors.clear()
    }
}
