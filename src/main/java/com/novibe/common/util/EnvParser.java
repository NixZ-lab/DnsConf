package com.novibe.common.util;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.EnvironmentVariables;
import com.novibe.common.exception.UserInputException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.isNull;

public class EnvParser {

    public static List<String> parse(String envValue) {
        if (isNull(envValue)) return List.of();
        envValue = envValue.strip();
        if (envValue.isEmpty()) return List.of();
        return Arrays.asList(envValue.strip().split(","));
    }

    public static List<DnsProfile> parseProfiles() {
        List<String> dnsList = parse(EnvironmentVariables.DNS);
        List<String> clientIdList = parse(EnvironmentVariables.CLIENT_ID);
        List<String> secretList = parse(EnvironmentVariables.AUTH_SECRET);

        if (clientIdList.size() != secretList.size()) {
            throw UserInputException.noStackTrace("CLIENT_ID values amount and AUTH_SECRET values amount must be equal, but were %s and %s"
                    .formatted(clientIdList.size(), secretList.size()));
        }
        int profilesAmount = clientIdList.size();

        if (dnsList.size() == 1) {
            String[] dnsFiller = new String[profilesAmount];
            Arrays.fill(dnsFiller, dnsList.getFirst());
            dnsList = Arrays.asList(dnsFiller);
        } else if (dnsList.size() != profilesAmount) {
            throw UserInputException.noStackTrace("DNS values amount must be equal to CLIENT_ID values amount or contain exactly one provider");
        }
        ArrayList<DnsProfile> dnsProfiles = new ArrayList<>();
        for (int i = 0; i < profilesAmount; i++) {
            DnsProfile dnsProfile = DnsProfile.builder()
                    .dnsProvider(dnsList.get(i).toUpperCase())
                    .clientId(clientIdList.get(i))
                    .authSecret(secretList.get(i))
                    .number(i + 1)
                    .build();
            dnsProfiles.add(dnsProfile);
        }
        return dnsProfiles;
    }

}
