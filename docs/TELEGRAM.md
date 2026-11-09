# Talk to WeiAssist from Telegram

Message the assistant on your phone from anywhere: from Telegram on another phone, a tablet or a computer.
It works through a Telegram bot that you create and that only you can use. There is no server in between:
WeiAssist on your phone fetches the bot's messages from Telegram itself.

## Set it up (about 2 minutes)

1. In Telegram, open [BotFather](https://t.me/BotFather), send `/newbot` and choose a name. BotFather gives
   you a **token** (it looks like `123456789:AA…`).
2. In WeiAssist: **Settings → Telegram**, paste the token, tap **Connect**.
3. Tap **Pair a Telegram account**, then **Open in Telegram** and press **Start**. (Or send the code shown in the
   app to your bot.) The code works once, for 10 minutes.

That's it: send your bot a message. The bot ignores everyone you haven't paired.

## What you can do

- **Chat:** each Telegram chat is a conversation of its own, also in the app's list (marked *Telegram*). Answers
  stream in as they are written, and the steps the assistant takes show in one status message.
- **Commands:** `/new` starts a new conversation, `/stop` stops the current task, `/status` says what it is doing,
  `/screen` sends a picture of the screen it is working on.
- **Photos and files:** send pictures, PDFs, text and other files (up to 20 MB, Telegram's limit for bots); an
  album arrives as one message. With Linux commands allowed, every file is also saved in the conversation's
  Termux folder (`inbox/`), so it can work with spreadsheets, archives or the photo itself.
- **Files back:** ask for a chart, a document or a video and it sends the file to the chat (up to 50 MB; pictures
  show as photos).

## What it may do from Telegram

Choose in **Settings → Telegram → What it may do from Telegram** (one choice for all chats):

| Choice | It can |
|---|---|
| Only talk | answer, nothing else |
| Talk and run Linux commands (default) | also run commands in [Termux](TERMUX.md), like a conversation in the app |
| Also use the phone's apps | also operate apps on the phone (needs the accessibility service) |

With phone apps allowed, before anything like **Send** or **Delete** it asks you, both in the chat (✅ Allow /
❌ Deny buttons) and on the phone's screen; the first answer counts. **Payments can only be allowed on the phone
itself**, never from the chat. A question nobody answers within 15 minutes counts as *Deny*.

## Good to know

- **Privacy:** bot chats are not end-to-end encrypted. What you send the bot and what the assistant answers
  (including files and screenshots) pass through Telegram's servers. Your bot token is stored encrypted on the
  phone and never given to Termux or the AI model.
- **Anyone you pair** can use the assistant (and Termux, and phone apps if you allowed them) on this phone. Remove
  accounts in Settings → Telegram.
- **The phone must be on, online, and WeiAssist running.** While the bot is on, WeiAssist keeps a notification so
  Android lets it run. Some phones still stop apps in the background: turning on WeiAssist's accessibility service,
  or allowing it to run in the background without battery restrictions, helps.
- **After the phone restarts or WeiAssist is updated**, open the app once to bring the bot back. A task that was
  running then shows as *Interrupted* in the app; send it again or tap **Continue** there.
- **Phone tasks while the phone is locked** may not work.
- **Voice messages** need a voice server, set under Settings → Voice input (see `tools/voice-server`). The bot shows
  what it heard and then acts on it right away. The phone must be able to reach the server.
- Not supported yet: videos and stickers; group chats.
