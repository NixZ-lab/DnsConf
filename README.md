🌐 Languages: [**English**](README.md) | [**Русский**](README.ru.md)

[![Last Build](../../actions/workflows/github_action.yml/badge.svg?branch=main)](../../actions/workflows/github_action.yml)

# DNS Block & Redirect Configurator

Configure redirect and blocking rules for Cloudflare and NextDNS accounts.

**Ready to run via GitHub Actions.** [Video guide](https://www.youtube.com/watch?v=vbAXM_xAL5I)

## Comparison of free plans: NextDNS vs Cloudflare

|                         | NextDNS                                                         | Cloudflare                                                                                                           |
|-------------------------|-----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| **DNS query limit**     | 300,000 per month                                               | 100,000 per day                                                                                                      |
| **IPv4 restrictions**   | DNS queries are limited to a single IP address (can be changed) | DNS queries are strictly limited to a single IP address (assigned automatically by Cloudflare and cannot be changed) |
| **DoH / DoT / IPv6**    | Unlimited                                                       | Unlimited                                                                                                            |
| **Setup API limits**    | 60 requests per minute                                          | Unlimited                                                                                                            |
| **General limitations** | None                                                            | Infrastructure is blocked by Roskomnadzor, causing availability issues in Russia                                     |
| **Advantages**          | Built-in ad and tracker blocking options                        | More reliable and faster infrastructure                                                                              |

If you are located in Russia, **NextDNS** is the only viable option of the two because of Roskomnadzor restrictions.

In other countries, **Cloudflare** offers more generous limits on the free plan. Tracker and ad blocking can also be enabled by providing a domain blocklist in `BLOCK`, for example: https://small.oisd.nl/domainswild2

## Easy setup

Use the configurator at https://dns-conf-ui.vercel.app in **Quick** mode. It automatically configures the DNS profile and performs the required GitHub setup steps.

Sign in with GitHub and provide **CLIENT_ID** and **AUTH_SECRET**. See [Setup credentials](#setup-credentials) for details.

## Standard setup

- [Setup credentials](#setup-credentials)
- [Setup profile](#setup-profile)
- [Setup data sources](#setup-data-sources)
- [Setup excluded redirects (optional)](#setup-excluded-redirects-optional)
- [Set up a DNS donor](#set-up-a-dns-donor)
- [Set up multiple profiles](#set-up-multiple-profiles)
- [GitHub Actions](#github-actions-setup)

---

## Setup credentials

### NextDNS credentials

1. Generate an **API key** at https://my.nextdns.io/account and save it as the `AUTH_SECRET` environment secret.
2. Open https://my.nextdns.io, copy the ID from the **Endpoints** section, and save it as the `CLIENT_ID` environment secret.

### Cloudflare credentials

1. Sign up for **Cloudflare**, open the _Zero Trust_ tab, and create an account.
   - The free plan has suitable limits for this use case.
   - Skip the payment method by selecting _Cancel and exit_ in the top-right corner.
   - Return to the _Zero Trust_ tab.
2. Create a **Cloudflare API token** at https://dash.cloudflare.com/profile/api-tokens with these permissions:

       Account.Zero Trust : Edit
       Account.Account Firewall Access Rules : Edit

   Save the token as the `AUTH_SECRET` environment secret.
3. Get the **Account ID** from https://dash.cloudflare.com/?to=/:account/workers and save it as the `CLIENT_ID` environment secret.

---

## Setup profile

Set the `DNS` environment variable to the DNS provider name: **Cloudflare** or **NextDNS**.

---

## Setup data sources

Each data source must be a link to a hosts file, for example:

https://raw.githubusercontent.com/Internet-Helper/GeoHideDNS/refs/heads/main/hosts/hosts

Multiple sources can be separated by commas:

`https://first.com/hosts,https://second.com/hosts`

### 1. Set up redirects

Set the source URLs in the `REDIRECT` environment variable.

The script ignores redirects to `0.0.0.0` and `127.0.0.1`. For example, from:

    0.0.0.0 domain.to.block
    1.2.3.4 domain.to.redirect
    127.0.0.1 another.to.block

only this entry is used for redirect processing:

    1.2.3.4 domain.to.redirect

Redirect priority follows the source order. If a domain appears more than once, the first matching IP address is used.

### 2. Set up a blocklist

Set the source URLs in the `BLOCK` environment variable.

The script keeps entries redirected to `0.0.0.0`, `127.0.0.1`, or `::1`, as well as lines containing only a domain. For example, from:

    1.2.3.4 domain.to.redirect
    0.0.0.0 domain.to.block
    127.0.0.1 another.to.block
    ::1 ipv6.to.block
    no-ip.just.domain

these domains are used for block processing:

    domain.to.block
    another.to.block
    ipv6.to.block
    no-ip.just.domain

- For **Cloudflare**, the same source can be used for both `BLOCK` and `REDIRECT`.
- For **NextDNS**, a practical option is to set only `REDIRECT` and select blocklists manually on the _Privacy_ tab.

---

## Setup excluded redirects (optional)

Add domains to the `EXCLUDE_REDIRECT` environment variable, separated by commas without spaces, for example:

`instagram.com,twitch.com`

These domains and their subdomains:

- are removed from existing redirect rules;
- are not added to new redirect rules.

---

## Set up a DNS donor

If the IP addresses supplied by a hosts source become outdated, the configurator can resolve fresh addresses through a donor DNS server before applying redirect rules.

Set the optional `DONOR_DNS` environment variable to either:

- an **IPv4 DNS server**, for example `111.88.96.50`;
- a **DNS-over-HTTPS endpoint**, for example `https://xbox-dns.ru/dns-query`.

For example, if a hosts file contains:

    1.2.3.4 domain-1.to.redirect
    1.2.3.4 domain-2.to.redirect
    1.2.3.4 domain-3.to.redirect

`DONOR_DNS` can resolve fresh IP addresses for those domains, and the updated addresses are then used when redirect rules are uploaded.

Leave `DONOR_DNS` empty if you do not want to use this feature.

---

## Set up multiple profiles

### Restrictions

All profiles receive the same `BLOCK`, `REDIRECT`, and `EXCLUDE_REDIRECT` settings.

### Multiple profiles for one provider

Add profile values to the corresponding environment secrets and variables, separated by commas without spaces. For example, for two NextDNS profiles:

- `AUTH_SECRET`: `secret_NextDns_1,secret_NextDns_2`
- `CLIENT_ID`: `client_id_NextDns_1,client_id_NextDns_2`

### Multiple profiles for different providers

Also list the provider for each profile in the `DNS` environment variable. For example:

- `DNS`: `NEXTDNS,CLOUDFLARE,NEXTDNS`
- `AUTH_SECRET`: `secret_NextDns_1,secret_Cloudflare_1,secret_NextDns_2`
- `CLIENT_ID`: `client_id_NextDns_1,client_id_Cloudflare_1,client_id_NextDns_2`

### Different DONOR_DNS values per profile

If `DONOR_DNS` contains one value, that donor is used for all profiles.

You can also provide one value per profile, separated by commas without spaces. Use `-` for profiles where donor DNS should be disabled. For example:

`-,111.88.96.50,-,111.88.96.50`

---

## Script behavior

### Cloudflare

Previously generated data is removed. The script identifies old data by:

- list name prefixes: **_Blocked websites by script_** and **_Override websites by script_**;
- rule name prefix: **_Rules set by script_**;
- a different **_Session id_**, stored in the description field.

After the old data is removed, new lists and rules are generated and applied.

To clear Cloudflare block or redirect settings, run the script without the corresponding source. For example, leaving `BLOCK` empty removes the previously generated block lists and rules.

### NextDNS

For `REDIRECT`:

- an existing domain is updated if its redirect IP changes;
- new domains are added;
- other redirect settings remain unchanged.

For `BLOCK`:

- new domains are added;
- other block settings remain unchanged.

Previously generated data is removed only when both `BLOCK` and `REDIRECT` sources are empty.

---

## GitHub Actions setup

### Step-by-step video guide

[REDIRECT for NextDNS](https://www.youtube.com/watch?v=vbAXM_xAL5I)

### Steps

1. Fork the repository.
2. Open _Settings_ → _Environments_.
3. Create a new environment named `DNS`.
4. Add `AUTH_SECRET` and `CLIENT_ID` to **Environment secrets**.
5. Add `DNS`, `REDIRECT`, `BLOCK`, `EXCLUDE_REDIRECT`, and optionally `DONOR_DNS` to **Environment variables**.

The action runs daily at **01:30 UTC**. To change the schedule, edit the cron expression in `.github/workflows/github_action.yml`.

To run it manually, open the _Actions_ tab and select **DNS Block&Redirect Configurer cron task**, then choose **Run workflow**.
