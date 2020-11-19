## What to store where?

- HTML => disk
- Images => disk
- Incoming JSON => disk
- Generated Hiccup => throw away
- User accounts => crux / Stripe
- Relationships => crux

## Application Architecture

- core.clj pulls everything together
- db.clj has everything that touches the DB + "templates" for entities
- figcup.clj responsible for turning Figma JSON structure into hiccup
