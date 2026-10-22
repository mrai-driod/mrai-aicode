# About & Updates

"Settings → About" shows app information and is also the entry point for checking updates.

## Version & Checking for Updates

The top card shows the app icon, app name, and a one-line introduction; the "Version" row below it shows the current version number.

**Tap the version row to check for updates manually**: this queries the GitHub release list for a newer version (filtered by your selected update channel). When a new version is found, a dialog shows "New version found" and lists **all changelogs from your current version up to the latest version**. Tap "Go to Download" to open the Release page **of the detected version** in your browser and get the APK. If you are already on the latest version, it shows "You're on the latest version"; if the network fails, it shows "Check failed" along with the error message.

## Automatic Update Checks

Enabled by default. When you open the app and reach the home page, it checks asynchronously in the background, **at most once a day** (if it already checked today, it won't repeat). When a new version is found, the update dialog pops up automatically; network failures are handled silently so you aren't disturbed.

Once turned off, automatic checks stop, but you can still check manually by tapping the version row.

## Update Channels

The default is "Stable":

- **Stable**: only detects official releases, filtering out RC previews.
- **Latest**: includes RC previews.

Switch to Latest if you want to try new features early; stay on Stable if you prefer stability.

Whichever channel you choose, "Go to Download" jumps to the Release page of the version shown in the dialog: if the Latest channel detects an RC preview, it opens that RC's page rather than landing on an older stable release.

## Other Entries

- **GitHub repository**: opens the source repository in your system browser.
- **Open source license**: this project uses GPL-3.0; tap to open the full LICENSE file.
