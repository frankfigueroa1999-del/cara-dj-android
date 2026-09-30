CARA DJ FOR ANDROID
===================
Same Cara as the PC and iPhone apps: Eleven v4 voice, stingers, moods, trivia,
queue buttons, sliders. Android can really turn Spotify down while she talks
(and it keeps running with the screen off).

GET THE APP (GitHub builds it for you, about 5 minutes)
-------------------------------------------------------
1. On GitHub, make a NEW repository (or reuse an empty one), e.g. "cara-android".
2. Upload everything in this folder EXCEPT the hidden ones (drag the files in):
   settings.gradle.kts, build.gradle.kts, gradle.properties, the whole "app" folder.
   (Make sure app/debug.keystore and app/src/main/assets/stingers/*.mp3 went up.)
3. Hidden folders don't upload from the web page, so add these two by hand with
   "Add file > Create new file":
   a) name:  .github/workflows/build.yml   (type the slashes, it makes the folders)
      paste in the contents of the build.yml file from this folder.
   b) name:  .gitignore   (optional) paste in the .gitignore contents.
4. Click the "Actions" tab. The build starts by itself. Wait for the green check.
   If you don't see it: Actions > "Build Cara DJ (Android)" > Run workflow.
5. Click the finished run, scroll to "Artifacts", download "CaraDJ-apk".
   Unzip it: inside is app-debug.apk.

INSTALL
-------
1. Get app-debug.apk onto your phone (email it, Google Drive, or USB).
2. Tap it. If Android asks, allow "Install unknown apps" for the app you opened
   it from (Chrome / Files / Drive), then tap Install. If Play Protect warns,
   choose "Install anyway" (it's your own app).
3. Later updates install right over the top and keep your settings.

FIRST RUN
---------
1. Spotify dashboard (developer.spotify.com/dashboard) > your app > Settings >
   add Redirect URI:   caradj://callback     (same one the iPhone app uses).
   Your Client ID is the same one you already have.
2. Open Cara DJ > Settings: paste Client ID, ElevenLabs sk_ key + Voice ID,
   Gemini key. Model is Eleven v4 already. Tap CONNECT SPOTIFY, log in, and you
   get sent back to the app.
3. Allow notifications when asked (it shows a small "Cara DJ is live" card
   while the DJ runs; that's what keeps it alive with the screen off).
4. Android Settings > Apps > Cara DJ > Battery > Unrestricted. Important, or
   some phones (Samsung especially) freeze it after a few minutes.
5. Start a playlist in the Spotify app, come back, tap START DJ.

NOTES
-----
- Talk-over / over-intro breaks: Android turns Spotify down for her. How far it
  turns down is up to Android and Spotify (there's no duck slider on the phone).
  If Spotify pauses instead of ducking on your phone, tell me.
- Silent breaks work like on the PC: pause, stinger, Cara, then resume.
- Spotify needs Premium for the pause/skip/resume controls.
- To change Cara's words or feeds, edit Content.kt on GitHub (pencil icon),
  commit, and download the new APK from Actions.
