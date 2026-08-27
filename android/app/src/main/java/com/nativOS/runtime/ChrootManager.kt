package com.nativOS.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

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
        private const val TURNIP_VERSION = "25.0.7"
        private const val TURNIP_ASSET = "gpu/mesa-vulkan-drivers-kgsl-25.0.7-arm64.deb"
        private const val TURNIP_SHA256 = "66b11a94835f66e80efc8556477334a14dc68456a76e31ada3cd2d440869c5d5"

        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val shmDir: File get() = File(baseDir, "shm")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")
    private val bridgeSocketDir: File get() = File(baseDir, "bridge")
    private val turnipRoot: File get() = File(rootfsDir, "opt/nativos-gpu/turnip-$TURNIP_VERSION")
    private val turnipIcd: File get() = File(turnipRoot, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")

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

        // Services such as polkit drop root privileges and must still be able to
        // enter the chroot root. Android's private parent directory remains 0700.
        rootShell.exec("chmod 755 ${rootfsDir.absolutePath}")

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        // Core filesystem mounts
        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        // wlroots sends its X11 framebuffer to the embedded server through
        // MIT-SHM. An anonymous tmpfs gets the generic tmpfs SELinux label,
        // which Android denies to our isolated :x11 app process. Keep /dev/shm
        // on app-owned storage so both the rooted compositor and X11 can use it.
        shmDir.mkdirs()
        val chrootShmDir = File(rootfsDir, "dev/shm").absolutePath
        val shmMount = mounts.firstOrNull { it.contains(" on $chrootShmDir ") }
        if (shmMount == null || !shmMount.startsWith("${shmDir.absolutePath} ")) {
            if (shmMount != null) rootShell.exec("umount $chrootShmDir")
            rootShell.exec("mkdir -p $chrootShmDir && mount --bind ${shmDir.absolutePath} $chrootShmDir")
            Log.i(TAG, "Bound app-owned shared memory directory into chroot")
        }
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

    /** Install and validate the bundled KGSL Turnip driver on Adreno devices. */
    private fun prepareHardwareGpu(): Boolean {
        if (!File("/dev/kgsl-3d0").exists()) {
            Log.i(TAG, "No KGSL device; using portable software rendering")
            return false
        }

        return try {
            val archive = File(baseDir, "gpu/mesa-vulkan-drivers-kgsl-$TURNIP_VERSION-arm64.deb")
            if (!archive.exists() || sha256(archive) != TURNIP_SHA256) {
                archive.parentFile?.mkdirs()
                context.assets.open(TURNIP_ASSET).use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (sha256(archive) != TURNIP_SHA256) {
                Log.e(TAG, "Bundled Turnip archive failed checksum validation")
                return false
            }

            val library = File(turnipRoot, "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so")
            if (!library.exists() || !turnipIcd.exists()) {
                val root = turnipRoot.absolutePath.removePrefix(rootfsDir.absolutePath)
                val archiveInChroot = archive.absolutePath
                val installCommand = """
                    mkdir -p $root &&
                    dpkg-deb -x $archiveInChroot $root &&
                    sed -i 's#/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so#$root/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so#' $root/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
                """.trimIndent().replace("\n", " ")
                if (execChroot(installCommand) != 0) {
                    Log.e(TAG, "Could not extract bundled Turnip driver")
                    return false
                }
            }

            val available = library.exists() && turnipIcd.exists()
            if (!available) {
                Log.w(TAG, "Turnip driver unavailable; using software")
                return false
            }

            val icd = turnipIcd.absolutePath.removePrefix(rootfsDir.absolutePath)
            val probe = "command -v vulkaninfo >/dev/null 2>&1 && " +
                "VK_ICD_FILENAMES=$icd TU_DEBUG=noconform " +
                "timeout 10s vulkaninfo --summary >/dev/null 2>&1"
            if (execChroot(probe) != 0) {
                Log.w(TAG, "Turnip Vulkan probe failed; using software rendering")
                return false
            }

            Log.i(TAG, "Turnip KGSL driver ready")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "GPU setup failed; using software rendering", error)
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Make modern GLib app launching work on kernels with partial close_range support. */
    private fun prepareCloseRangeCompatibility() {
        val library = File(rootfsDir, "usr/local/lib/libnativos-close-range.so")
        if (library.exists()) return

        try {
            val source = File(baseDir, "compat/close_range.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #define _GNU_SOURCE
                #include <errno.h>
                int close_range(unsigned int first, unsigned int last, int flags) {
                    (void) first; (void) last; (void) flags;
                    errno = ENOSYS;
                    return -1;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/lib && " +
                    "gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so ${source.absolutePath}"
            )
            if (result != 0) Log.w(TAG, "Could not build close_range compatibility shim")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare close_range compatibility shim", error)
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
    @Synchronized
    fun startPhoshSession(screenWidth: Int = 1080, screenHeight: Int = 2160) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) Log.w(TAG, "Replacing existing tracked session")
        stopTrackedSessionProcess()
        killRootfsProcesses()

        ensureMounts()
        bindX11Socket()
        prepareCloseRangeCompatibility()

        val hardwareGpu = prepareHardwareGpu()
        val gpuEnvironment = if (hardwareGpu) {
            """
                export NATIVOS_GPU=turnip
                # Termux:X11 legacy drawing does not provide wlroots with a DRI3
                # DRM fd. Keep the compositor on Pixman while applications use
                # hardware-accelerated Zink/Turnip.
                export WLR_RENDERER=pixman
                unset LIBGL_ALWAYS_SOFTWARE
                unset GBM_ALWAYS_SOFTWARE
                export GALLIUM_DRIVER=zink
                export MESA_LOADER_DRIVER_OVERRIDE=zink
                export VK_ICD_FILENAMES=${turnipIcd.absolutePath.removePrefix(rootfsDir.absolutePath)}
                export TU_DEBUG=noconform
                export ZINK_DESCRIPTORS=lazy
                export MESA_VK_WSI_DEBUG=sw
            """.trimIndent()
        } else {
            """
                export NATIVOS_GPU=software
                export WLR_RENDERER=pixman
                export LIBGL_ALWAYS_SOFTWARE=1
                export GBM_ALWAYS_SOFTWARE=1
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=swrast
            """.trimIndent()
        }
        // Embedded X11 legacy drawing cannot return a DRM fd from DRI3. Hide the
        // extension so wlroots selects its shared-memory path. Zink still renders
        // on Turnip and presents through MESA_VK_WSI_DEBUG=sw.
        val preloadLibraries =
            "/usr/local/lib/libsocket_hook.so /usr/local/lib/libnativos-close-range.so /usr/local/lib/libnodri3.so /usr/local/lib/libandroid-shmem.so"
        val appPreloadLibraries = if (hardwareGpu) {
            "/usr/local/lib/libsocket_hook.so /usr/local/lib/libnativos-close-range.so /usr/local/lib/libandroid-shmem.so"
        } else {
            preloadLibraries
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val runScript = """
            # Standard FHS PATH
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=/tmp
            export HOME=/root
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            export XDG_SESSION_TYPE=x11
            export XDG_CURRENT_DESKTOP=Phosh
            export XDG_SESSION_DESKTOP=phosh
            export DESKTOP_SESSION=phosh
            export XDG_DATA_DIRS=/usr/share:/usr/local/share
            export XDG_CONFIG_DIRS=/etc/xdg
            export DISPLAY=:0
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export GTK_A11Y=none
            # GTK4's GL renderer requires dmabuf support that the nested X11
            # backend cannot expose. Cairo keeps GTK windows visible while GL
            # applications continue to use Zink/Turnip.
            export GSK_RENDERER=cairo
            export WLR_NO_HARDWARE_CURSORS=1
            $gpuEnvironment

            # Ensure runtime dir exists with correct permissions
            mkdir -p /tmp/runtime-root
            chown root:root /tmp/runtime-root
            chmod 0700 /tmp/runtime-root

            # GNOME Software and PackageKit require a system bus. There is no
            # systemd in this chroot, so start D-Bus and polkit explicitly.
            mkdir -p /run/dbus
            rm -f /run/dbus/pid /run/dbus/system_bus_socket
            dbus-uuidgen --ensure
            if dbus-daemon --system --fork --nopidfile; then
                echo "NativOS: System D-Bus started"
                if [ -x /usr/lib/polkit-1/polkitd ]; then
                    /usr/lib/polkit-1/polkitd --no-debug &
                    echo "NativOS: polkit started"
                fi
            else
                echo "NativOS: WARNING — system D-Bus failed to start"
            fi

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
                export DISPLAY=:0
                
                # The X11 backend never owns Android's physical DRM display.
                export WLR_DRM_NO_ATOMIC=1
                export WLR_DRM_DEVICES=""
                
                # CRITICAL: Set TMPDIR to match the Android app's TMPDIR
                # libsocket_hook.so uses TMPDIR to construct the abstract socket path
                # The bundled X11 server (libXlorie.so) creates abstract socket at: @<TMPDIR>/.X11-unix/X0
                # Both sides MUST use the same TMPDIR value for the abstract socket path to match
                export TMPDIR=${tmpDir.absolutePath}
                
                # LD_PRELOAD: Only load libraries that actually exist on this device
                # libsocket_hook.so translates filesystem connect() to abstract socket
                # libnodri3.so makes wlroots use X11 shared-memory presentation.
                # libandroid-shmem.so provides shared memory on Android kernels
                PRELOAD=""
                for lib in $preloadLibraries; do
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

                # Phoc needs libnodri3, but hardware-accelerated applications do
                # not. Phosh replaces LD_PRELOAD before launching the app session.
                APP_PRELOAD=""
                for lib in $appPreloadLibraries; do
                    if [ -f "${'$'}lib" ]; then
                        if [ -z "${'$'}APP_PRELOAD" ]; then
                            APP_PRELOAD="${'$'}lib"
                        else
                            APP_PRELOAD="${'$'}APP_PRELOAD:${'$'}lib"
                        fi
                    fi
                done
                export NATIVOS_APP_LD_PRELOAD="${'$'}APP_PRELOAD"
                
                echo "NativOS: TMPDIR=${'$'}TMPDIR"
                echo "NativOS: DISPLAY=${'$'}DISPLAY"
                echo "NativOS: GPU=${'$'}NATIVOS_GPU"
                
                cat > /tmp/start_phosh.sh << 'PHOSHEOF'
#!/bin/bash
echo $$ > /tmp/phosh_loop.pid
while true; do
    dbus-run-session -- phoc -C /etc/nativOS/phoc.ini -E "bash -c 'export LD_PRELOAD=${'$'}NATIVOS_APP_LD_PRELOAD; exec /usr/libexec/phosh -U'"
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

    /** Wait until both the Wayland socket and Phosh process exist. */
    fun awaitDesktopReady(timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionProcess?.isAlive != true) return false
            if (execChroot(
                    "test -S /tmp/runtime-root/wayland-0 && " +
                        "(pgrep -x phosh >/dev/null 2>&1 || pidof phosh >/dev/null 2>&1)"
                ) == 0
            ) {
                Log.i(TAG, "Desktop readiness check passed")
                // The process and socket appear just before Phosh commits its first
                // frame. Keep the splash visible through that short final gap.
                Thread.sleep(500)
                return true
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.e(TAG, "Desktop did not become ready within ${timeoutMs}ms")
        return false
    }

    private fun stopTrackedSessionProcess() {
        sessionProcess?.let { process ->
            try {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.w(TAG, "Error stopping tracked session: ${error.message}")
            }
        }
        sessionProcess = null
    }

    /** Kill only processes whose filesystem root is this app's chroot. */
    private fun killRootfsProcesses() {
        val root = rootfsDir.absolutePath
        val command = """
            targets=''
            for proc in /proc/[0-9]*; do
                [ "${'$'}proc/root" -ef '$root' ] 2>/dev/null || continue
                targets="${'$'}targets ${'$'}{proc#/proc/}"
            done
            if [ -n "${'$'}targets" ]; then
                kill -TERM ${'$'}targets 2>/dev/null || true
                sleep 1
                for pid in ${'$'}targets; do
                    [ "/proc/${'$'}pid/root" -ef '$root' ] 2>/dev/null &&
                        kill -KILL "${'$'}pid" 2>/dev/null || true
                done
            fi
        """.trimIndent()
        try {
            val result = rootShell.exec(command) { output ->
                if (output.isNotBlank()) Log.d(TAG, "cleanup: ${output.trimEnd()}")
            }
            if (result == 0) {
                Log.i(TAG, "Cleared stale chroot processes")
            } else {
                Log.w(TAG, "Stale chroot cleanup exited with code $result")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not clear stale chroot processes", error)
        }
    }

    /** Stop the chroot session and unmount bind mounts. */
    fun stopSession() {
        Log.i(TAG, "Stopping session...")
        stopTrackedSessionProcess()
        killRootfsProcesses()
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
