package com.autocare.server.auth;

public record AuthRegisterRequest(String loginName, String password) {
}