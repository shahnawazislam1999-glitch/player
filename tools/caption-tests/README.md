JVM tests for the caption timing code (no Android needed). They compile `Srt.kt`, `YtCaptions.kt`, `CaptionAligner.kt`
and `Tests.kt` with any Kotlin compiler and run `TestsKt`, e.g.:

    kotlinc -language-version 1.9 app/src/main/java/com/raj/shadowplayer/{Srt,YtCaptions,CaptionAligner}.kt tools/caption-tests/Tests.kt -d out
    kotlin -cp out TestsKt

The simulation builds 60 sentences of known speech, a YouTube-style rolling caption with 12% recognizer errors, and a person-style
caption with loose timing, then measures how often a cue clips speech and how much extra picture it includes.
