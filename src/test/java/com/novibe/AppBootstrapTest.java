package com.novibe;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class AppBootstrapTest {
    @Test void commonAndNextDnsContextsStartWithoutAnyNetworkCalls() {
        try(var common=new AnnotationConfigApplicationContext("com.novibe.common");
            var profile=new AnnotationConfigApplicationContext()) {
            assertNotNull(common.getBean(HostsOverrideListsLoader.class));
            profile.setParent(common);
            profile.scan("com.novibe.dns.next_dns");
            profile.registerBean(DnsProfile.class,()->new DnsProfile("NEXTDNS","abc123","synthetic-test-only",1,null));
            profile.refresh();
            assertNotNull(profile.getBean(DnsTaskRunner.class));
        }
    }
}
