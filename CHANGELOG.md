# Changelog

## 1.0.0 - 2026-11-25
First public release. Everything from 0.1.0-0.2.1, plus:

- The menu now says who paid into a bounty: "1 player", "3 players", "the server" or "1 player + the server" (new `<placed_by>` placeholder and `placed-by.*` texts). Old configs are upgraded automatically.
- Player heads in the menu and dropped heads no longer show the extra grey profile line.

## 0.2.1 (2026-11-25)
- Fixed: servers that already had a config.yml didn't show the tax/expiry lines in the menu book and the amount dialog. They now come from new keys `menu.info-extra` and `dialog.body-extra`, which are added automatically.

## 0.2.0 (2026-11-25)
- **Anti-abuse:** no payout when killer and target share an IP (bounty stays open), contributors can't claim (configurable), placing cooldown (`place-cooldown-seconds`, bypass `wintermark.bounty.bypass.cooldown`), tax on top of each bounty (`tax-percent`, money sink).
- **Expiry with refund:** each contributor's share expires `expiry.days` after they last added to it and is refunded to them; server shares just vanish.
- **Top hunters:** `/bounty top [claims|earned]` from the claims history.
- **Auto bounties:** `auto-bounty.kills` player kills within `auto-bounty.minutes` puts a server bounty of `auto-bounty.amount` on the killer (stacks per extra kill; killing wanted players doesn't count; same-IP kills don't count).
- Menu info and the amount dialog show the tax and expiry.

## 0.1.1 (2026-11-25)
- Fixed: `/bounty check` without a name threw an error; it now shows your own bounty.

## 0.1.0 (2026-11-25)
- `/bounty set <player> <amount>` places a bounty with your own money; bounties stack (every contributor is tracked for later refunds).
- `/bounty` menu: player heads sorted by bounty size, 45 per page, click a head to add money through a native text-input dialog.
- Killing a wanted player pays the whole bounty to the killer, drops the target's head (lore: bounty + killer) and broadcasts the claim.
- `/bounty check [player]`, join reminder, notification when someone puts money on you.
- Admin: `/bounty give <player> <amount>` (server-paid), `/bounty remove <player> [norefund]` (refunds contributors), `/bounty forceclaim <target> <killer>`, `/bounty reload`.
- Works with Wintermark Economy (own API) or any Vault economy. SQLite storage (`bounties.db`), claims recorded for the coming top-hunters list.
