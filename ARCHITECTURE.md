# Logging Architecture Overview

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           OpenFeature Kotlin SDK                            │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                        OpenFeatureAPI                                 │ │
│  │                                                                       │ │
│  │  - Hooks: List<Hook<*>>                                              │ │
│  │  - Logger: Logger (NoOpLogger by default)                            │ │
│  │  - setLogger(logger: Logger)                                         │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                    │                                        │
│                                    │ registers                              │
│                                    ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                         LoggingHook<T>                                │ │
│  │                                                                       │ │
│  │  + before(ctx, hints)     → logs "evaluation starting"              │ │
│  │  + after(ctx, details)    → logs "evaluation completed"             │ │
│  │  + error(ctx, error)      → logs "evaluation error"                 │ │
│  │  + finallyAfter(ctx, ...)→ logs "evaluation finalized"              │ │
│  │                                                                       │ │
│  │  - logger: Logger                                                    │ │
│  │  - logEvaluationContext: Boolean = false (privacy-first)            │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                    │                                        │
│                                    │ uses                                   │
│                                    ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                      Logger Interface                                 │ │
│  │                                                                       │ │
│  │  + debug(message, throwable?)                                        │ │
│  │  + info(message, throwable?)                                         │ │
│  │  + warn(message, throwable?)                                         │ │
│  │  + error(message, throwable?)                                        │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                    △                                        │
│                                    │ implements                             │
│                                    │                                        │
│  ┌─────────────────────────────────┴─────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │ │
│  │  │ NoOpLogger  │  │ TestLogger  │  │ Slf4jLogger │  │TimberLogger │ │ │
│  │  │  (default)  │  │  (testing)  │  │    (JVM)    │  │  (Android)  │ │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                    △                                        │
│                                    │ created by                             │
│                                    │                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                      LoggerFactory (expect)                           │ │
│  │                                                                       │ │
│  │  + getLogger(tag: String): Logger                                    │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                    △                                        │
│                                    │ platform-specific implementations      │
│          ┌─────────────────────────┼─────────────────────────┐            │
│          │                         │                         │            │
│  ┌───────▼──────┐  ┌───────▼──────┐  ┌───────▼──────┐  ┌───▼──────────┐ │
│  │   Android    │  │     JVM      │  │  JavaScript  │  │    Native    │ │
│  │              │  │              │  │              │  │              │ │
│  │ AndroidLogger│  │  JvmLogger   │  │   JsLogger   │  │ NativeLogger │ │
│  │              │  │      OR      │  │              │  │              │ │
│  │Uses Android  │  │  Slf4jLogger │  │Uses console  │  │Uses println  │ │
│  │   Log API    │  │(auto-detect) │  │     API      │  │              │ │
│  │              │  │              │  │              │  │              │ │
│  │ → Logcat     │  │ → Console/   │  │ → Browser/   │  │ → stdout     │ │
│  │              │  │   SLF4J      │  │   Node.js    │  │              │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Data Flow Diagram

```
User Code
    │
    ├─ OpenFeatureAPI.addHooks(listOf(LoggingHook<Any>(logger)))
    │
    └─ client.getBooleanValue("my-flag", false)
           │
           ▼
    ┌──────────────────────────────────────────┐
    │   Flag Evaluation Lifecycle              │
    │                                          │
    │  1. BEFORE                               │
    │     │                                    │
    │     ├─► LoggingHook.before()            │
    │     │      │                             │
    │     │      └─► logger.debug()           │
    │     │              │                     │
    │     │              ▼                     │
    │     │        "Flag evaluation starting" │
    │     │                                    │
    │  2. PROVIDER EVALUATION                 │
    │     │                                    │
    │     ├─► Provider.getBooleanEvaluation() │
    │     │                                    │
    │  3. AFTER (if success)                  │
    │     │                                    │
    │     ├─► LoggingHook.after()             │
    │     │      │                             │
    │     │      └─► logger.debug()           │
    │     │              │                     │
    │     │              ▼                     │
    │     │        "Flag evaluation completed"│
    │     │                                    │
    │  3. ERROR (if failure)                  │
    │     │                                    │
    │     ├─► LoggingHook.error()             │
    │     │      │                             │
    │     │      └─► logger.error()           │
    │     │              │                     │
    │     │              ▼                     │
    │     │        "Flag evaluation error"    │
    │     │                                    │
    │  4. FINALLY                              │
    │     │                                    │
    │     └─► LoggingHook.finallyAfter()      │
    │            │                             │
    │            └─► logger.debug()           │
    │                    │                     │
    │                    ▼                     │
    │            "Flag evaluation finalized"  │
    │                                          │
    └──────────────────────────────────────────┘
           │
           ▼
    Return FlagEvaluationDetails
           │
           ▼
    User Code receives value
```

