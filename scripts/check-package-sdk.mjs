import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bootstrap = fs.readFileSync(path.join(root, 'app/src/main/assets/packages/nexus-package-sdk.js'), 'utf8');
const time = fs.readFileSync(path.join(root, 'app/src/main/assets/packages/operit/time.js'), 'utf8');
function kotlinScript(name) {
  const text = fs.readFileSync(path.join(root, 'plugin-runtime/src/main/kotlin/com/ai/assistance/operit/core/tools/javascript', name), 'utf8');
  const start = text.indexOf('"""') + 3, end = text.lastIndexOf('"""');
  const lines = text.slice(start, end).replace(/^\r?\n/, '').replace(/\r?\n\s*$/, '').split(/\r?\n/);
  const indent = Math.min(...lines.filter(line => line.trim()).map(line => line.match(/^ */)[0].length));
  return lines.map(line => line.slice(indent)).join('\n');
}
const versioning = kotlinScript('JsToolPkgApiRuntime.kt'), tools = kotlinScript('JsTools.kt');
const http = fs.readFileSync(path.join(root, 'app/src/main/assets/packages/operit-http.js'), 'utf8');
async function execute(scripts, entry, name, args, host, mainEntry, providerMode = false, uiStream = false) {
  const context = vm.createContext({ NativeInterface: { nexusInvoke: raw => JSON.stringify(host(JSON.parse(raw))) } });
  context.__nexusProviderMode = providerMode;
  context.__nexusUiStream = uiStream;
  vm.runInContext(bootstrap, context);
  vm.runInContext(versioning, context);
  vm.runInContext(tools, context);
  vm.runInContext(http, context);
  if (name === '__nexus_ui__') vm.runInContext(kotlinScript('JsComposeDslBridge.kt'), context);
  vm.runInContext('Tools.call = async (name,args) => globalThis.__nexusNativeCall(name,args)', context);
  context.__nexusScripts = scripts;
  context.__nexusMainEntry = mainEntry;
  context.__nexusPackageId = 'fixture.bundle';
  vm.runInContext(`__nexusInvoke(${JSON.stringify(entry)}, ${JSON.stringify(name)}, ${JSON.stringify(args)})`, context);
  await new Promise(setImmediate);
  return JSON.parse(JSON.stringify(context.__nexusResult));
}
const clock = await execute({ 'main.js': time }, 'main.js', 'format_time', {}, () => { throw Error('Unexpected native access'); });
assert.equal(clock.ok, true);
assert.match(clock.value.time24h, /^\d{2}:\d{2}:\d{2}$/);
const allowed = await execute({ 'main.js': 'exports.run = async p => Tools.call("device_status", p);' }, 'main.js', 'run', { probe: true }, request => {
  assert.equal(request.tool, 'device_status'); assert.equal(request.arguments.probe, true);
  return { ok: true, battery: 80 };
});
assert.equal(allowed.value.battery, 80);
const denied = await execute({ 'main.js': 'exports.run = async () => Tools.call("terminal", {});' }, 'main.js', 'run', {}, () => ({ ok: false, code: 'PACKAGE_TOOL_NOT_GRANTED' }));
assert.equal(denied.ok, false); assert.equal(denied.message, 'PACKAGE_TOOL_NOT_GRANTED');
const modules = await execute({ 'main.js': 'exports.run = () => require("./parts/answer").value;', 'parts/answer.js': 'exports.value = 42;' }, 'main.js', 'run', {}, () => ({}));
assert.equal(modules.value, 42);
const crossref = fs.readFileSync(path.join(root, 'app/src/main/assets/packages/operit/crossref.js'), 'utf8');
const search = await execute({ 'main.js': crossref }, 'main.js', 'search_by_doi', { doi: '10.fixture/paper' }, request => {
  assert.equal(request.tool, 'operit:http_request');
  assert.match(request.arguments.url, /api.crossref.org\/works\/10.fixture/);
  return { ok: true, data: { statusCode: 200, content: JSON.stringify({ status: 'ok', message: { title: ['Fixture research'], DOI: '10.fixture/paper', author: [{given:'A',family:'Researcher'}], published: {'date-parts':[[2026,11,11]]}, URL: 'https://example.com/paper' } }) } };
});
assert.equal(search.ok, true);
assert.match(JSON.stringify(search.value), /Fixture research/);
const files = await execute({ 'main.js': 'exports.run = async () => Tools.Files.write("out.txt", "body", false, "android");' }, 'main.js', 'run', {}, request => {
  assert.equal(request.tool, 'operit:write_file'); assert.equal(request.arguments.append, 'false');
  return { ok: true, data: { path: 'out.txt', bytes_written: 4 } };
});
assert.equal(files.value.bytes_written, 4);
let pages = 0;
const paged = await execute({ 'main.js': 'exports.run = async () => Tools.Files.read("long.txt");' }, 'main.js', 'run', {}, request => {
  pages++;
  assert.equal(request.tool, pages === 1 ? 'operit:read_file_full' : 'operit:read_file_chunk');
  if (pages > 1) { assert.equal(request.arguments.offset_bytes, 3); assert.equal(request.arguments.expected_revision, 'revision-1'); }
  return { ok: true, data: { content: pages === 1 ? 'one' : 'two', bytes_read: 3, next_offset_bytes: pages * 3, revision: 'revision-1', truncated: pages === 1 } };
});
assert.equal(paged.value.content, 'onetwo'); assert.equal(pages, 2);
const sidebar = fs.readFileSync(path.join(root, 'scripts/fixtures/operit/sidebar-bing-main.js'), 'utf8');
const registration = await execute({'main.js': sidebar}, 'main.js', '__nexus_registration__', {}, () => { throw Error('Registration accessed native capabilities'); }, 'main.js');
assert.equal(registration.ok, true);
const declared = registration.value.registrations[0];
assert.equal(declared.kind, 'NavigationEntry'); assert.equal(declared.title.zh, '打开 Bing');
const callback = await execute({'main.js': sidebar}, 'main.js', '__nexus_callback__', {key:declared.action.nexus_callback,event:{eventPayload:{entryId:declared.id}}}, request => {
  assert.equal(request.tool, 'operit:browser_navigate'); assert.equal(request.arguments.url, 'https://www.bing.com');
  return {ok:true,data:{url:request.arguments.url}};
}, 'main.js');
assert.equal(callback.value.sourceEntryId, declared.id);
const hookSource = 'exports.named = e => ({processedInput:e.eventPayload.processedInput+" named"}); exports.registerToolPkg = () => {' +
  'ToolPkg.registerPromptInputHook({id:"one",function:"named"}); ToolPkg.registerToolboxUiModule({id:"screen",screen:"ui/settings.ui.js"});' +
  'ToolPkg.registerSystemPromptComposeHook({id:"nested",handlers:{before:()=>"before",after:()=>"after"},function:e=>({systemPrompt:e.eventPayload.systemPrompt+" adapted"})}); };';
