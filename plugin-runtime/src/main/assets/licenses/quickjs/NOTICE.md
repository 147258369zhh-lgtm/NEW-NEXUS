# Native JavaScript Runtime

The JNI wrapper, Kotlin runtime, compatibility script, JavaScript Tools facade
and versioned ToolPkg API runtime are adapted from
[Operit](https://github.com/AAswordman/Operit), revision
`16cffb98f52a11dc77bd0a789ffa0b01ed398ee4`, under LGPL-3.0.
Their license is in `LICENSE-LGPL-3.0.txt`; its incorporated GPL terms are in
`LICENSE-GPL-3.0.txt`. These components are not relicensed under the app's license.
The 31 scripts under `app/src/main/assets/packages/operit/` are from the same
Operit revision and retain that project's LGPL-3.0 license.
`app/src/main/assets/packages/operit-http.js` is the original Operit OkHttp3
JavaScript facade, connected to Nexus's bounded REST transport.

QuickJS is vendored at Bellard upstream revision
`e585640ffa2cf88ce7c2d587583c6fe15928ec2f` under its MIT license in
`src/main/cpp/quickjs/LICENSE`. The source archive SHA-256 is
`56d9d9d7e206ea77a1a0badc560536e455d50639db6c0cc6e6ca8a8c5a12d47f`.

Nexus adaptations: pinned/offline native build, runtime memory/stack limits,
restricted host dispatch in an isolated Android service. No upstream Java
reflection bridge is exposed to packages. Sources and build instructions are
distributed with this repository so this library can be modified and rebuilt.

The unmodified `JsComposeDslBridge.kt` context/state/modifier/node bridge is from
the same Operit revision under LGPL-3.0, imported SHA-256
`9e87eebc203e0b1d5a881f9daf1a3fbf7e19515b3b731d392a0c0aacbaa3ec06`.
Nexus supplies the isolated invocation adapter and Miuix renderer without
exposing the original unrestricted Java bridge to UI scripts.

The numeric ToolPkg WASM JNI bridge is also adapted from the same Operit
revision under LGPL-3.0. Nexus adds opaque handle ownership, an invocation-local
bounded allocation pool, instruction metering and cancellation. WASI, native
host imports, JIT and native AOT modules are not enabled.

WAMR (WebAssembly Micro Runtime) is pinned to WAMR-2.4.5, revision
`25bd7eb63e828e4bd242cc9b38d260b4b31c6605`. Its source archive SHA-256 is
`4fbc793e6a1171c969cb6f8094feb94b9338ea00944676a218a7e4aa3afac77a`.
The vendored `src/main/cpp/wamr/core` and `build-scripts` retain the upstream
Apache-2.0 WITH LLVM-exception terms in `wamr/LICENSE` and its attribution
file. These files are also included in the APK assets under `licenses/quickjs`.
