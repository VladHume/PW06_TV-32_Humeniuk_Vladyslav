# Підготовка навчального релізу

1. Встановіть JDK 17, Android SDK Platform 35 і Build Tools 36.0.0. У local.properties задайте sdk.dir. Відкрийте корінь проєкту в Android Studio й дочекайтеся Gradle Sync.
2. Виконайте `./gradlew testDebugUnitTest lintDebug assembleDebug`.
3. Для UI-тестів запустіть Android-емулятор: `./gradlew connectedDebugAndroidTest`.
4. Для підпису release передайте змінні середовища RAD_RELEASE_STORE (шлях до власного JKS) і RAD_RELEASE_PASSWORD (пароль). Alias: course. Ключ і пароль не повинні потрапляти до Git.
5. Виконайте `./gradlew assembleRelease bundleRelease`. R8 увімкнено у release. Правила збереження DTO Gson містяться в app/proguard-rules.pro.
6. Перевірте підпис через `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`.
7. Встановіть APK на тестовий пристрій. Перевірте вхід, локальний журнал, API, мапу, сенсор, CSV/JSON і PDF. Відмова в дозволах не повинна завершувати застосунок.

У репозиторії немає ключів підпису. Для підготовленої локальної збірки ключ збережено окремо на робочому столі. Без змінних RAD_RELEASE_* Gradle генерує непідписаний release для підписування власним ключем.

## Метадані для Google Play

Назва: АЕС Моніторинг Навчальний

Короткий опис: Навчальна модель моніторингу вигаданих енергетичних постів.

Повний опис: Навчальний Android-застосунок демонструє роботу Kotlin і Jetpack Compose, локального журналу Room, REST API, мап, акселерометра, локальних нагадувань і звітів. Радіаційні дані демонстраційні. Застосунок не підключений до реальних атомних станцій і не призначений для ухвалення рішень з радіаційної безпеки.

Категорія: Освіта. Реклама: відсутня. Покупки: відсутні.

Підготовлені знімки інтерфейсу містяться в evidence/. Перед поданням у магазин потрібно перевірити актуальні вимоги Play Console, підготувати іконку/feature graphic необхідних розмірів, публічну політику конфіденційності та заповнити Data safety, віковий рейтинг і декларації дозволів відповідно до фінальної конфігурації. Це локально підготовлений навчальний реліз; у Google Play його не опубліковано.

## Поведінка даних

У режимі mock реєстрації залишаються в пам’яті процесу; демонстраційні токени та їхній строк дії зберігаються в локальних preferences. Це навчальна реалізація, а не production-сховище секретів. Записи, вибрані файли та PDF зберігаються локально. GPS використовується лише після дозволу для показу позиції на мапі. Запити тайлів OpenStreetMap використовують інтернет. За явного налаштування зовнішнього API дані входу надсилаються на введену адресу. Для реальних серверів клієнт вимагає HTTPS; HTTP дозволено лише для localhost і адреси хоста емулятора 10.0.2.2.

## Варіанти та перевірки після аудиту

- `./gradlew -PwithFlavors=true assembleDemoDebug` — flavor demo з суфіксом applicationId `.demo`.
- `./gradlew -PwithFlavors=true assembleFullDebug` — flavor full.
- `./gradlew -Pcoverage=true testDebugUnitTest coverageReport` — локальні тести й JaCoCo HTML/XML. Це окреме покриття локальних тестів, без об’єднання з instrumentation.
- `./gradlew -PtestBuildType=release assembleRelease assembleReleaseAndroidTest` — тестова збірка для перевірки оптимізованого релізу з тим самим локальним ключем.

Графіка та опис даних: `docs/play/`. Знімки: `evidence/`. Фактичні результати: `verification/`. Офіційні пояснення конфігурації: https://developer.android.com/build/build-variants та https://developer.android.com/studio/test/advanced-test-setup . Шаблон CI не активовано через обмеження токена, але локальні команди виконані.

### R8 і release instrumentation

У навчальному релізі збережено імена та точки входу застосунку, Compose, Lifecycle/SavedState/Activity, Kotlin/coroutines і tracing, спільні з instrumentation APK. R8 увімкнено, але ці keep-правила обмежують його скорочення/обфускацію; це компроміс для відтворюваного тестування, а не максимально малий production APK. Команда `-PtestBuildType=release assembleReleaseAndroidTest` створює окремий тестовий пакет.