const hookRegistration = await execute({'main.js':hookSource}, 'main.js', '__nexus_registration__', {}, () => {throw Error('No native call expected');}, 'main.js');
assert.equal(hookRegistration.ok, true);
const hookRows = hookRegistration.value.registrations;
assert.equal(hookRows[0].function.nexus_callback, 'PromptInputHook/one/function');
assert.equal(hookRows[1].screen, 'ui/settings.ui.js');
assert.notEqual(hookRows[2].handlers.before.nexus_callback, hookRows[2].handlers.after.nexus_callback);
const namedHook = await execute({'main.js':hookSource}, 'main.js', '__nexus_callback__', {key:hookRows[0].function.nexus_callback,event:{eventName:'before_process',eventPayload:{processedInput:'original'}}}, () => ({}), 'main.js');
assert.equal(namedHook.value.processedInput, 'original named');
const nestedHook = await execute({'main.js':hookSource}, 'main.js', '__nexus_callback__', {key:hookRows[2].handlers.before.nexus_callback,event:{}}, () => ({}), 'main.js');
assert.equal(nestedHook.value, 'before');
console.log('Package SDK: original time/Crossref scripts, Operit versioned Tools and OkHttp, file facade, module loading, host dispatch and denial passed. JS SDK evidence only.');

const wasm = vm.createContext({ NativeInterface: { __call: (method, raw) => {
  assert.equal(method, 'nexusWasm');
  const [encoded] = JSON.parse(raw), request = JSON.parse(encoded);
  assert.equal(request.module, 'core');
  if (request.export === 'i64') {
    assert.equal(request.args[0].value, '9223372036854775807');
    return JSON.stringify({success:true,results:[{type:'i64',value:'9223372036854775807'}]});
  }
  if (request.export === 'failed') return JSON.stringify({success:false,message:'instruction limit exceeded'});
  return JSON.stringify({success:true,results:[{type:'i32',value:request.args[0].value + 1}]});
} } });
vm.runInContext(bootstrap, wasm);
assert.equal(await vm.runInContext('ToolPkg.wasm.call("core", "increment", [{type:"i32",value:41}])', wasm), 42);
assert.equal(await vm.runInContext('ToolPkg.wasm.call("core", "i64", [{type:"i64",value:9223372036854775807n}])', wasm), '9223372036854775807');
await assert.rejects(vm.runInContext('ToolPkg.wasm.call("core", "i64", [{type:"i64",value:9007199254740992}])', wasm), /I64_REQUIRES_STRING/);
await assert.rejects(vm.runInContext('ToolPkg.wasm.call("core", "failed", [])', wasm), /instruction limit exceeded/);
console.log('Package WASM JavaScript ABI: numeric conversion, exact i64, raw bridge envelope and native-error propagation passed. This script does not execute the native engine.');

