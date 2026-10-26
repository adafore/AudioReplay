# AudioReplay 1.3.0 — ověření

Datum: **26. 10. 2026**. Výsledky verze 1.3.0 (versionCode 4).

| Kontrola | Výsledek |
|---|---|
| Jednotkové testy `:core:test` | **55/55 prošlo** |
| Android `:app:connectedDebugAndroidTest` | **27/27 prošlo**, 0 přeskočených |
| Lint `:app:lintDebug` | **0 chyb**, 49 upozornění |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**, optimalizované APK bez ladění |
| Podpis APK | Ověřen apksignerem, shodný s předchozími vydáními |
| Fyzický telefon | **Neinstalováno** — uživatel požádal jen o vydání na GitHubu |

Android testy běžely na AOSP emulátoru Android 15 / API 35, x86_64, profil Pixel 6, WHPX. Používají skutečné AudioRecord, MediaCodec, MediaExtractor a MediaMuxer. Testovací mikrofon emulátoru nenahrazuje fyzický mikrofon telefonu.

## Plánování

- Šestnáct jednotkových scénářů ověřuje páteční intervaly 07:00–12:00 a 12:30–22:00, mezeru mezi nimi, opakování další týden, více dnů, jednorázové datum, půlnoc včetně přechodu týdne, překryvy, sousedící intervaly, vypnuté intervaly, neplatné vstupy, změnu časového pásma a oba přechody letního času v Europe/Prague.
- **Dva skutečné mikrofonní intervaly na pozadí:** plán byl aktivován z viditelné aktivity, poté aktivita přešla do stavu CREATED. Služba zpočátku čekala bez PCM, první interval sám otevřel mikrofon, konec jej zastavil a zachoval buffer. Buffer šel v mezeře ručně uložit. Druhý interval vytvořil novou historii a znovu se ukončil bez smazání bufferu.
- Ruční Stop před budoucím startem vypnul plán a zabránil dalšímu spuštění. Připravené pravidlo zůstalo uložené.
- Pokud foreground služba neběží, obslužný receiver pouze zobrazil oznámení pro aktivaci; mikrofon se na pozadí sám nespustil.
- Uložení a opětovné načtení jednorázového i týdenního pravidla, úprava jednoho pravidla bez změny druhého a odebrání pravidla.

## Hlasitost a úpravy

Nová uložení používají pevné zesílení až +12 dB s kontrolou špiček celého vybraného úseku a rezervou před AAC. U hlasitého vstupu se zesílení sníží. Jednotkové testy ověřují požadovaný faktor u slabého vstupu, omezení u hlasitého PCM a saturaci ručně zvoleného zesílení. Android test **defaultGainCapsHotInputBeforeAacEncoding** exportoval hlasitější vstup do AAC, znovu jej dekódoval a ověřil zesílení odpovídající omezenému faktoru i absenci digitálních špiček na plném rozsahu. Původní buffer se při exportu nemění.

Zachované testy ověřují ořez se vzorkovou přesností, ruční zesílení +6 dB s přibližně dvojnásobnou amplitudou a nezměněný originál. Další automatické zesílení se k upravované kopii nepřičítá. Ovládání hlasitosti ze Záznamu a Nastavení bylo odstraněno; ruční ovládání zůstává v Historii.

## Zachované kontroly

Rotace a zkrácení diskového bufferu, souběžné snapshoty, Stop během exportu, validace délek a dvojí stisk, skutečný AAC/M4A a časové značky, simulované chyby enkodéru a místa, soukromé/veřejné ukládání, přejmenování/mazání, obnova publikace po pádu, denní názvy a migrace, přesun do veřejné složky, widgetové layouty a ikona. Mikrofonní služba má integrační test ukládání, pozastavení a Stop přes PendingIntenty oznámení; samostatný editor nespouští mikrofon.

Opakované skutečné převzetí mikrofonu testovacím APK se samostatným UID ověřuje automatické pokračování ve stejné historii. Řízené simulace ověřují chybu ovladače, obsazení vstupu, zachování PCM formátu, pozdní události původního vstupu, ruční zrušení pokusů a trvalou chybu. Testovací aktivita se samostatným UID není v distribuovaném APK.

## Omezení

