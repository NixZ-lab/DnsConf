package com.novibe.common;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.data_sources.HostsBlockListsLoader;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

@Setter(onMethod_ = @Autowired)
public abstract class DnsTaskRunner {

    protected DnsProfile dnsProfile;
    protected HostsBlockListsLoader blockListsLoader;
    protected HostsOverrideListsLoader overrideListsLoader;

    protected abstract void greetingMessage();

    protected abstract void process();

    protected abstract void finishMessage();

    public final void run() {
        greetingMessage();
        process();
        finishMessage();
    }
}