## Platform Selection Flow

```
                    User calls LoggerFactory.getLogger("tag")
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │  Which Platform?              │
                    └───────────────┬───────────────┘
                                    │
                ┌───────────────────┼───────────────────┐
                │                   │                   │
                ▼                   ▼                   ▼
         ┌────────────┐      ┌────────────┐      ┌────────────┐
         │  Android?  │      │    JVM?    │      │JavaScript? │
         └─────┬──────┘      └─────┬──────┘      └─────┬──────┘
               │                   │                   │
               │ YES               │ YES               │ YES
               ▼                   ▼                   ▼
      ┌────────────────┐   ┌──────────────┐   ┌──────────────┐
      │ AndroidLogger  │   │ Try SLF4J?   │   │   JsLogger   │
      │                │   └──────┬───────┘   │              │
      │ Uses Android   │          │           │ Uses console │
      │   Log API      │    ┌─────┴─────┐    │     API      │
      │                │    │           │    │              │
      │ → Logcat       │    │ Found?    │    │ → Browser/   │
      └────────────────┘    ▼           ▼    │   Node.js    │
                       ┌─────────┐ ┌────────┐ └──────────────┘
                       │   YES   │ │   NO   │
                       └────┬────┘ └───┬────┘
                            │          │
                            ▼          ▼
                    ┌────────────┐ ┌────────────┐
                    │Slf4jLogger │ │ JvmLogger  │
                    │            │ │            │
                    │ Delegates  │ │Uses stdout/│
                    │ to SLF4J   │ │   stderr   │
                    │  backend   │ │            │
                    │            │ │ → Console  │
                    └────────────┘ └────────────┘
```

## Hook Registration Levels

```
┌─────────────────────────────────────────────────────────────┐
│                      API LEVEL                              │
│  OpenFeatureAPI.addHooks(listOf(LoggingHook(...)))         │
│                                                             │
│  Scope: All flag evaluations from all clients              │
│  Use Case: Global debugging, production monitoring          │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ creates
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     CLIENT LEVEL                            │
│  val client = OpenFeatureAPI.getClient()                   │
│  client.addHooks(listOf(LoggingHook(...)))                 │
│                                                             │
│  Scope: All evaluations from this client                   │
│  Use Case: Per-component logging, specific feature area    │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ evaluates
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  INVOCATION LEVEL                           │
│  client.getBooleanValue(                                   │
│      "my-flag",                                            │
│      false,                                                │
│      FlagEvaluationOptions(                                │
│          hooks = listOf(LoggingHook(...))                  │
│      )                                                     │
│  )                                                         │
│                                                             │
│  Scope: Only this specific evaluation                      │
│  Use Case: Debugging specific flag, troubleshooting        │
└─────────────────────────────────────────────────────────────┘
```

## Privacy Control Flow

```
                    LoggingHook Created
                            │
                            ▼
              ┌─────────────────────────────┐
              │ logEvaluationContext param  │
              │                             │
              │  true  │  false (DEFAULT)   │
              └────┬───┴──────┬─────────────┘
                   │          │
    ┌──────────────┘          └──────────────┐
    │                                        │
    ▼                                        ▼
┌─────────────┐                      ┌─────────────┐
│  ENABLED    │                      │  DISABLED   │
│             │                      │  (privacy)  │
│ Context     │                      │             │
│ will be     │                      │ Context     │
│ logged      │                      │ NOT logged  │
└──────┬──────┘                      └──────┬──────┘
       │                                    │
       │        ┌──────────────────────────┬┘
       │        │                          │
       │        │  Per-evaluation override │
       │        │  via hookHints           │
       │        │                          │
       ▼        ▼                          ▼
┌──────────────────┐              ┌──────────────────┐
│ hints contain    │              │ hints contain    │
│ "logEvaluation   │              │ "logEvaluation   │
│ Context" = true? │              │ Context" = false?│
└────┬─────────────┘              └────┬─────────────┘
     │                                 │
     │ YES                             │ YES
     ▼                                 ▼
┌─────────────┐                  ┌─────────────┐
│   LOG IT    │                  │ DON'T LOG   │
│             │◄─────────────────┤             │
│ context={   │      NO          │ (privacy    │
│ ...details  │                  │ protected)  │
└─────────────┘                  └─────────────┘
```

