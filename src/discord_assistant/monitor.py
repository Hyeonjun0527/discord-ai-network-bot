"""monitor.py — Developer alert notifications via Discord DM.

Usage
-----
Call `notify_developer(message, bot)` from bot events (on_disconnect, on_error, etc.)
to send a DM to the Discord user whose ID is set in DEVELOPER_USER_ID.

Environment variable
--------------------
DEVELOPER_USER_ID  — numeric Discord user ID of the person who should receive alerts.
                     If unset or invalid, the notification is skipped and a warning is logged.
"""
from __future__ import annotations

import logging
import os
import traceback
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from discord.ext import commands

logger = logging.getLogger(__name__)


def _developer_user_id() -> int | None:
    """Read DEVELOPER_USER_ID from the environment. Returns None if unset or invalid."""
    raw = os.getenv("DEVELOPER_USER_ID", "").strip()
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        logger.warning(
            "DEVELOPER_USER_ID='%s' is not a valid integer — notifications disabled.", raw
        )
        return None


async def notify_developer(message: str, bot: "commands.Bot") -> None:
    """Send a DM alert to the developer identified by DEVELOPER_USER_ID.

    Parameters
    ----------
    message:
        Plain-text message to send (will be truncated to 1900 chars to stay within
        Discord's message length limit).
    bot:
        The running discord.py Bot instance, used to fetch the user and send the DM.
    """
    user_id = _developer_user_id()
    if user_id is None:
        logger.debug("notify_developer: DEVELOPER_USER_ID not configured, skipping DM.")
        return

    # Truncate to safe Discord length
    MAX_CHARS = 1900
    if len(message) > MAX_CHARS:
        message = message[: MAX_CHARS - 3] + "..."

    try:
        user = bot.get_user(user_id) or await bot.fetch_user(user_id)
        await user.send(message)
        logger.info("Developer notification sent to user %s.", user_id)
    except Exception as exc:  # pragma: no cover — network errors at runtime
        logger.warning(
            "Failed to send developer notification to user %s: %s", user_id, exc
        )


def format_disconnect_message(shard_id: int | None = None) -> str:
    """Return a formatted message for a bot disconnect event."""
    shard_info = f" (shard {shard_id})" if shard_id is not None else ""
    return f"[discord-assistant] Bot disconnected from Discord{shard_info}."


def format_error_message(event: str, exc: BaseException) -> str:
    """Return a formatted message for an unhandled error event."""
    tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    # Keep traceback concise for the DM
    if len(tb) > 1200:
        tb = tb[-1200:]
    return (
        f"[discord-assistant] Unhandled error in event `{event}`:\n"
        f"```\n{tb}\n```"
    )
