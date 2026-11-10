# ShadowPlayer

Native Android video player (Kotlin + Media3) with the full-screen sentence-practice features of SHADOWit.
No web page, no uploads: open the app, allow file access, pick a video.

## Use
- Videos are scanned automatically (All / Folders, search, sort).
- Tap a video: full screen opens at once. Controls are hidden; gestures:
  - tap middle = play/pause, tap left/right = previous/next sentence (instant, tap as fast as you like), double tap in the MIDDLE only = show/pin the seek bar and controls
  - quick tap with two fingers = add the sentence on screen to the starred list
  - slide left/right = seek, slide up/down on left half = brightness, right half = volume
- Button strip: previous, play, next, unlink, hide, bookmark, audio track, repeat, star, translated subtitle, copy, edit, delete, subtitles on/off, jump, landscape/portrait, lock.
- 3-dot menu: delay, playback settings (speed, passage loops, lines per group, sync, shuffle, starred only), previous/next video, upload subtitles/translated subtitles, Words, Range (pin / hide / delete), starred list, bulk delete.
- Subtitles: loaded from the video itself (MKV/MP4 text tracks), from a .srt next to the video (needs the one-time "All files access", menu entry), or uploaded from the 3-dot menu. They are remembered per video together with stars, bookmarks, hidden lines and position.
- Without subtitles it behaves as a normal player.

## Floating subtitle window
3-dot menu > Floating subtitle window: turn it on and allow the three permissions (Display over other apps, Notifications, no battery limit) from the same screen.
When the app is minimized, only the subtitle line shows over other apps with previous / play-pause / next. Tap the sentence to copy it, drag the small bar on top to move it. The notification has a Hide / Show switch (and Turn off).

## Starred subtitles
Stars no longer draw marks on the seek bar (bookmarks still do). 3-dot menu > Starred subtitles has Copy all, and there is also 3-dot menu > Copy all starred subtitles.

## Offline YouTube
In the Play a YouTube link window choose the quality (144p saves the most space), paste the link and tap Download offline: the video and its subtitles (.srt) are saved in the app. A saved video plays without internet. Offline videos in the same window lists them; use the bin to delete one or Delete all to clear everything.

## YouTube links (personal use)
- Library top bar > YouTube icon: paste a link (or it picks up a link from the clipboard). Recent links are listed; hold one to remove it.
- Or in the YouTube app: Share > ShadowPlayer.
- The stream is found with NewPipe Extractor and played by the same player, so sentence repeat, speed, loudness boost and the smart pen all work.
- Captions: the video's own YouTube captions load automatically (your last language, else English; written by a person before automatic). Subtitles icon > pick another language, turn off, or upload a .srt.
- Voice-accurate timing: automatic captions are rebuilt from the exact second of every spoken word and cut into sentences at real pauses, so a repeat starts on the first word and stops right after the last. When a person-written caption and an automatic one exist in the same language, the person's text is kept and its start / end are moved to the spoken words (the toast says "timed to the voice"). If the two do not match well, or anything fails, the caption keeps its own timing. Videos you already opened keep their saved subtitles: pick the caption again in the Subtitles menu to rebuild it with the new timing.
- Each repeat stops at the exact end position (ExoPlayer position trigger, with a fast timer as backup).
- Picture quality limit (144 / 240 / 360 / 480 / 720 / 1080p; 144p saves the most data) is set in the same link dialog. Stream addresses that expire are looked up again automatically and playback continues at the same second.
- YouTube changes often. If a link stops opening with a message about the extractor, raise the NewPipeExtractor version in `app/build.gradle.kts` and rebuild.

## Build the APK
Push this folder to a GitHub repo (branch `main`); **Actions > Build ShadowPlayer APK** produces `ShadowPlayer.apk` (Releases > latest, or run Artifacts).
The signing key `app/raj-hub.jks` is included for personal use only.
