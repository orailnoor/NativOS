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

        // Bubblewrap's privileged fallback needs the sandbox root to be a mount
        // point. Do this before the nested mounts so /dev, /proc, and /sys stay
        // visible inside that mount rather than being hidden by it.
        val existingMounts = rootShell.exec("mount").lines()
        if (existingMounts.none { it.contains(" on ${rootfsDir.absolutePath} ") }) {
            rootShell.exec("mount --bind ${rootfsDir.absolutePath} ${rootfsDir.absolutePath}")
            Log.i(TAG, "Made chroot root a bind mount")
        }

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

        // Android's app-data filesystem is nosuid. Change the flag only on the
        // isolated rootfs bind (not its parent mount), addressing it as / from
        // inside the chroot so mount resolves the correct bind mount.
        if (execChroot("mount -o remount,bind,suid,dev /") != 0) {
            Log.w(TAG, "Could not enable privileged helpers on chroot bind mount")
        }

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
                #include <dirent.h>
                #include <errno.h>
                #include <limits.h>
                #include <ftw.h>
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

    /**
     * Use Bubblewrap's privileged fallback on Android kernels that disable
     * unprivileged user namespaces. Android mounts app data with nosuid, so a
     * small setuid launcher prepares the mount sandbox, then drops the actual
     * application and D-Bus proxy to a locked UID. The original distro binary
     * remains preserved.
     */
    private fun prepareFlatpakCompatibility() {
        val distroBwrap = File(rootfsDir, "usr/bin/bwrap")
        val installedWrapper = File(rootfsDir, "usr/local/bin/bwrap")
        if (!distroBwrap.exists() && !installedWrapper.exists()) return

        try {
            val source = File(baseDir, "compat/nativos_bwrap.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #define _GNU_SOURCE
                #include <dirent.h>
                #include <errno.h>
                #include <fcntl.h>
                #include <limits.h>
                #include <stdio.h>
                #include <stdlib.h>
                #include <string.h>
                #include <sys/mman.h>
                #include <sys/stat.h>
                #include <sys/types.h>
                #include <unistd.h>

                static int unsupported_namespace(const char *value) {
                    return strcmp(value, "--unshare-pid") == 0 ||
                           strcmp(value, "--unshare-ipc") == 0;
                }

                static int bind_option(const char *value) {
                    return strcmp(value, "--bind") == 0 ||
                           strcmp(value, "--ro-bind") == 0 ||
                           strcmp(value, "--dev-bind") == 0 ||
                           strcmp(value, "--bind-try") == 0 ||
                           strcmp(value, "--ro-bind-try") == 0 ||
                           strcmp(value, "--dev-bind-try") == 0;
                }

                static int unavailable_runtime_bind(const char *source) {
                    return strncmp(source, "/tmp/runtime-root/doc/", 22) == 0 ||
                           strncmp(source, "/tmp/runtime-root/.flatpak-helper/", 34) == 0;
                }

                static void make_runtime_parents_traversable(const char *source) {
                    const char *prefix = "/tmp/runtime-root/";
                    if (strncmp(source, prefix, 18) != 0) return;
                    char *path = strdup(source);
                    if (path == NULL) return;
                    for (char *cursor = path + 18; (cursor = strchr(cursor, '/')) != NULL; cursor++) {
                        *cursor = '\0';
                        chmod(path, 0711);
                        *cursor = '/';
                    }
                    free(path);
                }

                static void make_root_parents_traversable(const char *source) {
                    if (strncmp(source, "/root/", 6) != 0) return;
                    char *path = strdup(source);
                    if (path == NULL) return;
                    for (char *cursor = path + 6; (cursor = strchr(cursor, '/')) != NULL; cursor++) {
                        *cursor = '\0';
                        chmod(path, 0755);
                        *cursor = '/';
                    }
                    free(path);
                }

                static void make_runtime_tree_traversable(const char *path, int depth) {
                    if (depth > 12) return;
                    chmod(path, 0711);
                    DIR *directory = opendir(path);
                    if (directory == NULL) return;
                    struct dirent *entry;
                    while ((entry = readdir(directory)) != NULL) {
                        if (strcmp(entry->d_name, ".") == 0 ||
                            strcmp(entry->d_name, "..") == 0) continue;
                        char child[PATH_MAX];
                        if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) >=
                            (int) sizeof(child)) continue;
                        struct stat info;
                        if (lstat(child, &info) == 0 && S_ISDIR(info.st_mode)) {
                            make_runtime_tree_traversable(child, depth + 1);
                        }
                    }
                    closedir(directory);
                }

                static void make_app_tree_writable(const char *path, int depth,
                                                   uid_t sandbox_uid) {
                    if (depth > 24) return;
                    struct stat info;
                    if (lstat(path, &info) != 0 || S_ISLNK(info.st_mode)) return;
                    lchown(path, sandbox_uid, 0);
                    mode_t mode = info.st_mode | S_IRUSR | S_IWUSR;
                    if (S_ISDIR(info.st_mode)) mode |= S_IXUSR;
                    chmod(path, mode);
                    if (!S_ISDIR(info.st_mode)) return;
                    DIR *directory = opendir(path);
                    if (directory == NULL) return;
                    struct dirent *entry;
                    while ((entry = readdir(directory)) != NULL) {
                        if (strcmp(entry->d_name, ".") == 0 ||
                            strcmp(entry->d_name, "..") == 0) continue;
                        char child[PATH_MAX];
                        if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) >=
                            (int) sizeof(child)) continue;
                        make_app_tree_writable(child, depth + 1, sandbox_uid);
                    }
                    closedir(directory);
                }

                static void prepare_writable_bind(const char *source,
                                                  const char *destination,
                                                  uid_t sandbox_uid) {
                    if (strcmp(destination, "/app/extra") == 0 ||
                        strcmp(destination, "/run/ld-so-cache-dir") == 0 ||
                        strncmp(source, "/root/.var/app/", 15) == 0 ||
                        strncmp(source, "/root/.config/", 14) == 0 ||
                        strncmp(source, "/tmp/runtime-root/", 18) == 0) {
                        int ownership_result = chown(source, sandbox_uid, 0);
                        (void) ownership_result;
                        struct stat info;
                        chmod(source,
                              lstat(source, &info) == 0 && S_ISDIR(info.st_mode) ? 0775 : 0664);
                    }
                    if (strncmp(source, "/root/.var/app/", 15) == 0) {
                        make_app_tree_writable(source, 0, sandbox_uid);
                    }
                    make_runtime_parents_traversable(source);
                    make_root_parents_traversable(source);
                }

                static int filtered_args_fd(int source_fd, uid_t sandbox_uid) {
                    off_t size = lseek(source_fd, 0, SEEK_END);
                    if (size < 0 || lseek(source_fd, 0, SEEK_SET) < 0) return -1;

                    char *buffer = malloc((size_t) size);
                    if (buffer == NULL) return -1;
                    size_t received = 0;
                    while (received < (size_t) size) {
                        ssize_t count = read(source_fd, buffer + received, (size_t) size - received);
                        if (count <= 0) {
                            free(buffer);
                            return -1;
                        }
                        received += (size_t) count;
                    }
                    int target_fd = memfd_create("nativos-bwrap-args", 0);
                    if (target_fd < 0) {
                        free(buffer);
                        return -1;
                    }
                    size_t offset = 0;
                    while (offset < received) {
                        size_t remaining = received - offset;
                        size_t length = strnlen(buffer + offset, remaining);
                        if (length == remaining) break;
                        if (bind_option(buffer + offset)) {
                            size_t source_offset = offset + length + 1;
                            if (source_offset < received) {
                                size_t source_length = strnlen(
                                    buffer + source_offset, received - source_offset);
                                size_t destination_offset = source_offset + source_length + 1;
                                if (source_length < received - source_offset &&
                                    destination_offset < received) {
                                    size_t destination_length = strnlen(
                                        buffer + destination_offset, received - destination_offset);
                                    if (destination_length < received - destination_offset) {
                                        if (unavailable_runtime_bind(buffer + source_offset) ||
                                            strcmp(buffer + destination_offset,
                                                   "/run/host/monitor") == 0) {
                                            offset = destination_offset + destination_length + 1;
                                            continue;
                                        }
                                        prepare_writable_bind(
                                            buffer + source_offset,
                                            buffer + destination_offset,
                                            sandbox_uid);
                                    }
                                }
                            }
                        }
                        if (strcmp(buffer + offset, "0600") == 0 &&
                            offset + length + 1 < received) {
                            size_t action_offset = offset + length + 1;
                            size_t action_length = strnlen(
                                buffer + action_offset, received - action_offset);
                            size_t fd_offset = action_offset + action_length + 1;
                            if ((strcmp(buffer + action_offset, "--file") == 0 ||
                                 strcmp(buffer + action_offset, "--ro-bind-data") == 0) &&
                                action_length < received - action_offset &&
                                fd_offset < received) {
                                size_t fd_length = strnlen(
                                    buffer + fd_offset, received - fd_offset);
                                size_t destination_offset = fd_offset + fd_length + 1;
                                if (fd_length < received - fd_offset &&
                                    destination_offset < received) {
                                    size_t destination_length = strnlen(
                                        buffer + destination_offset,
                                        received - destination_offset);
                                    if (destination_length < received - destination_offset &&
                                        strcmp(buffer + destination_offset,
                                               "/.flatpak-info") == 0) {
                                        static const char readable[] = "0644";
                                        if (write(target_fd, readable, sizeof(readable)) !=
                                            (ssize_t) sizeof(readable)) {
                                            close(target_fd);
                                            free(buffer);
                                            return -1;
                                        }
                                        offset += length + 1;
                                        continue;
                                    }
                                }
                            }
                        }
                        if (strcmp(buffer + offset, "--ro-bind-data") == 0 &&
                            offset + length + 1 < received) {
                            size_t fd_offset = offset + length + 1;
                            size_t fd_length = strnlen(
                                buffer + fd_offset, received - fd_offset);
                            size_t destination_offset = fd_offset + fd_length + 1;
                            if (fd_length < received - fd_offset &&
                                destination_offset < received) {
                                size_t destination_length = strnlen(
                                    buffer + destination_offset,
                                    received - destination_offset);
                                if (destination_length < received - destination_offset &&
                                    strcmp(buffer + destination_offset,
                                           "/.flatpak-info") == 0) {
                                    static const char permissions[] = "--perms";
                                    static const char readable[] = "0644";
                                    if (write(target_fd, permissions, sizeof(permissions)) !=
                                            (ssize_t) sizeof(permissions) ||
                                        write(target_fd, readable, sizeof(readable)) !=
                                            (ssize_t) sizeof(readable)) {
                                        close(target_fd);
                                        free(buffer);
                                        return -1;
                                    }
                                }
                            }
                        }
                        if (!unsupported_namespace(buffer + offset) &&
                            write(target_fd, buffer + offset, length + 1) != (ssize_t) (length + 1)) {
                            close(target_fd);
                            free(buffer);
                            return -1;
                        }
                        offset += length + 1;
                    }
                    free(buffer);
                    if (lseek(target_fd, 0, SEEK_SET) < 0) {
                        close(target_fd);
                        return -1;
                    }
                    return target_fd;
                }

                int main(int argc, char **argv) {
                    const char *program_name = strrchr(argv[0], '/');
                    program_name = program_name == NULL ? argv[0] : program_name + 1;
                    if (strcmp(program_name, "nativos-flatpak-drop") == 0) {
                        chmod("/", 0755);
                        chmod("/etc", 0755);
                        chmod("/usr", 0755);
                        chmod("/dev/shm", 01777);
                        chmod("/run", 0755);
                        chmod("/run/dbus", 0755);
                        chmod("/run/flatpak", 0755);
                        chmod("/run/user", 0755);
                        chmod("/run/user/0", 0700);
                        chmod("/.flatpak-info", 0644);
                        if (argc < 2 || setgid(1000) != 0 || setuid(1000) != 0) {
                            perror("nativos-flatpak-drop");
                            return 1;
                        }
                        execvp(argv[1], argv + 1);
                        perror("nativos-flatpak-drop: execvp");
                        return errno == ENOENT ? 127 : 126;
                    }

                    uid_t sandbox_uid = getuid() == 0 ? 1000 : getuid();
                    if (geteuid() == 0) {
                        // The desktop currently stores its per-user Flatpaks
                        // below /root. Let the sandbox UID traverse the runtime,
                        // and own the one writable apply-extra destination.
                        chmod("/root", 0755);
                        chmod("/root/.local/share/flatpak", 0755);
                        make_runtime_tree_traversable("/tmp/runtime-root", 0);
                        for (int input = 1; input + 2 < argc; input++) {
                            if (bind_option(argv[input])) {
                                prepare_writable_bind(
                                    argv[input + 1], argv[input + 2], sandbox_uid);
                            }
                        }
                    }
                    // Several Android kernels disable PID and IPC namespaces.
                    // Flatpak remains isolated by Bubblewrap's mount namespace;
                    // omit only the flags the kernel explicitly cannot create.
                    char **filtered = calloc((size_t) argc + 32, sizeof(char *));
                    if (filtered == NULL) {
                        perror("nativos-bwrap: calloc");
                        return 1;
                    }
                    int output = 0;
                    for (int input = 0; input < argc; input++) {
                        if (bind_option(argv[input]) && input + 2 < argc &&
                            unavailable_runtime_bind(argv[input + 1])) {
                            input += 2;
                            continue;
                        }
                        if (unsupported_namespace(argv[input])) {
                            continue;
                        }
                        if (geteuid() == 0 && strcmp(argv[input], "--") == 0) {
                            // Android cannot create the user namespace required
                            // by bwrap's --uid option. Bind this launcher into the
                            // completed mount sandbox and drop IDs immediately
                            // before the application process is executed instead.
                            filtered[output++] = "--ro-bind";
                            filtered[output++] = "/usr/local/bin/bwrap";
                            filtered[output++] = "/run/nativos-flatpak-drop";
                            filtered[output++] = "--dir";
                            filtered[output++] = "/run/nativos";
                            filtered[output++] = "--ro-bind-try";
                            filtered[output++] = "/usr/local/lib/libsocket_hook.so";
                            filtered[output++] = "/run/nativos/libsocket_hook.so";
                            filtered[output++] = "--ro-bind-try";
                            filtered[output++] = "/usr/local/lib/libnativos-close-range.so";
                            filtered[output++] = "/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "LD_PRELOAD";
                            filtered[output++] = "/run/nativos/libsocket_hook.so:/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "ZYPAK_LD_PRELOAD";
                            filtered[output++] = "/run/nativos/libsocket_hook.so:/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "TMPDIR";
                            filtered[output++] = "${tmpDir.absolutePath}";
                        }
                        if (strcmp(argv[input], "--args") == 0 && input + 1 < argc) {
                            int source_fd = atoi(argv[input + 1]);
                            int target_fd = filtered_args_fd(source_fd, sandbox_uid);
                            if (target_fd < 0) {
                                perror("nativos-bwrap: filter args");
                                return 1;
                            }
                            char *fd_value = malloc(24);
                            if (fd_value == NULL) return 1;
                            snprintf(fd_value, 24, "%d", target_fd);
                            filtered[output++] = argv[input];
                            filtered[output++] = fd_value;
                            input++;
                            continue;
                        }
                        filtered[output++] = argv[input];
                        if (geteuid() == 0 && strcmp(argv[input], "--") == 0) {
                            filtered[output++] = "/run/nativos-flatpak-drop";
                        }
                    }
                    filtered[output] = NULL;

                    execv("/usr/bin/bwrap", filtered);
                    perror("nativos-bwrap: execv");
                    return errno == ENOENT ? 127 : 126;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/bwrap ${source.absolutePath} && " +
                    "chown root:root /usr/local/bin/bwrap && chmod 4755 /usr/local/bin/bwrap && " +
                    "/usr/local/bin/bwrap --ro-bind / / --proc /proc --dev /dev /bin/true"
            )
            if (result == 0) {
                Log.i(TAG, "Flatpak Bubblewrap compatibility ready")
            } else {
                Log.w(TAG, "Could not prepare Flatpak Bubblewrap compatibility (exit $result)")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare Flatpak Bubblewrap compatibility", error)
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
        prepareFlatpakCompatibility()

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
            export XDG_DATA_DIRS=/run/nativOS/android-apps/share:/usr/share:/usr/local/share
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

            # The desktop runs as root to manage this private chroot, while
            # Flatpak applications are dropped to UID 1000. Permit that UID to
            # authenticate to the session bus; per-app xdg-dbus-proxy policies
            # still filter every Flatpak connection.
            if ! getent passwd 1000 >/dev/null 2>&1; then
                useradd --uid 1000 --no-create-home --home-dir /root \
                    --shell /usr/sbin/nologin nativos-app
            fi
            mkdir -p /etc/opt/chrome/policies/managed \
                /etc/opt/chrome/policies/recommended \
                /etc/opt/chrome/policies/enrollment
            dbus-uuidgen --ensure=/etc/machine-id

            configure_cobalt_wayland() {
                app_id="${'$'}1"
                flags_name="${'$'}2"
                [ -d "/root/.local/share/flatpak/app/${'$'}app_id" ] || return 0
                flags_dir="/root/.var/app/${'$'}app_id/config"
                flags_file="${'$'}flags_dir/${'$'}flags_name-flags.conf"
                mkdir -p "${'$'}flags_dir"
                touch "${'$'}flags_file"
                grep -qxF -- '--ozone-platform=wayland' "${'$'}flags_file" || \
                    printf '%s\n' '--ozone-platform=wayland' >> "${'$'}flags_file"
            }
            (
                while true; do
                    configure_cobalt_wayland com.google.Chrome chrome
                    configure_cobalt_wayland com.microsoft.Edge edge
                    configure_cobalt_wayland com.brave.Browser brave
                    configure_cobalt_wayland com.vivaldi.Vivaldi vivaldi
                    configure_cobalt_wayland com.opera.Opera opera
                    sleep 5
                done
            ) &

            mkdir -p /etc/dbus-1
            cat > /etc/dbus-1/session-local.conf << 'DBUSEOF'
<busconfig>
  <policy context="default">
    <allow user="1000"/>
  </policy>
</busconfig>
DBUSEOF

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
    dbus-run-session -- phoc -C /etc/nativOS/phoc.ini -E "bash -c '
        export LD_PRELOAD=${'$'}NATIVOS_APP_LD_PRELOAD
        [ -x /usr/libexec/xdg-desktop-portal-gtk ] && /usr/libexec/xdg-desktop-portal-gtk >/tmp/xdg-desktop-portal-gtk.log 2>&1 &
        [ -x /usr/libexec/xdg-desktop-portal ] && /usr/libexec/xdg-desktop-portal >/tmp/xdg-desktop-portal.log 2>&1 &
        exec /usr/libexec/phosh -U
    '"
    echo "NativOS: Phoc exited, restarting in 2 seconds..."
    sleep 2
done
PHOSHEOF
                chmod +x /tmp/start_phosh.sh
                exec /tmp/start_phosh.sh
            elif command -v kgx >/dev/null 2>&1; then
                echo "NativOS: Fallback — launching GNOME Console"
                exec kgx
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
            File(rootfsDir, "tmp").absolutePath,
            rootfsDir.absolutePath
        )
        targets.forEach { target ->
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
        // Never inherit Android's TMPDIR (normally /data/local/tmp): that path
        // does not exist inside the chroot and breaks GPG/Flatpak temporary dirs.
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export TMPDIR=/tmp HOME=/root; $command"
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
