How can we allow custom domains?

The idea is to have a few components in place to allow this.

Conceptually, a domain maps to a website in instantwebsite.app. The flow for
setting it up would be something like:

User has to setup a A record from their domain to $INSTANT-WEBSITE-IP

Then add domain to instantwebsite.app using the same name as they wanna use.

Then attach a website to the domain.

User could chose to always use latest version of website or stay with the current
one, requiring a manual updating step to make it visible under the domain.

## Architecture

We're adding a entity that is `domain`, responsible for mapping a hostname
to a particular `website`.

```
{:domain/id (str "d" (random-hex 16)
 :domain/hostname ""
 :domain/website_id nil
 :domain/auto-update? false}
```

### Backend

Responsible for CRUD operations to the main db. Also responds to the incoming
requests that will have a different hostname set. If it's another hostname,
it needs to check for the website-id that corresponds with that domain.

### Plugin

If there is a website-id set, the plugin will try to read the domain that
is currently jkk
The plugin will 

TODO: Should make sure $INSTANT-WEBSITE-IP is a permanent one, or could we use
domains instead?

TODO: Make sure backend reads :domain/auto-update? linked to current website
when it changes

### Staging

Staging is easy to setup! You need two A records. One for `stage.example.com`
and one for `example.com`. Both points to $INSTANT-WEBSITE-IP. Then in
instantwebsite.app you create two domains as well. The `stage.example.com` one
you have auto-update? set to true and `example.com` you set auto-update? to true.
Connect them both with the same initial :website/id

Now your workflow can be something like:

- Make changes to your website, update from the Figma Plugin, visit/send a
  link to `stage.example.com` whenever you want to preview it. `example.com` will
  always stay the same.
- Once you/others have confirmed that `state.example.com` is ok and fully checked,
  you go to the `state.example.com` domain in instantwebsite.app and change the
  website version to the latest one
you want
