package com.ai.assistance.operit.util

/** Adapted Operit numeric WASM bridge, hosted only by Nexus's isolated package process. */
object ToolPkgWasmNative {
    init { System.loadLibrary("toolpkgwasm") }
    external fun start(memoryBytes: Long)
    external fun load(bytes: ByteArray): Long
    external fun call(handle: Long, functionName: String, argTypes: IntArray, argBits: LongArray): String
    external fun close(handle: Long)
    external fun interrupt()
    external fun finish()
}
