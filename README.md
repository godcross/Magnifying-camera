# Magnifier Cam

A magnifying glass and close-up camera for Android (built and tuned for Samsung phones).
Made for photographing tiny things like a turntable stylus tip, small print, splinters, or hardware markings.

**Private by design:** the app has **no internet permission**, no ads, no analytics, and no accounts.
It only uses the camera, the flashlight and the gyroscope. Photos are saved to the phone at
`Pictures/MagnifyingCamera` (they also show up in Samsung Gallery).

## Features

**Magnify**
- Smooth zoom slider, pinch-to-zoom, and quick stops (1×, 2×, 4×, 8×, 16×, Max). Uses the phone's
  telephoto lens automatically where Samsung allows it.
- Freeze frame: lock the current view and pinch in to read it, then save it if you want.
- Grid with a centre crosshair for lining up small parts.
- Lens switch button appears if the phone exposes more than one rear camera to apps.

**Light and exposure**
- Flashlight on/off.
- Brightness slider (exposure compensation, shown in EV) to tame glare off shiny parts like a cantilever.

**Focus**
- Tap to focus and meter; focus stays locked on that spot until you release it.
- Manual focus slider, shown as distance in cm and inches when the lens reports it, so you can
  set focus at the lens's closest distance and move the phone instead.

**Sharp photos**
- Hardware stabilization: optical (OIS) and electronic (EIS) are switched on where the phone supports them.
- **Steady shot**: uses the gyroscope and waits until the phone stops moving before it fires.
  The dot in the top-left turns green when the phone is still.
- **Best of 3 / Best of 5**: takes a quick burst and keeps only the sharpest frame (measured by fine detail
  in the centre of the picture). The original JPEG is saved, with no re-compression.
- Self-timer (2, 5, 10 s), so touching the screen doesn't shake the shot.
- Volume buttons work as a shutter.
- Highest-resolution capture mode at JPEG quality 98.

**Photos**
- Built-in gallery and viewer: pinch up to 12× to check focus, swipe between photos, share, and delete.

## Getting the APK

Every push to `main` builds an APK with GitHub Actions:

1. Open the repo on GitHub → **Actions** → latest **Build APK** run.
2. Download the **MagnifierCam-apk-N** artifact (a zip), and unzip it to get the `.apk`.
3. Copy it to the phone and open it. Android will ask you to allow installs from that app (Files / My Files) once.

Each build is signed with the same key (`app/magnifier-release.jks`), so new versions install over old ones
and your photos and settings stay put. That key is in a public repo, so it's only suitable for sideloading;
make your own if you ever publish this to a store.

## Tips for stylus photos

1. Turn the **Light** on, then lower **Brightness** until the stylus isn't blown out.
2. Brace the phone on something solid (a block or the edge of the plinth); use **Timer 2s** or a volume button.
3. Zoom to 2–4× rather than putting the lens very close; the main camera can't focus closer than a few cm.
   The status line shows the lens's closest focus distance.
4. Tap the stylus tip to lock focus, then shoot with **Best of 5**.

## Building locally

Android Studio (Ladybug or newer) → Open this folder → Run. Or from the command line with JDK 17:
`./gradlew assembleRelease`.

Tech: Kotlin, CameraX 1.4 with Camera2 interop, minimum Android 8.0, target Android 15.
