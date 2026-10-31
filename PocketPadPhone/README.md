# Aplikacja Android

Aktualna wersja: Pocket Pad 1.4. Instrukcja instalacji, funkcje i polecenia budowania znajdują się w [głównym README](../README.md).

Budowanie i testy z tego folderu:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Wymagane: JDK 17 lub 21 i Android SDK Platform 35. Ustaw ANDROID_HOME lub lokalny plik local.properties z sdk.dir.
