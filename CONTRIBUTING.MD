# Contributing

YTindr is a personal fork of [YTDLnis](https://github.com/deniscerri/ytdlnis), so it does not take outside feature requests.

- Problems with yt-dlp itself (a site that no longer works, a missing format) belong upstream at [yt-dlp](https://github.com/yt-dlp/yt-dlp/issues).
- Problems that also happen in the original app belong at [YTDLnis](https://github.com/deniscerri/ytdlnis/issues).

## Building

Every push to `main` is built by GitHub Actions (`.github/workflows/android.yml`) and published as a signed arm64 release. Changes that only touch documentation (`*.md`, `docs/`, `fastlane/`) do not start a build.

To build locally, open the project in a recent Android Studio and build the `fossRelease` variant.
