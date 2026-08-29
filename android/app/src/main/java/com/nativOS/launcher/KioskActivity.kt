package com.nativOS.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nativOS.bridge.AndroidAppIntegration
import com.nativOS.bridge.BridgeService
import com.nativOS.runtime.ChrootManager
import com.nativOS.runtime.RootfsManager
import com.nativOS.settings.HomeRoleManager
import com.nativOS.settings.NativOSPreferences
import com.nativOS.x11.X11ServiceClient
import com.nativOS.x11.X11InputController
import com.nativOS.x11.X11ServerService
import com.termux.x11.MainActivity
import com.termux.x11.LorieView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-screen kiosk activity that replaces the Android home screen.
 *
 * Orchestrates the full NativOS boot sequence:
 *   1. Initialize X11 surface (LorieView)
 *   2. Download Linux rootfs (if first boot)
 *   3. Extract and configure rootfs
 *   4. Install Phosh desktop (if not installed)
 *   5. Start the Linux desktop session
 *
 * Escape hatch: press Volume Up 5 times rapidly to show Android.
 */
class KioskActivity : Activity() {

    companion object {
        private const val TAG = "NativOS.Kiosk"
        private const val ESCAPE_TAP_COUNT = 5
        private const val ESCAPE_WINDOW_MS = 3000L
    }

    private lateinit var chrootManager: ChrootManager
    private lateinit var rootfsManager: RootfsManager
    private var x11ServiceClient: X11ServiceClient? = null
    private var x11InputController: X11InputController? = null
    private val volumeUpTimestamps = mutableListOf<Long>()

    // Error flag — set by lambda callbacks to halt the boot sequence
    @Volatile private var bootFailed = false

