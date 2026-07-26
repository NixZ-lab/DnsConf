package com.novibe.dns.cloudflare.http.dto.response;

import com.google.gson.annotations.SerializedName;
import com.novibe.common.util.Jsonable;
import lombok.Data;

import java.util.List;

@Data
public class CloudflareApiResponse<T> implements Jsonable {

    private List<CloudflareApiMessage> errors;
    private List<CloudflareApiMessage> messages;
    private boolean success;
    private T result;

    @SerializedName("result_info")
    private ResultInfo resultInfo;

}
