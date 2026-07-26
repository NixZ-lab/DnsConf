package com.novibe.common.data_sources;

import com.novibe.common.config.EnvironmentVariables;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.EnvParser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ExcludeRedirectSettingsLoader {

    public List<String> loadIgnoredDomains() {
        return Optional.ofNullable(EnvParser.parse(EnvironmentVariables.EXCLUDE_REDIRECT))
                .stream()
                .flatMap(List::stream)
                .map(String::trim)
                .map(String::toLowerCase)
                .map(DataParser::removeWWW)
                .distinct()
                .toList();
    }
}
