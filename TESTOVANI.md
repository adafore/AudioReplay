# AudioReplay 1.2.0 — ověření

Datum: **25. 10. 2026**. Skutečně provedené kontroly verze 1.2.0 (versionCode 3).

| Kontrola | Výsledek |
|---|---|
| Jednotkové testy `:core:test` | **38/38 prošlo** |
| Android `:app:connectedDebugAndroidTest` | **22/22 prošlo**, 0 přeskočených |
| Lint `:app:lintDebug` | **0 chyb**, 47 upozornění |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**, optimalizované APK bez ladění |
| Podpis APK | Ověřen nástrojem apksigner; shodný s předchozími vydáními |
| Fyzický telefon Android 16 / API 36 | Aktualizace přes předchozí instalaci úspěšná, ověřeno versionCode 3 a spuštění aplikace |

Android testy běžely na AOSP emulátoru Android 15 / API 35, x86_64, profil Pixel 6, WHPX. Používají skutečné AudioRecord, MediaCodec, MediaExtractor a MediaMuxer. Vstup emulátoru nenahrazuje fyzický mikrofon.

## Nově ověřeno ve verzi 1.2.0

- **Skutečné opakované převzetí mikrofonu jinou aplikací:** testovací APK se samostatným UID a zdrojem VOICE_COMMUNICATION dvakrát převzalo mikrofon běžící službě AudioReplay. Vždy se objevil stav WAITING, ztlumené PCM nepřibývalo a po zavření druhé aplikace se záznam automaticky obnovil se stejným ID bufferové relace. Testovací aktivita není součástí distribuovaného APK.
- Čtyři integrační testy s řízeným vstupem simulují opakovanou chybu ovladače, obsazení vstupu, pozdní události z předchozího vstupu a trvalou chybu. Ověřují kontrolu obsazení před dalším spuštěním, zachování bufferu i formátu, ukládání během čekání a zrušení pokusů po ruční pauze či Stop. Tyto řízené chyby nejsou vydávány za fyzické poruchy zařízení.
- Jednotkové testy ověřují pokračování stejné relace přes více přerušení, rotaci bufferu a zachování rezervovaných exportů. Změna PCM formátu při navázání se odmítne bez ztráty existujícího zvuku.
- **Hlasitost výsledného M4A:** skutečný AAC export se zesílením +6 dB byl znovu dekódován a porovnán s exportem bez zesílení. Poměr amplitudy byl přibližně 2×; původní PCM soubory zůstaly bitově stejné.
- Zesílení kladných i záporných PCM16 vzorků, saturace bez přetečení, bitově shodný průchod při 0 dB, nezměněný konec částečného bloku a normalizace nastavení 0–18 dB.

## Zachované kontroly

Validace délek, dvojí stisk, rotace a zkrácení diskového bufferu, překrývající se snapshoty a Stop během exportu, AAC/M4A a časové značky, simulovaný nedostatek místa, chyba enkodéru, soukromé i veřejné ukládání, přejmenování/mazání, obnova publikace po simulovaném pádu, denní názvy a migrace, přesný ořez a zesílení se zachováním originálu, přesun do veřejné složky, widgetové layouty a adaptivní ikona. Integrační test skutečné mikrofonní služby ověřuje Uložit/Pozastavit přes PendingIntenty oznámení, ruční pokračování a Stop. Samostatný editor nespouští mikrofon.

## Výsledné APK a meze ověření

Výsledný release byl nainstalován přes původní aplikaci na připojeném fyzickém telefonu a otevřen. Uložené soubory se při aktualizaci nemažou, databázové schéma zůstalo kompatibilní. Soukromé nahrávky ani místní zálohy nejsou ve veřejném repozitáři či balíčcích.

