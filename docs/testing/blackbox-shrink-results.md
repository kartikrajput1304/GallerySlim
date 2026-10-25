# GallerySlim black-box test results: scanning, shrinking, checking and clearing, keeping it slim

Cases: `docs/testing/blackbox-shrink-cases.md`. Expectations come only from `docs/features/GallerySlim-Features.pdf`, updated
92-page edition (cases marked "(v2)" were changed or added for it). Tests: `app/src/test/java/com/galleryslim/blackbox/shrink/`.

## Run (round 2, after the PDF update and code fixes)

- Command: `cd /home/kartikrajput/galleryslim && flock .build.lock ~/tools/gradle-8.11.1/bin/gradle --no-daemon -q :app:testDebugUnitTest --tests 'com.galleryslim.blackbox.shrink.*'`
- Result: build succeeded, exit code 0. All 278 tests passed; none skipped.

| | Count |
|---|---|
| Test cases in the plan | 423 |
| Automated (unit + integration), one JUnit test each | 278 |
| PASS | 278 |
| FAIL | 0 |
| NOT AUTOMATED (device-only) | 145 |

## What happened to the 12 round-1 failures

| Case | Round 1 | Round 2 |
|---|---|---|
| RT03 | Ultra HDR HEIC said "Already in an efficient format" | Expectation unchanged (v2 §4.1 p16). Now **PASS**: code fixed. |
| SK02e | Trim errors grouped as "Video conversion failed" | v2 §4.8 p23 adds "Couldn't trim this video — left untouched". Expectation updated; **PASS**. |
| PQ09b | Undocumented early stop in the quality search | v2 §4.9 p24 step 4 documents the 1.84× prediction. Expectation updated; **PASS**. |
| GN03 | Compare "4.2 MB" vs §2.2 whole MB | v2 §5.2 p31–32 documents one decimal below 100 for Compare. Expectation updated; **PASS**. |
| GN04 | Make room "0 MB" for 500 KB | Expectation unchanged (v2 §7.4 p52 confirms whole KB). Now **PASS**: code fixed. |
| MR08b | Rungs 3–4 included protected / Never-touch / waiting items | Expectation unchanged (v2 §7.4 p52 "All four rungs"). Now **PASS**: code fixed. |
| RH04b, RH05, RH05b | Shrink-row window | v2 §5.9 p39: rows in the last 30 days plus older ones still in the trash; Undo only while in trash; "Shrunk, original gone from the trash". Expectations updated; **PASS**. |
| RH08a | Freed shrink vanished | v2 §5.9 p39: stays listed, "Shrunk, original deleted for good", no Undo. Expectation updated; **PASS**. |
| RH08b | Moved & deleted-for-good row had no Undo | v2 §5.9 p39: listed without Undo. Expectation updated; **PASS**. |
| D111 | No-fingerprint record not eligible for D1 | v2 §5.8 p37 step 3: such records are never eligible. Expectation updated; **PASS**. |

## New cases for the v2 text

- **Automated, all PASS:**
  - SK03, SK03b: "Already waiting in Ready to clear"; original changed during shrink, so the copy is removed.
  - RC30, RC30b, RC31, RC32, RC33: "Can't be cleared safely"; Remove the smaller copy; original already gone.
  - KO09, UR14, UR15: Keep original and Undo with a changed original.
  - D112, D113: D1 fingerprint and library-version checks.
  - RH10: logged-row Undo.
- **Device-only:**
  - RT38: the one-batch-at-a-time busy message "Another shrink is already running (…)". It is set in `ShrinkViewModel`, so it
    cannot be unit-tested.
  - RC34: the stale section UI and its messages.
  - KO14, UR16: Keep original and Undo messages.
  - D123: the "Nothing can be deleted for good right now…" snackbar. This is now the expected behaviour.
  - D124: the success count.
  - RH22: "Restored 1 item" / "Nothing to restore — those files have left the trash".

The PDF still contradicts itself on §4.8 p23: it says every failure text ends in "left untouched", but it lists "Not enough free
space" and "Already waiting in Ready to clear" as failure texts.

## Device-only cases for a human

All 145 are listed below as NOT AUTOMATED.

- **P1 (30):** SC33, RT30, RT31, RT32, RT36, RT37, SG01, PT12, CT03, PQ36, VD32, VD37, MP10, MP11, MP12, BV14, TM22, CM35, RC23, RC24, KO10, UR11, D121, D125, RH21, RH23, AR21, MR25, ON12, ON16.
- **P2 (54):** SC02, SC03, SC04, SC05, SC30, SC31, SC35, SC36, RT34, RT38, CT06, TR02, TR03, GP01, PG02, PG05, PG06, PG08, PQ31, PQ32, PQ33, PQ34, VD30, VD31, BV11, BV13, TM21, TM23, CM30, CM36, RC34, RC20, RC25, KO11, KO12, TL10, TL11, TL13, UR16, UR10, UR12, D120, D123, RH22, SF12, MR20, MR23, TP21, TP22, TP23, TP24, ON10, ON11, ON15.

## Per-case results

