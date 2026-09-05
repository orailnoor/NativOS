import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningPropertiesFile = rootProject.file("signing/keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

val preparePatchedXlorie by tasks.registering {
    val upstream = layout.projectDirectory.file(
        "src/main/xlorie-upstream/arm64-v8a/libXlorie.so"
    )
    val patched = layout.buildDirectory.file(
        "generated/nativosJniLibs/arm64-v8a/libXlorie.so"
    )
    inputs.file(upstream)
    outputs.file(patched)

    doLast {
        val bytes = upstream.asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == "f9d60f48c5af45971571783166437b25dfb84cf7a2233c0265c49f652a9fe851") {
            "libXlorie.so changed; review and update the cursor patch offsets"
        }

        fun patch(offset: Int, expected: ByteArray, replacement: ByteArray) {
            check(bytes.copyOfRange(offset, offset + expected.size).contentEquals(expected)) {
                "Unexpected libXlorie instruction at 0x${offset.toString(16)}"
            }
            replacement.copyInto(bytes, offset)
        }

        // Upstream ddxReady() forces CursorVisible=TRUE and installs rootCursor,
        // overriding Xorg's -nocursor flag. Preserve FALSE and skip that call.
        patch(0x0e3288, byteArrayOf(0x2b, 0x00, 0x80.toByte(), 0x52),
            byteArrayOf(0xeb.toByte(), 0x03, 0x1f, 0x2a))
        patch(0x0e32b8, byteArrayOf(0x80.toByte(), 0x01, 0x3f, 0xd6.toByte()),
            byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte()))

        patched.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

val verifyBundledRootfs by tasks.registering {
    val rootfsDirectory = layout.projectDirectory.dir("src/main/assets/rootfs")
    inputs.dir(rootfsDirectory)

    doLast {
        val asset = rootfsDirectory.file("nativos-rootfs-arm64.tgz").asFile
        val checksumFile = rootfsDirectory.file("nativos-rootfs-arm64.tgz.sha256").asFile
        check(asset.isFile && asset.length() > 100L * 1024L * 1024L) {
            "Bundled rootfs is missing or implausibly small. Run scripts/build-rootfs-asset.sh."
        }
        check(checksumFile.isFile) {
            "Bundled rootfs checksum is missing: ${checksumFile.path}"
        }
        val expected = checksumFile.readText().trim().substringBefore(' ').lowercase()
        check(expected.matches(Regex("[0-9a-f]{64}"))) {
            "Bundled rootfs checksum file is invalid"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Bundled rootfs checksum mismatch: expected $expected, got $actual"
        }
    }
}

android {
    namespace = "com.nativOS"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nativOS"
        minSdk = 28  // Android 9+ (modern root tools, Camera2 stable)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
            jniLibs.srcDir(layout.buildDirectory.dir("generated/nativosJniLibs"))
        }
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    androidResources {
        // The production rootfs is already gzip-compressed. Recompressing it
        // wastes build memory and provides no meaningful size reduction.
        noCompress += "tgz"
    }
}

tasks.named("preBuild").configure {
    dependsOn(preparePatchedXlorie)
    dependsOn(verifyBundledRootfs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON serialization for bridge protocol
    implementation("org.json:json:20240303")
}
