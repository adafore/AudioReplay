# AudioReplay 1.1.0 — ověření

Datum: **16. 10. 2026**. Níže jsou skutečně provedené kontroly verze 1.1.0 (versionCode 2).

| Kontrola | Výsledek |
|---|---|
| Jednotkové testy `:core:test` | **32/32 prošlo** |
| Android `:app:connectedDebugAndroidTest` | **16/16 prošlo**, 0 přeskočených |
| Lint `:app:lintDebug` | **0 chyb**, 47 upozornění |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**, optimalizované APK bez ladění |
| Podpis APK | Ověřen nástrojem apksigner, shodný s verzí 1.0.0 |
| Aktualizace fyzického telefonu Android 16 / API 36 | Instalace přes původní verzi úspěšná, ověřeno versionCode 2 a spuštění bez pádu |

Automatické Android testy běžely na AOSP emulátoru Android 15 / API 35, x86_64, profil Pixel 6, WHPX. Používají skutečné Android API AudioRecord, MediaCodec, MediaExtractor a MediaMuxer. Vstup emulátoru nenahrazuje fyzický mikrofon.

## Pokryté scénáře

- Validace délek, převody jednotek, dvojí stisk, rotace a omezení diskového bufferu, současné snapshoty a Stop během exportu.
- AAC/M4A export syntetického tónu, monotónní časové značky, dekódování, chyba enkodéru a simulovaný nedostatek místa.
- Soukromé i veřejné soubory, přejmenování, mazání, obnova atomické publikace po simulovaném pádu.
- Nové názvy podle dne, číslování, převod starých automatických názvů a zachování vlastních názvů.
- Přesný interval ořezu v PCM, zesílení +6 dB s přibližně dvojnásobnou amplitudou, saturace a zachování originálu.
- Přesun soukromého souboru do veřejné složky se zachováním ID, názvu, velikosti a zvuku.
- Všechny čtyři widgetové layouty, pravidla přizpůsobení velikosti a adaptivní ikona.
- Mikrofonní služba: start z viditelné aktivity, Uložit/Pozastavit přes skutečné PendingIntenty oznámení, Pokračovat a Stop.
- Samostatné zpracování úpravy v popředí bez spuštění mikrofonu.

## Ruční kontrola výsledného release APK

APK bylo nainstalováno na emulátor. Po udělení mikrofonu a zamítnutí POST_NOTIFICATIONS se záznam úspěšně spustil jako foreground služba typu microphone, historie rostla a uložení vytvořilo soubor s novým názvem. Ověřeno zastavení, zobrazení Historie a editoru Oříznout a zesílit. Na fyzickém telefonu byla po uživatelově souhlasu provedena aktualizace přes původní instalaci; předem vznikla místní záloha uložených dat. Tato soukromá záloha není součástí veřejného repozitáře ani balíčků.

Upozornění Lintu nejsou potlačena baseline souborem. Jde o nezablokující doporučení, například novější závislosti, KSP místo kapt, překladové zdroje, KTX a analýzu wake locku. Veřejný protokol záměrně neobsahuje syrové sestavovací logy s místními cestami.

## Meze ověření

Dosud neproběhl dlouhý 45minutový test nového vydání na fyzickém Xiaomi se zhasnutým displejem, měření baterie, skutečný hovor nebo změny externího audio vstupu. Rozložení widgetů na konkrétních launcherech, chování HyperOS a správce souborů je potřeba ověřit v daném telefonu. Automatické testy tyto fyzické scénáře nenahrazují. Android může při skrytém oznámení dál zobrazovat aktivní aplikaci a systémový indikátor mikrofonu.

## Opakování testů

```powershell
.\gradlew.bat :core:test :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease
```

Instrumentační testy používejte na emulátoru nebo vyhrazeném testovacím zařízení. Pracují s testovacími daty aplikace.

## Jednotkové testy

