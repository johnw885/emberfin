# Store assets

- `graphics/*.html` are the sources of the Play icon (512×512), TV banner (1280×720) and feature
  graphic (1024×500). `graphics/render.ps1` renders them with headless Microsoft Edge into
  `fastlane/metadata/android/en-US/images/`. They use the app's Inter font and Marquee amber `#FFB627`.
- TV screenshots (1920×1080) live in `fastlane/metadata/android/en-US/images/tvScreenshots/`. They
  must only show Creative Commons content (the current set uses Blender Foundation open movies, CC BY)
  and never a real server address, username or personal library.
- Listing text (title, short and full description, changelogs named by version code) is in
  `fastlane/metadata/android/en-US/`, the layout both F-Droid and fastlane `supply` read.