Telefon nebyl pro toto vydání k dispozici a verze 1.3.0 na něm nebyla nainstalována. Dosud neproběhl dlouhý test plánu na HyperOS se zhasnutým displejem, skutečný hovor, měření baterie nebo změny externího vstupu. Na emulátoru byly intervaly záměrně zkrácené na sekundy; testy netvrdí, že proběhlo několikahodinové nahrávání.

Plán se aktivuje uživatelem z otevřené aplikace s oprávněním mikrofonu a na Androidu 12+ i přesnými budíky. Mezi intervaly zůstává aktivní foreground služba, nikoli AudioRecord. Po úplném ukončení procesu nebo restartu telefonu je potřeba plán znovu aktivovat. Alarm ani boot receiver mikrofon skrytě nespouští; oznámení umožní aktivaci klepnutím uživatele. Při zakázaných oznámeních připomenutí vidět nebude. Vynucené zastavení aplikace v Androidu blokuje alarmy do dalšího otevření.

Konec intervalu jen vypne mikrofon a ponechá dostupný buffer; automaticky neukládá celý interval. Další spuštění začne novou historii. Při přerušení mikrofonu zvuk z chybějící doby nelze obnovit. Zesílení zvedá i šum a neopraví zkreslení již zachycené mikrofonem.

Lint nemá baseline. Upozornění zahrnují například novější závislosti, KSP místo kapt, překladové zdroje a analýzu wake locku. Syrové logy s místními cestami, soukromá média a podpisový klíč se nezveřejňují.

## Opakování

```powershell
.\gradlew.bat :core:test :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease
```

Instrumentační testy používejte na emulátoru nebo vyhrazeném testovacím zařízení. Pracují s daty testovací aplikace, udělují jí oprávnění mikrofonu/oznámení a pro plánovací testy povolují přesné alarmy pomocí shellového appops.

## Jednotkové testy

- `RecordingGainTest.fixedDefaultIsTwelveDecibelsAndQuietInputKeepsRequestedGain` — prošel
- `RecordingGainTest.sixDecibelsAmplifiesBothSignsAndClipsWithoutWrapping` — prošel
- `RecordingGainTest.zeroGainIsBitExactAndPartialReadDoesNotTouchTail` — prošel
- `RecordingGainTest.hotInputReducesGainWithHeadroomInsteadOfClipping` — prošel
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
- `ScheduleClockTest.sundayNightCanRunOnMonday` — prošel
- `ScheduleClockTest.fridayStartsAtSeven` — prošel
- `ScheduleClockTest.afternoonEndsAtTwentyTwo` — prošel
- `ScheduleClockTest.weeklyRepeatUsesNextFriday` — prošel
- `ScheduleClockTest.timezoneChangeKeepsLocalClockTime` — prošel
- `ScheduleClockTest.disabledIntervalsAreIgnored` — prošel
- `ScheduleClockTest.touchingRulesDoNotStopAtTheirBoundary` — prošel
- `ScheduleClockTest.oneTimeDateRunsOnlyOnce` — prošel
- `ScheduleClockTest.invalidAndDuplicateRulesAreRejected` — prošel
- `ScheduleClockTest.overnightExtendsIntoNextDay` — prošel
- `ScheduleClockTest.overlappingRulesFormOneContinuousWindow` — prošel
- `ScheduleClockTest.multipleWeekdaysChooseNearestLocalDay` — prošel
- `ScheduleClockTest.lunchGapStopsAndHasNextStart` — prošel
- `ScheduleClockTest.springClockGapMovesNonexistentTimeForward` — prošel
- `ScheduleClockTest.disabledPlanHasNoWakeup` — prošel
- `ScheduleClockTest.autumnRepeatedHourUsesFirstOccurrenceAndRealEnd` — prošel
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
- `AudioPipelineTest.defaultGainCapsHotInputBeforeAacEncoding` — prošel
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
- `ScheduleServiceTest.stopCancelsFutureScheduledStart` — prošel
- `ScheduleServiceTest.weeklyAndOneTimeRulesPersistAndCanBeEditedWithoutChangingOthers` — prošel
- `ScheduleServiceTest.twoBackgroundIntervalsStartStopAndKeepBufferForManualSave` — prošel
- `ScheduleServiceTest.absentServiceGetsActivationNotificationInsteadOfBackgroundMicrophone` — prošel
- `WidgetTest.adaptiveIconHasGreenBackgroundWithoutWhitePlate` — prošel
- `WidgetTest.allWidgetSizesInflateWithExpectedControls` — prošel
