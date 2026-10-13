# Bubble Pop — мобильная игра в стиле Zuma (Android 13+)

Игра для лопания цепочек шариков одинакового цвета: вы стреляете шариками из пушки,
вставляя их в трассу и собирая линии из 3+ шаров одного цвета. Комбо и каскады дают бонусы.

## Требования
- Android 13 и выше (minSdk 33, targetSdk 34)
- Сборка: Gradle 8.7 + Android Gradle Plugin

## Структура проекта
- `BubblePop/` — исходный код Android-проекта
- `BubblePop/releases/BubblePop-debug.apk` — готовый APK для установки

## Установка APK
1. Скачайте `BubblePop-debug.apk` из папки `BubblePop/releases/` (или из GitHub Releases).
2. На телефоне разрешите установку из неизвестных источников.
3. Откройте APK и установите.

Либо через ADB:
```bash
adb install -r BubblePop/releases/BubblePop-debug.apk
```

## Сборка из исходников
```bash
cd BubblePop
./gradlew assembleDebug
# Результат: app/build/outputs/apk/debug/app-debug.apk
```
