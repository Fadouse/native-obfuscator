# Native backend performance analysis (Calc benchmark)

## Benchmark recap
The provided `Calc` benchmark performs 10 000 iterations of three methods:
1. `call(100)` – 100 levels of recursion incrementing the static `count` field at the base case.
2. `runAdd()` – a tight floating-point loop up to 100.1 with increments of 0.99.
3. `runStr()` – repeated string concatenation until the length exceeds 101 characters.

Empirical timings with VM virtualization disabled show ~640 ms without control-flow flattening and ~762 ms with flattening enabled, so the native translation is still slower than desired.

## Where the time is spent

### 1. JNI round-trips for every field increment
With virtualization disabled, `MethodProcessor` emits the state-machine based native method body. Every Java `getstatic`/`putstatic` becomes an explicit JNI call (`GetStatic*Field`, `SetStatic*Field`) in the generated snippets.【F:obfuscator/src/main/resources/sources/cppsnippets.properties†L254-L320】 The field handler wraps each access with cache checks that rely on `env->IsSameObject` and mutex locking even when the cached weak global reference is valid.【F:obfuscator/src/main/java/by/radioegor146/instructions/FieldHandler.java†L15-L56】 As a result, each `++count` in `call(int)` issues two JNI calls and multiple cache checks. Because the recursion depth is 100, a single invocation of `call(100)` performs roughly 200 JNI transitions; 10 000 loop iterations therefore execute on the order of two million JNI field operations, dominating total time.

### 2. Per-invocation scaffolding overhead
The fallback state-machine emitter always allocates `jvalue` arrays for operand stack/locals and instantiates a `std::unordered_set<jobject>` to track references, even if the method never touches objects.【F:obfuscator/src/main/java/by/radioegor146/MethodProcessor.java†L610-L707】 For `call(int)` and `runAdd()` this means every recursive frame pays for constructing and later destroying an empty hash set, causing substantial allocator churn. The dispatcher also records per-instruction comments and emits state labels, adding extra branches when control-flow flattening is enabled.【F:obfuscator/src/main/java/by/radioegor146/MethodProcessor.java†L647-L720】 This explains the ~120 ms slowdown observed when flattening is toggled on: more state transitions and branch mispredictions on top of the existing JNI work.

### 3. High cost of translated object operations in `runStr()`
`runStr()` triggers repeated string concatenations. Each `NEW`, `INVOKESPECIAL`, and `INVOKEVIRTUAL` is lowered to cache-checked JNI calls (`CallNonvirtualObjectMethod`, `CallObjectMethod`, etc.), guarded by the same `env->IsSameObject` mutex pattern as fields and followed by method-ID lookups and argument marshaling.【F:obfuscator/src/main/java/by/radioegor146/instructions/MethodHandler.java†L151-L220】【F:obfuscator/src/main/resources/sources/cppsnippets.properties†L413-L449】 Although method/field IDs are cached after the first hit, the guard code and JNI transitions remain on the hot path for every concatenation. This keeps string-heavy workloads far from native performance even with string obfuscation disabled.

### 4. Virtual machine pipeline is bypassed but its guards still run
The compiler only emits micro-VM bytecode when virtualization is enabled; otherwise it falls back to the generic dispatcher described above.【F:obfuscator/src/main/java/by/radioegor146/MethodProcessor.java†L194-L224】 Because virtualization was disabled during the benchmark, the micro VM (`micro_vm.cpp`) did not execute, so the remaining overhead is entirely due to the JNI-heavy state machine and CFG flattening logic.

## Key takeaways and mitigation ideas
* **Reduce JNI crossings for simple fields.** For arithmetic counters such as `count`, consider emitting direct memory accesses when the field is in the same class module or caching the static value in a native shadow variable to batch updates before flushing through JNI.
* **Avoid allocating reference-tracking sets when unnecessary.** A compile-time escape analysis flag (e.g., only create `refs` if the method actually touches reference types) would eliminate millions of redundant hash-set constructions on primitive-only paths.
* **Let control-flow flattening be selective.** Restrict flattening to code that truly benefits from obfuscation. For tight arithmetic loops the additional dispatcher hops cost more than they obfuscate.
* **Batch string operations or hand off to the JVM sooner.** Methods like `runStr()` are dominated by repeated JNI calls; detecting common StringBuilder patterns and delegating them back to Java would avoid expensive native ↔ JVM transitions.

Addressing these areas should yield much larger gains than toggling individual obfuscation switches in isolation.
