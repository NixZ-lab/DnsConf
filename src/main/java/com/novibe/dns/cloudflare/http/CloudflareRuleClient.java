package com.novibe.dns.cloudflare.http;

import com.novibe.dns.cloudflare.http.dto.request.CreateRuleRequest;
import com.novibe.dns.cloudflare.http.dto.response.rule.MultiRuleApiResponse;
import com.novibe.dns.cloudflare.http.dto.response.rule.SingleRuleApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloudflareRuleClient {

    private static final String path = "/rules";

    private final RequestCloudflare requestCloudflare;

    @SneakyThrows
    public SingleRuleApiResponse createBlockingRule(CreateRuleRequest rule) {
        return requestCloudflare.post(path, rule, SingleRuleApiResponse.class);
    }

    @SneakyThrows
    public SingleRuleApiResponse removeRuleById(String id) {
        return requestCloudflare.delete(path + "/" + id, SingleRuleApiResponse.class);
    }

    @SneakyThrows
    public MultiRuleApiResponse getRules() {
        return requestCloudflare.get(path, MultiRuleApiResponse.class);
    }

}
