package com.novibe.dns.cloudflare.http.dto.response;

import com.google.gson.annotations.SerializedName;
import com.novibe.common.util.Jsonable;

public record CloudflareApiMessage(int code, String message,
                                   @SerializedName("documentation_url")
                                   String documentationUrl,
                                   Source source)
        implements Jsonable {

}
