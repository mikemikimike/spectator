# Spectator

Spectator is a Telegram bot that watches public Telegram channels and notifies Task owners about new Channel Posts.

## Language

**Invocation notification**:
A Telegram message emitted for each HTTP request handled by the Worker.
_Avoid_: Worker startup notification, Telegram alert

**Bot API snapshot**:
A checked-in local copy of the official Telegram Bot API reference used by the project's Telegram integration skill.
_Avoid_: SDK documentation, framework guide

**Task**:
A public Telegram channel link and optional Selection Rule stored by the bot and owned by one Telegram user ID. It watches for Channel Posts published after it was added; only its owner can list or delete it.
_Avoid_: text task, team task, shared task, Channel Task

**Selection Rule**:
A Task-specific set of required and prohibited keywords matched case-insensitively against a Channel Post's text or caption. Keywords may contain punctuation; surrounding punctuation in the post is ignored. A post matches when it contains any required keyword (or none are configured) and no prohibited keyword. Prohibited keywords take precedence. Posts without analysable text match; Tasks without a Selection Rule match every post.
_Avoid_: global filter, channel filter

**Task owner**:
A Telegram user ID that owns at least one stored Task.
_Avoid_: User, bot user

**Channel Post**:
A publication made by a Telegram channel. Later edits, deletion, and comments do not create a new Channel Post.
_Avoid_: Telegram message, update

**Channel notification**:
A Telegram message sent to a Task owner when Spectator observes a new Channel Post. Delivery is periodic and may occur more than once.
_Avoid_: real-time alert, exactly-once notification