- `ReplaySettingsTest.changeSaveToTwentyMinutesWithoutChangingBuffer` — prošel
- `ReplaySettingsTest.confirmationExpiresAndUsesMonotonicOrder` — prošel
- `ReplaySettingsTest.durationsRespectCurrentBuffer` — prošel
- `ReplaySettingsTest.shrinkBufferClampsSave` — prošel
- `ReplaySettingsTest.byteEstimateUsesLongArithmetic` — prošel
- `ReplaySettingsTest.confirmationIsIndependentForEachInput` — prošel
- `ReplaySettingsTest.confirmationCanBeDisabled` — prošel
- `ReplaySettingsTest.invalidAndUnfinishedInputsAreRejected` — prošel
- `ReplaySettingsTest.unitConversion` — prošel
- `ReplaySettingsTest.defaultsKeepIndependentLengths` — prošel
- `ReplaySettingsTest.invalidPersistedSettingsAreClamped` — prošel
- `SegmentStoreTest.pauseResumeStartsDistinctSession` — prošel
- `SegmentStoreTest.rotationKeepsExactTenSeconds` — prošel
- `SegmentStoreTest.growingBufferDoesNotResurrectExpiredSamples` — prošel
- `SegmentStoreTest.oldCacheCleanupDoesNotTouchSavedMedia` — prošel
- `SegmentStoreTest.snapshotEndsAtPressAndIncludesOpenSegment` — prošel
- `SegmentStoreTest.overlappingExportsHaveIndependentPins` — prošel
- `SegmentStoreTest.stopDeletesCacheButPreservesReservedExport` — prošel
- `SegmentStoreTest.shrinkingBufferCannotInvalidateExport` — prošel
- `SegmentStoreTest.lowStorageFailsWithoutClaimingWrittenFrames` — prošel
- `SegmentStoreTest.shortHistoryExportsOnlyRealFrames` — prošel
- `SegmentStoreTest.concurrentCaptureAndSnapshotsRemainOrdered` — prošel
- `UpdateFeaturesTest.customNamesAreNotLegacy` — prošel
- `UpdateFeaturesTest.validEditKeepsSubsecondPrecision` — prošel
- `UpdateFeaturesTest.invalidEditsAreRejected` — prošel
- `UpdateFeaturesTest.compactAndQuickWidgetsOnlySave` — prošel
- `UpdateFeaturesTest.labelsIncludeHalfMinutePreset` — prošel
- `UpdateFeaturesTest.gainSaturatesInsteadOfWrapping` — prošel
- `UpdateFeaturesTest.dailyDuplicatesAreNumbered` — prošel
- `UpdateFeaturesTest.firstDailyNameIsOnlyDate` — prošel
- `UpdateFeaturesTest.deletedNamesDoNotResetStoredSequence` — prošel
- `UpdateFeaturesTest.largerWidgetsIncludeStartAndStop` — prošel

## Android testy

- `AudioPipelineTest.captureContinuesWhileExportIsReservedAndBufferShrinks` — prošel
- `AudioPipelineTest.privatePublicationRenameAndDelete` — prošel
- `AudioPipelineTest.diskFullDoesNotIncreaseHistory` — prošel
- `AudioPipelineTest.interruptedEncoderDoesNotPublishPartialFile` — prošel
- `AudioPipelineTest.publicMediaStorePublicationAndDelete` — prošel
- `AudioPipelineTest.upgradingLegacyNamesPreservesCustomNamesAndMetadata` — prošel
- `AudioPipelineTest.interruptedPublicationIsDiscardedDuringRecovery` — prošel
- `AudioPipelineTest.completedFileIsRecoveredAfterProcessDeathBeforeDatabaseInsert` — prošel
- `AudioPipelineTest.overlappingExportsSurviveStop` — prošel
- `AudioPipelineTest.movingPrivateRecordingToPublicFolderPreservesIdAndAudio` — prošel
- `AudioPipelineTest.realAacEncoderProducesValidM4a` — prošel
- `AudioPipelineTest.trimAndGainProduceExactPcmAndPreserveOriginal` — prošel
- `RecordingServiceTest.standaloneEditServicePublishesCopyWithoutStartingMicrophone` — prošel
- `RecordingServiceTest.realMicrophoneSavePauseResumeAndStop` — prošel
- `WidgetTest.adaptiveIconHasGreenBackgroundWithoutWhitePlate` — prošel
- `WidgetTest.allWidgetSizesInflateWithExpectedControls` — prošel