let stateRevision = 0, stateValue = null;
const state = await execute({'main.js': 'exports.run=()=>{const before=ToolPkg.state.get("planning");return ToolPkg.state.put("planning",true,before.revision);};'},
  'main.js', 'run', {}, request => {
    assert.equal(request.tool, 'package:state'); assert.equal(request.arguments.key, 'planning');
    if (request.arguments.action === 'put') {
      assert.equal(request.arguments.expectedRevision, stateRevision); stateValue = request.arguments.value; stateRevision++;
    }
    return {ok:true,value:stateValue,revision:stateRevision};
  });
assert.equal(state.value.value, true); assert.equal(state.value.revision, 1);
console.log('Package-private state SDK: real host dispatch and optimistic revision payload passed.');

const uiSource = 'exports.default=ctx=>{const [count,setCount]=ctx.useState("count",0);return ctx.UI.Column({spacing:8},[ctx.UI.Text({text:"Count "+count}),ctx.UI.Button({text:"Increase",onClick:()=>setCount(count+1)})]);};';
const uiFirst = await execute({'ui.js':uiSource}, 'ui.js', '__nexus_ui__', {state:{},memo:{},moduleId:'counter'}, () => {throw Error('UI render requested native tool');});
assert.equal(uiFirst.ok, true); assert.equal(uiFirst.value.tree.children[0].props.text, 'Count 0');
const uiNext = await execute({'ui.js':uiSource}, 'ui.js', '__nexus_ui__', {state:uiFirst.value.state,memo:uiFirst.value.memo,moduleId:'counter',
  actionId:uiFirst.value.tree.children[1].props.onClick.__actionId,payload:null,expectedTree:uiFirst.value.tree}, () => {throw Error('UI action requested native tool');});
assert.equal(uiNext.ok, true); assert.equal(uiNext.value.tree.children[0].props.text, 'Count 1');
const staleUi = await execute({'ui.js':uiSource}, 'ui.js', '__nexus_ui__', {state:{count:2},memo:{},moduleId:'counter',
  actionId:uiFirst.value.tree.children[1].props.onClick.__actionId,payload:null,expectedTree:uiFirst.value.tree}, () => ({}));
assert.equal(staleUi.ok, false); assert.match(staleUi.message, /CHANGED_REFRESH_REQUIRED/);
console.log('Reused Compose context: actual screen construction, state callback, rerender and stale-tree rejection passed.');
const originalTranslateDialog = fs.readFileSync(path.join(root, 'scripts/fixtures/operit/message-translation-dialog.ui.js'), 'utf8');
const originalUi = await execute({'ui.js':originalTranslateDialog}, 'ui.js', '__nexus_ui__',
  {state:{message:{content:'A real message to render'}},memo:{},moduleId:'translate'}, () => {throw Error('Initial original dialog performed a native operation');});
