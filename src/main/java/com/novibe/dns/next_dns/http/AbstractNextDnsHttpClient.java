package com.novibe.dns.next_dns.http;

import com.novibe.common.HttpRequestSender;
import com.novibe.common.exception.CredentialsException;
import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.util.Log;

public abstract class AbstractNextDnsHttpClient extends HttpRequestSender {

    protected abstract String path();

    @Override
    protected String apiUrl() {
        return "https://api.nextdns.io/profiles/%s".formatted(dnsProfile.clientId());
    }

    @Override
    protected String authHeaderName() {
        return "X-Api-Key";
    }

    @Override
    protected String authHeaderValue() {
        return dnsProfile.authSecret();
    }

    @Override
    protected final void react401() {
        throw new CredentialsException("Invalid api key");
    }

    @Override
    protected void react403() {
        throw new CredentialsException("Invalid api key!");
    }

    @Override
    protected void react404(DnsHttpError dnsHttpError) {
        if (dnsProfile.clientId() != null) {
            Log.fail("Make sure that values of AUTH_SECRET and CLIENT_ID belongs to same account! Providing a new API Token to AUTH_SECRET may help.");
        }
        throw dnsHttpError;
    }
}
