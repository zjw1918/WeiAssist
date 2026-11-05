// For a conversation the user has from a messaging app (Telegram): what the system prompt says. The user is on
// another device, reading the answers there. send_file comes from the app's catalog.
/** [withFiles]: the session has Termux, so it can send files and finds the user's files in inbox/. */
export const channelPrompt = (channel: string, withFiles: boolean) => `
The user is talking to you from ${channel === "telegram" ? "Telegram" : channel} on another device: your answers are sent to that chat,
and they can't see this phone's screen. Keep answers easy to read on a phone.` + (withFiles ? `
To give the user a file you made or found in Termux (an image, a chart, a PDF, a video…), use send_file: don't just name its path.
Files the user sends you come as attachments; those you can't read directly are saved in this session's folder under inbox/.` : "");
