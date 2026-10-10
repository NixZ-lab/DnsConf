package com.novibe.common.service;

import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExcludeRedirectCheckService {

    private final List<DomainExclusion> ignoringList;

    public ExcludeRedirectCheckService(ExcludeRedirectSettingsLoader excludeRedirectSettingsLoader) {
        ignoringList = excludeRedirectSettingsLoader.loadIgnoredDomains().stream().map(DomainExclusion::parse).toList();
    }

    public boolean shouldExclude(String domain) {
        for (DomainExclusion ignored : ignoringList) {
            if (ignored.matches(domain)) {
                return true;
            }
        }
        return false;
    }

}
