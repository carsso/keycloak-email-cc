# keycloak-password-reset-cc

Envoie une copie de l'email « mot de passe oublié » de Keycloak à une adresse stockée
dans un attribut de l'utilisateur.

L'utilisateur reçoit son mail habituel ; un second mail, strictement identique (même
sujet, même corps, même lien d'action), part vers la ou les adresses de copie.

Seul `sendPasswordReset` est concerné : vérification d'email, invitations, execute-actions
et compagnie ne sont pas copiés.

## Build

```
mvn package
cp target/keycloak-password-reset-cc-1.0.0.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build     # inutile en start-dev
```

Testé de bout en bout sur Keycloak 26.7.3 et sur l'image `nightly` (999.0.0-SNAPSHOT,
future 27) — le même jar, compilé contre 26.7.3, fonctionne sur les deux sans recompilation.
`FreeMarkerEmailTemplateProvider` est d'ailleurs identique octet pour octet entre 26.7.3 et
`main`, et le point d'accroche utilisé existe depuis 26.0.

Réserve : `emailTemplate` est un SPI interne (Keycloak logue un `KC-SERVICES0047` à ce sujet).
Rien ne garantit contractuellement sa stabilité ; il faut donc refaire tourner le banc d'essai
ci-dessous à chaque montée de version majeure.

Le provider s'enregistre sous l'id `freemarker` avec un `order()` supérieur : il remplace
donc le provider d'emails par défaut sans aucune option de sélection à poser.

## Choisir l'attribut

Par défaut : `cc_email`.

Globalement, dans `keycloak.conf` ou en ligne de commande :

```
spi-email-template--freemarker--cc-attribute=copyTo
```

En variable d'environnement (Keycloak ≥ 26.2, format à double tiret) :

```
KC_SPI_EMAIL_TEMPLATE__FREEMARKER__CC_ATTRIBUTE=copyTo
```

Sur Keycloak 26.0 / 26.1, l'ancien format à simple tiret s'applique :
`--spi-email-template-freemarker-cc-attribute=copyTo`.

Par realm, un attribut de realm `passwordResetCcAttribute` prend le pas sur la valeur
globale :

```
kcadm.sh update realms/monrealm -s 'attributes.passwordResetCcAttribute=boss'
```

## Rendre le champ éditable dans la console admin

Depuis Keycloak 24, un attribut doit être déclaré dans le *User Profile* pour être
visible. Realm settings → User profile → Create attribute, ou via l'API :

```json
{
  "name": "copyTo",
  "displayName": "Adresse en copie du reset de mot de passe",
  "multivalued": false,
  "permissions": { "view": ["admin"], "edit": ["admin"] },
  "validations": { "email": {} }
}
```

## Détails

- Plusieurs adresses possibles : attribut multivalué, ou valeurs séparées par une
  virgule, un point-virgule ou un espace.
- Une adresse de copie égale à l'email de l'utilisateur est ignorée (pas de doublon).
- Un échec d'envoi de la copie est journalisé en WARN et ne casse pas le flux de reset :
  l'utilisateur a déjà reçu son mail.

## Essayer en local

```
mvn package && docker compose up -d
```

Puis créer un realm avec `resetPasswordAllowed`, un SMTP pointant sur `mailpit:1025`,
un utilisateur avec l'attribut `copyTo`, et déclencher « Mot de passe oublié ? ».
Les deux mails apparaissent sur http://localhost:18025.
