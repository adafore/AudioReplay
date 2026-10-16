# AudioReplay

Uložte zvuk, který už zazněl. Nativní aplikace pro Android s průběžným diskovým bufferem, rychlým uložením posledních minut a úpravami nahrávek. České rozhraní, světlý i tmavý motiv, bez cloudu a bez oprávnění k internetu.

**[Stáhnout nejnovější APK zdarma](https://github.com/adafore/AudioReplay/releases/latest)** · [Změny ve verzi 1.1.0](CHANGELOG.md) · [Výsledky testů](TESTOVANI.md)

## Instalace do telefonu

1. V telefonu otevřete odkaz na nejnovější vydání a v části **Assets** stáhněte soubor `AudioReplay-1.1.0.apk`.
2. Otevřete stažený soubor. Pokud Android požádá o povolení instalace z prohlížeče nebo správce souborů, potvrďte je pro tuto instalaci.
3. Otevřete AudioReplay, projděte úvod a klepněte na **Spustit**. Pro záznam povolte mikrofon. Oznámení jsou volitelná.

Podporován je Android 10 a novější. Obchod Google Play ani placený účet nejsou potřeba. Další verzi instalujte přes původní aplikaci; aktualizace je podepsaná stejným klíčem a zachová uložené nahrávky a nastavení.

## Používání

- **Buffer** uchovává až 60 minut; výchozí je 30 minut. Délka ukládaného úseku je nezávislá, výchozí 5 minut. Předvolby bufferu jsou 5/10/20/30/60 minut, úseku 1/2,5/5/10/20/30 minut. Číselné pole a posuvník dovolují i jiné hodnoty od 10 s.
- **Uložit** vytvoří M4A z reálně dostupné historie a nahrávání pokračuje. Pokud historie ještě nestačí, uloží se její dostupná část. Fronta přijme nejvýše 3 souběžně zadané úlohy.
- **Pozastavit** vypne mikrofon a zachová dosavadní historii pro uložení. Pokračování začne novou souvislou historii. **Zastavit** zruší dočasný buffer, dokončené nahrávky ponechá a již zadané ukládání dokončí.
- Nahrávky se jmenují jen datem dne, například `16.10.2026.m4a`, další `16.10.2026 2.m4a`. Staré automatické názvy z verze 1.0.0 se převedou; vlastní názvy aplikace nemění.
- V **Historii** lze přehrávat a posouvat zvuk, hledat, řadit, přejmenovat, sdílet a mazat. Tlačítko nůžek otevře **Oříznout a zesílit**: vyberte časový úsek a zesílení 0–18 dB. Výsledek se uloží jako nová kopie v aktuálně nastavené kvalitě. Vysoké zesílení může zvuk zkreslit; přetečení vzorků je ošetřeno saturací.
- Tlačítko složky v Historii otevře `Recordings/AudioReplay` (Android 10–11: `Music/AudioReplay`). Pokud existují soukromé nahrávky, nabídne nejprve jejich bezpečné přesunutí do veřejné složky a používání této složky pro další ukládání. Správce souborů nemá přímý přístup do soukromého adresáře aplikace.

## Widget a oznámení

Widget přidejte přes nabídku domovské obrazovky. Podržením a tažením okrajů změníte jeho velikost. Podle skutečně dostupného místa zobrazí:

| Velikost | Ovládání |
|---|---|
| Malá, přibližně 1×1 | Uložit předvolený čas |
| Širší | Uložit 1 minutu / 5 minut |
| Široká | Spustit, 1 minuta, 5 minut, Zastavit |
| Větší plocha | Stav, předvolený čas, 1/5 minut, Spustit/Pokračovat a Zastavit |

Spuštění přes widget je výslovnou akcí uživatele. Pokud chybí oprávnění mikrofonu, otevře aplikaci pro jeho udělení. Malý widget s uložením nezačne sám nahrávat. Nezávisle je dostupná dlaždice rychlého nastavení. Volitelný dvojí stisk do 900 ms se posuzuje samostatně pro každé tlačítko; vibrace přijde až po úspěšném uložení.

V **Nastavení → Oznámení nahrávání** lze změnit podrobnosti, tlačítka i soukromí. Přepínač zobrazení otevře systémovou kategorii oznámení nahrávání; poslední slovo při vypnutí a zvuku má Android. Aplikace nahrávání neblokuje, když uživatel oznámení skryje. Služba nadále dodržuje pravidla foreground service a Android může běh zobrazovat mezi aktivními aplikacemi. Zelený systémový indikátor mikrofonu se nemění.

## Sestavení ze zdrojů

- JDK 17, Gradle 8.13 (wrapper), AGP 8.13.2, Kotlin 2.2.21.
- Android SDK Platform 36, Build Tools 35.0.0, minSdk 29, targetSdk 36.
- Projekt otevřete v Android Studiu. Lokální `local.properties` s cestou k SDK vytvoří Studio; do Git repozitáře nepatří. První sestavení stáhne závislosti.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest  # připojený emulátor nebo testovací telefon
.\gradlew.bat :app:assembleRelease
```

Na macOS/Linux použijte `sh gradlew` místo `.\gradlew.bat`. Výstupy jsou `app/build/outputs/apk/debug/app-debug.apk` a `app/build/outputs/apk/release/app-release.apk`.

Pro kontinuitu prvních vydání používá lokální release stejný místní podpis jako verze 1.0.0. Soukromý klíč není v repozitáři. Vlastní sestavení na jiném počítači bude mít jiný podpis a nelze jím přepsat oficiální instalaci. Publikované APK je optimalizované a nemá zapnuté ladění.

## Struktura a architektura

```text
app/src/main/kotlin/cz/audioreplay/
  audio/       AudioRecord, AAC exportér, dekódování/ořez/zesílení
  service/     foreground služba, fronta úloh a oznámení
  controls/    dlaždice, widget a bezpečné PendingIntenty
  data/        DataStore, Room, atomická publikace a obnova souborů
  ui/          dashboard, historie, editor a nastavení v Compose
core/src/main/   diskový buffer, validace, názvy a pravidla velikostí widgetu
core/src/test/   jednotkové testy
app/src/androidTest/  skutečný kodek, úložiště, služba, editor a widgety
```

AudioRecord zapisuje PCM16 mono po malých blocích do pětisekundových segmentů. Preferuje 24 kHz, při nepodporovaném vstupu volí 48 / 44,1 / 16 kHz. Hodinový PCM buffer není v RAM. Monotónní počátek a čítače vzorků oddělují čas zvuku od měnitelného kalendářního data.

Uložení si při stisku rezervuje přesné rozsahy segmentů, včetně otevřeného segmentu. Rotace, změna délky i Stop respektují reference probíhajících exportů. AAC-LC (32/64/128 kb/s) a MediaMuxer vytvoří jednu chronologickou M4A stopu mimo capture thread. AAC může přidat priming/padding v řádu kodekových rámců. Editor dekóduje vybraný úsek do dočasného PCM na disku, zesílí vzorky a stejným exportérem uloží novou kopii.

Hotový soubor se synchronizuje a ověří. Soukromý se atomicky přejmenuje; veřejný je během kopírování v MediaStore označen `IS_PENDING` a zveřejní se až kompletní. AtomicFile journal umožňuje obnovit metadata nebo odstranit neúplnou publikaci. Room schéma zůstává kompatibilní s 1.0.0. Úpravy, publikace a ukládání se serializují a původní média zůstávají chráněná.

Nahrávání nemá automatický restart po pádu ani po startu telefonu. Proces vždy začíná ve vypnutém stavu a starý dočasný PCM buffer zahodí. Uložená média zůstanou. Soukromé nahrávky se při odinstalaci odstraní, veřejné zůstanou ve složce telefonu.

## Ověření a omezení

Skutečné výsledky jsou v [TESTOVANI.md](TESTOVANI.md). Emulátor ověřuje AudioRecord a kodeky, nenahrazuje dlouhý fyzický test Xiaomi/HyperOS, správce baterie, skutečné hovory a směrování zvuku. Zamykací obrazovka může podle nastavení telefonu skrýt obsah či akce oznámení. Přehrávání se při odchodu z aplikace pozastaví.

Nedostatek místa, přerušení mikrofonu, detekovaná změna vstupu nebo chyba kodeku mají viditelný chybový stav. Wake lock se drží při záznamu a práci se soubory, poté se uvolní. Přesnou spotřebu baterie aplikace netvrdí. Respektujte soukromí ostatních.

Platformní pravidla: [foreground mikrofon](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [spuštění služby z widgetu](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [volitelná oznámení](https://developer.android.com/develop/ui/views/notifications/notification-permission), [nastavení kategorií oznámení](https://developer.android.com/develop/ui/views/notifications/channels), [responzivní widgety](https://developer.android.com/develop/ui/views/appwidgets/layouts).
