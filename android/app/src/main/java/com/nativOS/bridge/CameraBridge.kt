package com.nativOS.bridge

import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera bridge — streams camera frames from Android's Camera2 API to Linux.
 *
 * Actions: list_cameras, start_preview, stop_preview, capture_photo, switch_camera
 * Frame data is streamed over a separate Unix socket for performance.
 */
class CameraBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Camera"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    private val cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var currentCameraId: String? = null

    private val cameraThread = HandlerThread("NativOS-Camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Frame callback — override this to pipe frames to the Linux socket
    var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_LIST_CAMERAS -> handleListCameras(request)
            BridgeProtocol.ACTION_START_PREVIEW -> handleStartPreview(request)
            BridgeProtocol.ACTION_STOP_PREVIEW -> handleStopPreview(request)
            BridgeProtocol.ACTION_CAPTURE_PHOTO -> handleCapturePhoto(request)
            BridgeProtocol.ACTION_SWITCH_CAMERA -> handleSwitchCamera(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown camera action: ${request.action}")
            )
        }
    }

    private fun handleListCameras(request: BridgeRequest): String {
        return try {
            val cameras = JSONArray()
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }

                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                val resolutions = JSONArray()
                sizes?.take(5)?.forEach { size ->
                    resolutions.put("${size.width}x${size.height}")
                }

                cameras.put(JSONObject().apply {
                    put("id", id)
                    put("facing", facingName)
                    put("resolutions", resolutions)
                })
            }

            BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("cameras", cameras)
            )
        } catch (e: Exception) {
            BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to list cameras: ${e.message}")
            )
        }
    }

    @Suppress("MissingPermission")
    private fun handleStartPreview(request: BridgeRequest): String {
        val cameraId = request.params.optString("camera_id", "0")
        val width = request.params.optInt("width", DEFAULT_WIDTH)
        val height = request.params.optInt("height", DEFAULT_HEIGHT)

        return try {
            stopCameraInternal()

            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        onFrameAvailable?.invoke(bytes, width, height)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    currentCameraId = cameraId
                    startPreviewSession(camera, width, height)
                    Log.i(TAG, "Camera $cameraId opened")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    Log.w(TAG, "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                }
            }, cameraHandler)

            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("camera_id", cameraId).put("width", width).put("height", height))
        } catch (e: SecurityException) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "CAMERA permission not granted"))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to start preview: ${e.message}"))
        }
    }

    private fun startPreviewSession(camera: CameraDevice, width: Int, height: Int) {
        try {
            val reader = imageReader ?: return
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
            }

            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(previewRequest.build(), null, cameraHandler)
                        Log.i(TAG, "Preview session active (${width}x${height})")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preview session: ${e.message}")
        }
    }

    private fun handleStopPreview(request: BridgeRequest): String {
        stopCameraInternal()
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    private fun handleCapturePhoto(request: BridgeRequest): String {
        // For now, capture is done through the preview stream
        // Full capture with max resolution requires a separate capture request
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
            JSONObject().put("message", "Full-resolution capture not yet implemented — use preview frames"))
    }

    private fun handleSwitchCamera(request: BridgeRequest): String {
        val cameraId = request.params.optString("camera_id", "")
        if (cameraId.isEmpty()) {
            // Toggle between front and back
            val newId = if (currentCameraId == "0") "1" else "0"
            request.params.put("camera_id", newId)
        }
        stopCameraInternal()
        return handleStartPreview(request)
    }

    private fun stopCameraInternal() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    fun cleanup() {
        stopCameraInternal()
        cameraThread.quitSafely()
    }
}
