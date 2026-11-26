# HolosReader

> **Мова / Language:** [English](README.md) | **Українська**

[![Latest Release](https://img.shields.io/github/v/release/smyhlin/HolosReader?display_name=tag&sort=semver)](https://github.com/smyhlin/HolosReader/releases/latest)
[![Build APK](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml)
[![Release APK](https://github.com/smyhlin/HolosReader/actions/workflows/release.yml/badge.svg)](https://github.com/smyhlin/HolosReader/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 10+](https://img.shields.io/badge/Android-10%2B%20(API%2029%E2%80%9335)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)

**HolosReader** — швидка повністю офлайн-читалка EPUB 3 / EPUB 2 для Android, спроєктована спеціально для гігантських веброманів і ранобе (6 000+ розділів) із вбудованим нейромережевим українським озвученням на базі [HolosTTS](https://github.com/patriotyk/HolosTTS). Написана виключно на стандартних Android Framework API (`android.useAndroidX=false`) для мінімального споживання пам'яті та миттєвого запуску.

---

## Можливості

### Оптимізація під гігантські книги (6 000+ розділів)
- **Одноразове O(N) індексування у SQLite:** потоковий парсинг EPUB (`BookDatabase.kt`, `EpubStreamingParser.kt`) відкриває книгу на 6 500 розділів за мілісекунди без повторного розбору OPF-маніфесту.
- **Віртуалізований рендеринг абзаців:** `ListView` із перевикористанням ViewHolder тримає в пам'яті лише видимі на екрані абзаци — жодних фризів навіть у дуже довгих розділах.
- **Постійний `ZipFile` та LRU-кеш зображень:** асинхронне декодування з `inSampleSize` під ширину екрана (`EpubImageCache.kt`) і повноекранний перегляд ілюстрацій по тапу.
- **Миттєвий пошук по змісту:** фільтрація через SQLite `LIKE` по тисячах розділів (`TocSheetController.kt`).

### Повна підтримка EPUB 3 та EPUB 2
- Вбудоване й блокове форматування (`<b>`/`<strong>`, `<i>`/`<em>`, `<u>`, `<s>`/`<del>`, `<code>`, `<sup>`/`<sub>`, `<h1>`–`<h6>`, `<blockquote>`, `<ul>`/`<ol>`).
- Стилізовані таблиці `<table>` (ідеально для LitRPG-статусів), виноски `<aside epub:type="footnote">`, `<figure>`/`<figcaption>`, `<img>` та SVG `<image>`.
- Витягування обкладинок, закладки, статистика читання (`StatisticsActivity.kt`) та опційне біонічне читання (`BionicTextHelper.kt`).

### Мінімалістична читалка та overlay-панелі керування
- **Повноекранний режим за замовчуванням:** приховує системний статус-бар під час запуску (Android 10–15+), показуючи акуратну назву розділу зверху та лічильник сторінок знизу, який можна сховати або повернути одним тапом.
- **Наскрізний скрабер по сторінках книги:** переміщення та індикація прогресу по глобальних сторінках книги (`поточна / всього`), а не лише по розділах.
- **Компактні швидкі панелі та напівпрозора шторка налаштувань:** керуйте яскравістю, 9 темами оформлення, розміром шрифту, полями й міжрядковим інтервалом поверх тексту або відкривайте 68%-шторку повних налаштувань (`ReaderSettingsActivity.kt`).
- **Живе прев'ю шрифтів:** кожен варіант у виборі шрифту відображається власним накресленням.

### Офлайн нейромережевий український TTS та медіакерування
- **4-рівневий офлайн український G2P (без Python):**
  1. Власний словник наголосів (`stress_user.txt` у `StressEditorActivity.kt`)
  2. 2 886 186 словоформ із [ukrainian-word-stress](https://github.com/patriotyk/ukrainian-word-stress) + контекстне розрізнення 36 414 омографів
  3. Вбудований 1,17 МБ ONNX нейромережевий акцентор (`accentor-lite.onnx` із [egorsmkv/ukrainian-accentor](https://github.com/egorsmkv/ukrainian-accentor)) для рідкісних слів і власних назв
  4. Нативний 1:1 Kotlin-порт [patriotyk/ipa-uk](https://github.com/patriotyk/ipa-uk)
- **Висувна аудіопанель у стилі Lyrics (`AudioSheetController.kt`):** підсвічений активний абзац, приглушений контекст, скрабер часу, лінійка швидкості, A-B повтор, вбудований таймер сну (`Set Timeout` на 14 пресетів), кешування розділу в офлайн WAV та вибір голосу з фільтром `Усі / Жіночі / Чоловічі` й прослуховуванням.
- **Повноцінний плеєр у шторці та на екрані блокування (`ReaderAudioService.kt`):** обкладинка книги, жива озвучувана фраза, назва розділу, перемотування та кнопки керування.
- **Системний TTS-рушій:** також реєструється як стандартний Android TTS (`HolosTtsService.kt`).

### Автооновлення з GitHub Releases без авторизації
- Перевіряє [GitHub Releases](https://github.com/smyhlin/HolosReader/releases/latest) автоматично під час запуску та вручну через **Про програму → Check for updates** (`GitHubUpdater.kt`, `ApkFileProvider.kt`) — без входу в акаунт GitHub.

---

## Встановлення

1. Завантажте останній `HolosReader-v*.apk` зі сторінки [**Releases**](https://github.com/smyhlin/HolosReader/releases/latest).
2. Встановіть APK на пристрій з Android 10+ (API 29+).
3. Наступні оновлення можна встановлювати безпосередньо в додатку через **Check for updates**.

---

## Збірка з вихідного коду

### Вимоги
- **JDK 17** (Temurin / OpenJDK)
- **Android SDK 35** (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 29`)
- **Gradle 8.11.1** (вбудовано через `./gradlew`)

### Локальна збірка
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Зібраний APK зберігається за шляхом:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI/CD
- **[`release.yml`](.github/workflows/release.yml):** автоматично оновлює `versionName` (`5.1.<run_number>`) і `versionCode` при кожному пуші в `main`, збирає APK, генерує `SHA256SUMS` і публікує реліз у GitHub Releases.
- **[`build-apk.yml`](.github/workflows/build-apk.yml):** збирає та завантажує тестовий артефакт `HolosReader-EPUB3-HolosTTS-apk` для гілок `dev` і `main`.

---

## Структура проєкту

| Модуль / Пакет | Призначення |
| --- | --- |
| `holos-onnx` | Міст до ONNX Runtime (`HolosOnnx.kt`) для нейромережевого TTS та акцентора |
| `com.holos.reader.epub` | Потоковий парсер EPUB, SQLite-індекс розділів/спіну, LRU-кеш зображень |
| `com.holos.reader.g2p` | Офлайн-нормалізатор українського тексту, словник наголосів, ONNX-акцентор, IPA |
| `com.holos.reader.tts` | Випереджальний синтез, фоновий `MediaSession`-сервіс, налаштування, каталог голосів |
| `com.holos.reader.ui` | Бібліотека, повноекранна читалка, overlay-налаштування, аудіошторка, зміст і статистика |
| `com.holos.reader.update` | Оновлення з GitHub Releases без логіну та `ContentProvider` для встановлення APK |

Правила архітектури, нюанси CI та протоколи синхронізації наведено у [`AGENTS.md`](AGENTS.md) і [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## Подяки

- [patriotyk/HolosTTS](https://github.com/patriotyk/HolosTTS) — архітектура та моделі українського нейромережевого TTS
- [patriotyk/ukrainian-word-stress](https://github.com/patriotyk/ukrainian-word-stress) — словник наголосів української мови
- [egorsmkv/ukrainian-accentor](https://github.com/egorsmkv/ukrainian-accentor) — нейромережевий український акцентор
- [patriotyk/ipa-uk](https://github.com/patriotyk/ipa-uk) — український фонетичний транскриптор IPA

---

## Ліцензія

Випущено під ліцензією [**MIT License**](LICENSE) — вільне використання, модифікація та розповсюдження для особистих і комерційних цілей.
