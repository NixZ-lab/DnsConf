package com.novibe.common.base_structures;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Accessors(fluent = true)
@Getter
public final class HostsLine {

    @EqualsAndHashCode.Include
    private final String domain;

    private final String ip;

    public HostsLine(String ip, String domain) {
        this.ip = ip;
        this.domain = domain;
    }

    public boolean hasDomainOnly() {
        return nonNull(domain) && isNull(ip);
    }

    public boolean hasIpAndDomain() {
        return nonNull(domain) && nonNull(ip);
    }

    public static HostsLine ipOnly(String ip) {
        return new HostsLine(ip, null);
    }

    public static HostsLine domainOnly(String domain) {
        return new HostsLine(null, domain);
    }

}