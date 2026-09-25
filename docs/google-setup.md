# Google setup

**You don't need this to use Glance.** The release exes have a Google sign-in client built in: run it and sign in.

It's for using an OAuth client that belongs to **you**, for example when you build Glance yourself or want your own Google project. A `client.json` next to the exe takes priority over the built-in client. Nothing needs publishing or verifying, because you're the only person using it. It takes about 5 minutes.

## 1. Create a project and turn on the Calendar API

1. Open the [Google Cloud console](https://console.cloud.google.com/) and create a project. Any name works, for example `glance`.
2. Go to **APIs & Services → Library**, search for **Google Calendar API** and click **Enable**.

## 2. Configure the consent screen

1. Go to **APIs & Services → OAuth consent screen**. In newer consoles this is **Google Auth Platform → Branding / Audience**.
2. Choose **External**, give it an app name and add your email as the support and developer contact.
3. Under **Audience**, click **Publish app** so its status becomes **In production**.

> [!IMPORTANT]
> If you leave the app in **Testing**, Google expires your sign-in every **7 days**. An app that's in production but unverified is fine for personal use. You'll see a "Google hasn't verified this app" screen once: click **Advanced → Go to *your app***.

## 3. Create the OAuth client

1. Go to **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. For **Application type**, choose **Desktop app**.
3. Click **Download JSON**, rename the file to `client.json` and put it next to `Glance.exe`.

That's the whole setup. Glance accepts the downloaded file as it is (`{"installed": {...}}`), or a flat file:

```json
{ "client_id": "…apps.googleusercontent.com", "client_secret": "…" }
```

For desktop apps, Google doesn't treat the client secret as confidential, but keep `client.json` out of git anyway. The repo's `.gitignore` already excludes it.

## 4. Sign in

Start Glance. On first run it opens your browser at Google's account chooser, so you can pick any account, not just the one the browser is signed in to (useful on a work PC). Approve **read-only** calendar access and you're done. The browser redirects to a one-off local port (`http://127.0.0.1:<port>/`) that Glance listens on, and it uses PKCE.

The resulting refresh token is saved as `token.dat` next to the exe, encrypted with Windows DPAPI. Only your Windows user can decrypt it. If you copy the folder to another PC, you'll need to sign in once more.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Put client.json … next to Glance.exe" | The file is missing or isn't valid JSON |
| `redirect_uri_mismatch` | The client is a *Web application*. Create a **Desktop app** client instead |
| You're signed out every week | The consent screen is still in **Testing**. Publish it (step 2) |
| Signed in with the wrong account | Right-click → **Sign out**, then **Sign in with Google…** and pick the right account. If it isn't listed, choose **Use another account** |
| "Sign-in failed" after a long wait | You have 15 minutes to finish in the browser. Click the widget's sign-in line to try again |
