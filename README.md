# keycloak-password-reset-cc

[![CI](https://github.com/carsso/keycloak-email-cc/actions/workflows/ci.yml/badge.svg)](https://github.com/carsso/keycloak-email-cc/actions/workflows/ci.yml)

Sends a copy of Keycloak's "forgot password" email to an address held in a user
attribute.

The user still receives their usual mail; a second, strictly identical mail (same
subject, same body, same action link) goes out to the copy address(es).

Only `sendPasswordReset` is affected: email verification, invitations, execute-actions
and friends are not copied.

## Build

```
mvn package
cp target/keycloak-password-reset-cc-1.0.0.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build     # not needed with start-dev
```

Tested end to end on Keycloak 26.7.3 and on the `nightly` image (999.0.0-SNAPSHOT, the
future 27) — the same jar, compiled against 26.7.3, works on both without recompiling.
`FreeMarkerEmailTemplateProvider` is in fact byte-for-byte identical between 26.7.3 and
`main`, and the hook it relies on has existed since 26.0.

Caveat: `emailTemplate` is an internal SPI (Keycloak logs a `KC-SERVICES0047` about it).
Nothing contractually guarantees its stability, so the test suite below should be re-run
on every major version bump.

The provider registers under the id `freemarker` with a higher `order()`: it therefore
replaces the default email provider with no option to set.

## Choosing the attribute

Default: `cc_email`.

Globally, in `keycloak.conf` or on the command line:

```
spi-email-template--freemarker--cc-attribute=copyTo
```

As an environment variable (Keycloak ≥ 26.2, double-dash spelling):

```
KC_SPI_EMAIL_TEMPLATE__FREEMARKER__CC_ATTRIBUTE=copyTo
```

On Keycloak 26.0 / 26.1, the older single-dash spelling applies:
`--spi-email-template-freemarker-cc-attribute=copyTo`.

Per realm, a realm attribute `passwordResetCcAttribute` takes precedence over the global
value:

```
kcadm.sh update realms/myrealm -s 'attributes.passwordResetCcAttribute=boss'
```

## Making the field editable in the admin console

Since Keycloak 24, an attribute must be declared in the *User Profile* to be visible.
Realm settings → User profile → Create attribute, or through the API:

```json
{
  "name": "copyTo",
  "displayName": "Password reset copy address",
  "multivalued": false,
  "permissions": { "view": ["admin"], "edit": ["admin"] },
  "validations": { "email": {} }
}
```

## Details

- Several addresses are supported: a multivalued attribute, or values separated by a
  comma, a semicolon or a space.
- A copy address equal to the user's own email is skipped (no duplicate).
- A failure to send the copy is logged at WARN and does not break the reset flow: the
  user has already received their mail.

## Tests

```
mvn test      # unit tests only
mvn verify    # + integration tests (requires Docker)
```

**Unit** — the provider is exercised against a mocked `EmailSenderProvider`: splitting and
cleaning up addresses, excluding the user's own address, deduplication, resolving the
attribute name and its per-realm override, isolating copy send failures, and checking that
no other email type gets copied.

**Integration** — Testcontainers starts a real Keycloak with the freshly built jar and a
Mailpit; the tests walk the actual "Forgot password?" pages and check what really landed on
the SMTP server. To target another version:

```
mvn verify -Dkeycloak.test.image=quay.io/keycloak/keycloak:26.0.8
```

CI replays this suite on 26.0.8, 26.2.5, 26.7.3 and `nightly`. The `nightly` job is allowed
to fail: it is a moving target, and `emailTemplate` remains an internal SPI.

## Trying it by hand

```
mvn package && docker compose up -d
```

Then create a realm with `resetPasswordAllowed`, an SMTP server pointing at `mailpit:1025`,
a user with the `copyTo` attribute, and trigger "Forgot password?". Both mails show up on
http://localhost:18025.

## License

[MIT](LICENSE)