Dosud neproběhl dlouhý 45minutový test nového vydání na fyzickém Xiaomi se zhasnutým displejem, měření baterie, skutečný telefonní hovor nebo změny externího audio vstupu. Chování HyperOS a jeho správce baterie se může lišit od AOSP. Automatické navázání vyžaduje, aby uživatelem spuštěná foreground služba dál běžela a aplikace měla oprávnění mikrofonu. Po ukončení celého procesu či restartu telefonu se nahrávání samo nespustí. Chybějící zvuk během přerušení nelze obnovit; ve výsledku se vynechá a v aplikaci je uveden počet přerušení.

Upozornění Lintu nejsou potlačena baseline souborem. Nezablokující doporučení zahrnují například novější závislosti, KSP místo kapt, překladové zdroje, KTX a analýzu wake locku. Syrové sestavovací logy s místními cestami se nezveřejňují.

## Opakování testů

```powershell
.\gradlew.bat :core:test :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease
```

Instrumentační testy spouštějte pouze na emulátoru nebo vyhrazeném testovacím zařízení: pracují s testovacími daty aplikace a při testu přerušení otevírají mikrofon testovacího APK.

## Jednotkové testy

- `RecordingGainTest.oldSettingsDefaultToNoGainAndInvalidSettingsAreClamped` — prošel
- `RecordingGainTest.sixDecibelsAmplifiesBothSignsAndClipsWithoutWrapping` — prošel
- `RecordingGainTest.zeroGainIsBitExactAndPartialReadDoesNotTouchTail` — prošel
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
- `SegmentStoreTest.repeatedRecoveriesStillRespectRetentionAndPinnedExports` — prošel
- `SegmentStoreTest.pauseResumeStartsDistinctSession` — prošel
- `SegmentStoreTest.rotationKeepsExactTenSeconds` — prošel
- `SegmentStoreTest.growingBufferDoesNotResurrectExpiredSamples` — prošel
- `SegmentStoreTest.automaticRecoveryAppendsWithoutResettingHistoryOrSession` — prošel
- `SegmentStoreTest.oldCacheCleanupDoesNotTouchSavedMedia` — prošel
- `SegmentStoreTest.snapshotEndsAtPressAndIncludesOpenSegment` — prošel
- `SegmentStoreTest.overlappingExportsHaveIndependentPins` — prošel
- `SegmentStoreTest.stopDeletesCacheButPreservesReservedExport` — prošel
- `SegmentStoreTest.shrinkingBufferCannotInvalidateExport` — prošel
- `SegmentStoreTest.lowStorageFailsWithoutClaimingWrittenFrames` — prošel
- `SegmentStoreTest.recoveryRejectsDifferentSampleRateAndPreservesExistingAudio` — prošel
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
- `AudioPipelineTest.exportGainMakesDecodedRecordingLouderWithoutChangingBuffer` — prošel
- `AudioPipelineTest.overlappingExportsSurviveStop` — prošel
- `AudioPipelineTest.movingPrivateRecordingToPublicFolderPreservesIdAndAudio` — prošel
- `AudioPipelineTest.realAacEncoderProducesValidM4a` — prošel
- `AudioPipelineTest.trimAndGainProduceExactPcmAndPreserveOriginal` — prošel
- `CaptureRecoveryTest.repeatedDriverFailuresWaitForFreeInputAndAppendToSameHistory` — prošel
- `CaptureRecoveryTest.manualPauseCancelsRecoveryAndStaleCallbacksCannotReviveRecording` — prošel
- `CaptureRecoveryTest.permanentStorageOrPermissionFailureDoesNotRetry` — prošel
- `CaptureRecoveryTest.stopDuringInitialBusyWaitPreventsAllLaterRetries` — prošel
- `RecordingServiceTest.anotherAppCanUseMicrophoneTwiceAndRecordingAutomaticallyContinues` — prošel
- `RecordingServiceTest.standaloneEditServicePublishesCopyWithoutStartingMicrophone` — prošel
- `RecordingServiceTest.realMicrophoneSavePauseResumeAndStop` — prošel
- `WidgetTest.adaptiveIconHasGreenBackgroundWithoutWhitePlate` — prošel
- `WidgetTest.allWidgetSizesInflateWithExpectedControls` — prošel
