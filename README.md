# MuteBridge

Syncs **EssentialsX** `/mute` to **SimpleVoiceChat** instantly.

## What it does

When an admin runs `/mute <player>` in EssentialsX, this plugin automatically mutes that player in SimpleVoiceChat too. No reconnection needed — it syncs every **5 seconds** and instantly on **player join** and **command use**.

## Requirements

- Paper server (1.20.x or newer)
- **EssentialsX**
- **SimpleVoiceChat**

## Build (no local Java needed)

1. Upload this entire folder to a **GitHub repo** (not a zip — the extracted files/folders).
2. Go to **Actions** → wait for the green checkmark.
3. Download the artifact and extract the `.jar`.

## Install

1. Drop `MuteBridge-1.0.0.jar` into your server's `/plugins/` folder.
2. Restart the server.
3. Done — `/mute` now also mutes voice chat.

## If your server is 1.21+

Change `api-version` in `plugin.yml` from `'1.20'` to `'1.21'`, and change `java-version` in `.github/workflows/build.yml` from `'17'` to `'21'`. Then rebuild.

## How it works

- Uses **reflection** to read EssentialsX mute status and control SimpleVoiceChat mute state.
- No compile-time dependency on either plugin, so it won't break on updates.
- A lightweight background task checks all online players every 5 seconds.
- Also syncs immediately when players join or when `/mute`/`/unmute` commands are detected.
