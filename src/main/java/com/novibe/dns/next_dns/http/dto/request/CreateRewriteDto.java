package com.novibe.dns.next_dns.http.dto.request;

import com.novibe.common.util.Jsonable;
import lombok.EqualsAndHashCode;

public record CreateRewriteDto(String name, @EqualsAndHashCode.Exclude String content) implements Jsonable {

}
