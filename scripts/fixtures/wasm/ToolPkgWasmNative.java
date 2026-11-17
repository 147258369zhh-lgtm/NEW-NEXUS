package com.ai.assistance.operit.util;

import java.util.HexFormat;

/** Local JVM fixture for the production JNI source. Android isolation is a separate boundary. */
public final class ToolPkgWasmNative {
    private static native void start(long bytes);
    private static native long load(byte[] bytes);
    private static native String call(long handle, String name, int[] types, long[] bits);
    private static native void close(long handle);
    private static native void interrupt();
    private static native void finish();
    private static void require(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    private static byte[] bytes(String hex) { return HexFormat.of().parseHex(hex); }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        start(8L * 1024 * 1024);
        long add = load(bytes("0061736d0100000001070160027f7f017f030201000707010361646400000a09010700200020016a0b"));
        require(call(add, "add", new int[]{1, 1}, new long[]{20, 22}).contains("\"value\":42"), "numeric export result");
        require(call(add, "add", new int[]{2, 1}, new long[]{20, 22}).contains("type mismatch"), "argument type validation");
        require(call(add, "missing", new int[]{}, new long[]{}).contains("not found"), "missing export");
        close(add);
        require(call(add, "add", new int[]{1, 1}, new long[]{20, 22}).contains("closed"), "closed handle");
        long loop = load(bytes("0061736d0100000001040160000003020100070801046c6f6f7000000a0901070003400c000b0b"));
        long started = System.nanoTime();
        require(call(loop, "loop", new int[]{}, new long[]{}).contains("instruction limit"), "bounded loop");
        require((System.nanoTime() - started) / 1_000_000 < 10000, "instruction limit returned");
        interrupt();
        require(call(loop, "loop", new int[]{}, new long[]{}).contains("cancelled"), "cancelled runtime");
        finish();
        start(8L * 1024 * 1024);
        try {
            // One initial linear memory of 256 pages cannot fit the invocation's 8 MiB pool.
            load(bytes("0061736d01000000050401008002"));
            throw new AssertionError("memory limit not enforced");
        } catch (IllegalArgumentException expected) { require(expected.getMessage() != null, "memory failure"); }
        finish();
        System.out.println("Production WAMR JNI host check: arithmetic, typed ABI, opaque handles, instruction limit, cancellation, memory budget and fresh invocation passed.");
    }
}
