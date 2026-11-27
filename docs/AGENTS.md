# مهمة الفريق: Al-Raad (الرعد) — Android Browser + Smart Stream Catcher + Player
أنتم فريق من وكيلين فقط (`Instinct` و `Pally`) تعملان معاً على مستودع `tombad8900/Al-Raad`. اقرأ هذا الملف كاملاً قبل البدء والتزم بدورك.

## 1. المشروع والـ Stack
تطبيق شخصي (Android RTL-first باللغة العربية):
- **متصفح WebView** مع تبويبات، سجل تصفح، وإشارات مرجعية.
- **مانع إعلانات مدمج** (hosts + cosmetic) يعمل offline بالكامل.
- **لاقط ذكي لروابط البث** (`m3u8`/`mpd`/`mp4`/`webm`/`mkv`) من أي صفحة مع التقاط كامل الـ `headers`.
- **مكتبة البثوث المحفوظة (Saved Streams Library):** عند التقاط رابط بث يمكن للمستخدم **تشغيله** أو **نسخه** أو **حفظه في المكتبة**؛ عند الحفظ يظهر في تبويب المكتبة ككارت أنيق ببنر/صورة مصغرة وعنوان وصيغة البث، محفوظاً بكل الـ `headers` (`User-Agent`, `Referer`, `Cookie`, `Origin`...) ورابط الصفحة الأصلية (`sourcePageUrl`) لتشغيله مباشرةً لاحقاً أو تحديث الرابط عند انتهاء صلاحيته.
- **مشغل Media3/ExoPlayer** بواجهة RTL جذابة (إيماءات، PiP، سرعة، جودة، تمرير `headers` فعلياً).
- **سجل الفحص والتشخيص الكامل (Diagnostics & Crash Logger):** يسجّل الأحداث والأعطال مع الاحتفاظ بالسياق التشخيصي غير الحساس، مع **حجب قيم بيانات الاعتماد والأسرار** من التخزين والعرض والنسخ والتصدير: أخطاء WebView وJS console وإصابات AdBlock، اكتشافات StreamSniffer، حالات وتوقفات Media3 وأخطاء 403/Codec، وأعطال التطبيق (`UncaughtExceptionHandler`). مع شاشة في الإعدادات لـ **العرض، الفلترة، النسخ، التصدير كملف (`.log`/`.txt`)، والحذف**.

Stack: Kotlin 2.x, Compose, Material3 (RTL-first), Media3, Room, DataStore, Hilt, Coroutines/Flow, Gradle KTS + Version Catalog. `minSdk 24`, `compileSdk 35`, `JDK 17`.

## 2. توزيع الأدوار (وكيلان فقط)
| الدور | الوكيل | المسؤولية والملكية | التذاكر |
|---|---|---|---|
| **Lead & Builder** | `Instinct` | هيكل المشروع، Contracts & Fakes، كتابة كود التطبيق والاختبارات الوحدوية (`app/`, `core/`, `data/`, `feature/`, `gradle/`)، البناء والاختبار محلياً، ودمج الـ PRs بعد موافقة `Pally` ونجاح CI. | `#1`, `#2`, `#4`, `#5`, `#6`, `#7`, `#8`, `#9`, `#10` |
| **Planner, Reviewer & QA** | `Pally` | إعداد ومراجعة خطط التنفيذ، إعداد CI والـ PR Guard (`.github/`), كتابة اختبارات التكامل والـ QA (`androidTest/`), مراجعة كل PR بدقة تقنية، والتحقق النهائي من معايير الـ MVP. | `#3`, `#11`, مراجعة كل PRs (`#1`–`#10`) |

قاعدة التوقيع: لأن الوكيلين قد يعلقان من نفس الحساب، يجب أن يبدأ كل تعليق بـ `[Instinct]` أو `[Pally]`.

## 3. الـ Contracts المجمدة (يكتبها Instinct في #1)
في `core/common/**/contracts/`:
- `StreamUrl(url: String, format: StreamFormat, source: StreamSource, headers: Map<String, String> = emptyMap(), size: Long? = null)`
- `SavedStream(id: Long = 0, title: String, stream: StreamUrl, thumbnailUrl: String? = null, sourcePageUrl: String? = null, createdAt: Long)`
- `enum StreamFormat { HLS, DASH, MP4, WEBM, MKV, UNKNOWN }`
- `enum StreamSource { NETWORK, JS_BRIDGE, DOM }`
- `interface StreamListener { fun onStreamDetected(stream: StreamUrl) }`
- `interface AdBlocker { suspend fun shouldBlock(url: String): Boolean; fun cosmeticCss(host: String): String }`
- `interface PlayerController { fun play(stream: StreamUrl); fun pause(); fun seekTo(ms: Long); fun release() }` (إلزامي تمرير `stream.headers` إلى Media3 عبر `DefaultHttpDataSource.Factory`).
- `interface DiagnosticsLogger { fun log(category: DiagCategory, level: DiagLevel, message: String, details: Map<String, String> = emptyMap()); fun observeLogs(): Flow<List<DiagEntry>>; suspend fun exportAsText(): String; suspend fun clear() }`
- `interface HistoryRepository / BookmarksRepository / SavedStreamsRepository / SettingsRepository`

