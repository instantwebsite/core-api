## Caddy API

We use the Caddy Admin API with `cawdy` to set/mutate/remove domains that the user
sets.


### Calling Points

- When a domain is verified, create the domain -> directory mapping
- When a domains website-id/revision gets updated, update directory mapping
- When a domain is deleted, remove the domain -> directory mapping
