package com.ai.assistance.operit.core.tools.javascript

object OperitSdkScripts {
    fun versioning(): String = buildToolPkgApiRuntimeScript()
    fun tools(): String = getJsToolsDefinition()
    fun compose(): String = buildComposeDslContextBridgeDefinition()
}