assert.equal(originalUi.ok, true); assert.equal(originalUi.value.tree.type, 'AlertDialog');
assert.equal(originalUi.value.tree.props.title, '翻译消息');
assert.equal(originalUi.value.tree.slots.text[0].type, 'LazyColumn');
assert.match(JSON.stringify(originalUi.value.tree), /A real message to render/);
console.log('Pinned upstream message-translation dialog rendered through the reused Compose context. Translation execution is not claimed by this check.');
const originalTranslated = await execute({'ui.js':originalTranslateDialog}, 'ui.js', '__nexus_ui__',
  {state:originalUi.value.state,memo:originalUi.value.memo,moduleId:'translate',actionId:originalUi.value.tree.props.onConfirm.__actionId,
    payload:null,expectedTree:originalUi.value.tree}, request => {
      assert.equal(request.tool,'operit:call_chat_model'); assert.equal(request.arguments.function_type,'TRANSLATION');
      assert.match(request.arguments.turns,/A real message to render/);
      return {ok:true,data:{text:'A controlled translated reply',finishReason:'stop'}};
  });
assert.equal(originalTranslated.ok,true); assert.equal(originalTranslated.value.state.translatedText,'A controlled translated reply');
assert.equal(originalTranslated.value.state.loading,false);
console.log('Pinned original dialog action invoked the real versioned Tools.Chat facade and rerendered a controlled model reply.');

const originalProvider = fs.readFileSync(path.join(root,'scripts/fixtures/operit/custom-ai-provider-main.js'),'utf8');
const providerRegistration = await execute({'main.js':originalProvider},'main.js','__nexus_registration__',{},()=>{throw Error('Native call during registration');},'main.js');
const providerRow = providerRegistration.value.registrations[0];
assert.equal(providerRow.kind,'AiProvider');
assert.equal(providerRow.sendMessage.function.nexus_callback,'AiProvider/example_openai_compatible_provider/sendMessage/function');
let streamRead = 0; const providerChunks = [];
const pluginResponse = await execute({'main.js':originalProvider},'main.js','__nexus_callback__',{
  key:providerRow.sendMessage.function.nexus_callback,event:{eventPayload:{config:{apiEndpoint:'https://example.com/v1/chat/completions',modelName:'fixture',apiKey:'controlled-key'},
    chatHistory:[{kind:'USER',content:'Hello'}]}}
}, request => {
  if(request.tool==='package:provider-http-start') {
    assert.equal(request.arguments.method,'POST');
    assert.equal((typeof request.arguments.body === 'string' ? JSON.parse(request.arguments.body) : request.arguments.body).model,'fixture');
    return {ok:true,statusCode:200,headers:{},contentType:'text/event-stream'};
  }
  if(request.tool==='package:provider-http-next') return ++streamRead===1
    ? {ok:true,done:false,chunk:'data: {"choices":[{"delta":{"content":"Real plugin reply"}}],"usage":{"prompt_tokens":8,"completion_tokens":3}}\n\n'}
    : {ok:true,done:true};
  if(request.tool==='package:provider-output') {if(request.arguments.value.chunk) providerChunks.push(request.arguments.value.chunk);return {ok:true};}
  throw Error('Unexpected provider host call '+request.tool);
},'main.js',true);
assert.equal(pluginResponse.ok,true,pluginResponse.message); assert.equal(pluginResponse.value.text,'Real plugin reply');
assert.deepEqual(providerChunks,['Real plugin reply']); assert.equal(pluginResponse.value.usage.input,8);
console.log('Pinned original custom AI provider: registration, bounded HTTP stream callbacks, intermediate output and final usage parsing passed.');

