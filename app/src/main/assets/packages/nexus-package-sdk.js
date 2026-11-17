(function (root) {
    "use strict";
    const cache = Object.create(null);
    const registrations = [], callbacks = Object.create(null), namedCallbacks = Object.create(null);
    const registerKinds = ["NavigationEntry", "ToolboxUiModule", "UiRoute", "DesktopWidget", "AppLifecycleHook", "MessageProcessingPlugin",
        "XmlRenderPlugin", "InputMenuTogglePlugin", "ChatInputHook", "ChatViewHook", "ChatMessageHook", "ChatMessageMenuItem", "ChatRuntimeHook",
        "ToolLifecycleHook", "PromptInputHook", "PromptHistoryHook", "PromptEstimateHistoryHook", "SystemPromptComposeHook", "ToolPromptComposeHook",
        "PromptFinalizeHook", "PromptEstimateFinalizeHook", "SummaryGenerateHook", "AiProvider"];
    root.ToolPkg = {};
    for (const kind of registerKinds) root.ToolPkg["register" + kind] = function (definition) {
        if (!definition || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$/.test(definition.id)) throw new Error("PACKAGE_REGISTRATION_ID_INVALID");
        if (registrations.length >= 64) throw new Error("PACKAGE_REGISTRATION_LIMIT");
        if (registrations.some(row => row.kind === kind && row.id === definition.id)) throw new Error("PACKAGE_REGISTRATION_DUPLICATE");
        function capture(value, path, depth) {
            if (depth > 20) throw new Error("PACKAGE_REGISTRATION_DEPTH_LIMIT");
            const key = kind + "/" + definition.id + "/" + path.join("/");
            if (typeof value === "function") { callbacks[key] = value; return {nexus_callback:key}; }
            if (typeof value === "string" && path.length === 1 && ["function", "action", "listModels", "sendMessage", "testConnection", "calculateInputTokens"].includes(path[0])) {
                if (!/^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(value)) throw new Error("PACKAGE_CALLBACK_EXPORT_INVALID");
                namedCallbacks[key] = value; return {nexus_callback:key};
            }
            if (Array.isArray(value)) return value.map((item, index) => capture(item, path.concat(String(index)), depth + 1));
            if (value && typeof value === "object") {
                const object = Object.create(null);
                for (const field of Object.keys(value)) {
                    if (["__proto__", "prototype", "constructor", "nexus_callback"].includes(field)) throw new Error("PACKAGE_REGISTRATION_FIELD_INVALID");
                    object[field] = capture(value[field], path.concat(field), depth + 1);
                }
                return object;
            }
            return value;
        }
        const clean = capture(definition, [], 0);
        registrations.push(Object.assign(clean, {kind: kind}));
    };
    root.ToolPkg._m = function () {};
    // Operit ToolPkg numeric WASM ABI. Modules remain inside this invocation's isolated process.
    root.ToolPkg.wasm = Object.freeze({ call: async function (moduleId, exportName, args) {
        if (typeof moduleId !== "string" || typeof exportName !== "string") throw new Error("PACKAGE_WASM_NAME_REQUIRED");
        const values = args == null ? [] : args;
        if (!Array.isArray(values) || values.length > 32) throw new Error("PACKAGE_WASM_ARGUMENT_LIMIT");
        const normalized = values.map(function (arg) {
            if (!arg || typeof arg !== "object" || !Object.prototype.hasOwnProperty.call(arg, "value")) throw new Error("PACKAGE_WASM_ARGUMENT_REQUIRED");
            const type = String(arg.type).toLowerCase();
            let value = arg.value;
            if (type === "i64") {
                if (typeof value === "bigint") value = value.toString();
                else if (typeof value === "number") {
                    if (!Number.isSafeInteger(value)) throw new Error("PACKAGE_WASM_I64_REQUIRES_STRING");
                    value = String(value);
                }
                if (typeof value !== "string" || !/^-?[0-9]{1,19}$/.test(value)) throw new Error("PACKAGE_WASM_I64_RANGE");
            } else {
                if (!["i32", "f32", "f64"].includes(type) || !["number", "string"].includes(typeof value)) throw new Error("PACKAGE_WASM_ARGUMENT_TYPE");
                value = Number(value);
                if (!Number.isFinite(value) || (type === "i32" && (!Number.isInteger(value) || value < -2147483648 || value > 2147483647)))
                    throw new Error("PACKAGE_WASM_ARGUMENT_RANGE");
            }
            return {type: type, value: value};
        });
        const reply = JSON.parse(root.NativeInterface.__call("nexusWasm", JSON.stringify([JSON.stringify({module: moduleId, export: exportName, args: normalized})])));
        if (!reply || reply.success !== true) throw new Error(reply && reply.message || "PACKAGE_WASM_CALL_FAILED");
        const decode = function (row) { return row.type === "i64" ? String(row.value) : Number(row.value); };
        const results = reply.results || [];
        if (!results.length) return undefined;
        if (results.length === 1) return decode(results[0]);
        return results.map(function (row) { return {type: row.type, value: decode(row), bits: row.bits}; });
    } });
    root.__nexusResult = { done: false };
    root.complete = function (value) {
        if (!root.__nexusResult.done) root.__nexusResult = { done: true, ok: !(value && (value.ok === false || value.success === false)), value: value === undefined ? null : value };
    };
    function invokeHost(name, args) {
        const raw = root.NativeInterface.nexusInvoke(JSON.stringify({ tool: name, arguments: args || {} }));
        const value = JSON.parse(raw);
        if (value.ok === false) throw new Error(value.code || "PACKAGE_TOOL_FAILED");
        return value;
    }
    root.__nexusNativeCall = invokeHost;
    root.__operitExpose = function (name, value) { root[name] = value; };
    root.__operitCurrentCallId = "nexus-package-call";
    root.__operitGetCallState = function () { return { toolPkgApi: { apiVersion: root.__nexusApiVersion || "1.0.1" } }; };
    root.toolCall = async function (nameOrOptions, args) {
        const name = typeof nameOrOptions === "string" ? nameOrOptions : nameOrOptions && nameOrOptions.name;
        const params = typeof nameOrOptions === "string" ? args : nameOrOptions && (nameOrOptions.params || nameOrOptions.parameters);
        if (!name) throw new Error("PACKAGE_TOOL_NAME_REQUIRED");
        if (root.__nexusProviderMode && name === "http_request" && nameOrOptions && typeof nameOrOptions.onIntermediateResult === "function") {
            const info = invokeHost("package:provider-http-start", params || {});
            let content = "";
            while (true) {
                const next = invokeHost("package:provider-http-next", {});
                if (next.done) break;
                content += next.chunk;
                if (content.length > 262144) throw new Error("PACKAGE_PROVIDER_HTTP_SIZE_LIMIT");
                await nameOrOptions.onIntermediateResult({type:"chunk",chunk:next.chunk});
            }
            return {statusCode:info.statusCode,headers:info.headers,content:content,contentType:info.contentType};
        }
        const response = invokeHost("operit:" + name, params || {});
        if (name === "read_file_full" && response.data && response.data.truncated) {
            const accumulated = response.data;
            let totalBytes = accumulated.bytes_read || 0, pages = 1;
            while (accumulated.truncated) {
                if (totalBytes >= 512 * 1024 || pages++ >= 40) throw new Error("PACKAGE_FILE_READ_LIMIT");
                const next = invokeHost("operit:read_file_chunk", Object.assign({}, params || {}, {
                    offset_bytes: accumulated.next_offset_bytes, expected_revision: accumulated.revision
                })).data;
                if (!next || next.next_offset_bytes <= accumulated.next_offset_bytes) throw new Error("PACKAGE_FILE_PAGING_FAILED");
                accumulated.content += next.content;
                totalBytes += next.bytes_read;
                accumulated.bytes_read = totalBytes;
                accumulated.next_offset_bytes = next.next_offset_bytes;
                accumulated.truncated = next.truncated;
            }
        }
        return Object.prototype.hasOwnProperty.call(response, "data") ? response.data : response;
    };
    root.getEnv = function (name) { return invokeHost("package:environment", { name: String(name) }).value || ""; };
    root.sendIntermediateResult = function (value) {
        return invokeHost(root.__nexusProviderMode ? "package:provider-output" : "package:message-output", {value:value});
    };
    root.ToolPkg.state = Object.freeze({
        get: function (key) { return invokeHost("package:state", {action: "get", key: key}); },
        put: function (key, value, expectedRevision) { return invokeHost("package:state", {action: "put", key: key, value: value, expectedRevision: expectedRevision}); },
        remove: function (key, expectedRevision) { return invokeHost("package:state", {action: "remove", key: key, expectedRevision: expectedRevision}); }
    });
    root.getLang = function () { return "zh"; };
    root.Tools = Object.freeze({ call: async function (name, args) { return invokeHost(name, args); } });
    function resolve(path, parent) {
        const base = path.startsWith(".") ? parent.split("/").slice(0, -1).concat(path.split("/")) : path.split("/");
        const segments = [];
        for (const part of base) {
            if (!part || part === ".") continue;
            if (part === "..") {
                if (!segments.length) throw new Error("PACKAGE_IMPORT_OUTSIDE_ROOT");
                segments.pop();
            } else segments.push(part);
        }
        const name = segments.join("/");
        return Object.prototype.hasOwnProperty.call(root.__nexusScripts, name) ? name : name + ".js";
    }
    function load(path, parent) {
        const name = resolve(path, parent || "");
        if (cache[name]) return cache[name].exports;
        if (!Object.prototype.hasOwnProperty.call(root.__nexusScripts, name)) throw new Error("PACKAGE_MODULE_UNAVAILABLE:" + name);
        const module = { exports: {} };
        cache[name] = module;
        const require = function (relative) { return load(String(relative), name); };
        new Function("module", "exports", "require", root.__nexusScripts[name])(module, module.exports, require);
        return module.exports;
    }
    root.__nexusInvoke = function (entry, name, args) {
        Promise.resolve().then(async function () {
            if (root.__nexusMainEntry) {
                const main = load(root.__nexusMainEntry);
                if (typeof main.registerToolPkg !== "function") throw new Error("PACKAGE_REGISTRATION_EXPORT_REQUIRED");
                await main.registerToolPkg({toolPkgId: root.__nexusPackageId});
            }
            if (name === "__nexus_registration__") return { registrations: registrations };
            if (name === "__nexus_ui__") {
                const module = args.screenCallback ? null : load(entry);
                const screen = args.screenCallback ? callbacks[args.screenCallback] : module.default || module.Screen;
                if (typeof screen !== "function") throw new Error("PACKAGE_UI_SCREEN_REQUIRED");
                function canonical(value) {
                    if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
                    if (value && typeof value === "object") return "{" + Object.keys(value).sort().map(key => JSON.stringify(key) + ":" + canonical(value[key])).join(",") + "}";
                    return JSON.stringify(value);
                }
                const effects = [];
                function context(state, memo, preview) {
                    const bundle = OperitComposeDslRuntime.createContext({state: state, memo: memo, packageName: root.__nexusPackageId,
                        toolPkgId: root.__nexusPackageId, uiModuleId: args.moduleId, moduleSpec: args.moduleSpec || {}});
                    bundle.ctx.getEnv = root.getEnv;
                    bundle.ctx.setEnv = function (key, value) { if (preview) throw new Error("PACKAGE_UI_PREVIEW_READ_ONLY"); return invokeHost("package:ui-environment", {name: key, value: value}); };
                    bundle.ctx.setEnvs = function (values) { if (preview) throw new Error("PACKAGE_UI_PREVIEW_READ_ONLY"); return invokeHost("package:ui-environment", {values: values}); };
                    bundle.ctx.listRoutes = function () { return args.routes || []; };
                    bundle.ctx.getHostRoutes = function () { return []; };
                    bundle.ctx.createWebViewController = function () { throw new Error("PACKAGE_UI_WEBVIEW_NOT_ADAPTED"); };
                    bundle.ctx.navigate = function (route, params) { if (!preview) effects.push({type: "navigate", route: route, arguments: params || {}}); };
                    bundle.ctx.showToast = function (message) { if (!preview) effects.push({type: "notice", text: String(message).slice(0,500)}); };
                    return bundle;
                }
                let bundle = context(args.state || {}, args.memo || {});
                async function renderScreen(bundle) {
                    if (root.__nexusUiStream) invokeHost("package:ui-render", {phase:"begin"});
                    try { return await screen(bundle.ctx); }
                    finally { if (root.__nexusUiStream) invokeHost("package:ui-render", {phase:"end"}); }
                }
                const tree = await renderScreen(bundle);
                if (args.actionId) {
                    if (canonical(tree) !== canonical(args.expectedTree)) throw new Error("PACKAGE_UI_CHANGED_REFRESH_REQUIRED");
                    let previews = Promise.resolve(), previewError = null, lastPreviewAt = -Infinity, active = true;
                    const unsubscribe = root.__nexusUiStream ? bundle.subscribeStateChange(function () {
                        if (!active || Date.now() - lastPreviewAt < 80) return;
                        lastPreviewAt = Date.now();
                        let state, memo;
                        try {
                            serializable(bundle.state, 0); serializable(bundle.memo, 0);
                            state = JSON.parse(JSON.stringify(bundle.state)); memo = JSON.parse(JSON.stringify(bundle.memo));
                        } catch (error) { previewError = error; return; }
                        previews = previews.then(async function () {
                            if (!active || previewError) return;
                            const snapshot = context(state, memo, true);
                            const frameTree = await renderScreen(snapshot);
                            if (active) invokeHost("package:ui-frame", {tree:frameTree,state:snapshot.state,memo:snapshot.memo,effects:[]});
                        }).catch(function (error) { previewError = error; });
                    }) : function () {};
                    try {
                        await bundle.invokeAction(args.actionId, args.payload);
                        await bundle.flushStateChanges();
                        await previews;
                        if (previewError) throw previewError;
                    } finally { active = false; unsubscribe(); }
                    bundle = context(bundle.state, bundle.memo);
                }
                const finalTree = args.actionId ? await renderScreen(bundle) : tree;
                function serializable(value, depth) {
                    if (depth > 24) throw new Error("PACKAGE_UI_STATE_DEPTH_LIMIT");
                    if (typeof value === "function" || typeof value === "symbol" || typeof value === "bigint") throw new Error("PACKAGE_UI_STATE_MUST_BE_JSON");
                    if (value && typeof value === "object") for (const key of Object.keys(value)) {
                        if (["__proto__", "prototype", "constructor"].includes(key)) throw new Error("PACKAGE_UI_STATE_KEY_INVALID");
                        serializable(value[key], depth + 1);
                    }
                }
                serializable(bundle.state, 0); serializable(bundle.memo, 0);
                return {tree: finalTree, state: bundle.state, memo: bundle.memo, effects: effects};
            }
            if (name === "__nexus_callback__") {
                let callback = callbacks[args.key];
                if (!callback && namedCallbacks[args.key]) {
                    callback = load(root.__nexusMainEntry || entry);
                    for (const field of namedCallbacks[args.key].split(".")) {
                        if (["__proto__", "prototype", "constructor"].includes(field)) throw new Error("PACKAGE_CALLBACK_EXPORT_INVALID");
                        callback = callback && callback[field];
                    }
                }
                if (typeof callback !== "function") throw new Error("PACKAGE_CALLBACK_UNAVAILABLE");
                return callback(args.event || {eventPayload:{}});
            }
            const exports = load(entry);
            if (typeof exports[name] !== "function") throw new Error("PACKAGE_EXPORT_UNAVAILABLE:" + name);
            return exports[name](args);
        }).then(root.complete, function (error) {
            root.__nexusResult = { done: true, ok: false, code: "PACKAGE_SCRIPT_ERROR", message: String(error && error.message || error).slice(0, 500) };
        });
    };
})(globalThis);
