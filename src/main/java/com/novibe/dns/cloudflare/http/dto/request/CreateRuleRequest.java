package com.novibe.dns.cloudflare.http.dto.request;

import com.google.gson.annotations.SerializedName;
import com.novibe.common.util.Jsonable;
import lombok.Builder;

import java.util.List;

@Builder
public record CreateRuleRequest(String name,
                                String description,
                                String action,
                                List<String> filters,
                                String traffic,
                                int precedence,
                                @SerializedName("rule_settings")
                                RuleSettings ruleSettings,
                                boolean enabled)
        implements Jsonable {

    public record RuleSettings(
            @SerializedName("override_ips")
            List<String> overrideIps) {
    }
}


