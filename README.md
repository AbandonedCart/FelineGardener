# FelineGardener

Android app for cat owners to browse ASPCA toxic plants.

## Features
- Downloads the ASPCA cats toxic plant list from:
  - `https://www.aspca.org/pet-care/animal-poison-control/cats-plant-list`
- Splits plants into:
  - `Plants Toxic to Cats`
  - `Plants Non-Toxic to Cats`
- Shows a searchable list across both groups
- Displays plant photos from ASPCA plant detail pages
- Shows alternate plant names as card subtitles
- Shows an in-app update banner when a newer GitHub Release is available
- Tapping a plant opens the ASPCA detail page

## Release signing and publishing
- Release keystore is committed at:
  - `app/signing/release.keystore`
- GitHub Actions workflow:
  - `.github/workflows/android-release.yml`
- Release publication:
  - On `main` pushes (and manual dispatch), publishes a GitHub Release tagged with the short GitHub commit hash
  - Release APK filename format uses the actual short hash value, e.g. `FelineGardener-abc1234.apk`
- Configure repository secrets used by the workflow:
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`

## Screenshot
![App screenshot](https://github.com/user-attachments/assets/ab189812-3608-41e5-9215-61e5296830af)