const widgetScripts = {
  'main.js':fs.readFileSync(path.join(root,'scripts/fixtures/operit/desktop-widget-main.js'),'utf8'),
  'ui/widget_demo/index.ui.js':fs.readFileSync(path.join(root,'scripts/fixtures/operit/desktop-widget-page.ui.js'),'utf8'),
  'ui/widget_demo/widget.ui.js':fs.readFileSync(path.join(root,'scripts/fixtures/operit/desktop-widget-render.ui.js'),'utf8')
};
const widgetRegistration = await execute(widgetScripts,'main.js','__nexus_registration__',{},()=>{throw Error('Widget registration touched native APIs');},'main.js');
assert.equal(widgetRegistration.ok,true,widgetRegistration.message);
const widgetRows = widgetRegistration.value.registrations;
const renderRow = widgetRows.find(row=>row.id==='today_hub_widget');
assert.equal(renderRow.screen.nexus_callback,'UiRoute/today_hub_widget/screen');
assert.equal(widgetRows.find(row=>row.kind==='DesktopWidget').render,renderRow.route);
const widgetScreen = await execute(widgetScripts,'main.js','__nexus_ui__',{
  moduleId:renderRow.id,screenCallback:renderRow.screen.nexus_callback,state:{},memo:{}
},()=>{throw Error('Widget render touched native APIs');},'main.js');
assert.equal(widgetScreen.ok,true,widgetScreen.message);
assert.equal(widgetScreen.value.tree.type,'Column');
assert.match(JSON.stringify(widgetScreen.value.tree),/今日进度/);
assert.equal(widgetScreen.value.tree.children.find(row=>row.type==='LinearProgressIndicator').props.progress>0,true);
const widgetPage = await execute(widgetScripts,'main.js','__nexus_ui__',{
  moduleId:'today_hub',screenCallback:'UiRoute/today_hub/screen',state:{},memo:{}
},()=>{throw Error('Widget page touched native APIs');},'main.js');
assert.equal(widgetPage.ok,true,widgetPage.message);assert.match(JSON.stringify(widgetPage.value.tree),/今日面板/);
console.log('Pinned original desktop widget: function screen registrations, actual summary tree/progress and full route page rendered.');

const liveMessageFrames = [];
const messageProcessorSource = 'exports.registerToolPkg=()=>ToolPkg.registerMessageProcessingPlugin({id:"live",function:async e=>{' +
  'if(e.eventPayload.probeOnly)return {matched:true}; sendIntermediateResult({chunk:"First "});' +
  'await Promise.resolve(); sendIntermediateResult({chunk:"second"}); return {matched:true,text:"First second"};}});';
const liveMessage = await execute({'main.js':messageProcessorSource},'main.js','__nexus_callback__',
  {key:'MessageProcessingPlugin/live/function',event:{eventPayload:{probeOnly:false}}},request=>{
    assert.equal(request.tool,'package:message-output'); liveMessageFrames.push(request.arguments.value.chunk); return {ok:true};
  },'main.js');
assert.equal(liveMessage.ok,true,liveMessage.message);assert.deepEqual(liveMessageFrames,['First ','second']);
assert.equal(liveMessage.value.text,'First second');
console.log('Message processor SDK: intermediate chunks dispatched before the final callback result.');

const uiFrames = [];
const asyncUiSource = 'exports.default=ctx=>{const [loading,setLoading]=ctx.useState("loading",false);return ctx.UI.Column({},[' +
  'ctx.UI.Text({text:loading?"Loading":"Ready"}),ctx.UI.Button({text:"Run",onClick:async()=>{setLoading(true);' +
  'await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); setLoading(false);}})]);};';
const initialAsyncUi = await execute({'ui.js':asyncUiSource},'ui.js','__nexus_ui__',{state:{},memo:{},moduleId:'async'},()=>{throw Error('Initial render native access');});
const completedAsyncUi = await execute({'ui.js':asyncUiSource},'ui.js','__nexus_ui__',{
  state:initialAsyncUi.value.state,memo:initialAsyncUi.value.memo,moduleId:'async',expectedTree:initialAsyncUi.value.tree,
  actionId:initialAsyncUi.value.tree.children[1].props.onClick.__actionId
},request=>{if(request.tool==='package:ui-render')return {ok:true};assert.equal(request.tool,'package:ui-frame');assert.deepEqual(request.arguments.effects,[]);uiFrames.push(request.arguments);return {ok:true};},null,false,true);
assert.equal(completedAsyncUi.ok,true,completedAsyncUi.message);
assert.ok(uiFrames.some(value=>value.tree.children[0].props.text==='Loading'));
assert.equal(completedAsyncUi.value.tree.children[0].props.text,'Ready');
console.log('Compose async action: real intermediate loading tree and final state rendered through the original state listener.');
