package com.novibe.common.exception;

import lombok.experimental.StandardException;

@StandardException
public class UserInputException extends RuntimeException {

    private UserInputException(String message) {
        super(message, null, false, false);
    }

    public static UserInputException noStackTrace(String message) {
        return new UserInputException(message);
    }

}
