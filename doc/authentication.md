## User Authentication

We're lazy. Let's just do email verification and then you have an account. Easy peazy.

Basically, we got three different tokens we care about.

First the API Token. It only allows (currently) creating/updating websites and upload
images and vectors. This is valid forever, no restriction. The user should be
able to manually rotate this whenever they feel like. If it's rotated, the old
one is invalid and should not be possible to use for authentication anymore.

The API token is only supposed to be used together with the Figma Plugin, as
we don't want to require people to having to enter a new token more than once.

For now, we do an access-token that works the same way as the API token basically. It doesn't expire, so we do a lookup to the DB each time. We are not aiming for scale here; we're aiming for simplicity.

;; Secondly is the access-token. This is a JWT token containing the time it expires
;; and the email of the user. This token is valid for just one hour. The purpose
;; of this token is to be used for authenticating all the rest of the API calls,
;; particularly for the frontend to talk with the backend. Once expired, it's
;; invalid and should be able to use it.
;; 
;; Third and last is the refresh-token.



The following is a demonstration with curl with EDN, but you could do it with
JSON as well.

$ curl -X POST http://localhost:8080/api/login/email/v@instantwebsite.app

Take the received code, put it at the end

$ curl -X POST http://localhost:8080/api/login/email/v@instantwebsite.app/code/193c396449a292fb1f841d25d7ba33063acc373881bd028d73c6d740cdea0a32
