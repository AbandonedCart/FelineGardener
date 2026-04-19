# Feline Gardener

Android app for cat owners to search ASPCA (non)toxic plant information.

## Features
- Downloads the ASPCA cats toxic plant list from:
  - `https://www.aspca.org/pet-care/animal-poison-control/cats-plant-list`
- Splits plants into:
  - `Plants Toxic to Cats`
  - `Plants Non-Toxic to Cats`
- Shows a searchable list across both groups
- Displays plant photos from ASPCA plant detail pages
- Shows alternate plant names as card subtitles
- Tapping a plant opens the ASPCA detail page
- Shows an in-app update banner when a newer Release is available
- Tapping the update banner downloads and installs the latest APK

## Release signing and publishing
- Release keystore is committed at:
  - `app/signing/release.keystore`
- GitHub Actions workflow:
  - `.github/workflows/android-release.yml`
- Release publication:
  - On `main` pushes, publishes a GitHub Release tagged with the short GitHub commit hash
  - Release APK filename format uses the short hash value, e.g. `FelineGardener-abc1234.apk`
- Configure repository secrets used by the workflow:
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`

## Disclaimer
This app is not affiliated with or endorsed by the ASPCA. All content is provided for education purposes only.

https://www.aspca.org/about-us/linking-policy