    // UI elements
    private var rootLayout: FrameLayout? = null
    private var overlayLayout: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var detailText: TextView? = null
    private var keyboardButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRITICAL: Set TMPDIR before ANY Termux/X11 classes are loaded.
        // If libXlorie is loaded before this, it will cache an empty TMPDIR and fail to create sockets.
        try {
            val tmpDir = java.io.File(filesDir, "tmp")
            tmpDir.mkdirs()
            val x11Dir = java.io.File(tmpDir, ".X11-unix")
            x11Dir.mkdirs()
            
            val staleSocket = java.io.File(x11Dir, "X0")
            if (staleSocket.exists() && !staleSocket.delete()) {
                Log.w(TAG, "Could not remove stale X11 socket " + staleSocket.absolutePath)
            } else if (!staleSocket.exists() || !staleSocket.exists()) {
                Log.i(TAG, "No stale X11 socket found or successfully deleted")
            }
            
            // CRITICAL: make it fully accessible to native library
            tmpDir.setExecutable(true, false)
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            x11Dir.setExecutable(true, false)
            x11Dir.setReadable(true, false)
            x11Dir.setWritable(true, false)

            val osClass = Class.forName("android.system.Os")
            val setenvMethod = osClass.getMethod("setenv", String::class.java, String::class.java, Boolean::class.java)
            setenvMethod.invoke(null, "TMPDIR", tmpDir.absolutePath, true)
            setenvMethod.invoke(null, "XDG_RUNTIME_DIR", tmpDir.absolutePath, true)
            val prefixDir = java.io.File(filesDir, "usr")
            prefixDir.mkdirs()
            setenvMethod.invoke(null, "PREFIX", prefixDir.absolutePath, true)
            setenvMethod.invoke(null, "HOME", filesDir.absolutePath, true)
            
            // Create symlinks for X11 share and etc to point to rootfs natively
            val prefixShare = java.io.File(prefixDir, "share")
            prefixShare.mkdirs()
            val prefixX11 = java.io.File(prefixShare, "X11")
            if (!prefixX11.exists()) {
                val rootfsX11 = java.io.File(filesDir, "rootfs/usr/share/X11")
                try {
                    android.system.Os.symlink(rootfsX11.absolutePath, prefixX11.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to symlink X11 share: ${e.message}")
                }
            }
            
            val prefixEtc = java.io.File(prefixDir, "etc")
            if (!prefixEtc.exists()) {
                val rootfsEtc = java.io.File(filesDir, "rootfs/etc")
                try {
                    android.system.Os.symlink(rootfsEtc.absolutePath, prefixEtc.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to symlink etc: ${e.message}")
                }
            }

            // Create wrapper script for xkbcomp
            val binDir = java.io.File(prefixDir, "bin")
            binDir.mkdirs()
            val xkbcompWrapper = java.io.File(binDir, "xkbcomp")
            val filesPath = filesDir.absolutePath
            xkbcompWrapper.writeText("""
                #!/system/bin/sh
                log -t NativOS.Xkbcomp "Executing xkbcomp wrapper with args: ${'$'}@"
                ID=${'$'}RANDOM
                TMPDIR="$filesPath/tmp"
                ROOTFS="$filesPath/rootfs"
                SCRIPT="${'$'}TMPDIR/xkbcomp_${'$'}ID.sh"
                
                echo "#!/bin/sh" > ${'$'}SCRIPT
                echo -n "/usr/bin/xkbcomp " >> ${'$'}SCRIPT
                for arg in "${'$'}@"; do
                  escaped=`echo "${'$'}arg" | sed -e "s/'/'\\\\\\\\''/g"`
                  echo -n "'${'$'}escaped' " >> ${'$'}SCRIPT
                done
                echo "" >> ${'$'}SCRIPT
                chmod +x ${'$'}SCRIPT
                
                su -c "chroot ${'$'}ROOTFS ${'$'}SCRIPT"
                EXIT_CODE=${'$'}?
                log -t NativOS.Xkbcomp "xkbcomp wrapper exit code: ${'$'}EXIT_CODE"
                rm -f ${'$'}SCRIPT
                exit ${'$'}EXIT_CODE
            """.trimIndent())
            xkbcompWrapper.setExecutable(true, false)
            
            val xkbRoot = java.io.File(filesDir, "rootfs/usr/share/X11/xkb")
            setenvMethod.invoke(null, "XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
            Log.i(TAG, "Set XKB_CONFIG_ROOT unconditionally to " + xkbRoot.absolutePath)
            
            Log.i(TAG, "Early TMPDIR and PREFIX setup complete: ${tmpDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set early TMPDIR", e)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        rootLayout = FrameLayout(this)
        // rootLayout!!.setBackgroundColor(Color.BLACK) // Removed: Solid background covers SurfaceView!

        // Instead of embedding LorieView, we will launch the Termux:X11 companion app later.

        // Setup overlay
        overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            setPadding(60, 60, 60, 60)
        }
        val titleText = TextView(this).apply {
            text = "NativOS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Initializing..."
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 30)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(40, 0, 40, 20) }
        }
        detailText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        overlayLayout!!.addView(titleText)
        overlayLayout!!.addView(statusText)
        overlayLayout!!.addView(progressBar)
        overlayLayout!!.addView(detailText)
        rootLayout!!.addView(overlayLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val density = resources.displayMetrics.density
        keyboardButton = TextView(this).apply {
            text = "⌨"
            contentDescription = "Show Android keyboard"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.argb(180, 25, 25, 25))
            }
            elevation = 8f * density
            visibility = View.GONE
            setOnClickListener { MainActivity.toggleKeyboardVisibility(this@KioskActivity) }
        }
        makeDraggable(keyboardButton!!)
        rootLayout!!.addView(keyboardButton, FrameLayout.LayoutParams(
            (52 * density).toInt(),
            (48 * density).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (52 * density).toInt()
            rightMargin = (12 * density).toInt()
        })

        setContentView(rootLayout)
        rootLayout?.post { HomeRoleManager.promptOnce(this) }
        enterImmersiveMode()
        Log.i(TAG, "KioskActivity created")

        chrootManager = ChrootManager(this)
        rootfsManager = RootfsManager(this)

