# Pocket Pad 1.4

Telefon z Androidem jako kontroler Bluetooth do komputera Windows, tabletu lub TV z Androidem.

- Dwa drążki, krzyżak, ABXY, L1/R1, cyfrowe L2/R2, L3/R3, Select i Start.
- Krzyżak i ABXY powiększone o 50%, razem z obszarami dotyku.
- Tryb samochodowy: przechylenie telefonu steruje poziomą osią lewej gałki; przycisk wyśrodkowania; pełny skręt przy 45°.
- DirectInput / bezpośredni Bluetooth HID oraz emulowany Xbox 360 na Windows. Tryb wybiera się na telefonie.
- Zapamiętany typ każdego sparowanego urządzenia. Tablet i TV z Androidem nie wymagają odbiornika Windows i nie pokazują komunikatów o jego braku.
- Odbiornik Windows sprawdza aktywność aplikacji. Po rozłączeniu lub 5 sekundach bez sygnału usuwa wirtualny kontroler Xbox i ukrywa nieaktywny Pocket Pad przed grami.

## Używanie

Zainstaluj aplikację na telefonie z Androidem 9 lub nowszym i obsługą profilu Bluetooth HID Device. Sparuj telefon z urządzeniem docelowym i wybierz je przez **POŁĄCZ**. Przy pierwszym połączeniu wskaż **Komputer z Windows** albo **Tablet / TV z Androidem**. Typ można zmienić w menu połączenia.

Na tablecie i TV nie instalujesz odbiornika. Gry muszą obsługiwać kontrolery Bluetooth.

Na Windows uruchom `PocketPadReceiver.exe`, wybierz DirectInput albo Xbox na telefonie i poczekaj na potwierdzenie. Odbiornik pozostaje w zasobniku. Wymagane: Windows x64, .NET Desktop Runtime 10, [ViGEmBus](https://github.com/nefarius/ViGEmBus) oraz [HidHide](https://github.com/nefarius/HidHide). Parowanie telefonu pozostaje zachowane. Zakończenie odbiornika przywraca widoczność fizycznego kontrolera.

Ekran aplikacji na telefonie musi pozostać otwarty. Aplikacja nie mapuje przycisków na dotyk w grach bez obsługi kontrolera. Komunikacja z urządzeniem docelowym odbywa się przez Bluetooth.

## Android: budowanie i testy

Źródła: `PocketPadPhone/`. Wymagane: JDK 17 lub 21, Android SDK Platform 35 i Gradle 8.7 (wrapper w repozytorium). Ustaw `ANDROID_HOME` albo lokalny, ignorowany plik `PocketPadPhone/local.properties` z `sdk.dir`.

```powershell
cd PocketPadPhone
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Na Linux/macOS użyj `bash ./gradlew`.

Build debug korzysta z lokalnego klucza debug. Aby aktualizować już zainstalowaną aplikację, potrzebny jest ten sam klucz podpisujący. Klucze prywatne nie są częścią repozytorium.

## Windows: budowanie

Źródła: `PocketPadReceiver/`. Wymagany .NET SDK 10 na Windows. Biblioteka SDL3 x64 jest dołączona wraz z licencją; zależności .NET pobiera NuGet.

```powershell
dotnet publish PocketPadReceiver/PocketPadReceiver.csproj -c Release --self-contained false -o publish/windows
```

Uruchom `publish/windows/PocketPadReceiver.exe`. Autostart jest opcjonalny: dodaj skrót do folderu Autostart użytkownika. Nie uruchamiaj równolegle starszego PocketPadModes.

Odbiornik akceptuje komendy wyboru trybu i sygnały aktywności przez osobną usługę Bluetooth RFCOMM od sparowanego urządzenia. Format raportu HID pozostaje zgodny z pierwotnym Pocket Pad.

## Weryfikacja

14 testów Android przeszło: tryby, format raportów, kierownica, reakcja filtra, powiększone obszary dotyku, brak nakładania kontrolek, typ urządzenia i potwierdzanie połączenia. Lint: 0 błędów.

Testy odbiornika wymagają odpowiednich sterowników i dostępu do Bluetooth. Zmieniają tymczasowo konfigurację kontrolera; uruchamiaj je po zamknięciu normalnego odbiornika:

```powershell
.\PocketPadReceiver.exe --self-test --result selftest.json
.\PocketPadReceiver.exe --watchdog-test --result watchdog-test.json
```

Test monitora sprawdza ukrywanie nieaktywnego kontrolera, tworzenie Xboxa po potwierdzeniu, usunięcie po wygaśnięciu sygnału i powrót do DirectInput. Przy końcu przywraca wcześniejsze ustawienia HidHide. Końcowe działanie układu i łącza radiowego należy sprawdzać na fizycznym telefonie i urządzeniu docelowym.

Licencje dołączonych bibliotek znajdują się w `Licenses/`. Skrypty wrappera Gradle zawierają własne informacje licencyjne.
