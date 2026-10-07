// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.inheritedGroupDetour
import engine.singbox.config.parseSingBoxJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Returns the active single outbound if the app is currently running in single-node (minimal) mode. */
internal fun AppState.selectedSingleOutboundOrNull(): OutboundState? {
    val activeTarget = selectorSelections[APP_GLOBAL_SELECTOR]?.trim().orEmpty()
    val isCustomSelector = selectors.any { it.tag == activeTarget }
    val isGroupSelector = activeTarget.startsWith("outbound_group_")
    val selectedSingleOutbound = outbounds.firstOrNull { it.tag == activeTarget }
    val isSingleOutbound = selectedSingleOutbound != null &&
        activeTarget != APP_DIRECT_OUTBOUND &&
        activeTarget != APP_GLOBAL_SELECTOR &&
        !isCustomSelector &&
        !isGroupSelector
    return if (isSingleOutbound) selectedSingleOutbound else null
}

/**
 * Checks whether an outbound group change (enable, disable, edit, delete) affects the currently active proxy.
 * In minimal single-node mode, returns false if the target group does not contain the active node,
 * its detour chain, or any node referenced by active route rules.
 */
internal fun AppState.isGroupAffectingActiveProxy(groupId: Int): Boolean {
    if (!proxyRunning) return false
    val singleNode = selectedSingleOutboundOrNull()
    if (singleNode != null) {
        if (singleNode.groupId == groupId) return true

        val outboundsByTag = outbounds.associateBy { it.tag }
        val parsed = runCatching { parseSingBoxJson(singleNode.json) }.getOrNull()
        val explicitDetour = (parsed?.get("detour") as? JsonPrimitive)?.contentOrNull?.trim()
        val groupDetours = outboundGroups.associate { it.id to it.detour }
        val detourTag = if (!explicitDetour.isNullOrBlank()) {
            explicitDetour
        } else if (parsed != null) {
            singleNode.inheritedGroupDetour(groupDetours[singleNode.groupId].orEmpty(), parsed)
        } else null

        if (!detourTag.isNullOrBlank() && outboundsByTag[detourTag]?.groupId == groupId) return true

        val routeReferencedTags = buildSet {
            routeRules
                .filter { it.enabled && it.action != SingBoxRouteRuleActionReject }
                .mapNotNull { it.outbound.trim().takeIf(String::isNotEmpty) }
                .forEach(::add)
            routeFinal.trim().takeIf(String::isNotEmpty)?.let(::add)
        }
        if (routeReferencedTags.any { outboundsByTag[it]?.groupId == groupId }) return true

        return false
    }

    val activeTarget = selectorSelections[APP_GLOBAL_SELECTOR]?.trim().orEmpty()
    if (activeTarget.startsWith("outbound_group_")) {
        val grp = outboundGroups.firstOrNull { managedOutboundGroupSelectorTag(it.id, it.name) == activeTarget }
        return grp?.id == groupId
    }

    val customSelector = selectors.firstOrNull { it.tag == activeTarget }
    if (customSelector != null) {
        val groupTag = outboundGroups.firstOrNull { it.id == groupId }?.let { managedOutboundGroupSelectorTag(it.id, it.name) }
        if (groupTag != null && customSelector.outbounds.contains(groupTag)) return true
        val outboundsInGroup = outbounds.filter { it.groupId == groupId }.map { it.tag }.toSet()
        return customSelector.outbounds.any { it in outboundsInGroup }
    }

    return outbounds.any { it.groupId == groupId }
}

/**
 * Checks whether the runtime configuration of an active single node has actually changed.
 */
internal fun AppState.hasSingleNodeKernelChanged(other: AppState, activeNode: OutboundState): Boolean {
    val otherNode = other.outbounds.firstOrNull { it.tag == activeNode.tag }
    if (otherNode == null || otherNode.type != activeNode.type || otherNode.json != activeNode.json) return true

    val thisGroupDetour = outboundGroups.firstOrNull { it.id == activeNode.groupId }?.detour.orEmpty()
    val otherGroupDetour = other.outboundGroups.firstOrNull { it.id == otherNode.groupId }?.detour.orEmpty()
    if (thisGroupDetour != otherGroupDetour) return true

    if (routeRules != other.routeRules || routeFinal != other.routeFinal) return true
    if (routeAutoDetectInterface != other.routeAutoDetectInterface ||
        routeOverrideAndroidVpn != other.routeOverrideAndroidVpn ||
        routeFindProcess != other.routeFindProcess ||
        routeDefaultNetworkStrategy != other.routeDefaultNetworkStrategy ||
        routeDefaultNetworkTypes != other.routeDefaultNetworkTypes
    ) return true

    if (dnsRules != other.dnsRules ||
        dnsServers != other.dnsServers ||
        dnsFinal != other.dnsFinal ||
        dnsCacheCapacity != other.dnsCacheCapacity ||
        dnsOptimisticCache != other.dnsOptimisticCache ||
        dnsDisableCache != other.dnsDisableCache ||
        dnsDisableExpire != other.dnsDisableExpire ||
        dnsTimeout != other.dnsTimeout ||
        enableLocalDns != other.enableLocalDns
    ) return true

    if (enableIpv6 != other.enableIpv6 ||
        configOverrideScript != other.configOverrideScript ||
        enableConfigOverrideScript != other.enableConfigOverrideScript
    ) return true

    return false
}