في `fakes/`: Fake in-memory لكل interface للاختبارات والتطوير. العقود مجمدة بعد دمج `#1`.

## 4. ترتيب التنفيذ (Waves)
- **Wave 0 (تأسيس):** `Instinct` ينفذ `#1` ويدمجه أولاً -> ثم `Instinct` ينفذ `#2` بالتوازي مع `Pally` في `#3` (CI workflow + PR guard).
- **Wave 1 (الأساس):** `Instinct` ينفذ `#4` (Design System + DiagnosticsLogger) ثم `#5` (Room + DataStore + Repositories).
- **Wave 2 (المميزات):** `Instinct` ينفذ `#6` (AdBlock)، `#7` (Browser + Sniffer)، `#8` (Media3 Player)، `#9` (Library + Diagnostics UI) — مهمة تلو الأخرى بـ PR نظيف لكل Issue، مع مراجعة `Pally` لكل PR.
- **Wave 3 (التكامل والـ QA):** `Instinct` ينفذ `#10` (الربط الكامل + تمرير كل الأحداث لـ DiagnosticsLogger)، ثم `Pally` ينفذ `#11` (QA شامل + `androidTest` على Emulator + التحقق من `assembleDebug` APK Artifact).

## 5. قواعد الـ PR والمراجعة والعمل المتسلسل
- كل التعليقات والـ Issues والـ PRs على GitHub تُكتب **بالإنجليزية**.
- لا push مباشر على `main`. كل تذكرة لها branch باسم `<agent>/issue-<N>-<slug>` وPR بعنوان `[Issue #N] Short description`.
- حد الـ 300 سطر للـ PR يطبق على Wave 1 وما بعدها (تُستثنى مهام Wave 0 `#1`, `#2`, `#3` من حد الـ 300 سطر).
- **مراجعة `Pally` لكل PR تفحص:**
  1. هل التعديل مرتبط بتذكرة واحدة ومختبر فعلياً؟
  2. هل `stream.headers` محفوظة وممررة فعلياً إلى `Media3` و`SavedStream`؟
  3. هل الأحداث والأخطاء التشخيصية محفوظة مع حجب قيم بيانات الاعتماد في `DiagnosticsLogger`؟
  4. هل CI أخضر (`assembleDebug` + `ktlint` + `detekt` + `unit tests`)؟
- عند الرفض: يضع `Pally` label `needs-fix` مع السبب الدقيق. عند الموافقة: `approve` + `ready-to-merge`، ويدمج `Instinct`.
- بلّغا تحديثات الحالة في `#12` (`Status Board`).

## 6. الأمان والحدود
- ممنوع لمس `secrets`/`keystore`/`.env`.
- **التعامل مع محتوى DRM/Widevine:** لا تضع قيوداً أو فلاتر تمنع الـ Sniffer من التقاط روابط المحتوى المحمي بـ DRM أو تمنع المشغل من محاولة تشغيله عبر آليات Media3 القياسية.
- لا تنزيل تلقائي، ولا إرسال بيانات المستخدم خارج الجهاز. JS injection آمن فقط. كل مكتبة جديدة تُضاف عبر Version Catalog.

## 7. تعريف الإنجاز (MVP)
- تصفح + تبويبات + history يعملون فعلياً، والمانع يحجب إعلانات صفحة اختبار ولا يكسر المواقع الشائعة.
- Sniffer يلتقط `m3u8` بالـ `headers` مع أزرار (تشغيل / نسخ / حفظ في المكتبة).
- المكتبة تعرض كروت وبنرات RTL وتشغّل البثوث المحفوظة بالـ `headers` مباشرةً.
- المشغل يشغّل `m3u8` + `mp4` مع PiP والإيماءات.
- سجل التشخيص يحتفظ بالسياق غير الحساس ويحجب بيانات الاعتماد، ويدعم العرض والنسخ والتصدير كملف والحذف.
- CI أخضر على `main`، و`androidTest` ناجحة على Emulator، و`assembleDebug` APK متاح كـ Artifact للتجربة على الهاتف.
