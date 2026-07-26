package com.novibe.common.util;

import com.google.gson.Gson;

public interface Jsonable {

    Gson mapper = new Gson();

    default String toJson() {
        return mapper.toJson(this);
    }

}
