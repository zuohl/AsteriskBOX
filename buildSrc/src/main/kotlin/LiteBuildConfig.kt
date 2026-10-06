// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

object LiteConfig {
    const val PACKAGE_NAME = "org.asterisk.zcc.abox.lite"
    val SUPPORTED_ANDROID_ABIS = listOf("arm64-v8a")

    fun resolveVersionName(upstreamVersion: String = ProjectConfig.VERSION_NAME): String {
        val trimmed = upstreamVersion.trim()
        return when {
            trimmed.endsWith("-lite") || trimmed.contains("-lite.") -> trimmed
            else -> "$trimmed-lite"
        }
    }

    fun calculateVersionCode(versionName: String = resolveVersionName()): Int {
        val regex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-dev)?(?:-lite(?:\.(\d+))?)?""")
        val match = regex.find(versionName.trim()) ?: return 1000000
        val major = match.groupValues[1].toIntOrNull() ?: 1
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        val rev = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
        return major * 1000000 + minor * 10000 + patch * 100 + rev
    }
}
