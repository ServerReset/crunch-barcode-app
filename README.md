# Crunch Barcode

A lightweight Android app that logs into your Crunch Fitness account and generates your check-in barcode. No ads, no tracking, no extra features — just your barcode.

## Features

- **Login** to Crunch Fitness with your member credentials
- **Display barcode** ready for scanner at the gym (QR Code or CODE128)
- **Save to Google Wallet** for quick access (uses Crunch's existing Google Pay integration)
- **Encrypted credential storage** via Android Keystore
- **Material 3** design with dynamic color support

## Download

Grab the latest APK from the [Releases page](https://github.com/ServerReset/crunch-barcode-app/releases).

The APK is signed with V1, V2, and V3 signature schemes and zipaligned. If installation is blocked, tap **More Details** → **Install Anyway**.

## How It Works

The app calls the same Netpulse REST API that the official Crunch Fitness app uses:

| Step | API Endpoint | Purpose |
|------|-------------|---------|
| 1 | `POST /np/exerciser/login` | Authenticate with email/password |
| 2 | `GET /np/exerciser/{uuid}/membership-barcode` | Fetch barcode value |
| 3 | `GET /np/exercisers/{uuid}/google/pay/barcode` | Get Google Wallet JWT |

Barcodes are rendered client-side with [ZXing](https://github.com/zxing/zxing) — no data ever leaves your device after the initial fetch.

## Download

Grab the latest APK from the [Releases page](https://github.com/YOUR_USERNAME/crunch-barcode-app/releases).

## Setup for Development

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35

### Build
```bash
git clone https://github.com/ServerReset/crunch-barcode-app.git
cd crunch-barcode-app

# Generate a release keystore (only needed once)
keytool -genkey -v -keystore app/release.keystore -alias release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Dev, OU=Dev, O=CrunchBarcode, L=City, S=State, C=US"

# Build
KEYSTORE_PASSWORD=android KEY_ALIAS=release KEY_PASSWORD=android ./gradlew assembleRelease
```

### Google Wallet (Optional)
The "Save to Wallet" button uses Crunch's existing Google Pay barcode endpoint. It returns a JWT that opens the Google Wallet save page. **No additional Google Cloud setup is needed** — the JWT is signed by Crunch's servers.

## Security
- Credentials are encrypted at rest using `EncryptedSharedPreferences` (Android Keystore-backed AES-256)
- No analytics, crash reporting, or third-party SDKs
- All network calls use HTTPS
- Session cookies are stored in memory only

## Disclaimer
This is an independent project not affiliated with Crunch Fitness or Netpulse. Use at your own risk.