## Log Message Structure

```
┌──────────────────────────────────────────────────────────────┐
│           Flag evaluation starting:                          │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Required Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ flag='my-flag'                  ← Flag key           │  │
│  │ type=BOOLEAN                     ← Value type         │  │
│  │ defaultValue=false               ← Fallback value     │  │
│  │ provider='MyProvider'            ← Provider name      │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Optional Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ client='MyClient'                ← Client name        │  │
│  │ context={...}                    ← If enabled         │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│           Flag evaluation completed:                         │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Required Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ flag='my-flag'                  ← Flag key           │  │
│  │ value=true                       ← Resolved value     │  │
│  │ provider='MyProvider'            ← Provider name      │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Optional Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ variant='on'                     ← Variant name       │  │
│  │ reason='TARGETING_MATCH'         ← Evaluation reason  │  │
│  │ context={...}                    ← If enabled         │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│           Flag evaluation error:                             │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Required Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ flag='my-flag'                  ← Flag key           │  │
│  │ type=BOOLEAN                     ← Value type         │  │
│  │ defaultValue=false               ← Fallback used      │  │
│  │ provider='MyProvider'            ← Provider name      │  │
│  │ error='Connection timeout'       ← Error message      │  │
│  │ [EXCEPTION STACK TRACE]          ← Full exception     │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Optional Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ context={...}                    ← If enabled         │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│           Flag evaluation finalized:                         │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Required Fields                                       │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ flag='my-flag'                  ← Flag key           │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Optional Fields (if error occurred)                  │  │
│  ├───────────────────────────────────────────────────────┤  │
│  │ errorCode=PROVIDER_NOT_READY     ← Error code        │  │
│  │ errorMessage='...'               ← Error description  │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

## Dependency Graph

```
┌─────────────────────────────────────────────────────────────┐
│                  Kotlin Multiplatform SDK                   │
│                    (NO DEPENDENCIES)                        │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ provides
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    Logger Interface                         │
│                   (commonMain)                              │
└─────────────────────────────────────────────────────────────┘
                            │
                ┌───────────┼───────────┐
                │           │           │
                ▼           ▼           ▼
         ┌──────────┐ ┌─────────┐ ┌─────────┐
         │ Platform │ │Platform │ │Platform │
         │ Loggers  │ │ Loggers │ │ Loggers │
         │          │ │         │ │         │
         │(Android) │ │  (JVM)  │ │  (JS)   │
         │    NO    │ │   NO    │ │   NO    │
         │   DEPS   │ │  DEPS   │ │  DEPS   │
         └──────────┘ └─────────┘ └─────────┘
                            │
                            │ optional
                            ▼
                ┌───────────────────────┐
                │  Framework Adapters   │
                │                       │
                │ SLF4J (compileOnly)   │
                │ Timber (compileOnly)  │
                │                       │
                │ NOT REQUIRED          │
                │ User provides if      │
                │ they want them        │
                └───────────────────────┘
```

## Summary

### Key Architectural Decisions

1. **Kotlin Multiplatform expect/actual**
   - Clean separation of platform-specific code
   - Common interface shared across all platforms
   - Type-safe platform detection at compile time

2. **Zero Runtime Dependencies**
   - SDK remains lightweight
   - No transitive dependencies forced on users
   - Framework adapters are optional (compileOnly)

3. **Privacy-First Design**
   - Context logging opt-in by default
   - Multiple levels of control (constructor + hints)
   - Clear warnings in documentation

4. **Hook-Based Architecture**
   - Follows OpenFeature specification
   - Separates logging from core evaluation logic
   - Supports multiple registration levels (API/Client/Invocation)

5. **Platform-Native Implementations**
   - Android → android.util.Log (Logcat)
   - JVM → System.out/err + SLF4J auto-detection
   - JavaScript → console API
   - Native → println

6. **Framework Integration**
   - SLF4J auto-detected on JVM
   - Timber adapter for Android
   - Easy to add custom loggers

This architecture achieves:
- ✅ Multiplatform support
- ✅ Zero dependencies
- ✅ Privacy protection
- ✅ Framework flexibility
- ✅ Specification compliance
- ✅ Excellent developer experience
