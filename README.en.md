<p align="center">
  <img src="docs/assets/readme-cover-en.svg" alt="TaxyRay reads your receipts and shows the tax inside" width="100%" />
</p>

<h1 align="center">TaxyRay</h1>

<p align="center">
  <strong>Read the receipt  see the tax inside</strong><br/>
  Scan a bill or pick a screenshot, then get the price and the tax for every item
</p>

<p align="center">
  <a href="https://github.com/Changjingjiu/TaxyRay/releases/latest"><img src="https://img.shields.io/github/v/release/Changjingjiu/TaxyRay?style=flat-square&amp;color=08785a" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-08785a?style=flat-square" alt="Android 8.0 and up" />
  <a href="https://github.com/Changjingjiu/TaxyRay/actions/workflows/android.yml"><img src="https://github.com/Changjingjiu/TaxyRay/actions/workflows/android.yml/badge.svg" alt="Android build and tests" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-08785a?style=flat-square" alt="MIT License" /></a>
</p>

<p align="center">
  <a href="https://github.com/Changjingjiu/TaxyRay/releases/latest"><strong>Download for Android</strong></a> ·
  <a href="README.md">中文</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://github.com/Changjingjiu/TaxyRay/issues">Report an issue</a>
</p>

## What this is

Bring your own AI service and TaxyRay reads paper receipts, shopping screenshots and e-bills, splits what you paid into a pre-tax amount and tax, and keeps a local ledger of it. The model only reads the bill — every amount is recomputed on your phone in exact decimal and integer-cent arithmetic, and nothing is booked until you confirm it.

> What you see is the VAT included in the price you paid. It is not a sum of every tax, not what the merchant actually remits, and not a tax certificate

## Features

- **Bill reading** Paper receipts, shopping screenshots and e-bills, up to 5 images and 30 orders per round
- **Price and tax per item** 13% / 9% / 6% / 0% and custom rates, with the working and the rate sources shown
- **Discounts and rounding** Order-level discounts spread across items in proportion, with the leftover cent assigned by largest remainder
- **One-tap review** Book the paid, unambiguous bills in one go, with duplicate checks against your ledger and against the same batch
- **Payment status check** Skip unpaid orders; unclear ones need your confirmation and the real paid total
- **Contribution card** Share the estimated tax of a single bill or of the whole ledger, in two templates

## Screens

<table>
  <tr>
    <td align="center" width="50%"><img src="docs/assets/screen-dashboard.png" alt="Overview with an estimated VAT of 24.58 yuan" width="100%" /></td>
    <td align="center" width="50%"><img src="docs/assets/screen-scan-batch.png" alt="Scan results with three bills and a one-tap review card" width="100%" /></td>
  </tr>
  <tr>
    <td align="center">Estimated tax and recent bills</td>
    <td align="center">Several bills at once, reviewed one by one or in one tap</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/assets/screen-review.png" alt="Review screen: 59.24 yuan of items, 59.20 paid, 4 fen discount spread across them" width="100%" /></td>
    <td align="center"><img src="docs/assets/screen-detail.png" alt="Bill detail: 113 yuan paid, 100.43 before tax, 12.57 in estimated tax" width="100%" /></td>
  </tr>
  <tr>
    <td align="center">Order-level discounts spread by paid amount</td>
    <td align="center">Every item gets its own pre-tax amount and tax</td>
  </tr>
</table>

<sub>Android 16 emulator screenshots with demo data — not an actual AI reading. The app UI is Chinese-only for now</sub>

<table>
  <tr><th width="50%">Paper</th><th width="50%">Forest</th></tr>
  <tr>
    <td><img src="docs/assets/contribution-paper.png" alt="Paper contribution card: 242 paid, 22 estimated tax" width="100%" /></td>
    <td><img src="docs/assets/contribution-forest.png" alt="Forest contribution card: 242 paid, 22 estimated tax" width="100%" /></td>
  </tr>
</table>

<sub>Exports are at least 1080 px wide; the haptic and confetti feedback never lands in the image</sub>

## Getting started

1. Download the APK from [Releases](https://github.com/Changjingjiu/TaxyRay/releases/latest) and install it on Android 8.0 or later
2. In **Settings → Receipt AI**, enter your service URL, a model that supports images and tool calling, and your own API key
3. Tap **Scan bills** on the home screen, take photos or pick images, then review each bill and confirm

You can also add a bill by hand — the ledger and the math work without a network connection

[Recognition rules and limits](docs/AI_RECOGNITION.md) · [Algorithm and boundaries](docs/ALGORITHM.md) · [Tax policy](docs/TAX_POLICY.md)

## Data and privacy

- Your ledger, scan drafts and API settings stay on the device; no account, no ads, no telemetry
- The API key is encrypted with the Android Keystore and never included in a backup
- The app goes online only when you start a scan, and only to the endpoint you configured
- Backups are plain JSON / CSV — keep them somewhere safe before switching phones

[Privacy](docs/PRIVACY.md) · [Security reports](SECURITY.md) · [Updates and signing](docs/UPDATES.md)

## Development

Kotlin · Jetpack Compose · Room · OkHttp. Requires JDK 17 and Android SDK 35

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

[Build and test](docs/DEVELOPMENT.md) · [Contributing](CONTRIBUTING.md) · [MIT License](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
