package com.nativOS.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the chroot Ubuntu filesystem: mounting, session lifecycle,
 * and command execution inside the chroot.
 *
 * Adapted from DroidDesk's ChrootRuntime for NativOS —
 * focused on Phosh/Wayland instead of XFCE.
 */
class ChrootManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.ChrootManager"

        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")
    private val bridgeSocketDir: File get() = File(baseDir, "bridge")

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    fun isRootfsReady(): Boolean = File(rootfsDir, "usr/bin/bash").exists()

    fun isPhoshInstalled(): Boolean =
        File(rootfsDir, "usr/bin/phosh-session").exists() ||
        File(rootfsDir, "usr/bin/phoc").exists()

    fun isRunning(): Boolean = sessionProcess?.isAlive == true

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getBridgeSocketPath(): String = File(bridgeSocketDir, "bridge.sock").absolutePath

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     * Also bind-mounts the bridge socket directory into the chroot.
     */
    fun ensureMounts() {
        if (!hasRoot()) return

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        // Core filesystem mounts
        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs") { isMounted("dev/shm") }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Fix missing symlinks in Android's /dev
        val devPath = File(rootfsDir, "dev").absolutePath
        rootShell.exec("ln -snf /proc/self/fd $devPath/fd")
        rootShell.exec("ln -snf /proc/self/fd/0 $devPath/stdin")
        rootShell.exec("ln -snf /proc/self/fd/1 $devPath/stdout")
        rootShell.exec("ln -snf /proc/self/fd/2 $devPath/stderr")

        // Grant app access to GPU for Termux:X11 DRI3
        rootShell.exec("chmod 666 /dev/dri/* 2>/dev/null")

        // Create runtime directories inside chroot
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /run/nativOS /root")

        // Bind-mount bridge socket directory so Linux side can connect
        bridgeSocketDir.mkdirs()
        val chrootBridgeDir = File(rootfsDir, "run/nativOS").absolutePath
        if (!mounts.any { it.contains(" on $chrootBridgeDir ") }) {
            rootShell.exec("mkdir -p $chrootBridgeDir && mount --bind ${bridgeSocketDir.absolutePath} $chrootBridgeDir")
            Log.i(TAG, "Bound bridge socket dir into chroot")
        }

        // Bind-mount host files directory into chroot so absolute paths match
        val hostFilesDir = context.filesDir.absolutePath
        val chrootFilesDir = File(rootfsDir, hostFilesDir).absolutePath
        if (!mounts.any { it.contains(" on $chrootFilesDir ") }) {
            rootShell.exec("mkdir -p $chrootFilesDir && mount --bind $hostFilesDir $chrootFilesDir")
            Log.i(TAG, "Bound host files dir into chroot for path compatibility")
        }

        // Refresh DNS
        try {
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not overwrite resolv.conf from Kotlin, it might be root-owned. Proceeding.")
        }

        Log.i(TAG, "All mounts ready")
    }

    /** Bind-mount the host X11 socket directory into the chroot. */
    fun bindX11Socket() {
        if (!hasRoot()) return
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath

        val mounts = rootShell.exec("mount").lines()
        if (mounts.any { it.contains(" on $chrootX11 ") }) {
            rootShell.exec("umount $chrootX11")
        }
    }

    // ── Session management ──

    /**
     * Start the Phosh desktop session inside the chroot.
     * This launches phoc (Wayland compositor) which hosts Phosh.
     *
     * @param screenWidth  The device's real screen width in pixels
     * @param screenHeight The device's real screen height in pixels
     */
    fun startPhoshSession(screenWidth: Int = 1080, screenHeight: Int = 2160) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        ensureMounts()
        bindX11Socket()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val runScript = """
            # Standard FHS PATH
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=/tmp
            export HOME=/root
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            export XDG_SESSION_TYPE=x11
            export XDG_DATA_DIRS=/usr/share:/usr/local/share
            export XDG_CONFIG_DIRS=/etc/xdg
            export DISPLAY=:0
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export WLR_NO_HARDWARE_CURSORS=1
            export LIBGL_ALWAYS_SOFTWARE=1

            # Ensure runtime dir exists with correct permissions
            mkdir -p /tmp/runtime-root
            chown root:root /tmp/runtime-root
            chmod 0700 /tmp/runtime-root

            # Set root password to 1234 so lockscreen can be unlocked
            echo 'root:1234' | chpasswd

            # We compiled a glibc-compatible libsocket_hook.so directly in the chroot
            # at /usr/local/lib/libsocket_hook.so
            # This library intercepts connect() and translates filesystem socket paths
            # to abstract sockets, preserving SCM_RIGHTS fd-passing (critical for MIT-SHM)

            # Start the NativOS bridge client daemon
            if [ -f /usr/local/bin/nativOS-bridge ]; then
                /usr/local/bin/nativOS-bridge &
                echo "NativOS: Bridge client started"
            fi
            
            # Clean up any lingering Wayland sockets and loops
            if [ -f /tmp/phosh_loop.pid ]; then kill -9 $(cat /tmp/phosh_loop.pid) 2>/dev/null || true; rm /tmp/phosh_loop.pid; fi
            rm -rf /tmp/runtime-root/wayland-* 2>/dev/null || true

            # Generate phoc.ini with device-specific resolution
            mkdir -p /etc/nativOS
            cat > /etc/nativOS/phoc.ini << PHOCEOF
[core]
xwayland=false

[output:X11-1]
mode=${screenWidth}x${screenHeight}
scale=${if (screenWidth >= 1440) 3 else if (screenWidth >= 1080) 2 else 1}
PHOCEOF
            
            echo "NativOS: Display configured: ${screenWidth}x${screenHeight} @ scale ${if (screenWidth >= 1440) 3 else if (screenWidth >= 1080) 2 else 1}"
            echo "NativOS: Starting Wayland session..."
            if command -v phoc >/dev/null 2>&1; then
                echo "NativOS: Launching phoc (X11 backend)..."
                
                # Configure wlroots X11 backend
                export WLR_BACKENDS=x11
                export WLR_X11_OUTPUTS=1
                export WLR_RENDERER=pixman
                export DISPLAY=:0
                
                # Disable DRI3/GPU features that crash inside chroot
                export WLR_DRM_NO_ATOMIC=1
                export GBM_ALWAYS_SOFTWARE=1
                export MESA_LOADER_DRIVER_OVERRIDE=swrast
                export WLR_DRM_DEVICES=""
                
                # CRITICAL: Set TMPDIR to match the Android app's TMPDIR
                # libsocket_hook.so uses TMPDIR to construct the abstract socket path
                # Termux:X11 creates abstract socket at: @<TMPDIR>/.X11-unix/X0
                export TMPDIR=/tmp
                
                # LD_PRELOAD: Only load libraries that actually exist on this device
                # libsocket_hook.so translates filesystem connect() to abstract socket
                # libnodri3.so blocks DRI3 extension (not available in chroot)
                # libandroid-shmem.so provides shared memory on Android kernels
                PRELOAD=""
                for lib in /usr/local/lib/libsocket_hook.so /usr/local/lib/libnodri3.so /usr/local/lib/libandroid-shmem.so; do
                    if [ -f "${'$'}lib" ]; then
                        if [ -z "${'$'}PRELOAD" ]; then
                            PRELOAD="${'$'}lib"
                        else
                            PRELOAD="${'$'}PRELOAD:${'$'}lib"
                        fi
                    fi
                done
                if [ -n "${'$'}PRELOAD" ]; then
                    export LD_PRELOAD=${'$'}PRELOAD
                    echo "NativOS: LD_PRELOAD=${'$'}LD_PRELOAD"
                else
                    echo "NativOS: No LD_PRELOAD libraries found (fresh install)"
                fi
                
                echo "NativOS: TMPDIR=${'$'}TMPDIR"
                echo "NativOS: DISPLAY=${'$'}DISPLAY"
                
                cat > /tmp/start_phosh.sh << 'PHOSHEOF'
#!/bin/bash
echo $$ > /tmp/phosh_loop.pid
while true; do
    dbus-run-session -- phoc -C /etc/nativOS/phoc.ini -E "bash -c 'exec /usr/libexec/phosh -U'"
    echo "NativOS: Phoc exited, restarting in 2 seconds..."
    sleep 2
done
PHOSHEOF
                chmod +x /tmp/start_phosh.sh
                exec /tmp/start_phosh.sh
            elif command -v xterm >/dev/null 2>&1; then
                echo "NativOS: Fallback — launching xterm"
                exec xterm
            else
                echo "NativOS: ERROR — no compositor or terminal found"
                sleep 999
            fi
        """.trimIndent()

        Log.i(TAG, "Starting Phosh session")

        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /usr/bin/env -i /bin/bash -c ${shellQuote(runScript)}"
        val startedSession = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()
        sessionProcess = startedSession

        // Log output from the session
        Thread {
            try {
                val reader = startedSession.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "SESSION: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                Log.d(TAG, "Session output stream closed")
            }
        }.start()
    }

    /** Stop the chroot session and unmount bind mounts. */
    fun stopSession() {
        Log.i(TAG, "Stopping session...")
        sessionProcess?.let {
            try {
                it.destroyForcibly()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session: ${e.message}")
            }
        }
        sessionProcess = null
        unmountAll()
        Log.i(TAG, "Session stopped")
    }

    /** Unmount all NativOS-related mounts. */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        val targets = listOf(
            File(rootfsDir, "run/nativOS").absolutePath,
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath
        )
        targets.reversed().forEach { target ->
            if (mounts.any { it.contains(" on $target ") }) {
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
        }
    }

    // ── Command execution ──

    /** Execute a command inside the chroot as root. */
    fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            Log.d(TAG, "chroot: ${chunk.trimEnd()}")
            onLog(chunk)
        }
        return output
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    private fun shellQuote(input: String): String {
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }
}
