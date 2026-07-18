# FreeMarket

FreeMarket is a Forge 1.20.1 client/server marketplace mod for Mohist servers. Players use a dedicated retro terminal to buy, sell, bid, comment, negotiate, follow listings, and manage their market history without operator permissions.

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- Java 17
- Mohist 1.20.1
- Vault and a Vault-compatible economy plugin
- ModernNotification 1.0.0 or newer on both sides is optional

## Features

- Fixed-price listings and timed auctions with complete ItemStack NBT preservation
- cards, product details, search filters, sorting, likes, comments, reviews, and persistent navigation
- Saved searches with new-listing alerts, price-drop alerts, purchase/listing/view history, seller blocking, and listing reports
- Editable operator-managed tags and fixed or percentage marketplace fees
- Persistent bell notifications with read/delete controls
- Optional ModernNotification integration using Top notifications for important transactions and Left notifications for normal activity
- Durable draft recovery, pending delivery recovery, transaction journaling, and operator-assisted payment recovery
- Japanese and English language resources

## Player usage

Place and use the Free Market Terminal to open the marketplace. `/freemarket open` is intentionally operator-only; normal players must use a terminal block.

Items selected for a listing draft are held by the server. Cancelling or abandoning an unfinished draft returns the exact stack. A completed purchase is inserted only when the entire stack fits; otherwise it is dropped at the recipient's position, and failed delivery attempts remain queued.

Completed, cancelled, removed, and expired listing records are retained for up to 30 days and up to 2,000 terminal records. Pending deliveries, unresolved payments, payment reviews, and listings with open reports are never removed by retention maintenance.

## Operator commands

- `/freemarket open` opens the normal market UI for the executing operator.
- `/freemarket admin` opens tag, fee, report, and moderation controls.
- `/freemarket resolve list [page]` lists payments awaiting manual review.
- `/freemarket resolve info <transaction>` shows journal and listing details.
- `/freemarket resolve <transaction> complete` completes delivery after the external economy state has been verified.
- `/freemarket resolve <transaction> return` returns the escrowed item after the external economy state has been verified.

The resolve list, info, complete, and return commands can also be run from the server console. Always inspect the Vault economy state before choosing a manual resolution.

## Build

Run `gradlew build`. The distributable JAR is created under `build/libs` and includes the FreeMarket license.

## License

CC BY-NC 4.0 (non-commercial use only). See `LICENSE`.
