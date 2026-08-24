package com.nativOS.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nativOS.bridge.BridgeService
import com.nativOS.runtime.ChrootManager
import com.nativOS.runtime.RootfsManager

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
    private val volumeUpTimestamps = mutableListOf<Long>()

    // Error flag — set by lambda callbacks to halt the boot sequence
    @Volatile private var bootFailed = false

    // UI elements
    private var rootLayout: FrameLayout? = null
    private var overlayLayout: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var detailText: TextView? = null

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

        setContentView(rootLayout)
        enterImmersiveMode()
        Log.i(TAG, "KioskActivity created")

        chrootManager = ChrootManager(this)
        rootfsManager = RootfsManager(this)

        startBridgeService()
        Thread(Runnable { runBootSequence() }, "nativOS-boot").start()
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
                    // Even if Phosh failed, try to install dbus so we can at least start xterm
                    updateOverlay(0.90, "Installing minimal session...", "Phosh failed, trying fallback")
                    try {
                        chrootManager.execChroot(
                            "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends dbus dbus-x11 xterm")
                    } catch (_: Exception) {}
                }
            } else {
                updateOverlay(0.95, "Phosh desktop found", "")
            }

            // Step 5: Start the Termux:X11 display server
            updateOverlay(0.95, "Starting Display Server...", "Launching Termux:X11")
            
            if (!isTermuxX11Installed()) {
                runOnUiThread {
                    updateOverlay(-1.0, "Display Server Missing", "Termux:X11 is required to render the Linux desktop")
                    
                    val btn = android.widget.Button(this@KioskActivity).apply {
                        text = "Download Termux:X11 from GitHub"
                        setOnClickListener {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/termux/termux-x11/releases/tag/nightly"))
                            startActivity(intent)
                        }
                    }
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 50, 0, 0)
                    }
                    overlayLayout?.addView(btn, params)
                }
                return // Halt the boot sequence
            }
            
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.termux.x11")).waitFor()
            launchTermuxX11()
            startTermuxX11Server()
            Thread.sleep(3000) // Give X server time to stabilize and create socket

            runOnUiThread { overlayLayout?.visibility = View.GONE }

            Log.i(TAG, "Starting test session...")

            // Detect the device's real screen resolution for universal device support
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val screenWidth: Int
            val screenHeight: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
            }
            Log.i(TAG, "Detected screen: ${screenWidth}x${screenHeight}")

            chrootManager.startPhoshSession(screenWidth, screenHeight)

        } catch (e: Exception) {
            Log.e(TAG, "Boot sequence failed", e)
            updateOverlay(-1.0, "Boot failed: ${e.message}", "Press Vol Up x5 to escape to Android")
        }
    }

    private fun isTermuxX11Installed(): Boolean {
        return try {
            packageManager.getPackageInfo("com.termux.x11", 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun installTermuxX11() {
        try {
            val apkUrl = "https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-debug.apk"
            val tmpApk = "/data/local/tmp/termux-x11.apk"
            Log.i(TAG, "Downloading Termux:X11 APK...")
            val dlCmd = "su -c 'curl -L -o $tmpApk $apkUrl && pm install -r -d -g $tmpApk && rm $tmpApk'"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", dlCmd))
            process.waitFor()
            if (process.exitValue() == 0) {
                Log.i(TAG, "Successfully installed Termux:X11")
            } else {
                Log.e(TAG, "Failed to install Termux:X11 (exit code ${process.exitValue()})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception installing Termux:X11", e)
        }
    }

    private fun launchTermuxX11() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.termux.x11")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                Log.i(TAG, "Launched Termux:X11 app")
            } else {
                Log.e(TAG, "Could not get launch intent for Termux:X11")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Termux:X11", e)
        }
    }

    private fun startTermuxX11Server() {
        try {
            val loaderFile = java.io.File(filesDir, "loader.apk")
            if (!loaderFile.exists()) {
                assets.open("loader.apk").use { input ->
                    loaderFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val rootfsXkb = java.io.File(filesDir, "rootfs/usr/share/X11/xkb")
            
            val cmd = StringBuilder()
            cmd.append("su -c '")
            cmd.append("killall -9 app_process; killall -9 phoc; killall -9 phosh; killall -9 dbus-run-session; rm -rf /data/local/tmp/xkb && cp -r ${rootfsXkb.absolutePath} /data/local/tmp/xkb && chmod -R 755 /data/local/tmp/xkb && ")
            cmd.append("export TMPDIR=/data/local/tmp && ")
            cmd.append("export XKB_CONFIG_ROOT=/data/local/tmp/xkb && ")
            cmd.append("/system/bin/app_process -cp ${loaderFile.absolutePath} / com.termux.x11.Loader :0 -legacy-drawing")
            cmd.append("'")
            
            Thread {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd.toString()))
                    process.waitFor()
                } catch (e: Exception) {
                    Log.e(TAG, "Termux:X11 server crashed", e)
                }
            }.start()
            
            Log.i(TAG, "Termux:X11 server started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Termux:X11 server", e)
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
        super.onDestroy()
        Log.i(TAG, "KioskActivity destroyed (session continues in background)")
    }
}