| ID | Result | Test | Type | Pri |
|---|---|---|---|---|
| GN01 | PASS | ScanAndEstimateTest.GN01_appScreensUseBinaryUnitsWithPdfFormat | unit | P3 |
| GN02 | PASS | ScanAndEstimateTest.GN02_storageGoalMakeRoomAndTripUseBinaryGb | unit | P2 |
| GN03 | PASS | CompareTest.GN03_compareSizeLineUsesOneDecimalBelow100 | unit | P3 |
| GN04 | PASS | ScanAndEstimateTest.GN04_makeRoomAndGoalSizesFollowAppScreenFormat | unit | P3 |
| SC01 | NOT AUTOMATED | – | device-only | P3 |
| SC02 | NOT AUTOMATED | – | device-only | P2 |
| SC03 | NOT AUTOMATED | – | device-only | P2 |
| SC04 | NOT AUTOMATED | – | device-only | P2 |
| SC05 | NOT AUTOMATED | – | device-only | P2 |
| SC06 | NOT AUTOMATED | – | device-only | P3 |
| SC07 | NOT AUTOMATED | – | device-only | P3 |
| SC08 | NOT AUTOMATED | – | device-only | P3 |
| SC10 | PASS | ScanAndEstimateTest.SC10_plainJpegIsCameraPhotoAt35Percent | unit | P2 |
| SC11 | PASS | ScanAndEstimateTest.SC11_pngIsScreenshotsAndImagesAt60Percent | unit | P2 |
| SC12 | PASS | ScanAndEstimateTest.SC12_unmeasuredH264VideoGetsFlat45Percent | unit | P2 |
| SC13 | PASS | ScanAndEstimateTest.SC13_measuredH264VideoEstimateComesFromBitratePlan | unit | P2 |
| SC14 | PASS | ScanAndEstimateTest.SC14_leanH264VideoCountsAsZeroAndAlreadyEfficient | unit | P2 |
| SC15 | PASS | ScanAndEstimateTest.SC15_motionPhotoEstimateIsDeclaredClipSize | unit | P2 |
| SC15b | PASS | ScanAndEstimateTest.SC15b_motionPhotoIsCheckedFirstEvenWhenAlsoUltraHdr | unit | P2 |
| SC16 | PASS | ScanAndEstimateTest.SC16_motionPhotoWithoutDeclaredClipIsHalfTheFile | unit | P2 |
| SC17 | PASS | ScanAndEstimateTest.SC17_ultraHdrJpegIsHdrPhotosAt3Percent | unit | P2 |
| SC18 | PASS | ScanAndEstimateTest.SC18_ultraHdrInNonJpegFormatIsZero | unit | P2 |
| SC19 | PASS | ScanAndEstimateTest.SC19_efficientAndOtherFormatsAreZero | unit | P2 |
| SC20 | PASS | ScanAndEstimateTest.SC20_unreadableJpegClassedOtherIsNeverEstimated | unit | P1 |
| SC21 | PASS | ScanAndEstimateTest.SC21_summaryTotalsAddUp | unit | P3 |
| SC22 | PASS | ScanAndEstimateTest.SC22_xmpMotionPhotoAndMicroVideoFlagsMarkMotionPhoto | unit | P2 |
| SC23 | PASS | ScanAndEstimateTest.SC23_samsungTrailerIsMotionPhotoOnlyWithMotionPhotoDataEntry | unit | P2 |
| SC24 | PASS | ScanAndEstimateTest.SC24_ultraHdrDetectedByGainMapMarkers | unit | P2 |
| SC26 | PASS | ScanAndEstimateTest.SC26_samsungTrailerUpTo64KiBIsReadLargerIsIgnored | unit | P3 |
| SC27 | PASS | ScanAndEstimateTest.SC27_cacheEntryReusedOnlyWhenSizeAndModifiedUnchangedAndKnown | unit | P2 |
| SC28 | PASS | ScanAndEstimateTest.SC28_cacheSurvivesSaveAndLoad | integration | P3 |
| SC29 | PASS | ScanAndEstimateTest.SC29_cacheWithOtherVersionIsIgnoredSoEverythingIsReadAgain | integration | P2 |
| SC30 | NOT AUTOMATED | – | device-only | P2 |
| SC31 | NOT AUTOMATED | – | device-only | P2 |
| SC32 | NOT AUTOMATED | – | device-only | P3 |
| SC33 | NOT AUTOMATED | – | device-only | P1 |
| SC34 | NOT AUTOMATED | – | device-only | P3 |
| SC35 | NOT AUTOMATED | – | device-only | P2 |
| SC36 | NOT AUTOMATED | – | device-only | P2 |
| RT01 | PASS | ShrinkRoutingTest.RT01_jpegAndPngGoToPhotoShrinkWithChosenPreset | integration | P2 |
| RT02 | PASS | ShrinkRoutingTest.RT02_ultraHdrJpegReachesPhotoPipelineForLosslessPath | integration | P1 |
| RT03 | PASS | ShrinkRoutingTest.RT03_ultraHdrInAnotherFormatIsSkippedAsProtected | integration | P2 |
| RT04 | PASS | ShrinkRoutingTest.RT04_motionPhotoIsSkippedByNormalShrink | integration | P1 |
| RT05 | PASS | ShrinkRoutingTest.RT05_heicAndWebpAreAlreadyEfficient | integration | P2 |
| RT06 | PASS | ShrinkRoutingTest.RT06_gifAndOtherImagesAreNotSupported | integration | P2 |
| RT07 | PASS | ShrinkRoutingTest.RT07_h264VideoGoesToVideoShrink | integration | P2 |
| RT08 | PASS | ShrinkRoutingTest.RT08_hevcVideoIsSkippedAsEfficientUnderNormalPresets | integration | P2 |
| RT09 | PASS | ShrinkRoutingTest.RT09_hevcAbove720pIsAcceptedWithLightCopy | integration | P2 |
| RT10 | PASS | ShrinkRoutingTest.RT10_av1IsAlreadyEfficientEvenWithLightCopy | integration | P2 |
| RT11 | PASS | ShrinkRoutingTest.RT11_otherOrUnknownCodecIsNotSupported | integration | P2 |
| RT12 | PASS | ShrinkRoutingTest.RT12_eachItemGetsItsOwnPresetFromAlbumRuleOrCooling | integration | P2 |
| RT13 | PASS | ShrinkRoutingTest.RT13_vaultFailureMeansItemIsNotShrunk | integration | P1 |
| RT14 | PASS | ShrinkRoutingTest.RT14_vaultCopyIsMadeBeforeTheShrink | integration | P1 |
| RT15 | PASS | ShrinkRoutingTest.RT15_vaultCopyIsMadeEvenWhenThePipelineThenSkips | integration | P3 |
| RT16 | PASS | ShrinkRoutingTest.RT16_vaultOffNeverCopiesToVault | integration | P3 |
| RT17 | PASS | ShrinkRoutingTest.RT17_successQueuesOriginalInReadyToClearAndNeverTrashes | integration | P1 |
| RT18 | PASS | ShrinkRoutingTest.RT18_skippedOrFailedItemsAreNotQueued | integration | P1 |
| RT19 | PASS | ShrinkRoutingTest.RT19_lowSpaceRunsSmallestFirstOtherwiseUserOrder | unit | P2 |
| RT19b | PASS | ShrinkRoutingTest.RT19b_controllerProcessesSmallestFirstWhenSpaceIsLow | integration | P2 |
| RT20 | PASS | ShrinkRoutingTest.RT20_onlyOneBatchRunsAtATime | integration | P1 |
| RT21 | PASS | ShrinkRoutingTest.RT21_decisionMemorySkipsRetryWithSameReason | unit | P2 |
| RT23 | PASS | ShrinkRoutingTest.RT23_cancelKeepsFinishedCopiesQueuedAndDeletesNothing | integration | P1 |
| RT24 | PASS | ShrinkRoutingTest.RT24_progressCountsAndQueuedSaving | integration | P2 |
| RT29 | PASS | ShrinkRoutingTest.RT29_copyWhoseRecordCannotBeWrittenIsRemovedAndItemFails | integration | P1 |
| RT30 | NOT AUTOMATED | – | device-only | P1 |
| RT31 | NOT AUTOMATED | – | device-only | P1 |
| RT32 | NOT AUTOMATED | – | device-only | P1 |
| RT33 | NOT AUTOMATED | – | device-only | P3 |
| RT34 | NOT AUTOMATED | – | device-only | P2 |
| RT36 | NOT AUTOMATED | – | device-only | P1 |
| RT38 | NOT AUTOMATED | – | device-only | P2 |
| RT37 | NOT AUTOMATED | – | device-only | P1 |
| SG01 | NOT AUTOMATED | – | device-only | P1 |
| PR01 | PASS | PresetsProtectionSettingsTest.PR01_presetFloorsAndStartQualities | unit | P2 |
| PR01b | PASS | PresetsProtectionSettingsTest.PR01b_onlyLightCopyReducesPhotosTo2048 | unit | P2 |
| PR02 | PASS | PresetsProtectionSettingsTest.PR02_videoQualityTargetPerPreset | unit | P2 |
| PR03 | PASS | PresetsProtectionSettingsTest.PR03_freshInstallSelectsGentle | integration | P2 |
| PR03b | PASS | PresetsProtectionSettingsTest.PR03b_chosenPresetIsRemembered | integration | P3 |
| PR04 | NOT AUTOMATED | – | device-only | P3 |
| PR06 | PASS | PresetsProtectionSettingsTest.PR06_presetNames | unit | P3 |
| PT01 | PASS | PresetsProtectionSettingsTest.PT01_favouriteIsProtected | unit | P1 |
| PT02 | PASS | PresetsProtectionSettingsTest.PT02_newerThanWindowIsProtected | unit | P1 |
| PT03 | PASS | PresetsProtectionSettingsTest.PT03_ageExactlyAtWindowIsNotProtected | unit | P2 |
| PT04 | PASS | PresetsProtectionSettingsTest.PT04_ageJustBelowWindowIsProtected | unit | P2 |
| PT05 | PASS | PresetsProtectionSettingsTest.PT05_unknownAgeIsNotProtected | unit | P2 |
| PT06 | PASS | PresetsProtectionSettingsTest.PT06_dateAddedUsedWhenDateTakenMissing | unit | P2 |
| PT07 | PASS | PresetsProtectionSettingsTest.PT07_dateTakenWinsOverDateAdded | unit | P2 |
| PT08 | PASS | PresetsProtectionSettingsTest.PT08_windowChoicesAndDefault | integration | P2 |
| PT09 | PASS | PresetsProtectionSettingsTest.PT09_sevenAndNinetyDayWindows | unit | P2 |
| PT10 | PASS | PresetsProtectionSettingsTest.PT10_makeRoomOvernightAndTripsExcludeProtectedEntirely | unit | P1 |
| PT11 | NOT AUTOMATED | – | device-only | P3 |
| PT12 | NOT AUTOMATED | – | device-only | P1 |
| SET01 | PASS | PresetsProtectionSettingsTest.SET01_freshInstallDefaults | integration | P2 |
| CT01 | NOT AUTOMATED | – | device-only | P3 |
| CT02 | NOT AUTOMATED | – | device-only | P3 |
| CT03 | NOT AUTOMATED | – | device-only | P1 |
| CT04 | NOT AUTOMATED | – | device-only | P3 |
| CT05 | NOT AUTOMATED | – | device-only | P3 |
| CT06 | NOT AUTOMATED | – | device-only | P2 |
| CT07 | PASS | ScanAndEstimateTest.CT07_categoryScreenTitles | unit | P3 |
| TR01 | NOT AUTOMATED | – | device-only | P3 |
| TR02 | NOT AUTOMATED | – | device-only | P2 |
| TR03 | NOT AUTOMATED | – | device-only | P2 |
| TR04 | NOT AUTOMATED | – | device-only | P3 |
| GP01 | NOT AUTOMATED | – | device-only | P2 |
| GP02 | NOT AUTOMATED | – | device-only | P3 |
| GP03 | NOT AUTOMATED | – | device-only | P3 |
| PG01 | NOT AUTOMATED | – | device-only | P3 |
| PG02 | NOT AUTOMATED | – | device-only | P2 |
| PG03 | NOT AUTOMATED | – | device-only | P3 |
| PG04 | NOT AUTOMATED | – | device-only | P3 |
| PG05 | NOT AUTOMATED | – | device-only | P2 |
| PG06 | NOT AUTOMATED | – | device-only | P2 |
| PG07 | NOT AUTOMATED | – | device-only | P3 |
| PG08 | NOT AUTOMATED | – | device-only | P2 |
| SK01 | PASS | ShrinkRoutingTest.SK01_skipReasonTexts | unit | P2 |
| SK02a | PASS | ShrinkRoutingTest.SK02a_vaultAndSpaceFailures | unit | P2 |
| SK02b | PASS | ShrinkRoutingTest.SK02b_videoFailures | unit | P2 |
| SK02c | PASS | ShrinkRoutingTest.SK02c_photoAndMotionFailures | unit | P2 |
| SK02d | PASS | ShrinkRoutingTest.SK02d_anythingElseIncludingLosslessPathIsCouldNotProcess | unit | P2 |
| SK02e | PASS | ShrinkRoutingTest.SK02e_trimErrorsAreCouldNotTrim | unit | P3 |
| SK03 | PASS | ShrinkRoutingTest.SK03_alreadyQueuedAndOriginalChangedFailures | unit | P1 |
| SK03b | PASS | ShrinkRoutingTest.SK03b_originalChangedDuringShrinkRemovesCopyAndFails | integration | P1 |
| PQ01 | PASS | PhotoQualityTest.PQ01_tooCompactForHeicBoundaries | unit | P2 |
| PQ02 | PASS | PhotoQualityTest.PQ02_startQualityLoweredBySourceQualityAndClamped | unit | P2 |
| PQ26 | PASS | PhotoQualityTest.PQ26_sourceJpegQualityIsEstimatedFromItsTables | unit | P3 |
| PQ03 | PASS | PhotoQualityTest.PQ03_atMostSixEncodes | unit | P2 |
| PQ04 | PASS | PhotoQualityTest.PQ04_failedCheckStepsUpByEightPassTooBigStepsDownByEight | unit | P2 |
| PQ05 | PASS | PhotoQualityTest.PQ05_stepDoublesTo16WhenSizeDidNotChange | unit | P3 |
| PQ06 | PASS | PhotoQualityTest.PQ06_stopsEarlyWhenEncodeIsAtLeast1point5xOriginal | unit | P2 |
| PQ07 | PASS | PhotoQualityTest.PQ07_acceptOnlyAtMost90PercentOfOriginal | unit | P2 |
| PQ08 | PASS | PhotoQualityTest.PQ08_acceptsSmallestPassingEncode | unit | P2 |
| PQ09 | PASS | PhotoQualityTest.PQ09_failedCheckGoesUpThenAcceptsWhenItFits | unit | P2 |
| PQ09b | PASS | PhotoQualityTest.PQ09b_stopsWhenPredictedNextStepUpCannotSaveSpace | unit | P3 |
| PQ10 | PASS | PhotoQualityTest.PQ10_noPassMeansQualityUnreachablePassButBigMeansNoSavings | unit | P2 |
| PQ11 | PASS | PhotoQualityTest.PQ11_qualityStaysWithin40To95 | unit | P2 |
| PQ12 | PASS | PhotoQualityTest.PQ12_screenLikeMeansAtLeast60PercentOfBlocksWith4OrFewerLevels | unit | P2 |
| PQ13 | PASS | PhotoQualityTest.PQ13_metricsSanityIdenticalScoresFullNoisyScoresLower | unit | P3 |
| PQ15 | PASS | PhotoQualityTest.PQ15_losslessReoptimisationKeepsEveryPixel | integration | P1 |
| PQ16 | PASS | PhotoQualityTest.PQ16_everythingAfterTheImageIsCopiedByteForByte | integration | P1 |
| PQ16b | PASS | PhotoQualityTest.PQ16b_secondImageGainMapStyleIsCopiedByteForByte | integration | P1 |
| PQ17 | PASS | PhotoQualityTest.PQ17_progressiveJpegIsNotSupported | integration | P2 |
| PQ21 | PASS | PhotoQualityTest.PQ21_photoCopyFolder | unit | P2 |
| PQ22 | PASS | PhotoQualityTest.PQ22_losslessCopyShowsVisuallyIdenticalMatch100 | unit | P3 |
| PQ30 | NOT AUTOMATED | – | device-only | P3 |
| PQ31 | NOT AUTOMATED | – | device-only | P2 |
| PQ32 | NOT AUTOMATED | – | device-only | P2 |
| PQ33 | NOT AUTOMATED | – | device-only | P2 |
| PQ34 | NOT AUTOMATED | – | device-only | P2 |
| PQ36 | NOT AUTOMATED | – | device-only | P1 |
| VD01 | PASS | VideoRulesTest.VD01_bitrateLadderByShortSide | unit | P2 |
| VD02 | PASS | VideoRulesTest.VD02_portraitUsesShortSide | unit | P2 |
| VD03 | PASS | VideoRulesTest.VD03_times1point5At48FpsOrMore | unit | P2 |
| VD04 | PASS | VideoRulesTest.VD04_skipWhenPlanAbove75PercentOfSource | unit | P2 |
| VD05 | PASS | VideoRulesTest.VD05_targetIsLowerOfPlanAnd55PercentOfSource | unit | P2 |
| VD06 | PASS | VideoRulesTest.VD06_bothResolutionAndBitrateUnknownIsSkipped | unit | P2 |
| VD07 | PASS | VideoRulesTest.VD07_contentFactorBetween0point7And1 | unit | P3 |
| VD08 | PASS | VideoRulesTest.VD08_screenRecordingDetection | unit | P3 |
| VD09 | PASS | VideoRulesTest.VD09_screenRecordingsAbove32FpsAreReencodedAt30 | unit | P3 |
| VD10 | PASS | VideoRulesTest.VD10_freeSpaceNeedsTwiceEstimatedOutputPlus300MB | unit | P2 |
| VD11 | PASS | VideoRulesTest.VD11_spotCheckSamplesFiveFramesAt10To90Percent | unit | P2 |
| VD12 | PASS | VideoRulesTest.VD12_everyFrameMustScoreAtLeast0point90 | unit | P1 |
| VD13 | PASS | VideoRulesTest.VD13_unknownDurationAlwaysFailsSpotCheck | unit | P1 |
| VD14 | PASS | VideoRulesTest.VD14_bandingGuardThresholds | unit | P2 |
| VD15 | PASS | VideoRulesTest.VD15_bandingIndexSanity | unit | P3 |
| VD17 | PASS | VideoRulesTest.VD17_videoCopyFolder | unit | P2 |
| VD30 | NOT AUTOMATED | – | device-only | P2 |
| VD31 | NOT AUTOMATED | – | device-only | P2 |
| VD32 | NOT AUTOMATED | – | device-only | P1 |
| VD34 | NOT AUTOMATED | – | device-only | P3 |
| VD35 | NOT AUTOMATED | – | device-only | P3 |
| VD37 | NOT AUTOMATED | – | device-only | P1 |
| MP02 | PASS | ShrinkRoutingTest.MP02_stripQueuesOriginalUnderMotionPhotos | integration | P2 |
| MP03 | PASS | ShrinkRoutingTest.MP03_stripNeverTouchesANonMotionPhoto | integration | P1 |
| MP04 | PASS | ShrinkRoutingTest.MP04_stripHonoursVaultFailure | integration | P1 |
| MP05 | PASS | MotionPhotoTest.MP05_clipBoundaryFoundAndStillIsExactBytePrefix | unit | P1 |
| MP05b | PASS | MotionPhotoTest.MP05b_boundaryFoundWithoutXmpDeclaration | unit | P2 |
| MP06 | PASS | MotionPhotoTest.MP06_noClipBoundaryMeansNoStrip | unit | P1 |
| MP07 | PASS | MotionPhotoTest.MP07_samsungClipStillIsAPrefixEndingBeforeTheClip | unit | P2 |
| MP08 | PASS | MotionPhotoTest.MP08_strippedStillHasMotionFlagZeroSameLengthAndInputUntouched | unit | P2 |
| MP09 | PASS | ShrinkRoutingTest.MP09_strippedMotionPhotoShowsNotMeasuredInCompare | integration | P3 |
| MP10 | NOT AUTOMATED | – | device-only | P1 |
| MP11 | NOT AUTOMATED | – | device-only | P1 |
| MP12 | NOT AUTOMATED | – | device-only | P1 |
| MP14 | NOT AUTOMATED | – | device-only | P3 |
| MP15 | NOT AUTOMATED | – | device-only | P3 |
| MP16 | NOT AUTOMATED | – | device-only | P3 |
| BV01 | PASS | BigVideosTrimTest.BV01_shortEdgeBecomes720KeepingAspect | unit | P2 |
| BV01b | PASS | BigVideosTrimTest.BV01b_dimensionsAreEvenAndAspectKept | unit | P2 |
| BV01c | PASS | BigVideosTrimTest.BV01c_smallerVideosAreNotUpscaled | unit | P2 |
| BV02 | PASS | BigVideosTrimTest.BV02_rotationIsRespected | unit | P2 |
| BV03 | PASS | BigVideosTrimTest.BV03_noSavingForOtherCodecsSmallHevcNoPlanOrUnknownSizeOrDuration | unit | P2 |
| BV04 | PASS | BigVideosTrimTest.BV04_smallH264IsReencodedNotUpscaled | unit | P2 |
| BV05 | PASS | BigVideosTrimTest.BV05_hevcAbove720pHasALightCopy | unit | P2 |
| BV10 | NOT AUTOMATED | – | device-only | P3 |
| BV11 | NOT AUTOMATED | – | device-only | P2 |
| BV12 | NOT AUTOMATED | – | device-only | P3 |
| BV13 | NOT AUTOMATED | – | device-only | P2 |
| BV14 | NOT AUTOMATED | – | device-only | P1 |
| BV15 | NOT AUTOMATED | – | device-only | P3 |
| BV16 | NOT AUTOMATED | – | device-only | P3 |
| TM01 | PASS | BigVideosTrimTest.TM01_unknownLengthCannotBeTrimmed | unit | P2 |
| TM02 | PASS | BigVideosTrimTest.TM02_startBeforeZero | unit | P2 |
| TM03 | PASS | BigVideosTrimTest.TM03_endMoreThan500msPastEndIsRefusedWithin500SnapsToEnd | unit | P2 |
| TM04 | PASS | BigVideosTrimTest.TM04_startAtOrAfterEnd | unit | P2 |
| TM05 | PASS | BigVideosTrimTest.TM05_keepAtLeastOneSecond | unit | P2 |
| TM06 | PASS | BigVideosTrimTest.TM06_mustCutAtLeastOneSecondInTotal | unit | P2 |
| TM07 | PASS | BigVideosTrimTest.TM07_trimBitratePlannerForH264SourceForHevc | unit | P2 |
| TM08 | PASS | BigVideosTrimTest.TM08_saveAboutIsTheCutShare | unit | P3 |
| TM09 | PASS | BigVideosTrimTest.TM09_handleLabelsUseMssOrHmmss | unit | P3 |
| TM10 | PASS | ShrinkRoutingTest.TM10_trimQueuesOriginalUnderTrimmedVideosAndRecentChangesSaysTrimmed | integration | P2 |
| TM11 | PASS | ShrinkRoutingTest.TM11_trimOfAPhotoIsRefused | integration | P2 |
| TM12 | PASS | ShrinkRoutingTest.TM12_trimHonoursVaultFailure | integration | P1 |
| TM20 | NOT AUTOMATED | – | device-only | P3 |
| TM21 | NOT AUTOMATED | – | device-only | P2 |
| TM22 | NOT AUTOMATED | – | device-only | P1 |
| TM23 | NOT AUTOMATED | – | device-only | P2 |
| TM24 | NOT AUTOMATED | – | device-only | P3 |
| CM01 | PASS | CompareTest.CM01_recordNotFound | unit | P2 |
| CM02 | PASS | CompareTest.CM02_alreadyClearedOrKept | unit | P2 |
| CM03 | PASS | CompareTest.CM03_trimmedVideoCannotBeCompared | unit | P3 |
| CM04 | PASS | CompareTest.CM04_missingFiles | unit | P2 |
| CM05 | PASS | CompareTest.CM05_photoComparerForPhotosAndMotionStripsVideoComparerForVideos | unit | P2 |
| CM06 | PASS | CompareTest.CM06_rowActionsMoveToSecondLineAtFontScale1point3 | unit | P3 |
| CM07 | PASS | CompareTest.CM07_verdictThresholds | unit | P2 |
| CM08 | PASS | CompareTest.CM08_matchPercentIsTruncatedNotRounded | unit | P3 |
| CM09 | PASS | CompareTest.CM09_sizeChangeWording | unit | P3 |
| CM10 | PASS | CompareTest.CM10_wipeStartsAtHalfStaysOffEdgesOriginalOnStartSide | unit | P3 |
| CM11 | PASS | CompareTest.CM11_zoomRangeFitToLargerOf3xFitAnd4xActualPixels | unit | P3 |
| CM12 | PASS | CompareTest.CM12_doubleTapGoesToActualPixelsWithin2xTo4xThenBackToFit | unit | P3 |
| CM13 | PASS | CompareTest.CM13_hundredPercentButtonTogglesActualPixelsAndFit | unit | P3 |
| CM14 | PASS | CompareTest.CM14_zoomChipOnlyWhenMoreThanOnePercentAboveFit | unit | P3 |
| CM15 | PASS | CompareTest.CM15_sideBySidePicksLargerArrangementRowOnTie | unit | P3 |
| CM16 | PASS | CompareTest.CM16_layoutSidePanelAndPortraitLimits | unit | P3 |
| CM17 | PASS | CompareTest.CM17_talkBackDividerDescription | unit | P3 |
| CM18 | PASS | CompareTest.CM18_infoColumnsMegapixelsFormatAndUnknownDash | unit | P3 |
| CM19 | PASS | CompareTest.CM19_videoDefaultsSideBySideCopyAudibleOnlyOneSide | unit | P3 |
| CM20 | PASS | CompareTest.CM20_transportLabels | unit | P3 |
| CM21 | PASS | CompareTest.CM21_totalIsShorterDurationUnknownIsDash | unit | P3 |
| CM22 | PASS | CompareTest.CM22_resyncWhenDriftOver100msAtMostEvery500ms | unit | P3 |
| CM23 | PASS | CompareTest.CM23_endWithin50msAndSeekPreviewsAtMostEvery80ms | unit | P3 |
| CM24 | PASS | CompareTest.CM24_frameGrabAtLeast50msBeforeEndAndMemoryBudget | unit | P3 |
| CM25 | PASS | CompareTest.CM25_verdictNotes | unit | P3 |
| CM26 | PASS | CompareTest.CM26_videoFormatAndBitrateLabels | unit | P3 |
| CM27 | PASS | CompareTest.CM27_paneErrorTexts | unit | P3 |
| CM30 | NOT AUTOMATED | – | device-only | P2 |
| CM31 | NOT AUTOMATED | – | device-only | P3 |
| CM33 | NOT AUTOMATED | – | device-only | P3 |
| CM34 | NOT AUTOMATED | – | device-only | P3 |
| CM35 | NOT AUTOMATED | – | device-only | P1 |
| CM36 | NOT AUTOMATED | – | device-only | P2 |
| CM37 | NOT AUTOMATED | – | device-only | P3 |
| CM38 | NOT AUTOMATED | – | device-only | P3 |
| CM39 | NOT AUTOMATED | – | device-only | P3 |
| RC01 | PASS | ClearingAndUndoTest.RC01_groupsInFixedOrder | unit | P3 |
| RC02 | PASS | ClearingAndUndoTest.RC02_groupHeaderCountAndSaving | unit | P2 |
| RC03 | PASS | ClearingAndUndoTest.RC03_clearButtonShowsCountAndTotalSizeOfOriginals | unit | P2 |
| RC04 | PASS | ClearingAndUndoTest.RC04_savingPerRecordNeverBelowZero | unit | P2 |
| RC05 | PASS | ClearingAndUndoTest.RC05_copyNotVisibleOrTrashedOrEmptyIsDroppedAndOriginalNotTrashed | unit | P1 |
| RC06 | PASS | ClearingAndUndoTest.RC06_publishedCopyAndPresentOriginalIsCleared | unit | P1 |
| RC06b | PASS | ClearingAndUndoTest.RC06b_onlyFailingRecordsLeaveTheBatch | unit | P1 |
| RC08 | PASS | ClearingAndUndoTest.RC08_unreadableStateNeverTrashesTheOriginal | unit | P1 |
| RC10 | PASS | ClearingAndUndoTest.RC10_okConfirmsTheRecords | unit | P1 |
| RC11 | PASS | ClearingAndUndoTest.RC11_cancelChangesNothing | unit | P1 |
| RC16 | PASS | ClearingAndUndoTest.RC16_dialogResultConfirmsOnlyTheBatchItWasOpenedFor | unit | P1 |
| RC30 | PASS | ClearingAndUndoTest.RC30_originalChangedMovesToCantBeClearedSafely | unit | P1 |
| RC30b | PASS | ClearingAndUndoTest.RC30b_copyNotTheSizeWrittenIsNotCleared | unit | P1 |
| RC31 | PASS | ClearingAndUndoTest.RC31_staleItemsAreNeverPartOfClear | unit | P1 |
| RC32 | PASS | ClearingAndUndoTest.RC32_removeSmallerCopyOnlyWhileOriginalInGalleryAndCopyIsOurs | unit | P1 |
| RC33 | PASS | ClearingAndUndoTest.RC33_originalAlreadyGoneOrTrashedLeavesListCopyKept | unit | P2 |
| RC34 | NOT AUTOMATED | – | device-only | P2 |
| RC20 | NOT AUTOMATED | – | device-only | P2 |
| RC21 | NOT AUTOMATED | – | device-only | P3 |
| RC22 | NOT AUTOMATED | – | device-only | P3 |
| RC23 | NOT AUTOMATED | – | device-only | P1 |
| RC24 | NOT AUTOMATED | – | device-only | P1 |
| RC25 | NOT AUTOMATED | – | device-only | P2 |
| RC26 | NOT AUTOMATED | – | device-only | P3 |
| RC27 | NOT AUTOMATED | – | device-only | P3 |
| RC28 | NOT AUTOMATED | – | device-only | P3 |
| KO01 | PASS | ClearingAndUndoTest.KO01_originalPresentDeletesOnlyTheCopy | unit | P1 |
| KO02 | PASS | ClearingAndUndoTest.KO02_originalInTrashKeepsCopyAndConfirms | unit | P1 |
| KO03 | PASS | ClearingAndUndoTest.KO03_originalGoneKeepsCopyAndConfirms | unit | P1 |
| KO04 | PASS | ClearingAndUndoTest.KO04_checkFailedChangesNothing | unit | P1 |
| KO05 | PASS | ClearingAndUndoTest.KO05_neverDeletesTheOnlyRemainingCopy | unit | P1 |
| KO08 | PASS | ClearingAndUndoTest.KO08_itemNoLongerQueuedIsIgnored | unit | P2 |
| KO09 | PASS | ClearingAndUndoTest.KO09_changedOriginalOrCopyDeletesNothing | unit | P1 |
| KO14 | NOT AUTOMATED | – | device-only | P3 |
| KO10 | NOT AUTOMATED | – | device-only | P1 |
| KO11 | NOT AUTOMATED | – | device-only | P2 |
| KO12 | NOT AUTOMATED | – | device-only | P2 |
| KO13 | NOT AUTOMATED | – | device-only | P3 |
| TL01 | PASS | ClearingAndUndoTest.TL01_inTrashCountsFullOriginalSizeAndEarliestExpiry | unit | P1 |
| TL02 | PASS | ClearingAndUndoTest.TL02_goneCountsOriginalMinusCopyAsFreed | unit | P2 |
| TL03 | PASS | ClearingAndUndoTest.TL03_restoredByUserCountsNothing | unit | P2 |
| TL04 | PASS | ClearingAndUndoTest.TL04_originalStateMappingAndLookupErrorNeverClaimsFreed | unit | P1 |
| TL05 | PASS | ClearingAndUndoTest.TL05_trashExpiryDateComesFromTheTrashedOriginal | unit | P3 |
| TL07 | PASS | ClearingAndUndoTest.TL07_originalDeletedForGoodCountsAsFreed | unit | P2 |
| TL10 | NOT AUTOMATED | – | device-only | P2 |
| TL11 | NOT AUTOMATED | – | device-only | P2 |
| TL13 | NOT AUTOMATED | – | device-only | P2 |
| TL14 | NOT AUTOMATED | – | device-only | P3 |
| UR01 | PASS | ClearingAndUndoTest.UR01_originalBackDeletesOnlyTheCopy | unit | P1 |
| UR02 | PASS | ClearingAndUndoTest.UR02_originalStillInTrashKeepsCopy | unit | P1 |
| UR03 | PASS | ClearingAndUndoTest.UR03_originalGoneKeepsCopy | unit | P1 |
| UR04 | PASS | ClearingAndUndoTest.UR04_lookupFailedKeepsEverything | unit | P1 |
| UR05 | PASS | ClearingAndUndoTest.UR05_undoneOnlyFromConfirmed | unit | P2 |
| UR08 | PASS | ClearingAndUndoTest.UR08_cancelledRestoreChangesNothing | unit | P1 |
| UR09 | PASS | ClearingAndUndoTest.UR09_runOriginalsInTheTrashAreOfferedForRestore | unit | P2 |
| UR14 | PASS | ClearingAndUndoTest.UR14_undoNeedsOriginalStillInTrashAndUnchanged | unit | P1 |
| UR15 | PASS | ClearingAndUndoTest.UR15_restoredButChangedOriginalKeepsTheCopy | unit | P1 |
| UR16 | NOT AUTOMATED | – | device-only | P2 |
| UR10 | NOT AUTOMATED | – | device-only | P2 |
| UR11 | NOT AUTOMATED | – | device-only | P1 |
| UR12 | NOT AUTOMATED | – | device-only | P2 |
| UR13 | NOT AUTOMATED | – | device-only | P3 |
| D101 | PASS | ClearingAndUndoTest.D101_confirmedOriginalInTrashWithPublishedCopyIsEligible | unit | P1 |
| D102 | PASS | ClearingAndUndoTest.D102_notAConfirmedShrinkIsNotEligible | unit | P1 |
| D103 | PASS | ClearingAndUndoTest.D103_originalNotInTrashIsNotEligible | unit | P1 |
| D104 | PASS | ClearingAndUndoTest.D104_originalGoneIsNotEligible | unit | P1 |
| D105 | PASS | ClearingAndUndoTest.D105_lookupFailedIsNotEligible | unit | P1 |
| D106 | PASS | ClearingAndUndoTest.D106_copyNotVerifiablyPublishedIsNotEligible | unit | P1 |
| D107 | PASS | ClearingAndUndoTest.D107_onlyTheEligibleSubsetIsDeleted | unit | P1 |
| D110 | PASS | ClearingAndUndoTest.D110_deletedOriginalsBecomeFreedForGood | unit | P2 |
| D111 | PASS | ClearingAndUndoTest.D111_recordWithoutFingerprintIsNeverEligible | unit | P2 |
| D112 | PASS | ClearingAndUndoTest.D112_originalChangedInTheTrashIsNotEligible | unit | P1 |
| D113 | PASS | ClearingAndUndoTest.D113_mediaLibraryVersionChangedIsNotEligible | unit | P1 |
| D120 | NOT AUTOMATED | – | device-only | P2 |
| D121 | NOT AUTOMATED | – | device-only | P1 |
| D122 | NOT AUTOMATED | – | device-only | P3 |
| D123 | NOT AUTOMATED | – | device-only | P2 |
| D124 | NOT AUTOMATED | – | device-only | P3 |
| D125 | NOT AUTOMATED | – | device-only | P1 |
| RH01 | PASS | RecentChangesTest.RH01_shrinkRowsShowShrunkOrTrimmedAndTheSaving | unit | P2 |
| RH02 | PASS | RecentChangesTest.RH02_loggedRowsDetailAndFileSize | unit | P2 |
| RH03 | PASS | RecentChangesTest.RH03_newestFirstAcrossShrinksAndLoggedRows | unit | P3 |
| RH04 | PASS | RecentChangesTest.RH04_loggedRowWindowIsExactly30Times24Hours | unit | P2 |
| RH04b | PASS | RecentChangesTest.RH04b_shrinkRowsOlderThan30DaysStayWhileOriginalInTrash | unit | P2 |
| RH05 | PASS | RecentChangesTest.RH05_oldShrinkWithOriginalInTrashListedWithUndo | unit | P2 |
| RH05b | PASS | RecentChangesTest.RH05b_recentShrinkWhoseOriginalLeftTheTrashListedWithoutUndo | unit | P2 |
| RH06 | PASS | RecentChangesTest.RH06_onlyConfirmedShrinksAreListed | unit | P2 |
| RH07 | PASS | RecentChangesTest.RH07_undoneLoggedRowsAreNotListed | unit | P2 |
| RH08a | PASS | RecentChangesTest.RH08a_shrinkDeletedForGoodStaysListedWithoutUndo | unit | P2 |
| RH08b | PASS | RecentChangesTest.RH08b_movedRowDeletedForGoodStaysListedWithoutUndo | unit | P2 |
| RH10 | PASS | RecentChangesTest.RH10_loggedRowsOfferUndoUnlessDeletedForGood | unit | P2 |
| RH09 | PASS | RecentChangesTest.RH09_groupedUnderDateHeadersNewestDayFirst | unit | P3 |
| RH20 | NOT AUTOMATED | – | device-only | P3 |
| RH21 | NOT AUTOMATED | – | device-only | P1 |
| RH22 | NOT AUTOMATED | – | device-only | P2 |
| RH23 | NOT AUTOMATED | – | device-only | P1 |
| RH24 | NOT AUTOMATED | – | device-only | P3 |
| AR01 | PASS | AlbumRulesCoolingTest.AR01_ruleChoicesInOrderWithLabels | unit | P3 |
| AR02 | PASS | AlbumRulesCoolingTest.AR02_albumsListedMostItemsFirstThenNameUnnamedForBlank | unit | P3 |
| AR03 | PASS | AlbumRulesCoolingTest.AR03_olderThanNYearsUses365point2425DaysStrictlyAbove | unit | P2 |
| AR04 | PASS | AlbumRulesCoolingTest.AR04_unknownAgeIsNeverOld | unit | P2 |
| AR05 | PASS | AlbumRulesCoolingTest.AR05_lightCopyRuleOnlyForOldStillPhotos | unit | P2 |
| AR06 | PASS | AlbumRulesCoolingTest.AR06_lightCopyRuleDoesNotItselfExcludeFavourites | unit | P3 |
| AR07 | PASS | AlbumRulesCoolingTest.AR07_neverTouchExcludesPhotosAndVideos | unit | P1 |
| AR08 | PASS | AlbumRulesCoolingTest.AR08_albumLightCopyRuleBeatsCooling | unit | P2 |
| AR09 | PASS | AlbumRulesCoolingTest.AR09_neverTouchBeatsEverything | unit | P1 |
| AR10 | PASS | AlbumRulesCoolingTest.AR10_ruleStorageRoundTripAndUnknownIsDefault | unit | P3 |
| AR20 | NOT AUTOMATED | – | device-only | P3 |
| AR21 | NOT AUTOMATED | – | device-only | P1 |
| CL01 | PASS | PresetsProtectionSettingsTest.CL01_coolingDefaultsOffWithChoices0To3 | integration | P2 |
| CL02 | PASS | AlbumRulesCoolingTest.CL02_oldStillPhotoInDefaultAlbumCools | unit | P2 |
| CL02b | PASS | AlbumRulesCoolingTest.CL02b_coolingAgeBoundary | unit | P3 |
| CL03 | PASS | AlbumRulesCoolingTest.CL03_favouritesNeverCool | unit | P1 |
| CL04 | PASS | AlbumRulesCoolingTest.CL04_motionAndUltraHdrPhotosNeverCool | unit | P1 |
| CL05 | PASS | AlbumRulesCoolingTest.CL05_videosNeverCool | unit | P2 |
| CL06 | PASS | AlbumRulesCoolingTest.CL06_albumRulesWinOverCooling | unit | P2 |
| CL07 | PASS | AlbumRulesCoolingTest.CL07_coolingOffNeverCools | unit | P2 |
| CL10 | NOT AUTOMATED | – | device-only | P3 |
| CL11 | NOT AUTOMATED | – | device-only | P3 |
| SF01 | PASS | MakeRoomTripOvernightTest.SF01_gapIsGoalMinusFreeNeverBelowZero | unit | P2 |
| SF02 | PASS | PresetsProtectionSettingsTest.SF02_storageGoalDefaultsOffAndOnlyAllowsOfferedValues | integration | P2 |
| SF03 | PASS | MakeRoomTripOvernightTest.SF03_morningNoteTexts | unit | P2 |
| SF04 | PASS | MakeRoomTripOvernightTest.SF04_unreadableFreeSpaceAssumesNoGap | unit | P2 |
| SF10 | NOT AUTOMATED | – | device-only | P3 |
| SF11 | NOT AUTOMATED | – | device-only | P3 |
| SF12 | NOT AUTOMATED | – | device-only | P2 |
| MR01 | PASS | MakeRoomTripOvernightTest.MR01_noTargetNoPlan | unit | P3 |
| MR02 | PASS | MakeRoomTripOvernightTest.MR02_rungOneIsJpegPngH264WithSavingLargestFirst | unit | P2 |
| MR03 | PASS | MakeRoomTripOvernightTest.MR03_stopsAsSoonAsEstimateReachesTarget | unit | P2 |
| MR04 | PASS | MakeRoomTripOvernightTest.MR04_rungTwoOnlyWhenRungOneFallsShort | unit | P2 |
| MR05 | PASS | MakeRoomTripOvernightTest.MR05_rungTwoEligibility | unit | P2 |
| MR06 | PASS | MakeRoomTripOvernightTest.MR06_rungTwoIs70PercentAndMovedPhotoCountsOnlyExtraSaving | unit | P2 |
| MR07 | PASS | MakeRoomTripOvernightTest.MR07_rungsThreeAndFourSkipFavouritesAndEmptyNeverRepeatLargestFirst | unit | P2 |
| MR08 | PASS | MakeRoomTripOvernightTest.MR08_rungsOneAndTwoLeaveOutReadyToClearNeverTouchAndProtected | unit | P1 |
| MR08b | PASS | MakeRoomTripOvernightTest.MR08b_rungsThreeAndFourAlsoLeaveOutReadyToClearNeverTouchAndProtected | unit | P2 |
| MR09 | PASS | MakeRoomTripOvernightTest.MR09_rungTitles | unit | P3 |
| MR10 | PASS | MakeRoomTripOvernightTest.MR10_targetChoices | unit | P3 |
| MR11 | PASS | MakeRoomTripOvernightTest.MR11_planShortOfTargetSaysSo | unit | P3 |
| MR13 | PASS | MakeRoomTripOvernightTest.MR13_rungsOneTwoRunAsShrinkThreeFourOnlyOpenReview | unit | P1 |
| MR15 | PASS | MakeRoomTripOvernightTest.MR15_nightCapsLimitRungsOneAndTwo | unit | P2 |
| MR20 | NOT AUTOMATED | – | device-only | P2 |
| MR22 | NOT AUTOMATED | – | device-only | P3 |
| MR23 | NOT AUTOMATED | – | device-only | P2 |
| MR24 | NOT AUTOMATED | – | device-only | P3 |
| MR25 | NOT AUTOMATED | – | device-only | P1 |
| TP01 | PASS | MakeRoomTripOvernightTest.TP01_inTwoDaysIsTodayPlusTwo | unit | P2 |
| TP02 | PASS | MakeRoomTripOvernightTest.TP02_thisWeekendIsTodayOnWeekendElseComingSaturday | unit | P2 |
| TP03 | PASS | MakeRoomTripOvernightTest.TP03_nextWeekIsTheComingMonday | unit | P2 |
| TP04 | PASS | MakeRoomTripOvernightTest.TP04_runsAt20hTheDayBeforeOrImmediatelyIfPassed | unit | P2 |
| TP05 | PASS | MakeRoomTripOvernightTest.TP05_tripDisappearsAfterItsDayEnds | unit | P3 |
| TP06 | PASS | MakeRoomTripOvernightTest.TP06_dateLabel | unit | P3 |
| TP07 | PASS | MakeRoomTripOvernightTest.TP07_choices | unit | P3 |
| TP08 | PASS | MakeRoomTripOvernightTest.TP08_gapIsNeededMinusFreeAndEnoughSpaceMeansNothingToDo | unit | P2 |
| TP09 | PASS | MakeRoomTripOvernightTest.TP09_unattendedLightCopiesOnlyWhenOptedIn | unit | P2 |
| TP12 | PASS | PresetsProtectionSettingsTest.TP12_oneTripAtATimeNewPlanReplacesOld | integration | P2 |
| TP20 | NOT AUTOMATED | – | device-only | P3 |
| TP21 | NOT AUTOMATED | – | device-only | P2 |
| TP22 | NOT AUTOMATED | – | device-only | P2 |
| TP23 | NOT AUTOMATED | – | device-only | P2 |
| TP24 | NOT AUTOMATED | – | device-only | P2 |
| ON01 | PASS | MakeRoomTripOvernightTest.ON01_capsAre300PhotosAnd10VideosKeepingOrder | unit | P2 |
| ON02 | PASS | MakeRoomTripOvernightTest.ON02_eligibleKindsAreJpegPngH264NotUltraHdrOrMotion | unit | P1 |
| ON06 | PASS | MakeRoomTripOvernightTest.ON06_withAGoalLargestSavingsArePickedFirst | unit | P2 |
| ON10 | NOT AUTOMATED | – | device-only | P2 |
| ON11 | NOT AUTOMATED | – | device-only | P2 |
| ON12 | NOT AUTOMATED | – | device-only | P1 |
| ON14 | NOT AUTOMATED | – | device-only | P3 |
| ON15 | NOT AUTOMATED | – | device-only | P2 |
| ON16 | NOT AUTOMATED | – | device-only | P1 |
