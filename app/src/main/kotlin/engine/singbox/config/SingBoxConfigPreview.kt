// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

internal fun loadSingBoxConfigPreview(
    running: Boolean,
    configFile: File,
    generateConfig: () -> String,
): String {
    val content = if (running) configFile.readText() else generateConfig()
    val parsed = runCatching { parseSingBoxJson(content) }.getOrNull() ?: return content
    val cleaned = cleanPreviewConfig(parsed)
    return encodeSingBoxJson(cleaned)
}

private fun cleanPreviewConfig(root: JsonObject): JsonObject {
    val outbounds = root["outbounds"] as? JsonArray ?: return root
    val hasAllTestSelector = outbounds.any {
        ((it as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull == APP_ALL_NODES_TEST_SELECTOR
    }
    if (!hasAllTestSelector) return root

    val globalSelector = outbounds.firstOrNull {
        ((it as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull == APP_GLOBAL_SELECTOR
    } as? JsonObject
    val defaultTarget = (globalSelector?.get("default") as? JsonPrimitive)?.contentOrNull
    val isSingleNodeMode = defaultTarget != null &&
        defaultTarget != APP_DIRECT_OUTBOUND &&
        defaultTarget != APP_GLOBAL_SELECTOR &&
        !defaultTarget.startsWith("outbound_group_")

    if (!isSingleNodeMode) return root

    val route = root["route"] as? JsonObject
    val routeRules = route?.get("rules") as? JsonArray
    val routeReferencedTags = buildSet {
        add(defaultTarget)
        add(APP_DIRECT_OUTBOUND)
        add(APP_GLOBAL_SELECTOR)
        routeRules?.forEach { element ->
            val ob = ((element as? JsonObject)?.get("outbound") as? JsonPrimitive)?.contentOrNull
            if (!ob.isNullOrBlank()) add(ob)
        }
        val routeFinal = (route?.get("final") as? JsonPrimitive)?.contentOrNull
        if (!routeFinal.isNullOrBlank()) add(routeFinal)
    }

    val cleanedOutbounds = outbounds.filter { element ->
        val tag = ((element as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull
        tag != APP_ALL_NODES_TEST_SELECTOR && (tag == null || tag in routeReferencedTags)
    }

    return JsonObject(
        buildMap {
            putAll(root)
            put("outbounds", JsonArray(cleanedOutbounds))
        },
    )
}
