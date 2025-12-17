# Strawberry Auth Server

Authentication backend for the Strawberry voice assistant app. Handles Google Sign-In and provisions API keys for users.

## Features

- Google OAuth token verification
- Per-user OpenRouter API key provisioning with $1 credit limit
- Shared Cartesia API key distribution
- User tracking with Vercel KV

## Setup

### 1. Google Cloud Console

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing
3. Enable "Google Sign-In" API
4. Create OAuth 2.0 credentials:
   - Application type: **Android**
   - Package name: `com.lorem.strawberry`
   - SHA-1 fingerprint: Get from Android Studio or `keytool`
5. Also create a **Web** client ID (used for token verification)
6. Copy the Web Client ID for `GOOGLE_CLIENT_ID`

### 2. OpenRouter

1. Go to [OpenRouter](https://openrouter.ai/keys)
2. Create a provisioning key (or use your main key)
3. Add credits to your account
4. Copy the key for `OPENROUTER_PROVISIONING_KEY`

### 3. Cartesia

1. Go to [Cartesia](https://cartesia.ai/)
2. Get your API key
3. Copy for `CARTESIA_API_KEY`

### 4. Deploy to Vercel

```bash
cd authserver
npm install
vercel login
vercel
```

Then add environment variables in Vercel Dashboard → Settings → Environment Variables.

### 5. (Optional) Add Vercel KV for persistence

1. In Vercel Dashboard → Storage → Create → KV
2. Connect to your project
3. Environment variables are auto-added

Without KV, user data is stored in-memory (resets on redeploy).

## API Endpoints

### POST /api/auth

Authenticate a user and get API keys.

**Request:**
```json
{
  "idToken": "google-id-token-from-android-app"
}
```

**Response:**
```json
{
  "success": true,
  "openRouterKey": "sk-or-v1-user-specific-key",
  "cartesiaKey": "shared-cartesia-key",
  "user": {
    "email": "user@gmail.com",
    "name": "User Name",
    "picture": "https://..."
  }
}
```

## Local Development

```bash
npm install
cp .env.example .env
# Fill in .env with your keys
npm run dev
```

Test with curl:
```bash
curl -X POST http://localhost:3000/api/auth \
  -H "Content-Type: application/json" \
  -d '{"idToken": "test-token"}'
```

## Architecture

```
User opens app
    ↓
Signs in with Google (Android SDK)
    ↓
App gets Google ID token
    ↓
App sends token to /api/auth
    ↓
Server verifies token with Google
    ↓
Server creates/retrieves user's OpenRouter key
    ↓
Server returns keys to app
    ↓
App stores keys in EncryptedSharedPreferences
```