        startBridgeService()
        Thread(Runnable { runBootSequence() }, "nativOS-boot").start()
    }

    private fun makeDraggable(view: View) {
        val dragThreshold = 6f * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold) {
                        dragged = true
                    }
                    val parent = target.parent as? View ?: return@setOnTouchListener true
                    target.x = (startX + dx).coerceIn(0f, (parent.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (parent.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun runBootSequence() {
        try {
            // Step 1: Root check
            updateOverlay(0.0, "Checking root access...", "")
            if (!chrootManager.hasRoot()) {
                updateOverlay(-1.0, "ERROR: No root access", "NativOS requires a rooted device (Magisk/KernelSU)")
                return
            }
            updateOverlay(0.05, "Root access confirmed", "")

            // Step 2: Download + extract rootfs if needed
            if (!rootfsManager.isRootfsReady()) {
                // Download
                updateOverlay(0.05, "Downloading Linux filesystem...", "First boot — this takes a few minutes")
                bootFailed = false
                rootfsManager.downloadRootfs { progress, status ->
                    if (progress < 0) {
                        bootFailed = true
                        updateOverlay(-1.0, "Download failed", status)
                    } else {
                        updateOverlay(0.05 + progress * 0.45, status, "")
                    }
                }
                if (bootFailed) return

                // Extract
                updateOverlay(0.50, "Extracting Linux filesystem...", "This may take a few minutes")
                bootFailed = false
                rootfsManager.extractRootfs { progress, status ->
                    if (progress < 0) {
                        bootFailed = true
                        updateOverlay(-1.0, "Extraction failed", status)
                    } else {
                        updateOverlay(0.50 + progress * 0.20, status, "")
                    }
                }
                if (bootFailed) return
            } else {
                updateOverlay(0.70, "Linux filesystem found", "")
            }

            if (!rootfsManager.isRootfsReady()) {
                updateOverlay(-1.0, "ERROR: Rootfs setup failed", "The Linux filesystem could not be prepared")
                return
            }

            // Step 3: Mount chroot
            updateOverlay(0.72, "Mounting Linux environment...", "")
            chrootManager.ensureMounts()
            chrootManager.bindX11Socket()
            AndroidAppIntegration.sync(this)

            // Step 4: Install essential packages (dbus is required for any session)
            updateOverlay(0.74, "Checking essential packages...", "")
            if (!chrootManager.isPhoshInstalled()) {
                updateOverlay(0.75, "Installing Phosh desktop...", "First boot — installing packages (5-15 min)")
                bootFailed = false
                rootfsManager.installPhosh(chrootManager) { progress, status ->
                    if (progress < 0) {
                        bootFailed = true
                        updateOverlay(-1.0, "Phosh installation failed", status)
                    } else {
                        updateOverlay(0.75 + progress * 0.20, status, "")
                    }
                }
                if (bootFailed) {
                    // Even if Phosh failed, leave the rootfs package database usable.
                    updateOverlay(0.90, "Installing minimal session...", "Phosh failed, trying fallback")
                    try {
                        chrootManager.execChroot(
                            "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends dbus dbus-x11")
                    } catch (_: Exception) {}
                }
            } else {
                updateOverlay(0.95, "Phosh desktop found", "")
            }

            // Migrate existing installs away from the tiny unmanaged XTerm/UXTerm
            // windows. GNOME Console is adaptive and is maximized by Phosh.
            updateOverlay(0.95, "Checking terminal...", "GNOME Console")
            rootfsManager.ensureProfessionalTerminal(chrootManager)

            // Migrate existing installations that have Flatpak but were created
            // before Flathub setup became independent from Firefox provisioning.
            if (rootfsManager.isFlatpakInstalled()) {
                updateOverlay(0.95, "Checking app catalog...", "Flathub")
                rootfsManager.ensureFlathub(chrootManager)
            }

            // Step 5: Attach the display surface to the bundled X11 service process.
            updateOverlay(0.95, "Starting Display Server...", "Initializing X11")
            
            // Android Views and the Termux Activity shim must be created on the UI thread.
            lateinit var lorieView: LorieView
            var viewCreationError: Throwable? = null
            val viewCreated = CountDownLatch(1)
            val surfaceReady = CountDownLatch(1)
            val surfaceReadyCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady.countDown()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    surfaceReady.countDown()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            }

            runOnUiThread {
                try {
                    val x11Activity = MainActivity.getInstance()
                    x11Activity.initLorieView(this@KioskActivity)
                    lorieView = x11Activity.lorieView
                    lorieView.holder.addCallback(surfaceReadyCallback)
                    (lorieView.parent as? ViewGroup)?.removeView(lorieView)
                    lorieView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    lorieView.setZOrderOnTop(false)
                    rootLayout?.addView(lorieView, 0)
                } catch (error: Throwable) {
                    viewCreationError = error
                } finally {
                    viewCreated.countDown()
                }
            }

            if (!viewCreated.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out creating the X11 display surface")
            }
            viewCreationError?.let { throw it }

            if (!surfaceReady.await(10, TimeUnit.SECONDS)) {
                lorieView.holder.removeCallback(surfaceReadyCallback)
                throw IllegalStateException("Timed out waiting for the X11 display surface")
            }
            lorieView.holder.removeCallback(surfaceReadyCallback)
            
            val rendererAttached = CountDownLatch(1)
            var rendererError: Throwable? = null
            x11ServiceClient = X11ServiceClient(
                context = this,
                onConnected = { connectionFd, logcatFd ->
                    try {
                        lorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { lorieView.startLogcat(it.detachFd()) }
                        x11InputController = X11InputController(lorieView)
                        lorieView.requestFocus()
                        Log.i(TAG, "LorieView attached to the bundled X11 service")
                        rendererAttached.countDown()
                    } catch (error: Throwable) {
                        rendererError = error
                        connectionFd.close()
                        logcatFd?.close()
                        rendererAttached.countDown()
                    }
                },
                onError = { message, error ->
                    rendererError = IllegalStateException(message, error)
                    rendererAttached.countDown()
                }
            ).also { it.connect() }

            if (!rendererAttached.await(10, TimeUnit.SECONDS) || !lorieView.connected()) {
                throw rendererError
                    ?: IllegalStateException("LorieView failed to attach to the X11 server")
            }

            Log.i(TAG, "Starting test session...")

            // Match Phoc's nested output to the actual X11 surface dimensions.
            val screenWidth = lorieView.width
            val screenHeight = lorieView.height
            Log.i(TAG, "Detected screen: ${screenWidth}x${screenHeight}")

            chrootManager.startPhoshSession(screenWidth, screenHeight)
            updateOverlay(0.98, "Starting Linux desktop...", "Waiting for Phosh")
            if (!chrootManager.awaitDesktopReady()) {
                throw IllegalStateException("Phosh did not become ready")
            }
            runOnUiThread {
                overlayLayout?.visibility = View.GONE
                keyboardButton?.visibility = View.VISIBLE
                keyboardButton?.bringToFront()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Boot sequence failed", e)
            updateOverlay(-1.0, "Boot failed: ${e.message}", "Press Vol Up x5 to escape to Android")
        }
    }

    private fun updateOverlay(progress: Double, status: String, detail: String) {
        Log.i(TAG, "BOOT: [${"%.0f".format(progress * 100)}%] $status ${if (detail.isNotEmpty()) "— $detail" else ""}")
        runOnUiThread {
            if (progress < 0) {
                statusText?.text = status
                statusText?.setTextColor(Color.parseColor("#FF5555"))
                detailText?.text = detail
                progressBar?.progress = 0
            } else {
                statusText?.text = status
                statusText?.setTextColor(Color.parseColor("#AAAAAA"))
                detailText?.text = detail
                progressBar?.progress = (progress * 1000).toInt()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        AndroidAppIntegration.sync(this)

        // A recreated Android Surface may use a multi-buffer queue. Repaint a
        // few frames so every buffer contains the desktop instead of stale
        // black/transparent data from before the Android app switch.
        listOf(50L, 150L, 300L).forEach { delay ->
            rootLayout?.postDelayed({
                MainActivity.getInstance().lorieView?.triggerCallback()
            }, delay)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = SystemClock.elapsedRealtime()
            volumeUpTimestamps.add(now)
            volumeUpTimestamps.removeAll { now - it > ESCAPE_WINDOW_MS }
            if (volumeUpTimestamps.size >= ESCAPE_TAP_COUNT) {
                volumeUpTimestamps.clear()
                escapeToAndroid()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun enterImmersiveMode() {
        if (!NativOSPreferences.hideSystemBars(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun startBridgeService() {
        Log.i(TAG, "Starting bridge service...")
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
    }

    private fun escapeToAndroid() {
        Log.i(TAG, "ESCAPE: Returning to Android temporarily")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        x11InputController = null
        stopService(Intent(this, X11ServerService::class.java))
        if (::chrootManager.isInitialized) {
            Thread({ chrootManager.stopSession() }, "nativOS-stop").start()
        }
        super.onDestroy()
        Log.i(TAG, "KioskActivity destroyed; stopping display session")
    }
}
