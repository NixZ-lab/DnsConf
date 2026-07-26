package com.novibe.dns.next_dns.http.dto.request;

import com.novibe.common.util.Jsonable;
import lombok.Getter;

@Getter
public final class CreateDenyDto implements Jsonable {

    private final String id;
    private final boolean active = true;

    public CreateDenyDto(String id) {
        this.id = id;
    }

}
